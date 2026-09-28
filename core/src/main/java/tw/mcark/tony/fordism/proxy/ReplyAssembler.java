package tw.mcark.tony.fordism.proxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Rebuilds one model reply from the events it arrived as, into the shape the same format returns when
 * not streaming — an Anthropic message, an OpenAI completion, a Responses object, a Converse output, a
 * Gemini candidate. A non-streamed reply is already that shape and is kept as it is.
 *
 * <p>Fed the same unwrapped events as the usage reader, after the tool has its bytes. Anything it does
 * not recognise is ignored: a malformed event loses detail from the transcript, never the reply.
 */
interface ReplyAssembler extends Consumer<JsonObject> {

    Optional<JsonObject> reply();

    private static String string(JsonObject o, String key) {
        JsonElement v = o == null ? null : o.get(key);
        return v != null && v.isJsonPrimitive() ? v.getAsString() : null;
    }

    private static JsonObject object(JsonObject o, String key) {
        JsonElement v = o == null ? null : o.get(key);
        return v != null && v.isJsonObject() ? v.getAsJsonObject() : null;
    }

    private static int index(JsonObject o, String key) {
        JsonElement v = o == null ? null : o.get(key);
        return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() ? v.getAsInt() : 0;
    }

    private static void append(JsonObject target, String key, String more) {
        if (more == null) {
            return;
        }
        String now = string(target, key);
        target.addProperty(key, now == null ? more : now + more);
    }

    /** A list that grows to whatever index an event names. */
    private static JsonObject at(List<JsonObject> list, int i) {
        while (list.size() <= i) {
            list.add(new JsonObject());
        }
        return list.get(i);
    }

    /** Streamed tool input arrives as JSON text in pieces; parse it once whole, or keep the text. */
    private static JsonElement parsedOrText(String text) {
        if (text == null) {
            return new JsonObject();
        }
        try {
            return JsonParser.parseString(text.isBlank() ? "{}" : text);
        } catch (RuntimeException e) {
            return new com.google.gson.JsonPrimitive(text);
        }
    }

    /** Anthropic Messages, and Bedrock Invoke once its base64 chunks are unwrapped. */
    final class Anthropic implements ReplyAssembler {
        private JsonObject message;
        private final List<JsonObject> blocks = new ArrayList<>();
        private final List<StringBuilder> partialJson = new ArrayList<>();

        @Override
        public void accept(JsonObject e) {
            String type = string(e, "type");
            if (type == null) {
                return;
            }
            switch (type) {
                case "message" -> message = e.deepCopy();
                case "message_start" -> {
                    JsonObject m = object(e, "message");
                    if (m != null) {
                        message = m.deepCopy();
                    }
                }
                case "content_block_start" -> {
                    int i = index(e, "index");
                    JsonObject block = object(e, "content_block");
                    while (blocks.size() <= i) {
                        blocks.add(new JsonObject());
                        partialJson.add(new StringBuilder());
                    }
                    blocks.set(i, block == null ? new JsonObject() : block.deepCopy());
                }
                case "content_block_delta" -> {
                    int i = index(e, "index");
                    JsonObject delta = object(e, "delta");
                    if (delta == null) {
                        return;
                    }
                    while (blocks.size() <= i) {
                        blocks.add(new JsonObject());
                        partialJson.add(new StringBuilder());
                    }
                    JsonObject block = blocks.get(i);
                    switch (String.valueOf(string(delta, "type"))) {
                        case "text_delta" -> append(block, "text", string(delta, "text"));
                        case "thinking_delta" -> append(block, "thinking", string(delta, "thinking"));
                        case "signature_delta" -> block.addProperty("signature", string(delta, "signature"));
                        case "input_json_delta" -> partialJson.get(i).append(String.valueOf(string(delta, "partial_json")));
                        default -> { }
                    }
                }
                case "content_block_stop" -> {
                    int i = index(e, "index");
                    if (i < blocks.size() && !partialJson.get(i).isEmpty()) {
                        blocks.get(i).add("input", parsedOrText(partialJson.get(i).toString()));
                    }
                }
                case "message_delta" -> {
                    if (message == null) {
                        message = new JsonObject();
                    }
                    JsonObject delta = object(e, "delta");
                    if (delta != null) {
                        delta.entrySet().forEach(entry -> message.add(entry.getKey(), entry.getValue()));
                    }
                    JsonObject usage = object(e, "usage");
                    if (usage != null) {
                        JsonObject merged = object(message, "usage") == null ? new JsonObject() : object(message, "usage");
                        usage.entrySet().forEach(entry -> merged.add(entry.getKey(), entry.getValue()));
                        message.add("usage", merged);
                    }
                }
                default -> { }
            }
        }

        @Override
        public Optional<JsonObject> reply() {
            if (message == null) {
                return Optional.empty();
            }
            if (!blocks.isEmpty()) {
                JsonArray content = new JsonArray();
                blocks.forEach(content::add);
                message.add("content", content);
            }
            return Optional.of(message);
        }
    }

