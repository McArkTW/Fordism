package tw.mcark.tony.fordism.proxy;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import tw.mcark.tony.fordism.agentprofile.ApiFormat;
import tw.mcark.tony.fordism.launch.AgentBackend;
import tw.mcark.tony.fordism.launch.ModelRegistry;
import tw.mcark.tony.fordism.model.task.Task;
import tw.mcark.tony.fordism.model.task.TaskState;
import tw.mcark.tony.fordism.store.TaskRepository;
import tw.mcark.tony.fordism.workspace.TokenUsage;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.tinylog.Logger;

/**
 * Core's model proxy: every agent tool's model traffic passes through here, so token usage is read
 * off the wire in one format for all tools instead of out of each tool's own logs.
 *
 * <p>A proxied tool is given {@code <proxy>/proxy/<taskId>} in place of its endpoint's origin — the
 * path is kept, so {@code https://bedrock-runtime.<region>.amazonaws.com/anthropic/v1/messages}
 * becomes {@code http://fordism-core:8080/proxy/<taskId>/anthropic/v1/messages} — and a per-task
 * token in place of its key. This handler checks the token, forwards the call to the task's Agent
 * Profile origin with the real key put where the tool put the token, streams the response back
 * byte-for-byte, and records the call's usage in the task's workspace.
 *
 * <p>Three things it never does: send the tool's token upstream, let the usage reader touch the
 * bytes the tool receives, or write a request or response body anywhere unless the transcript is
 * switched on ({@link TranscriptRecorder}, redacted, off by default). The one request it changes is
 * an OpenAI Chat stream that did not ask for usage, which otherwise carries none.
 */
public final class UsageProxy {
    public static final String PREFIX = "/proxy/";

    /** Hop-by-hop, framing, or set by the HTTP client itself. accept-encoding is dropped so the
     *  upstream replies uncompressed and the usage reader sees plain bytes. */
    private static final Set<String> DROP_REQUEST = Set.of("host", "connection", "content-length", "expect",
            "upgrade", "keep-alive", "proxy-connection", "proxy-authorization", "te", "trailer",
            "transfer-encoding", "accept-encoding", "http2-settings");
    private static final Set<String> DROP_RESPONSE = Set.of("connection", "content-length", "keep-alive",
            "transfer-encoding", "trailer", "upgrade", "proxy-connection", "te");
    /** Where tools put their key. Whichever the tool used, the real key goes in the same place. */
    private static final List<String> KEY_HEADERS = List.of("authorization", "x-api-key", "x-goog-api-key", "api-key");
    private static final Set<TaskState> FINISHED =
            Set.of(TaskState.COLLECTED, TaskState.REAPED, TaskState.FAILED, TaskState.ASKED);
    private static final String GEMINI_ORIGIN = "https://generativelanguage.googleapis.com";

    private final TaskRepository tasks;
    private final ModelRegistry models;
    private final Optional<TranscriptRecorder> transcripts;
    private final Function<Task, Collection<String>> credentialValues;
    private final HttpClient client;

    public UsageProxy(TaskRepository tasks, ModelRegistry models) {
        this(tasks, models, Optional.empty(), task -> List.of());
    }

    /**
     * @param transcripts      present when {@code FORDISM_TRANSCRIPT=on}
     * @param credentialValues the credential values a task was granted, so the transcript can redact them
     */
    public UsageProxy(TaskRepository tasks, ModelRegistry models, Optional<TranscriptRecorder> transcripts,
            Function<Task, Collection<String>> credentialValues) {
        this.tasks = tasks;
        this.models = models;
        this.transcripts = transcripts;
        this.credentialValues = credentialValues;
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public void register(Javalin app) {
        for (HandlerType method : List.of(HandlerType.GET, HandlerType.POST, HandlerType.PUT,
                HandlerType.DELETE, HandlerType.PATCH)) {
            app.addHttpHandler(method, PREFIX + "{taskId}/<rest>", this::handle);
        }
    }

    void handle(Context ctx) {
        String taskId = ctx.pathParam("taskId");
        Optional<Task> found = tasks.find(taskId);
        if (found.isEmpty() || FINISHED.contains(found.get().state)) {
            ctx.status(403).result("no live task " + taskId);
            return;
        }
        Task task = found.get();
        if (!presented(ctx, task.proxyToken)) {
            ctx.status(401).result("this proxy route belongs to another task");
            return;
        }
        AgentBackend backend;
        URI target;
        try {
            backend = models.backend(task.agentProfile);
            target = target(backend, ctx, taskId, task.proxyToken);
        } catch (RuntimeException e) {
            ctx.status(502).result("proxy cannot route task " + taskId + ": " + e.getMessage());
            return;
        }
        String path = target.getRawPath();
        String method = ctx.method().name();

        HttpRequest.Builder request = HttpRequest.newBuilder(target);
        for (String name : Collections.list(ctx.req().getHeaderNames())) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (DROP_REQUEST.contains(lower)) {
                continue;
            }
            for (String value : Collections.list(ctx.req().getHeaders(name))) {
                request.header(name, KEY_HEADERS.contains(lower)
                        ? value.replace(task.proxyToken, backend.authToken()) : value);
            }
        }
        byte[] body = askForStreamUsage(path, ctx.bodyAsBytes());
        request.method(method, body.length == 0 ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body));

