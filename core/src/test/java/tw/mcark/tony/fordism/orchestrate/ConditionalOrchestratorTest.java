package tw.mcark.tony.fordism.orchestrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tw.mcark.tony.fordism.model.run.WorkflowRun;
import tw.mcark.tony.fordism.model.run.WorkflowRunState;
import org.junit.jupiter.api.Test;

/**
 * THE CONTRACT: a step whose {@code when} is false is skipped — not run, and not failed.
 *
 * <p>The predicate reads a verdict, which is the agent's one word, so the two cases that matter
 * are "it said the value" and "it said something else". A guard that ran its step anyway would be
 * worse than no guard at all: the workflow would look like it had one.
 */
class ConditionalOrchestratorTest {

    private static final String GUARDED = """
            name: conditional-under-test
            strategy: conditional
            steps:
              - id: check
                template: generic
                task: decide whether the rest is needed
              - id: guarded
                template: generic
                task: only when the check said yes
                when: check.result == 'yes'
              - id: always
                template: generic
                task: this one has no guard
            """;

    private final OrchestratorHarness harness = new OrchestratorHarness();

    @Test
    void a_true_predicate_runs_the_step() {
        WorkflowRun run = harness.start(GUARDED);
        harness.reconcile(run);
        harness.collect(run, 0, "yes");

        harness.reconcile(run);
        harness.reconcile(run);

        assertTrue(harness.find(run, 1).isPresent(), "the check said yes — the guarded step runs");
    }

    @Test
    void a_false_predicate_skips_the_step_without_planting_it() {
        WorkflowRun run = harness.start(GUARDED);
        harness.reconcile(run);
        harness.collect(run, 0, "no");

        harness.reconcile(run);                 // advance off the check
        harness.reconcile(run);                 // the guard is false: skip, do not seed
        harness.reconcile(run);                 // plant the unguarded step

        assertTrue(harness.find(run, 1).isEmpty(), "a skipped step must never be planted");
        assertTrue(harness.find(run, 2).isPresent(), "the run continues past a skipped step");
    }

    @Test
    void a_skipped_step_does_not_fail_the_run() {
        WorkflowRun run = harness.start(GUARDED);
        harness.reconcile(run);
        harness.collect(run, 0, "no");
        harness.reconcile(run);
        harness.reconcile(run);
        harness.reconcile(run);
        harness.collect(run, 2);
        harness.reconcileUntilSettled(run);

        assertEquals(WorkflowRunState.DONE, run.state, "skipping is not failing");
    }
}
