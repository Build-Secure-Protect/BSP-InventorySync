package com.mrgregles.bsp_inventorysync.gametest;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.compat.PlayerReset;
import com.mrgregles.bsp_inventorysync.compat.PlayerReset.Forgot;
import com.mrgregles.bsp_inventorysync.give.GiveService;
import com.mrgregles.bsp_inventorysync.grave.GraveActions;
import com.mrgregles.bsp_inventorysync.grave.GraveBlockEntity;
import com.mrgregles.bsp_inventorysync.grave.GraveContents;
import com.mrgregles.bsp_inventorysync.grave.GraveService;
import com.mrgregles.bsp_inventorysync.grave.WorldGraveStore;
import com.mrgregles.bsp_inventorysync.storage.Grave;
import com.mrgregles.bsp_inventorysync.storage.MemoryStore;
import com.mrgregles.bsp_inventorysync.storage.SaveCause;
import com.mrgregles.bsp_inventorysync.storage.SyncStore;
import com.mrgregles.bsp_inventorysync.sync.SyncService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * PR18: BSP-Core's {@code /bsp player reset} reaches this mod as {@code PlayerResetEvent$Forgotten}, and the handler
 * ({@link PlayerReset#forget}) drops the player's waiting gives, their give/grave extras and empties their graves, keeps
 * their snapshots, and keeps anything of theirs from before the reset out of their reach afterwards. This mod builds
 * against a BSP-Core without the event, so the handler is called directly (and once through a stand-in event class by
 * name); the stores answer at once. The stores' clock is the test's: the reset is at {@link #RESET}, "before" is well
 * before it, "just before" is inside the clock grace.
 */
@GameTestHolder(BSPInventorySync.MODID)
@PrefixGameTestTemplate(false)
public class PlayerResetTests {
    private static final String EMPTY = "empty";
    private static final String HUB = "hub", PVP = "pvp";
    private static final long RESET = 100_000, BEFORE = 10_000, JUST_BEFORE = RESET - PlayerReset.GRACE_MS + 2_000, AFTER = 200_000;

    private static <T> T now(CompletableFuture<T> f) {
        if (!f.isDone()) {
            throw new AssertionError("the future is not done yet");
        }
        return f.join();
    }

    private static Grave grave(UUID owner, String serverId, boolean placed, long diedAt, BlockPos at) {
        List<GraveContents.Entry> entries = List.of(new GraveContents.Entry("inv:0", new ItemStack(Items.DIAMOND_SWORD)));
        return new Grave(UUID.randomUUID(), owner, "Alex", serverId, "minecraft:overworld", at.getX(), at.getY(), at.getZ(), placed, diedAt, diedAt, 0, 30,
                entries.size(), 0, new GraveContents(entries, new CompoundTag()).encode());
    }

    private static boolean standing(GameTestHelper h, BlockPos abs, Grave g) {
        return h.getLevel().getBlockEntity(abs) instanceof GraveBlockEntity be && g.id().equals(be.graveId());
    }

    private static byte[] one() {
        return new byte[] {1};
    }

    private static byte[] at(long ms) {
        return ByteBuffer.allocate(8).putLong(ms).array();
    }

    private static byte[] give(net.minecraft.world.item.Item item, int n) {
        return GiveService.encode(new ItemStack(item, n));
    }

    /** A store over {@code real} whose answers pass through {@code hook} first (null: the real answer). */
    @SuppressWarnings("unchecked")
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

    private static String text(List<Component> lines) {
        StringBuilder b = new StringBuilder();
        lines.forEach(c -> b.append(c.getString()).append('\n'));
        return b.toString();
    }

    @GameTest(template = EMPTY)
    public static void the_origin_forgets_gives_extras_and_graves_but_keeps_the_snapshots(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        AtomicLong clock = new AtomicLong(BEFORE);
        MemoryStore store = new MemoryStore(clock::get);
        SyncService hub = new SyncService(server, store, HUB);
        GiveService gives = new GiveService(server, store, HUB, 5_000);
        WorldGraveStore world = new WorldGraveStore();
        GraveService graves = new GraveService(server, world, () -> hub, 2);
        UUID id = UUID.randomUUID(), other = UUID.randomUUID();
        PlayerReset.clearForTests(server, id);
        graves.setLivePlayerLookup(Map.<UUID, ServerPlayer>of()::get);

        store.save(id, HUB, SaveCause.LOGOUT, new byte[] {9}).join();
        store.save(id, HUB, SaveCause.AUTOSAVE, new byte[] {8}).join();
        store.addGive(id, give(Items.EMERALD, 5), "Admin").join();
        store.addGive(id, give(Items.DIAMOND, 2), "Admin").join();
        store.addGive(other, give(Items.EMERALD, 1), "Admin").join();
        for (String kind : List.of(GiveService.DECLINED, GraveService.NOTICE, GraveService.RETURN)) {
            store.putExtra(kind, id, one()).join();
            store.putExtra(kind, other, one()).join();
        }
        store.putExtra("backpack", id, one()).join(); // keyed by a backpack, not the player: never touched (same key here on purpose)
        BlockPos here = h.absolutePos(new BlockPos(1, 2, 1));
        Grave standingHere = grave(id, HUB, true, BEFORE, here);
        GraveService.placeBlock(h.getLevel(), here, Direction.NORTH, standingHere.id(), id, "Alex", BEFORE, BEFORE, true);
        Grave onPvp = grave(id, PVP, true, BEFORE, here.east());
        Grave theirs = grave(other, HUB, false, BEFORE, here);
        store.addGrave(standingHere).join();
        store.addGrave(onPvp).join();
        store.addGrave(theirs).join();
        Grave waiting = grave(id, HUB, false, BEFORE, here); // in this world's data, on its way to the database
        world.addGrave(waiting).join();
        h.assertTrue(standing(h, here, standingHere), "a headstone of theirs stands here");

        clock.set(RESET);
        Forgot f = now(PlayerReset.forget(server, store, gives, graves, id, "Alex", RESET, true));
        h.assertTrue(f.gives() == 2 && f.extras() == 3 && f.graves() == 2 && f.localGraves() == 1 && f.complete(), "everything of theirs went: " + f);
        h.assertTrue(store.gives(List.of(id)).join().isEmpty() && store.gives(List.of(other)).join().size() == 1, "their waiting stacks, not another player's");
        for (String kind : List.of(GiveService.DECLINED, GraveService.NOTICE, GraveService.RETURN)) {
            h.assertTrue(store.extra(kind, id).join().isEmpty() && store.extra(kind, other).join().isPresent(), kind + ": theirs gone, another player's kept");
        }
        h.assertTrue(store.extra("backpack", id).join().isPresent(), "an extra of another kind is not the player's");
        List<Grave> left = store.graves(id).join();
        h.assertTrue(left.size() == 2 && left.stream().allMatch(Grave::empty), "their graves on every server are empty markers, so no old copy brings them back: " + left);
        h.assertTrue(!store.addGrave(standingHere).join() && store.grave(standingHere.id()).join().orElseThrow().empty(), "an old copy sent again finds its id taken");
        h.assertTrue(store.graves(other).join().stream().noneMatch(Grave::empty), "another player's grave is untouched");
        h.assertTrue(world.graves(id).join().stream().allMatch(Grave::empty), "the one in this world's data is a marker too (it carries the marker to the database)");
        h.assertTrue(!standing(h, here, standingHere), "the headstone standing here is gone");
        h.assertTrue(store.history(id).join().size() == 2, "the snapshot history is kept, for /invsync restore");
        h.assertTrue(PlayerReset.Gate.get(server).resetAt(id) == RESET, "this server's record of the reset is in its world data");

        // the undo: /invsync restore of a snapshot from before the reset, onto the reset player, keeps BSP-Core's stamp
        ServerPlayer p = TestPlayers.mockServerPlayer(h, id);
        p.getInventory().setItem(0, new ItemStack(Items.APPLE, 3));
        long preReset = hub.save(p, SaveCause.LOGOUT).join().version(); // no stamp in it
        p.getInventory().clearContent(); // BSP-Core's clear
        CompoundTag persisted = new CompoundTag();
        persisted.putLong("bsp_core:ResetAt", RESET);
        p.getPersistentData().put(Player.PERSISTED_NBT_TAG, persisted);
        List<String> rolled = com.mrgregles.bsp_inventorysync.command.AdminActions.restore(hub, server, p, preReset).join();
        h.assertTrue(p.getInventory().countItem(Items.APPLE) == 3, "the items from before the reset are back: " + rolled);
        Long kept = PlayerReset.stamp(p);
        h.assertTrue(kept != null && kept == RESET, "and the stamp is kept, so BSP-Core does not wipe them again: " + kept);
        long saved = store.latest(id).join().orElseThrow().version();
        ServerPlayer elsewhere = TestPlayers.mockServerPlayer(h, id); // their next arrival, from the admin save
        com.mrgregles.bsp_inventorysync.command.AdminActions.restore(hub, server, elsewhere, saved).join();
        Long carried = PlayerReset.stamp(elsewhere);
        h.assertTrue(carried != null && carried == RESET && elsewhere.getInventory().countItem(Items.APPLE) == 3, "the admin save carries the stamp: " + carried);
        ServerPlayer fresh = TestPlayers.mockServerPlayer(h);
        h.assertTrue(PlayerReset.stamp(fresh) == null, "a player never reset has no stamp");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_late_server_forgets_what_dates_from_before_the_reset_but_not_the_notices(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        AtomicLong clock = new AtomicLong(BEFORE);
        MemoryStore store = new MemoryStore(clock::get);
        SyncService pvp = new SyncService(server, store, PVP);
        GiveService gives = new GiveService(server, store, PVP, 5_000);
        WorldGraveStore world = new WorldGraveStore();
        GraveService graves = new GraveService(server, world, () -> pvp, 2);
        UUID id = UUID.randomUUID();
        PlayerReset.clearForTests(server, id);
        graves.setLivePlayerLookup(Map.<UUID, ServerPlayer>of()::get);

        store.addGive(id, give(Items.EMERALD, 5), "Admin").join();
        clock.set(JUST_BEFORE);
        store.addGive(id, give(Items.GOLD_INGOT, 1), "Admin").join(); // inside the clock grace: could be just after the reset on a slow clock
        clock.set(AFTER);
        store.addGive(id, give(Items.DIAMOND, 1), "Admin").join(); // given to the new player
        store.putExtra(GiveService.DECLINED, id, at(BEFORE)).join();
        store.putExtra(GraveService.NOTICE, id, one()).join();
        store.putExtra(GraveService.RETURN, id, one()).join();
        BlockPos here = h.absolutePos(new BlockPos(1, 2, 1));
        Grave netGrave = grave(id, PVP, true, BEFORE, here);
        GraveService.placeBlock(h.getLevel(), here, Direction.NORTH, netGrave.id(), id, "Alex", BEFORE, BEFORE, true);
        store.addGrave(netGrave).join();
        Grave newer = grave(id, HUB, false, AFTER, here);
        store.addGrave(newer).join();
        Grave before = grave(id, PVP, false, BEFORE, here), after = grave(id, PVP, false, AFTER, here);
        world.addGrave(before).join();
        world.addGrave(after).join(); // a death here after the reset, which this server heard of late

        Forgot f = now(PlayerReset.forget(server, store, gives, graves, id, "Alex", RESET, false));
        h.assertTrue(f.gives() == 1 && f.extras() == 1 && f.graves() == 1 && f.localGraves() == 1, "what dates from before the reset, on any server: " + f);
        h.assertTrue(store.gives(List.of(id)).join().size() == 2, "the stacks given just before (inside the grace) and after the reset wait on");
        h.assertTrue(store.extra(GiveService.DECLINED, id).join().isEmpty(), "a Cancel from before the reset goes (it carries its time)");
        h.assertTrue(store.extra(GraveService.NOTICE, id).join().isPresent() && store.extra(GraveService.RETURN, id).join().isPresent(),
                "the notices and return hints carry no time: they are the origin's to delete");
        h.assertTrue(store.grave(netGrave.id()).join().orElseThrow().empty() && !store.grave(newer.id()).join().orElseThrow().empty(), "the old grave emptied, the new one kept");
        h.assertTrue(!standing(h, here, netGrave), "its headstone here goes once the marker is written");
        h.assertTrue(world.grave(before.id()).join().orElseThrow().empty() && !world.grave(after.id()).join().orElseThrow().empty(), "a grave from after the reset is kept");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void hearing_of_the_same_reset_twice_keeps_what_came_after_it_and_the_sync_off_forgets_its_own(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        AtomicLong clock = new AtomicLong(BEFORE);
        MemoryStore store = new MemoryStore(clock::get);
        SyncService hub = new SyncService(server, store, HUB);
        GiveService gives = new GiveService(server, store, HUB, 5_000);
        GraveService graves = new GraveService(server, new WorldGraveStore(), () -> hub, 2);
        UUID id = UUID.randomUUID();
        PlayerReset.clearForTests(server, id);
        graves.setLivePlayerLookup(Map.<UUID, ServerPlayer>of()::get);

        store.addGive(id, give(Items.EMERALD, 5), "Admin").join();
        h.assertTrue(now(PlayerReset.forget(server, store, gives, graves, id, "Alex", RESET, true)).gives() == 1, "the first time it goes");

        clock.set(AFTER); // the new player plays on
        store.addGive(id, give(Items.DIAMOND, 1), "Admin").join();
        store.putExtra(GiveService.DECLINED, id, at(AFTER)).join();
        store.putExtra(GraveService.NOTICE, id, one()).join();
        Grave later = grave(id, HUB, false, AFTER, BlockPos.ZERO);
        store.addGrave(later).join();

        Forgot again = now(PlayerReset.forget(server, store, gives, graves, id, "Alex", RESET, true));
        h.assertTrue(again.nothing(), "the same reset again forgets nothing: " + again);
        h.assertTrue(store.gives(List.of(id)).join().size() == 1 && store.extra(GiveService.DECLINED, id).join().isPresent()
                && store.extra(GraveService.NOTICE, id).join().isPresent() && !store.grave(later.id()).join().orElseThrow().empty(), "what came after the reset is kept");
        h.assertTrue(now(PlayerReset.forget(server, store, gives, graves, id, "Alex", RESET, false)).nothing(), "and from a late server too");

        // with the sync off, this server's own waiting stacks and Cancel go wherever the reset was typed (they carry their times)
        AtomicLong offClock = new AtomicLong(BEFORE);
        MemoryStore own = new MemoryStore(offClock::get);
        GiveService offGives = new GiveService(server, own, null, 5_000);
        WorldGraveStore offWorld = new WorldGraveStore();
        GraveService offGraves = new GraveService(server, offWorld, () -> null, 2);
        UUID off = UUID.randomUUID(), steve = UUID.randomUUID();
        PlayerReset.clearForTests(server, off);
        PlayerReset.clearForTests(server, steve);
        offGraves.setLivePlayerLookup(Map.<UUID, ServerPlayer>of()::get);
        own.addGive(off, give(Items.EMERALD, 5), "Admin").join();
        own.putExtra(GiveService.DECLINED, off, at(BEFORE)).join();
        own.putExtra(GiveService.DECLINED, steve, at(AFTER)).join(); // another player, whose Cancel came after their reset
        offWorld.addGrave(grave(off, "", false, BEFORE, BlockPos.ZERO)).join();

        Forgot f = now(PlayerReset.forget(server, null, offGives, offGraves, off, "Alex", RESET, false));
        h.assertTrue(f.gives() == 1 && f.extras() == 1 && f.localGraves() == 1 && f.graves() == 0, "this server's own, though it was typed elsewhere: " + f);
        h.assertTrue(own.gives(List.of(off)).join().isEmpty() && own.extra(GiveService.DECLINED, off).join().isEmpty()
                && offWorld.graves(off).join().stream().allMatch(Grave::empty), "all gone");
        h.assertTrue(now(PlayerReset.forget(server, null, offGives, offGraves, steve, "Steve", RESET, false)).nothing() && own.extra(GiveService.DECLINED, steve).join().isPresent(),
                "a Cancel made after the reset is kept");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_grave_that_could_not_be_emptied_stays_out_of_reach(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        MemoryStore real = new MemoryStore(() -> BEFORE);
        BlockPos here = h.absolutePos(new BlockPos(1, 2, 1)), there = h.absolutePos(new BlockPos(3, 2, 1));
        UUID id = UUID.randomUUID();
        Grave stuck = grave(id, HUB, true, BEFORE, here), once = grave(id, HUB, true, BEFORE, there);
        Set<UUID> refusedOnce = new HashSet<>();
        // the stuck grave's write is always refused (a revision mismatch); the other's only the first time
        SyncStore store = hooked(real, m -> m.getName().equals("updateGrave")
                ? (Function<Object[], Object>) args -> {
                    Grave g = (Grave) args[0];
                    if (g.id().equals(stuck.id()) || refusedOnce.add(g.id())) {
                        return CompletableFuture.completedFuture(false);
                    }
                    return real.updateGrave(g);
                } : null);
        SyncService hub = new SyncService(server, store, HUB);
        GraveService graves = new GraveService(server, new WorldGraveStore(), () -> hub, 2);
        PlayerReset.clearForTests(server, id);
        Map<UUID, ServerPlayer> online = new HashMap<>();
        graves.setLivePlayerLookup(online::get);
        GraveService.placeBlock(h.getLevel(), here, Direction.NORTH, stuck.id(), id, "Alex", BEFORE, BEFORE, true);
        GraveService.placeBlock(h.getLevel(), there, Direction.NORTH, once.id(), id, "Alex", BEFORE, BEFORE, true);
        real.addGrave(stuck).join();
        real.addGrave(once).join();

        Forgot f = now(PlayerReset.forget(server, store, null, graves, id, "Alex", RESET, true));
        h.assertTrue(f.graves() == 1 && !f.complete(), "one emptied after a retry, one not: " + f);
        h.assertTrue(real.grave(once.id()).join().orElseThrow().empty() && !standing(h, there, once), "a refused write is read again and tried again; then its headstone goes");
        h.assertTrue(!real.grave(stuck.id()).join().orElseThrow().empty() && standing(h, here, stuck), "the headstone stays while the grave could not be emptied");

        ServerPlayer p = TestPlayers.mockServerPlayer(h, id);
        online.put(id, p);
        h.assertTrue(text(now(GraveActions.list(graves, id, AFTER))).contains("no graves"), "/grave lists nothing from before the reset");
        h.assertTrue(now(GraveActions.recover(graves, p, stuck.shortId(), AFTER)) != null, "/grave recover refuses it");
        h.assertTrue(now(graves.access().open(p, stuck.id(), false, h.getLevel().dimension(), here)) == null, "the headstone opens as empty");
        h.assertTrue(!standing(h, here, stuck) && p.getInventory().isEmpty() && !real.grave(stuck.id()).join().orElseThrow().empty(),
                "and goes, nothing handed over; the row stays for an admin");
        h.assertTrue(text(now(GraveActions.list(graves, id, AFTER))).contains("no graves"), "with its block gone it is still not offered for recovery");
        ServerPlayer other = TestPlayers.mockServerPlayer(h);
        h.assertTrue(now(graves.access().open(other, stuck.id(), false, null, null)) == null, "nor opened by anyone else");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void nothing_from_before_the_reset_is_handed_over_even_after_a_restart(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        AtomicLong clock = new AtomicLong(BEFORE);
        MemoryStore real = new MemoryStore(clock::get);
        boolean[] down = {false};
        SyncStore store = hooked(real, m -> down[0] && (m.getName().equals("takeGive") || m.getName().equals("updateGrave"))
                ? CompletableFuture.failedFuture(new java.sql.SQLException("test: the database is down")) : null);
        SyncService pvp = new SyncService(server, store, PVP);
        GiveService gives = new GiveService(server, store, PVP, 5_000);
        GraveService graves = new GraveService(server, new WorldGraveStore(), () -> pvp, 2);
        UUID id = UUID.randomUUID();
        PlayerReset.clearForTests(server, id);
        Map<UUID, ServerPlayer> online = new HashMap<>();
        graves.setLivePlayerLookup(online::get);

        real.addGive(id, give(Items.EMERALD, 5), "Admin").join();
        Grave old = grave(id, HUB, false, BEFORE, BlockPos.ZERO);
        real.addGrave(old).join();
        h.assertTrue(now(GraveActions.returnGrave(graves, id, "Alex", old.shortId(), "Mod")).ok(), "an admin queued a return before the reset");

        down[0] = true; // the deletes fail
        Forgot f = now(PlayerReset.forget(server, store, gives, graves, id, "Alex", RESET, true));
        down[0] = false;
        h.assertTrue(!f.complete() && real.gives(List.of(id)).join().size() == 1 && !real.grave(old.id()).join().orElseThrow().empty(), "the rows survived: " + f);

        // a restart: this server's record is read back from its world data
        CompoundTag saved = PlayerReset.Gate.get(server).save(new CompoundTag());
        h.assertTrue(PlayerReset.Gate.load(saved).resetAt(id) == RESET, "the record survives a restart");

        // and a server with no record of its own learns it from what the player carries (BSP-Core's bsp_core:ResetAt)
        PlayerReset.clearForTests(server, id);
        clock.set(JUST_BEFORE);
        real.addGive(id, give(Items.GOLD_INGOT, 1), "Admin").join();
        clock.set(AFTER);
        real.addGive(id, give(Items.DIAMOND, 1), "Admin").join();
        ServerPlayer p = TestPlayers.mockServerPlayer(h, id);
        CompoundTag persisted = new CompoundTag();
        persisted.putLong("bsp_core:ResetAt", RESET);
        p.getPersistentData().put(Player.PERSISTED_NBT_TAG, persisted);
        online.put(id, p);
        h.assertTrue(PlayerReset.resetAt(server, id, p) == RESET && PlayerReset.resetAt(server, id, null) == 0, "the reset time from the player's own data");

        now(gives.arrived(p, true)); // their next login, after BSP-Core's clear
        h.assertTrue(p.getInventory().countItem(Items.EMERALD) == 0, "the stack from before the reset is not handed over");
        h.assertTrue(p.getInventory().countItem(Items.DIAMOND) == 1 && p.getInventory().countItem(Items.GOLD_INGOT) == 1,
                "the ones given after it, or inside the clock grace, are");
        h.assertTrue(real.gives(List.of(id)).join().isEmpty(), "and the old row is dropped, not put back as a new one");
        h.assertTrue(now(graves.deliverReturns(List.of(p), true)) == 0 && p.getInventory().countItem(Items.DIAMOND_SWORD) == 0, "nor the grave queued for return");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void the_reset_is_heard_through_an_event_class_found_by_name_and_not_without_one(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        UUID id = UUID.randomUUID();
        PlayerReset.clearForTests(server, id);
        h.assertTrue(PlayerReset.register(FakeResetEvent.class.getName()) && PlayerReset.registered(FakeResetEvent.class.getName()), "registered by name");
        MinecraftForge.EVENT_BUS.post(new FakeResetEvent(server, id, "Alex", RESET, false));
        h.assertTrue(PlayerReset.Gate.get(server).resetAt(id) == RESET, "the getters were read and the reset recorded: " + PlayerReset.Gate.get(server).resetAt(id));
        h.assertTrue(PlayerReset.forgotten(server, id, null, BEFORE) && !PlayerReset.forgotten(server, id, null, JUST_BEFORE) && !PlayerReset.forgotten(server, id, null, AFTER),
                "the gate: well before the reset, not inside the grace or after");
        PlayerReset.clearForTests(server, id);

        // a BSP-Core without the reset: nothing registered
        String missing = "com.mrgregles.bsp_core.api.event.NoSuchResetEvent";
        h.assertTrue(!PlayerReset.register(missing) && !PlayerReset.registered(missing), "class not found: nothing registered");
        h.assertTrue(!PlayerReset.register("java.lang.String") && !PlayerReset.registered("java.lang.String"), "not an event: nothing registered");
        h.succeed();
    }
}
