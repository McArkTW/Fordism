package tw.mcark.tony.fordism.proxy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import tw.mcark.tony.fordism.model.task.Task;
import tw.mcark.tony.fordism.workspace.TokenUsage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.tinylog.Logger;

/**
 * Writes the model conversation of every proxied call to the task's {@code result/logs/llm.jsonl} —
 * one transcript format for every tool, on when {@code FORDISM_TRANSCRIPT=on}.
 *
 * <p>Each line is one call: what the call added to the conversation, and the reply rebuilt from its
 * stream. A call re-sends its whole history, so storing requests as they are would store the
 * conversation once per call; only messages beyond the previous call's are kept. Calls are grouped
 * into threads by their system prompt, so a tool's side calls (titles, summaries) do not break the
 * main conversation's deltas. A call whose history no longer extends the previous one stores only what
 * follows the part they share, with {@code "from": k} — the history was cut back to its first k
 * messages first. A full rewrite (the tool compacted everything) is {@code "from": 0, "reset": true}.
 * So a thread's history at call n is: history at the previous call, cut to {@code from} if present,
 * plus {@code new_messages}.
 *
 * <p>Large and repeated content goes to {@code llm-blobs.jsonl} by sha256: the system prompt and tool
 * definitions once, and any text over 64 KB (a file the agent read, read ten times, stored once).
 * Inline binaries — images, PDFs, as base64 in any format — are replaced by their size and hash, and
 * saved to {@code llm-files/} only with {@code FORDISM_TRANSCRIPT_FILES=on}.
 *
 * <p>Nothing is written before redaction: every secret core handed the container (the provider key,
 * the task token, the template's credentials) is removed by exact match, then common token shapes by
 * pattern. A secret the agent found by itself, in a shape no pattern knows, is not caught — that is
 * why this is off by default.
 *
 * <p>All of this runs on one background thread after the call has finished, so the tool never waits
 * on it. If that thread falls behind, calls are dropped rather than queued without bound, and the next
 * line says how many.
 */
