package com.hp.vcosmos.foundry.launch;

import com.hp.vcosmos.foundry.agentprofile.AgentProfile;
import com.hp.vcosmos.foundry.agentprofile.AgentProfileStore;
import com.hp.vcosmos.foundry.agentprofile.AgentTool;
import com.hp.vcosmos.foundry.config.FoundryConfiguration;

/**
 * Resolves a model to its backend endpoint. A named Agent Profile resolves to that profile's
 * base URL + API key; with no profile (or an unknown one) it falls back to the default backend
 * from config (Ollama — no gateway). Keyless profiles use a placeholder non-empty token.
 */
public final class ModelRegistry {
    private static final String DEFAULT_TOKEN = "foundry-agent-token";
    private final FoundryConfiguration configuration;
    private final AgentProfileStore profiles;

    public ModelRegistry(FoundryConfiguration configuration, AgentProfileStore profiles) {
        this.configuration = configuration;
        this.profiles = profiles;
    }

    /**
     * Resolves an Agent Profile (by name) to the backend that serves it. The profile's own model
     * wins; with no or unknown profile it falls back to the config default backend + claude-code.
     */
    public AgentBackend backend(String profileName, String model) {
        return profiles.getByName(profileName)
                .filter(profile -> profile.baseUrl() != null && !profile.baseUrl().isBlank())
                .map(profile -> new AgentBackend(
                        profile.baseUrl(),
                        profile.hasKey() ? profile.apiKey() : DEFAULT_TOKEN,
                        profile.tool(),
                        profile.model() == null || profile.model().isBlank() ? model : profile.model()))
                .orElseGet(() -> new AgentBackend(configuration.llmBaseUrl, DEFAULT_TOKEN, AgentTool.CLAUDE_CODE, model));
    }
}
