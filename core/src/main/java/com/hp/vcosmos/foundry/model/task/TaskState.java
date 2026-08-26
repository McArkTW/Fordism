package com.hp.vcosmos.foundry.model.task;

import com.google.gson.annotations.SerializedName;

/**
 * Task lifecycle states.
 *
 * <p>{@code ASKED} names what the agent did — wrote a question and stopped — not the condition it
 * left behind. A task whose attempts are spent or whose container died is {@code FAILED} or
 * {@code REAPED}: no answer exists that would move it. Only a task with a question waiting belongs
 * here, so its presence in the Questions inbox is a promise that answering it helps.
 *
 * <p>{@code ASKED} reads the two names it has had before. Gson maps an unknown enum constant to
 * {@code null} rather than throwing, so without these a snapshot written by an older build restores
 * a task whose state is null, and the first orchestrator to switch on it fails the whole run.
 */
public enum TaskState {
    PENDING, RUNNING, COLLECTED, REAPED,
    @SerializedName(value = "ASKED", alternate = {"NEEDS_RESCUE", "NEEDS_HUMAN"}) ASKED,
    FAILED
}
