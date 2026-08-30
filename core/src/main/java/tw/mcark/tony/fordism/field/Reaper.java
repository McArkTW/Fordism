package tw.mcark.tony.fordism.field;

import tw.mcark.tony.fordism.config.FordismConfiguration;
import tw.mcark.tony.fordism.launch.ContainerLauncher;
import tw.mcark.tony.fordism.model.task.ReportedState;
import tw.mcark.tony.fordism.model.task.Task;
import tw.mcark.tony.fordism.model.task.TaskState;
import tw.mcark.tony.fordism.store.TaskRepository;
import tw.mcark.tony.fordism.workspace.TaskResults;
import org.tinylog.Logger;

/** Culls rotten RUNNING tasks — over timeout, heartbeat-stale, or dead container. */
public final class Reaper {
    private final TaskRepository tasks;
    private final TaskResults results;
    private final ContainerLauncher launcher;
    private final CullPolicy policy;
    private final FordismConfiguration configuration;

    public Reaper(TaskRepository tasks, TaskResults results, ContainerLauncher launcher,
                  CullPolicy policy, FordismConfiguration configuration) {
        this.tasks = tasks;
        this.results = results;
        this.launcher = launcher;
        this.policy = policy;
        this.configuration = configuration;
    }

    public void sweep() {
        long now = System.currentTimeMillis();
        for (Task task : tasks.byState(TaskState.RUNNING)) {
            if (results.read(task).filter(result -> result.state() == ReportedState.FINISHED).isPresent()) {
                continue;   // Collector takes it
            }
            boolean timeout = now - task.dispatchedAt > task.config.timeoutSeconds() * 1000L;
            boolean stale = now - task.lastHeartbeatAt > configuration.heartbeatTimeoutMillis;
            boolean dead = false;
            try {
                dead = task.containerId != null && !launcher.isRunning(task.containerId);
            } catch (Exception e) {
                // treat unknown as alive
            }
            if (timeout || stale || dead) {
                CullDecision decision = policy.decide(task);
                String rot = timeout ? "timeout" : stale ? "stale" : "dead";
                Logger.warn("reap task {} rotten (timeout={} stale={} dead={}) decision={}",
                        task.id, timeout, stale, dead, decision);
                launcher.kill(task.containerId);
                launcher.remove(task.containerId);
                if (decision == CullDecision.ESCALATE) {
                    task.state = TaskState.REAPED;
                    task.error = "reaped: " + rot + " (attempt " + task.attempt
                            + " of " + task.config.maximumAttempts() + ")";
                    task.finishedAt = now;
                } else {
                    // The decision used to be computed only to be printed, so a container that died
                    // for any reason ended its run — no crashed agent was ever retried and
                    // TaskConfiguration.maxAttempts did nothing. Put it back in the queue instead;
                    // CullPolicy escalates once the attempts are spent, so this is bounded.
                    task.state = TaskState.PENDING;
                    task.attempt = task.attempt + 1;
                    task.containerId = null;
                    task.error = null;
                    task.finishedAt = 0;
                    task.lastHeartbeatAt = now;
                    Logger.info("retry task {} after {} — attempt {} of {}",
                            task.id, rot, task.attempt, task.config.maximumAttempts());
                }
                tasks.save(task);
            }
        }
    }
}
