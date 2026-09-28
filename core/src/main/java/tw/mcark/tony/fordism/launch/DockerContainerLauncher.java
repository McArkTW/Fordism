package tw.mcark.tony.fordism.launch;

import tw.mcark.tony.fordism.config.FordismConfiguration;
import tw.mcark.tony.fordism.credential.CredentialStore;
import tw.mcark.tony.fordism.model.task.NetworkPolicy;
import tw.mcark.tony.fordism.model.task.Task;
import tw.mcark.tony.fordism.proxy.UsageProxy;
import tw.mcark.tony.fordism.secret.SecretVault;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.Map;
import org.tinylog.Logger;
import java.util.ArrayList;
import java.util.List;

/**
 * Spawns one agent container per task via the docker CLI against the mounted host socket.
 * Mounts the HOST workspace path, sets the model backend + session env, joins the launcher network.
 */
public final class DockerContainerLauncher implements ContainerLauncher {
    private final FordismConfiguration configuration;
    private final ModelRegistry models;
    private final SecretVault secrets;
    private final CredentialStore credentials;

    public DockerContainerLauncher(FordismConfiguration configuration, ModelRegistry models, SecretVault secrets,
            CredentialStore credentials) {
        this.configuration = configuration;
        this.models = models;
        this.secrets = secrets;
        this.credentials = credentials;
    }

    public String launch(Task task) throws IOException {
        if (task.hostWorkspacePath == null || task.hostWorkspacePath.isBlank()) {
            // Otherwise the bind mount is the literal "null:/workspace": docker invents a volume,
            // the agent starts with no task and no result dir, and dies seconds later with nothing
            // to explain itself. Fail here, where the cause is still legible.
            throw new IOException("task " + task.id + " has no staged workspace (mode=" + task.mode + ")");
        }
        String name = "fd-" + task.id;
        Map<String, String> env = environment(task);

        Path envFile = writeEnvFile(name, env);
        try {
            // create, (join), start — not `run -d`. A proxied task on the "full" network sits on
            // docker's bridge for internet access, where core is unreachable, so it must also join
            // the launcher network before its first model call. Joining after `run` would race the
            // tool's startup, and a tool that fails its first call does not retry it.
            ProcessResult created = Proc.run(runCommand(task, name, envFile), 60);
            if (created.exit() != 0) {
                throw new IOException("docker create failed: " + created.err());
            }
            if (proxied(task) && task.config.network() == NetworkPolicy.FULL) {
                ProcessResult joined = Proc.run(List.of(configuration.dockerCmd, "network", "connect",
                        configuration.launcherNetwork, name), 60);
                if (joined.exit() != 0) {
                    remove(name);   // it holds only a task token that nothing else would honour
                    throw new IOException("could not join " + name + " to " + configuration.launcherNetwork
                            + " to reach the proxy: " + joined.err());
                }
            }
            ProcessResult started = Proc.run(List.of(configuration.dockerCmd, "start", name), 60);
            if (started.exit() != 0) {
                remove(name);
                throw new IOException("docker start failed: " + started.err());
            }
            return name;
        } finally {
            try {
                Files.deleteIfExists(envFile);
            } catch (IOException e) {
                Logger.warn("could not delete env file {}", envFile);
            }
        }
    }

