package com.mrgregles.bsp_inventorysync.gametest;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.NetworkConfig;
import com.mrgregles.bsp_inventorysync.SyncConfig;
import com.mrgregles.bsp_inventorysync.storage.JdbcStore;
import com.mrgregles.bsp_inventorysync.sync.Sync;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * The connection settings' move out of the SERVER config (sent to every player) into
 * {@code config/bsp_inventorysync-network.toml} (never sent): what moves, what wins, that it happens
 * once, that the log names keys and never values, and where the store reads its settings from after.
 * The real config values are changed for the test and put back afterwards.
 */
@GameTestHolder(BSPInventorySync.MODID)
@PrefixGameTestTemplate(false)
public class ConfigMoveTests {
    private static final String EMPTY = "empty";

    /** Every value a test touches, to put back. */
    private static final List<ForgeConfigSpec.ConfigValue<?>> TOUCHED = List.of(
            SyncConfig.DB_HOST, SyncConfig.DB_PORT, SyncConfig.DB_NAME, SyncConfig.DB_USER, SyncConfig.DB_PASSWORD, SyncConfig.DB_SSL,
            SyncConfig.REDIS_HOST, SyncConfig.REDIS_PORT, SyncConfig.REDIS_USERNAME, SyncConfig.REDIS_PASSWORD, SyncConfig.USE_BSP_CORE_DATABASE,
            NetworkConfig.DB_HOST, NetworkConfig.DB_PORT, NetworkConfig.DB_NAME, NetworkConfig.DB_USER, NetworkConfig.DB_PASSWORD, NetworkConfig.DB_SSL,
            NetworkConfig.REDIS_HOST, NetworkConfig.REDIS_PORT, NetworkConfig.REDIS_USERNAME, NetworkConfig.REDIS_PASSWORD);

    private record Saved<T>(ForgeConfigSpec.ConfigValue<T> value, T was) {
        void putBack() {
            value.set(was);
        }
    }

    private static <T> Saved<T> save(ForgeConfigSpec.ConfigValue<T> v) {
        return new Saved<>(v, v.get());
    }

    private static List<Saved<?>> saveAll() {
        List<Saved<?>> out = new ArrayList<>();
        TOUCHED.forEach(v -> out.add(save(v)));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> move() {
        try {
            Class<?> cls = Class.forName("com.mrgregles.bsp_inventorysync.ConfigMove");
            Method moves = cls.getDeclaredMethod("moves");
            Method run = cls.getDeclaredMethod("run", List.class);
            moves.setAccessible(true);
            run.setAccessible(true);
            return (List<String>) run.invoke(null, moves.invoke(null));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String message(List<String> keys) {
        try {
            Method m = Class.forName("com.mrgregles.bsp_inventorysync.ConfigMove").getDeclaredMethod("message", List.class);
            m.setAccessible(true);
            return (String) m.invoke(null, keys);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @GameTest(template = EMPTY)
    public static void the_connection_moves_once_out_of_the_server_config_and_the_log_shows_no_password(GameTestHelper h) {
        List<Saved<?>> before = saveAll();
        try {
            TOUCHED.forEach(ConfigMoveTests::reset);
            // as a server that kept its connection in bsp_inventorysync-server.toml
            SyncConfig.DB_HOST.set("db.example.net");
            SyncConfig.DB_PORT.set(3307);
            SyncConfig.DB_PASSWORD.set("pw-secret-123");
            SyncConfig.REDIS_PASSWORD.set("redis-secret-456");
            NetworkConfig.DB_HOST.set("keep.me.example"); // already set in the new file: it wins

            List<String> moved = move();
            h.assertTrue(moved.equals(List.of("storage.host", "storage.port", "storage.password", "redis.password")), "the keys set in the old file: " + moved);
            h.assertTrue(NetworkConfig.DB_PASSWORD.get().equals("pw-secret-123") && NetworkConfig.REDIS_PASSWORD.get().equals("redis-secret-456"), "both passwords are in the network file");
            h.assertTrue(NetworkConfig.DB_PORT.get() == 3307, "the port moved: " + NetworkConfig.DB_PORT.get());
            h.assertTrue(NetworkConfig.DB_HOST.get().equals("keep.me.example"), "a value already in the network file wins: " + NetworkConfig.DB_HOST.get());
            h.assertTrue(SyncConfig.DB_PASSWORD.get().isEmpty() && SyncConfig.REDIS_PASSWORD.get().isEmpty() && SyncConfig.DB_HOST.get().equals("localhost") && SyncConfig.DB_PORT.get() == 3306,
                    "the server config is back to its defaults, the passwords gone from it");

            String line = message(moved);
            h.assertTrue(line.contains("storage.password") && line.contains("config/bsp_inventorysync-network.toml"), "the log names the keys and the new file: " + line);
            h.assertTrue(!line.contains("pw-secret") && !line.contains("redis-secret") && !line.contains("db.example") && !line.contains("keep.me"), "and never a value: " + line);

            h.assertTrue(move().isEmpty(), "a second start moves nothing");
            h.assertTrue(NetworkConfig.DB_PASSWORD.get().equals("pw-secret-123"), "and leaves the network file alone");
        } finally {
            before.forEach(Saved::putBack);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void the_store_reads_its_own_connection_from_the_network_file_or_bsp_cores(GameTestHelper h) {
        List<Saved<?>> before = saveAll();
        try {
            TOUCHED.forEach(ConfigMoveTests::reset);
            SyncConfig.USE_BSP_CORE_DATABASE.set(false);
            NetworkConfig.DB_HOST.set("db.internal");
            NetworkConfig.DB_PASSWORD.set("pw-secret-789");
            SyncConfig.DB_HOST.set("stale.example"); // a value left behind in the old file is not read
            JdbcStore.Settings own = Sync.databaseSettings();
            h.assertTrue(own.host().equals("db.internal") && own.password().equals("pw-secret-789"), "this mod's own connection comes from the network file: " + own.host());
            h.assertTrue(own.describe().contains("config/bsp_inventorysync-network.toml") && !own.describe().contains("pw-secret"), "named by its file, without the password: " + own.describe());

            SyncConfig.USE_BSP_CORE_DATABASE.set(true);
            JdbcStore.Settings core = Sync.databaseSettings();
            h.assertTrue(core.describe().contains("BSP-Core's config/bsp_core-network.toml"), "BSP-Core's settings are named by its network file: " + core.describe());
        } finally {
            before.forEach(Saved::putBack);
        }
        h.succeed();
    }

    private static <T> void reset(ForgeConfigSpec.ConfigValue<T> v) {
        v.set(v.getDefault());
    }
}
