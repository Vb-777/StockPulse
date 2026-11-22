package com.stockpulse.ratelimit;

import com.stockpulse.metrics.Metrics;
import com.stockpulse.redis.RedisException;
import com.stockpulse.redis.RedisPool;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/**
 * Per-client token bucket held in Redis, so every API instance enforces one shared limit.
 *
 * If Redis is unreachable the limiter fails open (the request is allowed and counted in
 * {@code ratelimit_fail_open_total}): losing rate limiting briefly is better than losing the API.
 */
public final class RateLimiter {
    public record Decision(boolean allowed, long remaining, long retryAfterMs) { }

    private final RedisPool redis;
    private final Metrics metrics;
    private final int capacity;
    private final double refillPerSecond;
    private final String script;
    private final String sha;

    public RateLimiter(RedisPool redis, Metrics metrics, int capacity, double refillPerSecond) {
        this.redis = redis;
        this.metrics = metrics;
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.script = load("/ratelimit.lua");
        this.sha = sha1(script);
    }

    public int capacity() { return capacity; }

    public Decision check(String clientId) {
        try {
            @SuppressWarnings("unchecked")
            List<Object> r = (List<Object>) redis.eval(sha, script, List.of("rl:" + clientId),
                    List.of(Integer.toString(capacity), Double.toString(refillPerSecond), "1"));
            boolean ok = ((Long) r.get(0)) == 1L;
            if (!ok) metrics.inc("ratelimit_rejected_total");
            return new Decision(ok, (Long) r.get(1), (Long) r.get(2));
        } catch (RedisException e) {
            metrics.inc("ratelimit_fail_open_total");
            return new Decision(true, -1, 0);
        }
    }

    static String load(String resource) {
        try (InputStream in = RateLimiter.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("missing resource " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha1(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