    /**
     * The full {@code docker run} argument list. Split out from {@link #launch} so a test can read
     * what would be run without a docker daemon — the host-edge flags below are security-relevant
     * enough that "the argv actually carries them" is worth asserting.
     *
     * <p>The flags harden only the CONTAINER EDGE, never the inside: the agent still owns
     * {@code /workspace} completely and installs whatever the task needs. {@code --cap-drop ALL}
     * and {@code no-new-privileges} cost the agent nothing — it already runs non-root (uid 1001),
     * so it never held a capability — but they remove the rungs a container escape climbs. The
     * resource caps stop one task starving the host. Deliberately NOT here: {@code --read-only},
     * which would break package installs the agent legitimately needs, for no real gain on a
     * non-root box.
     */
    List<String> runCommand(Task task, String name, Path envFile) {
        List<String> cmd = new ArrayList<>(List.of(
                configuration.dockerCmd, "create", "--name", name,
                "--network", task.config.network().dockerNetwork(configuration.launcherNetwork),
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges",
                "--env-file", envFile.toString()));
        if (configuration.agentPidsLimit > 0) {
            cmd.add("--pids-limit");
            cmd.add(Long.toString(configuration.agentPidsLimit));
        }
        if (!configuration.agentMemory.isBlank()) {
            cmd.add("--memory");
            cmd.add(configuration.agentMemory);
        }
        if (!configuration.agentCpus.isBlank()) {
            cmd.add("--cpus");
            cmd.add(configuration.agentCpus);
        }
        cmd.add("-v");
        cmd.add(task.hostWorkspacePath + ":/workspace");
        // The image is chosen by the tool, not fixed: each tool has its own, holding only its CLI.
        cmd.add(configuration.agentImage(models.backend(task.agentProfile, task.config.model()).tool().wireName()));
        return cmd;
    }

    /**
     * The container's environment.
     *
     * <p>It goes in through --env-file, not -e. As {@code -e KEY=value} every secret sits in this
     * process's argv, which any user on the host can read out of /proc while the run lasts. The
     * file is 0600 and deleted as soon as docker has read it. This does NOT hide them from
     * {@code docker inspect}, which records a container's environment however it was supplied.
     *
     * <p>Credentials arrive only because the task's Agent Template asked for them, so an agent that
     * merely reads a work item never holds a token that can push. A value supplied when answering
     * the agent's question is applied last and wins, because the usual reason for answering one is
     * that the stored value is dead.
     */
    private Map<String, String> environment(Task task) {
        AgentBackend backend = models.backend(task.agentProfile, task.config.model());

        Map<String, String> env = new LinkedHashMap<>();
        // One image, both agent CLIs baked in; AGENT_TYPE picks the entrypoint branch. The model
        // dialect follows the tool: claude-code speaks Anthropic, qwen-code speaks OpenAI-chat.
        env.put("AGENT_TYPE", backend.tool().wireName());
        // The profile's format, which the store has already checked this tool speaks. The
        // entrypoint configures a tool's provider from it rather than assuming one endpoint shape
        // per tool: codex needs /responses where qwen-code needs /chat/completions, and both read
        // OPENAI_BASE_URL, so the dialect alone cannot tell them apart.
        env.put("AGENT_FORMAT", backend.format().wireName());
        env.put("AGENT_MODE", task.mode.wireName());
        env.put("FORDISM_SESSION_ID", task.sessionId);
        if (task.resumeMessage != null && !task.resumeMessage.isBlank()) {
            env.put("RESUME_PROMPT", task.resumeMessage);
        }
        // Proxied, the tool holds a per-task token instead of the profile's key and is pointed at
        // core (FORDISM_PROXY), which swaps the real key back in on the way out. The endpoint
        // variables still carry the profile's real URL: the entrypoint decides each tool's route
        // from the real host, then swaps the origin for FORDISM_PROXY. A task with network "none"
        // reaches nothing at all, so it keeps its real settings and the proxy stays out of it —
        // which also means the profile's key is only ever handed to a container that could not
        // have used the proxy anyway.
        String key = backend.authToken();
        if (proxied(task)) {
            if (task.proxyToken == null) {
                task.proxyToken = UUID.randomUUID().toString();
            }
            key = task.proxyToken;
            env.put("FORDISM_PROXY", configuration.proxyUrl.replaceAll("/+$", "") + UsageProxy.PREFIX + task.id);
        }
        // The environment follows the model DIALECT, not the tool: every OpenAI-compatible CLI
        // reads the same OPENAI_* names, so adding one is an enum line, not a branch here.
        switch (backend.dialect()) {
            case ANTHROPIC -> {
                env.put("ANTHROPIC_BASE_URL", backend.baseUrl());
                env.put("ANTHROPIC_AUTH_TOKEN", key);
            }
            case OPENAI -> {
                env.put("OPENAI_BASE_URL", backend.baseUrl());
                env.put("OPENAI_API_KEY", key);
                env.put("OPENAI_MODEL", backend.model());
            }
            case GOOGLE -> {
                env.put("GEMINI_API_KEY", key);
                // gemini-cli hits Google's endpoint by default; a profile baseUrl overrides it.
                if (backend.baseUrl() != null && !backend.baseUrl().isBlank()) {
                    env.put("GOOGLE_GEMINI_BASE_URL", backend.baseUrl());
                }
            }
        }

        Map<String, String> granted = credentials.values(task.credentials);
        if (!granted.isEmpty()) {
            env.putAll(granted);
            Logger.info("launch task {} with credentials {}", task.id, granted.keySet());
        }
        // A rescue secret reaches a step only if that step declared the key on its template
        // (task.credentials) or was itself the step answered (grantedSecretNames). SecretVault
        // holds the values per-run and would otherwise hand every one to every later container —
        // so a token typed to unblock a push step would land in a later read-only step an attacker
        // might control. A later step that genuinely needs the same secret declares it on its
        // template, which is what template credentials are for; rescue is for the unforeseen.
        Map<String, String> answered = secrets.forRun(task.runId);
        answered.keySet().retainAll(allowedSecretNames(task));
        if (!answered.isEmpty()) {
            env.putAll(answered);
            Logger.info("launch task {} with answered secrets {}", task.id, answered.keySet());
        }
        // The gh CLI reads GH_TOKEN and git reads GITHUB_TOKEN. Mirrored once, after the merge, so
        // whichever source won is the one both names carry.
        if (env.containsKey("GITHUB_TOKEN") && !env.containsKey("GH_TOKEN")) {
            env.put("GH_TOKEN", env.get("GITHUB_TOKEN"));
        }
        return env;
    }

