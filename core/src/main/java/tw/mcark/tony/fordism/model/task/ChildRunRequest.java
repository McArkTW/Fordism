package tw.mcark.tony.fordism.model.task;

import java.util.Map;

/**
 * A workflow run an agent asked the engine to start, read out of its {@code result.json}.
 *
 * <p>This is a REQUEST, not a run: the agent names a workflow and the parameters to start it with,
 * and the engine decides whether that workflow exists and whose child the run becomes. An agent
 * cannot start a run any other way — it has no network route to the API and no credentials for one
 * — so the whole surface for spawning work is this record and the field it is parsed from.
 */
public record ChildRunRequest(String workflow, Map<String, String> parameters) {

    public ChildRunRequest {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }
}
