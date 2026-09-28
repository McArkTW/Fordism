package tw.mcark.tony.fordism.proxy;

import com.google.gson.JsonElement;

/**
 * One step in a tool's chain of transcript fixes — the Chain of Responsibility for tools.
 *
 * <p>A fix changes how the transcript <em>recognises</em> content, never what it stores: it rewrites a
 * copy of a message, system prompt or tool list into the form used to tell whether this call repeats the
 * previous one. So a wrong fix can make the transcript store more than it needs to, but cannot change a
 * byte of what it records.
 *
 * <p>A fix exists because a real trace needed it, and is registered in {@link ToolFixes} only for the
 * tools that trace came from.
 */
interface TranscriptFix {

    /** The name written into each transcript line this fix applied to. */
    String name();

    /** The form this element is compared in. Receives and returns a copy it may change freely. */
    JsonElement forComparison(JsonElement element);

    /** Runs a chain over a copy of the element. */
    static JsonElement apply(java.util.List<TranscriptFix> chain, JsonElement element) {
        if (element == null) {
            return null;
        }
        JsonElement form = element.deepCopy();
        for (TranscriptFix fix : chain) {
            form = fix.forComparison(form);
        }
        return form;
    }
}
