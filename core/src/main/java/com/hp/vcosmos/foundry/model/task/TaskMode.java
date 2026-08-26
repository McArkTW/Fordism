package com.hp.vcosmos.foundry.model.task;

import com.google.gson.annotations.SerializedName;

/**
 * How the agent container is started for a task.
 *
 * <p>{@link #WORK} stages a fresh workspace and starts a new session. {@link #RESUME} skips staging
 * entirely — the workspace and the CLI's session store are still on the host mount, which is the
 * whole reason answering a question continues a conversation instead of restarting it.
 *
 * <p>{@code wireName} is the token the agent entrypoint branches on, and the annotation is the same
 * token in the state snapshot: this field is persisted, and Gson maps an unrecognised constant to
 * null, so a snapshot written before this enum existed must still restore. The literal appears
 * twice because an annotation takes a constant, not a method call — keep them in step.
 */
public enum TaskMode {
    @SerializedName("work") WORK("work"),
    @SerializedName("resume") RESUME("resume");

    private final String wireName;

    TaskMode(String wireName) {
        this.wireName = wireName;
    }

    /** The token the agent's entrypoint reads out of AGENT_MODE. */
    public String wireName() {
        return wireName;
    }

    public boolean isResume() {
        return this == RESUME;
    }
}
