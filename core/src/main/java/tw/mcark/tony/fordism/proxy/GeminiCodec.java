package tw.mcark.tony.fordism.proxy;

import com.google.gson.JsonObject;

/** Gemini: {@code systemInstruction}, {@code tools}, {@code contents}; model in the path. */
final class GeminiCodec implements FormatCodec {

    @Override
    public ConversationParts read(JsonObject request, String path) {
        return ConversationParts.of(request.get("systemInstruction"), request.get("tools"),
                ConversationParts.array(request.get("contents")), request, path);
    }

    @Override
    public ReplyAssembler assembler() {
        return new ReplyAssembler.Gemini();
    }
}
