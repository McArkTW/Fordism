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
    public String parentRunId;
    public String childRunId;
    public String taskZipPath;               // optional per-run task.zip staged into step 0's task/
    public String workflowSnapshot;          // the workflow YAML as it was at run time
    public volatile WorkflowRunState state = WorkflowRunState.ACTIVE;

    public WorkflowRun(String id, String workflowName, Strategy strategy, Map<String, String> parameterValues) {
        this.id = id;
        this.workflowName = workflowName;
        this.strategy = strategy;
        this.parameterValues = parameterValues;
    }
}
