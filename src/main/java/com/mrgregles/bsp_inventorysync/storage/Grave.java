package com.mrgregles.bsp_inventorysync.storage;

import java.util.UUID;

/**
 * One grave (D25): what a player's death left behind, kept until it is emptied. Every death with items
 * gets its own grave, so a second death never touches the first.
 *
 * <ul>
 * <li>{@code serverId}, {@code dimension} and the position say where it stands; {@code placed} is false for a
 * grave that found no spot (the void, nowhere dry nearby): it has no block and is recovered by command.</li>
 * <li>{@code diedAt} is the death; {@code touchedAt} the death or the last time someone opened it, which the
 * 7-day and 30-day rules count from; {@code archivedAt} is when the 30-day rule took it off its owner's list
 * (0 while it is theirs), after which only admins see it.</li>
 * <li>{@code xp} the experience points it holds; {@code stacks} how many stacks, for lists that do not open
 * the contents; {@code data} the contents ({@code grave.GraveContents}, GZIP NBT).</li>
 * <li>{@code revision} counts changes: {@link GraveStore#updateGrave} and {@link GraveStore#deleteGrave} only
 * succeed against the revision they were read at, so two servers can never both take the same stack.</li>
 * </ul>
 */
public record Grave(UUID id, UUID owner, String ownerName, String serverId, String dimension, int x, int y, int z, boolean placed,
                    long diedAt, long touchedAt, long archivedAt, int xp, int stacks, long revision, byte[] data) {

    /** The same grave with new contents and a new last-touched time; the revision is the one it was read at. */
    public Grave withContents(int newStacks, int newXp, byte[] newData, long newTouchedAt) {
        return new Grave(id, owner, ownerName, serverId, dimension, x, y, z, placed, diedAt, newTouchedAt, archivedAt, newXp, newStacks, revision, newData);
    }

    public Grave withArchivedAt(long at) {
        return new Grave(id, owner, ownerName, serverId, dimension, x, y, z, placed, diedAt, touchedAt, at, xp, stacks, revision, data);
    }

    public Grave withPlaced(boolean nowPlaced) {
        return new Grave(id, owner, ownerName, serverId, dimension, x, y, z, nowPlaced, diedAt, touchedAt, archivedAt, xp, stacks, revision, data);
    }

    /** As the store holds it after an update: one revision on. */
    public Grave nextRevision() {
        return new Grave(id, owner, ownerName, serverId, dimension, x, y, z, placed, diedAt, touchedAt, archivedAt, xp, stacks, revision + 1, data);
    }

    /** The same grave given to another player: theirs now, with no block (recovered with /grave at once), not put away. */
    public Grave withOwner(UUID newOwner, String newOwnerName, long newTouchedAt) {
        return new Grave(id, newOwner, newOwnerName, serverId, dimension, x, y, z, false, diedAt, newTouchedAt, 0, xp, stacks, revision, data);
    }

    /**
     * Emptied: nothing left in it. An emptied grave stays in the store as a marker until the sweep removes it, so an old
     * copy sent again (after a crash or a lost answer) finds its id taken and can never bring the items back.
     */
    public boolean empty() {
        return stacks <= 0 && xp <= 0;
    }

    public boolean archived() {
        return archivedAt > 0;
    }

    /** The id's first eight characters: how players and admins name a grave. */
    public String shortId() {
        return id.toString().substring(0, 8);
    }
}
