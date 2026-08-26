package tw.mcark.tony.fordism;

import tw.mcark.tony.fordism.agentprofile.AgentProfileStore;
import tw.mcark.tony.fordism.config.FordismConfiguration;
import tw.mcark.tony.fordism.field.Collector;
import tw.mcark.tony.fordism.field.CullPolicy;
import tw.mcark.tony.fordism.field.Dispatcher;
import tw.mcark.tony.fordism.field.FieldView;
import tw.mcark.tony.fordism.field.OrphanCuller;
import tw.mcark.tony.fordism.field.Reaper;
import tw.mcark.tony.fordism.launch.ContainerLauncher;
import tw.mcark.tony.fordism.launch.DockerContainerLauncher;
import tw.mcark.tony.fordism.launch.ModelRegistry;
import tw.mcark.tony.fordism.launch.SessionIdentifierFactory;
import tw.mcark.tony.fordism.model.run.WorkflowRun;
import tw.mcark.tony.fordism.model.task.Task;
import tw.mcark.tony.fordism.model.workflow.Workflow;
import tw.mcark.tony.fordism.orchestrate.Engine;
import tw.mcark.tony.fordism.orchestrate.ReconcileLoop;
import tw.mcark.tony.fordism.parse.WorkflowLoader;
import tw.mcark.tony.fordism.skill.SkillState;
import tw.mcark.tony.fordism.skill.SkillStore;
import tw.mcark.tony.fordism.store.InMemoryTaskRepository;
import tw.mcark.tony.fordism.store.InMemoryWorkflowRunRepository;
import tw.mcark.tony.fordism.store.JsonStateStore;
import tw.mcark.tony.fordism.store.TaskRepository;
import tw.mcark.tony.fordism.store.WorkflowRunRepository;
import tw.mcark.tony.fordism.web.App;
import tw.mcark.tony.fordism.workspace.TemplateStore;
import tw.mcark.tony.fordism.workspace.TaskResults;
import tw.mcark.tony.fordism.workspace.WorkspaceArchive;
import tw.mcark.tony.fordism.workspace.WorkspaceStager;
import tw.mcark.tony.fordism.credential.CredentialStore;
import tw.mcark.tony.fordism.secret.SecretVault;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;
import org.tinylog.Logger;

/** Composition root: wire the field + engine, start the reconcile loop and the Javalin app. */
public final class Fordism {
    private Fordism() {}

    public static void main(String[] args) {
        FordismConfiguration configuration = new FordismConfiguration();
        TaskRepository tasks = new InMemoryTaskRepository();
        WorkflowRunRepository runs = new InMemoryWorkflowRunRepository();
        SessionIdentifierFactory sessions = new SessionIdentifierFactory();
        AgentProfileStore profiles = new AgentProfileStore(configuration);
        ModelRegistry models = new ModelRegistry(configuration, profiles);
        WorkspaceStager workspaces = new WorkspaceStager(configuration);
        TaskResults results = new TaskResults();
        WorkspaceArchive archive = new WorkspaceArchive();
        SkillState skillState = new SkillState(configuration);
        SkillStore skills = new SkillStore(configuration, skillState);
        TemplateStore templates = new TemplateStore(configuration, skills);
        templates.migrateAndSeed();
        SecretVault secrets = new SecretVault();
        CredentialStore credentials = new CredentialStore(configuration);
        ContainerLauncher launcher = new DockerContainerLauncher(configuration, models, secrets, credentials);
        FieldView field = new FieldView(tasks, configuration);
        Dispatcher dispatcher = new Dispatcher(tasks, workspaces, templates, launcher, field);
        Collector collector = new Collector(tasks, results, launcher);
        Reaper reaper = new Reaper(tasks, results, launcher, new CullPolicy(), configuration);
        OrphanCuller culler = new OrphanCuller(tasks, runs, launcher);
        JsonStateStore stateStore = new JsonStateStore(configuration);
        Engine engine = new Engine(configuration, tasks, runs, sessions, dispatcher, collector, reaper,
                culler, stateStore, secrets);

        loadWorkflows(engine, configuration);
        restoreState(stateStore, tasks, runs);

        Thread loop = new Thread(new ReconcileLoop(engine), "reconcile-loop");
        loop.setDaemon(true);
        loop.start();

        new App(engine, configuration, templates, results, archive, skills, profiles, secrets, credentials).start();
        Logger.info("fordism-core up version={} gitSha={}", configuration.version, configuration.gitSha);
    }

    private static void loadWorkflows(Engine engine, FordismConfiguration configuration) {
        loadWorkflowDir(engine, configuration.workflowsDir);      // built-in defaults
        loadWorkflowDir(engine, configuration.userWorkflowsDir);  // persisted user workflows (override)
    }

    private static void loadWorkflowDir(Engine engine, String dirPath) {
        Path dir = Paths.get(dirPath);
        if (!Files.isDirectory(dir)) {
            return;
        }
        WorkflowLoader loader = new WorkflowLoader();
        try (Stream<Path> listing = Files.list(dir)) {
            for (Path file : (Iterable<Path>) listing.filter(p -> p.toString().endsWith(".yaml"))::iterator) {
                try {
                    Workflow workflow = loader.load(file);
                    engine.register(workflow);
                    engine.storeYaml(workflow.name(), Files.readString(file));
                    Logger.info("loaded workflow {} ({})", workflow.name(), workflow.strategy());
                } catch (Exception e) {
                    Logger.error("failed to load {}: {}", file, e.getMessage());
                }
            }
        } catch (Exception e) {
            Logger.error(e, "workflow load error");
        }
    }

    private static void restoreState(JsonStateStore stateStore, TaskRepository tasks, WorkflowRunRepository runs) {
        JsonStateStore.State state = stateStore.restore();
        for (WorkflowRun run : state.runs) {
            runs.save(run);
        }
        for (Task task : state.tasks) {
            tasks.save(task);
        }
        Logger.info("restored {} runs, {} tasks", state.runs.size(), state.tasks.size());
    }
}
