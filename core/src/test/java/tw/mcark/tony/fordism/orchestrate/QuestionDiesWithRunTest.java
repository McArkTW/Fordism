package tw.mcark.tony.fordism.orchestrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tw.mcark.tony.fordism.model.run.WorkflowRun;
import tw.mcark.tony.fordism.model.run.WorkflowRunState;
import tw.mcark.tony.fordism.model.task.Task;
import tw.mcark.tony.fordism.model.task.TaskState;
import tw.mcark.tony.fordism.model.workflow.Strategy;
import tw.mcark.tony.fordism.secret.SecretVault;
import tw.mcark.tony.fordism.store.InMemoryTaskRepository;
import tw.mcark.tony.fordism.store.InMemoryWorkflowRunRepository;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * THE CONTRACT: a question dies with its run.
 *
 * <p>The Questions inbox lists tasks in ASKED, and a question's presence there is a promise that
 * answering it helps (see {@link TaskState}). A run that ends terminally — abandoned, failed, done
 * — can never use an answer, so finishing it must take its asked task out of ASKED. Without this,
 * a superseded run's question lingers in every inbox forever and answering it resumes a session
 * whose output nothing will collect (seen live on AB#123757, 2026-08-25).
 */
class QuestionDiesWithRunTest {

    @Test
    void abandoningARunReapsItsAskedTask() {
        askedTaskLeavesAskedWhen(WorkflowRunState.ABANDONED);
    }

    @Test
    void failingARunReapsItsAskedTask() {
        askedTaskLeavesAskedWhen(WorkflowRunState.FAILED);
    }

    @Test
    void parkingARunAskedKeepsItsQuestion() {
        InMemoryTaskRepository tasks = new InMemoryTaskRepository();
        InMemoryWorkflowRunRepository runs = new InMemoryWorkflowRunRepository();
        Engine engine = engine(tasks, runs);
        WorkflowRun run = runWithAskedTask(tasks, runs);

        engine.finishRun(run, WorkflowRunState.ASKED);   // parked, not ended

        assertEquals(1, tasks.byState(TaskState.ASKED).size(),
                "a parked run's question must stay answerable");
    }

    private static void askedTaskLeavesAskedWhen(WorkflowRunState terminal) {
        InMemoryTaskRepository tasks = new InMemoryTaskRepository();
        InMemoryWorkflowRunRepository runs = new InMemoryWorkflowRunRepository();
        Engine engine = engine(tasks, runs);
        WorkflowRun run = runWithAskedTask(tasks, runs);

        engine.finishRun(run, terminal);

        assertTrue(tasks.byState(TaskState.ASKED).isEmpty(),
                "a " + terminal + " run's question must leave the inbox with it");
        assertEquals(TaskState.REAPED, tasks.byRun(run.id).get(0).state);
    }

    /** The level-triggered backstop: orphans from before the rule existed heal on the next tick. */
    @Test
    void theTickReapsAQuestionOrphanedByAnAlreadyTerminalRun() {
        InMemoryTaskRepository tasks = new InMemoryTaskRepository();
        InMemoryWorkflowRunRepository runs = new InMemoryWorkflowRunRepository();
        Engine engine = engine(tasks, runs);
        WorkflowRun run = runWithAskedTask(tasks, runs);
        run.state = WorkflowRunState.ABANDONED;          // ended without the finishRun hook firing
        runs.save(run);

        engine.reapOrphanQuestions();                     // the sweep tick() runs every interval

        assertTrue(tasks.byState(TaskState.ASKED).isEmpty(),
                "an orphaned question must heal on the next tick, not linger forever");
    }

    private static WorkflowRun runWithAskedTask(InMemoryTaskRepository tasks,
            InMemoryWorkflowRunRepository runs) {
        WorkflowRun run = new WorkflowRun("run-1", "workitem-plan", Strategy.LINEAR, Map.of());
        runs.save(run);
        Task task = new Task("task-1", run.id, 0, "session-1");
        task.state = TaskState.ASKED;
        task.question = "need a GITHUB_TOKEN";
        tasks.save(task);
        return run;
    }

    /** finishRun touches only runs, tasks and secrets; the field machinery can stay null. */
    private static Engine engine(InMemoryTaskRepository tasks, InMemoryWorkflowRunRepository runs) {
        return new Engine(null, tasks, runs, null, null, null, null, null, null, new SecretVault());
    }
}
