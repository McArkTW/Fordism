package tw.mcark.tony.fordism.orchestrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tw.mcark.tony.fordism.model.run.WorkflowRun;
import tw.mcark.tony.fordism.model.run.WorkflowRunState;
import tw.mcark.tony.fordism.model.task.TaskState;
import org.junit.jupiter.api.Test;

/**
 * THE CONTRACT: fan out everything whose dependencies have collected, and join only when they have.
 *
 * <p>The join is the half worth pinning. A step seeded while one dependency is still running reads
 * a workspace that is not finished being written, and nothing downstream can tell the difference.
 */
class GraphOrchestratorTest {

    private static final String FAN_OUT_THEN_JOIN = """
            name: graph-under-test
            strategy: graph
            steps:
              - id: fan-a
                template: generic
                task: branch a
              - id: fan-b
                template: generic
                task: branch b
              - id: join
                template: generic
                task: join both branches
                dependsOn: [fan-a, fan-b]
            """;

    private final OrchestratorHarness harness = new OrchestratorHarness();

    @Test
    void independent_steps_are_seeded_in_the_same_pass() {
        WorkflowRun run = harness.start(FAN_OUT_THEN_JOIN);

        harness.reconcile(run);

        assertEquals(2, harness.plantedCount(run), "both dependency-free steps fan out at once");
        assertTrue(harness.find(run, 2).isEmpty(), "the join has unmet dependencies");
    }

    @Test
    void the_join_waits_for_every_dependency_not_just_the_first() {
        WorkflowRun run = harness.start(FAN_OUT_THEN_JOIN);
        harness.reconcile(run);

        harness.collect(run, 0);
        harness.reconcile(run);
        assertTrue(harness.find(run, 2).isEmpty(), "one of two dependencies is not enough");

        harness.collect(run, 1);
        harness.reconcile(run);
        assertTrue(harness.find(run, 2).isPresent(), "both collected — the join may run");
    }

    @Test
    void the_join_is_staged_with_its_first_collected_dependency() {
        WorkflowRun run = harness.start(FAN_OUT_THEN_JOIN);
        harness.reconcile(run);
        harness.collect(run, 0);
        harness.collect(run, 1);
        harness.reconcile(run);

        assertEquals(harness.at(run, 0).workspacePath, harness.at(run, 2).previousWorkspace,
                "a graph step's previous is its first collected dependency, not the step above it");
    }

    @Test
    void the_run_is_done_only_once_every_step_has_collected() {
        WorkflowRun run = harness.start(FAN_OUT_THEN_JOIN);
        harness.reconcile(run);
        harness.collect(run, 0);
        harness.collect(run, 1);
        harness.reconcile(run);
        assertEquals(WorkflowRunState.ACTIVE, run.state, "the join has not collected yet");

        harness.collect(run, 2);
        harness.reconcile(run);
        assertEquals(WorkflowRunState.DONE, run.state);
    }

    @Test
    void one_dead_branch_fails_the_run_and_the_join_never_runs() {
        WorkflowRun run = harness.start(FAN_OUT_THEN_JOIN);
        harness.reconcile(run);
        harness.collect(run, 0);
        harness.die(run, 1, TaskState.REAPED);

        harness.reconcile(run);

        assertEquals(WorkflowRunState.FAILED, run.state);
        assertTrue(harness.find(run, 2).isEmpty(), "a join over a dead branch must never be seeded");
    }
}
