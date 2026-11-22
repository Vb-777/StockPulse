package com.stockpulse.order;

import com.stockpulse.db.Db;
import com.stockpulse.http.ApiError;
import com.stockpulse.json.Json;
import com.stockpulse.metrics.Metrics;
import com.stockpulse.product.ProductService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Order placement. One database transaction does all of it:
 *
 * <ol>
 *   <li>claims the (client, Idempotency-Key) pair - a concurrent retry with the same key blocks
 *       here until the first attempt commits, then replays its stored response;</li>
 *   <li>locks the inventory rows in SKU order (a fixed lock order, so two multi-line orders
 *       cannot deadlock) and checks stock;</li>
 *   <li>moves the units from {@code available} to {@code reserved}, writes the order and its
 *       lines, and writes an outbox event;</li>
 *   <li>stores the response under the idempotency key.</li>
 * </ol>
 *
 * Business rejections (unknown SKU, insufficient stock) are stored too, so a retry gets the
 * same answer rather than a different one.
 */
public final class OrderService {
    public static final int MAX_LINES = 20;
    public static final int MAX_QTY = 1000;
    static final Pattern CLIENT = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    public record Result(int status, String body, boolean replayed) { }

    private final Db db;
    private final ProductService products;
    private final Metrics metrics;

    public OrderService(Db db, ProductService products, Metrics metrics) {
        this.db = db;
        this.products = products;
        this.metrics = metrics;
    }

    public static void checkClient(String clientId) {
        if (clientId == null || !CLIENT.matcher(clientId).matches())
            throw new ApiError(401, "missing_client", "X-Client-Id header required ([A-Za-z0-9_-]{1,64})");
    }

    /** Validated order lines, merged and sorted by SKU. */
    static TreeMap<String, Integer> parseLines(Map<String, Object> body) {
        if (!(body.get("lines") instanceof List<?> lines) || lines.isEmpty() || lines.size() > MAX_LINES)
            throw ApiError.badRequest("lines must be a list of 1-" + MAX_LINES + " items");
        TreeMap<String, Integer> out = new TreeMap<>();
        for (Object o : lines) {
            if (!(o instanceof Map<?, ?> line)) throw ApiError.badRequest("each line must be an object");
            if (!(line.get("sku") instanceof String sku)) throw ApiError.badRequest("line.sku must be a string");
            ProductService.checkSku(sku);
            if (!(line.get("qty") instanceof Long q) || q < 1 || q > MAX_QTY)
                throw ApiError.badRequest("line.qty must be an integer 1-" + MAX_QTY);
            if (out.containsKey(sku)) throw ApiError.badRequest("sku " + sku + " appears twice");
            out.put(sku, q.intValue());
        }
        return out;
    }

