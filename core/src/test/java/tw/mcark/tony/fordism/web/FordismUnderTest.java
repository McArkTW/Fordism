package tw.mcark.tony.fordism.web;

import tw.mcark.tony.fordism.agentprofile.AgentProfileStore;
import tw.mcark.tony.fordism.auth.Accounts;
import tw.mcark.tony.fordism.auth.AuthConfiguration;
import tw.mcark.tony.fordism.auth.AuthGate;
import tw.mcark.tony.fordism.auth.Group;
import tw.mcark.tony.fordism.auth.GroupStore;
import tw.mcark.tony.fordism.auth.PasswordHash;
import tw.mcark.tony.fordism.auth.SeededGroups;
import tw.mcark.tony.fordism.auth.SessionCookie;
import tw.mcark.tony.fordism.auth.SessionStore;
import tw.mcark.tony.fordism.auth.User;
import tw.mcark.tony.fordism.auth.UserStore;
import tw.mcark.tony.fordism.config.FordismConfiguration;
import tw.mcark.tony.fordism.credential.CredentialStore;
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
import tw.mcark.tony.fordism.orchestrate.Engine;
import tw.mcark.tony.fordism.secret.SecretVault;
import tw.mcark.tony.fordism.skill.SkillPluginStore;
import tw.mcark.tony.fordism.skill.SkillState;
import tw.mcark.tony.fordism.skill.SkillStore;
import tw.mcark.tony.fordism.store.InMemoryTaskRepository;
import tw.mcark.tony.fordism.store.InMemoryWorkflowRunRepository;
import tw.mcark.tony.fordism.store.JsonStateStore;
import tw.mcark.tony.fordism.store.TaskRepository;
import tw.mcark.tony.fordism.store.WorkflowRunRepository;
import tw.mcark.tony.fordism.workspace.TaskResults;
import tw.mcark.tony.fordism.workspace.TemplateStore;
import tw.mcark.tony.fordism.workspace.WorkspaceArchive;
import tw.mcark.tony.fordism.workspace.WorkspaceStager;
import io.javalin.Javalin;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * The real application, wired the way {@code Fordism.main} wires it, on an ephemeral port.
 *
 * <p>A gate test that stubs the routes proves only that the stub is gated. This starts the actual
 * Javalin app so the paths under test are the paths that ship — including the ones Javalin matches
 * with {@code <name>} rather than {@code {name}}, which a hand-written table would get wrong.
 *
 * <p>Port 0, not 8080: a suite that needs a free well-known port fails on whichever machine is
 * already running the thing it is testing.
 */
final class FordismUnderTest implements AutoCloseable {

    static final String ADMIN_SECRET = "the-bootstrap-secret";

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final Javalin javalin;
    private final int port;
    private final Accounts accounts;

    FordismUnderTest(Path stateDir) {
        FordismConfiguration configuration = new FordismConfiguration();
        TaskRepository tasks = new InMemoryTaskRepository();
        WorkflowRunRepository runs = new InMemoryWorkflowRunRepository();
        AgentProfileStore profiles = new AgentProfileStore(stateDir.resolve("agent-profiles"));
        SkillStore skills = new SkillStore(configuration, new SkillState(configuration));
        SkillPluginStore skillPlugins = new SkillPluginStore(configuration);
        TemplateStore templates = new TemplateStore(stateDir.resolve("templates"), skills);
        TaskResults results = new TaskResults();
        SecretVault secrets = new SecretVault();
        CredentialStore credentials = new CredentialStore(stateDir.resolve("credentials"));
        ContainerLauncher launcher = new DockerContainerLauncher(configuration,
                new ModelRegistry(configuration, profiles), secrets, credentials);
        Engine engine = new Engine(configuration, tasks, runs, new SessionIdentifierFactory(),
                new Dispatcher(tasks, new WorkspaceStager(configuration), templates, launcher,
                        new FieldView(tasks, configuration)),
                new Collector(tasks, results, launcher),
                new Reaper(tasks, results, launcher, new CullPolicy(), configuration),
                new OrphanCuller(tasks, runs, launcher), new JsonStateStore(configuration), secrets);

        GroupStore groups = new GroupStore(stateDir);
        SeededGroups.into(groups);
        this.accounts = new Accounts(AuthConfiguration.from(Map.of(
                        "FORDISM_AUTH_LOCAL", "true", "FORDISM_ADMIN_SECRET", ADMIN_SECRET)),
                new UserStore(stateDir), groups, new SessionStore(stateDir));

        this.javalin = new App(engine, configuration, templates, results, new WorkspaceArchive(),
                skills, skillPlugins, profiles, secrets, credentials, accounts).startOn(0);
        this.port = javalin.port();
    }

    Accounts accounts() {
        return accounts;
    }

    /** Create a local account in a group and return the cookie a browser would then hold. */
    String signedInMemberOf(String groupName, String email) {
        User user = accounts.users().create(User.withPassword(email, email,
                PasswordHash.of("a-good-enough-password")));
        Group group = accounts.groups().findByName(groupName).orElseThrow();
        accounts.groups().update(group.withMember(user.id()));
        return SessionCookie.NAME + "=" + accounts.sessions().create(user.id()).token();
    }

    HttpRequest.Builder to(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    /** Fire a prepared request. The gate's answer is a status, so that is what callers read. */
    Response send(HttpRequest.Builder builder) {
        try {
            HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body(),
                    response.headers().firstValue("Set-Cookie"), response.headers().firstValue("Location"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while calling the app under test", e);
        } catch (IOException e) {
            throw new IllegalStateException("could not call the app under test", e);
        }
    }

    record Response(int status, String body, Optional<String> setCookie, Optional<String> location) {

        /** The session cookie this response issued, ready to send back as a Cookie header. */
        String sessionCookie() {
            String header = setCookie.orElseThrow(() -> new IllegalStateException("no Set-Cookie: " + body));
            return header.split(";", 2)[0];
        }
    }

    /** The header the gate demands on every write. */
    static HttpRequest.Builder writing(HttpRequest.Builder builder) {
        return builder.header(AuthGate.REQUEST_HEADER, "test");
    }

    @Override
    public void close() {
        javalin.stop();
    }
}
