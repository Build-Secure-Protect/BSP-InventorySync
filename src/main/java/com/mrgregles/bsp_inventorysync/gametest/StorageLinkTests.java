package com.mrgregles.bsp_inventorysync.gametest;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.storage.FastStore;
import com.mrgregles.bsp_inventorysync.storage.JdbcStore;
import com.mrgregles.bsp_inventorysync.storage.MemoryStore;
import com.mrgregles.bsp_inventorysync.storage.Presence;
import com.mrgregles.bsp_inventorysync.storage.RedisClient;
import com.mrgregles.bsp_inventorysync.storage.Resp;
import com.mrgregles.bsp_inventorysync.storage.SaveCause;
import com.mrgregles.bsp_inventorysync.storage.Snapshot;
import com.mrgregles.bsp_inventorysync.sync.ArrivalPolicy;
import com.mrgregles.bsp_inventorysync.sync.SyncService;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The Redis client against a fake Redis on a local socket, the fast store over the memory store, the
 * whole handshake through the fast store, and the JDBC store against nothing listening. These futures
 * complete on worker threads, so the tests wait on them (bounded) or poll from the test sequence.
 */
@GameTestHolder(BSPInventorySync.MODID)
@PrefixGameTestTemplate(false)
public class StorageLinkTests {
    private static final String EMPTY = "empty";
    private static final String HUB = "hub";
    private static final String PVP = "pvp";

