package com.mrgregles.bsp_inventorysync.gametest;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.VersionCheck;
import com.mrgregles.bsp_inventorysync.grave.GraveActions;
import com.mrgregles.bsp_inventorysync.grave.GraveContents;
import com.mrgregles.bsp_inventorysync.grave.GraveMenu;
import com.mrgregles.bsp_inventorysync.grave.GraveRegistry;
import com.mrgregles.bsp_inventorysync.grave.GraveService;
import com.mrgregles.bsp_inventorysync.grave.GraveSpot;
import com.mrgregles.bsp_inventorysync.grave.WorldGraveStore;
import com.mrgregles.bsp_inventorysync.storage.Grave;
import com.mrgregles.bsp_inventorysync.storage.MemoryStore;
import com.mrgregles.bsp_inventorysync.sync.SyncService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.network.NetworkRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The fixes from the graves audit of 2026-11-07: a write the store answers with an error after it landed still hands
 * over; an emptied grave stays as a marker so an old copy never brings it back; a new grave reaches the database only
 * with a snapshot, and is dropped (not duplicated) when the logout save fails; cancelled drops give the orbs back; the
 * screen's picture slots refuse every take and place; the End's rebuilt places are never a grave's spot; creative mode
 * never breaks one; the version check lets a client with the mod join servers without it; a networked grave's block
 * survives a server that cannot reach the database; a player whose data is arriving opens nothing; an unreadable grave
 * is refused untouched; a grave whose block a crash lost is recovered at once.
 */
@GameTestHolder(BSPInventorySync.MODID)
@PrefixGameTestTemplate(false)
public class GraveAuditTests {
    private static final String EMPTY = "empty";
    private static final String HUB = "hub";

