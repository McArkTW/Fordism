package tw.mcark.tony.fordism.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tw.mcark.tony.fordism.model.run.WorkflowRun;
import tw.mcark.tony.fordism.model.task.Task;
import tw.mcark.tony.fordism.model.task.TaskState;
import tw.mcark.tony.fordism.model.workflow.Strategy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The snapshot is rewritten every engine tick, so the window in which state.json is incomplete is
 * the window a crash lands in. It is written to a temp file and moved into place; these pin that
 * the previous snapshot survives until the new one is whole, and that nothing is left lying around.
 */
class AtomicSnapshotTest {

    @TempDir
    Path directory;

    @Test
    void a_snapshot_round_trips_and_leaves_no_temp_file_behind() {
        Path file = directory.resolve("_state/state.json");
        JsonStateStore store = new JsonStateStore(file);

        store.snapshot(List.of(run("run-1")), List.of(task("task-1", "run-1")));

        JsonStateStore.State restored = store.restore();
        assertEquals(1, restored.runs.size());
        assertEquals("run-1", restored.runs.get(0).id);
        assertEquals(1, restored.tasks.size());
        assertEquals(TaskState.RUNNING, restored.tasks.get(0).state);
        assertFalse(Files.exists(directory.resolve("_state/state.json.tmp")),
                "the temp file must be moved onto the snapshot, not left beside it");
    }

    @Test
    void the_previous_snapshot_is_still_readable_while_the_next_one_is_being_written() throws IOException {
        Path file = directory.resolve("_state/state.json");
        JsonStateStore store = new JsonStateStore(file);
        store.snapshot(List.of(run("run-1")), List.of());

        // What a half-written tick looks like on disk: the temp file exists and is garbage, and the
        // live snapshot is untouched. A write-in-place would have truncated it by now.
        Files.writeString(directory.resolve("_state/state.json.tmp"), "{\"runs\":[{\"id\":\"run-2");

        assertEquals(1, store.restore().runs.size());
        assertEquals("run-1", store.restore().runs.get(0).id);

        store.snapshot(List.of(run("run-2")), List.of());
        assertEquals("run-2", store.restore().runs.get(0).id);
        assertTrue(Files.notExists(directory.resolve("_state/state.json.tmp")));
    }

    private static WorkflowRun run(String id) {
        return new WorkflowRun(id, "demo", Strategy.LINEAR, Map.of());
    }

    private static Task task(String id, String runId) {
        Task task = new Task(id, runId, 0, "session-" + id);
        task.state = TaskState.RUNNING;
        return task;
    }
}
