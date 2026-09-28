package tw.mcark.tony.fordism.agentprofile;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/**
 * Which CLI drives a task, and therefore which model dialect it speaks.
 *
 * <p>One agent image bakes them all, so this is the only thing that decides. Each tool carries its
 * {@link Dialect}, and the launcher sets the environment for that dialect — not for the tool — so a
 * new OpenAI-compatible CLI is one enum line, not a new branch in the launcher.
 *
 * <ul>
 *   <li>{@link #CLAUDE_CODE} — Anthropic ({@code ANTHROPIC_BASE_URL} / {@code ANTHROPIC_AUTH_TOKEN}),
 *       {@code claude -p}.
 *   <li>{@link #QWEN_CODE} — OpenAI-chat ({@code OPENAI_*}), {@code qwen -p}.
 *   <li>{@link #GEMINI_CLI} — Google ({@code GEMINI_API_KEY}), {@code gemini -p}.
 *   <li>{@link #CODEX} — OpenAI ({@code OPENAI_*}), {@code codex exec}.
 *   <li>{@link #OPENCODE} — OpenAI-compatible ({@code OPENAI_*}), {@code opencode run}.
 * </ul>
 *
 * <p>Each tool also declares the {@link ApiFormat}s its endpoint may serve, primary first. The
 * dialect and the format answer different questions: the dialect names the environment variables,
 * the format says what is behind them. {@link #CODEX} is why the distinction earns its place — it
 * shares the OpenAI dialect with {@link #QWEN_CODE} but needs {@code /responses}, because codex
 * dropped chat-completions. {@link #OPENCODE} genuinely speaks either, so it lists both and the
 * profile picks.
 *
 * <p>The hyphenated wire names are persisted in each profile's JSON and accepted from the API. A
 * profile written before this field existed has no tool — {@link #from} reads that, and anything it
 * cannot place, as claude-code, the default the store applied before.
 */
public enum AgentTool {
    @SerializedName("claude-code") CLAUDE_CODE("claude-code", Dialect.ANTHROPIC, ApiFormat.ANTHROPIC),
    @SerializedName("qwen-code") QWEN_CODE("qwen-code", Dialect.OPENAI, ApiFormat.OPENAI_CHAT),
    @SerializedName("gemini-cli") GEMINI_CLI("gemini-cli", Dialect.GOOGLE, ApiFormat.GEMINI),
    @SerializedName("codex") CODEX("codex", Dialect.OPENAI, ApiFormat.OPENAI_RESPONSES, ApiFormat.OPENAI_CHAT),
    @SerializedName("opencode") OPENCODE("opencode", Dialect.OPENAI, ApiFormat.OPENAI_CHAT, ApiFormat.ANTHROPIC);

    /** The wire format a tool's model backend speaks — what the launcher's environment depends on. */
    public enum Dialect { ANTHROPIC, OPENAI, GOOGLE }

    private final String wireName;
    private final Dialect dialect;
    private final List<ApiFormat> formats;

    AgentTool(String wireName, Dialect dialect, ApiFormat... formats) {
        this.wireName = wireName;
        this.dialect = dialect;
        this.formats = List.of(formats);
    }

    public String wireName() {
        return wireName;
    }

    public Dialect dialect() {
        return dialect;
    }

    /** The wire formats this tool can speak, primary first. Never empty. */
    public List<ApiFormat> formats() {
        return formats;
    }

    /** The format a profile gets when it names none — what this tool's endpoint usually serves. */
    public ApiFormat primaryFormat() {
        return formats.get(0);
    }

    public boolean speaks(ApiFormat format) {
        return formats.contains(format);
    }

    /** The stored or submitted token; blank, absent or unrecognised means claude-code. */
    public static AgentTool from(String token) {
        if (token == null || token.isBlank()) {
            return CLAUDE_CODE;
        }
        String trimmed = token.trim().toLowerCase();
        for (AgentTool tool : values()) {
            if (tool.wireName.equals(trimmed)) {
                return tool;
            }
        }
        return CLAUDE_CODE;
    }
}
