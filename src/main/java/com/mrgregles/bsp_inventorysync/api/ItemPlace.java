package com.mrgregles.bsp_inventorysync.api;

import java.util.List;

/**
 * Where a backpack or dank sits, for a label such as "in ender chest" or "inside Shulker Box".
 *
 * @param area   the area of the slot holding it ({@code inventory}, {@code ender_chest}, {@code curios/back}, or
 *               {@code backpack/<id>} for a backpack inside another backpack)
 * @param slot   that slot's index
 * @param inside the item ids of the containers between that slot and the backpack, outermost first; empty when it
 *               sits right in the slot. A backpack in a shulker box in inventory slot 7: {@code inventory}, 7,
 *               {@code [minecraft:shulker_box]}
 */
public record ItemPlace(String area, int slot, List<String> inside) {
    public ItemPlace {
        inside = List.copyOf(inside);
    }
}
