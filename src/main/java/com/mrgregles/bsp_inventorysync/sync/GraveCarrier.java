package com.mrgregles.bsp_inventorysync.sync;

import com.mrgregles.bsp_inventorysync.storage.Grave;

import java.util.List;
import java.util.UUID;

/**
 * New graves ride on the player's next snapshot (D25, the audit of 2026-11-07): a grave made on a synced server is
 * written to the database in the same transaction as a snapshot of the player taken after the death (empty-handed),
 * so a crash, a failed write or a lost answer can never leave one without the other: the items are never both in
 * the grave and back in the player's inventory, and never in neither. The grave service holds the graves until then.
 * Server thread.
 */
public interface GraveCarrier {
    /** The player's graves not yet in the database: they go with this save. */
    List<Grave> pendingFor(UUID player);

    /** A save carrying these graves has landed. */
    void landed(UUID player, List<Grave> graves);

    /**
     * The player's logout save failed: the database still holds their snapshot from before the death, which they will
     * get back at their next login with the items, so their graves not yet in the database are dropped (logged).
     */
    void logoutSaveFailed(UUID player);
}
