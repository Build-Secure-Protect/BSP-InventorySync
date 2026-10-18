package com.mrgregles.bsp_inventorysync.storage;

import java.util.UUID;

/**
 * Where a player is, as far as the shared storage knows: online on {@code serverId} since {@code since},
 * or offline and last seen on {@code serverId}. A server refuses a login while the player is marked
 * online on another server (D5), and marks every player it held offline when it starts and stops.
 */
public record Presence(UUID player, boolean online, String serverId, long since) {
    public boolean onlineElsewhere(String here) {
        return online && !serverId.equals(here);
    }
}
