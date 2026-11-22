package com.stockpulse.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Small JDBC connection pool plus a transaction helper.
 *
 * The pool size is the real concurrency limit for database work: with virtual threads
 * the server can accept thousands of requests, and this semaphore is what stops them
 * from opening thousands of PostgreSQL backends.
 */
public final class Db implements AutoCloseable {
    @FunctionalInterface
    public interface Work<T> { T run(Connection c) throws SQLException; }

    /** Thrown when the pool cannot hand out a connection within the acquire timeout. */
    public static final class PoolTimeout extends RuntimeException {
        PoolTimeout() { super("database pool exhausted"); }
    }

    private final String url, user, password;
    private final int acquireTimeoutMs;
    private final BlockingQueue<Connection> idle;
    private final Semaphore permits;

    public Db(String url, String user, String password, int size, int acquireTimeoutMs) {
        this.url = url;
        this.user = user;
        this.password = password;
        this.acquireTimeoutMs = acquireTimeoutMs;
        this.idle = new ArrayBlockingQueue<>(size);
        this.permits = new Semaphore(size, true);
    }

    private Connection open() throws SQLException {
        Connection c = DriverManager.getConnection(url, user, password);
        c.setAutoCommit(false);
        return c;
    }

    /** Runs {@code work} in one transaction; commits on success, rolls back on any exception. */
    public <T> T tx(Work<T> work) {
        try {
            if (!permits.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS)) throw new PoolTimeout();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        Connection c = null;
        boolean healthy = true;
        try {
            c = idle.poll();
            if (c == null) c = open();
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (Throwable t) {
                try { c.rollback(); } catch (SQLException re) { healthy = false; }
                throw t;
            }
        } catch (SQLException e) {
            healthy = healthy && !isConnectionError(e);
            throw new DbException(e);
        } finally {
            if (c != null) {
                if (!healthy || !idle.offer(c)) closeQuietly(c);
            }
            permits.release();
        }
    }

    private static boolean isConnectionError(SQLException e) {
        String state = e.getSQLState();
        return state != null && state.startsWith("08"); // connection exception class
    }

    private static void closeQuietly(Connection c) {
        try { c.close(); } catch (SQLException ignored) { }
    }

    @Override
    public void close() {
        Connection c;
        while ((c = idle.poll()) != null) closeQuietly(c);
    }

    /** Unchecked wrapper so lambdas stay readable; keeps SQLState for callers that branch on it. */
    public static final class DbException extends RuntimeException {
        public final String sqlState;
        public DbException(SQLException e) {
            super(e.getMessage(), e);
            this.sqlState = e.getSQLState();
        }
    }
}
