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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    /** Token usage, summed from the session transcript. Robust to an early container reap. */
    public TokenUsage usage(Task task) {
        String transcript = transcript(task).orElse(null);
        if (transcript == null) {
            return null;
        }
        long input = sumMatches(transcript, "\"(?:input|prompt)_tokens\"\\s*:\\s*(\\d+)");
        long output = sumMatches(transcript, "\"(?:output|completion)_tokens\"\\s*:\\s*(\\d+)");
        if (input == 0 && output == 0) {
            return null;   // an agent that reported none — the UI shows a dash
        }
        return new TokenUsage(input, output, input + output, countMatches(transcript, "\"usage\""));
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
