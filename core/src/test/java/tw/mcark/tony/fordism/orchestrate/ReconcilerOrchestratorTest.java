package tw.mcark.tony.fordism.orchestrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tw.mcark.tony.fordism.model.run.WorkflowRun;
import tw.mcark.tony.fordism.model.run.WorkflowRunState;
import tw.mcark.tony.fordism.model.task.ChildRunRequest;
import tw.mcark.tony.fordism.model.task.TaskState;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * THE CONTRACT: run the generator once per iteration, continuing from the last one, until it says
 * done or the iterations are spent.
 *
 * <p>"Continuing from the last one" is the part that is not obvious from the YAML: every pass after
 * the first stages the previous iteration's workspace whatever the generator step declared, because
 * a reconciler that started from nothing each time would loop forever over the same first move.
 */
class ReconcilerOrchestratorTest {

    private static final String CONVERGING = """
            name: reconciler-under-test
            strategy: reconciler
            maxIterations: 3
            generator:
              id: generate
              template: generic
              task: converge on the desired state
            """;

    private static final String ONE_ITERATION = """
            name: reconciler-one-shot
            strategy: reconciler
            maxIterations: 1
            generator:
              id: generate
              template: generic
              task: converge on the desired state
            """;

    private static final String NO_GENERATOR = """
            name: reconciler-without-a-generator
            strategy: reconciler
            maxIterations: 3
            """;

    private final OrchestratorHarness harness = new OrchestratorHarness();

    @Test
    void the_generator_runs_once_per_iteration() {
        WorkflowRun run = harness.start(CONVERGING);

        harness.reconcile(run);
        assertEquals(1, harness.plantedCount(run));
        assertEquals("converge on the desired state", harness.at(run, 0).taskText);

        harness.collect(run, 0, "not yet");
        harness.reconcile(run);                 // not done: next iteration
        assertEquals(1, run.iteration);

        harness.reconcile(run);
        assertEquals(2, harness.plantedCount(run), "the second iteration is its own task");
    }

    @Test
    void every_iteration_after_the_first_continues_from_the_one_before_it() {
        WorkflowRun run = harness.start(CONVERGING);
        harness.reconcile(run);
        assertTrue(harness.find(run, 0).isPresent());
        assertEquals(false, harness.at(run, 0).includePreviousResult,
                "the first pass has nothing to continue from");

        harness.collect(run, 0, "not yet");
        harness.reconcile(run);
        harness.reconcile(run);

        assertTrue(harness.at(run, 1).includePreviousResult,
                "a loop whose passes cannot see the last one loops over the same first move");
        assertEquals(harness.at(run, 0).workspacePath, harness.at(run, 1).previousWorkspace);
    }

    @Test
    void a_done_verdict_finishes_the_run() {
        WorkflowRun run = harness.start(CONVERGING);
        harness.reconcile(run);

        harness.collect(run, 0, "done");
        harness.reconcile(run);

        assertEquals(WorkflowRunState.DONE, run.state);
    }

    @Test
    void spent_iterations_fail_the_run_rather_than_parking_it() {
        WorkflowRun run = harness.start(ONE_ITERATION);
        harness.reconcile(run);
        harness.collect(run, 0, "not yet");

        harness.reconcileUntilSettled(run);

        assertEquals(WorkflowRunState.FAILED, run.state,
                "an unconverged loop with no iterations left is spent, not waiting on anyone");
    }

    @Test
    void a_dead_iteration_fails_the_run() {
        WorkflowRun run = harness.start(CONVERGING);
        harness.reconcile(run);
        harness.die(run, 0, TaskState.REAPED);

        harness.reconcile(run);

        assertEquals(WorkflowRunState.FAILED, run.state);
    }

    // ---- child runs ----

    private static final String CHILD_WORKFLOW = """
            name: child-of-the-reconciler
            strategy: linear
            parameters:
              - name: goal
            steps:
              - id: work
                template: generic
                task: ${goal}
            """;

    private static List<ChildRunRequest> twoChildren() {
        return List.of(new ChildRunRequest("child-of-the-reconciler", Map.of("goal", "the first piece")),
                new ChildRunRequest("child-of-the-reconciler", Map.of("goal", "the second piece")));
    }

    /** An iteration that delegated its work to two child runs and is waiting on them. */
    private WorkflowRun runWaitingOnTwoChildren() {
        harness.register(CHILD_WORKFLOW);
        WorkflowRun run = harness.start(CONVERGING);
        harness.reconcile(run);
        harness.collect(run, 0, "not yet");
        harness.requestRuns(run, 0, twoChildren());
        harness.reconcile(run);                 // starts them
        return run;
    }

