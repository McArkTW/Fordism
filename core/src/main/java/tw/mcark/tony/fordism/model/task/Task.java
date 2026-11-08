package tw.mcark.tony.fordism.model.task;

import tw.mcark.tony.fordism.model.workflow.ArtifactAccess;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

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
    public Map<String, String> fanInWorkspaces; // label -> workspace, when several predecessors feed this one
    public String taskZipPath;                // optional task.zip to unzip into this task's task/
    public TaskMode mode = TaskMode.WORK;     // resume skips staging and continues the session
    public TaskConfiguration config = TaskConfiguration.defaults();
    public volatile TaskState state = TaskState.PENDING;
    public int attempt = 1;
    public String workspacePath;              // container-side path core reads (…/result)
    public String hostWorkspacePath;          // host-side path bind-mounted into the agent
    /**
     * The run's {@code artifact/} — the product, as opposed to this task's own bench. One folder per
     * run, mounted at {@code /workspace/artifact} in every task of it; {@code artifact} says whether
     * this one may write it. {@code artifactFrom} is the run whose artifact the run started from, so
     * the stager can copy it in the first time the folder is laid out. All null on a task restored
     * from a snapshot written before artifacts existed, which then simply mounts none.
     */
    public ArtifactAccess artifact;
    public String artifactPath;               // container-side path core lays the artifact out at
    public String hostArtifactPath;           // host-side path bind-mounted at /workspace/artifact
    public String artifactFrom;
    public String containerId;
    public String proxyToken;                 // the key a proxied tool holds instead of the profile's; valid only for this task
    public String summary;
    public String verdict;                    // the agent's one-word answer, when it wrote one
    public String error;
    public String question;                   // what the agent asked when it stopped (state:asked)
    public List<String> secretsRequested;     // environment variables the pause asked for; no values here
    public List<String> grantedSecretNames;   // rescue-secret keys answered TO THIS task; scopes vault injection
    public List<ChildRunRequest> childRuns;   // runs the agent asked for; only the reconciler acts on them
    public String resumeMessage;              // the human's answer, injected on resume
    /**
     * The task whose agent this one continues ({@code continues: true}): its workspace and session
     * are this task's. Null for a task that started its own agent.
     */
    public String continuesTaskId;
    /**
     * Where this task's {@code task/} and {@code result/} live now, relative to its workspace. Null
     * while they are at the top, which is every task nobody has continued; {@code steps/<index>}
     * once a continuing task set them aside to take the bench.
     */
    public String signOff;
    /**
     * Whether the continuing agent kept its conversation. Null until the task is dispatched (and on
     * every task that continues nothing); false for a one-shot tool, which gets the workspace but a
     * fresh session.
     */
    public Boolean conversationKept;
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
     * The folder holding this task's {@code task/} and {@code result/}: the workspace itself, or the
     * place a continuing task moved them to. What "the previous workspace" means to the stager,
     * which copies {@code result/} out of it — so a predecessor whose sign-off was set aside still
     * hands its own result on, not the result of the task that now holds the bench.
     */
    public String signOffPath() {
        if (workspacePath == null) {
            return null;
        }
        return signOff == null ? workspacePath : Paths.get(workspacePath, signOff).toString();
    }

    /** This task's {@code result/}, wherever its sign-off lives now; null before it was staged. */
    public Path resultDirectory() {
        String folder = signOffPath();
        return folder == null ? null : Paths.get(folder, "result");
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
