package tw.mcark.tony.fordism.workspace;

import tw.mcark.tony.fordism.model.task.Task;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Streams a task's workspace, or just its results, to the browser as a zip. */
public final class WorkspaceArchive {

    /** The task's ENTIRE workspace: task + result + logs + the agent's session store. */
    public void zipWorkspace(Task task, OutputStream out) throws IOException {
        zip(task.workspacePath == null ? null : Paths.get(task.workspacePath), out);
    }

    /** Just the task's result/ dir. */
    public void zipResult(Task task, OutputStream out) throws IOException {
        zip(task.workspacePath == null ? null : Paths.get(task.workspacePath, "result"), out);
    }

    private static void zip(Path root, OutputStream out) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            if (root == null || !Files.isDirectory(root)) {
                return;   // an empty zip, rather than a broken download
            }
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path path : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                    zos.putNextEntry(new ZipEntry(root.relativize(path).toString().replace('\\', '/')));
                    Files.copy(path, zos);
                    zos.closeEntry();
                }
            }
        }
    }
}
