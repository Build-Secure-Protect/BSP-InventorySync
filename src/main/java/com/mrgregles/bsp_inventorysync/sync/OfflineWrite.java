package com.mrgregles.bsp_inventorysync.sync;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.storage.Presence;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Writing a change to a player who is not on this server: the editor's offline edit (ED-2) and the staff
 * panel's (SU-IS2, D29 and its audit). In order:
 * <ol>
 * <li>{@link SyncService#tryBeginWrite}: one change at a time per player on this server, none while this
 * server is still saving them or stopping ({@code busy} answers then); an arrival of theirs here waits
 * until the end;</li>
 * <li>the checkout is claimed in one step ({@code SyncStore.claim}): when another server holds the player,
 * {@code elsewhere} answers and nothing is written or released;</li>
 * <li>otherwise {@code work} runs (check the version, write the snapshot, pin) while this server holds the
 * checkout, so an arrival elsewhere waits for it;</li>
 * <li>the checkout is released, and the release has landed before the hold ends: a release still on its way
 * could otherwise free the checkout of an arrival on this same server that started right after. A claim
 * that failed is released too (it may have landed before its answer was lost); a release that fails is logged.</li>
 * </ol>
 * The answer gives up after twice the store's timeout; the hold itself lasts until the store has really
 * answered, and server stop waits for it. A failure of {@code work} still releases, then fails the answer.
 * Server thread.
 */
public final class OfflineWrite {
    private OfflineWrite() {}

    public static <T> CompletableFuture<T> run(SyncService sync, UUID player, Supplier<CompletableFuture<T>> busy,
                                               Function<Presence, CompletableFuture<T>> elsewhere, Supplier<CompletableFuture<T>> work) {
        if (!sync.tryBeginWrite(player)) {
            return busy.get();
        }
        String here = sync.serverId();
        CompletableFuture<T> out;
        try {
            out = sync.store().claim(player, here).handle((held, claimError) -> {
                if (claimError != null) {
                    return released(sync, player, CompletableFuture.<T>failedFuture(claimError));
                }
                if (!held.online() || !held.serverId().equals(here)) {
                    return elsewhere.apply(held);
                }
                CompletableFuture<T> done;
                try {
                    done = work.get();
                } catch (RuntimeException e) {
                    done = CompletableFuture.failedFuture(e);
                }
                return released(sync, player, done);
            }).thenCompose(f -> f);
        } catch (RuntimeException e) {
            out = released(sync, player, CompletableFuture.failedFuture(e));
        }
        CompletableFuture<T> held = out.whenComplete((value, error) -> sync.endWrite(player));
        sync.track(held);
        return held.copy().orTimeout(2 * sync.storeTimeoutMs(), TimeUnit.MILLISECONDS);
    }

    /** The same answer, once the release (this server's checkout only) has landed. */
    private static <T> CompletableFuture<T> released(SyncService sync, UUID player, CompletableFuture<T> done) {
        return done.handle((value, error) -> sync.store().setPresence(player, sync.serverId(), false)
                        .handle((v, releaseError) -> {
                            if (releaseError != null) {
                                BSPInventorySync.LOGGER.warn("BSP-InventorySync: releasing {} after an offline change failed: {}; other servers wait for them until this server restarts or the arrival times out", player, releaseError.toString());
                            }
                            return (Void) null;
                        })
                        .thenCompose(v -> error == null ? CompletableFuture.completedFuture(value) : CompletableFuture.<T>failedFuture(error)))
                .thenCompose(f -> f);
    }
}
