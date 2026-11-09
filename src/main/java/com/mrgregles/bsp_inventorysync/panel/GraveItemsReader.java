package com.mrgregles.bsp_inventorysync.panel;

import com.mrgregles.bsp_inventorysync.api.ItemArea;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** The grave package's way into the staff panel's reader (SU-IS3): one grave's stacks and extras as read-only areas. */
public final class GraveItemsReader {
    private GraveItemsReader() {}

    public static List<ItemArea> read(List<ItemStack> stacks, CompoundTag extras) {
        return SnapshotItems.readGrave(stacks, extras);
    }
}
