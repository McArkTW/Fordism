package tw.mcark.tony.fordism.orchestrate.strategy;

import tw.mcark.tony.fordism.model.run.WorkflowRun;
import tw.mcark.tony.fordism.model.run.WorkflowRunState;
import tw.mcark.tony.fordism.model.task.Task;
import tw.mcark.tony.fordism.model.workflow.Step;
import tw.mcark.tony.fordism.model.workflow.Workflow;
import tw.mcark.tony.fordism.orchestrate.Engine;
import tw.mcark.tony.fordism.orchestrate.Orchestrator;
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
            case FAILED, REAPED -> engine.finishRun(run, WorkflowRunState.FAILED);
            default -> { /* PENDING / RUNNING: wait. ASKED never reaches here — Engine parks the run. */ }
        }
    }
}
