package com.mrgregles.bsp_inventorysync.gametest;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.api.event.InventoryRestoredEvent;
import com.mrgregles.bsp_inventorysync.compat.BspCore;
import com.mrgregles.bsp_inventorysync.storage.MemoryStore;
import com.mrgregles.bsp_inventorysync.storage.Presence;
import com.mrgregles.bsp_inventorysync.storage.SaveCause;
import com.mrgregles.bsp_inventorysync.storage.Snapshot;
import com.mrgregles.bsp_inventorysync.sync.Arrival;
import com.mrgregles.bsp_inventorysync.sync.ArrivalPolicy;
import com.mrgregles.bsp_inventorysync.sync.Sync;
import com.mrgregles.bsp_inventorysync.sync.SyncEvents;
import com.mrgregles.bsp_inventorysync.sync.SyncService;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The arrival handshake of D5 and its races, with two {@link SyncService}s ("hub" and "pvp") sharing
 * one in-memory store as two servers would share the database, driven tick by tick on a manual clock.
 * The memory store completes at once, so one tick settles each step.
 */
@GameTestHolder(BSPInventorySync.MODID)
@PrefixGameTestTemplate(false)
public class HandshakeTests {
    private static final String EMPTY = "empty";
    private static final String HUB = "hub";
    private static final String PVP = "pvp";
    private static final ArrivalPolicy FAST = new ArrivalPolicy(1_000, false);

    static final class Restores {
        final List<InventoryRestoredEvent> events = new ArrayList<>();

        @SubscribeEvent
        public void on(InventoryRestoredEvent e) {
            events.add(e);
        }
    }

    /** A player settled on {@code service}: logged in, handshake done, inventory as given. */
    private static ServerPlayer settled(GameTestHelper h, SyncService service, UUID uuid, long now, ItemStack... hotbar) {
        ServerPlayer p = TestPlayers.mockServerPlayer(h, uuid);
        service.onLogin(p, now);
        service.tick(now);
        h.assertTrue(!SyncService.isSyncing(uuid), "settled after one tick on " + service.serverId());
        for (int i = 0; i < hotbar.length; i++) {
            p.getInventory().setItem(i, hotbar[i]);
        }
        return p;
    }

    private static Presence presence(GameTestHelper h, MemoryStore store, UUID uuid) {
        return store.presence(uuid).join().orElseThrow(() -> new AssertionError("no presence"));
    }

