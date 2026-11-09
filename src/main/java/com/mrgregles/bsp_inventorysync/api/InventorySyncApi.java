package com.mrgregles.bsp_inventorysync.api;

import com.mrgregles.bsp_inventorysync.data.ItemRules;
import com.mrgregles.bsp_inventorysync.data.Modules;
import com.mrgregles.bsp_inventorysync.sync.Sync;
import com.mrgregles.bsp_inventorysync.sync.SyncService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * What other mods may call. Everything else in the jar may change without notice. See
 * {@code docs/API.md}. Events are in {@code api.event}, on the Forge bus.
 */
public final class InventorySyncApi {
    /** Bumped when anything here changes shape. Compile-time constant: catch {@link LinkageError} to detect an older mod. */
    public static final int API_VERSION = 2;

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

    // ------------------------------------------------------------------ staff tools (API_VERSION 2, SU-IS2)

    /**
     * A player's items (API_VERSION 2): live when they are on this server and their data has arrived, otherwise
     * their newest snapshot, with Curios slots, backpacks and danks. A player online on another server is read
     * from their newest snapshot, up to one auto-save old ({@code online} true, {@code serverId} that server):
     * ask that server for the live view. Empty when there is nothing to show (no saved data, or the sync is off
     * and they are not on this server). Callable from any thread; the future completes on the server thread, so
     * never {@code join()} it there (it would hang the server): use {@code thenAccept}. It fails when the store
     * does, or when no server is running.
     */
    public static CompletableFuture<Optional<PlayerItems>> read(UUID player) {
        com.mrgregles.bsp_inventorysync.panel.PanelService p = panel();
        return p == null ? CompletableFuture.failedFuture(new IllegalStateException("no server running")) : p.read(player);
    }

    /**
     * Makes one change to a player's items (API_VERSION 2); see {@link EditResult.Outcome} for the answers.
     * <ul>
     * <li>On this server, live: {@code expectedVersion} is not used (the slot is checked instead). The state
     * before is saved and pinned, the change saved as an {@code ADMIN} snapshot noted "panel: {@code by}".</li>
     * <li>Offline: on their newest snapshot, only while it is still {@code expectedVersion}
     * ({@link PlayerItems#version()}); one change at a time per player on this server, written under the player's
     * checkout as {@code /invsync edit} offline does, saved under the server id of the snapshot it changes, the
     * version it started from pinned. They get it at their next login, on any server.</li>
     * <li>Online on another server: {@code ONLINE}, nothing changed.</li>
     * </ul>
     * A live Shatter Totem is never added or removed, not even inside another item. Offline, backpacks and danks
     * only give items up. Callable from any thread; the future completes on the server thread, so never
     * {@code join()} it there (it would hang the server). Failures are answered as {@code FAILED}; the future
     * itself fails only when no server is running.
     */
    public static CompletableFuture<EditResult> edit(UUID player, long expectedVersion, Edit change, String by) {
        com.mrgregles.bsp_inventorysync.panel.PanelService p = panel();
        return p == null ? CompletableFuture.failedFuture(new IllegalStateException("no server running")) : p.edit(player, expectedVersion, change, by);
    }

    /**
     * Saves a player of this server now, as an admin snapshot (API_VERSION 2). Any change to their inventory,
     * ender chest, Curios, backpacks or danks is saved by the next auto-save anyway (every 60 s by default, only
     * when something changed); this closes that window, so a change made some other way survives a crash.
     * Completes with the new version, or 0 when the sync is off here or their data is still arriving (nothing
     * is saved then). Call on the server thread; the future completes there (never {@code join()} it there), and
     * fails when the store does.
     */
    public static CompletableFuture<Long> saveNow(ServerPlayer player) {
        com.mrgregles.bsp_inventorysync.panel.PanelService p = panel();
        return p == null ? CompletableFuture.completedFuture(0L) : p.saveNow(player);
    }

