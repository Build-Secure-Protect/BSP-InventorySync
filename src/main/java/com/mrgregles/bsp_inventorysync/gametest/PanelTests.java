package com.mrgregles.bsp_inventorysync.gametest;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.api.Edit;
import com.mrgregles.bsp_inventorysync.api.EditResult;
import com.mrgregles.bsp_inventorysync.api.EditResult.Outcome;
import com.mrgregles.bsp_inventorysync.api.ItemArea;
import com.mrgregles.bsp_inventorysync.api.ItemPlace;
import com.mrgregles.bsp_inventorysync.api.PlayerItems;
import com.mrgregles.bsp_inventorysync.data.PlayerSnapshotCodec;
import com.mrgregles.bsp_inventorysync.panel.PanelService;
import com.mrgregles.bsp_inventorysync.storage.MemoryStore;
import com.mrgregles.bsp_inventorysync.storage.Presence;
import com.mrgregles.bsp_inventorysync.storage.SaveCause;
import com.mrgregles.bsp_inventorysync.storage.Snapshot;
import com.mrgregles.bsp_inventorysync.storage.SyncStore;
import com.mrgregles.bsp_inventorysync.sync.ArrivalPolicy;
import com.mrgregles.bsp_inventorysync.sync.OfflineWrite;
import com.mrgregles.bsp_inventorysync.sync.SyncService;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Server Utilities' request SU-IS2 (D29): the staff panel's read, edit and save-now, against the in-memory
 * store, with "hub" as this server and "pvp" as another. The memory store and the default codec answer at
 * once and the service runs its server-thread steps at once, so every future is done when the call returns.
 * Curios, Sophisticated Backpacks and Dank Storage are not loaded here: their parts are tested on snapshots
 * written by hand in each mod's own stored form (read from the pack's jars: Curios 5.14.1, Sophisticated
 * Core 1.5.12 on the servers and 1.5.24 in the pack folder, stored the same way; Dank Storage 16), and their live parts are untested in game (docs/TESTING.md).
 */
@GameTestHolder(BSPInventorySync.MODID)
@PrefixGameTestTemplate(false)
public class PanelTests {
    private static final String EMPTY = "empty";
    private static final String HUB = "hub";
    private static final String PVP = "pvp";

    private static final UUID B1 = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID B2 = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID B3 = UUID.fromString("00000000-0000-0000-0000-0000000000b3");
    private static final UUID B4 = UUID.fromString("00000000-0000-0000-0000-0000000000b4");

    /** A service for "hub": players on it are those in {@code online}. */
    private static PanelService panel(GameTestHelper h, SyncService sync, Map<UUID, ServerPlayer> online) {
        return new PanelService(h.getLevel().getServer(), sync, online::get, Runnable::run);
    }

    private static <T> T now(CompletableFuture<T> f) {
        if (!f.isDone()) {
            throw new AssertionError("the future is not done yet");
        }
        return f.join();
    }

    // ------------------------------------------------------------------ hand-written snapshots

    private static CompoundTag item(String id, int count) {
        CompoundTag c = new CompoundTag();
        c.putString("id", id);
        c.putByte("Count", (byte) count);
        return c;
    }

    private static CompoundTag backpackItem(UUID contents) {
        CompoundTag c = item("sophisticatedbackpacks:backpack", 1);
        CompoundTag tag = new CompoundTag();
        tag.putUUID("contentsUuid", contents);
        c.put("tag", tag);
        return c;
    }

    private static CompoundTag slot(int slot, CompoundTag item) {
        CompoundTag c = item.copy();
        c.putInt("Slot", slot);
        return c;
    }

    /** A backpack slot as Sophisticated Core writes every slot: with {@code realCount}. */
    private static CompoundTag packSlot(int slot, CompoundTag item, int count) {
        CompoundTag c = slot(slot, item);
        c.putInt("realCount", count);
        return c;
    }

    private static CompoundTag vanillaSlot(int slot, CompoundTag item) {
        CompoundTag c = new CompoundTag();
        c.putInt("Slot", slot);
        c.put("Item", item);
        return c;
    }

    private static CompoundTag pack(UUID uuid, CompoundTag... slots) {
        CompoundTag inventory = new CompoundTag();
        ListTag items = new ListTag();
        for (CompoundTag s : slots) {
            items.add(s);
        }
        inventory.put("Items", items);
        inventory.putInt("Size", 27);
        CompoundTag contents = new CompoundTag();
        contents.put("inventory", inventory);
        contents.put("upgradeInventory", new CompoundTag()); // never shown or touched
        CompoundTag entry = new CompoundTag();
        entry.putUUID("Uuid", uuid);
        entry.put("Contents", contents);
        return entry;
    }

