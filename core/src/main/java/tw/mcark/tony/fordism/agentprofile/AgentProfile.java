package tw.mcark.tony.fordism.agentprofile;

/**
 * An Agent Profile: a named backend + the agent tool that drives it. Identity is {@code id} (a UUID,
 * the on-disk filename); {@code name} is a mutable display label — renaming edits the field, the
 * id (and every reference to it) is unaffected. {@code apiKey} is write-only (never returned).
 * {@code tool} selects the agent runtime ({@link AgentTool}) and {@code format} the wire format the
 * endpoint speaks ({@link ApiFormat}); a format the tool cannot speak is refused here rather than
 * discovered as a 404 inside a container.
 */
public record AgentProfile(String id, String name, String baseUrl, String apiKey, String model, AgentTool tool,
                           ApiFormat format) {
    public AgentProfile {
        // Tolerant about what is MISSING, strict about what is WRONG. A record already on disk may
        // predate either field, and refusing it here would take the store — and every template
        // pointing at it — down at boot; so an absent tool or format is filled in. A format the
        // tool cannot speak is not an absence, it is a mistake, and it throws.
        tool = tool == null ? AgentTool.CLAUDE_CODE : tool;
        format = format == null ? tool.primaryFormat() : format;
        if (!tool.speaks(format)) {
            throw new IllegalArgumentException(tool.wireName() + " does not speak " + format.wireName()
                    + "; it speaks " + tool.formats().stream().map(ApiFormat::wireName).toList());
        }
    }

    public boolean hasKey() {
        return apiKey != null && !apiKey.isBlank();
    }


}
