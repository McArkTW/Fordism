package tw.mcark.tony.fordism.orchestrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tw.mcark.tony.fordism.agentprofile.AgentProfile;
import tw.mcark.tony.fordism.agentprofile.AgentTool;
import tw.mcark.tony.fordism.config.FordismConfiguration;
import tw.mcark.tony.fordism.field.Dispatcher;
import tw.mcark.tony.fordism.field.FieldView;
import tw.mcark.tony.fordism.launch.ContainerLauncher;
import tw.mcark.tony.fordism.launch.ModelRegistry;
import tw.mcark.tony.fordism.launch.SessionIdentifierFactory;
import tw.mcark.tony.fordism.model.run.WorkflowRun;
import tw.mcark.tony.fordism.model.task.Task;
import tw.mcark.tony.fordism.model.task.TaskMode;
import tw.mcark.tony.fordism.model.task.TaskState;
import tw.mcark.tony.fordism.parse.WorkflowLoader;
import tw.mcark.tony.fordism.secret.SecretVault;
import tw.mcark.tony.fordism.store.InMemoryTaskRepository;
import tw.mcark.tony.fordism.store.InMemoryWorkflowRunRepository;
import tw.mcark.tony.fordism.workspace.ArtifactStore;
import tw.mcark.tony.fordism.workspace.TaskResults;
import tw.mcark.tony.fordism.workspace.TemplateStore;
import tw.mcark.tony.fordism.workspace.WorkspaceStager;

/**
 * A step that continues the previous step's agent, from the orchestrator's seed through the
 * dispatcher's hand-over to what core reads back afterwards.
 *
 * <p>The first step's workspace is laid out by hand, as the stager would have — its real path is
 * the container root {@code /workspaces}, which a test cannot write — and everything after that is
 * the real dispatcher and stager on a temporary directory. Only the container is fake: the launcher
 * records what it was asked to start.
 */
class ContinuesRunTest {

    private static final String THREE_STEPS = """
            name: propose-apply-report
            steps:
              - id: propose
                task: propose a change
              - id: apply
                task: apply the change
                continues: true
              - id: report
                task: report on it
                includePreviousResult: true
            """;

    @TempDir
    Path directory;

    private final InMemoryTaskRepository tasks = new InMemoryTaskRepository();
    private final InMemoryWorkflowRunRepository runs = new InMemoryWorkflowRunRepository();
    private final OrchestratorRegistry orchestrators = new OrchestratorRegistry();
    private final List<Task> launched = new ArrayList<>();
    private Engine engine;
    private Dispatcher dispatcher;

    /** The engine and dispatcher, with the profile every task resolves to (the sole one). */
    private void wire(Supplier<Optional<AgentProfile>> sole) {
        FordismConfiguration configuration = new FordismConfiguration();
        ModelRegistry models = new ModelRegistry(configuration, name -> Optional.empty(), sole);
        ArtifactStore artifacts = new ArtifactStore(directory.resolve("_artifacts"), directory.resolve("_artifacts"),
                configuration.agentGroupId);
        TemplateStore templates = new TemplateStore(directory.resolve("no-templates"), null);
        dispatcher = new Dispatcher(tasks, new WorkspaceStager(configuration, models, artifacts), templates,
                new RecordingLauncher(), new FieldView(tasks, configuration));
        engine = new Engine(null, tasks, runs, new SessionIdentifierFactory(), dispatcher,
                null, null, null, null, new SecretVault());
    }

    private WorkflowRun start() {
        engine.register(new WorkflowLoader().parse(THREE_STEPS));
        return engine.createRun("propose-apply-report", java.util.Map.of());
    }

    private void reconcile(WorkflowRun run) {
        orchestrators.get(run.strategy).reconcile(run, engine);
    }

    /** What the stager and the agent would have left for a finished step. */
    private Task finishFirstStep(WorkflowRun run) throws IOException {
        reconcile(run);
        Task first = engine.currentTask(run, 0).orElseThrow();
        Path workspace = directory.resolve(first.id);
        Files.createDirectories(workspace.resolve("task"));
        Files.writeString(workspace.resolve("task/task.md"), "propose a change");
        Files.createDirectories(workspace.resolve("result"));
        Files.writeString(workspace.resolve("result/result.json"),
                "{\"state\":\"finished\",\"summary\":\"proposed\",\"verdict\":\"pass\"}");
        Files.writeString(workspace.resolve("result/proposal.md"), "the proposal");
        Files.createDirectories(workspace.resolve(".claude/projects"));
        Files.writeString(workspace.resolve(".claude/projects/session.jsonl"), "{}");
        first.workspacePath = workspace.toString();
        first.hostWorkspacePath = "/host/" + first.id;
        first.state = TaskState.COLLECTED;
        tasks.save(first);
        return first;
    }

    /** Finish whatever the dispatcher just started at this step, as its agent would. */
    private Task finish(WorkflowRun run, int step, String summary) throws IOException {
        Task task = engine.currentTask(run, step).orElseThrow();
        Files.writeString(task.resultDirectory().resolve("result.json"),
                "{\"state\":\"finished\",\"summary\":\"" + summary + "\"}");
        Files.writeString(task.resultDirectory().resolve(summary + ".md"), summary);
        task.state = TaskState.COLLECTED;
        tasks.save(task);
        return task;
    }

    private static Optional<AgentProfile> profile(AgentTool tool) {
        return Optional.of(new AgentProfile("p", "the-profile", "https://example.test", "k", "m", tool, null));
    }

