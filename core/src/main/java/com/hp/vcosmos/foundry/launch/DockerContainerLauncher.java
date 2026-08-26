package com.hp.vcosmos.foundry.launch;

import com.hp.vcosmos.foundry.config.FoundryConfiguration;
import com.hp.vcosmos.foundry.credential.CredentialStore;
import com.hp.vcosmos.foundry.model.task.Task;
import com.hp.vcosmos.foundry.secret.SecretVault;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import org.tinylog.Logger;
import java.util.ArrayList;
import java.util.List;

/**
 * Spawns one agent container per task via the docker CLI against the mounted host socket.
 * Mounts the HOST workspace path, sets the model backend + session env, joins the launcher network.
 */
public final class DockerContainerLauncher implements ContainerLauncher {
    private final FoundryConfiguration configuration;
    private final ModelRegistry models;
    private final SecretVault secrets;
    private final CredentialStore credentials;

    public DockerContainerLauncher(FoundryConfiguration configuration, ModelRegistry models, SecretVault secrets,
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
        String name = "fa-" + task.id;
        Map<String, String> env = environment(task);

        Path envFile = writeEnvFile(name, env);
        try {
            List<String> cmd = new ArrayList<>(List.of(
                    configuration.dockerCmd, "run", "-d", "--name", name,
                    "--network", task.config.network().dockerNetwork(configuration.launcherNetwork),
                    "--env-file", envFile.toString()));
            cmd.add("-v"); cmd.add(task.hostWorkspacePath + ":/workspace");
            cmd.add(configuration.agentImage);
            ProcessResult result = Proc.run(cmd, 60);
            if (result.exit() != 0) {
                throw new IOException("docker run failed: " + result.err());
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
        env.put("AGENT_MODE", task.mode.wireName());
        env.put("FOUNDRY_SESSION_ID", task.sessionId);
        if (task.resumeMessage != null && !task.resumeMessage.isBlank()) {
            env.put("RESUME_PROMPT", task.resumeMessage);
        }
        if (backend.isQwenCode()) {
            env.put("OPENAI_BASE_URL", backend.baseUrl());
            env.put("OPENAI_API_KEY", backend.authToken());
            env.put("OPENAI_MODEL", backend.model());
        } else {
            env.put("ANTHROPIC_BASE_URL", backend.baseUrl());
            env.put("ANTHROPIC_AUTH_TOKEN", backend.authToken());
        }

        Map<String, String> granted = credentials.values(task.credentials);
        if (!granted.isEmpty()) {
            env.putAll(granted);
            Logger.info("launch task {} with credentials {}", task.id, granted.keySet());
        }
        Map<String, String> answered = secrets.forRun(task.runId);
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
     * An --env-file for docker to read once at start. A value must not contain a newline: docker
     * reads the next line as another variable, so a multi-line RESUME_PROMPT (the only human-written
     * value here) would inject whatever followed it. Newlines are flattened to spaces.
     */
    private Path writeEnvFile(String name, Map<String, String> env) throws IOException {
        Path file = Files.createTempFile("foundry-" + name + "-", ".env");
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
