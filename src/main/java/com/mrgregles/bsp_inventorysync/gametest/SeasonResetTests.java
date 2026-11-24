package com.mrgregles.bsp_inventorysync.gametest;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.api.event.InventoryRestoredEvent;
import com.mrgregles.bsp_inventorysync.compat.PlayerReset;
import com.mrgregles.bsp_inventorysync.compat.SeasonReset;
import com.mrgregles.bsp_inventorysync.compat.SeasonReset.Dropped;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * SR10: BSP-Core's {@code /bsp season reset} reaches this mod as {@code SeasonEvent$Ended} with {@code isSeasonReset()}, and the
 * handler ({@link SeasonReset#forgetAll}) drops every player's waiting gives, give/grave extras and graves, keeps every snapshot,
 * changes the database only on the server it was typed on, and keeps anything from before the reset out of everyone's reach. The
 * season reset counts as a reset of every player in {@link PlayerReset.Gate}, which this server's world data shares with every
 * other test: each test sets it and puts back what it found before it ends (the stores answer at once, so nothing else runs in
 * between). The stores' clock is the test's, as in {@link PlayerResetTests}.
 */
@GameTestHolder(BSPInventorySync.MODID)
@PrefixGameTestTemplate(false)
public class SeasonResetTests {
    private static final String EMPTY = "empty";
    private static final String HUB = "hub", PVP = "pvp";
    private static final long RESET = 100_000, BEFORE = 10_000, JUST_BEFORE = RESET - PlayerReset.GRACE_MS + 2_000, AFTER = 200_000;

    private static <T> T now(CompletableFuture<T> f) {
        if (!f.isDone()) {
            throw new AssertionError("the future is not done yet");
        }
        return f.join();
    }

    /** Runs the test body with the season gate as it found it afterwards, whatever the body did. */
    private static void withGate(GameTestHelper h, Consumer<MinecraftServer> body) {
        MinecraftServer server = h.getLevel().getServer();
        PlayerReset.Gate gate = PlayerReset.Gate.get(server);
        long was = gate.seasonAt();
        try {
            gate.setSeasonForTests(0);
            body.accept(server);
        } finally {
            gate.setSeasonForTests(was);
        }
        h.succeed();
    }

    private static Grave grave(UUID owner, String name, String serverId, boolean placed, long diedAt, BlockPos at) {
        List<GraveContents.Entry> entries = List.of(new GraveContents.Entry("inv:0", new ItemStack(Items.DIAMOND_SWORD)));
        return new Grave(UUID.randomUUID(), owner, name, serverId, "minecraft:overworld", at.getX(), at.getY(), at.getZ(), placed, diedAt, diedAt, 0, 30,
                entries.size(), 0, new GraveContents(entries, new CompoundTag()).encode());
    }

    private static boolean standing(GameTestHelper h, BlockPos abs, Grave g) {
        return h.getLevel().getBlockEntity(abs) instanceof GraveBlockEntity be && g.id().equals(be.graveId());
    }

    private static Grave place(GameTestHelper h, SyncStore store, UUID owner, String serverId, BlockPos abs) {
        Grave g = grave(owner, "Alex", serverId, true, BEFORE, abs);
        GraveService.placeBlock(h.getLevel(), abs, Direction.NORTH, g.id(), owner, "Alex", BEFORE, BEFORE, true);
        store.addGrave(g).join();
        return g;
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

    private static String text(List<Component> lines) {
        StringBuilder b = new StringBuilder();
        lines.forEach(c -> b.append(c.getString()).append('\n'));
        return b.toString();
    }

    @GameTest(template = EMPTY)
    public static void the_origin_forgets_every_players_gives_extras_and_graves_but_keeps_the_snapshots(GameTestHelper h) {
        withGate(h, server -> {
            AtomicLong clock = new AtomicLong(BEFORE);
            MemoryStore store = new MemoryStore(clock::get);
            SyncService hub = new SyncService(server, store, HUB);
            GiveService gives = new GiveService(server, store, HUB, 5_000);
            WorldGraveStore world = new WorldGraveStore();
            GraveService graves = new GraveService(server, world, () -> hub, 2);
            graves.setLivePlayerLookup(Map.<UUID, ServerPlayer>of()::get);
            UUID alex = UUID.randomUUID(), steve = UUID.randomUUID(), later = UUID.randomUUID();

            store.save(alex, HUB, SaveCause.LOGOUT, new byte[] {9}).join();
            store.save(steve, HUB, SaveCause.LOGOUT, new byte[] {8}).join();
            store.addGive(alex, give(Items.EMERALD, 5), "Admin").join();
            store.addGive(steve, give(Items.DIAMOND, 2), "Admin").join();
            for (UUID id : List.of(alex, steve)) {
                for (String kind : List.of(GiveService.DECLINED, GraveService.NOTICE, GraveService.RETURN)) {
                    store.putExtra(kind, id, kind.equals(GiveService.DECLINED) ? at(BEFORE) : one()).join();
                }
            }
            store.putExtra("backpack", alex, one()).join(); // keyed by a backpack, not a player: never touched
            BlockPos here = h.absolutePos(new BlockPos(1, 2, 1));
            Grave standingHere = place(h, store, alex, HUB, here);
            Grave onPvp = grave(steve, "Steve", PVP, true, BEFORE, here.east());
            store.addGrave(onPvp).join();
            Grave waiting = grave(steve, "Steve", HUB, false, BEFORE, here); // in this world's data, on its way to the database
            world.addGrave(waiting).join();
            clock.set(JUST_BEFORE);
            store.addGive(later, give(Items.GOLD_INGOT, 1), "Admin").join(); // inside the clock grace: could be just after on a slow clock

            clock.set(RESET);
            Dropped d = now(SeasonReset.forgetAll(server, store, gives, graves, RESET, true));
            h.assertTrue(d.gives() == 2 && d.extras() == 6 && d.graves() == 2 && d.localGraves() == 1 && d.headstones() == 1 && d.complete(),
                    "everyone's went: " + d);
            h.assertTrue(store.gives(List.of(alex, steve)).join().isEmpty() && store.gives(List.of(later)).join().size() == 1,
                    "every waiting stack from before the reset, not the one inside the grace");
            for (String kind : List.of(GiveService.DECLINED, GraveService.NOTICE, GraveService.RETURN)) {
                h.assertTrue(store.extraKeys(kind).join().isEmpty(), kind + ": every player's gone");
            }
            h.assertTrue(store.extra("backpack", alex).join().isPresent(), "an extra of another kind is kept");
            h.assertTrue(store.graves(alex).join().stream().allMatch(Grave::empty) && store.graves(steve).join().stream().allMatch(Grave::empty),
                    "every grave on every server is an empty marker, so no old copy brings it back");
            h.assertTrue(!store.addGrave(standingHere).join(), "an old copy sent again finds its id taken");
            h.assertTrue(world.graves(steve).join().stream().allMatch(Grave::empty), "the one in this world's data is a marker too");
            h.assertTrue(!standing(h, here, standingHere), "the headstone standing here is gone");
            h.assertTrue(store.history(alex).join().size() == 1 && store.history(steve).join().size() == 1, "every snapshot is kept, for /invsync restore");
            h.assertTrue(PlayerReset.Gate.get(server).seasonAt() == RESET, "this server's record of the season reset is in its world data");
            UUID stranger = UUID.randomUUID();
            h.assertTrue(PlayerReset.forgotten(server, stranger, null, BEFORE) && !PlayerReset.forgotten(server, stranger, null, JUST_BEFORE)
                    && !PlayerReset.forgotten(server, stranger, null, AFTER), "the gate holds for every player: well before the reset, not inside the grace or after");

            // the new season plays on, and the same reset heard again (a late notice) keeps all of it
            clock.set(AFTER);
            store.addGive(alex, give(Items.DIAMOND, 1), "Admin").join();
            store.putExtra(GiveService.DECLINED, alex, at(AFTER)).join();
            Grave fresh = grave(alex, "Alex", HUB, false, AFTER, BlockPos.ZERO);
            store.addGrave(fresh).join();
            store.putExtra(GraveService.NOTICE, alex, one()).join();
            store.putExtra(GraveService.RETURN, steve, one()).join();
            Dropped again = now(SeasonReset.forgetAll(server, store, gives, graves, RESET, true));
            h.assertTrue(again.gives() == 0 && again.extras() == 0 && again.graves() == 0 && again.localGraves() == 0, "the same reset again forgets nothing: " + again);
            h.assertTrue(store.gives(List.of(alex)).join().size() == 1 && store.extra(GiveService.DECLINED, alex).join().isPresent()
                    && !store.grave(fresh.id()).join().orElseThrow().empty(), "what came after the reset is kept");
            h.assertTrue(store.extra(GraveService.NOTICE, alex).join().isPresent() && store.extra(GraveService.RETURN, steve).join().isPresent(),
                    "and the notices and hints made since, which carry no time: they go only the first time");
        });
    }

    @GameTest(template = EMPTY)
    public static void another_server_changes_no_shared_rows_but_takes_down_its_headstones_and_forgets_its_own(GameTestHelper h) {
        withGate(h, server -> {
            AtomicLong clock = new AtomicLong(BEFORE);
            MemoryStore store = new MemoryStore(clock::get);
            SyncService pvp = new SyncService(server, store, PVP);
            GiveService gives = new GiveService(server, store, PVP, 5_000);
            WorldGraveStore world = new WorldGraveStore();
            GraveService graves = new GraveService(server, world, () -> pvp, 2);
            Map<UUID, ServerPlayer> online = new HashMap<>();
            graves.setLivePlayerLookup(online::get);
            UUID id = UUID.randomUUID();

            store.addGive(id, give(Items.EMERALD, 5), "Admin").join();
            store.putExtra(GiveService.DECLINED, id, at(BEFORE)).join();
            store.putExtra(GraveService.NOTICE, id, one()).join();
            BlockPos here = h.absolutePos(new BlockPos(1, 2, 1)), there = h.absolutePos(new BlockPos(3, 2, 1));
            Grave standingHere = place(h, store, id, PVP, here);
            Grave queued = grave(id, "Alex", HUB, false, BEFORE, BlockPos.ZERO);
            store.addGrave(queued).join();
            h.assertTrue(now(GraveActions.returnGrave(graves, id, "Alex", queued.shortId(), "Mod")).ok(), "an admin queued a return before the reset");
            Grave local = grave(id, "Alex", PVP, false, BEFORE, BlockPos.ZERO);
            world.addGrave(local).join();
            clock.set(AFTER);
            Grave afterGrave = grave(id, "Alex", PVP, true, AFTER, there); // a death after the reset, here, before this server heard of it
            GraveService.placeBlock(h.getLevel(), there, Direction.NORTH, afterGrave.id(), id, "Alex", AFTER, AFTER, true);
            store.addGrave(afterGrave).join();

            Dropped d = now(SeasonReset.forgetAll(server, store, gives, graves, RESET, false));
            h.assertTrue(d.gives() == 0 && d.extras() == 0 && d.graves() == 0 && d.localGraves() == 1 && d.headstones() == 1 && d.complete(),
                    "only this server's own copies and headstones: " + d);
            h.assertTrue(store.gives(List.of(id)).join().size() == 1 && store.extra(GiveService.DECLINED, id).join().isPresent()
                    && store.extra(GraveService.NOTICE, id).join().isPresent() && store.graves(id).join().stream().noneMatch(Grave::empty),
                    "the database is the origin's to change: untouched here");
            h.assertTrue(!standing(h, here, standingHere) && standing(h, there, afterGrave), "the old headstone here is gone; the new season's stays");
            h.assertTrue(world.graves(id).join().stream().allMatch(Grave::empty), "this world's own copy is an empty marker");

            // a restart: the record is read back from the world data, the players' records with it
            PlayerReset.Gate.get(server).note(id, 5);
            CompoundTag saved = PlayerReset.Gate.get(server).save(new CompoundTag());
            PlayerReset.Gate back = PlayerReset.Gate.load(saved);
            h.assertTrue(back.seasonAt() == RESET && back.resetAt(id) == 5, "the season reset and the players' resets survive a restart");
            PlayerReset.Gate.get(server).remove(id);

            // whatever the database still holds from before the reset is never handed over here
            ServerPlayer p = TestPlayers.mockServerPlayer(h, id);
            online.put(id, p);
            store.addGive(id, give(Items.DIAMOND, 1), "Admin").join(); // given after the reset
            now(gives.arrived(p, true));
            h.assertTrue(p.getInventory().countItem(Items.EMERALD) == 0 && p.getInventory().countItem(Items.DIAMOND) == 1,
                    "the old stack is not handed over, the new one is");
            store.putExtra(GiveService.DECLINED, id, at(BEFORE)).join(); // the join above cleared it; one whose delete failed at the reset
            now(gives.arrived(p, false)); // a switch from another server keeps a Cancel recorded there, unless it is from before the reset
            h.assertTrue(store.extra(GiveService.DECLINED, id).join().isPresent() && !gives.declined(id), "the Cancel from before the reset left in the database counts as none");
            store.putExtra(GiveService.DECLINED, id, at(AFTER)).join();
            now(gives.arrived(p, false));
            h.assertTrue(gives.declined(id), "a Cancel made after it still counts");
            h.assertTrue(now(graves.deliverReturns(List.of(p), true)) == 0 && p.getInventory().countItem(Items.DIAMOND_SWORD) == 0, "nor the grave queued for return");
            String listed = text(now(GraveActions.list(graves, id, AFTER)));
            h.assertTrue(listed.contains("Your graves (1)") && listed.contains(there.getX() + " " + there.getY() + " " + there.getZ())
                    && !listed.contains(here.getX() + " " + here.getY() + " " + here.getZ()) && !listed.contains("could not be placed"),
                    "/grave lists only the new season's grave: " + listed);
            h.assertTrue(now(GraveActions.recover(graves, p, queued.shortId(), AFTER)) != null, "/grave recover refuses an old one");
        });
    }

    @GameTest(template = EMPTY)
    public static void with_the_sync_off_the_server_forgets_its_own_wherever_the_reset_was_typed(GameTestHelper h) {
        withGate(h, server -> {
            AtomicLong clock = new AtomicLong(BEFORE);
            MemoryStore own = new MemoryStore(clock::get);
            GiveService gives = new GiveService(server, own, null, 5_000);
            WorldGraveStore world = new WorldGraveStore();
            GraveService graves = new GraveService(server, world, () -> null, 2);
            graves.setLivePlayerLookup(Map.<UUID, ServerPlayer>of()::get);
            UUID alex = UUID.randomUUID(), steve = UUID.randomUUID();
            own.addGive(alex, give(Items.EMERALD, 5), "Admin").join();
            own.putExtra(GiveService.DECLINED, alex, at(BEFORE)).join();
            own.putExtra(GiveService.DECLINED, steve, at(AFTER)).join(); // a Cancel from after the reset
            BlockPos here = h.absolutePos(new BlockPos(1, 2, 1));
            Grave standingHere = grave(steve, "Steve", "", true, BEFORE, here);
            GraveService.placeBlock(h.getLevel(), here, Direction.NORTH, standingHere.id(), steve, "Steve", BEFORE, BEFORE, true);
            world.addGrave(standingHere).join();
            world.addGrave(grave(alex, "Alex", "", false, BEFORE, BlockPos.ZERO)).join();

            Dropped d = now(SeasonReset.forgetAll(server, null, gives, graves, RESET, false));
            h.assertTrue(d.gives() == 1 && d.extras() == 1 && d.localGraves() == 2 && d.graves() == 0 && d.complete(), "this server's own, though typed elsewhere: " + d);
            h.assertTrue(own.gives(List.of(alex)).join().isEmpty() && own.extra(GiveService.DECLINED, alex).join().isEmpty()
                    && own.extra(GiveService.DECLINED, steve).join().isPresent(), "the old stack and Cancel go, the new Cancel stays");
            h.assertTrue(world.graves(alex).join().stream().allMatch(Grave::empty) && world.graves(steve).join().stream().allMatch(Grave::empty)
                    && !standing(h, here, standingHere), "every grave in this world's data is a marker, its headstone gone");
        });
    }

    @GameTest(template = EMPTY)
    public static void an_admin_restore_keeps_the_season_stamp_before_the_restore_event_is_heard(GameTestHelper h) {
        withGate(h, server -> {
            MemoryStore store = new MemoryStore(System::currentTimeMillis);
            SyncService hub = new SyncService(server, store, HUB);
            UUID id = UUID.randomUUID();
            ServerPlayer p = TestPlayers.mockServerPlayer(h, id);
            p.getInventory().setItem(0, new ItemStack(Items.APPLE, 3));
            CompoundTag old = new CompoundTag();
            old.putInt(SeasonReset.SEASON_RESET, 2); // the season reset they had been through when it was taken
            p.getPersistentData().put(Player.PERSISTED_NBT_TAG, old);
            long preReset = hub.save(p, SaveCause.LOGOUT).join().version();

            p.getInventory().clearContent(); // BSP-Core's season wipe
            CompoundTag persisted = new CompoundTag();
            persisted.putInt(SeasonReset.SEASON_RESET, 3);
            persisted.putLong("bsp_core:ResetAt", RESET);
            persisted.putString("othermod:Key", "kept");
            p.getPersistentData().put(Player.PERSISTED_NBT_TAG, persisted);

            Integer[] seenSeason = {null};
            Long[] seenReset = {null};
            int[] seenApples = {-1};
            Consumer<InventoryRestoredEvent> listener = e -> {
                if (e.getEntity().getUUID().equals(id)) { // as BSP-Core's own listener of the event reads the player
                    seenSeason[0] = SeasonReset.stamp(e.getEntity());
                    seenReset[0] = PlayerReset.stamp(e.getEntity());
                    seenApples[0] = e.getEntity().getInventory().countItem(Items.APPLE);
                }
            };
            MinecraftForge.EVENT_BUS.addListener(net.minecraftforge.eventbus.api.EventPriority.HIGHEST, false, InventoryRestoredEvent.class, listener);
            List<String> rolled;
            try {
                rolled = com.mrgregles.bsp_inventorysync.command.AdminActions.restore(hub, server, p, preReset).join();
            } finally {
                MinecraftForge.EVENT_BUS.unregister(listener);
            }
            h.assertTrue(p.getInventory().countItem(Items.APPLE) == 3, "the items from before the reset are back: " + rolled);
            h.assertTrue(seenApples[0] == 3, "the restore is applied before the event, so a wipe heard on it comes after the restore: " + seenApples[0]);
            h.assertTrue(seenSeason[0] != null && seenSeason[0] == 3 && seenReset[0] != null && seenReset[0] == RESET,
                    "the event already sees both stamps kept, so BSP-Core does not wipe them again: " + seenSeason[0] + ", " + seenReset[0]);
            Integer kept = SeasonReset.stamp(p);
            h.assertTrue(kept != null && kept == 3 && PlayerReset.stamp(p) == RESET, "and they stay after it: " + kept);

            // a restored snapshot with a later season reset than the player carries keeps its own
            p.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG).putInt(SeasonReset.SEASON_RESET, 5);
            long later = hub.save(p, SaveCause.ADMIN).join().version();
            p.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG).putInt(SeasonReset.SEASON_RESET, 4);
            com.mrgregles.bsp_inventorysync.command.AdminActions.restore(hub, server, p, later).join();
            h.assertTrue(SeasonReset.stamp(p) == 5, "the higher one wins: " + SeasonReset.stamp(p));
            h.assertTrue(SeasonReset.stamp(TestPlayers.mockServerPlayer(h)) == null, "a player who never went through one has no stamp");
        });
    }

    @GameTest(template = EMPTY)
    public static void the_season_reset_is_heard_through_an_event_class_found_by_name_and_only_when_it_says_so(GameTestHelper h) {
        withGate(h, server -> {
            h.assertTrue(SeasonReset.register(FakeSeasonEvent.class.getName()) && SeasonReset.registered(FakeSeasonEvent.class.getName()), "registered by name");
            h.assertTrue(SeasonReset.register(FakeSeasonEvent.Older.class.getName()), "an older event registered by name");
            MinecraftForge.EVENT_BUS.post(new FakeSeasonEvent.Older(server));
            h.assertTrue(PlayerReset.Gate.get(server).seasonAt() == 0, "an event without isSeasonReset() is not a season reset");
            MinecraftForge.EVENT_BUS.post(new FakeSeasonEvent(server, false, true, RESET));
            h.assertTrue(PlayerReset.Gate.get(server).seasonAt() == 0, "nor one that answers false (a season's end or a full reset)");
            // heard from another server, so nothing shared is written by whatever services this test server runs
            MinecraftForge.EVENT_BUS.post(new FakeSeasonEvent(server, true, false, RESET));
            h.assertTrue(PlayerReset.Gate.get(server).seasonAt() == RESET, "a season reset: the getters were read and the event's own time recorded");
            try {
                Object older = new FakeSeasonEvent.Older(server);
                h.assertTrue(!SeasonReset.flag(older, "isSeasonReset") && !SeasonReset.flag(older, "isOrigin") && SeasonReset.number(older, "getResetMs") == 0,
                        "a missing getter counts as false, or 0");
                // BSP-Core's real event: not a season reset, whichever version this repo builds against
                Object real = new com.mrgregles.bsp_core.api.event.SeasonEvent.Ended(server, 3, true, "Test");
                h.assertTrue(!SeasonReset.flag(real, "isSeasonReset") && SeasonReset.number(real, "getResetMs") == 0, "BSP-Core's own event of a full reset");
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
            long start = SeasonReset.seasonStart(server);
            h.assertTrue(start == com.mrgregles.bsp_core.admin.SeasonData.get(server).startMs, "BSP-Core's season start is read: " + start);
            h.assertTrue(SeasonReset.resetTime(server, 1_234, false) == 1_234, "the event's own time comes first");
            h.assertTrue(SeasonReset.resetTime(server, 0, false) == start, "then BSP-Core's season start");
            h.assertTrue(SeasonReset.resetTime(server, 0, true) > 0, "the origin always has a time");
            String missing = "com.mrgregles.bsp_core.api.event.NoSuchSeasonEvent";
            h.assertTrue(!SeasonReset.register(missing) && !SeasonReset.registered(missing), "class not found: nothing registered");
            h.assertTrue(!SeasonReset.register("java.lang.String") && !SeasonReset.registered("java.lang.String"), "not an event: nothing registered");
        });
    }

}
