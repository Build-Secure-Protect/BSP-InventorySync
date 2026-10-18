package com.mrgregles.bsp_inventorysync.sync;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.api.event.InventoryRestoredEvent;
import com.mrgregles.bsp_inventorysync.api.event.InventorySavedEvent;
import com.mrgregles.bsp_inventorysync.compat.BspCore;
import com.mrgregles.bsp_inventorysync.data.PlayerSnapshotCodec;
import com.mrgregles.bsp_inventorysync.storage.Presence;
import com.mrgregles.bsp_inventorysync.storage.SaveCause;
import com.mrgregles.bsp_inventorysync.storage.Snapshot;
import com.mrgregles.bsp_inventorysync.storage.SyncStore;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The sync on one server: saves players to the shared {@link SyncStore}, restores them on arrival,
 * and runs the arrival handshake of D5.
 *
 * <p><b>The handshake.</b> The store's presence row is the checkout: a server marks a player online
 * on itself when it has applied their data, and offline only after its last save of them has landed.
 * An arriving player is frozen ({@link #isSyncing}) while this server polls the row every tick; the
 * moment the source has released them (offline, or never seen, or last held here) this server takes
 * the row, reads the newest snapshot and applies it. If the source does not release them within the
 * policy's timeout, the last known snapshot is applied and the player told, or the login is refused
 * (D5). Redis (task IS-5) makes the same handshake faster; nothing here changes for it.
 *
 * <p><b>The totem rules</b> (D4) sit around the data modules: a snapshot never carries a live Shatter
 * Totem (the exclusion {@code compat.BspCore} installs), and a restore keeps every live totem the
 * player holds and gives it back through BSP-Core afterwards. {@link InventoryRestoredEvent} is
 * posted after that; {@link InventorySavedEvent} once the store confirms a save.
 *
 * <p><b>Races.</b> A player who leaves before their restore is never saved (their inventory here is
 * stale) and never marked online here. A player who arrives dead waits for the respawn, and the data
 * is applied to the respawned player. A death saves at respawn, when the drops have left the
 * inventory. {@code beginTransfer} (BSP-Teleport, request T1) takes an early snapshot before the proxy
 * switch; the logout then saves again, as a transfer.
 *
 * <p>Everything here runs on the server thread; store futures hop back with {@code server.execute}.
 */
public final class SyncService {
    private static final Set<UUID> SYNCING = ConcurrentHashMap.newKeySet();
    private static final double TELEPORT_DISTANCE_SQ = 4 * 4; // a jump this big in one tick is a teleport (a landing), not walking

    /** An arrival in progress. */
    private final class Pending {
        ServerPlayer player;
        final long started;
        Vec3 anchor;
        ResourceKey<Level> anchorDim;
        boolean queryInFlight;
        boolean acquired;
        boolean waitingForRespawn;
        @Nullable Snapshot deferred;
        boolean deferredFallback;

        Pending(ServerPlayer player, long started) {
            this.player = player;
            this.started = started;
            this.anchor = player.position();
            this.anchorDim = player.level().dimension();
        }
    }

    private final MinecraftServer server;
    private final SyncStore store;
    private final String serverId;
    private final ArrivalPolicy policy;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Set<UUID> heldHere = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> transfers = new ConcurrentHashMap<>();
    private final Map<UUID, Arrival> arrivals = new ConcurrentHashMap<>();
    private final Set<CompletableFuture<?>> inFlightSaves = ConcurrentHashMap.newKeySet();
    private final Map<UUID, CompoundTag> lastSavedTag = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastSaveAt = new ConcurrentHashMap<>();
    private volatile int snapshotsKept = 16;
    private volatile long autosaveMs = 60_000;
    private volatile long storeTimeoutMs = 5_000;
    private volatile long skippedAutosaves, writtenAutosaves, storeTimeouts;

    public SyncService(MinecraftServer server, SyncStore store, String serverId) {
        this(server, store, serverId, ArrivalPolicy.DEFAULT);
    }

    public SyncService(MinecraftServer server, SyncStore store, String serverId, ArrivalPolicy policy) {
        this.server = server;
        this.store = store;
        this.serverId = serverId;
        this.policy = policy;
    }

    public SyncStore store() {
        return store;
    }

    public String serverId() {
        return serverId;
    }

    public ArrivalPolicy policy() {
        return policy;
    }

    /** How many unpinned snapshots each save leaves behind (D13); pinned ones are never pruned. */
    public void setSnapshotsKept(int keep) {
        snapshotsKept = Math.max(1, keep);
    }

    public int snapshotsKept() {
        return snapshotsKept;
    }

    /** How often a settled player whose data changed is saved (D7); 0 turns the auto-save off. */
    public void setAutosaveMs(long ms) {
        autosaveMs = Math.max(0, ms);
    }

    /** The watchdog: how long any one store call may take before it is given up as failed. */
    public void setStoreTimeoutMs(long ms) {
        storeTimeoutMs = Math.max(100, ms);
    }

    /** Counters for {@code /invsync status}: auto-saves skipped because nothing changed, written, and store calls the watchdog cut off. */
    public long skippedAutosaves() {
        return skippedAutosaves;
    }

    public long writtenAutosaves() {
        return writtenAutosaves;
    }

    public long storeTimeouts() {
        return storeTimeouts;
    }

    /**
     * The watchdog around a store future: a call that does not answer within the store timeout fails
     * with a {@link java.util.concurrent.TimeoutException}, so a frozen database never leaves a player
     * frozen, a save hanging or the shutdown waiting. The failure is logged once per call.
     */
    private <T> CompletableFuture<T> guard(CompletableFuture<T> future, String what) {
        return future.orTimeout(storeTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS).whenComplete((v, error) -> {
            if (error instanceof java.util.concurrent.TimeoutException || (error != null && error.getCause() instanceof java.util.concurrent.TimeoutException)) {
                storeTimeouts++;
                BSPInventorySync.LOGGER.error("BSP-InventorySync: the store did not answer within {} ms ({})", storeTimeoutMs, what);
            }
        });
    }

    /** Whether a player's data is still arriving: frozen, inventory not yet theirs. */
    public static boolean isSyncing(UUID player) {
        return SYNCING.contains(player);
    }

    /** The last arrival of a player on this server, if any. */
    public Optional<Arrival> lastArrival(UUID player) {
        return Optional.ofNullable(arrivals.get(player));
    }

    /** Players waiting for their data right now. */
    public int waiting() {
        return pending.size();
    }

    // ------------------------------------------------------------------ saving

    /**
     * Takes the player's snapshot now (server thread) and writes it to the store. The future completes
     * with the stored snapshot; {@link InventorySavedEvent} is posted on the server thread when it does.
     */
    public CompletableFuture<Snapshot> save(ServerPlayer player, SaveCause cause) {
        return save(player, PlayerSnapshotCodec.capture(player), cause);
    }

    private CompletableFuture<Snapshot> save(ServerPlayer player, CompoundTag tag, SaveCause cause) {
        byte[] data = PlayerSnapshotCodec.encode(tag);
        UUID id = player.getUUID();
        String name = player.getGameProfile().getName();
        CompletableFuture<Snapshot> future = guard(store.save(id, serverId, cause, data), "save " + name + " " + cause.id());
        inFlightSaves.add(future);
        return future.whenComplete((snapshot, error) -> {
            inFlightSaves.remove(future);
            if (error != null) {
                BSPInventorySync.LOGGER.error("BSP-InventorySync: saving {} ({}) failed: {}", name, cause.id(), error.toString());
                return;
            }
            lastSavedTag.put(id, tag);
            store.prune(id, snapshotsKept).whenComplete((n, pruneError) -> {
                if (pruneError != null) {
                    BSPInventorySync.LOGGER.warn("BSP-InventorySync: pruning {}'s snapshots failed: {}", name, pruneError.toString());
                }
            });
            server.execute(() -> MinecraftForge.EVENT_BUS.post(new InventorySavedEvent(id, name, cause, snapshot.version(), snapshot.size(), serverId)));
        });
    }

    /** Saves every listed player whose data is not still arriving. */
    public List<CompletableFuture<Snapshot>> saveAll(Collection<ServerPlayer> players, SaveCause cause) {
        List<CompletableFuture<Snapshot>> out = new ArrayList<>();
        for (ServerPlayer player : players) {
            if (!isSyncing(player.getUUID())) {
                out.add(save(player, cause));
            }
        }
        return out;
    }

    /**
     * The auto-save (D7, Plus's idea without its bookkeeping): a settled player is captured when their
     * turn comes round, and written only if the capture differs from what was last saved for them;
     * an unchanged player costs one capture and no store call. Empty when nothing was written.
     */
    public Optional<CompletableFuture<Snapshot>> autosave(ServerPlayer player, long now) {
        UUID id = player.getUUID();
        if (isSyncing(id)) {
            return Optional.empty();
        }
        CompoundTag tag = PlayerSnapshotCodec.capture(player);
        lastSaveAt.put(id, now);
        if (tag.equals(lastSavedTag.get(id))) {
            skippedAutosaves++;
            return Optional.empty();
        }
        writtenAutosaves++;
        return Optional.of(save(player, tag, SaveCause.AUTOSAVE));
    }

    /** Every settled player whose last save (or last check) is older than the auto-save interval. */
    public int autosaveDue(Collection<ServerPlayer> players, long now) {
        if (autosaveMs <= 0) {
            return 0;
        }
        int written = 0;
        for (ServerPlayer player : players) {
            UUID id = player.getUUID();
            if (isSyncing(id)) {
                continue;
            }
            Long last = lastSaveAt.get(id);
            if (last == null) {
                lastSaveAt.put(id, now); // first seen (the mod was switched on while they were online): their turn comes one interval from now
                continue;
            }
            if (now - last >= autosaveMs && autosave(player, now).isPresent()) {
                written++;
            }
        }
        return written;
    }

    // ------------------------------------------------------------------ restoring

    /** What a restore did. */
    public record Restored(long version, String sourceServer, PlayerSnapshotCodec.Result modules, int totemsKept) {}

    /**
     * Applies a snapshot to an online player, keeping any live totem they hold, and posts
     * {@link InventoryRestoredEvent}. Server thread.
     */
    public Restored restore(ServerPlayer player, Snapshot snapshot, boolean fallback) {
        CompoundTag tag = PlayerSnapshotCodec.decode(snapshot.data());
        List<ItemStack> totems = BspCore.takeLiveTotems(player);
        PlayerSnapshotCodec.Result result = PlayerSnapshotCodec.apply(player, tag, new com.mrgregles.bsp_inventorysync.data.RestoreContext(snapshot.serverId(), serverId));
        lastSavedTag.put(player.getUUID(), tag);
        for (ItemStack totem : totems) {
            BspCore.giveBack(player, totem);
        }
        if (!totems.isEmpty()) {
            BSPInventorySync.LOGGER.info("BSP-InventorySync: {} held {} totem(s) during the restore; given back", player.getGameProfile().getName(), totems.size());
        }
        MinecraftForge.EVENT_BUS.post(new InventoryRestoredEvent(player, snapshot.version(), snapshot.serverId(), fallback, result.failed(), totems.size()));
        return new Restored(snapshot.version(), snapshot.serverId(), result, totems.size());
    }

    /**
     * Reads the player's newest snapshot and applies it on the server thread, outside the handshake
     * (an operator's {@code /invsync resync}). Empty when the store has none; the event still fires
     * with version 0.
     */
    public CompletableFuture<Optional<Restored>> restoreLatest(ServerPlayer player) {
        return guard(store.latest(player.getUUID()), "latest " + player.getGameProfile().getName()).thenCompose(latest -> {
            CompletableFuture<Optional<Restored>> out = new CompletableFuture<>();
            server.execute(() -> {
                try {
                    if (latest.isPresent()) {
                        out.complete(Optional.of(restore(player, latest.get(), false)));
                    } else {
                        MinecraftForge.EVENT_BUS.post(new InventoryRestoredEvent(player, 0, "", false, List.of(), 0));
                        out.complete(Optional.empty());
                    }
                } catch (Throwable t) {
                    out.completeExceptionally(t);
                }
            });
            return out;
        });
    }

    // ------------------------------------------------------------------ the handshake

    /** A player has logged in (after BSP-Teleport's landing): freeze them and start waiting for the source. */
    public void onLogin(ServerPlayer player, long now) {
        UUID id = player.getUUID();
        SYNCING.add(id);
        pending.put(id, new Pending(player, now));
        player.setDeltaMovement(Vec3.ZERO);
    }

    /** Once a tick: polls the source's release for every waiting player, holds them still, times out, and runs the auto-save sweep. */
    public void tick(long now) {
        if (autosaveMs > 0 && server.getPlayerList() != null) {
            autosaveDue(server.getPlayerList().getPlayers(), now);
        }
        if (pending.isEmpty()) {
            return;
        }
        for (Pending p : new ArrayList<>(pending.values())) {
            holdStill(p);
            if (p.waitingForRespawn || p.queryInFlight) {
                continue;
            }
            if (p.acquired) {
                continue; // the arrival is being read from the store
            }
            if (now - p.started >= policy.timeoutMs()) {
                timedOut(p, now);
                continue;
            }
            p.queryInFlight = true;
            UUID id = p.player.getUUID();
            guard(store.presence(id), "presence " + p.player.getGameProfile().getName()).whenComplete((presence, error) -> server.execute(() -> {
                p.queryInFlight = false;
                if (pending.get(id) != p) {
                    return; // left meanwhile
                }
                if (error != null) {
                    BSPInventorySync.LOGGER.warn("BSP-InventorySync: could not read where {} is: {}", p.player.getGameProfile().getName(), error.toString());
                    return; // try again next tick, until the timeout
                }
                if (presence.isEmpty() || !presence.get().onlineElsewhere(serverId)) {
                    arrive(p, now, false);
                }
            }));
        }
    }

    /** Keeps a frozen player where they logged in; a jump of a teleport's size (a landing) moves the anchor instead. */
    private void holdStill(Pending p) {
        ServerPlayer player = p.player;
        Vec3 pos = player.position();
        if (player.level().dimension() != p.anchorDim || pos.distanceToSqr(p.anchor) >= TELEPORT_DISTANCE_SQ) {
            p.anchor = pos;
            p.anchorDim = player.level().dimension();
            return;
        }
        if (pos.distanceToSqr(p.anchor) > 1.0e-4) {
            player.teleportTo(p.anchor.x, p.anchor.y, p.anchor.z);
        }
        player.setDeltaMovement(Vec3.ZERO);
    }

    /** The source has released the player (or the wait is over): take the checkout, read and apply. */
    private void arrive(Pending p, long now, boolean fallback) {
        p.acquired = true;
        UUID id = p.player.getUUID();
        heldHere.add(id);
        guard(store.setPresence(id, serverId, true).thenCompose(v -> store.latest(id)), "arrival of " + p.player.getGameProfile().getName())
                .whenComplete((latest, error) -> server.execute(() -> {
                    if (pending.get(id) != p) {
                        return; // left meanwhile; onLogout released the checkout
                    }
                    if (error != null) {
                        BSPInventorySync.LOGGER.error("BSP-InventorySync: could not read {}'s data; they keep what this server had", p.player.getGameProfile().getName(), error);
                        finish(p, null, fallback, Arrival.Outcome.UNAVAILABLE, now);
                        return;
                    }
                    if (p.player.isDeadOrDying()) {
                        p.waitingForRespawn = true; // apply to the respawned player instead
                        p.deferred = latest.orElse(null);
                        p.deferredFallback = fallback;
                        return;
                    }
                    finish(p, latest.orElse(null), fallback, fallback ? Arrival.Outcome.FALLBACK : Arrival.Outcome.CLEAN, now);
                }));
    }

    /** The wait is over without a release: apply the last known snapshot and say so, or refuse the login. */
    private void timedOut(Pending p, long now) {
        UUID id = p.player.getUUID();
        String name = p.player.getGameProfile().getName();
        if (policy.kickOnTimeout()) {
            BSPInventorySync.LOGGER.warn("BSP-InventorySync: {} is still held by another server after {} ms; refusing the login", name, now - p.started);
            pending.remove(id);
            SYNCING.remove(id);
            arrivals.put(id, new Arrival(id, Arrival.Outcome.KICKED, 0, now - p.started));
            p.player.connection.disconnect(Component.literal("Your data is still being saved by the server you came from. Please try again in a moment."));
            return;
        }
        BSPInventorySync.LOGGER.warn("BSP-InventorySync: {} is still held by another server after {} ms; applying the last known data", name, now - p.started);
        arrive(p, now, true);
    }

    /** Applies (or not) and unfreezes. {@code snapshot} null: nothing to apply, the player keeps what they have. */
    private void finish(Pending p, @Nullable Snapshot snapshot, boolean fallback, Arrival.Outcome outcome, long now) {
        UUID id = p.player.getUUID();
        pending.remove(id);
        SYNCING.remove(id);
        lastSaveAt.put(id, now); // the auto-save interval counts from the arrival
        long version = 0;
        try {
            if (snapshot != null) {
                version = restore(p.player, snapshot, fallback).version();
            } else {
                MinecraftForge.EVENT_BUS.post(new InventoryRestoredEvent(p.player, 0, "", fallback, List.of(), 0));
            }
        } finally {
            arrivals.put(id, new Arrival(id, outcome, version, now - p.started));
        }
        if (fallback) {
            p.player.displayClientMessage(Component.literal("Your items may be out of date: the server you came from did not answer in time.").withStyle(ChatFormatting.GOLD), false);
        } else if (outcome == Arrival.Outcome.UNAVAILABLE) {
            p.player.displayClientMessage(Component.literal("Your items could not be loaded from the network. Tell an admin if anything is missing.").withStyle(ChatFormatting.RED), false);
        }
    }

    /**
     * A player respawned. One who arrived dead now gets their data; otherwise, if their data is
     * settled, the death is saved (the drops have left the inventory by now). {@code endConquered}
     * is the return from the End, not a death.
     */
    public void onRespawn(ServerPlayer respawned, boolean endConquered, long now) {
        UUID id = respawned.getUUID();
        Pending p = pending.get(id);
        if (p != null) {
            p.player = respawned;
            p.anchor = respawned.position();
            p.anchorDim = respawned.level().dimension();
            if (p.waitingForRespawn) {
                p.waitingForRespawn = false;
                finish(p, p.deferred, p.deferredFallback, p.deferredFallback ? Arrival.Outcome.FALLBACK : Arrival.Outcome.CLEAN, now);
            }
            return;
        }
        if (!endConquered) {
            save(respawned, SaveCause.DEATH);
        }
    }

    /**
     * A player has logged out (after BSP-Core took their totems). Settled players are saved and the
     * checkout released once the save has landed; players still arriving are neither saved (their
     * inventory here is stale) nor, unless the checkout was already taken, released.
     */
    public CompletableFuture<Void> onLogout(ServerPlayer player) {
        UUID id = player.getUUID();
        Pending p = pending.remove(id);
        SYNCING.remove(id);
        String target = transfers.remove(id);
        lastSavedTag.remove(id);
        lastSaveAt.remove(id);
        if (p != null) {
            arrivals.put(id, new Arrival(id, Arrival.Outcome.LEFT, 0, 0));
            BSPInventorySync.LOGGER.info("BSP-InventorySync: {} left before their data arrived; nothing saved", player.getGameProfile().getName());
            if (p.acquired) {
                heldHere.remove(id);
                return guard(store.setPresence(id, serverId, false), "release " + player.getGameProfile().getName());
            }
            return CompletableFuture.completedFuture(null);
        }
        if (!heldHere.contains(id)) {
            // never arrived through the handshake (the mod was switched on while they were online): save anyway
            BSPInventorySync.LOGGER.info("BSP-InventorySync: {} was not checked in here; saving on logout regardless", player.getGameProfile().getName());
        }
        heldHere.remove(id);
        SaveCause cause = target != null ? SaveCause.TRANSFER : SaveCause.LOGOUT;
        return save(player, cause).handle((snapshot, error) -> null)
                .thenCompose(v -> guard(store.setPresence(id, serverId, false), "release " + player.getGameProfile().getName())); // released even after a failed save, so the player is never stuck (the error is logged)
    }

    // ------------------------------------------------------------------ transfers (request T1)

    /**
     * BSP-Teleport is about to send the player to {@code targetServer}: take a snapshot now, before the
     * proxy switch, so the save is in flight when they leave. The logout that follows saves again,
     * as a transfer. Returns false while the player's own data is still arriving (nothing is saved then).
     */
    public boolean beginTransfer(ServerPlayer player, String targetServer) {
        UUID id = player.getUUID();
        if (isSyncing(id)) {
            return false;
        }
        transfers.put(id, targetServer);
        save(player, SaveCause.TRANSFER);
        return true;
    }

    /** The transfer did not happen: the next logout is an ordinary one. */
    public boolean cancelTransfer(ServerPlayer player) {
        return transfers.remove(player.getUUID()) != null;
    }

    public Optional<String> transferTarget(UUID player) {
        return Optional.ofNullable(transfers.get(player));
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Players already online when this service starts (a reload, or the mod switched on while they
     * play): checked in here and given an auto-save turn, as if they had arrived. Nothing is applied
     * to them: what they hold is current.
     */
    public int adopt(Collection<ServerPlayer> players, long now) {
        int n = 0;
        for (ServerPlayer player : players) {
            UUID id = player.getUUID();
            if (isSyncing(id)) {
                continue;
            }
            heldHere.add(id);
            lastSaveAt.put(id, now);
            guard(store.setPresence(id, serverId, true), "adopt " + player.getGameProfile().getName());
            n++;
        }
        return n;
    }

    /** Server start: nobody can still be held by this server. */
    public CompletableFuture<Integer> onServerStarted() {
        return store.clearPresence(serverId);
    }

    /**
     * Server stop: waits (bounded) for the saves the logouts started, releases everyone this server
     * held, and closes the store.
     */
    public void shutdown(long waitMs) {
        long deadline = System.currentTimeMillis() + waitMs;
        for (CompletableFuture<?> f : new ArrayList<>(inFlightSaves)) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) {
                break;
            }
            try {
                f.get(left, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                // logged by save()
            }
        }
        try {
            store.clearPresence(serverId).get(Math.max(100, deadline - System.currentTimeMillis()), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            BSPInventorySync.LOGGER.warn("BSP-InventorySync: could not release this server's players on stop: {}", e.toString());
        }
        pending.clear();
        heldHere.clear();
        SYNCING.clear();
        store.close();
    }
}
