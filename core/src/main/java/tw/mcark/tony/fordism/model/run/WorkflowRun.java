package tw.mcark.tony.fordism.model.run;

import tw.mcark.tony.fordism.model.workflow.Strategy;
import java.util.Map;

/** A live workflow-run instance (mutable). */
public final class WorkflowRun {
    public final String id;
    public final String workflowName;
    public final Strategy strategy;
    public final Map<String, String> parameterValues;
    public final long createdAt = System.currentTimeMillis();

    public int currentStepIndex = 0;
    public int iteration = 0;

    /**
     * The run that spawned this one, and which of its iterations did. Null and 0 for a run a human
     * started, which is every run except a reconciler's children.
     *
     * <p>The iteration is recorded as well as the parent, because a reconciler loops: without it,
     * the pass that is waiting cannot tell the runs it just spawned from the ones the pass before
     * it already finished, and would wait for children that ended two iterations ago.
     */
    public String parentRunId;
    public int parentIteration;
    public String taskZipPath;               // optional per-run task.zip staged into step 0's task/
    public String workflowSnapshot;          // the workflow YAML as it was at run time

    /**
     * The run whose {@code artifact/} this one starts from — null for a run that starts from an empty
     * one. Copied, never shared: each run owns its own product folder, so continuing from a run cannot
     * change it, and the source is held from culling until this run has ended.
     */
    public String artifactFrom;

    /**
     * When the run reached a terminal state, which is where the artifact's retention window starts.
     * 0 while it is going, and on a run restored from a snapshot written before this existed — which
     * then counts from {@link #createdAt}, the earliest moment it could have ended.
     */
    public long endedAt;
    public volatile WorkflowRunState state = WorkflowRunState.ACTIVE;

    public WorkflowRun(String id, String workflowName, Strategy strategy, Map<String, String> parameterValues) {
        this.id = id;
        this.workflowName = workflowName;
        this.strategy = strategy;
        this.parameterValues = parameterValues;
    }
}
