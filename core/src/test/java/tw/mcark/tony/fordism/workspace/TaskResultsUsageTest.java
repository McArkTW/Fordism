package tw.mcark.tony.fordism.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import tw.mcark.tony.fordism.model.task.Task;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a run cost, read back off the transcript the agent left behind.
 *
 * <p>Both dialects are pinned here because the number is summed with a regex over the raw file, not
 * parsed from a schema: claude-code writes {@code input_tokens}/{@code output_tokens}, qwen-code
 * writes {@code prompt_tokens}/{@code completion_tokens}, and a cached-prompt field must not be
 * counted twice into the input total.
 */
class TaskResultsUsageTest {

    @TempDir
    Path directory;

    private final TaskResults results = new TaskResults();

    @Test
    void the_claude_dialect_sums_per_turn_and_counts_the_turns() throws IOException {
        Task task = taskIn("claude");
        writeTranscript(task, """
                {"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"Reading the task."}],\
                "usage":{"input_tokens":1200,"output_tokens":80}}}
                {"type":"user","message":{"role":"user","content":[{"type":"tool_result","content":"ok"}]}}
                {"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","name":"Write"}],\
                "usage":{"input_tokens":1500,"cache_read_input_tokens":900,"output_tokens":120}}}
                {"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"Done."}],\
                "usage":{"input_tokens":1700,"output_tokens":40}}}
                """);

        TokenUsage usage = results.usage(task);

        // 900 cache_read_input_tokens are deliberately absent: they are a breakdown of the input the
        // provider already reported, so adding them would double-bill every cached turn.
        assertEquals(4400L, usage.inputTokens());
        assertEquals(240L, usage.outputTokens());
        assertEquals(4640L, usage.total());
        assertEquals(3L, usage.turns(), "one turn per usage block, not per line");
    }

    @Test
    void the_qwen_dialect_is_read_from_the_session_store_when_no_logs_copy_exists() throws IOException {
        Task task = taskIn("qwen");
        Path session = Path.of(task.workspacePath, ".qwen", "chats");
        Files.createDirectories(session);
        Files.writeString(session.resolve("session.jsonl"), """
                {"role":"assistant","usage":{"prompt_tokens":600,"completion_tokens":25}}
                {"role":"assistant","usage":{"prompt_tokens":900,"completion_tokens":75}}
                """);

        TokenUsage usage = results.usage(task);

        assertEquals(1500L, usage.inputTokens());
        assertEquals(100L, usage.outputTokens());
        assertEquals(1600L, usage.total());
        assertEquals(2L, usage.turns());
    }

    @Test
    void a_task_with_no_transcript_and_one_that_reported_no_usage_both_read_as_unknown() throws IOException {
        // null, not zero — the UI shows a dash. A container reaped before it wrote anything has not
        // spent nothing; it has spent an amount nobody recorded.
        Task none = taskIn("none");
        assertNull(results.usage(none));

        Task silent = taskIn("silent");
        writeTranscript(silent, """
                {"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"hello"}]}}
                """);
        assertNull(results.usage(silent));
    }

    private Task taskIn(String name) throws IOException {
        Task task = new Task("task-" + name, "run-1", 0, "session-" + name);
        Path workspace = directory.resolve(name);
        Files.createDirectories(workspace);
        task.workspacePath = workspace.toString();
        return task;
    }

    /** The copy the agent leaves under result/logs — what the reader prefers when it is there. */
    private static void writeTranscript(Task task, String content) throws IOException {
        Path logs = Path.of(task.workspacePath, "result", "logs");
        Files.createDirectories(logs);
        Files.writeString(logs.resolve("transcript.jsonl"), content);
    }
}
