package tw.mcark.tony.fordism.model.task;

/** Network policy imposed by core at container launch. */
public enum NetworkPolicy {
    NONE, FORDISM_ONLY, FULL;

    /**
     * The policy a workflow named, defaulting to {@link #NONE} for an absent or unrecognised value.
     *
     * <p>Deny by default, and deny on a typo: the agent talks to core through the mounted
     * filesystem, never the network, so a step that says nothing about egress needs none — and a
     * step that misspells {@code full} should fail visibly for want of network rather than quietly
     * run with less than it asked for. {@code fordism-only} remains for the rare step that really
     * does need to reach core over HTTP.
     */
    public static NetworkPolicy from(String value) {
        if (value == null) {
            return NONE;
        }
        return switch (value.trim().toLowerCase()) {
            case "fordism-only" -> FORDISM_ONLY;
            case "full" -> FULL;
            default -> NONE;
        };
    }

    /**
     * The token the workflow YAML uses for this policy, and what the parsed view carries. An
     * omitted {@code network} is {@link #NONE}, so that name appears in a step's outline even
     * though nobody typed it — an operator should see the egress a step will actually get, not
     * only the egress somebody remembered to write down.
     */
    public String wireName() {
        return switch (this) {
            case NONE -> "none";
            case FULL -> "full";
            case FORDISM_ONLY -> "fordism-only";
        };
    }

    /** The docker --network value: the fordism launcher network, "none", or "bridge". */
    public String dockerNetwork(String fordismNetwork) {
        return switch (this) {
            case NONE -> "none";
            case FULL -> "bridge";
            case FORDISM_ONLY -> fordismNetwork;
        };
    }
}
