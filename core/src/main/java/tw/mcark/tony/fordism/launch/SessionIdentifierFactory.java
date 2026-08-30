package tw.mcark.tony.fordism.launch;

import java.util.UUID;

/** Per-task session identity: a unique id, one constant display name for every agent. */
public final class SessionIdentifierFactory {
    public static final String DISPLAY_NAME = "A fordism agent task.";

    public String newSessionId() {
        return UUID.randomUUID().toString();
    }
}
