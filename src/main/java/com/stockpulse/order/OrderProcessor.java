package com.stockpulse.order;

import com.stockpulse.db.Db;
import com.stockpulse.metrics.Metrics;
import com.stockpulse.product.ProductService;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Settles one PENDING order: charge, then either sell the reserved units (CONFIRMED) or
 * release them back to available (CANCELLED).
 *
 * Safe to run any number of times for the same order. The queue delivers at least once, so
 * duplicates are expected; the transition is guarded twice - the status is re-checked under a
 * row lock, and {@code order_transitions} has a primary key on (order, from_status) - so the
 * stock movement happens exactly once.
 */
public final class OrderProcessor {
    public enum Outcome { CONFIRMED, CANCELLED, ALREADY_DONE, NOT_FOUND }

    private final Db db;
    private final PaymentGateway payments;
    private final ProductService products;
    private final Metrics metrics;

    public OrderProcessor(Db db, PaymentGateway payments, ProductService products, Metrics metrics) {
        this.db = db;
        this.payments = payments;
        this.products = products;
        this.metrics = metrics;
    }

    public Outcome process(UUID orderId, String worker) throws InterruptedException {
        // Cheap pre-check without locks, so duplicates do not call the payment gateway again.
        Long amount = db.tx(c -> {
            try (PreparedStatement p = c.prepareStatement("SELECT status, total_cents FROM orders WHERE id = ?")) {
                p.setObject(1, orderId);
                try (ResultSet rs = p.executeQuery()) {
                    if (!rs.next()) return null;
                    return "PENDING".equals(rs.getString(1)) ? rs.getLong(2) : -1L;
                }
            }
        });
        if (amount == null) return count(Outcome.NOT_FOUND);
        if (amount < 0) return count(Outcome.ALREADY_DONE);

        // The external call happens outside any transaction: no row lock is held while waiting on it.
        PaymentGateway.Outcome pay = payments.charge(orderId, amount);
        String to = pay.approved() ? "CONFIRMED" : "CANCELLED";

        List<String> touched = new ArrayList<>();
        Outcome result = db.tx(c -> {
            try (PreparedStatement p = c.prepareStatement("SELECT status FROM orders WHERE id = ? FOR UPDATE")) {
                p.setObject(1, orderId);
                try (ResultSet rs = p.executeQuery()) {
                    if (!rs.next()) return Outcome.NOT_FOUND;
                    if (!"PENDING".equals(rs.getString(1))) return Outcome.ALREADY_DONE;
                }
            }
            List<Object[]> lines = new ArrayList<>();
            try (PreparedStatement p = c.prepareStatement(
                    "SELECT sku, qty FROM order_lines WHERE order_id = ? ORDER BY sku")) {
                p.setObject(1, orderId);
                try (ResultSet rs = p.executeQuery()) {
                    while (rs.next()) lines.add(new Object[]{rs.getString(1), rs.getInt(2)});
                }
            }
            String move = pay.approved()
                    ? "UPDATE inventory SET reserved = reserved - ?, sold = sold + ? WHERE sku = ?"
                    : "UPDATE inventory SET reserved = reserved - ?, available = available + ? WHERE sku = ?";
            try (PreparedStatement p = c.prepareStatement(move)) {
                for (Object[] l : lines) {
                    p.setInt(1, (Integer) l[1]);
                    p.setInt(2, (Integer) l[1]);
                    p.setString(3, (String) l[0]);
                    p.addBatch();
                    touched.add((String) l[0]);
                }
                p.executeBatch();
            }
            try (PreparedStatement p = c.prepareStatement(
                    "UPDATE orders SET status = ?, reason = ?, updated_at = now() WHERE id = ?")) {
                p.setString(1, to);
                p.setString(2, pay.reason());
                p.setObject(3, orderId);
                p.executeUpdate();
            }
            try (PreparedStatement p = c.prepareStatement(
                    "INSERT INTO order_transitions (order_id, from_status, to_status, worker) VALUES (?, 'PENDING', ?, ?)")) {
                p.setObject(1, orderId);
                p.setString(2, to);
                p.setString(3, worker);
                p.executeUpdate();
            }
            return pay.approved() ? Outcome.CONFIRMED : Outcome.CANCELLED;
        });
        if (result == Outcome.CANCELLED) products.invalidate(touched);
        return count(result);
    }

    private Outcome count(Outcome o) {
        metrics.inc("orders_processed_total", "outcome", o.name().toLowerCase());
        return o;
    }
}
