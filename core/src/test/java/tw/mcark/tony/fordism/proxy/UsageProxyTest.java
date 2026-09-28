package tw.mcark.tony.fordism.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import tw.mcark.tony.fordism.agentprofile.AgentProfile;
import tw.mcark.tony.fordism.agentprofile.AgentTool;
import tw.mcark.tony.fordism.agentprofile.ApiFormat;
import tw.mcark.tony.fordism.config.FordismConfiguration;
import tw.mcark.tony.fordism.launch.ModelRegistry;
import tw.mcark.tony.fordism.model.task.Task;
import tw.mcark.tony.fordism.model.task.TaskState;
import tw.mcark.tony.fordism.store.InMemoryTaskRepository;
import tw.mcark.tony.fordism.workspace.TaskResults;
import tw.mcark.tony.fordism.workspace.TokenUsage;
import com.sun.net.httpserver.HttpServer;
import io.javalin.Javalin;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The proxy end to end: a real Javalin route in front of a real upstream HTTP server. */
class UsageProxyTest {
    private static final String TOKEN = "task-token-0001";
    private static final String REAL_KEY = "real-provider-key";

    @TempDir
    Path workspace;

    private HttpServer upstream;
    private Javalin core;
    private InMemoryTaskRepository tasks;
    private Task task;
    private final Map<String, String> seen = new ConcurrentHashMap<>();
    private final AtomicInteger upstreamCalls = new AtomicInteger();
    private final HttpClient client = HttpClient.newHttpClient();

