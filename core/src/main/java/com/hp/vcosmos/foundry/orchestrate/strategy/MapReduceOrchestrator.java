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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Fan a step out over a list parameter (one task per item), then a reduce step. */
public final class MapReduceOrchestrator implements Orchestrator {
    public void reconcile(WorkflowRun run, Engine engine) {
        Workflow workflow = engine.workflowFor(run);
        Step mapStep = workflow.steps().get(0);
        Step reduceStep = workflow.steps().size() > 1 ? workflow.steps().get(1) : null;
        String listName = mapStep.forEach() == null ? "" : mapStep.forEach().replaceAll("[${}]", "");
        List<String> items = engine.paramList(run, listName);
        boolean allMapped = true;
        for (int k = 0; k < items.size(); k++) {
            Optional<Task> mapTask = engine.currentTask(run, k);
            if (mapTask.filter(task -> task.state == TaskState.FAILED || task.state == TaskState.REAPED).isPresent()) {
                engine.finishRun(run, WorkflowRunState.FAILED);
                return;
            }
            if (mapTask.isEmpty()) {
                // Each map task sees its own ${item}; nothing is fed forward between them.
                Map<String, String> values = new HashMap<>(run.parameterValues);
                values.put("item", items.get(k));
                engine.seedTask(run, TaskSeed.of(k, mapStep, values, null, 1));
            }
            if (mapTask.filter(task -> task.state == TaskState.COLLECTED).isEmpty()) {
                allMapped = false;
            }
        }
        if (!allMapped) {
            return;
        }
        if (reduceStep == null) {
            engine.finishRun(run, WorkflowRunState.DONE);
            return;
        }
        int reduceIndex = items.size();
        Optional<Task> reduceTask = engine.currentTask(run, reduceIndex);
        if (reduceTask.isEmpty()) {
            // The reduce step reads the FIRST map task's workspace, not the one before it in index
            // order — which is the map fan-out's last item, and not what "previous" means here.
            String firstMapWorkspace = engine.currentTask(run, 0).map(first -> first.workspacePath).orElse(null);
            engine.seedTask(run, TaskSeed.of(reduceIndex, reduceStep, run.parameterValues, firstMapWorkspace, 1));
            return;
        }
        switch (reduceTask.get().state) {
            case COLLECTED -> engine.finishRun(run, WorkflowRunState.DONE);
            case FAILED, REAPED -> engine.finishRun(run, WorkflowRunState.FAILED);
            default -> { /* wait */ }
        }
    }
}
