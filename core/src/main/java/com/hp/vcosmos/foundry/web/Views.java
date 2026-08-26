package com.hp.vcosmos.foundry.web;

import com.hp.vcosmos.foundry.workspace.ResultFile;
import com.hp.vcosmos.foundry.workspace.TokenUsage;
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

    public record RunSummary(String id, String workflow, String state, long createdAt, long durationMs) {}

    public record RunDetail(String id, String workflow, String strategy, String state, long createdAt,
                            String workflowSnapshot, List<TaskDetail> tasks) {}

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

    public record WorkflowStep(String id, String template, List<String> dependsOn, String forEach,
                               String when, boolean includePreviousResult, int timeoutSeconds) {}

    public record WorkflowParameter(String name, String label, String type, boolean required,
                                    String defaultValue, String help) {}

    /** Whether a workflow could start now, and the first reason it could not. */
    public record Preflight(boolean ready, String problem) {}

    /** The acknowledgement for a started run. */
    public record RunStarted(String runId, String workflow, String state) {}
}
