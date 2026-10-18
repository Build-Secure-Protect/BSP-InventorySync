package com.mrgregles.bsp_inventorysync.gametest;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.storage.MemoryStore;
import com.mrgregles.bsp_inventorysync.storage.Presence;
import com.mrgregles.bsp_inventorysync.storage.SaveCause;
import com.mrgregles.bsp_inventorysync.storage.Snapshot;
import com.mrgregles.bsp_inventorysync.storage.SyncStore;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The storage rules of {@link SyncStore}, checked against {@link MemoryStore}, which is also what every
 * other rule's test runs on. Headless: {@code ./gradlew runGameTestServer}. Each test uses the empty
 * template {@code bsp_inventorysync:empty} (from tools/gen_gametest_structures.py) and places nothing.
 */
@GameTestHolder(BSPInventorySync.MODID)
@PrefixGameTestTemplate(false)
public class StoreTests {
    private static final String EMPTY = "empty";
    private static final String HUB = "hub";
    private static final String PVP = "pvp";

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** The in-memory store completes every future at once; a pending one would be a bug in the store. */
    private static <T> T now(GameTestHelper h, CompletableFuture<T> f) {
        if (!f.isDone()) {
            h.fail("memory store returned a pending future");
        }
        return f.join();
    }

    @GameTest(template = EMPTY)
    public static void save_numbers_versions_from_one_and_latest_is_the_newest(GameTestHelper h) {
        AtomicLong clock = new AtomicLong(1_000);
        SyncStore store = new MemoryStore(clock::get);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();

        h.assertTrue(now(h, store.latest(alice)).isEmpty(), "no snapshot before the first save");

        Snapshot a1 = now(h, store.save(alice, HUB, SaveCause.LOGOUT, bytes("a1")));
        clock.set(2_000);
        Snapshot a2 = now(h, store.save(alice, PVP, SaveCause.DEATH, bytes("a2")));
        Snapshot b1 = now(h, store.save(bob, HUB, SaveCause.AUTOSAVE, bytes("b1")));

        h.assertTrue(a1.version() == 1 && a2.version() == 2, "versions count from 1 per player: " + a1 + ", " + a2);
        h.assertTrue(b1.version() == 1, "another player's versions start at 1 too: " + b1);
        h.assertTrue(a1.savedAt() == 1_000 && a2.savedAt() == 2_000, "savedAt comes from the store's clock");
        h.assertTrue(a2.serverId().equals(PVP) && a2.cause() == SaveCause.DEATH && !a2.pinned(), "server, cause and pin are kept: " + a2);

        Optional<Snapshot> latest = now(h, store.latest(alice));
        h.assertTrue(latest.isPresent() && latest.get().version() == 2 && new String(latest.get().data(), StandardCharsets.UTF_8).equals("a2"), "latest is the newest version with its data");
        h.assertTrue(now(h, store.snapshot(alice, 1)).map(Snapshot::version).orElse(0L) == 1, "a snapshot can be read by version");
        h.assertTrue(now(h, store.snapshot(alice, 9)).isEmpty(), "an unknown version is empty");

        // delete the newest: the version is not reused
        h.assertTrue(now(h, store.delete(alice, 2)), "delete reports the removed snapshot");
        h.assertTrue(!now(h, store.delete(alice, 2)), "a second delete finds nothing");
        Snapshot a3 = now(h, store.save(alice, HUB, SaveCause.LOGOUT, bytes("a3")));
        h.assertTrue(a3.version() == 3, "a deleted version is never reused: " + a3);
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void history_is_newest_first_and_prune_keeps_pinned_and_latest(GameTestHelper h) {
        SyncStore store = new MemoryStore(() -> 0);
        UUID alice = UUID.randomUUID();
        for (int i = 1; i <= 6; i++) {
            now(h, store.save(alice, HUB, SaveCause.AUTOSAVE, bytes("v" + i)));
        }
        List<Snapshot> history = now(h, store.history(alice));
        h.assertTrue(history.size() == 6 && history.get(0).version() == 6 && history.get(5).version() == 1, "history is newest first: " + history);

        h.assertTrue(now(h, store.pin(alice, 2, true)), "pin reports success");
        h.assertTrue(!now(h, store.pin(alice, 42, true)), "pinning an unknown version fails");
        h.assertTrue(now(h, store.snapshot(alice, 2)).map(Snapshot::pinned).orElse(false), "the pin is stored");

        int removed = now(h, store.prune(alice, 2));
        List<Snapshot> left = now(h, store.history(alice));
        // unpinned were 1,3,4,5,6 (6 is the latest): keep 2 unpinned including the latest -> 5 and 6 stay, 1,3,4 go; 2 stays pinned
        h.assertTrue(removed == 3, "prune removed the three oldest unpinned, got " + removed + ": " + left);
        h.assertTrue(left.stream().map(Snapshot::version).toList().equals(List.of(6L, 5L, 2L)), "kept the latest, the newest unpinned and the pinned one: " + left);

        h.assertTrue(now(h, store.prune(alice, 0)) == 1, "keep 0 removes every unpinned snapshot but the latest");
        h.assertTrue(now(h, store.history(alice)).stream().map(Snapshot::version).toList().equals(List.of(6L, 2L)), "the latest and the pinned one survive keep 0");

        h.assertTrue(now(h, store.pin(alice, 2, false)), "unpin");
        h.assertTrue(now(h, store.prune(alice, 1)) == 1, "unpinned, it goes on the next prune");
        h.assertTrue(now(h, store.prune(UUID.randomUUID(), 1)) == 0, "pruning an unknown player removes nothing");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void presence_is_per_player_and_cleared_per_server(GameTestHelper h) {
        AtomicLong clock = new AtomicLong(5);
        SyncStore store = new MemoryStore(clock::get);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID carol = UUID.randomUUID();

        h.assertTrue(now(h, store.presence(alice)).isEmpty(), "unknown players have no presence");
        now(h, store.setPresence(alice, HUB, true));
        now(h, store.setPresence(bob, HUB, true));
        now(h, store.setPresence(carol, PVP, true));

        Presence a = now(h, store.presence(alice)).orElseThrow();
        h.assertTrue(a.online() && a.serverId().equals(HUB) && a.since() == 5, "online on hub since the clock: " + a);
        h.assertTrue(a.onlineElsewhere(PVP) && !a.onlineElsewhere(HUB), "onlineElsewhere compares the server");

        clock.set(9);
        int cleared = now(h, store.clearPresence(HUB));
        h.assertTrue(cleared == 2, "hub held two players, cleared " + cleared);
        Presence a2 = now(h, store.presence(alice)).orElseThrow();
        h.assertTrue(!a2.online() && a2.serverId().equals(HUB) && a2.since() == 9, "offline, last seen on hub, at the new time: " + a2);
        h.assertTrue(now(h, store.presence(carol)).orElseThrow().online(), "pvp's player is untouched");
        h.assertTrue(now(h, store.clearPresence(HUB)) == 0, "a second clear finds nobody");

        now(h, store.setPresence(alice, PVP, true));
        Presence a3 = now(h, store.presence(alice)).orElseThrow();
        h.assertTrue(a3.online() && a3.serverId().equals(PVP) && !a3.onlineElsewhere(PVP), "moved to pvp");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void extra_blobs_round_trip_by_kind_and_key(GameTestHelper h) {
        SyncStore store = new MemoryStore(() -> 0);
        UUID pack = UUID.randomUUID();

        h.assertTrue(now(h, store.extra("backpack", pack)).isEmpty(), "nothing stored yet");
        now(h, store.putExtra("backpack", pack, bytes("contents")));
        now(h, store.putExtra("dank", pack, bytes("other kind, same key")));

        h.assertTrue(new String(now(h, store.extra("backpack", pack)).orElseThrow(), StandardCharsets.UTF_8).equals("contents"), "the blob comes back");
        h.assertTrue(new String(now(h, store.extra("dank", pack)).orElseThrow(), StandardCharsets.UTF_8).equals("other kind, same key"), "kinds keep separate rows for the same key");

        now(h, store.putExtra("backpack", pack, bytes("replaced")));
        h.assertTrue(new String(now(h, store.extra("backpack", pack)).orElseThrow(), StandardCharsets.UTF_8).equals("replaced"), "put replaces");
        h.assertTrue(now(h, store.deleteExtra("backpack", pack)), "delete reports the removed blob");
        h.assertTrue(now(h, store.extra("backpack", pack)).isEmpty() && !now(h, store.deleteExtra("backpack", pack)), "gone, and a second delete finds nothing");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void snapshot_data_is_copied_and_a_closed_store_refuses(GameTestHelper h) {
        SyncStore store = new MemoryStore(() -> 0);
        UUID alice = UUID.randomUUID();
        byte[] original = bytes("abc");
        Snapshot s = now(h, store.save(alice, HUB, SaveCause.LOGOUT, original));
        original[0] = 'z';
        h.assertTrue(new String(s.data(), StandardCharsets.UTF_8).equals("abc"), "changing the caller's array after save changes nothing");
        s.data()[0] = 'z';
        h.assertTrue(new String(now(h, store.latest(alice)).orElseThrow().data(), StandardCharsets.UTF_8).equals("abc"), "changing a returned copy changes nothing");

        byte[] extra = bytes("xyz");
        now(h, store.putExtra("k", alice, extra));
        extra[0] = 'q';
        h.assertTrue(new String(now(h, store.extra("k", alice)).orElseThrow(), StandardCharsets.UTF_8).equals("xyz"), "extra blobs are copied too");

        h.assertTrue(now(h, store.ping()), "ping is true while open");
        store.close();
        h.assertTrue(store.latest(alice).isCompletedExceptionally(), "a closed store fails its futures");
        h.succeed();
    }
}
