package com.mrgregles.bsp_inventorysync.storage;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Redis in front of the database (D5, D6): the handshake's checkout and the newest snapshot of every
 * player live in Redis too, so a server switch never waits on MySQL. MySQL stays the source of truth
 * and the safety net: every write goes there; Redis is written after it, read first, and ignored
 * whenever it fails or holds nothing.
 *
 * <p>Keys, under {@code bsp_is:<cluster>:}: {@code presence:<uuid>} ({@code online|server|since}),
 * {@code held:<server>} (a set of the players a server holds, so it can release them all at start and
 * stop), {@code snap:<uuid>} (the newest snapshot with a header line and the blob, with a time to live).
 */
public final class FastStore implements SyncStore {
    private final SyncStore inner;
    private final RedisClient redis;
    private final String prefix;
    private final long snapshotTtlMs;

    public FastStore(SyncStore inner, RedisClient redis, String cluster, long snapshotTtlMs) {
        this.inner = inner;
        this.redis = redis;
        this.prefix = "bsp_is:" + cluster + ":";
        this.snapshotTtlMs = snapshotTtlMs;
    }

    public SyncStore inner() {
        return inner;
    }

    public RedisClient redis() {
        return redis;
    }

    public String presenceKey(UUID player) {
        return prefix + "presence:" + player;
    }

    public String heldKey(String serverId) {
        return prefix + "held:" + serverId;
    }

    public String snapshotKey(UUID player) {
        return prefix + "snap:" + player;
    }

    private static <T> CompletableFuture<T> quiet(CompletableFuture<T> f) {
        return f.exceptionally(t -> null); // Redis failures are logged by the client; the database carries on
    }

    // ------------------------------------------------------------------ snapshots

    static byte[] encode(Snapshot s) {
        String header = s.version() + "\n" + s.serverId() + "\n" + s.savedAt() + "\n" + s.cause().id() + "\n" + (s.pinned() ? 1 : 0) + "\n";
        byte[] h = header.getBytes(StandardCharsets.UTF_8);
        byte[] data = s.data();
        ByteArrayOutputStream out = new ByteArrayOutputStream(h.length + data.length);
        out.writeBytes(h);
        out.writeBytes(data);
        return out.toByteArray();
    }

    static Optional<Snapshot> decode(UUID player, byte[] raw) {
        if (raw == null) {
            return Optional.empty();
        }
        try {
            int pos = 0;
            String[] fields = new String[5];
            for (int i = 0; i < 5; i++) {
                int nl = pos;
                while (nl < raw.length && raw[nl] != '\n') {
                    nl++;
                }
                if (nl >= raw.length) {
                    return Optional.empty();
                }
                fields[i] = new String(raw, pos, nl - pos, StandardCharsets.UTF_8);
                pos = nl + 1;
            }
            byte[] data = new byte[raw.length - pos];
            System.arraycopy(raw, pos, data, 0, data.length);
            return Optional.of(new Snapshot(player, Long.parseLong(fields[0]), fields[1], Long.parseLong(fields[2]), SaveCause.byId(fields[3]), "1".equals(fields[4]), data));
        } catch (RuntimeException e) {
            BSPInventorySync.LOGGER.warn("BSP-InventorySync: a cached snapshot of {} could not be read; using the database", player);
            return Optional.empty();
        }
    }

    @Override
    public CompletableFuture<Snapshot> save(UUID player, String serverId, SaveCause cause, byte[] data) {
        return inner.save(player, serverId, cause, data).thenCompose(snapshot ->
                redis.set(snapshotKey(player), encode(snapshot), snapshotTtlMs)
                        .handle((v, error) -> {
                            if (error != null) {
                                return quiet(redis.del(snapshotKey(player))).thenApply(x -> snapshot); // never leave an older copy in the cache
                            }
                            return CompletableFuture.completedFuture(snapshot);
                        })
                        .thenCompose(f -> f));
    }

