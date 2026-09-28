package tw.mcark.tony.fordism.proxy;

import com.google.gson.JsonObject;

/** Bedrock Converse: {@code system}, {@code toolConfig}, {@code messages}; model in the path. */
final class ConverseCodec implements FormatCodec {

    @Override
    public ConversationParts read(JsonObject request, String path) {
        return ConversationParts.of(request.get("system"), request.get("toolConfig"),
                ConversationParts.array(request.get("messages")), request, path);
    }

    @Override
    public ReplyAssembler assembler() {
        return new ReplyAssembler.Converse();
    }
}