    /**
     * A player's graves that still hold something, newest death first (API_VERSION 2, D30): on every server of the
     * network when the sync is on, else this server's. Callable from any thread; the future completes on the server
     * thread (never {@code join()} it there), and fails when graves are off here or the store fails.
     */
    public static CompletableFuture<java.util.List<GraveInfo>> graves(UUID player) {
        com.mrgregles.bsp_inventorysync.grave.GraveService s = com.mrgregles.bsp_inventorysync.grave.GraveService.active();
        if (s == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("graves are off on this server"));
        }
        return onServerThread(() -> com.mrgregles.bsp_inventorysync.grave.GraveActions.infos(s, player, System.currentTimeMillis()));
    }

    /**
     * What one of a player's graves holds (API_VERSION 2, Server Utilities' request SU-IS3): what a return would give back.
     * {@code id} is the grave's id or its first eight characters. Empty when they have no grave with that id, or it has
     * been emptied. Network-wide when the sync is on, as {@link #graves}. Read-only: nothing changes, nothing is logged.
     * Callable from any thread; the future completes on the server thread (never {@code join()} it there), and fails when
     * graves are off here or the store fails.
     */
    public static CompletableFuture<Optional<GraveItems>> graveItems(UUID player, String id) {
        com.mrgregles.bsp_inventorysync.grave.GraveService s = com.mrgregles.bsp_inventorysync.grave.GraveService.active();
        if (s == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("graves are off on this server"));
        }
        return onServerThread(() -> com.mrgregles.bsp_inventorysync.grave.GraveActions.items(s, player, id == null ? "" : id));
    }

    /**
     * Returns a grave to its owner (API_VERSION 2, D30), as {@code /invsync grave return} does: into their inventory now
     * when they are on this server (slots as they were, armour worn, backpack and dank contents, the XP; what does not
     * fit stays in the grave, recoverable with {@code /grave}), at their next login on any server when they are offline,
     * refused when they are on another server ({@code ONLINE}, return it there). Only a grave that could not be placed,
     * one put away, or one whose headstone is gone; a standing headstone is opened where it stands. {@code id} is the
     * grave's id or its first eight characters; {@code by} names who did it (log line). Callable from any thread; the
     * future completes on the server thread (never {@code join()} it there). Failures are answered as {@code FAILED}.
     */
    public static CompletableFuture<GraveReturn> returnGrave(UUID player, String id, String by) {
        com.mrgregles.bsp_inventorysync.grave.GraveService s = com.mrgregles.bsp_inventorysync.grave.GraveService.active();
        if (s == null) {
            return CompletableFuture.completedFuture(new GraveReturn(GraveReturn.Outcome.FAILED, "Graves are off on this server.", 0, 0, 0, ""));
        }
        String who = by == null || by.isBlank() ? "staff" : "panel: " + by.trim();
        return onServerThread(() -> com.mrgregles.bsp_inventorysync.grave.GraveActions.returnGrave(s, player, s.nameOf(player), id == null ? "" : id, who))
                .exceptionally(t -> new GraveReturn(GraveReturn.Outcome.FAILED, "Could not return the grave: " + (t.getCause() != null ? t.getCause().getMessage() : t.getMessage()) + ".",
                        0, 0, 0, s.serverIdNow()));
    }

    /** Runs the step on the server thread and hands its answer over there. */
    private static <T> CompletableFuture<T> onServerThread(java.util.function.Supplier<CompletableFuture<T>> step) {
        net.minecraft.server.MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("no server running"));
        }
        CompletableFuture<T> out = new CompletableFuture<>();
        server.execute(() -> {
            try {
                step.get().whenComplete((v, e) -> server.execute(() -> {
                    if (e != null) {
                        out.completeExceptionally(e);
                    } else {
                        out.complete(v);
                    }
                }));
            } catch (Throwable t) {
                out.completeExceptionally(t);
            }
        });
        return out;
    }

    @javax.annotation.Nullable
    private static com.mrgregles.bsp_inventorysync.panel.PanelService panel() {
        net.minecraft.server.MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return null;
        }
        return new com.mrgregles.bsp_inventorysync.panel.PanelService(server, Sync.active(), id -> server.getPlayerList().getPlayer(id), server);
    }
}
