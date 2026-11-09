package com.mrgregles.bsp_inventorysync.gametest;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.api.GraveItems;
import com.mrgregles.bsp_inventorysync.api.ItemArea;
import com.mrgregles.bsp_inventorysync.api.ItemPlace;
import com.mrgregles.bsp_inventorysync.compat.BspCore;
import com.mrgregles.bsp_inventorysync.data.ItemCodec;
import com.mrgregles.bsp_inventorysync.grave.GraveActions;
import com.mrgregles.bsp_inventorysync.grave.GraveContents;
import com.mrgregles.bsp_inventorysync.grave.GraveService;
import com.mrgregles.bsp_inventorysync.grave.WorldGraveStore;
import com.mrgregles.bsp_inventorysync.storage.Grave;
import com.mrgregles.bsp_inventorysync.storage.MemoryStore;
import com.mrgregles.bsp_inventorysync.sync.SyncService;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Server Utilities' request SU-IS3: what one grave holds, read-only, for the staff panel ({@code InventorySyncApi.graveItems}).
 * Graves are written into the stores directly, their backpack and dank contents in the forms the sync stores them in (as
 * {@code GraveService} takes them at a death); Sophisticated Backpacks and Dank Storage are not loaded here, so their items
 * are a missing mod's placeholders, as on a server without them.
 */
@GameTestHolder(BSPInventorySync.MODID)
@PrefixGameTestTemplate(false)
public class GraveItemsTests {
    private static final String EMPTY = "empty";

    private static final UUID B1 = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID B2 = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    private static final UUID B3 = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
    private static final UUID B9 = UUID.fromString("00000000-0000-0000-0000-0000000000c9");

    /** A shulker box holding these item compounds. */
    private static ItemStack shulker(CompoundTag... items) {
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        ListTag list = new ListTag();
        byte slot = 0;
        for (CompoundTag i : items) {
            CompoundTag e = i.copy();
            e.putByte("Slot", slot++);
            list.add(e);
        }
        CompoundTag bet = new CompoundTag();
        bet.put("Items", list);
        box.getOrCreateTag().put("BlockEntityTag", bet);
        return box;
    }

    /** Whether any item inside the stack's data is a live Shatter Totem. */
    private static boolean holdsLiveTotem(ItemStack stack) {
        boolean[] found = {false};
        java.util.function.Consumer<net.minecraft.nbt.Tag>[] walk = new java.util.function.Consumer[1];
        walk[0] = tag -> {
            if (tag instanceof CompoundTag c) {
                if (c.contains("id") && c.contains("Count") && BspCore.isLiveTotem(ItemStack.of(c))) {
                    found[0] = true;
                }
                c.getAllKeys().forEach(k -> walk[0].accept(c.get(k)));
            } else if (tag instanceof ListTag l) {
                l.forEach(walk[0]);
            }
        };
        if (stack.hasTag()) {
            walk[0].accept(stack.getTag());
        }
        return found[0];
    }

