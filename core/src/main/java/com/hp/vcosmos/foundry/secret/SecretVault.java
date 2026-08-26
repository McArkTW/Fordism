package com.hp.vcosmos.foundry.secret;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Secrets a human handed over during rescue, scoped to one run and injected into every container
 * that run launches afterwards.
 *
 * Held in memory only, and deliberately outside the Task/WorkflowRun models: JsonStateStore writes
 * those to disk as plain JSON, so a secret carried on them would become a plaintext token in a file
 * that outlives the run. The price of that choice is that a core restart forgets them, and the next
 * task needing one pauses for rescue again — a re-ask, never a wrong answer.
 *
 * This closes the transcript leak, not the host-access one: the values still reach the agent as
 * environment variables, so anyone with docker access to the host can read them out of
 * `docker inspect` for as long as the container is kept. Same exposure GITHUB_TOKEN already has.
 */
public final class SecretVault {

    /** Names only. Anything else could carry a newline or '=' into the docker --env-file. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final Map<String, Map<String, String>> byRun = new ConcurrentHashMap<>();

    /** Add to (not replace) a run's secrets, so a second rescue can supply what the first missed. */
    public void put(String runId, Map<String, String> secrets) {
        if (runId == null || secrets == null || secrets.isEmpty()) {
            return;
        }
        byRun.computeIfAbsent(runId, id -> new ConcurrentHashMap<>()).putAll(secrets);
    }

    /** A run's secrets as launch environment. Empty (never null) for a run that never had any. */
    public Map<String, String> forRun(String runId) {
        Map<String, String> held = runId == null ? null : byRun.get(runId);
        return held == null ? Map.of() : new LinkedHashMap<>(held);
    }

    /** The names held for a run — safe to show in the UI and log; the values are not. */
    public Set<String> namesFor(String runId) {
        return forRun(runId).keySet();
    }

    public void clear(String runId) {
        if (runId != null) {
            byRun.remove(runId);
        }
    }

    /**
     * Why a name/value pair cannot be accepted, or null if it can. A newline is rejected rather
     * than flattened: the env-file writer turns one into a space, which would silently corrupt a
     * token into one that fails authentication for reasons no log explains.
     */
    public static String reject(String name, String value) {
        if (name == null || !NAME.matcher(name).matches()) {
            return "not a valid environment-variable name: " + name;
        }
        if (value == null || value.isBlank()) {
            return "no value given for " + name;
        }
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            return "value for " + name + " contains a line break";
        }
        return null;
    }
}
