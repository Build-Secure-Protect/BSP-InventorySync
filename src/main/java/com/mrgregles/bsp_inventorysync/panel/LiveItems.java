package com.mrgregles.bsp_inventorysync.panel;

import com.mrgregles.bsp_inventorysync.api.ItemArea;
import com.mrgregles.bsp_inventorysync.compat.BspCore;
import com.mrgregles.bsp_inventorysync.compat.Curios;
import com.mrgregles.bsp_inventorysync.compat.DankStorage;
import com.mrgregles.bsp_inventorysync.compat.SophisticatedBackpacks;
import com.mrgregles.bsp_inventorysync.data.ItemCodec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandlerModifiable;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * The staff panel's view of a player online on this server (D29 and its audit). Reading builds the player's
 * items in the snapshot's form and reads it as {@link SnapshotItems} does, so a live read and a stored one look
 * the same. A live Shatter Totem is never written there: its slot holds an inert copy (a spent husk with the
 * totem's look) marked locked. Changes go to the live objects: the inventory and ender chest, Curios' handler,
 * the backpack's own wrapper, the dank's inventory. Server thread.
 */
final class LiveItems {
    private LiveItems() {}

    static List<ItemArea> read(ServerPlayer player) {
        return SnapshotItems.read(root(player), true);
    }

    /** The player's items now, in the snapshot's form (totems as locked inert copies). */
    static CompoundTag root(ServerPlayer player) {
        CompoundTag root = new CompoundTag();
        CompoundTag modules = new CompoundTag();
        CompoundTag inv = new CompoundTag();
        inv.put("Items", slots(player.getInventory()));
        ItemStack cursor = player.containerMenu.getCarried();
        if (!cursor.isEmpty()) {
            inv.put("Cursor", ItemCodec.save(shown(cursor)));
            if (BspCore.isLiveTotem(cursor)) {
                inv.putBoolean(SnapshotItems.Cursor.CURSOR_LOCKED, true);
            }
        }
        modules.put("inventory", inv);
        CompoundTag ender = new CompoundTag();
        ender.put("Items", slots(player.getEnderChestInventory()));
        modules.put("ender_chest", ender);
        if (Curios.present()) {
            CompoundTag out = new CompoundTag();
            new Curios.Module().save(player, out);
            modules.put("curios", out);
        }
        if (SophisticatedBackpacks.present()) {
            CompoundTag out = new CompoundTag();
            new SophisticatedBackpacks.Module().save(player, out);
            modules.put("backpacks", out);
        }
        if (DankStorage.present()) {
            CompoundTag out = new CompoundTag();
            new DankStorage.Module().save(player, out);
            modules.put("dank", out);
        }
        root.put("Modules", modules);
        return root;
    }

    /** Every non-empty slot in this mod's stored form; a live totem as its locked inert copy. */
    private static ListTag slots(Container c) {
        ListTag list = new ListTag();
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (!s.isEmpty()) {
                CompoundTag entry = new CompoundTag();
                entry.putInt("Slot", i);
                entry.put("Item", ItemCodec.save(shown(s)));
                if (BspCore.isLiveTotem(s)) {
                    entry.putBoolean(SnapshotItems.LOCKED, true);
                }
                list.add(entry);
            }
        }
        return list;
    }

    /** What a read may hand out: the stack itself, or for a live totem an inert copy (spent, nothing else of its identity). */
    private static ItemStack shown(ItemStack s) {
        if (!BspCore.isLiveTotem(s)) {
            return s;
        }
        ItemStack husk = s.copyWithCount(s.getCount());
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("Spent", true);
        husk.setTag(tag);
        return husk;
    }

    /** The live slots of an area, or null when the player has no such area now. */
    @Nullable
    static SlotView view(ServerPlayer player, String id) {
        ItemArea.Kind kind = SnapshotItems.kind(id);
        if (kind == null) {
            return null;
        }
        String key = id.indexOf('/') < 0 ? "" : id.substring(id.indexOf('/') + 1);
        return switch (kind) {
            case INVENTORY -> new ContainerView(player.getInventory(), 0, 36);
            case ARMOR -> new ContainerView(player.getInventory(), 36, 4);
            case OFFHAND -> new ContainerView(player.getInventory(), 40, 1);
            case ENDER_CHEST -> new ContainerView(player.getEnderChestInventory(), 0, player.getEnderChestInventory().getContainerSize());
            case CURSOR -> new CursorView(player);
            case CURIOS, CURIOS_COSMETIC -> {
                IItemHandlerModifiable h = Curios.liveStacks(player, key, kind == ItemArea.Kind.CURIOS_COSMETIC);
                yield h == null ? null : new HandlerView(h, false);
            }
            case BACKPACK -> {
                UUID uuid;
                try {
                    uuid = UUID.fromString(key);
                } catch (IllegalArgumentException e) {
                    yield null;
                }
                IItemHandlerModifiable h = SophisticatedBackpacks.liveContents(player, uuid);
                yield h == null ? null : new HandlerView(h, true);
            }
            case DANK -> {
                int frequency;
                try {
                    frequency = Integer.parseInt(key);
                } catch (NumberFormatException e) {
                    yield null;
                }
                DankStorage.Live d = DankStorage.live(player, frequency);
                yield d == null ? null : new DankView(d);
            }
        };
    }

    /** Tells the player's client what changed. */
    static void broadcast(ServerPlayer player) {
        player.inventoryMenu.broadcastChanges();
        if (player.containerMenu != player.inventoryMenu) {
            player.containerMenu.broadcastChanges();
        }
    }

    private record ContainerView(Container c, int offset, int size) implements SlotView {
        @Override
        public ItemStack get(int slot) {
            return c.getItem(offset + slot);
        }

        @Override
        public void set(int slot, ItemStack stack) {
            c.setItem(offset + slot, stack);
            c.setChanged();
        }

        @Override
        public int limit(int slot, ItemStack stack) {
            return Math.min(c.getMaxStackSize(), stack.getMaxStackSize());
        }
    }

    private record CursorView(ServerPlayer player) implements SlotView {
        @Override
        public int size() {
            return 1;
        }

        @Override
        public ItemStack get(int slot) {
            return player.containerMenu.getCarried();
        }

        @Override
        public void set(int slot, ItemStack stack) {
            player.containerMenu.setCarried(stack);
        }

        @Override
        public int limit(int slot, ItemStack stack) {
            return stack.getMaxStackSize();
        }
    }

    /** A Curios row or a backpack's slots, through the mod's own handler. */
    private record HandlerView(IItemHandlerModifiable h, boolean backpack) implements SlotView {
        @Override
        public int size() {
            return h.getSlots();
        }

        @Override
        public ItemStack get(int slot) {
            return h.getStackInSlot(slot);
        }

        @Override
        public void set(int slot, ItemStack stack) {
            h.setStackInSlot(slot, stack);
        }

        @Override
        public int limit(int slot, ItemStack stack) {
            return backpack ? SophisticatedBackpacks.stackLimit(h, slot, stack) : stack.getMaxStackSize();
        }
    }

    private record DankView(DankStorage.Live d) implements SlotView {
        @Override
        public int size() {
            return d.size();
        }

        @Override
        public ItemStack get(int slot) {
            return d.get(slot);
        }

        @Override
        public void set(int slot, ItemStack stack) {
            d.set(slot, stack);
        }

        @Override
        public int limit(int slot, ItemStack stack) {
            return d.limit(slot, stack);
        }
    }
}
