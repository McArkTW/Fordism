package com.hp.vcosmos.foundry.field;

import com.hp.vcosmos.foundry.launch.ContainerLauncher;
import com.hp.vcosmos.foundry.model.task.Task;
import com.hp.vcosmos.foundry.model.task.TaskState;
import com.hp.vcosmos.foundry.store.TaskRepository;
import com.hp.vcosmos.foundry.store.WorkflowRunRepository;
import java.util.List;
import org.tinylog.Logger;

/**
 * Clears the field when a run is over: no task may still be live under a run that has ended.
 *
 * <p>This is what makes abandoning a run mean anything. The other three sweeps are keyed on TASK
 * state, not run state — the Dispatcher launches any PENDING task, the Collector harvests any
 * RUNNING one, and the Reaper restarts one whose container died. So marking a run ABANDONED stops
 * the orchestrator advancing it and nothing else: the container keeps running, and killing it by
 * hand would just make the Reaper decide the task rotted and queue it again.
 *
 * <p>Hence the order below — mark the task terminal FIRST, then kill. Once it is out of
 * {@code byState(RUNNING)} the other sweeps cannot see it, so a late {@code result.json} cannot
 * resurrect it and no retry fires.
 *
 * <p>It also repairs a leak that predates abandonment: an orchestrator that fails a run while
 * another of its steps is still running left that container alive, holding a launcher slot, with
 * nothing in the system ever collecting it.
 */
public final class OrphanCuller {
    /** The only states a task can be in and still cost something. */
    private static final List<TaskState> LIVE = List.of(TaskState.PENDING, TaskState.RUNNING);

    private final TaskRepository tasks;
    private final WorkflowRunRepository runs;
    private final ContainerLauncher launcher;

    public OrphanCuller(TaskRepository tasks, WorkflowRunRepository runs, ContainerLauncher launcher) {
        this.tasks = tasks;
        this.runs = runs;
        this.launcher = launcher;
    }

    public void sweep() {
        for (TaskState live : LIVE) {
            for (Task task : tasks.byState(live)) {
                runs.find(task.runId)
                        .filter(run -> run.state.isTerminal())
                        .ifPresent(run -> cull(task, run.state.name().toLowerCase()));
            }
        }
    }

    private void cull(Task task, String because) {
        task.state = TaskState.REAPED;
        task.error = "run " + because + " — task culled before it finished";
        task.finishedAt = System.currentTimeMillis();
        tasks.save(task);           // first, so no other sweep can still see it
        launcher.kill(task.containerId);
        launcher.remove(task.containerId);
        Logger.info("cull task {} — its run is {}", task.id, because);
    }
}
