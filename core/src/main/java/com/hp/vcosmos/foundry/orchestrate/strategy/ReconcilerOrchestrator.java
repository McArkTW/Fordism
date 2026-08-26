package com.hp.vcosmos.foundry.orchestrate.strategy;

import com.hp.vcosmos.foundry.model.run.WorkflowRun;
import com.hp.vcosmos.foundry.model.run.WorkflowRunState;
import com.hp.vcosmos.foundry.model.task.Task;
import com.hp.vcosmos.foundry.model.task.TaskSeed;
import com.hp.vcosmos.foundry.model.workflow.Step;
import com.hp.vcosmos.foundry.model.workflow.Workflow;
import com.hp.vcosmos.foundry.orchestrate.Engine;
import com.hp.vcosmos.foundry.orchestrate.Orchestrator;
import java.util.Optional;

/** Run the generator each iteration; stop on "done" or maxIterations. (Child-run spawning is the fuller form.) */
public final class ReconcilerOrchestrator implements Orchestrator {
    public void reconcile(WorkflowRun run, Engine engine) {
        Workflow workflow = engine.workflowFor(run);
        Step generator = workflow.generator();
        if (generator == null) {
            engine.finishRun(run, WorkflowRunState.FAILED);
            return;
        }
        Optional<Task> planted = engine.currentTask(run, run.iteration);
        if (planted.isEmpty()) {
            if (run.iteration >= workflow.maxIterations()) {
                // Spent, not stuck — nothing to rescue. See ReworkOrchestrator.
                engine.finishRun(run, WorkflowRunState.FAILED);
                return;
            }
            // Each iteration continues from the last one's workspace, so every pass after the first
            // includes the previous result whatever the generator step declared.
            String previousWorkspace = run.iteration == 0 ? null
                    : engine.currentTask(run, run.iteration - 1).map(previous -> previous.workspacePath).orElse(null);
            TaskSeed seed = TaskSeed.of(run.iteration, generator, run.parameterValues, previousWorkspace, 1);
            engine.seedTask(run, run.iteration > 0 ? seed.includingPreviousResult() : seed);
            return;
        }
        switch (planted.get().state) {
            case COLLECTED -> {
                if (planted.get().reports("done")) {
                    engine.finishRun(run, WorkflowRunState.DONE);
                } else {
                    run.iteration++;
                    engine.saveRun(run);
                }
            }
            case FAILED, REAPED, ASKED -> engine.finishRun(run, WorkflowRunState.FAILED);
            default -> { /* wait */ }
        }
    }
}
