package com.hp.vcosmos.foundry.model.run;

import com.google.gson.annotations.SerializedName;

/**
 * Workflow-run lifecycle states. {@code ASKED} = one of its tasks is waiting on an answer, and
 * reads its two former names so an older snapshot still restores (see {@code TaskState}).
 */
public enum WorkflowRunState {
    ACTIVE, WAITING_ON_CHILD, DONE, FAILED,
    @SerializedName(value = "ASKED", alternate = {"NEEDS_RESCUE", "NEEDS_HUMAN"}) ASKED,
    /** A human stopped it. Deliberately not FAILED — the work did not fail, someone ended it. */
    ABANDONED;

    /**
     * Whether the run is over. A terminal run is never orchestrated again, and no task of it may
     * still be live — {@code OrphanCuller} enforces that second half.
     *
     * <p>ASKED is not terminal: it is parked, and answering resumes it.
     */
    public boolean isTerminal() {
        return this == DONE || this == FAILED || this == ABANDONED;
    }
}