public final class TranscriptRecorder {
    static final String FILE = "llm.jsonl";
    static final String BLOBS = "llm-blobs.jsonl";
    static final String FILES_DIR = "llm-files";
    static final int BLOB_TEXT_BYTES = 64 * 1024;
    static final int MAX_LINE_BYTES = 2 * 1024 * 1024;
    static final int MAX_BLOB_BYTES = 16 * 1024 * 1024;
    static final long MAX_TASK_BYTES = 200L * 1024 * 1024;
    /** The per-task limit in force; a field only so a test can reach it without writing 200 MB. */
    static long maxTaskBytes = MAX_TASK_BYTES;
    static final long MAX_FILES_BYTES = 100L * 1024 * 1024;
    private static final int KEEP_TASKS = 500;

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final List<Pattern> TOKEN_SHAPES = List.of(
            Pattern.compile("\\b(?:AKIA|ASIA)[A-Z0-9]{16}\\b"),
            Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]{20,}"),
            Pattern.compile("\\bsk-[A-Za-z0-9_-]{20,}"),
            Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{36,}"),
            Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{22,}"),
            Pattern.compile("\\bxai-[A-Za-z0-9]{20,}"),
            Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}"),
            Pattern.compile("bedrock-api-key-[A-Za-z0-9+/=]{20,}"),
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----", Pattern.DOTALL));
    private static final Pattern BASE64 = Pattern.compile("^[A-Za-z0-9+/=\\r\\n_-]+$");

    /** One finished call, captured on the proxy thread. */
    record Call(String tool, String method, String path, int status, long millis, String format, byte[] requestBody,
                JsonObject reply, TokenUsage usage, boolean complete, Collection<String> secrets) {
    }

    private final boolean saveFiles;
    private final Executor executor;
    private final AtomicLong dropped = new AtomicLong();
    private final Map<String, TaskLog> logs = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, TaskLog> eldest) {
            return size() > KEEP_TASKS;
        }
    };

    public TranscriptRecorder(boolean saveFiles) {
        this(saveFiles, backgroundWriter());
    }

    TranscriptRecorder(boolean saveFiles, Executor executor) {
        this.saveFiles = saveFiles;
        this.executor = executor;
    }

    private static Executor backgroundWriter() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(256), runnable -> {
                    Thread thread = new Thread(runnable, "transcript-writer");
                    thread.setDaemon(true);
                    return thread;
                });
        return pool;
    }

    /** Hands the call to the writer. Never blocks the proxy and never throws into it. */
    void record(Task task, Call call) {
        if (task.workspacePath == null || task.workspacePath.isBlank()) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    write(task, call);
                } catch (RuntimeException e) {
                    Logger.warn("transcript for task {} lost a call: {}", task.id, e.toString());
                }
            });
        } catch (RuntimeException rejected) {
            dropped.incrementAndGet();
        }
    }

    /** Per-task state: the last history hash per thread, blobs already written, bytes spent. */
    /**
     * Where one call's transcript output goes: the task's open log, the directory its blobs are
     * written to, and the credential values to redact on the way. The three are never used apart —
     * every writer below needs all of them — so they travel as one rather than as three arguments
     * a caller could transpose.
     */
    private record CallSink(TaskLog log, Path logs, Collection<String> secrets) {}

    /**
     * Where a value sits in the tree being walked: its key and the object holding it. An array
     * element has neither, which is {@link #loose()} — the pair is what tells a base64 string from
     * a sibling media type, so it travels together or not at all.
     */
    private record Slot(String key, JsonObject parent) {
        static Slot loose() {
            return new Slot(null, null);
        }
    }

    /**
     * One thing to store: the content as written, and the form the tool's fixes see it in, which is
     * what the reference hashes. They differ only where a fix applies, and storing one against the
     * other's hash would silently split a blob in two.
     */
    private record Stored(JsonElement content, JsonElement identity) {
        static Stored same(JsonElement both) {
            return new Stored(both, both);
        }
    }

    private static final class TaskLog {
        final Map<String, List<String>> threads = new HashMap<>();
        final Set<String> blobs = new HashSet<>();
        long bytes;
        long fileBytes;
        long calls;
        boolean stopped;
    }

    private void write(Task task, Call call) {
        Path logs = Paths.get(task.workspacePath, "result", "logs");
        TaskLog log;
        synchronized (this.logs) {
            log = this.logs.computeIfAbsent(task.id, id -> fresh(logs));
        }
        if (log.stopped) {
            return;
        }
        log.calls++;
        // Everything below writes through this: the task's log, where its blobs go, and the values
        // to redact on the way out.
        CallSink sink = new CallSink(log, logs, call.secrets());

        JsonObject request = parse(call.requestBody());
        FormatCodec codec = FormatCodecs.forFormat(call.format());
        ConversationParts parts = codec.read(request, call.path());
        List<TranscriptFix> fixes = ToolFixes.forTool(call.tool());

        JsonObject line = new JsonObject();
        line.addProperty("call", log.calls);
        line.addProperty("at", Instant.now().toString());
        line.addProperty("attempt", task.attempt);
        if (call.tool() != null) {
            line.addProperty("tool", call.tool());
        }
        line.addProperty("format", call.format());
        JsonArray fixNames = new JsonArray();
        fixes.forEach(f -> fixNames.add(f.name()));
        line.add("fixes", fixNames);
        if (parts.model != null) {
            line.addProperty("model", parts.model);
        }
        line.addProperty("method", call.method());
        line.addProperty("path", call.path());
        line.addProperty("status", call.status());
        line.addProperty("ms", call.millis());
        if (!call.complete()) {
            line.addProperty("complete", false);
        }
        long lost = dropped.getAndSet(0);
        if (lost > 0) {
            line.addProperty("dropped_before", lost);
        }

        // A thread is the format's key plus the conversation's first message. The key alone mixed up two
        // conversations sharing a system prompt: kimi runs two explore subagents at once, each opening with
        // its own first message, and every switch between them read as a full history rewrite.
        JsonElement threadKey = codec.threadKey(parts);
        JsonArray threadKeyAndFirst = new JsonArray();
        threadKeyAndFirst.add(threadKey == null ? new JsonPrimitive("") : TranscriptFix.apply(fixes, threadKey));
        if (!parts.messages.isEmpty()) {
            threadKeyAndFirst.add(TranscriptFix.apply(fixes, parts.messages.get(0)));
        }
        String thread = sha256(GSON.toJson(threadKeyAndFirst)).substring(0, 12);
        line.addProperty("thread", thread);
        if (parts.system != null) {
            line.addProperty("system",
                    blob(sink, "system", new Stored(parts.system, TranscriptFix.apply(fixes, parts.system))));
        }
        if (parts.tools != null) {
            line.addProperty("tools",
                    blob(sink, "tools", new Stored(parts.tools, TranscriptFix.apply(fixes, parts.tools))));
        }
        if (parts.params != null && !parts.params.isEmpty()) {
            line.add("params", parts.params);
        }

        // Messages are compared in the form the tool's fixes give them, and stored exactly as sent.
        JsonArray messages = parts.messages;
        List<String> hashes = new ArrayList<>();
        messages.forEach(m -> hashes.add(sha256(GSON.toJson(TranscriptFix.apply(fixes, m)))));
        List<String> previous = log.threads.get(thread);
        // Keep the longest run of messages this call shares with the previous one, store the rest. A tool
        // that only replaces its newest message (openclaw swaps a volatile context message each turn) then
        // costs one message, not its whole history. "from": k says the history was cut back to k messages
        // before these were added; k = 0 is a full rewrite, also marked "reset".
        int kept = 0;
        if (previous != null) {
            while (kept < previous.size() && kept < hashes.size() && previous.get(kept).equals(hashes.get(kept))) {
                kept++;
            }
            if (kept < previous.size()) {
                line.addProperty("from", kept);
                if (kept == 0) {
                    line.addProperty("reset", true);
                }
            }
        }
        JsonArray added = new JsonArray();
        for (int i = kept; i < messages.size(); i++) {
            added.add(messages.get(i));
        }
        log.threads.put(thread, hashes);
        line.add("new_messages", shrink(sink, added));
        if (call.reply() != null) {
            line.add("response", shrink(sink, call.reply()));
        }
        if (call.usage() != null) {
            JsonObject usage = new JsonObject();
            usage.addProperty("input_tokens", call.usage().inputTokens());
            usage.addProperty("cache_creation_input_tokens", call.usage().cacheWriteTokens());
            usage.addProperty("cache_read_input_tokens", call.usage().cacheReadTokens());
            usage.addProperty("output_tokens", call.usage().outputTokens());
            line.add("usage", usage);
        }

        String text = redact(GSON.toJson(line), call.secrets());
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_LINE_BYTES) {
            // Still too big after blobbing long strings: the line keeps its metadata, the content moves out.
            JsonElement moved = line.remove("new_messages");
            line.addProperty("new_messages_blob", blob(sink, "text", Stored.same(moved)));
            if (line.has("response")) {
                JsonElement reply = line.remove("response");
                line.addProperty("response_blob", blob(sink, "text", Stored.same(reply)));
            }
            text = redact(GSON.toJson(line), call.secrets());
        }
        append(log, logs.resolve(FILE), text);
    }

    private static TaskLog fresh(Path logs) {
        TaskLog log = new TaskLog();
        for (String name : new String[] {FILE, BLOBS}) {
            try {
                if (Files.exists(logs.resolve(name))) {
                    log.bytes += Files.size(logs.resolve(name));
                }
            } catch (IOException e) {
                // unreadable size: the limit simply starts from zero
            }
        }
        return log;
    }

    /** Writes a line, or — once, at the task limit — the line that says writing stopped. */
    private static void append(TaskLog log, Path file, String text) {
        byte[] bytes = (text + "\n").getBytes(StandardCharsets.UTF_8);
        if (log.stopped) {
            return;
        }
        if (log.bytes + bytes.length > maxTaskBytes) {
            log.stopped = true;
            file = file.resolveSibling(FILE);   // the stop line belongs in the transcript, whichever file hit the limit
            bytes = ("{\"stopped\":\"size limit\",\"limit_bytes\":" + maxTaskBytes + ",\"at\":\""
                    + Instant.now() + "\"}\n").getBytes(StandardCharsets.UTF_8);
        }
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.bytes += bytes.length;
        } catch (IOException e) {
            Logger.warn("transcript not written to {}: {}", file, e.toString());
        }
    }

    /**
     * Stores content once per task and returns its reference. The reference is the hash of {@code identity},
     * the content as the tool's fixes see it, so a system prompt whose cache marker moved is still one blob;
     * the first form seen is the one stored.
     */
    private String blob(CallSink sink, String kind, Stored stored) {
        String json = redact(GSON.toJson(stored.content()), sink.secrets());
        String hash = "sha256:" + sha256(redact(GSON.toJson(stored.identity()), sink.secrets()));
        if (sink.log().blobs.add(hash)) {
            JsonObject record = new JsonObject();
            record.addProperty("sha256", hash);
            record.addProperty("kind", kind);
            record.addProperty("bytes", json.getBytes(StandardCharsets.UTF_8).length);
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BLOB_BYTES) {
                record.addProperty("truncated", true);
                record.addProperty("head", json.substring(0, Math.min(json.length(), 64 * 1024)));
                record.addProperty("tail", json.substring(Math.max(0, json.length() - 64 * 1024)));
            } else {
                record.add("content", JsonParser.parseString(json));
            }
            append(sink.log(), sink.logs().resolve(BLOBS), GSON.toJson(record));
        }
        return hash;
    }

    /** Long strings to blobs, inline binaries to placeholders; works on a copy. */
    private JsonElement shrink(CallSink sink, JsonElement element) {
        return walk(sink, element.deepCopy(), Slot.loose());
    }

    private JsonElement walk(CallSink sink, JsonElement e, Slot slot) {
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            for (String k : new ArrayList<>(o.keySet())) {
                o.add(k, walk(sink, o.get(k), new Slot(k, o)));
            }
            return o;
        }
        if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            for (int i = 0; i < a.size(); i++) {
                a.set(i, walk(sink, a.get(i), Slot.loose()));
            }
            return a;
        }
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            return e;
        }
        String s = e.getAsString();
        if (s.startsWith("data:") && s.contains(";base64,") && s.length() > 256) {
            String mediaType = s.substring(5, s.indexOf(';'));
            return binary(sink, s.substring(s.indexOf(";base64,") + 8), mediaType);
        }
        if (("data".equals(slot.key()) || "bytes".equals(slot.key()))
                && s.length() > 1024 && BASE64.matcher(s).matches()) {
            return binary(sink, s, mediaType(slot.parent()));
        }
        if (s.getBytes(StandardCharsets.UTF_8).length > BLOB_TEXT_BYTES) {
            JsonObject ref = new JsonObject();
            ref.addProperty("$blob", blob(sink, "text", Stored.same(new JsonPrimitive(s))));
            ref.addProperty("bytes", s.getBytes(StandardCharsets.UTF_8).length);
            return ref;
        }
        return e;
    }

    private static String mediaType(JsonObject parent) {
        if (parent == null) {
            return null;
        }
        for (String k : new String[] {"media_type", "mimeType", "mime_type", "format"}) {
            if (parent.has(k) && parent.get(k).isJsonPrimitive()) {
                return parent.get(k).getAsString();
            }
        }
        return null;
    }

    private JsonObject binary(CallSink sink, String base64, String mediaType) {
        byte[] bytes = null;
        try {
            bytes = Base64.getMimeDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            try {
                bytes = Base64.getUrlDecoder().decode(base64);
            } catch (IllegalArgumentException ignored) {
                // not decodable: hash the text instead
            }
        }
        String hash = bytes != null ? sha256(bytes) : sha256(base64);
        JsonObject placeholder = new JsonObject();
        placeholder.addProperty("$binary", "sha256:" + hash);
        placeholder.addProperty("bytes", bytes != null ? bytes.length : base64.length());
        if (mediaType != null) {
            placeholder.addProperty("media_type", mediaType);
        }
        if (saveFiles && bytes != null && sink.log().fileBytes + bytes.length <= MAX_FILES_BYTES) {
            Path file = sink.logs().resolve(FILES_DIR).resolve(hash + extension(mediaType));
            try {
                if (!Files.exists(file)) {
                    Files.createDirectories(file.getParent());
                    Files.write(file, bytes);
                    sink.log().fileBytes += bytes.length;
                }
                placeholder.addProperty("file", FILES_DIR + "/" + file.getFileName());
            } catch (IOException e) {
                Logger.warn("transcript file not saved: {}", e.toString());
            }
        }
        return placeholder;
    }

    private static String extension(String mediaType) {
        if (mediaType == null) {
            return ".bin";
        }
        String type = mediaType.toLowerCase(Locale.ROOT);
        if (type.contains("png")) return ".png";
        if (type.contains("jpeg") || type.contains("jpg")) return ".jpg";
        if (type.contains("gif")) return ".gif";
        if (type.contains("webp")) return ".webp";
        if (type.contains("pdf")) return ".pdf";
        return ".bin";
    }

    /** Every secret core handed the container, then every common token shape. */
    static String redact(String text, Collection<String> secrets) {
        String out = text;
        if (secrets != null) {
            for (String secret : secrets) {
                if (secret == null || secret.length() < 8) {
                    continue;
                }
                out = out.replace(secret, "[REDACTED]");
                String escaped = GSON.toJson(secret);
                escaped = escaped.substring(1, escaped.length() - 1);
                if (!escaped.equals(secret)) {
                    out = out.replace(escaped, "[REDACTED]");
                }
            }
        }
        for (Pattern shape : TOKEN_SHAPES) {
            Matcher m = shape.matcher(out);
            if (m.find()) {
                out = m.replaceAll("[REDACTED]");
            }
        }
        return out;
    }

    private static JsonObject parse(byte[] body) {
        try {
            JsonElement e = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
            return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }

    static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
