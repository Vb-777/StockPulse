package com.stockpulse;

import static com.stockpulse.TestEnv.body;
import static com.stockpulse.TestEnv.order;
import static com.stockpulse.TestEnv.scalar;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OrderFlowTest {
    App app;
    TestEnv.Client http;

    @BeforeEach
    void up() throws Exception {
        TestEnv.reset();
        app = new App(TestEnv.config(Map.of("DB_POOL", "16"))).start();
        http = new TestEnv.Client(app.port());
        product("APPLE-1", 100, 250);
        product("PEAR-2", 5, 400);
    }

    @AfterEach
    void down() { app.close(); }

    void product(String sku, long stock, long price) throws Exception {
        assertEquals(201, http.post("/products", Map.of("sku", sku, "name", sku, "price_cents", price, "stock", stock)).statusCode());
    }

    HttpResponse<String> place(String client, String key, String body) throws Exception {
        return http.post("/orders", body, "X-Client-Id", client, "Idempotency-Key", key);
    }

    @Test
    void placeReservesStockAndWritesOutbox() throws Exception {
        HttpResponse<String> r = place("c1", "k1", order("APPLE-1", "3", "PEAR-2", "2"));
        assertEquals(201, r.statusCode(), r.body());
        Map<String, Object> b = body(r);
        assertEquals("PENDING", b.get("status"));
        assertEquals(Long.valueOf(3 * 250 + 2 * 400), b.get("total_cents"));
        assertEquals(97L, scalar("SELECT available FROM inventory WHERE sku='APPLE-1'"));
        assertEquals(3L, scalar("SELECT reserved FROM inventory WHERE sku='APPLE-1'"));
        assertEquals(1L, scalar("SELECT count(*) FROM outbox WHERE published_at IS NULL"));
        Map<String, Object> got = body(http.get("/orders/" + b.get("id"), "X-Client-Id", "c1"));
        assertEquals("PENDING", got.get("status"));
    }

    @Test
    void retryWithSameKeyReplaysWithoutReservingAgain() throws Exception {
        HttpResponse<String> a = place("c1", "k1", order("APPLE-1", "4"));
        HttpResponse<String> b = place("c1", "k1", order("APPLE-1", "4"));
        assertEquals(201, b.statusCode());
        assertEquals(a.body(), b.body());
        assertEquals("true", b.headers().firstValue("Idempotent-Replayed").orElse(""));
        assertEquals(1L, scalar("SELECT count(*) FROM orders"));
        assertEquals(96L, scalar("SELECT available FROM inventory WHERE sku='APPLE-1'"));
    }

    @Test
    void sameKeyDifferentBodyIsRejected() throws Exception {
        place("c1", "k1", order("APPLE-1", "1"));
        HttpResponse<String> r = place("c1", "k1", order("APPLE-1", "2"));
        assertEquals(422, r.statusCode());
        assertEquals(1L, scalar("SELECT count(*) FROM orders"));
    }

    @Test
    void keysAreScopedPerClient() throws Exception {
        assertEquals(201, place("c1", "same", order("APPLE-1", "1")).statusCode());
        assertEquals(201, place("c2", "same", order("APPLE-1", "1")).statusCode());
        assertEquals(2L, scalar("SELECT count(*) FROM orders"));
    }

    @Test
    void rejectionsAreStoredAndReplayed() throws Exception {
        HttpResponse<String> r = place("c1", "k1", order("PEAR-2", "6"));
        assertEquals(409, r.statusCode());
        assertEquals("insufficient_stock", ((Map<?, ?>) body(r).get("error")).get("code"));
        http.post("/products/PEAR-2/restock", Map.of("qty", 100L));
        // Same key, same body: same answer as the first attempt, even though stock is now there.
        assertEquals(r.body(), place("c1", "k1", order("PEAR-2", "6")).body());
        assertEquals(201, place("c1", "k2", order("PEAR-2", "6")).statusCode());
        assertEquals(422, place("c1", "k3", order("GHOST-9", "1")).statusCode());
    }

    @Test
    void otherClientsOrdersAreInvisible() throws Exception {
        String id = (String) body(place("c1", "k1", order("APPLE-1", "1"))).get("id");
        assertEquals(404, http.get("/orders/" + id, "X-Client-Id", "c2").statusCode());
        assertEquals(400, http.get("/orders/not-a-uuid", "X-Client-Id", "c1").statusCode());
    }

    @Test
    void concurrentRetriesOfOneKeyCreateOneOrder() throws Exception {
        List<Callable<HttpResponse<String>>> calls = new ArrayList<>();
        for (int i = 0; i < 24; i++) calls.add(() -> place("c1", "burst", order("APPLE-1", "2")));
        Set<String> bodies = new HashSet<>();
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Future<HttpResponse<String>> f : ex.invokeAll(calls)) {
                assertEquals(201, f.get().statusCode(), f.get().body());
                bodies.add(f.get().body());
            }
        }
        assertEquals(1, bodies.size());
        assertEquals(1L, scalar("SELECT count(*) FROM orders"));
        assertEquals(98L, scalar("SELECT available FROM inventory WHERE sku='APPLE-1'"));
    }

    @Test
    void neverOversellsUnderContention() throws Exception {
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            String key = "k" + i;
            calls.add(() -> place("c" + (key.hashCode() & 7), key, order("APPLE-1", "1")).statusCode());
        }
        int ok = 0, sold = 0;
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Future<Integer> f : ex.invokeAll(calls)) {
                if (f.get() == 201) ok++;
                else if (f.get() == 409) sold++;
            }
        }
        assertEquals(100, ok);
        assertEquals(200, sold);
        assertEquals(0L, scalar("SELECT available FROM inventory WHERE sku='APPLE-1'"));
        assertEquals(100L, scalar("SELECT reserved FROM inventory WHERE sku='APPLE-1'"));
    }

    @Test
    void opposingMultiLineOrdersDoNotDeadlock() throws Exception {
        http.post("/products/PEAR-2/restock", Map.of("qty", 995L));
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            String body = i % 2 == 0 ? order("APPLE-1", "1", "PEAR-2", "1") : order("PEAR-2", "1", "APPLE-1", "1");
            String key = "m" + i;
            calls.add(() -> place("c1", key, body).statusCode());
        }
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Future<Integer> f : ex.invokeAll(calls)) assertEquals(Integer.valueOf(201), f.get());
        }
        assertEquals(20L, scalar("SELECT available FROM inventory WHERE sku='APPLE-1'"));
        assertTrue(app.inventoryStats().get("rows_violating_conservation").equals(0L));
    }
}
