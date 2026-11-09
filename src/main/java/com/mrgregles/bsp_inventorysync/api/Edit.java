package com.mrgregles.bsp_inventorysync.api;

import net.minecraft.world.item.ItemStack;

/**
 * One change for {@link InventorySyncApi#edit} (D29). Areas and slots are those of {@link ItemArea}.
 */
public sealed interface Edit permits Edit.Add, Edit.Remove {
    String area();

    /**
     * Puts {@code stack.getCount()} of the item into an addable area, first onto stacks of the same item, then
     * into empty slots: all of it or nothing ({@code NO_ROOM}). Refused for a Shatter Totem, for an item this
     * mod never saves, for a missing mod's placeholder, and above 100 stacks, as {@code /invsync give} refuses them.
     */
    record Add(String area, ItemStack stack) implements Edit {
        public Add {
            stack = stack.copy();
        }
    }

    /**
     * Takes {@code count} from one slot, which must still hold {@code expected} (the same item and tags as the
     * read showed; its count is not compared) with at least {@code count} of it; otherwise {@code CHANGED}.
     */
    record Remove(String area, int slot, ItemStack expected, int count) implements Edit {
        public Remove {
            expected = expected.copy();
        }
    }

    static Add add(String area, ItemStack stack) {
        return new Add(area, stack);
    }

    static Remove remove(String area, int slot, ItemStack expected, int count) {
        return new Remove(area, slot, expected, count);
    }
}
