package tw.mcark.tony.fordism.field;

import tw.mcark.tony.fordism.launch.ContainerLauncher;
import tw.mcark.tony.fordism.model.task.Task;
import tw.mcark.tony.fordism.model.task.TaskResult;
import tw.mcark.tony.fordism.model.task.TaskState;
import tw.mcark.tony.fordism.store.TaskRepository;
import tw.mcark.tony.fordism.workspace.CredentialScrub;
import tw.mcark.tony.fordism.workspace.TaskResults;
import java.util.Optional;
import org.tinylog.Logger;

/** Harvests RUNNING tasks that reported finished; heartbeats the rest. */
public final class Collector {
    private final TaskRepository tasks;
    private final TaskResults results;
    private final ContainerLauncher launcher;
    private final CredentialScrub scrub;

    public Collector(TaskRepository tasks, TaskResults results, ContainerLauncher launcher,
            CredentialScrub scrub) {
        this.tasks = tasks;
        this.results = results;
        this.launcher = launcher;
        this.scrub = scrub;
    }

    public void sweep() {
        for (Task task : tasks.byState(TaskState.RUNNING)) {
            Optional<TaskResult> reported = results.read(task);
            if (reported.isEmpty()) {
                continue;   // nothing written yet — the agent is still working
            }
            TaskResult result = reported.get();
            if (!result.state().isTerminal()) {
                task.lastHeartbeatAt = System.currentTimeMillis();
                tasks.save(task);
                continue;
            }
            task.summary = result.summary();
            task.finishedAt = System.currentTimeMillis();
            switch (result.state()) {
                case FINISHED -> {
                    task.state = TaskState.COLLECTED;
                    task.verdict = result.verdict();
                    // Carried onto the task, not acted on here: the Collector is a sensor. Whether
                    // these runs are started at all is the orchestrator's decision, and only the
                    // reconciler makes it.
                    task.childRuns = result.childRuns();
                    Logger.info("collect task {} COLLECTED :: {}", task.id, task.summary);
                }
                case FAILED -> {
                    task.state = TaskState.FAILED;
                    task.error = "agent reported failed";
                    Logger.warn("collect task {} agent FAILED", task.id);
                }
                case ASKED -> {
                    task.state = TaskState.ASKED;
                    task.question = result.question();
                    task.secretsRequested = result.secrets();
                    Logger.info("collect task {} ASKED :: {}", task.id, task.question);
                }
                default -> throw new IllegalStateException("unhandled terminal state " + result.state());
            }
            tasks.save(task);
            launcher.remove(task.containerId);
            // Including on ASKED: the container that wrote the file is gone either way, and the
            // fresh one that resumes the session writes it again from its own environment.
            scrub.scrub(task);
        }
    }
}
