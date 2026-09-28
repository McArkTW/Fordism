package tw.mcark.tony.fordism.proxy;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * A message whose content is one plain text block compares equal to the same text as a string:
 * {@code [{"type":"text","text":"x"}]} is {@code "x"}.
 *
 * <p>Seen on real Bedrock runs of claude-code and dsh: the same message is sent as a string in one call and
 * as a one-block list in the next, because a cache marker can only hang on the list form and the marker
 * moves each call. Without this, their second main call stored the whole history again.
 */
final class SingleTextBlockAsString implements TranscriptFix {

    @Override
    public String name() {
        return "single-text-block-as-string";
    }

    @Override
    public JsonElement forComparison(JsonElement element) {
        if (!element.isJsonObject()) {
            return element;
        }
        JsonObject message = element.getAsJsonObject();
        JsonElement content = message.get("content");
        if (content == null || !content.isJsonArray() || content.getAsJsonArray().size() != 1) {
            return message;
        }
        JsonElement block = content.getAsJsonArray().get(0);
        if (block.isJsonObject() && block.getAsJsonObject().size() == 2
                && block.getAsJsonObject().has("type") && block.getAsJsonObject().get("type").isJsonPrimitive()
                && "text".equals(block.getAsJsonObject().get("type").getAsString())
                && block.getAsJsonObject().has("text") && block.getAsJsonObject().get("text").isJsonPrimitive()) {
            message.add("content", block.getAsJsonObject().get("text"));
        }
        return message;
    }
}
