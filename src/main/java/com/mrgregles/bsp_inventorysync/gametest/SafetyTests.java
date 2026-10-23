package com.mrgregles.bsp_inventorysync.gametest;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.compat.BspCore;
import com.mrgregles.bsp_inventorysync.data.ItemCodec;
import com.mrgregles.bsp_inventorysync.data.PlayerSnapshotCodec;
import com.mrgregles.bsp_inventorysync.data.vanilla.ForgeDataModule;
import com.mrgregles.bsp_inventorysync.data.vanilla.InventoryModule;
import com.mrgregles.bsp_inventorysync.storage.FastStore;
import com.mrgregles.bsp_inventorysync.storage.JdbcStore;
import com.mrgregles.bsp_inventorysync.storage.MemoryStore;
import com.mrgregles.bsp_inventorysync.storage.Presence;
import com.mrgregles.bsp_inventorysync.storage.RedisClient;
import com.mrgregles.bsp_inventorysync.storage.SaveCause;
import com.mrgregles.bsp_inventorysync.storage.Snapshot;
import com.mrgregles.bsp_inventorysync.sync.Arrival;
import com.mrgregles.bsp_inventorysync.sync.ArrivalPolicy;
import com.mrgregles.bsp_inventorysync.sync.SyncService;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The fixes of the 2026-10-23 performance and safety check, one test per item-loss or duplication
 * path that was found: the cursor and crafting grid at logout, the stale copy while frozen (crafting
 * grid, commands), refused arrivals, late releases, the stale Redis copy, data of mods missing on one
 * server, totems in the ender chest, and the save and store behaviour under load or outage.
 */
@GameTestHolder(BSPInventorySync.MODID)
@PrefixGameTestTemplate(false)
public class SafetyTests {
    private static final String EMPTY = "empty";
    private static final String HUB = "hub";
    private static final String PVP = "pvp";
    private static final ArrivalPolicy FAST = new ArrivalPolicy(1_000, false);

    /** A player settled on {@code service}: logged in, handshake done. */
    private static ServerPlayer settled(GameTestHelper h, SyncService service, UUID id, long now) {
        ServerPlayer p = TestPlayers.mockServerPlayer(h, id);
        service.onLogin(p, now);
        service.tick(now);
        h.assertTrue(!SyncService.isSyncing(id), "settled on " + service.serverId());
        return p;
    }

    /** A player frozen on {@code service} because {@code holder} still holds them in the store. */
    private static ServerPlayer frozen(GameTestHelper h, SyncService service, MemoryStore store, UUID id, String holder) {
        store.setPresence(id, holder, true).join();
        ServerPlayer q = TestPlayers.mockServerPlayer(h, id);
        service.onLogin(q, 0);
        service.tick(0);
        h.assertTrue(SyncService.isSyncing(id), "frozen while " + holder + " holds them");
        return q;
    }

    /** How many of {@code item} a snapshot carries in the inventory module, cursor included. */
    private static int countIn(CompoundTag snapshot, Item item) {
        CompoundTag inv = PlayerSnapshotCodec.module(snapshot, InventoryModule.ID);
        int n = 0;
        for (Tag t : inv.getList("Items", Tag.TAG_COMPOUND)) {
            ItemStack s = ItemCodec.load(((CompoundTag) t).getCompound("Item"));
            n += s.is(item) ? s.getCount() : 0;
        }
        if (inv.contains("Cursor", Tag.TAG_COMPOUND)) {
            ItemStack c = ItemCodec.load(inv.getCompound("Cursor"));
            n += c.is(item) ? c.getCount() : 0;
        }
        return n;
    }

    private static int onGround(GameTestHelper h, AABB area, Item item) {
        return h.getLevel().getEntitiesOfClass(ItemEntity.class, area).stream().filter(e -> e.getItem().is(item)).mapToInt(e -> e.getItem().getCount()).sum();
    }

    /** Logs the player out as Minecraft does: disconnected, the logout save, then removed (menus closed, loose items dropped). */
    private static CompoundTag logOutAndRemove(SyncService service, MemoryStore store, ServerPlayer p) {
        p.disconnect();
        service.onLogout(p).join();
        p.remove(Entity.RemovalReason.UNLOADED_WITH_PLAYER);
        return PlayerSnapshotCodec.decode(store.latest(p.getUUID()).join().orElseThrow().data());
    }

