package tw.mcark.tony.fordism.workspace;

import tw.mcark.tony.fordism.config.FordismConfiguration;
import tw.mcark.tony.fordism.launch.Proc;
import tw.mcark.tony.fordism.launch.ProcessResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.tinylog.Logger;

/**
 * Where each run's {@code artifact/} lives: the product the run is building, kept apart from the
 * per-task workspaces that build it.
 *
 * <p>A workspace is the agent's bench — its task, its {@code result/}, its session store — and is
 * thrown away with the task. The artifact is what the line produces: one folder per run, under
 * {@code _artifacts/<run-id>} beside the workspaces, mounted into every task of the run. The first
 * step clones or scaffolds into it, each later one works on what the last left, so no step repeats
 * the setup and nothing uncommitted is lost between them.
 *
 * <p>It is laid out lazily, the first time a task of the run is staged, rather than when the run is
 * created. A run continuing from another ({@code artifactFrom}) copies that run's artifact at that
 * moment — which is why the culler holds a source until every run continuing from it has ended.
 */
public final class ArtifactStore {
    /** The folder under the workspaces root that holds one artifact per run. */
    public static final String FOLDER = "_artifacts";

    private final Path root;
    private final Path hostRoot;
    private final long agentGroupId;

    public ArtifactStore(FordismConfiguration configuration) {
        this(Paths.get(configuration.workspacesDir, FOLDER), Paths.get(configuration.workspacesHost, FOLDER),
                configuration.agentGroupId);
    }

    /** {@code root} is where core reads and writes; {@code hostRoot} is the same folder as docker sees it. */
    public ArtifactStore(Path root, Path hostRoot, long agentGroupId) {
        this.root = root;
        this.hostRoot = hostRoot;
        this.agentGroupId = agentGroupId;
    }

    /** The run's artifact as core sees it. Refuses an id that is not one path segment. */
    public Path path(String runId) {
        return root.resolve(segment(runId));
    }

    /** The run's artifact as docker sees it, for the bind mount. */
    public String hostPath(String runId) {
        return hostRoot.resolve(segment(runId)).toString();
    }

    public boolean exists(String runId) {
        return isSegment(runId) && Files.isDirectory(path(runId));
    }

    /**
     * The run's artifact, laid out if this is the first task to need it: empty, or a copy of
     * {@code fromRunId}'s when the run continues from one.
     *
     * <p>The copy goes to a sibling first and is renamed into place, so a crash half way leaves no
     * folder that a later task would take for a finished copy.
     */
    public Path prepare(String runId, String fromRunId) throws IOException {
        Path artifact = path(runId);
        if (Files.isDirectory(artifact)) {
            return artifact;
        }
        Files.createDirectories(root);
        if (fromRunId == null || fromRunId.isBlank()) {
            Files.createDirectories(artifact);
        } else {
            Path source = path(fromRunId);
            if (!Files.isDirectory(source)) {
                throw new IOException("run " + runId + " continues from the artifact of run " + fromRunId
                        + ", which no longer exists");
            }
            Path staging = root.resolve("." + segment(runId) + "-" + UUID.randomUUID());
            copyTree(source, staging);
            Files.move(staging, artifact, StandardCopyOption.ATOMIC_MOVE);
            Logger.info("artifact of run {} copied from run {}", runId, fromRunId);
        }
        groupWritable(artifact);
        return artifact;
    }

    /** Remove a run's artifact. Absent is not an error: culling twice is culling once. */
    public void delete(String runId) throws IOException {
        Path artifact = path(runId);
        if (!Files.exists(artifact, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(artifact)) {
            for (Path path : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(path);
            }
        }
    }

    /**
     * A tree, copied as a working tree needs it: symlinks stay links (a {@code node_modules/.bin} or
     * a repo's own links would otherwise be followed, or loop), and attributes come along so an
     * executable script is still executable and git sees no mode change.
     */
    static void copyTree(Path source, Path destination) throws IOException {
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                Path target = destination.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(path, target, LinkOption.NOFOLLOW_LINKS, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    /**
     * Hand the artifact to the agent's group, as {@link WorkspaceStager} does a workspace — but with
     * {@code g+rwX}, not a blanket 775: this is a working tree, often a git checkout, and marking
     * every file executable would show the whole repo as modified to the next step.
     */
    private void groupWritable(Path artifact) {
        try {
            ProcessResult owned = Proc.run(
                    List.of("chgrp", "-R", String.valueOf(agentGroupId), artifact.toString()), 60);
            String mode = owned.exit() == 0 ? "g+rwX" : "a+rwX";
            if (owned.exit() != 0) {
                Logger.warn("chgrp {} failed on {} ({}) — falling back to world-writable",
                        agentGroupId, artifact, owned.err().trim());
            }
            Proc.run(List.of("chmod", "-R", mode, artifact.toString()), 60);
        } catch (Exception e) {
            // dev/windows — chgrp/chmod do not exist and the agent is not running here either
            Logger.debug("could not set permissions on {}: {}", artifact, e.getMessage());
        }
    }

    private static boolean isSegment(String runId) {
        return runId != null && runId.matches("[A-Za-z0-9_-][A-Za-z0-9._-]*");
    }

    /** A run id as one path segment. Ids are UUIDs; anything that could climb out of the root is refused. */
    private static String segment(String runId) {
        if (!isSegment(runId)) {
            throw new IllegalArgumentException("not a run id: " + runId);
        }
        return runId;
    }
}
