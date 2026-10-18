package com.mrgregles.bsp_inventorysync.sync;

/**
 * How an arrival waits (D5): for at most {@code timeoutMs} for the source server to release the
 * player; then either the last known snapshot is applied and the player told (the default), or the
 * login is refused so they can try again.
 */
public record ArrivalPolicy(long timeoutMs, boolean kickOnTimeout) {
    public static final ArrivalPolicy DEFAULT = new ArrivalPolicy(10_000, false);
}
