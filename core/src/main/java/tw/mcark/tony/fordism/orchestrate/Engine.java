package tw.mcark.tony.fordism.orchestrate;

import tw.mcark.tony.fordism.config.FordismConfiguration;
import tw.mcark.tony.fordism.field.Collector;
import tw.mcark.tony.fordism.field.Dispatcher;
import tw.mcark.tony.fordism.field.OrphanCuller;
import tw.mcark.tony.fordism.field.Reaper;
import tw.mcark.tony.fordism.launch.SessionIdentifierFactory;
import tw.mcark.tony.fordism.model.run.WorkflowRun;
import tw.mcark.tony.fordism.model.run.WorkflowRunState;
import tw.mcark.tony.fordism.secret.SecretVault;
import tw.mcark.tony.fordism.model.task.Task;
import tw.mcark.tony.fordism.model.task.TaskSeed;
import tw.mcark.tony.fordism.model.workflow.Step;
import tw.mcark.tony.fordism.model.workflow.Workflow;
import tw.mcark.tony.fordism.parse.WorkflowLoader;
import tw.mcark.tony.fordism.store.JsonStateStore;
import tw.mcark.tony.fordism.store.TaskRepository;
import tw.mcark.tony.fordism.store.WorkflowRunRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.tinylog.Logger;
import java.util.Optional;
import tw.mcark.tony.fordism.model.task.TaskState;

/** Holds the field mechanism + repositories and drives the reconcile tick. */
public final class Engine {
    private final FordismConfiguration configuration;
    private final TaskRepository tasks;
    private final WorkflowRunRepository runs;
    private final SessionIdentifierFactory sessions;
    private final Dispatcher dispatcher;
    private final Collector collector;
    private final Reaper reaper;
    private final OrphanCuller culler;
    private final JsonStateStore stateStore;
    private final SecretVault secrets;
    private final OrchestratorRegistry orchestrators = new OrchestratorRegistry();
    private final Map<String, Workflow> workflows = new HashMap<>();
    private final Map<String, String> workflowYaml = new HashMap<>();
    private final WorkflowLoader loader = new WorkflowLoader();
    private final AtomicLong seq = new AtomicLong();

    public Engine(FordismConfiguration configuration, TaskRepository tasks, WorkflowRunRepository runs,
                  SessionIdentifierFactory sessions, Dispatcher dispatcher, Collector collector, Reaper reaper,
                  OrphanCuller culler, JsonStateStore stateStore, SecretVault secrets) {
        this.configuration = configuration;
        this.tasks = tasks;
        this.runs = runs;
        this.sessions = sessions;
        this.dispatcher = dispatcher;
        this.collector = collector;
        this.reaper = reaper;
        this.culler = culler;
        this.stateStore = stateStore;
        this.secrets = secrets;
    }

    /* ---- workflow registry ---- */
    public void register(Workflow workflow) { workflows.put(workflow.name(), workflow); }
    public Optional<Workflow> workflow(String name) { return Optional.ofNullable(workflows.get(name)); }

    /**
     * The definition a run is executing. Deleting a workflow with a run in flight leaves the run
     * with nothing to advance through, so this says so loudly rather than letting an orchestrator
     * dereference nothing — {@link #tick()} turns the throw into a FAILED run with the reason.
     */
    public Workflow workflowFor(WorkflowRun run) {
        return workflow(run.workflowName).orElseThrow(() -> new IllegalStateException(
                "run " + run.id + " references workflow \"" + run.workflowName + "\", which no longer exists"));
    }
    public List<Workflow> allWorkflows() { return new ArrayList<>(workflows.values()); }

    /* ---- workflow CRUD (definitions are YAML) ---- */
    public String workflowYaml(String name) { return workflowYaml.get(name); }
    public void storeYaml(String name, String yaml) { workflowYaml.put(name, yaml); }

    /** Parse without registering or writing — used to check a definition before it is stored. */
    public Workflow parse(String yaml) {
        return loader.parse(yaml);
    }

