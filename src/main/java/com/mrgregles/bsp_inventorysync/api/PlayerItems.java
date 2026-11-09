package com.mrgregles.bsp_inventorysync.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A player's items for a staff tool (SU-IS2, D29), from {@link InventorySyncApi#read}.
 *
 * @param player   the player
 * @param online   whether they are online on the network, as this mod's checkout knows it (on this server
 *                 while their data is still arriving counts as online)
 * @param serverId the server holding them when online; otherwise the last server they were on (empty when unknown,
 *                 and for a live read with the sync off here)
 * @param live     true: read from the player on this server just now; false: read from snapshot {@code version}
 * @param version  the version of their newest snapshot (0 when there is none). For an offline player pass it to
 *                 {@link InventorySyncApi#edit}; a live edit does not use it
 * @param savedAt  when that snapshot was saved (milliseconds since the epoch; 0 when there is none)
 * @param areas    every area, in this order: {@code inventory}, {@code armor}, {@code offhand}, {@code cursor},
 *                 {@code ender_chest}, then the Curios slots, backpacks and danks (see {@link ItemArea})
 */
public record PlayerItems(UUID player, boolean online, String serverId, boolean live, long version, long savedAt, List<ItemArea> areas) {
    public PlayerItems {
        areas = List.copyOf(areas);
    }

    /** The area with this id, if the player has it. */
    public Optional<ItemArea> area(String id) {
        return areas.stream().filter(a -> a.id().equals(id)).findFirst();
    }
}
