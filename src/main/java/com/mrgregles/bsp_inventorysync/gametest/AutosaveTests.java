package com.mrgregles.bsp_inventorysync.gametest;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.data.PlayerSnapshotCodec;
import com.mrgregles.bsp_inventorysync.data.vanilla.AdvancementsModule;
import com.mrgregles.bsp_inventorysync.storage.MemoryStore;
import com.mrgregles.bsp_inventorysync.storage.Presence;
import com.mrgregles.bsp_inventorysync.storage.SaveCause;
import com.mrgregles.bsp_inventorysync.storage.Snapshot;
import com.mrgregles.bsp_inventorysync.storage.SyncStore;
import com.mrgregles.bsp_inventorysync.sync.Arrival;
import com.mrgregles.bsp_inventorysync.sync.ArrivalPolicy;
import com.mrgregles.bsp_inventorysync.sync.SyncService;
import net.minecraft.advancements.Advancement;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.AdvancementEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** The auto-save and its change detection, the advancements dirty flag, and the watchdog on store calls (IS-7). */
@GameTestHolder(BSPInventorySync.MODID)
@PrefixGameTestTemplate(false)
public class AutosaveTests {
    private static final String EMPTY = "empty";
    private static final String HUB = "hub";

    private static ServerPlayer settled(GameTestHelper h, SyncService service, long now) {
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        service.onLogin(p, now);
        service.tick(now);
        h.assertTrue(!SyncService.isSyncing(p.getUUID()), "settled");
        return p;
    }

