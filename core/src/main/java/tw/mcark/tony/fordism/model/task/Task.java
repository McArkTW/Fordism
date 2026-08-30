package tw.mcark.tony.fordism.model.task;

import java.util.List;

/** A live task instance (mutable — state advances over its lifecycle). */
public final class Task {
    public final String id;
    public final String runId;
    public final int stepIndex;
    public final String sessionId;
    public final long createdAt = System.currentTimeMillis();

    /**
     * When this task was last armed to run. Set at creation and moved forward by a resume — the
     * one path where a later attempt is the SAME task rather than a new one.
     *
     * <p>Read through {@link #armedAt()}, never directly: a snapshot written before this field
     * existed restores it as 0, because Gson does not run field initialisers.
     */
    public long armedAt = System.currentTimeMillis();

    public String template;
    public String agentProfile;               // resolved from the template manifest; "" → default backend
    public List<String> credentials;          // credential keys the template granted; no values here
    public String taskText;
    public boolean includePreviousResult;
    public String previousWorkspace;          // container-side path of the previous step's workspace
    public String taskZipPath;                // optional task.zip to unzip into this task's task/
    public TaskMode mode = TaskMode.WORK;     // resume skips staging and continues the session
    public TaskConfiguration config = TaskConfiguration.defaults();
    public volatile TaskState state = TaskState.PENDING;
    public int attempt = 1;
    public String workspacePath;              // container-side path core reads (…/result)
    public String hostWorkspacePath;          // host-side path bind-mounted into the agent
    public String containerId;
    public String summary;
    public String verdict;                    // the agent's one-word answer, when it wrote one
    public String error;
    public String question;                   // what the agent asked when it stopped (state:asked)
    public List<String> secretsRequested;     // environment variables the pause asked for; no values here
    public List<String> grantedSecretNames;   // rescue-secret keys answered TO THIS task; scopes vault injection
    public List<ChildRunRequest> childRuns;   // runs the agent asked for; only the reconciler acts on them
    public String resumeMessage;              // the human's answer, injected on resume
    public volatile long lastHeartbeatAt;
    public long dispatchedAt;
    public long finishedAt;

    public Task(String id, String runId, int stepIndex, String sessionId) {
        this.id = id;
        this.runId = runId;
        this.stepIndex = stepIndex;
        this.sessionId = sessionId;
    }

    /**
     * When this task was last armed, falling back to when it was created.
     *
     * <p>The fallback is for a task restored from a snapshot written before {@code armedAt} existed:
     * Gson leaves an absent field at 0, and 0 would make an in-flight rework look older than every
     * task in the run. Creation time is what the comparison used before, so an old snapshot keeps
     * exactly the behaviour it was written under.
     */
    public long armedAt() {
        return armedAt == 0 ? createdAt : armedAt;
    }

    /**
     * Whether this task answered {@code expected} — the question every gate, branch and reconciler
     * loop asks.
     *
     * <p>An explicit {@code verdict} decides it. Falling back to a substring of the summary is what
     * this replaces: "no failures found" contains "fail", so a passing gate reworked itself. The
     * fallback stays for agents that write no verdict, unchanged, so existing workflows keep the
     * behaviour they were proven with.
     */
    public boolean reports(String expected) {
        if (verdict != null && !verdict.isBlank()) {
            return verdict.trim().equalsIgnoreCase(expected);
        }
        return summary != null && summary.toLowerCase().contains(expected.toLowerCase());
    }
}
