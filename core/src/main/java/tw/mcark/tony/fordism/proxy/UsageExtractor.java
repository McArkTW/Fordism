package tw.mcark.tony.fordism.proxy;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import tw.mcark.tony.fordism.workspace.TokenUsage;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * Reads the token counts out of one response's events, for one wire format.
 *
 * <p>Every format ends in the same {@link TokenUsage}: the three disjoint input classes Anthropic
 * bills — uncached input, cache write, cache read — plus output. Formats that fold cached tokens
 * into their input figure (OpenAI, Gemini) are split apart here, so a number means the same thing
 * whichever tool produced it.
 *
 * <p>The format is chosen from the request path, not the Agent Profile: the path is what the tool
 * actually called, and one profile can be reached through two of these (claude-code sends Bedrock's
 * native Invoke API to a profile whose other tools use its {@code /anthropic} endpoint).
 */
interface UsageExtractor {

    void accept(JsonObject event);

    Optional<TokenUsage> usage();

    String format();

    /** The extractor for this request path, or empty for a call that bills no tokens. */
    static Optional<UsageExtractor> forPath(String rawPath) {
        String path = decode(rawPath).toLowerCase(Locale.ROOT);
        if (path.contains("/chat/completions")) {
            return Optional.of(new OpenAiChat());
        }
        if (path.endsWith("/responses")) {
            return Optional.of(new OpenAiResponses());
        }
        if (path.contains(":generatecontent") || path.contains(":streamgeneratecontent")) {
            return Optional.of(new Gemini());
        }
        if (path.contains("/converse")) {
            return Optional.of(new BedrockConverse());
        }
        if (path.contains("/invoke")) {
            return Optional.of(new Anthropic("bedrock-invoke"));
        }
        if (path.endsWith("/messages")) {
            return Optional.of(new Anthropic("anthropic"));
        }
        return Optional.empty();
    }

    private static String decode(String path) {
        try {
            return URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return path;
        }
    }

    static long number(JsonObject object, String key) {
        if (object == null) {
            return 0L;
        }
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                ? value.getAsLong() : 0L;
    }

    static JsonObject object(JsonObject parent, String key) {
        if (parent == null) {
            return null;
        }
        JsonElement value = parent.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    /**
     * Anthropic Messages — also what Bedrock's Invoke API returns once its base64 chunks are
     * unwrapped. Streamed, the input classes arrive in {@code message_start} and output in
     * {@code message_delta}, cumulatively; taking the largest value seen per class is right for both.
     */
    final class Anthropic implements UsageExtractor {
        private final String format;
        private long input;
        private long cacheWrite;
        private long cacheRead;
        private long output;
        private boolean seen;

        Anthropic(String format) {
            this.format = format;
        }

        @Override
        public void accept(JsonObject event) {
            JsonObject message = object(event, "message");
            JsonObject usage = message != null ? object(message, "usage") : object(event, "usage");
            if (usage == null) {
                return;
            }
            seen = true;
            input = Math.max(input, number(usage, "input_tokens"));
            cacheWrite = Math.max(cacheWrite, number(usage, "cache_creation_input_tokens"));
            cacheRead = Math.max(cacheRead, number(usage, "cache_read_input_tokens"));
            output = Math.max(output, number(usage, "output_tokens"));
        }

        @Override
        public Optional<TokenUsage> usage() {
            return seen ? Optional.of(TokenUsage.of(new TokenUsage.Counts(input, cacheWrite, cacheRead, output), 1)) : Optional.empty();
        }

        @Override
        public String format() {
            return format;
        }
    }

    /**
     * OpenAI Chat Completions. {@code prompt_tokens} includes the cached ones; streamed, usage comes
     * only in a final chunk, and only when the request asked for it ({@code stream_options}).
     */
    final class OpenAiChat implements UsageExtractor {
        private long prompt;
        private long cached;
        private long completion;
        private boolean seen;

        @Override
        public void accept(JsonObject event) {
            JsonObject usage = object(event, "usage");
            if (usage == null) {
                return;
            }
            seen = true;
            prompt = number(usage, "prompt_tokens");
            completion = number(usage, "completion_tokens");
            cached = number(object(usage, "prompt_tokens_details"), "cached_tokens");
        }

        @Override
        public Optional<TokenUsage> usage() {
            return seen
                    ? Optional.of(TokenUsage.of(new TokenUsage.Counts(Math.max(0L, prompt - cached), 0L, cached, completion), 1))
                    : Optional.empty();
        }

        @Override
        public String format() {
            return "openai-chat";
        }
    }

    /** OpenAI Responses: usage on the response object, streamed inside {@code response.completed}. */
    final class OpenAiResponses implements UsageExtractor {
        private long input;
        private long cached;
        private long output;
        private boolean seen;

        @Override
        public void accept(JsonObject event) {
            JsonObject response = object(event, "response");
            JsonObject usage = object(response != null ? response : event, "usage");
            if (usage == null) {
                return;
            }
            seen = true;
            input = number(usage, "input_tokens");
            output = number(usage, "output_tokens");
            cached = number(object(usage, "input_tokens_details"), "cached_tokens");
        }

        @Override
        public Optional<TokenUsage> usage() {
            return seen
                    ? Optional.of(TokenUsage.of(new TokenUsage.Counts(Math.max(0L, input - cached), 0L, cached, output), 1))
                    : Optional.empty();
        }

        @Override
        public String format() {
            return "openai-responses";
        }
    }

    /**
     * Bedrock Converse — the reply body, or the {@code metadata} event of a stream. Its input
     * figure already excludes the cache classes, which it reports separately.
     */
    final class BedrockConverse implements UsageExtractor {
        private long input;
        private long cacheWrite;
        private long cacheRead;
        private long output;
        private boolean seen;

        @Override
        public void accept(JsonObject event) {
            JsonObject usage = object(event, "usage");
            if (usage == null || !usage.has("inputTokens")) {
                return;
            }
            seen = true;
            input = number(usage, "inputTokens");
            output = number(usage, "outputTokens");
            cacheRead = number(usage, "cacheReadInputTokens");
            cacheWrite = number(usage, "cacheWriteInputTokens");
        }

        @Override
        public Optional<TokenUsage> usage() {
            return seen ? Optional.of(TokenUsage.of(new TokenUsage.Counts(input, cacheWrite, cacheRead, output), 1)) : Optional.empty();
        }

        @Override
        public String format() {
            return "bedrock-converse";
        }
    }

    /**
     * Gemini generateContent. Every streamed chunk repeats the running totals, so the last one wins;
     * {@code promptTokenCount} includes cached content, and thinking tokens bill as output.
     */
    final class Gemini implements UsageExtractor {
        private long prompt;
        private long cached;
        private long output;
        private boolean seen;

        @Override
        public void accept(JsonObject event) {
            JsonObject usage = object(event, "usageMetadata");
            if (usage == null) {
                return;
            }
            seen = true;
            prompt = number(usage, "promptTokenCount");
            cached = number(usage, "cachedContentTokenCount");
            output = number(usage, "candidatesTokenCount") + number(usage, "thoughtsTokenCount");
        }

        @Override
        public Optional<TokenUsage> usage() {
            return seen
                    ? Optional.of(TokenUsage.of(new TokenUsage.Counts(Math.max(0L, prompt - cached), 0L, cached, output), 1))
                    : Optional.empty();
        }

        @Override
        public String format() {
            return "gemini";
        }
    }
}
