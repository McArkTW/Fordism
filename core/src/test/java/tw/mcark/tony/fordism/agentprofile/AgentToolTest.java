package tw.mcark.tony.fordism.agentprofile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The tool → dialect mapping, which is what the launcher's environment switch depends on. Getting a
 * dialect wrong hands an agent the wrong provider's env vars, so it never authenticates — a failure
 * that only shows at run time, which is exactly why it is pinned here.
 */
class AgentToolTest {

    @Test
    void each_tool_speaks_the_dialect_its_cli_expects() {
        assertEquals(AgentTool.Dialect.ANTHROPIC, AgentTool.CLAUDE_CODE.dialect());
        assertEquals(AgentTool.Dialect.OPENAI, AgentTool.QWEN_CODE.dialect());
        assertEquals(AgentTool.Dialect.OPENAI, AgentTool.CODEX.dialect());
        assertEquals(AgentTool.Dialect.OPENAI, AgentTool.OPENCODE.dialect());
        assertEquals(AgentTool.Dialect.GOOGLE, AgentTool.GEMINI_CLI.dialect());
    }

    @Test
    void wire_names_are_unique_and_every_tool_has_a_dialect() {
        Set<String> seen = new HashSet<>();
        for (AgentTool tool : AgentTool.values()) {
            assertNotNull(tool.dialect(), tool.name());
            assertEquals(true, seen.add(tool.wireName()), "duplicate wire name " + tool.wireName());
        }
    }

    @Test
    void an_unknown_or_absent_tool_falls_back_to_claude_code() {
        assertEquals(AgentTool.CLAUDE_CODE, AgentTool.from(null));
        assertEquals(AgentTool.CLAUDE_CODE, AgentTool.from(""));
        assertEquals(AgentTool.CLAUDE_CODE, AgentTool.from("a-tool-that-does-not-exist"));
    }
}
