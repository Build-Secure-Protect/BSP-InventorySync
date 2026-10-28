package com.mrgregles.bsp_inventorysync;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * The network connection: this mod's own database and Redis settings, in a COMMON config,
 * {@code config/bsp_inventorysync-network.toml}. Forge sends every SERVER config file to each player at
 * login, so a password kept in {@code bsp_inventorysync-server.toml} reached everyone who joined; a
 * COMMON config is never sent. Never ship this file in the client modpack.
 *
 * <p>The database fields are used only when {@code storage.useBspCoreDatabase} is false in the SERVER
 * config; the Redis fields whenever {@code redis.enabled} is true. Values that were in the SERVER config
 * are moved here once, at the first start ({@link ConfigMove}).
 */
public final class NetworkConfig {
    public static final String FILE = "bsp_inventorysync-network.toml";

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.ConfigValue<String> DB_HOST;
    public static final ForgeConfigSpec.IntValue DB_PORT;
    public static final ForgeConfigSpec.ConfigValue<String> DB_NAME;
    public static final ForgeConfigSpec.ConfigValue<String> DB_USER;
    public static final ForgeConfigSpec.ConfigValue<String> DB_PASSWORD;
    public static final ForgeConfigSpec.BooleanValue DB_SSL;
    public static final ForgeConfigSpec.ConfigValue<String> REDIS_HOST;
    public static final ForgeConfigSpec.IntValue REDIS_PORT;
    public static final ForgeConfigSpec.ConfigValue<String> REDIS_USERNAME;
    public static final ForgeConfigSpec.ConfigValue<String> REDIS_PASSWORD;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.comment("The database, used when storage.useBspCoreDatabase is false in bsp_inventorysync-server.toml (true: BSP-Core's",
                "config/bsp_core-network.toml is used instead). This file stays on the server: it is never sent to players.").push("database");
        DB_HOST = b.define("host", "localhost");
        DB_PORT = b.defineInRange("port", 3306, 1, 65535);
        DB_NAME = b.define("database", "bsp");
        DB_USER = b.define("user", "bsp");
        DB_PASSWORD = b.comment("Plain text: keep this file private, and never put it in the client modpack.").define("password", "");
        DB_SSL = b.define("useSsl", false);
        b.pop();
        b.comment("Redis, used when redis.enabled is true in bsp_inventorysync-server.toml.").push("redis");
        REDIS_HOST = b.define("host", "localhost");
        REDIS_PORT = b.defineInRange("port", 6379, 1, 65535);
        REDIS_USERNAME = b.comment("Leave empty unless Redis uses ACL users; the password alone is the usual requirepass.").define("username", "");
        REDIS_PASSWORD = b.comment("Plain text: keep this file private, and never put it in the client modpack.").define("password", "");
        b.pop();
        SPEC = b.build();
    }

    private NetworkConfig() {}
}
