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
 * <p>Five of them keep a session a later container can resume — claude-code, qwen-code, gemini-cli,
 * codex and opencode — which is what makes human-in-the-loop, a resumed rework and the self-heal
 * loop work for them. The rest are one-shot: answering their question re-runs the task with the
 * answer appended, over the workspace the first attempt left behind. The entrypoint's
 * {@code sessioned()} is the list, and it is deliberately the conservative one — a resume flag a
 * tool does not honour starts a silent NEW session, which is worse than not resuming at all.
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
    @SerializedName("opencode") OPENCODE("opencode", Dialect.OPENAI, ApiFormat.OPENAI_CHAT, ApiFormat.ANTHROPIC),
    @SerializedName("aider") AIDER("aider", Dialect.OPENAI, ApiFormat.OPENAI_CHAT, ApiFormat.ANTHROPIC),
    @SerializedName("goose") GOOSE("goose", Dialect.OPENAI, ApiFormat.OPENAI_CHAT, ApiFormat.ANTHROPIC),
    @SerializedName("copilot") COPILOT("copilot", Dialect.OPENAI, ApiFormat.OPENAI_CHAT),
    @SerializedName("pi") PI("pi", Dialect.OPENAI, ApiFormat.OPENAI_CHAT, ApiFormat.ANTHROPIC),
    @SerializedName("crush") CRUSH("crush", Dialect.OPENAI, ApiFormat.OPENAI_CHAT, ApiFormat.ANTHROPIC),
    @SerializedName("cline") CLINE("cline", Dialect.OPENAI, ApiFormat.OPENAI_CHAT, ApiFormat.ANTHROPIC),
    @SerializedName("continue") CONTINUE("continue", Dialect.OPENAI, ApiFormat.OPENAI_CHAT, ApiFormat.ANTHROPIC),
    @SerializedName("openhands") OPENHANDS("openhands", Dialect.OPENAI, ApiFormat.OPENAI_CHAT, ApiFormat.ANTHROPIC),
    @SerializedName("dsh") DSH("dsh", Dialect.OPENAI, ApiFormat.OPENAI_CHAT, ApiFormat.ANTHROPIC),
    @SerializedName("openclaw") OPENCLAW("openclaw", Dialect.OPENAI, ApiFormat.OPENAI_CHAT, ApiFormat.ANTHROPIC),
    @SerializedName("hermes") HERMES("hermes", Dialect.OPENAI, ApiFormat.OPENAI_CHAT, ApiFormat.ANTHROPIC),
    @SerializedName("deepagents") DEEPAGENTS("deepagents", Dialect.OPENAI, ApiFormat.OPENAI_CHAT, ApiFormat.ANTHROPIC),
    @SerializedName("kimi") KIMI("kimi", Dialect.OPENAI, ApiFormat.OPENAI_CHAT, ApiFormat.ANTHROPIC),
    @SerializedName("codewhale") CODEWHALE("codewhale", Dialect.OPENAI, ApiFormat.OPENAI_CHAT, ApiFormat.ANTHROPIC),
    @SerializedName("reasonix") REASONIX("reasonix", Dialect.OPENAI, ApiFormat.OPENAI_CHAT, ApiFormat.ANTHROPIC),
    @SerializedName("jcode") JCODE("jcode", Dialect.OPENAI, ApiFormat.OPENAI_CHAT, ApiFormat.ANTHROPIC),
    @SerializedName("grok") GROK("grok", Dialect.OPENAI, ApiFormat.OPENAI_CHAT, ApiFormat.ANTHROPIC);

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
