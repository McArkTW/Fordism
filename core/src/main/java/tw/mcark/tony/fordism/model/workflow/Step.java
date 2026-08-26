package tw.mcark.tony.fordism.model.workflow;

import tw.mcark.tony.fordism.model.task.TaskConfiguration;
import java.util.List;
import java.util.Map;

/** The universal step atom (template + task), plus optional per-strategy wiring. */
public record Step(String id, String template, String task, boolean includePreviousResult,
                   List<String> dependsOn, Map<String, Object> onFail, String forEach, String when,
                   TaskConfiguration config) {}
