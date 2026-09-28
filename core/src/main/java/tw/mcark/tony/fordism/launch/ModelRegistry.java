package tw.mcark.tony.fordism.launch;

import tw.mcark.tony.fordism.agentprofile.AgentProfile;
import tw.mcark.tony.fordism.agentprofile.AgentProfileStore;
import tw.mcark.tony.fordism.agentprofile.AgentTool;
import tw.mcark.tony.fordism.agentprofile.ApiFormat;
import tw.mcark.tony.fordism.config.FordismConfiguration;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Resolves a model to its backend endpoint. A named Agent Profile resolves to that profile's
 * base URL + API key; with no profile (or an unknown one) it falls back to the default backend
 * from config (Ollama — no gateway). Keyless profiles use a placeholder non-empty token.
 */
public final class ModelRegistry {
    private static final String DEFAULT_TOKEN = "fordism-agent-token";
    private final FordismConfiguration configuration;
    private final Function<String, Optional<AgentProfile>> byName;
    private final Supplier<Optional<AgentProfile>> sole;

    public ModelRegistry(FordismConfiguration configuration, AgentProfileStore profiles) {
        this(configuration, profiles::getByName, profiles::sole);
    }

    /**
     * How a profile is found, rather than where it is stored. The store is the only caller that
     * matters in production; this shape exists so the proxy's tests can stand a profile up without
     * a directory on disk, and so nothing here needs to know a profile is a file.
     */
    public ModelRegistry(FordismConfiguration configuration, Function<String, Optional<AgentProfile>> byName,
            Supplier<Optional<AgentProfile>> sole) {
        this.configuration = configuration;
        this.byName = byName;
        this.sole = sole;
    }

    /**
     * Resolves an Agent Profile (by name) to the backend that serves it. The profile's own model
     * wins. With no profile named: the sole existing profile, if there is exactly one — so a fresh
     * install works the moment its first profile is created — else the config default backend
     * + claude-code.
     */
    /**
     * The backend a profile resolves to, with no step model to prefer — what the proxy needs, since
     * it is handed a task id and has to find the real endpoint behind the token the tool presented.
     * The profile's own model is used, exactly as it would be when a step names none.
     */
    public AgentBackend backend(String profileName) {
        return backend(profileName, null);
    }

    public AgentBackend backend(String profileName, String model) {
        return byName.apply(profileName)
                .or(sole)
                .filter(profile -> profile.baseUrl() != null && !profile.baseUrl().isBlank())
                .map(profile -> new AgentBackend(
                        profile.baseUrl(),
                        profile.hasKey() ? profile.apiKey() : DEFAULT_TOKEN,
                        profile.tool(),
                        profile.format(),
                        profile.model() == null || profile.model().isBlank() ? model : profile.model()))
                .orElseGet(() -> new AgentBackend(configuration.llmBaseUrl, DEFAULT_TOKEN, AgentTool.CLAUDE_CODE,
                        AgentTool.CLAUDE_CODE.primaryFormat(), model));
    }
}
