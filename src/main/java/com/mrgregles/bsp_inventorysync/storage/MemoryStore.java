package com.mrgregles.bsp_inventorysync.storage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * The {@link SyncStore} with no database: everything in memory, every future already complete.
 * Used by the GameTests, by the no-op mode when the server has no shared database (D10, nothing is
 * shared then but the mod still works on one server), and as the reference the MySQL store is
 * checked against. Thread-safe: each player's rows are guarded by their own lock.
 */
public final class MemoryStore implements SyncStore {
    private static final class PlayerRows {
        long nextVersion = 1;
        final TreeMap<Long, Snapshot> snapshots = new TreeMap<>();
    }

    private record ExtraKey(String kind, UUID key) {}

    private final Map<UUID, PlayerRows> players = new ConcurrentHashMap<>();
    private final Map<UUID, Presence> presence = new ConcurrentHashMap<>();
    private final Map<ExtraKey, byte[]> extras = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    private volatile boolean closed;

    public MemoryStore() {
        this(System::currentTimeMillis);
    }

    /** With a clock of the test's choosing, so {@code savedAt} and {@code since} are predictable. */
    public MemoryStore(LongSupplier clock) {
        this.clock = clock;
    }

    private PlayerRows rows(UUID player) {
        return players.computeIfAbsent(player, k -> new PlayerRows());
    }

    private <T> CompletableFuture<T> done(T value) {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("store closed"));
        }
        return CompletableFuture.completedFuture(value);
    }

    @Override
    public CompletableFuture<Snapshot> save(UUID player, String serverId, SaveCause cause, byte[] data) {
        PlayerRows r = rows(player);
        Snapshot s;
        synchronized (r) {
            s = new Snapshot(player, r.nextVersion++, serverId, clock.getAsLong(), cause, false, data);
            r.snapshots.put(s.version(), s);
        }
        return done(s);
    }

    @Override
    public CompletableFuture<Optional<Snapshot>> latest(UUID player) {
        PlayerRows r = players.get(player);
        if (r == null) {
            return done(Optional.empty());
        }
        synchronized (r) {
            Map.Entry<Long, Snapshot> last = r.snapshots.lastEntry();
            return done(last == null ? Optional.empty() : Optional.of(last.getValue()));
        }
    }

    @Override
    public CompletableFuture<Optional<Snapshot>> snapshot(UUID player, long version) {
        PlayerRows r = players.get(player);
        if (r == null) {
            return done(Optional.empty());
        }
        synchronized (r) {
            return done(Optional.ofNullable(r.snapshots.get(version)));
        }
    }

    @Override
    public CompletableFuture<List<Snapshot>> history(UUID player) {
        PlayerRows r = players.get(player);
        if (r == null) {
            return done(List.of());
        }
        synchronized (r) {
            return done(new ArrayList<>(r.snapshots.descendingMap().values()));
        }
    }

    @Override
    public CompletableFuture<Boolean> pin(UUID player, long version, boolean pinned) {
        PlayerRows r = players.get(player);
        if (r == null) {
            return done(false);
        }
        synchronized (r) {
            Snapshot s = r.snapshots.get(version);
            if (s == null) {
                return done(false);
            }
            r.snapshots.put(version, s.withPinned(pinned));
            return done(true);
        }
    }

    @Override
    public CompletableFuture<Boolean> delete(UUID player, long version) {
        PlayerRows r = players.get(player);
        if (r == null) {
            return done(false);
        }
        synchronized (r) {
            return done(r.snapshots.remove(version) != null);
        }
    }

    @Override
    public CompletableFuture<Integer> prune(UUID player, int keep) {
        PlayerRows r = players.get(player);
        if (r == null) {
            return done(0);
        }
        synchronized (r) {
            if (r.snapshots.isEmpty()) {
                return done(0);
            }
            long latest = r.snapshots.lastKey();
            List<Snapshot> unpinned = new ArrayList<>();
            for (Snapshot s : r.snapshots.values()) {
                if (!s.pinned() && s.version() != latest) {
                    unpinned.add(s);
                }
            }
            // the latest counts as one of the kept unpinned snapshots when it is unpinned
            int kept = r.snapshots.get(latest).pinned() ? 0 : 1;
            int excess = unpinned.size() + kept - Math.max(keep, 0);
            if (excess <= 0) {
                return done(0);
            }
            unpinned.sort(Comparator.comparingLong(Snapshot::version));
            int removed = 0;
            for (Snapshot s : unpinned) {
                if (removed >= excess) {
                    break;
                }
                r.snapshots.remove(s.version());
                removed++;
            }
            return done(removed);
        }
    }

    @Override
    public CompletableFuture<Optional<Presence>> presence(UUID player) {
        return done(Optional.ofNullable(presence.get(player)));
    }

    @Override
    public CompletableFuture<Void> setPresence(UUID player, String serverId, boolean online) {
        if (online) {
            presence.put(player, new Presence(player, true, serverId, clock.getAsLong()));
        } else {
            presence.computeIfPresent(player, (k, now) -> now.serverId().equals(serverId) ? new Presence(player, false, serverId, clock.getAsLong()) : now);
        }
        return done(null);
    }

    @Override
    public CompletableFuture<Integer> clearPresence(String serverId) {
        int n = 0;
        long now = clock.getAsLong();
        for (Map.Entry<UUID, Presence> e : presence.entrySet()) {
            Presence p = e.getValue();
            if (p.online() && p.serverId().equals(serverId)) {
                e.setValue(new Presence(p.player(), false, serverId, now));
                n++;
            }
        }
        return done(n);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> extra(String kind, UUID key) {
        byte[] b = extras.get(new ExtraKey(kind, key));
        return done(b == null ? Optional.empty() : Optional.of(b.clone()));
    }

    @Override
    public CompletableFuture<Void> putExtra(String kind, UUID key, byte[] data) {
        extras.put(new ExtraKey(kind, key), data.clone());
        return done(null);
    }

    @Override
    public CompletableFuture<Boolean> deleteExtra(String kind, UUID key) {
        return done(extras.remove(new ExtraKey(kind, key)) != null);
    }

    @Override
    public CompletableFuture<Boolean> ping() {
        return done(!closed);
    }

    @Override
    public void close() {
        closed = true;
    }

    /** For tests and {@code /invsync status}: how many players have at least one snapshot. */
    public int playerCount() {
        return (int) players.values().stream().filter(r -> {
            synchronized (r) {
                return !r.snapshots.isEmpty();
            }
        }).count();
    }
}
