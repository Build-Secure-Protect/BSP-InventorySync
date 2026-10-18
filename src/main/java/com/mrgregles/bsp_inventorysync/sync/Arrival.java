package com.mrgregles.bsp_inventorysync.sync;

import java.util.UUID;

/** What happened when a player arrived; the last one per player is kept for {@code /invsync status} and the tests. */
public record Arrival(UUID player, Outcome outcome, long version, long waitedMs) {
    public enum Outcome {
        /** The source released the player in time and the newest snapshot was applied (or none existed, on a first login). */
        CLEAN,
        /** The wait timed out; the last known snapshot was applied and the player told. */
        FALLBACK,
        /** The wait timed out and the login was refused. */
        KICKED,
        /** The store could not be read; the player keeps what this server had for them. */
        UNAVAILABLE,
        /** The player left before the restore. Nothing was applied or saved. */
        LEFT
    }
}
