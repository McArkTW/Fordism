package com.hp.vcosmos.foundry.orchestrate.strategy;

import com.hp.vcosmos.foundry.model.run.WorkflowRun;
import com.hp.vcosmos.foundry.model.run.WorkflowRunState;
import com.hp.vcosmos.foundry.model.task.Task;
import com.hp.vcosmos.foundry.model.workflow.Step;
import com.hp.vcosmos.foundry.model.workflow.Workflow;
import com.hp.vcosmos.foundry.orchestrate.Engine;
import com.hp.vcosmos.foundry.orchestrate.Orchestrator;
import java.util.Optional;

/** Steps in order, one at a time; stage previous result when requested. */
public final class LinearOrchestrator implements Orchestrator {
    public void reconcile(WorkflowRun run, Engine engine) {
        Workflow workflow = engine.workflowFor(run);
        if (run.currentStepIndex >= workflow.steps().size()) {
            engine.finishRun(run, WorkflowRunState.DONE);
            return;
        }
        Step step = workflow.steps().get(run.currentStepIndex);
        Optional<Task> planted = engine.currentTask(run, run.currentStepIndex);
        if (planted.isEmpty()) {
            engine.seedStep(run, step, 1);
            return;
        }
        switch (planted.get().state) {
            case COLLECTED -> { run.currentStepIndex++; engine.saveRun(run); }
            case FAILED, REAPED, ASKED -> engine.finishRun(run, WorkflowRunState.FAILED);
            default -> { /* PENDING / RUNNING: wait */ }
        }
    }
}
