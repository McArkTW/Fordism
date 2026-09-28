package tw.mcark.tony.fordism.agentprofile;

import com.google.gson.annotations.SerializedName;
import java.util.Optional;

/**
 * The wire format a profile's endpoint speaks. The endpoint decides it, not the model and not the
 * tool: the same Claude model is served as Anthropic Messages at api.anthropic.com, as OpenAI Chat
 * Completions through a {@code /v1/} compatibility layer, and as neither on Bedrock. So the profile
 * states it, and each {@link AgentTool} declares which formats it can speak.
 *
 * <p>This is deliberately finer than {@link AgentTool.Dialect}. The dialect decides which
 * environment variable NAMES the launcher sets ({@code OPENAI_*} for every OpenAI-compatible CLI);
 * the format decides what the endpoint behind those names actually serves. Two tools can share a
 * dialect and still need different endpoints — codex speaks {@code /responses} where qwen-code
 * speaks {@code /chat/completions}, and both read {@code OPENAI_BASE_URL}.
 */
public enum ApiFormat {
    @SerializedName("anthropic") ANTHROPIC("anthropic"),
    @SerializedName("openai-chat") OPENAI_CHAT("openai-chat"),
    @SerializedName("openai-responses") OPENAI_RESPONSES("openai-responses"),
    @SerializedName("gemini") GEMINI("gemini");

    private final String wireName;

    ApiFormat(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    /**
     * The submitted token. Blank is empty — the caller substitutes the tool's primary format.
     *
     * <p>An unrecognised token throws rather than falling back, which is the opposite of
     * {@link AgentTool#from}. A misspelled tool has a safe default (claude-code was what the store
     * applied before the field existed); a misspelled format has none — guessing one would point
     * the agent at an endpoint nobody chose and only show up as a 404 at run time.
     */
    public static Optional<ApiFormat> from(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String trimmed = token.trim().toLowerCase();
        for (ApiFormat format : values()) {
            if (format.wireName.equals(trimmed)) {
                return Optional.of(format);
            }
        }
        throw new IllegalArgumentException("unknown format \"" + token + "\"");
    }
}
