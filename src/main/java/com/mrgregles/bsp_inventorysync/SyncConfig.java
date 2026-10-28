package com.mrgregles.bsp_inventorysync;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

/**
 * Server config ({@code <world>/serverconfig/bsp_inventorysync-server.toml}). The mod is off unless
 * {@code storage.mode} is {@code mysql} (D10). By default the database settings are BSP-Core's own
 * ({@code storage.*} in its server config), so the owner enters them once; this mod still keeps its
 * own tables under its own prefix (D6).
 */
public final class SyncConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.ConfigValue<String> STORAGE_MODE;
    public static final ForgeConfigSpec.ConfigValue<String> SERVER_ID;
    public static final ForgeConfigSpec.BooleanValue USE_BSP_CORE_DATABASE;
    public static final ForgeConfigSpec.ConfigValue<String> DB_HOST;
    public static final ForgeConfigSpec.IntValue DB_PORT;
    public static final ForgeConfigSpec.ConfigValue<String> DB_NAME;
    public static final ForgeConfigSpec.ConfigValue<String> DB_USER;
    public static final ForgeConfigSpec.ConfigValue<String> DB_PASSWORD;
    public static final ForgeConfigSpec.BooleanValue DB_SSL;
    public static final ForgeConfigSpec.ConfigValue<String> TABLE_PREFIX;
    public static final ForgeConfigSpec.IntValue SNAPSHOTS_KEPT;
    public static final ForgeConfigSpec.IntValue STORE_TIMEOUT_SECONDS;
    public static final ForgeConfigSpec.IntValue AUTOSAVE_SECONDS;
    public static final ForgeConfigSpec.BooleanValue REDIS_ENABLED;
    public static final ForgeConfigSpec.ConfigValue<String> REDIS_HOST;
    public static final ForgeConfigSpec.IntValue REDIS_PORT;
    public static final ForgeConfigSpec.ConfigValue<String> REDIS_USERNAME;
    public static final ForgeConfigSpec.ConfigValue<String> REDIS_PASSWORD;
    public static final ForgeConfigSpec.ConfigValue<String> REDIS_CLUSTER;
    public static final ForgeConfigSpec.IntValue REDIS_SNAPSHOT_TTL_SECONDS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> EXCLUDE_FORGE_DATA;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> EXCLUDE_CAPABILITIES;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> NETWORK_SERVERS;
    public static final ForgeConfigSpec.IntValue ARRIVAL_TIMEOUT_SECONDS;
    public static final ForgeConfigSpec.BooleanValue KICK_ON_TIMEOUT;

    /** The comment on each key whose value now lives in {@link NetworkConfig}; the key stays so that Forge keeps an old value until it is moved. */
    static final String MOVED = "Moved to config/bsp_inventorysync-network.toml (a SERVER config is sent to every player). A value left here is moved there at the next start and cleared.";

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("storage");
        STORAGE_MODE = b.comment("\"off\": this server syncs nothing (single player, a server without the shared database). \"mysql\": sync through the network's database.")
                .define("mode", "off");
        SERVER_ID = b.comment("This server's name on the network. Empty: use BSP-Core's storage.serverId. Must equal the Velocity server name.")
                .define("serverId", "");
        USE_BSP_CORE_DATABASE = b.comment("true: connect to the database BSP-Core's config/bsp_core-network.toml names (host, port, database, user, password, useSsl).",
                        "false: use this mod's own config/bsp_inventorysync-network.toml.")
                .define("useBspCoreDatabase", true);
        DB_HOST = b.comment(MOVED).define("host", "localhost");
        DB_PORT = b.comment(MOVED).defineInRange("port", 3306, 1, 65535);
        DB_NAME = b.comment(MOVED).define("database", "bsp");
        DB_USER = b.comment(MOVED).define("user", "bsp");
        DB_PASSWORD = b.comment(MOVED).define("password", "");
        DB_SSL = b.comment(MOVED).define("useSsl", false);
        TABLE_PREFIX = b.comment("Prefix of this mod's tables (letters, digits and underscores).").define("tablePrefix", "bsp_is_");
        SNAPSHOTS_KEPT = b.comment("How many unpinned snapshots are kept per player; pinned ones are kept for ever.").defineInRange("snapshotsKept", 16, 1, 1000);
        STORE_TIMEOUT_SECONDS = b.comment("The watchdog: a database or Redis call that takes longer than this is given up as failed, so a frozen database never leaves a player frozen.").defineInRange("timeoutSeconds", 5, 1, 120);
        AUTOSAVE_SECONDS = b.comment("How often each online player is checked and, if their data changed, saved. 0 turns the auto-save off (saves still happen on logout, transfer, death and world save).").defineInRange("autosaveSeconds", 60, 0, 3600);
        b.pop();
        b.comment("Redis makes a server switch instant: the checkout and the newest snapshot are kept there too, and the database is",
                "only the safety net. This mod has its own small Redis client; nothing is bundled. Plain connection: keep Redis on the local network.").push("redis");
        REDIS_ENABLED = b.define("enabled", false);
        REDIS_HOST = b.comment(MOVED).define("host", "localhost");
        REDIS_PORT = b.comment(MOVED).defineInRange("port", 6379, 1, 65535);
        REDIS_USERNAME = b.comment(MOVED).define("username", "");
        REDIS_PASSWORD = b.comment(MOVED).define("password", "");
        REDIS_CLUSTER = b.comment("Servers that share players use the same cluster name; keys are bsp_is:<cluster>:*.").define("cluster", "bsp");
        REDIS_SNAPSHOT_TTL_SECONDS = b.comment("How long the newest snapshot stays cached in Redis after a save.").defineInRange("snapshotTtlSeconds", 600, 10, 86_400);
        b.pop();
        b.comment("What travels with the player besides the vanilla data: every mod's persistent NBT (ForgeData) and capabilities (ForgeCaps),",
                "so mods with per-player data need no special support. List here what must stay on each server.").push("sync");
        EXCLUDE_FORGE_DATA = b.comment("Dotted paths in the player's persistent NBT that are not synced (positions that only mean something on one server).")
                .defineListAllowEmpty(List.of("excludeForgeData"), () -> List.of("PlayerPersisted.bsp_core:LastMainDim", "PlayerPersisted.bsp_core:LastMainPos"), o -> o instanceof String);
        EXCLUDE_CAPABILITIES = b.comment("Capability ids that are not synced through the generic module. curios:inventory travels through the Curios adapter instead.")
                .defineListAllowEmpty(List.of("excludeCapabilities"), () -> List.of("curios:inventory"), o -> o instanceof String);
        NETWORK_SERVERS = b.comment("The server ids (storage.serverId) of every backend of this network. A snapshot saved by any other server",
                        "(a test server or an old world pointed at the same database) is never applied on arrival: the newest snapshot",
                        "from a listed server is used instead. This server's own id always counts. Empty: snapshots from any server are applied.")
                .defineListAllowEmpty(List.of("networkServers"), List::of, o -> o instanceof String);
        b.pop();
        b.push("arrival");
        ARRIVAL_TIMEOUT_SECONDS = b.comment("How long an arriving player waits, frozen, for the server they came from to finish saving.")
                .defineInRange("timeoutSeconds", 10, 1, 300);
        KICK_ON_TIMEOUT = b.comment("After the wait: false applies the last known snapshot and tells the player it may be out of date; true refuses the login so they can try again.")
                .define("kickOnTimeout", false);
        b.pop();
        SPEC = b.build();
    }

    private SyncConfig() {}

    /** A config value, or the fallback while the config is not loaded yet (BSP-Core's pattern). */
    public static <T> T getOr(ForgeConfigSpec.ConfigValue<T> value, T fallback) {
        try {
            return value.get();
        } catch (IllegalStateException | NullPointerException e) {
            return fallback;
        }
    }
}
