package com.mrgregles.bsp_inventorysync.storage;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Where graves are kept (D25): the network database on synced servers, the world's own data elsewhere
 * ({@code grave.WorldGraveStore}). Rules every implementation keeps, tested against {@link GraveTable}:
 * <ul>
 * <li>{@link #addGrave} stores a grave under the id it carries and answers true; a grave with that id already
 * there is left as it is and the answer is false, so sending the same grave twice is harmless.</li>
 * <li>{@link #graves} is every grave of the owner, archived ones included, newest death first.</li>
 * <li>{@link #updateGrave} and {@link #deleteGrave} succeed only when the stored revision is the one given; an
 * update stores the grave one revision on.</li>
 * <li>{@link #gravesTouchedBefore} is the graves last touched before the time, oldest first, at most {@code limit}.</li>
 * </ul>
 */
public interface GraveStore {
    /** Stores a new grave; false when one with its id is already there (nothing is changed then). */
    CompletableFuture<Boolean> addGrave(Grave grave);

    CompletableFuture<Optional<Grave>> grave(UUID id);

    /** Every grave of the owner, archived ones included, newest death first. */
    CompletableFuture<List<Grave>> graves(UUID owner);

    /** Replaces the grave if its stored revision is still {@code grave.revision()}, storing it one revision on. */
    CompletableFuture<Boolean> updateGrave(Grave grave);

    /** Removes the grave if its stored revision is still {@code revision}. */
    CompletableFuture<Boolean> deleteGrave(UUID id, long revision);

    /** Graves last touched before {@code before}, oldest first, at most {@code limit}: the expiry sweep's work. */
    CompletableFuture<List<Grave>> gravesTouchedBefore(long before, int limit);
}
