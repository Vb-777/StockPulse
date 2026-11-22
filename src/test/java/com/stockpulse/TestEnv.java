package com.stockpulse;

import com.stockpulse.json.Json;
import com.stockpulse.redis.RedisConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

/**
 * Integration-test environment: a real PostgreSQL database and a real Redis, both wiped before
 * each test. Defaults match a local run; CI overrides them with TEST_DB_URL, TEST_REDIS_HOST etc.
 * Redis tests use database 15 so a developer's local data in database 0 is never flushed.
 */
public final class TestEnv {
    public static final String DB_URL = env("TEST_DB_URL", "jdbc:postgresql://127.0.0.1:5432/stockpulse_test");
    public static final String DB_USER = env("TEST_DB_USER", "sp");
    public static final String DB_PASSWORD = env("TEST_DB_PASSWORD", "sp");
    public static final String REDIS_HOST = env("TEST_REDIS_HOST", "127.0.0.1");
    public static final int REDIS_PORT = Integer.parseInt(env("TEST_REDIS_PORT", "6379"));
    public static final int REDIS_DB = 15;

    private TestEnv() { }

    static String env(String k, String d) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? d : v;
    }

    public static Config config(Map<String, String> overrides) {
        Map<String, String> m = new HashMap<>();
        m.put("MODE", "http");
        m.put("HOST", "127.0.0.1");
        m.put("PORT", "0");
        m.put("DB_URL", DB_URL);
        m.put("DB_USER", DB_USER);
        m.put("DB_PASSWORD", DB_PASSWORD);
        m.put("DB_POOL", "8");
        m.put("REDIS_HOST", REDIS_HOST);
        m.put("REDIS_PORT", Integer.toString(REDIS_PORT));
        m.put("REDIS_DB", Integer.toString(REDIS_DB));
        m.put("PAYMENT_LATENCY_MS", "0");
        m.put("PAYMENT_DECLINE_PERCENT", "0");
        m.put("RATE_CAPACITY", "100000");
        m.put("RATE_REFILL_PER_SECOND", "100000");
        m.putAll(overrides);
        return Config.fromEnv(m);
    }

    /** Fresh schema and empty Redis database. */
    public static void reset() throws Exception {
        try (Connection c = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             Statement s = c.createStatement()) {
            s.execute("DROP SCHEMA public CASCADE");
            s.execute("CREATE SCHEMA public");
        }
        try (RedisConnection r = new RedisConnection(REDIS_HOST, REDIS_PORT, REDIS_DB, 2000)) {
            r.call("FLUSHDB");
        }
    }

    public static long scalar(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    public static void exec(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    /** Minimal HTTP client for tests. */
    public static final class Client {
        private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        private final String base;

        public Client(int port) { this.base = "http://127.0.0.1:" + port; }

        public HttpResponse<String> send(String method, String path, String body, String... headers) throws Exception {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            for (int i = 0; i + 1 < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        }

        public HttpResponse<String> get(String path, String... headers) throws Exception { return send("GET", path, null, headers); }

        public HttpResponse<String> post(String path, Object json, String... headers) throws Exception {
            return send("POST", path, json instanceof String s ? s : Json.write(json), headers);
        }
    }

    public static Map<String, Object> body(HttpResponse<String> r) { return Json.parseObject(r.body()); }

    public static String order(String... skuQty) {
        StringBuilder b = new StringBuilder("{\"lines\":[");
        for (int i = 0; i + 1 < skuQty.length; i += 2) {
            if (i > 0) b.append(',');
            b.append("{\"sku\":\"").append(skuQty[i]).append("\",\"qty\":").append(skuQty[i + 1]).append('}');
        }
        return b.append("]}").toString();
    }
}
