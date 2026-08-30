package tw.mcark.tony.fordism.web;

import tw.mcark.tony.fordism.workspace.ResultFile;
import tw.mcark.tony.fordism.workspace.TokenUsage;
import java.util.List;

/**
 * The shapes the HTTP API answers with.
 *
 * <p>These were hand-built {@code LinkedHashMap}s in each controller, which made the API a thing
 * you could only discover by reading the code that assembled it — and let it drift from the app's
 * matching TypeScript types without anything noticing. Each record here is the counterpart of one
 * type in {@code app/src/.../data/*.service.ts}; the component names ARE the JSON field names, so
 * renaming one is a visible API change rather than a typo in a string key.
 *
 * <p>Gson omits a null component, so an optional field is simply absent — the same wire output the
 * maps produced.
 */
public final class Views {
    private Views() {}

    /* ---- runs ---- */

    /**
     * One run in the list. {@code parentRunId} is set only on a run a reconciler spawned — Gson
     * omits a null, so an ordinary run's row is byte-for-byte what it was before child runs existed.
     */
    public record RunSummary(String id, String workflow, String state, long createdAt, long durationMs,
                             String parentRunId) {}

    /**
     * A run with everything its page draws: its tasks, the run that spawned it, and the runs it
     * spawned. The children are here rather than behind their own endpoint because a reconciler's
     * page is unreadable without them — its own tasks only ever say "I delegated this".
     */
    public record RunDetail(String id, String workflow, String strategy, String state, long createdAt,
                            String workflowSnapshot, List<TaskDetail> tasks, String parentRunId,
                            List<RunSummary> children) {}

    public record TaskDetail(String taskId, int step, String state, String template, String session,
                             String workspace, String summary, String verdict, long createdAt, int attempt,
                             String error, String question, List<String> secretsRequested,
                             Iterable<String> secretsHeld, long durationMs, TokenUsage usage) {}

    /** One task waiting on an answer — the Questions inbox row. */
    public record Question(String taskId, String runId, String workflow, int step, String template,
                           String question, List<String> secretsRequested, Iterable<String> secretsHeld,
                           String summary, long createdAt) {}

    public record TaskResultFiles(String taskId, String workspace, List<ResultFile> files) {}

    /* ---- workflows ---- */

    public record WorkflowSummary(String name, String strategy, int steps, String description,
                                  List<String> tags, List<String> templates) {}

    /**
     * A workflow as the engine parsed it — the editor draws its outline from this, never from its
     * own parse, so what you see is what would run. {@code yaml} is carried only by {@code get}.
     */
    public record WorkflowDetail(String name, String description, String strategy, List<String> tags,
                                 int maxIterations, List<WorkflowParameter> parameters,
                                 List<WorkflowStep> steps, String generator, String yaml) {

        public WorkflowDetail withYaml(String source) {
            return new WorkflowDetail(name, description, strategy, tags, maxIterations, parameters,
                    steps, generator, source);
        }
    }

    /**
     * One step in the outline. {@code network} is the egress core would impose, not the text the
     * YAML carried — a step that says nothing about network still reaches core and nothing else,
     * and the outline is the only place that shows it before a run starts.
     */
    public record WorkflowStep(String id, String template, List<String> dependsOn, String forEach,
                               String when, boolean includePreviousResult, int timeoutSeconds,
                               String network) {}

    public record WorkflowParameter(String name, String label, String type, boolean required,
                                    String defaultValue, String help) {}

    /** Whether a workflow could start now, and the first reason it could not. */
    public record Preflight(boolean ready, String problem) {}

    /** The acknowledgement for a started run. */
    public record RunStarted(String runId, String workflow, String state) {}

    /* ---- auth ---- */

    /**
     * What the login screen needs before anyone has signed in: which providers to offer, and
     * whether this install still has no accounts at all — the only state in which the one-time
     * admin bootstrap is open.
     */
    public record AuthProviders(List<Provider> providers, boolean bootstrapRequired) {}

    public record Provider(String id) {}

    /** The signed-in user, with the effective grants the UI hides or shows actions by. */
    public record Me(String id, String email, String displayName, List<String> groups,
                     List<String> permissions, boolean mfaEnabled) {}

    /**
     * A user as the Users page reads one. There is deliberately no field for the password hash:
     * the record cannot carry it, so no endpoint can leak it by forgetting to strip it.
     */
    public record UserSummary(String id, String email, String displayName, List<Identity> identities,
                              boolean mfaEnabled) {}

    /**
     * One way an account can sign in. A stored password appears as the {@code local} provider —
     * the Users page's honest answer to "how does this person actually get in" is one list, not a
     * flag plus a list.
     */
    public record Identity(String provider, String subject) {}

    public record GroupSummary(String id, String name, List<String> members, List<String> grants) {}

    /**
     * One API token as its owner's list shows it. There is deliberately no field for the value:
     * the record cannot carry it, so no endpoint can leak it by forgetting to strip it — the same
     * argument {@link UserSummary} makes about a password hash.
     */
    public record ApiTokenSummary(String id, String name, List<String> grants, long createdAt,
                                  long expiresAt, long lastUsedAt) {}

    /** A token the instant it was minted: the row, plus the only copy of its value there will be. */
    public record MintedApiToken(ApiTokenSummary token, String value) {}
}
