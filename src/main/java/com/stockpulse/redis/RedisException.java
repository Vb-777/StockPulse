package com.stockpulse.redis;

/** An error reply from Redis (e.g. WRONGTYPE, NOSCRIPT) or a broken connection. */
public class RedisException extends RuntimeException {
    public RedisException(String msg) { super(msg); }
    public RedisException(String msg, Throwable cause) { super(msg, cause); }
}
