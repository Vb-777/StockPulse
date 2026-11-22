package com.stockpulse.http;

import com.stockpulse.json.Json;
import java.util.LinkedHashMap;
import java.util.Map;

/** A response: status, body text, content type and extra headers. */
public record Response(int status, String body, String contentType, Map<String, String> headers) {

    public static Response json(int status, Object body) {
        return new Response(status, Json.write(body), "application/json", new LinkedHashMap<>());
    }

    public static Response rawJson(int status, String body) {
        return new Response(status, body, "application/json", new LinkedHashMap<>());
    }

    public static Response text(int status, String body) {
        return new Response(status, body, "text/plain; version=0.0.4", new LinkedHashMap<>());
    }

    public Response header(String k, String v) {
        headers.put(k, v);
        return this;
    }
}
