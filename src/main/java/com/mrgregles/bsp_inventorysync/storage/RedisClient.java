package com.mrgregles.bsp_inventorysync.storage;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This mod's own small Redis client (D6, Region Guard's D34 pattern): one connection, one daemon
 * thread, request and reply, AUTH, PING keep-alive, over a plain socket (Redis stays on the local
 * network). Nothing is bundled, so nothing can clash with Mohist's libraries or another mod's client.
 *
 * <p>Commands queue up while the first connection is being made; once the link has been down, they
 * fail at once until it is back, so callers fall back to MySQL without waiting. The thread reconnects
 * by itself, waiting longer after each failure (half a second, doubling, at most 30 s).
 */
public final class RedisClient implements AutoCloseable {
    private static final int CONNECT_TIMEOUT_MS = 3000, READ_TIMEOUT_MS = 15_000, MAX_QUEUE = 10_000;
    private static final long FIRST_RETRY_MS = 500, MAX_RETRY_MS = 30_000, KEEPALIVE_MS = 30_000, CLOSE_WAIT_MS = 2000;

    public record Settings(String host, int port, String username, String password) {
        public String describe() {
            return "redis " + host + ":" + port;
        }
    }

    private record Pending(byte[][] args, CompletableFuture<Object> future) {}

    private final Settings settings;
    private final LinkedBlockingQueue<Pending> queue = new LinkedBlockingQueue<>(MAX_QUEUE);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread worker;
    private volatile boolean connected;
    /** True from the first failed connection or lost link until the next successful connect: commands fail at once then. */
    private volatile boolean down;
    private volatile Socket socket;
    private volatile String lastError = "";
    private volatile long sent, failed;

    public RedisClient(Settings settings) {
        this.settings = settings;
        worker = new Thread(this::loop, "bsp-inventorysync-redis");
        worker.setDaemon(true);
        worker.start();
    }

    public boolean connected() {
        return connected;
    }

    public String status() {
        return settings.describe() + ": " + (connected ? "connected" : "down") + "; sent " + sent + ", failed " + failed
                + (lastError.isEmpty() ? "" : " (last error: " + lastError + ")");
    }

    /** Sends one command; the reply is a {@code String}, {@code Long}, {@code byte[]}, {@code null} or {@code List}; an error reply fails the future. */
    public CompletableFuture<Object> command(byte[]... args) {
        CompletableFuture<Object> future = new CompletableFuture<>();
        if (!running.get()) {
            future.completeExceptionally(new IOException("Redis client closed"));
            return future;
        }
        if (down) {
            failed++;
            future.completeExceptionally(new IOException("Redis is down" + (lastError.isEmpty() ? "" : ": " + lastError)));
            return future;
        }
        if (!queue.offer(new Pending(args, future))) {
            failed++;
            future.completeExceptionally(new IOException("Redis queue full"));
        }
        return future;
    }

    public CompletableFuture<Object> command(String... args) {
        byte[][] raw = new byte[args.length][];
        for (int i = 0; i < args.length; i++) {
            raw[i] = Resp.bytes(args[i]);
        }
        return command(raw);
    }

    // ------------------------------------------------------------------ helpers

    /** The value, or null when the key is absent. */
    public CompletableFuture<byte[]> get(String key) {
        return command("GET", key).thenApply(r -> (byte[]) r);
    }

    /** SET, with a time to live in milliseconds when {@code pxMs} is positive. */
    public CompletableFuture<Void> set(String key, byte[] value, long pxMs) {
        CompletableFuture<Object> f = pxMs > 0
                ? command(Resp.bytes("SET"), Resp.bytes(key), value, Resp.bytes("PX"), Resp.bytes(Long.toString(pxMs)))
                : command(Resp.bytes("SET"), Resp.bytes(key), value);
        return f.thenApply(r -> null);
    }

    public CompletableFuture<Long> del(String... keys) {
        String[] args = new String[keys.length + 1];
        args[0] = "DEL";
        System.arraycopy(keys, 0, args, 1, keys.length);
        return command(args).thenApply(r -> (Long) r);
    }

    public CompletableFuture<Long> sadd(String key, String member) {
        return command("SADD", key, member).thenApply(r -> (Long) r);
    }

    public CompletableFuture<Long> srem(String key, String member) {
        return command("SREM", key, member).thenApply(r -> (Long) r);
    }

