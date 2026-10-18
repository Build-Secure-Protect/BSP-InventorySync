package com.mrgregles.bsp_inventorysync.gametest;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.api.event.InventoryRestoredEvent;
import com.mrgregles.bsp_inventorysync.api.event.InventorySavedEvent;
import com.mrgregles.bsp_inventorysync.compat.BspCore;
import com.mrgregles.bsp_inventorysync.data.ItemCodec;
import com.mrgregles.bsp_inventorysync.data.PlayerSnapshotCodec;
import com.mrgregles.bsp_inventorysync.data.vanilla.InventoryModule;
import com.mrgregles.bsp_inventorysync.storage.MemoryStore;
import com.mrgregles.bsp_inventorysync.storage.SaveCause;
import com.mrgregles.bsp_inventorysync.storage.Snapshot;
import com.mrgregles.bsp_inventorysync.sync.SyncService;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The totem rules of D4, against BSP-Core's real Shatter Totem item: a live totem is never in a
 * snapshot; a totem handed back during login survives the restore and is given back through
 * BSP-Core; the api events fire with the right content. The store is the in-memory one.
 */
@GameTestHolder(BSPInventorySync.MODID)
@PrefixGameTestTemplate(false)
public class TotemRuleTests {
    private static final String EMPTY = "empty";
    private static final String HUB = "hub";

