package com.mrgregles.bsp_inventorysync.api;

import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/**
 * One slot of an {@link ItemArea}.
 *
 * @param stack       a copy of what is in it (empty when nothing); counts above 64 in backpacks and danks are real
 * @param missingItem the item's id when its mod is not on the server that read it: {@code stack} is then this mod's
 *                    placeholder (a barrier named "Missing item: ..."), which keeps the real item and may be removed
 *                    like any other; null otherwise
 * @param locked      a live Shatter Totem, or an item holding one anywhere inside it (a shulker box, a backpack's
 *                    stored contents): shown, never added or removed through the api (D29). {@code stack} is then a
 *                    copy with every such totem made an inert husk: never place or give it
 */
public record ItemSlot(int index, ItemStack stack, @Nullable String missingItem, boolean locked) {
    public boolean isEmpty() {
        return stack.isEmpty();
    }
}