    /**
     * Alex as saved by pvp: stone and a widget of a mod this server lacks in the inventory, a shulker box holding
     * backpack B4 in inventory slot 7, dank 5 in inventory slot 4, backpack B1 in the ender chest, a ring and
     * backpack B2 (holding B3) in Curios, 256 cobblestone in B1 (count above a byte), 1000 dirt in the dank.
     */
    private static CompoundTag alex(GameTestHelper h, UUID id) {
        ServerPlayer p = TestPlayers.mockServerPlayer(h, id);
        p.getInventory().setItem(9, new ItemStack(Items.STONE, 10));
        p.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 60));
        p.getInventory().setItem(39, new ItemStack(Items.IRON_HELMET));
        p.getEnderChestInventory().setItem(0, new ItemStack(Items.DIAMOND, 5));
        p.setHealth(13f);
        CompoundTag root = PlayerSnapshotCodec.capture(p);
        ListTag inv = PlayerSnapshotCodec.module(root, "inventory").getList("Items", Tag.TAG_COMPOUND);
        inv.add(vanillaSlot(20, item("nosuchmod:widget", 3)));
        CompoundTag shulker = item("minecraft:shulker_box", 1);
        CompoundTag bet = new CompoundTag();
        ListTag inside = new ListTag();
        inside.add(slot(0, backpackItem(B4)));
        bet.put("Items", inside);
        CompoundTag shulkerTag = new CompoundTag();
        shulkerTag.put("BlockEntityTag", bet);
        shulker.put("tag", shulkerTag);
        inv.add(vanillaSlot(7, shulker));
        CompoundTag dankItem = item("dankstorage:dank_1", 1);
        CompoundTag settings = new CompoundTag();
        settings.putInt("dankstorage:frequency", 5);
        CompoundTag dankTag = new CompoundTag();
        dankTag.put("settings", settings);
        dankItem.put("tag", dankTag);
        inv.add(vanillaSlot(4, dankItem));
        PlayerSnapshotCodec.module(root, "ender_chest").getList("Items", Tag.TAG_COMPOUND).add(vanillaSlot(2, backpackItem(B1)));

        CompoundTag modules = root.getCompound("Modules");
        // Curios: the module keeps Curios' writeTag() under "Curios"
        ListTag curios = new ListTag();
        curios.add(curiosType("ring", true, slot(0, item("minecraft:gold_ingot", 1))));
        curios.add(curiosType("back", false, slot(0, backpackItem(B2))));
        CompoundTag written = new CompoundTag();
        written.put("Curios", curios);
        CompoundTag curiosModule = new CompoundTag();
        curiosModule.put("Curios", written);
        modules.put("curios", curiosModule);
        // Sophisticated Backpacks
        ListTag packs = new ListTag();
        packs.add(pack(B1, packSlot(0, item("minecraft:cobblestone", 256), 256), packSlot(1, item("minecraft:torch", 5), 5)));
        packs.add(pack(B2, packSlot(3, backpackItem(B3), 1)));
        packs.add(pack(B3, packSlot(0, item("minecraft:apple", 2), 2)));
        packs.add(pack(B4));
        CompoundTag backpacks = new CompoundTag();
        backpacks.put("Packs", packs);
        modules.put("backpacks", backpacks);
        // Dank Storage
        CompoundTag dirt = slot(0, item("minecraft:dirt", 1000));
        dirt.putInt("ExtendedCount", 1000);
        CompoundTag dankContents = new CompoundTag();
        ListTag dankItems = new ListTag();
        dankItems.add(dirt);
        dankContents.put("Items", dankItems);
        ListTag ghosts = new ListTag();
        ghosts.add(slot(1, item("minecraft:stone", 1)));
        dankContents.put("GhostItems", ghosts);
        dankContents.putString("DankStats", "four"); // 36 slots; 16384 a slot with the servers' config
        CompoundTag dank = new CompoundTag();
        dank.putInt("Frequency", 5);
        dank.put("Contents", dankContents);
        ListTag danks = new ListTag();
        danks.add(dank);
        CompoundTag dankModule = new CompoundTag();
        dankModule.put("Danks", danks);
        modules.put("dank", dankModule);
        return root;
    }

    private static CompoundTag curiosType(String type, boolean cosmetic, CompoundTag... stacks) {
        CompoundTag handler = new CompoundTag();
        ListTag items = new ListTag();
        for (CompoundTag s : stacks) {
            items.add(s);
        }
        CompoundTag main = new CompoundTag();
        main.put("Items", items);
        main.putInt("Size", 2);
        handler.put("Stacks", main);
        CompoundTag cos = new CompoundTag();
        cos.put("Items", new ListTag());
        cos.putInt("Size", 2);
        handler.put("Cosmetics", cos);
        handler.putBoolean("HasCosmetic", cosmetic);
        handler.putInt("SavedBaseSize", 2);
        CompoundTag entry = new CompoundTag();
        entry.putString("Identifier", type);
        entry.put("StacksHandler", handler);
        return entry;
    }

    /** Saves Alex as pvp did at logout (v1), offline. */
    private static Snapshot savedAlex(GameTestHelper h, MemoryStore store, UUID id) {
        Snapshot v1 = store.save(id, PVP, SaveCause.LOGOUT, PlayerSnapshotCodec.encode(alex(h, id))).join();
        store.setPresence(id, PVP, true).join();
        store.setPresence(id, PVP, false).join();
        return v1;
    }

    private static CompoundTag moduleOf(Snapshot s, String id) {
        return PlayerSnapshotCodec.module(PlayerSnapshotCodec.decode(s.data()), id);
    }

    private static CompoundTag entryAt(ListTag list, int slot) {
        for (Tag t : list) {
            if (((CompoundTag) t).getInt("Slot") == slot) {
                return (CompoundTag) t;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ the checkout (SU2-1)

    @GameTest(template = EMPTY)
    public static void the_checkout_is_taken_only_when_no_other_server_holds_it(GameTestHelper h) {
        MemoryStore store = new MemoryStore();
        UUID id = UUID.randomUUID();
        Presence first = store.claim(id, HUB).join();
        h.assertTrue(first.online() && first.serverId().equals(HUB), "an unknown player is taken: " + first);
        h.assertTrue(store.claim(id, HUB).join().serverId().equals(HUB), "taken again by the same server");
        store.setPresence(id, HUB, false).join();
        store.setPresence(id, PVP, true).join();
        Presence refused = store.claim(id, HUB).join();
        h.assertTrue(refused.online() && refused.serverId().equals(PVP), "held by pvp: the claim says so and changes nothing: " + refused);
        h.assertTrue(store.presence(id).join().orElseThrow().serverId().equals(PVP), "pvp still holds them");
        store.setPresence(id, PVP, false).join();
        h.assertTrue(store.claim(id, HUB).join().serverId().equals(HUB), "free again: taken");
        h.succeed();
    }

    /**
     * The race the claim closes: the arrival's poll saw the player free, then an offline change on pvp took the
     * checkout before the arrival took it. Before D29 the arrival took it anyway and read the snapshot from
     * before the change (which the change then lost to the player's next save). Now it waits for pvp's release
     * and arrives with the change.
     */
    @GameTest(template = EMPTY)
    public static void an_arrival_that_loses_the_checkout_to_an_offline_change_waits_and_gets_the_change(GameTestHelper h) {
        MemoryStore store = new MemoryStore();
        UUID id = UUID.randomUUID();
        ServerPlayer before = TestPlayers.mockServerPlayer(h, id);
        before.getInventory().setItem(0, new ItemStack(Items.STONE, 10));
        store.save(id, PVP, SaveCause.LOGOUT, PlayerSnapshotCodec.encode(PlayerSnapshotCodec.capture(before))).join();
        store.setPresence(id, PVP, true).join(); // pvp's offline change holds the checkout
        boolean[] stale = {true}; // the first poll still sees the player free
        SyncStore racing = (SyncStore) java.lang.reflect.Proxy.newProxyInstance(SyncStore.class.getClassLoader(), new Class<?>[] {SyncStore.class}, (proxy, m, args) -> {
            if (m.getName().equals("presence") && stale[0]) {
                stale[0] = false;
                return CompletableFuture.completedFuture(Optional.empty());
            }
            try {
                return m.invoke(store, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        });
        SyncService hub = new SyncService(h.getLevel().getServer(), racing, HUB, new ArrivalPolicy(60_000, false));
        ServerPlayer p = TestPlayers.mockServerPlayer(h, id);
        hub.onLogin(p, 0);
        hub.tick(1);
        h.assertTrue(SyncService.isSyncing(id), "still waiting: pvp holds the checkout");
        h.assertTrue(store.presence(id).join().orElseThrow().serverId().equals(PVP), "pvp's checkout was not taken over");
        hub.tick(2);
        h.assertTrue(SyncService.isSyncing(id), "still waiting while pvp writes");
        ServerPlayer edited = TestPlayers.mockServerPlayer(h, id);
        edited.getInventory().setItem(0, new ItemStack(Items.STONE, 4));
        store.save(id, PVP, SaveCause.ADMIN, PlayerSnapshotCodec.encode(PlayerSnapshotCodec.capture(edited))).join(); // the change: v2
        store.setPresence(id, PVP, false).join(); // released
        hub.tick(3);
        h.assertTrue(!SyncService.isSyncing(id), "arrived after the release");
        h.assertTrue(p.getInventory().getItem(0).getCount() == 4, "with the change (v2), not the snapshot before it: " + p.getInventory().getItem(0));
        h.assertTrue(store.presence(id).join().orElseThrow().serverId().equals(HUB), "hub holds them now");
        hub.onLogout(p);
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void an_arrival_here_waits_while_this_server_writes_an_offline_change(GameTestHelper h) {
        MemoryStore store = new MemoryStore();
        SyncService hub = new SyncService(h.getLevel().getServer(), store, HUB, new ArrivalPolicy(60_000, false));
        UUID id = UUID.randomUUID();
        h.assertTrue(hub.tryBeginWrite(id), "the write starts");
        ServerPlayer p = TestPlayers.mockServerPlayer(h, id);
        hub.onLogin(p, 0);
        hub.tick(1);
        h.assertTrue(SyncService.isSyncing(id) && hub.saving(id), "waits for the write");
        hub.endWrite(id);
        hub.tick(2);
        h.assertTrue(!SyncService.isSyncing(id), "arrives once it is written");
        hub.onLogout(p);
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void the_write_hold_ends_only_after_the_release_has_landed(GameTestHelper h) {
        MemoryStore store = new MemoryStore();
        CompletableFuture<Void> release = new CompletableFuture<>();
        SyncStore slowRelease = (SyncStore) java.lang.reflect.Proxy.newProxyInstance(SyncStore.class.getClassLoader(), new Class<?>[] {SyncStore.class}, (proxy, m, args) -> {
            if (m.getName().equals("setPresence") && Boolean.FALSE.equals(args[2])) {
                return release.thenCompose(v -> store.setPresence((UUID) args[0], (String) args[1], false));
            }
            try {
                return m.invoke(store, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        });
        SyncService hub = new SyncService(h.getLevel().getServer(), slowRelease, HUB);
        UUID id = UUID.randomUUID();
        CompletableFuture<String> out = OfflineWrite.run(hub, id, () -> CompletableFuture.completedFuture("busy"), held -> CompletableFuture.completedFuture("elsewhere"), () -> CompletableFuture.completedFuture("written"));
        h.assertTrue("busy".equals(now(OfflineWrite.run(hub, id, () -> CompletableFuture.completedFuture("busy"), held -> CompletableFuture.completedFuture("elsewhere"),
                () -> CompletableFuture.completedFuture("written")))), "a second change of the same player on this server is busy");
        h.assertTrue(!out.isDone() && hub.saving(id), "the answer and the end of the hold wait for the release");
        h.assertTrue(store.presence(id).join().orElseThrow().online(), "still held while the release is on its way");
        release.complete(null);
        h.assertTrue(out.isDone() && "written".equals(out.join()) && !hub.saving(id), "then both");
        h.assertTrue(!store.presence(id).join().orElseThrow().online(), "released");

        store.setPresence(id, PVP, true).join();
        CompletableFuture<String> elsewhere = OfflineWrite.run(hub, id, () -> CompletableFuture.completedFuture("busy"), held -> CompletableFuture.completedFuture("on " + held.serverId()), () -> CompletableFuture.completedFuture("written"));
        h.assertTrue("on pvp".equals(now(elsewhere)) && !hub.saving(id), "held by pvp: nothing written, nothing released");
        h.assertTrue(store.presence(id).join().orElseThrow().serverId().equals(PVP), "pvp's checkout untouched");
        h.succeed();
    }

    // ------------------------------------------------------------------ reading (SU2-2)

    @GameTest(template = EMPTY)
    public static void the_panel_reads_an_offline_players_snapshot_with_curios_backpacks_and_danks(GameTestHelper h) {
        MemoryStore store = new MemoryStore();
        SyncService hub = new SyncService(h.getLevel().getServer(), store, HUB);
        UUID id = UUID.randomUUID();
        Snapshot v1 = savedAlex(h, store, id);
        PlayerItems items = now(panel(h, hub, Map.of()).read(id)).orElseThrow();
        h.assertTrue(!items.online() && !items.live() && items.version() == v1.version() && items.serverId().equals(PVP) && items.savedAt() == v1.savedAt(),
                "offline, from v1, last on pvp: " + items.online() + " " + items.live() + " v" + items.version() + " " + items.serverId());
        List<String> ids = items.areas().stream().map(ItemArea::id).toList();
        h.assertTrue(ids.equals(List.of("inventory", "armor", "offhand", "cursor", "ender_chest", "curios/ring", "curios_cosmetic/ring", "curios/back",
                "backpack/" + B1, "backpack/" + B2, "backpack/" + B3, "backpack/" + B4, "dank/5")), "areas in order: " + ids);
        ItemArea inv = items.area("inventory").orElseThrow();
        h.assertTrue(inv.size() == 36 && inv.slot(9).stack().is(Items.STONE) && inv.slot(9).stack().getCount() == 10 && inv.addable(), "inventory");
        h.assertTrue("nosuchmod:widget".equals(inv.slot(20).missingItem()) && inv.slot(20).stack().getCount() == 3, "a missing mod's item is named: " + inv.slot(20));
        h.assertTrue(items.area("armor").orElseThrow().slot(3).stack().is(Items.IRON_HELMET) && !items.area("armor").orElseThrow().addable(), "armour, head in slot 3, remove only");
        h.assertTrue(items.area("ender_chest").orElseThrow().size() == 27 && items.area("ender_chest").orElseThrow().slot(0).stack().getCount() == 5, "ender chest");
        h.assertTrue(items.area("curios/ring").orElseThrow().slot(0).stack().is(Items.GOLD_INGOT) && items.area("curios/ring").orElseThrow().size() == 2
                && items.area("curios/ring").orElseThrow().kind() == ItemArea.Kind.CURIOS && items.area("curios/ring").orElseThrow().key().equals("ring"), "a Curios ring");
        ItemArea b1 = items.area("backpack/" + B1).orElseThrow();
        h.assertTrue(b1.size() == 27 && b1.slot(0).stack().is(Items.COBBLESTONE) && b1.slot(0).stack().getCount() == 256, "256 cobblestone, above a byte: " + b1.slot(0));
        h.assertTrue(items.area("dank/5").orElseThrow().size() == 36 && items.area("dank/5").orElseThrow().slot(0).stack().getCount() == 1000, "a tier-four dank with 1000 dirt");
        h.assertTrue(!b1.addable() && b1.removable() && !items.area("dank/5").orElseThrow().addable(), "in a snapshot, backpacks and danks only give items up");
        h.assertTrue(new ItemPlace("ender_chest", 2, List.of()).equals(b1.place()), "B1 in the ender chest: " + b1.place());
        h.assertTrue(new ItemPlace("curios/back", 0, List.of()).equals(items.area("backpack/" + B2).orElseThrow().place()), "B2 on the back");
        h.assertTrue(new ItemPlace("backpack/" + B2, 3, List.of()).equals(items.area("backpack/" + B3).orElseThrow().place()), "B3 inside B2");
        h.assertTrue(new ItemPlace("inventory", 7, List.of("minecraft:shulker_box")).equals(items.area("backpack/" + B4).orElseThrow().place()),
                "B4 inside a shulker box: " + items.area("backpack/" + B4).orElseThrow().place());
        h.assertTrue(new ItemPlace("inventory", 4, List.of()).equals(items.area("dank/5").orElseThrow().place()), "the dank in the inventory");
        h.assertTrue(now(panel(h, hub, Map.of()).read(UUID.randomUUID())).isEmpty(), "nobody with no data: empty");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_player_on_another_server_is_read_from_their_snapshot_and_named_there(GameTestHelper h) {
        MemoryStore store = new MemoryStore();
        SyncService hub = new SyncService(h.getLevel().getServer(), store, HUB);
        UUID id = UUID.randomUUID();
        store.save(id, "spawn", SaveCause.LOGOUT, PlayerSnapshotCodec.encode(alex(h, id))).join(); // saved by spawn
        store.setPresence(id, PVP, true).join(); // now held by pvp
        PlayerItems items = now(panel(h, hub, Map.of()).read(id)).orElseThrow();
        h.assertTrue(items.online() && !items.live() && items.serverId().equals(PVP) && items.version() == 1, "online on pvp (not spawn, which saved it), read from v1: " + items.serverId());
        h.assertTrue(now(panel(h, null, Map.of()).read(id)).isEmpty(), "sync off and not here: nothing to read");
        h.succeed();
    }

    // ------------------------------------------------------------------ offline changes (SU2-3)

    @GameTest(template = EMPTY)
    public static void an_offline_change_is_saved_as_an_admin_snapshot_with_the_old_version_pinned(GameTestHelper h) {
        MemoryStore store = new MemoryStore();
        SyncService hub = new SyncService(h.getLevel().getServer(), store, HUB);
        UUID id = UUID.randomUUID();
        Snapshot v1 = savedAlex(h, store, id);
        PanelService panel = panel(h, hub, Map.of());
        EditResult r = now(panel.edit(id, 1, Edit.remove("backpack/" + B1, 0, new ItemStack(Items.COBBLESTONE), 6), "Mod"));
        h.assertTrue(r.ok() && r.version() == 2 && r.pinnedVersion() == 1 && r.serverId().equals(HUB), "done: " + r);
        Snapshot v2 = store.latest(id).join().orElseThrow();
        h.assertTrue(v2.version() == 2 && v2.cause() == SaveCause.ADMIN && v2.serverId().equals(PVP),
                "an admin snapshot under pvp, the server of the snapshot it changes, so pvp's dank frequencies are renumbered anywhere else: " + v2);
        h.assertTrue("panel: Mod".equals(PlayerSnapshotCodec.note(PlayerSnapshotCodec.decode(v2.data()))), "noted with who made it");
        h.assertTrue(store.snapshot(id, 1).join().orElseThrow().pinned(), "the old version is pinned");
        Presence checkout = store.presence(id).join().orElseThrow();
        h.assertTrue(!checkout.online() && checkout.serverId().equals(HUB), "hub claimed the checkout and released it: " + checkout);
        ListTag before = entryList(v1);
        ListTag after = entryList(v2);
        h.assertTrue(entryAt(after, 0).getInt("realCount") == 250, "250 cobblestone left, in the mod's own count: " + entryAt(after, 0));
        h.assertTrue(entryAt(after, 1).equals(entryAt(before, 1)), "the torch slot untouched");
        h.assertTrue(moduleOf(v2, "vitals").equals(moduleOf(v1, "vitals")) && moduleOf(v2, "curios").equals(moduleOf(v1, "curios")), "every other module untouched");

        EditResult widget = now(panel.edit(id, 2, Edit.remove("inventory", 20, now(panel.read(id)).orElseThrow().area("inventory").orElseThrow().slot(20).stack(), 1), "Mod"));
        h.assertTrue(widget.ok(), "a missing mod's item can be removed: " + widget);
        CompoundTag left = entryAt(PlayerSnapshotCodec.module(PlayerSnapshotCodec.decode(store.latest(id).join().orElseThrow().data()), "inventory").getList("Items", Tag.TAG_COMPOUND), 20).getCompound("Item");
        h.assertTrue(left.getString("id").equals("nosuchmod:widget") && left.getByte("Count") == 2, "and the rest goes back as the original item: " + left);

        EditResult ring = now(panel.edit(id, 3, Edit.remove("curios/ring", 0, new ItemStack(Items.GOLD_INGOT), 1), "Mod"));
        h.assertTrue(ring.ok(), "a Curios slot can be emptied: " + ring);
        h.assertTrue(now(panel.read(id)).orElseThrow().area("curios/ring").orElseThrow().slot(0).isEmpty(), "the ring is gone");
        h.succeed();
    }

    private static ListTag entryList(Snapshot s) {
        for (Tag t : moduleOf(s, "backpacks").getList("Packs", Tag.TAG_COMPOUND)) {
            CompoundTag e = (CompoundTag) t;
            if (e.getUUID("Uuid").equals(B1)) {
                return e.getCompound("Contents").getCompound("inventory").getList("Items", Tag.TAG_COMPOUND);
            }
        }
        throw new AssertionError("no B1");
    }

    @GameTest(template = EMPTY)
    public static void an_offline_change_is_refused_when_the_version_moved(GameTestHelper h) {
        MemoryStore store = new MemoryStore();
        SyncService hub = new SyncService(h.getLevel().getServer(), store, HUB);
        UUID id = UUID.randomUUID();
        Snapshot v1 = savedAlex(h, store, id);
        store.save(id, PVP, SaveCause.LOGOUT, v1.data()).join(); // they played again: v2
        EditResult r = now(panel(h, hub, Map.of()).edit(id, 1, Edit.remove("inventory", 9, new ItemStack(Items.STONE), 1), "Mod"));
        h.assertTrue(r.outcome() == Outcome.CHANGED && r.message().contains("now v2, read v1"), "refused: " + r);
        h.assertTrue(store.latest(id).join().orElseThrow().version() == 2 && !store.snapshot(id, 1).join().orElseThrow().pinned(), "nothing saved or pinned");
        Presence released = store.presence(id).join().orElseThrow();
        h.assertTrue(!released.online() && released.serverId().equals(HUB) && !hub.saving(id), "claimed by hub, then released; the hold ended: " + released);
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void an_offline_change_is_refused_while_the_player_is_online_on_another_server(GameTestHelper h) {
        MemoryStore store = new MemoryStore();
        SyncService hub = new SyncService(h.getLevel().getServer(), store, HUB);
        UUID id = UUID.randomUUID();
        savedAlex(h, store, id);
        store.setPresence(id, PVP, true).join();
        EditResult r = now(panel(h, hub, Map.of()).edit(id, 1, Edit.remove("inventory", 9, new ItemStack(Items.STONE), 1), "Mod"));
        h.assertTrue(r.outcome() == Outcome.ONLINE && r.serverId().equals(PVP), "online on pvp: " + r);
        h.assertTrue(store.latest(id).join().orElseThrow().version() == 1, "nothing saved");
        h.assertTrue(store.presence(id).join().orElseThrow().serverId().equals(PVP) && store.presence(id).join().orElseThrow().online(), "pvp still holds them");

        store.setPresence(id, PVP, false).join();
        h.assertTrue(hub.tryBeginWrite(id) && !hub.tryBeginWrite(id), "one write at a time on this server");
        EditResult busy = now(panel(h, hub, Map.of()).edit(id, 1, Edit.remove("inventory", 9, new ItemStack(Items.STONE), 1), "Mod"));
        hub.endWrite(id);
        h.assertTrue(busy.outcome() == Outcome.BUSY, "busy: " + busy);
        h.assertTrue(now(panel(h, null, Map.of()).edit(id, 1, Edit.remove("inventory", 9, new ItemStack(Items.STONE), 1), "Mod")).outcome() == Outcome.FAILED, "sync off: failed");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void adds_go_onto_like_items_then_empty_slots_all_or_nothing_and_only_where_allowed(GameTestHelper h) {
        MemoryStore store = new MemoryStore();
        SyncService hub = new SyncService(h.getLevel().getServer(), store, HUB);
        UUID id = UUID.randomUUID();
        savedAlex(h, store, id);
        PanelService panel = panel(h, hub, Map.of());
        EditResult r = now(panel.edit(id, 1, Edit.add("inventory", new ItemStack(Items.DIAMOND, 70)), "Mod"));
        h.assertTrue(r.ok(), "added: " + r);
        ItemArea inv = now(panel.read(id)).orElseThrow().area("inventory").orElseThrow();
        h.assertTrue(inv.slot(0).stack().getCount() == 64 && inv.slot(1).stack().is(Items.DIAMOND) && inv.slot(1).stack().getCount() == 64
                && inv.slot(2).stack().getCount() == 2, "4 onto the 60, then 64 and 2 in the first empty slots: " + inv.slot(0) + " " + inv.slot(1) + " " + inv.slot(2));

        EditResult dank = now(panel.edit(id, 2, Edit.add("dank/5", new ItemStack(Items.DIRT, 10)), "Mod"));
        EditResult pack = now(panel.edit(id, 2, Edit.add("backpack/" + B4, new ItemStack(Items.ENDER_PEARL)), "Mod"));
        h.assertTrue(dank.outcome() == Outcome.REFUSED && pack.outcome() == Outcome.REFUSED, "offline, danks and backpacks only give items up: " + dank + " " + pack);
        EditResult dankTake = now(panel.edit(id, 2, Edit.remove("dank/5", 0, new ItemStack(Items.DIRT), 400), "Mod"));
        h.assertTrue(dankTake.ok() && now(panel.read(id)).orElseThrow().area("dank/5").orElseThrow().slot(0).stack().getCount() == 600, "but give them up: " + dankTake);

        EditResult tooMuch = now(panel.edit(id, 3, Edit.add("inventory", new ItemStack(Items.ENDER_PEARL, 16 * 40)), "Mod"));
        h.assertTrue(tooMuch.outcome() == Outcome.NO_ROOM && store.latest(id).join().orElseThrow().version() == 3, "40 stacks do not fit the free slots: nothing added: " + tooMuch);
        ItemStack namedPack = new ItemStack(Items.CHEST);
        namedPack.getOrCreateTag().putUUID("contentsUuid", B1);
        EditResult copy = now(panel.edit(id, 3, Edit.add("inventory", namedPack), "Mod"));
        h.assertTrue(copy.outcome() == Outcome.REFUSED, "an item carrying a backpack's contents id is refused: " + copy);
        EditResult armour = now(panel.edit(id, 3, Edit.add("armor", new ItemStack(Items.IRON_BOOTS)), "Mod"));
        h.assertTrue(armour.outcome() == Outcome.REFUSED, "armour only gives items up: " + armour);
        EditResult cursed = now(panel.edit(id, 3, Edit.add("curios/ring", new ItemStack(Items.GOLD_INGOT)), "Mod"));
        h.assertTrue(cursed.outcome() == Outcome.REFUSED, "Curios slots only give items up");
        EditResult changed = now(panel.edit(id, 3, Edit.remove("inventory", 9, new ItemStack(Items.DIAMOND), 1), "Mod"));
        h.assertTrue(changed.outcome() == Outcome.CHANGED, "the slot holds stone, not diamond: " + changed);
        h.assertTrue(now(panel.edit(id, 3, Edit.remove("pockets", 0, new ItemStack(Items.STONE), 1), "Mod")).outcome() == Outcome.FAILED, "no such area: failed");
        h.assertTrue(now(panel.edit(id, 3, Edit.remove("backpack/" + UUID.randomUUID(), 0, new ItemStack(Items.STONE), 1), "Mod")).outcome() == Outcome.CHANGED, "a backpack no longer there: changed");
        h.assertTrue(store.latest(id).join().orElseThrow().version() == 3, "none of the refusals saved anything");
        h.succeed();
    }

    // ------------------------------------------------------------------ live changes (SU2-3)

    @GameTest(template = EMPTY)
    public static void a_live_change_saves_the_state_before_pinned_and_itself_noted(GameTestHelper h) {
        MemoryStore store = new MemoryStore();
        SyncService hub = new SyncService(h.getLevel().getServer(), store, HUB);
        UUID id = UUID.randomUUID();
        ServerPlayer p = TestPlayers.mockServerPlayer(h, id);
        p.getInventory().setItem(9, new ItemStack(Items.STONE, 10));
        PanelService panel = panel(h, hub, Map.of(id, p));
        PlayerItems read = now(panel.read(id)).orElseThrow();
        h.assertTrue(read.live() && read.online() && read.serverId().equals(HUB), "read live");
        EditResult r = now(panel.edit(id, 999, Edit.remove("inventory", 9, new ItemStack(Items.STONE), 5), "Mod"));
        h.assertTrue(r.ok() && p.getInventory().getItem(9).getCount() == 5, "five taken, the version not used live: " + r);
        List<Snapshot> history = store.history(id).join();
        h.assertTrue(history.size() == 2 && history.stream().allMatch(s -> s.cause() == SaveCause.ADMIN), "two admin snapshots: " + history);
        h.assertTrue(history.get(1).pinned() && r.pinnedVersion() == history.get(1).version() && r.version() == history.get(0).version(), "the one before is pinned");
        h.assertTrue("panel: Mod".equals(PlayerSnapshotCodec.note(PlayerSnapshotCodec.decode(history.get(0).data()))), "the change is noted");
        CompoundTag before9 = entryAt(PlayerSnapshotCodec.module(PlayerSnapshotCodec.decode(history.get(1).data()), "inventory").getList("Items", Tag.TAG_COMPOUND), 9);
        h.assertTrue(before9 != null && before9.getCompound("Item").getByte("Count") == 10, "before: 10 stone in slot 9: " + before9);
        h.assertTrue(hub.autosave(p, Long.MAX_VALUE / 2).isEmpty(), "the note is not a change: the next auto-save writes nothing");

        EditResult add = now(panel.edit(id, 0, Edit.add("ender_chest", new ItemStack(Items.DIAMOND, 3)), "Mod"));
        h.assertTrue(add.ok() && p.getEnderChestInventory().getItem(0).getCount() == 3, "added to the ender chest live: " + add);
        EditResult moved = now(panel.edit(id, 0, Edit.remove("inventory", 9, new ItemStack(Items.STONE), 6), "Mod"));
        h.assertTrue(moved.outcome() == Outcome.CHANGED && p.getInventory().getItem(9).getCount() == 5, "only 5 left: refused, nothing taken: " + moved);
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_live_shatter_totem_is_shown_locked_and_never_added_or_removed(GameTestHelper h) {
        MemoryStore store = new MemoryStore();
        SyncService hub = new SyncService(h.getLevel().getServer(), store, HUB);
        UUID id = UUID.randomUUID();
        ServerPlayer p = TestPlayers.mockServerPlayer(h, id);
        ItemStack totem = TotemRuleTests.totem(h);
        p.getInventory().setItem(3, totem.copy());
        PanelService panel = panel(h, hub, Map.of(id, p));
        // a shulker box holding a totem (the game never lets one in; the panel must not let one in or out either)
        ItemStack shulker = new ItemStack(Items.SHULKER_BOX);
        CompoundTag bet = new CompoundTag();
        ListTag inside = new ListTag();
        CompoundTag entry = totem.save(new CompoundTag());
        entry.putByte("Slot", (byte) 0);
        inside.add(entry);
        bet.put("Items", inside);
        shulker.getOrCreateTag().put("BlockEntityTag", bet);
        p.getInventory().setItem(5, shulker.copy());
        PlayerItems read = now(panel.read(id)).orElseThrow();
        var shown = read.area("inventory").orElseThrow().slot(3);
        h.assertTrue(shown.locked() && !read.area("inventory").orElseThrow().slot(0).locked(), "the totem is shown, locked");
        h.assertTrue(!shown.stack().isEmpty() && !com.mrgregles.bsp_inventorysync.compat.BspCore.isLiveTotem(shown.stack()), "as an inert copy, never a live totem: " + shown.stack().getTag());
        EditResult take = now(panel.edit(id, 0, Edit.remove("inventory", 3, shown.stack(), 1), "Mod"));
        h.assertTrue(take.outcome() == Outcome.REFUSED && TotemRuleTests.liveTotems(p) == 1, "never removed: " + take);
        EditResult give = now(panel.edit(id, 0, Edit.add("inventory", totem), "Mod"));
        h.assertTrue(give.outcome() == Outcome.REFUSED && TotemRuleTests.liveTotems(p) == 1, "never added: " + give);
        EditResult boxIn = now(panel.edit(id, 0, Edit.add("ender_chest", shulker), "Mod"));
        h.assertTrue(boxIn.outcome() == Outcome.REFUSED, "nor inside a shulker box: " + boxIn);
        EditResult boxOut = now(panel.edit(id, 0, Edit.remove("inventory", 5, shulker, 1), "Mod"));
        h.assertTrue(boxOut.outcome() == Outcome.REFUSED && !p.getInventory().getItem(5).isEmpty(), "and a box holding one is never removed: " + boxOut);
        h.assertTrue(store.history(id).join().isEmpty(), "nothing saved for a refusal");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_player_still_arriving_is_busy_and_save_now_saves_only_a_settled_player(GameTestHelper h) {
        MemoryStore store = new MemoryStore();
        SyncService hub = new SyncService(h.getLevel().getServer(), store, HUB, new ArrivalPolicy(60_000, false));
        UUID id = UUID.randomUUID();
        savedAlex(h, store, id);
        store.setPresence(id, PVP, true).join(); // pvp has not released them yet
        ServerPlayer p = TestPlayers.mockServerPlayer(h, id);
        hub.onLogin(p, 0);
        Map<UUID, ServerPlayer> online = new HashMap<>(Map.of(id, p));
        PanelService panel = panel(h, hub, online);
        h.assertTrue(now(panel.edit(id, 1, Edit.add("inventory", new ItemStack(Items.DIAMOND)), "Mod")).outcome() == Outcome.BUSY, "busy while arriving");
        PlayerItems read = now(panel.read(id)).orElseThrow();
        h.assertTrue(!read.live() && read.online() && read.serverId().equals(HUB) && read.version() == 1, "read from the snapshot, not the stale copy here");
        h.assertTrue(now(panel.saveNow(p)) == 0L && store.latest(id).join().orElseThrow().version() == 1, "save now saves nothing for them yet");
        store.setPresence(id, PVP, false).join();
        hub.tick(1);
        h.assertTrue(!SyncService.isSyncing(id), "arrived");
        long saved = now(panel.saveNow(p));
        h.assertTrue(saved == 2 && store.latest(id).join().orElseThrow().cause() == SaveCause.ADMIN, "save now: an admin snapshot, v" + saved);
        hub.onLogout(p);
        h.succeed();
    }

    // ------------------------------------------------------------------ the audit's fixes (AUD-5)

    @GameTest(template = EMPTY)
    public static void a_live_change_with_the_sync_off_is_made_and_a_blank_name_is_staff(GameTestHelper h) {
        UUID id = UUID.randomUUID();
        ServerPlayer p = TestPlayers.mockServerPlayer(h, id);
        p.getInventory().setItem(9, new ItemStack(Items.STONE, 10));
        PanelService off = panel(h, null, Map.of(id, p));
        PlayerItems read = now(off.read(id)).orElseThrow();
        h.assertTrue(read.live() && read.version() == 0 && read.serverId().isEmpty(), "live, no version, no server id with the sync off");
        EditResult r = now(off.edit(id, 0, Edit.remove("inventory", 9, new ItemStack(Items.STONE), 3), "  "));
        h.assertTrue(r.ok() && r.version() == 0 && p.getInventory().getItem(9).getCount() == 7 && r.message().contains("not saved"), "made, not saved: " + r);

        MemoryStore store = new MemoryStore();
        SyncService hub = new SyncService(h.getLevel().getServer(), store, HUB);
        now(panel(h, hub, Map.of(id, p)).edit(id, 0, Edit.remove("inventory", 9, new ItemStack(Items.STONE), 1), ""));
        h.assertTrue("panel: staff".equals(PlayerSnapshotCodec.note(PlayerSnapshotCodec.decode(store.latest(id).join().orElseThrow().data()))), "a blank name is noted as staff");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_change_never_creates_a_section_the_snapshot_lacks(GameTestHelper h) {
        MemoryStore store = new MemoryStore();
        SyncService hub = new SyncService(h.getLevel().getServer(), store, HUB);
        UUID id = UUID.randomUUID();
        CompoundTag root = alex(h, id);
        root.getCompound("Modules").remove("inventory"); // the inventory module failed to save
        store.save(id, PVP, SaveCause.LOGOUT, PlayerSnapshotCodec.encode(root)).join();
        PanelService panel = panel(h, hub, Map.of());
        PlayerItems read = now(panel.read(id)).orElseThrow();
        h.assertTrue(read.area("inventory").isEmpty() && read.area("ender_chest").isPresent(), "no inventory area to show");
        EditResult r = now(panel.edit(id, 1, Edit.add("inventory", new ItemStack(Items.DIAMOND)), "Mod"));
        h.assertTrue(r.outcome() == Outcome.FAILED && store.latest(id).join().orElseThrow().version() == 1, "refused, nothing saved: " + r);
        EditResult ender = now(panel.edit(id, 1, Edit.add("ender_chest", new ItemStack(Items.DIAMOND)), "Mod"));
        h.assertTrue(ender.ok(), "the ender chest can still be changed: " + ender);
        CompoundTag saved = PlayerSnapshotCodec.decode(store.latest(id).join().orElseThrow().data());
        h.assertTrue(!saved.getCompound("Modules").contains("inventory"), "and the saved change has no empty inventory, which would empty them at login");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_linked_backpack_is_read_only(GameTestHelper h) {
        MemoryStore store = new MemoryStore();
        SyncService hub = new SyncService(h.getLevel().getServer(), store, HUB);
        UUID id = UUID.randomUUID();
        CompoundTag root = alex(h, id);
        CompoundTag b1 = entryAt(PlayerSnapshotCodec.module(root, "ender_chest").getList("Items", Tag.TAG_COMPOUND), 2).getCompound("Item");
        CompoundTag link = new CompoundTag();
        link.put("endpoint", new CompoundTag());
        b1.getCompound("tag").put("linked_storage", link); // as Sophisticated Core marks an endpoint
        store.save(id, PVP, SaveCause.LOGOUT, PlayerSnapshotCodec.encode(root)).join();
        PanelService panel = panel(h, hub, Map.of());
        ItemArea area = now(panel.read(id)).orElseThrow().area("backpack/" + B1).orElseThrow();
        h.assertTrue(!area.removable() && !area.addable(), "read-only");
        h.assertTrue(now(panel.read(id)).orElseThrow().area("backpack/" + B2).orElseThrow().removable(), "other backpacks are not");
        EditResult r = now(panel.edit(id, 1, Edit.remove("backpack/" + B1, 0, new ItemStack(Items.COBBLESTONE), 1), "Mod"));
        h.assertTrue(r.outcome() == Outcome.REFUSED && store.latest(id).join().orElseThrow().version() == 1, "refused: " + r);
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_claim_that_fails_is_released_and_a_failed_pin_is_reported(GameTestHelper h) {
        MemoryStore store = new MemoryStore();
        boolean[] failClaim = {true};
        boolean[] failPin = {false};
        SyncStore flaky = (SyncStore) java.lang.reflect.Proxy.newProxyInstance(SyncStore.class.getClassLoader(), new Class<?>[] {SyncStore.class}, (proxy, m, args) -> {
            if (m.getName().equals("claim") && failClaim[0]) {
                store.claim((UUID) args[0], (String) args[1]).join(); // it landed, but the answer was lost
                return CompletableFuture.failedFuture(new java.sql.SQLException("connection lost"));
            }
            if (m.getName().equals("pin") && failPin[0]) {
                return CompletableFuture.failedFuture(new java.sql.SQLException("pin failed"));
            }
            try {
                return m.invoke(store, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        });
        SyncService hub = new SyncService(h.getLevel().getServer(), flaky, HUB);
        UUID id = UUID.randomUUID();
        savedAlex(h, store, id);
        PanelService panel = panel(h, hub, Map.of());
        EditResult lost = now(panel.edit(id, 1, Edit.remove("inventory", 9, new ItemStack(Items.STONE), 1), "Mod"));
        h.assertTrue(lost.outcome() == Outcome.FAILED && lost.message().contains("Nothing was changed"), "failed: " + lost);
        h.assertTrue(!store.presence(id).join().orElseThrow().online() && !hub.saving(id), "the claim that may have landed is released");
        failClaim[0] = false;
        failPin[0] = true;
        EditResult unpinned = now(panel.edit(id, 1, Edit.remove("inventory", 9, new ItemStack(Items.STONE), 1), "Mod"));
        h.assertTrue(unpinned.ok() && unpinned.version() == 2 && unpinned.pinnedVersion() == 0 && unpinned.message().contains("could not be pinned"), "saved, the pin failure said: " + unpinned);
        h.succeed();
    }

    /** With Redis, a release sent while the arrival's claim is still queued must not run first (D29 audit). */
    @GameTest(template = EMPTY)
    public static void a_release_waits_for_the_arrivals_claim(GameTestHelper h) {
        MemoryStore store = new MemoryStore();
        CompletableFuture<Void> gate = new CompletableFuture<>();
        List<String> calls = new java.util.ArrayList<>();
        SyncStore slowClaim = (SyncStore) java.lang.reflect.Proxy.newProxyInstance(SyncStore.class.getClassLoader(), new Class<?>[] {SyncStore.class}, (proxy, m, args) -> {
            if (m.getName().equals("claim")) {
                return gate.thenCompose(v -> {
                    calls.add("claim");
                    return store.claim((UUID) args[0], (String) args[1]);
                });
            }
            if (m.getName().equals("setPresence") && Boolean.FALSE.equals(args[2])) {
                calls.add("release");
            }
            try {
                return m.invoke(store, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        });
        SyncService hub = new SyncService(h.getLevel().getServer(), slowClaim, HUB, new ArrivalPolicy(60_000, false));
        UUID id = UUID.randomUUID();
        ServerPlayer p = TestPlayers.mockServerPlayer(h, id);
        hub.onLogin(p, 0);
        hub.tick(1); // the poll sees them free; the claim is queued
        hub.onLogout(p); // they leave before it lands
        h.assertTrue(!calls.contains("release"), "nothing released before the claim has landed: " + calls);
        gate.complete(null);
        h.assertTrue(calls.equals(List.of("claim", "release")), "the claim, then the release: " + calls);
        h.assertTrue(!store.presence(id).join().orElseThrow().online(), "they end up released, not held for ever");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_player_whose_login_was_refused_is_failed_not_busy(GameTestHelper h) {
        MemoryStore store = new MemoryStore();
        SyncService hub = new SyncService(h.getLevel().getServer(), store, HUB, new ArrivalPolicy(60_000, false));
        UUID id = UUID.randomUUID();
        savedAlex(h, store, id);
        ServerPlayer p = TestPlayers.mockServerPlayer(h, id);
        hub.failNextRestore(new IllegalStateException("test: the restore fails"));
        hub.onLogin(p, 0);
        hub.tick(1);
        h.assertTrue(hub.refused(id), "the login was refused");
        EditResult r = now(panel(h, hub, Map.of(id, p)).edit(id, 1, Edit.add("inventory", new ItemStack(Items.DIAMOND)), "Mod"));
        h.assertTrue(r.outcome() == Outcome.FAILED && r.message().contains("refused"), "failed with the reason: " + r);
        hub.onLogout(p);
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_malformed_request_is_answered_failed(GameTestHelper h) {
        MemoryStore store = new MemoryStore();
        SyncService hub = new SyncService(h.getLevel().getServer(), store, HUB);
        UUID id = UUID.randomUUID();
        ServerPlayer p = TestPlayers.mockServerPlayer(h, id);
        PanelService panel = panel(h, hub, Map.of(id, p));
        h.assertTrue(now(panel.edit(id, 0, null, "Mod")).outcome() == Outcome.FAILED, "no change");
        h.assertTrue(now(panel.edit(id, 0, Edit.remove(null, 0, new ItemStack(Items.STONE), 1), "Mod")).outcome() == Outcome.FAILED, "no area");
        h.assertTrue(now(panel.edit(id, 0, Edit.remove("pockets", 0, new ItemStack(Items.STONE), 1), "Mod")).outcome() == Outcome.FAILED, "an unknown area");
        h.assertTrue(now(panel.edit(id, 0, Edit.remove("inventory", 99, new ItemStack(Items.STONE), 1), "Mod")).outcome() == Outcome.FAILED, "no such slot");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void an_offline_change_waits_for_this_servers_own_logout_save(GameTestHelper h) {
        MemoryStore store = new MemoryStore();
        CompletableFuture<Void> gate = new CompletableFuture<>();
        boolean[] hold = {true};
        SyncStore slowSave = (SyncStore) java.lang.reflect.Proxy.newProxyInstance(SyncStore.class.getClassLoader(), new Class<?>[] {SyncStore.class}, (proxy, m, args) -> {
            if (m.getName().equals("save") && hold[0]) {
                hold[0] = false;
                return gate.thenCompose(v -> store.save((UUID) args[0], (String) args[1], (SaveCause) args[2], (byte[]) args[3]));
            }
            try {
                return m.invoke(store, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        });
        SyncService hub = new SyncService(h.getLevel().getServer(), slowSave, HUB);
        UUID id = UUID.randomUUID();
        ServerPlayer p = TestPlayers.mockServerPlayer(h, id);
        p.getInventory().setItem(9, new ItemStack(Items.STONE, 10));
        hub.onLogout(p); // their logout save is on its way
        PanelService panel = panel(h, hub, Map.of());
        EditResult busy = now(panel.edit(id, 1, Edit.remove("inventory", 9, new ItemStack(Items.STONE), 1), "Mod"));
        h.assertTrue(busy.outcome() == Outcome.BUSY, "busy until it lands: " + busy);
        gate.complete(null);
        EditResult r = now(panel.edit(id, 1, Edit.remove("inventory", 9, new ItemStack(Items.STONE), 1), "Mod"));
        h.assertTrue(r.ok() && r.version() == 2, "then made on the logout save: " + r);
        h.succeed();
    }
}
