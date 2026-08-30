package tw.mcark.tony.fordism.orchestrate;

import tw.mcark.tony.fordism.field.Dispatcher;
import tw.mcark.tony.fordism.launch.SessionIdentifierFactory;
import tw.mcark.tony.fordism.model.run.WorkflowRun;
import tw.mcark.tony.fordism.model.run.WorkflowRunState;
import tw.mcark.tony.fordism.model.task.ChildRunRequest;
import tw.mcark.tony.fordism.model.task.Task;
import tw.mcark.tony.fordism.model.task.TaskState;
import tw.mcark.tony.fordism.model.workflow.Workflow;
import tw.mcark.tony.fordism.parse.WorkflowLoader;
import tw.mcark.tony.fordism.secret.SecretVault;
import tw.mcark.tony.fordism.store.InMemoryTaskRepository;
import tw.mcark.tony.fordism.store.InMemoryWorkflowRunRepository;
import tw.mcark.tony.fordism.workspace.TemplateStore;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A real {@link Engine} over in-memory repositories, driven one reconcile pass at a time.
 *
 * <p>The orchestrators take the concrete Engine, so there is nothing to mock: the harness builds
 * the real thing and supplies only the collaborators a reconcile actually reaches. Seeding calls
 * {@code sessions.newSessionId()} and {@code dispatcher.captureCredentials(task)}, and nothing
 * else — so the dispatcher is constructed with a template store pointed at a directory that does
 * not exist (its {@code all()} answers empty rather than touching the disk) and null for the
 * stager, launcher and field view, which only {@code sweep()} would reach. A null that a test
 * path can reach would be a lie; these cannot be reached, and a change that made them reachable
 * would fail here loudly rather than pass on a stub that quietly answered.
 *
 * <p>The agent, the container and the workspace are all absent on purpose. What is under test is
 * the decision an orchestrator makes when a task reaches a state, so the harness puts tasks into
 * those states directly, which is exactly what the Collector and Reaper do in production.
 */
final class OrchestratorHarness {

    private final InMemoryTaskRepository tasks = new InMemoryTaskRepository();
    private final InMemoryWorkflowRunRepository runs = new InMemoryWorkflowRunRepository();
    private final OrchestratorRegistry orchestrators = new OrchestratorRegistry();
    private final WorkflowLoader loader = new WorkflowLoader();
    private final Engine engine;

    OrchestratorHarness() {
        Dispatcher dispatcher = new Dispatcher(tasks, null,
                new TemplateStore(Paths.get("no-templates-in-this-harness"), null), null, null);
        this.engine = new Engine(null, tasks, runs, new SessionIdentifierFactory(), dispatcher,
                null, null, null, null, new SecretVault());
    }

    /** Register a workflow from its YAML and start a run of it. */
    WorkflowRun start(String yaml) {
        return start(yaml, Map.of());
    }

    WorkflowRun start(String yaml, Map<String, String> parameters) {
        return engine.createRun(register(yaml), parameters);
    }

    /** Register a workflow without starting it — what a child run's workflow needs to exist. */
    String register(String yaml) {
        Workflow workflow = loader.parse(yaml);
        engine.register(workflow);
        return workflow.name();
    }

    /**
     * One reconcile pass for this run's strategy — what {@code Engine.tick} does for one active
     * run, minus the ASKED park that {@code QuestionDiesWithRunTest} already covers.
     *
     * <p>The clock is walked to the next millisecond first. {@code Task.createdAt} is
     * {@code System.currentTimeMillis()}, and {@code ReworkOrchestrator.isStale} compares two of
     * them — in production successive seeds are a reconcile interval apart, but a test can plant
     * two tasks inside one millisecond, where "newer" is not expressible. This makes every pass
     * land in its own millisecond so the ordering under test is the one being asserted.
     */
    void reconcile(WorkflowRun run) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() == start) {
            Thread.onSpinWait();
        }
        orchestrators.get(run.strategy).reconcile(run, engine);
    }

    /** Reconcile until the run leaves ACTIVE, or give up — a stuck run must fail, not hang. */
    void reconcileUntilSettled(WorkflowRun run) {
        for (int pass = 0; pass < 50 && run.state == WorkflowRunState.ACTIVE; pass++) {
            reconcile(run);
        }
    }

    /**
     * The agent finished and the collector read a verdict off its result.
     *
     * <p>Both workspace paths are filled in because a collected task has been through dispatch, and
     * the host path is what says a session exists on disk to resume into.
     */
    void collect(WorkflowRun run, int stepIndex, String verdict) {
        Task task = at(run, stepIndex);
        task.state = TaskState.COLLECTED;
        task.verdict = verdict;
        task.workspacePath = "/fordism/workspaces/" + task.id + "/result";
        task.hostWorkspacePath = "/var/lib/fordism/workspaces/" + task.id;
        tasks.save(task);
    }

    /** What the agent wrote about its work — the text a rework in resume mode is handed back. */
    void summarise(WorkflowRun run, int stepIndex, String summary) {
        Task task = at(run, stepIndex);
        task.summary = summary;
        tasks.save(task);
    }

    /** The runs the agent named in its result — what the Collector carries onto a collected task. */
    void requestRuns(WorkflowRun run, int stepIndex, List<ChildRunRequest> requested) {
        Task task = at(run, stepIndex);
        task.childRuns = requested;
        tasks.save(task);
    }

    /**
     * The runs a reconciler spawned from this one, in a stable order.
     *
     * <p>createdAt then id, because an iteration starts every one of its children inside a single
     * tick — on the timestamp alone they tie, and a test that asserted on position would pass or
     * fail by the map's iteration order.
     */
    List<WorkflowRun> childrenOf(WorkflowRun run) {
        List<WorkflowRun> children = new ArrayList<>(runs.children(run.id));
        children.sort(Comparator.comparingLong((WorkflowRun child) -> child.createdAt)
                .thenComparing(child -> child.id));
        return children;
    }

    /** One named parameter from each of this run's children, for asserting on what was asked for. */
    List<String> childParameters(WorkflowRun run, String parameter) {
        List<String> values = new ArrayList<>();
        for (WorkflowRun child : childrenOf(run)) {
            values.add(child.parameterValues.get(parameter));
        }
        values.sort(String::compareTo);
        return values;
    }

    /** A child run reached its end, however it got there. */
    void finish(WorkflowRun child, WorkflowRunState state) {
        child.state = state;
        runs.save(child);
    }

    /** Collected with no verdict at all — the ordinary case for a step nothing branches on. */
    void collect(WorkflowRun run, int stepIndex) {
        collect(run, stepIndex, "");
    }

    /** The task died: FAILED is a dispatch or agent failure, REAPED is a container the reaper took. */
    void die(WorkflowRun run, int stepIndex, TaskState terminal) {
        Task task = at(run, stepIndex);
        task.state = terminal;
        tasks.save(task);
    }

    /** The latest task planted at that step. */
    Task at(WorkflowRun run, int stepIndex) {
        return find(run, stepIndex).orElseThrow(() ->
                new AssertionError("no task was planted at step " + stepIndex + " of run " + run.id));
    }

    Optional<Task> find(WorkflowRun run, int stepIndex) {
        return engine.currentTask(run, stepIndex);
    }

    /** How many tasks this run has planted in total, across every step and attempt. */
    int plantedCount(WorkflowRun run) {
        return tasks.byRun(run.id).size();
    }
}
