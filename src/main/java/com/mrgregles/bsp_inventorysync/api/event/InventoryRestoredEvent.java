package com.mrgregles.bsp_inventorysync.api.event;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;

import java.util.List;

/**
 * A snapshot has been applied to a player on this server. Posted on the server thread, once per
 * restore, after every data module has run and after any Shatter Totem found in the player's hands
 * before the restore has been given back through BSP-Core. BSP-Core acts on it (request R3): only
 * now does it hand over pending items and strip coins from an older season, because both touch the
 * inventory the restore has just replaced. Notification only, never cancelable.
 */
public class InventoryRestoredEvent extends PlayerEvent {
    private final long version;
    private final String sourceServer;
    private final boolean fallback;
    private final List<String> failedModules;
    private final int totemsKept;

    public InventoryRestoredEvent(ServerPlayer player, long version, String sourceServer, boolean fallback, List<String> failedModules, int totemsKept) {
        super(player);
        this.version = version;
        this.sourceServer = sourceServer;
        this.fallback = fallback;
        this.failedModules = List.copyOf(failedModules);
        this.totemsKept = totemsKept;
    }

    @Override
    public ServerPlayer getEntity() {
        return (ServerPlayer) super.getEntity();
    }

    /** The version of the snapshot applied; 0 when no snapshot existed and the player keeps what they have. */
    public long getVersion() {
        return version;
    }

    /** The server that took the snapshot (its {@code storage.serverId}); empty when none was applied. */
    public String getSourceServer() {
        return sourceServer;
    }

    /** True when the arrival wait timed out and the last known snapshot was applied instead of a confirmed one (D5). */
    public boolean isFallback() {
        return fallback;
    }

    /** Data modules that threw while loading; their part of the player's data is unchanged. */
    public List<String> getFailedModules() {
        return failedModules;
    }

    /** Shatter Totems found on the player before the restore and given back after it. */
    public int getTotemsKept() {
        return totemsKept;
    }
}
