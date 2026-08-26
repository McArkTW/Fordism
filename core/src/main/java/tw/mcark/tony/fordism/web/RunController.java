package tw.mcark.tony.fordism.web;

import com.google.gson.Gson;
import tw.mcark.tony.fordism.model.run.WorkflowRun;
import tw.mcark.tony.fordism.model.run.WorkflowRunState;
import tw.mcark.tony.fordism.model.task.Task;
import tw.mcark.tony.fordism.model.task.TaskMode;
import tw.mcark.tony.fordism.model.task.TaskState;
import tw.mcark.tony.fordism.orchestrate.Engine;
import tw.mcark.tony.fordism.secret.SecretVault;
import tw.mcark.tony.fordism.workspace.TaskResults;
import tw.mcark.tony.fordism.workspace.WorkspaceArchive;
import io.javalin.http.Context;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.tinylog.Logger;
import java.util.Optional;
import tw.mcark.tony.fordism.store.RunQuery;
import java.util.LinkedHashSet;
import java.util.Set;

/** /api/runs[...] — list runs, get a run with its tasks, preview + download a task's result. */
public final class RunController {
    private static final Gson GSON = new Gson();
    private final Engine engine;
    private final TaskResults results;
    private final WorkspaceArchive archive;
    private final SecretVault secrets;

    public RunController(Engine engine, TaskResults results, WorkspaceArchive archive, SecretVault secrets) {
        this.engine = engine;
        this.results = results;
        this.archive = archive;
        this.secrets = secrets;
    }

    /**
     * The run history: newest first, filtered and paged by query parameters.
     *
     * <p>The body stays a plain array — the app and ApiShapeTest already agree on that shape, and
     * paging metadata is not data. The cursor for the next page goes in {@code X-Next-Cursor}, and
     * its absence means this was the last page. With no parameters at all this answers exactly what
     * it always did.
     */
    public void list(Context ctx) {
        RunQuery query = queryFrom(ctx);
        List<WorkflowRun> found = engine.runs().query(query);
        boolean more = found.size() > query.effectiveLimit();
        List<WorkflowRun> page = more ? found.subList(0, query.effectiveLimit()) : found;

        String cursor = RunQuery.nextCursor(page, more);
        if (cursor != null) {
            ctx.header("X-Next-Cursor", cursor);
        }
        List<Views.RunSummary> out = new ArrayList<>();
        for (WorkflowRun run : page) {
            out.add(new Views.RunSummary(run.id, run.workflowName, run.state.toString(),
                    run.createdAt, durationOf(run)));
        }
        Api.json(ctx, out);
    }

    /**
     * The query the caller asked for. Every parameter is optional and an unreadable one is ignored
     * rather than rejected — a stale bookmark should show you runs, not an error.
     */
    private static RunQuery queryFrom(Context ctx) {
        // Both spellings: ?state=A&state=B and ?state=A,B. Callers reach for either, and reading
        // only the first value silently narrowed the filter instead of failing — Live asked for
        // three states and got one.
        Set<WorkflowRunState> states = new LinkedHashSet<>();
        for (String value : ctx.queryParams("state")) {
            for (String token : Api.names(value)) {
                runState(token).ifPresent(states::add);
            }
        }
        return new RunQuery(ctx.queryParam("workflow"), states, number(ctx.queryParam("since")),
                ctx.queryParam("q"), "oldest".equalsIgnoreCase(ctx.queryParam("sort")),
                ctx.queryParam("before"), (int) numberOr(ctx.queryParam("limit"), RunQuery.DEFAULT_LIMIT));
    }

