package com.hp.vcosmos.foundry.field;

import com.hp.vcosmos.foundry.launch.ContainerLauncher;
import com.hp.vcosmos.foundry.model.task.Task;
import com.hp.vcosmos.foundry.model.task.TaskResult;
import com.hp.vcosmos.foundry.model.task.TaskState;
import com.hp.vcosmos.foundry.store.TaskRepository;
import com.hp.vcosmos.foundry.workspace.TaskResults;
import java.util.Optional;
import org.tinylog.Logger;

/** Harvests RUNNING tasks that reported finished; heartbeats the rest. */
public final class Collector {
    private final TaskRepository tasks;
    private final TaskResults results;
    private final ContainerLauncher launcher;

    public Collector(TaskRepository tasks, TaskResults results, ContainerLauncher launcher) {
        this.tasks = tasks;
        this.results = results;
        this.launcher = launcher;
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
        }
    }
}
