package com.mrgregles.bsp_inventorysync.compat;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.give.GiveService;
import com.mrgregles.bsp_inventorysync.grave.GraveService;
import com.mrgregles.bsp_inventorysync.storage.Give;
import com.mrgregles.bsp_inventorysync.storage.Grave;
import com.mrgregles.bsp_inventorysync.storage.GraveStore;
import com.mrgregles.bsp_inventorysync.storage.SyncStore;
import com.mrgregles.bsp_inventorysync.sync.Sync;
import com.mrgregles.bsp_inventorysync.sync.SyncService;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SR10: BSP-Core's {@code /bsp season reset} starts every player over, and this mod forgets what it still holds for anyone
 * outside their snapshots: every waiting {@code /invsync give} stack, every {@code give_declined}, {@code grave_notice} and
 * {@code grave_return} extra, and every grave (an admin's return marked on one goes with it). Snapshots are kept, so an admin can
 * undo the reset for one player with {@code /invsync restore}; that restore keeps the {@code bsp_core:SeasonReset} the player
 * carries ({@link #keepStamp}), as it keeps {@code bsp_core:ResetAt}, so BSP-Core does not wipe them again.
 *
 * <p>BSP-Core's {@code SeasonEvent$Ended} is listened to by class name and read by reflection, {@code isSeasonReset()} and
 * {@code isOrigin()} counting as false (and {@code getResetMs()} as 0) on a BSP-Core without them. The shared rows (the database) are changed only on the server
 * the reset was typed on ({@code isOrigin()}); every server forgets its own copies (the graves in its world data and riding on a
 * snapshot, the Cancels it remembers) and takes down the headstones of graves from before the reset that stand in its loaded
 * chunks. Only what dates from before the reset goes ({@link PlayerReset#GRACE_MS} earlier), so a server that hears of it late
 * never forgets what came after.
 *
 * <p>The reset's time is the event's own {@code getResetMs()} (the same on every server), else BSP-Core's new season start
 * ({@code SeasonData.startMs}), else, on the origin only, now ({@link #resetTime}); another server with no time forgets nothing.
 * The grave notices and return hints, which carry no time, go only the first time this server hears of that reset. It is kept in {@link PlayerReset.Gate}, where it counts as a reset of every player:
 * nothing from before it is handed over, returned, listed, recovered or opened (an admin still opens it), across restarts too.
 */
@Mod.EventBusSubscriber(modid = BSPInventorySync.MODID)
public final class SeasonReset {
    public static final String ENDED_EVENT = "com.mrgregles.bsp_core.api.event.SeasonEvent$Ended";
    /** BSP-Core's per-player key: the number of the season reset whose wipe of what they carry is done. */
    public static final String SEASON_RESET = "bsp_core:SeasonReset";

    private static final Set<String> REGISTERED = ConcurrentHashMap.newKeySet();
    @Nullable private static final Method SEASON_GET;
    @Nullable private static final Field SEASON_START;

    static {
        Class<?> data = Reflect.type("com.mrgregles.bsp_core.admin.SeasonData");
        Method get = null;
        Field start = null;
        try {
            if (data != null) {
                get = data.getMethod("get", MinecraftServer.class);
                start = data.getField("startMs");
            }
        } catch (NoSuchMethodException | NoSuchFieldException | LinkageError e) {
            get = null;
            start = null;
        }
        SEASON_GET = get;
        SEASON_START = start;
    }

    private SeasonReset() {}

    /** What one season reset made this server forget, for the log and the tests. */
    public record Dropped(int gives, int extras, int graves, int localGraves, int headstones, boolean complete) {}

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        if (ModList.get().isLoaded("bsp_core")) {
            register(ENDED_EVENT);
        }
    }

    /** Registers the listener for the event class of that name; false (and nothing registered) when it is missing or not an event. */
    @SuppressWarnings("unchecked")
    public static boolean register(String eventClass) {
        if (REGISTERED.contains(eventClass)) {
            return true;
        }
        Class<?> type;
        try {
            type = Class.forName(eventClass);
        } catch (ClassNotFoundException | LinkageError e) {
            BSPInventorySync.LOGGER.info("BSP-InventorySync: this BSP-Core has no season event ({}); /bsp season reset will not reach this mod", eventClass);
            return false;
        }
        if (!Event.class.isAssignableFrom(type)) {
            BSPInventorySync.LOGGER.warn("BSP-InventorySync: {} is not an event; the season reset is not listened to", eventClass);
            return false;
        }
        if (!REGISTERED.add(eventClass)) {
            return true;
        }
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, (Class<Event>) type, SeasonReset::onEnded);
        BSPInventorySync.LOGGER.info("BSP-InventorySync: listening to BSP-Core's season reset");
        return true;
    }

    /** Whether a listener is registered for that event class (tests). */
    public static boolean registered(String eventClass) {
        return REGISTERED.contains(eventClass);
    }

    private static void onEnded(Event event) {
        try {
            if (!flag(event, "isSeasonReset")) {
                return; // a season's end, or a full reset: nothing of this mod's changes
            }
            boolean origin = flag(event, "isOrigin");
            Object server = event.getClass().getMethod("getServer").invoke(event);
            if (!(server instanceof MinecraftServer s)) {
                return;
            }
            long resetMs = resetTime(s, number(event, "getResetMs"), origin);
            if (resetMs <= 0) {
                BSPInventorySync.LOGGER.error("BSP-InventorySync: a season reset was heard from another server, but not when it was made; nothing was forgotten here "
                        + "(what dates from before it would be guessed, and what came after it could be lost)");
                return;
            }
            SyncService sync = Sync.active();
            forgetAll(s, sync == null ? null : sync.store(), GiveService.peek(), GraveService.active(), resetMs, origin);
        } catch (RuntimeException | ReflectiveOperationException | LinkageError e) {
            BSPInventorySync.LOGGER.error("BSP-InventorySync: could not read BSP-Core's season reset; nothing was forgotten here", e);
        }
    }

    /** A boolean getter of the event; false when the event has no such method (an older BSP-Core) or it answers something else. */
    public static boolean flag(Object event, String getter) throws ReflectiveOperationException {
        Method m;
        try {
            m = event.getClass().getMethod(getter);
        } catch (NoSuchMethodException e) {
            return false;
        }
        return Boolean.TRUE.equals(m.invoke(event));
    }

    /** A long getter of the event; 0 when the event has no such method (an older BSP-Core) or it answers something else. */
    public static long number(Object event, String getter) throws ReflectiveOperationException {
        Method m;
        try {
            m = event.getClass().getMethod(getter);
        } catch (NoSuchMethodException e) {
            return 0;
        }
        return m.invoke(event) instanceof Long l ? l : 0;
    }

    /**
     * When the season reset was made: the event's own time ({@code getResetMs()}, the same on every server) when it has one, else
     * BSP-Core's new season start ({@link #seasonStart}), else, only on the server it was typed on (which hears it at once), now.
     * 0 on another server that has neither: a guessed time there could drop what came after the reset.
     */
    public static long resetTime(MinecraftServer server, long fromEvent, boolean origin) {
        if (fromEvent > 0) {
            return fromEvent;
        }
        long start = seasonStart(server);
        if (start > 0) {
            return start;
        }
        return origin ? System.currentTimeMillis() : 0;
    }

    /** BSP-Core's season start ({@code SeasonData.startMs}, by reflection); 0 when it is not readable or not believable. */
    public static long seasonStart(MinecraftServer server) {
        long now = System.currentTimeMillis();
        if (SEASON_GET != null && SEASON_START != null) {
            try {
                long start = SEASON_START.getLong(SEASON_GET.invoke(null, server));
                if (start > 0 && start <= now + 60_000) {
                    return start;
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                BSPInventorySync.LOGGER.debug("BSP-InventorySync: BSP-Core's season start not readable: {}", e.toString());
            }
        }
        return 0;
    }

    // ------------------------------------------------------------------ the stamp on an admin restore

    /** The {@code bsp_core:SeasonReset} the player carries in {@code ForgeData.PlayerPersisted}, or null when none. */
    @Nullable
    public static Integer stamp(Player player) {
        CompoundTag persisted = player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        return persisted.contains(SEASON_RESET, Tag.TAG_ANY_NUMERIC) ? persisted.getInt(SEASON_RESET) : null;
    }

    /**
     * Puts the stamp {@link #stamp} read before an admin restore back (the higher of it and the restored one), so restoring a
     * snapshot from before a season reset undoes it for that player instead of having BSP-Core wipe them again. Nothing when
     * {@code before} is null. Server thread.
     */
    public static void keepStamp(Player player, @Nullable Integer before) {
        if (before == null) {
            return;
        }
        CompoundTag forgeData = player.getPersistentData();
        CompoundTag persisted;
        if (forgeData.contains(Player.PERSISTED_NBT_TAG, Tag.TAG_COMPOUND)) {
            persisted = forgeData.getCompound(Player.PERSISTED_NBT_TAG); // the live compound
        } else {
            persisted = new CompoundTag();
            forgeData.put(Player.PERSISTED_NBT_TAG, persisted);
        }
        Integer restored = stamp(player);
        if (restored == null || restored < before) {
            persisted.putInt(SEASON_RESET, before);
        }
    }

    // ------------------------------------------------------------------ forgetting

    /**
     * Forgets every player as of {@code resetMs}. {@code network} is the sync's store (null with the sync off), {@code gives} and
     * {@code graves} the running services (null when there are none); {@code origin} whether the reset was typed here, the only
     * server that changes the database. Server thread; completes with what went once every store has answered ({@code complete}
     * false when some read or write failed: it is logged, and the gate keeps what is left from everyone).
     */
    public static CompletableFuture<Dropped> forgetAll(MinecraftServer server, @Nullable SyncStore network, @Nullable GiveService gives, @Nullable GraveService graves,
                                                       long resetMs, boolean origin) {
        PlayerReset.Gate gate = PlayerReset.Gate.get(server);
        boolean firstTime = gate.seasonAt() < resetMs; // the extras without a time go only the first time this reset is heard
        gate.noteSeason(resetMs);
        long cut = resetMs - PlayerReset.GRACE_MS;
        boolean[] failed = {false};
        if (gives != null) {
            gives.forgetAll();
        }
        int[] local = {0};
        try {
            local[0] = graves == null ? 0 : graves.forgetLocalAll(cut, PlayerReset.EMPTY_DATA);
        } catch (RuntimeException e) {
            // the database's part below still runs; the gate keeps what is left here from everyone
            BSPInventorySync.LOGGER.error("BSP-InventorySync: the graves kept on this server could not all be emptied at the season reset", e);
            failed[0] = true;
        }
        List<SyncStore> stores = new ArrayList<>();
        if (network != null && origin) {
            stores.add(network);
        }
        if (gives != null && gives.store() != network) {
            stores.add(gives.store()); // the sync is off here: this server's own waiting stacks and Cancels
        }
        List<CompletableFuture<Integer>> givesGone = new ArrayList<>();
        List<CompletableFuture<Integer>> extrasGone = new ArrayList<>();
        for (SyncStore store : stores) {
            givesGone.add(dropGives(server, store, cut, failed));
            extrasGone.add(eachKey(store, GiveService.DECLINED, failed, key -> PlayerReset.dropDeclined(store, key, cut, failed)));
        }
        if (network != null && origin && firstTime) {
            // the grave notices and return hints carry no time: they go on the origin only, and once per reset
            for (String kind : List.of(GraveService.NOTICE, GraveService.RETURN)) {
                extrasGone.add(eachKey(network, kind, failed, key -> PlayerReset.dropExtra(network, kind, key, failed)));
            }
        }
        int[] headstones = {0};
        CompletableFuture<Integer> gravesGone = network == null ? CompletableFuture.completedFuture(0)
                : origin ? markGraves(server, network, graves, cut, headstones, failed)
                : takeDownHeadstones(server, network, graves, cut, headstones, failed);
        CompletableFuture<Integer> g = PlayerReset.sum(givesGone), x = PlayerReset.sum(extrasGone);
        return CompletableFuture.allOf(g, x, gravesGone).thenCompose(v -> PlayerReset.onServer(server, () -> {
            Dropped d = new Dropped(g.join(), x.join(), gravesGone.join(), local[0], headstones[0], !failed[0]);
            BSPInventorySync.LOGGER.info("BSP-InventorySync: season reset{}: {} waiting stack(s), {} extra(s), {} grave(s) in the database and {} kept here forgotten, "
                            + "{} headstone(s) taken down here{}; every snapshot is kept",
                    origin ? " typed here" : " heard from another server", d.gives(), d.extras(), d.graves(), d.localGraves(), d.headstones(),
                    d.complete() ? "" : " (some reads or writes failed, see above: what is left is never handed to anyone)");
            return d;
        }));
    }

    /** Takes every waiting stack from before the cut out of the store; each is logged (they are in no snapshot). */
    private static CompletableFuture<Integer> dropGives(MinecraftServer server, SyncStore store, long cut, boolean[] failed) {
        return PlayerReset.safe(store::allGives).handle((rows, e) -> {
            if (e != null) {
                BSPInventorySync.LOGGER.warn("BSP-InventorySync: could not read the waiting stacks for the season reset: {}", e.toString());
                failed[0] = true;
                return List.<Give>of();
            }
            return rows.stream().filter(r -> r.createdAt() <= cut).toList();
        }).thenCompose(rows -> {
            List<CompletableFuture<Give>> takes = rows.stream().map(r -> PlayerReset.safe(() -> store.takeGive(r.id())).handle((took, e) -> {
                if (e != null) {
                    BSPInventorySync.LOGGER.warn("BSP-InventorySync: a waiting stack for {} from {} was not deleted at the season reset: {}", r.player(), r.by(), e.toString());
                    failed[0] = true;
                    return null;
                }
                return Boolean.TRUE.equals(took) ? r : null; // false: handed over meanwhile
            })).toList();
            return CompletableFuture.allOf(takes.toArray(CompletableFuture[]::new)).thenCompose(v -> PlayerReset.onServer(server, () -> {
                int n = 0;
                for (CompletableFuture<Give> t : takes) {
                    Give r = t.join();
                    if (r != null) {
                        n++;
                        BSPInventorySync.LOGGER.info("BSP-InventorySync: a waiting stack for {} from {} dropped at the season reset: {}", r.player(), r.by(), PlayerReset.describe(r));
                    }
                }
                return n;
            }));
        });
    }

    /** Runs {@code each} for every key of an extra kind in the store and sums what it removed. */
    private static CompletableFuture<Integer> eachKey(SyncStore store, String kind, boolean[] failed, java.util.function.Function<UUID, CompletableFuture<Integer>> each) {
        return PlayerReset.safe(() -> store.extraKeys(kind)).handle((keys, e) -> {
            if (e != null) {
                BSPInventorySync.LOGGER.warn("BSP-InventorySync: could not read the {} records for the season reset: {}", kind, e.toString());
                failed[0] = true;
                return List.<UUID>of();
            }
            return keys;
        }).thenCompose(keys -> PlayerReset.sum(keys.stream().map(each).toList()));
    }

    /** Every grave in the database from before the cut, read in full (the season reset is rare). */
    private static CompletableFuture<List<Grave>> oldGraves(GraveStore network, long cut, boolean[] failed) {
        return PlayerReset.safe(() -> network.gravesTouchedBefore(Long.MAX_VALUE, Integer.MAX_VALUE)).handle((all, e) -> {
            if (e != null) {
                BSPInventorySync.LOGGER.warn("BSP-InventorySync: could not read the graves for the season reset: {}", e.toString());
                failed[0] = true;
                return List.<Grave>of();
            }
            return all.stream().filter(gr -> gr.diedAt() <= cut).toList();
        });
    }

    /** The origin: every grave from before the cut written as an empty marker; each headstone here goes once its write is confirmed. */
    private static CompletableFuture<Integer> markGraves(MinecraftServer server, GraveStore network, @Nullable GraveService graves, long cut, int[] headstones, boolean[] failed) {
        return oldGraves(network, cut, failed).thenCompose(old -> {
            List<Grave> full = old.stream().filter(gr -> !gr.empty()).toList();
            List<CompletableFuture<PlayerReset.Mark>> marks = full.stream().map(gr -> PlayerReset.mark(network, gr, cut, PlayerReset.MARK_TRIES)).toList();
            return CompletableFuture.allOf(marks.toArray(CompletableFuture[]::new)).thenCompose(v -> PlayerReset.onServer(server, () -> {
                int n = 0;
                for (int i = 0; i < full.size(); i++) {
                    Grave gr = full.get(i);
                    switch (marks.get(i).join()) {
                        case MARKED -> {
                            n++;
                            headstones[0] += takeDown(graves, gr);
                            BSPInventorySync.LOGGER.warn("BSP-InventorySync: {}'s grave {} emptied at the season reset; it held {}{}", gr.ownerName(), gr.shortId(),
                                    GraveService.describe(gr), gr.xp() > 0 ? " and " + gr.xp() + " xp" : "");
                        }
                        case GONE -> headstones[0] += takeDown(graves, gr); // emptied meanwhile: nothing left in it
                        case FAILED -> {
                            failed[0] = true;
                            BSPInventorySync.LOGGER.warn("BSP-InventorySync: {}'s grave {} could not be emptied at the season reset; it is never opened for anyone but an admin, "
                                    + "nor returned. It holds {}", gr.ownerName(), gr.shortId(), GraveService.describe(gr));
                        }
                    }
                }
                return n;
            }));
        });
    }

    /** Another server: the database is the origin's to change, so only the headstones of graves from before the cut standing here go. */
    private static CompletableFuture<Integer> takeDownHeadstones(MinecraftServer server, GraveStore network, @Nullable GraveService graves, long cut, int[] headstones,
                                                                 boolean[] failed) {
        if (graves == null) {
            return CompletableFuture.completedFuture(0);
        }
        return oldGraves(network, cut, failed).thenCompose(old -> PlayerReset.onServer(server, () -> {
            for (Grave gr : old) {
                headstones[0] += takeDown(graves, gr);
            }
            return 0;
        }));
    }

    /** The grave's headstone goes if it stands here; 1 when it did. */
    private static int takeDown(@Nullable GraveService graves, Grave gr) {
        return graves != null && graves.removeBlock(gr) ? 1 : 0;
    }
}
