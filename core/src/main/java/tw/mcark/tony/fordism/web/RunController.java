package tw.mcark.tony.fordism.web;

import com.google.gson.Gson;
import tw.mcark.tony.fordism.model.run.WorkflowRun;
import tw.mcark.tony.fordism.model.run.WorkflowRunState;
import tw.mcark.tony.fordism.model.task.Task;
import tw.mcark.tony.fordism.model.task.TaskMode;
import tw.mcark.tony.fordism.model.task.TaskState;
import tw.mcark.tony.fordism.orchestrate.Engine;
import tw.mcark.tony.fordism.secret.SecretVault;
import tw.mcark.tony.fordism.workspace.ArtifactStore;
import tw.mcark.tony.fordism.workspace.TaskResults;
import tw.mcark.tony.fordism.workspace.WorkspaceArchive;
import io.javalin.http.Context;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
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
    /** The most entries the artifact view lists; a dependency tree can hold hundreds of thousands. */
    private static final int ARTIFACT_LISTING_LIMIT = 2000;
    private final Engine engine;
    private final TaskResults results;
    private final WorkspaceArchive archive;
    private final SecretVault secrets;
    private final ArtifactStore artifacts;

    public RunController(Engine engine, TaskResults results, WorkspaceArchive archive, SecretVault secrets,
            ArtifactStore artifacts) {
        this.engine = engine;
        this.results = results;
        this.archive = archive;
        this.secrets = secrets;
        this.artifacts = artifacts;
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
            out.add(summaryOf(run));
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
        List<Views.RunSummary> children = new ArrayList<>();
        for (WorkflowRun child : engine.runs().children(run.id)) {
            children.add(summaryOf(child));
        }
        // createdAt then id, for the same reason RunQuery pages that way: an iteration starts all
        // of its children in one tick, so on the timestamp alone they tie and the order the page
        // renders them in changes between requests.
        children.sort(Comparator.comparingLong(Views.RunSummary::createdAt)
                .thenComparing(Views.RunSummary::id));
        Api.json(ctx, new Views.RunDetail(run.id, run.workflowName, run.strategy.wireName(), run.state.toString(),
                run.createdAt, run.workflowSnapshot, taskViews, run.parentRunId, children, artifactOf(run)));
    }

    /** Where the run's artifact is, as docker mounts it, and whether there is one there now. */
    private Views.RunArtifact artifactOf(WorkflowRun run) {
        return new Views.RunArtifact(artifacts.hostPath(run.id), artifacts.exists(run.id), run.artifactFrom);
    }

    /**
     * What the run's artifact holds, by name and size — the run page's view of the product.
     *
     * <p>{@code .git} and {@code node_modules} are listed as one entry and not entered: they are
     * the bulk of a working tree by count and none of what a person opens the page to see.
     */
    public void artifact(Context ctx) {
        WorkflowRun run = pathRun(ctx).orElse(null);
        if (run == null) {
            return;
        }
        Path root = artifacts.path(run.id);
        ArtifactListing listing;
        try {
            listing = ArtifactListing.of(root, ARTIFACT_LISTING_LIMIT);
        } catch (IOException e) {
            Api.fail(ctx, 500, "could not read the artifact: " + e.getMessage());
            return;
        }
        Api.json(ctx, new Views.ArtifactFiles(run.id, artifacts.hostPath(run.id), Files.isDirectory(root),
                listing.files(), listing.truncated()));
    }

    /**
     * An artifact's entries, at most {@code limit} of them.
     *
     * <p>A file-tree visit, not a stream and a filter: {@code Files.walk} would still descend every
     * entry under {@code node_modules} only to have them dropped, and walk on past the cap. Here a
     * bulk folder is listed and its subtree skipped, and the visit stops as soon as the cap is hit.
     */
    record ArtifactListing(List<Views.ArtifactEntry> files, boolean truncated) {

        private static final Set<String> BULK = Set.of(".git", "node_modules");

        static ArtifactListing of(Path root, int limit) throws IOException {
            List<Views.ArtifactEntry> files = new ArrayList<>();
            boolean[] truncated = {false};
            if (!Files.isDirectory(root)) {
                return new ArtifactListing(files, false);
            }
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (directory.equals(root)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (!add(directory, attributes)) {
                        return FileVisitResult.TERMINATE;
                    }
                    return BULK.contains(directory.getFileName().toString())
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    return add(file, attributes) ? FileVisitResult.CONTINUE : FileVisitResult.TERMINATE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException failure) {
                    return FileVisitResult.CONTINUE;   // unreadable is not worth failing the listing for
                }

                /** False once the cap is reached: the entry is not listed and the visit stops. */
                private boolean add(Path path, BasicFileAttributes attributes) {
                    if (files.size() >= limit) {
                        truncated[0] = true;
                        return false;
                    }
                    boolean directory = attributes.isDirectory();
                    files.add(new Views.ArtifactEntry(root.relativize(path).toString().replace('\\', '/'),
                            directory ? 0L : attributes.size(), directory));
                    return true;
                }
            });
            return new ArtifactListing(files, truncated[0]);
        }
    }


    /** Download the run's artifact — the product — as a zip. */
    public void artifactZip(Context ctx) {
        WorkflowRun run = pathRun(ctx).orElse(null);
        if (run == null) {
            return;
        }
        ctx.contentType("application/zip");
        ctx.header("Content-Disposition", "attachment; filename=\"" + run.id + "-artifact.zip\"");
        try {
            archive.zipArtifact(artifacts.path(run.id), ctx.outputStream());
        } catch (IOException e) {
            ctx.status(500);
        }
    }

    /** One row of the run list — and of the children a reconciler's page lists under it. */
    private Views.RunSummary summaryOf(WorkflowRun run) {
        return new Views.RunSummary(run.id, run.workflowName, run.state.toString(),
                run.createdAt, durationOf(run), run.parentRunId, run.artifactFrom);
    }

    Views.TaskDetail detailOf(Task task) {
        return new Views.TaskDetail(task.id, task.stepIndex, task.state.toString(), task.template,
                task.sessionId, task.hostWorkspacePath, task.summary, task.verdict, task.createdAt,
                task.attempt, task.error, task.question,
                task.secretsRequested == null ? List.of() : task.secretsRequested,
                secrets.namesFor(task.runId), elapsedOf(task), results.usage(task), continuationOf(task),
                task.signOff);
    }

    /** The task this one continued, as the run page names it; null for a task with its own agent. */
    private Views.Continuation continuationOf(Task task) {
        if (task.continuesTaskId == null) {
            return null;
        }
        int step = engine.tasks().find(task.continuesTaskId).map(continued -> continued.stepIndex).orElse(-1);
        return new Views.Continuation(task.continuesTaskId, step, task.conversationKept);
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

    /**
     * The task's transcript. Core's proxy transcript when the task was proxied — one shape for
     * every tool — and the tool's own session store otherwise, which is the only thing an
     * un-proxied task has. The app parses both.
     */
    public void transcript(Context ctx) {
        Task task = pathTask(ctx).orElse(null);
        if (task == null) {
            return;
        }
        String transcript = results.proxyTranscript(task).or(() -> results.transcript(task)).orElse(null);
        if (transcript == null) {
            ctx.status(404).contentType("application/json").result("{\"error\":\"no transcript\"}");
            return;
        }
        // The charset is not optional: Jetty encodes a String body for a type it does not know as text
        // in ISO-8859-1, so every em dash and middle dot reached the browser as a lone, undecodable byte.
        ctx.contentType("application/x-ndjson; charset=utf-8").result(transcript);
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
            // Record on the task which keys it was answered, so the launcher injects the run's
            // vault secrets only into the steps that declared or asked for them — not every
            // later container. Merge, because a second rescue can add to the first.
            List<String> granted = task.grantedSecretNames == null
                    ? new ArrayList<>() : new ArrayList<>(task.grantedSecretNames);
            for (String key : supplied.keySet()) {
                if (!granted.contains(key)) {
                    granted.add(key);
                }
            }
            task.grantedSecretNames = granted;
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
        Api.json(ctx, summaryOf(run));
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
