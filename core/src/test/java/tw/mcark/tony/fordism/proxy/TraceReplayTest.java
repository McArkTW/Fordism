package tw.mcark.tony.fordism.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import tw.mcark.tony.fordism.model.task.Task;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Golden traces: real request sequences captured from each tool on Bedrock ({@code resources/traces/}),
 * replayed through the recorder. They prove three things per tool:
 *
 * <ol>
 *   <li>the classification of every call — append, tail edit ({@code from}), or rewrite — is what the real run
 *       produced, so a change to the recorder or a fix that alters it fails here;</li>
 *   <li>rebuilding each call's history from the transcript (previous history, cut to {@code from}, plus
 *       {@code new_messages}) gives exactly the messages the tool sent — nothing lost, nothing invented;</li>
 *   <li>a tool registered with a fix actually needs it: replayed without its fixes, its trace rewrites history.</li>
 * </ol>
 *
 * <p>Captured 2026-09-17 with a pass-through in front of real Bedrock; checked for keys before committing.
 */
class TraceReplayTest {

    @TempDir
    Path dir;

    /** Expected per model call: "append", "from:k", or "reset". */
    private static final Map<String, List<String>> EXPECTED = Map.of(
            "claude-code", List.of("append", "append", "append", "append", "append"),
            "dsh", List.of("append", "append", "append", "append", "append", "append"),
            "openclaw", List.of("append", "from:1", "from:3"),
            "pi", List.of("append", "append"));

    @Test
    void claudeCode() throws Exception {
        verify("claude-code");
    }

    @Test
    void dsh() throws Exception {
        verify("dsh");
    }

    @Test
    void openclaw() throws Exception {
        verify("openclaw");
    }

    @Test
    void pi() throws Exception {
        verify("pi");
    }

    @Test
    void claudeCodeAndDshNeedTheirFix_withoutItTheirTracesRewriteHistory() throws Exception {
        for (String tool : List.of("claude-code", "dsh")) {
            List<JsonObject> lines = replay(tool, "tool-with-no-fixes");   // resolves to the default chain
            long rewrites = lines.stream().filter(l -> l.has("from")).count();
            assertTrue(rewrites > 0, tool + ": the trace no longer needs single-text-block-as-string; "
                    + "re-examine before keeping it registered");
        }
    }

    private void verify(String tool) throws Exception {
        List<JsonObject> lines = replay(tool, tool);
        List<String> actual = new ArrayList<>();
        for (JsonObject line : lines) {
            actual.add(line.has("reset") ? "reset" : line.has("from") ? "from:" + line.get("from").getAsInt() : "append");
        }
        assertEquals(EXPECTED.get(tool), actual, tool + " classification per call");
        assertHistoriesRebuildExactly(tool, lines);
    }

    /** Replays the trace as {@code asTool}, returning the transcript lines, in call order. */
    private List<JsonObject> replay(String traceTool, String asTool) throws Exception {
        Task task = new Task("t-" + asTool + "-" + traceTool, "r", 0, "s");
        task.workspacePath = dir.resolve(asTool + "-" + traceTool).toString();
        TranscriptRecorder recorder = new TranscriptRecorder(false, Runnable::run);
        for (JsonObject request : requests(traceTool)) {
            String path = request.get("path").getAsString();
            String format = UsageExtractor.forPath(path).orElseThrow().format();
            byte[] body = request.get("body").toString().getBytes(StandardCharsets.UTF_8);
            recorder.record(task, new TranscriptRecorder.Call(asTool, "POST", path, 200, 1, format, body,
                    null, null, true, List.of()));
        }
        List<JsonObject> lines = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(task.workspacePath, "result", "logs", "llm.jsonl"))) {
            lines.add(JsonParser.parseString(line).getAsJsonObject());
        }
        return lines;
    }

    private static void assertHistoriesRebuildExactly(String tool, List<JsonObject> lines) throws Exception {
        List<JsonObject> requests = requests(tool);
        Map<String, List<JsonElement>> history = new HashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            JsonObject line = lines.get(i);
            String path = requests.get(i).get("path").getAsString();
            String format = UsageExtractor.forPath(path).orElseThrow().format();
            JsonArray sent = FormatCodecs.forFormat(format).read(requests.get(i).getAsJsonObject("body"), path).messages;

            List<JsonElement> rebuilt = new ArrayList<>(history.getOrDefault(line.get("thread").getAsString(), List.of()));
            if (line.has("from")) {
                rebuilt = new ArrayList<>(rebuilt.subList(0, line.get("from").getAsInt()));
            }
            line.getAsJsonArray("new_messages").forEach(rebuilt::add);
            history.put(line.get("thread").getAsString(), rebuilt);

            JsonArray rebuiltArray = new JsonArray();
            rebuilt.forEach(rebuiltArray::add);
            // Where a fix made two shapes compare equal, the stored copy is the first shape seen; compare in the
            // tool's fixed form, which is exactly the equality the transcript promises.
            List<TranscriptFix> fixes = ToolFixes.forTool(tool);
            // Per message, as the recorder compares them: a message-level rule does nothing to a whole list.
            JsonArray want = new JsonArray();
            sent.forEach(m -> want.add(TranscriptFix.apply(fixes, m)));
            JsonArray got = new JsonArray();
            rebuiltArray.forEach(m -> got.add(TranscriptFix.apply(fixes, m)));
            if (!want.equals(got)) {
                int at = 0;
                while (at < Math.min(want.size(), got.size()) && want.get(at).equals(got.get(at))) {
                    at++;
                }
                String sentAt = at < want.size() ? want.get(at).toString() : "<none>";
                String gotAt = at < got.size() ? got.get(at).toString() : "<none>";
                throw new AssertionError(tool + " call " + (i + 1) + ": rebuilt history differs at message " + at
                        + " of " + want.size() + " sent / " + got.size() + " rebuilt (from=" + line.get("from") + ")\n sent:    "
                        + sentAt.substring(0, Math.min(600, sentAt.length())) + "\n rebuilt: " + gotAt.substring(0, Math.min(600, gotAt.length())));
            }
        }
    }

    private static List<JsonObject> requests(String tool) throws Exception {
        try (InputStream in = TraceReplayTest.class.getResourceAsStream("/traces/" + tool + ".json.gz")) {
            if (in == null) {
                throw new AssertionError("missing trace fixture for " + tool);
            }
            String json = new String(new GZIPInputStream(in).readAllBytes(), StandardCharsets.UTF_8);
            List<JsonObject> out = new ArrayList<>();
            JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("requests")
                    .forEach(r -> out.add(r.getAsJsonObject()));
            return out;
        }
    }
}
