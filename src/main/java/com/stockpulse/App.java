package com.stockpulse;

import com.stockpulse.db.Db;
import com.stockpulse.http.ApiError;
import com.stockpulse.http.HttpApi;
import com.stockpulse.http.Request;
import com.stockpulse.http.Response;
import com.stockpulse.metrics.Metrics;
import com.stockpulse.order.OrderProcessor;
import com.stockpulse.order.OrderService;
import com.stockpulse.order.OrderWorker;
import com.stockpulse.order.OutboxRelay;
import com.stockpulse.order.PaymentGateway;
import com.stockpulse.product.ProductService;
import com.stockpulse.ratelimit.RateLimiter;
import com.stockpulse.redis.RedisPool;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Wires the service together. MODE selects the role of this process:
 * {@code api} (HTTP + outbox relay), {@code worker} (queue consumers), {@code relay}, {@code all},
 * or {@code http} (HTTP alone, for tests).
 */
public final class App implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(App.class.getName());

    public final Config cfg;
    public final Metrics metrics = new Metrics();
    public final Db db;
    public final RedisPool redis;
    public final ProductService products;
    public final OrderService orders;
    public final OrderProcessor processor;
    public final RateLimiter limiter;
    private final List<Runnable> stoppers = new ArrayList<>();
    private final List<Thread> threads = new ArrayList<>();
    private HttpApi http;
    private int port = -1;

    public App(Config cfg) {
        this.cfg = cfg;
        this.db = new Db(cfg.dbUrl(), cfg.dbUser(), cfg.dbPassword(), cfg.dbPool(), 5000);
        this.redis = new RedisPool(cfg.redisHost(), cfg.redisPort(), cfg.redisDb(), cfg.redisPool(), 2000);
        this.products = new ProductService(db, redis, metrics, cfg.cacheEnabled(), cfg.cacheTtlSeconds());
        this.orders = new OrderService(db, products, metrics);
        this.processor = new OrderProcessor(db,
                new PaymentGateway.Simulated(cfg.paymentLatencyMs(), cfg.paymentDeclinePercent()), products, metrics);
        this.limiter = new RateLimiter(redis, metrics, cfg.rateCapacity(), cfg.rateRefillPerSecond());
    }

    public void migrate() {
        String sql;
        try (InputStream in = App.class.getResourceAsStream("/schema.sql")) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        db.tx(c -> {
            try (Statement s = c.createStatement()) {
                // Serialise concurrent migrations from several instances starting at once.
                s.execute("SELECT pg_advisory_xact_lock(4242)");
                s.execute(sql);
            }
            return null;
        });
    }

    public App start() throws IOException {
        migrate();
        if (cfg.runsRelay()) {
            OutboxRelay relay = new OutboxRelay(db, redis, metrics, 200, 1_000_000);
            stoppers.add(relay::stop);
            threads.add(Thread.ofVirtual().name("outbox-relay").start(relay));
        }
        if (cfg.runsWorker()) {
            OrderWorker.ensureGroup(redis);
            String host = ProcessHandle.current().pid() + "";
            for (int i = 0; i < cfg.workerThreads(); i++) {
                OrderWorker w = new OrderWorker(redis, processor, metrics, "w-" + host + "-" + i,
                        cfg.claimIdleMs(), cfg.maxDeliveries());
                stoppers.add(w::stop);
                threads.add(Thread.ofVirtual().name("worker-" + i).start(w));
            }
        }
        if (cfg.runsApi()) {
            http = routes(new HttpApi(metrics));
            port = http.start(cfg.host(), cfg.port(), 1024);
            LOG.info("StockPulse API listening on " + cfg.host() + ":" + port + " mode=" + cfg.mode());
        }
        return this;
    }

    public int port() { return port; }

    HttpApi routes(HttpApi api) {
        return api
                .route("GET", "/health", r -> health())
                .route("GET", "/metrics", r -> Response.text(200, metrics.render()))
                .route("POST", "/products", r -> {
                    admin(r);
                    return Response.json(201, products.create(r.json()));
                })
                .route("POST", "/products/{sku}/restock", r -> {
                    admin(r);
                    products.restock(r.param("sku"), r.json());
                    return Response.rawJson(200, products.get(r.param("sku")));
                })
                .route("GET", "/products/{sku}", r -> Response.rawJson(200, products.get(r.param("sku"))))
                .route("POST", "/orders", r -> {
                    String client = r.header("X-Client-Id");
                    OrderService.checkClient(client);
                    limit(client);
                    OrderService.Result res = orders.place(client, r.header("Idempotency-Key"), r.json());
                    Response resp = Response.rawJson(res.status(), res.body());
                    if (res.replayed()) resp.header("Idempotent-Replayed", "true");
                    return resp;
                })
                .route("GET", "/orders/{id}", r -> {
                    String client = r.header("X-Client-Id");
                    OrderService.checkClient(client);
                    limit(client);
                    return Response.json(200, orders.get(client, r.param("id")));
                })
                .route("GET", "/stats/inventory", r -> {
                    admin(r);
                    return Response.json(200, inventoryStats());
                });
    }

    private void limit(String client) {
        RateLimiter.Decision d = limiter.check(client);
        if (!d.allowed()) {
            long secs = Math.max(1, (d.retryAfterMs() + 999) / 1000);
            throw new ApiError(429, "rate_limited", "too many requests",
                    Map.of("Retry-After", Long.toString(secs)));
        }
    }

    private void admin(Request r) {
        if (cfg.adminToken() == null) return;
        String got = r.header("X-Admin-Token");
        // Constant-time compare so the token cannot be guessed byte by byte from response timing.
        if (got == null || !MessageDigest.isEqual(got.getBytes(StandardCharsets.UTF_8),
                cfg.adminToken().getBytes(StandardCharsets.UTF_8)))
            throw new ApiError(403, "forbidden", "admin token required");
    }

    private Response health() {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean ok = true;
        try {
            db.tx(c -> { try (Statement s = c.createStatement()) { s.execute("SELECT 1"); } return null; });
            m.put("db", "up");
        } catch (RuntimeException e) {
            m.put("db", "down");
            ok = false;
        }
        try {
            redis.call("PING");
            m.put("redis", "up");
        } catch (RuntimeException e) {
            m.put("redis", "down");
            ok = false;
        }
        m.put("status", ok ? "ok" : "degraded");
        return Response.json(ok ? 200 : 503, m);
    }

    /** Conservation check: every unit is available, reserved or sold - never lost, never double-counted. */
    public Map<String, Object> inventoryStats() {
        return db.tx(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT coalesce(sum(available),0), coalesce(sum(reserved),0), "
                         + "coalesce(sum(sold),0), coalesce(sum(total),0), "
                         + "count(*) FILTER (WHERE available + reserved + sold <> total) FROM inventory")) {
                rs.next();
                m.put("available", rs.getLong(1));
                m.put("reserved", rs.getLong(2));
                m.put("sold", rs.getLong(3));
                m.put("total", rs.getLong(4));
                m.put("rows_violating_conservation", rs.getLong(5));
            }
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT status, count(*) FROM orders GROUP BY status ORDER BY status")) {
                Map<String, Object> st = new LinkedHashMap<>();
                while (rs.next()) st.put(rs.getString(1), rs.getLong(2));
                m.put("orders", st);
            }
            return m;
        });
    }

    @Override
    public void close() {
        if (http != null) http.stop(2);
        stoppers.forEach(Runnable::run);
        for (Thread t : threads) {
            try { t.join(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        redis.close();
        db.close();
    }

    public static void main(String[] args) throws Exception {
        // The JDBC driver sends the JVM zone to PostgreSQL on connect; run in UTC regardless of host settings.
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
        if (args.length > 0 && args[0].equals("loadgen")) {
            com.stockpulse.bench.LoadGen.main(java.util.Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        App app = new App(Config.fromEnv(System.getenv())).start();
        Runtime.getRuntime().addShutdownHook(new Thread(app::close, "shutdown"));
        Thread.currentThread().join();
    }
}
