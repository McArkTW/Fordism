package tw.mcark.tony.fordism.model.workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What a failing gate does to the step it sends the work back to.
 *
 * <p>{@link #RETRY} plants a new task over a fresh workspace, staged with the retry step's own last
 * result. The agent starts a new session: it reads what it wrote last time as an input, but it does
 * not remember writing it, and it never sees what the gate objected to.
 *
 * <p>{@link #RESUME} re-arms the task that is already there in the CLI's resume mode. The workspace
 * and the session are still on the host mount — the same reason answering a question continues a
 * conversation instead of restarting it — so the agent picks up where it left off and is told what
 * the gate said. That is the difference: rework as correction, not as a second first attempt.
 *
 * <p>{@code RETRY} is the default because it is what {@code onFail} did before this key existed.
 */
public enum ReworkMode {
    RETRY("retry"),
    RESUME("resume");

    private final String wireName;

    ReworkMode(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    /**
     * The mode a workflow declared. Absent means {@link #RETRY}; anything unrecognised throws,
     * because a misspelled mode silently meaning "retry" is how a workflow ends up doing the one
     * thing its author wrote the key to avoid.
     */
    public static ReworkMode from(String token) {
        if (token == null || token.isBlank()) {
            return RETRY;
        }
        String normalized = token.trim().toLowerCase(Locale.ROOT);
        for (ReworkMode mode : values()) {
            if (mode.wireName.equals(normalized)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("unknown onFail mode \"" + token + "\" — one of " + names());
    }

    /** The valid names, for the error a bad workflow gets at parse time. */
    public static List<String> names() {
        List<String> out = new ArrayList<>();
        for (ReworkMode mode : values()) {
            out.add(mode.wireName);
        }
        return out;
    }
}
