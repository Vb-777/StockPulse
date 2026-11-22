package com.stockpulse.http;

import com.stockpulse.json.Json;
import java.util.Map;

/** An incoming request after routing: path parameters resolved, body read (size-capped). */
public record Request(String method, String path, Map<String, String> params, Map<String, String> query,
                      com.sun.net.httpserver.Headers headers, String body) {

    public String header(String name) { return headers.getFirst(name); }

    public String param(String name) { return params.get(name); }

    public Map<String, Object> json() {
        if (body == null || body.isBlank()) throw ApiError.badRequest("request body must be a JSON object");
        try {
            return Json.parseObject(body);
        } catch (Json.ParseException e) {
            throw ApiError.badRequest("invalid JSON: " + e.getMessage());
        }
    }
}