    /** OpenAI Chat Completions: deltas per choice, tool calls assembled by index. */
    final class OpenAiChat implements ReplyAssembler {
        private JsonObject whole;
        private JsonObject head;
        private final List<JsonObject> messages = new ArrayList<>();
        private final List<List<JsonObject>> toolCalls = new ArrayList<>();
        private final List<String> finish = new ArrayList<>();
        private JsonObject usage;

        @Override
        public void accept(JsonObject e) {
            JsonElement choices = e.get("choices");
            if ("chat.completion".equals(string(e, "object"))) {
                whole = e.deepCopy();
                return;
            }
            if (head == null) {
                head = new JsonObject();
                for (String key : new String[] {"id", "model", "created"}) {
                    if (e.has(key)) {
                        head.add(key, e.get(key));
                    }
                }
            }
            if (object(e, "usage") != null) {
                usage = object(e, "usage");
            }
            if (choices == null || !choices.isJsonArray()) {
                return;
            }
            for (JsonElement c : choices.getAsJsonArray()) {
                if (!c.isJsonObject()) {
                    continue;
                }
                JsonObject choice = c.getAsJsonObject();
                int i = index(choice, "index");
                JsonObject message = at(messages, i);
                while (toolCalls.size() <= i) {
                    toolCalls.add(new ArrayList<>());
                    finish.add(null);
                }
                JsonObject delta = object(choice, "delta");
                if (delta != null) {
                    if (delta.has("role")) {
                        message.add("role", delta.get("role"));
                    }
                    append(message, "content", string(delta, "content"));
                    append(message, "reasoning_content", string(delta, "reasoning_content"));
                    JsonElement calls = delta.get("tool_calls");
                    if (calls != null && calls.isJsonArray()) {
                        for (JsonElement tc : calls.getAsJsonArray()) {
                            if (!tc.isJsonObject()) {
                                continue;
                            }
                            JsonObject call = at(toolCalls.get(i), index(tc.getAsJsonObject(), "index"));
                            JsonObject part = tc.getAsJsonObject();
                            if (part.has("id")) {
                                call.add("id", part.get("id"));
                            }
                            if (part.has("type")) {
                                call.add("type", part.get("type"));
                            }
                            JsonObject fn = object(part, "function");
                            if (fn != null) {
                                JsonObject target = object(call, "function") == null ? new JsonObject() : object(call, "function");
                                append(target, "name", string(fn, "name"));
                                append(target, "arguments", string(fn, "arguments"));
                                call.add("function", target);
                            }
                        }
                    }
                }
                if (string(choice, "finish_reason") != null) {
                    finish.set(i, string(choice, "finish_reason"));
                }
            }
        }

        @Override
        public Optional<JsonObject> reply() {
            if (whole != null) {
                return Optional.of(whole);
            }
            if (head == null) {
                return Optional.empty();
            }
            JsonObject out = head.deepCopy();
            out.addProperty("object", "chat.completion");
            JsonArray choices = new JsonArray();
            for (int i = 0; i < messages.size(); i++) {
                JsonObject message = messages.get(i);
                if (!toolCalls.get(i).isEmpty()) {
                    JsonArray calls = new JsonArray();
                    toolCalls.get(i).forEach(calls::add);
                    message.add("tool_calls", calls);
                }
                JsonObject choice = new JsonObject();
                choice.addProperty("index", i);
                choice.add("message", message);
                choice.addProperty("finish_reason", finish.get(i));
                choices.add(choice);
            }
            out.add("choices", choices);
            if (usage != null) {
                out.add("usage", usage);
            }
            return Optional.of(out);
        }
    }

    /** OpenAI Responses: the completed event carries the whole response object. */
    final class OpenAiResponses implements ReplyAssembler {
        private JsonObject response;

        @Override
        public void accept(JsonObject e) {
            if ("response".equals(string(e, "object"))) {
                response = e.deepCopy();
                return;
            }
            String type = string(e, "type");
            JsonObject r = object(e, "response");
            if (r != null && type != null && (type.equals("response.completed") || type.equals("response.incomplete")
                    || type.equals("response.failed") || response == null)) {
                response = r.deepCopy();
            }
        }

        @Override
        public Optional<JsonObject> reply() {
            return Optional.ofNullable(response);
        }
    }

    /** Bedrock Converse: blocks by contentBlockIndex; tool input and reasoning arrive in pieces. */
    final class Converse implements ReplyAssembler {
        private JsonObject whole;
        private String role;
        private String stopReason;
        private JsonObject usage;
        private final List<JsonObject> blocks = new ArrayList<>();
        private final List<StringBuilder> toolInput = new ArrayList<>();

