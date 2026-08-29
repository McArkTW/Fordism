package tw.mcark.tony.fordism.web;

import tw.mcark.tony.fordism.agentprofile.AgentProfileStore;
import tw.mcark.tony.fordism.auth.Accounts;
import tw.mcark.tony.fordism.auth.AuthGate;
import tw.mcark.tony.fordism.auth.Enrollment;
import tw.mcark.tony.fordism.auth.ExternalAuthProviders;
import tw.mcark.tony.fordism.config.FordismConfiguration;
import tw.mcark.tony.fordism.credential.CredentialStore;
import tw.mcark.tony.fordism.orchestrate.Engine;
import tw.mcark.tony.fordism.secret.SecretVault;
import tw.mcark.tony.fordism.skill.SkillStore;
import tw.mcark.tony.fordism.workspace.TemplateStore;
import tw.mcark.tony.fordism.workspace.TaskResults;
import tw.mcark.tony.fordism.workspace.WorkspaceArchive;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.tinylog.Logger;

/** The Javalin HTTP application: health/version + auth + workflow + template + skill + run endpoints. */
public final class App {
    private final Engine engine;
    private final FordismConfiguration configuration;
    private final TemplateStore templates;
    private final TaskResults results;
    private final WorkspaceArchive archive;
    private final SkillStore skills;
    private final AgentProfileStore profiles;
    private final SecretVault secrets;
    private final CredentialStore credentials;
    private final Accounts accounts;

    public App(Engine engine, FordismConfiguration configuration, TemplateStore templates, TaskResults results,
            WorkspaceArchive archive,
            SkillStore skills, AgentProfileStore profiles, SecretVault secrets, CredentialStore credentials,
            Accounts accounts) {
        this.engine = engine;
        this.configuration = configuration;
        this.templates = templates;
        this.results = results;
        this.archive = archive;
        this.skills = skills;
        this.profiles = profiles;
        this.secrets = secrets;
        this.credentials = credentials;
        this.accounts = accounts;
    }

    public Javalin start() {
        return startOn(configuration.port);
    }

    /**
     * Start on an explicit port. The seam a test binds to port 0 through — the alternative is a
     * suite that fails on whichever machine already has 8080.
     */
    public Javalin startOn(int port) {
        WorkflowController workflows = new WorkflowController(engine, configuration, templates, profiles, credentials);
        RunController runs = new RunController(engine, results, archive, secrets);
        TemplateController templateApi = new TemplateController(templates);
        SkillController skillApi = new SkillController(skills);
        AgentProfileController profileApi = new AgentProfileController(profiles, templates);
        CredentialController credentialApi = new CredentialController(credentials, templates);
        AuthController authApi = new AuthController(accounts,
                ExternalAuthProviders.from(accounts.configuration()),
                new Enrollment(accounts.configuration(), accounts.users()));
        UserController userApi = new UserController(accounts);
        GroupController groupApi = new GroupController(accounts);

        Javalin app = Javalin.create();

        // First, before any route: no session, no answer. Health, version and /api/auth/* are the
        // only exemptions, and AuthGate — not this list — is where that is decided.
        app.before("/api/*", new AuthGate(accounts)::guard);

        app.get("/api/health", ctx -> ctx.contentType("application/json").result("{\"status\":\"ok\"}"));
        app.get("/api/version", this::version);
        app.get("/api/auth/providers", authApi::offered);
        app.post("/api/auth/bootstrap", authApi::bootstrap);
        app.post("/api/auth/login", authApi::login);
        app.post("/api/auth/logout", authApi::logout);
        app.get("/api/auth/me", authApi::me);
        app.get("/api/auth/{provider}/login", authApi::beginExternal);
        app.get("/api/auth/{provider}/callback", authApi::completeExternal);
        app.get("/api/workflows", workflows::list);
        app.get("/api/workflows/{name}", workflows::get);
        app.post("/api/workflows", workflows::save);
        app.post("/api/workflows/validate", workflows::validate);
        app.put("/api/workflows/{name}", workflows::save);
        app.delete("/api/workflows/{name}", workflows::delete);
        app.post("/api/workflows/{name}/run", workflows::run);
        app.get("/api/workflows/{name}/preflight", workflows::preflight);
        app.get("/api/templates", templateApi::list);
        app.post("/api/templates", templateApi::create);
        app.get("/api/templates/{id}", templateApi::get);
        app.put("/api/templates/{id}", templateApi::update);
        app.delete("/api/templates/{id}", templateApi::delete);
        app.get("/api/skills", skillApi::list);
        app.get("/api/skills-source", skillApi::source);
        app.post("/api/skills-state", skillApi::setEnabled);
        app.post("/api/skills", skillApi::save);
        app.post("/api/skills/upload", skillApi::upload);
        app.get("/api/skills/<name>", skillApi::get);
        app.delete("/api/skills/<name>", skillApi::delete);
        app.get("/api/agent-profiles", profileApi::list);
        app.post("/api/agent-profiles", profileApi::create);
        app.get("/api/agent-profiles/{id}", profileApi::get);
        app.put("/api/agent-profiles/{id}", profileApi::update);
        app.delete("/api/agent-profiles/{id}", profileApi::delete);
        app.get("/api/credentials", credentialApi::list);
        app.get("/api/credentials/{key}", credentialApi::get);
        app.put("/api/credentials/{key}", credentialApi::save);
        app.delete("/api/credentials/{key}", credentialApi::delete);
        app.get("/api/runs", runs::list);
        app.get("/api/runs/{id}", runs::get);
        app.post("/api/runs/{id}/abandon", runs::abandon);
        app.get("/api/tasks/{id}/result", runs::result);
        app.get("/api/tasks/{id}/result.zip", runs::resultZip);
        app.get("/api/tasks/{id}/workspace.zip", runs::workspaceZip);
        app.get("/api/tasks/{id}/transcript", runs::transcript);
        app.post("/api/tasks/{id}/answer", runs::answer);
        app.get("/api/questions", runs::questions);
        app.get("/api/users", userApi::list);
        app.post("/api/users", userApi::create);
        app.put("/api/users/{id}", userApi::update);
        app.delete("/api/users/{id}", userApi::delete);
        app.get("/api/groups", groupApi::list);
        app.post("/api/groups", groupApi::create);
        app.put("/api/groups/{id}", groupApi::update);
        app.delete("/api/groups/{id}", groupApi::delete);
        app.start(port);

        Logger.info("Javalin listening on :{}", app.port());
        return app;
    }

    private void version(Context ctx) {
        ctx.contentType("application/json").result(
                "{\"service\":\"fordism-core\",\"version\":\"" + configuration.version
                + "\",\"gitSha\":\"" + configuration.gitSha + "\",\"builtAt\":\"" + configuration.builtAt + "\"}");
    }
}
