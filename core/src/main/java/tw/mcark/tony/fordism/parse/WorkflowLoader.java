package tw.mcark.tony.fordism.parse;

import tw.mcark.tony.fordism.model.task.NetworkPolicy;
import tw.mcark.tony.fordism.model.task.TaskConfiguration;
import tw.mcark.tony.fordism.model.workflow.Parameter;
import tw.mcark.tony.fordism.model.workflow.Step;
import tw.mcark.tony.fordism.model.workflow.Strategy;
import tw.mcark.tony.fordism.model.workflow.Workflow;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;

/**
 * Parses workflow YAML into Workflow definitions.
 *
 * <p>Unknown keys are rejected. Reading only the keys it recognises cannot tell a typo from a
 * comment, so {@code dependOn} for {@code dependsOn} produced a graph step with no dependencies —
 * every step fired at once, no error, and the first sign of it was the run.
 */
public final class WorkflowLoader {

    private static final Set<String> WORKFLOW_KEYS =
            Set.of("name", "description", "strategy", "tags", "parameters", "steps", "generator", "maxIterations");
    private static final Set<String> STEP_KEYS =
            Set.of("id", "template", "task", "includePreviousResult", "dependsOn", "onFail", "forEach", "when", "config");
    private static final Set<String> CONFIG_KEYS = Set.of("model", "timeoutSeconds", "network", "maxAttempts");
    private static final Set<String> ON_FAIL_KEYS = Set.of("retry", "maxAttempts");
    private static final Set<String> PARAMETER_KEYS = Set.of("name", "label", "type", "required", "default", "help");
    private static final Set<String> PARAMETER_TYPES = Set.of("text", "textarea", "number");

    /** The step id a {@code when:} predicate reads — same shape ConditionalOrchestrator evaluates. */
    private static final Pattern WHEN_STEP = Pattern.compile("(\\w[\\w-]*)\\.result\\s*==\\s*'([^']*)'");

