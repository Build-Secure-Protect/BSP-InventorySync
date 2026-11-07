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
 * <li>{@code gives}: stacks waiting for a player ({@code /invsync give}, D23).</li>
 * <li>{@code graves}: one row per grave (D25), its contents a GZIP blob, a revision for safe updates.</li>
 * </ul>
 */
public final class JdbcStore implements SyncStore {
    public record Settings(String host, int port, String database, String user, String password, boolean ssl, String prefix, String source) {
        /** Settings without a named source (tests). */
        public Settings(String host, int port, String database, String user, String password, boolean ssl, String prefix) {
            this(host, port, database, user, password, ssl, prefix, "");
        }

        public String describe() {
            return "mysql " + host + ":" + port + "/" + database + " as " + user + ", tables " + prefix + "*" + (source.isEmpty() ? "" : " (settings from " + source + ")");
        }
    }

    private final Settings settings;
    private final String url;
    private final String players, snapshots, extra, gives, graves;
    private final ExecutorService worker;
    private static final long FIRST_BACKOFF_MS = 1_000, MAX_BACKOFF_MS = 15_000;

    private Connection connection;
    private volatile String lastError = "";
    private volatile boolean closed;
    private long retryAt, backoffMs;
    private volatile long connectAttempts;

    public JdbcStore(Settings settings) {
        this.settings = settings;
        this.url = "jdbc:mysql://" + settings.host() + ":" + settings.port() + "/" + settings.database()
                + "?useSSL=" + settings.ssl() + "&allowPublicKeyRetrieval=true&characterEncoding=utf8&connectTimeout=5000&socketTimeout=15000";
        this.players = settings.prefix() + "players";
        this.snapshots = settings.prefix() + "snapshots";
        this.extra = settings.prefix() + "extra";
        this.gives = settings.prefix() + "gives";
        this.graves = settings.prefix() + "graves";
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
                st.executeUpdate("CREATE TABLE IF NOT EXISTS " + gives + " ("
                        + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, player CHAR(36) NOT NULL, item MEDIUMBLOB NOT NULL, "
                        + "given_by VARCHAR(64) NOT NULL, created_at BIGINT NOT NULL, INDEX (player))");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS " + graves + " ("
                        + "id CHAR(36) NOT NULL PRIMARY KEY, owner CHAR(36) NOT NULL, owner_name VARCHAR(64) NOT NULL, "
                        + "server_id VARCHAR(64) NOT NULL, dimension VARCHAR(255) NOT NULL, x INT NOT NULL, y INT NOT NULL, z INT NOT NULL, "
                        + "placed TINYINT NOT NULL, died_at BIGINT NOT NULL, touched_at BIGINT NOT NULL, archived_at BIGINT NOT NULL DEFAULT 0, "
                        + "xp INT NOT NULL, stacks INT NOT NULL, revision BIGINT NOT NULL, data MEDIUMBLOB NOT NULL, "
                        + "INDEX (owner), INDEX (touched_at))");
                try (ResultSet rs = st.executeQuery("SELECT @@max_allowed_packet")) {
                    if (rs.next() && rs.getLong(1) < 16L * 1024 * 1024) {
                        BSPInventorySync.LOGGER.warn("BSP-InventorySync: the database's max_allowed_packet is {} KB; a player with a very full inventory can exceed it and fail to save. 16 MB or more is safe.", rs.getLong(1) / 1024);
                    }
                }
            }
            return null;
        });
    }

    /**
     * The connection, reopened when it has failed. While the database cannot be reached, a new attempt is
     * made at most once per back-off period (1 s, doubling to 15 s); calls in between fail at once. Without
     * that, every queued call would spend the full connect timeout on an unreachable host and the queue
     * (saves, presence polls) would grow for as long as the outage lasts.
     */
    private Connection conn() throws SQLException {
        if (connection == null || connection.isClosed() || !connection.isValid(2)) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                    // reopening anyway
                }
                connection = null;
            }
            if (System.currentTimeMillis() < retryAt) {
                throw new SQLException("the database is unreachable; waiting before the next try", "08001");
            }
            connectAttempts++;
            try {
                connection = DriverManager.getConnection(url, settings.user(), settings.password());
                connection.setAutoCommit(true);
                backoffMs = 0;
            } catch (SQLException e) {
                backoffMs = backoffMs == 0 ? FIRST_BACKOFF_MS : Math.min(backoffMs * 2, MAX_BACKOFF_MS);
                retryAt = System.currentTimeMillis() + backoffMs;
                throw e;
            }
        }
        return connection;
    }

    /** How often a connection was attempted; for the tests and the log. */
    public long connectAttempts() {
        return connectAttempts;
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
        return saveWithGraves(player, serverId, cause, data, List.of());
    }

    @Override
    public CompletableFuture<Snapshot> saveWithGraves(UUID player, String serverId, SaveCause cause, byte[] data, List<Grave> newGraves) {
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
                for (Grave g : newGraves) {
                    insertGrave(c, g); // in the same transaction: the graves land with this snapshot or not at all
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
    public CompletableFuture<Long> latestVersion(UUID player) {
        return run(() -> {
            try (PreparedStatement ps = conn().prepareStatement("SELECT MAX(version) FROM " + snapshots + " WHERE player = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L; // MAX of no rows is NULL, read as 0
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
        if (!online) {
            return run(() -> {
                try (PreparedStatement ps = conn().prepareStatement("UPDATE " + players + " SET online = 0, since = ? WHERE player = ? AND server_id = ?")) {
                    ps.setLong(1, System.currentTimeMillis());
                    ps.setString(2, player.toString());
                    ps.setString(3, serverId);
                    ps.executeUpdate(); // another server has taken the player meanwhile: their checkout stays
                }
                return null;
            });
        }
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
    public CompletableFuture<Give> addGive(UUID player, byte[] item, String by) {
        return run(() -> {
            long now = System.currentTimeMillis();
            String name = by.length() > 64 ? by.substring(0, 64) : by;
            try (PreparedStatement ps = conn().prepareStatement("INSERT INTO " + gives + " (player, item, given_by, created_at) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, player.toString());
                ps.setBytes(2, item);
                ps.setString(3, name);
                ps.setLong(4, now);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("no id for the new give");
                    }
                    return new Give(keys.getLong(1), player, item, name, now);
                }
            }
        });
    }

    @Override
    public CompletableFuture<List<Give>> gives(java.util.Collection<UUID> players) {
        if (players.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        List<UUID> ids = List.copyOf(players);
        return run(() -> {
            String marks = String.join(", ", java.util.Collections.nCopies(ids.size(), "?"));
            try (PreparedStatement ps = conn().prepareStatement("SELECT id, player, item, given_by, created_at FROM " + gives
                    + " WHERE player IN (" + marks + ") ORDER BY id")) {
                for (int i = 0; i < ids.size(); i++) {
                    ps.setString(i + 1, ids.get(i).toString());
                }
                List<Give> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Give(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getBytes(3), rs.getString(4), rs.getLong(5)));
                    }
                }
                return out;
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> takeGive(long id) {
        return run(() -> {
            try (PreparedStatement ps = conn().prepareStatement("DELETE FROM " + gives + " WHERE id = ?")) {
                ps.setLong(1, id);
                return ps.executeUpdate() > 0;
            }
        });
    }

    // ------------------------------------------------------------------ graves

    private static final String GRAVE_COLUMNS = "id, owner, owner_name, server_id, dimension, x, y, z, placed, died_at, touched_at, archived_at, xp, stacks, revision, data";

    private static Grave graveRow(ResultSet rs) throws SQLException {
        return new Grave(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("owner")), rs.getString("owner_name"),
                rs.getString("server_id"), rs.getString("dimension"), rs.getInt("x"), rs.getInt("y"), rs.getInt("z"), rs.getInt("placed") != 0,
                rs.getLong("died_at"), rs.getLong("touched_at"), rs.getLong("archived_at"), rs.getInt("xp"), rs.getInt("stacks"),
                rs.getLong("revision"), rs.getBytes("data"));
    }

    private static List<Grave> graveRows(PreparedStatement ps) throws SQLException {
        List<Grave> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(graveRow(rs));
            }
        }
        return out;
    }

    @Override
    public CompletableFuture<Boolean> addGrave(Grave g) {
        return run(() -> insertGrave(conn(), g));
    }

    /**
     * Inserts the grave; false when its id is already there (nothing changed). {@code ON DUPLICATE KEY UPDATE id = id}
     * rather than {@code INSERT IGNORE}, which would also turn a too-long value or a too-big blob into a warning and
     * store it cut short.
     */
    private boolean insertGrave(Connection c, Grave g) throws SQLException {
        {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + graves + " (" + GRAVE_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE id = id")) {
                ps.setString(1, g.id().toString());
                ps.setString(2, g.owner().toString());
                ps.setString(3, g.ownerName().length() > 64 ? g.ownerName().substring(0, 64) : g.ownerName());
                ps.setString(4, g.serverId());
                ps.setString(5, g.dimension());
                ps.setInt(6, g.x());
                ps.setInt(7, g.y());
                ps.setInt(8, g.z());
                ps.setInt(9, g.placed() ? 1 : 0);
                ps.setLong(10, g.diedAt());
                ps.setLong(11, g.touchedAt());
                ps.setLong(12, g.archivedAt());
                ps.setInt(13, g.xp());
                ps.setInt(14, g.stacks());
                ps.setLong(15, g.revision());
                ps.setBytes(16, g.data());
                return ps.executeUpdate() == 1; // 1 inserted; 0 when the id is already there
            }
        }
    }

    @Override
    public CompletableFuture<Optional<Grave>> grave(UUID id) {
        return run(() -> {
            try (PreparedStatement ps = conn().prepareStatement("SELECT " + GRAVE_COLUMNS + " FROM " + graves + " WHERE id = ?")) {
                ps.setString(1, id.toString());
                List<Grave> rows = graveRows(ps);
                return rows.isEmpty() ? Optional.<Grave>empty() : Optional.of(rows.get(0));
            }
        });
    }

    @Override
    public CompletableFuture<List<Grave>> graves(UUID owner) {
        return run(() -> {
            try (PreparedStatement ps = conn().prepareStatement("SELECT " + GRAVE_COLUMNS + " FROM " + graves + " WHERE owner = ? ORDER BY died_at DESC")) {
                ps.setString(1, owner.toString());
                return graveRows(ps);
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> updateGrave(Grave g) {
        return run(() -> {
            try (PreparedStatement ps = conn().prepareStatement("UPDATE " + graves + " SET owner = ?, owner_name = ?, placed = ?, touched_at = ?, archived_at = ?, xp = ?, stacks = ?, "
                    + "data = ?, revision = revision + 1 WHERE id = ? AND revision = ?")) {
                ps.setString(1, g.owner().toString());
                ps.setString(2, g.ownerName().length() > 64 ? g.ownerName().substring(0, 64) : g.ownerName());
                ps.setInt(3, g.placed() ? 1 : 0);
                ps.setLong(4, g.touchedAt());
                ps.setLong(5, g.archivedAt());
                ps.setInt(6, g.xp());
                ps.setInt(7, g.stacks());
                ps.setBytes(8, g.data());
                ps.setString(9, g.id().toString());
                ps.setLong(10, g.revision());
                return ps.executeUpdate() > 0;
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> deleteGrave(UUID id, long revision) {
        return run(() -> {
            try (PreparedStatement ps = conn().prepareStatement("DELETE FROM " + graves + " WHERE id = ? AND revision = ?")) {
                ps.setString(1, id.toString());
                ps.setLong(2, revision);
                return ps.executeUpdate() > 0;
            }
        });
    }

    @Override
    public CompletableFuture<List<Grave>> gravesTouchedBefore(long before, int limit) {
        return run(() -> {
            try (PreparedStatement ps = conn().prepareStatement("SELECT " + GRAVE_COLUMNS + " FROM " + graves + " WHERE touched_at < ? ORDER BY touched_at ASC LIMIT ?")) {
                ps.setLong(1, before);
                ps.setInt(2, Math.max(0, limit));
                return graveRows(ps);
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
