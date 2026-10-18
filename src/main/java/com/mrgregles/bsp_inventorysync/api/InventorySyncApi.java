package com.mrgregles.bsp_inventorysync.api;

import com.mrgregles.bsp_inventorysync.data.ItemRules;
import com.mrgregles.bsp_inventorysync.data.Modules;
import com.mrgregles.bsp_inventorysync.sync.Sync;
import com.mrgregles.bsp_inventorysync.sync.SyncService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;
import java.util.function.Predicate;

/**
 * What other mods may call. Everything else in the jar may change without notice. See
 * {@code docs/API.md}. Events are in {@code api.event}, on the Forge bus.
 */
public final class InventorySyncApi {
    /** Bumped when anything here changes shape. Compile-time constant: catch {@link LinkageError} to detect an older mod. */
    public static final int API_VERSION = 1;

    private InventorySyncApi() {}

    /**
     * Adds a data module: your per-player data saved and restored with everything else. Register
     * during common setup. A module with the id of an existing one replaces it.
     */
    public static void registerModule(DataModule module) {
        Modules.register(module);
    }

    /** Items matching the rule are never written into a snapshot (their slot is saved empty). */
    public static void excludeItems(Predicate<ItemStack> rule) {
        ItemRules.addExclusion(rule);
    }

    /**
     * Whether the player's data is still arriving: between their login and the restore (or the
     * timeout fallback). While true the player is frozen; treat the inventory as not yet theirs.
     */
    public static boolean isSyncing(UUID player) {
        return SyncService.isSyncing(player);
    }

    /** Whether this server syncs at all (a shared database is configured). */
    public static boolean isEnabled() {
        return Sync.enabled();
    }

    /**
     * For a teleport mod about to send the player to another server (BSP-Teleport, after BSP-Core's
     * {@code BspTransfer.beginTransfer} and before the proxy switch): takes a snapshot now, so the save
     * is already in flight when they leave; the logout then saves once more, as a transfer. Returns
     * false when sync is off here or the player's own data is still arriving.
     */
    public static boolean beginTransfer(ServerPlayer player, String targetServer) {
        SyncService s = Sync.active();
        return s != null && s.beginTransfer(player, targetServer);
    }

    /** The transfer announced with {@link #beginTransfer} did not happen. */
    public static boolean cancelTransfer(ServerPlayer player) {
        SyncService s = Sync.active();
        return s != null && s.cancelTransfer(player);
    }
}
