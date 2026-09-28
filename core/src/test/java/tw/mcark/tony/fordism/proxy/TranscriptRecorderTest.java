package tw.mcark.tony.fordism.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import tw.mcark.tony.fordism.model.task.Task;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TranscriptRecorderTest {
    private static final String KEY = "real-provider-key-1234567890";
    private static final String TOKEN = "task-token-abcdef123456";
    private static final String CREDENTIAL = "granted-credential-value-XYZ";

    @TempDir
    Path workspace;
    private Task task;
    private TranscriptRecorder recorder;
    private String tool = "pi";   // a tool with no fixes of its own: the default chain

    @BeforeEach
    void setUp() {
        task = new Task("t1", "r1", 0, "s1");
        task.workspacePath = workspace.toString();
        recorder = new TranscriptRecorder(false, Runnable::run);
    }

    @AfterEach
    void restoreLimit() {
        TranscriptRecorder.maxTaskBytes = TranscriptRecorder.MAX_TASK_BYTES;
    }

    private void call(String system, JsonArray messages, String replyText) {
        JsonObject request = new JsonObject();
        request.addProperty("model", "global.anthropic.claude-sonnet-5");
        request.addProperty("max_tokens", 1000);
        if (system != null) {
            request.addProperty("system", system);
        }
        JsonArray tools = new JsonArray();
        JsonObject toolDefinition = new JsonObject();
        toolDefinition.addProperty("name", "write");
        tools.add(toolDefinition);
        request.add("tools", tools);
        request.add("messages", messages);
        JsonObject reply = new JsonObject();
        reply.addProperty("type", "message");
        reply.addProperty("text", replyText);
        recorder.record(task, new TranscriptRecorder.Call(tool, "POST", "/anthropic/v1/messages", 200, 10, "anthropic",
                request.toString().getBytes(StandardCharsets.UTF_8), reply, null, true, List.of(KEY, TOKEN, CREDENTIAL)));
    }

    private static JsonObject message(String role, String text, boolean cacheMarker) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        JsonArray content = new JsonArray();
        JsonObject block = new JsonObject();
        block.addProperty("type", "text");
        block.addProperty("text", text);
        if (cacheMarker) {
            JsonObject cache = new JsonObject();
            cache.addProperty("type", "ephemeral");
            block.add("cache_control", cache);
        }
        content.add(block);
        m.add("content", content);
        return m;
    }

    private List<JsonObject> lines() throws Exception {
        List<JsonObject> out = new ArrayList<>();
        for (String line : Files.readAllLines(workspace.resolve("result/logs/llm.jsonl"))) {
            out.add(JsonParser.parseString(line).getAsJsonObject());
        }
        return out;
    }

    private List<JsonObject> blobs() throws Exception {
        List<JsonObject> out = new ArrayList<>();
        Path file = workspace.resolve("result/logs/llm-blobs.jsonl");
        if (Files.exists(file)) {
            for (String line : Files.readAllLines(file)) {
                out.add(JsonParser.parseString(line).getAsJsonObject());
            }
        }
        return out;
    }

    @Test
    void eachCallStoresOnlyWhatItAddedEvenWhenCacheMarkersMove() throws Exception {
        JsonArray history = new JsonArray();
        history.add(message("user", "write the file", true));
        call("You are an agent.", history, "a1");

        JsonArray second = new JsonArray();
        second.add(message("user", "write the file", false));      // marker moved off this message...
        second.add(message("assistant", "a1", false));
        second.add(message("user", "tool result 1", true));         // ...onto the newest one
        call("You are an agent.", second, "a2");

        JsonArray third = second.deepCopy();
        third.get(2).getAsJsonObject().getAsJsonArray("content").get(0).getAsJsonObject().remove("cache_control");
        third.add(message("assistant", "a2", false));
        third.add(message("user", "tool result 2", true));
        call("You are an agent.", third, "a3");

        List<JsonObject> lines = lines();
        assertEquals(3, lines.size());
        assertEquals(1, lines.get(0).getAsJsonArray("new_messages").size());
        assertEquals(2, lines.get(1).getAsJsonArray("new_messages").size());
        assertEquals(2, lines.get(2).getAsJsonArray("new_messages").size());
        lines.forEach(l -> assertFalse(l.has("reset"), "no call rewrote history: " + l));
        assertEquals("a3", lines.get(2).getAsJsonObject("response").get("text").getAsString());
        assertEquals(1000, lines.get(0).getAsJsonObject("params").get("max_tokens").getAsInt());
        assertTrue(lines.toString().contains("cache_control"), "a fix changes how messages compare, never what is stored");
        assertEquals(2, blobs().size(), "system prompt and tools are stored once for all three calls");
        assertEquals(lines.get(0).get("system"), lines.get(2).get("system"));
    }

    @Test
    void aHistoryWithANewFirstMessageIsAnotherConversationStoredWhole() throws Exception {
        JsonArray first = new JsonArray();
        first.add(message("user", "a", false));
        first.add(message("assistant", "b", false));
        call("sys", first, "r1");
        JsonArray compacted = new JsonArray();
        compacted.add(message("user", "summary of a and b", false));
        call("sys", compacted, "r2");

        List<JsonObject> lines = lines();
        assertFalse(lines.get(0).get("thread").equals(lines.get(1).get("thread")));
        assertFalse(lines.get(1).has("reset"));
        assertEquals(1, lines.get(1).getAsJsonArray("new_messages").size());
    }

    @Test
    void twoConversationsSharingASystemPromptInterleaveWithoutRewrites() throws Exception {
        // kimi runs two explore subagents at once: one system prompt, a different first message each.
        JsonArray a = new JsonArray();
        a.add(message("user", "explore repo A", false));
        JsonArray b = new JsonArray();
        b.add(message("user", "explore repo B", false));
        call("explorer", a, "a1");
        call("explorer", b, "b1");
        JsonArray a2 = a.deepCopy();
        a2.add(message("assistant", "a1", false));
        a2.add(message("user", "result A", false));
        call("explorer", a2, "a2");
        JsonArray b2 = b.deepCopy();
        b2.add(message("assistant", "b1", false));
        b2.add(message("user", "result B", false));
        call("explorer", b2, "b2");

        List<JsonObject> lines = lines();
        assertEquals(lines.get(0).get("thread"), lines.get(2).get("thread"));
        assertEquals(lines.get(1).get("thread"), lines.get(3).get("thread"));
        assertFalse(lines.get(0).get("thread").equals(lines.get(1).get("thread")));
        for (JsonObject line : lines) {
            assertFalse(line.has("from"), "no call rewrote its own conversation: " + line);
        }
        assertEquals(2, lines.get(2).getAsJsonArray("new_messages").size());
        assertEquals(2, lines.get(3).getAsJsonArray("new_messages").size());
    }

    @Test
    void aToolThatSwapsItsNewestMessageCostsOnlyThatMessage() throws Exception {
        JsonArray first = new JsonArray();
        first.add(message("user", "task", false));
        first.add(message("user", "volatile context v1", false));
        call("sys", first, "r1");
        JsonArray second = new JsonArray();
        second.add(message("user", "task", false));
        second.add(message("assistant", "r1", false));            // the volatile message is replaced...
        second.add(message("user", "volatile context v2", false));
        call("sys", second, "r2");

        JsonObject line = lines().get(1);
        assertEquals(1, line.get("from").getAsInt(), "history cut back to the one message both calls share");
        assertFalse(line.has("reset"));
        assertEquals(2, line.getAsJsonArray("new_messages").size());
    }

    @Test
    void forATool_withThatFix_aStringAndASingleTextBlockAreTheSameMessage() throws Exception {
        tool = "claude-code";
        JsonArray first = new JsonArray();
        first.add(message("user", "same words", true));           // array form, carrying a cache marker
        call("sys", first, "r1");
        JsonArray second = new JsonArray();
        JsonObject plain = new JsonObject();
        plain.addProperty("role", "user");
        plain.addProperty("content", "same words");                // string form, marker gone
        second.add(plain);
        second.add(message("assistant", "r1", false));
        call("sys", second, "r2");

        JsonObject line = lines().get(1);
        assertFalse(line.has("from"), "an unchanged message in another shape is not a rewrite: " + line);
        assertEquals(1, line.getAsJsonArray("new_messages").size());
        assertEquals("claude-code", line.get("tool").getAsString());
        assertEquals("[\"strip-cache-markers\",\"single-text-block-as-string\"]", line.get("fixes").toString());
        assertEquals("assistant", line.getAsJsonArray("new_messages").get(0).getAsJsonObject().get("role").getAsString(),
                "only the reply is new; the reshaped user message is recognised and not stored again");
    }

    @Test
    void forAToolWithoutThatFix_theSameShapeChangeIsARewrite() throws Exception {
        tool = "pi";   // nobody has seen pi do this, so pi does not get the fix
        JsonArray first = new JsonArray();
        first.add(message("user", "same words", false));
        call("sys", first, "r1");
        JsonArray second = new JsonArray();
        JsonObject plain = new JsonObject();
        plain.addProperty("role", "user");
        plain.addProperty("content", "same words");
        second.add(plain);
        call("sys", second, "r2");

        List<JsonObject> lines = lines();
        JsonObject line = lines.get(1);
        assertFalse(lines.get(0).get("thread").equals(line.get("thread")),
                "without the fix the shapes differ: another first message, not guessed equal");
        assertEquals(1, line.getAsJsonArray("new_messages").size(), "so the message is stored again");
        assertEquals("[\"strip-cache-markers\"]", line.get("fixes").toString());
    }

    @Test
    void aSideCallWithAnotherSystemPromptDoesNotBreakTheMainThread() throws Exception {
        JsonArray main = new JsonArray();
        main.add(message("user", "task", false));
        call("main agent", main, "m1");
        JsonArray title = new JsonArray();
        title.add(message("user", "make a title", false));
        call("title writer", title, "Title");
        JsonArray mainAgain = main.deepCopy();
        mainAgain.add(message("assistant", "m1", false));
        mainAgain.add(message("user", "next", false));
        call("main agent", mainAgain, "m2");

        List<JsonObject> lines = lines();
        assertEquals(lines.get(0).get("thread"), lines.get(2).get("thread"));
        assertFalse(lines.get(0).get("thread").equals(lines.get(1).get("thread")));
        assertFalse(lines.get(2).has("reset"));
        assertEquals(2, lines.get(2).getAsJsonArray("new_messages").size());
    }

    @Test
    void aLargeFileReadTwiceIsStoredOnce() throws Exception {
        String file = "x".repeat(100 * 1024);
        JsonArray first = new JsonArray();
        first.add(message("user", file, false));
        call("s", first, "r");
        JsonArray again = new JsonArray();
        again.add(message("user", file, false));
        call("other system", again, "r");

        String text = String.join("\n", Files.readAllLines(workspace.resolve("result/logs/llm.jsonl")));
        assertFalse(text.contains("xxxxxxxxxx"), "the 100 KB text is referenced, not inlined");
        long textBlobs = blobs().stream().filter(b -> b.get("kind").getAsString().equals("text")).count();
        assertEquals(1, textBlobs, "same content, one blob");
    }

    @Test
    void inlineImagesBecomePlaceholdersAndAreSavedOnlyWhenAsked() throws Exception {
        byte[] png = new byte[5000];
        png[0] = (byte) 0x89;
        String data = Base64.getEncoder().encodeToString(png);
        JsonArray messages = new JsonArray();
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        JsonArray content = new JsonArray();
        JsonObject image = new JsonObject();
        image.addProperty("type", "image");
        JsonObject source = new JsonObject();
        source.addProperty("type", "base64");
        source.addProperty("media_type", "image/png");
        source.addProperty("data", data);
        image.add("source", source);
        content.add(image);
        user.add("content", content);
        messages.add(user);

        call("s", messages, "r");
        String text = Files.readString(workspace.resolve("result/logs/llm.jsonl"));
        assertFalse(text.contains(data.substring(0, 200)), "no base64 in the transcript");
        JsonObject placeholder = lines().get(0).getAsJsonArray("new_messages").get(0).getAsJsonObject()
                .getAsJsonArray("content").get(0).getAsJsonObject().getAsJsonObject("source").getAsJsonObject("data");
        assertEquals(5000, placeholder.get("bytes").getAsInt());
        assertEquals("image/png", placeholder.get("media_type").getAsString());
        assertFalse(Files.exists(workspace.resolve("result/logs/llm-files")));

        recorder = new TranscriptRecorder(true, Runnable::run);
        task = new Task("t2", "r1", 0, "s2");
        task.workspacePath = workspace.resolve("second").toString();
        call("s", messages, "r");
        try (Stream<Path> saved = Files.list(workspace.resolve("second/result/logs/llm-files"))) {
            Path file = saved.findFirst().orElseThrow();
            assertTrue(file.toString().endsWith(".png"));
            assertEquals(5000, Files.size(file));
        }
    }

    @Test
    void noSecretSurvivesAnywhereItCouldAppear() throws Exception {
        String bigWithSecret = "y".repeat(70 * 1024) + " " + CREDENTIAL + " ";
        JsonArray messages = new JsonArray();
        messages.add(message("user", "key " + KEY + " token " + TOKEN + " cred " + CREDENTIAL, false));
        // Assembled at runtime so the source never holds a literal that secret scanners (rightly) flag.
        String fakeAwsKey = "AKIA" + "ABCDEFGHIJKLMNOP";
        messages.add(message("user", "found " + fakeAwsKey + " and ghp_" + "a".repeat(36) + " in a file", false));
        messages.add(message("user", bigWithSecret, false));
        call("system mentions " + CREDENTIAL, messages, "reply echoes " + KEY);

        String everything = Files.readString(workspace.resolve("result/logs/llm.jsonl"))
                + Files.readString(workspace.resolve("result/logs/llm-blobs.jsonl"));
        for (String secret : List.of(KEY, TOKEN, CREDENTIAL, fakeAwsKey, "ghp_" + "a".repeat(36))) {
            assertFalse(everything.contains(secret), "leaked: " + secret);
        }
        assertTrue(everything.contains("[REDACTED]"));
    }

    @Test
    void atTheTaskLimitOneStopLineIsWrittenAndNothingAfter() throws Exception {
        TranscriptRecorder.maxTaskBytes = 3000;
        for (int i = 0; i < 20; i++) {
            JsonArray messages = new JsonArray();
            messages.add(message("user", "message number " + i + " " + "z".repeat(300), false));
            call("s" + i, messages, "r");
        }
        List<JsonObject> lines = lines();
        assertEquals("size limit", lines.get(lines.size() - 1).get("stopped").getAsString());
        assertEquals(1, lines.stream().filter(l -> l.has("stopped")).count());
    }
}
