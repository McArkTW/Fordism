package tw.mcark.tony.fordism.proxy;

import com.google.gson.JsonObject;

/** Anthropic Messages: {@code system}, {@code tools}, {@code messages}. */
final class AnthropicCodec implements FormatCodec {

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
