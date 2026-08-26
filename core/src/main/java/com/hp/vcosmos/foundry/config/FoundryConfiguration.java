package com.hp.vcosmos.foundry.config;

/** All runtime configuration, read from the environment (compose/Jenkins provide it). */
public final class FoundryConfiguration {
    public final int port = (int) envLong("FOUNDRY_PORT", 8080);
    public final long reconcileIntervalMillis = envLong("FOUNDRY_RECONCILE_MS", 2000);
    public final int maximumConcurrentTasks = (int) envLong("FOUNDRY_LAUNCHER_MAX", 8);
    public final long heartbeatTimeoutMillis = envLong("FOUNDRY_HEARTBEAT_MS", 180_000);

    // Workspaces: core writes at the CONTAINER path; spawned agents bind-mount the HOST path.
    public final String workspacesDir = env("FOUNDRY_WORKSPACES_DIR", "/workspaces");
    public final String workspacesHost = env("FOUNDRY_WORKSPACES_HOST", workspacesDir);
    public final String templatesRoot = env("AGENT_TEMPLATES", "/templates");
    public final String workflowsDir = env("FOUNDRY_WORKFLOWS_DIR", "workflows");
    // User-created/edited workflows + the run/task state snapshot persist on the host mount.
    public final String userWorkflowsDir = env("FOUNDRY_USER_WORKFLOWS_DIR", workspacesDir + "/_workflows");
    public final String stateDir = env("FOUNDRY_STATE_DIR", workspacesDir + "/_state");
    public final String skillsDir = env("FOUNDRY_SKILLS_DIR", "skills");   // the skills library (SKILL.md per skill)
    public final String agentProfilesDir = env("FOUNDRY_AGENT_PROFILES_DIR", "agent-profiles"); // editable Agent Profiles (JSON per profile)
    // Stored credentials (JSON per credential). On the host mount so they outlive a redeploy —
    // that is the whole difference between this and the in-memory rescue vault.
    public final String credentialsDir = env("FOUNDRY_CREDENTIALS_DIR", workspacesDir + "/_credentials");

    // The gid the agent image runs as (agent/Dockerfile pins node to 1001). Core runs as root, so
    // this is the only thing the two share — a staged workspace is chgrp'd to it.
    public final long agentGroupId = envLong("FOUNDRY_AGENT_GID", 1001);

    // Launcher
    public final String launcherNetwork = env("FOUNDRY_LAUNCHER_NETWORK", "bridge");
    public final String agentImage = env("FOUNDRY_AGENT_IMAGE", "foundry/foundry-agent:local");
    public final String dockerCmd = env("FOUNDRY_DOCKER_CMD", "docker");

    // Default model backend when a task resolves no explicit Agent Profile. No gateway —
    // defaults to Ollama; an explicit Agent Profile (its baseUrl) overrides this per agent.
    public final String llmBaseUrl = env("LLM_BASE_URL", "http://localhost:11434");
    public final String agentModel = env("AGENT_MODEL", "qwen3");

    // Build identity (stamped by CI) surfaced at /api/version
    // Heimdall RS256 public key (PEM) used to verify identity tokens. Empty = auth off.
    public final String heimdallPubKey = env("HEIMDALL_PUBKEY", "");

    public final String version = env("FOUNDRY_VERSION", "dev");
    public final String gitSha = env("FOUNDRY_GIT_SHA", "unknown");
    public final String builtAt = env("FOUNDRY_BUILT_AT", "unknown");

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
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
