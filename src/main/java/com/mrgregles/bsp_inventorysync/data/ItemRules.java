package com.mrgregles.bsp_inventorysync.data;

import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Items that must never be written into a snapshot. The first and most important rule is BSP-Core's:
 * a live Shatter Totem is never synced (D4; the predicate is added by {@code compat/BspCore} in task
 * IS-3). An excluded item is simply left out, its slot saved as empty.
 */
public final class ItemRules {
    private static final List<Predicate<ItemStack>> EXCLUSIONS = new CopyOnWriteArrayList<>();

    private ItemRules() {}

    public static void addExclusion(Predicate<ItemStack> rule) {
        EXCLUSIONS.add(rule);
    }

    public static boolean removeExclusion(Predicate<ItemStack> rule) {
        return EXCLUSIONS.remove(rule);
    }

    /** True when no snapshot may carry this stack. Empty stacks are not excluded, they are just empty. */
    public static boolean excluded(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        for (Predicate<ItemStack> rule : EXCLUSIONS) {
            if (rule.test(stack)) {
                return true;
            }
        }
        return false;
    }
}
