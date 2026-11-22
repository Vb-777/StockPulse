package com.stockpulse.order;

import com.stockpulse.metrics.Metrics;
import com.stockpulse.redis.RedisConnection;
import com.stockpulse.redis.RedisException;
import com.stockpulse.redis.RedisPool;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A consumer in the {@code workers} group of the orders stream.
 *
 * An entry is acknowledged (XACK) only after its order has been settled and committed. If the
 * worker dies first, the entry stays in the group's pending list; any live worker reclaims it
 * once it has been idle longer than {@code claimIdleMs} (XPENDING + XCLAIM) and processes it
 * again. An entry that has failed {@code maxDeliveries} times is moved to a dead-letter stream
 * instead of blocking the queue forever.
 */
public final class OrderWorker implements Runnable {
    public static final String GROUP = "workers";
    public static final String DLQ = "orders-dlq";
    private static final Logger LOG = Logger.getLogger(OrderWorker.class.getName());

    private final RedisPool redis;
    private final OrderProcessor processor;
    private final Metrics metrics;
    private final String name;
    private final long claimIdleMs;
    private final int maxDeliveries;
    private volatile boolean running = true;
    private long lastReclaim;

    public OrderWorker(RedisPool redis, OrderProcessor processor, Metrics metrics, String name,
                       long claimIdleMs, int maxDeliveries) {
        this.redis = redis;
        this.processor = processor;
        this.metrics = metrics;
        this.name = name;
        this.claimIdleMs = claimIdleMs;
        this.maxDeliveries = maxDeliveries;
    }

    public void stop() { running = false; }

    public static void ensureGroup(RedisPool redis) {
        try {
            // Start at 0, not $, so events published before the group existed are not skipped.
            redis.call("XGROUP", "CREATE", OutboxRelay.STREAM, GROUP, "0", "MKSTREAM");
        } catch (RedisException e) {
            if (e.getMessage() == null || !e.getMessage().startsWith("BUSYGROUP")) throw e;
        }
    }

    record Entry(String id, String orderId) { }

    /** Parses an XREADGROUP / XCLAIM reply into entries; deleted entries come back with null fields. */
    @SuppressWarnings("unchecked")
    static List<Entry> entries(Object streamEntries) {
        List<Entry> out = new ArrayList<>();
        if (streamEntries == null) return out;
        for (Object o : (List<Object>) streamEntries) {
            List<Object> e = (List<Object>) o;
            String id = (String) e.get(0);
            String orderId = null;
            List<Object> fields = (List<Object>) e.get(1);
            if (fields != null) {
                for (int i = 0; i + 1 < fields.size(); i += 2) {
                    if ("order_id".equals(fields.get(i))) orderId = (String) fields.get(i + 1);
                }
            }
            out.add(new Entry(id, orderId));
        }
        return out;
    }

    @Override
    public void run() {
        ensureGroup(redis);
        RedisConnection conn = null;
        while (running) {
            try {
                if (conn == null || conn.isBroken()) {
                    if (conn != null) conn.close();
                    conn = redis.newConnection();
                    conn.setReadTimeout(5000);
                }
                if (System.currentTimeMillis() - lastReclaim > Math.max(1000, claimIdleMs / 2)) {
                    reclaim(conn);
                    lastReclaim = System.currentTimeMillis();
                }
                Object reply = conn.call("XREADGROUP", "GROUP", GROUP, name, "COUNT", "32", "BLOCK", "1000",
                        "STREAMS", OutboxRelay.STREAM, ">");
                if (reply == null) continue;
                @SuppressWarnings("unchecked")
                List<Object> streams = (List<Object>) reply;
                for (Object s : streams) {
                    @SuppressWarnings("unchecked")
                    List<Object> pair = (List<Object>) s;
                    for (Entry e : entries(pair.get(1))) handle(conn, e);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException e) {
                metrics.inc("worker_errors_total");
                LOG.log(Level.WARNING, name + ": " + e.getMessage());
                sleepQuietly(500);
            }
        }
        if (conn != null) conn.close();
    }

    void handle(RedisConnection conn, Entry e) throws InterruptedException {
        if (e.orderId == null) { // trimmed or malformed entry: nothing to do
            conn.call("XACK", OutboxRelay.STREAM, GROUP, e.id);
            return;
        }
        try {
            processor.process(UUID.fromString(e.orderId), name);
            conn.call("XACK", OutboxRelay.STREAM, GROUP, e.id);
            metrics.inc("worker_acked_total");
        } catch (RedisException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            // Leave it pending; it is retried after claimIdleMs and dead-lettered after maxDeliveries.
            metrics.inc("worker_process_failures_total");
            LOG.log(Level.WARNING, name + " failed order " + e.orderId + ": " + ex.getMessage());
        }
    }

    /** Takes over entries that another (probably dead) consumer has held too long. */
    @SuppressWarnings("unchecked")
    void reclaim(RedisConnection conn) throws InterruptedException {
        List<Object> pending = (List<Object>) conn.call("XPENDING", OutboxRelay.STREAM, GROUP,
                "IDLE", Long.toString(claimIdleMs), "-", "+", "100");
        if (pending == null || pending.isEmpty()) return;
        List<String> claim = new ArrayList<>();
        for (Object o : pending) {
            List<Object> p = (List<Object>) o; // [id, consumer, idle ms, deliveries]
            String id = (String) p.get(0);
            long deliveries = (Long) p.get(3);
            if (deliveries >= maxDeliveries) {
                List<Entry> dead = entries(conn.call("XCLAIM", OutboxRelay.STREAM, GROUP, name, "0", id));
                for (Entry d : dead) {
                    conn.call("XADD", DLQ, "*", "source_id", d.id, "order_id", String.valueOf(d.orderId),
                            "deliveries", Long.toString(deliveries));
                    conn.call("XACK", OutboxRelay.STREAM, GROUP, d.id);
                    metrics.inc("worker_dead_lettered_total");
                }
            } else {
                claim.add(id);
            }
        }
        if (claim.isEmpty()) return;
        String[] args = new String[5 + claim.size()];
        args[0] = "XCLAIM";
        args[1] = OutboxRelay.STREAM;
        args[2] = GROUP;
        args[3] = name;
        args[4] = Long.toString(claimIdleMs);
        for (int i = 0; i < claim.size(); i++) args[5 + i] = claim.get(i);
        List<Entry> taken = entries(conn.call(args));
        metrics.add(taken.size(), "worker_reclaimed_total");
        for (Entry e : taken) handle(conn, e);
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
