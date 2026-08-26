package tw.mcark.tony.fordism.model.task;

/**
 * Task lifecycle states.
 *
 * <p>{@code ASKED} names what the agent did — wrote a question and stopped — not the condition it
 * left behind. A task whose attempts are spent or whose container died is {@code FAILED} or
 * {@code REAPED}: no answer exists that would move it. Only a task with a question waiting belongs
 * here, so its presence in the Questions inbox is a promise that answering it helps.
 *
 * <p>When renaming a constant, keep a {@code @SerializedName(alternate)} for the old spelling:
 * Gson maps an unknown enum constant to {@code null} rather than throwing, so a snapshot written
 * by an older build would restore a task whose state is null, and the first orchestrator to switch
 * on it fails the whole run.
 */
public enum TaskState {
    PENDING, RUNNING, COLLECTED, REAPED, ASKED, FAILED
}
