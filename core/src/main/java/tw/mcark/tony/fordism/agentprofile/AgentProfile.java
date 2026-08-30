package tw.mcark.tony.fordism.agentprofile;

/**
 * An Agent Profile: a named backend + the agent tool that drives it. Identity is {@code id} (a UUID,
 * the on-disk filename); {@code name} is a mutable display label — renaming edits the field, the
 * id (and every reference to it) is unaffected. {@code apiKey} is write-only (never returned).
 * {@code tool} selects the agent runtime: {@code claude-code} (Anthropic dialect) or
 * {@code qwen-code} (OpenAI-chat dialect); blank defaults to claude-code.
 */
public record AgentProfile(String id, String name, String baseUrl, String apiKey, String model, AgentTool tool) {
    public AgentProfile {
        tool = tool == null ? AgentTool.CLAUDE_CODE : tool;   // records written before the field existed
    }

    public boolean hasKey() {
        return apiKey != null && !apiKey.isBlank();
    }


}
