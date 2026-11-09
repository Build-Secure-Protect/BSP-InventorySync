package com.mrgregles.bsp_inventorysync.panel;

import net.minecraft.world.item.ItemStack;

/**
 * The slots of one area, live on an online player or inside a decoded snapshot, as the staff panel's
 * edits see them (D29). {@link #get} returns a stack the caller may read but must not change.
 */
interface SlotView {
    int size();

    ItemStack get(int slot);

    void set(int slot, ItemStack stack);

    /** How many of {@code stack}'s item the slot can hold; 0 when it does not take that item. */
    int limit(int slot, ItemStack stack);
}
