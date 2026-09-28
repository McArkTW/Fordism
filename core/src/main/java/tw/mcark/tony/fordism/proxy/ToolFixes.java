package tw.mcark.tony.fordism.proxy;

import java.util.List;
import java.util.Map;

/**
 * Each tool's chain of transcript fixes — a registry keyed by the tool's wire name, falling back to the
 * default chain for a tool with no entry of its own.
 *
 * <p>A tool gets a fix here only when a real trace of that tool needed it, and that trace is kept as a
 * test fixture ({@code core/src/test/resources/traces/<tool>/}). No fix is assumed to apply to a tool
 * nobody has seen do the thing it corrects.
 *
 * <p>To add one: capture the tool's trace, find the call the transcript mistook for a rewrite, write the
 * fix, register it for that tool below, and commit the trace with it.
 */
final class ToolFixes {

    static final List<TranscriptFix> DEFAULT = List.of(new StripCacheMarkers());

    private static final Map<String, List<TranscriptFix>> BY_TOOL = Map.of(
            // Real Bedrock smoke runs, 2026-09-17: both sent one message as a string, then as a one-block
            // list carrying a cache marker, and stored their whole history again on the second main call.
            "claude-code", List.of(new StripCacheMarkers(), new SingleTextBlockAsString()),
            "dsh", List.of(new StripCacheMarkers(), new SingleTextBlockAsString()));

    private ToolFixes() {
    }

    static List<TranscriptFix> forTool(String tool) {
        return tool == null ? DEFAULT : BY_TOOL.getOrDefault(tool, DEFAULT);
    }
}
