package com.mrgregles.bsp_inventorysync.storage;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The shared storage behind the sync: snapshots per player with a rotating history (D13), who is
 * online where (D5), and extra blobs for data that lives outside the player's own NBT, such as a
 * backpack's contents keyed by its {@code contentsUuid} (D6, D12). Every call returns a future; the
 * MySQL store completes them on its worker thread, the in-memory store at once. Callers hop back to
 * the server thread themselves before touching the game.
 *
 * <p>Rules every implementation keeps, tested against {@link MemoryStore} in {@code gametest}:
 * <ul>
 * <li>{@link #save} gives the snapshot the player's next version (1 for the first) and the store's
 * clock as {@code savedAt}; versions never repeat and never go down, even after deletes.</li>
 * <li>{@link #latest} is the highest version, whatever its cause or pin.</li>
 * <li>{@link #history} is newest first.</li>
 * <li>{@link #prune} removes the oldest unpinned snapshots until at most {@code keep} unpinned remain,
 * and never removes the latest.</li>
 * <li>{@link #clearPresence} marks offline everyone that server held, and nobody else.</li>
 * </ul>
 * Graves (D25) live in the same store, with the rules of {@link GraveStore}.
 */
public interface SyncStore extends GraveStore {
    /** Stores a new snapshot and returns it with its version and time filled in. */
    CompletableFuture<Snapshot> save(UUID player, String serverId, SaveCause cause, byte[] data);

    /**
     * As {@link #save}, together with new graves of that player (D25), in one write: either the snapshot and the graves
     * both land, or neither does. A grave already there (sent before, answer lost) is left as it is.
     */
    CompletableFuture<Snapshot> saveWithGraves(UUID player, String serverId, SaveCause cause, byte[] data, java.util.List<Grave> graves);

    /** The newest snapshot of the player, if any. */
    CompletableFuture<Optional<Snapshot>> latest(UUID player);

    /**
     * The newest snapshot's version, 0 when there is none: what {@link #latest} would return, without its
     * data. The Redis cache checks its copy against it.
     */
    default CompletableFuture<Long> latestVersion(UUID player) {
        return latest(player).thenApply(s -> s.map(Snapshot::version).orElse(0L));
    }

    /** One snapshot by version. */
    CompletableFuture<Optional<Snapshot>> snapshot(UUID player, long version);

    /** Every snapshot of the player, newest first. */
    CompletableFuture<List<Snapshot>> history(UUID player);

    /** Pins or unpins a snapshot; false when there is no such version. */
    CompletableFuture<Boolean> pin(UUID player, long version, boolean pinned);

    /** Removes one snapshot; false when there is no such version. The version is not reused. */
    CompletableFuture<Boolean> delete(UUID player, long version);

    /** Rotation: removes the oldest unpinned snapshots beyond {@code keep}, never the latest. Returns how many went. */
    CompletableFuture<Integer> prune(UUID player, int keep);

    /** Where the player is, if the store has ever heard of them. */
    CompletableFuture<Optional<Presence>> presence(UUID player);

    /**
     * Marks the player online on {@code serverId}, now; or offline, but only while {@code serverId} is the
     * server recorded as holding them (a late release never undoes another server's checkout).
     */
    CompletableFuture<Void> setPresence(UUID player, String serverId, boolean online);

    /**
     * Takes the player's checkout for {@code serverId} in one step, and only when no other server holds it:
     * the player is marked online on {@code serverId} when they are offline, unknown, or already held by
     * {@code serverId}; otherwise nothing changes. Two servers can never both take it (SU-IS2, D29). Returns
     * where the player is afterwards: online on {@code serverId} when it worked, else the server holding them.
     */
    CompletableFuture<Presence> claim(UUID player, String serverId);

    /** Marks offline every player recorded online on {@code serverId} (server start and stop). Returns how many. */
    CompletableFuture<Integer> clearPresence(String serverId);

    /** An extra blob by kind and key (for example {@code "backpack"} and a {@code contentsUuid}). */
    CompletableFuture<Optional<byte[]>> extra(String kind, UUID key);

    /** Stores or replaces an extra blob. */
    CompletableFuture<Void> putExtra(String kind, UUID key, byte[] data);

    /** Removes an extra blob; false when there was none. */
    CompletableFuture<Boolean> deleteExtra(String kind, UUID key);

    /** Queues one stack for a player (D23) and returns it with its id and time. */
    CompletableFuture<Give> addGive(UUID player, byte[] item, String by);

    /** The stacks waiting for these players, oldest first. */
    CompletableFuture<List<Give>> gives(Collection<UUID> players);

    /** Removes a waiting stack: true for exactly one caller, the one that removed it, who then hands it over. */
    CompletableFuture<Boolean> takeGive(long id);

    /** Whether the store can be reached right now. */
    CompletableFuture<Boolean> ping();

    /** Releases connections and threads. Further calls fail. */
    void close();
}
