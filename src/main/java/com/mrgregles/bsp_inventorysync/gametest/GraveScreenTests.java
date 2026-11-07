package com.mrgregles.bsp_inventorysync.gametest;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.grave.GraveAccess;
import com.mrgregles.bsp_inventorysync.grave.GraveContents;
import com.mrgregles.bsp_inventorysync.grave.GraveMenu;
import com.mrgregles.bsp_inventorysync.grave.GraveRegistry;
import com.mrgregles.bsp_inventorysync.grave.GraveService;
import com.mrgregles.bsp_inventorysync.grave.WorldGraveStore;
import com.mrgregles.bsp_inventorysync.storage.Grave;
import com.mrgregles.bsp_inventorysync.storage.MemoryStore;
import com.mrgregles.bsp_inventorysync.sync.SyncService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The grave screen (D25 2b, 3a, 23a, 24a, task GR-3): who may open a grave, the layout, taking one stack and Take all
 * (back into the slots they came from, the rest left in the grave), the XP to the first opener, the last stack removing
 * the grave and its block, two screens never taking the same stack, a player who leaves while the store answers, a
 * grave still being saved, an empty grave's block, a right-click a claim refused, backpack contents chosen for the
 * stacks taken, and walking away.
 */
@GameTestHolder(BSPInventorySync.MODID)
@PrefixGameTestTemplate(false)
public class GraveScreenTests {
    private static final String EMPTY = "empty";
    private static final String HUB = "hub";

    private static GraveService localService(GameTestHelper h) {
        GraveService g = new GraveService(h.getLevel().getServer(), new WorldGraveStore(), () -> null, 2);
        g.setPrivateMs(60 * 60_000L);
        return g;
    }

