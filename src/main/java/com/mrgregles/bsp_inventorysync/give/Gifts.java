package com.mrgregles.bsp_inventorysync.give;

import com.mrgregles.bsp_inventorysync.data.ItemRules;
import com.mrgregles.bsp_inventorysync.editor.ItemCatalog;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/** What {@code /invsync give} refuses, as in vanilla {@code /give} plus this mod's item rules. */
public final class Gifts {
    private Gifts() {}

    /** Null when the item may be given in that number, else the reason. */
    @Nullable
    public static String refusal(ItemStack item, int count) {
        String name = item.getHoverName().getString();
        if (!ItemCatalog.offered(item) || ItemRules.excluded(item)) {
            return name + " cannot be given here: Shatter Totems come from BSP-Core, and this item never travels between servers.";
        }
        int max = Math.max(1, item.getMaxStackSize()) * 100;
        if (count > max) {
            return "At most " + max + " " + name + " at a time (100 stacks).";
        }
        return null;
    }
}
