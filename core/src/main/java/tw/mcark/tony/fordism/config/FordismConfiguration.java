package tw.mcark.tony.fordism.config;

import java.util.ArrayList;
import java.util.List;

/** All runtime configuration, read from the environment (compose provides it). */
public final class FordismConfiguration {
    public final int port = (int) envLong("FORDISM_PORT", 8080);
    public final long reconcileIntervalMillis = envLong("FORDISM_RECONCILE_MS", 2000);
    public final int maximumConcurrentTasks = (int) envLong("FORDISM_LAUNCHER_MAX", 8);
    public final long heartbeatTimeoutMillis = envLong("FORDISM_HEARTBEAT_MS", 180_000);

    // Workspaces: core writes at the CONTAINER path; spawned agents bind-mount the HOST path.
    public final String workspacesDir = env("FORDISM_WORKSPACES_DIR", "/workspaces");
    public final String workspacesHost = env("FORDISM_WORKSPACES_HOST", workspacesDir);
    public final String templatesRoot = env("AGENT_TEMPLATES", "/templates");
    public final String workflowsDir = env("FORDISM_WORKFLOWS_DIR", "workflows");
    // User-created/edited workflows + the run/task state snapshot persist on the host mount.
    public final String userWorkflowsDir = env("FORDISM_USER_WORKFLOWS_DIR", workspacesDir + "/_workflows");
    public final String stateDir = env("FORDISM_STATE_DIR", workspacesDir + "/_state");
    public final String skillsDir = env("FORDISM_SKILLS_DIR", "skills");   // the skills library (SKILL.md per skill)
    public final String agentProfilesDir = env("FORDISM_AGENT_PROFILES_DIR", "agent-profiles"); // editable Agent Profiles (JSON per profile)
    // Stored credentials (JSON per credential). On the host mount so they outlive a redeploy —
    // that is the whole difference between this and the in-memory rescue vault.
    public final String credentialsDir = env("FORDISM_CREDENTIALS_DIR", workspacesDir + "/_credentials");

    // The gid the agent image runs as (agent/Dockerfile pins node to 1001). Core runs as root, so
    // this is the only thing the two share — a staged workspace is chgrp'd to it.
    public final long agentGroupId = envLong("FORDISM_AGENT_GID", 1001);

    // Launcher
    public final String launcherNetwork = env("FORDISM_LAUNCHER_NETWORK", "bridge");
    // One image per tool: the launcher appends the tool's wire name, so claude-code runs
    // fordism/fordism-agent-claude-code:<tag>. It was one image carrying every CLI, of which a run
    // used exactly one — and the tools shared $HOME, which is the mounted workspace.
    public final String agentImagePrefix = env("FORDISM_AGENT_IMAGE_PREFIX", "fordism/fordism-agent");
    public final String agentImageTag = env("FORDISM_AGENT_IMAGE_TAG", "local");
    public final String dockerCmd = env("FORDISM_DOCKER_CMD", "docker");

    // Core's model proxy, as an agent container reaches it — e.g. http://fordism-core:8080. Set,
    // every agent on the launcher network sends its model calls through core, which records their
    // token usage in the task's result/logs/usage.jsonl and swaps the real key in. Empty (the
    // default), agents call the provider directly and nothing about the old path changes.
    public final String proxyUrl = env("FORDISM_PROXY_URL", "");

    // With the proxy on, also write every model call's conversation to result/logs/llm.jsonl, one
    // format for every tool, redacted. Off by default: it stores what the agent read and wrote.
    // _FILES additionally saves inline images and PDFs to result/logs/llm-files/.
    public final boolean transcript = env("FORDISM_TRANSCRIPT", "").equalsIgnoreCase("on");
    public final boolean transcriptFiles = env("FORDISM_TRANSCRIPT_FILES", "").equalsIgnoreCase("on");

    // Agent container edge. The agent has full control INSIDE its container — it installs
    // packages and runs project test suites, which is the point — so nothing here restricts what
    // it does with /workspace. These bound only what a compromised agent could do to the HOST:
    // no added capabilities, no privilege escalation, and caps on the resources one task may burn.
    // Sized generously because a real build is heavy; 0 means "let docker decide" (no flag).
    public final String agentMemory = env("FORDISM_AGENT_MEMORY", "4g");
    public final String agentCpus = env("FORDISM_AGENT_CPUS", "2");
    public final long agentPidsLimit = envLong("FORDISM_AGENT_PIDS", 1024);

    // Default model backend when a task resolves no explicit Agent Profile. No gateway —
    // defaults to Ollama; an explicit Agent Profile (its baseUrl) overrides this per agent.
    public final String llmBaseUrl = env("LLM_BASE_URL", "http://localhost:11434");
    public final String agentModel = env("AGENT_MODEL", "qwen3");

    // Authentication settings live in AuthConfiguration, not here: auth is always on, so they are
    // validated at startup (half-configured provider, zero providers) rather than defaulted.

    // Build identity (stamped by CI) surfaced at /api/version
    public final String version = env("FORDISM_VERSION", "dev");
    public final String gitSha = env("FORDISM_GIT_SHA", "unknown");
    public final String builtAt = env("FORDISM_BUILT_AT", "unknown");

    // Workspace-relative files an agent tool had to write a live API key into, deleted once the
    // task ends (see CredentialScrub). Nearly every tool takes its key from the environment and
    // leaves nothing behind; a tool that can only resolve one from a file on disk writes it into
    // the workspace, which is bind-mounted from the host, kept and downloadable. Comma-separated;
    // empty scrubs nothing.
    public final List<String> credentialFiles = envList("FORDISM_CREDENTIAL_FILES", ".reasonix/.env");

    /** The image that runs this tool. */
    public String agentImage(String toolWireName) {
        return agentImagePrefix + "-" + toolWireName + ":" + agentImageTag;
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** A comma-separated list; blank entries dropped, so a trailing comma is not a blank path. */
    private static List<String> envList(String key, String fallback) {
        List<String> out = new ArrayList<>();
        for (String part : env(key, fallback).split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return List.copyOf(out);
    }

    private static long envLong(String key, long fallback) {
        try {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
