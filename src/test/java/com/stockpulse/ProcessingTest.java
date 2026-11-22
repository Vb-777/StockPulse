package com.stockpulse;

import static com.stockpulse.TestEnv.body;
import static com.stockpulse.TestEnv.order;
import static com.stockpulse.TestEnv.scalar;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.stockpulse.order.OrderProcessor;
import com.stockpulse.order.OrderWorker;
import com.stockpulse.order.OutboxRelay;
import com.stockpulse.redis.RedisConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ProcessingTest {
    App app;
    TestEnv.Client http;

    void start(Map<String, String> cfg) throws Exception {
        TestEnv.reset();
        app = new App(TestEnv.config(cfg)).start();
        http = new TestEnv.Client(app.port());
        http.post("/products", Map.of("sku", "BOOK-1", "name", "Book", "price_cents", 500L, "stock", 1000L));
    }

    @AfterEach
    void down() { if (app != null) app.close(); }

    String place(String key, int qty) throws Exception {
        return (String) body(http.post("/orders", order("BOOK-1", Integer.toString(qty)),
                "X-Client-Id", "c1", "Idempotency-Key", key)).get("id");
    }

    static void await(String what, Callable<Boolean> cond) throws Exception {
        long end = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < end) {
            if (cond.call()) return;
            Thread.sleep(50);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    @Test
    void endToEndConfirmsAndSellsReservedUnits() throws Exception {
        start(Map.of("MODE", "all", "WORKER_THREADS", "2"));
        for (int i = 0; i < 40; i++) place("k" + i, 2);
        await("all confirmed", () -> scalar("SELECT count(*) FROM orders WHERE status='CONFIRMED'") == 40);
        assertEquals(80L, scalar("SELECT sold FROM inventory WHERE sku='BOOK-1'"));
        assertEquals(0L, scalar("SELECT reserved FROM inventory WHERE sku='BOOK-1'"));
        assertEquals(40L, scalar("SELECT count(*) FROM order_transitions"));
        assertEquals(0L, app.inventoryStats().get("rows_violating_conservation"));
    }

    @Test
    void declinedPaymentReleasesStock() throws Exception {
        start(Map.of("MODE", "all", "PAYMENT_DECLINE_PERCENT", "100"));
        String id = place("k1", 5);
        await("cancelled", () -> "CANCELLED".equals(body(http.get("/orders/" + id, "X-Client-Id", "c1")).get("status")));
        assertEquals(1000L, scalar("SELECT available FROM inventory WHERE sku='BOOK-1'"));
        assertEquals("payment_declined", body(http.get("/orders/" + id, "X-Client-Id", "c1")).get("reason"));
        assertEquals(Long.valueOf(1000), body(http.get("/products/BOOK-1")).get("available"));
    }

    @Test
    void duplicateDeliveriesApplyOnce() throws Exception {
        start(Map.of());
        UUID id = UUID.fromString(place("k1", 3));
        List<Callable<OrderProcessor.Outcome>> calls = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            String w = "w" + i;
            calls.add(() -> app.processor.process(id, w));
        }
        int confirmed = 0;
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Future<OrderProcessor.Outcome> f : ex.invokeAll(calls)) {
                if (f.get() == OrderProcessor.Outcome.CONFIRMED) confirmed++;
                else assertEquals(OrderProcessor.Outcome.ALREADY_DONE, f.get());
            }
        }
        assertEquals(1, confirmed);
        assertEquals(1L, scalar("SELECT count(*) FROM order_transitions"));
        assertEquals(3L, scalar("SELECT sold FROM inventory WHERE sku='BOOK-1'"));
    }

    @Test
    void relayRepublishIsAbsorbed() throws Exception {
        start(Map.of());
        place("k1", 1);
        OutboxRelay relay = new OutboxRelay(app.db, app.redis, app.metrics, 100, 1000);
        assertEquals(1, relay.publishOnce());
        // Simulate a relay that crashed after XADD but before marking the row: publish again.
        TestEnv.exec("UPDATE outbox SET published_at = NULL");
        assertEquals(1, relay.publishOnce());
        assertEquals(Long.valueOf(2), app.redis.call("XLEN", OutboxRelay.STREAM));
        OrderWorker.ensureGroup(app.redis);
        OrderWorker w = new OrderWorker(app.redis, app.processor, app.metrics, "solo", 60_000, 5);
        Thread t = Thread.ofVirtual().start(w);
        await("both entries acked", () -> app.metrics.count("worker_acked_total") == 2);
        w.stop();
        t.join(3000);
        assertEquals(1L, scalar("SELECT count(*) FROM order_transitions"));
        assertEquals(1L, app.metrics.count("orders_processed_total", "outcome", "already_done"));
    }

    @Test
    void deadConsumersEntriesAreReclaimed() throws Exception {
        start(Map.of());
        for (int i = 0; i < 10; i++) place("k" + i, 1);
        OutboxRelay relay = new OutboxRelay(app.db, app.redis, app.metrics, 100, 1000);
        relay.publishOnce();
        OrderWorker.ensureGroup(app.redis);
        // A consumer reads all ten entries and dies without acknowledging any of them.
        try (RedisConnection dead = app.redis.newConnection()) {
            dead.call("XREADGROUP", "GROUP", OrderWorker.GROUP, "doomed", "COUNT", "100", "STREAMS", OutboxRelay.STREAM, ">");
        }
        assertEquals(10L, scalar("SELECT count(*) FROM orders WHERE status='PENDING'"));
        OrderWorker live = new OrderWorker(app.redis, app.processor, app.metrics, "survivor", 200, 5);
        Thread t = Thread.ofVirtual().start(live);
        await("reclaimed and confirmed", () -> scalar("SELECT count(*) FROM orders WHERE status='CONFIRMED'") == 10);
        await("pending list drained", () -> ((List<?>) app.redis.call("XPENDING", OutboxRelay.STREAM, OrderWorker.GROUP)).get(0).equals(0L));
        live.stop();
        t.join(3000);
        assertEquals(10L, app.metrics.count("worker_reclaimed_total"));
    }

    @Test
    void poisonEntryIsDeadLettered() throws Exception {
        start(Map.of());
        OrderWorker.ensureGroup(app.redis);
        app.redis.call("XADD", OutboxRelay.STREAM, "*", "event_id", "0", "order_id", "not-a-uuid", "type", "order.created");
        OrderWorker w = new OrderWorker(app.redis, app.processor, app.metrics, "w", 100, 3);
        Thread t = Thread.ofVirtual().start(w);
        await("dead-lettered", () -> ((Long) app.redis.call("XLEN", OrderWorker.DLQ)) == 1L);
        w.stop();
        t.join(3000);
        assertTrue(app.metrics.count("worker_process_failures_total") >= 3);
        assertEquals(0L, ((List<?>) app.redis.call("XPENDING", OutboxRelay.STREAM, OrderWorker.GROUP)).get(0));
    }
}
