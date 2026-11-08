package tw.mcark.tony.fordism.workspace;

import tw.mcark.tony.fordism.agentprofile.AgentProfile;
import tw.mcark.tony.fordism.agentprofile.AgentProfileStore;
import tw.mcark.tony.fordism.launch.ModelRegistry;
import tw.mcark.tony.fordism.parse.StepAgent;
import tw.mcark.tony.fordism.parse.StepAgents;
import java.util.Optional;

/**
 * A template resolved to the agent it runs as, the way the dispatcher and launcher will resolve it:
 * the template's Agent Profile, else the sole profile, else the default backend — and the tool that
 * profile drives. What the {@code continues} check compares, so it refuses exactly the pairs that
 * would run as two different CLIs.
 */
public final class TemplateAgents implements StepAgents {
    private final TemplateStore templates;
    private final AgentProfileStore profiles;
    private final ModelRegistry models;

    public TemplateAgents(TemplateStore templates, AgentProfileStore profiles, ModelRegistry models) {
        this.templates = templates;
        this.profiles = profiles;
        this.models = models;
    }

    public Optional<StepAgent> of(String template) {
        return templates.getByName(template).map(found -> agentFor(found.agentProfile()));
    }

    public Optional<StepAgent> bare() {
        return Optional.of(agentFor(null));
    }

    /** Mirrors {@link ModelRegistry#backend}: a profile with no base URL is not used, so it is not named. */
    private StepAgent agentFor(String profileName) {
        String profile = profiles.getByName(profileName).or(profiles::sole)
                .filter(found -> found.baseUrl() != null && !found.baseUrl().isBlank())
                .map(AgentProfile::name).orElse("");
        return new StepAgent(profile, models.backend(profileName).tool());
    }
}
