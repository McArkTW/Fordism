package tw.mcark.tony.fordism.parse;

import tw.mcark.tony.fordism.agentprofile.AgentTool;

/**
 * Which CLI a step's agent is, as far as continuing it is concerned: the Agent Profile its template
 * resolves to, and that profile's tool. {@code profile} is blank for the default backend.
 */
public record StepAgent(String profile, AgentTool tool) {
    public StepAgent {
        profile = profile == null ? "" : profile;
    }
}
