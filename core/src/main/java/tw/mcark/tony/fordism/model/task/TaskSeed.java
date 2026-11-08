package tw.mcark.tony.fordism.model.task;

import tw.mcark.tony.fordism.model.workflow.ArtifactAccess;
import tw.mcark.tony.fordism.model.workflow.Step;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A step, resolved into everything the engine needs to plant one task from it.
 *
 * <p>This exists because every orchestrator was assembling the same nine positional arguments by
 * hand — substitute the template, substitute the task text, find the previous workspace, pass the
 * step's config, pass the attempt — and one transposed pair would have been invisible at the call
 * site. {@link #of} is that assembly, written once.
 *
 * <p>{@code artifact} rides along from the step, so the task the engine plants mounts the run's
 * artifact the way the loader decided — read-only where a sibling could be running beside it.
 *
 * <p>{@code continues} rides along the same way: the engine plants such a seed as the continued
 * step's agent — its session id, resume mode — instead of a new one.
 */
public record TaskSeed(int stepIndex, String template, String taskText, boolean includePreviousResult,
                       String previousWorkspace, TaskConfiguration config, int attempt,
                       Map<String, String> fanIn, ArtifactAccess artifact, boolean continues) {

    public TaskSeed {
        artifact = artifact == null ? ArtifactAccess.WRITE : artifact;
    }

    public TaskSeed(int stepIndex, String template, String taskText, boolean includePreviousResult,
                    String previousWorkspace, TaskConfiguration config, int attempt) {
        this(stepIndex, template, taskText, includePreviousResult, previousWorkspace, config, attempt, Map.of(),
                ArtifactAccess.WRITE, false);
    }

    /**
     * The same seed, staging several predecessors instead of one: a reduce over every map task, a
     * join over every dependency. Each lands in its own {@code task/previous-result/<label>/}, in
     * this order, so none of them is silently dropped in favour of the first.
     */
    public TaskSeed withFanIn(Map<String, String> workspacesByLabel) {
        return new TaskSeed(stepIndex, template, taskText, includePreviousResult, previousWorkspace, config,
                attempt, new LinkedHashMap<>(workspacesByLabel), artifact, continues);
    }

    /** Resolve a workflow step against a run's parameter values. */
    public static TaskSeed of(int stepIndex, Step step, Map<String, String> values,
                              String previousWorkspace, int attempt) {
        return new TaskSeed(stepIndex,
                substitute(step.template(), values),
                substitute(step.task(), values),
                step.includePreviousResult(),
                previousWorkspace,
                step.config(),
                attempt,
                Map.of(),
                step.artifact(),
                step.continues());
    }

    /**
     * The same seed, but staging the previous workspace whatever the step declared — for a loop
     * whose every pass after the first continues from the one before it.
     */
    public TaskSeed includingPreviousResult() {
        return new TaskSeed(stepIndex, template, taskText, true, previousWorkspace, config, attempt, fanIn, artifact,
                continues);
    }

    /**
     * {@code ${name}} → its parameter value.
     *
     * <p>A parameter the run supplied as null becomes empty. A placeholder the run has no parameter
     * for at all is left standing, so it reaches the agent as the literal {@code ${name}} — ugly in
     * the prompt, but visible in the transcript, which is how you find out the run was started
     * without it. Emptying it would hide that.
     */
    public static String substitute(String text, Map<String, String> values) {
        if (text == null) {
            return null;
        }
        String out = text;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            out = out.replace("${" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
        }
        return out;
    }
}
