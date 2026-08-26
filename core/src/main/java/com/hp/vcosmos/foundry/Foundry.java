package com.hp.vcosmos.foundry;

import com.hp.vcosmos.foundry.agentprofile.AgentProfileStore;
import com.hp.vcosmos.foundry.config.FoundryConfiguration;
import com.hp.vcosmos.foundry.field.Collector;
import com.hp.vcosmos.foundry.field.CullPolicy;
import com.hp.vcosmos.foundry.field.Dispatcher;
import com.hp.vcosmos.foundry.field.FieldView;
import com.hp.vcosmos.foundry.field.OrphanCuller;
import com.hp.vcosmos.foundry.field.Reaper;
import com.hp.vcosmos.foundry.launch.ContainerLauncher;
import com.hp.vcosmos.foundry.launch.DockerContainerLauncher;
import com.hp.vcosmos.foundry.launch.ModelRegistry;
import com.hp.vcosmos.foundry.launch.SessionIdentifierFactory;
import com.hp.vcosmos.foundry.model.run.WorkflowRun;
import com.hp.vcosmos.foundry.model.task.Task;
import com.hp.vcosmos.foundry.model.workflow.Workflow;
import com.hp.vcosmos.foundry.orchestrate.Engine;
import com.hp.vcosmos.foundry.orchestrate.ReconcileLoop;
import com.hp.vcosmos.foundry.parse.WorkflowLoader;
import com.hp.vcosmos.foundry.skill.SkillState;
import com.hp.vcosmos.foundry.skill.SkillStore;
import com.hp.vcosmos.foundry.store.InMemoryTaskRepository;
import com.hp.vcosmos.foundry.store.InMemoryWorkflowRunRepository;
import com.hp.vcosmos.foundry.store.JsonStateStore;
import com.hp.vcosmos.foundry.store.TaskRepository;
import com.hp.vcosmos.foundry.store.WorkflowRunRepository;
import com.hp.vcosmos.foundry.web.App;
import com.hp.vcosmos.foundry.workspace.TemplateStore;
import com.hp.vcosmos.foundry.workspace.TaskResults;
import com.hp.vcosmos.foundry.workspace.WorkspaceArchive;
import com.hp.vcosmos.foundry.workspace.WorkspaceStager;
import com.hp.vcosmos.foundry.credential.CredentialStore;
import com.hp.vcosmos.foundry.secret.SecretVault;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;
import org.tinylog.Logger;

/** Composition root: wire the field + engine, start the reconcile loop and the Javalin app. */
public final class Foundry {
    private Foundry() {}

    public static void main(String[] args) {
        FoundryConfiguration configuration = new FoundryConfiguration();
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
        profiles.migrateLegacyRecords();
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
        Logger.info("foundry-core up version={} gitSha={}", configuration.version, configuration.gitSha);
    }

    private static void loadWorkflows(Engine engine, FoundryConfiguration configuration) {
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
