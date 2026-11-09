package com.mrgregles.bsp_inventorysync.api;

/**
 * The answer to {@link InventorySyncApi#returnGrave} (D30). Nothing was taken from the grave unless the outcome is
 * {@code OK} or {@code PARTIAL}.
 *
 * @param message  one plain line for staff
 * @param returned the stacks put back into the player's inventory
 * @param left     the stacks that did not fit: they stay in the grave, which stays recoverable with {@code /grave}
 * @param xp       the experience points given back
 * @param serverId {@code ONLINE}: the server the player is on, where the return must be made; otherwise this server (empty when
 *                 the sync is off here)
 */
public record GraveReturn(Outcome outcome, String message, int returned, int left, int xp, String serverId) {
    public enum Outcome {
        /** Everything is back in the player's inventory. */
        OK,
        /** Part of it, maybe none: {@code left} stacks did not fit and wait for them, recoverable with {@code /grave} (in the grave, or in a new one the message names). */
        PARTIAL,
        /** The player is offline: they get it back at their next login, on any server. */
        QUEUED,
        /** The player is online on another server ({@code serverId}): return it there. */
        ONLINE,
        /** Try again in a moment: the grave is still being saved or changed meanwhile, or the player is not alive. */
        BUSY,
        /** No grave of theirs with that id (or it has been emptied). */
        NOT_FOUND,
        /** A headstone still stands in the world: open it where it stands. */
        REFUSED,
        /** Anything else: graves off, the database failing, a login refused here, an offline return with the sync off. */
        FAILED
    }

    /** Accepted: returned now, wholly ({@code OK}) or in part ({@code PARTIAL}, possibly nothing that fitted), or queued for their next login. */
    public boolean ok() {
        return outcome == Outcome.OK || outcome == Outcome.PARTIAL || outcome == Outcome.QUEUED;
    }
}
