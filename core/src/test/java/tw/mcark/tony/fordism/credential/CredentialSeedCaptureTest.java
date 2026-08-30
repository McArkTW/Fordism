package tw.mcark.tony.fordism.credential;

import static org.junit.jupiter.api.Assertions.assertEquals;

import tw.mcark.tony.fordism.field.Dispatcher;
import tw.mcark.tony.fordism.launch.SessionIdentifierFactory;
import tw.mcark.tony.fordism.model.run.WorkflowRun;
import tw.mcark.tony.fordism.model.task.Task;
import tw.mcark.tony.fordism.model.task.TaskConfiguration;
import tw.mcark.tony.fordism.model.task.TaskSeed;
import tw.mcark.tony.fordism.model.workflow.Strategy;
import tw.mcark.tony.fordism.orchestrate.Engine;
import tw.mcark.tony.fordism.secret.SecretVault;
import tw.mcark.tony.fordism.store.InMemoryTaskRepository;
import tw.mcark.tony.fordism.store.InMemoryWorkflowRunRepository;
import tw.mcark.tony.fordism.workspace.AgentTemplate;
import tw.mcark.tony.fordism.workspace.TemplateStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The capture rule behind the grant: a task's credential list is fixed the moment the task is
 * SEEDED — editing the template afterwards changes nothing for a task already in the queue —
 * while a reseed (a retry is a new seed) reads the template as of that later moment.
 */
class CredentialSeedCaptureTest {

    @TempDir
    Path directory;

    private InMemoryTaskRepository tasks;
    private TemplateStore templates;
    private Engine engine;
    private WorkflowRun run;
    private String templateId;

    @BeforeEach
    void setUp() throws IOException {
        tasks = new InMemoryTaskRepository();
        InMemoryWorkflowRunRepository runs = new InMemoryWorkflowRunRepository();
        // Skill staging is never reached here, so the store needs no SkillStore.
        templates = new TemplateStore(directory, null);
        templateId = templates.create(template(List.of("GITHUB_TOKEN")));
        // Seeding needs only the dispatcher's credential capture; the rest of the field
        // machinery can stay null.
        Dispatcher dispatcher = new Dispatcher(tasks, null, templates, null, null);
        engine = new Engine(null, tasks, runs, new SessionIdentifierFactory(), dispatcher,
                null, null, null, null, new SecretVault());
        run = new WorkflowRun("run-1", "qc-linear", Strategy.LINEAR, Map.of());
        runs.save(run);
    }

    @Test
    void editing_a_template_after_seeding_does_not_change_a_queued_tasks_credentials() throws IOException {
        Task queued = engine.seedTask(run, seed(1));
        assertEquals(List.of("GITHUB_TOKEN"), queued.credentials);

        templates.update(templateId, template(List.of("GITHUB_TOKEN", "PUSH_TOKEN")));

        assertEquals(List.of("GITHUB_TOKEN"), tasks.find(queued.id).orElseThrow().credentials,
                "the grant was captured at seed time; a later template edit must not widen it");
    }

    @Test
    void a_reseed_captures_the_template_as_of_reseed_time() throws IOException {
        Task first = engine.seedTask(run, seed(1));
        templates.update(templateId, template(List.of("PUSH_TOKEN")));

        Task second = engine.seedTask(run, seed(2));

        assertEquals(List.of("GITHUB_TOKEN"), tasks.find(first.id).orElseThrow().credentials,
                "the earlier seed keeps what it captured");
        assertEquals(List.of("PUSH_TOKEN"), second.credentials,
                "a retry is a NEW seed, so it reads the template as it stands now");
    }

    private AgentTemplate template(List<String> credentialKeys) {
        return new AgentTemplate(templateId, "worker", "", "", List.of(), credentialKeys, "");
    }

    private TaskSeed seed(int attempt) {
        return new TaskSeed(0, "worker", "do the thing", false, null,
                TaskConfiguration.defaults(), attempt);
    }
}