    @GameTest(template = EMPTY)
    public static void a_switch_arrives_with_the_saved_inventory_and_no_visible_wait(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        MemoryStore store = new MemoryStore();
        SyncService hub = new SyncService(server, store, HUB, FAST);
        SyncService pvp = new SyncService(server, store, PVP, FAST);
        UUID id = UUID.randomUUID();
        Restores restores = new Restores();
        MinecraftForge.EVENT_BUS.register(restores);
        try {
            ServerPlayer p = settled(h, hub, id, 1_000, new ItemStack(Items.DIAMOND_PICKAXE), new ItemStack(Items.TORCH, 12));
            h.assertTrue(restores.events.size() == 1 && restores.events.get(0).getVersion() == 0, "a first login gets the version-0 event: " + restores.events);
            h.assertTrue(presence(h, store, id).online() && presence(h, store, id).serverId().equals(HUB), "checked in on hub");
            h.assertTrue(hub.lastArrival(id).orElseThrow().outcome() == Arrival.Outcome.CLEAN, "clean first arrival");

            hub.onLogout(p).join(); // the proxy switch: hub saves and releases
            Presence after = presence(h, store, id);
            h.assertTrue(!after.online() && after.serverId().equals(HUB), "released by hub after the save: " + after);
            Snapshot saved = store.latest(id).join().orElseThrow();
            h.assertTrue(saved.version() == 1 && saved.cause() == SaveCause.LOGOUT && saved.serverId().equals(HUB), "saved as v1 by hub: " + saved);

            ServerPlayer q = TestPlayers.mockServerPlayer(h, id);
            pvp.onLogin(q, 2_000);
            h.assertTrue(SyncService.isSyncing(id), "frozen on arrival");
            pvp.tick(2_000);
            h.assertTrue(!SyncService.isSyncing(id), "settled in the first tick: the source had already released");
            h.assertTrue(q.getInventory().getItem(0).is(Items.DIAMOND_PICKAXE) && q.getInventory().getItem(1).getCount() == 12, "hub's inventory arrived on pvp");
            Presence now = presence(h, store, id);
            h.assertTrue(now.online() && now.serverId().equals(PVP), "checked in on pvp: " + now);
            Arrival a = pvp.lastArrival(id).orElseThrow();
            h.assertTrue(a.outcome() == Arrival.Outcome.CLEAN && a.version() == 1 && a.waitedMs() == 0, "clean arrival of v1 with no wait: " + a);
            h.assertTrue(restores.events.size() == 2 && restores.events.get(1).getEntity() == q && restores.events.get(1).getSourceServer().equals(HUB), "the restored event names the source: " + restores.events);
        } finally {
            MinecraftForge.EVENT_BUS.unregister(restores);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void an_arrival_waits_frozen_until_the_source_releases_then_applies(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        MemoryStore store = new MemoryStore();
        SyncService hub = new SyncService(server, store, HUB, FAST);
        SyncService pvp = new SyncService(server, store, PVP, FAST);
        UUID id = UUID.randomUUID();
        ServerPlayer p = settled(h, hub, id, 0, new ItemStack(Items.GOLD_BLOCK, 3));

        // the target sees the login while hub still holds the player
        ServerPlayer q = TestPlayers.mockServerPlayer(h, id);
        q.getInventory().setItem(0, new ItemStack(Items.ROTTEN_FLESH)); // pvp's stale local copy
        Vec3 anchor = q.position();
        pvp.onLogin(q, 100);
        pvp.tick(100);
        pvp.tick(150);
        h.assertTrue(SyncService.isSyncing(id), "still frozen while hub holds the player");
        h.assertTrue(pvp.waiting() == 1, "one player waiting");
        h.assertTrue(q.getInventory().getItem(0).is(Items.ROTTEN_FLESH), "nothing applied yet");

        // the freeze: walking is undone, actions and damage are refused
        q.setPos(anchor.x + 0.6, anchor.y, anchor.z + 0.3);
        pvp.tick(200);
        h.assertTrue(q.position().distanceTo(anchor) < 1.0e-3, "walked, put back: " + q.position() + " vs " + anchor);
        BlockPos at = BlockPos.containing(anchor);
        PlayerInteractEvent.RightClickBlock click = new PlayerInteractEvent.RightClickBlock(q, InteractionHand.MAIN_HAND, at, new BlockHitResult(anchor, net.minecraft.core.Direction.UP, at, false));
        MinecraftForge.EVENT_BUS.post(click);
        h.assertTrue(click.isCanceled(), "a right click is refused while frozen");
        EntityItemPickupEvent pickup = new EntityItemPickupEvent(q, new ItemEntity(h.getLevel(), anchor.x, anchor.y, anchor.z, new ItemStack(Items.APPLE)));
        MinecraftForge.EVENT_BUS.post(pickup);
        h.assertTrue(pickup.isCanceled(), "a pickup is refused while frozen");
        LivingAttackEvent hurt = new LivingAttackEvent(q, q.damageSources().generic(), 4f);
        MinecraftForge.EVENT_BUS.post(hurt);
        h.assertTrue(hurt.isCanceled(), "damage is refused while frozen");

        // a landing-sized jump moves the anchor instead of being undone (BSP-Teleport lands the player after the login)
        q.setPos(anchor.x + 30, anchor.y, anchor.z);
        pvp.tick(250);
        h.assertTrue(q.position().distanceTo(anchor) > 29, "a teleport is kept: " + q.position());

        hub.onLogout(p).join(); // hub finishes its save and releases
        pvp.tick(300);
        h.assertTrue(!SyncService.isSyncing(id), "settled on the first tick after the release");
        h.assertTrue(q.getInventory().getItem(0).is(Items.GOLD_BLOCK), "hub's inventory applied, the stale copy gone");
        Arrival a = pvp.lastArrival(id).orElseThrow();
        h.assertTrue(a.outcome() == Arrival.Outcome.CLEAN && a.waitedMs() == 200, "clean after a 200 ms wait: " + a);
        h.assertTrue(presence(h, store, id).serverId().equals(PVP) && presence(h, store, id).online(), "checked in on pvp");
        LivingAttackEvent hurtLater = new LivingAttackEvent(q, q.damageSources().generic(), 4f);
        MinecraftForge.EVENT_BUS.post(hurtLater);
        h.assertTrue(!hurtLater.isCanceled(), "damage is allowed again once settled");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_frozen_player_who_tosses_an_item_gets_it_back_and_a_full_inventory_drops_the_rest(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        MemoryStore store = new MemoryStore();
        SyncService pvp = new SyncService(server, store, PVP, FAST);
        UUID id = UUID.randomUUID();
        store.setPresence(id, HUB, true).join(); // hub still holds the player, so they stay frozen on pvp
        ServerPlayer q = TestPlayers.mockServerPlayer(h, id);
        Vec3 start = h.absoluteVec(new Vec3(1.5, 2, 1.5));
        q.moveTo(start.x, start.y, start.z, 0, 0);
        AABB around = new AABB(start, start).inflate(4);
        pvp.onLogin(q, 0);
        pvp.tick(0);
        h.assertTrue(SyncService.isSyncing(id), "frozen while hub holds the player");
        try {
            // Q (the whole stack) on a hotbar slot: the stack has left the inventory before the event
            q.getInventory().selected = 0;
            q.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 5));
            q.drop(true);
            h.assertTrue(q.getInventory().countItem(Items.DIAMOND) == 5, "a tossed stack comes back: " + q.getInventory().countItem(Items.DIAMOND));
            h.assertTrue(h.getLevel().getEntitiesOfClass(ItemEntity.class, around).isEmpty(), "and nothing is dropped");

            // thrown from the cursor into a full inventory: what fits comes back, the rest drops as a normal toss
            for (int i = 0; i < 35; i++) {
                q.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
            }
            q.getInventory().setItem(35, new ItemStack(Items.EMERALD, 62));
            q.drop(new ItemStack(Items.EMERALD, 5), true);
            h.assertTrue(q.getInventory().countItem(Items.EMERALD) == 64, "two fit back: " + q.getInventory().countItem(Items.EMERALD));
            List<ItemEntity> dropped = h.getLevel().getEntitiesOfClass(ItemEntity.class, around);
            h.assertTrue(dropped.size() == 1 && dropped.get(0).getItem().is(Items.EMERALD) && dropped.get(0).getItem().getCount() == 3, "the other three are on the ground: " + dropped);
        } finally {
            h.getLevel().getEntitiesOfClass(ItemEntity.class, around).forEach(ItemEntity::discard);
            pvp.onLogout(q).join();
        }
        h.assertTrue(!SyncService.isSyncing(id), "no longer frozen after leaving");
        h.succeed();
    }

    /** BSP-Core's login wait (request R3), by reflection so the test still runs against a BSP-Core without it. */
    @Nullable
    private static Method loginSettle(String name, Class<?>... types) {
        try {
            return Class.forName("com.mrgregles.bsp_core.admin.LoginSettle").getMethod(name, types);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    @GameTest(template = EMPTY)
    public static void bsp_core_settles_a_login_on_the_one_restore_event_with_the_sync_on_or_off(GameTestHelper h) throws ReflectiveOperationException {
        MinecraftServer server = h.getLevel().getServer();
        Method hold = loginSettle("hold", ServerPlayer.class, int.class);
        Method waiting = loginSettle("isWaiting", UUID.class);
        Restores restores = new Restores();
        MinecraftForge.EVENT_BUS.register(restores);
        try {
            // the sync is off in the dev run: a login still posts one version-0 event, so BSP-Core settles at once
            h.assertTrue(Sync.active() == null, "the sync is off in the dev run");
            ServerPlayer off = TestPlayers.mockServerPlayer(h);
            if (hold != null) {
                hold.invoke(null, off, 600); // as BSP-Core's own login listener does
            }
            SyncEvents.onLogin(new PlayerEvent.PlayerLoggedInEvent(off));
            h.assertTrue(restores.events.size() == 1 && restores.events.get(0).getEntity() == off && restores.events.get(0).getVersion() == 0, "one version-0 event with the sync off: " + restores.events);
            if (waiting != null) {
                h.assertTrue(!(boolean) waiting.invoke(null, off.getUUID()), "BSP-Core settled the login at once");
            }

            // the sync on: BSP-Core waits while the player is frozen and settles on the arrival's event
            MemoryStore store = new MemoryStore();
            SyncService pvp = new SyncService(server, store, PVP, FAST);
            UUID id = UUID.randomUUID();
            store.setPresence(id, HUB, true).join();
            ServerPlayer q = TestPlayers.mockServerPlayer(h, id);
            if (hold != null) {
                hold.invoke(null, q, 600);
            }
            pvp.onLogin(q, 0);
            pvp.tick(0);
            h.assertTrue(SyncService.isSyncing(id) && restores.events.size() == 1, "frozen, no event yet");
            if (waiting != null) {
                h.assertTrue((boolean) waiting.invoke(null, id), "BSP-Core still waits while the player is frozen");
            }
            store.setPresence(id, HUB, false).join(); // hub releases the player
            pvp.tick(50);
            h.assertTrue(!SyncService.isSyncing(id) && restores.events.size() == 2 && restores.events.get(1).getEntity() == q, "one event for the arrival: " + restores.events);
            if (waiting != null) {
                h.assertTrue(!(boolean) waiting.invoke(null, id), "BSP-Core settled the login on it");
            }
            pvp.onLogout(q).join();
        } finally {
            MinecraftForge.EVENT_BUS.unregister(restores);
        }
        h.succeed();
    }

    private static Object call(@Nullable Method m, Object... args) {
        try {
            return m == null ? null : m.invoke(null, args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** BSP-Core's owed items for a player (its season data), by reflection; null without BSP-Core's class. */
    @Nullable
    @SuppressWarnings("unchecked")
    private static Map<UUID, List<ItemStack>> owedItems(MinecraftServer server) {
        try {
            Object season = Class.forName("com.mrgregles.bsp_core.admin.SeasonData").getMethod("get", MinecraftServer.class).invoke(null, server);
            return (Map<UUID, List<ItemStack>>) season.getClass().getField("pendingItems").get(season);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    @GameTest(template = EMPTY, timeoutTicks = 100)
    public static void a_dead_arrival_past_bsp_cores_wait_gets_its_data_and_owed_totem_after_the_respawn(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        Method hold = loginSettle("hold", ServerPlayer.class, int.class);
        Method waiting = loginSettle("isWaiting", UUID.class);
        Map<UUID, List<ItemStack>> owed = owedItems(server);
        if (hold == null || waiting == null || owed == null) {
            h.succeed(); // a BSP-Core without the login wait (before 1.4.1): nothing of BSP-Core's to check
            return;
        }
        MemoryStore store = new MemoryStore();
        SyncService hub = new SyncService(server, store, HUB, FAST);
        SyncService pvp = new SyncService(server, store, PVP, FAST);
        UUID id = UUID.randomUUID();
        hub.onLogout(settled(h, hub, id, 0, new ItemStack(Items.DIAMOND_PICKAXE))).join(); // saved and released by hub

        ItemStack totem = TotemRuleTests.totem(h);
        UUID instance = totem.getTag().getUUID("Instance");
        owed.computeIfAbsent(id, k -> new ArrayList<>()).add(totem.copy()); // BSP-Core owes them a totem (a transfer's hand-back)

        ServerPlayer dead = TestPlayers.mockServerPlayer(h, id);
        dead.setHealth(0f); // they left on the death screen last time
        call(hold, dead, 5); // BSP-Core's login listener, with a 5-tick wait
        pvp.onLogin(dead, 100);
        pvp.tick(100);
        h.assertTrue(SyncService.isSyncing(id), "the dead arrival waits for the respawn");

        Restores restores = new Restores();
        MinecraftForge.EVENT_BUS.register(restores);
        h.runAfterDelay(20, () -> {
            try {
                // four times BSP-Core's wait has passed on the death screen: nothing settled, nothing handed to the corpse
                h.assertTrue((boolean) call(waiting, id), "BSP-Core still waits: its countdown stands still while the player is dead");
                h.assertTrue(owed.getOrDefault(id, List.of()).size() == 1, "the totem is still owed, not given to the dead entity");
                h.assertTrue(TotemRuleTests.liveTotems(dead) == 0 && dead.getInventory().getItem(0).isEmpty(), "the dead entity got nothing");

                // the respawn: BSP-Core's listener (normal priority) restarts its wait on the new entity, then ours (LOWEST) restores
                ServerPlayer alive = TestPlayers.mockServerPlayer(h, id);
                MinecraftForge.EVENT_BUS.post(new PlayerEvent.PlayerRespawnEvent(alive, false));
                h.assertTrue((boolean) call(waiting, id), "still waiting at the respawn, for the restore");
                pvp.onRespawn(alive, false, 2_000);
                h.assertTrue(!SyncService.isSyncing(id) && alive.getInventory().getItem(0).is(Items.DIAMOND_PICKAXE), "the data went to the respawned player");
                List<InventoryRestoredEvent> mine = restores.events.stream().filter(e -> e.getEntity().getUUID().equals(id)).toList(); // other tests run meanwhile
                h.assertTrue(mine.size() == 1 && mine.get(0).getEntity() == alive, "one restore event, for the living entity: " + mine.size());
                h.assertTrue(!(boolean) call(waiting, id), "BSP-Core settled on that event");
                int found = 0;
                for (int i = 0; i < alive.getInventory().getContainerSize(); i++) {
                    ItemStack s = alive.getInventory().getItem(i);
                    if (BspCore.isLiveTotem(s) && s.hasTag() && s.getTag().hasUUID("Instance") && s.getTag().getUUID("Instance").equals(instance)) {
                        found++;
                    }
                }
                h.assertTrue(found == 1, "the owed totem arrived once, in the respawned player's inventory: " + found);
                h.assertTrue(owed.getOrDefault(id, List.of()).isEmpty(), "and is no longer owed");
                pvp.onLogout(alive).join();
            } finally {
                MinecraftForge.EVENT_BUS.unregister(restores);
                owed.remove(id);
            }
            h.succeed();
        });
    }

    /** A static method of BSP-Core by reflection, private ones too (older versions keep some private); null when absent. */
    @Nullable
    private static Method bspCoreMethod(String className, String name, Class<?>... types) {
        try {
            Method m = Class.forName(className).getDeclaredMethod(name, types);
            m.setAccessible(true);
            return m;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    @GameTest(template = EMPTY)
    public static void a_full_inventory_switch_gets_the_owed_totem_by_bsp_cores_totem_route_and_it_stays_live(GameTestHelper h) throws ReflectiveOperationException {
        MinecraftServer server = h.getLevel().getServer();
        Method hold = loginSettle("hold", ServerPlayer.class, int.class);
        Method waiting = loginSettle("isWaiting", UUID.class);
        Method ledgerOf = bspCoreMethod("com.mrgregles.bsp_core.data.TotemLedger", "get", MinecraftServer.class);
        Method track = bspCoreMethod("com.mrgregles.bsp_core.event.TotemRestrictionHandler", "trackIdentity", ServerPlayer.class);
        Map<UUID, List<ItemStack>> owed = owedItems(server);
        if (hold == null || waiting == null || ledgerOf == null || track == null || owed == null) {
            h.succeed(); // a BSP-Core without the login wait or the ledger: nothing of BSP-Core's to check
            return;
        }
        Object ledger = ledgerOf.invoke(null, server);
        Method seen = ledger.getClass().getMethod("seen", UUID.class, UUID.class, UUID.class, int.class, CompoundTag.class, long.class);
        Method currentInstance = ledger.getClass().getMethod("currentInstance", UUID.class);
        Method forget = ledger.getClass().getMethod("forget", UUID.class);

        MemoryStore store = new MemoryStore();
        SyncService hub = new SyncService(server, store, HUB, FAST);
        SyncService pvp = new SyncService(server, store, PVP, FAST);
        UUID id = UUID.randomUUID();
        ItemStack[] full = new ItemStack[36];
        for (int i = 0; i < full.length; i++) {
            full[i] = new ItemStack(Items.COBBLESTONE, 64);
        }
        hub.onLogout(settled(h, hub, id, 0, full)).join(); // hub saves a full inventory and releases the player

        // the totem left hub with the transfer; pvp's ledger still remembers an older instance of it
        UUID totemId = UUID.randomUUID();
        UUID older = UUID.randomUUID();
        UUID travelling = UUID.randomUUID();
        seen.invoke(ledger, totemId, older, UUID.randomUUID(), 0, null, server.overworld().getGameTime());
        ItemStack totem = TotemRuleTests.totem(h);
        totem.getOrCreateTag().putUUID("TotemId", totemId);
        totem.getOrCreateTag().putUUID("Instance", travelling);
        owed.computeIfAbsent(id, k -> new ArrayList<>()).add(totem.copy()); // parked for the player while they wait (R3)

        ServerPlayer q = TestPlayers.mockServerPlayer(h, id);
        Vec3 start = h.absoluteVec(new Vec3(1.5, 2, 1.5));
        q.moveTo(start.x, start.y, start.z, 0, 0);
        AABB around = new AABB(start, start).inflate(4);
        try {
            call(hold, q, 600); // BSP-Core's login listener
            pvp.onLogin(q, 0);
            pvp.tick(0); // hub has released: arrive, restore the full inventory, post the event, BSP-Core settles
            h.assertTrue(!SyncService.isSyncing(id) && !(boolean) call(waiting, id), "restored and settled");
            track.invoke(null, q); // BSP-Core's identity check, as on the player's next tick

            int found = 0;
            int cobbleHeld = 0;
            for (int i = 0; i < q.getInventory().getContainerSize(); i++) {
                ItemStack s = q.getInventory().getItem(i);
                if (s.hasTag() && s.getTag().hasUUID("TotemId") && s.getTag().getUUID("TotemId").equals(totemId)) {
                    found++;
                    h.assertTrue(BspCore.isLiveTotem(s) && s.getTag().getUUID("Instance").equals(travelling), "the owed totem is live, the instance that travelled: " + s.getTag());
                }
                if (s.is(Items.COBBLESTONE)) {
                    cobbleHeld += s.getCount();
                }
            }
            h.assertTrue(found == 1, "the owed totem is in the full inventory exactly once: " + found);
            h.assertTrue(travelling.equals(currentInstance.invoke(ledger, totemId)), "the ledger recorded its arrival: " + currentInstance.invoke(ledger, totemId));
            h.assertTrue(owed.getOrDefault(id, List.of()).isEmpty(), "and it is no longer owed");
            List<ItemEntity> dropped = h.getLevel().getEntitiesOfClass(ItemEntity.class, around);
            h.assertTrue(dropped.stream().noneMatch(e -> e.getItem().getItem() == totem.getItem()), "no totem on the ground: " + dropped);
            int cobbleDropped = dropped.stream().filter(e -> e.getItem().is(Items.COBBLESTONE)).mapToInt(e -> e.getItem().getCount()).sum();
            h.assertTrue(cobbleDropped >= 64 && cobbleHeld + cobbleDropped == 36 * 64, "a displaced stack is at their feet and nothing is lost: held " + cobbleHeld + ", dropped " + cobbleDropped);
        } finally {
            h.getLevel().getEntitiesOfClass(ItemEntity.class, around).forEach(ItemEntity::discard);
            owed.remove(id);
            forget.invoke(ledger, totemId);
            pvp.onLogout(q).join();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_timeout_applies_the_last_known_snapshot_as_a_fallback_or_kicks(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        MemoryStore store = new MemoryStore();
        SyncService hub = new SyncService(server, store, HUB, FAST);
        UUID id = UUID.randomUUID();
        ServerPlayer p = settled(h, hub, id, 0, new ItemStack(Items.EMERALD, 5));
        hub.save(p, SaveCause.AUTOSAVE).join(); // v1 exists; hub still holds the player (crashed, or very slow)

        Restores restores = new Restores();
        MinecraftForge.EVENT_BUS.register(restores);
        try {
            SyncService pvp = new SyncService(server, store, PVP, new ArrivalPolicy(1_000, false));
            ServerPlayer q = TestPlayers.mockServerPlayer(h, id);
            pvp.onLogin(q, 10_000);
            pvp.tick(10_000);
            pvp.tick(10_999);
            h.assertTrue(SyncService.isSyncing(id), "still waiting just before the timeout");
            pvp.tick(11_000);
            h.assertTrue(!SyncService.isSyncing(id), "settled at the timeout");
            h.assertTrue(q.getInventory().getItem(0).is(Items.EMERALD), "the last known snapshot was applied");
            Arrival a = pvp.lastArrival(id).orElseThrow();
            h.assertTrue(a.outcome() == Arrival.Outcome.FALLBACK && a.version() == 1 && a.waitedMs() == 1_000, "fallback arrival: " + a);
            h.assertTrue(restores.events.size() == 1 && restores.events.get(0).isFallback(), "the event says fallback");
            h.assertTrue(presence(h, store, id).serverId().equals(PVP), "pvp holds the player now, whatever hub said");

            // the kick variant: hub "holds" the player again, a kicking server times out
            store.setPresence(id, HUB, true).join();
            SyncService strict = new SyncService(server, store, "strict", new ArrivalPolicy(500, true));
            ServerPlayer r = TestPlayers.mockServerPlayer(h, id);
            r.getInventory().setItem(0, new ItemStack(Items.STICK));
            strict.onLogin(r, 20_000);
            strict.tick(20_000);
            strict.tick(20_500);
            h.assertTrue(!SyncService.isSyncing(id), "no longer syncing after the kick");
            Arrival k = strict.lastArrival(id).orElseThrow();
            h.assertTrue(k.outcome() == Arrival.Outcome.KICKED && k.version() == 0, "kicked: " + k);
            h.assertTrue(r.getInventory().getItem(0).is(Items.STICK), "nothing applied to the kicked player");
            h.assertTrue(presence(h, store, id).serverId().equals(HUB), "the kicking server took nothing over");
            h.assertTrue(restores.events.size() == 1, "no restored event for a kick");
        } finally {
            MinecraftForge.EVENT_BUS.unregister(restores);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void leaving_before_the_restore_saves_nothing_and_takes_nothing_over(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        MemoryStore store = new MemoryStore();
        SyncService hub = new SyncService(server, store, HUB, FAST);
        SyncService pvp = new SyncService(server, store, PVP, FAST);
        UUID id = UUID.randomUUID();
        settled(h, hub, id, 0, new ItemStack(Items.NETHERITE_INGOT));
        int before = store.history(id).join().size();

        ServerPlayer q = TestPlayers.mockServerPlayer(h, id);
        q.getInventory().setItem(0, new ItemStack(Items.ROTTEN_FLESH)); // stale local copy that must never be saved
        pvp.onLogin(q, 0);
        pvp.tick(0);
        h.assertTrue(SyncService.isSyncing(id), "waiting on hub");
        pvp.onLogout(q).join(); // disconnected during the sync
        h.assertTrue(!SyncService.isSyncing(id) && pvp.waiting() == 0, "no longer waiting");
        h.assertTrue(store.history(id).join().size() == before, "nothing was saved for the player who left mid-sync");
        Presence pr = presence(h, store, id);
        h.assertTrue(pr.online() && pr.serverId().equals(HUB), "hub still holds the player: " + pr);
        h.assertTrue(pvp.lastArrival(id).orElseThrow().outcome() == Arrival.Outcome.LEFT, "recorded as left");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_dead_arrival_waits_for_the_respawn_and_a_death_saves_at_respawn(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        MemoryStore store = new MemoryStore();
        SyncService hub = new SyncService(server, store, HUB, FAST);
        SyncService pvp = new SyncService(server, store, PVP, FAST);
        UUID id = UUID.randomUUID();
        ServerPlayer p = settled(h, hub, id, 0, new ItemStack(Items.BOW));
        hub.onLogout(p).join();

        ServerPlayer dead = TestPlayers.mockServerPlayer(h, id);
        dead.setHealth(0f); // this server's copy of the player died last time they were here
        h.assertTrue(dead.isDeadOrDying(), "the arriving entity is dead");
        pvp.onLogin(dead, 0);
        pvp.tick(0);
        h.assertTrue(SyncService.isSyncing(id), "a dead arrival stays frozen until the respawn");
        h.assertTrue(presence(h, store, id).serverId().equals(PVP), "but pvp has taken the player over");
        h.assertTrue(dead.getInventory().getItem(0).isEmpty(), "nothing applied to the corpse");

        ServerPlayer alive = TestPlayers.mockServerPlayer(h, id);
        pvp.onRespawn(alive, false, 50);
        h.assertTrue(!SyncService.isSyncing(id), "settled at the respawn");
        h.assertTrue(alive.getInventory().getItem(0).is(Items.BOW), "the data went to the respawned player");
        h.assertTrue(pvp.lastArrival(id).orElseThrow().outcome() == Arrival.Outcome.CLEAN, "clean");

        // an ordinary death later: saved at respawn, after the drops left the inventory
        int before = store.history(id).join().size();
        ServerPlayer again = TestPlayers.mockServerPlayer(h, id);
        again.getInventory().setItem(0, new ItemStack(Items.ARROW, 7)); // what keepInventory or a grave left them
        pvp.onRespawn(again, false, 100);
        Snapshot death = store.latest(id).join().orElseThrow();
        h.assertTrue(store.history(id).join().size() == before + 1 && death.cause() == SaveCause.DEATH, "one death snapshot: " + death);
        pvp.onRespawn(again, true, 150); // back from the End: not a death
        h.assertTrue(store.history(id).join().size() == before + 1, "leaving the End saves nothing");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void begin_transfer_saves_early_and_the_logout_saves_again_as_a_transfer(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        MemoryStore store = new MemoryStore();
        SyncService hub = new SyncService(server, store, HUB, FAST);
        UUID id = UUID.randomUUID();
        ServerPlayer p = settled(h, hub, id, 0, new ItemStack(Items.COMPASS));

        h.assertTrue(hub.beginTransfer(p, PVP), "transfer announced");
        h.assertTrue(hub.transferTarget(id).orElse("").equals(PVP), "target remembered");
        List<Snapshot> early = store.history(id).join();
        h.assertTrue(early.size() == 1 && early.get(0).cause() == SaveCause.TRANSFER, "the early snapshot is a transfer: " + early);
        hub.onLogout(p).join();
        List<Snapshot> both = store.history(id).join();
        h.assertTrue(both.size() == 2 && both.get(0).cause() == SaveCause.TRANSFER && both.get(0).version() == 2, "the logout saved again as a transfer: " + both);
        h.assertTrue(hub.transferTarget(id).isEmpty(), "the transfer mark is cleared by the logout");

        // cancelled: the logout is ordinary
        UUID other = UUID.randomUUID();
        ServerPlayer o = settled(h, hub, other, 0, new ItemStack(Items.MAP));
        h.assertTrue(hub.beginTransfer(o, PVP) && hub.cancelTransfer(o) && !hub.cancelTransfer(o), "begin, cancel once");
        hub.onLogout(o).join();
        h.assertTrue(store.latest(other).join().orElseThrow().cause() == SaveCause.LOGOUT, "an ordinary logout after a cancelled transfer");

        // a player whose data is still arriving cannot be transferred
        SyncService pvp = new SyncService(server, store, PVP, FAST);
        ServerPlayer q = TestPlayers.mockServerPlayer(h, UUID.randomUUID());
        store.setPresence(q.getUUID(), HUB, true).join();
        pvp.onLogin(q, 0);
        pvp.tick(0);
        h.assertTrue(!pvp.beginTransfer(q, HUB), "no transfer while syncing");
        pvp.onLogout(q).join();
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void world_save_saves_settled_players_and_start_clears_this_servers_presence(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        MemoryStore store = new MemoryStore();
        SyncService hub = new SyncService(server, store, HUB, FAST);
        SyncService pvp = new SyncService(server, store, PVP, FAST);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        ServerPlayer pa = settled(h, hub, a, 0, new ItemStack(Items.BOOK));
        ServerPlayer pb = TestPlayers.mockServerPlayer(h, b);
        store.setPresence(b, PVP, true).join(); // b is held by pvp, arriving on hub
        hub.onLogin(pb, 0);
        hub.tick(0);
        h.assertTrue(SyncService.isSyncing(b), "b is still arriving");

        var saves = hub.saveAll(List.of(pa, pb), SaveCause.WORLD_SAVE);
        h.assertTrue(saves.size() == 1, "only the settled player is saved on a world save");
        h.assertTrue(store.latest(a).join().orElseThrow().cause() == SaveCause.WORLD_SAVE, "a's world save");
        h.assertTrue(store.latest(b).join().isEmpty(), "b was not saved");

        // hub restarts: everyone it held is released; pvp's player is untouched
        int cleared = hub.onServerStarted().join();
        h.assertTrue(cleared == 1, "hub held one player, released " + cleared);
        h.assertTrue(!presence(h, store, a).online(), "a released");
        h.assertTrue(presence(h, store, b).online() && presence(h, store, b).serverId().equals(PVP), "b still held by pvp");
        hub.onLogout(pb).join();
        h.succeed();
    }
}
