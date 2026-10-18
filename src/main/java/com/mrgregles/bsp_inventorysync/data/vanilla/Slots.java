package com.mrgregles.bsp_inventorysync.data.vanilla;

import com.mrgregles.bsp_inventorysync.data.ItemCodec;
import com.mrgregles.bsp_inventorysync.data.ItemRules;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/** A container's slots as a list of {@code {Slot, Item}} compounds; empty and excluded slots are left out. */
final class Slots {
    private static final String SLOT = "Slot";
    private static final String ITEM = "Item";

    private Slots() {}

    static ListTag save(Container container) {
        ListTag list = new ListTag();
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty() || ItemRules.excluded(stack)) {
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.putInt(SLOT, i);
            entry.put(ITEM, ItemCodec.save(stack));
            list.add(entry);
        }
        return list;
    }

    /** Clears the container and fills the listed slots; slots beyond the container's size are dropped. */
    static int load(Container container, ListTag list) {
        container.clearContent();
        int placed = 0;
        for (Tag t : list) {
            if (!(t instanceof CompoundTag entry)) {
                continue;
            }
            int slot = entry.getInt(SLOT);
            if (slot < 0 || slot >= container.getContainerSize()) {
                continue;
            }
            ItemStack stack = ItemCodec.load(entry.getCompound(ITEM));
            if (!stack.isEmpty()) {
                container.setItem(slot, stack);
                placed++;
            }
        }
        return placed;
    }
}
