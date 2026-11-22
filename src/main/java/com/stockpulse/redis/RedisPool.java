package com.stockpulse.redis;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Fixed-size pool of {@link RedisConnection}s. Broken connections are replaced, never reused. */
public final class RedisPool implements AutoCloseable {
    private final String host;
    private final int port;
    private final int database;
    private final int timeoutMs;
    private final BlockingQueue<RedisConnection> idle;
    private final java.util.concurrent.Semaphore permits;

    public RedisPool(String host, int port, int size, int timeoutMs) { this(host, port, 0, size, timeoutMs); }

    public RedisPool(String host, int port, int database, int size, int timeoutMs) {
        this.host = host;
        this.port = port;
        this.database = database;
        this.timeoutMs = timeoutMs;
        this.idle = new ArrayBlockingQueue<>(size);
        this.permits = new java.util.concurrent.Semaphore(size, true);
    }

    public RedisConnection newConnection() { return new RedisConnection(host, port, database, timeoutMs); }

    public <T> T with(Function<RedisConnection, T> fn) {
        try {
            if (!permits.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) throw new RedisException("redis pool exhausted");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RedisException("interrupted", e);
        }
        RedisConnection c = null;
        try {
            c = idle.poll();
            if (c == null) c = newConnection();
            return fn.apply(c);
        } finally {
            if (c != null) {
                if (c.isBroken() || !idle.offer(c)) c.close();
            }
            permits.release();
        }
    }

    public Object call(String... args) { return with(c -> c.call(args)); }

    public String get(String key) { return (String) call("GET", key); }

    public void setex(String key, int seconds, String value) { call("SET", key, value, "EX", Integer.toString(seconds)); }

    public long del(String... keys) {
        String[] a = new String[keys.length + 1];
        a[0] = "DEL";
        System.arraycopy(keys, 0, a, 1, keys.length);
        return (Long) call(a);
    }

    /** EVALSHA with transparent fallback to EVAL when the script is not cached on the server. */
    public Object eval(String sha, String script, List<String> keys, List<String> args) {
        String[] a = new String[3 + keys.size() + args.size()];
        a[0] = "EVALSHA";
        a[1] = sha;
        a[2] = Integer.toString(keys.size());
        int k = 3;
        for (String s : keys) a[k++] = s;
        for (String s : args) a[k++] = s;
        try {
            return call(a);
        } catch (RedisException e) {
            if (e.getMessage() == null || !e.getMessage().startsWith("NOSCRIPT")) throw e;
            a[0] = "EVAL";
            a[1] = script;
            return call(a);
        }
    }

    @Override
    public void close() {
        RedisConnection c;
        while ((c = idle.poll()) != null) c.close();
    }
}
