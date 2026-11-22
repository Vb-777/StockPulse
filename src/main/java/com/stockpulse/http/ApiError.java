package com.stockpulse.http;

/** An error with an HTTP status and a stable machine-readable code, returned to the client as JSON. */
public class ApiError extends RuntimeException {
    public final int status;
    public final String code;
    public final java.util.Map<String, String> headers;

    public ApiError(int status, String code, String message) { this(status, code, message, java.util.Map.of()); }

    public ApiError(int status, String code, String message, java.util.Map<String, String> headers) {
        super(message);
        this.status = status;
        this.code = code;
        this.headers = headers;
    }

    public static ApiError badRequest(String msg) { return new ApiError(400, "bad_request", msg); }
    public static ApiError notFound(String msg) { return new ApiError(404, "not_found", msg); }
}
