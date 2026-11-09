package com.mrgregles.bsp_inventorysync.api;

import javax.annotation.Nullable;
import java.util.List;

/**
 * One group of slots in a {@link PlayerItems}: slot {@code i} of the area is {@code slots().get(i)}, empty
 * slots included. Area ids, and what {@link #key()} is for each:
 * <ul>
 * <li>{@code inventory}: 36 slots, 0 to 8 the hotbar, 9 to 35 the rest;</li>
 * <li>{@code armor}: 4 slots, 0 feet, 1 legs, 2 chest, 3 head;</li>
 * <li>{@code offhand} and {@code cursor} (the item held on the mouse): one slot each;</li>
 * <li>{@code ender_chest}: 27 slots in vanilla;</li>
 * <li>{@code curios/<type>} and {@code curios_cosmetic/<type>}: a Curios slot type ({@code ring}, {@code back}),
 * the key is the type;</li>
 * <li>{@code backpack/<contentsUuid>}: a Sophisticated Backpack's item slots (not its upgrades), the key is the
 * contents id, {@link #place()} where the backpack sits; a backpack never opened has no slots yet (0);</li>
 * <li>{@code dank/<frequency>}: a Dank Storage dank, the key is its frequency on the server the data comes from,
 * {@link #place()} where it sits.</li>
 * </ul>
 *
 * @param addable   whether {@link Edit.Add} may put items here: the inventory and the ender chest; backpacks and
 *                  danks too when the read is live (in a snapshot their filters are not known, so they only give
 *                  items up). Armour, offhand, cursor and Curios slots can only be emptied
 * @param removable whether {@link Edit.Remove} may take from here: every area but a linked-storage backpack, whose
 *                  items live in another backpack (read-only)
 * @param place     where a backpack or dank sits; null for the other areas, and for a backpack or dank whose item
 *                  was not found among the player's items
 */
public record ItemArea(String id, Kind kind, boolean addable, boolean removable, @Nullable ItemPlace place, List<ItemSlot> slots) {
    public enum Kind { INVENTORY, ARMOR, OFFHAND, CURSOR, ENDER_CHEST, CURIOS, CURIOS_COSMETIC, BACKPACK, DANK }

    public ItemArea {
        slots = List.copyOf(slots);
    }

    public int size() {
        return slots.size();
    }

    public ItemSlot slot(int index) {
        return slots.get(index);
    }

    /** The part of the id after the slash: the Curios type, the backpack's contents id, the dank's frequency; empty for the rest. */
    public String key() {
        int slash = id.indexOf('/');
        return slash < 0 ? "" : id.substring(slash + 1);
    }
}
