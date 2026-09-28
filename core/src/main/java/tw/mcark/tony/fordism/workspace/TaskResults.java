package tw.mcark.tony.fordism.workspace;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import tw.mcark.tony.fordism.model.task.ChildRunRequest;
import tw.mcark.tony.fordism.model.task.ReportedState;
import tw.mcark.tony.fordism.model.task.Task;
import tw.mcark.tony.fordism.model.task.TaskResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads back what an agent left in its workspace: the result contract, the files it produced, the
 * session transcript, and the token usage inside it.
 *
 * <p>Everything here is a sensor — it only ever reads, and an absent or half-written file is a
 * normal answer (the agent is mid-run), not a failure.
 */
public final class TaskResults {
    private static final Gson GSON = new Gson();
    private static final long INLINE_PREVIEW_LIMIT_BYTES = 200_000;
    private static final int BINARY_SNIFF_BYTES = 8000;

    /**
     * The task's {@code result/result.json}, or empty while the agent has not written a readable
     * one yet.
     *
     * <p>Parsed as JSON, because it is JSON. This used to scrape the fields with regexes, and
     * {@code "((?:[^"\\]|\\.)*)"} is the textbook catastrophic-backtracking alternation: fine on a
     * one-line summary, and on a couple of kilobytes of markdown with many escaped newlines and
     * quotes it explodes and throws StackOverflowError — which killed the reconcile-loop thread, so
     * collection, reaping and run advancement stopped for every task on the instance. 2026-07-27.
     */
    public Optional<TaskResult> read(Task task) {
        try {
            if (task.workspacePath == null) {
                return Optional.empty();
            }
            Path resultJson = Paths.get(task.workspacePath, "result", "result.json");
            if (!Files.exists(resultJson)) {
                return Optional.empty();
            }
            JsonObject node = GSON.fromJson(Files.readString(resultJson), JsonObject.class);
            if (node == null) {
                return Optional.empty();
            }
            return Optional.of(new TaskResult(ReportedState.from(string(node, "state")), string(node, "summary"),
                    string(node, "question"), strings(node, "secrets"), string(node, "verdict"),
                    childRuns(node)));
        } catch (IOException e) {
            return Optional.empty();
        } catch (JsonSyntaxException e) {
            // A half-written file — the agent is mid-write. Not an error; look again next tick.
            return Optional.empty();
        }
    }

    private static String string(JsonObject node, String field) {
        JsonElement value = node.get(field);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    /**
     * A string array field, tolerating the shapes an agent actually writes: a proper array, or a
     * single bare string when it only needed one. A non-array, non-string value is ignored rather
     * than thrown on — a malformed hint must not turn a legitimate pause into an unreadable result.
     */
    private static List<String> strings(JsonObject node, String field) {
        JsonElement value = node.get(field);
        if (value == null || value.isJsonNull()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        if (value.isJsonArray()) {
            for (JsonElement element : value.getAsJsonArray()) {
                if (element != null && element.isJsonPrimitive()) {
                    out.add(element.getAsString());
                }
            }
        } else if (value.isJsonPrimitive()) {
            out.add(value.getAsString());
        }
        return out;
    }

    /**
     * The {@code runs} array: workflow runs the agent asked the engine to start.
     *
     * <p>Skipped rather than thrown on, entry by entry, for the same reason {@link #strings} is
     * lenient: this field is written by a model, and one malformed entry must not make an otherwise
     * good result unreadable — that would turn a finished task into a rotten one. An entry with no
     * workflow name is not a request for anything, so there is nothing to lose by dropping it.
     */
    private static List<ChildRunRequest> childRuns(JsonObject node) {
        JsonElement value = node.get("runs");
        if (value == null || !value.isJsonArray()) {
            return List.of();
        }
        List<ChildRunRequest> out = new ArrayList<>();
        for (JsonElement element : value.getAsJsonArray()) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            String workflow = string(entry, "workflow");
            if (workflow == null || workflow.isBlank()) {
                continue;
            }
            out.add(new ChildRunRequest(workflow, parameters(entry.get("parameters"))));
        }
        return out;
    }

    /** A child run's parameter values. Non-scalar values are dropped: a run parameter is text. */
    private static Map<String, String> parameters(JsonElement value) {
        Map<String, String> out = new LinkedHashMap<>();
        if (value == null || !value.isJsonObject()) {
            return out;
        }
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
            if (entry.getValue() != null && entry.getValue().isJsonPrimitive()) {
                out.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        return out;
    }

    /** The result/ files (name + size + text content, or a binary flag) for preview. */
    public List<ResultFile> files(Task task) {
        List<ResultFile> out = new ArrayList<>();
        if (task.workspacePath == null) {
            return out;
        }
        Path resultDir = Paths.get(task.workspacePath, "result");
        if (!Files.isDirectory(resultDir)) {
            return out;
        }
        try (Stream<Path> walk = Files.walk(resultDir)) {
            for (Path path : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                out.add(preview(resultDir, path));
            }
        } catch (IOException e) {
            // a workspace that vanished under us — the caller renders what it got
        }
        return out;
    }

    private static ResultFile preview(Path resultDir, Path path) {
        String name = resultDir.relativize(path).toString().replace('\\', '/');
        long size = 0L;
        boolean binary = false;
        String content = "";
        try {
            size = Files.size(path);
            if (size > INLINE_PREVIEW_LIMIT_BYTES) {
                binary = true;   // too large to inline — download instead
            } else {
                byte[] bytes = Files.readAllBytes(path);
                if (looksBinary(bytes)) {
                    binary = true;
                } else {
                    content = new String(bytes, StandardCharsets.UTF_8);
                }
            }
        } catch (Exception e) {
            binary = true;
        }
        return new ResultFile(name, size, binary, content);
    }

    private static boolean looksBinary(byte[] bytes) {
        int limit = Math.min(bytes.length, BINARY_SNIFF_BYTES);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) {
                return true;   // a NUL byte in the head ⇒ not text
            }
        }
        return false;
    }

