package tw.mcark.tony.fordism.proxy;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * OpenAI Chat Completions: {@code tools}, {@code messages}. There is no separate system field — the system
 * prompt is the first message, with role {@code system} or {@code developer} — so that message keys the thread.
 */
final class OpenAiChatCodec implements FormatCodec {

    @Override
    public ConversationParts read(JsonObject request, String path) {
        return ConversationParts.of(null, request.get("tools"),
                ConversationParts.array(request.get("messages")), request, path);
    }

    @Override
    public ReplyAssembler assembler() {
        return new ReplyAssembler.OpenAiChat();
    }

    @Override
    public JsonElement threadKey(ConversationParts parts) {
        if (!parts.messages.isEmpty() && parts.messages.get(0).isJsonObject()) {
            JsonElement role = parts.messages.get(0).getAsJsonObject().get("role");
            if (role != null && role.isJsonPrimitive()
                    && ("system".equals(role.getAsString()) || "developer".equals(role.getAsString()))) {
                return parts.messages.get(0);
            }
        }
        return new JsonPrimitive("");
    }
}
