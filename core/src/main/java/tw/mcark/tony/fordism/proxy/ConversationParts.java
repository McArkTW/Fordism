package tw.mcark.tony.fordism.proxy;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * The conversation inside one model request, wherever its format keeps it: the system prompt, the tool
 * definitions, the message history, the model, and every other field as {@code params}.
 */
final class ConversationParts {
    /** Every key any format uses for the conversation itself; everything else is a param. */
    private static final Set<String> CONVERSATION_KEYS = Set.of("system", "tools", "messages", "input",
            "instructions", "contents", "systemInstruction", "toolConfig", "model");
    private static final int MAX_PARAMS_CHARS = 8192;

    final JsonElement system;
    final JsonElement tools;
    final JsonArray messages;
    final JsonObject params;
    final String model;

    private ConversationParts(JsonElement system, JsonElement tools, JsonArray messages, JsonObject params, String model) {
        this.system = system;
        this.tools = tools;
        this.messages = messages;
        this.params = params;
        this.model = model;
    }

    static ConversationParts of(JsonElement system, JsonElement tools, JsonArray messages, JsonObject request, String path) {
        String model = request.has("model") && request.get("model").isJsonPrimitive()
                ? request.get("model").getAsString() : modelFromPath(path);
        JsonObject params = new JsonObject();
        request.entrySet().forEach(entry -> {
            if (!CONVERSATION_KEYS.contains(entry.getKey())) {
                params.add(entry.getKey(), entry.getValue());
            }
        });
        boolean small = new GsonBuilder().create().toJson(params).length() <= MAX_PARAMS_CHARS;
        return new ConversationParts(system, tools, messages == null ? new JsonArray() : messages,
                small ? params : null, model);
    }

    static JsonArray array(JsonElement e) {
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    /** Bedrock and Gemini carry the model in the path, not the body. */
    static String modelFromPath(String path) {
        String p = URLDecoder.decode(path == null ? "" : path.replace("+", "%2B"), StandardCharsets.UTF_8);
        int model = p.indexOf("/model/");
        if (model >= 0) {
            String rest = p.substring(model + 7);
            int end = rest.indexOf('/');
            return end < 0 ? rest : rest.substring(0, end);
        }
        int models = p.indexOf("/models/");
        if (models >= 0) {
            String rest = p.substring(models + 8);
            int end = rest.indexOf(':');
            return end < 0 ? rest : rest.substring(0, end);
        }
        return null;
    }
}
