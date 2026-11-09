package com.mrgregles.bsp_inventorysync.api;

import java.util.UUID;

/**
 * One grave of a player, from {@link InventorySyncApi#graves} (D30). Emptied graves are not listed.
 *
 * @param shortId     the id's first eight characters: what {@code /invsync grave ...} and {@link InventorySyncApi#returnGrave} take
 * @param serverId    the server where the player died
 * @param dimension   the dimension's id, and {@code x y z} where the headstone stands (or the death, for one that could not be placed)
 * @param placed      whether a headstone stands in the world
 * @param archived    put away: nobody opened it for {@code graves.archiveAfterDays}; only admins see it
 * @param diedAt      when the player died, and {@code touchedAt} when it was last opened (ms since the epoch)
 * @param recoverable whether its owner may recover it from anywhere now with {@code /grave}
 * @param returnable  whether an admin may return it ({@link InventorySyncApi#returnGrave}): one that could not be placed, or one put
 *                    away; a standing headstone is opened where it stands
 */
public record GraveInfo(UUID id, String shortId, String serverId, String dimension, int x, int y, int z, boolean placed, boolean archived,
                        long diedAt, long touchedAt, int stacks, int xp, boolean recoverable, boolean returnable) {}
