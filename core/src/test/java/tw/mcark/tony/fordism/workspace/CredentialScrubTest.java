package tw.mcark.tony.fordism.workspace;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tw.mcark.tony.fordism.model.task.Task;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The guarantee in {@link CredentialScrub}'s javadoc: a key a tool had to write into the workspace
 * does not outlive the task. The workspace is host-mounted, kept, and downloadable through
 * {@code run.workspace.download}, so a key left there is a key handed to everyone who can pull the
 * bundle — which is a wider audience than the one that could read the container's environment.
 */
class CredentialScrubTest {

    private static Task taskIn(Path workspace) {
        Task task = new Task("t1", "r1", 0, "s1");
        task.workspacePath = workspace.toString();
        return task;
    }

    @Test
    void the_configured_file_is_gone_and_the_agents_own_output_is_not(@TempDir Path workspace) throws IOException {
        Files.createDirectories(workspace.resolve(".reasonix"));
        Files.writeString(workspace.resolve(".reasonix/.env"), "PROVIDER_KEY=sk-live-secret");
        Files.createDirectories(workspace.resolve("result"));
        Files.writeString(workspace.resolve("result/answer.txt"), "the work");

        new CredentialScrub(List.of(".reasonix/.env")).scrub(taskIn(workspace));

        assertFalse(Files.exists(workspace.resolve(".reasonix/.env")), "the key survived the task");
        assertTrue(Files.exists(workspace.resolve("result/answer.txt")), "the scrub took the agent's work with it");
    }

    /**
     * The destructive case. An operator-supplied list is the only input here, so a path that climbs
     * out of the workspace is a configuration mistake — and it is pointed at a file that EXISTS,
     * because aiming an escape at a missing file passes against an unguarded resolve too.
     */
    @Test
    void a_path_climbing_out_of_the_workspace_deletes_nothing(@TempDir Path root) throws IOException {
        Path workspace = Files.createDirectories(root.resolve("ws"));
        Path sibling = Files.writeString(root.resolve("other-task-secret"), "sk-not-yours");

        new CredentialScrub(List.of("../other-task-secret")).scrub(taskIn(workspace));

        assertTrue(Files.exists(sibling), "the scrub reached outside the workspace it was given");
    }

    @Test
    void an_absent_file_or_workspace_is_not_an_error(@TempDir Path workspace) {
        new CredentialScrub(List.of(".reasonix/.env")).scrub(taskIn(workspace));
        new CredentialScrub(List.of(".reasonix/.env")).scrub(taskIn(workspace.resolve("never-staged")));

        Task unstaged = new Task("t2", "r1", 0, "s1");
        new CredentialScrub(List.of(".reasonix/.env")).scrub(unstaged);
        new CredentialScrub(List.of(".reasonix/.env")).scrub(null);
    }

    @Test
    void an_empty_list_scrubs_nothing(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("keep.txt"), "x");
        new CredentialScrub(List.of()).scrub(taskIn(workspace));
        assertTrue(Files.exists(workspace.resolve("keep.txt")));
    }
}
