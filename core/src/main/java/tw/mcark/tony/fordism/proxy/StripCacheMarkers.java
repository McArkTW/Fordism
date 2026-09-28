package tw.mcark.tony.fordism.proxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Prompt-cache markers are ignored when comparing. Tools move them to the newest message on every call
 * ({@code cache_control} in Anthropic formats, a {@code cachePoint} block in Converse), so the same message
 * would otherwise look different each time and every call would look like a rewritten history.
 *
 * <p>In every tool's chain: a cache marker never changes what a message says.
 */
final class StripCacheMarkers implements TranscriptFix {

    @Override
    public String name() {
        return "strip-cache-markers";
    }

    @Override
    public JsonElement forComparison(JsonElement element) {
        strip(element);
        return element;
    }

    private static void strip(JsonElement e) {
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            o.remove("cache_control");
            o.keySet().forEach(k -> strip(o.get(k)));
        } else if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            for (int i = a.size() - 1; i >= 0; i--) {
                JsonElement item = a.get(i);
                if (item.isJsonObject() && item.getAsJsonObject().size() == 1 && item.getAsJsonObject().has("cachePoint")) {
                    a.remove(i);
                } else {
                    strip(item);
                }
            }
        }
    }
}