    @GameTest(template = EMPTY)
    public static void autosave_writes_changed_players_only_and_skips_unchanged_ones(GameTestHelper h) {
        MemoryStore store = new MemoryStore();
        SyncService hub = new SyncService(h.getLevel().getServer(), store, HUB, new ArrivalPolicy(1_000, false));
        hub.setAutosaveMs(60_000);
        ServerPlayer a = settled(h, hub, 0);
        ServerPlayer b = settled(h, hub, 0);
        a.getInventory().setItem(0, new ItemStack(Items.IRON_INGOT, 3));
        b.getInventory().setItem(0, new ItemStack(Items.COAL, 9));
        List<ServerPlayer> players = List.of(a, b);

        h.assertTrue(hub.autosaveDue(players, 30_000) == 0, "nothing is due half way through the interval");
        int first = hub.autosaveDue(players, 60_000);
        h.assertTrue(first == 2, "both are written on their first turn (nothing was saved for them yet), got " + first);
        h.assertTrue(store.latest(a.getUUID()).join().orElseThrow().cause() == SaveCause.AUTOSAVE, "as auto-saves");
        h.assertTrue(hub.autosaveDue(players, 90_000) == 0, "not due again yet");

        a.getInventory().setItem(1, new ItemStack(Items.GOLD_INGOT)); // only a changes
        int second = hub.autosaveDue(players, 120_000);
        h.assertTrue(second == 1, "only the changed player is written, got " + second);
        h.assertTrue(store.history(a.getUUID()).join().size() == 2 && store.history(b.getUUID()).join().size() == 1, "a has two snapshots, b still one");
        h.assertTrue(hub.skippedAutosaves() == 1 && hub.writtenAutosaves() == 3, "counters: skipped " + hub.skippedAutosaves() + ", written " + hub.writtenAutosaves());

        // a restore counts as "last saved": an unchanged arrival is not written again
        Snapshot latest = store.latest(a.getUUID()).join().orElseThrow();
        hub.restore(a, latest, false);
        h.assertTrue(hub.autosave(a, 200_000).isEmpty(), "unchanged since the restore: skipped");
        a.setHealth(5f);
        Optional<CompletableFuture<Snapshot>> written = hub.autosave(a, 260_000);
        h.assertTrue(written.isPresent() && written.get().join().version() == 3, "a change to vitals is a change");

        // a player whose data is still arriving is never auto-saved
        ServerPlayer c = TestPlayers.mockServerPlayer(h);
        store.setPresence(c.getUUID(), "pvp", true).join();
        hub.onLogin(c, 300_000);
        hub.tick(300_000);
        h.assertTrue(SyncService.isSyncing(c.getUUID()) && hub.autosave(c, 400_000).isEmpty(), "syncing players are skipped");
        hub.onLogout(c).join();

        hub.setAutosaveMs(0);
        a.getInventory().setItem(2, new ItemStack(Items.DIAMOND));
        h.assertTrue(hub.autosaveDue(players, 999_000) == 0, "0 turns the sweep off");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void advancement_bytes_are_reused_until_a_criterion_changes(GameTestHelper h) {
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        Advancement root = p.getServer().getAdvancements().getAdvancement(ResourceLocation.parse("minecraft:story/root"));
        String criterion = root.getCriteria().keySet().iterator().next();
        p.getAdvancements().getOrStartProgress(root).grantProgress(criterion);
        AdvancementsModule.markDirty(p.getUUID());

        long before = AdvancementsModule.fileWrites();
        PlayerSnapshotCodec.capture(p);
        h.assertTrue(AdvancementsModule.fileWrites() == before + 1, "the first capture writes the file");
        byte[] first = PlayerSnapshotCodec.module(PlayerSnapshotCodec.capture(p), AdvancementsModule.ID).getByteArray("Json");
        h.assertTrue(AdvancementsModule.fileWrites() == before + 1, "the second capture reuses the bytes: no file write");
        h.assertTrue(first.length > 2, "and still carries the JSON");

        // the Forge event marks the player dirty (vanilla posts it when a criterion is granted or revoked)
        p.getAdvancements().getOrStartProgress(root).revokeProgress(criterion);
        MinecraftForge.EVENT_BUS.post(new AdvancementEvent.AdvancementProgressEvent(p, root, p.getAdvancements().getOrStartProgress(root), criterion, AdvancementEvent.AdvancementProgressEvent.ProgressType.REVOKE));
        byte[] after = PlayerSnapshotCodec.module(PlayerSnapshotCodec.capture(p), AdvancementsModule.ID).getByteArray("Json");
        h.assertTrue(AdvancementsModule.fileWrites() == before + 2, "dirty again: the file is written once more");
        h.assertTrue(!java.util.Arrays.equals(first, after), "and the bytes changed with the progress");

        AdvancementsModule.forget(p.getUUID());
        PlayerSnapshotCodec.capture(p);
        h.assertTrue(AdvancementsModule.fileWrites() == before + 3, "after a logout nothing is reused");
        h.succeed();
    }

    /** A store whose every call never answers: a frozen database. */
    static final class HangingStore implements SyncStore {
        private static <T> CompletableFuture<T> never() {
            return new CompletableFuture<>();
        }

        @Override public CompletableFuture<Snapshot> save(UUID player, String serverId, SaveCause cause, byte[] data) { return never(); }
        @Override public CompletableFuture<Optional<Snapshot>> latest(UUID player) { return never(); }
        @Override public CompletableFuture<Optional<Snapshot>> snapshot(UUID player, long version) { return never(); }
        @Override public CompletableFuture<List<Snapshot>> history(UUID player) { return never(); }
        @Override public CompletableFuture<Boolean> pin(UUID player, long version, boolean pinned) { return never(); }
        @Override public CompletableFuture<Boolean> delete(UUID player, long version) { return never(); }
        @Override public CompletableFuture<Integer> prune(UUID player, int keep) { return never(); }
        @Override public CompletableFuture<Optional<Presence>> presence(UUID player) { return never(); }
        @Override public CompletableFuture<Void> setPresence(UUID player, String serverId, boolean online) { return never(); }
        @Override public CompletableFuture<Integer> clearPresence(String serverId) { return never(); }
        @Override public CompletableFuture<Optional<byte[]>> extra(String kind, UUID key) { return never(); }
        @Override public CompletableFuture<Void> putExtra(String kind, UUID key, byte[] data) { return never(); }
        @Override public CompletableFuture<Boolean> deleteExtra(String kind, UUID key) { return never(); }
        @Override public CompletableFuture<Boolean> ping() { return never(); }
        @Override public void close() {}
    }

    @GameTest(template = EMPTY, timeoutTicks = 400)
    public static void a_frozen_store_is_cut_off_by_the_watchdog_and_the_player_is_not_left_frozen(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        SyncService frozen = new SyncService(server, new HangingStore(), HUB, new ArrivalPolicy(600, false));
        frozen.setStoreTimeoutMs(200);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        p.getInventory().setItem(0, new ItemStack(Items.BREAD));

        // a save against a frozen store fails within the timeout instead of hanging
        long t0 = System.nanoTime();
        Throwable error;
        try {
            frozen.save(p, SaveCause.LOGOUT).get(3, TimeUnit.SECONDS);
            error = null;
        } catch (ExecutionException e) {
            error = e.getCause();
        } catch (TimeoutException | InterruptedException e) {
            error = e;
        }
        long tookMs = (System.nanoTime() - t0) / 1_000_000;
        h.assertTrue(error instanceof TimeoutException && tookMs < 2_000, "the save was cut off by the watchdog in " + tookMs + " ms: " + error);
        h.assertTrue(frozen.storeTimeouts() == 1, "one timeout counted, got " + frozen.storeTimeouts());

        // an arrival: it waits for this server's own save of the player (never answered), times out, the take-over times out,
        // and the player is refused (never left frozen, and nothing of this server's stale copy is saved)
        frozen.onLogin(p, System.currentTimeMillis());
        h.startSequence()
                .thenWaitUntil(() -> {
                    frozen.tick(System.currentTimeMillis());
                    h.assertTrue(!SyncService.isSyncing(p.getUUID()), "still frozen: timeouts so far " + frozen.storeTimeouts());
                })
                .thenExecute(() -> {
                    Arrival a = frozen.lastArrival(p.getUUID()).orElseThrow();
                    h.assertTrue(a.outcome() == Arrival.Outcome.UNAVAILABLE, "the store never answered: " + a);
                    h.assertTrue(a.waitedMs() >= 600, "waited out the arrival timeout: " + a);
                    h.assertTrue(p.getInventory().getItem(0).is(Items.BREAD), "the player keeps what this server had");
                    h.assertTrue(frozen.storeTimeouts() >= 2, "the save and the take-over were cut off (the arrival waits for this server's own save of the player instead of polling): " + frozen.storeTimeouts());
                })
                .thenSucceed();
    }
}
