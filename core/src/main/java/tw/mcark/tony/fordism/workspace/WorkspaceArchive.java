package tw.mcark.tony.fordism.workspace;

import tw.mcark.tony.fordism.model.task.Task;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Streams a task's workspace, just its results, or a run's artifact to the browser as a zip. */
public final class WorkspaceArchive {

    /**
     * The task's ENTIRE workspace: task + result + logs + the agent's session store. A continued
     * agent's workspace is shared by its steps, so this is the same bench, steps/ and all, for each.
     */
    public void zipWorkspace(Task task, OutputStream out) throws IOException {
        zip(task.workspacePath == null ? null : Paths.get(task.workspacePath), out);
    }

    /** Just the task's result/ dir — under steps/ once a later step continued its agent. */
    public void zipResult(Task task, OutputStream out) throws IOException {
        zip(task.resultDirectory(), out);
    }

    /** A run's artifact/ — the product, which no single task's workspace holds. */
    public void zipArtifact(Path artifact, OutputStream out) throws IOException {
        zip(artifact, out);
    }

    private static void zip(Path root, OutputStream out) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            if (root == null || !Files.isDirectory(root)) {
                return;   // an empty zip, rather than a broken download
            }
            // Regular files only, links not followed: an agent can plant a symlink to anything core
            // can read, and core reads as root. A link is left out rather than resolved.
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path path : (Iterable<Path>) walk.filter(entry -> Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS))::iterator) {
                    zos.putNextEntry(new ZipEntry(root.relativize(path).toString().replace('\\', '/')));
                    Files.copy(path, zos);
                    zos.closeEntry();
                }
            }
        }
    }
}
