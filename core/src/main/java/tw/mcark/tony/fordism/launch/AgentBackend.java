package tw.mcark.tony.fordism.launch;

import tw.mcark.tony.fordism.agentprofile.AgentTool;
import tw.mcark.tony.fordism.agentprofile.ApiFormat;

/**
 * Everything the launcher needs to point an agent at a model: where to call, what to authenticate
 * with, which CLI drives the task, which wire format the endpoint speaks, and which model to ask for.
 *
 * <p>{@code tool} decides the dialect, and the dialect selects the environment variable names the
 * launcher sets; the tool's wire name selects the entrypoint branch. {@code format} says what the
 * endpoint behind those names serves, which is what the entrypoint configures a tool's provider
 * against — core has already checked the tool speaks it.
 */
public record AgentBackend(String baseUrl, String authToken, AgentTool tool, ApiFormat format, String model) {

    public AgentTool.Dialect dialect() {
        return tool.dialect();
    }
}
