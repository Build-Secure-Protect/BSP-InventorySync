package com.mrgregles.bsp_inventorysync.storage;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The {@link SyncStore} on MySQL through plain JDBC (D6). No driver is bundled: BSP-Core's full jar
 * provides one on Forge servers and Mohist ships its own (two copies refuse to load together). One
 * connection on one worker thread, reopened when it fails; every future completes on that thread.
 *
 * <p>Tables, all under the configured prefix (default {@code bsp_is_}):
 * <ul>
 * <li>{@code players}: one row per player: the next snapshot version (so a deleted version is never
 * reused) and where they are (online, server, since). The presence row is the handshake's checkout.</li>
 * <li>{@code snapshots}: one row per snapshot: version, server, time, cause, pin, the GZIP blob.</li>
 * <li>{@code extra}: blobs that live outside the player's NBT, by kind and key (backpack contents).</li>
 * </ul>
 */
public final class JdbcStore implements SyncStore {
    public record Settings(String host, int port, String database, String user, String password, boolean ssl, String prefix) {
        public String describe() {
            return "mysql " + host + ":" + port + "/" + database + " as " + user + ", tables " + prefix + "*";
        }
    }

    private final Settings settings;
    private final String url;
    private final String players, snapshots, extra;
    private final ExecutorService worker;
    private Connection connection;
    private volatile String lastError = "";
    private volatile boolean closed;

