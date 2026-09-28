package tw.mcark.tony.fordism.workspace;

import com.google.gson.annotations.SerializedName;

/**
 * What a task's session cost, summed from its transcript.
 *
 * <p>The snake_case wire names are the provider's, mirrored so the UI reads the same field name it
 * would see in a raw transcript.
 *
 * <p>The three input classes are disjoint and bill at different rates, so they are kept apart
 * rather than added into one number: {@code inputTokens} is only what followed the last cache
 * breakpoint, while the cached context is reported as {@code cacheWriteTokens} (written this turn)
 * and {@code cacheReadTokens} (served from an earlier turn's write). Total input is the sum of the
 * three. On a long agent run the cache classes are nearly all of it — an uncached-only figure
 * understates the session by orders of magnitude.
 */
public record TokenUsage(@SerializedName("input_tokens") long inputTokens,
                         @SerializedName("cache_creation_input_tokens") long cacheWriteTokens,
                         @SerializedName("cache_read_input_tokens") long cacheReadTokens,
                         @SerializedName("output_tokens") long outputTokens,
                         long total, long turns) {

    /**
     * The four billed classes of one call or one session, before they are summed. Named at the call
     * site because four bare longs in a row are four chances to transpose two of them, and the
     * mistake would be invisible: the total would still be right.
     */
    public record Counts(long input, long cacheWrite, long cacheRead, long output) {
        long total() {
            return input + cacheWrite + cacheRead + output;
        }
    }

    /** Sums the four classes into {@code total}, so no caller can disagree about what total means. */
    public static TokenUsage of(Counts counts, long turns) {
        return new TokenUsage(counts.input(), counts.cacheWrite(), counts.cacheRead(), counts.output(),
                counts.total(), turns);
    }
}
