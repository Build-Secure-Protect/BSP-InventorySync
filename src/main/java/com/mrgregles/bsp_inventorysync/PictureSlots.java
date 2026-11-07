package com.mrgregles.bsp_inventorysync;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The top part of this mod's chest screens (the grave, the waiting gifts) only shows pictures of items that live
 * elsewhere. The screens handle every click on it themselves; these slots also refuse taking and placing, so a mod
 * that moves items between slots without a click (a sorting or deposit button) can never take a copy out or put
 * something in.
 */
public final class PictureSlots {
    private PictureSlots() {}

    /** Replaces the menu's first {@code count} slots with ones nothing can take from or put into. */
    public static void lock(AbstractContainerMenu menu, int count) {
        for (int i = 0; i < count && i < menu.slots.size(); i++) {
            Slot old = menu.slots.get(i);
            Slot locked = new Slot(old.container, old.getContainerSlot(), old.x, old.y) {
                @Override
                public boolean mayPickup(Player player) {
                    return false;
                }

                @Override
                public boolean mayPlace(ItemStack stack) {
                    return false;
                }
            };
            locked.index = i;
            menu.slots.set(i, locked);
        }
    }
}
