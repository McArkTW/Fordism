package tw.mcark.tony.fordism.orchestrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tw.mcark.tony.fordism.model.run.WorkflowRun;
import tw.mcark.tony.fordism.model.run.WorkflowRunState;
import tw.mcark.tony.fordism.model.task.TaskState;
import org.junit.jupiter.api.Test;

/**
 * THE CONTRACT: one step at a time, in order, and a dead task ends the run.
 *
 * <p>Linear is the strategy the other five are variations of, so the two rules asserted here — a
 * step is never planted twice, and FAILED/REAPED is never walked past — are the ones the whole
 * engine rests on. A run that walked past a dead step would report DONE over work that never
 * happened, which is precisely what "never rubber-stamped" is a promise against.
 */
class LinearOrchestratorTest {

    private static final String TWO_STEPS = """
            name: linear-under-test
            strategy: linear
            steps:
              - id: first
                template: generic
                task: do the first thing
              - id: second
                template: generic
                task: do the second thing
                includePreviousResult: true
            """;

    private final OrchestratorHarness harness = new OrchestratorHarness();

    @Test
    void it_seeds_one_step_at_a_time_and_finishes_when_the_last_collects() {
        WorkflowRun run = harness.start(TWO_STEPS);

        harness.reconcile(run);
        assertEquals(1, harness.plantedCount(run), "only the first step may be planted");
        assertTrue(harness.find(run, 1).isEmpty(), "the second step must wait for the first");

        harness.collect(run, 0);
        harness.reconcile(run);                 // advances the index
        harness.reconcile(run);                 // plants the second step
        assertEquals("do the second thing", harness.at(run, 1).taskText);

        harness.collect(run, 1);
        harness.reconcileUntilSettled(run);
        assertEquals(WorkflowRunState.DONE, run.state);
    }

    @Test
    void a_planted_step_is_never_planted_again_while_it_runs() {
        WorkflowRun run = harness.start(TWO_STEPS);
        harness.reconcile(run);
        String first = harness.at(run, 0).id;

        harness.at(run, 0).state = TaskState.RUNNING;
        harness.reconcile(run);
        harness.reconcile(run);

        assertEquals(1, harness.plantedCount(run), "a running step must not be re-seeded every tick");
        assertEquals(first, harness.at(run, 0).id);
    }

    @Test
    void a_failed_step_fails_the_run_rather_than_advancing_past_it() {
        deadStepFailsTheRun(TaskState.FAILED);
    }

    @Test
    void a_reaped_step_fails_the_run_rather_than_advancing_past_it() {
        deadStepFailsTheRun(TaskState.REAPED);
    }

    private void deadStepFailsTheRun(TaskState terminal) {
        WorkflowRun run = harness.start(TWO_STEPS);
        harness.reconcile(run);
        harness.die(run, 0, terminal);

        harness.reconcile(run);

        assertEquals(WorkflowRunState.FAILED, run.state, terminal + " must end the run");
        assertTrue(harness.find(run, 1).isEmpty(), "nothing may run after a dead step");
    }

    @Test
    void the_second_step_is_staged_with_the_first_steps_workspace_when_it_asked_for_it() {
        WorkflowRun run = harness.start(TWO_STEPS);
        harness.reconcile(run);
        harness.collect(run, 0);
        harness.reconcile(run);
        harness.reconcile(run);

        assertTrue(harness.at(run, 1).includePreviousResult);
        assertEquals(harness.at(run, 0).workspacePath, harness.at(run, 1).previousWorkspace,
                "includePreviousResult means the previous step's workspace, not a fresh one");
    }
}
