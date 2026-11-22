package com.stockpulse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.stockpulse.metrics.Metrics;
import com.stockpulse.ratelimit.RateLimiter;
import com.stockpulse.redis.RedisPool;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RateLimitTest {
    App app;

    @AfterEach
    void down() { if (app != null) app.close(); }

    @Test
    void bucketEmptiesThenRefills() throws Exception {
        TestEnv.reset();
        try (RedisPool redis = new RedisPool(TestEnv.REDIS_HOST, TestEnv.REDIS_PORT, TestEnv.REDIS_DB, 4, 2000)) {
            RateLimiter rl = new RateLimiter(redis, new Metrics(), 5, 10);
            for (int i = 0; i < 5; i++) assertTrue(rl.check("a").allowed(), "request " + i);
            RateLimiter.Decision d = rl.check("a");
            assertFalse(d.allowed());
            assertTrue(d.retryAfterMs() > 0 && d.retryAfterMs() <= 100, "retry " + d.retryAfterMs());
            assertTrue(rl.check("b").allowed(), "other clients have their own bucket");
            Thread.sleep(250); // 10 tokens/s -> at least 2 tokens back
            assertTrue(rl.check("a").allowed());
        }
    }

    @Test
    void apiReturns429WithRetryAfter() throws Exception {
        TestEnv.reset();
        app = new App(TestEnv.config(Map.of("RATE_CAPACITY", "3", "RATE_REFILL_PER_SECOND", "0.5"))).start();
        TestEnv.Client http = new TestEnv.Client(app.port());
        int limited = 0;
        HttpResponse<String> last = null;
        for (int i = 0; i < 5; i++) {
            last = http.get("/orders/00000000-0000-0000-0000-000000000000", "X-Client-Id", "greedy");
            if (last.statusCode() == 429) limited++;
        }
        assertEquals(2, limited);
        assertEquals("2", last.headers().firstValue("Retry-After").orElse(""));
    }

    @Test
    void failsOpenWhenRedisIsDown() {
        Metrics m = new Metrics();
        try (RedisPool dead = new RedisPool("127.0.0.1", 1, 2, 300)) {
            RateLimiter rl = new RateLimiter(dead, m, 1, 1);
            assertTrue(rl.check("a").allowed());
            assertTrue(rl.check("a").allowed());
        }
        assertEquals(2L, m.count("ratelimit_fail_open_total"));
    }
}