    @Test
    void an_iteration_can_ask_for_whole_runs_and_they_are_started_with_its_parameters() {
        WorkflowRun run = runWaitingOnTwoChildren();

        List<WorkflowRun> children = harness.childrenOf(run);
        assertEquals(2, children.size());
        assertEquals(run.id, children.get(0).parentRunId);
        assertEquals(run.iteration, children.get(0).parentIteration);
        // By value, not by position: both are started inside one tick, so nothing orders them.
        assertEquals(List.of("the first piece", "the second piece"),
                harness.childParameters(run, "goal"));
    }

    @Test
    void the_same_children_are_not_started_twice_on_the_next_tick() {
        WorkflowRun run = runWaitingOnTwoChildren();

        harness.reconcile(run);
        harness.reconcile(run);

        assertEquals(2, harness.childrenOf(run).size(),
                "the iteration is level-triggered — a second pass must not start the work again");
    }

    @Test
    void the_iteration_does_not_advance_while_a_child_is_still_going() {
        WorkflowRun run = runWaitingOnTwoChildren();
        harness.finish(harness.childrenOf(run).get(0), WorkflowRunState.DONE);

        harness.reconcile(run);

        assertEquals(0, run.iteration, "one of two finished is not finished");
        assertEquals(WorkflowRunState.ACTIVE, run.state);
    }

    @Test
    void a_child_parked_on_a_question_is_waited_for_rather_than_treated_as_over() {
        WorkflowRun run = runWaitingOnTwoChildren();
        harness.finish(harness.childrenOf(run).get(0), WorkflowRunState.DONE);
        harness.finish(harness.childrenOf(run).get(1), WorkflowRunState.ASKED);

        harness.reconcile(run);

        assertEquals(0, run.iteration, "someone can still answer it — the work is not over");
        assertEquals(WorkflowRunState.ACTIVE, run.state);
    }

    @Test
    void the_iteration_advances_once_every_child_is_done() {
        WorkflowRun run = runWaitingOnTwoChildren();
        for (WorkflowRun child : harness.childrenOf(run)) {
            harness.finish(child, WorkflowRunState.DONE);
        }

        harness.reconcile(run);

        assertEquals(1, run.iteration);
        assertEquals(WorkflowRunState.ACTIVE, run.state);
    }

    @Test
    void a_child_that_failed_fails_the_parent_rather_than_being_looped_past() {
        WorkflowRun run = runWaitingOnTwoChildren();
        harness.finish(harness.childrenOf(run).get(0), WorkflowRunState.DONE);
        harness.finish(harness.childrenOf(run).get(1), WorkflowRunState.FAILED);

        harness.reconcile(run);

        assertEquals(WorkflowRunState.FAILED, run.state,
                "a reconciler whose delegated work failed has not converged");
    }

    @Test
    void a_done_verdict_is_not_believed_until_the_children_it_delegated_to_have_finished() {
        harness.register(CHILD_WORKFLOW);
        WorkflowRun run = harness.start(CONVERGING);
        harness.reconcile(run);
        harness.collect(run, 0, "done");        // says done, but delegated the work
        harness.requestRuns(run, 0, twoChildren());

        harness.reconcile(run);                 // starts them
        harness.reconcile(run);
        assertEquals(WorkflowRunState.ACTIVE, run.state, "the work it claims is still in flight");

        for (WorkflowRun child : harness.childrenOf(run)) {
            harness.finish(child, WorkflowRunState.DONE);
        }
        harness.reconcile(run);
        assertEquals(WorkflowRunState.DONE, run.state);
    }

    @Test
    void a_later_iteration_waits_for_its_own_children_not_the_previous_ones() {
        WorkflowRun run = runWaitingOnTwoChildren();
        for (WorkflowRun child : harness.childrenOf(run)) {
            harness.finish(child, WorkflowRunState.DONE);
        }
        harness.reconcile(run);                 // iteration 1
        harness.reconcile(run);                 // plants the second iteration
        harness.collect(run, 1, "not yet");
        harness.requestRuns(run, 1, List.of(
                new ChildRunRequest("child-of-the-reconciler", Map.of("goal", "the third piece"))));

        harness.reconcile(run);                 // starts the second iteration's child
        harness.reconcile(run);

        assertEquals(3, harness.childrenOf(run).size());
        assertEquals(1, run.iteration,
                "the pass must wait for the child it just started, not read the two that already ended");
    }

    @Test
    void a_reconciler_with_no_generator_fails_instead_of_spinning() {
        WorkflowRun run = harness.start(NO_GENERATOR);

        harness.reconcile(run);

        assertEquals(WorkflowRunState.FAILED, run.state);
        assertEquals(0, harness.plantedCount(run));
    }
}
