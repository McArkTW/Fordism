package tw.mcark.tony.fordism.proxy;

import java.util.Map;

/** The codec for each wire format, keyed by the names {@link UsageExtractor#forPath} gives formats. */
final class FormatCodecs {
    private static final Map<String, FormatCodec> BY_FORMAT = Map.of(
            "anthropic", new AnthropicCodec(),
            "bedrock-invoke", new BedrockInvokeCodec(),
            "bedrock-converse", new ConverseCodec(),
            "openai-chat", new OpenAiChatCodec(),
            "openai-responses", new OpenAiResponsesCodec(),
            "gemini", new GeminiCodec());

    private FormatCodecs() {
    }

    /** Every format the usage reader recognises has a codec; a format without one is a bug, not a skip. */
    static FormatCodec forFormat(String format) {
        FormatCodec codec = BY_FORMAT.get(format);
        if (codec == null) {
            throw new IllegalArgumentException("no transcript codec for format " + format);
        }
        return codec;
    }

    static java.util.Set<String> formats() {
        return BY_FORMAT.keySet();
    }
}