    private static <T> T now(CompletableFuture<T> f) {
        if (!f.isDone()) {
            throw new AssertionError("the future is not done yet");
        }
        return f.join();
    }

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
        CompoundTag entry = new CompoundTag();
        entry.putUUID("Uuid", uuid);
        entry.put("Contents", contents);
        return entry;
    }

    private static CompoundTag packSlot(int slot, CompoundTag item, int count) {
        CompoundTag c = item.copy();
        c.putInt("Slot", slot);
        c.putInt("realCount", count);
        return c;
    }

    /**
     * Alex's grave: a sword, a live Shatter Totem, a missing mod's widget, backpack B1 (holding 300 cobblestone, 200 of a
     * missing mod's gizmo and backpack B2, which holds a live totem in its stored contents), a dank (frequency 7, 2000
     * dirt), a shulker box holding a live totem, a shulker box holding backpack B3, and 55 xp; plus the stored contents of
     * backpack B9, whose item was taken from the grave earlier.
     */
    private static Grave alexsGrave(GameTestHelper h, UUID owner) {
        CompoundTag dankItem = item("dankstorage:dank_4", 1);
        CompoundTag settings = new CompoundTag();
        settings.putInt("dankstorage:frequency", 7);
        CompoundTag dankTag = new CompoundTag();
        dankTag.put("settings", settings);
        dankItem.put("tag", dankTag);
        List<GraveContents.Entry> entries = List.of(
                new GraveContents.Entry("inv:0", new ItemStack(Items.DIAMOND_SWORD)),
                new GraveContents.Entry("inv:1", TotemRuleTests.totem(h)),
                new GraveContents.Entry("inv:2", ItemCodec.load(item("nosuchmod:widget", 3))),
                new GraveContents.Entry("inv:3", ItemCodec.load(backpackItem(B1))),
                new GraveContents.Entry("inv:4", ItemCodec.load(dankItem)),
                new GraveContents.Entry("inv:5", shulker(TotemRuleTests.totem(h).save(new CompoundTag()))),
                new GraveContents.Entry("inv:6", shulker(backpackItem(B3))));
        CompoundTag extras = new CompoundTag();
        ListTag packs = new ListTag();
        packs.add(pack(B1, packSlot(0, item("minecraft:cobblestone", 300), 300), packSlot(1, item("nosuchmod:gizmo", 200), 200), packSlot(5, backpackItem(B2), 1)));
        packs.add(pack(B2, packSlot(0, item("minecraft:apple", 4), 4), packSlot(1, TotemRuleTests.totem(h).save(new CompoundTag()), 1)));
        packs.add(pack(B3, packSlot(0, item("minecraft:bread", 6), 6)));
        packs.add(pack(B9, packSlot(0, item("minecraft:emerald", 9), 9))); // its item is no longer in the grave
        extras.put("Packs", packs);
        CompoundTag dirt = item("minecraft:dirt", 2000);
        dirt.putInt("Slot", 0);
        dirt.putInt("ExtendedCount", 2000);
        CompoundTag dankContents = new CompoundTag();
        ListTag dankItems = new ListTag();
        dankItems.add(dirt);
        dankContents.put("Items", dankItems);
        dankContents.putString("DankStats", "four");
        CompoundTag dank = new CompoundTag();
        dank.putInt("Frequency", 7);
        dank.put("Contents", dankContents);
        ListTag danks = new ListTag();
        danks.add(dank);
        extras.put("Danks", danks);
        long t = System.currentTimeMillis();
        return new Grave(UUID.randomUUID(), owner, "Alex", "pvp", "minecraft:overworld", 1, 64, 1, false, t, t, 0, 55, entries.size(), 0,
                new GraveContents(entries, extras).encode());
    }

    @GameTest(template = EMPTY)
    public static void a_graves_items_are_read_with_its_backpacks_and_danks_and_nothing_changes(GameTestHelper h) {
        MemoryStore store = new MemoryStore();
        SyncService hub = new SyncService(h.getLevel().getServer(), store, "hub");
        GraveService graves = new GraveService(h.getLevel().getServer(), new WorldGraveStore(), () -> hub, 2);
        UUID id = UUID.randomUUID();
        Grave g = alexsGrave(h, id);
        store.addGrave(g).join(); // in the network database (it came from pvp)
        GraveItems items = now(GraveActions.items(graves, id, g.shortId())).orElseThrow();
        h.assertTrue(items.xp() == 55, "the XP: " + items.xp());
        List<String> ids = items.areas().stream().map(ItemArea::id).toList();
        h.assertTrue(ids.equals(List.of("grave", "backpack/" + B1, "backpack/" + B2, "backpack/" + B3, "dank/7")),
                "the grave, then its backpacks and dank; not B9, whose item left the grave: " + ids);
        h.assertTrue(items.areas().stream().noneMatch(a -> a.addable() || a.removable()), "all read-only");
        ItemArea grave = items.areas().get(0);
        h.assertTrue(grave.kind() == ItemArea.Kind.GRAVE && grave.size() == 7 && grave.slot(0).stack().is(Items.DIAMOND_SWORD), "one slot per stack: " + grave.slots());
        h.assertTrue(grave.slot(1).locked() && !grave.slot(1).stack().isEmpty() && !BspCore.isLiveTotem(grave.slot(1).stack()), "the totem: locked, an inert copy");
        h.assertTrue("nosuchmod:widget".equals(grave.slot(2).missingItem()) && grave.slot(2).stack().getCount() == 3, "a missing mod's item, named: " + grave.slot(2));
        h.assertTrue(grave.slot(5).locked() && !holdsLiveTotem(grave.slot(5).stack()) && grave.slot(5).stack().is(Items.SHULKER_BOX),
                "a shulker box holding a totem: locked, its copy holds only an inert husk");
        ItemArea b1 = items.areas().get(1);
        h.assertTrue(new ItemPlace("grave", 3, List.of()).equals(b1.place()) && b1.slot(0).stack().getCount() == 300, "B1 in grave slot 3, 300 cobblestone: " + b1.place());
        h.assertTrue("nosuchmod:gizmo".equals(b1.slot(1).missingItem()) && b1.slot(1).stack().getCount() == 200, "a missing mod's item in a backpack, 200 of it: " + b1.slot(1));
        ItemArea b2 = items.areas().get(2);
        h.assertTrue(new ItemPlace("backpack/" + B1, 5, List.of()).equals(b2.place()), "B2 inside B1: " + b2.place());
        h.assertTrue(b2.slot(1).locked() && !b2.slot(1).stack().isEmpty() && !BspCore.isLiveTotem(b2.slot(1).stack()), "a totem in a backpack's stored contents: locked, inert");
        h.assertTrue(b1.slot(5).locked() && grave.slot(3).locked(), "and the backpacks holding it are locked too, up to the grave slot");
        h.assertTrue(new ItemPlace("grave", 6, List.of("minecraft:shulker_box")).equals(items.areas().get(3).place()), "B3 inside a shulker box: " + items.areas().get(3).place());
        h.assertTrue(new ItemPlace("grave", 4, List.of()).equals(items.areas().get(4).place()) && items.areas().get(4).slot(0).stack().getCount() == 2000, "the dank, 2000 dirt");
        Grave after = store.grave(g.id()).join().orElseThrow();
        h.assertTrue(after.revision() == g.revision() && after.touchedAt() == g.touchedAt() && after.xp() == 55 && java.util.Arrays.equals(after.data(), g.data()),
                "reading changed nothing, byte for byte (not even its clock)");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void an_unknown_or_emptied_grave_or_someone_elses_reads_as_empty(GameTestHelper h) {
        WorldGraveStore world = new WorldGraveStore();
        GraveService graves = new GraveService(h.getLevel().getServer(), world, () -> null, 2);
        UUID id = UUID.randomUUID();
        Grave g = alexsGrave(h, id);
        world.addGrave(g).join();
        h.assertTrue(now(GraveActions.items(graves, id, g.id().toString())).isPresent(), "by its whole id too (this server's own data, sync off)");
        h.assertTrue(now(GraveActions.items(graves, id, "ffffffff")).isEmpty(), "an unknown id");
        h.assertTrue(now(GraveActions.items(graves, id, g.shortId().substring(0, 7))).isEmpty(), "an id shorter than 8 characters");
        h.assertTrue(now(GraveActions.items(graves, UUID.randomUUID(), g.shortId())).isEmpty(), "another player's grave");
        Grave read = world.grave(g.id()).join().orElseThrow();
        world.updateGrave(read.withContents(0, 0, new GraveContents(List.of(), new CompoundTag()).encode(), read.touchedAt())).join();
        Optional<GraveItems> emptied = now(GraveActions.items(graves, id, g.shortId()));
        h.assertTrue(emptied.isEmpty(), "an emptied grave");
        h.succeed();
    }

    /** Every read, not only graves: a player's snapshot with a shulker box holding a totem (the sync keeps only top-level totems out). */
    @GameTest(template = EMPTY)
    public static void a_read_never_hands_out_a_totem_nested_in_a_shulker_box(GameTestHelper h) {
        MemoryStore store = new MemoryStore();
        SyncService hub = new SyncService(h.getLevel().getServer(), store, "hub");
        UUID id = UUID.randomUUID();
        net.minecraft.server.level.ServerPlayer p = TestPlayers.mockServerPlayer(h, id);
        p.getEnderChestInventory().setItem(4, shulker(TotemRuleTests.totem(h).save(new CompoundTag())));
        p.getEnderChestInventory().setItem(5, shulker(shulker(TotemRuleTests.totem(h).save(new CompoundTag())).save(new CompoundTag()))); // a box in a box
        store.save(id, "pvp", com.mrgregles.bsp_inventorysync.storage.SaveCause.LOGOUT,
                com.mrgregles.bsp_inventorysync.data.PlayerSnapshotCodec.encode(com.mrgregles.bsp_inventorysync.data.PlayerSnapshotCodec.capture(p))).join();
        var panel = new com.mrgregles.bsp_inventorysync.panel.PanelService(h.getLevel().getServer(), hub, java.util.Map.<UUID, net.minecraft.server.level.ServerPlayer>of()::get, Runnable::run);
        var slot = now(panel.read(id)).orElseThrow().area("ender_chest").orElseThrow().slot(4);
        h.assertTrue(slot.locked() && slot.stack().is(Items.SHULKER_BOX) && !holdsLiveTotem(slot.stack()), "locked, and its copy holds only an inert husk");
        var deep = now(panel.read(id)).orElseThrow().area("ender_chest").orElseThrow().slot(5);
        h.assertTrue(deep.locked() && !holdsLiveTotem(deep.stack()), "a totem two boxes deep too");
        var live = now(new com.mrgregles.bsp_inventorysync.panel.PanelService(h.getLevel().getServer(), null, java.util.Map.of(id, p)::get, Runnable::run).read(id)).orElseThrow();
        h.assertTrue(live.area("ender_chest").orElseThrow().slot(4).locked() && !holdsLiveTotem(live.area("ender_chest").orElseThrow().slot(4).stack()), "live too");
        h.assertTrue(holdsLiveTotem(p.getEnderChestInventory().getItem(4)), "the player's own box is untouched");
        h.succeed();
    }
}