    @Override
    public CompletableFuture<Optional<Snapshot>> latest(UUID player) {
        return quiet(redis.get(snapshotKey(player))).thenCompose(raw -> {
            Optional<Snapshot> cached = decode(player, raw);
            return cached.isPresent() ? CompletableFuture.completedFuture(cached) : inner.latest(player);
        });
    }

    @Override
    public CompletableFuture<Optional<Snapshot>> snapshot(UUID player, long version) {
        return inner.snapshot(player, version);
    }

    @Override
    public CompletableFuture<List<Snapshot>> history(UUID player) {
        return inner.history(player);
    }

    @Override
    public CompletableFuture<Boolean> pin(UUID player, long version, boolean pinned) {
        return inner.pin(player, version, pinned);
    }

    @Override
    public CompletableFuture<Boolean> delete(UUID player, long version) {
        return inner.delete(player, version).thenCompose(deleted -> quiet(redis.del(snapshotKey(player))).thenApply(x -> deleted));
    }

    @Override
    public CompletableFuture<Integer> prune(UUID player, int keep) {
        return inner.prune(player, keep);
    }

    // ------------------------------------------------------------------ presence

    static Optional<Presence> decodePresence(UUID player, byte[] raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String[] parts = new String(raw, StandardCharsets.UTF_8).split("\\|", 3);
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Presence(player, "1".equals(parts[0]), parts[1], Long.parseLong(parts[2])));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    static byte[] encodePresence(boolean online, String serverId, long since) {
        return ((online ? "1" : "0") + "|" + serverId + "|" + since).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public CompletableFuture<Optional<Presence>> presence(UUID player) {
        return quiet(redis.get(presenceKey(player))).thenCompose(raw -> {
            Optional<Presence> cached = decodePresence(player, raw);
            return cached.isPresent() ? CompletableFuture.completedFuture(cached) : inner.presence(player);
        });
    }

    @Override
    public CompletableFuture<Void> setPresence(UUID player, String serverId, boolean online) {
        long now = System.currentTimeMillis();
        CompletableFuture<Void> fast = quiet(redis.set(presenceKey(player), encodePresence(online, serverId, now), 0))
                .thenCompose(v -> quiet(online ? redis.sadd(heldKey(serverId), player.toString()) : redis.srem(heldKey(serverId), player.toString())))
                .thenApply(v -> null);
        return inner.setPresence(player, serverId, online).thenCombine(fast, (a, b) -> null);
    }

    @Override
    public CompletableFuture<Integer> clearPresence(String serverId) {
        long now = System.currentTimeMillis();
        CompletableFuture<Void> fast = quiet(redis.smembers(heldKey(serverId))).thenCompose(members -> {
            CompletableFuture<?> all = CompletableFuture.completedFuture(null);
            if (members != null) {
                for (String m : members) {
                    all = all.thenCompose(x -> quiet(redis.set(prefix + "presence:" + m, encodePresence(false, serverId, now), 0)));
                }
            }
            return all.thenCompose(x -> quiet(redis.del(heldKey(serverId)))).thenApply(x -> null);
        });
        return inner.clearPresence(serverId).thenCombine(fast, (n, b) -> n);
    }

    // ------------------------------------------------------------------ the rest

    @Override
    public CompletableFuture<Optional<byte[]>> extra(String kind, UUID key) {
        return inner.extra(kind, key);
    }

    @Override
    public CompletableFuture<Void> putExtra(String kind, UUID key, byte[] data) {
        return inner.putExtra(kind, key, data);
    }

    @Override
    public CompletableFuture<Boolean> deleteExtra(String kind, UUID key) {
        return inner.deleteExtra(kind, key);
    }

    @Override
    public CompletableFuture<Boolean> ping() {
        return inner.ping();
    }

    @Override
    public void close() {
        redis.close();
        inner.close();
    }
}
