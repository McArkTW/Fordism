package tw.mcark.tony.fordism.model.task;

/** Network policy imposed by core at container launch. */
public enum NetworkPolicy {
    NONE, FORDISM_ONLY, FULL;

    public static NetworkPolicy from(String value) {
        if (value == null) {
            return FORDISM_ONLY;
        }
        return switch (value.trim().toLowerCase()) {
            case "none" -> NONE;
            case "full" -> FULL;
            default -> FORDISM_ONLY;
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
