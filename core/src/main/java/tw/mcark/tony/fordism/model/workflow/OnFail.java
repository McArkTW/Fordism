package tw.mcark.tony.fordism.model.workflow;

/**
 * A rework gate's instructions: which step to send the work back to, how many times, and how.
 *
 * <p>This was an untyped {@code Map<String, Object>} read key by key at the one call site, with
 * {@code maxAttempts} defaulted in the orchestrator rather than at parse. A third key made that
 * shape untenable: an unknown key was already rejected by the loader, but a MISSPELLED VALUE was
 * not, and the orchestrator had no place to say so. Parsed into a record, a bad mode is a parse
 * error the editor shows while you type instead of a surprise on the run that needed it.
 */
public record OnFail(String retryStepId, int maximumAttempts, ReworkMode mode) {

    /** What {@code onFail} bounded a rework at before {@code maxAttempts} could be written down. */
    public static final int DEFAULT_MAXIMUM_ATTEMPTS = 3;
}
