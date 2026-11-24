package com.mrgregles.bsp_inventorysync.gametest;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.compat.RegionGuard;
import com.mrgregles.bsp_inventorysync.grave.GraveBlock;
import com.mrgregles.bsp_inventorysync.grave.GraveBlockEntity;
import com.mrgregles.bsp_inventorysync.grave.GraveBorder;
import com.mrgregles.bsp_inventorysync.grave.GraveContents;
import com.mrgregles.bsp_inventorysync.grave.GraveService;
import com.mrgregles.bsp_inventorysync.grave.GraveSpot;
import com.mrgregles.bsp_inventorysync.grave.WorldGraveStore;
import com.mrgregles.bsp_inventorysync.storage.Grave;
import com.mrgregles.bsp_inventorysync.storage.GraveStore;
import com.mrgregles.bsp_inventorysync.storage.MemoryStore;
import com.mrgregles.bsp_inventorysync.sync.SyncService;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Fix GB-1 (2026-11-18): a grave keeps at least {@code graves.borderMargin} (5) whole blocks inside the world border,
 * Minecraft's own and Region Guard's, the tighter of the two; a death outside or near the edge puts it at the nearest
 * spot that far inside; graves already standing too close are moved once at the server's start. Each test that sets a
 * border runs in a batch of its own (batches run one after another), since the border is the whole world's, and puts
 * the border back before it ends. Edges are placed relative to the test's own box.
 */
@GameTestHolder(BSPInventorySync.MODID)
@PrefixGameTestTemplate(false)
public class GraveBorderTests {
    private static final String EMPTY = "empty";
    private static final long HOUR = 3_600_000L;

    private static GraveService service(GameTestHelper h) {
        return new GraveService(h.getLevel().getServer(), new WorldGraveStore(), () -> null, 2);
    }

    /** A stone floor at relative y 0 along x 0..7 at relative z {@code z}. */
    private static void floorAlongX(GameTestHelper h, int z) {
        for (int x = 0; x <= 7; x++) {
            h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
        }
    }

    /** A stone floor at relative y 0 along z 0..7 at relative x {@code x}. */
    private static void floorAlongZ(GameTestHelper h, int x) {
        for (int z = 0; z <= 7; z++) {
            h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
        }
    }