    /** A live Shatter Totem, as BSP-Core's own tests make one; identities are optional for the rule. */
    static ItemStack totem(GameTestHelper h) {
        Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse("bsp_core:shatter_totem"));
        h.assertTrue(item != null && item != Items.AIR, "BSP-Core's shatter_totem item is registered");
        ItemStack stack = new ItemStack(item);
        stack.getOrCreateTag().putUUID("Instance", UUID.randomUUID());
        return stack;
    }

    /** A spent husk, as BSP-Core's {@code TotemIdentity.spend} leaves it: only the totem id and the Spent flag. */
    static ItemStack spentTotem(GameTestHelper h) {
        ItemStack stack = totem(h);
        CompoundTag fresh = new CompoundTag();
        fresh.putUUID("TotemId", UUID.randomUUID());
        fresh.putBoolean("Spent", true);
        stack.setTag(fresh);
        return stack;
    }

    static int liveTotems(ServerPlayer p) {
        int n = 0;
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (BspCore.isLiveTotem(inv.getItem(i))) {
                n++;
            }
        }
        return n + (BspCore.isLiveTotem(p.containerMenu.getCarried()) ? 1 : 0);
    }

    /** Collects the api events while registered. */
    static final class Events {
        final List<InventorySavedEvent> saved = new ArrayList<>();
        final List<InventoryRestoredEvent> restored = new ArrayList<>();

        @SubscribeEvent
        public void onSaved(InventorySavedEvent e) {
            saved.add(e);
        }

        @SubscribeEvent
        public void onRestored(InventoryRestoredEvent e) {
            restored.add(e);
        }
    }

    @GameTest(template = EMPTY)
    public static void a_live_totem_is_never_in_a_snapshot_but_a_spent_one_is(GameTestHelper h) {
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        h.assertTrue(BspCore.isLiveTotem(totem(h)) && !BspCore.isLiveTotem(spentTotem(h)) && !BspCore.isLiveTotem(new ItemStack(Items.DIRT)), "the rule: " + BspCore.sources());
        Inventory inv = p.getInventory();
        inv.setItem(0, new ItemStack(Items.DIRT));
        inv.setItem(3, totem(h));
        inv.setItem(7, spentTotem(h));
        inv.setItem(40, totem(h)); // offhand
        p.containerMenu.setCarried(totem(h));

        CompoundTag snapshot = PlayerSnapshotCodec.capture(p);
        CompoundTag module = PlayerSnapshotCodec.module(snapshot, InventoryModule.ID);
        ListTag items = module.getList("Items", Tag.TAG_COMPOUND);
        List<Integer> slots = new ArrayList<>();
        for (Tag t : items) {
            slots.add(((CompoundTag) t).getInt("Slot"));
        }
        h.assertTrue(slots.equals(List.of(0, 7)), "only the dirt and the spent husk are saved, got slots " + slots);
        h.assertTrue(!module.contains("Cursor"), "the totem on the cursor is not saved");
        for (Tag t : items) {
            ItemStack back = ItemCodec.load(((CompoundTag) t).getCompound("Item"));
            h.assertTrue(!BspCore.isLiveTotem(back), "no saved item is a live totem, found " + back + " " + back.getTag());
        }
        h.assertTrue(BspCore.isLiveTotem(ItemCodec.load(items.getCompound(1).getCompound("Item"))) == false && items.getCompound(1).getCompound("Item").getCompound("tag").getBoolean("Spent"), "the husk is saved as a spent totem");
        h.assertTrue(liveTotems(p) == 3, "taking the snapshot did not touch the player's totems");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_totem_handed_back_during_login_survives_the_restore(GameTestHelper h) {
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        SyncService sync = new SyncService(h.getLevel().getServer(), new MemoryStore(() -> 42), HUB);
        Events events = new Events();
        MinecraftForge.EVENT_BUS.register(events);
        try {
            Inventory inv = p.getInventory();
            inv.setItem(0, new ItemStack(Items.DIRT, 5));
            inv.setItem(1, new ItemStack(Items.STONE, 9));
            inv.selected = 0;
            Snapshot saved = sync.save(p, SaveCause.TRANSFER).join();
            h.assertTrue(saved.version() == 1 && saved.cause() == SaveCause.TRANSFER, "saved as version 1: " + saved);

            // on the target server: empty inventory, then BSP-Core hands two totems back into the hand slot and a free slot
            inv.clearContent();
            ItemStack first = totem(h);
            ItemStack second = totem(h);
            inv.setItem(0, first.copy());
            inv.setItem(5, second.copy());
            h.assertTrue(liveTotems(p) == 2, "two totems before the restore");

            SyncService.Restored restored = sync.restore(p, saved, false);
            h.assertTrue(restored.totemsKept() == 2 && restored.modules().clean(), "two totems kept, every module applied: " + restored);
            h.assertTrue(inv.getItem(0).is(Items.DIRT) && inv.getItem(0).getCount() == 5 && inv.getItem(1).is(Items.STONE), "the snapshot's items are back in their slots");
            h.assertTrue(liveTotems(p) == 2, "both totems are still with the player, got " + liveTotems(p));
            h.assertTrue(inv.getItem(5).isEmpty() || BspCore.isLiveTotem(inv.getItem(5)), "slot 5 holds a totem or nothing, never a stale item");
            List<UUID> instances = new ArrayList<>();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                if (BspCore.isLiveTotem(inv.getItem(i))) {
                    instances.add(inv.getItem(i).getTag().getUUID("Instance"));
                }
            }
            h.assertTrue(instances.contains(first.getTag().getUUID("Instance")) && instances.contains(second.getTag().getUUID("Instance")), "the very same totems, not copies: " + instances);

            h.assertTrue(events.restored.size() == 1, "one InventoryRestoredEvent, got " + events.restored.size());
            InventoryRestoredEvent e = events.restored.get(0);
            h.assertTrue(e.getEntity() == p && e.getVersion() == 1 && e.getSourceServer().equals(HUB) && !e.isFallback() && e.getFailedModules().isEmpty() && e.getTotemsKept() == 2, "event content");
        } finally {
            MinecraftForge.EVENT_BUS.unregister(events);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_kept_totem_goes_to_the_hand_slot_when_the_snapshot_leaves_it_free(GameTestHelper h) {
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        SyncService sync = new SyncService(h.getLevel().getServer(), new MemoryStore(), HUB);
        Inventory inv = p.getInventory();
        inv.setItem(0, new ItemStack(Items.DIRT));
        inv.selected = 2; // the hand slot is empty in the snapshot
        Snapshot saved = sync.save(p, SaveCause.LOGOUT).join();

        inv.clearContent();
        inv.selected = 0;
        inv.setItem(0, totem(h)); // BSP-Core's hand-back landed in the hand slot of the moment
        SyncService.Restored restored = sync.restore(p, saved, false);
        h.assertTrue(restored.totemsKept() == 1, "one totem kept");
        h.assertTrue(inv.selected == 2, "selected slot restored first");
        h.assertTrue(BspCore.isLiveTotem(inv.getItem(2)), "the totem went to the restored hand slot (BSP-Core's giveBack rule), inventory: " + inv.getItem(0) + ", " + inv.getItem(2));
        h.assertTrue(inv.getItem(0).is(Items.DIRT), "the snapshot's dirt is back in slot 0");
        h.assertTrue(liveTotems(p) == 1, "no duplicate");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void save_posts_the_saved_event_and_restore_latest_round_trips(GameTestHelper h) {
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        MemoryStore store = new MemoryStore(() -> 7);
        SyncService sync = new SyncService(h.getLevel().getServer(), store, HUB);
        Events events = new Events();
        MinecraftForge.EVENT_BUS.register(events);
        try {
            p.getInventory().setItem(4, new ItemStack(Items.GOLD_INGOT, 3));
            sync.save(p, SaveCause.AUTOSAVE).join();
            p.getInventory().setItem(4, new ItemStack(Items.GOLD_INGOT, 8));
            sync.save(p, SaveCause.LOGOUT).join();
            h.assertTrue(events.saved.size() == 2, "two InventorySavedEvents, got " + events.saved.size());
            InventorySavedEvent first = events.saved.get(0);
            InventorySavedEvent second = events.saved.get(1);
            h.assertTrue(first.getVersion() == 1 && first.getCause() == SaveCause.AUTOSAVE && first.getPlayer().equals(p.getUUID()) && first.getServerId().equals(HUB) && first.getBytes() > 20, "first event: v" + first.getVersion() + " " + first.getCause() + " " + first.getBytes() + " bytes");
            h.assertTrue(second.getVersion() == 2 && second.getCause() == SaveCause.LOGOUT && second.getPlayerName().equals(p.getGameProfile().getName()), "second event");
            h.assertTrue(store.history(p.getUUID()).join().size() == 2, "two snapshots in the store");

            p.getInventory().clearContent();
            var restored = sync.restoreLatest(p).join();
            h.assertTrue(restored.isPresent() && restored.get().version() == 2, "the newest snapshot was applied: " + restored);
            h.assertTrue(p.getInventory().getItem(4).getCount() == 8, "with the newest count, got " + p.getInventory().getItem(4));
            h.assertTrue(events.restored.size() == 1 && events.restored.get(0).getVersion() == 2, "one restored event for version 2");

            ServerPlayer fresh = TestPlayers.mockServerPlayer(h);
            fresh.getInventory().setItem(0, new ItemStack(Items.BREAD));
            var none = sync.restoreLatest(fresh).join();
            h.assertTrue(none.isEmpty(), "a player with no snapshot gets nothing applied");
            h.assertTrue(fresh.getInventory().getItem(0).is(Items.BREAD), "and keeps what they have");
            h.assertTrue(events.restored.size() == 2 && events.restored.get(1).getVersion() == 0 && events.restored.get(1).getEntity() == fresh, "the event still fires with version 0 so BSP-Core's deliveries run");
        } finally {
            MinecraftForge.EVENT_BUS.unregister(events);
        }
        h.succeed();
    }
}
