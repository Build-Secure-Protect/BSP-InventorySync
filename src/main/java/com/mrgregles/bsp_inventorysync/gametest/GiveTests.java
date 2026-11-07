package com.mrgregles.bsp_inventorysync.gametest;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.data.PlayerSnapshotCodec;
import com.mrgregles.bsp_inventorysync.give.Gifts;
import com.mrgregles.bsp_inventorysync.give.GiveService;
import com.mrgregles.bsp_inventorysync.give.WaitingMenu;
import com.mrgregles.bsp_inventorysync.storage.Give;
import com.mrgregles.bsp_inventorysync.storage.MemoryStore;
import com.mrgregles.bsp_inventorysync.storage.SaveCause;
import com.mrgregles.bsp_inventorysync.sync.ArrivalPolicy;
import com.mrgregles.bsp_inventorysync.sync.SyncService;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.UUID;

/**
 * {@code /invsync give} (D23) and the waiting-items popup (D24), with one in-memory store shared by
 * services that stand for two servers ("hub" and "pvp"): what fits goes in at once, the rest waits in the
 * store and is handed over once, on whichever server the player is; the popup's Deposit and Cancel; a Cancel
 * kept across a server switch and cleared by a join; and the refusals.
 */
@GameTestHolder(BSPInventorySync.MODID)
@PrefixGameTestTemplate(false)
public class GiveTests {
    private static final String EMPTY = "empty";
    private static final String HUB = "hub", PVP = "pvp";

    private static GiveService service(GameTestHelper h, MemoryStore store, String serverId) {
        return new GiveService(h.getLevel().getServer(), store, serverId, 5_000);
    }

    /** Fills every main slot (hotbar and the 27 above) with a full stack of the item. */
    private static void fill(ServerPlayer p, Item item) {
        for (int i = 0; i < 36; i++) {
            p.getInventory().setItem(i, new ItemStack(item, item.getMaxStackSize()));
        }
    }

    private static int count(ServerPlayer p, Item item) {
        return p.getInventory().countItem(item);
    }

    private static List<Give> waiting(MemoryStore store, UUID id) {
        return store.gives(List.of(id)).join();
    }