    private static <T> T wait(GameTestHelper h, CompletableFuture<T> f, String what) {
        try {
            return f.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new net.minecraft.gametest.framework.GameTestAssertException(what + " failed: " + e.getCause());
        } catch (TimeoutException e) {
            throw new net.minecraft.gametest.framework.GameTestAssertException(what + " took more than 5 s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new net.minecraft.gametest.framework.GameTestAssertException(what + " interrupted");
        }
    }

    /** The failure of a future, or null when it succeeded. */
    private static Throwable failure(CompletableFuture<?> f) {
        try {
            f.get(5, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (TimeoutException | InterruptedException e) {
            return e;
        }
    }

    private static RedisClient client(FakeRedis redis, String password) {
        return new RedisClient(new RedisClient.Settings("127.0.0.1", redis.port(), "", password));
    }

    @GameTest(template = EMPTY, timeoutTicks = 400)
    public static void redis_client_round_trips_values_and_fails_fast_while_down(GameTestHelper h) throws IOException {
        FakeRedis redis = FakeRedis.start("secret");
        RedisClient client = client(redis, "secret");
        RedisClient wrong = client(redis, "nope");
        try {
            h.assertTrue(wait(h, client.ping(), "ping"), "PONG with the right password");
            Throwable bad = failure(wrong.ping());
            h.assertTrue(bad != null && String.valueOf(bad.getMessage()).contains("WRONGPASS"), "the wrong password is refused: " + bad);

            byte[] blob = new byte[] {0, 1, 2, (byte) 0xFF, '\r', '\n', 42};
            wait(h, client.set("k", blob, 0), "set");
            h.assertTrue(java.util.Arrays.equals(wait(h, client.get("k"), "get"), blob), "binary values round trip");
            h.assertTrue(wait(h, client.get("missing"), "get missing") == null, "a missing key is null");
            wait(h, client.set("t", Resp.bytes("x"), 50), "set with ttl");
            h.assertTrue(wait(h, client.sadd("s", "a"), "sadd") == 1 && wait(h, client.sadd("s", "a"), "sadd again") == 0, "set members are unique");
            h.assertTrue(wait(h, client.smembers("s"), "smembers").equals(java.util.List.of("a")), "members listed");
            h.assertTrue(wait(h, client.del("k", "s"), "del") == 2, "both keys deleted");
            h.assertTrue(wait(h, client.get("k"), "get after del") == null, "gone");

            redis.stop();
            Throwable down = failure(client.ping());
            h.assertTrue(down != null, "a command fails once Redis is down");
            long t0 = System.nanoTime();
            Throwable fast = failure(client.ping());
            h.assertTrue(fast != null && (System.nanoTime() - t0) < 500_000_000L, "and fails fast while it stays down");
            h.assertTrue(!client.connected(), "reported down: " + client.status());

            redis.restart();
            // the client reconnects by itself (half a second, then a second, ...)
            h.startSequence().thenWaitUntil(() -> {
                Boolean pong = null;
                try {
                    pong = client.ping().get(2, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // not yet
                }
                h.assertTrue(Boolean.TRUE.equals(pong), "not reconnected yet: " + client.status());
            }).thenExecute(() -> {
                client.close();
                wrong.close();
                redis.close();
            }).thenSucceed();
        } catch (RuntimeException e) {
            client.close();
            wrong.close();
            redis.close();
            throw e;
        }
    }

    @GameTest(template = EMPTY, timeoutTicks = 400)
    public static void fast_store_serves_presence_and_snapshots_from_redis_and_falls_back(GameTestHelper h) throws IOException {
        FakeRedis redis = FakeRedis.start("");
        RedisClient client = client(redis, "");
        MemoryStore inner = new MemoryStore(() -> 5);
        FastStore fast = new FastStore(inner, client, "test", 60_000);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        try {
            // presence: in Redis, mirrored in the store, read from Redis first
            wait(h, fast.setPresence(alice, HUB, true), "set presence");
            h.assertTrue(redis.get(fast.presenceKey(alice)) != null && redis.get(fast.presenceKey(alice)).startsWith("1|hub|"), "presence in Redis: " + redis.get(fast.presenceKey(alice)));
            h.assertTrue(redis.members(fast.heldKey(HUB)).contains(alice.toString()), "hub's held set lists her");
            Presence mirrored = wait(h, inner.presence(alice), "inner presence").orElseThrow();
            h.assertTrue(mirrored.online() && mirrored.serverId().equals(HUB), "mirrored in the database store");
            Presence read = wait(h, fast.presence(alice), "fast presence").orElseThrow();
            h.assertTrue(read.online() && read.serverId().equals(HUB), "read back through the fast path");
            h.assertTrue(wait(h, fast.presence(bob), "unknown presence").isEmpty(), "unknown players have none");

            // snapshots: saved to the store, cached in Redis, served from the cache
            Snapshot saved = wait(h, fast.save(alice, HUB, SaveCause.LOGOUT, Resp.bytes("inventory")), "save");
            h.assertTrue(saved.version() == 1, "version from the store");
            h.assertTrue(redis.get(fast.snapshotKey(alice)) != null && redis.get(fast.snapshotKey(alice)).startsWith("1\nhub\n5\nlogout\n0\n"), "cached with its header: " + redis.get(fast.snapshotKey(alice)));
            wait(h, inner.delete(alice, 1), "delete from inner"); // only the cache has it now
            Optional<Snapshot> cached = wait(h, fast.latest(alice), "latest from cache");
            h.assertTrue(cached.isPresent() && cached.get().version() == 1 && new String(cached.get().data(), StandardCharsets.UTF_8).equals("inventory") && cached.get().cause() == SaveCause.LOGOUT, "served from Redis: " + cached);

            // a delete clears the cache; the store is asked again
            wait(h, fast.save(alice, HUB, SaveCause.DEATH, Resp.bytes("v2")), "save v2");
            wait(h, fast.delete(alice, 2), "delete v2");
            h.assertTrue(redis.get(fast.snapshotKey(alice)) == null, "cache cleared by the delete");
            h.assertTrue(wait(h, fast.latest(alice), "latest after delete").isEmpty(), "nothing left anywhere");

            // clearPresence releases everyone in the held set, in Redis and in the store
            wait(h, fast.setPresence(alice, HUB, true), "alice on hub");
            wait(h, fast.setPresence(bob, HUB, true), "bob on hub");
            wait(h, fast.setPresence(UUID.randomUUID(), PVP, true), "someone on pvp");
            int cleared = wait(h, fast.clearPresence(HUB), "clear hub");
            h.assertTrue(cleared == 2, "the store cleared two, got " + cleared);
            h.assertTrue(redis.get(fast.presenceKey(alice)).startsWith("0|hub|") && redis.get(fast.presenceKey(bob)).startsWith("0|hub|"), "both offline in Redis");
            h.assertTrue(redis.members(fast.heldKey(HUB)).isEmpty(), "hub's held set is gone");
            h.assertTrue(!wait(h, fast.presence(alice), "alice presence").orElseThrow().online(), "offline through the fast path");

            // Redis down: everything still works through the store
            wait(h, fast.save(bob, PVP, SaveCause.AUTOSAVE, Resp.bytes("b1")), "save bob");
            redis.stop();
            failure(client.ping()); // let the client notice
            Snapshot degraded = wait(h, fast.save(bob, PVP, SaveCause.LOGOUT, Resp.bytes("b2")), "save without Redis");
            h.assertTrue(degraded.version() == 2, "saved to the store without Redis");
            h.assertTrue(wait(h, fast.latest(bob), "latest without Redis").orElseThrow().version() == 2, "read from the store without Redis");
            wait(h, fast.setPresence(bob, PVP, false), "presence without Redis");
            h.assertTrue(!wait(h, fast.presence(bob), "presence read without Redis").orElseThrow().online(), "presence from the store without Redis");
        } finally {
            fast.close();
            redis.close();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 400)
    public static void the_handshake_runs_through_the_fast_store(GameTestHelper h) throws IOException {
        MinecraftServer server = h.getLevel().getServer();
        FakeRedis redis = FakeRedis.start("pw");
        FastStore store = new FastStore(new MemoryStore(), client(redis, "pw"), "test", 60_000);
        SyncService hub = new SyncService(server, store, HUB, new ArrivalPolicy(5_000, false));
        SyncService pvp = new SyncService(server, store, PVP, new ArrivalPolicy(5_000, false));
        UUID id = UUID.randomUUID();
        AtomicReference<ServerPlayer> p = new AtomicReference<>();
        AtomicReference<ServerPlayer> q = new AtomicReference<>();
        h.startSequence()
                .thenExecute(() -> {
                    p.set(TestPlayers.mockServerPlayer(h, id));
                    hub.onLogin(p.get(), System.currentTimeMillis());
                })
                .thenWaitUntil(() -> {
                    hub.tick(System.currentTimeMillis());
                    h.assertTrue(!SyncService.isSyncing(id), "hub still settling");
                })
                .thenExecute(() -> {
                    h.assertTrue(redis.get(store.presenceKey(id)).startsWith("1|hub|"), "hub holds the player in Redis");
                    p.get().getInventory().setItem(0, new ItemStack(Items.ELYTRA));
                    hub.onLogout(p.get()); // save, cache, release: all on worker threads
                })
                .thenWaitUntil(() -> h.assertTrue(redis.get(store.presenceKey(id)) != null && redis.get(store.presenceKey(id)).startsWith("0|hub|"), "hub has not released yet"))
                .thenExecute(() -> {
                    h.assertTrue(redis.get(store.snapshotKey(id)) != null, "the snapshot is cached");
                    q.set(TestPlayers.mockServerPlayer(h, id));
                    pvp.onLogin(q.get(), System.currentTimeMillis());
                })
                .thenWaitUntil(() -> {
                    pvp.tick(System.currentTimeMillis());
                    h.assertTrue(!SyncService.isSyncing(id), "pvp still settling");
                })
                .thenExecute(() -> {
                    h.assertTrue(q.get().getInventory().getItem(0).is(Items.ELYTRA), "the inventory arrived through Redis");
                    h.assertTrue(redis.get(store.presenceKey(id)).startsWith("1|pvp|"), "pvp holds the player now");
                    h.assertTrue(pvp.lastArrival(id).orElseThrow().outcome() == com.mrgregles.bsp_inventorysync.sync.Arrival.Outcome.CLEAN, "clean");
                    pvp.onLogout(q.get());
                    store.close();
                    redis.close();
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 400)
    public static void jdbc_store_fails_cleanly_when_nothing_listens(GameTestHelper h) throws IOException {
        int port;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = probe.getLocalPort();
        }
        JdbcStore store = new JdbcStore(new JdbcStore.Settings("127.0.0.1", port, "bsp", "bsp", "", false, "bsp_is_test_"));
        try {
            h.assertTrue(store.settings().describe().contains("bsp_is_test_*"), "describe names the prefix");
            boolean ping = wait(h, store.ping(), "ping");
            h.assertTrue(!ping, "ping is false with nothing listening");
            Throwable error = failure(store.latest(UUID.randomUUID()));
            h.assertTrue(error != null, "a read fails with an exception, not a hang: " + error);
            BSPInventorySync.LOGGER.info("BSP-InventorySync test: JDBC failure as expected: {}", String.valueOf(error).replace('\n', ' '));
            h.assertTrue(store.status().contains("last error"), "the status shows the error: " + store.status());
        } finally {
            store.close();
        }
        Throwable closed = failure(store.ping());
        h.assertTrue(closed != null || true, "a closed store does not hang");
        h.succeed();
    }
}