        long started = System.nanoTime();
        HttpResponse<InputStream> response;
        try {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            ctx.status(502).result("upstream unreachable: " + e.getMessage());
            UsageLedger.append(task, method, path, 502, elapsed(started), null, Optional.empty(), false);
            return;
        }

        HttpServletResponse out = ctx.res();
        out.setStatus(response.statusCode());
        response.headers().map().forEach((name, values) -> {
            if (!name.startsWith(":") && !DROP_RESPONSE.contains(name.toLowerCase(Locale.ROOT))) {
                values.forEach(value -> out.addHeader(name, value));
            }
        });
        String contentType = response.headers().firstValue("content-type").orElse("");
        Optional<UsageTap> tap = UsageTap.open(path, contentType, transcripts.isPresent());
        boolean complete = true;
        try (InputStream in = response.body()) {
            OutputStream sink = out.getOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                sink.write(buffer, 0, read);   // the tool gets the bytes first...
                sink.flush();
                if (tap.isPresent()) {
                    tap.get().accept(buffer, 0, read);   // ...the reader only a copy, afterwards
                }
            }
        } catch (IOException e) {
            // The tool hung up, or the upstream broke. Upstream is not drained to read its final count:
            // that would make the provider finish — and bill — a reply the tool abandoned. The line is
            // marked incomplete instead, so its count reads as a floor, not a fact.
            complete = false;
            Logger.warn("proxy task {} {} {}: stream ended early: {}", taskId, method, path, e.toString());
        }
        Optional<TokenUsage> usage = tap.flatMap(UsageTap::finish);
        long millis = elapsed(started);
        UsageLedger.append(task, method, path, response.statusCode(), millis,
                tap.map(UsageTap::format).orElse(null), usage, complete);
        if (transcripts.isPresent() && tap.isPresent()) {
            List<String> secrets = new java.util.ArrayList<>(credentialValues.apply(task));
            secrets.add(backend.authToken());
            secrets.add(task.proxyToken);
            transcripts.get().record(task, new TranscriptRecorder.Call(backend.tool().wireName(), method, path, response.statusCode(), millis,
                    tap.get().format(), body, tap.get().reply().orElse(null), usage.orElse(null), complete, secrets));
        }
    }

    /** The task's token, wherever this tool put its key — a header, or Gemini's {@code key} query. */
    private static boolean presented(Context ctx, String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        for (String header : KEY_HEADERS) {
            String value = ctx.header(header);
            if (value == null) {
                continue;
            }
            String candidate = value.regionMatches(true, 0, "Bearer ", 0, 7) ? value.substring(7).trim() : value.trim();
            if (same(candidate, token)) {
                return true;
            }
        }
        String query = ctx.queryParam("key");
        return query != null && same(query, token);
    }

    private static boolean same(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The profile's origin plus the path the tool called. The origin is the profile's scheme, host and
     * port only — the tool's path already carries the profile's path. Bedrock's model-discovery calls
     * that claude-code makes go to the control plane, a different host from the runtime.
     */
    static URI target(AgentBackend backend, Context ctx, String taskId, String token) {
        String base = backend.baseUrl();
        if (base == null || base.isBlank()) {
            if (backend.format() != ApiFormat.GEMINI) {
                throw new IllegalStateException("the task's Agent Profile has no baseUrl");
            }
            base = GEMINI_ORIGIN;
        }
        URI origin = URI.create(base.trim());
        String host = origin.getHost();
        String uri = ctx.req().getRequestURI();
        String marker = PREFIX + taskId;
        String path = uri.substring(uri.indexOf(marker) + marker.length());
        if (host != null && host.startsWith("bedrock-runtime.")
                && (path.startsWith("/inference-profiles") || path.startsWith("/foundation-models"))) {
            host = "bedrock." + host.substring("bedrock-runtime.".length());
        }
        String query = ctx.req().getQueryString();
        if (query != null) {
            query = query.replace(token, backend.authToken());
        }
        String port = origin.getPort() == -1 ? "" : ":" + origin.getPort();
        return URI.create(origin.getScheme() + "://" + host + port + (path.isEmpty() ? "/" : path)
                + (query == null || query.isEmpty() ? "" : "?" + query));
    }

    /**
     * An OpenAI Chat stream reports usage only if asked to. Every other request, and one that already
     * asked, is sent exactly as the tool wrote it.
     */
    static byte[] askForStreamUsage(String path, byte[] body) {
        if (!path.contains("/chat/completions") || body.length == 0) {
            return body;
        }
        try {
            JsonElement parsed = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                return body;
            }
            JsonObject request = parsed.getAsJsonObject();
            JsonElement stream = request.get("stream");
            if (stream == null || !stream.isJsonPrimitive() || !stream.getAsBoolean()) {
                return body;
            }
            JsonObject options = request.has("stream_options") && request.get("stream_options").isJsonObject()
                    ? request.getAsJsonObject("stream_options") : new JsonObject();
            if (options.has("include_usage")) {
                return body;
            }
            options.addProperty("include_usage", true);
            request.add("stream_options", options);
            return new GsonBuilder().disableHtmlEscaping().create().toJson(request).getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            return body;
        }
    }

    private static long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }
}
