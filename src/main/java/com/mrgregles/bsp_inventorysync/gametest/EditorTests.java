package com.mrgregles.bsp_inventorysync.gametest;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.compat.BspCore;
import com.mrgregles.bsp_inventorysync.editor.EditorActions;
import com.mrgregles.bsp_inventorysync.editor.EditorMenu;
import com.mrgregles.bsp_inventorysync.editor.EditorSession;
import com.mrgregles.bsp_inventorysync.editor.ItemCatalog;
import com.mrgregles.bsp_inventorysync.storage.MemoryStore;
import com.mrgregles.bsp_inventorysync.storage.SaveCause;
import com.mrgregles.bsp_inventorysync.storage.Snapshot;
import com.mrgregles.bsp_inventorysync.sync.SyncService;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

/**
 * The admin inventory editor (D21, layout C of D22), against mock players: the working copy never
 * touches the player before Confirm; the admin's own inventory stays locked and is never handed the
 * cursor; the item list never offers a totem and never replaces an item taken from the player; armour
 * slots take only armour; Confirm refuses a player whose inventory changed meanwhile; and a confirmed
 * edit pins the state before it and saves itself as an admin snapshot.
 */
@GameTestHolder(BSPInventorySync.MODID)
@PrefixGameTestTemplate(false)
public class EditorTests {
    private static final String EMPTY = "empty";
    private static final int TOOLBAR = EditorSession.PAGE;
    private static final List<ItemCatalog.Category> TEST_LIST = List.of(new ItemCatalog.Category("Test", List.of(new ItemStack(Items.DIAMOND), new ItemStack(Items.OAK_PLANKS), new ItemStack(Items.DIAMOND_HELMET))));

    private static ServerPlayer target(GameTestHelper h) {
        ServerPlayer t = TestPlayers.mockServerPlayer(h);
        t.getInventory().setItem(0, new ItemStack(Items.DIAMOND_SWORD)); // hotbar 0: Inventory tab slot 27
        t.getInventory().setItem(9, new ItemStack(Items.STONE, 10)); // main 9: Inventory tab slot 0
        return t;
    }

    private static int count(ServerPlayer p, net.minecraft.world.item.Item item) {
        int n = p.getInventory().countItem(item);
        for (int i = 0; i < p.getEnderChestInventory().getContainerSize(); i++) {
            ItemStack s = p.getEnderChestInventory().getItem(i);
            n += s.is(item) ? s.getCount() : 0;
        }
        return n;
    }

    private static String text(List<Component> lines) {
        return String.join(" | ", lines.stream().map(Component::getString).toList());
    }

