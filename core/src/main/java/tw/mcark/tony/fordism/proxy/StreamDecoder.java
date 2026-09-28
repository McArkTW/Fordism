package tw.mcark.tony.fordism.proxy;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Turns the bytes of a model response into the JSON objects inside them, whatever the framing.
 *
 * <p>Three framings cover every provider a tool can reach: server-sent events (Anthropic, OpenAI,
 * Gemini with {@code alt=sse}), AWS binary event-stream (Bedrock's native Invoke and Converse
 * streams), and a plain body (every non-streaming reply, and Gemini's streamed JSON array). The
 * response's own {@code Content-Type} picks one — nothing here knows which provider sent it.
 *
 * <p>Bytes arrive in whatever chunks the network delivered, so an event can be split anywhere,
 * including inside a multi-byte character. Each decoder buffers bytes, never decoded text, until a
 * whole frame is present.
 */
abstract class StreamDecoder {

    /** A frame too large to be a model event means the stream is not what the header claimed. */
    static final int MAX_FRAME_BYTES = 32 * 1024 * 1024;

    abstract void accept(byte[] bytes, int offset, int length, Consumer<JsonObject> out);

    abstract void finish(Consumer<JsonObject> out);

    static StreamDecoder forContentType(String contentType) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (type.contains("text/event-stream")) {
            return new ServerSentEvents();
        }
        if (type.contains("application/vnd.amazon.eventstream")) {
            return new AwsEventStream();
        }
        return new WholeBody();
    }

    /** Parses one JSON text; anything that is not a JSON object is not an event and is skipped. */
    static void emitJson(String text, Consumer<JsonObject> out) {
        if (text == null || text.isBlank()) {
            return;
        }
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (parsed.isJsonObject()) {
                out.accept(parsed.getAsJsonObject());
            } else if (parsed.isJsonArray()) {
                for (JsonElement element : parsed.getAsJsonArray()) {
                    if (element.isJsonObject()) {
                        out.accept(element.getAsJsonObject());
                    }
                }
            }
        } catch (JsonSyntaxException | IllegalStateException e) {
            // A keep-alive comment or a provider's plain-text error is not a reason to stop reading.
        }
    }

    /** A growable byte buffer that can drop consumed bytes from its front. */
    static final class Buffer {
        byte[] data = new byte[8192];
        int size;

        void append(byte[] bytes, int offset, int length) {
            if (size + length > data.length) {
                data = Arrays.copyOf(data, Math.max(data.length * 2, size + length));
            }
            System.arraycopy(bytes, offset, data, size, length);
            size += length;
        }

        void drop(int count) {
            System.arraycopy(data, count, data, 0, size - count);
            size -= count;
        }
    }

    /** {@code text/event-stream}: events end at a blank line; the payload is the {@code data:} lines. */
    static final class ServerSentEvents extends StreamDecoder {
        private final Buffer buffer = new Buffer();

        @Override
        void accept(byte[] bytes, int offset, int length, Consumer<JsonObject> out) {
            buffer.append(bytes, offset, length);
            int end;
            while ((end = boundary()) >= 0) {
                int separator = buffer.data[end] == '\r' ? 4 : 2;
                event(new String(buffer.data, 0, end, StandardCharsets.UTF_8), out);
                buffer.drop(end + separator);
            }
            if (buffer.size > MAX_FRAME_BYTES) {
                buffer.size = 0;
            }
        }

        @Override
        void finish(Consumer<JsonObject> out) {
            if (buffer.size > 0) {
                event(new String(buffer.data, 0, buffer.size, StandardCharsets.UTF_8), out);
                buffer.size = 0;
            }
        }

        /** Index of the first "\n\n" or "\r\n\r\n", or -1. */
        private int boundary() {
            for (int i = 0; i + 1 < buffer.size; i++) {
                if (buffer.data[i] == '\n' && buffer.data[i + 1] == '\n') {
                    return i;
                }
                if (i + 3 < buffer.size && buffer.data[i] == '\r' && buffer.data[i + 1] == '\n'
                        && buffer.data[i + 2] == '\r' && buffer.data[i + 3] == '\n') {
                    return i;
                }
            }
            return -1;
        }

        private static void event(String text, Consumer<JsonObject> out) {
            StringBuilder data = new StringBuilder();
            for (String line : text.split("\n")) {
                String trimmed = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
                if (trimmed.startsWith("data:")) {
                    String value = trimmed.substring(5);
                    if (value.startsWith(" ")) {
                        value = value.substring(1);
                    }
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(value);
                }
            }
            String payload = data.toString();
            if (!payload.equals("[DONE]")) {
                emitJson(payload, out);
            }
        }
    }

    /**
     * {@code application/vnd.amazon.eventstream}: length-prefixed binary frames — total length (4),
     * headers length (4), prelude CRC (4), headers, payload, message CRC (4). Only the payload
     * matters: an event's is JSON, and an exception's JSON carries no usage, so headers are skipped
     * rather than interpreted.
     */
    static final class AwsEventStream extends StreamDecoder {
        private final Buffer buffer = new Buffer();
        private boolean lost;

        @Override
        void accept(byte[] bytes, int offset, int length, Consumer<JsonObject> out) {
            if (lost) {
                return;
            }
            buffer.append(bytes, offset, length);
            while (buffer.size >= 12) {
                int total = readInt(0);
                int headers = readInt(4);
                if (total < 16 || total > MAX_FRAME_BYTES || headers < 0 || 12 + headers > total - 4) {
                    lost = true;       // not a frame boundary: stop rather than read garbage as usage
                    buffer.size = 0;
                    return;
                }
                if (buffer.size < total) {
                    return;
                }
                int payloadStart = 12 + headers;
                int payloadLength = total - 4 - payloadStart;
                emitJson(new String(buffer.data, payloadStart, payloadLength, StandardCharsets.UTF_8), out);
                buffer.drop(total);
            }
        }

        @Override
        void finish(Consumer<JsonObject> out) {
            buffer.size = 0;
        }

        private int readInt(int at) {
            return ((buffer.data[at] & 0xff) << 24) | ((buffer.data[at + 1] & 0xff) << 16)
                    | ((buffer.data[at + 2] & 0xff) << 8) | (buffer.data[at + 3] & 0xff);
        }
    }

    /** Anything else: the whole body is one JSON value, read once the response has ended. */
    static final class WholeBody extends StreamDecoder {
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private boolean overflow;

        @Override
        void accept(byte[] bytes, int offset, int length, Consumer<JsonObject> out) {
            if (overflow) {
                return;
            }
            if (body.size() + length > MAX_FRAME_BYTES) {
                overflow = true;
                body.reset();
                return;
            }
            body.write(bytes, offset, length);
        }

        @Override
        void finish(Consumer<JsonObject> out) {
            if (!overflow) {
                emitJson(body.toString(StandardCharsets.UTF_8), out);
            }
        }
    }
}
