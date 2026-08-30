package tw.mcark.tony.fordism.workspace;

import com.google.gson.annotations.SerializedName;

/**
 * What a task's session cost, summed from its transcript.
 *
 * <p>The snake_case wire names are the provider's, mirrored so the UI reads the same field name it
 * would see in a raw transcript.
 */
public record TokenUsage(@SerializedName("input_tokens") long inputTokens,
                         @SerializedName("output_tokens") long outputTokens,
                         long total, long turns) {}
