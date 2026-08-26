package com.hp.vcosmos.foundry.model.task;

/** Network policy imposed by core at container launch. */
public enum NetworkPolicy {
    NONE, FOUNDRY_ONLY, FULL;

    public static NetworkPolicy from(String value) {
        if (value == null) {
            return FOUNDRY_ONLY;
        }
        return switch (value.trim().toLowerCase()) {
            case "none" -> NONE;
            case "full" -> FULL;
            default -> FOUNDRY_ONLY;
        };
    }

    /** The docker --network value: the foundry launcher network, "none", or "bridge". */
    public String dockerNetwork(String foundryNetwork) {
        return switch (this) {
            case NONE -> "none";
            case FULL -> "bridge";
            case FOUNDRY_ONLY -> foundryNetwork;
        };
    }
}
