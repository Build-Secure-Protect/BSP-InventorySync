package com.mrgregles.bsp_inventorysync.api.event;

import com.mrgregles.bsp_inventorysync.storage.SaveCause;
import net.minecraftforge.eventbus.api.Event;

import java.util.UUID;

/**
 * A player's snapshot has been written to the shared store. Posted on the server thread once the store
 * confirms, which may be after the player has left (so it carries the id and name, not the player).
 * Notification only, never cancelable.
 */
public class InventorySavedEvent extends Event {
    private final UUID player;
    private final String playerName;
    private final SaveCause cause;
    private final long version;
    private final int bytes;
    private final String serverId;

    public InventorySavedEvent(UUID player, String playerName, SaveCause cause, long version, int bytes, String serverId) {
        this.player = player;
        this.playerName = playerName;
        this.cause = cause;
        this.version = version;
        this.bytes = bytes;
        this.serverId = serverId;
    }

    public UUID getPlayer() {
        return player;
    }

    public String getPlayerName() {
        return playerName;
    }

    /** Why it was saved: logout, transfer, death, auto-save, world save, admin. */
    public SaveCause getCause() {
        return cause;
    }

    /** The snapshot's per-player version; higher is newer. */
    public long getVersion() {
        return version;
    }

    /** Compressed size of the snapshot. */
    public int getBytes() {
        return bytes;
    }

    /** This server's id, as BSP-Core's {@code storage.serverId}. */
    public String getServerId() {
        return serverId;
    }
}
