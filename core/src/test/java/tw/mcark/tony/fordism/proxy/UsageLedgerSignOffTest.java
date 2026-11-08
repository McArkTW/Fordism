package tw.mcark.tony.fordism.proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tw.mcark.tony.fordism.model.task.Task;

/**
 * The ledger writes where core reads: the task's own result/, which is under steps/ once a later
 * step continued its agent. Writing to the top of the workspace would land in the other step's
 * sign-off and bill it for this task's calls.
 */
class UsageLedgerSignOffTest {

    @TempDir
    Path workspace;

    @Test
    void a_task_whose_sign_off_was_set_aside_writes_under_steps() {
        Task task = new Task("t0", "r1", 0, "s1");
        task.workspacePath = workspace.toString();
        task.signOff = "steps/0";

        UsageLedger.append(task, "POST", "/v1/messages", 200, 5L, "anthropic", Optional.empty(), true);

        assertTrue(Files.isRegularFile(workspace.resolve("steps/0/result/logs").resolve(UsageLedger.FILE)));
        assertFalse(Files.exists(workspace.resolve("result/logs").resolve(UsageLedger.FILE)));
    }

    @Test
    void a_task_at_the_top_of_its_workspace_writes_where_it_always_did() {
        Task task = new Task("t1", "r1", 1, "s1");
        task.workspacePath = workspace.toString();

        UsageLedger.append(task, "POST", "/v1/messages", 200, 5L, "anthropic", Optional.empty(), true);

        assertTrue(Files.isRegularFile(workspace.resolve("result/logs").resolve(UsageLedger.FILE)));
    }
}
