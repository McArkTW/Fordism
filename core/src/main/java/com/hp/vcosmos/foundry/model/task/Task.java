package com.hp.vcosmos.foundry.model.task;

import java.util.List;

/** A live task instance (mutable — state advances over its lifecycle). */
public final class Task {
    public final String id;
    public final String runId;
    public final int stepIndex;
    public final String sessionId;
    public final long createdAt = System.currentTimeMillis();

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
