package tw.mcark.tony.fordism.model.run;

/**
 * Workflow-run lifecycle states. {@code ASKED} = one of its tasks is waiting on an answer. When
 * renaming a constant, keep a {@code @SerializedName(alternate)} for the old spelling so an older
 * snapshot still restores (see {@code TaskState}).
 */
public enum WorkflowRunState {
    ACTIVE, DONE, FAILED, ASKED,
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
