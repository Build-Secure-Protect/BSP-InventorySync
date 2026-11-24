package com.mrgregles.bsp_inventorysync.gametest;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.eventbus.api.Event;

import java.util.UUID;

/**
 * GameTests only: an event with the getters of BSP-Core's {@code PlayerResetEvent$Forgotten}, registered by its name as the
 * real one is, so the listener's by-class-name path and its reflection run (this repo builds against a BSP-Core without it).
 */
public class FakeResetEvent extends Event {
    private final MinecraftServer server;
    private final UUID player;
    private final String name;
    private final long resetMs;
    private final boolean origin;

    public FakeResetEvent() {
        this(null, null, "", 0, false);
    }

    public FakeResetEvent(MinecraftServer server, UUID player, String name, long resetMs, boolean origin) {
        this.server = server;
        this.player = player;
        this.name = name;
        this.resetMs = resetMs;
        this.origin = origin;
    }

    public MinecraftServer getServer() {
        return server;
    }

    public UUID getPlayer() {
        return player;
    }

    public String getName() {
        return name;
    }

    public long getResetMs() {
        return resetMs;
    }

    public boolean isOrigin() {
        return origin;
    }

    public String getBy() {
        return "Test";
    }
}
