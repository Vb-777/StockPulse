package com.stockpulse.http;

import com.stockpulse.metrics.Metrics;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * HTTP front end on the JDK's built-in server with one virtual thread per request.
 *
 * Routes are matched on method plus a path template ({@code /orders/{id}}). Every response is
 * counted and timed per route template, not per concrete path, so metric cardinality stays fixed.
 */
public final class HttpApi implements AutoCloseable {
    public static final int MAX_BODY_BYTES = 64 * 1024;
    private static final Logger LOG = Logger.getLogger(HttpApi.class.getName());

    @FunctionalInterface
    public interface Handler { Response handle(Request r) throws Exception; }

    private record Route(String method, String template, String[] parts, Handler handler) { }

    private final List<Route> routes = new ArrayList<>();
    private final Metrics metrics;
    private HttpServer server;
    private ExecutorService executor;

    public HttpApi(Metrics metrics) { this.metrics = metrics; }

    public HttpApi route(String method, String template, Handler h) {
        routes.add(new Route(method, template, split(template), h));
        return this;
    }

    private static String[] split(String path) {
        String p = path.startsWith("/") ? path.substring(1) : path;
        return p.isEmpty() ? new String[0] : p.split("/", -1);
    }

    public int start(String host, int port, int backlog) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), backlog);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", this::dispatch);
        server.start();
        return server.getAddress().getPort();
    }

    void dispatch(HttpExchange ex) {
        long t0 = System.nanoTime();
        String template = "unmatched";
        Response resp;
        try {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getRawPath();
            String[] parts = split(path);
            Route hit = null;
            Map<String, String> params = null;
            boolean pathMatched = false;
            for (Route r : routes) {
                Map<String, String> p = match(r.parts, parts);
                if (p == null) continue;
                pathMatched = true;
                if (r.method.equals(method)) { hit = r; params = p; break; }
            }
            if (hit == null) {
                throw pathMatched ? new ApiError(405, "method_not_allowed", method + " not allowed on " + path)
                                  : ApiError.notFound("no route for " + path);
            }
            template = hit.template;
            String body = readBody(ex.getRequestBody());
            Request req = new Request(method, path, params, query(ex.getRequestURI().getRawQuery()),
                    ex.getRequestHeaders(), body);
            resp = hit.handler.handle(req);
        } catch (ApiError e) {
            resp = error(e);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "unhandled error on " + ex.getRequestURI(), e);
            resp = Response.json(500, Map.of("error", Map.of("code", "internal", "message", "internal error")));
        }
        send(ex, resp);
        metrics.inc("http_requests_total", "route", template, "status", Integer.toString(resp.status()));
        metrics.observeMs((System.nanoTime() - t0) / 1e6, "http_request_duration_ms", "route", template);
    }

    static Response error(ApiError e) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", e.code);
        err.put("message", e.getMessage());
        Response r = Response.json(e.status, Map.of("error", err));
        e.headers.forEach(r::header);
        return r;
    }

    static Map<String, String> match(String[] tpl, String[] path) {
        if (tpl.length != path.length) return null;
        Map<String, String> params = new HashMap<>();
        for (int i = 0; i < tpl.length; i++) {
            String t = tpl[i];
            if (t.startsWith("{") && t.endsWith("}")) {
                if (path[i].isEmpty()) return null;
                params.put(t.substring(1, t.length() - 1), URLDecoder.decode(path[i], StandardCharsets.UTF_8));
            } else if (!t.equals(path[i])) {
                return null;
            }
        }
        return params;
    }

    static Map<String, String> query(String raw) {
        Map<String, String> q = new HashMap<>();
        if (raw == null || raw.isEmpty()) return q;
        for (String kv : raw.split("&")) {
            int eq = kv.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? kv : kv.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8);
            q.putIfAbsent(k, v);
        }
        return q;
    }

    static String readBody(InputStream in) throws IOException {
        byte[] b = in.readNBytes(MAX_BODY_BYTES + 1);
        if (b.length > MAX_BODY_BYTES) throw new ApiError(413, "payload_too_large", "body exceeds " + MAX_BODY_BYTES + " bytes");
        return new String(b, StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange ex, Response r) {
        try (ex) {
            byte[] b = r.body().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", r.contentType());
            r.headers().forEach((k, v) -> ex.getResponseHeaders().set(k, v));
            ex.sendResponseHeaders(r.status(), b.length == 0 ? -1 : b.length);
            if (b.length > 0) {
                try (OutputStream os = ex.getResponseBody()) { os.write(b); }
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "client went away", e);
        }
    }

    /** Stops accepting connections and waits up to {@code graceSeconds} for in-flight requests. */
    public void stop(int graceSeconds) {
        if (server != null) server.stop(graceSeconds);
        if (executor != null) executor.close();
    }

    @Override
    public void close() { stop(0); }
}
