package com.hp.vcosmos.foundry.web;

import com.google.gson.Gson;

/** Thin gson wrapper for JSON responses. */
public final class Json {
    private static final Gson GSON = new Gson();

    private Json() {}

    public static String write(Object value) {
        return GSON.toJson(value);
    }
}