    public Workflow upsert(String yaml) throws IOException {
        Workflow workflow = loader.parse(yaml);
        if (workflow.name() == null || workflow.name().isBlank()) {
            throw new IllegalArgumentException("workflow needs a name");
        }
        workflows.put(workflow.name(), workflow);
        workflowYaml.put(workflow.name(), yaml);
        Files.createDirectories(Paths.get(configuration.userWorkflowsDir));
        Files.writeString(Paths.get(configuration.userWorkflowsDir, workflow.name() + ".yaml"), yaml);
        Logger.info("workflow upserted {}", workflow.name());
        return workflow;
    }

    public void delete(String name) throws IOException {
        workflows.remove(name);
        workflowYaml.remove(name);
        Files.deleteIfExists(Paths.get(configuration.userWorkflowsDir, name + ".yaml"));
        Logger.info("workflow deleted {}", name);
    }

    /* ---- accessors used by web / loop ---- */
    public TaskRepository tasks() { return tasks; }
    public WorkflowRunRepository runs() { return runs; }
    public long reconcileIntervalMillis() { return configuration.reconcileIntervalMillis; }

    /* ---- run creation ---- */
    public WorkflowRun createRun(String workflowName, Map<String, String> params) {
        return createRun(workflowName, params, null);
    }

    public WorkflowRun createRun(String workflowName, Map<String, String> params, String taskZipPath) {
        Workflow workflow = workflow(workflowName)
                .orElseThrow(() -> new IllegalArgumentException("unknown workflow: " + workflowName));
        WorkflowRun run = new WorkflowRun(java.util.UUID.randomUUID().toString(), workflowName, workflow.strategy(), params);
        run.taskZipPath = taskZipPath;
        run.workflowSnapshot = workflowYaml.get(workflowName);
        runs.save(run);
        Logger.info("run created {} workflow={} strategy={} zip={}", run.id, workflowName, workflow.strategy(), taskZipPath != null);
        return run;
    }

    /* ---- helpers used by orchestrators ---- */
    /** The latest attempt at that step, or empty when nothing has been planted there yet. */
    public Optional<Task> currentTask(WorkflowRun run, int stepIndex) {
        return tasks.byRunStep(run.id, stepIndex);
    }

    /** Plant a task for a resolved step. Always in work mode — resume is re-arming an existing task. */
    public Task seedTask(WorkflowRun run, TaskSeed seed) {
        Task task = new Task(UUID.randomUUID().toString(), run.id, seed.stepIndex(), sessions.newSessionId());
        task.template = seed.template();
        task.taskText = seed.taskText();
        task.includePreviousResult = seed.includePreviousResult();
        task.previousWorkspace = seed.previousWorkspace();
        task.config = seed.config();
        task.attempt = seed.attempt();
        if (seed.stepIndex() == 0 && run.taskZipPath != null) {
            task.taskZipPath = run.taskZipPath;
        }
        // The credential grant is captured NOW, while the task is being seeded: editing the
        // template after this moment must not change what this already-queued task receives.
        dispatcher.captureCredentials(task);
        tasks.save(task);
        Logger.info("seed run {} step {} template={} attempt={}",
                run.id, seed.stepIndex(), seed.template(), seed.attempt());
        return task;
    }

    /**
     * Plant the run's CURRENT step, feeding it the previous step's workspace when the step asked
     * for it. Every strategy that advances in order does exactly this, always at
     * {@code run.currentStepIndex} — a strategy whose "previous" means something else (graph's first
     * dependency, map-reduce's first map task) seeds through {@link #seedTask} and says so.
     */
    public Task seedStep(WorkflowRun run, Step step, int attempt) {
        int stepIndex = run.currentStepIndex;
        String previousWorkspace = null;
        if (step.includePreviousResult() && stepIndex > 0) {
            previousWorkspace = currentTask(run, stepIndex - 1).map(previous -> previous.workspacePath).orElse(null);
        }
        return seedTask(run, TaskSeed.of(stepIndex, step, run.parameterValues, previousWorkspace, attempt));
    }

