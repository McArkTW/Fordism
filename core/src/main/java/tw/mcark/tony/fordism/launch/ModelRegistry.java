package tw.mcark.tony.fordism.launch;

import tw.mcark.tony.fordism.agentprofile.AgentProfile;
import tw.mcark.tony.fordism.agentprofile.AgentProfileStore;
import tw.mcark.tony.fordism.agentprofile.AgentTool;
import tw.mcark.tony.fordism.config.FordismConfiguration;

/**
 * Resolves a model to its backend endpoint. A named Agent Profile resolves to that profile's
 * base URL + API key; with no profile (or an unknown one) it falls back to the default backend
 * from config (Ollama — no gateway). Keyless profiles use a placeholder non-empty token.
 */
public final class ModelRegistry {
    private static final String DEFAULT_TOKEN = "fordism-agent-token";
    private final FordismConfiguration configuration;
    private final AgentProfileStore profiles;

    public ModelRegistry(FordismConfiguration configuration, AgentProfileStore profiles) {
        this.configuration = configuration;
        this.profiles = profiles;
    }

    /**
     * Resolves an Agent Profile (by name) to the backend that serves it. The profile's own model
     * wins. With no profile named: the sole existing profile, if there is exactly one — so a fresh
     * install works the moment its first profile is created — else the config default backend
     * + claude-code.
     */
    public AgentBackend backend(String profileName, String model) {
        return profiles.getByName(profileName)
                .or(profiles::sole)
                .filter(profile -> profile.baseUrl() != null && !profile.baseUrl().isBlank())
                .map(profile -> new AgentBackend(
                        profile.baseUrl(),
                        profile.hasKey() ? profile.apiKey() : DEFAULT_TOKEN,
                        profile.tool(),
                        profile.model() == null || profile.model().isBlank() ? model : profile.model()))
                .orElseGet(() -> new AgentBackend(configuration.llmBaseUrl, DEFAULT_TOKEN, AgentTool.CLAUDE_CODE, model));
    }
}
