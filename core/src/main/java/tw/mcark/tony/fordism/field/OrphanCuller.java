package tw.mcark.tony.fordism.field;

import tw.mcark.tony.fordism.launch.ContainerLauncher;
import tw.mcark.tony.fordism.model.run.WorkflowRun;
import tw.mcark.tony.fordism.model.task.Task;
import tw.mcark.tony.fordism.model.task.TaskState;
import tw.mcark.tony.fordism.store.TaskRepository;
import tw.mcark.tony.fordism.store.WorkflowRunRepository;
import tw.mcark.tony.fordism.workspace.ArtifactStore;
import tw.mcark.tony.fordism.workspace.CredentialScrub;
import java.io.IOException;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    private final CredentialScrub scrub;
    private final ArtifactRetention retention;

    public OrphanCuller(TaskRepository tasks, WorkflowRunRepository runs, ContainerLauncher launcher,
            CredentialScrub scrub) {
        this(tasks, runs, launcher, scrub, ArtifactRetention.keepForever());
    }

    public OrphanCuller(TaskRepository tasks, WorkflowRunRepository runs, ContainerLauncher launcher,
            CredentialScrub scrub, ArtifactRetention retention) {
        this.tasks = tasks;
        this.runs = runs;
        this.launcher = launcher;
        this.scrub = scrub;
        this.retention = retention;
    }

    public void sweep() {
        for (TaskState live : LIVE) {
            for (Task task : tasks.byState(live)) {
                runs.find(task.runId)
                        .filter(run -> run.state.isTerminal())
                        .ifPresent(run -> cull(task, run.state.name().toLowerCase()));
            }
        }
        if (retention.culls()) {
            cullArtifacts(System.currentTimeMillis());
        }
    }

    /**
     * Remove the artifact of every run that ended longer ago than the retention window.
     *
     * <p>Except one that a run still going was started from. The copy is made when that run stages
     * its first task, which may be well after it was accepted — a run queued behind a full launcher,
     * or parked on a question before its first step — so culling the source in between would fail a
     * run that had been promised a product to continue.
     */
    void cullArtifacts(long now) {
        List<WorkflowRun> all = runs.all();
        Set<String> held = new HashSet<>();
        for (WorkflowRun run : all) {
            if (!run.state.isTerminal() && run.artifactFrom != null) {
                held.add(run.artifactFrom);
            }
        }
        ArtifactStore store = retention.store();
        for (WorkflowRun run : all) {
            long ended = run.endedAt > 0 ? run.endedAt : run.createdAt;
            boolean expired = run.state.isTerminal() && now - ended >= retention.window().toMillis();
            if (!expired || held.contains(run.id) || !store.exists(run.id)) {
                continue;
            }
            try {
                store.delete(run.id);
                Logger.info("cull artifact of run {} — ended {} ago", run.id, Duration.ofMillis(now - ended));
            } catch (IOException e) {
                Logger.warn("could not cull the artifact of run {}: {}", run.id, e.getMessage());
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
        scrub.scrub(task);
        Logger.info("cull task {} — its run is {}", task.id, because);
    }
}
