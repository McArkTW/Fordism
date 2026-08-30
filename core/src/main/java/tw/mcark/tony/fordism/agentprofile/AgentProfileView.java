package tw.mcark.tony.fordism.agentprofile;

/**
 * An Agent Profile as the browser sees it — the API key is never a field here, only the fact that
 * one is stored. {@code exists} is absent on the list rows.
 */
public record AgentProfileView(String id, String name, String baseUrl, String model, String tool,
                               boolean hasKey, Boolean exists) {

    public static AgentProfileView of(AgentProfile profile) {
        return new AgentProfileView(profile.id(), profile.name(), profile.baseUrl(),
                profile.model() == null ? "" : profile.model(), profile.tool().wireName(),
                profile.hasKey(), null);
    }

    public AgentProfileView found() {
        return new AgentProfileView(id, name, baseUrl, model, tool, hasKey, true);
    }
}