    public CompletableFuture<List<String>> smembers(String key) {
        return command("SMEMBERS", key).thenApply(r -> {
            List<String> out = new ArrayList<>();
            if (r instanceof List<?> list) {
                for (Object o : list) {
                    out.add(Resp.text(o));
                }
            }
            return out;
        });
    }

    public CompletableFuture<Boolean> ping() {
        return command("PING").thenApply(r -> "PONG".equals(Resp.text(r)));
    }

    /** Sends what is still queued (up to two seconds), then closes. */
    @Override
    public void close() {
        running.set(false);
        try {
            worker.join(CLOSE_WAIT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
        worker.interrupt();
        failAll(new IOException("Redis client closed"));
    }

    // ------------------------------------------------------------------ the connection

    private Socket connect() throws IOException {
        Socket s = new Socket();
        try {
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
            s.connect(new InetSocketAddress(settings.host(), settings.port()), CONNECT_TIMEOUT_MS);
            s.setSoTimeout(READ_TIMEOUT_MS);
            if (!settings.password().isEmpty()) {
                OutputStream out = s.getOutputStream();
                if (settings.username().isEmpty()) {
                    Resp.command(out, Resp.bytes("AUTH"), Resp.bytes(settings.password()));
                } else {
                    Resp.command(out, Resp.bytes("AUTH"), Resp.bytes(settings.username()), Resp.bytes(settings.password()));
                }
                if (Resp.read(s.getInputStream()) instanceof Resp.Error e) {
                    throw new IOException("Redis refused the login: " + e.message());
                }
            }
            return s;
        } catch (IOException | RuntimeException e) {
            s.close();
            throw e;
        }
    }

    private void failAll(IOException why) {
        List<Pending> drained = new ArrayList<>();
        queue.drainTo(drained);
        for (Pending p : drained) {
            failed++;
            p.future().completeExceptionally(why);
        }
    }

    private void loop() {
        long retry = FIRST_RETRY_MS;
        boolean everConnected = false;
        while (running.get()) {
            try (Socket s = connect()) {
                socket = s;
                InputStream in = new BufferedInputStream(s.getInputStream());
                OutputStream out = new BufferedOutputStream(s.getOutputStream());
                connected = true;
                down = false;
                retry = FIRST_RETRY_MS;
                if (!everConnected || !lastError.isEmpty()) {
                    BSPInventorySync.LOGGER.info("BSP-InventorySync: {} {}", settings.describe(), everConnected ? "connected again" : "connected");
                    everConnected = true;
                    lastError = "";
                }
                long lastUse = System.currentTimeMillis();
                while (running.get() || !queue.isEmpty()) {
                    Pending p = queue.poll(1, TimeUnit.SECONDS);
                    if (p == null) {
                        if (!running.get()) {
                            return;
                        }
                        if (System.currentTimeMillis() - lastUse >= KEEPALIVE_MS) {
                            Resp.command(out, Resp.bytes("PING"));
                            Resp.read(in);
                            lastUse = System.currentTimeMillis();
                        }
                        continue;
                    }
                    Object reply;
                    try {
                        Resp.command(out, p.args());
                        reply = Resp.read(in);
                    } catch (IOException e) {
                        p.future().completeExceptionally(e);
                        failed++;
                        throw e;
                    }
                    lastUse = System.currentTimeMillis();
                    if (reply instanceof Resp.Error e) {
                        failed++;
                        p.future().completeExceptionally(new IOException(e.message()));
                    } else {
                        sent++;
                        p.future().complete(reply);
                    }
                }
            } catch (InterruptedException e) {
                return;
            } catch (IOException | RuntimeException e) {
                String message = e.getMessage() == null ? e.toString() : e.getMessage();
                if (!message.equals(lastError) && running.get()) {
                    BSPInventorySync.LOGGER.warn("BSP-InventorySync: {} down ({}); MySQL carries everything meanwhile", settings.describe(), message);
                }
                lastError = message;
            } finally {
                connected = false;
                down = true;
                socket = null;
            }
            failAll(new IOException("Redis is down: " + lastError));
            try {
                Thread.sleep(retry);
            } catch (InterruptedException e) {
                return;
            }
            retry = Math.min(retry * 2, MAX_RETRY_MS);
        }
    }
}
