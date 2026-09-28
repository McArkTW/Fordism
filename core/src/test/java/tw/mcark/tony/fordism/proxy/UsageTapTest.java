package tw.mcark.tony.fordism.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tw.mcark.tony.fordism.workspace.TokenUsage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;

/**
 * Every format the proxy reads, each fed one byte at a time — the worst split the network can make,
 * including through the middle of a multi-byte character and a binary frame header.
 */
class UsageTapTest {

    private static TokenUsage read(String path, String contentType, byte[] body) {
        UsageTap tap = UsageTap.open(path, contentType).orElseThrow();
        for (int i = 0; i < body.length; i++) {
            tap.accept(body, i, 1);
        }
        return tap.finish().orElseThrow(() -> new AssertionError("no usage read from " + path));
    }

    private static void assertUsage(TokenUsage usage, long input, long cacheWrite, long cacheRead, long output) {
        assertEquals(input, usage.inputTokens(), "input");
        assertEquals(cacheWrite, usage.cacheWriteTokens(), "cache write");
        assertEquals(cacheRead, usage.cacheReadTokens(), "cache read");
        assertEquals(output, usage.outputTokens(), "output");
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void anthropicStreamTakesInputFromStartAndOutputFromDelta() {
        String sse = """
                event: message_start
                data: {"type":"message_start","message":{"id":"m1","usage":{"input_tokens":7,"cache_creation_input_tokens":27569,"cache_read_input_tokens":120,"output_tokens":1}}}

                event: content_block_delta
                data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"héllo — ✓"}}

                event: ping
                data: not json at all

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":100}}

                event: message_stop
                data: {"type":"message_stop"}

                """;
        assertUsage(read("/anthropic/v1/messages", "text/event-stream; charset=utf-8", utf8(sse)), 7, 27569, 120, 100);
    }

    @Test
    void anthropicStreamWithCrlfSeparators() {
        String sse = "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":3,\"output_tokens\":1}}}\r\n\r\n"
                + "data: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":9}}\r\n\r\n";
        assertUsage(read("/v1/messages", "text/event-stream", utf8(sse)), 3, 0, 0, 9);
    }

    @Test
    void anthropicWholeBody() {
        String body = "{\"type\":\"message\",\"usage\":{\"input_tokens\":11,\"cache_creation_input_tokens\":5,"
                + "\"cache_read_input_tokens\":6,\"output_tokens\":12}}";
        assertUsage(read("/v1/messages", "application/json", utf8(body)), 11, 5, 6, 12);
    }

    @Test
    void openAiChatStreamSplitsCachedTokensOutOfPrompt() {
        String sse = """
                data: {"choices":[{"delta":{"content":"hi"}}],"usage":null}

                data: {"choices":[],"usage":{"prompt_tokens":1000,"completion_tokens":50,"prompt_tokens_details":{"cached_tokens":800}}}

                data: [DONE]

                """;
        assertUsage(read("/v1/chat/completions", "text/event-stream", utf8(sse)), 200, 0, 800, 50);
    }

    @Test
    void openAiResponsesStreamReadsResponseCompleted() {
        String sse = """
                event: response.created
                data: {"type":"response.created","response":{"usage":null}}

                event: response.completed
                data: {"type":"response.completed","response":{"usage":{"input_tokens":500,"input_tokens_details":{"cached_tokens":100},"output_tokens":40}}}

                """;
        assertUsage(read("/v1/responses", "text/event-stream", utf8(sse)), 400, 0, 100, 40);
    }

    @Test
    void bedrockConverseWholeBody() {
        String body = "{\"output\":{},\"usage\":{\"inputTokens\":21,\"outputTokens\":22,"
                + "\"cacheReadInputTokens\":23,\"cacheWriteInputTokens\":24,\"totalTokens\":90}}";
        assertUsage(read("/model/global.anthropic.claude-sonnet-5/converse", "application/json", utf8(body)), 21, 24, 23, 22);
    }

    @Test
    void bedrockConverseEventStreamReadsTheMetadataFrame() {
        byte[] stream = concat(
                frame("contentBlockDelta", "{\"contentBlockIndex\":0,\"delta\":{\"text\":\"ok\"}}"),
                frame("metadata", "{\"usage\":{\"inputTokens\":31,\"outputTokens\":32,\"cacheReadInputTokens\":33,"
                        + "\"cacheWriteInputTokens\":34},\"metrics\":{\"latencyMs\":5}}"));
        assertUsage(read("/model/global.anthropic.claude-sonnet-5/converse-stream",
                "application/vnd.amazon.eventstream", stream), 31, 34, 33, 32);
    }

    @Test
    void bedrockInvokeEventStreamUnwrapsBase64AnthropicEvents() {
        byte[] stream = concat(
                frame("chunk", chunk("{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":41,"
                        + "\"cache_creation_input_tokens\":42,\"cache_read_input_tokens\":43,\"output_tokens\":1}}}")),
                frame("chunk", chunk("{\"type\":\"message_delta\",\"usage\":{\"output_tokens\":44}}")));
        assertUsage(read("/model/global.anthropic.claude-sonnet-5/invoke-with-response-stream",
                "application/vnd.amazon.eventstream", stream), 41, 42, 43, 44);
    }

    @Test
    void geminiSseTakesTheLastRunningTotal() {
        String sse = """
                data: {"candidates":[],"usageMetadata":{"promptTokenCount":90,"candidatesTokenCount":3}}

                data: {"candidates":[],"usageMetadata":{"promptTokenCount":100,"cachedContentTokenCount":60,"candidatesTokenCount":10,"thoughtsTokenCount":5}}

                """;
        assertUsage(read("/v1beta/models/gemini-2.5-pro:streamGenerateContent", "text/event-stream", utf8(sse)), 40, 0, 60, 15);
    }

    @Test
    void geminiStreamedJsonArrayWithEncodedColon() {
        String body = "[{\"usageMetadata\":{\"promptTokenCount\":10,\"candidatesTokenCount\":1}},"
                + "{\"usageMetadata\":{\"promptTokenCount\":10,\"candidatesTokenCount\":7}}]";
        assertUsage(read("/v1beta/models/gemini-2.5-pro%3AstreamGenerateContent", "application/json", utf8(body)), 10, 0, 0, 7);
    }

    @Test
    void callsThatBillNothingOpenNoTap() {
        assertTrue(UsageTap.open("/inference-profiles", "application/json").isEmpty());
        assertTrue(UsageTap.open("/v1/models", "application/json").isEmpty());
        assertTrue(UsageTap.open("/v1/messages/count_tokens", "application/json").isEmpty());
    }

    @Test
    void anErrorBodyYieldsNoUsageAndNoException() {
        UsageTap tap = UsageTap.open("/v1/messages", "application/json").orElseThrow();
        byte[] body = utf8("{\"type\":\"error\",\"error\":{\"message\":\"overloaded\"}}");
        tap.accept(body, 0, body.length);
        assertEquals(Optional.empty(), tap.finish());
    }

    @Test
    void openAiChatStreamGetsIncludeUsageOnlyWhenMissing() {
        String asked = new String(UsageProxy.askForStreamUsage("/v1/chat/completions",
                utf8("{\"model\":\"m\",\"stream\":true,\"messages\":[{\"content\":\"<a&b>\"}]}")), StandardCharsets.UTF_8);
        assertTrue(asked.contains("\"stream_options\":{\"include_usage\":true}"), asked);
        assertTrue(asked.contains("<a&b>"), "content must not be HTML-escaped: " + asked);

        byte[] notStreaming = utf8("{\"model\":\"m\",\"stream\":false}");
        assertEquals(notStreaming, UsageProxy.askForStreamUsage("/v1/chat/completions", notStreaming));
        byte[] alreadyAsked = utf8("{\"stream\":true,\"stream_options\":{\"include_usage\":false}}");
        assertEquals(alreadyAsked, UsageProxy.askForStreamUsage("/v1/chat/completions", alreadyAsked));
        byte[] anthropic = utf8("{\"stream\":true}");
        assertEquals(anthropic, UsageProxy.askForStreamUsage("/v1/messages", anthropic));
    }

    // --- AWS event-stream frames, built the way Bedrock sends them ---

    private static String chunk(String json) {
        return "{\"bytes\":\"" + Base64.getEncoder().encodeToString(utf8(json)) + "\",\"p\":\"abc\"}";
    }

    static byte[] frame(String eventType, String payloadJson) {
        byte[] headers = concat(header(":event-type", eventType), header(":content-type", "application/json"),
                header(":message-type", "event"));
        byte[] payload = utf8(payloadJson);
        int total = 12 + headers.length + payload.length + 4;
        ByteBuffer prelude = ByteBuffer.allocate(8).putInt(total).putInt(headers.length);
        CRC32 preludeCrc = new CRC32();
        preludeCrc.update(prelude.array());
        ByteBuffer frame = ByteBuffer.allocate(total);
        frame.put(prelude.array()).putInt((int) preludeCrc.getValue()).put(headers).put(payload);
        CRC32 messageCrc = new CRC32();
        messageCrc.update(frame.array(), 0, total - 4);
        frame.putInt((int) messageCrc.getValue());
        return frame.array();
    }

    private static byte[] header(String name, String value) {
        byte[] n = utf8(name);
        byte[] v = utf8(value);
        return ByteBuffer.allocate(1 + n.length + 1 + 2 + v.length)
                .put((byte) n.length).put(n).put((byte) 7).putShort((short) v.length).put(v).array();
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
