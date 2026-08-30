package tw.mcark.tony.fordism.orchestrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tw.mcark.tony.fordism.model.run.WorkflowRun;
import tw.mcark.tony.fordism.model.run.WorkflowRunState;
import tw.mcark.tony.fordism.model.task.TaskState;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * THE CONTRACT: one map task per item, each carrying its own item, and a reduce that waits.
 *
 * <p>The reduce lands at the item count, so a list whose length changed between passes would put
 * the reduce on top of a map task. It cannot change — it is read from the run's frozen parameter
 * values — and the assertion that the reduce is at index N is what says so out loud.
 */
class MapReduceOrchestratorTest {

    private static final String FAN_THEN_REDUCE = """
            name: map-reduce-under-test
            strategy: map-reduce
            parameters:
              - name: items
            steps:
              - id: map
                template: generic
                task: handle ${item}
                forEach: ${items}
              - id: reduce
                template: generic
                task: combine everything
            """;

    private final OrchestratorHarness harness = new OrchestratorHarness();

    private WorkflowRun threeItems() {
        return harness.start(FAN_THEN_REDUCE, Map.of("items", "alpha, beta, gamma"));
    }

    @Test
    void every_item_gets_its_own_task_carrying_its_own_value() {
        WorkflowRun run = threeItems();

        harness.reconcile(run);

        assertEquals(3, harness.plantedCount(run));
        assertEquals("handle alpha", harness.at(run, 0).taskText);
        assertEquals("handle beta", harness.at(run, 1).taskText);
        assertEquals("handle gamma", harness.at(run, 2).taskText);
    }

    @Test
    void the_reduce_waits_for_the_last_map_task() {
        WorkflowRun run = threeItems();
        harness.reconcile(run);
        harness.collect(run, 0);
        harness.collect(run, 1);

        harness.reconcile(run);
        assertTrue(harness.find(run, 3).isEmpty(), "two of three mapped is not all mapped");

        harness.collect(run, 2);
        harness.reconcile(run);
        assertTrue(harness.find(run, 3).isPresent(), "the reduce is planted at the item count");
    }

    @Test
    void the_reduce_reads_the_first_map_workspace_not_the_last() {
        WorkflowRun run = threeItems();
        harness.reconcile(run);
        harness.collect(run, 0);
        harness.collect(run, 1);
        harness.collect(run, 2);
        harness.reconcile(run);

        assertEquals(harness.at(run, 0).workspacePath, harness.at(run, 3).previousWorkspace);
    }

    @Test
    void the_run_finishes_when_the_reduce_collects() {
        WorkflowRun run = threeItems();
        harness.reconcile(run);
        harness.collect(run, 0);
        harness.collect(run, 1);
        harness.collect(run, 2);
        harness.reconcile(run);
        harness.collect(run, 3);

        harness.reconcile(run);

        assertEquals(WorkflowRunState.DONE, run.state);
    }

    @Test
    void one_dead_map_task_fails_the_run_before_the_reduce_is_seeded() {
        WorkflowRun run = threeItems();
        harness.reconcile(run);
        harness.collect(run, 0);
        harness.die(run, 1, TaskState.FAILED);
        harness.collect(run, 2);

        harness.reconcile(run);

        assertEquals(WorkflowRunState.FAILED, run.state);
        assertTrue(harness.find(run, 3).isEmpty(), "a reduce over an incomplete fan-out is not a reduce");
    }
}