    private static final String SSE_FIRST =
            "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":7,"
            + "\"cache_creation_input_tokens\":27569,\"cache_read_input_tokens\":3,\"output_tokens\":1}}}\n\n";
    private static final String SSE_REST =
            "event: message_delta\ndata: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":100}}\n\n"
            + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";

    @BeforeEach
    void start() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            upstreamCalls.incrementAndGet();
            seen.put("path", exchange.getRequestURI().getRawPath());
            seen.put("query", String.valueOf(exchange.getRequestURI().getRawQuery()));
            seen.put("x-api-key", String.valueOf(exchange.getRequestHeaders().getFirst("x-api-key")));
            seen.put("accept-encoding", String.valueOf(exchange.getRequestHeaders().getFirst("accept-encoding")));
            seen.put("body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String path = exchange.getRequestURI().getRawPath();
            try {
                if (path.endsWith("/slow")) {
                    Thread.sleep(35_000);   // longer than Jetty's default 30s idle timeout
                    byte[] body = "{\"type\":\"message\",\"usage\":{\"input_tokens\":1,\"output_tokens\":2}}"
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("content-type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                } else {
                    exchange.getResponseHeaders().add("content-type", "text/event-stream");
                    exchange.getResponseHeaders().add("x-request-id", "req-abc");
                    exchange.sendResponseHeaders(200, 0);
                    OutputStream out = exchange.getResponseBody();
                    out.write(SSE_FIRST.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    Thread.sleep(1500);
                    if (exchange.getRequestHeaders().containsKey("x-test-long-tail")) {
                        // enough bytes that writing them to a tool that hung up must fail, not buffer
                        byte[] pad = ": keep-alive padding\n\n".repeat(4096).getBytes(StandardCharsets.UTF_8);
                        for (int i = 0; i < 64; i++) {
                            out.write(pad);
                            out.flush();
                        }
                    }
                    out.write(SSE_REST.getBytes(StandardCharsets.UTF_8));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        upstream.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        upstream.start();

        AgentProfile profile = new AgentProfile("id-1", "bench-profile",
                "http://127.0.0.1:" + upstream.getAddress().getPort() + "/anthropic", REAL_KEY,
                "global.anthropic.claude-sonnet-5", AgentTool.OPENCODE, ApiFormat.ANTHROPIC);
        ModelRegistry models = new ModelRegistry(new FordismConfiguration(),
                name -> name.equals("bench-profile") ? Optional.of(profile) : Optional.empty(), Optional::empty);

        tasks = new InMemoryTaskRepository();
        task = new Task("task-1", "run-1", 0, "session-1");
        task.state = TaskState.RUNNING;
        task.agentProfile = "bench-profile";
        task.proxyToken = TOKEN;
        task.workspacePath = workspace.toString();
        tasks.save(task);

        core = Javalin.create();
        new UsageProxy(tasks, models).register(core);
        core.start(0);
    }

    @AfterEach
    void stop() {
        core.stop();
        upstream.stop(0);
    }

    private URI proxied(String path) {
        return URI.create("http://127.0.0.1:" + core.port() + "/proxy/task-1" + path);
    }

    @Test
    void streamsThroughUnbufferedSwapsTheKeyAndRecordsUsage() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(proxied("/anthropic/v1/messages?beta=true"))
                .header("x-api-key", TOKEN)
                .header("accept-encoding", "gzip")
                .POST(HttpRequest.BodyPublishers.ofString("{\"stream\":true}"))
                .build();
        long started = System.nanoTime();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        assertEquals("text/event-stream", response.headers().firstValue("content-type").orElse(""));
        assertEquals("req-abc", response.headers().firstValue("x-request-id").orElse(""));

        InputStream in = response.body();
        byte[] first = new byte[SSE_FIRST.length()];
        int got = 0;
        while (got < first.length) {
            int n = in.read(first, got, first.length - got);
            if (n < 0) {
                break;
            }
            got += n;
        }
        long firstEventMillis = (System.nanoTime() - started) / 1_000_000L;
        assertTrue(firstEventMillis < 1200,
                "the first event must reach the tool before upstream finishes; took " + firstEventMillis + "ms");
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        all.write(first, 0, got);
        all.write(in.readAllBytes());
        assertEquals(SSE_FIRST + SSE_REST, all.toString(StandardCharsets.UTF_8), "bytes must pass through unchanged");

        assertEquals("/anthropic/v1/messages", seen.get("path"), "the tool's path is kept under the profile's origin");
        assertEquals("beta=true", seen.get("query"));
        assertEquals(REAL_KEY, seen.get("x-api-key"), "upstream gets the real key where the tool put its token");
        assertEquals("null", seen.get("accept-encoding"), "upstream must answer uncompressed");
        assertEquals("{\"stream\":true}", seen.get("body"));

        Path ledger = workspace.resolve("result/logs/usage.jsonl");
        List<String> lines = waitForLines(ledger, 1);
        JsonObject line = JsonParser.parseString(lines.get(0)).getAsJsonObject();
        assertEquals(200, line.get("status").getAsInt());
        assertEquals("anthropic", line.get("format").getAsString());
        assertEquals("/anthropic/v1/messages", line.get("path").getAsString());
        assertEquals(7, line.get("input_tokens").getAsLong());
        assertEquals(27569, line.get("cache_creation_input_tokens").getAsLong());
        assertEquals(3, line.get("cache_read_input_tokens").getAsLong());
        assertEquals(100, line.get("output_tokens").getAsLong());
        assertFalse(lines.get(0).contains(TOKEN) || lines.get(0).contains(REAL_KEY), "no key in the ledger");

        TokenUsage summed = new TaskResults().usage(task);
        assertEquals(TokenUsage.of(new TokenUsage.Counts(7, 27569, 3, 100), 1), summed, "Fordism's task metric reads the ledger");
    }

    @Test
    void withTheTranscriptOnTheCallIsRecordedWithoutAnyKey() throws Exception {
        core.stop();
        core = Javalin.create();
        AgentProfile profile = new AgentProfile("id-1", "bench-profile",
                "http://127.0.0.1:" + upstream.getAddress().getPort() + "/anthropic", REAL_KEY,
                "global.anthropic.claude-sonnet-5", AgentTool.OPENCODE, ApiFormat.ANTHROPIC);
        new UsageProxy(tasks, new ModelRegistry(new FordismConfiguration(), n -> Optional.of(profile), Optional::empty),
                Optional.of(new TranscriptRecorder(false, Runnable::run)), t -> List.of()).register(core);
        core.start(0);

        String request = "{\"model\":\"global.anthropic.claude-sonnet-5\",\"stream\":true,\"system\":\"be brief\","
                + "\"messages\":[{\"role\":\"user\",\"content\":\"hello " + TOKEN + "\"}]}";
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(proxied("/anthropic/v1/messages"))
                .header("x-api-key", TOKEN)
                .POST(HttpRequest.BodyPublishers.ofString(request)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(SSE_FIRST + SSE_REST, response.body(), "the tool's bytes are untouched with the transcript on");

        Path transcript = workspace.resolve("result/logs/llm.jsonl");
        JsonObject line = JsonParser.parseString(waitForLines(transcript, 1).get(0)).getAsJsonObject();
        assertEquals("anthropic", line.get("format").getAsString());
        assertEquals(1, line.getAsJsonArray("new_messages").size());
        assertEquals(100, line.getAsJsonObject("response").getAsJsonObject("usage").get("output_tokens").getAsInt());
        assertEquals(27569, line.getAsJsonObject("usage").get("cache_creation_input_tokens").getAsLong());
        String all = Files.readString(transcript) + Files.readString(workspace.resolve("result/logs/llm-blobs.jsonl"));
        assertFalse(all.contains(TOKEN) || all.contains(REAL_KEY), "no key or token in the transcript");
    }

    @Test
    void aToolThatHangsUpMidStreamLeavesAnIncompleteLine() throws Exception {
        HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(proxied("/anthropic/v1/messages"))
                .header("x-api-key", TOKEN)
                .header("x-test-long-tail", "1")
                .POST(HttpRequest.BodyPublishers.ofString("{\"stream\":true}")).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        InputStream in = response.body();
        in.readNBytes(SSE_FIRST.length());
        in.close();   // the tool hangs up before the rest arrives

        JsonObject line = JsonParser.parseString(waitForLines(workspace.resolve("result/logs/usage.jsonl"), 1).get(0))
                .getAsJsonObject();
        assertFalse(line.get("complete").getAsBoolean(), "a cut stream must say its count is a floor: " + line);
        assertEquals(7, line.get("input_tokens").getAsLong(), "what did arrive is still recorded");
    }

    @Test
    void aWrongTokenIsRefusedAndNeverReachesUpstream() throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(proxied("/anthropic/v1/messages"))
                .header("authorization", "Bearer someone-elses-token")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
        assertEquals(0, upstreamCalls.get());
    }

    @Test
    void aFinishedTaskIsRefused() throws Exception {
        task.state = TaskState.COLLECTED;
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(proxied("/anthropic/v1/messages"))
                .header("x-api-key", TOKEN)
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, response.statusCode());
        assertEquals(0, upstreamCalls.get());
    }

    @Test
    void bearerTokenAndQueryKeyAreBothSwapped() throws Exception {
        client.send(HttpRequest.newBuilder(proxied("/anthropic/v1/messages?key=" + TOKEN))
                .header("authorization", "Bearer " + TOKEN)
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals("key=" + REAL_KEY, seen.get("query"));
    }

    @Test
    void aSilentUpstreamLongerThanTheIdleTimeoutStillAnswers() throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(proxied("/anthropic/slow"))
                .header("x-api-key", TOKEN)
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"output_tokens\":2"), response.body());
    }

    private static List<String> waitForLines(Path file, int count) throws Exception {
        for (int i = 0; i < 50; i++) {
            if (Files.exists(file)) {
                List<String> lines = Files.readAllLines(file);
                if (lines.size() >= count) {
                    return lines;
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("ledger never got " + count + " line(s): " + file);
    }
}