    private static Grave die(GameTestHelper h, ServerPlayer p, BlockPos abs) {
        p.moveTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5);
        GraveService graves = service(h);
        graves.recordLayout(p, h.getLevel().damageSources().fall());
        List<ItemEntity> drops = new ArrayList<>(List.of(new ItemEntity(h.getLevel(), p.getX(), p.getY(), p.getZ(), new ItemStack(Items.DIAMOND, 2))));
        return graves.onDrops(p, h.getLevel().damageSources().fall(), drops, 1_000);
    }

    /** Minecraft's border with its east edge at absolute x {@code maxX}, reaching far to the west, north and south. */
    private static double[] vanillaEastEdge(GameTestHelper h, double maxX) {
        WorldBorder wb = h.getLevel().getWorldBorder();
        double[] before = {wb.getCenterX(), wb.getCenterZ(), wb.getSize()};
        wb.setCenter(maxX - 500_000, h.absolutePos(BlockPos.ZERO).getZ());
        wb.setSize(1_000_000);
        return before;
    }

    private static void restore(GameTestHelper h, double[] before) {
        WorldBorder wb = h.getLevel().getWorldBorder();
        wb.setCenter(before[0], before[1]);
        wb.setSize(before[2]);
    }

    private static BlockPos at(Grave g) {
        return new BlockPos(g.x(), g.y(), g.z());
    }

    // ------------------------------------------------------------------ a death at the border

    @GameTest(template = EMPTY, batch = "gb1_vanilla")
    public static void a_death_outside_or_near_minecrafts_border_puts_the_grave_five_blocks_inside(GameTestHelper h) {
        floorAlongX(h, 3);
        int edge = h.absolutePos(new BlockPos(8, 0, 0)).getX(); // the border's east edge on the box's east side
        double[] before = vanillaEastEdge(h, edge);
        try {
            BlockPos near = h.absolutePos(new BlockPos(5, 1, 3)); // 2 whole blocks from the edge
            Grave g = die(h, TestPlayers.mockServerPlayer(h), near);
            h.assertTrue(g != null && g.placed(), "a grave stands: " + g);
            h.assertTrue(at(g).equals(h.absolutePos(new BlockPos(2, 1, 3))), "a death 2 blocks from the edge: the grave at the nearest spot with 5 whole blocks to the edge: "
                    + at(g) + ", edge x " + edge);

            BlockPos outside = h.absolutePos(new BlockPos(12, 1, 3)); // 4 blocks past the edge
            Grave o = die(h, TestPlayers.mockServerPlayer(h), outside);
            h.assertTrue(o != null && o.placed(), "a death outside the border still gets a standing grave: " + o);
            h.assertTrue(o.x() + 1 <= edge - 5 && o.z() == outside.getZ(), "inside, with 5 whole blocks to the edge: " + at(o) + ", edge x " + edge);

            // the spot rule itself: a dry, free spot on stone is no grave's spot when it is within 5 blocks of the edge
            h.assertTrue(!GraveSpot.good(h.getLevel(), h.absolutePos(new BlockPos(3, 1, 3)), Set.of()), "4 whole blocks from the edge is not a spot");
            h.assertTrue(GraveSpot.good(h.getLevel(), h.absolutePos(new BlockPos(0, 1, 3)), Set.of()), "7 is");
        } finally {
            restore(h, before);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = "gb1_surface")
    public static void a_death_outside_underground_with_no_room_there_puts_the_grave_on_top_of_the_column_inside(GameTestHelper h) {
        // The test box lies underground in the test world (its terrain reaches y 63 above it). The column the grave is moved
        // to gets a stone on its real top, so there is somewhere to stand up there; everything set outside the box is put back.
        var level = h.getLevel();
        int edge = h.absolutePos(new BlockPos(8, 0, 0)).getX();
        BlockPos column = h.absolutePos(new BlockPos(2, 0, 3));
        int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ());
        BlockPos stone = new BlockPos(column.getX(), top, column.getZ());
        var wasThere = level.getBlockState(stone);
        double[] before = vanillaEastEdge(h, edge);
        Grave g = null;
        try {
            level.setBlock(stone, Blocks.STONE.defaultBlockState(), 3);
            // in the solid rock under the box, past the edge: nothing free within the search radius (2) down there
            BlockPos death = h.absolutePos(new BlockPos(12, -3, 3));
            g = die(h, TestPlayers.mockServerPlayer(h), death);
            h.assertTrue(g != null && g.placed(), "a standing grave: " + (g == null ? null : at(g)));
            h.assertTrue(g.y() > top - 3 && g.x() + 1 <= edge - 5, "it stands up at the top of the column inside, not in the rock: " + at(g) + ", top " + top + ", edge x " + edge);
        } finally {
            if (g != null && g.placed() && g.y() > h.absolutePos(new BlockPos(0, 4, 0)).getY()) {
                level.setBlock(at(g), Blocks.AIR.defaultBlockState(), 3); // outside the box: removed by hand
            }
            level.setBlock(stone, wasThere, 3);
            restore(h, before);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = "gb1_region")
    public static void region_guards_border_counts_too_on_any_side(GameTestHelper h) {
        floorAlongZ(h, 3);
        int edge = h.absolutePos(BlockPos.ZERO).getZ(); // Region Guard's north edge on the box's north side
        RegionGuard.borderForTests(Level.OVERWORLD, new RegionGuard.Square(-3.0E7, 3.0E7, edge, 3.0E7));
        try {
            Grave g = die(h, TestPlayers.mockServerPlayer(h), h.absolutePos(new BlockPos(3, 1, 1)));
            h.assertTrue(g != null && g.placed() && at(g).equals(h.absolutePos(new BlockPos(3, 1, 5))),
                    "a death 1 block inside Region Guard's north edge: the grave 5 whole blocks in: " + (g == null ? null : at(g)) + ", edge z " + edge);
        } finally {
            RegionGuard.borderForTests(Level.OVERWORLD, null);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = "gb1_both")
    public static void with_both_borders_the_tighter_one_wins(GameTestHelper h) {
        floorAlongX(h, 3);
        int near = h.absolutePos(new BlockPos(8, 0, 0)).getX(), far = h.absolutePos(new BlockPos(30, 0, 0)).getX();
        double[] before = vanillaEastEdge(h, far); // Minecraft's edge far off
        RegionGuard.borderForTests(Level.OVERWORLD, new RegionGuard.Square(-3.0E7, near, -3.0E7, 3.0E7)); // Region Guard's close by
        try {
            Grave g = die(h, TestPlayers.mockServerPlayer(h), h.absolutePos(new BlockPos(6, 1, 3)));
            h.assertTrue(g != null && g.placed() && at(g).equals(h.absolutePos(new BlockPos(2, 1, 3))),
                    "Region Guard's nearer edge decides: " + (g == null ? null : at(g)));
        } finally {
            RegionGuard.borderForTests(Level.OVERWORLD, null);
            restore(h, before);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = "gb1_margin")
    public static void the_margin_follows_the_config(GameTestHelper h) {
        floorAlongX(h, 3);
        int edge = h.absolutePos(new BlockPos(8, 0, 0)).getX();
        double[] before = vanillaEastEdge(h, edge);
        int was = GraveBorder.margin();
        GraveBorder.setMargin(2);
        try {
            Grave g = die(h, TestPlayers.mockServerPlayer(h), h.absolutePos(new BlockPos(7, 1, 3)));
            h.assertTrue(g != null && g.placed() && at(g).equals(h.absolutePos(new BlockPos(5, 1, 3))), "margin 2: 2 whole blocks to the edge: " + (g == null ? null : at(g)));
        } finally {
            GraveBorder.setMargin(was);
            restore(h, before);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void the_border_arithmetic_counts_whole_blocks(GameTestHelper h) {
        int was = GraveBorder.margin();
        GraveBorder.setMargin(5);
        try {
            RegionGuard.Square b = new RegionGuard.Square(0, 100, -50.5, 50.5);
            h.assertTrue(GraveBorder.inside(b, new BlockPos(5, 0, 0)) && !GraveBorder.inside(b, new BlockPos(4, 0, 0)), "west: block 5 has blocks 0 to 4 between it and the edge");
            h.assertTrue(GraveBorder.inside(b, new BlockPos(94, 0, 0)) && !GraveBorder.inside(b, new BlockPos(95, 0, 0)), "east: block 94 has blocks 95 to 99");
            h.assertTrue(GraveBorder.inside(b, new BlockPos(50, 0, -45)) && !GraveBorder.inside(b, new BlockPos(50, 0, -46)), "a half-block edge rounds inwards");
            h.assertTrue(GraveBorder.clampInside(b, new BlockPos(500, 70, -500)).equals(new BlockPos(94, 70, -45)), "far outside: the nearest column inside, same height");
            BlockPos in = new BlockPos(20, 70, 0);
            h.assertTrue(GraveBorder.clampInside(b, in) == in, "already inside: unchanged");
            RegionGuard.Square narrow = new RegionGuard.Square(0, 4, 0, 4);
            h.assertTrue(GraveBorder.inside(narrow, new BlockPos(1, 0, 2)) && GraveBorder.inside(narrow, new BlockPos(2, 0, 1)),
                    "a border too narrow for the margin still has its middle blocks");
        } finally {
            GraveBorder.setMargin(was);
        }
        h.succeed();
    }

    // ------------------------------------------------------------------ graves already standing (7 b, and the AUD-9 audit)

    /** A standing grave of {@code owner} in {@code level}: its headstone (facing east, public two hours after the death, networked, with a head) and its record in {@code store}. */
    private static Grave standing(GameTestHelper h, ServerLevel level, GraveStore store, String serverId, BlockPos abs, UUID owner) {
        UUID id = UUID.randomUUID();
        // none of facing east, two hours, networked or the head is what a new headstone would get by default
        h.assertTrue(GraveService.placeBlock(level, abs, Direction.EAST, id, owner, "Steve", 1_000, 1_000 + 2 * HOUR, true, new GameProfile(owner, "Steve")),
                "the old headstone stands");
        Grave g = new Grave(id, owner, "Steve", serverId, level.dimension().location().toString(), abs.getX(), abs.getY(), abs.getZ(), true, 1_000, 1_000, 0, 0, 1, 0,
                new GraveContents(List.of(new GraveContents.Entry("inv:0", new ItemStack(Items.DIAMOND, 3))), new CompoundTag()).encode());
        store.addGrave(g).join();
        return g;
    }

    private static Grave standing(GameTestHelper h, GraveService graves, BlockPos abs, UUID owner) {
        return standing(h, h.getLevel(), graves.local(), "", abs, owner);
    }

    /** Lets held store answers through (each may lead to another) until {@code f} is done; all on this thread. */
    private static <T> T drive(GameTestHelper h, List<Runnable> held, CompletableFuture<T> f) {
        for (int i = 0; i < 100 && !f.isDone(); i++) {
            List<Runnable> now = new ArrayList<>(held);
            held.removeAll(now);
            now.forEach(Runnable::run);
        }
        h.assertTrue(f.isDone(), "the moves finished");
        return f.join();
    }

    @GameTest(template = EMPTY, batch = "gb1_move")
    public static void graves_already_too_close_to_the_border_are_moved_inside_at_the_start(GameTestHelper h) {
        floorAlongX(h, 3);
        int edge = h.absolutePos(new BlockPos(8, 0, 0)).getX();
        double[] before = vanillaEastEdge(h, edge);
        try {
            GraveService graves = service(h);
            UUID owner = UUID.randomUUID();
            BlockPos oldAt = h.absolutePos(new BlockPos(6, 1, 3)), fineAt = h.absolutePos(new BlockPos(0, 1, 3));
            Grave close = standing(h, graves, oldAt, owner), fine = standing(h, graves, fineAt, owner);

            int moved = graves.moveFromBorders().join();
            h.assertTrue(moved == 1, "one grave moved: " + moved);
            Grave after = graves.local().grave(close.id()).join().orElseThrow();
            BlockPos newAt = h.absolutePos(new BlockPos(2, 1, 3));
            h.assertTrue(after.placed() && at(after).equals(newAt) && after.revision() == close.revision() + 1,
                    "the close one now stands 5 whole blocks inside, written at its revision: " + after);
            h.assertTrue(!(h.getLevel().getBlockEntity(oldAt) instanceof GraveBlockEntity) && h.getLevel().getBlockState(oldAt).isAir(), "its old headstone is gone");
            h.assertTrue(h.getLevel().getBlockEntity(newAt) instanceof GraveBlockEntity be && close.id().equals(be.graveId()) && be.publicAt() == 1_000 + 2 * HOUR,
                    "a headstone for it at the new spot, keeping when it becomes public");
            h.assertTrue(h.getLevel().getBlockState(newAt).getValue(GraveBlock.FACING) == Direction.EAST, "and the way it faced");
            h.assertTrue(h.getLevel().getBlockEntity(newAt) instanceof GraveBlockEntity be2 && be2.networked() && be2.profile() != null && "Steve".equals(be2.profile().getName()),
                    "and its network flag and head");
            Grave untouched = graves.local().grave(fine.id()).join().orElseThrow();
            h.assertTrue(untouched.revision() == fine.revision() && at(untouched).equals(fineAt)
                    && h.getLevel().getBlockEntity(fineAt) instanceof GraveBlockEntity fb && fine.id().equals(fb.graveId()), "the one far enough in is left alone");
            h.assertTrue(graves.moveFromBorders().join() == 0, "a second run moves nothing");
        } finally {
            restore(h, before);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = "gb1_late")
    public static void two_graves_moved_toward_the_same_spot_never_share_it(GameTestHelper h) {
        floorAlongX(h, 3);
        int edge = h.absolutePos(new BlockPos(8, 0, 0)).getX();
        double[] before = vanillaEastEdge(h, edge);
        List<Runnable> held = new CopyOnWriteArrayList<>();
        try {
            GraveService graves = service(h);
            UUID owner = UUID.randomUUID();
            Grave a = standing(h, graves, h.absolutePos(new BlockPos(6, 1, 3)), owner), b = standing(h, graves, h.absolutePos(new BlockPos(7, 1, 3)), owner);
            GraveService.holdMoveAnswersForTests(held); // the store answers late, as a database does
            int moved = drive(h, held, graves.moveFromBorders());
            Grave a2 = graves.local().grave(a.id()).join().orElseThrow(), b2 = graves.local().grave(b.id()).join().orElseThrow();
            h.assertTrue(moved == 2 && a2.placed() && b2.placed(), "both moved and standing: " + a2 + " / " + b2);
            h.assertTrue(!at(a2).equals(at(b2)), "never at the same spot: " + at(a2) + " and " + at(b2));
            for (Grave g : List.of(a2, b2)) {
                h.assertTrue(h.getLevel().getBlockEntity(at(g)) instanceof GraveBlockEntity be && g.id().equals(be.graveId()), "each headstone holds its own grave: " + at(g));
            }
        } finally {
            GraveService.holdMoveAnswersForTests(null);
            restore(h, before);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = "gb1_late_death")
    public static void a_death_taking_the_spot_while_a_move_waits_is_never_overwritten(GameTestHelper h) {
        floorAlongX(h, 3);
        int edge = h.absolutePos(new BlockPos(8, 0, 0)).getX();
        double[] before = vanillaEastEdge(h, edge);
        List<Runnable> held = new CopyOnWriteArrayList<>();
        try {
            GraveService graves = service(h);
            BlockPos oldAt = h.absolutePos(new BlockPos(6, 1, 3)), spot = h.absolutePos(new BlockPos(2, 1, 3));
            Grave a = standing(h, graves, oldAt, UUID.randomUUID());
            GraveService.holdMoveAnswersForTests(held);
            CompletableFuture<Integer> moving = graves.moveFromBorders(); // its spot chosen, the store's answer not in yet
            Grave d = die(h, TestPlayers.mockServerPlayer(h), spot); // meanwhile a death right there
            h.assertTrue(d != null && d.placed() && at(d).equals(spot), "the death's grave stands at the spot: " + (d == null ? null : at(d)));
            drive(h, held, moving);
            h.assertTrue(h.getLevel().getBlockEntity(spot) instanceof GraveBlockEntity be && d.id().equals(be.graveId()), "the death's headstone is untouched");
            Grave a2 = graves.local().grave(a.id()).join().orElseThrow();
            h.assertTrue(!a2.placed(), "the moved grave is kept without a block, recoverable with /grave at once: " + a2);
            h.assertTrue(!(h.getLevel().getBlockEntity(oldAt) instanceof GraveBlockEntity), "its old headstone at the edge is gone");
        } finally {
            GraveService.holdMoveAnswersForTests(null);
            restore(h, before);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = "gb1_net")
    public static void on_a_synced_server_waiting_graves_move_too_and_the_owner_gets_one_notice(GameTestHelper h) {
        floorAlongX(h, 3);
        int edge = h.absolutePos(new BlockPos(8, 0, 0)).getX();
        double[] before = vanillaEastEdge(h, edge);
        try {
            MemoryStore memory = new MemoryStore();
            SyncService sync = new SyncService(h.getLevel().getServer(), memory, "hub");
            GraveService graves = new GraveService(h.getLevel().getServer(), new WorldGraveStore(), () -> sync, 2);
            graves.setLivePlayerLookup(id -> null); // the owner is offline
            UUID owner = UUID.randomUUID();
            Grave a = standing(h, h.getLevel(), memory, "hub", h.absolutePos(new BlockPos(6, 1, 3)), owner);
            Grave b = standing(h, h.getLevel(), memory, "hub", h.absolutePos(new BlockPos(7, 1, 3)), owner);
            Grave c = standing(h, h.getLevel(), graves.local(), "hub", h.absolutePos(new BlockPos(5, 1, 3)), owner); // still waiting in the world's data for the database
            int moved = graves.moveFromBorders().join();
            h.assertTrue(moved == 3, "all three moved: " + moved);
            Grave c2 = graves.local().grave(c.id()).join().orElseThrow();
            h.assertTrue(c2.placed() && c2.x() + 1 <= edge - 5, "the waiting one too, in the world's data: " + c2);
            for (Grave g : List.of(a, b)) {
                Grave now = memory.grave(g.id()).join().orElseThrow();
                h.assertTrue(now.placed() && now.x() + 1 <= edge - 5, "in the database at its new place: " + now);
            }
            String notice = memory.extra(GraveService.NOTICE, owner).join().map(bytes -> new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).orElse("");
            long lines = notice.lines().filter(l -> !l.isBlank()).count();
            h.assertTrue(lines == 3 && notice.contains(at(c2).getX() + " " + at(c2).getY() + " " + at(c2).getZ()), "one notice naming all three: " + notice);
        } finally {
            restore(h, before);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = "gb1_lerp")
    public static void a_shrinking_border_counts_at_the_size_it_shrinks_to(GameTestHelper h) {
        floorAlongX(h, 3);
        int edge = h.absolutePos(new BlockPos(8, 0, 0)).getX();
        WorldBorder wb = h.getLevel().getWorldBorder();
        double[] before = {wb.getCenterX(), wb.getCenterZ(), wb.getSize()};
        try {
            wb.setCenter(edge - 500_000, h.absolutePos(BlockPos.ZERO).getZ());
            wb.lerpSizeBetween(3_000_000, 1_000_000, 600_000L); // shrinking over ten minutes to an east edge at the box's side
            Grave g = die(h, TestPlayers.mockServerPlayer(h), h.absolutePos(new BlockPos(5, 1, 3)));
            h.assertTrue(g != null && g.placed() && at(g).equals(h.absolutePos(new BlockPos(2, 1, 3))), "kept inside the border it is shrinking to: " + (g == null ? null : at(g)));
        } finally {
            restore(h, before); // setSize ends the shrink
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = "gb1_overlap")
    public static void borders_that_do_not_overlap_go_by_region_guards(GameTestHelper h) {
        int edge = h.absolutePos(new BlockPos(8, 0, 0)).getX();
        double[] before = vanillaEastEdge(h, edge);
        RegionGuard.borderForTests(Level.OVERWORLD, new RegionGuard.Square(edge + 100, edge + 200, -3.0E7, 3.0E7)); // wholly east of Minecraft's
        try {
            RegionGuard.Square b = GraveBorder.of(h.getLevel());
            h.assertTrue(b.minX() == edge + 100 && b.maxX() == edge + 200, "Region Guard's border alone: " + b);
        } finally {
            RegionGuard.borderForTests(Level.OVERWORLD, null);
            restore(h, before);
        }
        h.succeed();
    }

    /** Remembers each block before a test changes it, so it can be put back. */
    private static void remember(ServerLevel level, java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> was, BlockPos pos) {
        was.putIfAbsent(pos.immutable(), level.getBlockState(pos));
    }

    @GameTest(template = EMPTY, batch = "gb1_nether")
    public static void in_the_nether_a_grave_never_goes_up_onto_the_roof(GameTestHelper h) {
        ServerLevel nether = h.getLevel().getServer().getLevel(Level.NETHER);
        h.assertTrue(nether != null, "the test server has a Nether");
        java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> was = new java.util.LinkedHashMap<>();
        RegionGuard.borderForTests(Level.NETHER, new RegionGuard.Square(-3.0E7, 8, -3.0E7, 3.0E7)); // the east edge at x 8
        try {
            // the nearest columns inside (x 2) sealed in netherrack around y 64, out to the search radius (2)
            for (int x = 0; x <= 4; x++) {
                for (int y = 62; y <= 66; y++) {
                    for (int z = -2; z <= 2; z++) {
                        BlockPos p = new BlockPos(x, y, z);
                        remember(nether, was, p);
                        nether.setBlock(p, Blocks.NETHERRACK.defaultBlockState(), 3);
                    }
                }
            }
            ServerPlayer p = TestPlayers.mockServerPlayer(h, UUID.randomUUID(), nether);
            p.moveTo(12.5, 64, 0.5);
            GraveService graves = service(h);
            graves.recordLayout(p, nether.damageSources().fall());
            List<ItemEntity> drops = new ArrayList<>(List.of(new ItemEntity(nether, 12.5, 64, 0.5, new ItemStack(Items.DIAMOND, 2))));
            Grave g = graves.onDrops(p, nether.damageSources().fall(), drops, 1_000);
            h.assertTrue(g != null && !g.placed(), "no room inside at that height and no top step in the Nether: kept without a block, not on the roof: "
                    + (g == null ? null : at(g) + " placed " + g.placed()));

            // a standing grave at the edge there finds no spot either: it loses its block, recoverable at once
            BlockPos oldAt = new BlockPos(6, 64, 0);
            remember(nether, was, oldAt);
            Grave s = standing(h, nether, graves.local(), "", oldAt, UUID.randomUUID());
            h.assertTrue(graves.moveFromBorders().join() == 1, "it is dealt with");
            Grave s2 = graves.local().grave(s.id()).join().orElseThrow();
            h.assertTrue(!s2.placed() && !(nether.getBlockEntity(oldAt) instanceof GraveBlockEntity), "kept without a block, its headstone gone: " + s2);
        } finally {
            RegionGuard.borderForTests(Level.NETHER, null);
            was.forEach((pos, state) -> nether.setBlock(pos, state, 3));
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = "gb1_far")
    public static void a_death_far_past_the_border_still_gets_a_standing_grave(GameTestHelper h) {
        BlockPos box = h.absolutePos(BlockPos.ZERO);
        int edge = box.getX() + 3_000;
        RegionGuard.borderForTests(Level.OVERWORLD, new RegionGuard.Square(-3.0E7, edge, -3.0E7, 3.0E7));
        ServerLevel level = h.getLevel();
        java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> was = new java.util.LinkedHashMap<>();
        BlockPos death = new BlockPos(edge + 10, box.getY() + 1, box.getZ() + 3); // 3,000 blocks away
        BlockPos column = new BlockPos(edge - 6, death.getY(), death.getZ()); // the nearest column with the margin (5) to spare
        Grave g = null;
        Grave first;
        try {
            // In land nobody has loaded: the search loads it. What stands there depends on the world's seed (a fresh test
            // world takes a random one: ocean, rock, hills), so this first grave may have a spot or not; it is kept either way.
            h.assertTrue(!level.hasChunkAt(column), "the land at " + column + " is not loaded yet");
            first = die(h, TestPlayers.mockServerPlayer(h), death);
            h.assertTrue(first != null && level.hasChunkAt(column), "a grave all the same, and the search loaded the land: " + first);
            h.assertTrue(!first.placed() || first.x() + 1 <= edge - 5, "never outside the border: " + at(first) + ", edge x " + edge);
            if (first.placed()) {
                level.setBlock(at(first), Blocks.AIR.defaultBlockState(), 3); // far outside the box: removed by hand
            }

            // now on ground of its own, the same death gets a standing grave at the nearest columns inside
            for (int x = edge - 10; x <= edge - 6; x++) {
                for (int z = death.getZ() - 3; z <= death.getZ() + 3; z++) {
                    for (int y = death.getY() - 1; y <= death.getY() + 2; y++) {
                        BlockPos p = new BlockPos(x, y, z);
                        remember(level, was, p);
                        level.setBlock(p, y < death.getY() ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
            g = die(h, TestPlayers.mockServerPlayer(h), death);
            h.assertTrue(g != null && g.placed(), "a standing grave: " + (g == null ? null : at(g)));
            h.assertTrue(g.x() + 1 <= edge - 5 && g.x() >= edge - 6 - 2 && Math.abs(g.z() - death.getZ()) <= 2, "at the nearest columns inside: " + at(g) + ", edge x " + edge);
        } finally {
            RegionGuard.borderForTests(Level.OVERWORLD, null);
            if (g != null && g.placed() && !was.containsKey(at(g))) {
                level.setBlock(at(g), Blocks.AIR.defaultBlockState(), 3); // outside the ground built for it: removed by hand
            }
            was.forEach((pos, state) -> level.setBlock(pos, state, 3)); // the land as it was
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = "gb1_restart")
    public static void the_graves_start_reads_the_margin_and_moves_graves_too_close(GameTestHelper h) {
        // in the End, where no other test leaves graves, so the real start touches only this one
        ServerLevel end = h.getLevel().getServer().getLevel(Level.END);
        h.assertTrue(end != null && GraveService.active() != null, "the test server has the End and graves on");
        WorldGraveStore world = WorldGraveStore.get(h.getLevel().getServer());
        java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> was = new java.util.LinkedHashMap<>();
        RegionGuard.borderForTests(Level.END, new RegionGuard.Square(-3.0E7, 300, -3.0E7, 3.0E7)); // the east edge at x 300
        int margin = GraveBorder.margin();
        Grave g = null;
        try {
            for (int x = 285; x <= 299; x++) {
                BlockPos floor = new BlockPos(x, 63, 300);
                remember(end, was, floor);
                remember(end, was, floor.above());
                end.setBlock(floor, Blocks.OBSIDIAN.defaultBlockState(), 3);
            }
            BlockPos oldAt = new BlockPos(297, 64, 300); // 2 whole blocks from the edge
            g = standing(h, end, world, GraveService.active().serverIdNow(), oldAt, UUID.randomUUID());
            GraveBorder.setMargin(1); // the start must read graves.borderMargin (5) again, or this grave counts as far enough in
            GraveService.restart(h.getLevel().getServer());
            h.assertTrue(GraveBorder.margin() == 5, "the start read the margin from the config: " + GraveBorder.margin());
            Grave after = world.grave(g.id()).join().orElseThrow();
            h.assertTrue(after.placed() && at(after).equals(new BlockPos(294, 64, 300)), "the start moved it 5 whole blocks in: " + after);
            h.assertTrue(end.getBlockEntity(at(after)) instanceof GraveBlockEntity be && g.id().equals(be.graveId()), "its headstone there");
            remember(end, was, at(after));
        } finally {
            GraveBorder.setMargin(margin);
            RegionGuard.borderForTests(Level.END, null);
            if (g != null) {
                world.grave(g.id()).join().ifPresent(x -> world.deleteGrave(x.id(), x.revision()).join());
            }
            was.forEach((pos, state) -> end.setBlock(pos, state, 3));
        }
        h.succeed();
    }
}
