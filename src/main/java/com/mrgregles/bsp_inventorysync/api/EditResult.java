package com.mrgregles.bsp_inventorysync.api;

/**
 * The answer to {@link InventorySyncApi#edit}. Nothing was changed unless the outcome is {@code OK}.
 *
 * @param message       one plain line for staff
 * @param version       {@code OK}: the snapshot the change was saved as; 0 when it could not be saved yet (it is then
 *                      saved with the player's next save), and 0 with the sync off here, where this mod saves nothing
 * @param pinnedVersion {@code OK}: the snapshot of the state before the change, pinned, so
 *                      {@code /invsync restore <player> <version>} undoes it; 0 when it could not be saved or pinned
 *                      (the message says which)
 * @param serverId      {@code ONLINE}: the server holding the player, where the change must be made; otherwise this server
 */
public record EditResult(Outcome outcome, String message, long version, long pinnedVersion, String serverId) {
    public enum Outcome {
        /** Done. */
        OK,
        /** The player is online on another server ({@code serverId}): make the change there. */
        ONLINE,
        /** The data moved since it was read: a newer snapshot, the slot no longer holds the expected item, or the area is gone. Read again. */
        CHANGED,
        /** Try again in a moment: their data is arriving here, or this server is saving them (a logout save on its way) or writing another change of theirs. */
        BUSY,
        /** An add did not fit. */
        NO_ROOM,
        /** Not allowed: a Shatter Totem (also inside an item), an item carrying a backpack's or dank's id, an item that never travels, a missing mod's item, an area that only takes removals, a linked backpack. */
        REFUSED,
        /** Anything else: no saved data, no such area or slot, a section their snapshot lacks, a login refused here, the server stopping, the database failing or not answering in time. */
        FAILED
    }

    public boolean ok() {
        return outcome == Outcome.OK;
    }
}
