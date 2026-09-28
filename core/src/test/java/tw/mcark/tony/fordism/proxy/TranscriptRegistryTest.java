package tw.mcark.tony.fordism.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The two registries: a codec for every format, and a fix chain for every tool. */
class TranscriptRegistryTest {

    @Test
    void everyFormatTheUsageReaderRecognisesHasACodec() {
        List<String> paths = List.of("/v1/messages", "/model/m/invoke", "/model/m/converse-stream",
                "/v1/chat/completions", "/v1/responses", "/v1beta/models/g:streamGenerateContent");
        for (String path : paths) {
            String format = UsageExtractor.forPath(path).orElseThrow().format();
            FormatCodecs.forFormat(format);   // throws if missing
        }
        assertEquals(6, FormatCodecs.formats().size());
    }

    @Test
    void aFormatWithoutACodecIsAnErrorNotASkip() {
        assertThrows(IllegalArgumentException.class, () -> FormatCodecs.forFormat("mystery"));
    }

    @Test
    void eachCodecFindsTheConversationWhereItsFormatKeepsIt() {
        assertParts("anthropic", "{\"model\":\"m\",\"system\":\"S\",\"tools\":[1],\"messages\":[1,2],\"max_tokens\":9}", "S", 2, "m");
        assertParts("bedrock-invoke", "{\"system\":\"S\",\"tools\":[1],\"messages\":[1,2,3]}", "S", 3, "claude-x");
        assertParts("bedrock-converse", "{\"system\":[{\"text\":\"S\"}],\"toolConfig\":{},\"messages\":[1]}", "[{\"text\":\"S\"}]", 1, "claude-x");
        assertParts("openai-chat", "{\"model\":\"m\",\"messages\":[{\"role\":\"system\",\"content\":\"S\"},2]}", null, 2, "m");
        assertParts("openai-responses", "{\"model\":\"m\",\"instructions\":\"S\",\"input\":\"hi\"}", "S", 1, "m");
        assertParts("gemini", "{\"systemInstruction\":{\"parts\":[]},\"contents\":[1,2]}", "{\"parts\":[]}", 2, "g");
    }

    private static void assertParts(String format, String request, String system, int messages, String model) {
        String path = switch (format) {
            case "bedrock-invoke" -> "/model/claude-x/invoke";
            case "bedrock-converse" -> "/model/claude-x/converse";
            case "gemini" -> "/v1beta/models/g:generateContent";
            default -> "/v1/x";
        };
        ConversationParts parts = FormatCodecs.forFormat(format)
                .read(JsonParser.parseString(request).getAsJsonObject(), path);
        assertEquals(system, parts.system == null ? null
                : parts.system.isJsonPrimitive() ? parts.system.getAsString() : parts.system.toString(), format);
        assertEquals(messages, parts.messages.size(), format);
        assertEquals(model, parts.model, format);
    }

    @Test
    void openAiChatThreadsBySystemMessageBecauseItHasNoSystemField() {
        FormatCodec chat = FormatCodecs.forFormat("openai-chat");
        ConversationParts parts = chat.read(JsonParser.parseString(
                "{\"messages\":[{\"role\":\"developer\",\"content\":\"D\"},{\"role\":\"user\",\"content\":\"u\"}]}")
                .getAsJsonObject(), "/v1/chat/completions");
        assertEquals("{\"role\":\"developer\",\"content\":\"D\"}", chat.threadKey(parts).toString());
    }

    @Test
    void aToolWithoutItsOwnChainGetsTheDefault() {
        assertSame(ToolFixes.DEFAULT, ToolFixes.forTool("pi"));
        assertSame(ToolFixes.DEFAULT, ToolFixes.forTool("some-future-tool"));
        assertSame(ToolFixes.DEFAULT, ToolFixes.forTool(null));
    }

    @Test
    void theChainRunsInOrder_markersFirstThenTheShapeRule() {
        List<TranscriptFix> chain = ToolFixes.forTool("claude-code");
        assertEquals(List.of("strip-cache-markers", "single-text-block-as-string"), chain.stream().map(TranscriptFix::name).toList());
        JsonElement message = JsonParser.parseString(
                "{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"x\",\"cache_control\":{\"type\":\"ephemeral\"}}]}");
        // The block only has type+text once its marker is gone, so order is what makes this match the string form.
        assertEquals("{\"role\":\"user\",\"content\":\"x\"}", TranscriptFix.apply(chain, message).toString());
    }

    @Test
    void aFixWorksOnACopyAndNeverTouchesTheOriginal() {
        JsonObject message = JsonParser.parseString(
                "{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"x\",\"cache_control\":{}}]}").getAsJsonObject();
        String before = message.toString();
        TranscriptFix.apply(ToolFixes.forTool("claude-code"), message);
        assertEquals(before, message.toString());
    }
}
