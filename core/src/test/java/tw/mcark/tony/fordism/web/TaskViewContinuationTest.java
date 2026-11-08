package tw.mcark.tony.fordism.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import tw.mcark.tony.fordism.model.task.Task;
import tw.mcark.tony.fordism.orchestrate.Engine;
import tw.mcark.tony.fordism.secret.SecretVault;
import tw.mcark.tony.fordism.store.InMemoryTaskRepository;
import tw.mcark.tony.fordism.store.InMemoryWorkflowRunRepository;
import tw.mcark.tony.fordism.workspace.TaskResults;

/** The run page names the task a continuing task continued, and says when the conversation did not come. */
class TaskViewContinuationTest {
    private final InMemoryTaskRepository tasks = new InMemoryTaskRepository();
    private final RunController controller = new RunController(
            new Engine(null, tasks, new InMemoryWorkflowRunRepository(), null, null, null, null, null, null,
                    new SecretVault()),
            new TaskResults(), null, new SecretVault(), null);

    private Task task(String id, int step) {
        Task task = new Task(id, "r1", step, "s1");
        tasks.save(task);
        return task;
    }

    @Test
    void a_continued_task_shows_which_task_it_continued() {
        Task first = task("t0", 0);
        first.signOff = "steps/0";
        Task second = task("t1", 1);
        second.continuesTaskId = "t0";
        second.conversationKept = true;

        Views.TaskDetail view = controller.detailOf(second);

        assertEquals(new Views.Continuation("t0", 0, true), view.continuation());
        assertEquals("steps/0", controller.detailOf(first).signOff());
        assertNull(controller.detailOf(first).continuation(), "the first step continued nothing");
    }

    @Test
    void a_one_shot_tool_is_flagged_as_a_fresh_conversation() {
        task("t0", 0);
        Task second = task("t1", 1);
        second.continuesTaskId = "t0";
        second.conversationKept = false;

        assertEquals(Boolean.FALSE, controller.detailOf(second).continuation().conversationKept());
    }
}