    /** Canonical fingerprint of the request, so a reused key with a different body is detected. */
    static String requestHash(TreeMap<String, Integer> lines) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(Json.write(lines).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Result place(String clientId, String idemKey, Map<String, Object> body) {
        checkClient(clientId);
        if (idemKey == null || idemKey.isBlank() || idemKey.length() > 100)
            throw new ApiError(400, "missing_idempotency_key", "Idempotency-Key header required (1-100 chars)");
        TreeMap<String, Integer> lines = parseLines(body);
        String hash = requestHash(lines);

        Result r = db.tx(c -> {
            try (PreparedStatement p = c.prepareStatement(
                    "INSERT INTO idempotency_keys (client_id, idem_key, request_hash) VALUES (?, ?, ?) "
                            + "ON CONFLICT DO NOTHING")) {
                p.setString(1, clientId);
                p.setString(2, idemKey);
                p.setString(3, hash);
                if (p.executeUpdate() == 0) return replay(c, clientId, idemKey, hash);
            }
            Result fresh = reserve(c, clientId, lines);
            try (PreparedStatement p = c.prepareStatement(
                    "UPDATE idempotency_keys SET status_code = ?, response_body = ? WHERE client_id = ? AND idem_key = ?")) {
                p.setInt(1, fresh.status);
                p.setString(2, fresh.body);
                p.setString(3, clientId);
                p.setString(4, idemKey);
                p.executeUpdate();
            }
            return fresh;
        });
        if (r.replayed) {
            metrics.inc("orders_replayed_total");
        } else {
            metrics.inc("orders_placed_total", "status", Integer.toString(r.status));
            if (r.status == 201) products.invalidate(lines.keySet());
        }
        return r;
    }

    private static Result replay(Connection c, String clientId, String idemKey, String hash) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "SELECT request_hash, status_code, response_body FROM idempotency_keys WHERE client_id = ? AND idem_key = ?")) {
            p.setString(1, clientId);
            p.setString(2, idemKey);
            try (ResultSet rs = p.executeQuery()) {
                if (!rs.next()) throw new IllegalStateException("idempotency row vanished");
                if (!rs.getString(1).equals(hash))
                    throw new ApiError(422, "idempotency_key_reused",
                            "Idempotency-Key was already used with a different request body");
                int status = rs.getInt(2);
                if (rs.wasNull()) throw new ApiError(409, "request_in_progress", "a request with this key is in progress");
                return new Result(status, rs.getString(3), true);
            }
        }
    }

    private Result reserve(Connection c, String clientId, TreeMap<String, Integer> lines) throws SQLException {
        Map<String, long[]> stock = new HashMap<>(); // sku -> {available, price}
        Array skus = c.createArrayOf("text", lines.keySet().toArray());
        try (PreparedStatement p = c.prepareStatement(
                "SELECT i.sku, i.available, p.price_cents FROM inventory i JOIN products p ON p.sku = i.sku "
                        + "WHERE i.sku = ANY(?) ORDER BY i.sku FOR UPDATE OF i")) {
            p.setArray(1, skus);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) stock.put(rs.getString(1), new long[]{rs.getLong(2), rs.getLong(3)});
            }
        }
        List<String> unknown = new ArrayList<>();
        List<Map<String, Object>> shortBy = new ArrayList<>();
        for (var e : lines.entrySet()) {
            long[] s = stock.get(e.getKey());
            if (s == null) { unknown.add(e.getKey()); continue; }
            if (s[0] < e.getValue()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("sku", e.getKey());
                m.put("requested", (long) e.getValue());
                m.put("available", s[0]);
                shortBy.add(m);
            }
        }
        if (!unknown.isEmpty()) return error(422, "unknown_sku", "unknown sku(s): " + unknown, null);
        if (!shortBy.isEmpty()) return error(409, "insufficient_stock", "not enough stock", shortBy);

        UUID id = UUID.randomUUID();
        long total = 0;
        try (PreparedStatement p = c.prepareStatement(
                "UPDATE inventory SET available = available - ?, reserved = reserved + ? WHERE sku = ?")) {
            for (var e : lines.entrySet()) {
                p.setLong(1, e.getValue());
                p.setLong(2, e.getValue());
                p.setString(3, e.getKey());
                p.addBatch();
                total += e.getValue() * stock.get(e.getKey())[1];
            }
            p.executeBatch();
        }
        try (PreparedStatement p = c.prepareStatement(
                "INSERT INTO orders (id, client_id, status, total_cents) VALUES (?, ?, 'PENDING', ?)")) {
            p.setObject(1, id);
            p.setString(2, clientId);
            p.setLong(3, total);
            p.executeUpdate();
        }
        List<Map<String, Object>> outLines = new ArrayList<>();
        try (PreparedStatement p = c.prepareStatement(
                "INSERT INTO order_lines (order_id, sku, qty, price_cents) VALUES (?, ?, ?, ?)")) {
            for (var e : lines.entrySet()) {
                long price = stock.get(e.getKey())[1];
                p.setObject(1, id);
                p.setString(2, e.getKey());
                p.setInt(3, e.getValue());
                p.setLong(4, price);
                p.addBatch();
                Map<String, Object> l = new LinkedHashMap<>();
                l.put("sku", e.getKey());
                l.put("qty", (long) e.getValue());
                l.put("price_cents", price);
                outLines.add(l);
            }
            p.executeBatch();
        }
        try (PreparedStatement p = c.prepareStatement(
                "INSERT INTO outbox (order_id, event_type) VALUES (?, 'order.created')")) {
            p.setObject(1, id);
            p.executeUpdate();
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", id.toString());
        resp.put("status", "PENDING");
        resp.put("total_cents", total);
        resp.put("lines", outLines);
        return new Result(201, Json.write(resp), false);
    }

    private static Result error(int status, String code, String msg, Object details) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", code);
        err.put("message", msg);
        if (details != null) err.put("details", details);
        return new Result(status, Json.write(Map.of("error", err)), false);
    }

    public Map<String, Object> get(String clientId, String id) {
        checkClient(clientId);
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw ApiError.badRequest("order id must be a UUID");
        }
        return db.tx(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            try (PreparedStatement p = c.prepareStatement(
                    "SELECT status, total_cents, reason, created_at, updated_at FROM orders WHERE id = ? AND client_id = ?")) {
                p.setObject(1, uuid);
                p.setString(2, clientId);
                try (ResultSet rs = p.executeQuery()) {
                    // Another client's order is reported as missing, not forbidden: no existence leak.
                    if (!rs.next()) throw ApiError.notFound("no order " + id);
                    m.put("id", id);
                    m.put("status", rs.getString(1));
                    m.put("total_cents", rs.getLong(2));
                    if (rs.getString(3) != null) m.put("reason", rs.getString(3));
                    m.put("created_at", rs.getObject(4).toString());
                    m.put("updated_at", rs.getObject(5).toString());
                }
            }
            List<Map<String, Object>> lines = new ArrayList<>();
            try (PreparedStatement p = c.prepareStatement(
                    "SELECT sku, qty, price_cents FROM order_lines WHERE order_id = ? ORDER BY sku")) {
                p.setObject(1, uuid);
                try (ResultSet rs = p.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> l = new LinkedHashMap<>();
                        l.put("sku", rs.getString(1));
                        l.put("qty", (long) rs.getInt(2));
                        l.put("price_cents", rs.getLong(3));
                        lines.add(l);
                    }
                }
            }
            m.put("lines", lines);
            return m;
        });
    }
}
