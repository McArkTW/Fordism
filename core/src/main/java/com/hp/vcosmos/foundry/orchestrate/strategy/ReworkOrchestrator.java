package com.hp.vcosmos.foundry.orchestrate.strategy;

import com.hp.vcosmos.foundry.model.run.WorkflowRun;
import com.hp.vcosmos.foundry.model.run.WorkflowRunState;
import com.hp.vcosmos.foundry.model.task.Task;
import com.hp.vcosmos.foundry.model.task.TaskSeed;
import com.hp.vcosmos.foundry.model.task.TaskState;
import com.hp.vcosmos.foundry.model.workflow.Step;
import com.hp.vcosmos.foundry.model.workflow.Workflow;
import com.hp.vcosmos.foundry.orchestrate.Engine;
import com.hp.vcosmos.foundry.orchestrate.Orchestrator;
import com.hp.vcosmos.foundry.parse.WorkflowLoader;
import java.util.Optional;

/** Linear, plus: a gate step that fails re-seeds the work step up to maxAttempts, else FAILED. */
public final class ReworkOrchestrator implements Orchestrator {
    public void reconcile(WorkflowRun run, Engine engine) {
        Workflow workflow = engine.workflowFor(run);
        if (run.currentStepIndex >= workflow.steps().size()) {
            engine.finishRun(run, WorkflowRunState.DONE);
            return;
        }
        Step step = workflow.steps().get(run.currentStepIndex);
        Optional<Task> planted = engine.currentTask(run, run.currentStepIndex);
        Optional<Task> previous = run.currentStepIndex == 0
                ? Optional.empty()
                : engine.currentTask(run, run.currentStepIndex - 1);
        if (planted.isPresent() && isStale(planted.get(), previous.orElse(null))) {
            // Re-run it against the reworked input instead of reading last round's verdict. The
            // attempt count carries over, so it reflects how many times this step has been judged.
            engine.seedStep(run, step, planted.get().attempt + 1);
            return;
        }
        if (planted.isEmpty()) {
            engine.seedStep(run, step, 1);
            return;
        }
        Task task = planted.get();
        if (task.state == TaskState.COLLECTED) {
            boolean fail = task.reports("fail");
            if (step.onFail() != null && fail) {
                String retryId = (String) step.onFail().get("retry");
                int max = WorkflowLoader.asInt(step.onFail().get("maxAttempts"), 3);
                int retryIndex = Engine.indexOfStep(workflow, retryId);
                Optional<Task> retry = engine.currentTask(run, retryIndex);
                int nextAttempt = retry.map(t -> t.attempt).orElse(1) + 1;
                if (nextAttempt > max) {
                    // Spent, not stuck. Nothing asked anything and there is no task to re-arm, so
                    // ASKED would advertise an action nobody can take — the run would sit in an
                    // inbox that cannot help it. Seen as atc-job-debug run 1c532485.
                    engine.finishRun(run, WorkflowRunState.FAILED);
                    return;
                }
                // The rework re-reads its own last workspace, not the gate's — it is continuing its
                // own work, so seedStep's "the step before me" is the wrong input here.
                Step retryStep = workflow.steps().get(retryIndex);
                engine.seedTask(run, TaskSeed.of(retryIndex, retryStep, run.parameterValues,
                        retry.map(t -> t.workspacePath).orElse(null), nextAttempt));
                run.currentStepIndex = retryIndex;
                engine.saveRun(run);
            } else {
                run.currentStepIndex++;
                engine.saveRun(run);
            }
        } else if (task.state == TaskState.FAILED || task.state == TaskState.REAPED) {
            engine.finishRun(run, WorkflowRunState.FAILED);
        }
    }

    /**
     * True when this task judged an input that has since been reworked.
     *
     * <p>A rework rewinds the run to an earlier step. Every step after it already has a task, and
     * the orchestrator only seeds where there is none — so on the way back the gate's OLD verdict is
     * read again, "fail" is found again, and another rework fires without the retry ever being
     * looked at. The loop is not a loop: the retry runs, its work is never judged, and the run
     * burns its attempts and escalates no matter how good the rework was.
     *
     * <p>A task is stale when the step before it produced a NEWER task than the one it read.
     * In ordinary forward flow that cannot happen — a step is always seeded after its predecessor.
     */
    private boolean isStale(Task task, Task previous) {
        return previous != null && previous.createdAt > task.createdAt;
    }
}
