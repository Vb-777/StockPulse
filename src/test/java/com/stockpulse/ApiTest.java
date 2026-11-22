package com.stockpulse;

import static com.stockpulse.TestEnv.body;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApiTest {
    App app;
    TestEnv.Client http;

    @BeforeEach
    void up() throws Exception {
        TestEnv.reset();
        app = new App(TestEnv.config(Map.of("ADMIN_TOKEN", "s3cret"))).start();
        http = new TestEnv.Client(app.port());
    }

    @AfterEach
    void down() { app.close(); }

    HttpResponse<String> createProduct(String sku, long stock) throws Exception {
        return http.post("/products", Map.of("sku", sku, "name", "Widget " + sku, "price_cents", 1999L, "stock", stock),
                "X-Admin-Token", "s3cret");
    }

    @Test
    void createReadAndCacheProduct() throws Exception {
        assertEquals(201, createProduct("WID-1", 10).statusCode());
        HttpResponse<String> r1 = http.get("/products/WID-1");
        HttpResponse<String> r2 = http.get("/products/WID-1");
        assertEquals(200, r1.statusCode());
        assertEquals(r1.body(), r2.body());
        assertEquals(Long.valueOf(10), body(r1).get("available"));
        assertEquals(1L, app.metrics.count("cache_requests_total", "result", "miss"));
        assertEquals(1L, app.metrics.count("cache_requests_total", "result", "hit"));
    }

    @Test
    void restockInvalidatesCache() throws Exception {
        createProduct("WID-2", 3);
        http.get("/products/WID-2");
        HttpResponse<String> r = http.post("/products/WID-2/restock", Map.of("qty", 7L), "X-Admin-Token", "s3cret");
        assertEquals(200, r.statusCode());
        assertEquals(Long.valueOf(10), body(http.get("/products/WID-2")).get("available"));
    }

    @Test
    void adminRoutesNeedToken() throws Exception {
        HttpResponse<String> r = http.post("/products", Map.of("sku", "AB", "name", "x", "price_cents", 1L, "stock", 1L));
        assertEquals(403, r.statusCode());
        r = http.post("/products", Map.of("sku", "AB", "name", "x", "price_cents", 1L, "stock", 1L), "X-Admin-Token", "wrong");
        assertEquals(403, r.statusCode());
    }

    @Test
    void duplicateProductIsConflict() throws Exception {
        assertEquals(201, createProduct("DUP-1", 1).statusCode());
        assertEquals(409, createProduct("DUP-1", 1).statusCode());
    }

    @Test
    void errorsAreStructuredJson() throws Exception {
        assertEquals("not_found", ((Map<?, ?>) body(http.get("/products/NOPE-1")).get("error")).get("code"));
        assertEquals(400, http.get("/products/bad%20sku").statusCode());
        assertEquals(404, http.get("/no/such/route").statusCode());
        assertEquals(405, http.send("DELETE", "/products/WID-1", null).statusCode());
        assertEquals(400, http.post("/orders", "{not json", "X-Client-Id", "c1", "Idempotency-Key", "k").statusCode());
        assertEquals(401, http.post("/orders", TestEnv.order("AB", "1"), "Idempotency-Key", "k").statusCode());
        assertEquals(400, http.post("/orders", TestEnv.order("AB", "1"), "X-Client-Id", "c1").statusCode());
        assertEquals(413, http.post("/orders", "{\"x\":\"" + "a".repeat(70_000) + "\"}", "X-Client-Id", "c1",
                "Idempotency-Key", "k").statusCode());
    }

    @Test
    void healthAndMetrics() throws Exception {
        HttpResponse<String> h = http.get("/health");
        assertEquals(200, h.statusCode());
        assertEquals("ok", body(h).get("status"));
        http.get("/products/NOPE-2");
        String m = http.get("/metrics").body();
        assertTrue(m.contains("http_requests_total{route=\"/products/{sku}\",status=\"404\"} 1"), m);
        assertTrue(m.contains("http_request_duration_ms_count{route=\"/health\"} 1"), m);
    }
}