    /**
     * Token usage, summed from the task's own record of it. Robust to an early container reap.
     *
     * <p>Three sources, in order of how much they can be trusted. Core's proxy ledger is the same
     * record for every tool, read off the wire. Failing that the tool's own transcript is parsed as
     * JSONL, one record per line — scraping it with a regex over the whole file counted every
     * assistant message as many times as the tool wrote it, and Claude Code repeats each one, so a
     * 19-message run reported 45 turns and 2.2x its real tokens. Records carrying a message id are
     * therefore counted once per id. The whole-file regex survives only as the last fallback, for a
     * tool writing some other shape.
     */
    public TokenUsage usage(Task task) {
        TokenUsage proxied = usageFromLedger(task);
        if (proxied != null) {
            return proxied;   // read off the wire by core's proxy: the same record for every tool
        }
        String transcript = transcript(task).orElse(null);
        if (transcript == null) {
            return null;
        }
        TokenUsage parsed = usageFromJsonl(transcript);
        if (parsed != null) {
            return parsed;
        }
        long input = sumMatches(transcript, "\"(?:input|prompt)_tokens\"\\s*:\\s*(\\d+)");
        long output = sumMatches(transcript, "\"(?:output|completion)_tokens\"\\s*:\\s*(\\d+)");
        if (input == 0 && output == 0) {
            return null;   // an agent that reported none — the UI shows a dash
        }
        return TokenUsage.of(new TokenUsage.Counts(input, 0L, 0L, output), countMatches(transcript, "\"usage\""));
    }

    /**
     * The sum of {@code result/logs/usage.jsonl}, which core's proxy writes one line per model
     * call, or null when the task was not proxied. A line with no token fields is a call that
     * billed nothing.
     */
    private static TokenUsage usageFromLedger(Task task) {
        if (task.workspacePath == null) {
            return null;
        }
        Path ledger = Paths.get(task.workspacePath, "result", "logs", "usage.jsonl");
        if (!Files.isRegularFile(ledger)) {
            return null;
        }
        long input = 0L;
        long cacheWrite = 0L;
        long cacheRead = 0L;
        long output = 0L;
        long calls = 0L;
        try {
            for (String line : Files.readAllLines(ledger, StandardCharsets.UTF_8)) {
                JsonObject record = asObject(line);
                if (record == null || !record.has("output_tokens")) {
                    continue;
                }
                input += longAt(record, "input_tokens");
                cacheWrite += longAt(record, "cache_creation_input_tokens");
                cacheRead += longAt(record, "cache_read_input_tokens");
                output += longAt(record, "output_tokens");
                calls++;
            }
        } catch (IOException e) {
            return null;
        }
        return calls == 0 ? null : TokenUsage.of(new TokenUsage.Counts(input, cacheWrite, cacheRead, output), calls);
    }

