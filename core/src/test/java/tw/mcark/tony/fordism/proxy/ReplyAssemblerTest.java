package tw.mcark.tony.fordism.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** Each format's streamed reply, fed one byte at a time, comes back as that format's whole reply. */
class ReplyAssemblerTest {

    private static JsonObject rebuild(String path, String contentType, byte[] body) {
        UsageTap tap = UsageTap.open(path, contentType, true).orElseThrow();
        for (int i = 0; i < body.length; i++) {
            tap.accept(body, i, 1);
        }
        tap.finish();
        return tap.reply().orElseThrow(() -> new AssertionError("no reply rebuilt for " + path));
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void anthropicTextThinkingAndToolInputFromPieces() {
        String sse = """
                data: {"type":"message_start","message":{"id":"m1","role":"assistant","model":"s5","content":[],"usage":{"input_tokens":3,"output_tokens":1}}}

                data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}

                data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"let me "}}

                data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"see"}}

                data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig"}}

                data: {"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}

                data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"héllo "}}

                data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"✓"}}

                data: {"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"t1","name":"write","input":{}}}

                data: {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\\"path\\": \\"result/"}}

                data: {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"result.json\\"}"}}

                data: {"type":"content_block_stop","index":2}

                data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":42}}

                data: {"type":"message_stop"}

                """;
        JsonObject reply = rebuild("/anthropic/v1/messages", "text/event-stream", utf8(sse));
        assertEquals("tool_use", reply.get("stop_reason").getAsString());
        assertEquals(42, reply.getAsJsonObject("usage").get("output_tokens").getAsInt());
        assertEquals("let me see", reply.getAsJsonArray("content").get(0).getAsJsonObject().get("thinking").getAsString());
        assertEquals("sig", reply.getAsJsonArray("content").get(0).getAsJsonObject().get("signature").getAsString());
        assertEquals("héllo ✓", reply.getAsJsonArray("content").get(1).getAsJsonObject().get("text").getAsString());
        assertEquals("result/result.json", reply.getAsJsonArray("content").get(2).getAsJsonObject()
                .getAsJsonObject("input").get("path").getAsString());
    }

    @Test
    void bedrockInvokeStreamRebuildsTheAnthropicMessage() {
        byte[] stream = UsageTapTest.concat(
                UsageTapTest.frame("chunk", chunk("{\"type\":\"message_start\",\"message\":{\"role\":\"assistant\",\"content\":[],\"usage\":{\"input_tokens\":1}}}")),
                UsageTapTest.frame("chunk", chunk("{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}")),
                UsageTapTest.frame("chunk", chunk("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"ok\"}}")),
                UsageTapTest.frame("chunk", chunk("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":5}}")));
        JsonObject reply = rebuild("/model/global.anthropic.claude-sonnet-5/invoke-with-response-stream",
                "application/vnd.amazon.eventstream", stream);
        assertEquals("ok", reply.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());
        assertEquals("end_turn", reply.get("stop_reason").getAsString());
    }

    @Test
    void openAiChatContentAndToolCallArgumentsFromPieces() {
        String sse = """
                data: {"id":"c1","model":"m","choices":[{"index":0,"delta":{"role":"assistant","content":"hi "}}]}

                data: {"id":"c1","model":"m","choices":[{"index":0,"delta":{"content":"there"}}]}

                data: {"id":"c1","model":"m","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"write","arguments":"{\\"a\\":"}}]}}]}

                data: {"id":"c1","model":"m","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"1}"}}]},"finish_reason":"tool_calls"}]}

                data: {"id":"c1","model":"m","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":4}}

                data: [DONE]

                """;
        JsonObject reply = rebuild("/v1/chat/completions", "text/event-stream", utf8(sse));
        JsonObject message = reply.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message");
        assertEquals("hi there", message.get("content").getAsString());
        JsonObject call = message.getAsJsonArray("tool_calls").get(0).getAsJsonObject();
        assertEquals("call_1", call.get("id").getAsString());
        assertEquals("{\"a\":1}", call.getAsJsonObject("function").get("arguments").getAsString());
        assertEquals("tool_calls", reply.getAsJsonArray("choices").get(0).getAsJsonObject().get("finish_reason").getAsString());
        assertEquals(4, reply.getAsJsonObject("usage").get("completion_tokens").getAsInt());
    }

    @Test
    void openAiResponsesTakesTheCompletedResponse() {
        String sse = """
                data: {"type":"response.created","response":{"id":"r1","status":"in_progress","output":[]}}

                data: {"type":"response.output_text.delta","delta":"o"}

                data: {"type":"response.completed","response":{"id":"r1","status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"ok"}]}]}}

                """;
        JsonObject reply = rebuild("/v1/responses", "text/event-stream", utf8(sse));
        assertEquals("completed", reply.get("status").getAsString());
        assertEquals("ok", reply.getAsJsonArray("output").get(0).getAsJsonObject().getAsJsonArray("content")
                .get(0).getAsJsonObject().get("text").getAsString());
    }

    @Test
    void converseStreamRebuildsTextAndToolUse() {
        byte[] stream = UsageTapTest.concat(
                UsageTapTest.frame("messageStart", "{\"role\":\"assistant\"}"),
                UsageTapTest.frame("contentBlockDelta", "{\"contentBlockIndex\":0,\"delta\":{\"text\":\"wri\"}}"),
                UsageTapTest.frame("contentBlockDelta", "{\"contentBlockIndex\":0,\"delta\":{\"text\":\"ting\"}}"),
                UsageTapTest.frame("contentBlockStart", "{\"contentBlockIndex\":1,\"start\":{\"toolUse\":{\"toolUseId\":\"tu1\",\"name\":\"write\"}}}"),
                UsageTapTest.frame("contentBlockDelta", "{\"contentBlockIndex\":1,\"delta\":{\"toolUse\":{\"input\":\"{\\\"p\\\":\"}}}"),
                UsageTapTest.frame("contentBlockDelta", "{\"contentBlockIndex\":1,\"delta\":{\"toolUse\":{\"input\":\"2}\"}}}"),
                UsageTapTest.frame("messageStop", "{\"stopReason\":\"tool_use\"}"),
                UsageTapTest.frame("metadata", "{\"usage\":{\"inputTokens\":1,\"outputTokens\":2},\"metrics\":{\"latencyMs\":1}}"));
        JsonObject reply = rebuild("/model/x/converse-stream", "application/vnd.amazon.eventstream", stream);
        var content = reply.getAsJsonObject("output").getAsJsonObject("message").getAsJsonArray("content");
        assertEquals("writing", content.get(0).getAsJsonObject().get("text").getAsString());
        assertEquals(2, content.get(1).getAsJsonObject().getAsJsonObject("toolUse").getAsJsonObject("input").get("p").getAsInt());
        assertEquals("tool_use", reply.get("stopReason").getAsString());
    }

    @Test
    void geminiJoinsTextAcrossChunks() {
        String sse = """
                data: {"candidates":[{"content":{"role":"model","parts":[{"text":"hel"}]}}]}

                data: {"candidates":[{"content":{"role":"model","parts":[{"text":"lo"}]}}]}

                data: {"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"write","args":{"x":1}}}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":5}}

                """;
        JsonObject reply = rebuild("/v1beta/models/g:streamGenerateContent", "text/event-stream", utf8(sse));
        var candidate = reply.getAsJsonArray("candidates").get(0).getAsJsonObject();
        var parts = candidate.getAsJsonObject("content").getAsJsonArray("parts");
        assertEquals(2, parts.size());
        assertEquals("hello", parts.get(0).getAsJsonObject().get("text").getAsString());
        assertEquals("write", parts.get(1).getAsJsonObject().getAsJsonObject("functionCall").get("name").getAsString());
        assertEquals("STOP", candidate.get("finishReason").getAsString());
    }

    private static String chunk(String json) {
        return "{\"bytes\":\"" + Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)) + "\"}";
    }
}