    private static Optional<WorkflowRunState> runState(String token) {
        try {
            return Optional.of(WorkflowRunState.valueOf(token.trim().toUpperCase()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();   // a state nobody has — match nothing rather than 400
        }
    }

    private static Long number(String value) {
        return value == null || value.isBlank() ? null : numberOr(value, 0L);
    }

    private static long numberOr(String value, long fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public void get(Context ctx) {
        WorkflowRun run = pathRun(ctx).orElse(null);
        if (run == null) {
            return;
        }
        List<Task> tasks = engine.tasks().byRun(run.id);
        tasks.sort(Comparator.comparingInt(t -> t.stepIndex));
        List<Views.TaskDetail> taskViews = new ArrayList<>();
        for (Task task : tasks) {
            taskViews.add(detailOf(task));
        }
        Api.json(ctx, new Views.RunDetail(run.id, run.workflowName, run.strategy.wireName(), run.state.toString(),
                run.createdAt, run.workflowSnapshot, taskViews));
    }

    private Views.TaskDetail detailOf(Task task) {
        return new Views.TaskDetail(task.id, task.stepIndex, task.state.toString(), task.template,
                task.sessionId, task.hostWorkspacePath, task.summary, task.verdict, task.createdAt,
                task.attempt, task.error, task.question,
                task.secretsRequested == null ? List.of() : task.secretsRequested,
                secrets.namesFor(task.runId), elapsedOf(task), results.usage(task));
    }

    /** Live while it runs; frozen once it ended, even when nothing recorded a finish time. */
    private static long elapsedOf(Task task) {
        if (task.dispatchedAt <= 0) {
            return 0L;
        }
        boolean terminal = task.state == TaskState.COLLECTED || task.state == TaskState.FAILED
                || task.state == TaskState.REAPED || task.state == TaskState.ASKED;
        long end;
        if (task.finishedAt > 0) {
            end = task.finishedAt;
        } else if (terminal) {
            end = Math.max(task.lastHeartbeatAt, task.dispatchedAt);
        } else {
            end = System.currentTimeMillis();
        }
        return Math.max(0L, end - task.dispatchedAt);
    }

    /** Wall-clock from the first task dispatched to the last one that finished, 0 while running. */
    private long durationOf(WorkflowRun run) {
        long start = 0;
        long end = 0;
        for (Task task : engine.tasks().byRun(run.id)) {
            if (task.dispatchedAt > 0 && (start == 0 || task.dispatchedAt < start)) {
                start = task.dispatchedAt;
            }
            end = Math.max(end, task.finishedAt);
        }
        return start > 0 && end > start ? end - start : 0;
    }

    /** Every task that stopped to ask something, across all runs — the Questions inbox. */
    public void questions(Context ctx) {
        List<Views.Question> out = new ArrayList<>();
        for (Task task : engine.tasks().byState(TaskState.ASKED)) {
            // Names only, both ways: what this question asks for, and what the run already holds
            // from an earlier answer. The UI renders one masked field per outstanding name.
            out.add(new Views.Question(task.id, task.runId,
                    engine.runs().find(task.runId).map(run -> run.workflowName).orElse(""),
                    task.stepIndex, task.template, task.question,
                    task.secretsRequested == null ? List.of() : task.secretsRequested,
                    secrets.namesFor(task.runId), task.summary, task.createdAt));
        }
        out.sort(Comparator.comparingLong(Views.Question::createdAt));
        Api.json(ctx, out);
    }

    /** Preview a task's result/ files (name + text content). */
    public void result(Context ctx) {
        Task task = pathTask(ctx).orElse(null);
        if (task == null) {
            return;
        }
        Api.json(ctx, new Views.TaskResultFiles(task.id, task.hostWorkspacePath, results.files(task)));
    }

    /** Download a task's full result/ as a zip. */
    public void resultZip(Context ctx) {
        Task task = pathTask(ctx).orElse(null);
        if (task == null) {
            return;
        }
        ctx.contentType("application/zip");
        ctx.header("Content-Disposition", "attachment; filename=\"" + task.id + "-result.zip\"");
        try {
            archive.zipResult(task, ctx.outputStream());
        } catch (IOException e) {
            ctx.status(500);
        }
    }

    /** Download a task's ENTIRE workspace as a zip (task + result + logs + session store). */
    public void workspaceZip(Context ctx) {
        Task task = pathTask(ctx).orElse(null);
        if (task == null) {
            return;
        }
        ctx.contentType("application/zip");
        ctx.header("Content-Disposition", "attachment; filename=\"" + task.id + "-workspace.zip\"");
        try {
            archive.zipWorkspace(task, ctx.outputStream());
        } catch (IOException e) {
            ctx.status(500);
        }
    }

    /** The task's normalized session transcript (result/logs/transcript.jsonl). */
    public void transcript(Context ctx) {
        Task task = pathTask(ctx).orElse(null);
        if (task == null) {
            return;
        }
        String transcript = results.transcript(task).orElse(null);
        if (transcript == null) {
            ctx.status(404).contentType("application/json").result("{\"error\":\"no transcript\"}");
            return;
        }
        ctx.contentType("application/x-ndjson").result(transcript);
    }

    /**
     * Answer an ASKED task: inject the human's reply and resume the session. The task is re-armed
     * in resume mode over its EXISTING workspace (so {@code claude --resume} continues the same
     * session), and its run is reactivated so the dispatcher relaunches it.
     */
    public void answer(Context ctx) {
        Task task = pathTask(ctx).orElse(null);
        if (task == null) {
            return;
        }
        if (task.state != TaskState.ASKED) {
            ctx.status(400).contentType("application/json").result("{\"error\":\"this task is not waiting on an answer\"}");
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> body = GSON.fromJson(ctx.body(), Map.class);
        String message = body == null || body.get("message") == null ? "" : body.get("message").toString().trim();

        // Secrets travel beside the message, never inside it. resumeMessage becomes RESUME_PROMPT,
        // which is prompt text: it lands in the session transcript on disk and is served verbatim by
        // /api/tasks/{id}/transcript. A token pasted into the message box would sit there for the
        // life of the workspace. These go to the vault instead, and the agent is told the NAMES.
        Map<String, String> supplied = new LinkedHashMap<>();
        Object raw = body == null ? null : body.get("secrets");
        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String name = entry.getKey() == null ? null : entry.getKey().toString();
                String value = entry.getValue() == null ? null : entry.getValue().toString();
                String reason = SecretVault.reject(name, value);
                if (reason != null) {
                    ctx.status(400).contentType("application/json")
                            .result(Json.write(Map.of("error", reason)));
                    return;
                }
                supplied.put(name, value);
            }
        }

        if (message.isEmpty() && supplied.isEmpty()) {
            ctx.status(400).contentType("application/json").result("{\"error\":\"message or secrets required\"}");
            return;
        }
        if (!supplied.isEmpty()) {
            secrets.put(task.runId, supplied);
            String names = String.join(", ", supplied.keySet());
            String note = "The following are now set as environment variables in your container: " + names
                    + ". Their values are not in this message — read them from the environment, and do not"
                    + " print or write them anywhere. Continue from where you paused.";
            message = message.isEmpty() ? note : note + "\n\n" + message;
            Logger.info("answer task {} supplied secrets {}", task.id, supplied.keySet());
        }
        task.resumeMessage = message;
        task.secretsRequested = null;
        task.mode = TaskMode.RESUME;
        task.question = null;
        task.error = null;
        task.finishedAt = 0;
        task.state = TaskState.PENDING;   // dispatcher will relaunch it (resume mode)
        engine.tasks().save(task);
        engine.runs().find(task.runId).ifPresent(run -> {
            run.state = WorkflowRunState.ACTIVE;
            engine.runs().save(run);
        });
        ctx.status(200).contentType("application/json").result("{\"taskId\":\"" + task.id + "\",\"state\":\"resumed\"}");
    }

    /**
     * Stop a run that is still going. The state is the whole instruction: the run leaves the
     * orchestrator's set immediately, and the next reconcile tick culls whatever it still had
     * running (see OrphanCuller). Nothing is deleted — the workspace stays readable.
     */
    public void abandon(Context ctx) {
        WorkflowRun run = pathRun(ctx).orElse(null);
        if (run == null) {
            return;
        }
        if (run.state.isTerminal()) {
            Api.fail(ctx, 409, "run is already " + run.state.name().toLowerCase() + " — nothing to stop");
            return;
        }
        engine.finishRun(run, WorkflowRunState.ABANDONED);
        Logger.info("run {} abandoned by a human", run.id);
        Api.json(ctx, new Views.RunSummary(run.id, run.workflowName, run.state.toString(),
                run.createdAt, 0L));
    }

    /** The task named in the path, or empty after answering 404. */
    private Optional<Task> pathTask(Context ctx) {
        Optional<Task> task = engine.tasks().find(ctx.pathParam("id"));
        if (task.isEmpty()) {
            Api.fail(ctx, 404, "unknown task");
        }
        return task;
    }

    /** The run named in the path, or empty after answering 404. */
    private Optional<WorkflowRun> pathRun(Context ctx) {
        Optional<WorkflowRun> run = engine.runs().find(ctx.pathParam("id"));
        if (run.isEmpty()) {
            Api.fail(ctx, 404, "unknown run");
        }
        return run;
    }
}
