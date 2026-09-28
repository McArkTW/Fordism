package tw.mcark.tony.fordism.proxy;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import tw.mcark.tony.fordism.model.task.Task;
import tw.mcark.tony.fordism.workspace.TokenUsage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Optional;
import org.tinylog.Logger;

/**
 * Appends one line per proxied model call to the task's {@code result/logs/usage.jsonl}.
 *
 * <p>Append-only, one call per line, never rewritten: a container killed mid-run still leaves every
 * call it completed counted, and a retried task keeps its failed attempt's lines — that attempt was
 * billed too. Every call gets a line, including one that returned no usage (an error, a model
 * listing), so the file is also the record of what the tool actually asked the provider.
 *
 * <p>The request's query string is never written: Gemini can carry its key there.
 */
final class UsageLedger {
    static final String FILE = "usage.jsonl";
    private static final Gson GSON = new Gson();

    private UsageLedger() {
    }

    static Path path(Task task) {
        return Paths.get(task.workspacePath, "result", "logs", FILE);
    }

    static void append(Task task, String method, String path, int status, long millis, String format,
            Optional<TokenUsage> usage, boolean complete) {
        if (task.workspacePath == null || task.workspacePath.isBlank()) {
            return;
        }
        JsonObject line = new JsonObject();
        line.addProperty("at", Instant.now().toString());
        line.addProperty("attempt", task.attempt);
        line.addProperty("method", method);
        line.addProperty("path", path);
        line.addProperty("status", status);
        line.addProperty("ms", millis);
        if (format != null) {
            line.addProperty("format", format);
        }
        if (!complete) {
            line.addProperty("complete", false);   // the stream was cut; any count is a floor
        }
        usage.ifPresent(u -> {
            line.addProperty("input_tokens", u.inputTokens());
            line.addProperty("cache_creation_input_tokens", u.cacheWriteTokens());
            line.addProperty("cache_read_input_tokens", u.cacheReadTokens());
            line.addProperty("output_tokens", u.outputTokens());
        });
        Path file = path(task);
        synchronized (UsageLedger.class) {
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, GSON.toJson(line) + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                Logger.warn("usage ledger for task {} not written: {}", task.id, e.toString());
            }
        }
    }
}
