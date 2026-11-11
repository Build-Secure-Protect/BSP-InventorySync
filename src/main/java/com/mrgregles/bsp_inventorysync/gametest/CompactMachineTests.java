package com.mrgregles.bsp_inventorysync.gametest;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.compat.CompactMachines;
import com.mrgregles.bsp_inventorysync.data.PlayerSnapshotCodec;
import com.mrgregles.bsp_inventorysync.grave.GraveContents;
import com.mrgregles.bsp_inventorysync.grave.GraveService;
import com.mrgregles.bsp_inventorysync.grave.WorldGraveStore;
import com.mrgregles.bsp_inventorysync.machine.MachineCommands;
import com.mrgregles.bsp_inventorysync.machine.MachineGuard;
import com.mrgregles.bsp_inventorysync.storage.Grave;
import com.mrgregles.bsp_inventorysync.storage.MemoryStore;
import com.mrgregles.bsp_inventorysync.storage.SaveCause;
import com.mrgregles.bsp_inventorysync.sync.ArrivalPolicy;
import com.mrgregles.bsp_inventorysync.sync.SyncService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.gametest.GameTestHolder;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * D31 (BSP-Mod Fixes' R-3): a bound Compact Machine works only on its home server. The dev run has no Compact Machines,
 * so a lodestone stands in for a machine item and block (same id for both, as the machines have); the rules read the
 * item id and Compact Machines' own {@code room_pos} tag, nothing of the mod itself.
 */
@GameTestHolder(BSPInventorySync.MODID)
@PrefixGameTestTemplate(false)
public class CompactMachineTests {
    private static final String EMPTY = "empty";
    private static final String HUB = "hub";
    private static final String PVP = "pvp";
    private static final String STAND_IN = "minecraft:lodestone";
    private static final ArrivalPolicy FAST = new ArrivalPolicy(1_000, false);

    /** Runs {@code body} with the lodestone treated as a machine and, when {@code local} is set, as server {@code local}. */
    private static void with(String local, Runnable body) {
        CompactMachines.treatAsMachineForTests(STAND_IN, true);
        CompactMachines.loadedForTests(true);
        MachineGuard.actAsServerForTests(local);
        MachineGuard.forgetToldForTests();
        try {
            body.run();
        } finally {
            MachineGuard.forgetToldForTests();
            MachineGuard.actAsServerForTests(null);
            CompactMachines.loadedForTests(null);
            CompactMachines.treatAsMachineForTests(STAND_IN, false);
        }
    }

    /** A stand-in machine: bound to room (3, 0) when {@code bound}, with {@code home} as its mark when not null. */
    private static ItemStack machine(boolean bound, String home) {
        ItemStack s = new ItemStack(Items.LODESTONE);
        if (bound) {
            s.getOrCreateTag().putIntArray(CompactMachines.ROOM, new int[]{3, 0});
        }
        if (home != null) {
            s.getOrCreateTag().putString(CompactMachines.HOME, home);
        }
        return s;
    }

    /** A container item holding {@code inside} the way its item keeps it: a shulker box's BlockEntityTag, a bundle's Items. */
    private static ItemStack holding(ItemStack container, ItemStack inside) {
        CompoundTag item = inside.save(new CompoundTag());
        item.putByte("Slot", (byte) 0);
        ListTag items = new ListTag();
        items.add(item);
        if (container.is(Items.BUNDLE)) {
            container.getOrCreateTag().put("Items", items);
        } else {
            CompoundTag bet = new CompoundTag();
            bet.put("Items", items);
            container.getOrCreateTag().put("BlockEntityTag", bet);
        }
        return container;
    }

    /** The mark of the first item inside a container item ("" for none). */
    private static String insideHome(ItemStack container) {
        CompoundTag tag = container.getOrCreateTag();
        ListTag items = container.is(Items.BUNDLE) ? tag.getList("Items", Tag.TAG_COMPOUND) : tag.getCompound("BlockEntityTag").getList("Items", Tag.TAG_COMPOUND);
        return items.getCompound(0).getCompound("tag").getString(CompactMachines.HOME);
    }

    private static String homeOf(ItemStack s) {
        return s.hasTag() ? s.getTag().getString(CompactMachines.HOME) : "";
    }

    private static ServerPlayer settled(GameTestHelper h, SyncService service, UUID id) {
        ServerPlayer p = TestPlayers.mockServerPlayer(h, id);
        service.onLogin(p, 0);
        service.tick(0);
        h.assertTrue(!SyncService.isSyncing(id), "settled on " + service.serverId());
        return p;
    }

    /** A survival player standing beside a stone block, looking at its top; returns the hit on that top. */
    private static BlockHitResult facingStone(GameTestHelper h, ServerPlayer p) {
        h.setBlock(new BlockPos(1, 1, 1), Blocks.STONE);
        BlockPos stone = h.absolutePos(new BlockPos(1, 1, 1));
        p.moveTo(stone.getX() + 2.5, stone.getY() + 1, stone.getZ() + 0.5);
        p.setGameMode(GameType.SURVIVAL);
        return new BlockHitResult(Vec3.atCenterOf(stone).add(0, 0.5, 0), Direction.UP, stone, false);
    }

    /** The value of a future that must already be done (never join one on the server thread). */
    private static <T> T done(java.util.concurrent.CompletableFuture<T> f) {
        if (!f.isDone()) {
            throw new AssertionError("the future is not done yet");
        }
        return f.join();
    }

    /** Runs {@code body} counting the place events fired meanwhile (the guard must refuse before any block is set). */
    private static int placeEventsDuring(Runnable body) {
        int[] n = {0};
        java.util.function.Consumer<BlockEvent.EntityPlaceEvent> counter = e -> n[0]++;
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, BlockEvent.EntityPlaceEvent.class, counter);
        try {
            body.run();
        } finally {
            MinecraftForge.EVENT_BUS.unregister(counter);
        }
        return n[0];
    }

    /** The packets sent to a mock player since the last call (its connection writes them to an embedded channel). */
    private static List<Object> sent(ServerPlayer p) {
        EmbeddedChannel channel = (EmbeddedChannel) p.connection.connection.channel();
        List<Object> out = new ArrayList<>();
        for (Object o; (o = channel.readOutbound()) != null; ) {
            out.add(o);
        }
        return out;
    }

    private static List<String> actionBar(List<Object> packets) {
        return packets.stream().filter(o -> o instanceof ClientboundSystemChatPacket c && c.overlay())
                .map(o -> ((ClientboundSystemChatPacket) o).content().getString()).toList();
    }

    /** A placement made the way Dank Storage's construction mode makes it: the selected item's own useOn, with its stack. */
    private static void placeAsADank(ServerPlayer placer, ItemStack stack, BlockHitResult hit, GameTestHelper h) {
        stack.getItem().useOn(new UseOnContext(h.getLevel(), placer, InteractionHand.MAIN_HAND, stack, hit) {});
    }

    // ------------------------------------------------------------------ the mark

    @GameTest(template = EMPTY)
    public static void a_bound_machine_is_marked_with_its_server_and_a_mark_is_never_changed(GameTestHelper h) {
        with(null, () -> {
            ItemStack bound = machine(true, null);
            h.assertTrue(CompactMachines.mark(bound, HUB) == 1 && homeOf(bound).equals(HUB), "an unmarked bound machine takes the server: " + bound.getTag());
            h.assertTrue(CompactMachines.mark(bound, PVP) == 0 && homeOf(bound).equals(HUB), "a mark is never changed");
            h.assertTrue(bound.getTag().getIntArray(CompactMachines.ROOM)[0] == 3, "room_pos untouched");

            ItemStack unbound = machine(false, null);
            h.assertTrue(CompactMachines.mark(unbound, HUB) == 0 && !unbound.hasTag(), "an unbound machine is never marked (D31 5)");
            ItemStack other = new ItemStack(Items.DIAMOND);
            other.getOrCreateTag().putIntArray(CompactMachines.ROOM, new int[]{3, 0});
            h.assertTrue(CompactMachines.mark(other, HUB) == 0 && homeOf(other).isEmpty(), "only machine items are marked");
            h.assertTrue(CompactMachines.mark(machine(true, null), "") == 0, "no server id (sync off): nothing marked");

            ItemStack shulker = holding(new ItemStack(Items.SHULKER_BOX), machine(true, null));
            ItemStack bundle = holding(new ItemStack(Items.BUNDLE), holding(new ItemStack(Items.SHULKER_BOX), machine(true, null)));
            h.assertTrue(CompactMachines.mark(shulker, HUB) == 1 && insideHome(shulker).equals(HUB), "a machine inside a shulker box is marked");
            h.assertTrue(CompactMachines.mark(bundle, HUB) == 1, "a machine in a shulker box in a bundle is marked: " + bundle.getTag());
            ItemStack inner = ItemStack.of(bundle.getTag().getList("Items", Tag.TAG_COMPOUND).getCompound(0));
            h.assertTrue(insideHome(inner).equals(HUB), "at any depth: " + inner.getTag());

            h.assertTrue(CompactMachines.awayFromHome(machine(true, HUB), PVP), "marked for hub: away from home on pvp");
            h.assertTrue(!CompactMachines.awayFromHome(machine(true, "HUB"), HUB), "ids that differ only by case are the same server");
            h.assertTrue(!CompactMachines.awayFromHome(machine(true, null), PVP), "unmarked counts as local (5 a)");
            h.assertTrue(!CompactMachines.awayFromHome(machine(false, HUB), PVP), "an unbound machine is never away from home");
            h.assertTrue(!CompactMachines.awayFromHome(machine(true, HUB), ""), "sync off: nothing is away from home");
        });
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_save_marks_what_the_player_carries_with_this_server_and_another_server_keeps_it(GameTestHelper h) {
        with(null, () -> {
            MemoryStore store = new MemoryStore();
            UUID id = UUID.randomUUID();
            SyncService hub = new SyncService(h.getLevel().getServer(), store, HUB, FAST);
            ServerPlayer p = settled(h, hub, id);
            p.getInventory().setItem(0, machine(true, null));
            p.getInventory().setItem(1, machine(false, null));
            p.getInventory().setItem(2, holding(new ItemStack(Items.SHULKER_BOX), machine(true, null)));
            p.getInventory().setItem(3, machine(true, "frost"));
            p.getEnderChestInventory().setItem(0, machine(true, null));
            hub.onLogout(p).join();

            CompoundTag saved = PlayerSnapshotCodec.decode(store.latest(id).join().orElseThrow().data());
            List<String> homes = new ArrayList<>();
            collectHomes(saved, homes);
            homes.sort(null);
            h.assertTrue(homes.equals(List.of("frost", HUB, HUB, HUB)), "the snapshot marks the three unmarked bound machines with hub and keeps frost's: " + homes);

            SyncService pvp = new SyncService(h.getLevel().getServer(), store, PVP, FAST);
            ServerPlayer q = settled(h, pvp, id);
            h.assertTrue(homeOf(q.getInventory().getItem(0)).equals(HUB), "arrives on pvp marked hub: " + q.getInventory().getItem(0).getTag());
            h.assertTrue(!q.getInventory().getItem(1).hasTag(), "the unbound machine arrives with no tag");
            h.assertTrue(insideHome(q.getInventory().getItem(2)).equals(HUB), "the machine in the shulker box too");
            h.assertTrue(homeOf(q.getInventory().getItem(3)).equals("frost"), "a machine marked elsewhere keeps its mark");
            h.assertTrue(homeOf(q.getEnderChestInventory().getItem(0)).equals(HUB), "and the one in the ender chest");
            h.assertTrue(CompactMachines.awayFromHome(q.getInventory().getItem(0), PVP), "so it is away from home on pvp");
            pvp.onLogout(q).join();
        });
        h.succeed();
    }

    private static void collectHomes(Tag tag, List<String> out) {
        if (tag instanceof CompoundTag c) {
            if (c.contains(CompactMachines.HOME, Tag.TAG_STRING)) {
                out.add(c.getString(CompactMachines.HOME));
            }
            for (String key : c.getAllKeys()) {
                collectHomes(c.get(key), out);
            }
        } else if (tag instanceof ListTag l) {
            for (Tag t : l) {
                collectHomes(t, out);
            }
        }
    }

    @GameTest(template = EMPTY)
    public static void an_unmarked_machine_in_an_older_snapshot_takes_the_server_that_saved_it(GameTestHelper h) {
        with(null, () -> {
            MemoryStore store = new MemoryStore();
            UUID id = UUID.randomUUID();
            ServerPlayer before = TestPlayers.mockServerPlayer(h, id);
            before.getInventory().setItem(0, machine(true, null));
            // saved by a server running a version without the mark: the snapshot carries the machine unmarked
            store.save(id, HUB, SaveCause.LOGOUT, PlayerSnapshotCodec.encode(PlayerSnapshotCodec.capture(before))).join();

            SyncService pvp = new SyncService(h.getLevel().getServer(), store, PVP, FAST);
            ServerPlayer q = settled(h, pvp, id);
            h.assertTrue(homeOf(q.getInventory().getItem(0)).equals(HUB), "marked with the snapshot's server, not this one: " + q.getInventory().getItem(0).getTag());
            pvp.onLogout(q).join();
        });
        h.succeed();
    }

    // ------------------------------------------------------------------ the refusal

    @GameTest(template = EMPTY)
    public static void a_machine_is_refused_away_from_home_and_stays_in_the_hand(GameTestHelper h) {
        with(PVP, () -> {
            ServerPlayer p = TestPlayers.mockServerPlayer(h);
            BlockHitResult hit = facingStone(h, p);
            BlockPos above = hit.getBlockPos().above();
            p.setItemInHand(InteractionHand.MAIN_HAND, machine(true, HUB));
            // refused at the right click itself, before the item is used (so before Compact Machines would bind the block)
            PlayerInteractEvent.RightClickBlock click = new PlayerInteractEvent.RightClickBlock(p, InteractionHand.MAIN_HAND, hit.getBlockPos(), hit);
            MinecraftForge.EVENT_BUS.post(click);
            h.assertTrue(click.getUseItem() == Event.Result.DENY, "the right click denies the item's use, got " + click.getUseItem());
            MachineGuard.forgetToldForTests(); // the hand-posted click above was told; the real one below starts afresh
            sent(p);
            int events = placeEventsDuring(() -> p.gameMode.useItemOn(p, h.getLevel(), p.getMainHandItem(), InteractionHand.MAIN_HAND, hit));
            h.assertTrue(events == 0, "nothing was set and rolled back: no place event fired, got " + events);
            h.assertTrue(h.getLevel().getBlockState(above).isAir(), "not placed: " + h.getLevel().getBlockState(above));
            ItemStack held = p.getMainHandItem();
            h.assertTrue(held.is(Items.LODESTONE) && held.getCount() == 1 && homeOf(held).equals(HUB), "the machine stays in the hand, unchanged: " + held);
            List<Object> packets = sent(p);
            h.assertTrue(actionBar(packets).equals(List.of(MachineGuard.refusal(HUB))), "one action-bar line naming the home server: " + actionBar(packets));
            int slot = p.getInventory().selected;
            h.assertTrue(packets.stream().anyMatch(o -> o instanceof ClientboundContainerSetSlotPacket c && c.getSlot() == slot && c.getItem().getCount() == 1),
                    "the hand's slot is sent again with the machine in it (the client's prediction took it)");

            // held down: a click every few ticks; the slot is sent each time, the line at most once a second (AUD-8 5 a)
            p.gameMode.useItemOn(p, h.getLevel(), p.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
            List<Object> again = sent(p);
            h.assertTrue(actionBar(again).isEmpty(), "not told again within the second: " + actionBar(again));
            h.assertTrue(again.stream().anyMatch(o -> o instanceof ClientboundContainerSetSlotPacket c && c.getSlot() == slot), "but the slot is sent again");
        });
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_machine_is_placed_at_home_and_when_unmarked_unbound_or_the_sync_is_off(GameTestHelper h) {
        String[][] cases = {
                {PVP, "Pvp", "bound", "its own server, in another case"},
                {PVP, null, "bound", "an unmarked bound machine (local, 5 a)"},
                {PVP, HUB, "unbound", "an unbound machine, whatever its tag"},
                {"", HUB, "bound", "the sync off: no server id"},
        };
        for (String[] c : cases) {
            with(c[0], () -> {
                ServerPlayer p = TestPlayers.mockServerPlayer(h);
                BlockHitResult hit = facingStone(h, p);
                BlockPos above = hit.getBlockPos().above();
                p.setItemInHand(InteractionHand.MAIN_HAND, machine(c[2].equals("bound"), c[1]));
                PlayerInteractEvent.RightClickBlock click = new PlayerInteractEvent.RightClickBlock(p, InteractionHand.MAIN_HAND, hit.getBlockPos(), hit);
                MinecraftForge.EVENT_BUS.post(click);
                h.assertTrue(click.getUseItem() != Event.Result.DENY, c[3] + ": the right click lets it through");
                p.gameMode.useItemOn(p, h.getLevel(), p.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
                boolean placed = h.getLevel().getBlockState(above).is(Blocks.LODESTONE);
                h.getLevel().removeBlock(above, false);
                h.assertTrue(placed && p.getMainHandItem().isEmpty(), c[3] + ": placed, got " + h.getLevel().getBlockState(above) + " and " + p.getMainHandItem());
            });
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_machine_is_refused_however_it_is_placed_and_placed_so_at_home(GameTestHelper h) {
        with(PVP, () -> {
            ServerPlayer p = TestPlayers.mockServerPlayer(h);
            BlockHitResult hit = facingStone(h, p);
            BlockPos above = hit.getBlockPos().above();
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIRT)); // the "dank" in the hand
            ItemStack selected = machine(true, HUB);

            int events = placeEventsDuring(() -> placeAsADank(p, selected, hit, h));
            h.assertTrue(h.getLevel().getBlockState(above).isAir() && events == 0, "Dank Storage's route (the item's own useOn): not placed, nothing set: " + events);
            h.assertTrue(selected.getCount() == 1 && homeOf(selected).equals(HUB), "the selected stack is untouched: " + selected);

            p.setItemInHand(InteractionHand.MAIN_HAND, machine(true, HUB));
            p.getMainHandItem().useOn(new UseOnContext(p, InteractionHand.MAIN_HAND, hit)); // past the click, as a mod calling the stack
            h.assertTrue(h.getLevel().getBlockState(above).isAir() && p.getMainHandItem().getCount() == 1, "the stack's useOn: not placed, kept");

            ItemStack noPlacer = machine(true, HUB);
            noPlacer.getItem().useOn(new UseOnContext(h.getLevel(), null, InteractionHand.MAIN_HAND, noPlacer, hit) {});
            h.assertTrue(h.getLevel().getBlockState(above).isAir() && noPlacer.getCount() == 1, "with no player at all (a block placer): not placed");

            // the control: the same routes at home do place
            ItemStack atHome = machine(true, PVP);
            placeAsADank(p, atHome, hit, h);
            boolean placed = h.getLevel().getBlockState(above).is(Blocks.LODESTONE);
            h.getLevel().removeBlock(above, false);
            h.assertTrue(placed, "a machine of this server is placed by the same route");
        });
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void another_mods_listener_cannot_allow_the_click_again(GameTestHelper h) {
        with(PVP, () -> {
            ServerPlayer p = TestPlayers.mockServerPlayer(h);
            BlockHitResult hit = facingStone(h, p);
            p.setItemInHand(InteractionHand.MAIN_HAND, machine(true, HUB));
            java.util.function.Consumer<PlayerInteractEvent.RightClickBlock> allow = e -> e.setUseItem(Event.Result.ALLOW);
            MinecraftForge.EVENT_BUS.addListener(EventPriority.LOW, false, PlayerInteractEvent.RightClickBlock.class, allow);
            PlayerInteractEvent.RightClickBlock click = new PlayerInteractEvent.RightClickBlock(p, InteractionHand.MAIN_HAND, hit.getBlockPos(), hit);
            try {
                MinecraftForge.EVENT_BUS.post(click);
            } finally {
                MinecraftForge.EVENT_BUS.unregister(allow);
            }
            h.assertTrue(click.getUseItem() == Event.Result.DENY, "the refusal runs last, after a listener that allows it: " + click.getUseItem());
        });
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_machine_is_refused_from_the_offhand_and_in_creative(GameTestHelper h) {
        with(PVP, () -> {
            ServerPlayer p = TestPlayers.mockServerPlayer(h);
            BlockHitResult hit = facingStone(h, p);
            BlockPos above = hit.getBlockPos().above();
            p.setItemInHand(InteractionHand.OFF_HAND, machine(true, HUB));
            PlayerInteractEvent.RightClickBlock click = new PlayerInteractEvent.RightClickBlock(p, InteractionHand.OFF_HAND, hit.getBlockPos(), hit);
            MinecraftForge.EVENT_BUS.post(click);
            h.assertTrue(click.getUseItem() == Event.Result.DENY, "the offhand click is denied");
            p.gameMode.useItemOn(p, h.getLevel(), p.getOffhandItem(), InteractionHand.OFF_HAND, hit);
            h.assertTrue(h.getLevel().getBlockState(above).isAir() && p.getOffhandItem().getCount() == 1, "not placed from the offhand, kept");

            p.setGameMode(GameType.CREATIVE);
            p.setItemInHand(InteractionHand.MAIN_HAND, machine(true, HUB));
            p.gameMode.useItemOn(p, h.getLevel(), p.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
            h.assertTrue(h.getLevel().getBlockState(above).isAir() && p.getMainHandItem().getCount() == 1, "not placed in creative either");
        });
        h.succeed();
    }

    // ------------------------------------------------------------------ marks made here

    @GameTest(template = EMPTY)
    public static void a_dropped_bound_machine_is_marked_with_this_server(GameTestHelper h) {
        with(HUB, () -> {
            Vec3 at = Vec3.atCenterOf(h.absolutePos(new BlockPos(1, 2, 1)));
            ItemEntity fresh = new ItemEntity(h.getLevel(), at.x, at.y, at.z, machine(true, null));
            ItemEntity marked = new ItemEntity(h.getLevel(), at.x, at.y, at.z, machine(true, "frost"));
            ItemEntity unbound = new ItemEntity(h.getLevel(), at.x, at.y, at.z, machine(false, null));
            ItemEntity box = new ItemEntity(h.getLevel(), at.x, at.y, at.z, holding(new ItemStack(Items.SHULKER_BOX), machine(true, null)));
            for (ItemEntity e : List.of(fresh, marked, unbound, box)) {
                h.getLevel().addFreshEntity(e);
            }
            try {
                h.assertTrue(homeOf(fresh.getItem()).equals(HUB), "the machine block's drop takes this server: " + fresh.getItem().getTag());
                h.assertTrue(homeOf(marked.getItem()).equals("frost"), "a dropped machine marked elsewhere keeps its mark");
                h.assertTrue(!unbound.getItem().hasTag(), "an unbound one is untouched");
                h.assertTrue(insideHome(box.getItem()).equals(HUB), "a machine in a dropped shulker box too");
            } finally {
                for (ItemEntity e : List.of(fresh, marked, unbound, box)) {
                    e.discard();
                }
            }
        });
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_grave_keeps_the_home_of_the_machines_in_it(GameTestHelper h) {
        with(null, () -> {
            SyncService hub = new SyncService(h.getLevel().getServer(), new MemoryStore(), HUB, FAST);
            GraveService graves = new GraveService(h.getLevel().getServer(), new WorldGraveStore(), () -> hub, 2);
            ServerPlayer p = TestPlayers.mockServerPlayer(h);
            h.setBlock(new BlockPos(2, 0, 2), Blocks.STONE);
            BlockPos abs = h.absolutePos(new BlockPos(2, 1, 2));
            p.moveTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5);
            List<ItemEntity> drops = new ArrayList<>();
            drops.add(new ItemEntity(h.getLevel(), p.getX(), p.getY(), p.getZ(), machine(true, null)));
            drops.add(new ItemEntity(h.getLevel(), p.getX(), p.getY(), p.getZ(), holding(new ItemStack(Items.SHULKER_BOX), machine(true, null))));
            Grave g = graves.onDrops(p, h.getLevel().damageSources().fall(), drops, 1_000);
            h.assertTrue(g != null, "a grave");
            List<GraveContents.Entry> raw = GraveContents.decode(g.data()).entries();
            h.assertTrue(homeOf(raw.get(0).stack()).equals(HUB) && insideHome(raw.get(1).stack()).equals(HUB),
                    "stored marked with the server of the death, so a recovery anywhere keeps it: " + raw);
            if (g.placed()) {
                h.getLevel().removeBlock(new BlockPos(g.x(), g.y(), g.z()), false);
            }

            // a grave stored before the mark existed: its machines take the grave's server when it is read
            Grave old = new Grave(UUID.randomUUID(), p.getUUID(), "Steve", "frost", "minecraft:overworld", 1, 64, 2, false, 1, 1, 0, 0, 1, 0,
                    new GraveContents(List.of(new GraveContents.Entry("inv:0", machine(true, null))), new CompoundTag()).encode());
            h.assertTrue(homeOf(GraveContents.decode(old.data()).entries().get(0).stack()).isEmpty(), "stored unmarked");
            h.assertTrue(homeOf(GraveContents.of(old).entries().get(0).stack()).equals("frost"), "read through the grave: frost's");
        });
        h.succeed();
    }

    // ------------------------------------------------------------------ the admin fix

    @GameTest(template = EMPTY)
    public static void the_admin_command_shows_sets_and_clears_the_home_of_the_machine_in_hand(GameTestHelper h) {
        with(PVP, () -> {
            ServerPlayer p = TestPlayers.mockServerPlayer(h);
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND));
            h.assertTrue(MachineCommands.setHome(p, PVP, "admin").startsWith("Hold a Compact Machine"), "not a machine: refused");
            p.setItemInHand(InteractionHand.MAIN_HAND, machine(false, null));
            h.assertTrue(MachineCommands.setHome(p, PVP, "admin").contains("no room yet") && !p.getMainHandItem().hasTag(), "an unbound machine gets no home");
            p.setItemInHand(InteractionHand.MAIN_HAND, machine(true, HUB));
            h.assertTrue(MachineCommands.describe(p.getMainHandItem()).equals("This machine's room is on hub."), "shown");
            String set = MachineCommands.setHome(p, PVP, "admin");
            h.assertTrue(homeOf(p.getMainHandItem()).equals(PVP) && set.contains("was hub"), "set: " + set);
            h.assertTrue(!CompactMachines.awayFromHome(p.getMainHandItem(), PVP), "and it works here now");
            String cleared = MachineCommands.clear(p, "admin");
            h.assertTrue(!p.getMainHandItem().getTag().contains(CompactMachines.HOME) && cleared.contains("was pvp"), "cleared: " + cleared);
            h.assertTrue(p.getMainHandItem().getTag().contains(CompactMachines.ROOM), "the room is kept");
            h.assertTrue(MachineCommands.clear(p, "admin").contains("no home mark"), "nothing more to clear");
        });
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void the_command_runs_through_the_tree_for_level_2_only(GameTestHelper h) {
        with(PVP, () -> {
            ServerPlayer p = TestPlayers.mockServerPlayer(h);
            p.setItemInHand(InteractionHand.MAIN_HAND, machine(true, HUB));
            var commands = h.getLevel().getServer().getCommands();
            commands.performPrefixedCommand(p.createCommandSourceStack().withPermission(0), "invsync machine home pvp");
            h.assertTrue(homeOf(p.getMainHandItem()).equals(HUB), "level 0: refused, the mark unchanged");
            int ok = commands.performPrefixedCommand(p.createCommandSourceStack().withPermission(2), "invsync machine home pvp");
            h.assertTrue(ok == 1 && homeOf(p.getMainHandItem()).equals(PVP), "level 2: set");
            commands.performPrefixedCommand(p.createCommandSourceStack().withPermission(2), "invsync machine clear");
            h.assertTrue(!p.getMainHandItem().getTag().contains(CompactMachines.HOME), "clear through the tree");
            int console = commands.performPrefixedCommand(h.getLevel().getServer().createCommandSourceStack().withSuppressedOutput(), "invsync machine home pvp");
            h.assertTrue(console == 0, "the console has no hand: fails cleanly");
        });
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void setting_a_home_warns_about_an_unknown_server_and_clearing_says_what_follows(GameTestHelper h) {
        with(PVP, () -> {
            SyncService sync = new SyncService(h.getLevel().getServer(), new MemoryStore(), PVP, FAST);
            sync.setNetworkServers(List.of(HUB, PVP));
            ServerPlayer p = TestPlayers.mockServerPlayer(h);
            p.setItemInHand(InteractionHand.MAIN_HAND, machine(true, HUB));
            String typo = MachineCommands.setHome(p, "hbu", "admin", sync);
            h.assertTrue(homeOf(p.getMainHandItem()).equals("hbu") && typo.contains("Warning") && typo.contains("sync.networkServers"), "set, with a warning: " + typo);
            String listed = MachineCommands.setHome(p, "HUB", "admin", sync);
            h.assertTrue(!listed.contains("Warning"), "a listed server (any case) sets quietly: " + listed);
            h.assertTrue(!MachineCommands.setHome(p, PVP, "admin", sync).contains("Warning"), "this server sets quietly");
            h.assertTrue(MachineCommands.setHome(p, HUB, "admin", null).contains("not checked"), "with the sync off it says the id was not checked");
            String cleared = MachineCommands.clear(p, "admin");
            h.assertTrue(cleared.contains("counts as this server's (pvp)") && cleared.contains("opens this server's room"), "the clear says what it means: " + cleared);
        });
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_machine_given_through_the_network_queue_is_marked_with_the_giving_server(GameTestHelper h) {
        with(null, () -> {
            MemoryStore store = new MemoryStore();
            com.mrgregles.bsp_inventorysync.give.GiveService hub = new com.mrgregles.bsp_inventorysync.give.GiveService(h.getLevel().getServer(), store, HUB, 5_000);
            UUID id = UUID.randomUUID();
            ItemStack typed = machine(true, null);
            done(hub.give(null, id, typed, 1, "Admin"));
            done(hub.give(null, id, machine(true, "frost"), 1, "Admin"));
            List<String> homes = store.gives(List.of(id)).join().stream().map(g -> homeOf(com.mrgregles.bsp_inventorysync.give.GiveService.decode(g))).sorted().toList();
            h.assertTrue(homes.equals(List.of("frost", HUB)), "the row carries the giving server's mark, a mark already there kept: " + homes);
            h.assertTrue(!typed.getTag().contains(CompactMachines.HOME), "the command's own stack is not changed");
        });
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_server_without_compact_machines_never_marks_with_its_own_id(GameTestHelper h) {
        with(HUB, () -> {
            CompactMachines.loadedForTests(false);
            MemoryStore store = new MemoryStore();
            UUID id = UUID.randomUUID();
            SyncService hub = new SyncService(h.getLevel().getServer(), store, HUB, FAST);
            ServerPlayer p = settled(h, hub, id);
            p.getInventory().setItem(0, machine(true, null));
            hub.onLogout(p).join();
            List<String> homes = new ArrayList<>();
            collectHomes(PlayerSnapshotCodec.decode(store.latest(id).join().orElseThrow().data()), homes);
            h.assertTrue(homes.isEmpty(), "the save leaves it unmarked: " + homes);

            Vec3 at = Vec3.atCenterOf(h.absolutePos(new BlockPos(1, 2, 1)));
            ItemEntity drop = new ItemEntity(h.getLevel(), at.x, at.y, at.z, machine(true, null));
            h.getLevel().addFreshEntity(drop);
            boolean dropMarked = homeOf(drop.getItem()).length() > 0;
            drop.discard();
            h.assertTrue(!dropMarked, "a drop is not marked");

            com.mrgregles.bsp_inventorysync.give.GiveService gives = new com.mrgregles.bsp_inventorysync.give.GiveService(h.getLevel().getServer(), store, HUB, 5_000);
            done(gives.give(null, id, machine(true, null), 1, "Admin"));
            h.assertTrue(homeOf(com.mrgregles.bsp_inventorysync.give.GiveService.decode(store.gives(List.of(id)).join().get(0))).isEmpty(), "a give is not marked");

            // a restore still marks with the server that saved the snapshot
            SyncService pvp = new SyncService(h.getLevel().getServer(), store, PVP, FAST);
            ServerPlayer q = settled(h, pvp, id);
            h.assertTrue(homeOf(q.getInventory().getItem(0)).equals(HUB), "restored: the snapshot's server");
            pvp.onLogout(q).join();
        });
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_returned_grave_and_its_backpacks_and_danks_carry_the_graves_server(GameTestHelper h) {
        with(null, () -> {
            WorldGraveStore world = new WorldGraveStore();
            GraveService graves = new GraveService(h.getLevel().getServer(), world, () -> null, 2);
            UUID id = UUID.randomUUID();
            ServerPlayer p = TestPlayers.mockServerPlayer(h, id);
            graves.setLivePlayerLookup(java.util.Map.of(id, p)::get);
            CompoundTag extras = new CompoundTag();
            ListTag danks = new ListTag();
            CompoundTag dank = new CompoundTag();
            CompoundTag contents = new CompoundTag();
            ListTag items = new ListTag();
            CompoundTag inDank = machine(true, null).save(new CompoundTag());
            inDank.putInt("Slot", 0);
            items.add(inDank);
            contents.put("Items", items);
            dank.putInt("Frequency", 7);
            dank.put("Contents", contents);
            danks.add(dank);
            extras.put("Danks", danks);
            long t = System.currentTimeMillis();
            Grave g = new Grave(UUID.randomUUID(), id, "Alex", "frost", "minecraft:overworld", 10, 64, 10, false, t, t, 0, 0, 1, 0,
                    new GraveContents(List.of(new GraveContents.Entry("inv:0", machine(true, null))), extras).encode());
            world.addGrave(g).join();
            h.assertTrue(GraveContents.of(g).extras().getList("Danks", Tag.TAG_COMPOUND).getCompound(0).getCompound("Contents")
                    .getList("Items", Tag.TAG_COMPOUND).getCompound(0).getCompound("tag").getString(CompactMachines.HOME).equals("frost"),
                    "a machine in a dank's contents in the grave takes the grave's server");
            var r = done(com.mrgregles.bsp_inventorysync.grave.GraveActions.returnGrave(graves, id, "Alex", g.shortId(), "Mod"));
            h.assertTrue(r.returned() == 1, "returned: " + r);
            h.assertTrue(homeOf(p.getInventory().getItem(0)).equals("frost"), "the machine handed back by the real return path is frost's: " + p.getInventory().getItem(0).getTag());
        });
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void machines_in_curios_backpack_and_dank_data_are_marked(GameTestHelper h) {
        with(null, () -> {
            // the shapes the adapters save: Curios' handler tag, Sophisticated Backpacks' stored contents, Dank Storage's contents
            CompoundTag curioStack = machine(true, null).save(new CompoundTag());
            curioStack.putInt("Slot", 0);
            CompoundTag curios = new CompoundTag();
            ListTag curioList = new ListTag();
            CompoundTag ring = new CompoundTag();
            ring.putString("Identifier", "ring");
            CompoundTag stacks = new CompoundTag();
            ListTag ringItems = new ListTag();
            ringItems.add(curioStack);
            stacks.put("Items", ringItems);
            CompoundTag handler = new CompoundTag();
            handler.put("Stacks", stacks);
            ring.put("StacksHandler", handler);
            curioList.add(ring);
            curios.put("Curios", curioList);

            CompoundTag packSlot = machine(true, null).save(new CompoundTag());
            packSlot.putInt("Slot", 3);
            packSlot.putInt("realCount", 1);
            ListTag packItems = new ListTag();
            packItems.add(packSlot);
            CompoundTag inventory = new CompoundTag();
            inventory.put("Items", packItems);
            CompoundTag pack = new CompoundTag();
            pack.put("inventory", inventory);
            ListTag packs = new ListTag();
            packs.add(pack);
            CompoundTag backpacks = new CompoundTag();
            backpacks.put("Packs", packs);

            CompoundTag modules = new CompoundTag();
            modules.put("curios", curios);
            modules.put("backpacks", backpacks);
            CompoundTag root = new CompoundTag();
            root.put("Modules", modules);
            int marked = CompactMachines.markTree(root, HUB);
            List<String> homes = new ArrayList<>();
            collectHomes(root, homes);
            h.assertTrue(marked == 2 && homes.equals(List.of(HUB, HUB)), "the Curios and backpack machines are marked: " + marked + " " + homes);
        });
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void the_shrinking_devices_way_back_stays_on_its_server_by_default(GameTestHelper h) {
        List<? extends String> excluded = com.mrgregles.bsp_inventorysync.SyncConfig.EXCLUDE_CAPABILITIES.getDefault();
        h.assertTrue(excluded.contains("compactmachines:room_history") && excluded.contains("curios:inventory"), "default exclusions: " + excluded);
        h.succeed();
    }
}
