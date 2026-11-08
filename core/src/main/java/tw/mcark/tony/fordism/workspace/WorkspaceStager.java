package tw.mcark.tony.fordism.workspace;

import tw.mcark.tony.fordism.config.FordismConfiguration;
import tw.mcark.tony.fordism.launch.ModelRegistry;
import tw.mcark.tony.fordism.launch.Proc;
import tw.mcark.tony.fordism.launch.ProcessResult;
import tw.mcark.tony.fordism.model.task.Task;
import tw.mcark.tony.fordism.model.task.TaskConfiguration;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.tinylog.Logger;

/**
 * Lays out the directory a task's agent will run in, and records both paths it is known by: the
 * container path core writes to, and the host path the agent bind-mounts.
 *
 * <p>Workspaces are kept — never garbage-collected — and a retried task keeps its id, so staging
 * has to cope with a directory a previous attempt already wrote to (see {@link #clearResult}).
 */
public final class WorkspaceStager {
    /** Where the earlier steps of a continued agent keep their sign-offs, inside its workspace. */
    public static final String STEPS = "steps";

    private final FordismConfiguration configuration;
    private final ModelRegistry models;
    private final ArtifactStore artifacts;

    /** No profiles: config.json carries the step's own model. */
    public WorkspaceStager(FordismConfiguration configuration, ArtifactStore artifacts) {
        this(configuration, new ModelRegistry(configuration, name -> Optional.empty(), Optional::empty), artifacts);
    }

    public WorkspaceStager(FordismConfiguration configuration, ModelRegistry models, ArtifactStore artifacts) {
        this.configuration = configuration;
        this.models = models;
        this.artifacts = artifacts;
    }

    public Path stage(Task task, TemplateStore templates) throws IOException {
        Path workspace = Paths.get(configuration.workspacesDir, task.id);          // container-side path
        Files.createDirectories(workspace.resolve("task"));
        clearResult(workspace.resolve("result"));
        Files.createDirectories(workspace.resolve("result"));
        writeConfig(task, workspace);
        templates.stageInto(task.template, workspace);
        if (task.taskZipPath != null) {
            unzipInto(Paths.get(task.taskZipPath), workspace.resolve("task"));   // uploaded inputs
        }
        writeTask(task, workspace);
        task.workspacePath = workspace.toString();
        task.hostWorkspacePath = Paths.get(configuration.workspacesHost, task.id).toString();
        stageArtifact(task, workspace);
        groupWritable(workspace);
        return workspace;
    }

    /**
     * Stage a continuing task into the workspace of the task it continues: the same bench, and so
     * the same session store, with this step's own task, config and template.
     *
     * <p>Whatever held the top of the workspace must already have been {@link #setAside set aside};
     * this lays out a fresh {@code task/} and an empty {@code result/} over the space it left. The
     * old template's skills, memory and instructions go first: the template is this step's, and a
     * skill only the last step's template granted must not linger for this one to find.
     *
     * <p>Whether the conversation carries over is the tool's to say, and is recorded here, where the
     * profile the task will run on is known: a one-shot tool gets the bench and a fresh session.
     */
    public Path continueInto(Task task, Task continued, TemplateStore templates) throws IOException {
        if (continued.workspacePath == null || continued.hostWorkspacePath == null) {
            throw new IOException("task " + task.id + " continues task " + continued.id
                    + ", which was never staged: there is no workspace to continue in");
        }
        Path workspace = Paths.get(continued.workspacePath);
        for (String inherited : new String[]{"skills", "memory"}) {
            deleteTree(workspace.resolve(inherited));
        }
        Files.deleteIfExists(workspace.resolve("instructions.md"));
        deleteTree(workspace.resolve("task"));   // set aside already; anything left is nobody's
        Files.createDirectories(workspace.resolve("task"));
        clearResult(workspace.resolve("result"));
        Files.createDirectories(workspace.resolve("result"));
        writeConfig(task, workspace);
        templates.stageInto(task.template, workspace);
        writeTask(task, workspace);
        task.workspacePath = continued.workspacePath;
        task.hostWorkspacePath = continued.hostWorkspacePath;
        task.conversationKept = models.backend(task.agentProfile, task.config.model()).tool().keepsSession();
        stageArtifact(task, workspace);
        groupWritable(workspace);
        return workspace;
    }

