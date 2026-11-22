package com.stockpulse.order;

import com.stockpulse.db.Db;
import com.stockpulse.metrics.Metrics;
import com.stockpulse.redis.RedisException;
import com.stockpulse.redis.RedisPool;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Moves committed outbox rows onto the Redis stream.
 *
 * Writing the order and its event in one database transaction means an order can never exist
 * without its event, or the other way round. The relay then publishes at least once: if it
 * crashes after XADD but before marking the row, the event is published again, which the
 * idempotent {@link OrderProcessor} absorbs. {@code FOR UPDATE SKIP LOCKED} lets several relays
 * run side by side without publishing the same row concurrently.
 */
public final class OutboxRelay implements Runnable {
    public static final String STREAM = "orders";
    private static final Logger LOG = Logger.getLogger(OutboxRelay.class.getName());

    private final Db db;
    private final RedisPool redis;
    private final Metrics metrics;
    private final int batch;
    private final long maxStreamLen;
    private volatile boolean running = true;

    public OutboxRelay(Db db, RedisPool redis, Metrics metrics, int batch, long maxStreamLen) {
        this.db = db;
        this.redis = redis;
        this.metrics = metrics;
        this.batch = batch;
        this.maxStreamLen = maxStreamLen;
    }

    public void stop() { running = false; }

    /** Publishes one batch; returns how many events were published. */
    public int publishOnce() {
        return db.tx(c -> {
            List<long[]> ids = new ArrayList<>();
            List<String[]> events = new ArrayList<>();
            try (PreparedStatement p = c.prepareStatement(
                    "SELECT id, order_id, event_type FROM outbox WHERE published_at IS NULL "
                            + "ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED")) {
                p.setInt(1, batch);
                try (ResultSet rs = p.executeQuery()) {
                    while (rs.next()) {
                        ids.add(new long[]{rs.getLong(1)});
                        events.add(new String[]{Long.toString(rs.getLong(1)), rs.getString(2), rs.getString(3)});
                    }
                }
            }
            if (events.isEmpty()) return 0;
            redis.with(conn -> {
                for (String[] e : events) {
                    conn.call("XADD", STREAM, "MAXLEN", "~", Long.toString(maxStreamLen), "*",
                            "event_id", e[0], "order_id", e[1], "type", e[2]);
                }
                return null;
            });
            try (PreparedStatement p = c.prepareStatement(
                    "UPDATE outbox SET published_at = now() WHERE id = ANY(?)")) {
                p.setArray(1, c.createArrayOf("bigint", ids.stream().map(a -> a[0]).toArray()));
                p.executeUpdate();
            }
            metrics.add(events.size(), "outbox_published_total");
            return events.size();
        });
    }

    @Override
    public void run() {
        while (running) {
            try {
                if (publishOnce() == 0) Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RedisException | Db.DbException | Db.PoolTimeout e) {
                metrics.inc("outbox_errors_total");
                LOG.log(Level.WARNING, "outbox relay error, retrying: " + e.getMessage());
                try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
        }
    }
}
