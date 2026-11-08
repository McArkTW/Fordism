package tw.mcark.tony.fordism.model.workflow;

/**
 * What a step may do to its run's {@code artifact/} — the product the run is building.
 *
 * <p>Never written in YAML. The loader derives it from the strategy and {@code dependsOn}, because
 * the only thing that decides it is whether another step could be running beside this one, and that
 * is a fact about the workflow's shape rather than a preference of the step's author. Two agents
 * writing the same tree at once is a merge nobody asked for; a step that might have company gets the
 * tree read-only and hands its work back through {@code result/}, and a later step merges it.
 */
public enum ArtifactAccess {
    WRITE, READ_ONLY;

    /** The token the editor outline and the agent's environment carry. */
    public String wireName() {
        return switch (this) {
            case WRITE -> "write";
            case READ_ONLY -> "read-only";
        };
    }

    /**
     * Null is {@link #WRITE}: a task restored from a snapshot written before this existed ran with
     * no artifact at all, so nothing it could do there was ever shared with a sibling.
     */
    public static boolean readOnly(ArtifactAccess access) {
        return access == READ_ONLY;
    }
}
