package com.hp.vcosmos.foundry.model.task;

import com.hp.vcosmos.foundry.model.workflow.Step;
import java.util.Map;

/**
 * A step, resolved into everything the engine needs to plant one task from it.
 *
 * <p>This exists because every orchestrator was assembling the same nine positional arguments by
 * hand — substitute the template, substitute the task text, find the previous workspace, pass the
 * step's config, pass the attempt — and one transposed pair would have been invisible at the call
 * site. {@link #of} is that assembly, written once.
 */
public record TaskSeed(int stepIndex, String template, String taskText, boolean includePreviousResult,
                       String previousWorkspace, TaskConfiguration config, int attempt) {

    /** Resolve a workflow step against a run's parameter values. */
    public static TaskSeed of(int stepIndex, Step step, Map<String, String> values,
                              String previousWorkspace, int attempt) {
        return new TaskSeed(stepIndex,
                substitute(step.template(), values),
                substitute(step.task(), values),
                step.includePreviousResult(),
                previousWorkspace,
                step.config(),
                attempt);
    }

    /**
     * The same seed, but staging the previous workspace whatever the step declared — for a loop
     * whose every pass after the first continues from the one before it.
     */
    public TaskSeed includingPreviousResult() {
        return new TaskSeed(stepIndex, template, taskText, true, previousWorkspace, config, attempt);
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