    public JdbcStore(Settings settings) {
        this.settings = settings;
        this.url = "jdbc:mysql://" + settings.host() + ":" + settings.port() + "/" + settings.database()
                + "?useSSL=" + settings.ssl() + "&allowPublicKeyRetrieval=true&characterEncoding=utf8&connectTimeout=5000&socketTimeout=15000";
        this.players = settings.prefix() + "players";
        this.snapshots = settings.prefix() + "snapshots";
        this.extra = settings.prefix() + "extra";
        this.worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "bsp-inventorysync-mysql");
            t.setDaemon(true);
            return t;
        });
    }

    public Settings settings() {
        return settings;
    }

    public String status() {
        return settings.describe() + (lastError.isEmpty() ? "" : " (last error: " + lastError + ")");
    }

    /** Creates the tables if they are missing. */
    public CompletableFuture<Void> init() {
        return run(() -> {
            try (Statement st = conn().createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS " + players + " ("
                        + "player CHAR(36) NOT NULL PRIMARY KEY, next_version BIGINT NOT NULL DEFAULT 1, "
                        + "online TINYINT NOT NULL DEFAULT 0, server_id VARCHAR(64) NOT NULL DEFAULT '', since BIGINT NOT NULL DEFAULT 0)");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS " + snapshots + " ("
                        + "player CHAR(36) NOT NULL, version BIGINT NOT NULL, server_id VARCHAR(64) NOT NULL, saved_at BIGINT NOT NULL, "
                        + "cause VARCHAR(16) NOT NULL, pinned TINYINT NOT NULL DEFAULT 0, data MEDIUMBLOB NOT NULL, "
                        + "PRIMARY KEY (player, version))");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS " + extra + " ("
                        + "kind VARCHAR(32) NOT NULL, id CHAR(36) NOT NULL, updated_at BIGINT NOT NULL, data MEDIUMBLOB NOT NULL, "
                        + "PRIMARY KEY (kind, id))");
            }
            return null;
        });
    }

    private Connection conn() throws SQLException {
        if (connection == null || connection.isClosed() || !connection.isValid(2)) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                    // reopening anyway
                }
            }
            connection = DriverManager.getConnection(url, settings.user(), settings.password());
            connection.setAutoCommit(true);
        }
        return connection;
    }

    private <T> CompletableFuture<T> run(Callable<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (closed) {
            future.completeExceptionally(new IllegalStateException("store closed"));
            return future;
        }
        try {
            worker.execute(() -> {
                try {
                    future.complete(work.call());
                } catch (Throwable t) {
                    String message = t.getMessage() == null ? t.toString() : t.getMessage();
                    if (!message.equals(lastError)) {
                        BSPInventorySync.LOGGER.error("BSP-InventorySync: {} failed: {}", settings.describe(), message);
                    }
                    lastError = message;
                    future.completeExceptionally(t);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    private static Snapshot row(ResultSet rs, UUID player) throws SQLException {
        return new Snapshot(player, rs.getLong("version"), rs.getString("server_id"), rs.getLong("saved_at"),
                SaveCause.byId(rs.getString("cause")), rs.getInt("pinned") != 0, rs.getBytes("data"));
    }

    // ------------------------------------------------------------------ snapshots

    @Override
    public CompletableFuture<Snapshot> save(UUID player, String serverId, SaveCause cause, byte[] data) {
        return run(() -> {
            Connection c = conn();
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO " + players + " (player) VALUES (?)")) {
                    ps.setString(1, player.toString());
                    ps.executeUpdate();
                }
                long version;
                try (PreparedStatement ps = c.prepareStatement("SELECT next_version FROM " + players + " WHERE player = ? FOR UPDATE")) {
                    ps.setString(1, player.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        version = rs.getLong(1);
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("UPDATE " + players + " SET next_version = ? WHERE player = ?")) {
                    ps.setLong(1, version + 1);
                    ps.setString(2, player.toString());
                    ps.executeUpdate();
                }
                long now = System.currentTimeMillis();
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + snapshots + " (player, version, server_id, saved_at, cause, pinned, data) VALUES (?, ?, ?, ?, ?, 0, ?)")) {
                    ps.setString(1, player.toString());
                    ps.setLong(2, version);
                    ps.setString(3, serverId);
                    ps.setLong(4, now);
                    ps.setString(5, cause.id());
                    ps.setBytes(6, data);
                    ps.executeUpdate();
                }
                c.commit();
                return new Snapshot(player, version, serverId, now, cause, false, data);
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        });
    }

    @Override
    public CompletableFuture<Optional<Snapshot>> latest(UUID player) {
        return run(() -> {
            try (PreparedStatement ps = conn().prepareStatement("SELECT version, server_id, saved_at, cause, pinned, data FROM " + snapshots + " WHERE player = ? ORDER BY version DESC LIMIT 1")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(row(rs, player)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public CompletableFuture<Optional<Snapshot>> snapshot(UUID player, long version) {
        return run(() -> {
            try (PreparedStatement ps = conn().prepareStatement("SELECT version, server_id, saved_at, cause, pinned, data FROM " + snapshots + " WHERE player = ? AND version = ?")) {
                ps.setString(1, player.toString());
                ps.setLong(2, version);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(row(rs, player)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public CompletableFuture<List<Snapshot>> history(UUID player) {
        return run(() -> {
            List<Snapshot> out = new ArrayList<>();
            try (PreparedStatement ps = conn().prepareStatement("SELECT version, server_id, saved_at, cause, pinned, data FROM " + snapshots + " WHERE player = ? ORDER BY version DESC")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(row(rs, player));
                    }
                }
            }
            return out;
        });
    }

    @Override
    public CompletableFuture<Boolean> pin(UUID player, long version, boolean pinned) {
        return run(() -> {
            try (PreparedStatement ps = conn().prepareStatement("UPDATE " + snapshots + " SET pinned = ? WHERE player = ? AND version = ?")) {
                ps.setInt(1, pinned ? 1 : 0);
                ps.setString(2, player.toString());
                ps.setLong(3, version);
                return ps.executeUpdate() > 0;
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> delete(UUID player, long version) {
        return run(() -> {
            try (PreparedStatement ps = conn().prepareStatement("DELETE FROM " + snapshots + " WHERE player = ? AND version = ?")) {
                ps.setString(1, player.toString());
                ps.setLong(2, version);
                return ps.executeUpdate() > 0;
            }
        });
    }

    @Override
    public CompletableFuture<Integer> prune(UUID player, int keep) {
        return run(() -> {
            record Row(long version, boolean pinned) {}
            List<Row> rows = new ArrayList<>();
            try (PreparedStatement ps = conn().prepareStatement("SELECT version, pinned FROM " + snapshots + " WHERE player = ? ORDER BY version ASC")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new Row(rs.getLong(1), rs.getInt(2) != 0));
                    }
                }
            }
            if (rows.isEmpty()) {
                return 0;
            }
            long latest = rows.get(rows.size() - 1).version();
            List<Long> unpinned = new ArrayList<>();
            int kept = 0;
            for (Row r : rows) {
                if (r.pinned()) {
                    continue;
                }
                if (r.version() == latest) {
                    kept = 1;
                } else {
                    unpinned.add(r.version());
                }
            }
            int excess = unpinned.size() + kept - Math.max(keep, 0);
            int removed = 0;
            if (excess > 0) {
                try (PreparedStatement ps = conn().prepareStatement("DELETE FROM " + snapshots + " WHERE player = ? AND version = ?")) {
                    for (long v : unpinned) {
                        if (removed >= excess) {
                            break;
                        }
                        ps.setString(1, player.toString());
                        ps.setLong(2, v);
                        removed += ps.executeUpdate();
                    }
                }
            }
            return removed;
        });
    }

    // ------------------------------------------------------------------ presence

    @Override
    public CompletableFuture<Optional<Presence>> presence(UUID player) {
        return run(() -> {
            try (PreparedStatement ps = conn().prepareStatement("SELECT online, server_id, since FROM " + players + " WHERE player = ? AND since > 0")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(new Presence(player, rs.getInt(1) != 0, rs.getString(2), rs.getLong(3))) : Optional.empty();
                }
            }
        });
    }

    @Override
    public CompletableFuture<Void> setPresence(UUID player, String serverId, boolean online) {
        return run(() -> {
            try (PreparedStatement ps = conn().prepareStatement("INSERT INTO " + players + " (player, online, server_id, since) VALUES (?, ?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE online = VALUES(online), server_id = VALUES(server_id), since = VALUES(since)")) {
                ps.setString(1, player.toString());
                ps.setInt(2, online ? 1 : 0);
                ps.setString(3, serverId);
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Integer> clearPresence(String serverId) {
        return run(() -> {
            try (PreparedStatement ps = conn().prepareStatement("UPDATE " + players + " SET online = 0, since = ? WHERE online = 1 AND server_id = ?")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setString(2, serverId);
                return ps.executeUpdate();
            }
        });
    }

    // ------------------------------------------------------------------ extra blobs

    @Override
    public CompletableFuture<Optional<byte[]>> extra(String kind, UUID key) {
        return run(() -> {
            try (PreparedStatement ps = conn().prepareStatement("SELECT data FROM " + extra + " WHERE kind = ? AND id = ?")) {
                ps.setString(1, kind);
                ps.setString(2, key.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getBytes(1)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public CompletableFuture<Void> putExtra(String kind, UUID key, byte[] data) {
        return run(() -> {
            try (PreparedStatement ps = conn().prepareStatement("INSERT INTO " + extra + " (kind, id, updated_at, data) VALUES (?, ?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE updated_at = VALUES(updated_at), data = VALUES(data)")) {
                ps.setString(1, kind);
                ps.setString(2, key.toString());
                ps.setLong(3, System.currentTimeMillis());
                ps.setBytes(4, data);
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Boolean> deleteExtra(String kind, UUID key) {
        return run(() -> {
            try (PreparedStatement ps = conn().prepareStatement("DELETE FROM " + extra + " WHERE kind = ? AND id = ?")) {
                ps.setString(1, kind);
                ps.setString(2, key.toString());
                return ps.executeUpdate() > 0;
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> ping() {
        return run(() -> {
            try (Statement st = conn().createStatement(); ResultSet rs = st.executeQuery("SELECT 1")) {
                return rs.next();
            }
        }).exceptionally(t -> false);
    }

    @Override
    public void close() {
        closed = true;
        worker.shutdown();
        try {
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                worker.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // closing anyway
            }
        }
    }
}
