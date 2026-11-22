package com.stockpulse;

import java.util.Map;

/** Settings from environment variables, with defaults for a local run. */
public record Config(
        String mode, String host, int port,
        String dbUrl, String dbUser, String dbPassword, int dbPool,
        String redisHost, int redisPort, int redisDb, int redisPool,
        boolean cacheEnabled, int cacheTtlSeconds,
        int rateCapacity, double rateRefillPerSecond,
        int workerThreads, long claimIdleMs, int maxDeliveries,
        int paymentLatencyMs, int paymentDeclinePercent,
        String adminToken) {

    public static Config fromEnv(Map<String, String> env) {
        return new Config(
                env.getOrDefault("MODE", "all"),
                env.getOrDefault("HOST", "0.0.0.0"),
                i(env, "PORT", 8080),
                env.getOrDefault("DB_URL", "jdbc:postgresql://127.0.0.1:5432/stockpulse"),
                env.getOrDefault("DB_USER", "sp"),
                env.getOrDefault("DB_PASSWORD", "sp"),
                i(env, "DB_POOL", 16),
                env.getOrDefault("REDIS_HOST", "127.0.0.1"),
                i(env, "REDIS_PORT", 6379),
                i(env, "REDIS_DB", 0),
                i(env, "REDIS_POOL", 32),
                Boolean.parseBoolean(env.getOrDefault("CACHE_ENABLED", "true")),
                i(env, "CACHE_TTL_SECONDS", 5),
                i(env, "RATE_CAPACITY", 100),
                Double.parseDouble(env.getOrDefault("RATE_REFILL_PER_SECOND", "50")),
                i(env, "WORKER_THREADS", 4),
                Long.parseLong(env.getOrDefault("CLAIM_IDLE_MS", "5000")),
                i(env, "MAX_DELIVERIES", 5),
                i(env, "PAYMENT_LATENCY_MS", 20),
                i(env, "PAYMENT_DECLINE_PERCENT", 10),
                env.get("ADMIN_TOKEN"));
    }

    private static int i(Map<String, String> env, String k, int d) {
        String v = env.get(k);
        return v == null ? d : Integer.parseInt(v);
    }

    /** {@code http}: HTTP only, no background relay - for tests and benchmarks that drive the relay by hand. */
    public boolean runsApi() { return mode.equals("all") || mode.equals("api") || mode.equals("http"); }
    public boolean runsWorker() { return mode.equals("all") || mode.equals("worker"); }
    public boolean runsRelay() { return mode.equals("all") || mode.equals("api") || mode.equals("relay"); }
}