        @Override
        public void accept(JsonObject e) {
            if (e.has("output") && object(e, "output") != null) {
                whole = e.deepCopy();
                return;
            }
            if (e.has("role") && e.size() <= 2) {
                role = string(e, "role");
            }
            if (e.has("stopReason")) {
                stopReason = string(e, "stopReason");
            }
            if (object(e, "usage") != null) {
                usage = object(e, "usage");
            }
            if (!e.has("contentBlockIndex")) {
                return;
            }
            int i = index(e, "contentBlockIndex");
            while (blocks.size() <= i) {
                blocks.add(new JsonObject());
                toolInput.add(new StringBuilder());
            }
            JsonObject block = blocks.get(i);
            JsonObject start = object(e, "start");
            JsonObject toolStart = object(start, "toolUse");
            if (toolStart != null) {
                block.add("toolUse", toolStart.deepCopy());
            }
            JsonObject delta = object(e, "delta");
            if (delta != null) {
                append(block, "text", string(delta, "text"));
                JsonObject tool = object(delta, "toolUse");
                if (tool != null) {
                    toolInput.get(i).append(String.valueOf(string(tool, "input")));
                }
                JsonObject reasoning = object(delta, "reasoningContent");
                if (reasoning != null) {
                    JsonObject text = object(block, "reasoningContent") == null ? new JsonObject() : object(block, "reasoningContent");
                    append(text, "text", string(reasoning, "text"));
                    if (reasoning.has("signature")) {
                        text.add("signature", reasoning.get("signature"));
                    }
                    block.add("reasoningContent", text);
                }
            }
        }

        @Override
        public Optional<JsonObject> reply() {
            if (whole != null) {
                return Optional.of(whole);
            }
            if (blocks.isEmpty() && stopReason == null && usage == null) {
                return Optional.empty();
            }
            JsonArray content = new JsonArray();
            for (int i = 0; i < blocks.size(); i++) {
                JsonObject block = blocks.get(i);
                if (!toolInput.get(i).isEmpty() && object(block, "toolUse") != null) {
                    object(block, "toolUse").add("input", parsedOrText(toolInput.get(i).toString()));
                }
                content.add(block);
            }
            JsonObject message = new JsonObject();
            message.addProperty("role", role == null ? "assistant" : role);
            message.add("content", content);
            JsonObject output = new JsonObject();
            output.add("message", message);
            JsonObject out = new JsonObject();
            out.add("output", output);
            if (stopReason != null) {
                out.addProperty("stopReason", stopReason);
            }
            if (usage != null) {
                out.add("usage", usage);
            }
            return Optional.of(out);
        }
    }

    /** Gemini: every chunk adds parts to candidate 0; adjacent text parts of the same kind are joined. */
    final class Gemini implements ReplyAssembler {
        private final JsonArray parts = new JsonArray();
        private String role = "model";
        private String finishReason;
        private JsonObject usage;
        private String modelVersion;
        private boolean seen;

        @Override
        public void accept(JsonObject e) {
            if (object(e, "usageMetadata") != null) {
                usage = object(e, "usageMetadata");
            }
            if (e.has("modelVersion")) {
                modelVersion = string(e, "modelVersion");
            }
            JsonElement candidates = e.get("candidates");
            if (candidates == null || !candidates.isJsonArray() || candidates.getAsJsonArray().isEmpty()) {
                return;
            }
            seen = true;
            JsonObject candidate = candidates.getAsJsonArray().get(0).getAsJsonObject();
            if (string(candidate, "finishReason") != null) {
                finishReason = string(candidate, "finishReason");
            }
            JsonObject content = object(candidate, "content");
            if (content == null) {
                return;
            }
            if (string(content, "role") != null) {
                role = string(content, "role");
            }
            JsonElement more = content.get("parts");
            if (more == null || !more.isJsonArray()) {
                return;
            }
            for (JsonElement p : more.getAsJsonArray()) {
                if (!p.isJsonObject()) {
                    continue;
                }
                JsonObject part = p.getAsJsonObject();
                JsonObject last = parts.isEmpty() ? null : parts.get(parts.size() - 1).getAsJsonObject();
                boolean joinable = last != null && part.has("text") && last.has("text") && part.size() == last.size()
                        && String.valueOf(part.get("thought")).equals(String.valueOf(last.get("thought")));
                if (joinable) {
                    append(last, "text", string(part, "text"));
                } else {
                    parts.add(part.deepCopy());
                }
            }
        }

        @Override
        public Optional<JsonObject> reply() {
            if (!seen && usage == null) {
                return Optional.empty();
            }
            JsonObject content = new JsonObject();
            content.addProperty("role", role);
            content.add("parts", parts);
            JsonObject candidate = new JsonObject();
            candidate.add("content", content);
            if (finishReason != null) {
                candidate.addProperty("finishReason", finishReason);
            }
            JsonArray candidates = new JsonArray();
            candidates.add(candidate);
            JsonObject out = new JsonObject();
            out.add("candidates", candidates);
            if (usage != null) {
                out.add("usageMetadata", usage);
            }
            if (modelVersion != null) {
                out.addProperty("modelVersion", modelVersion);
            }
            return Optional.of(out);
        }
    }
}
