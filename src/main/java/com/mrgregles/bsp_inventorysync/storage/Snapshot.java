package com.mrgregles.bsp_inventorysync.storage;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * One saved state of a player: the serialised, compressed data plus what is needed to order and
 * manage it. Versions count up per player and never repeat; the newest version is the one a server
 * applies on login. The data array is copied in and out so a snapshot can never change after it was
 * taken.
 */
public final class Snapshot {
    private final UUID player;
    private final long version;
    private final String serverId;
    private final long savedAt;
    private final SaveCause cause;
    private final boolean pinned;
    private final byte[] data;

    public Snapshot(UUID player, long version, String serverId, long savedAt, SaveCause cause, boolean pinned, byte[] data) {
        this.player = Objects.requireNonNull(player, "player");
        this.version = version;
        this.serverId = Objects.requireNonNull(serverId, "serverId");
        this.savedAt = savedAt;
        this.cause = Objects.requireNonNull(cause, "cause");
        this.pinned = pinned;
        this.data = Objects.requireNonNull(data, "data").clone();
    }

    public UUID player() {
        return player;
    }

    /** Per-player counter, 1 for the first snapshot ever taken of this player. */
    public long version() {
        return version;
    }

    /** The {@code storage.serverId} of the server that took it. */
    public String serverId() {
        return serverId;
    }

    /** Wall clock, milliseconds since the epoch, on the saving server. */
    public long savedAt() {
        return savedAt;
    }

    public SaveCause cause() {
        return cause;
    }

    /** A pinned snapshot is never removed by rotation. */
    public boolean pinned() {
        return pinned;
    }

    /** A copy of the serialised data. */
    public byte[] data() {
        return data.clone();
    }

    public int size() {
        return data.length;
    }

    public Snapshot withPinned(boolean value) {
        return value == pinned ? this : new Snapshot(player, version, serverId, savedAt, cause, value, data);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Snapshot s && s.player.equals(player) && s.version == version && s.serverId.equals(serverId)
                && s.savedAt == savedAt && s.cause == cause && s.pinned == pinned && Arrays.equals(s.data, data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(player, version, serverId, savedAt, cause, pinned, Arrays.hashCode(data));
    }

    @Override
    public String toString() {
        return "Snapshot[" + player + " v" + version + " " + cause.id() + " from " + serverId + " at " + savedAt + (pinned ? " pinned" : "") + ", " + data.length + " bytes]";
    }
}
