package com.mrgregles.bsp_inventorysync.sync;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.SyncConfig;
import com.mrgregles.bsp_inventorysync.compat.BspCore;
import com.mrgregles.bsp_inventorysync.storage.FastStore;
import com.mrgregles.bsp_inventorysync.storage.JdbcStore;
import com.mrgregles.bsp_inventorysync.storage.RedisClient;
import com.mrgregles.bsp_inventorysync.storage.SyncStore;
import net.minecraft.server.MinecraftServer;

import javax.annotation.Nullable;

/**
 * The one {@link SyncService} of this server, or none when the server syncs nothing (D10: no shared
 * database configured, single player, a dev run). The store behind it is chosen here from the config:
 * MySQL (BSP-Core's database settings by default, this mod's own tables), with Redis in front when
 * enabled.
 */
public final class Sync {
    @Nullable private static volatile SyncService active;

    private Sync() {}

    @Nullable
    public static SyncService active() {
        return active;
    }

    public static boolean enabled() {
        return active != null;
    }

    public static void start(MinecraftServer server) {
        String mode = SyncConfig.getOr(SyncConfig.STORAGE_MODE, "off");
        if (!"mysql".equalsIgnoreCase(mode)) {
            BSPInventorySync.LOGGER.info("BSP-InventorySync: storage.mode is \"{}\": no shared database, inventory sync is off on this server", mode);
            return;
        }
        String serverId = SyncConfig.getOr(SyncConfig.SERVER_ID, "");
        if (serverId.isEmpty()) {
            serverId = BspCore.serverId().orElse("");
        }
        if (serverId.isEmpty()) {
            BSPInventorySync.LOGGER.error("BSP-InventorySync: no server id: set storage.serverId here or in BSP-Core's config. Inventory sync is off on this server.");
            return;
        }
        JdbcStore.Settings db = databaseSettings();
        JdbcStore jdbc = new JdbcStore(db);
        jdbc.init().whenComplete((v, error) -> {
            if (error != null) {
                BSPInventorySync.LOGGER.error("BSP-InventorySync: could not prepare the tables on {}: {}. Saves and restores will fail until the database answers.", db.describe(), error.toString());
            } else {
                BSPInventorySync.LOGGER.info("BSP-InventorySync: tables ready on {}", db.describe());
            }
        });
        SyncStore store = jdbc;
        String redisNote = "no Redis";
        if (SyncConfig.getOr(SyncConfig.REDIS_ENABLED, false)) {
            RedisClient.Settings rs = new RedisClient.Settings(SyncConfig.getOr(SyncConfig.REDIS_HOST, "localhost"), SyncConfig.getOr(SyncConfig.REDIS_PORT, 6379),
                    SyncConfig.getOr(SyncConfig.REDIS_USERNAME, ""), SyncConfig.getOr(SyncConfig.REDIS_PASSWORD, ""));
            store = new FastStore(jdbc, new RedisClient(rs), SyncConfig.getOr(SyncConfig.REDIS_CLUSTER, "bsp"), SyncConfig.getOr(SyncConfig.REDIS_SNAPSHOT_TTL_SECONDS, 600) * 1000L);
            redisNote = rs.describe();
        }
        ArrivalPolicy policy = new ArrivalPolicy(SyncConfig.getOr(SyncConfig.ARRIVAL_TIMEOUT_SECONDS, 10) * 1000L, SyncConfig.getOr(SyncConfig.KICK_ON_TIMEOUT, false));
        SyncService service = new SyncService(server, store, serverId, policy);
        service.setSnapshotsKept(SyncConfig.getOr(SyncConfig.SNAPSHOTS_KEPT, 16));
        service.setAutosaveMs(SyncConfig.getOr(SyncConfig.AUTOSAVE_SECONDS, 60) * 1000L);
        service.setStoreTimeoutMs(SyncConfig.getOr(SyncConfig.STORE_TIMEOUT_SECONDS, 5) * 1000L);
        active = service;
        service.onServerStarted();
        BSPInventorySync.LOGGER.info("BSP-InventorySync: sync started as \"{}\" on {} with {}", serverId, db.describe(), redisNote);
    }

    /** BSP-Core's database settings when {@code storage.useBspCoreDatabase} is on, else this mod's own; always this mod's table prefix. */
    static JdbcStore.Settings databaseSettings() {
        String prefix = SyncConfig.getOr(SyncConfig.TABLE_PREFIX, "bsp_is_");
        if (SyncConfig.getOr(SyncConfig.USE_BSP_CORE_DATABASE, true)) {
            java.util.Optional<String> host = BspCore.storageSetting("STORAGE_HOST", String.class);
            if (host.isPresent()) {
                return new JdbcStore.Settings(host.get(),
                        BspCore.storageSetting("STORAGE_PORT", Integer.class).orElse(3306),
                        BspCore.storageSetting("STORAGE_DATABASE", String.class).orElse("bsp"),
                        BspCore.storageSetting("STORAGE_USER", String.class).orElse("bsp"),
                        BspCore.storageSetting("STORAGE_PASSWORD", String.class).orElse(""),
                        BspCore.storageSetting("STORAGE_SSL", Boolean.class).orElse(false), prefix,
                        "BSP-Core's bsp_core-server.toml, storage.useBspCoreDatabase = true");
            }
            BSPInventorySync.LOGGER.warn("BSP-InventorySync: BSP-Core's database settings could not be read; using this mod's own storage.* fields");
        }
        return new JdbcStore.Settings(SyncConfig.getOr(SyncConfig.DB_HOST, "localhost"), SyncConfig.getOr(SyncConfig.DB_PORT, 3306), SyncConfig.getOr(SyncConfig.DB_NAME, "bsp"),
                SyncConfig.getOr(SyncConfig.DB_USER, "bsp"), SyncConfig.getOr(SyncConfig.DB_PASSWORD, ""), SyncConfig.getOr(SyncConfig.DB_SSL, false), prefix,
                "this mod's bsp_inventorysync-server.toml");
    }

    public static void stop() {
        SyncService service = active;
        if (service == null) {
            return;
        }
        active = null;
        service.shutdown(5_000);
    }

    /**
     * {@code /invsync reload}: the config is read again and the stores reopened. Players online keep
     * their state: the new service adopts them (checked in here, their auto-save clock started), so
     * their logout saves and releases as usual. Returns one line for the operator.
     */
    public static String reload(MinecraftServer server) {
        stop();
        start(server);
        SyncService service = active;
        if (service == null) {
            return "reloaded: inventory sync is off on this server (storage.mode is not mysql, or no server id)";
        }
        int adopted = service.adopt(server.getPlayerList().getPlayers(), System.currentTimeMillis());
        return "reloaded: sync on as \"" + service.serverId() + "\", " + adopted + " online player(s) adopted";
    }
}
