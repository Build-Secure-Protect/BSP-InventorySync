package com.mrgregles.bsp_inventorysync.compat;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.give.GiveService;
import com.mrgregles.bsp_inventorysync.grave.GraveContents;
import com.mrgregles.bsp_inventorysync.grave.GraveService;
import com.mrgregles.bsp_inventorysync.storage.Give;
import com.mrgregles.bsp_inventorysync.storage.Grave;
import com.mrgregles.bsp_inventorysync.storage.GraveStore;
import com.mrgregles.bsp_inventorysync.storage.SyncStore;
import com.mrgregles.bsp_inventorysync.sync.Sync;
import com.mrgregles.bsp_inventorysync.sync.SyncService;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * PR18: BSP-Core's {@code /bsp player reset} makes a player brand-new on the network, and this mod forgets what it
 * still holds for them outside their snapshots: the waiting {@code /invsync give} stacks, their {@code give_declined},
 * {@code grave_notice} and {@code grave_return} extras, and the graves they own. The snapshot history is kept, so an
 * admin can undo a mistaken reset with {@code /invsync restore}. BSP-Core itself clears what they carry at the settle and
 * saves it through {@code InventorySyncApi.saveNow}.
 *
 * <p>BSP-Core's {@code PlayerResetEvent$Forgotten} is listened to by class name, so this mod builds against a BSP-Core
 * without it and does nothing on one. Everything that carries a time is forgotten on every server that hears of the reset,
 * but only when it dates from before the reset ({@link #GRACE_MS} earlier, for clocks that differ between servers): the
 * waiting stacks (by when they were given), the Cancel (by when it was made) and the graves (by the death). So a server
 * that hears of it hours late, or twice, never forgets what the player has had since. The grave notices and return hints
 * carry no time and go only on the server where the reset was typed ({@code isOrigin()}). A grave is not deleted but
 * written as an empty marker (as an emptied grave is), so no old copy can bring it back; its headstone goes once that write
 * is confirmed.
 *
 * <p>The reset time is kept as a gate ({@link #forgotten}): this server's own record in its world data
 * ({@code bsp_inventorysync_player_resets}), BSP-Core's record of the resets this server knows of, and the
 * {@code bsp_core:ResetAt} the player carries, whichever is newest. Nothing of the player's from before it is handed over,
 * returned, listed, recovered or opened (an admin still opens it), so a row that survived its delete stays out of reach,
 * across restarts too.
 */
@Mod.EventBusSubscriber(modid = BSPInventorySync.MODID)
public final class PlayerReset {
    public static final String FORGOTTEN_EVENT = "com.mrgregles.bsp_core.api.event.PlayerResetEvent$Forgotten";
    /** How much earlier than the reset something must be to count as before it: servers' clocks differ by a few seconds. */
    public static final long GRACE_MS = 5_000;
    private static final String RESET_AT = "bsp_core:ResetAt";
    private static final int MARK_TRIES = 3;
    private static final byte[] EMPTY_DATA = new GraveContents(List.of(), new CompoundTag()).encode();

    /** Per player, the newest reset whose network extras this server has deleted (the origin only): a repeat leaves them. */
    private static final Map<UUID, Long> CLEARED = new ConcurrentHashMap<>();
    /** The event classes a listener is registered for (once per JVM: the bus outlives an integrated server). */
    private static final Set<String> REGISTERED = ConcurrentHashMap.newKeySet();

    @Nullable private static final Method LEDGER_GET, LEDGER_LATEST, ROW_RESET_MS;

    static {
        Class<?> ledger = Reflect.type("com.mrgregles.bsp_core.admin.PlayerResetLedger");
        Class<?> row = Reflect.type("com.mrgregles.bsp_core.admin.PlayerResetRow");
        LEDGER_GET = find(ledger, "get", MinecraftServer.class);
        LEDGER_LATEST = find(ledger, "latest", UUID.class);
        ROW_RESET_MS = find(row, "resetMs");
    }

    private PlayerReset() {}

    @Nullable
    private static Method find(@Nullable Class<?> type, String name, Class<?>... args) {
        try {
            return type == null ? null : type.getMethod(name, args);
        } catch (NoSuchMethodException | LinkageError e) {
            return null;
        }
    }

    /** What one reset made this server forget, for the log and the tests. */
    public record Forgot(int gives, int extras, int graves, int localGraves, boolean complete) {
        public boolean nothing() {
            return gives == 0 && extras == 0 && graves == 0 && localGraves == 0;
        }
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        if (ModList.get().isLoaded("bsp_core")) {
            register(FORGOTTEN_EVENT);
        }
    }

    /**
     * Registers the listener for the event class of that name; false (and nothing registered) when the class is missing
     * (a BSP-Core from before the reset) or is not an event.
     */
    @SuppressWarnings("unchecked")
    public static boolean register(String eventClass) {
        if (REGISTERED.contains(eventClass)) {
            return true;
        }
        Class<?> type;
        try {
            type = Class.forName(eventClass);
        } catch (ClassNotFoundException | LinkageError e) {
            BSPInventorySync.LOGGER.info("BSP-InventorySync: this BSP-Core has no player reset ({}); /bsp player reset will not reach this mod", eventClass);
            return false;
        }
        if (!Event.class.isAssignableFrom(type)) {
            BSPInventorySync.LOGGER.warn("BSP-InventorySync: {} is not an event; the player reset is not listened to", eventClass);
            return false;
        }
        if (!REGISTERED.add(eventClass)) {
            return true;
        }
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, (Class<Event>) type, PlayerReset::onForgotten);
        BSPInventorySync.LOGGER.info("BSP-InventorySync: listening to BSP-Core's player reset");
        return true;
    }

    /** Whether a listener is registered for that event class (tests). */
    public static boolean registered(String eventClass) {
        return REGISTERED.contains(eventClass);
    }

    private static void onForgotten(Event event) {
        try {
            UUID player = (UUID) get(event, "getPlayer");
            Object name = get(event, "getName");
            long resetMs = (Long) get(event, "getResetMs");
            boolean origin = (Boolean) get(event, "isOrigin");
            Object server = get(event, "getServer");
            if (player == null || !(server instanceof MinecraftServer s)) {
                return;
            }
            SyncService sync = Sync.active();
            forget(s, sync == null ? null : sync.store(), GiveService.peek(), GraveService.active(), player, name == null ? player.toString() : name.toString(), resetMs, origin);
        } catch (RuntimeException | ReflectiveOperationException | LinkageError e) {
            BSPInventorySync.LOGGER.error("BSP-InventorySync: could not read BSP-Core's player reset; nothing was forgotten here", e);
        }
    }

    @Nullable
    private static Object get(Object event, String getter) throws ReflectiveOperationException {
        Method m = event.getClass().getMethod(getter);
        return m.invoke(event);
    }

    // ------------------------------------------------------------------ the gate

    /** This server's own record of the resets it has heard of: the newest per player, in its world data. */
    public static final class Gate extends SavedData {
        public static final String NAME = BSPInventorySync.MODID + "_player_resets";
        private final Map<UUID, Long> resets = new HashMap<>();

        public static Gate get(MinecraftServer server) {
            return server.overworld().getDataStorage().computeIfAbsent(Gate::load, Gate::new, NAME);
        }

        public static Gate load(CompoundTag tag) {
            Gate g = new Gate();
            for (String key : tag.getAllKeys()) {
                try {
                    g.resets.put(UUID.fromString(key), tag.getLong(key));
                } catch (IllegalArgumentException ignored) {
                    // not a player id: skipped
                }
            }
            return g;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            resets.forEach((id, at) -> tag.putLong(id.toString(), at));
            return tag;
        }

        /** The newest reset of the player recorded here, 0 when none. */
        public long resetAt(UUID player) {
            return resets.getOrDefault(player, 0L);
        }

        public void note(UUID player, long resetMs) {
            if (resetAt(player) < resetMs) {
                resets.put(player, resetMs);
                setDirty();
            }
        }

        /** GameTests: forgets the player. */
        public void remove(UUID player) {
            if (resets.remove(player) != null) {
                setDirty();
            }
        }
    }

    /**
     * The newest reset of the player this server knows of (0 when none): its own record, BSP-Core's (by reflection, when it
     * has one) and the {@code bsp_core:ResetAt} the player carries when they are here ({@code entity}). Server thread.
     */
    public static long resetAt(MinecraftServer server, UUID player, @Nullable Player entity) {
        long at = Gate.get(server).resetAt(player);
        if (LEDGER_GET != null && LEDGER_LATEST != null && ROW_RESET_MS != null) {
            try {
                Object row = LEDGER_LATEST.invoke(LEDGER_GET.invoke(null, server), player);
                if (row != null && ROW_RESET_MS.invoke(row) instanceof Long ms) {
                    at = Math.max(at, ms);
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                BSPInventorySync.LOGGER.debug("BSP-InventorySync: BSP-Core's reset record not readable: {}", e.toString());
            }
        }
        if (entity != null) {
            at = Math.max(at, entity.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG).getLong(RESET_AT));
        }
        return at;
    }

    /** Whether something of the player's from {@code at} (ms) dates from before a reset this server knows of. Server thread. */
    public static boolean forgotten(MinecraftServer server, UUID player, @Nullable Player entity, long at) {
        long reset = resetAt(server, player, entity);
        return reset > 0 && before(at, reset);
    }

    /** Whether a grave dates from before its owner's reset (its owner looked up here when online). Server thread. */
    public static boolean forgotten(MinecraftServer server, Grave g) {
        return forgotten(server, g.owner(), server.getPlayerList().getPlayer(g.owner()), g.diedAt());
    }

    private static boolean before(long at, long resetMs) {
        return at <= resetMs - GRACE_MS;
    }

    /** The {@code bsp_core:ResetAt} the player carries in {@code ForgeData.PlayerPersisted}, or null when none. */
    @Nullable
    public static Long stamp(Player player) {
        CompoundTag persisted = player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        return persisted.contains(RESET_AT, net.minecraft.nbt.Tag.TAG_LONG) ? persisted.getLong(RESET_AT) : null;
    }

    /**
     * Puts the stamp {@link #stamp} read before an admin restore back (the newer of it and the restored one), so restoring a
     * snapshot from before a reset undoes the reset instead of having BSP-Core wipe the player again at their next login.
     * Nothing when {@code before} is null. Server thread.
     */
    public static void keepStamp(Player player, @Nullable Long before) {
        if (before == null) {
            return;
        }
        CompoundTag forgeData = player.getPersistentData();
        CompoundTag persisted;
        if (forgeData.contains(Player.PERSISTED_NBT_TAG, net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            persisted = forgeData.getCompound(Player.PERSISTED_NBT_TAG); // the live compound
        } else {
            persisted = new CompoundTag();
            forgeData.put(Player.PERSISTED_NBT_TAG, persisted);
        }
        Long restored = stamp(player);
        if (restored == null || restored < before) {
            persisted.putLong(RESET_AT, before);
        }
    }

    // ------------------------------------------------------------------ forgetting

    /**
     * Forgets the player as of {@code resetMs}. {@code network} is the sync's store (null with the sync off), {@code gives}
     * and {@code graves} the running services (null when there are none). Server thread; completes with what went, once
     * every store has answered ({@code complete} false when some write failed: it is logged, and the gate keeps what is
     * left from the player).
     */
    public static CompletableFuture<Forgot> forget(MinecraftServer server, @Nullable SyncStore network, @Nullable GiveService gives, @Nullable GraveService graves,
                                                   UUID player, String name, long resetMs, boolean origin) {
        Gate.get(server).note(player, resetMs);
        long cut = resetMs - GRACE_MS;
        if (gives != null) {
            gives.forget(player);
        }
        int localGraves = graves == null ? 0 : graves.forgetLocal(player, cut, EMPTY_DATA);
        boolean[] failed = {false};
        List<SyncStore> stores = new ArrayList<>();
        if (network != null) {
            stores.add(network);
        }
        if (gives != null && gives.store() != network) {
            stores.add(gives.store()); // the sync is off here: this server's own waiting stacks and Cancel
        }
        List<CompletableFuture<Integer>> givesGone = new ArrayList<>();
        List<CompletableFuture<Integer>> extrasGone = new ArrayList<>();
        for (SyncStore store : stores) {
            givesGone.add(dropGives(server, store, player, cut, name, failed));
            extrasGone.add(dropDeclined(store, player, cut, failed));
        }
        // the extras that carry no time: only where the reset was typed, and once per reset
        boolean shared = origin && network != null && CLEARED.getOrDefault(player, Long.MIN_VALUE) < resetMs;
        boolean[] sharedFailed = {false};
        if (shared) {
            for (String kind : List.of(GraveService.NOTICE, GraveService.RETURN)) {
                extrasGone.add(dropExtra(network, kind, player, sharedFailed));
            }
        }
        CompletableFuture<Integer> gravesGone = network == null ? CompletableFuture.completedFuture(0) : markGraves(server, network, graves, player, name, cut, failed);
        CompletableFuture<Integer> g = sum(givesGone), x = sum(extrasGone);
        return CompletableFuture.allOf(g, x, gravesGone).thenCompose(v -> onServer(server, () -> {
            if (shared && !sharedFailed[0]) {
                CLEARED.merge(player, resetMs, Math::max);
            }
            boolean complete = !failed[0] && !sharedFailed[0];
            Forgot f = new Forgot(g.join(), x.join(), gravesGone.join(), localGraves, complete);
            BSPInventorySync.LOGGER.info("BSP-InventorySync: {} was reset{}: {} waiting stack(s), {} extra(s), {} grave(s) in the database and {} here forgotten{}; their snapshots are kept",
                    name, origin ? " here" : " on another server", f.gives(), f.extras(), f.graves(), f.localGraves(),
                    complete ? "" : " (some writes failed, see above: what is left is never handed to them)");
            return f;
        }));
    }

    /** Takes the player's waiting stacks from before the cut out of the store; each is logged (they are in no snapshot). */
    private static CompletableFuture<Integer> dropGives(MinecraftServer server, SyncStore store, UUID player, long cut, String name, boolean[] failed) {
        return safe(() -> store.gives(List.of(player))).handle((rows, e) -> {
            if (e != null) {
                BSPInventorySync.LOGGER.warn("BSP-InventorySync: could not read {}'s waiting stacks for their reset: {}", name, e.toString());
                failed[0] = true;
                return List.<Give>of();
            }
            return rows.stream().filter(r -> r.player().equals(player) && r.createdAt() <= cut).toList();
        }).thenCompose(rows -> {
            List<CompletableFuture<Give>> takes = rows.stream().map(r -> safe(() -> store.takeGive(r.id())).handle((took, e) -> {
                if (e != null) {
                    BSPInventorySync.LOGGER.warn("BSP-InventorySync: a waiting stack of {} from {} was not deleted at their reset: {}", name, r.by(), e.toString());
                    failed[0] = true;
                    return null;
                }
                return Boolean.TRUE.equals(took) ? r : null; // false: handed over or dropped by another server meanwhile
            })).toList();
            return CompletableFuture.allOf(takes.toArray(CompletableFuture[]::new)).thenCompose(v -> onServer(server, () -> {
                int n = 0;
                for (CompletableFuture<Give> t : takes) {
                    Give r = t.join();
                    if (r != null) {
                        n++;
                        BSPInventorySync.LOGGER.info("BSP-InventorySync: a waiting stack for {} from {} dropped at their reset: {}", name, r.by(), describe(r));
                    }
                }
                return n;
            }));
        });
    }

    /** Removes the player's Cancel when it was made before the cut (one without a time, from before this rule, counts as old). */
    private static CompletableFuture<Integer> dropDeclined(SyncStore store, UUID player, long cut, boolean[] failed) {
        return safe(() -> store.extra(GiveService.DECLINED, player)).thenCompose(found -> {
            if (found.isEmpty() || GiveService.declinedAt(found.get()) > cut) {
                return CompletableFuture.completedFuture(0);
            }
            return dropExtra(store, GiveService.DECLINED, player, failed);
        }).exceptionally(e -> {
            BSPInventorySync.LOGGER.warn("BSP-InventorySync: the Cancel of {} was not read at their reset: {}", player, e.toString());
            failed[0] = true;
            return 0;
        });
    }

    private static CompletableFuture<Integer> dropExtra(SyncStore store, String kind, UUID player, boolean[] failed) {
        return safe(() -> store.deleteExtra(kind, player)).handle((gone, e) -> {
            if (e != null) {
                BSPInventorySync.LOGGER.warn("BSP-InventorySync: {} of {} was not deleted at their reset: {}", kind, player, e.toString());
                failed[0] = true;
                return 0;
            }
            return Boolean.TRUE.equals(gone) ? 1 : 0;
        });
    }

    private enum Mark { MARKED, GONE, FAILED }

    /** Writes the player's graves from before the cut in the network store as empty markers; each headstone here goes once its write is confirmed. */
    private static CompletableFuture<Integer> markGraves(MinecraftServer server, GraveStore network, @Nullable GraveService graves, UUID player, String name, long cut,
                                                         boolean[] failed) {
        return safe(() -> network.graves(player)).handle((all, e) -> {
            if (e != null) {
                BSPInventorySync.LOGGER.warn("BSP-InventorySync: could not read {}'s graves for their reset: {}", name, e.toString());
                failed[0] = true;
                return List.<Grave>of();
            }
            return all.stream().filter(gr -> gr.owner().equals(player) && gr.diedAt() <= cut && !gr.empty()).toList();
        }).thenCompose(mine -> {
            List<CompletableFuture<Mark>> marks = mine.stream().map(gr -> mark(network, gr, cut, MARK_TRIES)).toList();
            return CompletableFuture.allOf(marks.toArray(CompletableFuture[]::new)).thenCompose(v -> onServer(server, () -> {
                int n = 0;
                for (int i = 0; i < mine.size(); i++) {
                    Grave gr = mine.get(i);
                    switch (marks.get(i).join()) {
                        case MARKED -> {
                            n++;
                            if (graves != null) {
                                graves.removeBlock(gr);
                            }
                            BSPInventorySync.LOGGER.warn("BSP-InventorySync: {}'s grave {} emptied at their reset; it held {}{}", name, gr.shortId(), GraveService.describe(gr),
                                    gr.xp() > 0 ? " and " + gr.xp() + " xp" : "");
                        }
                        case GONE -> {
                            if (graves != null) {
                                graves.removeBlock(gr); // emptied meanwhile: nothing left in it
                            }
                        }
                        case FAILED -> {
                            failed[0] = true;
                            BSPInventorySync.LOGGER.warn("BSP-InventorySync: {}'s grave {} could not be emptied at their reset; it is never opened for anyone but an admin, "
                                    + "nor returned to them. It holds {}", name, gr.shortId(), GraveService.describe(gr));
                        }
                    }
                }
                return n;
            }));
        });
    }

    /** One grave as an empty marker, at the revision read; a changed grave is read again and tried again, a lost answer checked by reading back. */
    private static CompletableFuture<Mark> mark(GraveStore store, Grave g, long cut, int tries) {
        Grave marker = g.withContents(0, 0, EMPTY_DATA, System.currentTimeMillis());
        return safe(() -> store.updateGrave(marker)).handle((ok, e) -> e == null && Boolean.TRUE.equals(ok)).thenCompose(ok -> {
            if (ok) {
                return CompletableFuture.completedFuture(Mark.MARKED);
            }
            return safe(() -> store.grave(g.id())).handle((now, e) -> {
                if (e != null) {
                    return Optional.<Optional<Grave>>empty();
                }
                return Optional.of(now);
            }).thenCompose(read -> {
                if (read.isEmpty()) {
                    return tries > 1 ? mark(store, g, cut, tries - 1) : CompletableFuture.completedFuture(Mark.FAILED);
                }
                Optional<Grave> now = read.get();
                if (now.isEmpty() || now.get().empty()) {
                    // gone or emptied: the write landed after all (its answer was lost), someone took the rest, or the sweep removed it
                    return CompletableFuture.completedFuture(now.isPresent() && now.get().revision() > g.revision() && now.get().touchedAt() == marker.touchedAt()
                            ? Mark.MARKED : Mark.GONE);
                }
                return tries > 1 ? mark(store, now.get(), cut, tries - 1) : CompletableFuture.completedFuture(Mark.FAILED);
            });
        });
    }

    /** "5 Emerald": the stack a waiting row holds, for the log. Server thread (it reads the item registry). */
    public static String describe(Give r) {
        try {
            var s = GiveService.decode(r);
            return s.getCount() + " " + s.getHoverName().getString();
        } catch (RuntimeException e) {
            return r.item().length + " bytes";
        }
    }

    private static <T> CompletableFuture<T> safe(Supplier<CompletableFuture<T>> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private static CompletableFuture<Integer> sum(List<CompletableFuture<Integer>> each) {
        return CompletableFuture.allOf(each.toArray(CompletableFuture[]::new)).thenApply(v -> each.stream().mapToInt(CompletableFuture::join).sum());
    }

    /** Runs {@code step} on the server thread: at once when already there, else at the next task. */
    private static <T> CompletableFuture<T> onServer(MinecraftServer server, Supplier<T> step) {
        if (server.isSameThread()) {
            try {
                return CompletableFuture.completedFuture(step.get());
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
        }
        CompletableFuture<T> f = new CompletableFuture<>();
        server.execute(() -> {
            try {
                f.complete(step.get());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f;
    }

    /** GameTests: forgets this server's record of the player and the repeat guard. */
    public static void clearForTests(MinecraftServer server, UUID player) {
        Gate.get(server).remove(player);
        CLEARED.remove(player);
    }
}
