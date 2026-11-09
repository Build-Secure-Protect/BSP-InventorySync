package com.mrgregles.bsp_inventorysync.panel;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.api.Edit;
import com.mrgregles.bsp_inventorysync.api.EditResult;
import com.mrgregles.bsp_inventorysync.api.EditResult.Outcome;
import com.mrgregles.bsp_inventorysync.api.PlayerItems;
import com.mrgregles.bsp_inventorysync.data.PlayerSnapshotCodec;
import com.mrgregles.bsp_inventorysync.storage.Presence;
import com.mrgregles.bsp_inventorysync.storage.SaveCause;
import com.mrgregles.bsp_inventorysync.storage.Snapshot;
import com.mrgregles.bsp_inventorysync.sync.OfflineWrite;
import com.mrgregles.bsp_inventorysync.sync.SyncService;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * The staff panel's reads and changes (Server Utilities' request SU-IS2, D29 and its audit), behind
 * {@code InventorySyncApi.read}, {@code edit} and {@code saveNow}.
 * <ul>
 * <li>A player on this server whose data has arrived is read and changed live. A change saves the state
 * before it (pinned) and itself (an {@code ADMIN} snapshot noted "panel: &lt;by&gt;") at once.</li>
 * <li>A player held by another server is read from their newest snapshot (up to an auto-save old) and not
 * changed here: {@code ONLINE} names the server where the change must be made.</li>
 * <li>An offline player is read from their newest snapshot and changed on it, as {@code /invsync edit}
 * offline: one change at a time, under the claimed checkout ({@link OfflineWrite}), refused when the version
 * moved, saved as an {@code ADMIN} snapshot with the note under the server id of the snapshot it changes (a
 * dank's frequency belongs to that server), the version it started from pinned. Backpacks and danks only give
 * items up there.</li>
 * </ul>
 * Everything that touches the game runs on {@code main} (the server thread); the futures complete there.
 */
public final class PanelService {
    private final MinecraftServer server;
    @Nullable private final SyncService sync;
    private final Function<UUID, ServerPlayer> here;
    private final Executor main;

    /**
     * @param here the player with that id on this server, or null (the player list; tests give their mock players)
     * @param main runs a task on the server thread (the server itself; tests run it at once)
     */
    public PanelService(MinecraftServer server, @Nullable SyncService sync, Function<UUID, ServerPlayer> here, Executor main) {
        this.server = server;
        this.sync = sync;
        this.here = here;
        this.main = main;
    }

    // ------------------------------------------------------------------ read

    public CompletableFuture<Optional<PlayerItems>> read(UUID id) {
        return toMain(onMain(() -> {
            ServerPlayer player = here.apply(id);
            if (player != null && settled(id)) {
                var areas = LiveItems.read(player);
                if (sync == null) {
                    return CompletableFuture.completedFuture(Optional.of(new PlayerItems(id, true, serverId(), true, 0, 0, areas)));
                }
                return sync.networkLatest(id).thenApply(latest -> Optional.of(new PlayerItems(id, true, serverId(), true,
                        latest.map(Snapshot::version).orElse(0L), latest.map(Snapshot::savedAt).orElse(0L), areas)));
            }
            if (sync == null) {
                return CompletableFuture.completedFuture(Optional.<PlayerItems>empty());
            }
            boolean arrivingHere = player != null;
            return sync.store().presence(id).thenCombine(sync.networkLatest(id), Seen::new)
                    .thenCompose(seen -> onMain(() -> {
                        if (seen.latest().isEmpty()) {
                            return CompletableFuture.completedFuture(Optional.<PlayerItems>empty());
                        }
                        Snapshot s = seen.latest().get();
                        boolean online = arrivingHere || seen.presence().map(Presence::online).orElse(false);
                        String where = arrivingHere ? serverId() : seen.presence().map(Presence::serverId).orElse(s.serverId());
                        var areas = SnapshotItems.read(PlayerSnapshotCodec.decode(s.data()), false);
                        return CompletableFuture.completedFuture(Optional.of(new PlayerItems(id, online, where, false, s.version(), s.savedAt(), areas)));
                    }));
        }));
    }

    private record Seen(Optional<Presence> presence, Optional<Snapshot> latest) {}

    // ------------------------------------------------------------------ edit

    public CompletableFuture<EditResult> edit(UUID id, long expectedVersion, @Nullable Edit change, @Nullable String by) {
        String who = by == null || by.isBlank() ? "staff" : by.trim();
        return toMain(onMain(() -> {
            Planner.Plan bad = Planner.malformed(change);
            if (bad != null) {
                return done(bad.refused(), bad.message());
            }
            if (sync != null && sync.stopping()) {
                return done(Outcome.FAILED, "This server is stopping; nothing was changed.");
            }
            ServerPlayer player = here.apply(id);
            if (player != null) {
                if (sync != null && sync.refused(id)) {
                    return done(Outcome.FAILED, name(id) + "'s login here was refused (their data could not be loaded), so nothing of theirs is changed or saved here.");
                }
                if (!settled(id)) {
                    return done(Outcome.BUSY, name(id) + "'s data is still arriving here; try again in a moment.");
                }
                return live(player, change, who);
            }
            if (sync == null) {
                return done(Outcome.FAILED, name(id) + " is not on this server, and changing a player who is not here needs the sync on.");
            }
            String name = name(id);
            return OfflineWrite.run(sync, id,
                    () -> done(Outcome.BUSY, "This server is still saving " + name + " or writing another change of theirs; try again in a moment."),
                    held -> CompletableFuture.completedFuture(new EditResult(Outcome.ONLINE, name + " is on " + held.serverId() + "; make the change there.", 0, 0, held.serverId())),
                    () -> offline(id, name, expectedVersion, change, who))
                    .exceptionally(t -> result(Outcome.FAILED, "Could not change " + name + ": " + reason(t) + ". Nothing was changed."));
        }));
    }

    private CompletableFuture<EditResult> live(ServerPlayer player, Edit change, String who) {
        String name = player.getGameProfile().getName();
        Map<String, SnapshotItems.Area> areas = SnapshotItems.areas(LiveItems.root(player));
        boolean linked = SnapshotItems.places(areas).linked().contains(change.area());
        SlotView view;
        Planner.Plan plan;
        try {
            view = LiveItems.view(player, change.area());
            plan = Planner.plan(change.area(), view, !linked && SnapshotItems.addable(change.area(), true), !linked, change, s -> SnapshotItems.carriesTotem(s, areas));
        } catch (RuntimeException | LinkageError e) {
            BSPInventorySync.LOGGER.error("BSP-InventorySync: panel: {}'s change to {} could not be planned", who, name, e);
            return done(Outcome.FAILED, "Could not read " + name + "'s " + change.area() + " (" + reason(e) + "); nothing was changed.");
        }
        if (plan.refused() != null) {
            return done(plan.refused(), plan.message());
        }
        CompletableFuture<Snapshot> before = sync == null ? null : sync.save(player, SaveCause.ADMIN); // the state before, captured now
        try {
            plan.commit(view);
        } catch (RuntimeException | LinkageError e) {
            BSPInventorySync.LOGGER.error("BSP-InventorySync: panel: {}'s change to {} failed and was undone", who, name, e);
            LiveItems.broadcast(player);
            return done(Outcome.FAILED, "Could not change " + name + "'s " + change.area() + " (" + reason(e) + "); nothing was changed.");
        }
        LiveItems.broadcast(player);
        BSPInventorySync.LOGGER.info("BSP-InventorySync: panel: {} changed {}'s items (online here): {}", who, name, plan.message());
        String line = "Changed " + name + "'s items: " + plan.message() + ".";
        if (sync == null) {
            return done(Outcome.OK, line + " The sync is off here, so it is not saved by this mod.");
        }
        CompletableFuture<Snapshot> after = sync.save(player, SaveCause.ADMIN, "panel: " + who);
        CompletableFuture<Before> kept = before
                .thenCompose(b -> sync.store().pin(player.getUUID(), b.version(), true)
                        .handle((ok, e) -> new Before(b.version(), e == null && Boolean.TRUE.equals(ok), e == null ? "not found" : reason(e))))
                .exceptionally(t -> new Before(0, false, reason(t)));
        return after.handle((a, e) -> e == null ? a : null).thenCombine(kept, (a, b) -> {
            String undo = b.pinned() ? " The state before is v" + b.version() + " (pinned)."
                    : b.version() > 0 ? " The state before is v" + b.version() + " but could not be pinned (" + b.problem() + ")."
                    : " The state before could not be saved (" + b.problem() + ").";
            if (a == null) {
                return new EditResult(Outcome.OK, line + " It could not be saved yet; it is saved with " + name + "'s next save." + undo, 0, b.pinned() ? b.version() : 0, serverId());
            }
            return new EditResult(Outcome.OK, line + " Saved as v" + a.version() + "." + undo, a.version(), b.pinned() ? b.version() : 0, serverId());
        });
    }

    private record Before(long version, boolean pinned, String problem) {}

    /** With the checkout held: the version check, the change on the snapshot, the write and the pin. */
    private CompletableFuture<EditResult> offline(UUID id, String name, long expectedVersion, Edit change, String who) {
        return sync.networkLatest(id).thenCompose(latest -> {
            if (latest.isEmpty()) {
                return CompletableFuture.completedFuture(result(Outcome.FAILED, name + " has no saved data to change."));
            }
            Snapshot from = latest.get();
            if (from.version() != expectedVersion) {
                return CompletableFuture.completedFuture(result(Outcome.CHANGED, name + "'s data changed since it was read (now v" + from.version() + ", read v" + expectedVersion + "); read it again."));
            }
            return onMain(() -> {
                CompoundTag root = PlayerSnapshotCodec.decode(from.data());
                Map<String, SnapshotItems.Area> areas = SnapshotItems.areas(root);
                SnapshotItems.Area area = areas.get(change.area());
                String module = SnapshotItems.vanillaModule(change.area());
                if (area == null && module != null) {
                    return CompletableFuture.completedFuture(result(Outcome.FAILED, name + "'s saved data has no " + module + " section (it failed to save); nothing was changed."));
                }
                boolean linked = SnapshotItems.places(areas).linked().contains(change.area());
                Planner.Plan plan = Planner.plan(change.area(), area == null ? null : area.view(), !linked && SnapshotItems.addable(change.area(), false), !linked,
                        change, s -> SnapshotItems.carriesTotem(s, areas));
                if (plan.refused() != null) {
                    return CompletableFuture.completedFuture(result(plan.refused(), plan.message()));
                }
                plan.commit(area.view());
                byte[] data = PlayerSnapshotCodec.encode(PlayerSnapshotCodec.withNote(root, "panel: " + who));
                return sync.store().save(id, from.serverId(), SaveCause.ADMIN, data)
                        .thenCompose(saved -> sync.store().pin(id, from.version(), true).handle((ok, e) -> {
                            BSPInventorySync.LOGGER.info("BSP-InventorySync: panel: {} changed {}'s saved items (v{} to v{}): {}", who, name, from.version(), saved.version(), plan.message());
                            boolean pinned = e == null && Boolean.TRUE.equals(ok);
                            String undo = pinned ? "the state before is v" + from.version() + " (pinned)"
                                    : "the state before is v" + from.version() + " but could not be pinned (" + (e == null ? "not found" : reason(e)) + ")";
                            return new EditResult(Outcome.OK, "Changed " + name + "'s saved items: " + plan.message() + ". Saved as v" + saved.version()
                                    + "; " + undo + ". They get it at their next login.", saved.version(), pinned ? from.version() : 0, serverId());
                        }));
            });
        });
    }

    // ------------------------------------------------------------------ save now

    /**
     * Saves an online player of this server now (an admin snapshot), so a change made to them some other way
     * survives a crash before the next auto-save. The new version; 0 when the sync is off here or their data is
     * still arriving (nothing is saved then: what this server holds for them is not theirs yet). Call on the
     * server thread; the future completes there, and fails when the store does.
     */
    public CompletableFuture<Long> saveNow(ServerPlayer player) {
        if (sync == null || !settled(player.getUUID())) {
            return CompletableFuture.completedFuture(0L);
        }
        return toMain(sync.save(player, SaveCause.ADMIN).thenApply(Snapshot::version));
    }

    // ------------------------------------------------------------------ helpers

    private boolean settled(UUID id) {
        return !SyncService.isSyncing(id) && (sync == null || sync.settled(id));
    }

    private String serverId() {
        return sync == null ? "" : sync.serverId();
    }

    private String name(UUID id) {
        ServerPlayer p = here.apply(id);
        if (p != null) {
            return p.getGameProfile().getName();
        }
        var cache = server.getProfileCache();
        return cache == null ? id.toString() : cache.get(id).map(com.mojang.authlib.GameProfile::getName).orElse(id.toString());
    }

    private EditResult result(Outcome outcome, String message) {
        return new EditResult(outcome, message, 0, 0, serverId());
    }

    private CompletableFuture<EditResult> done(Outcome outcome, String message) {
        return CompletableFuture.completedFuture(result(outcome, message));
    }

    private static String reason(Throwable t) {
        Throwable c = t instanceof java.util.concurrent.CompletionException && t.getCause() != null ? t.getCause() : t;
        if (c instanceof java.util.concurrent.TimeoutException) {
            return "the database did not answer in time";
        }
        return c.getMessage() == null ? c.toString() : c.getMessage();
    }

    /** The same answer, handed over on the server thread. */
    private <T> CompletableFuture<T> toMain(CompletableFuture<T> f) {
        CompletableFuture<T> out = new CompletableFuture<>();
        f.whenComplete((value, error) -> main.execute(() -> {
            if (error != null) {
                out.completeExceptionally(error);
            } else {
                out.complete(value);
            }
        }));
        return out;
    }

    /** Runs the step on the server thread and hands back its future's answer. */
    private <T> CompletableFuture<T> onMain(java.util.function.Supplier<CompletableFuture<T>> step) {
        CompletableFuture<T> out = new CompletableFuture<>();
        main.execute(() -> {
            try {
                step.get().whenComplete((value, error) -> {
                    if (error != null) {
                        out.completeExceptionally(error);
                    } else {
                        out.complete(value);
                    }
                });
            } catch (Throwable t) {
                out.completeExceptionally(t);
            }
        });
        return out;
    }
}
