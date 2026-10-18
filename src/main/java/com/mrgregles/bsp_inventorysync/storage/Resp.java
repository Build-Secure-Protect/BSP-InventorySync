package com.mrgregles.bsp_inventorysync.storage;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The Redis wire format (RESP2), as much as this mod needs, binary safe: commands go out as arrays of
 * bulk strings; replies come back as {@code String} (simple strings), {@link Error}, {@code Long},
 * {@code byte[]} (bulk strings; {@code null} when absent) and {@code List} of these.
 * Region Guard's codec with byte arrays instead of strings for the bulk values, so snapshots travel as they are.
 */
public final class Resp {
    /** An error reply, such as {@code WRONGPASS ...} or {@code NOAUTH ...}. */
    public record Error(String message) {}

    private static final int MAX_BULK = 64 * 1024 * 1024;

    private Resp() {}

    public static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** A bulk reply as text; null stays null. */
    public static String text(Object reply) {
        if (reply instanceof byte[] b) {
            return new String(b, StandardCharsets.UTF_8);
        }
        return reply == null ? null : reply.toString();
    }

    /** Writes one command and flushes. */
    public static void command(OutputStream out, byte[]... args) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.write(("*" + args.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
        for (byte[] arg : args) {
            bulk(buf, arg);
        }
        out.write(buf.toByteArray());
        out.flush();
    }

    public static void bulk(OutputStream out, byte[] value) throws IOException {
        out.write(("$" + value.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(value);
        out.write('\r');
        out.write('\n');
    }

    public static Object read(InputStream in) throws IOException {
        return value(in, next(in));
    }

    public static Object readAfter(InputStream in, int type) throws IOException {
        return value(in, type);
    }

    private static Object value(InputStream in, int type) throws IOException {
        switch (type) {
            case '+':
                return line(in);
            case '-':
                return new Error(line(in));
            case ':':
                return Long.parseLong(line(in));
            case '$': {
                int n = Integer.parseInt(line(in));
                if (n < 0) {
                    return null;
                }
                if (n > MAX_BULK) {
                    throw new IOException("Redis reply too large: " + n + " bytes");
                }
                byte[] data = in.readNBytes(n);
                if (data.length < n) {
                    throw new EOFException("Redis closed the connection");
                }
                next(in);
                next(in); // \r\n
                return data;
            }
            case '*': {
                int n = Integer.parseInt(line(in));
                if (n < 0) {
                    return null;
                }
                List<Object> list = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    list.add(read(in));
                }
                return list;
            }
            default:
                throw new IOException("Not a Redis reply (first byte " + type + ")");
        }
    }

    public static int next(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new EOFException("Redis closed the connection");
        }
        return b;
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = next(in)) != '\r') {
            buf.write(b);
        }
        next(in); // \n
        return buf.toString(StandardCharsets.UTF_8);
    }
}
