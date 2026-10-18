package com.mrgregles.bsp_inventorysync.gametest;

import com.mrgregles.bsp_inventorysync.storage.Resp;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A tiny Redis stand-in for GameTests, on a real local socket (Region Guard's, with keys): AUTH, PING,
 * GET, SET (PX, NX), DEL, EXISTS, SADD, SREM, SMEMBERS in RESP2, binary safe, with expiry. {@link #stop()}
 * and {@link #restart()} play an outage on the same port. Bound to 127.0.0.1 only.
 */
public final class FakeRedis implements AutoCloseable {
    private record Value(byte[] data, long expiresAt) {
        boolean live(long now) {
            return expiresAt == 0 || expiresAt > now;
        }
    }

    private final String password;
    private final int port;
    private volatile ServerSocket listener;
    private final Set<Client> clients = ConcurrentHashMap.newKeySet();
    private final Map<String, Value> values = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> sets = new ConcurrentHashMap<>();

    private FakeRedis(String password, int port) throws IOException {
        this.password = password;
        this.port = port;
        listen();
    }

    public static FakeRedis start(String password) throws IOException {
        int port;
        try (ServerSocket probe = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            port = probe.getLocalPort();
        }
        return new FakeRedis(password, port);
    }

    public int port() {
        return port;
    }

    /** A stored value as text, or null; for assertions. */
    public String get(String key) {
        Value v = values.get(key);
        return v == null || !v.live(System.currentTimeMillis()) ? null : new String(v.data(), StandardCharsets.UTF_8);
    }

    public Set<String> members(String key) {
        return sets.getOrDefault(key, Set.of());
    }

    public int keys() {
        return values.size();
    }

    public void stop() {
        ServerSocket l = listener;
        listener = null;
        if (l != null) {
            try {
                l.close();
            } catch (IOException ignored) {
                // stopping anyway
            }
        }
        for (Client c : clients) {
            c.close();
        }
        clients.clear();
    }

    public void restart() throws IOException {
        if (listener == null) {
            listen();
        }
    }

    @Override
    public void close() {
        stop();
    }

    private void listen() throws IOException {
        ServerSocket l = new ServerSocket();
        l.setReuseAddress(true);
        l.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
        listener = l;
        Thread t = new Thread(() -> {
            while (listener == l) {
                try {
                    Socket s = l.accept();
                    Client c = new Client(s);
                    clients.add(c);
                    Thread ct = new Thread(c::serve, "fake-redis-client");
                    ct.setDaemon(true);
                    ct.start();
                } catch (IOException e) {
                    return;
                }
            }
        }, "fake-redis-accept");
        t.setDaemon(true);
        t.start();
    }

    private final class Client {
        private final Socket socket;
        private final OutputStream out;
        private boolean authed = password.isEmpty();

        Client(Socket socket) throws IOException {
            this.socket = socket;
            this.out = new BufferedOutputStream(socket.getOutputStream());
        }

        private static String text(Object o) {
            return Resp.text(o);
        }

        void serve() {
            try (socket) {
                InputStream in = new BufferedInputStream(socket.getInputStream());
                while (true) {
                    if (!(Resp.read(in) instanceof List<?> args) || args.isEmpty()) {
                        reply("-ERR protocol\r\n");
                        continue;
                    }
                    String cmd = text(args.get(0)).toUpperCase(Locale.ROOT);
                    long now = System.currentTimeMillis();
                    if (cmd.equals("AUTH")) {
                        authed = text(args.get(args.size() - 1)).equals(password);
                        reply(authed ? "+OK\r\n" : "-WRONGPASS invalid username-password pair or user is disabled.\r\n");
                    } else if (!authed) {
                        reply("-NOAUTH Authentication required.\r\n");
                    } else if (cmd.equals("PING")) {
                        reply("+PONG\r\n");
                    } else if (cmd.equals("GET") && args.size() == 2) {
                        Value v = values.get(text(args.get(1)));
                        if (v == null || !v.live(now)) {
                            values.remove(text(args.get(1)));
                            reply("$-1\r\n");
                        } else {
                            bulk(v.data());
                        }
                    } else if (cmd.equals("SET") && args.size() >= 3) {
                        String key = text(args.get(1));
                        long expiresAt = 0;
                        boolean nx = false;
                        for (int i = 3; i < args.size(); i++) {
                            String opt = text(args.get(i)).toUpperCase(Locale.ROOT);
                            if (opt.equals("PX") && i + 1 < args.size()) {
                                expiresAt = now + Long.parseLong(text(args.get(++i)));
                            } else if (opt.equals("EX") && i + 1 < args.size()) {
                                expiresAt = now + 1000L * Long.parseLong(text(args.get(++i)));
                            } else if (opt.equals("NX")) {
                                nx = true;
                            }
                        }
                        Value old = values.get(key);
                        if (nx && old != null && old.live(now)) {
                            reply("$-1\r\n");
                        } else {
                            values.put(key, new Value((byte[]) args.get(2), expiresAt));
                            reply("+OK\r\n");
                        }
                    } else if (cmd.equals("DEL") && args.size() >= 2) {
                        long n = 0;
                        for (int i = 1; i < args.size(); i++) {
                            String k = text(args.get(i));
                            if (values.remove(k) != null || sets.remove(k) != null) {
                                n++;
                            }
                        }
                        reply(":" + n + "\r\n");
                    } else if (cmd.equals("EXISTS") && args.size() == 2) {
                        Value v = values.get(text(args.get(1)));
                        reply(":" + (v != null && v.live(now) ? 1 : 0) + "\r\n");
                    } else if (cmd.equals("SADD") && args.size() == 3) {
                        reply(":" + (sets.computeIfAbsent(text(args.get(1)), k -> ConcurrentHashMap.newKeySet()).add(text(args.get(2))) ? 1 : 0) + "\r\n");
                    } else if (cmd.equals("SREM") && args.size() == 3) {
                        Set<String> set = sets.get(text(args.get(1)));
                        reply(":" + (set != null && set.remove(text(args.get(2))) ? 1 : 0) + "\r\n");
                    } else if (cmd.equals("SMEMBERS") && args.size() == 2) {
                        List<String> members = new ArrayList<>(sets.getOrDefault(text(args.get(1)), Set.of()));
                        array(members);
                    } else {
                        reply("-ERR unknown command '" + cmd + "'\r\n");
                    }
                }
            } catch (IOException | RuntimeException e) {
                // the client went away or we were stopped
            } finally {
                clients.remove(this);
            }
        }

        private synchronized void reply(String raw) throws IOException {
            out.write(raw.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        private synchronized void bulk(byte[] data) throws IOException {
            Resp.bulk(out, data);
            out.flush();
        }

        private synchronized void array(List<String> parts) throws IOException {
            out.write(("*" + parts.size() + "\r\n").getBytes(StandardCharsets.US_ASCII));
            for (String p : parts) {
                Resp.bulk(out, Resp.bytes(p));
            }
            out.flush();
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
    }
}
