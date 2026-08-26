package tw.mcark.tony.fordism.workspace;

import tw.mcark.tony.fordism.config.FordismConfiguration;
import tw.mcark.tony.fordism.launch.Proc;
import tw.mcark.tony.fordism.launch.ProcessResult;
import tw.mcark.tony.fordism.model.task.Task;
import tw.mcark.tony.fordism.model.task.TaskConfiguration;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.tinylog.Logger;

/**
 * Lays out the directory a task's agent will run in, and records both paths it is known by: the
 * container path core writes to, and the host path the agent bind-mounts.
 *
 * <p>Workspaces are kept — never garbage-collected — so this only ever creates.
 */
public final class WorkspaceStager {
    private final FordismConfiguration configuration;

    public WorkspaceStager(FordismConfiguration configuration) {
        this.configuration = configuration;
    }

    public Path stage(Task task, TemplateStore templates) throws IOException {
        Path workspace = Paths.get(configuration.workspacesDir, task.id);          // container-side path
        Files.createDirectories(workspace.resolve("task"));
        Files.createDirectories(workspace.resolve("result"));
        TaskConfiguration config = task.config;
        Files.writeString(workspace.resolve("config.json"),
                String.format("{\"model\":\"%s\",\"timeout\":%d,\"network\":\"%s\"}",
                        config.model(), config.timeoutSeconds(), config.network().name().toLowerCase()));
        templates.stageInto(task.template, workspace);
        if (task.taskZipPath != null) {
            unzipInto(Paths.get(task.taskZipPath), workspace.resolve("task"));   // uploaded inputs
        }
        // Written after the unzip: the workflow defines the task, and an uploaded file must not be
        // able to replace it by being named task.md.
        Files.writeString(workspace.resolve("task/task.md"), task.taskText == null ? "" : task.taskText);
        if (task.includePreviousResult && task.previousWorkspace != null) {
            Path previous = Paths.get(task.previousWorkspace, "result");
            if (Files.isDirectory(previous)) {
                Path into = workspace.resolve("task/previous-result");
                Files.createDirectories(into);
                TemplateStore.copyDir(previous, into);
            }
        }
        task.workspacePath = workspace.toString();
        task.hostWorkspacePath = Paths.get(configuration.workspacesHost, task.id).toString();
        groupWritable(workspace);
        return workspace;
    }

    /**
     * Hand the workspace to the agent's group so it can write {@code result/} and its session store.
     *
     * <p>Group, not world. Core runs as root and the agent as {@code agentGroupId}, so the two only
     * meet through the group bit — and 777 would additionally let every account on the host read
     * every run's transcript, for workspaces that are kept forever. The chgrp has to land for 775 to
     * be enough, so a failure there falls back to the old behaviour rather than leaving a workspace
     * the agent cannot write.
     */
    private void groupWritable(Path workspace) {
        try {
            ProcessResult owned = Proc.run(
                    List.of("chgrp", "-R", String.valueOf(configuration.agentGroupId), workspace.toString()), 20);
            if (owned.exit() != 0) {
                Logger.warn("chgrp {} failed on {} ({}) — falling back to world-writable",
                        configuration.agentGroupId, workspace, owned.err().trim());
                Proc.run(List.of("chmod", "-R", "777", workspace.toString()), 20);
                return;
            }
            Proc.run(List.of("chmod", "-R", "775", workspace.toString()), 20);
        } catch (Exception e) {
            // dev/windows — chgrp/chmod do not exist and the agent is not running here either
            Logger.debug("could not set permissions on {}: {}", workspace, e.getMessage());
        }
    }

    /** Extract a task.zip into the task/ dir (zip-slip guarded). */
    private static void unzipInto(Path zip, Path dest) throws IOException {
        Files.createDirectories(dest);
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path target = dest.resolve(entry.getName()).normalize();
                if (!target.startsWith(dest)) {
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (OutputStream os = Files.newOutputStream(target)) {
                        zis.transferTo(os);
                    }
                }
            }
        }
    }
}