    @GameTest(template = EMPTY)
    public static void at_logout_the_cursor_and_crafting_grid_are_saved_once_and_never_also_dropped(GameTestHelper h) {
        MemoryStore store = new MemoryStore();
        SyncService hub = new SyncService(h.getLevel().getServer(), store, HUB, FAST);
        Vec3 start = h.absoluteVec(new Vec3(1.5, 2, 1.5));
        AABB around = new AABB(start, start).inflate(4);
        try {
            // room in the inventory: both go into it and into the snapshot, nothing is dropped
            ServerPlayer p = settled(h, hub, UUID.randomUUID(), 0);
            p.moveTo(start.x, start.y, start.z, 0, 0);
            p.inventoryMenu.getCraftSlots().setItem(0, new ItemStack(Items.APPLE, 3));
            p.containerMenu.setCarried(new ItemStack(Items.DIAMOND, 5));
            CompoundTag saved = logOutAndRemove(hub, store, p);
            h.assertTrue(countIn(saved, Items.DIAMOND) == 5 && countIn(saved, Items.APPLE) == 3, "the cursor and grid items are in the snapshot: " + countIn(saved, Items.DIAMOND) + ", " + countIn(saved, Items.APPLE));
            h.assertTrue(onGround(h, around, Items.DIAMOND) == 0 && onGround(h, around, Items.APPLE) == 0, "and not also on the ground");

            // a full inventory: what does not fit is dropped where they stood and left out of the snapshot
            ServerPlayer q = settled(h, hub, UUID.randomUUID(), 0);
            q.moveTo(start.x, start.y, start.z, 0, 0);
            for (int i = 0; i < 36; i++) {
                q.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
            }
            q.inventoryMenu.getCraftSlots().setItem(0, new ItemStack(Items.APPLE, 3));
            q.containerMenu.setCarried(new ItemStack(Items.DIAMOND, 5));
            CompoundTag full = logOutAndRemove(hub, store, q);
            h.assertTrue(countIn(full, Items.DIAMOND) == 0 && countIn(full, Items.APPLE) == 0, "nothing that did not fit is in the snapshot");
            h.assertTrue(onGround(h, around, Items.DIAMOND) == 5 && onGround(h, around, Items.APPLE) == 3, "it is on the ground, once: " + onGround(h, around, Items.DIAMOND) + ", " + onGround(h, around, Items.APPLE));
        } finally {
            h.getLevel().getEntitiesOfClass(ItemEntity.class, around).forEach(ItemEntity::discard);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void stale_items_moved_into_the_crafting_grid_while_frozen_are_gone_after_the_restore(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        MemoryStore store = new MemoryStore();
        SyncService hub = new SyncService(server, store, HUB, FAST);
        SyncService pvp = new SyncService(server, store, PVP, FAST);
        UUID id = UUID.randomUUID();
        ServerPlayer p = settled(h, hub, id, 0);
        p.getInventory().setItem(0, new ItemStack(Items.BREAD));
        hub.onLogout(p).join();

        ServerPlayer q = frozen(h, pvp, store, id, HUB);
        CraftingContainer grid = q.inventoryMenu.getCraftSlots();
        grid.setItem(0, new ItemStack(Items.EMERALD, 10)); // pvp's stale copy, moved into the grid while waiting
        store.setPresence(id, HUB, false).join();
        pvp.tick(50);
        h.assertTrue(!SyncService.isSyncing(id), "settled");
        h.assertTrue(grid.isEmpty(), "the grid was emptied by the restore: " + grid.getItem(0));
        h.assertTrue(q.getInventory().countItem(Items.EMERALD) == 0 && q.getInventory().countItem(Items.BREAD) == 1, "only hub's items");
        pvp.onLogout(q).join();
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void commands_wait_until_the_data_has_arrived(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        MemoryStore store = new MemoryStore();
        SyncService pvp = new SyncService(server, store, PVP, FAST);
        UUID id = UUID.randomUUID();
        ServerPlayer q = frozen(h, pvp, store, id, HUB);
        CommandEvent early = new CommandEvent(server.getCommands().getDispatcher().parse("seed", q.createCommandSourceStack()));
        MinecraftForge.EVENT_BUS.post(early);
        h.assertTrue(early.isCanceled(), "a command from a frozen player is refused");
        store.setPresence(id, HUB, false).join();
        pvp.tick(50);
        CommandEvent later = new CommandEvent(server.getCommands().getDispatcher().parse("seed", q.createCommandSourceStack()));
        MinecraftForge.EVENT_BUS.post(later);
        h.assertTrue(!later.isCanceled(), "and runs once they have settled");
        pvp.onLogout(q).join();
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void an_arrival_that_cannot_be_read_is_refused_and_saves_nothing(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        MemoryStore store = new MemoryStore();
        SyncService pvp = new SyncService(server, store, PVP, FAST);
        UUID id = UUID.randomUUID();
        store.save(id, HUB, SaveCause.LOGOUT, new byte[] {1, 2, 3}).join(); // a snapshot that cannot be read
        ServerPlayer q = TestPlayers.mockServerPlayer(h, id);
        q.getInventory().setItem(0, new ItemStack(Items.ROTTEN_FLESH)); // pvp's stale copy
        pvp.onLogin(q, 0);
        pvp.tick(0);
        h.assertTrue(!SyncService.isSyncing(id), "not left frozen");
        h.assertTrue(pvp.lastArrival(id).orElseThrow().outcome() == Arrival.Outcome.UNAVAILABLE, "unavailable: " + pvp.lastArrival(id));
        pvp.onLogout(q).join(); // the refusal disconnects them
        pvp.autosaveDue(List.of(q), 10_000_000);
        h.assertTrue(store.history(id).join().size() == 1, "the stale copy was never saved over their data");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_late_release_never_undoes_another_servers_checkout(GameTestHelper h) throws IOException {
        UUID id = UUID.randomUUID();
        MemoryStore memory = new MemoryStore();
        memory.setPresence(id, HUB, true).join();
        memory.setPresence(id, PVP, true).join(); // pvp took over (the fallback after a slow hub)
        memory.setPresence(id, HUB, false).join(); // hub's release, late
        Presence now = memory.presence(id).join().orElseThrow();
        h.assertTrue(now.online() && now.serverId().equals(PVP), "pvp still holds the player: " + now);

        FakeRedis redis = FakeRedis.start("");
        RedisClient client = new RedisClient(new RedisClient.Settings("127.0.0.1", redis.port(), "", ""));
        MemoryStore inner = new MemoryStore();
        FastStore fast = new FastStore(inner, client, "test", 60_000);
        try {
            fast.setPresence(id, HUB, true).get(5, TimeUnit.SECONDS);
            fast.setPresence(id, PVP, true).get(5, TimeUnit.SECONDS);
            fast.setPresence(id, HUB, false).get(5, TimeUnit.SECONDS);
            Presence cached = fast.presence(id).get(5, TimeUnit.SECONDS).orElseThrow();
            h.assertTrue(cached.online() && cached.serverId().equals(PVP), "the Redis copy also still names pvp: " + cached);
            h.assertTrue(fast.clearPresence(HUB).get(5, TimeUnit.SECONDS) == 0 && fast.presence(id).get(5, TimeUnit.SECONDS).orElseThrow().online(), "hub's restart releases nobody pvp holds");
        } catch (Exception e) {
            throw new net.minecraft.gametest.framework.GameTestAssertException("store call failed: " + e);
        } finally {
            client.close();
            redis.close();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 400)
    public static void a_stale_redis_copy_is_never_served_as_the_newest(GameTestHelper h) throws IOException {
        FakeRedis redis = FakeRedis.start("");
        RedisClient client = new RedisClient(new RedisClient.Settings("127.0.0.1", redis.port(), "", ""));
        MemoryStore inner = new MemoryStore();
        FastStore fast = new FastStore(inner, client, "test", 60_000);
        UUID id = UUID.randomUUID();
        try {
            fast.save(id, HUB, SaveCause.AUTOSAVE, new byte[] {1}).get(5, TimeUnit.SECONDS); // v1, cached
            redis.stop();
            client.ping().exceptionally(t -> false).get(5, TimeUnit.SECONDS); // the client notices
            fast.save(id, HUB, SaveCause.LOGOUT, new byte[] {2}).get(5, TimeUnit.SECONDS); // v2 in the database; the cache write and delete both fail
            h.assertTrue(redis.get(fast.snapshotKey(id)) != null && redis.get(fast.snapshotKey(id)).startsWith("1\n"), "Redis still holds v1");
            redis.restart();
        } catch (Exception e) {
            client.close();
            redis.close();
            throw new net.minecraft.gametest.framework.GameTestAssertException("setup failed: " + e);
        }
        h.startSequence().thenWaitUntil(() -> {
            Boolean pong = null;
            try {
                pong = client.ping().get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // not yet
            }
            h.assertTrue(Boolean.TRUE.equals(pong), "not reconnected yet");
        }).thenExecute(() -> {
            try {
                Snapshot latest = fast.latest(id).get(5, TimeUnit.SECONDS).orElseThrow();
                h.assertTrue(latest.version() == 2, "the database's v2 is served, not the stale v1: v" + latest.version());
                h.assertTrue(redis.get(fast.snapshotKey(id)) == null, "and the stale copy is gone from Redis");
            } catch (Exception e) {
                throw new net.minecraft.gametest.framework.GameTestAssertException("latest failed: " + e);
            } finally {
                client.close();
                redis.close();
            }
        }).thenSucceed();
    }

    @GameTest(template = EMPTY)
    public static void a_module_or_capability_missing_here_is_carried_forward_not_lost(GameTestHelper h) {
        MemoryStore store = new MemoryStore();
        SyncService hub = new SyncService(h.getLevel().getServer(), store, HUB, FAST);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        CompoundTag source = PlayerSnapshotCodec.capture(p);
        CompoundTag ghost = new CompoundTag();
        ghost.putInt("Keys", 7);
        source.getCompound("Modules").put("ghost_mod", ghost); // a module of a mod this server lacks
        boolean caps = ForgeDataModule.capsSupported() && PlayerSnapshotCodec.module(source, ForgeDataModule.ID).contains(ForgeDataModule.CAPS, Tag.TAG_COMPOUND);
        if (caps) {
            CompoundTag cap = new CompoundTag();
            cap.putInt("Rads", 3);
            PlayerSnapshotCodec.module(source, ForgeDataModule.ID).getCompound(ForgeDataModule.CAPS).put("othermod:radiation", cap);
        }
        Snapshot arrived = store.save(p.getUUID(), PVP, SaveCause.TRANSFER, PlayerSnapshotCodec.encode(source)).join();
        hub.restore(p, arrived, false);
        p.getInventory().setItem(0, new ItemStack(Items.STICK));
        CompoundTag saved = PlayerSnapshotCodec.decode(hub.save(p, SaveCause.LOGOUT).join().data());
        h.assertTrue(saved.getCompound("Modules").getCompound("ghost_mod").getInt("Keys") == 7, "the missing mod's module travels on: " + saved.getCompound("Modules").getAllKeys());
        h.assertTrue(countIn(saved, Items.STICK) == 1, "while this server's own modules are captured fresh");
        if (caps) {
            h.assertTrue(PlayerSnapshotCodec.module(saved, ForgeDataModule.ID).getCompound(ForgeDataModule.CAPS).getCompound("othermod:radiation").getInt("Rads") == 3, "and the missing mod's capability too");
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_totem_in_the_ender_chest_survives_a_restore(GameTestHelper h) {
        SyncService hub = new SyncService(h.getLevel().getServer(), new MemoryStore(), HUB);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        p.getEnderChestInventory().setItem(0, new ItemStack(Items.BREAD));
        Snapshot saved = hub.save(p, SaveCause.LOGOUT).join();
        ItemStack totem = TotemRuleTests.totem(h);
        UUID instance = totem.getTag().getUUID("Instance");
        p.getEnderChestInventory().setItem(5, totem);
        SyncService.Restored r = hub.restore(p, saved, false);
        h.assertTrue(r.totemsKept() == 1, "the ender chest's totem was kept: " + r);
        int found = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (BspCore.isLiveTotem(s) && s.getTag().getUUID("Instance").equals(instance)) {
                found++;
            }
        }
        h.assertTrue(found == 1, "and given back into the inventory once: " + found);
        h.assertTrue(p.getEnderChestInventory().getItem(0).is(Items.BREAD), "the ender chest has the snapshot's bread");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_world_save_writes_only_players_whose_data_changed(GameTestHelper h) {
        MemoryStore store = new MemoryStore();
        SyncService hub = new SyncService(h.getLevel().getServer(), store, HUB, FAST);
        ServerPlayer p = settled(h, hub, UUID.randomUUID(), 0);
        p.getInventory().setItem(0, new ItemStack(Items.BOOK));
        List<CompletableFuture<Snapshot>> first = hub.saveAll(List.of(p), SaveCause.WORLD_SAVE);
        first.forEach(CompletableFuture::join);
        h.assertTrue(first.size() == 1, "the first world save writes");
        h.assertTrue(hub.saveAll(List.of(p), SaveCause.WORLD_SAVE).isEmpty(), "an unchanged player writes nothing");
        p.getInventory().setItem(1, new ItemStack(Items.FEATHER));
        List<CompletableFuture<Snapshot>> third = hub.saveAll(List.of(p), SaveCause.WORLD_SAVE);
        third.forEach(CompletableFuture::join);
        h.assertTrue(third.size() == 1 && store.history(p.getUUID()).join().size() == 2, "a changed one writes again; two snapshots in all");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_crowd_due_at_once_is_auto_saved_a_few_per_tick(GameTestHelper h) {
        SyncService hub = new SyncService(h.getLevel().getServer(), new MemoryStore(), HUB, FAST);
        hub.setAutosaveMs(1_000);
        List<ServerPlayer> crowd = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ServerPlayer p = settled(h, hub, UUID.randomUUID(), 0);
            p.getInventory().setItem(0, new ItemStack(Items.ARROW, i + 1));
            crowd.add(p);
        }
        int a = hub.autosaveDue(crowd, 1_000);
        int b = hub.autosaveDue(crowd, 1_000);
        int c = hub.autosaveDue(crowd, 1_000);
        h.assertTrue(a == 4 && b == 4 && c == 2, "spread over three ticks: " + a + ", " + b + ", " + c);
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void saves_compressed_off_the_server_thread_reach_the_store_in_order(GameTestHelper h) {
        MemoryStore store = new MemoryStore();
        SyncService hub = new SyncService(h.getLevel().getServer(), store, HUB, FAST);
        ExecutorService codec = Executors.newSingleThreadExecutor();
        hub.setCodecExecutor(codec);
        try {
            ServerPlayer p = TestPlayers.mockServerPlayer(h);
            List<CompletableFuture<Snapshot>> saves = new ArrayList<>();
            for (int i = 1; i <= 5; i++) {
                p.getInventory().setItem(0, new ItemStack(Items.GOLD_NUGGET, i));
                saves.add(hub.save(p, SaveCause.ADMIN));
            }
            saves.forEach(CompletableFuture::join);
            for (Snapshot s : store.history(p.getUUID()).join()) {
                int count = countIn(PlayerSnapshotCodec.decode(s.data()), Items.GOLD_NUGGET);
                h.assertTrue(count == s.version(), "v" + s.version() + " holds the capture taken " + s.version() + "th, got " + count);
            }
        } finally {
            codec.shutdown();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void an_unreachable_database_is_retried_after_a_pause_not_on_every_call(GameTestHelper h) throws IOException {
        int port;
        try (ServerSocket probe = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            port = probe.getLocalPort(); // closed again: nothing listens there
        }
        JdbcStore store = new JdbcStore(new JdbcStore.Settings("127.0.0.1", port, "bsp", "bsp", "", false, "bsp_is_test_"));
        try {
            for (int i = 0; i < 5; i++) {
                h.assertTrue(!store.ping().join(), "no database answers");
            }
            h.assertTrue(store.connectAttempts() == 1, "one connection attempt, the other calls failed at once: " + store.connectAttempts());
        } finally {
            store.close();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void health_above_the_bare_maximum_comes_back_once_gear_applies(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        MemoryStore store = new MemoryStore();
        SyncService hub = new SyncService(server, store, HUB, FAST);
        SyncService pvp = new SyncService(server, store, PVP, FAST);
        UUID id = UUID.randomUUID();
        ServerPlayer p = settled(h, hub, id, 0);
        p.getAttribute(Attributes.MAX_HEALTH).addTransientModifier(new AttributeModifier(UUID.randomUUID(), "test gear", 20, AttributeModifier.Operation.ADDITION));
        p.setHealth(30f);
        hub.onLogout(p).join();

        ServerPlayer q = TestPlayers.mockServerPlayer(h, id);
        pvp.onLogin(q, 0);
        pvp.tick(0);
        h.assertTrue(q.getHealth() == 20f, "capped by the bare maximum at the restore: " + q.getHealth());
        q.getAttribute(Attributes.MAX_HEALTH).addTransientModifier(new AttributeModifier(UUID.randomUUID(), "test gear", 20, AttributeModifier.Operation.ADDITION)); // the gear applies on the next tick
        h.runAfterDelay(4, () -> {
            pvp.tick(1_000);
            h.assertTrue(q.getHealth() == 30f, "the saved health is back once the gear applies: " + q.getHealth());
            pvp.onLogout(q).join();
            h.succeed();
        });
    }
}