    public void finishRun(WorkflowRun run, WorkflowRunState state) {
        run.state = state;
        runs.save(run);
        // A supplied credential exists to get THIS run moving; past its last task there is nothing
        // left to spend it on, so drop it rather than hold a live token for the process's lifetime.
        //
        // ASKED is explicitly not that. This same method parks a run awaiting an answer (see
        // tick()), so clearing on every state would wipe what the human supplied at the FIRST
        // question the moment a later task asked another — and the credential would be gone from
        // every container after it, for a run the human believed they had already answered.
        if (state.isTerminal()) {
            secrets.clear(run.id);
            // A question in the inbox is a promise that answering it helps (see TaskState). A
            // terminal run can never use an answer, so its asked task leaves the inbox with it —
            // otherwise an abandoned run's question lingers forever and answering it resumes a
            // session whose output nothing will collect.
            for (Task task : tasks.byRun(run.id)) {
                if (task.state == TaskState.ASKED) {
                    task.state = TaskState.REAPED;
                    tasks.save(task);
                    Logger.info("task {} reaped: its run ended {}", task.id, state);
                }
            }
        }
        Logger.info("run {} -> {}", run.id, state);
    }

    public void saveRun(WorkflowRun run) { runs.save(run); }

    public List<String> paramList(WorkflowRun run, String name) {
        String value = run.parameterValues.getOrDefault(name, "").trim().replaceAll("^\\[|\\]$", "");
        List<String> out = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim().replaceAll("^[\"']|[\"']$", "");
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    /** True if any of the run's tasks stopped to ask something. */
    private boolean awaitingAnswer(WorkflowRun run) {
        return tasks.byRun(run.id).stream().anyMatch(task -> task.state == TaskState.ASKED);
    }

    public static int indexOfStep(Workflow workflow, String id) {
        for (int i = 0; i < workflow.steps().size(); i++) {
            if (workflow.steps().get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    /* ---- the tick: sensors first, then orchestrators, then dispatch ---- */

    /**
     * One reconcile pass. Every phase is isolated, because the loop has no supervisor: a throw
     * anywhere used to abandon the rest of the tick, so a single bad task stopped collection,
     * reaping and dispatch for every task on the instance. {@code Throwable}, not {@code Exception}
     * — a StackOverflowError in one helper did exactly that once, and an Error is no less fatal to
     * the pass than an exception.
     */
    public void tick() {
        guard("collect", collector::sweep);
        guard("reap", reaper::sweep);
        guard("cull", culler::sweep);
        guard("reapOrphanQuestions", this::reapOrphanQuestions);
        guard("orchestrate", this::orchestrateActiveRuns);
        guard("dispatch", dispatcher::sweep);
        guard("snapshot", () -> stateStore.snapshot(runs.all(), tasks.all()));
    }

    /**
     * Level-triggered backstop for the rule in {@link #finishRun}: a question whose run has ended
     * — or whose run the store no longer knows — leaves the inbox. Heals tasks orphaned before
     * that rule existed, and any edge a crash ever skips.
     */
    void reapOrphanQuestions() {
        for (Task task : tasks.byState(TaskState.ASKED)) {
            boolean runEnded = runs.find(task.runId)
                    .map(run -> run.state.isTerminal()).orElse(true);
            if (runEnded) {
                task.state = TaskState.REAPED;
                tasks.save(task);
                Logger.info("task {} reaped: its run is gone or ended", task.id);
            }
        }
    }

    private void guard(String phase, Runnable work) {
        try {
            work.run();
        } catch (Throwable t) {
            Logger.error(t, "reconcile phase {} failed", phase);
        }
    }

    private void orchestrateActiveRuns() {
        for (WorkflowRun run : runs.active()) {
            if (awaitingAnswer(run)) {
                finishRun(run, WorkflowRunState.ASKED);   // pause until someone answers
                continue;
            }
            Orchestrator orchestrator = orchestrators.get(run.strategy);
            try {
                orchestrator.reconcile(run, this);
            } catch (Exception e) {
                Logger.error(e, "orchestrator failed for run {}", run.id);
                finishRun(run, WorkflowRunState.FAILED);
            }
        }
    }
}