    /** The player dies at the relative spot with these stacks in these inventory slots; returns the grave. */
    private static Grave die(GameTestHelper h, GraveService graves, ServerPlayer p, int x, int z, Map<Integer, ItemStack> slots) {
        h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
        BlockPos abs = h.absolutePos(new BlockPos(x, 1, z));
        p.moveTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5);
        p.getInventory().clearContent();
        slots.forEach((i, s) -> p.getInventory().setItem(i, s.copy()));
        graves.recordLayout(p, h.getLevel().damageSources().fall());
        List<ItemEntity> drops = new ArrayList<>();
        slots.values().forEach(s -> drops.add(new ItemEntity(h.getLevel(), p.getX(), p.getY(), p.getZ(), s.copy())));
        p.getInventory().clearContent();
        Grave g = graves.onDrops(p, h.getLevel().damageSources().fall(), drops, System.currentTimeMillis());
        h.assertTrue(g != null && g.placed(), "a grave");
        return g;
    }

    private static GraveMenu open(GameTestHelper h, GraveService graves, ServerPlayer viewer, Grave g) {
        CompletableFuture<GraveMenu> f = graves.access().open(viewer, g.id(), false, h.getLevel().dimension(), new BlockPos(g.x(), g.y(), g.z()));
        h.assertTrue(f.isDone(), "answered at once (the world's own store)");
        return f.join();
    }

    private static int slotOf(GraveMenu m, ItemStack like) {
        for (int i = 0; i < GraveMenu.ITEMS; i++) {
            int e = m.entryAt(i);
            if (e >= 0 && m.contents().entries().get(e).stack().is(like.getItem())) {
                return i;
            }
        }
        return -1;
    }

    private static void fillMain(ServerPlayer p) {
        for (int i = 0; i < 36; i++) {
            p.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
        }
    }

    @GameTest(template = EMPTY)
    public static void the_owner_opens_at_once_and_everyone_else_after_the_hour(GameTestHelper h) {
        GraveService graves = localService(h);
        ServerPlayer owner = TestPlayers.mockServerPlayer(h), other = TestPlayers.mockServerPlayer(h);
        Grave g = die(h, graves, owner, 3, 3, Map.of(0, new ItemStack(Items.DIAMOND, 5)));
        other.moveTo(owner.getX(), owner.getY(), owner.getZ());
        h.assertTrue(open(h, graves, other, g) == null, "someone else waits the hour");
        h.assertTrue(other.containerMenu == other.inventoryMenu, "no screen for them");
        GraveMenu mine = open(h, graves, owner, g);
        h.assertTrue(mine != null && owner.containerMenu == mine, "the owner opens it at once");
        owner.closeContainer();
        graves.setPrivateMs(0);
        h.assertTrue(open(h, graves, other, g) != null, "anyone once it is public");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void the_screen_lays_the_grave_out_like_the_inventory(GameTestHelper h) {
        GraveService graves = localService(h);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        Grave g = die(h, graves, p, 3, 3, Map.of(12, new ItemStack(Items.BREAD, 3), 2, new ItemStack(Items.IRON_SWORD), 39, new ItemStack(Items.IRON_HELMET),
                36, new ItemStack(Items.IRON_BOOTS), 40, new ItemStack(Items.SHIELD)));
        GraveMenu m = open(h, graves, p, g);
        h.assertTrue(slotOf(m, new ItemStack(Items.BREAD)) == 3, "main slot 12 shows in the main rows: " + slotOf(m, new ItemStack(Items.BREAD)));
        h.assertTrue(slotOf(m, new ItemStack(Items.IRON_SWORD)) == 29, "hotbar slot 2 in the hotbar row: " + slotOf(m, new ItemStack(Items.IRON_SWORD)));
        h.assertTrue(slotOf(m, new ItemStack(Items.IRON_HELMET)) == 36 && slotOf(m, new ItemStack(Items.IRON_BOOTS)) == 39 && slotOf(m, new ItemStack(Items.SHIELD)) == 40,
                "helmet, boots and offhand in the armour row");
        h.assertTrue(m.getSlot(GraveMenu.TAKE_ALL).getItem().is(Items.LIME_CONCRETE) && m.getSlot(GraveMenu.CLOSE).getItem().is(Items.RED_CONCRETE)
                && m.getSlot(GraveMenu.INFO).getItem().is(Items.PAPER), "Take all, Close and the info paper");
        List<GraveContents.Entry> many = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            many.add(new GraveContents.Entry("", new ItemStack(Items.COBBLESTONE)));
        }
        many.add(new GraveContents.Entry("inv:0", new ItemStack(Items.STICK)));
        Grave big = new Grave(UUID.randomUUID(), p.getUUID(), "x", "", "minecraft:overworld", 0, 0, 0, false, 0, 0, 0, 0, many.size(), 0,
                new GraveContents(many, new CompoundTag()).encode());
        graves.local().addGrave(big).join();
        GraveMenu bigMenu = graves.access().open(p, big.id(), false, null, null).join();
        h.assertTrue(bigMenu.pages() == 2 && bigMenu.entryAt(27) == 50, "51 stacks make two pages, the stick still at its hotbar place: " + bigMenu.pages());
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void clicking_a_stack_takes_it_to_its_slot_and_the_last_one_removes_the_grave_and_its_block(GameTestHelper h) {
        GraveService graves = localService(h);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        Grave g = die(h, graves, p, 3, 3, Map.of(4, new ItemStack(Items.GOLDEN_APPLE, 2), 20, new ItemStack(Items.EMERALD, 7)));
        BlockPos at = new BlockPos(g.x(), g.y(), g.z());
        GraveMenu m = open(h, graves, p, g);
        m.clicked(slotOf(m, new ItemStack(Items.GOLDEN_APPLE)), 0, ClickType.PICKUP, p);
        h.assertTrue(p.getInventory().getItem(4).is(Items.GOLDEN_APPLE) && p.getInventory().getItem(4).getCount() == 2, "the apples are back in hotbar slot 4");
        Grave after = graves.local().grave(g.id()).join().orElseThrow();
        h.assertTrue(after.stacks() == 1 && m.contents().stacks() == 1, "the grave holds one stack now, and the screen shows it");
        h.assertTrue(p.containerMenu.getCarried().isEmpty(), "nothing on the cursor");
        m.clicked(slotOf(m, new ItemStack(Items.EMERALD)), 0, ClickType.QUICK_MOVE, p);
        h.assertTrue(p.getInventory().getItem(20).is(Items.EMERALD), "the emeralds back in slot 20");
        h.assertTrue(graves.local().grave(g.id()).join().map(Grave::empty).orElse(true), "the grave is empty (kept as a marker only)");
        h.assertTrue(h.getLevel().getBlockState(at).isAir(), "and its block");
        h.assertTrue(p.containerMenu == p.inventoryMenu, "the screen closed");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void take_all_puts_things_back_where_they_were_and_leaves_what_does_not_fit(GameTestHelper h) {
        GraveService graves = localService(h);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        Grave g = die(h, graves, p, 3, 3, Map.of(39, new ItemStack(Items.DIAMOND_HELMET), 0, new ItemStack(Items.DIAMOND_SWORD),
                5, new ItemStack(Items.DIAMOND, 64), 6, new ItemStack(Items.GOLD_INGOT, 64)));
        fillMain(p);
        p.getInventory().setItem(0, ItemStack.EMPTY); // one free slot: the sword's own
        p.setGameMode(net.minecraft.world.level.GameType.CREATIVE); // where vanilla's insert would delete what does not fit
        GraveMenu m = open(h, graves, p, g);
        m.clicked(GraveMenu.TAKE_ALL, 0, ClickType.PICKUP, p);
        h.assertTrue(p.getInventory().getItem(39).is(Items.DIAMOND_HELMET), "the helmet is worn again");
        h.assertTrue(p.getInventory().getItem(0).is(Items.DIAMOND_SWORD), "the sword in its hotbar slot");
        Grave left = graves.local().grave(g.id()).join().orElseThrow();
        List<String> kept = GraveContents.decode(left.data()).entries().stream().map(e -> e.stack().getItem().toString() + "x" + e.stack().getCount()).toList();
        h.assertTrue(left.stacks() == 2 && kept.contains("diamondx64") && kept.contains("gold_ingotx64"), "the diamonds and gold stay in the grave: " + kept);
        h.assertTrue(p.containerMenu == m && m.contents().stacks() == 2, "the screen stays open with the rest");
        p.getInventory().setItem(10, ItemStack.EMPTY);
        p.getInventory().setItem(11, new ItemStack(Items.DIAMOND, 54));
        m.clicked(slotOf(m, new ItemStack(Items.DIAMOND)), 0, ClickType.PICKUP, p);
        Grave split = graves.local().grave(g.id()).join().orElseThrow();
        int diamondsLeft = GraveContents.decode(split.data()).entries().stream().filter(e -> e.stack().is(Items.DIAMOND)).mapToInt(e -> e.stack().getCount()).sum();
        h.assertTrue(p.getInventory().countItem(Items.DIAMOND) == 54 + 64 && diamondsLeft == 0, "with room for 74 the 64 go in, beside the 54 held: " + p.getInventory().countItem(Items.DIAMOND) + ", left " + diamondsLeft);
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void part_of_a_stack_is_taken_when_only_part_fits(GameTestHelper h) {
        GraveService graves = localService(h);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        Grave g = die(h, graves, p, 3, 3, Map.of(7, new ItemStack(Items.DIAMOND, 64)));
        fillMain(p);
        p.getInventory().setItem(3, new ItemStack(Items.DIAMOND, 54)); // room for 10, and the home slot 7 is full of dirt
        GraveMenu m = open(h, graves, p, g);
        m.clicked(slotOf(m, new ItemStack(Items.DIAMOND)), 0, ClickType.PICKUP, p);
        Grave left = graves.local().grave(g.id()).join().orElseThrow();
        int inGrave = GraveContents.decode(left.data()).entries().get(0).stack().getCount();
        h.assertTrue(p.getInventory().getItem(3).getCount() == 64 && inGrave == 54, "10 taken, 54 stay: " + p.getInventory().getItem(3).getCount() + " / " + inGrave);
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void the_first_to_open_gets_the_xp_and_nobody_after(GameTestHelper h) {
        GraveService graves = localService(h);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        h.setBlock(new BlockPos(3, 0, 3), Blocks.STONE);
        BlockPos abs = h.absolutePos(new BlockPos(3, 1, 3));
        p.moveTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5);
        graves.recordLayout(p, h.getLevel().damageSources().fall());
        graves.holdXp(p, 35);
        Grave g = graves.onDrops(p, h.getLevel().damageSources().fall(),
                new ArrayList<>(List.of(new ItemEntity(h.getLevel(), p.getX(), p.getY(), p.getZ(), new ItemStack(Items.COAL)))), System.currentTimeMillis());
        int before = p.totalExperience;
        GraveMenu m = open(h, graves, p, g);
        h.assertTrue(p.totalExperience - before == 35, "the 35 points at the first opening: " + (p.totalExperience - before));
        h.assertTrue(graves.local().grave(g.id()).join().orElseThrow().xp() == 0 && m.grave().xp() == 0, "the grave holds none now");
        p.closeContainer();
        open(h, graves, p, g);
        h.assertTrue(p.totalExperience - before == 35, "and none at the second: " + (p.totalExperience - before));
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void two_screens_never_take_the_same_stack_twice(GameTestHelper h) {
        GraveService graves = localService(h);
        graves.setPrivateMs(0);
        ServerPlayer owner = TestPlayers.mockServerPlayer(h), other = TestPlayers.mockServerPlayer(h);
        Grave g = die(h, graves, owner, 3, 3, Map.of(1, new ItemStack(Items.DIAMOND, 10), 2, new ItemStack(Items.EMERALD, 10)));
        other.moveTo(owner.getX(), owner.getY(), owner.getZ());
        GraveMenu a = open(h, graves, owner, g), b = open(h, graves, other, g);
        int diamondSlot = slotOf(b, new ItemStack(Items.DIAMOND));
        a.clicked(slotOf(a, new ItemStack(Items.DIAMOND)), 0, ClickType.PICKUP, owner);
        h.assertTrue(b.entryAt(diamondSlot) < 0, "the other screen no longer shows the diamonds");
        b.clicked(diamondSlot, 0, ClickType.PICKUP, other);
        h.assertTrue(owner.getInventory().countItem(Items.DIAMOND) == 10 && other.getInventory().countItem(Items.DIAMOND) == 0, "ten diamonds in all");

        // a screen that missed a change (another server took from the grave): its click is refused and nothing is handed over
        Grave now = graves.local().grave(g.id()).join().orElseThrow();
        graves.local().updateGrave(now.withContents(now.stacks(), 0, now.data(), now.touchedAt())).join(); // someone else's write
        int emeraldSlot = slotOf(b, new ItemStack(Items.EMERALD));
        b.clicked(emeraldSlot, 0, ClickType.PICKUP, other);
        h.assertTrue(other.getInventory().countItem(Items.EMERALD) == 0, "the stale click took nothing");
        h.assertTrue(graves.local().grave(g.id()).join().orElseThrow().stacks() == 1, "the emeralds are still in the grave");
        b.clicked(emeraldSlot, 0, ClickType.PICKUP, other);
        h.assertTrue(other.getInventory().countItem(Items.EMERALD) == 10, "the screen was shown the grave again, and the next click takes them");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void nothing_can_be_put_into_a_grave(GameTestHelper h) {
        GraveService graves = localService(h);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        Grave g = die(h, graves, p, 3, 3, Map.of(0, new ItemStack(Items.COAL, 3)));
        p.getInventory().setItem(9, new ItemStack(Items.DIRT, 5));
        GraveMenu m = open(h, graves, p, g);
        int ownSlot = -1;
        for (int i = GraveMenu.SLOTS; i < m.slots.size(); i++) {
            if (m.getSlot(i).getItem().is(Items.DIRT)) {
                ownSlot = i;
            }
        }
        h.assertTrue(m.quickMoveStack(p, ownSlot).isEmpty() && m.getSlot(ownSlot).getItem().is(Items.DIRT), "shift-click from the inventory moves nothing in");
        h.assertTrue(!m.canDragTo(m.getSlot(10)) && m.canDragTo(m.getSlot(ownSlot)), "no dragging into the grave");
        m.setCarried(new ItemStack(Items.STONE, 4));
        m.clicked(10, 0, ClickType.PICKUP, p); // an empty place in the grave, with stone on the cursor
        h.assertTrue(m.getCarried().is(Items.STONE) && m.getCarried().getCount() == 4, "the stone stays on the cursor");
        h.assertTrue(graves.local().grave(g.id()).join().orElseThrow().stacks() == 1 && m.entryAt(10) < 0, "and the grave is unchanged");
        m.setCarried(ItemStack.EMPTY);
        h.succeed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void a_player_who_leaves_while_the_store_answers_finds_it_in_a_grave_of_theirs(GameTestHelper h) {
        MemoryStore memory = new MemoryStore();
        AtomicBoolean hold = new AtomicBoolean(false);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        SyncService sync = new SyncService(h.getLevel().getServer(), GiveTests.hooked(memory, (m, args, real) -> m.equals("updateGrave") && hold.get()
                ? gate.thenCompose(v -> (CompletableFuture<?>) real.get()) : null), HUB);
        GraveService graves = new GraveService(h.getLevel().getServer(), new WorldGraveStore(), () -> sync, 2);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        Grave g = die(h, graves, p, 3, 3, Map.of(0, new ItemStack(Items.DIAMOND, 9), 1, new ItemStack(Items.EMERALD, 9)));
        GraveMenu[] menu = new GraveMenu[1];
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(memory.graveCount() == 1 && graves.local().size() == 0, "the grave reached the database"))
                .thenExecute(() -> {
                    h.assertTrue(graves.storeFor(g.id()) != null, "it can be opened now");
                    menu[0] = graves.access().open(p, g.id(), false, null, null).join();
                    h.assertTrue(menu[0] != null, "opened");
                    hold.set(true);
                    menu[0].clicked(slotOf(menu[0], new ItemStack(Items.DIAMOND)), 0, ClickType.PICKUP, p);
                    h.assertTrue(menu[0].busy(), "the take is being written");
                    menu[0].clicked(slotOf(menu[0], new ItemStack(Items.EMERALD)), 0, ClickType.PICKUP, p);
                    h.assertTrue(p.getInventory().countItem(Items.EMERALD) == 0, "a click while it is written waits");
                    p.disconnect(); // gone before the database answers
                    hold.set(false);
                    gate.complete(null);
                })
                .thenWaitUntil(() -> {
                    int diamonds = memory.graves(p.getUUID()).join().stream().filter(x -> !x.id().equals(g.id()))
                            .flatMap(x -> GraveContents.decode(x.data()).entries().stream()).filter(e -> e.stack().is(Items.DIAMOND)).mapToInt(e -> e.stack().getCount()).sum();
                    h.assertTrue(diamonds == 9, "the diamonds wait in a new grave of theirs: " + diamonds);
                })
                .thenExecute(() -> h.assertTrue(p.getInventory().countItem(Items.DIAMOND) == 0, "and never into the gone player's inventory"))
                .thenSucceed();
    }

    @GameTest(template = EMPTY)
    public static void a_grave_still_being_saved_is_not_opened(GameTestHelper h) {
        MemoryStore memory = new MemoryStore();
        SyncService sync = new SyncService(h.getLevel().getServer(), GiveTests.hooked(memory, (m, args, real) -> m.equals("saveWithGraves")
                ? CompletableFuture.failedFuture(new java.sql.SQLException("test: the database is down")) : null), HUB);
        GraveService graves = new GraveService(h.getLevel().getServer(), new WorldGraveStore(), () -> sync, 2);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        Grave g = die(h, graves, p, 3, 3, Map.of(0, new ItemStack(Items.DIAMOND, 9)));
        h.assertTrue(graves.storeFor(g.id()) == null, "on its way, not in the database");
        h.assertTrue(graves.access().open(p, g.id(), false, null, null).join() == null && p.containerMenu == p.inventoryMenu, "refused until it is in the database");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void an_empty_graves_block_goes_on_a_right_click_and_a_claim_never_stops_one(GameTestHelper h) {
        GraveService graves = localService(h);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        BlockPos stray = h.absolutePos(new BlockPos(1, 1, 1));
        h.setBlock(new BlockPos(1, 0, 1), Blocks.STONE);
        GraveService.placeBlock(h.getLevel(), stray, Direction.NORTH, UUID.randomUUID(), p.getUUID(), "Gone", 0, 0); // its grave is in no store
        Grave g = die(h, graves, p, 5, 5, Map.of(0, new ItemStack(Items.COAL, 2)));
        GraveService before = GraveService.swapActive(graves);
        try {
            p.moveTo(stray.getX() + 0.5, stray.getY(), stray.getZ() + 0.5);
            PlayerInteractEvent.RightClickBlock click = new PlayerInteractEvent.RightClickBlock(p, InteractionHand.MAIN_HAND, stray,
                    new BlockHitResult(Vec3.atCenterOf(stray), Direction.UP, stray, false));
            MinecraftForge.EVENT_BUS.post(click);
            h.assertTrue(h.getLevel().getBlockState(stray).isAir(), "the block of a grave that is gone goes too");

            BlockPos at = new BlockPos(g.x(), g.y(), g.z());
            p.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
            PlayerInteractEvent.RightClickBlock claimed = new PlayerInteractEvent.RightClickBlock(p, InteractionHand.MAIN_HAND, at,
                    new BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false));
            claimed.setCanceled(true); // a claim or region refused the click first
            MinecraftForge.EVENT_BUS.post(claimed);
            h.assertTrue(p.containerMenu instanceof GraveMenu m && m.grave().id().equals(g.id()), "the grave opens anyway");
            h.assertTrue(claimed.isCanceled() && claimed.getCancellationResult() == net.minecraft.world.InteractionResult.SUCCESS, "and nothing else happens with the click");
            p.closeContainer();
        } finally {
            GraveService.swapActive(before);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void the_screen_closes_when_the_player_walks_away_from_the_grave(GameTestHelper h) {
        GraveService graves = localService(h);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        Grave g = die(h, graves, p, 3, 3, Map.of(0, new ItemStack(Items.COAL, 2)));
        GraveMenu m = open(h, graves, p, g);
        h.assertTrue(m.stillValid(p), "open beside it");
        p.moveTo(p.getX() + 12, p.getY(), p.getZ());
        h.assertTrue(!m.stillValid(p), "not 12 blocks away");
        h.assertTrue(GraveRegistry.GRAVE.get() != null, "the block is registered");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void backpacks_take_their_own_contents_and_the_ones_inside_them(GameTestHelper h) {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        ItemStack pack = new ItemStack(Items.CHEST);
        pack.getOrCreateTag().putUUID("contentsUuid", a);
        ListTag packs = new ListTag();
        CompoundTag inner = new CompoundTag();
        inner.putUUID("contentsUuid", b); // a backpack inside backpack a
        packs.add(entry(a, inner));
        packs.add(entry(b, new CompoundTag()));
        packs.add(entry(c, new CompoundTag())); // another grave stack's backpack, not taken
        ListTag chosen = invoke(List.of(pack), packs);
        List<UUID> ids = chosen.stream().map(t -> ((CompoundTag) t).getUUID("Uuid")).toList();
        h.assertTrue(ids.size() == 2 && ids.contains(a) && ids.contains(b), "a and the one inside it, not c: " + ids);
        h.succeed();
    }

    private static CompoundTag entry(UUID id, CompoundTag contents) {
        CompoundTag e = new CompoundTag();
        e.putUUID("Uuid", id);
        CompoundTag wrap = new CompoundTag();
        wrap.put("Inner", contents);
        e.put("Contents", wrap);
        return e;
    }

    private static ListTag invoke(List<ItemStack> stacks, ListTag packs) {
        try {
            java.lang.reflect.Method m = GraveAccess.class.getDeclaredMethod("packsFor", java.util.Collection.class, ListTag.class);
            m.setAccessible(true);
            return (ListTag) m.invoke(null, stacks, packs);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
