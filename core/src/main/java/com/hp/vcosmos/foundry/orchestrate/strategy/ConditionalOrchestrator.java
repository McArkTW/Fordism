package com.hp.vcosmos.foundry.orchestrate.strategy;

import com.hp.vcosmos.foundry.model.run.WorkflowRun;
import com.hp.vcosmos.foundry.model.run.WorkflowRunState;
import com.hp.vcosmos.foundry.model.task.Task;
import com.hp.vcosmos.foundry.model.workflow.Step;
import com.hp.vcosmos.foundry.model.workflow.Workflow;
import com.hp.vcosmos.foundry.orchestrate.Engine;
import com.hp.vcosmos.foundry.orchestrate.Orchestrator;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Linear, but skip steps whose `when` predicate is false. Supports: {@code <stepId>.result == 'value'}. */
public final class ConditionalOrchestrator implements Orchestrator {
    public void reconcile(WorkflowRun run, Engine engine) {
        Workflow workflow = engine.workflowFor(run);
        if (run.currentStepIndex >= workflow.steps().size()) {
            engine.finishRun(run, WorkflowRunState.DONE);
            return;
        }
        Step step = workflow.steps().get(run.currentStepIndex);
        if (step.when() != null && !evaluate(step.when(), run, engine)) {
            run.currentStepIndex++;
            engine.saveRun(run);
            return;
        }
        Optional<Task> planted = engine.currentTask(run, run.currentStepIndex);
        if (planted.isEmpty()) {
            engine.seedStep(run, step, 1);
            return;
        }
        switch (planted.get().state) {
            case COLLECTED -> { run.currentStepIndex++; engine.saveRun(run); }
            case FAILED, REAPED, ASKED -> engine.finishRun(run, WorkflowRunState.FAILED);
            default -> { /* wait */ }
        }
    }

    private boolean evaluate(String when, WorkflowRun run, Engine engine) {
        Workflow workflow = engine.workflowFor(run);
        Matcher matcher = Pattern.compile("(\\w[\\w-]*)\\.result\\s*==\\s*'([^']*)'").matcher(when);
        if (!matcher.find()) {
            // Returning true made a typo'd predicate RUN the step it was written to guard — the one
            // outcome its author certainly did not want, and silently.
            throw new IllegalArgumentException("cannot parse when: \"" + when
                    + "\" — expected <stepId>.result == 'value'");
        }
        String stepId = matcher.group(1);
        int index = Engine.indexOfStep(workflow, stepId);
        if (index < 0) {
            throw new IllegalArgumentException("when: names unknown step \"" + stepId + "\"");
        }
        return engine.currentTask(run, index)
                .filter(task -> task.reports(matcher.group(2)))
                .isPresent();
    }
}
