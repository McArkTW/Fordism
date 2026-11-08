package tw.mcark.tony.fordism.parse;

import tw.mcark.tony.fordism.model.task.NetworkPolicy;
import tw.mcark.tony.fordism.model.task.TaskConfiguration;
import tw.mcark.tony.fordism.model.workflow.ArtifactAccess;
import tw.mcark.tony.fordism.model.workflow.OnFail;
import tw.mcark.tony.fordism.model.workflow.Parameter;
import tw.mcark.tony.fordism.model.workflow.ReworkMode;
import tw.mcark.tony.fordism.model.workflow.Step;
import tw.mcark.tony.fordism.model.workflow.Strategy;
import tw.mcark.tony.fordism.model.workflow.Workflow;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
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
            Set.of("id", "template", "task", "includePreviousResult", "dependsOn", "onFail", "forEach", "when", "config",
                    "continues");
    private static final Set<String> CONFIG_KEYS = Set.of("model", "timeoutSeconds", "network", "maxAttempts");
    private static final Set<String> ON_FAIL_KEYS = Set.of("retry", "maxAttempts", "mode");
    private static final Set<String> PARAMETER_KEYS = Set.of("name", "label", "type", "required", "default", "help");
    private static final Set<String> PARAMETER_TYPES = Set.of("text", "textarea", "number");

    /** A run-request field, not a parameter: the run whose artifact a new run continues from. */
    public static final String RESERVED_PARAMETER = "artifactFrom";

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
        for (Parameter parameter : parameters) {
            // The run request reads this field as the run to continue from and takes it out of the
            // values, so a parameter by this name could be declared but never receive a value.
            if (RESERVED_PARAMETER.equals(parameter.name())) {
                throw new IllegalArgumentException("parameter \"" + RESERVED_PARAMETER
                        + "\" is reserved: on a run request it names the run whose artifact the new run starts from"
                        + " — rename the parameter");
            }
        }
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
        steps = withArtifactAccess(strategy, steps);
        Workflow workflow = new Workflow(name, description, strategy, tags, parameters, steps, generator, maxIterations);
        validateContinuation(workflow);
        return workflow;
    }

    /**
     * Every {@code continues} must name exactly one step whose agent it can take over.
     *
     * <p>A continuing step runs in its predecessor's workspace, in its predecessor's session. That
     * needs one predecessor, and it needs the bench to be free: so not the first step (nothing to
     * continue), not a {@code map-reduce} step (the map is many agents, and the reduce would have
     * many to choose from), not a {@code graph} step with no dependency or several, and not the
     * reconciler's generator, whose iterations are each a new agent reading the last one's result.
     * Two steps continuing the same one would be two agents on one bench, which is the merge core
     * refuses everywhere else too.
     */
    private static void validateContinuation(Workflow workflow) {
        if (workflow.generator() != null && workflow.generator().continues()) {
            throw new IllegalArgumentException("the generator cannot continue: a reconciler has no step before it,"
                    + " and each iteration is a new agent that reads the last one's result");
        }
        Map<Integer, String> continuedBy = new LinkedHashMap<>();
        List<Step> steps = workflow.steps();
        for (int index = 0; index < steps.size(); index++) {
            Step step = steps.get(index);
            if (!step.continues()) {
                continue;
            }
            String what = "step \"" + step.id() + "\" continues";
            switch (workflow.strategy()) {
                case MAP_REDUCE -> throw new IllegalArgumentException(what + ", but a map-reduce step cannot:"
                        + " the map is one agent per item, so there is no single agent to continue or be continued");
                case RECONCILER -> throw new IllegalArgumentException(what + ", but a reconciler runs only its"
                        + " generator, which cannot continue");
                case GRAPH -> {
                    if (step.dependsOn().isEmpty()) {
                        throw new IllegalArgumentException(what + " but has no dependsOn: a graph step continues"
                                + " its one dependency, and this one has none");
                    }
                    if (step.dependsOn().size() > 1) {
                        throw new IllegalArgumentException(what + " but dependsOn " + step.dependsOn()
                                + ": a graph step continues its one dependency, and a join has several agents"
                                + " to choose from");
                    }
                }
                default -> {
                    if (index == 0) {
                        throw new IllegalArgumentException(what + ", but it is the first step: there is no"
                                + " agent before it to continue");
                    }
                }
            }
            OptionalInt continued = workflow.continuedStep(index);
            if (continued.isPresent()) {
                String earlier = continuedBy.putIfAbsent(continued.getAsInt(), step.id());
                if (earlier != null) {
                    throw new IllegalArgumentException("steps \"" + earlier + "\" and \"" + step.id()
                            + "\" both continue \"" + steps.get(continued.getAsInt()).id()
                            + "\": one agent's workspace cannot hold two agents at once");
                }
            }
        }
    }

    /**
     * A continuing step must run the same Agent Profile and tool as the step it continues: the
     * session it resumes belongs to one CLI, and another would start a silent new one in its store.
     *
     * <p>Run twice. At load time, with no parameter values, a template that names a parameter or
     * does not exist yet cannot be resolved, and the pair is left for later rather than refused on a
     * guess. At run creation the values are in and the templates are what they are: one that still
     * does not exist runs bare on the default backend, and that is what is compared.
     *
     * @param values the run's parameter values, or null at load time
     */
    public static void checkContinuedAgents(Workflow workflow, StepAgents agents, Map<String, String> values) {
        for (int index = 0; index < workflow.steps().size(); index++) {
            OptionalInt continued = workflow.continuedStep(index);
            if (continued.isEmpty()) {
                continue;
            }
            Step step = workflow.steps().get(index);
            Step earlier = workflow.steps().get(continued.getAsInt());
            Optional<StepAgent> mine = agentOf(step, agents, values);
            Optional<StepAgent> theirs = agentOf(earlier, agents, values);
            if (mine.isEmpty() || theirs.isEmpty()) {
                continue;   // not resolvable yet; run creation checks again
            }
            if (!mine.get().equals(theirs.get())) {
                throw new IllegalArgumentException("step \"" + step.id() + "\" continues \"" + earlier.id()
                        + "\" but runs as " + describe(mine.get()) + " where \"" + earlier.id() + "\" runs as "
                        + describe(theirs.get()) + ": a session belongs to one agent profile and tool,"
                        + " so give both templates the same agent profile");
            }
        }
    }

    private static Optional<StepAgent> agentOf(Step step, StepAgents agents, Map<String, String> values) {
        if (values == null) {
            if (step.template() != null && step.template().contains("${")) {
                return Optional.empty();
            }
            return agents.of(step.template());
        }
        String template = step.template();
        if (template != null) {
            for (Map.Entry<String, String> entry : values.entrySet()) {
                template = template.replace("${" + entry.getKey() + "}",
                        entry.getValue() == null ? "" : entry.getValue());
            }
        }
        return agents.of(template).or(agents::bare);
    }

    private static String describe(StepAgent agent) {
        return (agent.profile().isBlank() ? "the default profile" : "profile \"" + agent.profile() + "\"")
                + " (" + agent.tool().wireName() + ")";
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
            if (step.onFail() != null && step.onFail().retryStepId() != null) {
                requireStep(ids, step.onFail().retryStepId(), "step \"" + step.id() + "\" onFail.retry");
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


    /**
     * Each step with the artifact access the workflow's shape allows it.
     *
     * <p>The rule is one sentence: a step may write {@code artifact/} only when nothing else can run
     * beside it. Core never merges two trees, so the alternative to deciding this here is two agents
     * writing one working tree at once, and whichever finished last silently winning. A read-only
     * step still sees the product; it hands its work back through {@code result/}, and the reduce or
     * join after it — which does get the tree writable — merges on purpose.
     *
     * <ul>
     *   <li>{@code linear}, {@code rework}, {@code conditional}: one step at a time, so every step writes.
     *   <li>{@code map-reduce}: the map items run together, so they read; the reduce writes.
     *   <li>{@code graph}: a step writes only if every other step is upstream or downstream of it —
     *       ordered relative to it through {@code dependsOn}. Anything else could share its moment.
     *   <li>{@code reconciler}: the generator writes. Its child runs are other runs, and get a copy
     *       of this one's artifact through {@code artifactFrom}, not this tree.
     * </ul>
     */
    static List<Step> withArtifactAccess(Strategy strategy, List<Step> steps) {
        Map<String, Set<String>> upstream = strategy == Strategy.GRAPH ? upstream(steps) : Map.of();
        List<Step> out = new ArrayList<>();
        for (int index = 0; index < steps.size(); index++) {
            Step step = steps.get(index);
            ArtifactAccess access = switch (strategy) {
                case LINEAR, REWORK, CONDITIONAL, RECONCILER -> ArtifactAccess.WRITE;
                case MAP_REDUCE -> index == 0 ? ArtifactAccess.READ_ONLY : ArtifactAccess.WRITE;
                case GRAPH -> orderedAgainstEveryOther(step, steps, upstream)
                        ? ArtifactAccess.WRITE : ArtifactAccess.READ_ONLY;
            };
            out.add(step.withArtifact(access));
        }
        return out;
    }

    /** Whether every other step must finish before this one starts, or cannot start until it has. */
    private static boolean orderedAgainstEveryOther(Step step, List<Step> steps, Map<String, Set<String>> upstream) {
        for (Step other : steps) {
            if (other == step) {
                continue;
            }
            boolean before = upstream.get(step.id()).contains(other.id());
            boolean after = upstream.get(other.id()).contains(step.id());
            if (!before && !after) {
                return false;
            }
        }
        return true;
    }

    /**
     * Every step's transitive {@code dependsOn}: what must collect, directly or not, before it runs.
     * Cycle-safe — a cycle is a graph that never finishes, which is not this method's to report.
     */
    private static Map<String, Set<String>> upstream(List<Step> steps) {
        Map<String, List<String>> direct = new LinkedHashMap<>();
        for (Step step : steps) {
            direct.put(step.id(), step.dependsOn());
        }
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (Step step : steps) {
            Set<String> seen = new LinkedHashSet<>();
            Deque<String> pending = new ArrayDeque<>(step.dependsOn());
            while (!pending.isEmpty()) {
                String next = pending.pop();
                if (seen.add(next)) {
                    pending.addAll(direct.getOrDefault(next, List.of()));
                }
            }
            out.put(step.id(), seen);
        }
        return out;
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
        OnFail onFail = null;
        if (map.get("onFail") instanceof Map<?, ?> raw) {
            Map<String, Object> declared = (Map<String, Object>) raw;
            reject("onFail", declared, ON_FAIL_KEYS);
            // maxAttempts defaults here rather than in the orchestrator, so the bound a workflow
            // will actually run under is visible in the parsed shape the editor draws.
            onFail = new OnFail(text(declared.get("retry")),
                    asInt(declared.get("maxAttempts"), OnFail.DEFAULT_MAXIMUM_ATTEMPTS),
                    ReworkMode.from(text(declared.get("mode"))));
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
                dependsOn, onFail, (String) map.get("forEach"), (String) map.get("when"), config, null,
                Boolean.TRUE.equals(map.get("continues")));
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

    /** A scalar as text; absent stays absent, so a caller can tell "not written" from "written blank". */
    private static String text(Object value) {
        return value == null ? null : value.toString();
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
