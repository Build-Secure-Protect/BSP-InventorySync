package com.mrgregles.bsp_inventorysync.api;

import java.util.List;

/**
 * What one grave holds, from {@link InventorySyncApi#graveItems} (Server Utilities' request SU-IS3): what a return would
 * give back. Read-only: every area has {@code addable} and {@code removable} false.
 *
 * @param areas the area {@code grave} first, one slot per stack the grave holds (copies with all their data;
 *              {@code missingItem} and {@code locked} as in {@link InventorySyncApi#read}, a Shatter Totem as an inert
 *              copy), then a {@code backpack/<contentsUuid>} or {@code dank/<frequency>} area for each backpack and dank
 *              inside it, each with its {@link ItemArea#place()} (area {@code grave}, the slot, the containers in between;
 *              a backpack inside another backpack sits in that one's area)
 * @param xp    the experience points the grave holds
 *
 * <p>Backpacks and danks are those whose item is still in the grave (directly, inside a shulker box, or inside another
 * backpack); stored contents of one already taken are not listed. Their contents are as the grave stored them at the
 * death: a return on another server gives exactly these; on the server where the player died it gives what that
 * server's storage holds then, which differs only if a duplicated backpack or another dank on the same frequency
 * changed it since.
 */
public record GraveItems(List<ItemArea> areas, int xp) {
    public GraveItems {
        areas = List.copyOf(areas);
    }
}
