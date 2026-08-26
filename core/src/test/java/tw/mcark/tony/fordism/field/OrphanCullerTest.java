package tw.mcark.tony.fordism.field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tw.mcark.tony.fordism.launch.ContainerLauncher;
import tw.mcark.tony.fordism.model.run.WorkflowRun;
import tw.mcark.tony.fordism.model.run.WorkflowRunState;
import tw.mcark.tony.fordism.model.task.Task;
import tw.mcark.tony.fordism.model.task.TaskState;
import tw.mcark.tony.fordism.model.workflow.Strategy;
import tw.mcark.tony.fordism.store.InMemoryTaskRepository;
import tw.mcark.tony.fordism.store.InMemoryWorkflowRunRepository;
import tw.mcark.tony.fordism.store.TaskRepository;
import tw.mcark.tony.fordism.store.WorkflowRunRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** No task may still be live under a run that has ended. */
class OrphanCullerTest {

    /** Records what was killed so the test can assert the container was actually dealt with. */
    private static final class RecordingLauncher implements ContainerLauncher {
        private final List<String> killed = new ArrayList<>();
        private final List<String> removed = new ArrayList<>();

        public String launch(Task task) {
            throw new UnsupportedOperationException("the culler never launches");
        }

        public boolean isRunning(String containerId) {
            return true;
        }

        public void kill(String containerId) {
            killed.add(containerId);
        }

        public void remove(String containerId) {
            removed.add(containerId);
        }
    }

    private TaskRepository tasks;
    private WorkflowRunRepository runs;
    private RecordingLauncher launcher;
    private OrphanCuller culler;

    @BeforeEach
    void setUp() {
        tasks = new InMemoryTaskRepository();
        runs = new InMemoryWorkflowRunRepository();
        launcher = new RecordingLauncher();
        culler = new OrphanCuller(tasks, runs, launcher);
    }

    private WorkflowRun run(String id, WorkflowRunState state) {
        WorkflowRun run = new WorkflowRun(id, "qc-linear", Strategy.LINEAR, Map.of());
        run.state = state;
        runs.save(run);
        return run;
    }

    private Task task(String id, String runId, TaskState state) {
        Task task = new Task(id, runId, 0, "session-" + id);
        task.state = state;
        task.containerId = "fd-" + id;
        tasks.save(task);
        return task;
    }

    @Test
    void a_running_task_under_an_abandoned_run_is_culled_and_its_container_killed() {
        run("r1", WorkflowRunState.ABANDONED);
        task("t1", "r1", TaskState.RUNNING);

        culler.sweep();

        assertEquals(TaskState.REAPED, tasks.find("t1").orElseThrow().state);
        assertTrue(tasks.find("t1").orElseThrow().error.contains("abandoned"));
        assertEquals(List.of("fd-t1"), launcher.killed);
        assertEquals(List.of("fd-t1"), launcher.removed);
    }

    @Test
    void a_queued_task_is_culled_too_or_the_dispatcher_would_start_it() {
        run("r1", WorkflowRunState.ABANDONED);
        task("t1", "r1", TaskState.PENDING);

        culler.sweep();

        assertEquals(TaskState.REAPED, tasks.find("t1").orElseThrow().state);
    }

    @Test
    void the_task_is_marked_before_the_container_is_touched() {
        // The order is the whole trick: kill first and the Reaper sees a dead container, decides
        // the task rotted, and re-queues it. Once it is not RUNNING, no other sweep can see it.
        run("r1", WorkflowRunState.ABANDONED);
        task("t1", "r1", TaskState.RUNNING);

        culler.sweep();

        assertTrue(tasks.byState(TaskState.RUNNING).isEmpty());
        assertTrue(tasks.byState(TaskState.PENDING).isEmpty());
    }

    @Test
    void a_failed_run_leaves_no_container_behind_either() {
        // Predates abandonment: an orchestrator failing a run while another step ran left that
        // container alive, holding a launcher slot, with nothing ever collecting it.
        run("r1", WorkflowRunState.FAILED);
        task("t1", "r1", TaskState.RUNNING);

        culler.sweep();

        assertEquals(TaskState.REAPED, tasks.find("t1").orElseThrow().state);
        assertEquals(List.of("fd-t1"), launcher.killed);
    }

    @Test
    void a_live_run_is_left_completely_alone() {
        run("r1", WorkflowRunState.ACTIVE);
        task("t1", "r1", TaskState.RUNNING);
        task("t2", "r1", TaskState.PENDING);

        culler.sweep();

        assertEquals(TaskState.RUNNING, tasks.find("t1").orElseThrow().state);
        assertEquals(TaskState.PENDING, tasks.find("t2").orElseThrow().state);
        assertTrue(launcher.killed.isEmpty());
    }

    @Test
    void a_parked_run_is_not_terminal_so_its_work_survives_being_answered() {
        // ASKED means waiting on a human, not over. Culling here would destroy the session the
        // answer is about to resume.
        run("r1", WorkflowRunState.ASKED);
        task("t1", "r1", TaskState.PENDING);

        culler.sweep();

        assertEquals(TaskState.PENDING, tasks.find("t1").orElseThrow().state);
        assertTrue(launcher.killed.isEmpty());
    }

    @Test
    void sweeping_twice_changes_nothing_the_second_time() {
        run("r1", WorkflowRunState.ABANDONED);
        task("t1", "r1", TaskState.RUNNING);

        culler.sweep();
        culler.sweep();

        assertEquals(1, launcher.killed.size());
    }

    @Test
    void an_already_finished_task_is_not_disturbed() {
        run("r1", WorkflowRunState.ABANDONED);
        task("t1", "r1", TaskState.COLLECTED);

        culler.sweep();

        assertEquals(TaskState.COLLECTED, tasks.find("t1").orElseThrow().state);
        assertTrue(launcher.killed.isEmpty());
    }
}
