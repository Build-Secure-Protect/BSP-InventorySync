package com.mrgregles.bsp_inventorysync.gametest;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.api.GraveInfo;
import com.mrgregles.bsp_inventorysync.api.GraveReturn;
import com.mrgregles.bsp_inventorysync.api.GraveReturn.Outcome;
import com.mrgregles.bsp_inventorysync.grave.GraveActions;
import com.mrgregles.bsp_inventorysync.grave.GraveContents;
import com.mrgregles.bsp_inventorysync.grave.GraveService;
import com.mrgregles.bsp_inventorysync.grave.WorldGraveStore;
import com.mrgregles.bsp_inventorysync.storage.Grave;
import com.mrgregles.bsp_inventorysync.storage.MemoryStore;
import com.mrgregles.bsp_inventorysync.storage.SaveCause;
import com.mrgregles.bsp_inventorysync.storage.SyncStore;
import com.mrgregles.bsp_inventorysync.sync.ArrivalPolicy;
import com.mrgregles.bsp_inventorysync.sync.SyncService;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * D30 and its audit: an admin returns a player's grave into their inventory ({@code /invsync grave return},
 * {@code InventorySyncApi.returnGrave}). The stores answer at once and the grave code runs its server-thread steps at
 * once then, so every future is done when the call returns (a test that needs a late answer holds it with a gate).
 * Graves are written into the stores directly; the player list is a map of mock players.
 */
@GameTestHolder(BSPInventorySync.MODID)
@PrefixGameTestTemplate(false)
public class GraveReturnTests {
    private static final String EMPTY = "empty";
    private static final String HUB = "hub";
    private static final String PVP = "pvp";

    private static <T> T now(CompletableFuture<T> f) {
        if (!f.isDone()) {
            throw new AssertionError("the future is not done yet");
        }
        return f.join();
    }

    /** A grave of {@code owner}: a sword from hotbar 0, a helmet from the head slot (39), 10 stone from slot 9, and {@code xp}. */
    private static Grave grave(UUID owner, String serverId, boolean placed, long archivedAt, int xp) {
        List<GraveContents.Entry> entries = List.of(
                new GraveContents.Entry("inv:0", new ItemStack(Items.DIAMOND_SWORD)),
                new GraveContents.Entry("inv:39", new ItemStack(Items.IRON_HELMET)),
                new GraveContents.Entry("inv:9", new ItemStack(Items.STONE, 10)));
        return grave(owner, serverId, placed, archivedAt, xp, entries);
    }

    private static Grave grave(UUID owner, String serverId, boolean placed, long archivedAt, int xp, List<GraveContents.Entry> entries) {
        long t = System.currentTimeMillis();
        return new Grave(UUID.randomUUID(), owner, "Alex", serverId, "minecraft:overworld", 10, 64, 10, placed, t, t, archivedAt, xp, entries.size(), 0,
                new GraveContents(entries, new CompoundTag()).encode());
    }

    private static boolean marked(Grave g) {
        return GraveContents.decode(g.data()).extras().contains("ReturnBy");
    }

