package tw.mcark.tony.fordism.proxy;

import com.google.gson.JsonObject;

/** Bedrock Invoke: an Anthropic body without {@code model}, which is in the path; the stream wraps
 * Anthropic events in base64, unwrapped before the assembler sees them. */
final class BedrockInvokeCodec implements FormatCodec {

    @Override
    public ConversationParts read(JsonObject request, String path) {
        return ConversationParts.of(request.get("system"), request.get("tools"),
                ConversationParts.array(request.get("messages")), request, path);
    }

    @Override
    public ReplyAssembler assembler() {
        return new ReplyAssembler.Anthropic();
    }
}
