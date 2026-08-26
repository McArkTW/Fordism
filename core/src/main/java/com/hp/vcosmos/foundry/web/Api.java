package com.hp.vcosmos.foundry.web;

import com.google.gson.Gson;
import io.javalin.http.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The request/response plumbing every controller needs: read a JSON body, coerce a field, answer
 * with an error.
 *
 * <p>Each controller used to carry its own private copy of these four, which is how
 * {@code CredentialController} ended up escaping errors differently from {@code TemplateController}
 * for the same kind of failure.
 */
public final class Api {
    private static final Gson GSON = new Gson();

    private Api() {}

    /** The request body as a map; an empty map when there is no body or it is not an object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> body(Context ctx) {
        Map<String, Object> parsed = GSON.fromJson(ctx.body(), Map.class);
        return parsed == null ? Map.of() : parsed;
    }

    /** A body field as a string; "" when absent, so callers never null-check a form value. */
    public static String string(Object value) {
        return value == null ? "" : value.toString();
    }

    /** A list-of-names field, accepting a JSON array or a comma/newline-separated string. */
    public static List<String> names(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                addName(out, item == null ? null : item.toString());
            }
        } else if (value != null) {
            for (String part : value.toString().split("[,\\r\\n]+")) {
                addName(out, part);
            }
        }
        return out;
    }

    private static void addName(List<String> out, String value) {
        if (value != null && !value.isBlank() && !out.contains(value.trim())) {
            out.add(value.trim());
        }
    }

    /** An error response. The message is JSON-encoded by gson, so it needs no hand-escaping. */
    public static void fail(Context ctx, int status, String message) {
        ctx.status(status).contentType("application/json")
                .result(Json.write(Map.of("error", message == null ? "" : message)));
    }

    /** A one-field acknowledgement, e.g. {@code {"id":"…"}}. */
    public static void ok(Context ctx, String field, String value) {
        ctx.status(200).contentType("application/json").result(Json.write(Map.of(field, value)));
    }

    /** A JSON body for a value the caller already assembled. */
    public static void json(Context ctx, Object value) {
        ctx.contentType("application/json").result(Json.write(value));
    }
}
