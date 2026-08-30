package tw.mcark.tony.fordism.launch;

import tw.mcark.tony.fordism.agentprofile.AgentTool;

/**
 * Everything the launcher needs to point an agent at a model: where to call, what to authenticate
 * with, which CLI drives the task, and which model to ask for.
 *
 * <p>{@code tool} decides the dialect, and the dialect selects the environment variable names the
 * launcher sets; the tool's wire name selects the entrypoint branch.
 */
public record AgentBackend(String baseUrl, String authToken, AgentTool tool, String model) {

    public AgentTool.Dialect dialect() {
        return tool.dialect();
    }
}
