package com.stockpulse.product;

import com.stockpulse.db.Db;
import com.stockpulse.http.ApiError;
import com.stockpulse.json.Json;
import com.stockpulse.metrics.Metrics;
import com.stockpulse.redis.RedisException;
import com.stockpulse.redis.RedisPool;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Product reads go through a cache-aside layer in Redis.
 *
 * The cached document includes the displayed stock level, so it is kept short-lived (a few
 * seconds) and deleted whenever an order changes that SKU's availability. Displayed stock may
 * therefore lag by at most the TTL; it never decides anything, because order placement always
 * re-checks stock under a row lock in PostgreSQL.
 *
 * Concurrent misses for the same SKU in one process are collapsed into a single database read
 * ("single flight"), so an expiring hot key does not send a burst of identical queries.
 */
public final class ProductService {
    public static final Pattern SKU = Pattern.compile("^[A-Z0-9][A-Z0-9-]{1,31}$");

    private final Db db;
    private final RedisPool redis;
    private final Metrics metrics;
    private final boolean cacheEnabled;
    private final int ttlSeconds;
    private final ConcurrentHashMap<String, CompletableFuture<String>> inflight = new ConcurrentHashMap<>();

    public ProductService(Db db, RedisPool redis, Metrics metrics, boolean cacheEnabled, int ttlSeconds) {
        this.db = db;
        this.redis = redis;
        this.metrics = metrics;
        this.cacheEnabled = cacheEnabled;
        this.ttlSeconds = ttlSeconds;
    }

    static String cacheKey(String sku) { return "product:" + sku; }

    public static void checkSku(String sku) {
        if (sku == null || !SKU.matcher(sku).matches()) throw ApiError.badRequest("sku must match " + SKU.pattern());
    }

    /** Returns the product as a JSON document string. */
    public String get(String sku) {
        checkSku(sku);
        if (!cacheEnabled) return load(sku);
        String cached = null;
        try {
            cached = redis.get(cacheKey(sku));
        } catch (RedisException e) {
            metrics.inc("cache_errors_total");
        }
        if (cached != null) {
            metrics.inc("cache_requests_total", "result", "hit");
            return cached;
        }
        metrics.inc("cache_requests_total", "result", "miss");
        CompletableFuture<String> mine = new CompletableFuture<>();
        CompletableFuture<String> other = inflight.putIfAbsent(sku, mine);
        if (other != null) {
            metrics.inc("cache_coalesced_total");
            try {
                return other.join();
            } catch (java.util.concurrent.CompletionException e) {
                if (e.getCause() instanceof RuntimeException re) throw re;
                throw e;
            }
        }
        try {
            String doc = load(sku);
            try {
                redis.setex(cacheKey(sku), ttlSeconds, doc);
            } catch (RedisException e) {
                metrics.inc("cache_errors_total");
            }
            mine.complete(doc);
            return doc;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inflight.remove(sku, mine);
        }
    }

    String load(String sku) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT p.sku, p.name, p.price_cents, i.available FROM products p "
                            + "JOIN inventory i ON i.sku = p.sku WHERE p.sku = ?")) {
                ps.setString(1, sku);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw ApiError.notFound("no product " + sku);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("sku", rs.getString(1));
                    m.put("name", rs.getString(2));
                    m.put("price_cents", rs.getLong(3));
                    m.put("available", rs.getLong(4));
                    return Json.write(m);
                }
            }
        });
    }

    /** Drops cached documents after their stock changed. Failures are counted, not thrown: the TTL bounds staleness. */
    public void invalidate(Collection<String> skus) {
        if (!cacheEnabled || skus.isEmpty()) return;
        try {
            redis.del(skus.stream().map(ProductService::cacheKey).toArray(String[]::new));
        } catch (RedisException e) {
            metrics.inc("cache_errors_total");
        }
    }

    public Map<String, Object> create(Map<String, Object> body) {
        String sku = str(body, "sku");
        checkSku(sku);
        String name = str(body, "name");
        if (name.isBlank() || name.length() > 200) throw ApiError.badRequest("name must be 1-200 characters");
        long price = nonNegative(body, "price_cents");
        long stock = nonNegative(body, "stock");
        db.tx(c -> {
            try (PreparedStatement p = c.prepareStatement(
                    "INSERT INTO products (sku, name, price_cents) VALUES (?, ?, ?) ON CONFLICT (sku) DO NOTHING")) {
                p.setString(1, sku);
                p.setString(2, name);
                p.setLong(3, price);
                if (p.executeUpdate() == 0) throw new ApiError(409, "already_exists", "product " + sku + " exists");
            }
            try (PreparedStatement p = c.prepareStatement(
                    "INSERT INTO inventory (sku, available, total) VALUES (?, ?, ?)")) {
                p.setString(1, sku);
                p.setLong(2, stock);
                p.setLong(3, stock);
                p.executeUpdate();
            }
            return null;
        });
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sku", sku);
        m.put("name", name);
        m.put("price_cents", price);
        m.put("available", stock);
        return m;
    }

    public void restock(String sku, Map<String, Object> body) {
        checkSku(sku);
        long qty = nonNegative(body, "qty");
        if (qty == 0) throw ApiError.badRequest("qty must be positive");
        int n = db.tx(c -> {
            try (PreparedStatement p = c.prepareStatement(
                    "UPDATE inventory SET available = available + ?, total = total + ? WHERE sku = ?")) {
                p.setLong(1, qty);
                p.setLong(2, qty);
                p.setString(3, sku);
                return p.executeUpdate();
            }
        });
        if (n == 0) throw ApiError.notFound("no product " + sku);
        invalidate(java.util.List.of(sku));
    }

    static String str(Map<String, Object> b, String k) {
        if (!(b.get(k) instanceof String s)) throw ApiError.badRequest(k + " must be a string");
        return s;
    }

    static long nonNegative(Map<String, Object> b, String k) {
        if (!(b.get(k) instanceof Long v) || v < 0) throw ApiError.badRequest(k + " must be a non-negative integer");
        return v;
    }
}
