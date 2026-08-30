package tw.mcark.tony.fordism.model.task;

/**
 * What an agent said about itself in {@code result/result.json}.
 *
 * <p>This is the agent's claim, not the engine's verdict — the engine decides the {@link TaskState}
 * from it. Anything the agent has not finished saying reads as {@link #RUNNING}: a token we do not
 * recognise, a missing field, a file caught mid-write. Treating the unknown as "still working" is
 * what makes the Reaper, not the parser, the thing that ends a task that never reports.
 */
public enum ReportedState {
    RUNNING, FINISHED, FAILED, ASKED;

    /** The wire token an agent wrote, or {@link #RUNNING} for anything unrecognised. */
    public static ReportedState from(String token) {
        if (token == null) {
            return RUNNING;
        }
        return switch (token.trim().toLowerCase()) {
            case "finished" -> FINISHED;
            case "failed" -> FAILED;
            case "asked" -> ASKED;
            default -> RUNNING;
        };
    }

    /** Whether the agent has said its last word, whatever that word was. */
    public boolean isTerminal() {
        return this != RUNNING;
    }
}