    @GameTest(template = EMPTY)
    public static void a_player_here_with_room_gets_it_all_at_once(GameTestHelper h) {
        MemoryStore store = new MemoryStore();
        GiveService hub = service(h, store, HUB);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        GiveService.Result r = hub.give(p, p.getUUID(), new ItemStack(Items.DIAMOND), 130, "Admin").join();
        h.assertTrue(r.handed() == 130 && r.waiting() == 0, "all handed over: " + r);
        h.assertTrue(count(p, Items.DIAMOND) == 130, "in the inventory: " + count(p, Items.DIAMOND));
        h.assertTrue(waiting(store, p.getUUID()).isEmpty(), "nothing waits");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_full_inventory_keeps_the_rest_waiting_and_creative_mode_never_deletes_it(GameTestHelper h) {
        MemoryStore store = new MemoryStore();
        GiveService hub = service(h, store, HUB);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        p.setGameMode(GameType.CREATIVE); // vanilla's Inventory.add deletes what does not fit in creative mode
        fill(p, Items.DIRT);
        p.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 60));
        GiveService.Result r = hub.give(p, p.getUUID(), new ItemStack(Items.DIAMOND), 70, "Admin").join();
        h.assertTrue(r.handed() == 4 && r.waiting() == 66, "4 fitted on the diamond stack, 66 wait: " + r);
        h.assertTrue(r.where().equals("waiting until they make room"), "where: " + r.where());
        List<Give> rows = waiting(store, p.getUUID());
        h.assertTrue(rows.size() == 2 && rows.stream().mapToInt(g -> GiveService.decode(g).getCount()).sum() == 66, "66 waiting in two rows: " + rows.size());
        h.assertTrue(p.containerMenu instanceof WaitingMenu w && w.rows().size() == 2, "the popup opened with both: " + p.containerMenu);
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void deposit_hands_over_what_fits_and_closes_when_everything_is_in(GameTestHelper h) {
        MemoryStore store = new MemoryStore();
        GiveService hub = service(h, store, HUB);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        fill(p, Items.DIRT);
        hub.give(p, p.getUUID(), new ItemStack(Items.EMERALD), 128, "Admin").join();
        WaitingMenu menu = (WaitingMenu) p.containerMenu;
        h.assertTrue(menu.rows().size() == 2, "two stacks wait");

        menu.clicked(WaitingMenu.DEPOSIT, 0, ClickType.PICKUP, p); // no room yet
        h.assertTrue(p.containerMenu == menu && menu.rows().size() == 2 && count(p, Items.EMERALD) == 0, "nothing moved without room");

        p.getInventory().setItem(5, ItemStack.EMPTY); // room for one stack
        menu.clicked(WaitingMenu.DEPOSIT, 0, ClickType.PICKUP, p);
        h.assertTrue(count(p, Items.EMERALD) == 64 && menu.rows().size() == 1 && p.containerMenu == menu, "one stack in, the popup stays with the other");
        h.assertTrue(waiting(store, p.getUUID()).size() == 1, "and one row left in the store");

        p.getInventory().setItem(6, ItemStack.EMPTY);
        menu.clicked(WaitingMenu.DEPOSIT, 0, ClickType.PICKUP, p);
        h.assertTrue(count(p, Items.EMERALD) == 128 && waiting(store, p.getUUID()).isEmpty(), "everything in, nothing waits");
        h.assertTrue(p.containerMenu == p.inventoryMenu && !hub.declined(p.getUUID()), "closed, and not a Cancel");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void the_popup_shows_only_pictures_but_the_players_own_inventory_works(GameTestHelper h) {
        MemoryStore store = new MemoryStore();
        GiveService hub = service(h, store, HUB);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        fill(p, Items.DIRT);
        hub.give(p, p.getUUID(), new ItemStack(Items.EMERALD), 10, "Admin").join();
        WaitingMenu menu = (WaitingMenu) p.containerMenu;
        menu.clicked(0, 0, ClickType.PICKUP, p); // the waiting emeralds
        h.assertTrue(menu.getCarried().isEmpty() && menu.getSlot(0).getItem().is(Items.EMERALD), "a waiting stack cannot be picked up");
        int ownFirst = WaitingMenu.SLOTS; // the player's own main inventory starts after the popup's 27 slots
        menu.clicked(ownFirst, 0, ClickType.QUICK_MOVE, p);
        h.assertTrue(menu.getSlot(ownFirst).getItem().is(Items.DIRT) && menu.getSlot(1).getItem().isEmpty(), "shift-click moves nothing into the popup");
        menu.clicked(ownFirst, 0, ClickType.PICKUP, p);
        h.assertTrue(menu.getCarried().is(Items.DIRT) && menu.getSlot(ownFirst).getItem().isEmpty(), "their own dirt is picked up");
        menu.clicked(-999, 0, ClickType.PICKUP, p); // dropped outside the screen
        h.assertTrue(menu.getCarried().isEmpty(), "and dropped: room made");
        menu.clicked(WaitingMenu.DEPOSIT, 0, ClickType.PICKUP, p);
        h.assertTrue(count(p, Items.EMERALD) == 10 && p.containerMenu == p.inventoryMenu, "then Deposit puts the emeralds in");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void cancel_waits_for_the_next_join_and_a_server_switch_is_not_one(GameTestHelper h) {
        MemoryStore store = new MemoryStore();
        GiveService hub = service(h, store, HUB);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        fill(p, Items.DIRT);
        hub.give(p, p.getUUID(), new ItemStack(Items.EMERALD), 5, "Admin").join();
        WaitingMenu menu = (WaitingMenu) p.containerMenu;
        menu.clicked(WaitingMenu.CANCEL, 0, ClickType.PICKUP, p);
        h.assertTrue(p.containerMenu == p.inventoryMenu && hub.declined(p.getUUID()), "closed and put off");
        h.assertTrue(store.extra(GiveService.DECLINED, p.getUUID()).join().isPresent(), "the Cancel is in the store, for every server");
        p.getInventory().setItem(3, ItemStack.EMPTY);
        hub.check(p).join();
        h.assertTrue(count(p, Items.EMERALD) == 0 && p.containerMenu == p.inventoryMenu, "nothing handed over or asked while put off, even with room");

        GiveService pvp = service(h, store, PVP);
        ServerPlayer there = TestPlayers.mockServerPlayer(h, p.getUUID());
        fill(there, Items.DIRT);
        pvp.arrived(there, false).join(); // a switch through BSP-Teleport
        h.assertTrue(pvp.declined(there.getUUID()) && there.containerMenu == there.inventoryMenu, "a switch keeps the Cancel");

        ServerPlayer later = TestPlayers.mockServerPlayer(h, p.getUUID());
        fill(later, Items.DIRT);
        pvp.loggedOut(there.getUUID());
        pvp.arrived(later, true).join(); // their next join
        h.assertTrue(!pvp.declined(later.getUUID()) && store.extra(GiveService.DECLINED, later.getUUID()).join().isEmpty(), "a join clears it");
        h.assertTrue(later.containerMenu instanceof WaitingMenu, "and asks again");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void escape_counts_as_cancel_but_a_logout_does_not(GameTestHelper h) {
        MemoryStore store = new MemoryStore();
        GiveService hub = service(h, store, HUB);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        fill(p, Items.DIRT);
        hub.give(p, p.getUUID(), new ItemStack(Items.EMERALD), 5, "Admin").join();
        p.closeContainer(); // Esc
        h.assertTrue(hub.declined(p.getUUID()), "Esc is a Cancel");

        ServerPlayer q = TestPlayers.mockServerPlayer(h);
        fill(q, Items.DIRT);
        hub.give(q, q.getUUID(), new ItemStack(Items.EMERALD), 5, "Admin").join();
        h.assertTrue(q.containerMenu instanceof WaitingMenu, "asked");
        q.disconnect(); // logged out with the popup open
        q.closeContainer();
        h.assertTrue(!hub.declined(q.getUUID()) && store.extra(GiveService.DECLINED, q.getUUID()).join().isEmpty(), "a logout is not a Cancel");
        h.assertTrue(waiting(store, q.getUUID()).size() == 1, "and the items still wait");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_player_on_another_server_gets_it_from_that_server_once(GameTestHelper h) {
        MemoryStore store = new MemoryStore();
        GiveService hub = service(h, store, HUB);
        GiveService pvp = service(h, store, PVP);
        UUID id = UUID.randomUUID();
        store.setPresence(id, PVP, true).join();
        GiveService.Result r = hub.give(null, id, new ItemStack(Items.GOLDEN_APPLE), 20, "Console").join();
        h.assertTrue(r.handed() == 0 && r.waiting() == 20 && r.where().equals("on the way to pvp"), "queued for pvp: " + r);

        ServerPlayer there = TestPlayers.mockServerPlayer(h, id);
        List<Give> rows = waiting(store, id);
        pvp.check(there).join();
        h.assertTrue(count(there, Items.GOLDEN_APPLE) == 20 && waiting(store, id).isEmpty(), "handed over on pvp");
        pvp.handOver(there, rows).join(); // the same rows again, as a second server racing for them would
        h.assertTrue(count(there, Items.GOLDEN_APPLE) == 20, "never twice: " + count(there, Items.GOLDEN_APPLE));

        UUID offline = UUID.randomUUID();
        GiveService.Result o = hub.give(null, offline, new ItemStack(Items.BREAD), 3, "Console").join();
        h.assertTrue(o.where().equals("waiting for their next login") && waiting(store, offline).size() == 1, "an offline player's waits: " + o);
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void the_arrival_hands_over_after_the_restore_and_tells_a_join_from_a_switch(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        MemoryStore store = new MemoryStore();
        ArrivalPolicy fast = new ArrivalPolicy(1_000, false);
        SyncService hubSync = new SyncService(server, store, HUB, fast);
        SyncService pvpSync = new SyncService(server, store, PVP, fast);
        GiveService pvp = service(h, store, PVP);
        pvpSync.setGives(pvp);
        UUID id = UUID.randomUUID();

        ServerPlayer onHub = TestPlayers.mockServerPlayer(h, id);
        hubSync.onLogin(onHub, 0);
        hubSync.tick(0);
        hubSync.beginTransfer(onHub, PVP);
        hubSync.onLogout(onHub).join(); // saved as a transfer
        store.addGive(id, encode(new ItemStack(Items.DIAMOND, 7)), "Admin").join();
        store.putExtra(GiveService.DECLINED, id, new byte[] {1}).join(); // they had cancelled on hub

        ServerPlayer arrived = TestPlayers.mockServerPlayer(h, id);
        pvpSync.onLogin(arrived, 1_000);
        pvpSync.tick(1_000);
        h.assertTrue(!SyncService.isSyncing(id) && pvp.declined(id) && count(arrived, Items.DIAMOND) == 0, "a switch: still put off, nothing handed over");
        pvpSync.onLogout(arrived).join(); // a real logout this time
        pvp.loggedOut(id);

        ServerPlayer joined = TestPlayers.mockServerPlayer(h, id);
        pvpSync.onLogin(joined, 2_000);
        pvpSync.tick(2_000);
        h.assertTrue(!pvp.declined(id) && count(joined, Items.DIAMOND) == 7 && waiting(store, id).isEmpty(), "a join: handed over after the restore");
        pvpSync.onLogout(joined).join();
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void totems_and_more_than_a_hundred_stacks_are_refused(GameTestHelper h) {
        Item totem = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse("bsp_core:shatter_totem"));
        h.assertTrue(totem != null && totem != Items.AIR, "BSP-Core's totem is registered in this run");
        h.assertTrue(Gifts.refusal(new ItemStack(totem), 1) != null, "a Shatter Totem is refused");
        h.assertTrue(Gifts.refusal(new ItemStack(Items.DIAMOND), 6_400) == null, "100 stacks of diamonds are fine");
        h.assertTrue(Gifts.refusal(new ItemStack(Items.DIAMOND), 6_401) != null, "one more is refused");
        h.assertTrue(Gifts.refusal(new ItemStack(Items.DIAMOND_SWORD), 101) != null, "unstackables: 100 at most");
        h.succeed();
    }

    /** Decides a call on the store: return null to let the in-memory store answer as usual. */
    interface Hook {
        @javax.annotation.Nullable
        Object answer(String method, Object[] args, java.util.function.Supplier<Object> real);
    }

    /** The in-memory store behind a hook, to make one call fail or answer late. */
    static com.mrgregles.bsp_inventorysync.storage.SyncStore hooked(MemoryStore memory, Hook hook) {
        return (com.mrgregles.bsp_inventorysync.storage.SyncStore) java.lang.reflect.Proxy.newProxyInstance(
                com.mrgregles.bsp_inventorysync.storage.SyncStore.class.getClassLoader(), new Class<?>[] {com.mrgregles.bsp_inventorysync.storage.SyncStore.class},
                (proxy, method, args) -> {
                    java.util.function.Supplier<Object> real = () -> {
                        try {
                            return method.invoke(memory, args);
                        } catch (ReflectiveOperationException e) {
                            throw new IllegalStateException(e.getCause() != null ? e.getCause() : e);
                        }
                    };
                    Object answer = hook.answer(method.getName(), args, real);
                    return answer != null ? answer : real.get();
                });
    }

    private static GiveService service(GameTestHelper h, com.mrgregles.bsp_inventorysync.storage.SyncStore store, String serverId) {
        return new GiveService(h.getLevel().getServer(), store, serverId, 5_000);
    }

    @GameTest(template = EMPTY)
    public static void a_failed_removal_never_loses_the_other_stacks_or_leaves_the_player_stuck(GameTestHelper h) {
        MemoryStore memory = new MemoryStore();
        java.util.concurrent.atomic.AtomicBoolean failOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
        GiveService hub = service(h, hooked(memory, (m, args, real) -> m.equals("takeGive") && failOnce.getAndSet(false)
                ? java.util.concurrent.CompletableFuture.failedFuture(new java.sql.SQLException("test: the database dropped the call")) : null), HUB);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        memory.addGive(p.getUUID(), GiveService.encode(new ItemStack(Items.DIAMOND, 64)), "Admin").join();
        memory.addGive(p.getUUID(), GiveService.encode(new ItemStack(Items.EMERALD, 64)), "Admin").join();
        java.util.concurrent.CompletableFuture<List<Give>> f = hub.handOver(p, waiting(memory, p.getUUID()));
        h.assertTrue(f.isDone() && !f.isCompletedExceptionally(), "the hand-over finished");
        h.assertTrue(count(p, Items.EMERALD) == 64, "the stack whose removal worked was handed over: " + count(p, Items.EMERALD));
        h.assertTrue(count(p, Items.DIAMOND) == 0 && waiting(memory, p.getUUID()).size() == 1, "the failed one still waits");
        hub.check(p).join();
        h.assertTrue(count(p, Items.DIAMOND) == 64 && waiting(memory, p.getUUID()).isEmpty(), "and is handed over at the next check: the player is not stuck");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_player_who_leaves_during_a_hand_over_gets_it_next_time(GameTestHelper h) {
        MemoryStore memory = new MemoryStore();
        java.util.concurrent.CompletableFuture<Void> gate = new java.util.concurrent.CompletableFuture<>();
        GiveService hub = service(h, hooked(memory, (m, args, real) -> m.equals("takeGive")
                ? gate.thenCompose(v -> (java.util.concurrent.CompletableFuture<?>) real.get()) : null), HUB);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        memory.addGive(p.getUUID(), GiveService.encode(new ItemStack(Items.DIAMOND, 30)), "Admin").join();
        java.util.concurrent.CompletableFuture<List<Give>> f = hub.handOver(p, waiting(memory, p.getUUID()));
        p.disconnect(); // logged out (and saved) while the store was answering
        gate.complete(null);
        h.assertTrue(f.isDone() && count(p, Items.DIAMOND) == 0, "nothing put into the copy that was already saved");
        List<Give> back = waiting(memory, p.getUUID());
        h.assertTrue(back.size() == 1 && GiveService.decode(back.get(0)).getCount() == 30, "it waits again: " + back.size());
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_cancel_survives_a_reload_of_the_sync(GameTestHelper h) {
        MemoryStore memory = new MemoryStore();
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        fill(p, Items.DIRT);
        memory.addGive(p.getUUID(), GiveService.encode(new ItemStack(Items.EMERALD, 5)), "Admin").join();
        memory.putExtra(GiveService.DECLINED, p.getUUID(), new byte[] {1}).join(); // cancelled before the reload
        GiveService fresh = service(h, memory, HUB); // what /invsync reload starts, knowing nobody
        fresh.check(p).join();
        h.assertTrue(fresh.declined(p.getUUID()) && p.containerMenu == p.inventoryMenu, "not asked again: the Cancel was read first");

        ServerPlayer q = TestPlayers.mockServerPlayer(h);
        fill(q, Items.DIRT);
        memory.addGive(q.getUUID(), GiveService.encode(new ItemStack(Items.EMERALD, 5)), "Admin").join();
        fresh.check(q).join();
        h.assertTrue(q.containerMenu instanceof WaitingMenu, "someone who never cancelled is asked");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_partly_stored_give_says_how_many_to_give_again(GameTestHelper h) {
        MemoryStore memory = new MemoryStore();
        java.util.concurrent.atomic.AtomicInteger adds = new java.util.concurrent.atomic.AtomicInteger();
        GiveService hub = service(h, hooked(memory, (m, args, real) -> m.equals("addGive") && adds.incrementAndGet() == 2
                ? java.util.concurrent.CompletableFuture.failedFuture(new java.sql.SQLException("test: the second row failed")) : null), HUB);
        UUID offline = UUID.randomUUID();
        GiveService.Result r = hub.give(null, offline, new ItemStack(Items.EMERALD), 128, "Admin").join();
        h.assertTrue(r.waiting() == 64 && r.failed() == 64, "64 stored, 64 to give again: " + r);
        h.assertTrue(waiting(memory, offline).size() == 1, "one row stored");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_failed_cancel_read_is_tried_again_before_anything_is_handed_over(GameTestHelper h) {
        MemoryStore memory = new MemoryStore();
        java.util.concurrent.atomic.AtomicBoolean failOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
        GiveService pvp = service(h, hooked(memory, (m, args, real) -> m.equals("extra") && failOnce.getAndSet(false)
                ? java.util.concurrent.CompletableFuture.failedFuture(new java.sql.SQLException("test: read failed")) : null), PVP);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        fill(p, Items.DIRT);
        memory.addGive(p.getUUID(), GiveService.encode(new ItemStack(Items.EMERALD, 5)), "Admin").join();
        memory.putExtra(GiveService.DECLINED, p.getUUID(), new byte[] {1}).join(); // cancelled on the server they came from
        pvp.arrived(p, false).join(); // a switch, whose read fails
        h.assertTrue(pvp.declined(p.getUUID()) && p.containerMenu == p.inventoryMenu, "read again before asking: still put off");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void closing_during_a_deposit_decides_on_what_is_left(GameTestHelper h) {
        MemoryStore memory = new MemoryStore();
        java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<Void>> gate = new java.util.concurrent.atomic.AtomicReference<>();
        GiveService hub = service(h, hooked(memory, (m, args, real) -> m.equals("takeGive") && gate.get() != null
                ? gate.get().thenCompose(v -> (java.util.concurrent.CompletableFuture<?>) real.get()) : null), HUB);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        fill(p, Items.DIRT);
        hub.give(p, p.getUUID(), new ItemStack(Items.EMERALD), 128, "Admin").join();
        WaitingMenu menu = (WaitingMenu) p.containerMenu;
        p.getInventory().setItem(5, ItemStack.EMPTY);
        p.getInventory().setItem(6, ItemStack.EMPTY); // room for everything
        gate.set(new java.util.concurrent.CompletableFuture<>());
        menu.clicked(WaitingMenu.DEPOSIT, 0, ClickType.PICKUP, p); // answered late
        p.closeContainer(); // Esc while it is answered
        h.assertTrue(!hub.declined(p.getUUID()), "nothing decided yet");
        gate.get().complete(null);
        h.assertTrue(count(p, Items.EMERALD) == 128 && !hub.declined(p.getUUID()), "everything went in, so it was no Cancel");

        ServerPlayer q = TestPlayers.mockServerPlayer(h);
        fill(q, Items.DIRT);
        gate.set(null);
        hub.give(q, q.getUUID(), new ItemStack(Items.EMERALD), 128, "Admin").join();
        WaitingMenu second = (WaitingMenu) q.containerMenu;
        q.getInventory().setItem(5, ItemStack.EMPTY); // room for one of the two
        gate.set(new java.util.concurrent.CompletableFuture<>());
        second.clicked(WaitingMenu.DEPOSIT, 0, ClickType.PICKUP, q);
        q.closeContainer();
        gate.get().complete(null);
        h.assertTrue(count(q, Items.EMERALD) == 64 && hub.declined(q.getUUID()), "one went in, the other is put off");
        h.succeed();
    }

    private static byte[] encode(ItemStack s) {
        return GiveService.encode(s);
    }
}