    @Test
    void the_continuing_step_reuses_the_workspace_and_the_session_and_the_first_result_survives() throws IOException {
        wire(() -> profile(AgentTool.CLAUDE_CODE));
        WorkflowRun run = start();
        Task first = finishFirstStep(run);

        reconcile(run);   // advance past step 0
        reconcile(run);   // seed step 1
        Task second = engine.currentTask(run, 1).orElseThrow();
        assertEquals(first.id, second.continuesTaskId);
        assertEquals(first.sessionId, second.sessionId, "the same session id, so the CLI resumes it");
        assertEquals(TaskMode.RESUME, second.mode);
        assertNotEquals(first.id, second.id, "a new task — only the agent carries over");

        dispatcher.sweep();

        assertEquals(1, launched.size());
        assertEquals(first.workspacePath, second.workspacePath, "the same workspace");
        assertEquals(first.hostWorkspacePath, second.hostWorkspacePath);
        assertEquals(Boolean.TRUE, second.conversationKept);
        Path workspace = Path.of(first.workspacePath);
        assertEquals("apply the change", Files.readString(workspace.resolve("task/task.md")));
        assertTrue(Files.isDirectory(workspace.resolve("result")));
        try (java.util.stream.Stream<Path> left = Files.list(workspace.resolve("result"))) {
            assertEquals(0, left.count(), "result/ is empty for the new step");
        }
        assertTrue(Files.isRegularFile(workspace.resolve(".claude/projects/session.jsonl")), "the session store stays");

        assertEquals("steps/0", first.signOff);
        assertEquals("the proposal", Files.readString(workspace.resolve("steps/0/result/proposal.md")));
        assertEquals("propose a change", Files.readString(workspace.resolve("steps/0/task/task.md")));
        TaskResults results = new TaskResults();
        assertEquals("proposed", results.read(first).orElseThrow().summary(), "core reads step 0 where it went");
        assertTrue(results.files(first).stream().anyMatch(file -> file.name().equals("proposal.md")));
        assertTrue(results.read(second).isEmpty(), "and not the new step's empty result as step 0's");
    }

    @Test
    void the_step_after_a_continued_step_still_gets_that_steps_result() throws IOException {
        wire(() -> profile(AgentTool.CLAUDE_CODE));
        WorkflowRun run = start();
        finishFirstStep(run);
        reconcile(run);
        reconcile(run);
        dispatcher.sweep();
        Task second = finish(run, 1, "applied");

        reconcile(run);   // advance past step 1
        reconcile(run);   // seed step 2
        Task third = engine.currentTask(run, 2).orElseThrow();
        assertNull(third.continuesTaskId);
        Path into = directory.resolve("third/task/previous-result");
        WorkspaceStager.stagePrevious(third, into);

        assertEquals(second.signOffPath(), third.previousWorkspace);
        assertEquals("applied", Files.readString(into.resolve("applied.md")), "step 1's result, not step 0's");
        assertFalse(Files.exists(into.resolve("proposal.md")));
    }

    @Test
    void a_queued_reader_follows_a_sign_off_that_was_set_aside() throws IOException {
        // Step 2 both continues step 1 and asks for its result: the seed points at the top of the
        // workspace, the hand-over moves step 1's result to steps/1, and the copy must follow it.
        wire(() -> profile(AgentTool.CLAUDE_CODE));
        engine.register(new WorkflowLoader().parse("""
                name: three-in-one
                steps:
                  - id: propose
                    task: propose
                  - id: apply
                    task: apply
                    continues: true
                  - id: polish
                    task: polish
                    continues: true
                    includePreviousResult: true
                """));
        WorkflowRun run = engine.createRun("three-in-one", java.util.Map.of());
        Task first = finishFirstStep(run);
        reconcile(run);
        reconcile(run);
        dispatcher.sweep();
        finish(run, 1, "applied");
        reconcile(run);
        reconcile(run);
        dispatcher.sweep();

        Path workspace = Path.of(first.workspacePath);
        Task third = engine.currentTask(run, 2).orElseThrow();
        assertEquals(first.sessionId, third.sessionId, "the same agent all the way down");
        assertEquals("applied", Files.readString(workspace.resolve("task/previous-result/applied.md")));
        assertEquals("applied", Files.readString(workspace.resolve("steps/1/result/applied.md")));
        assertEquals("the proposal", Files.readString(workspace.resolve("steps/0/result/proposal.md")));
    }

    @Test
    void a_one_shot_tool_gets_the_workspace_and_a_fresh_conversation() throws IOException {
        wire(() -> profile(AgentTool.AIDER));
        WorkflowRun run = start();
        Task first = finishFirstStep(run);
        reconcile(run);
        reconcile(run);
        dispatcher.sweep();

        Task second = engine.currentTask(run, 1).orElseThrow();
        assertEquals(first.workspacePath, second.workspacePath, "its earlier files are where it left them");
        assertEquals(Boolean.FALSE, second.conversationKept, "but the conversation starts fresh");
        assertEquals("the proposal",
                Files.readString(Path.of(first.workspacePath).resolve("steps/0/result/proposal.md")));
    }

    /** Records what it was asked to start; nothing runs. */
    private final class RecordingLauncher implements ContainerLauncher {
        public String launch(Task task) {
            launched.add(task);
            return "fd-" + task.id;
        }

        public boolean isRunning(String containerId) {
            return true;
        }

        public void kill(String containerId) {
            // nothing to kill
        }

        public void remove(String containerId) {
            // nothing to remove
        }
    }
}
