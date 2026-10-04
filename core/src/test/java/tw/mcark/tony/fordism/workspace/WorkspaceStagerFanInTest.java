package tw.mcark.tony.fordism.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tw.mcark.tony.fordism.model.task.Task;

/**
 * A step fed by several predecessors sees all of them. Staged flat, the last would overwrite the
 * rest; staged from the first alone, a reduce over three items summarised one.
 */
class WorkspaceStagerFanInTest {

    @TempDir
    Path directory;

    private String workspaceWith(String name, String file, String content) throws IOException {
        Path result = directory.resolve(name).resolve("result");
        Files.createDirectories(result);
        Files.writeString(result.resolve(file), content);
        return directory.resolve(name).toString();
    }

    @Test
    void every_predecessor_lands_in_its_own_folder() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("1-Docker", workspaceWith("a", "summary.md", "docker"));
        sources.put("2-Kubernetes", workspaceWith("b", "summary.md", "kubernetes"));
        Task task = new Task("t", "r", 2, "s");
        task.fanInWorkspaces = sources;
        Path into = directory.resolve("reduce/task/previous-result");

        WorkspaceStager.stagePrevious(task, into);

        assertEquals("docker", Files.readString(into.resolve("1-Docker/summary.md")));
        assertEquals("kubernetes", Files.readString(into.resolve("2-Kubernetes/summary.md")));
        assertFalse(Files.exists(into.resolve("summary.md")), "nothing is copied flat beside the folders");
    }

    @Test
    void a_single_predecessor_is_still_staged_flat() throws IOException {
        Task task = new Task("t", "r", 1, "s");
        task.previousWorkspace = workspaceWith("only", "answer.md", "flat");
        Path into = directory.resolve("next/task/previous-result");

        WorkspaceStager.stagePrevious(task, into);

        assertTrue(Files.isRegularFile(into.resolve("answer.md")));
    }

    @Test
    void a_free_text_label_cannot_climb_out_of_the_task_folder() {
        assertEquals("1-a-b-c", WorkspaceStager.folderName("1-a/b c"));
        assertEquals("etc-passwd", WorkspaceStager.folderName("../../etc/passwd"));
        assertEquals("_", WorkspaceStager.folderName("/.."));
    }
}