    public Workflow load(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return fromMap(new Yaml().load(in));
        }
    }

    public Workflow parse(String yaml) {
        Object loaded = new Yaml().load(yaml);
        if (!(loaded instanceof Map)) {
            throw new IllegalArgumentException("workflow YAML must be a mapping");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) loaded;
        return fromMap(map);
    }

    @SuppressWarnings("unchecked")
    public Workflow fromMap(Map<String, Object> map) {
        reject("workflow", map, WORKFLOW_KEYS);
        String name = (String) map.get("name");
        String description = (String) map.getOrDefault("description", "");
        String strategyName = (String) map.getOrDefault("strategy", Strategy.LINEAR.wireName());
        Strategy strategy = Strategy.from(strategyName).orElseThrow(() -> new IllegalArgumentException(
                "unknown strategy \"" + strategyName + "\" — one of " + Strategy.names()));
        List<String> tags = strings(map.get("tags"));
        List<Parameter> parameters = parameters(map.get("parameters"));
        List<Step> steps = new ArrayList<>();
        if (map.get("steps") instanceof List<?> list) {
            int index = 0;
            for (Object item : list) {
                if (!(item instanceof Map)) {
                    throw new IllegalArgumentException("each entry of steps must be a mapping");
                }
                steps.add(stepFromMap((Map<String, Object>) item, "s" + (index++)));
            }
        }
        Step generator = map.get("generator") instanceof Map<?, ?> gen
                ? stepFromMap((Map<String, Object>) gen, "generator") : null;
        int maxIterations = asInt(map.get("maxIterations"), 10);
        if (strategy == Strategy.MAP_REDUCE && steps.size() != 2) {
            // MapReduceOrchestrator reads exactly steps 0 (map) and 1 (reduce); a third step would
            // be silently ignored, so refuse the shape here rather than drop work at run time.
            throw new IllegalArgumentException("map-reduce workflow \"" + name
                    + "\" must have exactly 2 steps (map, then reduce) — found " + steps.size());
        }
        validateStepReferences(steps);
        return new Workflow(name, description, strategy, tags, parameters, steps, generator, maxIterations);
    }

    /**
     * Every {@code dependsOn}, {@code onFail.retry} and {@code when} must name a step that exists.
     *
     * <p>Checked here because the orchestrators cannot check it usefully. A misspelled
     * {@code dependsOn} resolves to step index -1, so the graph orchestrator never sees its
     * dependency collect: the step is never seeded, the run never completes, and there is no task
     * to reap and nothing to time out — it stays ACTIVE forever. Rework and Conditional fail the
     * run loudly on the same typo, which is no better a place to learn about it than parse time.
     */
    private static void validateStepReferences(List<Step> steps) {
        Set<String> ids = new LinkedHashSet<>();
        for (Step step : steps) {
            ids.add(step.id());
        }
        for (Step step : steps) {
            for (String dependency : step.dependsOn()) {
                requireStep(ids, dependency, "step \"" + step.id() + "\" dependsOn");
            }
            if (step.onFail() != null && step.onFail().get("retry") != null) {
                requireStep(ids, step.onFail().get("retry").toString(), "step \"" + step.id() + "\" onFail.retry");
            }
            if (step.when() != null) {
                Matcher matcher = WHEN_STEP.matcher(step.when());
                if (!matcher.find()) {
                    throw new IllegalArgumentException("step \"" + step.id() + "\" has an unparseable when: \""
                            + step.when() + "\" — expected <stepId>.result == 'value'");
                }
                requireStep(ids, matcher.group(1), "step \"" + step.id() + "\" when");
            }
        }
    }

    private static void requireStep(Set<String> ids, String referenced, String what) {
        if (!ids.contains(referenced)) {
            throw new IllegalArgumentException(what + " names step \"" + referenced
                    + "\" which does not exist — one of " + sorted(ids));
        }
    }

    @SuppressWarnings("unchecked")
    private Step stepFromMap(Map<String, Object> map, String defaultId) {
        reject("step", map, STEP_KEYS);
        String id = (String) map.getOrDefault("id", defaultId);
        boolean includePreviousResult = Boolean.TRUE.equals(map.get("includePreviousResult"));
        List<String> dependsOn = strings(map.get("dependsOn"));
        Map<String, Object> onFail = null;
        if (map.get("onFail") instanceof Map<?, ?> raw) {
            onFail = (Map<String, Object>) raw;
            reject("onFail", onFail, ON_FAIL_KEYS);
        }
        TaskConfiguration config = TaskConfiguration.defaults();
        if (map.get("config") instanceof Map<?, ?> rawConfig) {
            Map<String, Object> c = (Map<String, Object>) rawConfig;
            reject("config", c, CONFIG_KEYS);
            config = new TaskConfiguration(
                    (String) c.getOrDefault("model", config.model()),
                    asInt(c.get("timeoutSeconds"), config.timeoutSeconds()),
                    NetworkPolicy.from((String) c.get("network")),
                    asInt(c.get("maxAttempts"), config.maximumAttempts()));
        }
        return new Step(id, (String) map.get("template"), (String) map.get("task"), includePreviousResult,
                dependsOn, onFail, (String) map.get("forEach"), (String) map.get("when"), config);
    }

    /** A bare name or a mapping — both declare a parameter; the mapping also describes it. */
    @SuppressWarnings("unchecked")
    private List<Parameter> parameters(Object value) {
        List<Parameter> out = new ArrayList<>();
        if (!(value instanceof List<?> list)) {
            return out;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> raw) {
                Map<String, Object> p = (Map<String, Object>) raw;
                reject("parameter", p, PARAMETER_KEYS);
                String name = (String) p.get("name");
                if (name == null || name.isBlank()) {
                    throw new IllegalArgumentException("a parameter needs a name");
                }
                String type = (String) p.getOrDefault("type", "text");
                if (!PARAMETER_TYPES.contains(type)) {
                    throw new IllegalArgumentException("parameter \"" + name + "\" has unknown type \"" + type
                            + "\" — one of " + sorted(PARAMETER_TYPES));
                }
                out.add(new Parameter(name, (String) p.getOrDefault("label", ""), type,
                        Boolean.TRUE.equals(p.get("required")),
                        p.get("default") == null ? "" : p.get("default").toString(),
                        (String) p.getOrDefault("help", "")));
            } else if (item != null) {
                out.add(Parameter.named(item.toString()));
            }
        }
        return out;
    }

    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !item.toString().isBlank()) {
                    out.add(item.toString().trim());
                }
            }
        }
        return out;
    }

    private static void reject(String what, Map<String, Object> map, Set<String> allowed) {
        Set<String> unknown = new LinkedHashSet<>();
        for (String key : map.keySet()) {
            if (!allowed.contains(key)) {
                unknown.add(key);
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("unknown " + what + " key(s) " + unknown
                    + " — allowed: " + sorted(allowed));
        }
    }

    private static List<String> sorted(Set<String> values) {
        List<String> out = new ArrayList<>(values);
        out.sort(String::compareTo);
        return out;
    }

    public static int asInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }
}
