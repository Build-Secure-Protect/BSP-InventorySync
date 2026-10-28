package com.mrgregles.bsp_inventorysync;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The one-time move of the connection settings from {@code bsp_inventorysync-server.toml} (a SERVER
 * config, which Forge sends to every player) into {@link NetworkConfig} (a COMMON config, which it
 * never sends). It runs at {@code ServerAboutToStartEvent}: the server config is loaded by then, nobody
 * can have joined, and the sync reads its settings later, at server started.
 *
 * <p>Each old value that is not its default is copied into the network file, unless the network file
 * already holds a value of its own, which wins; the old value is reset to its default either way, and
 * both files are saved. The log names the keys that moved, never a value.
 */
@Mod.EventBusSubscriber(modid = BSPInventorySync.MODID)
public final class ConfigMove {
    /** One key: its name in the server config, its old place, its new one. */
    record Move<T>(String key, ForgeConfigSpec.ConfigValue<T> from, ForgeConfigSpec.ConfigValue<T> to) {}

    private ConfigMove() {}

    static List<Move<?>> moves() {
        return List.of(
                new Move<>("storage.host", SyncConfig.DB_HOST, NetworkConfig.DB_HOST),
                new Move<>("storage.port", SyncConfig.DB_PORT, NetworkConfig.DB_PORT),
                new Move<>("storage.database", SyncConfig.DB_NAME, NetworkConfig.DB_NAME),
                new Move<>("storage.user", SyncConfig.DB_USER, NetworkConfig.DB_USER),
                new Move<>("storage.password", SyncConfig.DB_PASSWORD, NetworkConfig.DB_PASSWORD),
                new Move<>("storage.useSsl", SyncConfig.DB_SSL, NetworkConfig.DB_SSL),
                new Move<>("redis.host", SyncConfig.REDIS_HOST, NetworkConfig.REDIS_HOST),
                new Move<>("redis.port", SyncConfig.REDIS_PORT, NetworkConfig.REDIS_PORT),
                new Move<>("redis.username", SyncConfig.REDIS_USERNAME, NetworkConfig.REDIS_USERNAME),
                new Move<>("redis.password", SyncConfig.REDIS_PASSWORD, NetworkConfig.REDIS_PASSWORD));
    }

    @SubscribeEvent
    public static void onAboutToStart(ServerAboutToStartEvent event) {
        List<String> moved;
        try {
            moved = run(moves());
        } catch (IllegalStateException e) {
            BSPInventorySync.LOGGER.warn("BSP-InventorySync: the connection settings could not be checked for moving ({})", e.getMessage());
            return;
        }
        if (!moved.isEmpty()) {
            SyncConfig.SPEC.save();
            NetworkConfig.SPEC.save();
            BSPInventorySync.LOGGER.warn(message(moved));
        }
    }

    /** Moves every key whose old value is not its default; returns the keys that were set in the old file. */
    static List<String> run(List<Move<?>> moves) {
        List<String> moved = new ArrayList<>();
        for (Move<?> m : moves) {
            if (moveOne(m)) {
                moved.add(m.key());
            }
        }
        return moved;
    }

    private static <T> boolean moveOne(Move<T> m) {
        T old = m.from().get();
        if (Objects.equals(old, m.from().getDefault())) {
            return false;
        }
        if (Objects.equals(m.to().get(), m.to().getDefault())) {
            m.to().set(old); // the network file had nothing of its own here
        }
        m.from().set(m.from().getDefault());
        return true;
    }

    /** The log line: the keys only, never a value. */
    static String message(List<String> keys) {
        return "BSP-InventorySync: moved " + String.join(", ", keys) + " from bsp_inventorysync-server.toml to config/" + NetworkConfig.FILE
                + " (a value already set there was kept). The server config is sent to every player who joins: change the database and Redis passwords.";
    }
}