    /** A store over {@code real} whose answers pass through {@code hook} first (null: the real answer). */
    private static SyncStore hooked(MemoryStore real, Function<java.lang.reflect.Method, Object> hook) {
        return (SyncStore) java.lang.reflect.Proxy.newProxyInstance(SyncStore.class.getClassLoader(), new Class<?>[] {SyncStore.class}, (proxy, m, args) -> {
            Object answer = hook.apply(m);
            if (answer != null) {
                return answer instanceof Function<?, ?> f ? ((Function<Object[], Object>) f).apply(args) : answer;
            }
            try {
                return m.invoke(real, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }

    private static void fill(ServerPlayer p) {
        for (int i = 0; i < 41; i++) {
            p.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
        }
    }

    // ------------------------------------------------------------------ now (GRV-1)

    @GameTest(template = EMPTY)
    public static void a_returned_grave_goes_back_into_the_slots_it_came_from_with_its_xp(GameTestHelper h) {
        WorldGraveStore world = new WorldGraveStore();
        GraveService graves = new GraveService(h.getLevel().getServer(), world, () -> null, 2);
        UUID id = UUID.randomUUID();
        ServerPlayer p = TestPlayers.mockServerPlayer(h, id);
        graves.setLivePlayerLookup(Map.of(id, p)::get);
        Grave g = grave(id, "", false, 0, 100);
        world.addGrave(g).join();
        int xpBefore = p.totalExperience;
        GraveReturn r = now(GraveActions.returnGrave(graves, id, "Alex", g.shortId(), "Mod"));
        h.assertTrue(r.outcome() == Outcome.OK && r.returned() == 3 && r.left() == 0 && r.xp() == 100, "returned: " + r);
        h.assertTrue(p.getInventory().getItem(0).is(Items.DIAMOND_SWORD) && p.getInventory().getItem(39).is(Items.IRON_HELMET)
                && p.getInventory().getItem(9).getCount() == 10, "each in the slot it came from, the helmet worn");
        h.assertTrue(p.totalExperience - xpBefore == 100, "the xp to them: " + (p.totalExperience - xpBefore));
        h.assertTrue(world.grave(g.id()).join().orElseThrow().empty(), "the grave is empty (kept as a marker)");
        h.assertTrue(now(GraveActions.returnGrave(graves, id, "Alex", g.shortId(), "Mod")).outcome() == Outcome.NOT_FOUND, "a second return finds nothing: nothing twice");

        Grave xpOnly = grave(id, "", false, 0, 50, List.of());
        world.addGrave(xpOnly).join();
        GraveReturn x = now(GraveActions.returnGrave(graves, id, "Alex", xpOnly.shortId(), "Mod"));
        h.assertTrue(x.outcome() == Outcome.OK && x.xp() == 50 && p.totalExperience - xpBefore == 150 && world.grave(xpOnly.id()).join().orElseThrow().empty(),
                "a grave holding only XP: the XP back, the grave empty: " + x);
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void what_does_not_fit_stays_in_the_grave_and_a_put_away_one_comes_back(GameTestHelper h) {
        WorldGraveStore world = new WorldGraveStore();
        GraveService graves = new GraveService(h.getLevel().getServer(), world, () -> null, 2);
        UUID id = UUID.randomUUID();
        ServerPlayer p = TestPlayers.mockServerPlayer(h, id);
        graves.setLivePlayerLookup(Map.of(id, p)::get);
        for (int i = 0; i < 36; i++) {
            p.getInventory().setItem(i, new ItemStack(Items.DIRT, 64)); // the main inventory is full; the armour slots are free
        }
        Grave g = grave(id, PVP, true, System.currentTimeMillis() - 1_000, 100); // put away (its headstone stood on pvp)
        world.addGrave(g).join();
        int xpBefore = p.totalExperience;
        GraveReturn r = now(GraveActions.returnGrave(graves, id, "Alex", g.shortId(), "Mod"));
        h.assertTrue(r.outcome() == Outcome.PARTIAL && r.returned() == 1 && r.left() == 2 && r.xp() == 100, "only the helmet fits: " + r);
        h.assertTrue(p.getInventory().getItem(39).is(Items.IRON_HELMET) && p.totalExperience - xpBefore == 100, "worn, and the XP reached them");
        Grave after = world.grave(g.id()).join().orElseThrow();
        h.assertTrue(after.stacks() == 2 && after.xp() == 0 && !after.archived(), "the sword and the stone wait in it, brought back from put-away: " + after);
        h.assertTrue(graves.recoverable(after, System.currentTimeMillis()), "and the player can recover the rest with /grave at once: " + after);
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void when_nothing_fits_a_put_away_grave_still_comes_back(GameTestHelper h) {
        WorldGraveStore world = new WorldGraveStore();
        GraveService graves = new GraveService(h.getLevel().getServer(), world, () -> null, 2);
        UUID id = UUID.randomUUID();
        ServerPlayer p = TestPlayers.mockServerPlayer(h, id);
        graves.setLivePlayerLookup(Map.of(id, p)::get);
        fill(p);
        Grave g = grave(id, PVP, true, System.currentTimeMillis() - 1_000, 0);
        world.addGrave(g).join();
        GraveReturn r = now(GraveActions.returnGrave(graves, id, "Alex", g.shortId(), "Mod"));
        h.assertTrue(r.outcome() == Outcome.PARTIAL && r.returned() == 0 && r.left() == 3, "nothing fits: " + r);
        Grave after = world.grave(g.id()).join().orElseThrow();
        h.assertTrue(!after.archived() && !after.placed() && after.stacks() == 3 && graves.recoverable(after, System.currentTimeMillis()),
                "not left put away on its way to deletion: brought back, recoverable with /grave: " + after);
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_standing_headstone_or_someone_elses_grave_is_not_returned(GameTestHelper h) {
        WorldGraveStore world = new WorldGraveStore();
        GraveService graves = new GraveService(h.getLevel().getServer(), world, () -> null, 2);
        UUID id = UUID.randomUUID();
        ServerPlayer p = TestPlayers.mockServerPlayer(h, id);
        graves.setLivePlayerLookup(Map.of(id, p)::get);
        Grave standing = grave(id, PVP, true, 0, 100); // a headstone on pvp
        Grave gone = grave(id, "", true, 0, 100); // a headstone of this server whose block is missing: not a reason (D30 5b, audit 2a)
        Grave theirs = grave(UUID.randomUUID(), "", false, 0, 100);
        world.addGrave(standing).join();
        world.addGrave(gone).join();
        world.addGrave(theirs).join();
        GraveReturn r = now(GraveActions.returnGrave(graves, id, "Alex", standing.shortId(), "Mod"));
        h.assertTrue(r.outcome() == Outcome.REFUSED && world.grave(standing.id()).join().orElseThrow().stacks() == 3, "a standing headstone is opened where it stands: " + r);
        h.assertTrue(now(GraveActions.returnGrave(graves, id, "Alex", gone.shortId(), "Mod")).outcome() == Outcome.REFUSED, "nor one whose block is missing");
        h.assertTrue(now(GraveActions.returnGrave(graves, id, "Alex", theirs.shortId(), "Mod")).outcome() == Outcome.NOT_FOUND, "another player's grave, by the list");
        h.assertTrue(now(graves.access().giveBack(p, theirs.id(), "Mod")).outcome() == Outcome.NOT_FOUND, "and by the return itself, asked directly");
        h.assertTrue(now(GraveActions.returnGrave(graves, id, "Alex", standing.shortId().substring(0, 7), "Mod")).outcome() == Outcome.NOT_FOUND, "an id shorter than 8 characters");
        List<GraveInfo> infos = now(GraveActions.infos(graves, id, System.currentTimeMillis()));
        h.assertTrue(infos.size() == 2 && infos.stream().noneMatch(GraveInfo::returnable), "listed, not returnable: " + infos);
        h.assertTrue(p.getInventory().isEmpty(), "nothing given");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void stacks_that_could_not_be_handed_over_wait_in_a_new_grave_with_the_xp(GameTestHelper h) {
        MemoryStore real = new MemoryStore();
        CompletableFuture<Void> gate = new CompletableFuture<>();
        boolean[] hold = {true};
        SyncStore slow = hooked(real, m -> m.getName().equals("updateGrave") && hold[0]
                ? (Function<Object[], Object>) args -> {
                    hold[0] = false;
                    return gate.thenCompose(v -> real.updateGrave((Grave) args[0]));
                } : null);
        SyncService hub = new SyncService(h.getLevel().getServer(), slow, HUB);
        GraveService graves = new GraveService(h.getLevel().getServer(), new WorldGraveStore(), () -> hub, 2);
        UUID id = UUID.randomUUID();
        ServerPlayer p = TestPlayers.mockServerPlayer(h, id);
        graves.setLivePlayerLookup(Map.of(id, p)::get);
        Grave g = grave(id, PVP, false, 0, 100);
        real.addGrave(g).join();
        CompletableFuture<GraveReturn> f = graves.access().giveBack(p, g.id(), "Mod");
        h.assertTrue(!f.isDone(), "the grave is being written");
        fill(p); // meanwhile the inventory filled up
        gate.complete(null);
        GraveReturn r = now(f);
        h.assertTrue(r.outcome() == Outcome.PARTIAL && r.returned() == 0 && r.left() == 3 && r.message().contains("new grave"), "counted as left, the new grave named: " + r);
        List<Grave> mine = real.graves(id).join().stream().filter(x -> !x.empty()).toList();
        h.assertTrue(mine.size() == 1 && mine.get(0).stacks() == 3 && mine.get(0).serverId().equals(PVP) && !mine.get(0).placed(),
                "a new grave with the three stacks, kept under the server their contents came from: " + mine);
        h.assertTrue(real.grave(g.id()).join().orElseThrow().empty(), "the old one is empty");

        // the player leaves mid-return: the XP goes into the new grave with the stacks
        Grave g2 = grave(id, PVP, false, 0, 40);
        real.addGrave(g2).join();
        CompletableFuture<Void> gate2 = new CompletableFuture<>();
        hold[0] = true;
        SyncStore slow2 = hooked(real, m -> m.getName().equals("updateGrave") && hold[0]
                ? (Function<Object[], Object>) args -> {
                    hold[0] = false;
                    return gate2.thenCompose(v -> real.updateGrave((Grave) args[0]));
                } : null);
        SyncService hub2 = new SyncService(h.getLevel().getServer(), slow2, HUB);
        GraveService graves2 = new GraveService(h.getLevel().getServer(), new WorldGraveStore(), () -> hub2, 2);
        ServerPlayer p2 = TestPlayers.mockServerPlayer(h, id);
        graves2.setLivePlayerLookup(Map.of(id, p2)::get);
        CompletableFuture<GraveReturn> f2 = graves2.access().giveBack(p2, g2.id(), "Mod");
        p2.setHealth(0f); // dead meanwhile
        gate2.complete(null);
        GraveReturn r2 = now(f2);
        h.assertTrue(r2.xp() == 0 && r2.left() == 3, "nothing given to a player who is gone: " + r2);
        Grave kept = real.graves(id).join().stream().filter(x -> !x.empty() && !x.id().equals(mine.get(0).id())).findFirst().orElseThrow();
        h.assertTrue(kept.xp() == 40 && kept.stacks() == 3, "the XP waits with the stacks in the new grave: " + kept);
        h.succeed();
    }

    // ------------------------------------------------------------------ at the next login (GRV-2)

    @GameTest(template = EMPTY)
    public static void an_offline_players_grave_comes_back_at_their_next_arrival(GameTestHelper h) {
        MemoryStore store = new MemoryStore();
        SyncService hub = new SyncService(h.getLevel().getServer(), store, HUB);
        GraveService graves = new GraveService(h.getLevel().getServer(), new WorldGraveStore(), () -> hub, 2);
        UUID id = UUID.randomUUID();
        Map<UUID, ServerPlayer> online = new HashMap<>();
        graves.setLivePlayerLookup(online::get);
        Grave g = grave(id, PVP, false, 0, 100);
        store.addGrave(g).join();
        GraveReturn queued = now(GraveActions.returnGrave(graves, id, "Alex", g.shortId(), "Mod"));
        h.assertTrue(queued.outcome() == Outcome.QUEUED && queued.ok(), "offline: queued: " + queued);
        h.assertTrue(marked(store.grave(g.id()).join().orElseThrow()) && store.extra(GraveService.RETURN, id).join().isPresent(),
                "the mark on the grave itself, and a hint for the minute round");
        h.assertTrue(now(GraveActions.returnGrave(graves, id, "Alex", g.shortId(), "Mod")).outcome() == Outcome.QUEUED, "asking again is harmless");

        ServerPlayer p = TestPlayers.mockServerPlayer(h, id);
        online.put(id, p); // they log in here
        int n = now(graves.deliverReturns(List.of(p)));
        h.assertTrue(n == 1 && p.getInventory().getItem(0).is(Items.DIAMOND_SWORD) && p.getInventory().getItem(39).is(Items.IRON_HELMET), "returned: " + n);
        Grave after = store.grave(g.id()).join().orElseThrow();
        h.assertTrue(after.empty() && !marked(after) && store.extra(GraveService.RETURN, id).join().isEmpty(), "the mark, the hint and the grave are spent");
        h.assertTrue(store.latest(id).join().map(s -> s.cause() == SaveCause.ADMIN).orElse(false), "and the player was saved at once");
        h.assertTrue(now(graves.deliverReturns(List.of(p), true)) == 0, "nothing more to return");

        // an arrival looks at the graves even without the hint (it was lost)
        Grave g2 = grave(id, PVP, false, 0, 0);
        store.addGrave(g2).join();
        online.remove(id);
        now(GraveActions.returnGrave(graves, id, "Alex", g2.shortId(), "Mod"));
        store.deleteExtra(GraveService.RETURN, id).join();
        online.put(id, p);
        p.getInventory().clearContent();
        h.assertTrue(now(graves.deliverReturns(List.of(p))) == 0, "the minute round needs the hint");
        h.assertTrue(now(graves.deliverReturns(List.of(p), true)) == 1 && p.getInventory().getItem(0).is(Items.DIAMOND_SWORD), "the arrival finds the mark");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_busy_return_is_tried_again_and_a_queued_one_is_never_lost_to_the_hint(GameTestHelper h) {
        MemoryStore real = new MemoryStore();
        boolean[] refuse = {false};
        SyncStore flaky = hooked(real, m -> m.getName().equals("updateGrave") && refuse[0]
                ? (Function<Object[], Object>) args -> {
                    refuse[0] = false;
                    return CompletableFuture.completedFuture(false); // another server changed the grave first
                } : null);
        SyncService hub = new SyncService(h.getLevel().getServer(), flaky, HUB);
        GraveService graves = new GraveService(h.getLevel().getServer(), new WorldGraveStore(), () -> hub, 2);
        UUID id = UUID.randomUUID();
        Map<UUID, ServerPlayer> online = new HashMap<>();
        graves.setLivePlayerLookup(online::get);
        Grave g = grave(id, PVP, false, 0, 0);
        real.addGrave(g).join();
        now(GraveActions.returnGrave(graves, id, "Alex", g.shortId(), "Mod"));
        ServerPlayer p = TestPlayers.mockServerPlayer(h, id);
        online.put(id, p);
        refuse[0] = true;
        h.assertTrue(now(graves.deliverReturns(List.of(p))) == 0, "the grave changed meanwhile: not returned this round");
        h.assertTrue(marked(real.grave(g.id()).join().orElseThrow()) && real.extra(GraveService.RETURN, id).join().isPresent(), "still marked, a new hint for the next round");
        h.assertTrue(now(graves.deliverReturns(List.of(p))) == 1 && p.getInventory().getItem(0).is(Items.DIAMOND_SWORD), "returned the next round");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_player_still_arriving_gets_it_once_their_data_is_here_and_a_refused_one_never(GameTestHelper h) {
        MemoryStore store = new MemoryStore();
        SyncService hub = new SyncService(h.getLevel().getServer(), store, HUB, new ArrivalPolicy(60_000, false));
        GraveService graves = new GraveService(h.getLevel().getServer(), new WorldGraveStore(), () -> hub, 2);
        UUID id = UUID.randomUUID();
        ServerPlayer p = TestPlayers.mockServerPlayer(h, id);
        graves.setLivePlayerLookup(Map.of(id, p)::get);
        Grave g = grave(id, PVP, false, 0, 100);
        store.addGrave(g).join();
        store.setPresence(id, PVP, true).join(); // pvp has not released them yet
        hub.onLogin(p, 0);
        GraveReturn r = now(GraveActions.returnGrave(graves, id, "Alex", g.shortId(), "Mod"));
        h.assertTrue(r.outcome() == Outcome.QUEUED, "still arriving: queued, not put into the copy that is about to be replaced: " + r);
        h.assertTrue(now(graves.deliverReturns(List.of(p), true)) == 0 && p.getInventory().isEmpty(), "nothing while they are still arriving");
        store.setPresence(id, PVP, false).join();
        hub.tick(1);
        h.assertTrue(!SyncService.isSyncing(id), "arrived");
        h.assertTrue(now(graves.deliverReturns(List.of(p), true)) == 1 && p.getInventory().getItem(0).is(Items.DIAMOND_SWORD), "then returned");
        hub.onLogout(p);

        // a login refused here: nothing is handed to a player who is about to be disconnected unsaved
        UUID other = UUID.randomUUID();
        ServerPlayer q = TestPlayers.mockServerPlayer(h, other);
        Grave g2 = grave(other, PVP, false, 0, 0);
        store.addGrave(g2).join();
        store.save(other, PVP, SaveCause.LOGOUT, com.mrgregles.bsp_inventorysync.data.PlayerSnapshotCodec.encode(com.mrgregles.bsp_inventorysync.data.PlayerSnapshotCodec.capture(q))).join();
        graves.setLivePlayerLookup(Map.of(other, q)::get);
        hub.failNextRestore(new IllegalStateException("test: the restore fails"));
        hub.onLogin(q, 0);
        hub.tick(2);
        h.assertTrue(hub.refused(other), "refused");
        ServerPlayer still = TestPlayers.mockServerPlayer(h, other); // still connected: on a real server the disconnect lands a tick later
        graves.setLivePlayerLookup(Map.of(other, still)::get);
        h.assertTrue(now(GraveActions.returnGrave(graves, other, "Bob", g2.shortId(), "Mod")).outcome() == Outcome.FAILED, "the return says so");
        h.assertTrue(now(graves.access().giveBack(still, g2.id(), "Mod")).outcome() == Outcome.BUSY, "the return itself refuses them");
        h.assertTrue(now(graves.deliverReturns(List.of(still), true)) == 0 && still.getInventory().isEmpty() && store.grave(g2.id()).join().orElseThrow().stacks() == 3,
                "and nothing is delivered");
        hub.onLogout(q);
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_player_on_another_server_is_named_and_with_the_sync_off_offline_fails(GameTestHelper h) {
        MemoryStore store = new MemoryStore();
        SyncService hub = new SyncService(h.getLevel().getServer(), store, HUB);
        GraveService graves = new GraveService(h.getLevel().getServer(), new WorldGraveStore(), () -> hub, 2);
        UUID id = UUID.randomUUID();
        graves.setLivePlayerLookup(new HashMap<UUID, ServerPlayer>()::get);
        Grave g = grave(id, PVP, false, 0, 100);
        store.addGrave(g).join();
        store.setPresence(id, PVP, true).join();
        GraveReturn r = now(GraveActions.returnGrave(graves, id, "Alex", g.shortId(), "Mod"));
        h.assertTrue(r.outcome() == Outcome.ONLINE && r.serverId().equals(PVP) && !marked(store.grave(g.id()).join().orElseThrow()), "on pvp: return it there, nothing marked: " + r);

        WorldGraveStore world = new WorldGraveStore();
        GraveService off = new GraveService(h.getLevel().getServer(), world, () -> null, 2);
        off.setLivePlayerLookup(new HashMap<UUID, ServerPlayer>()::get);
        Grave local = grave(id, "", false, 0, 100);
        world.addGrave(local).join();
        h.assertTrue(now(GraveActions.returnGrave(off, id, "Alex", local.shortId(), "Mod")).outcome() == Outcome.FAILED, "offline with the sync off: failed");
        h.succeed();
    }
}
