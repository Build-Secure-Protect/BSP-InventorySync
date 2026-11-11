package com.mrgregles.bsp_inventorysync.sync;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.api.event.InventoryRestoredEvent;
import com.mrgregles.bsp_inventorysync.api.event.InventorySavedEvent;
import com.mrgregles.bsp_inventorysync.compat.BspCore;
import com.mrgregles.bsp_inventorysync.data.ItemRules;
import com.mrgregles.bsp_inventorysync.data.PlayerSnapshotCodec;
import com.mrgregles.bsp_inventorysync.data.vanilla.VitalsModule;
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
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.CraftingContainer;
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
import java.util.concurrent.Executor;

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
    /** A player still waiting after this long is told why they cannot move, and again every second. */
    private static final long NOTICE_AFTER_MS = 1_000, NOTICE_EVERY_MS = 1_000;

    /** A snapshot read on arrival, already decompressed off the server thread. */
    private record Loaded(Snapshot snapshot, CompoundTag tag) {}

    /** An arrival in progress. */
    private final class Pending {
        ServerPlayer player;
        final long started;
        Vec3 anchor;
        ResourceKey<Level> anchorDim;
        boolean queryInFlight;
        boolean acquired;
        boolean waitingForRespawn;
        @Nullable Loaded deferred;
        boolean deferredFallback;
        long noticedAt;
        boolean warned;

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
    /** The arrival's take of the checkout, as the store really answers it: a release waits for it (D29 audit). */
    private final Map<UUID, CompletableFuture<?>> takes = new ConcurrentHashMap<>();
    /** Players this server is writing an offline change of: one at a time (D29 audit). */
    private final Set<UUID> writing = ConcurrentHashMap.newKeySet();
    private volatile boolean stopping;
    private final Map<UUID, String> transfers = new ConcurrentHashMap<>();
    private final Map<UUID, Arrival> arrivals = new ConcurrentHashMap<>();
    private final Set<CompletableFuture<?>> inFlightSaves = ConcurrentHashMap.newKeySet();
    private final Map<UUID, CompoundTag> lastSavedTag = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastSaveAt = new ConcurrentHashMap<>();
    /** Saves of a player this server has started and the store has not answered yet. */
    private final Map<UUID, Integer> savingNow = new ConcurrentHashMap<>();
    /** Players refused at arrival: whatever this server holds for them is a stale copy and is never saved. */
    private final Set<UUID> noSave = ConcurrentHashMap.newKeySet();
    /** Health to give back once a restored player's gear has applied its bonuses (one tick after the restore). */
    private final Map<UUID, HealthLater> healthLater = new ConcurrentHashMap<>();
    private volatile int snapshotsKept = 16;
    private volatile long autosaveMs = 60_000;
    private volatile long storeTimeoutMs = 5_000;
    private volatile long skippedAutosaves, writtenAutosaves, storeTimeouts;
    /** {@code sync.networkServers} as written, and lower-cased for matching. */
    private volatile Set<String> networkServers = Set.of();
    private volatile Set<String> networkServersLower = Set.of();
    /** Server ids already warned about for matching the list only when case is ignored (once each). */
    private final Set<String> caseWarned = ConcurrentHashMap.newKeySet();
    /** Where snapshots are compressed and decompressed: one background thread on a real server, the calling thread in tests. */
    private volatile Executor codec = Runnable::run;
    /** Hands over waiting items at each arrival ({@code /invsync give}); null in tests that do not need it. */
    @Nullable private volatile com.mrgregles.bsp_inventorysync.give.GiveService gives;
    /** See {@link #failNextRestore}. */
    @Nullable private volatile RuntimeException restoreFault;

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

    /**
     * Moves snapshot compression and decompression off the server thread. It must run one task at a time
     * (a single thread), so a player's saves keep their order on the way to the store.
     */
    public void setCodecExecutor(Executor executor) {
        codec = executor;
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

    private record HealthLater(ServerPlayer player, float health, int dueTick) {}

    /** Whether this server must not save the player now: their data is still arriving, or they were refused. */
    private boolean mustNotSave(UUID player) {
        return isSyncing(player) || noSave.contains(player);
    }

    /** Whether this server may save the player now: their data has arrived and their login was not refused. */
    public boolean settled(UUID player) {
        return !mustNotSave(player);
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
        return save(player, capture(player), cause);
    }

    /**
     * As {@link #save(ServerPlayer, SaveCause)}, with a note stored inside the snapshot (who made an admin change:
     * "panel: <name>", D29). The note is not part of the player's state: the next auto-save does not count it as a change.
     */
    public CompletableFuture<Snapshot> save(ServerPlayer player, SaveCause cause, @Nullable String note) {
        return startSave(player, capture(player), cause, note).guarded();
    }

    /**
     * The player's state now, with what this server cannot capture carried forward from the snapshot last
     * saved or restored here: a module whose mod is missing here or that failed this time, and the
     * capabilities of mods missing here. Without that, a player passing through a server that lacks a mod
     * would arrive on the next one without that mod's data, and keep whatever stale copy that server had.
     */
    CompoundTag capture(ServerPlayer player) {
        CompoundTag tag = PlayerSnapshotCodec.carryForward(PlayerSnapshotCodec.capture(player), lastSavedTag.get(player.getUUID()));
        com.mrgregles.bsp_inventorysync.compat.CompactMachines.markTree(tag, com.mrgregles.bsp_inventorysync.compat.CompactMachines.localMark(serverId)); // D31: an unmarked bound machine is this server's
        return tag;
    }

    private CompletableFuture<Snapshot> save(ServerPlayer player, CompoundTag tag, SaveCause cause) {
        return startSave(player, tag, cause).guarded();
    }

    /** A save under way: {@code raw} completes when the store has really answered, {@code guarded} also on the watchdog's timeout. */
    private record Saving(CompletableFuture<Snapshot> raw, CompletableFuture<Snapshot> guarded) {}

    /**
     * The capture is the player's state and is taken on the server thread; compressing it and handing it
     * to the store happen on the codec thread, one save after another, so a player's saves reach the
     * store in the order they were taken.
     */
    private Saving startSave(ServerPlayer player, CompoundTag tag, SaveCause cause) {
        return startSave(player, tag, cause, null);
    }

    private Saving startSave(ServerPlayer player, CompoundTag tag, SaveCause cause, @Nullable String note) {
        UUID id = player.getUUID();
        String name = player.getGameProfile().getName();
        savingNow.merge(id, 1, Integer::sum);
        GraveCarrier carrier = graveCarrier;
        List<com.mrgregles.bsp_inventorysync.storage.Grave> graves = carrier == null ? List.of() : List.copyOf(carrier.pendingFor(id));
        CompoundTag stored = note == null ? tag : PlayerSnapshotCodec.withNote(tag, note);
        CompletableFuture<Snapshot> raw = CompletableFuture.supplyAsync(() -> encodeChecked(stored, name), codec)
                .thenCompose(data -> graves.isEmpty() ? store.save(id, serverId, cause, data) : store.saveWithGraves(id, serverId, cause, data, graves));
        inFlightSaves.add(raw);
        raw.whenComplete((snapshot, error) -> {
            inFlightSaves.remove(raw);
            savingNow.computeIfPresent(id, (k, n) -> n > 1 ? n - 1 : null);
            if (error != null) {
                BSPInventorySync.LOGGER.error("BSP-InventorySync: saving {} ({}) failed: {}", name, cause.id(), error.toString());
                return;
            }
            lastSavedTag.put(id, tag);
            if (!graves.isEmpty()) {
                server.execute(() -> carrier.landed(id, graves));
            }
            store.prune(id, snapshotsKept).whenComplete((n, pruneError) -> {
                if (pruneError != null) {
                    BSPInventorySync.LOGGER.warn("BSP-InventorySync: pruning {}'s snapshots failed: {}", name, pruneError.toString());
                }
            });
            server.execute(() -> MinecraftForge.EVENT_BUS.post(new InventorySavedEvent(id, name, cause, snapshot.version(), snapshot.size(), serverId)));
        });
        return new Saving(raw, guard(raw.copy(), "save " + name + " " + cause.id()));
    }

    /**
     * The server ids of this network ({@code sync.networkServers}); empty means any. This server's own id
     * always counts. Ids are matched ignoring case, as Velocity matches its server names: a list entry
     * "BSP-Saferoom" stands for the server "BSP-SafeRoom", with a warning, since the difference is a typo.
     */
    @Nullable private volatile GraveCarrier graveCarrier;

    /** The grave service whose new graves ride on this service's saves. */
    public void setGraveCarrier(@Nullable GraveCarrier carrier) {
        this.graveCarrier = carrier;
    }

    public void setGives(@Nullable com.mrgregles.bsp_inventorysync.give.GiveService gives) {
        this.gives = gives;
    }

    public void setNetworkServers(Collection<? extends String> ids) {
        Set<String> copy = Set.copyOf(ids);
        networkServers = copy;
        networkServersLower = copy.stream().map(SyncService::lower).collect(java.util.stream.Collectors.toUnmodifiableSet());
        caseWarned.clear();
        for (String id : copy) {
            if (!id.equals(serverId) && id.equalsIgnoreCase(serverId)) {
                BSPInventorySync.LOGGER.warn("BSP-InventorySync: sync.networkServers lists \"{}\" but this server's id is \"{}\"; matched ignoring case, but correct the spelling", id, serverId);
            }
        }
    }

    /** Whether {@code id} is this server or listed in {@code sync.networkServers}, ignoring case (an empty list lists nothing). */
    public boolean isKnownServer(String id) {
        return serverId.equalsIgnoreCase(id) || networkServersLower.contains(lower(id));
    }

    /** Whether a snapshot saved by {@code source} may be applied here. */
    public boolean fromNetwork(String source) {
        Set<String> ids = networkServers;
        if (ids.isEmpty() || ids.contains(source) || serverId.equalsIgnoreCase(source)) {
            return true;
        }
        if (!networkServersLower.contains(lower(source))) {
            return false;
        }
        if (caseWarned.add(source)) {
            BSPInventorySync.LOGGER.warn("BSP-InventorySync: snapshots saved by \"{}\" match sync.networkServers only when case is ignored; they are applied, but correct the spelling in the list", source);
        }
        return true;
    }

    private static String lower(String id) {
        return id.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * The newest snapshot saved by a server of this network. One saved by any other server (a test server
     * or a world pointed at the same database by mistake) is passed over, logged, and the newest from a
     * network server used instead; empty when there is none.
     */
    public CompletableFuture<Optional<Snapshot>> networkLatest(UUID player) {
        return store.latest(player).thenCompose(latest -> {
            if (latest.isEmpty() || fromNetwork(latest.get().serverId())) {
                return CompletableFuture.completedFuture(latest);
            }
            BSPInventorySync.LOGGER.warn("BSP-InventorySync: {}'s newest snapshot v{} was saved by \"{}\", which is not in sync.networkServers; it is not applied",
                    player, latest.get().version(), latest.get().serverId());
            return store.history(player).thenApply(all -> all.stream().filter(s -> fromNetwork(s.serverId())).findFirst());
        });
    }

    /**
     * Whether this server is still writing a save of the player, or an offline change of theirs ({@link #beginWrite}):
     * an arrival waits for it (a quick relog must not read the save before).
     */
    public boolean saving(UUID player) {
        return savingNow.containsKey(player);
    }

    /**
     * This server starts writing an offline change of the player (the editor, the staff panel; D29). Refused
     * (false) while another change of theirs is being written here, while this server is still saving them
     * (a logout save on its way: the change would be read past it), and once the server is stopping. Until
     * {@link #endWrite}, an arrival of theirs on this server waits, as for this server's own save, and
     * {@link #saving} is true. Server thread.
     */
    public boolean tryBeginWrite(UUID player) {
        if (stopping || savingNow.containsKey(player) || !writing.add(player)) {
            return false;
        }
        savingNow.merge(player, 1, Integer::sum);
        return true;
    }

    /** The end of a write that {@link #tryBeginWrite} let start. */
    public void endWrite(UUID player) {
        if (writing.remove(player)) {
            savingNow.computeIfPresent(player, (k, n) -> n > 1 ? n - 1 : null);
        }
    }

    /** Whether the server is stopping: no new offline change starts. */
    public boolean stopping() {
        return stopping;
    }

    /** Server stop waits (bounded) for this as for a save: an offline change under way. */
    public void track(CompletableFuture<?> work) {
        inFlightSaves.add(work);
        work.whenComplete((v, e) -> inFlightSaves.remove(work));
    }

    /** How long a store call may take before the watchdog gives up on it. */
    public long storeTimeoutMs() {
        return storeTimeoutMs;
    }

    /** Whether the player's login here was refused (their data could not be read or applied): nothing of theirs is saved. */
    public boolean refused(UUID player) {
        return noSave.contains(player);
    }

    /** A snapshot this large is logged: MySQL's MEDIUMBLOB holds 16 MB, and many servers cap a packet lower. */
    private static final int LARGE_SNAPSHOT_BYTES = 4 * 1024 * 1024;

    private static byte[] encodeChecked(CompoundTag tag, String name) {
        byte[] data = PlayerSnapshotCodec.encode(tag);
        if (data.length >= LARGE_SNAPSHOT_BYTES) {
            BSPInventorySync.LOGGER.warn("BSP-InventorySync: {}'s snapshot is {} KB compressed; MySQL takes at most 16 MB (and max_allowed_packet may be lower)", name, data.length / 1024);
        }
        return data;
    }

    /**
     * Saves every listed player whose data is not still arriving and has changed since it was last saved
     * or restored here. The world save calls this, all in one tick, so the snapshots match the world on
     * disk; an unchanged player writes nothing and leaves their snapshot history alone.
     */
    public List<CompletableFuture<Snapshot>> saveAll(Collection<ServerPlayer> players, SaveCause cause) {
        List<CompletableFuture<Snapshot>> out = new ArrayList<>();
        for (ServerPlayer player : players) {
            if (mustNotSave(player.getUUID())) {
                continue;
            }
            CompoundTag tag = capture(player);
            if (!tag.equals(lastSavedTag.get(player.getUUID()))) {
                out.add(save(player, tag, cause));
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
        if (mustNotSave(id)) {
            return Optional.empty();
        }
        CompoundTag tag = capture(player);
        lastSaveAt.put(id, now);
        if (tag.equals(lastSavedTag.get(id))) {
            skippedAutosaves++;
            return Optional.empty();
        }
        writtenAutosaves++;
        return Optional.of(save(player, tag, SaveCause.AUTOSAVE));
    }

    /** At most this many players are captured for the auto-save in one tick; the rest take their turn in the next ticks. */
    static final int AUTOSAVE_CAPTURES_PER_TICK = 4;

    /**
     * Every settled player whose last save (or last check) is older than the auto-save interval, at most
     * {@link #AUTOSAVE_CAPTURES_PER_TICK} of them per call, so a crowd that came due together (a restart,
     * a lag spike) is spread over the following ticks.
     */
    public int autosaveDue(Collection<ServerPlayer> players, long now) {
        if (autosaveMs <= 0) {
            return 0;
        }
        int written = 0;
        int captured = 0;
        for (ServerPlayer player : players) {
            UUID id = player.getUUID();
            if (mustNotSave(id)) {
                continue;
            }
            Long last = lastSaveAt.get(id);
            if (last == null) {
                lastSaveAt.put(id, staggered(now)); // first seen (the mod was switched on while they were online): a turn somewhere in the next interval
                continue;
            }
            if (now - last >= autosaveMs) {
                if (captured++ >= AUTOSAVE_CAPTURES_PER_TICK) {
                    break;
                }
                if (autosave(player, now).isPresent()) {
                    written++;
                }
            }
        }
        return written;
    }

    /** A last-save time that puts the next auto-save somewhere in the coming interval, so players seen together are not saved together. */
    private long staggered(long now) {
        return autosaveMs <= 0 ? now : now - java.util.concurrent.ThreadLocalRandom.current().nextLong(autosaveMs);
    }

    // ------------------------------------------------------------------ restoring

    /** What a restore did. */
    public record Restored(long version, String sourceServer, PlayerSnapshotCodec.Result modules, int totemsKept) {}

    /**
     * Applies a snapshot to an online player, keeping any live totem they hold, and posts
     * {@link InventoryRestoredEvent}. Server thread.
     */
    public Restored restore(ServerPlayer player, Snapshot snapshot, boolean fallback) {
        return restore(player, snapshot, PlayerSnapshotCodec.decode(snapshot.data()), fallback);
    }

    /** As {@link #restore(ServerPlayer, Snapshot, boolean)} with the snapshot already decompressed. */
    public Restored restore(ServerPlayer player, Snapshot snapshot, CompoundTag tag, boolean fallback) {
        List<ItemStack> totems = BspCore.takeLiveTotems(player);
        PlayerSnapshotCodec.Result result;
        try {
            RuntimeException fault = restoreFault;
            if (fault != null) {
                restoreFault = null;
                throw fault;
            }
            // D31 5 a: a bound machine saved before the mark existed belongs to the server that saved it
            com.mrgregles.bsp_inventorysync.compat.CompactMachines.markTree(tag, snapshot.serverId());
            result = PlayerSnapshotCodec.apply(player, tag, new com.mrgregles.bsp_inventorysync.data.RestoreContext(snapshot.serverId(), serverId));
            lastSavedTag.put(player.getUUID(), PlayerSnapshotCodec.withoutNote(tag));
        } finally {
            for (ItemStack totem : totems) { // also when the restore failed: the player keeps what BSP-Core handed back
                BspCore.giveBack(player, totem);
            }
        }
        if (!totems.isEmpty()) {
            BSPInventorySync.LOGGER.info("BSP-InventorySync: {} held {} totem(s) during the restore; given back", player.getGameProfile().getName(), totems.size());
        }
        postRestored(new InventoryRestoredEvent(player, snapshot.version(), snapshot.serverId(), fallback, result.failed(), totems.size()));
        return new Restored(snapshot.version(), snapshot.serverId(), result, totems.size());
    }

    /**
     * Posts the restore event. A listener that throws is logged and the login goes on: the restore itself
     * has been applied, and other listeners (BSP-Core's login settle) still need the event.
     */
    static void postRestored(InventoryRestoredEvent event) {
        try {
            MinecraftForge.EVENT_BUS.post(event);
        } catch (RuntimeException | LinkageError e) {
            BSPInventorySync.LOGGER.error("BSP-InventorySync: a listener of the restore event failed for {}", event.getEntity().getGameProfile().getName(), e);
        }
    }

    /** GameTests: the next restore throws this before applying anything, standing in for a fault outside the data modules. */
    public void failNextRestore(RuntimeException fault) {
        restoreFault = fault;
    }

    /**
     * Reads the player's newest snapshot and applies it on the server thread, outside the handshake
     * (an operator's {@code /invsync resync}). Empty when the store has none; the event still fires
     * with version 0.
     */
    public CompletableFuture<Optional<Restored>> restoreLatest(ServerPlayer player) {
        return guard(networkLatest(player.getUUID()), "latest " + player.getGameProfile().getName()).thenCompose(latest -> {
            CompletableFuture<Optional<Restored>> out = new CompletableFuture<>();
            server.execute(() -> {
                try {
                    if (latest.isPresent()) {
                        out.complete(Optional.of(restore(player, latest.get(), false)));
                    } else {
                        postRestored(new InventoryRestoredEvent(player, 0, "", false, List.of(), 0));
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
        if (!healthLater.isEmpty()) {
            giveHealthBack();
        }
        if (pending.isEmpty()) {
            return;
        }
        for (Pending p : new ArrayList<>(pending.values())) {
            holdStill(p);
            if (p.waitingForRespawn) {
                continue;
            }
            if (now - p.started >= NOTICE_AFTER_MS && now - p.noticedAt >= NOTICE_EVERY_MS) {
                p.noticedAt = now;
                p.player.displayClientMessage(Component.literal("Loading your items...").withStyle(ChatFormatting.GRAY), true);
            }
            if (p.queryInFlight) {
                continue;
            }
            if (p.acquired) {
                continue; // the arrival is being read from the store
            }
            if (now - p.started >= policy.timeoutMs()) {
                timedOut(p, now);
                continue;
            }
            if (saving(p.player.getUUID())) {
                continue; // a quick relog: this server's own logout save of them has not landed yet
            }
            p.queryInFlight = true;
            UUID id = p.player.getUUID();
            guard(store.presence(id), "presence " + p.player.getGameProfile().getName()).whenComplete((presence, error) -> server.execute(() -> {
                p.queryInFlight = false;
                if (pending.get(id) != p) {
                    return; // left meanwhile
                }
                if (error != null) {
                    if (!p.warned) {
                        p.warned = true; // once per arrival: the poll repeats every tick
                        BSPInventorySync.LOGGER.warn("BSP-InventorySync: could not read where {} is: {}; retrying until the arrival timeout", p.player.getGameProfile().getName(), error.toString());
                    }
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

    /**
     * The source has released the player (or the wait is over): take the checkout, read and apply. The checkout
     * is claimed (D29): taken only if no other server has taken it since the poll saw it free (an offline edit
     * written from another server, which holds it for a moment); then the player goes back to waiting. After
     * the timeout it is taken whatever the store says, as before: the source did not answer in time.
     */
    private void arrive(Pending p, long now, boolean fallback) {
        p.acquired = true;
        UUID id = p.player.getUUID();
        heldHere.add(id);
        CompletableFuture<Boolean> take = fallback
                ? store.setPresence(id, serverId, true).thenApply(v -> true)
                : store.claim(id, serverId).thenApply(held -> held.online() && held.serverId().equals(serverId));
        takes.put(id, take); // a release (they leave meanwhile) goes after it, whatever it answers
        guard(take.thenCompose(took -> took ? networkLatest(id).thenApply(Optional::of) : CompletableFuture.completedFuture(Optional.<Optional<Snapshot>>empty())),
                "arrival of " + p.player.getGameProfile().getName())
                .thenApplyAsync(took -> took.map(latest -> latest.map(s -> new Loaded(s, PlayerSnapshotCodec.decode(s.data())))), codec)
                .whenComplete((took, error) -> server.execute(() -> {
                    if (pending.get(id) != p) {
                        return; // left meanwhile; onLogout released the checkout
                    }
                    if (error != null) {
                        BSPInventorySync.LOGGER.error("BSP-InventorySync: could not read {}'s data; refusing the login so this server's stale copy is never saved over it", p.player.getGameProfile().getName(), error);
                        finish(p, null, fallback, Arrival.Outcome.UNAVAILABLE, now);
                        return;
                    }
                    if (took.isEmpty()) {
                        p.acquired = false; // another server took the checkout first: wait for its release, as for any source
                        heldHere.remove(id);
                        return;
                    }
                    Optional<Loaded> latest = took.get();
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
            noSave.add(id); // what this server holds for them is stale: their logout must not save it
            arrivals.put(id, new Arrival(id, Arrival.Outcome.KICKED, 0, now - p.started));
            p.player.connection.disconnect(Component.literal("Your data is still being saved by the server you came from. Please try again in a moment."));
            return;
        }
        BSPInventorySync.LOGGER.warn("BSP-InventorySync: {} is still held by another server after {} ms; applying the last known data", name, now - p.started);
        arrive(p, now, true);
    }

    /**
     * Applies (or not) and unfreezes. {@code loaded} null: nothing to apply, the player keeps what they have.
     * Before applying, the 2x2 crafting grid is emptied: at login it starts empty, so anything in it now
     * was moved there from this server's stale copy while the player waited, and the restore would not
     * replace it.
     */
    private void finish(Pending p, @Nullable Loaded loaded, boolean fallback, Arrival.Outcome outcome, long now) {
        UUID id = p.player.getUUID();
        pending.remove(id);
        SYNCING.remove(id);
        if (outcome == Arrival.Outcome.UNAVAILABLE) {
            // the store could not be read: letting them play would mean playing on this server's stale copy, and
            // saving it later over their real data. Refused instead, and nothing of theirs is saved here.
            noSave.add(id);
            arrivals.put(id, new Arrival(id, outcome, 0, now - p.started));
            p.player.connection.disconnect(Component.literal("Your items could not be loaded from the network. Nothing was changed: please rejoin in a minute."));
            return;
        }
        lastSaveAt.put(id, now); // the auto-save interval counts from the arrival
        long version = 0;
        List<String> failedModules = List.of();
        if (loaded != null) {
            p.player.inventoryMenu.getCraftSlots().clearContent();
            p.player.inventoryMenu.slotsChanged(p.player.inventoryMenu.getCraftSlots());
            Restored r;
            try {
                r = restore(p.player, loaded.snapshot(), loaded.tag(), fallback);
            } catch (RuntimeException | LinkageError e) {
                // half applied, or not at all: letting them play would mean saving that over their real data later.
                // Refused as an unreadable store is; the restore event is not posted, and BSP-Core drops its wait at the logout.
                BSPInventorySync.LOGGER.error("BSP-InventorySync: the restore of {}'s v{} failed; refusing the login so nothing of theirs is saved here",
                        p.player.getGameProfile().getName(), loaded.snapshot().version(), e);
                noSave.add(id);
                lastSavedTag.remove(id);
                lastSaveAt.remove(id);
                arrivals.put(id, new Arrival(id, Arrival.Outcome.UNAVAILABLE, 0, now - p.started));
                p.player.connection.disconnect(Component.literal("Your items could not be loaded from the network. Nothing was changed: please rejoin in a minute."));
                return;
            }
            version = r.version();
            failedModules = r.modules().failed();
            float health = VitalsModule.savedHealth(PlayerSnapshotCodec.module(loaded.tag(), VitalsModule.ID));
            if (health > p.player.getHealth()) {
                healthLater.put(id, new HealthLater(p.player, health, server.getTickCount() + 2)); // capped by a maximum that gear has not raised yet
            }
        } else {
            postRestored(new InventoryRestoredEvent(p.player, 0, "", fallback, List.of(), 0));
        }
        arrivals.put(id, new Arrival(id, outcome, version, now - p.started));
        com.mrgregles.bsp_inventorysync.give.GiveService g = gives;
        if (g != null) {
            // a join to the network, unless they came through BSP-Teleport (saved as a transfer): a join clears a Cancel of the waiting items
            boolean join = loaded == null || loaded.snapshot().cause() != SaveCause.TRANSFER;
            g.arrived(p.player, join);
        }
        if (fallback) {
            p.player.displayClientMessage(Component.literal("Your items may be out of date: the server you came from did not answer in time. Tell an admin if anything is missing.").withStyle(ChatFormatting.GOLD), false);
        }
        if (!failedModules.isEmpty()) {
            p.player.displayClientMessage(Component.literal("Some of your data could not be loaded (" + String.join(", ", failedModules) + "). Tell an admin.").withStyle(ChatFormatting.RED), false);
        }
    }

    /** Gives restored players the health their gear allows, now that its bonuses have applied. */
    private void giveHealthBack() {
        int tick = server.getTickCount();
        healthLater.values().removeIf(h -> {
            if (tick < h.dueTick()) {
                return false;
            }
            if (!h.player().isRemoved() && h.player().isAlive() && h.health() > h.player().getHealth()) {
                h.player().setHealth(Math.min(h.health(), h.player().getMaxHealth()));
            }
            return true;
        });
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
        lastSaveAt.remove(id);
        healthLater.remove(id);
        if (noSave.remove(id)) {
            lastSavedTag.remove(id);
            heldHere.remove(id);
            BSPInventorySync.LOGGER.info("BSP-InventorySync: {} was refused at arrival; nothing saved", player.getGameProfile().getName());
            return release(id, player.getGameProfile().getName()); // only if this server took them
        }
        if (p != null) {
            lastSavedTag.remove(id);
            arrivals.put(id, new Arrival(id, Arrival.Outcome.LEFT, 0, 0));
            BSPInventorySync.LOGGER.info("BSP-InventorySync: {} left before their data arrived; nothing saved", player.getGameProfile().getName());
            if (p.acquired) {
                heldHere.remove(id);
                return release(id, player.getGameProfile().getName());
            }
            takes.remove(id);
            return CompletableFuture.completedFuture(null);
        }
        if (!heldHere.contains(id)) {
            // never arrived through the handshake (the mod was switched on while they were online): save anyway
            BSPInventorySync.LOGGER.info("BSP-InventorySync: {} was not checked in here; saving on logout regardless", player.getGameProfile().getName());
        }
        heldHere.remove(id);
        takes.remove(id);
        SaveCause cause = target != null ? SaveCause.TRANSFER : SaveCause.LOGOUT;
        CompoundTag tag = departureCapture(player);
        lastSavedTag.remove(id);
        // released once the store has really answered the save (not merely the watchdog), so no server reads
        // their data before it lands; released after a failed save too, so the player is never stuck (the error is logged)
        return startSave(player, tag, cause).raw().handle((snapshot, error) -> {
                    GraveCarrier carrier = graveCarrier;
                    if (error != null && carrier != null) {
                        server.execute(() -> carrier.logoutSaveFailed(id));
                    }
                    return (Void) null;
                })
                .thenCompose(v -> guard(store.setPresence(id, serverId, false), "release " + player.getGameProfile().getName()))
                .whenComplete((v, error) -> {
                    if (!heldHere.contains(id) && !pending.containsKey(id)) {
                        lastSavedTag.remove(id); // the save put it back; nothing is kept for a player who has gone
                    }
                });
    }

    /**
     * Releases the checkout, after the arrival's take has really been answered by the store: with Redis, a
     * release sent while the claim is still queued would clear Redis first and then see the claim write it
     * back, leaving the player held there for every other server (D29 audit).
     */
    private CompletableFuture<Void> release(UUID id, String name) {
        CompletableFuture<?> take = takes.remove(id);
        CompletableFuture<Void> after = take == null ? CompletableFuture.completedFuture(null) : take.handle((v, e) -> (Void) null);
        return guard(after.thenCompose(v -> store.setPresence(id, serverId, false)), "release " + name);
    }

    // ------------------------------------------------------------------ transfers (request T1)

    /**
     * BSP-Teleport is about to send the player to {@code targetServer}: take a snapshot now, before the
     * proxy switch, so the save is in flight when they leave. The logout that follows saves again,
     * as a transfer. Returns false while the player's own data is still arriving (nothing is saved then).
     */
    public boolean beginTransfer(ServerPlayer player, String targetServer) {
        UUID id = player.getUUID();
        if (mustNotSave(id)) {
            return false;
        }
        transfers.put(id, targetServer);
        player.closeContainer(); // still connected: an open menu gives its items back to the inventory, as on any close
        save(player, departureCapture(player), SaveCause.TRANSFER);
        return true;
    }

    /**
     * The snapshot of a player who is leaving. Once a disconnected player is removed from the world,
     * Minecraft drops the item on their cursor and those in their 2x2 crafting grid where they stood, so
     * a snapshot holding the cursor item would put it in two places. They are moved into the inventory
     * first and saved with it; whatever does not fit stays where it was, is left out of the snapshot, and
     * is dropped here as before.
     */
    CompoundTag departureCapture(ServerPlayer player) {
        Inventory inv = player.getInventory();
        CraftingContainer grid = player.inventoryMenu.getCraftSlots();
        for (int i = 0; i < grid.getContainerSize(); i++) {
            ItemStack stack = grid.getItem(i);
            if (!stack.isEmpty() && !ItemRules.excluded(stack)) {
                inv.add(stack); // shrinks the stack to what did not fit
                if (stack.isEmpty()) {
                    grid.setItem(i, ItemStack.EMPTY);
                }
            }
        }
        ItemStack carried = player.containerMenu.getCarried();
        if (!carried.isEmpty() && !ItemRules.excluded(carried)) {
            inv.add(carried);
        }
        carried = player.containerMenu.getCarried();
        if (carried.isEmpty()) {
            player.containerMenu.setCarried(ItemStack.EMPTY);
            return capture(player);
        }
        player.containerMenu.setCarried(ItemStack.EMPTY); // the rest stays on the cursor for Minecraft to drop, but not in the snapshot
        try {
            return capture(player);
        } finally {
            player.containerMenu.setCarried(carried);
        }
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
            if (mustNotSave(id)) {
                continue;
            }
            heldHere.add(id);
            lastSaveAt.put(id, staggered(now));
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
        stopping = true;
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
        takes.clear();
        SYNCING.clear();
        store.close();
    }
}