    @GameTest(template = EMPTY)
    public static void items_from_the_list_reach_the_player_only_on_confirm(GameTestHelper h) {
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        ServerPlayer t = target(h);
        EditorSession s = new EditorSession(t, TEST_LIST);
        s.click(TOOLBAR + 2, 0, false); // Items tab
        s.click(0, 0, false); // a full stack of diamonds on the cursor
        h.assertTrue(s.cursor().is(Items.DIAMOND) && s.cursor().getCount() == 64, "a full stack: " + s.cursor());
        s.click(TOOLBAR, 0, false); // Inventory tab: the cursor stays
        h.assertTrue(s.cursor().getCount() == 64, "the cursor survives the tab switch");
        s.click(1, 0, false); // into main slot 10
        s.click(TOOLBAR + 2, 0, false);
        s.click(1, 1, false); // one oak plank
        s.click(TOOLBAR, 0, false);
        s.click(2, 0, false);
        h.assertTrue(count(t, Items.DIAMOND) == 0, "nothing reaches the player before Confirm");
        s.click(TOOLBAR + 8, 0, false); // Save
        h.assertTrue(s.tab() == EditorSession.Tab.CONFIRM && s.diff().describe().equals("+64 Diamond, +1 Oak Planks"), "the summary: " + s.diff().describe());
        h.assertTrue(s.display(1).is(Items.DIAMOND) && s.display(2).is(Items.OAK_PLANKS), "shown in the Added row");

        List<Component> reply = EditorActions.confirm(admin, s, t, null);
        h.assertTrue(text(reply).startsWith("Changed " + t.getGameProfile().getName() + "'s inventory: +64 Diamond, +1 Oak Planks"), "the reply: " + text(reply));
        h.assertTrue(t.getInventory().getItem(10).is(Items.DIAMOND) && t.getInventory().getItem(10).getCount() == 64 && t.getInventory().getItem(11).is(Items.OAK_PLANKS), "written to the player");
        h.assertTrue(t.getInventory().getItem(0).is(Items.DIAMOND_SWORD) && t.getInventory().getItem(9).getCount() == 10, "the rest untouched");
        h.assertTrue(count(admin, Items.DIAMOND) == 0, "the admin got nothing");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void the_admins_own_inventory_is_locked_and_never_receives_the_cursor(GameTestHelper h) {
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        admin.getInventory().setItem(0, new ItemStack(Items.BREAD, 5));
        ServerPlayer t = target(h);
        EditorSession s = new EditorSession(t, TEST_LIST);
        EditorMenu menu = new EditorMenu(1, admin, s, () -> { });
        menu.clicked(27, 0, ClickType.PICKUP, admin); // the player's sword onto the cursor
        h.assertTrue(menu.getCarried().is(Items.DIAMOND_SWORD) && s.cursor().is(Items.DIAMOND_SWORD), "picked up in the editor");
        int ownBread = 54 + 27; // the admin's hotbar slot 0 in a 6-row chest
        menu.clicked(ownBread, 0, ClickType.PICKUP, admin);
        menu.clicked(ownBread, 0, ClickType.QUICK_MOVE, admin);
        menu.clicked(0, 0, ClickType.SWAP, admin); // number key 1 over a top slot
        menu.clicked(0, 0, ClickType.THROW, admin);
        menu.clicked(-999, 0, ClickType.PICKUP, admin); // a click outside the window
        h.assertTrue(admin.getInventory().getItem(0).is(Items.BREAD) && admin.getInventory().getItem(0).getCount() == 5, "the admin's own slots never change");
        h.assertTrue(s.cursor().is(Items.DIAMOND_SWORD), "and the cursor is still the sword");
        menu.removed(admin); // the admin closes the screen holding the sword
        h.assertTrue(count(admin, Items.DIAMOND_SWORD) == 0 && menu.getCarried().isEmpty(), "the cursor is dropped, not handed to the admin");
        h.assertTrue(t.getInventory().getItem(0).is(Items.DIAMOND_SWORD), "and the player still has it: nothing was confirmed");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void the_list_never_replaces_an_item_taken_from_the_player(GameTestHelper h) {
        ServerPlayer t = target(h);
        EditorSession s = new EditorSession(t, TEST_LIST);
        s.click(27, 0, false); // the sword
        s.click(TOOLBAR + 2, 0, false);
        s.click(0, 0, false); // the diamond entry, while holding the sword
        h.assertTrue(s.cursor().is(Items.DIAMOND_SWORD), "the sword is still on the cursor: " + s.cursor());
        s.click(0, 0, true); // shift: a stack of diamonds straight into the inventory
        h.assertTrue(s.inventory().stream().anyMatch(i -> i.is(Items.DIAMOND) && i.getCount() == 64), "shift-click added a full stack");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void armour_slots_take_only_what_can_be_worn_there(GameTestHelper h) {
        ServerPlayer t = target(h);
        EditorSession s = new EditorSession(t, TEST_LIST);
        h.assertTrue(s.display(36).is(Items.GRAY_STAINED_GLASS_PANE) && s.display(36).getHoverName().getString().equals("Helmet"), "an empty helmet slot is a named pane");
        s.click(0, 0, false); // stone onto the cursor
        s.click(36, 0, false); // the helmet slot
        h.assertTrue(s.inventory().get(39).isEmpty() && s.cursor().is(Items.STONE), "stone is refused");
        s.click(TOOLBAR + 6, 0, false); // trash the stone
        s.click(TOOLBAR + 2, 0, false);
        s.click(2, 0, false); // a diamond helmet
        s.click(TOOLBAR, 0, false);
        s.click(39, 0, false); // the boots slot
        h.assertTrue(s.inventory().get(36).isEmpty(), "a helmet is refused as boots");
        s.click(36, 0, false);
        h.assertTrue(s.inventory().get(39).is(Items.DIAMOND_HELMET) && s.cursor().isEmpty(), "and worn as a helmet");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void an_admin_may_trash_a_players_totem_but_the_list_never_offers_one(GameTestHelper h) {
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        ServerPlayer t = target(h);
        t.getInventory().setItem(2, TotemRuleTests.totem(h)); // hotbar 2: Inventory tab slot 29
        EditorSession s = new EditorSession(t, ItemCatalog.categories(h.getLevel().getServer()));
        s.click(29, 0, false);
        h.assertTrue(BspCore.isLiveTotem(s.cursor()), "the totem is on the cursor");
        s.click(TOOLBAR + 6, 0, false); // Trash
        s.click(TOOLBAR + 8, 0, false); // Save
        h.assertTrue(s.diff().removed().size() == 1 && BspCore.isLiveTotem(s.diff().removed().get(0)), "the summary lists the totem as removed");
        EditorActions.confirm(admin, s, t, null);
        h.assertTrue(TotemRuleTests.liveTotems(t) == 0, "the player no longer has it");

        h.assertTrue(!ItemCatalog.offered(TotemRuleTests.totem(h)) && !ItemCatalog.offered(TotemRuleTests.spentTotem(h)), "the list never offers a totem, live or spent");
        boolean anyTotem = ItemCatalog.categories(h.getLevel().getServer()).stream().flatMap(c -> c.items().stream())
                .anyMatch(i -> "bsp_core:shatter_totem".equals(String.valueOf(ForgeRegistries.ITEMS.getKey(i.getItem()))));
        h.assertTrue(!anyTotem, "in no creative tab");
        h.assertTrue(ItemCatalog.search("totem").items().stream().noneMatch(i -> "bsp_core:shatter_totem".equals(String.valueOf(ForgeRegistries.ITEMS.getKey(i.getItem())))), "nor in a search");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void confirm_refuses_a_player_whose_inventory_changed_meanwhile(GameTestHelper h) {
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        ServerPlayer t = target(h);
        EditorSession s = new EditorSession(t, TEST_LIST);
        s.click(27, 0, false);
        s.click(TOOLBAR + 6, 0, false); // trash the sword
        t.getInventory().setItem(5, new ItemStack(Items.APPLE, 3)); // the player picked something up meanwhile
        List<Component> reply = EditorActions.confirm(admin, s, t, null);
        h.assertTrue(text(reply).contains("changed while you edited; nothing was changed"), "refused: " + text(reply));
        h.assertTrue(t.getInventory().getItem(0).is(Items.DIAMOND_SWORD) && t.getInventory().getItem(5).is(Items.APPLE), "the player keeps both");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_confirmed_edit_pins_the_state_before_and_saves_itself(GameTestHelper h) {
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        ServerPlayer t = target(h);
        MemoryStore store = new MemoryStore();
        SyncService sync = new SyncService(h.getLevel().getServer(), store, "hub");
        EditorSession s = new EditorSession(t, TEST_LIST);
        s.click(0, 0, false); // the stone
        s.click(TOOLBAR + 6, 0, false); // trashed
        EditorActions.confirm(admin, s, t, sync);
        List<Snapshot> history = store.history(t.getUUID()).join();
        h.assertTrue(history.size() == 2 && history.stream().allMatch(x -> x.cause() == SaveCause.ADMIN), "two admin snapshots: " + history);
        Snapshot after = history.get(0);
        Snapshot before = history.get(1);
        h.assertTrue(before.pinned() && !after.pinned(), "the state before the edit is pinned");
        h.assertTrue(t.getInventory().countItem(Items.STONE) == 0, "the stone is gone");
        h.succeed();
    }

    // ------------------------------------------------------------------ offline players (ED-2)

    /** A player's snapshot v1 saved by "pvp": stone and a sword, diamonds in the ender chest, and an item of a mod this server lacks. */
    private static EditorActions.Opened savedPlayer(GameTestHelper h, MemoryStore store, java.util.UUID id) {
        ServerPlayer p = TestPlayers.mockServerPlayer(h, id);
        p.getInventory().setItem(0, new ItemStack(Items.DIAMOND_SWORD));
        p.getInventory().setItem(9, new ItemStack(Items.STONE, 10));
        p.getEnderChestInventory().setItem(0, new ItemStack(Items.DIAMOND, 5));
        p.setHealth(13f);
        net.minecraft.nbt.CompoundTag tag = com.mrgregles.bsp_inventorysync.data.PlayerSnapshotCodec.capture(p);
        net.minecraft.nbt.CompoundTag widget = new net.minecraft.nbt.CompoundTag();
        widget.putString("id", "nosuchmod:widget");
        widget.putByte("Count", (byte) 1);
        net.minecraft.nbt.CompoundTag entry = new net.minecraft.nbt.CompoundTag();
        entry.putInt("Slot", 20);
        entry.put("Item", widget);
        com.mrgregles.bsp_inventorysync.data.PlayerSnapshotCodec.module(tag, "inventory").getList("Items", net.minecraft.nbt.Tag.TAG_COMPOUND).add(entry);
        Snapshot v1 = store.save(id, "pvp", SaveCause.LOGOUT, com.mrgregles.bsp_inventorysync.data.PlayerSnapshotCodec.encode(tag)).join();
        return new EditorActions.Opened(v1, tag);
    }

    @GameTest(template = EMPTY)
    public static void an_offline_edit_becomes_their_newest_snapshot_and_keeps_everything_else(GameTestHelper h) {
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        MemoryStore store = new MemoryStore();
        SyncService sync = new SyncService(h.getLevel().getServer(), store, "hub");
        java.util.UUID id = java.util.UUID.randomUUID();
        EditorActions.Opened opened = savedPlayer(h, store, id);
        EditorSession s = EditorActions.offlineSession(id, "Alex", opened.tag(), TEST_LIST);
        h.assertTrue(s.display(0).is(Items.STONE) && s.display(27).is(Items.DIAMOND_SWORD), "the snapshot's inventory is shown");
        s.click(0, 0, false); // the stone
        s.click(TOOLBAR + 6, 0, false); // trashed
        List<Component> reply = EditorActions.confirmOffline(admin, s, opened, sync).join();
        h.assertTrue(text(reply).startsWith("Changed Alex's saved inventory: -10 Stone"), "the reply: " + text(reply));

        Snapshot v2 = store.latest(id).join().orElseThrow();
        h.assertTrue(v2.version() == 2 && v2.cause() == SaveCause.ADMIN && v2.serverId().equals("hub"), "saved as v2, admin, by this server: " + v2);
        net.minecraft.nbt.CompoundTag after = com.mrgregles.bsp_inventorysync.data.PlayerSnapshotCodec.decode(v2.data());
        net.minecraft.nbt.ListTag items = com.mrgregles.bsp_inventorysync.data.PlayerSnapshotCodec.module(after, "inventory").getList("Items", net.minecraft.nbt.Tag.TAG_COMPOUND);
        List<String> ids = items.stream().map(t -> ((net.minecraft.nbt.CompoundTag) t).getCompound("Item").getString("id")).toList();
        h.assertTrue(!ids.contains("minecraft:stone") && ids.contains("minecraft:diamond_sword"), "the stone is gone, the sword stays: " + ids);
        h.assertTrue(ids.contains("nosuchmod:widget"), "the missing mod's item went back as the original, not as a placeholder: " + ids);
        h.assertTrue(com.mrgregles.bsp_inventorysync.data.PlayerSnapshotCodec.module(after, "ender_chest").getList("Items", net.minecraft.nbt.Tag.TAG_COMPOUND).size() == 1, "the ender chest kept");
        h.assertTrue(com.mrgregles.bsp_inventorysync.data.PlayerSnapshotCodec.module(after, "vitals").equals(com.mrgregles.bsp_inventorysync.data.PlayerSnapshotCodec.module(opened.tag(), "vitals")), "every other module untouched");
        h.assertTrue(store.snapshot(id, 1).join().orElseThrow().pinned(), "the snapshot it started from is pinned");
        h.assertTrue(store.presence(id).join().map(pr -> !pr.online()).orElse(true), "the checkout was released");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void an_offline_edit_is_refused_if_they_came_online_or_their_data_changed(GameTestHelper h) {
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        MemoryStore store = new MemoryStore();
        SyncService sync = new SyncService(h.getLevel().getServer(), store, "hub");
        java.util.UUID id = java.util.UUID.randomUUID();
        EditorActions.Opened opened = savedPlayer(h, store, id);
        EditorSession s = EditorActions.offlineSession(id, "Alex", opened.tag(), TEST_LIST);
        s.click(0, 0, false);
        s.click(TOOLBAR + 6, 0, false);

        store.setPresence(id, "pvp", true).join(); // they logged in on pvp meanwhile
        h.assertTrue(text(EditorActions.confirmOffline(admin, s, opened, sync).join()).contains("came online on pvp while you edited"), "refused while online");
        h.assertTrue(store.latest(id).join().orElseThrow().version() == 1, "nothing saved");

        store.save(id, "pvp", SaveCause.LOGOUT, opened.snapshot().data()).join(); // they played and left: v2
        store.setPresence(id, "pvp", false).join();
        h.assertTrue(text(EditorActions.confirmOffline(admin, s, opened, sync).join()).contains("changed while you edited (now v2, you opened v1)"), "refused after a newer save");
        h.assertTrue(store.latest(id).join().orElseThrow().version() == 2 && store.latest(id).join().orElseThrow().cause() == SaveCause.LOGOUT, "their own v2 stands");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void the_editor_will_not_open_on_a_player_on_another_server_or_without_data(GameTestHelper h) {
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        MemoryStore store = new MemoryStore();
        SyncService sync = new SyncService(h.getLevel().getServer(), store, "hub");
        java.util.UUID id = java.util.UUID.randomUUID();
        savedPlayer(h, store, id);
        store.setPresence(id, "pvp", true).join();
        h.assertTrue("Alex is on pvp; edit them there.".equals(EditorActions.openOffline(admin, id, "Alex", null, sync).join()), "on another server");
        h.assertTrue("Ghost has no saved data to edit.".equals(EditorActions.openOffline(admin, java.util.UUID.randomUUID(), "Ghost", null, sync).join()), "no data");
        h.assertTrue(EditorActions.openOffline(admin, id, "Alex", null, null).join().contains("needs the sync on"), "sync off");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void search_finds_items_by_name_or_id(GameTestHelper h) {
        ItemCatalog.Category found = ItemCatalog.search("diamond");
        h.assertTrue(found.items().stream().anyMatch(i -> i.is(Items.DIAMOND)) && found.items().stream().anyMatch(i -> i.is(Items.DIAMOND_SWORD)), "diamond items found");
        h.assertTrue(found.items().stream().noneMatch(i -> i.is(Items.STONE)), "and nothing else");
        h.assertTrue(!ItemCatalog.categories(h.getLevel().getServer()).isEmpty(), "the creative tabs are built on the server");
        h.succeed();
    }
}
