package tw.mcark.tony.fordism.proxy;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * How one wire format is read for the transcript — the Strategy for formats. Each format keeps its
 * conversation in different fields and streams its reply differently; everything after these answers
 * (diffing, fixes, blobs, redaction) is the same for all of them. Chosen by {@link FormatCodecs}.
 */
interface FormatCodec {

    /** Where this format keeps the system prompt, tools and messages. */
    ConversationParts read(JsonObject request, String path);

    /** A fresh assembler for one reply in this format. */
    ReplyAssembler assembler();

    /**
     * What groups calls into one thread: calls with the same system prompt continue the same conversation,
     * so a tool's side calls (titles, summaries) do not interleave with it.
     */
    default JsonElement threadKey(ConversationParts parts) {
        return parts.system;
    }
}
