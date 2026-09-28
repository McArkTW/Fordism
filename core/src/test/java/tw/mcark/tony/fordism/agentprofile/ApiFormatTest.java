package tw.mcark.tony.fordism.agentprofile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The profile's wire format, and the rule the javadoc on {@link AgentProfile} states: a format the
 * tool cannot speak is refused when the profile is built, not discovered as a 404 inside a
 * container twenty seconds into a task.
 */
class ApiFormatTest {

    private static AgentProfile profile(AgentTool tool, ApiFormat format) {
        return new AgentProfile("id", "p", "https://example.test", "sk-x", "m", tool, format);
    }

    @Test
    void a_format_the_tool_cannot_speak_is_refused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> profile(AgentTool.CLAUDE_CODE, ApiFormat.GEMINI));
        assertTrue(refused.getMessage().contains("claude-code"), refused.getMessage());
        assertTrue(refused.getMessage().contains("gemini"), refused.getMessage());
    }

    @Test
    void an_absent_format_becomes_the_tools_primary() {
        assertEquals(ApiFormat.ANTHROPIC, profile(AgentTool.CLAUDE_CODE, null).format());
        assertEquals(ApiFormat.OPENAI_CHAT, profile(AgentTool.QWEN_CODE, null).format());
        assertEquals(ApiFormat.GEMINI, profile(AgentTool.GEMINI_CLI, null).format());
    }

    /**
     * The reason this enum exists at all. Both tools carry Dialect.OPENAI, so the launcher hands
     * them the same OPENAI_* variable names — but codex has dropped chat-completions, so what the
     * endpoint must serve differs. Collapse the two and codex's entrypoint writes wire_api "chat"
     * again and every call 404s.
     */
    @Test
    void codex_and_qwen_share_a_dialect_but_not_a_format() {
        assertEquals(AgentTool.CODEX.dialect(), AgentTool.QWEN_CODE.dialect());
        assertEquals(ApiFormat.OPENAI_RESPONSES, AgentTool.CODEX.primaryFormat());
        assertEquals(ApiFormat.OPENAI_CHAT, AgentTool.QWEN_CODE.primaryFormat());
    }

    @Test
    void a_tool_that_speaks_two_formats_accepts_either() {
        assertEquals(ApiFormat.ANTHROPIC, profile(AgentTool.OPENCODE, ApiFormat.ANTHROPIC).format());
        assertEquals(ApiFormat.OPENAI_CHAT, profile(AgentTool.OPENCODE, ApiFormat.OPENAI_CHAT).format());
    }

    /**
     * Unlike {@link AgentTool#from}, which falls back to claude-code. A misspelled tool has a
     * default that was always right; a misspelled format has none, and guessing would point the
     * agent at an endpoint nobody chose.
     */
    @Test
    void an_unknown_format_throws_rather_than_falling_back() {
        assertThrows(IllegalArgumentException.class, () -> ApiFormat.from("openai-responsez"));
        assertTrue(ApiFormat.from("").isEmpty());
        assertTrue(ApiFormat.from(null).isEmpty());
        assertEquals(ApiFormat.OPENAI_RESPONSES, ApiFormat.from("  OpenAI-Responses ").orElseThrow());
    }

    @Test
    void wire_names_are_unique_and_every_tool_declares_at_least_one_format() {
        Set<String> seen = new HashSet<>();
        for (ApiFormat format : ApiFormat.values()) {
            assertTrue(seen.add(format.wireName()), "duplicate wire name " + format.wireName());
        }
        for (AgentTool tool : AgentTool.values()) {
            assertTrue(!tool.formats().isEmpty(), tool.name() + " declares no format");
            assertTrue(tool.speaks(tool.primaryFormat()), tool.name());
        }
    }
}
