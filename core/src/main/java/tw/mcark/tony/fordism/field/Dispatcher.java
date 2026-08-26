package tw.mcark.tony.fordism.field;

import tw.mcark.tony.fordism.launch.ContainerLauncher;
import tw.mcark.tony.fordism.model.task.Task;
import tw.mcark.tony.fordism.model.task.TaskConfiguration;
import tw.mcark.tony.fordism.model.task.TaskState;
import tw.mcark.tony.fordism.store.TaskRepository;
import tw.mcark.tony.fordism.workspace.AgentTemplate;
import tw.mcark.tony.fordism.workspace.TemplateStore;
import tw.mcark.tony.fordism.workspace.WorkspaceStager;
import org.tinylog.Logger;

/** Plants PENDING tasks: stage the workspace, launch the agent container, move to RUNNING. */
public final class Dispatcher {
    private final TaskRepository tasks;
    private final WorkspaceStager workspaces;
    private final TemplateStore templates;
    private final ContainerLauncher launcher;
    private final FieldView field;

    public Dispatcher(TaskRepository tasks, WorkspaceStager workspaces, TemplateStore templates,
                      ContainerLauncher launcher, FieldView field) {
        this.tasks = tasks;
        this.workspaces = workspaces;
        this.templates = templates;
        this.launcher = launcher;
        this.field = field;
    }

    public void sweep() {
        for (Task task : tasks.byState(TaskState.PENDING)) {
            if (!field.hasCapacity()) {
                break;
            }
            try {
                if (!task.mode.isResume()) {
                    applyTemplate(task);
                    workspaces.stage(task, templates);   // resume reuses the existing workspace + session
                }
                task.containerId = launcher.launch(task);
                task.state = TaskState.RUNNING;
                task.dispatchedAt = System.currentTimeMillis();
                task.lastHeartbeatAt = task.dispatchedAt;
                tasks.save(task);
                Logger.info("dispatch task {} step {} ({}, {}) -> {}", task.id, task.stepIndex, task.template, task.mode, task.containerId);
            } catch (Exception e) {
                task.state = TaskState.FAILED;
                task.error = "dispatch: " + e.getMessage();
                task.finishedAt = System.currentTimeMillis();
                tasks.save(task);
                Logger.error("dispatch failed for task {}: {}", task.id, e.getMessage());
            }
        }
    }

    /**
     * Resolve the step's template manifest onto the task: its Agent Profile + (if set) its model
     * win, and its credential grant is captured here rather than at launch — so editing a template
     * cannot change what an already-seeded task receives, and the snapshot records what it had.
     */
    private void applyTemplate(Task task) {
        AgentTemplate template = templates.getByName(task.template).orElse(null);
        if (template == null) {
            return;   // a template-less step runs bare on the default backend
        }
        task.credentials = template.credentials();
        if (template.agentProfile() != null && !template.agentProfile().isBlank()) {
            task.agentProfile = template.agentProfile();
        }
        if (template.model() != null && !template.model().isBlank()) {
            TaskConfiguration current = task.config;
            task.config = new TaskConfiguration(template.model(), current.timeoutSeconds(),
                    current.network(), current.maximumAttempts());
        }
    }
}
