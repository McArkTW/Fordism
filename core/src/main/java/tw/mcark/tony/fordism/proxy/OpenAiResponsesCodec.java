package tw.mcark.tony.fordism.proxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * OpenAI Responses: {@code instructions}, {@code tools}, {@code input} — which is either the history as an
 * array or a single user string.
 */
final class OpenAiResponsesCodec implements FormatCodec {

    @Override
    public ConversationParts read(JsonObject request, String path) {
        JsonElement input = request.get("input");
        JsonArray messages;
        if (input != null && input.isJsonPrimitive()) {
            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            user.add("content", input);
            messages = new JsonArray();
            messages.add(user);
        } else {
            messages = ConversationParts.array(input);
        }
        return ConversationParts.of(request.get("instructions"), request.get("tools"), messages, request, path);
    }

    @Override
    public ReplyAssembler assembler() {
        return new ReplyAssembler.OpenAiResponses();
    }
}