    /** Sums each distinct usage record, or null when the transcript is not JSONL we understand. */
    private static TokenUsage usageFromJsonl(String transcript) {
        long input = 0L;
        long cacheWrite = 0L;
        long cacheRead = 0L;
        long output = 0L;
        long turns = 0L;
        Set<String> seen = new HashSet<>();
        for (String line : transcript.split("\\R")) {
            JsonObject record = asObject(line);
            if (record == null) {
                continue;
            }
            JsonObject message = record.has("message") && record.get("message").isJsonObject()
                    ? record.getAsJsonObject("message") : record;
            if (!message.has("usage") || !message.get("usage").isJsonObject()) {
                continue;
            }
            // The same message is written more than once by some tools; its id is what makes it one.
            String id = message.has("id") && message.get("id").isJsonPrimitive()
                    ? message.get("id").getAsString() : null;
            if (id != null && !seen.add(id)) {
                continue;
            }
            JsonObject usage = message.getAsJsonObject("usage");
            input += longAt(usage, "input_tokens", "prompt_tokens");
            output += longAt(usage, "output_tokens", "completion_tokens");
            cacheWrite += longAt(usage, "cache_creation_input_tokens", "cache_write_tokens");
            cacheRead += longAt(usage, "cache_read_input_tokens", "cached_tokens");
            turns++;
        }
        if (turns == 0 || (input == 0 && output == 0 && cacheWrite == 0 && cacheRead == 0)) {
            return null;
        }
        return TokenUsage.of(new TokenUsage.Counts(input, cacheWrite, cacheRead, output), turns);
    }

    /** One JSONL line as an object; null for a blank or stray non-JSON line, which is not fatal. */
    private static JsonObject asObject(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        try {
            JsonElement parsed = GSON.fromJson(line, JsonElement.class);
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (JsonSyntaxException e) {
            return null;
        }
    }

    /** The first of these keys the object actually carries, as a long; 0 when it carries none. */
    private static long longAt(JsonObject usage, String... keys) {
        for (String key : keys) {
            if (usage.has(key) && usage.get(key).isJsonPrimitive()) {
                try {
                    return usage.get(key).getAsLong();
                } catch (NumberFormatException e) {
                    return 0L;
                }
            }
        }
        return 0L;
    }

    /**
     * Core's proxy transcript, {@code result/logs/llm.jsonl} — present only when
     * {@code FORDISM_TRANSCRIPT} was on for the task. One format for every tool, which is what the
     * tool's own transcript can never be.
     */
    public Optional<String> proxyTranscript(Task task) {
        if (!hasProxyTranscript(task)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(proxyTranscriptPath(task)));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Whether a proxy transcript exists, without reading it — asked for every task on a run page. */
    public boolean hasProxyTranscript(Task task) {
        if (task.workspacePath == null) {
            return false;
        }
        try {
            Path file = proxyTranscriptPath(task);
            return Files.isRegularFile(file) && Files.size(file) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private static Path proxyTranscriptPath(Task task) {
        return Paths.get(task.workspacePath, "result", "logs", "llm.jsonl");
    }

    private static long sumMatches(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        long sum = 0L;
        while (matcher.find()) {
            sum += Long.parseLong(matcher.group(1));
        }
        return sum;
    }

    private static long countMatches(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        long count = 0L;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /**
     * The session transcript. The agent tool writes it to the host-persisted store (Claude Code →
     * {@code .claude/…jsonl}, Qwen Code → {@code .qwen/…jsonl}) — read the largest such file so it
     * survives even when the container is reaped mid-run. Falls back to result/logs/transcript.jsonl.
     */
    public Optional<String> transcript(Task task) {
        try {
            if (task.workspacePath == null) {
                return Optional.empty();
            }
            Path logsCopy = Paths.get(task.workspacePath, "result", "logs", "transcript.jsonl");
            if (Files.exists(logsCopy) && Files.size(logsCopy) > 0) {
                return Optional.of(Files.readString(logsCopy));
            }
            Path session = largestJsonl(Paths.get(task.workspacePath));
            return session == null ? Optional.empty() : Optional.of(Files.readString(session));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Path largestJsonl(Path workspace) {
        Path best = null;
        long bestSize = -1L;
        for (String dir : new String[]{".claude", ".qwen"}) {
            Path root = workspace.resolve(dir);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path path : (Iterable<Path>) walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".jsonl"))::iterator) {
                    long size = Files.size(path);
                    if (size > bestSize) {
                        bestSize = size;
                        best = path;
                    }
                }
            } catch (IOException e) {
                // an unreadable session store just means no transcript from it
            }
        }
        return best;
    }
}
