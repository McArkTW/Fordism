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
import java.util.Optional;

/** Seed every step whose dependsOn are all COLLECTED; fan-out + join; done when all collected. */
public final class GraphOrchestrator implements Orchestrator {
    public void reconcile(WorkflowRun run, Engine engine) {
        Workflow workflow = engine.workflowFor(run);
        boolean allCollected = true;
        for (int i = 0; i < workflow.steps().size(); i++) {
            Step step = workflow.steps().get(i);
            Optional<Task> planted = engine.currentTask(run, i);
            if (planted.filter(GraphOrchestrator::rotten).isPresent()) {
                engine.finishRun(run, WorkflowRunState.FAILED);
                return;
            }
            boolean depsReady = true;
            String firstDepWorkspace = null;
            for (String dependency : step.dependsOn()) {
                // The loader guarantees the id resolves, so an absent task here means "not seeded
                // yet", never "misspelled".
                Optional<Task> dependencyTask = engine.currentTask(run, Engine.indexOfStep(workflow, dependency))
                        .filter(candidate -> candidate.state == TaskState.COLLECTED);
                if (dependencyTask.isEmpty()) {
                    depsReady = false;
                } else if (firstDepWorkspace == null) {
                    firstDepWorkspace = dependencyTask.get().workspacePath;
                }
            }
            if (planted.isEmpty() && depsReady) {
                // A graph step's "previous" is its first collected dependency, not the step before
                // it in the list — so this one seeds directly rather than through seedStep.
                engine.seedTask(run, TaskSeed.of(i, step, run.parameterValues, firstDepWorkspace, 1));
            }
            if (planted.filter(candidate -> candidate.state == TaskState.COLLECTED).isEmpty()) {
                allCollected = false;
            }
        }
        if (allCollected) {
            engine.finishRun(run, WorkflowRunState.DONE);
        }
    }

    private static boolean rotten(Task task) {
        return task.state == TaskState.FAILED || task.state == TaskState.REAPED;
    }
}
