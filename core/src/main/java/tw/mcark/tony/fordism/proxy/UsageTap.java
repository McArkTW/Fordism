package tw.mcark.tony.fordism.proxy;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import tw.mcark.tony.fordism.workspace.TokenUsage;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import org.tinylog.Logger;

/**
 * Reads token usage off a response as its bytes pass by, without ever being able to affect them.
 *
 * <p>The proxy writes each chunk to the tool before handing a copy here, and nothing thrown in here
 * escapes: a response this cannot parse loses its metric, never its bytes.
 */
final class UsageTap {
    private final StreamDecoder decoder;
    private final UsageExtractor extractor;
    private final Optional<ReplyAssembler> assembler;
    private boolean broken;

    private UsageTap(StreamDecoder decoder, UsageExtractor extractor, boolean assemble) {
        this.decoder = decoder;
        this.extractor = extractor;
        this.assembler = assemble ? Optional.of(FormatCodecs.forFormat(extractor.format()).assembler()) : Optional.empty();
    }

    /** A tap for this call, or empty when the path is not one that bills tokens. */
    static Optional<UsageTap> open(String path, String contentType) {
        return open(path, contentType, false);
    }

    /** As above; {@code assemble} also rebuilds the reply, for the transcript. */
    static Optional<UsageTap> open(String path, String contentType, boolean assemble) {
        return UsageExtractor.forPath(path)
                .map(extractor -> new UsageTap(StreamDecoder.forContentType(contentType), extractor, assemble));
    }

    /** The reply rebuilt from what passed, when assembling; empty otherwise or if nothing was readable. */
    Optional<com.google.gson.JsonObject> reply() {
        try {
            return assembler.flatMap(ReplyAssembler::reply);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    String format() {
        return extractor.format();
    }

    void accept(byte[] bytes, int offset, int length) {
        if (broken) {
            return;
        }
        try {
            decoder.accept(bytes, offset, length, this::emit);
        } catch (RuntimeException e) {
            broken = true;
            Logger.warn("usage tap stopped reading a {} response: {}", extractor.format(), e.toString());
        }
    }

    Optional<TokenUsage> finish() {
        if (!broken) {
            try {
                decoder.finish(this::emit);
            } catch (RuntimeException e) {
                broken = true;
                Logger.warn("usage tap could not finish a {} response: {}", extractor.format(), e.toString());
            }
        }
        return broken ? Optional.empty() : extractor.usage();
    }

    /**
     * Bedrock's Invoke stream wraps each Anthropic event as {@code {"bytes":"<base64 JSON>"}}; unwrap
     * it so the Anthropic extractor sees the event itself.
     */
    private void emit(JsonObject event) {
        JsonElement bytes = event.get("bytes");
        if (bytes != null && bytes.isJsonPrimitive() && bytes.getAsJsonPrimitive().isString()) {
            try {
                String inner = new String(Base64.getDecoder().decode(bytes.getAsString()), StandardCharsets.UTF_8);
                StreamDecoder.emitJson(inner, this::deliver);
                return;
            } catch (IllegalArgumentException e) {
                // not base64 after all — fall through and read the object as it is
            }
        }
        deliver(event);
    }

    private void deliver(JsonObject event) {
        extractor.accept(event);
        assembler.ifPresent(a -> {
            try {
                a.accept(event);
            } catch (RuntimeException e) {
                // a reply it cannot rebuild costs the transcript detail, never the usage count
            }
        });
    }
}