    /**
     * Move a task's {@code task/} and {@code result/} out of the top of its workspace, into
     * {@code steps/<index>/}, so a continuing task can take the bench and this one keeps its sign-off.
     *
     * <p>{@code <index>} is the step's. A second sign-off of the same step in one workspace — a
     * rework that came round again — goes beside the first as {@code steps/<index>-2/}, never over
     * it: the earlier task still points there. The task is told where its sign-off went, and the
     * folder holding it is returned for anything that was going to read it from the old place.
     */
    public Path setAside(Task owner) throws IOException {
        Path workspace = Paths.get(owner.workspacePath);
        String folder = STEPS + "/" + owner.stepIndex;
        for (int copy = 2; Files.exists(workspace.resolve(folder)); copy++) {
            folder = STEPS + "/" + owner.stepIndex + "-" + copy;
        }
        Path target = workspace.resolve(folder);
        Files.createDirectories(target);
        for (String part : new String[]{"task", "result"}) {
            if (Files.exists(workspace.resolve(part))) {
                Files.move(workspace.resolve(part), target.resolve(part));
            }
        }
        owner.signOff = folder;
        return target;
    }

    /**
     * {@code config.json}: the model, timeout and network this task runs under.
     *
     * <p>The model the entrypoint hands the CLI is the one the launcher resolved — the profile's own
     * model wins — not the step's, which defaults to qwen3 and would reach a claude-code profile on
     * api.anthropic.com as a model it has never heard of.
     */
    private void writeConfig(Task task, Path workspace) throws IOException {
        TaskConfiguration config = task.config;
        String model = models.backend(task.agentProfile, config.model()).model();
        Files.writeString(workspace.resolve("config.json"),
                String.format("{\"model\":\"%s\",\"timeout\":%d,\"network\":\"%s\"}",
                        model, config.timeoutSeconds(), config.network().name().toLowerCase()));
    }

    /**
     * {@code task/task.md}, and the predecessors' results when the step asked for them.
     *
     * <p>Written after any unzip: the workflow defines the task, and an uploaded file must not be
     * able to replace it by being named task.md.
     */
    private static void writeTask(Task task, Path workspace) throws IOException {
        Files.writeString(workspace.resolve("task/task.md"), task.taskText == null ? "" : task.taskText);
        if (task.includePreviousResult) {
            stagePrevious(task, workspace.resolve("task/previous-result"));
        }
    }

    /**
     * Lay out the run's artifact if this is its first task, and record where the launcher mounts it.
     *
     * <p>The empty {@code artifact/} made inside the workspace is only the mountpoint. Left to docker,
     * it would be created root-owned inside a workspace the agent is meant to own.
     */
    private void stageArtifact(Task task, Path workspace) throws IOException {
        Path artifact = artifacts.prepare(task.runId, task.artifactFrom);
        Files.createDirectories(workspace.resolve("artifact"));
        task.artifactPath = artifact.toString();
        task.hostArtifactPath = artifacts.hostPath(task.runId);
    }

    /**
     * The predecessors' {@code result/} under {@code into}. One predecessor is copied flat, as it
     * always was. Several (a reduce over its map tasks, a join over its dependencies) each get a
     * folder named by their label: copying them all flat would let the last overwrite the rest, and
     * staging only the first is how a reduce came to summarise one item out of three.
     */
    public static void stagePrevious(Task task, Path into) throws IOException {
        if (task.fanInWorkspaces != null && !task.fanInWorkspaces.isEmpty()) {
            for (Map.Entry<String, String> source : task.fanInWorkspaces.entrySet()) {
                copyResult(source.getValue(), into.resolve(folderName(source.getKey())));
            }
        } else if (task.previousWorkspace != null) {
            copyResult(task.previousWorkspace, into);
        }
    }

    private static void copyResult(String workspace, Path into) throws IOException {
        Path result = Paths.get(workspace, "result");
        if (Files.isDirectory(result)) {
            Files.createDirectories(into);
            TemplateStore.copyDir(result, into);
        }
    }

    /** A label as one safe path segment: a map item is free text and must not climb out of task/. */
    static String folderName(String label) {
        String safe = label.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^[.-]+|-+$", "");
        return safe.isEmpty() ? "_" : safe;
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

    /**
     * Empty the result directory before the agent starts.
     *
     * <p>A reaped task goes back to PENDING under its OWN id, so it re-stages into the workspace the
     * failed attempt left behind — result.json included. The Collector reads that file on the very
     * next tick, before the fresh agent has written anything, and harvests the dead attempt's answer
     * as this attempt's: a retry that reports FINISHED without having run. Logs go with it, or the
     * task detail view would show the previous attempt's transcript beside the new result.
     */
    private static void clearResult(Path result) throws IOException {
        deleteTree(result);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(path);
            }
        }
    }

    /**
     * Extract a task.zip into the task/ dir (zip-slip guarded).
     *
     * <p>Package-private rather than private so the guard itself can be tested: it is the one thing
     * here that decides whether an uploaded file lands outside the workspace.
     */
    static void unzipInto(Path zip, Path dest) throws IOException {
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