    /**
     * Whether this task's model calls go through core's proxy: it is configured, and the task has a
     * network at all. A "full" task is joined to the launcher network in {@link #launch} to reach
     * it; a "none" task could not reach core even if it wanted to.
     */
    private boolean proxied(Task task) {
        return !configuration.proxyUrl.isBlank() && task.config.network() != NetworkPolicy.NONE;
    }

    /** The rescue-secret keys this task may receive: what its template declared, plus what it was answered. */
    static java.util.Set<String> allowedSecretNames(Task task) {
        java.util.Set<String> allowed = new java.util.LinkedHashSet<>();
        if (task.credentials != null) {
            allowed.addAll(task.credentials);
        }
        if (task.grantedSecretNames != null) {
            allowed.addAll(task.grantedSecretNames);
        }
        return allowed;
    }

    /**
     * An --env-file for docker to read once at start. A value must not contain a newline: docker
     * reads the next line as another variable, so a multi-line RESUME_PROMPT (the only human-written
     * value here) would inject whatever followed it. Newlines are flattened to spaces.
     */
    private Path writeEnvFile(String name, Map<String, String> env) throws IOException {
        Path file = Files.createTempFile("fordism-" + name + "-", ".env");
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException e) {
            // non-POSIX filesystem (dev on Windows) — the temp dir is already user-scoped
        }
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> entry : env.entrySet()) {
            String value = entry.getValue() == null ? "" : entry.getValue().replaceAll("[\\r\\n]+", " ");
            body.append(entry.getKey()).append('=').append(value).append('\n');
        }
        Files.writeString(file, body.toString());
        return file;
    }

    public boolean isRunning(String containerId) throws IOException {
        ProcessResult result = Proc.run(List.of(configuration.dockerCmd, "inspect", "-f", "{{.State.Running}}", containerId), 20);
        return result.exit() == 0 && result.out().trim().equals("true");
    }

    public void kill(String containerId) {
        try {
            if (containerId != null) {
                Proc.run(List.of(configuration.dockerCmd, "kill", containerId), 20);
            }
        } catch (Exception e) {
            // best effort
        }
    }

    public void remove(String containerId) {
        try {
            if (containerId != null) {
                Proc.run(List.of(configuration.dockerCmd, "rm", "-f", containerId), 20);
            }
        } catch (Exception e) {
            // best effort
        }
    }
}
