package com.mrgregles.bsp_inventorysync.give;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Room in the main inventory (hotbar and the 27 slots above it). Gifts are put in here rather than through
 * {@code Inventory.add}, which deletes whatever does not fit when the player is in creative mode.
 */
public final class Room {
    public static final int MAIN = 36;

    private Room() {}

    /** A copy of the main inventory, to try placements on. */
    static List<ItemStack> of(ServerPlayer p) {
        List<ItemStack> out = new ArrayList<>(MAIN);
        for (int i = 0; i < MAIN; i++) {
            out.add(p.getInventory().getItem(i).copy());
        }
        return out;
    }

    /** Places the whole stack on the copy (merging first, then empty slots) and returns true; or changes nothing and returns false. */
    static boolean place(List<ItemStack> slots, ItemStack stack) {
        int max = Math.min(stack.getMaxStackSize(), 64);
        int space = 0;
        for (ItemStack s : slots) {
            space += s.isEmpty() ? max : ItemStack.isSameItemSameTags(s, stack) ? Math.max(0, max - s.getCount()) : 0;
        }
        if (space < stack.getCount()) {
            return false;
        }
        int left = stack.getCount();
        for (int i = 0; i < slots.size() && left > 0; i++) { // merge first, as the real insert does
            ItemStack s = slots.get(i);
            if (!s.isEmpty() && ItemStack.isSameItemSameTags(s, stack)) {
                int move = Math.min(left, max - s.getCount());
                if (move > 0) {
                    s.grow(move);
                    left -= move;
                }
            }
        }
        for (int i = 0; i < slots.size() && left > 0; i++) {
            if (slots.get(i).isEmpty()) {
                int move = Math.min(left, max);
                slots.set(i, stack.copyWithCount(move));
                left -= move;
            }
        }
        return true;
    }

    /** How many of these stacks would not fit, one after the other: the free slots still needed (about one per stack). */
    static int missing(ServerPlayer p, List<ItemStack> stacks) {
        List<ItemStack> room = of(p);
        int n = 0;
        for (ItemStack s : stacks) {
            if (!place(room, s)) {
                n++;
            }
        }
        return n;
    }

    /** Puts as much of the stack as fits into the real main inventory; the stack keeps the rest. Returns how many went in. */
    public static int insert(Inventory inv, ItemStack stack) {
        int max = Math.min(stack.getMaxStackSize(), 64);
        int before = stack.getCount();
        for (int i = 0; i < MAIN && !stack.isEmpty(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && ItemStack.isSameItemSameTags(s, stack)) {
                int move = Math.min(stack.getCount(), max - s.getCount());
                if (move > 0) {
                    s.grow(move);
                    stack.shrink(move);
                    inv.setChanged();
                }
            }
        }
        for (int i = 0; i < MAIN && !stack.isEmpty(); i++) {
            if (inv.getItem(i).isEmpty()) {
                int move = Math.min(stack.getCount(), max);
                inv.setItem(i, stack.copyWithCount(move));
                stack.shrink(move);
            }
        }
        return before - stack.getCount();
    }
}
