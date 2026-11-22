package com.stockpulse.redis;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * One RESP2 connection. Not thread-safe: use through {@link RedisPool}.
 *
 * Reply mapping: simple string and bulk string to {@code String} (nil bulk to null),
 * integer to {@code Long}, array to {@code List<Object>} (nil array to null),
 * error reply to {@link RedisException}.
 */
public final class RedisConnection implements AutoCloseable {
    private final Socket socket;
    private final OutputStream out;
    private final InputStream in;
    private boolean broken;

    public RedisConnection(String host, int port, int timeoutMs) { this(host, port, 0, timeoutMs); }

    public RedisConnection(String host, int port, int database, int timeoutMs) {
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            socket.setTcpNoDelay(true);
            out = new BufferedOutputStream(socket.getOutputStream(), 8192);
            in = new BufferedInputStream(socket.getInputStream(), 8192);
        } catch (IOException e) {
            throw new RedisException("cannot connect to redis " + host + ":" + port, e);
        }
        if (database != 0) call("SELECT", Integer.toString(database));
    }

    public boolean isBroken() { return broken; }

    /** Socket read timeout for the next commands; 0 waits forever (for blocking reads). */
    public void setReadTimeout(int ms) {
        try { socket.setSoTimeout(ms); } catch (IOException e) { broken = true; throw new RedisException("socket", e); }
    }

    public Object call(String... args) {
        try {
            writeCommand(args);
            out.flush();
            return read();
        } catch (IOException e) {
            broken = true;
            throw new RedisException("redis i/o failure: " + e.getMessage(), e);
        }
    }

    private void writeCommand(String... args) throws IOException {
        out.write(('*' + Integer.toString(args.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
        for (String a : args) {
            byte[] b = a.getBytes(StandardCharsets.UTF_8);
            out.write(('$' + Integer.toString(b.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(b);
            out.write('\r');
            out.write('\n');
        }
    }

    private Object read() throws IOException {
        int type = in.read();
        if (type < 0) throw new EOFException("connection closed");
        String line = readLine();
        switch (type) {
            case '+': return line;
            case '-': throw new RedisException(line);
            case ':': return Long.parseLong(line);
            case '$': {
                int n = Integer.parseInt(line);
                if (n < 0) return null;
                byte[] b = in.readNBytes(n);
                if (b.length != n) throw new EOFException("short bulk reply");
                readLine();
                return new String(b, StandardCharsets.UTF_8);
            }
            case '*': {
                int n = Integer.parseInt(line);
                if (n < 0) return null;
                List<Object> list = new ArrayList<>(n);
                for (int k = 0; k < n; k++) {
                    try {
                        list.add(read());
                    } catch (RedisException e) {
                        list.add(e); // error inside an array (e.g. EXEC) - keep reading
                    }
                }
                return list;
            }
            default:
                broken = true;
                throw new IOException("bad RESP type byte " + type);
        }
    }

    private String readLine() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(32);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\r') {
                int n = in.read();
                if (n != '\n') throw new IOException("expected LF");
                return b.toString(StandardCharsets.UTF_8);
            }
            b.write(c);
        }
        throw new EOFException("connection closed mid-line");
    }

    @Override
    public void close() {
        try { socket.close(); } catch (IOException ignored) { }
    }
}
