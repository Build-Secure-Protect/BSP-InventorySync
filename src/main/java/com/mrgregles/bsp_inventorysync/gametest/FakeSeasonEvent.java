package com.mrgregles.bsp_inventorysync.gametest;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.eventbus.api.Event;

/**
 * GameTests only: an event with the getters of BSP-Core's {@code SeasonEvent$Ended} from the season reset on, registered by its
 * name as the real one is, so the listener's by-class-name path and its reflection run (this repo builds against a BSP-Core
 * whose event has none of {@code isSeasonReset()}, {@code isOrigin()} and {@code getResetMs()}).
 */
public class FakeSeasonEvent extends Event {
    private final MinecraftServer server;
    private final boolean seasonReset;
    private final boolean origin;
    private final long resetMs;

    public FakeSeasonEvent() {
        this(null, false, false, 0);
    }

    public FakeSeasonEvent(MinecraftServer server, boolean seasonReset, boolean origin, long resetMs) {
        this.server = server;
        this.seasonReset = seasonReset;
        this.origin = origin;
        this.resetMs = resetMs;
    }

    public long getResetMs() {
        return resetMs;
    }

    public MinecraftServer getServer() {
        return server;
    }

    public boolean isSeasonReset() {
        return seasonReset;
    }

    public boolean isOrigin() {
        return origin;
    }

    public boolean isFullReset() {
        return true;
    }

    public int getEndedNumber() {
        return 4;
    }

    public String getBy() {
        return "Test";
    }

    /** The event of a BSP-Core from before the season reset: a full reset, with no {@code isSeasonReset()}, {@code isOrigin()} or {@code getResetMs()} to read. */
    public static class Older extends Event {
        private final MinecraftServer server;

        public Older() {
            this(null);
        }

        public Older(MinecraftServer server) {
            this.server = server;
        }

        public MinecraftServer getServer() {
            return server;
        }

        public boolean isFullReset() {
            return true;
        }
    }
}