    private static Grave die(GameTestHelper h, GraveService graves, ServerPlayer p, int x, int z, ItemStack... stacks) {
        h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
        BlockPos abs = h.absolutePos(new BlockPos(x, 1, z));
        p.moveTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5);
        graves.recordLayout(p, h.getLevel().damageSources().fall());
        List<ItemEntity> drops = new ArrayList<>();
        for (ItemStack s : stacks) {
            drops.add(new ItemEntity(h.getLevel(), p.getX(), p.getY(), p.getZ(), s.copy()));
        }
        Grave g = graves.onDrops(p, h.getLevel().damageSources().fall(), drops, System.currentTimeMillis());
        h.assertTrue(g != null, "a grave");
        return g;
    }

    private static GraveService local(GameTestHelper h) {
        return new GraveService(h.getLevel().getServer(), new WorldGraveStore(), () -> null, 2);
    }

    @GameTest(template = EMPTY, timeoutTicks = 300)
    public static void a_take_the_store_answers_with_an_error_after_it_landed_is_still_handed_over(GameTestHelper h) {
        MemoryStore memory = new MemoryStore();
        AtomicBoolean lateAnswer = new AtomicBoolean(false);
        SyncService sync = new SyncService(h.getLevel().getServer(), GiveTests.hooked(memory, (m, args, real) -> {
            if (m.equals("updateGrave") && lateAnswer.getAndSet(false)) {
                ((CompletableFuture<?>) real.get()).join(); // the write lands...
                return CompletableFuture.failedFuture(new java.sql.SQLException("test: the socket timed out")); // ...but the answer is lost
            }
            return null;
        }), HUB);
        GraveService graves = new GraveService(h.getLevel().getServer(), new WorldGraveStore(), () -> sync, 2);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        Grave g = die(h, graves, p, 3, 3, new ItemStack(Items.DIAMOND, 7), new ItemStack(Items.EMERALD, 2));
        GraveMenu[] menu = new GraveMenu[1];
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(memory.graveCount() == 1, "in the database"))
                .thenExecute(() -> {
                    menu[0] = graves.access().open(p, g.id(), false, null, null).join();
                    h.assertTrue(menu[0] != null, "opened");
                    int slot = -1;
                    for (int i = 0; i < GraveMenu.ITEMS; i++) {
                        int e = menu[0].entryAt(i);
                        if (e >= 0 && menu[0].contents().entries().get(e).stack().is(Items.DIAMOND)) {
                            slot = i;
                        }
                    }
                    lateAnswer.set(true);
                    menu[0].clicked(slot, 0, ClickType.PICKUP, p);
                })
                .thenWaitUntil(() -> h.assertTrue(p.getInventory().countItem(Items.DIAMOND) == 7, "read back, found written, handed over"))
                .thenExecute(() -> {
                    int inGrave = GraveContents.decode(memory.grave(g.id()).join().orElseThrow().data()).entries().stream()
                            .filter(e -> e.stack().is(Items.DIAMOND)).mapToInt(e -> e.stack().getCount()).sum();
                    h.assertTrue(inGrave == 0, "and not left in the grave as well");
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY)
    public static void an_emptied_grave_stays_as_a_marker_and_an_old_copy_never_brings_it_back(GameTestHelper h) {
        GraveService graves = local(h);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        Grave g = die(h, graves, p, 3, 3, new ItemStack(Items.GOLD_INGOT, 4));
        GraveMenu m = graves.access().open(p, g.id(), false, null, null).join();
        m.clicked(GraveMenu.TAKE_ALL, 0, ClickType.PICKUP, p);
        h.assertTrue(p.getInventory().countItem(Items.GOLD_INGOT) == 4, "taken");
        h.assertTrue(!graves.local().addGrave(g).join(), "the old copy, sent again after a crash, finds its id taken");
        h.assertTrue(graves.local().grave(g.id()).join().orElseThrow().empty(), "and the grave stays empty");
        h.assertTrue(graves.access().open(p, g.id(), false, null, null).join() == null, "an empty grave does not open");
        h.succeed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void a_new_grave_lands_only_with_a_snapshot_and_is_dropped_when_the_logout_save_fails(GameTestHelper h) {
        MemoryStore memory = new MemoryStore();
        SyncService sync = new SyncService(h.getLevel().getServer(), GiveTests.hooked(memory, (m, args, real) ->
                m.equals("saveWithGraves") ? CompletableFuture.failedFuture(new java.sql.SQLException("test: the database is down")) : null), HUB);
        GraveService graves = new GraveService(h.getLevel().getServer(), new WorldGraveStore(), () -> sync, 2);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        Grave g = die(h, graves, p, 3, 3, new ItemStack(Items.DIAMOND, 5));
        BlockPos at = new BlockPos(g.x(), g.y(), g.z());
        h.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    h.assertTrue(memory.graveCount() == 0 && graves.unsent() == 1, "not in the database without its snapshot");
                    sync.onLogout(p); // its save fails too: the database still has them as before the death
                })
                .thenWaitUntil(() -> h.assertTrue(graves.unsent() == 0, "the grave is dropped, not kept beside the items they get back"))
                .thenExecute(() -> {
                    h.assertTrue(memory.graveCount() == 0, "never written");
                    h.assertTrue(h.getLevel().getBlockState(at).isAir(), "its block went too");
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY)
    public static void drops_another_mod_cancelled_give_the_held_orbs_back(GameTestHelper h) {
        GraveService graves = local(h);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        h.setBlock(new BlockPos(3, 0, 3), Blocks.STONE);
        BlockPos abs = h.absolutePos(new BlockPos(3, 1, 3));
        p.moveTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5);
        graves.recordLayout(p, h.getLevel().damageSources().fall());
        h.assertTrue(graves.holdXp(p, 30), "held");
        graves.dropsCancelled(p);
        List<ExperienceOrb> orbs = h.getLevel().getEntitiesOfClass(ExperienceOrb.class, new AABB(abs).inflate(1.5));
        int points = orbs.stream().mapToInt(GraveTests::points).sum(); // vanilla merges equal orbs into one that counts twice
        orbs.forEach(o -> o.discard());
        h.assertTrue(points == 30 && graves.local().size() == 0, "the 30 points dropped, no grave: " + points);
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void the_screens_picture_slots_refuse_every_take_and_place(GameTestHelper h) {
        GraveService graves = local(h);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        Grave g = die(h, graves, p, 3, 3, new ItemStack(Items.COAL, 3));
        GraveMenu m = graves.access().open(p, g.id(), false, null, null).join();
        for (int i = 0; i < GraveMenu.SLOTS; i++) {
            h.assertTrue(!m.getSlot(i).mayPickup(p) && !m.getSlot(i).mayPlace(new ItemStack(Items.DIRT)) && m.getSlot(i).index == i, "slot " + i + " is a picture");
        }
        h.assertTrue(m.getSlot(GraveMenu.SLOTS).mayPickup(p), "the player's own inventory is not");
        p.closeContainer();
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void graves_never_stand_where_the_end_is_rebuilt(GameTestHelper h) {
        ServerLevel end = h.getLevel().getServer().getLevel(Level.END);
        h.assertTrue(end != null, "the End");
        h.assertTrue(GraveSpot.endReserved(end, new BlockPos(100, 50, 0)) && GraveSpot.endReserved(end, new BlockPos(102, 49, -2)), "the arrival platform");
        h.assertTrue(GraveSpot.endReserved(end, new BlockPos(0, 64, 0)) && GraveSpot.endReserved(end, new BlockPos(3, 90, -3)), "the podium, at any height");
        h.assertTrue(GraveSpot.endReserved(end, new BlockPos(-42, 90, -1)), "above a pillar");
        h.assertTrue(!GraveSpot.endReserved(end, new BlockPos(60, 60, 60)) && !GraveSpot.endReserved(end, new BlockPos(-42, 40, -1)), "elsewhere is fine");
        h.assertTrue(!GraveSpot.endReserved(h.getLevel(), new BlockPos(100, 50, 0)), "and only in the End");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_creative_player_never_breaks_a_grave_on_either_side(GameTestHelper h) {
        BlockPos abs = h.absolutePos(new BlockPos(2, 1, 2));
        h.setBlock(new BlockPos(2, 0, 2), Blocks.STONE);
        GraveService.placeBlock(h.getLevel(), abs, Direction.NORTH, UUID.randomUUID(), UUID.randomUUID(), "Steve", 0, 0);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        p.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        boolean removed = h.getLevel().getBlockState(abs).onDestroyedByPlayer(h.getLevel(), abs, p, false, h.getLevel().getFluidState(abs));
        h.assertTrue(!removed && h.getLevel().getBlockState(abs).is(GraveRegistry.GRAVE.get()), "still standing");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_client_with_the_mod_may_join_servers_without_it_and_servers_want_the_same_version(GameTestHelper h) {
        String absent = NetworkRegistry.ABSENT.version();
        h.assertTrue(VersionCheck.clientAccepts(VersionCheck.version()) && VersionCheck.clientAccepts(absent) && VersionCheck.clientAccepts(NetworkRegistry.ACCEPTVANILLA),
                "a client joins a server with the same version, one without the mod, and a vanilla one");
        h.assertTrue(!VersionCheck.clientAccepts("0.0.1"), "but not one with another version");
        h.assertTrue(VersionCheck.serverAccepts(VersionCheck.version()) && !VersionCheck.serverAccepts(absent) && !VersionCheck.serverAccepts("0.0.1"),
                "a server takes only the same version");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_networked_graves_block_survives_a_server_that_cannot_reach_the_database(GameTestHelper h) {
        GraveService graves = local(h); // no network here now
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        BlockPos abs = h.absolutePos(new BlockPos(2, 1, 2));
        h.setBlock(new BlockPos(2, 0, 2), Blocks.STONE);
        GraveService.placeBlock(h.getLevel(), abs, Direction.NORTH, UUID.randomUUID(), p.getUUID(), "Steve", 0, 0, true);
        graves.access().openAt(p, h.getLevel(), abs);
        h.assertTrue(h.getLevel().getBlockState(abs).is(GraveRegistry.GRAVE.get()), "not taken for an empty grave");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_player_whose_data_is_arriving_opens_nothing_and_takes_no_xp(GameTestHelper h) {
        GraveService graves = local(h);
        SyncService sync = new SyncService(h.getLevel().getServer(), new MemoryStore(), HUB);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        Grave g = new Grave(UUID.randomUUID(), p.getUUID(), "Steve", "", "minecraft:overworld", 0, 0, 0, false, 1, 1, 0, 40, 1, 0,
                new GraveContents(List.of(new GraveContents.Entry("", new ItemStack(Items.COAL))), new CompoundTag()).encode());
        graves.local().addGrave(g).join();
        sync.onLogin(p, System.currentTimeMillis());
        try {
            h.assertTrue(graves.access().open(p, g.id(), false, null, null).join() == null, "refused while arriving");
            h.assertTrue(graves.local().grave(g.id()).join().orElseThrow().xp() == 40, "the XP stays in the grave");
        } finally {
            sync.onLogout(p);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void an_unreadable_grave_is_refused_untouched(GameTestHelper h) {
        GraveService graves = local(h);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        Grave g = new Grave(UUID.randomUUID(), p.getUUID(), "Steve", "", "minecraft:overworld", 0, 0, 0, false, 1, 1, 0, 25, 1, 0, new byte[] {1, 2, 3});
        graves.local().addGrave(g).join();
        CompletableFuture<GraveMenu> f = graves.access().open(p, g.id(), false, null, null);
        h.assertTrue(f.isDone() && f.getNow(null) == null, "refused at once");
        Grave after = graves.local().grave(g.id()).join().orElseThrow();
        h.assertTrue(after.revision() == 0 && after.xp() == 25, "nothing written, the XP kept");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_grave_whose_block_a_crash_lost_is_recovered_at_once(GameTestHelper h) {
        GraveService graves = local(h);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        Grave g = die(h, graves, p, 3, 3, new ItemStack(Items.DIAMOND, 2));
        String early = GraveActions.recover(graves, p, g.shortId(), System.currentTimeMillis()).join();
        h.assertTrue(early != null && early.startsWith("Go to it"), "with its block standing: go to it");
        h.getLevel().setBlock(new BlockPos(g.x(), g.y(), g.z()), Blocks.AIR.defaultBlockState(), 3); // the world rolled back without it
        h.assertTrue(GraveActions.recover(graves, p, g.shortId(), System.currentTimeMillis()).join() == null && p.containerMenu instanceof GraveMenu,
                "without it: recovered at once");
        p.closeContainer();
        h.succeed();
    }
}
