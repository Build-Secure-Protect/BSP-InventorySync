package com.mrgregles.bsp_inventorysync.gametest;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.grave.GraveContents;
import com.mrgregles.bsp_inventorysync.grave.GraveService;
import com.mrgregles.bsp_inventorysync.grave.WorldGraveStore;
import com.mrgregles.bsp_inventorysync.storage.Grave;
import com.mrgregles.bsp_inventorysync.storage.GraveStore;
import com.mrgregles.bsp_inventorysync.storage.MemoryStore;
import com.mrgregles.bsp_inventorysync.storage.SaveCause;
import com.mrgregles.bsp_inventorysync.storage.Snapshot;
import com.mrgregles.bsp_inventorysync.sync.SyncService;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Graves (D25, task GR-1): the stores' rules, the world's own grave data across a save, and the death capture:
 * the drops go into a grave of their own with the slots they came from and the death's XP, nothing for a PvP death
 * or while the player's data arrives, the spot (dry, beside a dropped totem, near the last solid ground, or none),
 * and the hand-over to the database with the world's data as the safety net. One test dies through Forge's real
 * events. Blocks are built inside the empty template; the search radius is kept small so it stays inside it.
 */
@GameTestHolder(BSPInventorySync.MODID)
@PrefixGameTestTemplate(false)
public class GraveTests {
    private static final String EMPTY = "empty";
    private static final String HUB = "hub";

    private static GraveService service(GameTestHelper h, Supplier<SyncService> sync) {
        return new GraveService(h.getLevel().getServer(), new WorldGraveStore(), sync, 2);
    }

    private static GraveService localService(GameTestHelper h) {
        return service(h, () -> null);
    }

    /** A stone floor under the relative spot and the player standing on it, in the middle of the block. */
    private static BlockPos standOn(GameTestHelper h, ServerPlayer p, int x, int y, int z) {
        h.setBlock(new BlockPos(x, y - 1, z), Blocks.STONE);
        BlockPos abs = h.absolutePos(new BlockPos(x, y, z));
        p.moveTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5);
        return abs;
    }

    private static List<ItemEntity> drops(GameTestHelper h, ServerPlayer p, ItemStack... stacks) {
        List<ItemEntity> out = new ArrayList<>();
        for (ItemStack s : stacks) {
            out.add(new ItemEntity(h.getLevel(), p.getX(), p.getY(), p.getZ(), s.copy()));
        }
        return out;
    }

    private static DamageSource fall(GameTestHelper h) {
        return h.getLevel().damageSources().fall();
    }

    private static Grave grave(UUID owner, long diedAt, long touchedAt) {
        return new Grave(UUID.randomUUID(), owner, "Steve", HUB, "minecraft:overworld", 1, 64, 2, true, diedAt, touchedAt, 0, 0, 1, 0,
                new GraveContents(List.of(new GraveContents.Entry("inv:0", new ItemStack(Items.DIAMOND, 3))), new CompoundTag()).encode());
    }

    /** An orb's points, counting the orbs vanilla merged into it. */
    static int points(ExperienceOrb orb) {
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        orb.saveWithoutId(tag);
        return tag.getShort("Value") * Math.max(1, tag.getInt("Count"));
    }

    private static GraveContents contents(Grave g) {
        return GraveContents.decode(g.data());
    }

    // ------------------------------------------------------------------ the stores

    private static void storeRules(GameTestHelper h, GraveStore store, String which) {
        UUID owner = UUID.randomUUID();
        Grave older = grave(owner, 1_000, 1_000), newer = grave(owner, 2_000, 5_000), other = grave(UUID.randomUUID(), 3_000, 3_000);
        h.assertTrue(store.addGrave(older).join() && store.addGrave(newer).join() && store.addGrave(other).join(), which + ": three graves added");
        h.assertTrue(!store.addGrave(older.withPlaced(false)).join(), which + ": the same id again is refused and changes nothing");
        h.assertTrue(store.grave(older.id()).join().orElseThrow().placed(), which + ": the first copy stands");
        List<UUID> mine = store.graves(owner).join().stream().map(Grave::id).toList();
        h.assertTrue(mine.equals(List.of(newer.id(), older.id())), which + ": every grave of the owner, newest death first: " + mine);

        Grave read = store.grave(older.id()).join().orElseThrow();
        h.assertTrue(store.updateGrave(read.withContents(0, 0, read.data(), 9_000)).join(), which + ": an update at the read revision");
        Grave after = store.grave(older.id()).join().orElseThrow();
        h.assertTrue(after.revision() == read.revision() + 1 && after.touchedAt() == 9_000 && after.stacks() == 0, which + ": stored one revision on: " + after);
        h.assertTrue(!store.updateGrave(read.withContents(5, 0, read.data(), 9_500)).join(), which + ": an update at a stale revision is refused (another server changed it first)");
        h.assertTrue(!store.deleteGrave(older.id(), read.revision()).join(), which + ": so is a delete at a stale revision");
        h.assertTrue(store.deleteGrave(older.id(), after.revision()).join() && store.grave(older.id()).join().isEmpty(), which + ": a delete at the stored revision");

        List<UUID> due = store.gravesTouchedBefore(6_000, 10).join().stream().map(Grave::id).toList();
        h.assertTrue(due.equals(List.of(other.id(), newer.id())), which + ": graves touched before the time, oldest first: " + due);
        h.assertTrue(store.gravesTouchedBefore(6_000, 1).join().size() == 1, which + ": at most the limit");
    }

    @GameTest(template = EMPTY)
    public static void the_grave_stores_keep_every_grave_and_change_one_only_at_its_revision(GameTestHelper h) {
        storeRules(h, new MemoryStore(), "memory store");
        storeRules(h, new WorldGraveStore(), "world data");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void the_world_grave_data_survives_a_save_and_keeps_what_it_cannot_read(GameTestHelper h) {
        WorldGraveStore store = new WorldGraveStore();
        Grave g = grave(UUID.randomUUID(), 1_234, 5_678).withArchivedAt(42);
        store.addGrave(g).join();
        CompoundTag saved = store.save(new CompoundTag());
        saved.getList("Graves", 10).add(new CompoundTag()); // a broken entry
        WorldGraveStore loaded = WorldGraveStore.load(saved);
        Grave back = loaded.grave(g.id()).join().orElseThrow();
        h.assertTrue(back.owner().equals(g.owner()) && back.ownerName().equals(g.ownerName()) && back.serverId().equals(g.serverId())
                && back.dimension().equals(g.dimension()) && back.x() == g.x() && back.y() == g.y() && back.z() == g.z() && back.placed() == g.placed()
                && back.diedAt() == g.diedAt() && back.touchedAt() == g.touchedAt() && back.archivedAt() == 42 && back.xp() == g.xp()
                && back.stacks() == g.stacks() && back.revision() == g.revision() && Arrays.equals(back.data(), g.data()), "every field back: " + back);
        h.assertTrue(loaded.size() == 1, "one readable grave");
        h.assertTrue(loaded.save(new CompoundTag()).getList("Graves", 10).size() == 2, "the unreadable entry is written back, never dropped");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void grave_contents_keep_each_stack_its_tag_and_its_slot(GameTestHelper h) {
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.enchant(Enchantments.SHARPNESS, 5);
        sword.setDamageValue(17);
        GraveContents c = new GraveContents(List.of(new GraveContents.Entry("inv:3", sword), new GraveContents.Entry("", new ItemStack(Items.ARROW, 40))), new CompoundTag());
        GraveContents back = GraveContents.decode(c.encode());
        h.assertTrue(back.stacks() == 2, "two stacks");
        h.assertTrue(back.entries().get(0).slot().equals("inv:3") && ItemStack.matches(back.entries().get(0).stack(), sword), "the sword with its enchantment and damage, in slot inv:3");
        h.assertTrue(back.entries().get(1).slot().isEmpty() && back.entries().get(1).stack().getCount() == 40, "the arrows, from no inventory slot");
        h.assertTrue(GraveContents.invIndex("inv:40") == 40 && GraveContents.invIndex("inv:41") == -1 && GraveContents.invIndex("curios:ring") == -1, "slot names");
        h.succeed();
    }

    // ------------------------------------------------------------------ the death

    @GameTest(template = EMPTY)
    public static void a_death_puts_the_drops_in_a_grave_with_the_slots_they_came_from(GameTestHelper h) {
        GraveService graves = localService(h);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        BlockPos at = standOn(h, p, 3, 1, 3);
        ItemStack sword = new ItemStack(Items.IRON_SWORD), bread = new ItemStack(Items.BREAD, 12), helmet = new ItemStack(Items.IRON_HELMET), shield = new ItemStack(Items.SHIELD);
        p.getInventory().setItem(0, sword.copy());
        p.getInventory().setItem(5, bread.copy());
        p.getInventory().setItem(39, helmet.copy());
        p.getInventory().setItem(40, shield.copy());
        graves.recordLayout(p, fall(h));
        p.getInventory().clearContent(); // vanilla has dropped everything by the drops event
        List<ItemEntity> drops = drops(h, p, bread, sword, shield, helmet, new ItemStack(Items.ARROW, 7));

        Grave g = graves.onDrops(p, fall(h), drops, 1_000);
        h.assertTrue(g != null, "a grave");
        h.assertTrue(drops.isEmpty(), "nothing is left to drop: " + drops.size());
        h.assertTrue(g.stacks() == 5 && g.placed() && g.x() == at.getX() && g.y() == at.getY() && g.z() == at.getZ(), "five stacks, standing where the player died: " + g);
        h.assertTrue(g.owner().equals(p.getUUID()) && g.diedAt() == 1_000 && g.touchedAt() == 1_000 && g.archivedAt() == 0, "owner and times");
        h.assertTrue(graves.local().grave(g.id()).join().isPresent(), "kept in the world's data (no network)");
        List<String> slots = contents(g).entries().stream().map(GraveContents.Entry::slot).toList();
        h.assertTrue(slots.equals(List.of("inv:5", "inv:0", "inv:40", "inv:39", "")), "each stack with the slot it came from, the arrows from none: " + slots);
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_second_death_makes_a_second_grave_and_never_touches_the_first(GameTestHelper h) {
        GraveService graves = localService(h);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        standOn(h, p, 2, 1, 2);
        graves.recordLayout(p, fall(h));
        Grave first = graves.onDrops(p, fall(h), drops(h, p, new ItemStack(Items.DIAMOND, 30)), 1_000);
        standOn(h, p, 5, 1, 5);
        graves.recordLayout(p, fall(h));
        Grave second = graves.onDrops(p, fall(h), drops(h, p, new ItemStack(Items.BREAD, 4)), 2_000);
        h.assertTrue(first != null && second != null && !first.id().equals(second.id()), "two graves");
        List<Grave> mine = graves.graves(p.getUUID()).join();
        h.assertTrue(mine.size() == 2 && mine.get(0).id().equals(second.id()), "both listed, newest first");
        h.assertTrue(contents(mine.get(1)).entries().get(0).stack().getCount() == 30, "the first still holds its 30 diamonds");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_pvp_death_makes_no_grave_and_leaves_the_drops_and_the_orbs(GameTestHelper h) {
        GraveService graves = localService(h);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        ServerPlayer killer = TestPlayers.mockServerPlayer(h);
        standOn(h, p, 3, 1, 3);
        DamageSource pvp = h.getLevel().damageSources().playerAttack(killer);
        graves.recordLayout(p, pvp);
        h.assertTrue(!graves.holdXp(p, 35), "the orbs are not held back: they drop for the killer");
        List<ItemEntity> drops = drops(h, p, new ItemStack(Items.DIAMOND, 10), new ItemStack(Items.GOLD_INGOT, 5));
        h.assertTrue(graves.onDrops(p, pvp, drops, 1_000) == null, "no grave");
        h.assertTrue(drops.size() == 2 && graves.local().size() == 0, "the drops are untouched and nothing is stored");
        h.assertTrue(!GraveService.killedByPlayer(p, h.getLevel().damageSources().playerAttack(p)), "hurting yourself is not PvP");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void the_death_xp_goes_into_the_grave_or_back_as_orbs_when_there_is_none(GameTestHelper h) {
        GraveService graves = localService(h);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        standOn(h, p, 2, 1, 2);
        graves.recordLayout(p, fall(h));
        h.assertTrue(graves.holdXp(p, 35), "the orbs are held back for the grave");
        Grave g = graves.onDrops(p, fall(h), drops(h, p, new ItemStack(Items.COAL, 3)), 1_000);
        h.assertTrue(g != null && g.xp() == 35, "the grave holds the 35 points: " + g);

        BlockPos at = standOn(h, p, 5, 1, 5);
        graves.recordLayout(p, fall(h));
        h.assertTrue(graves.holdXp(p, 21), "held at a death with nothing to drop");
        h.assertTrue(graves.onDrops(p, fall(h), new ArrayList<>(), 2_000) == null, "no items, no grave");
        List<ExperienceOrb> orbs = h.getLevel().getEntitiesOfClass(ExperienceOrb.class, new AABB(at).inflate(1.5));
        int points = orbs.stream().mapToInt(GraveTests::points).sum(); // vanilla merges equal orbs into one that counts twice
        orbs.forEach(o -> o.discard());
        h.assertTrue(points == 21, "the 21 points came back as orbs where the player died: " + points);
        h.assertTrue(!graves.holdXp(p, 10), "and nothing is held outside a death under way");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void totems_and_items_this_mod_never_saves_stay_in_the_drops(GameTestHelper h) {
        GraveService graves = localService(h);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        standOn(h, p, 3, 1, 3);
        graves.recordLayout(p, fall(h));
        List<ItemEntity> drops = drops(h, p, TotemRuleTests.totem(h), new ItemStack(Items.EMERALD, 9));
        Grave g = graves.onDrops(p, fall(h), drops, 1_000);
        h.assertTrue(g != null && g.stacks() == 1 && contents(g).entries().get(0).stack().is(Items.EMERALD), "only the emeralds are in the grave");
        h.assertTrue(drops.size() == 1 && com.mrgregles.bsp_inventorysync.compat.BspCore.isLiveTotem(drops.get(0).getItem()), "the live totem is never put in a grave");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void nothing_is_made_while_the_players_data_is_still_arriving(GameTestHelper h) {
        GraveService graves = localService(h);
        SyncService sync = new SyncService(h.getLevel().getServer(), new MemoryStore(), HUB);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        standOn(h, p, 3, 1, 3);
        sync.onLogin(p, System.currentTimeMillis());
        try {
            h.assertTrue(SyncService.isSyncing(p.getUUID()), "arriving");
            graves.recordLayout(p, fall(h));
            h.assertTrue(!graves.holdXp(p, 20), "no orbs held");
            List<ItemEntity> drops = drops(h, p, new ItemStack(Items.DIAMOND, 10));
            h.assertTrue(graves.onDrops(p, fall(h), drops, 1_000) == null && drops.size() == 1, "no grave from this server's stale copy");
        } finally {
            sync.onLogout(p);
        }
        h.succeed();
    }

    // ------------------------------------------------------------------ the spot

    @GameTest(template = EMPTY)
    public static void a_death_in_water_or_in_a_wall_puts_the_grave_on_the_nearest_dry_spot(GameTestHelper h) {
        GraveService graves = localService(h);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        for (int x = 1; x <= 5; x++) {
            h.setBlock(new BlockPos(x, 0, 3), Blocks.STONE);
        }
        h.setBlock(new BlockPos(3, 1, 3), Blocks.WATER);
        BlockPos death = standOn(h, p, 3, 1, 3);
        h.setBlock(new BlockPos(3, 1, 3), Blocks.WATER); // standOn put the floor; the death spot itself is water
        graves.recordLayout(p, fall(h));
        Grave wet = graves.onDrops(p, fall(h), drops(h, p, new ItemStack(Items.DIRT)), 1_000);
        BlockPos at = new BlockPos(wet.x(), wet.y(), wet.z());
        h.assertTrue(wet.placed() && at.distManhattan(death) == 1 && h.getLevel().getBlockState(at).getFluidState().isEmpty(), "beside the water, on dry ground: " + at + " from " + death);

        h.setBlock(new BlockPos(3, 1, 3), Blocks.STONE);
        p.moveTo(death.getX() + 0.5, death.getY(), death.getZ() + 0.5); // inside the stone now
        graves.recordLayout(p, fall(h));
        Grave walled = graves.onDrops(p, fall(h), drops(h, p, new ItemStack(Items.DIRT)), 2_000);
        BlockPos at2 = new BlockPos(walled.x(), walled.y(), walled.z());
        h.assertTrue(walled.placed() && !at2.equals(death) && h.getLevel().getBlockState(at2).is(com.mrgregles.bsp_inventorysync.grave.GraveRegistry.GRAVE.get()),
                "out of the wall, in the nearest free spot, where the grave now stands: " + at2);
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void the_spot_where_a_dropped_totem_lies_is_left_for_the_totem(GameTestHelper h) {
        GraveService graves = localService(h);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        for (int x = 1; x <= 5; x++) {
            h.setBlock(new BlockPos(x, 0, 3), Blocks.STONE);
        }
        BlockPos death = standOn(h, p, 3, 1, 3);
        // BSP-Core's own dropped-totem entity, spawned as BSP-Core does at a death (a plain item entity only becomes one a tick later)
        net.minecraft.world.entity.Entity totem;
        java.lang.reflect.Method destroy;
        try {
            Class<?> type = Class.forName("com.mrgregles.bsp_core.totem.ShatterTotemItemEntity");
            totem = (net.minecraft.world.entity.Entity) type.getConstructor(net.minecraft.world.level.Level.class, double.class, double.class, double.class, ItemStack.class)
                    .newInstance(h.getLevel(), death.getX() + 0.5, death.getY() + 0.1, death.getZ() + 0.5, TotemRuleTests.totem(h));
            // removed on purpose with BSP-Core's own destroy() where it has one (a plain discard then places the totem);
            // older BSP-Core (1.6.2, compiled against here) has none, and a discard places nothing
            java.lang.reflect.Method d;
            try {
                d = type.getMethod("destroy");
            } catch (NoSuchMethodException none) {
                d = null;
            }
            destroy = d;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("BSP-Core's ShatterTotemItemEntity", e);
        }
        totem.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        totem.setNoGravity(true);
        h.getLevel().addFreshEntity(totem);
        try {
            h.assertTrue(com.mrgregles.bsp_inventorysync.compat.BspCore.isDroppedTotem(totem), "BSP-Core's entity is recognised as a dropped totem");
            graves.recordLayout(p, fall(h));
            Grave g = graves.onDrops(p, fall(h), drops(h, p, new ItemStack(Items.DIRT)), 1_000);
            BlockPos at = new BlockPos(g.x(), g.y(), g.z());
            h.assertTrue(g.placed() && !at.equals(death) && at.distManhattan(death) == 1, "the grave stands beside the totem's spot, not on it: " + at + ", death " + death);
        } finally {
            if (destroy == null) {
                totem.discard();
            } else {
                try {
                    destroy.invoke(totem);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        h.succeed();
    }

    /** Fills the 3x3x3 cube around the relative centre with stone: no spot within a radius of 1. */
    private static BlockPos sealIn(GameTestHelper h, ServerPlayer p, int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    h.setBlock(new BlockPos(x + dx, y + dy, z + dz), Blocks.STONE);
                }
            }
        }
        BlockPos abs = h.absolutePos(new BlockPos(x, y, z));
        p.moveTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5);
        return abs;
    }

    @GameTest(template = EMPTY)
    public static void with_no_spot_near_the_death_the_grave_goes_where_the_player_last_stood_on_solid_ground(GameTestHelper h) {
        GraveService graves = localService(h);
        graves.setRadius(1);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        BlockPos ground = standOn(h, p, 6, 1, 6);
        p.setOnGround(true);
        graves.noteGround(p);
        h.assertTrue(ground.equals(graves.lastGround(p.getUUID()).pos()), "the last solid ground is noted");
        sealIn(h, p, 2, 1, 2);
        graves.recordLayout(p, fall(h));
        Grave g = graves.onDrops(p, fall(h), drops(h, p, new ItemStack(Items.DIAMOND, 2)), 1_000);
        h.assertTrue(g.placed() && new BlockPos(g.x(), g.y(), g.z()).equals(ground), "the grave stands where the player last stood: " + g);
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void with_no_spot_at_all_the_grave_is_kept_without_one(GameTestHelper h) {
        GraveService graves = localService(h);
        graves.setRadius(1);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        BlockPos death = sealIn(h, p, 3, 1, 3);
        graves.recordLayout(p, fall(h));
        List<ItemEntity> drops = drops(h, p, new ItemStack(Items.DIAMOND, 2), new ItemStack(Items.EMERALD, 3));
        Grave g = graves.onDrops(p, fall(h), drops, 1_000);
        h.assertTrue(g != null && !g.placed() && g.stacks() == 2 && drops.isEmpty(), "a grave with no spot still holds everything: " + g);
        h.assertTrue(new BlockPos(g.x(), g.y(), g.z()).equals(death), "it remembers where the death was");
        h.succeed();
    }

    // ------------------------------------------------------------------ the database

    @GameTest(template = EMPTY)
    public static void a_grave_goes_to_the_database_with_the_death_snapshot(GameTestHelper h) {
        MemoryStore memory = new MemoryStore();
        SyncService sync = new SyncService(h.getLevel().getServer(), memory, HUB);
        GraveService graves = service(h, () -> sync);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        graves.setLivePlayerLookup(id -> id.equals(p.getUUID()) ? p : null);
        standOn(h, p, 3, 1, 3);
        p.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 5));
        graves.recordLayout(p, fall(h));
        p.getInventory().clearContent();
        p.setHealth(0); // lying dead
        Grave g = graves.onDrops(p, fall(h), drops(h, p, new ItemStack(Items.DIAMOND, 5)), 1_000);
        h.assertTrue(g != null && g.serverId().equals(HUB), "a grave on server " + HUB);
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(memory.graveCount() == 1 && graves.local().size() == 0, "in the database, out of the world's data"))
                .thenWaitUntil(() -> {
                    Optional<Snapshot> s = memory.latest(p.getUUID()).join();
                    h.assertTrue(s.isPresent() && s.get().cause() == SaveCause.DEATH, "the death saved as a snapshot: " + s);
                })
                .thenExecute(() -> {
                    h.assertTrue(graves.graves(p.getUUID()).join().size() == 1, "listed once");
                    h.assertTrue(graves.unsent() == 0, "nothing waits to be sent");
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY)
    public static void a_grave_whose_snapshot_did_not_land_rides_on_the_next_one(GameTestHelper h) {
        MemoryStore memory = new MemoryStore();
        java.util.concurrent.atomic.AtomicBoolean down = new java.util.concurrent.atomic.AtomicBoolean(true);
        SyncService sync = new SyncService(h.getLevel().getServer(), GiveTests.hooked(memory, (m, args, real) -> m.equals("saveWithGraves") && down.get()
                ? CompletableFuture.failedFuture(new java.sql.SQLException("test: the database is down")) : null), HUB);
        GraveService graves = service(h, () -> sync);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        standOn(h, p, 3, 1, 3);
        graves.recordLayout(p, fall(h));
        Grave g = graves.onDrops(p, fall(h), drops(h, p, new ItemStack(Items.GOLD_INGOT, 8)), 1_000);
        h.assertTrue(g != null, "a grave");
        h.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    h.assertTrue(memory.graveCount() == 0 && memory.latest(p.getUUID()).join().isEmpty() && graves.unsent() == 1,
                            "neither the grave nor the death snapshot is in the database: both or neither");
                    h.assertTrue(graves.graves(p.getUUID()).join().size() == 1, "still listed");
                    down.set(false);
                    sync.save(p, SaveCause.AUTOSAVE); // the next save of the player carries it
                })
                .thenWaitUntil(() -> h.assertTrue(memory.graveCount() == 1 && graves.unsent() == 0 && memory.latest(p.getUUID()).join().isPresent(), "landed with that snapshot"))
                .thenExecute(() -> {
                    sync.save(p, SaveCause.AUTOSAVE);
                    h.assertTrue(memory.graveCount() == 1, "never twice");
                })
                .thenSucceed();
    }

    // ------------------------------------------------------------------ Forge's own events

    @GameTest(template = EMPTY)
    public static void a_real_death_through_forge_events_makes_the_grave_and_keeps_the_orbs(GameTestHelper h) {
        GraveService graves = localService(h);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        standOn(h, p, 3, 1, 3);
        p.getInventory().setItem(2, new ItemStack(Items.GOLDEN_APPLE, 3));
        p.getInventory().setItem(38, new ItemStack(Items.IRON_CHESTPLATE));
        p.experienceLevel = 5; // vanilla drops 35 points
        GameRules.BooleanValue keep = h.getLevel().getGameRules().getRule(GameRules.RULE_KEEPINVENTORY);
        boolean keepBefore = keep.get();
        keep.set(false, h.getLevel().getServer());
        GraveService before = GraveService.swapActive(graves);
        try {
            p.die(fall(h));
        } finally {
            GraveService.swapActive(before);
            keep.set(keepBefore, h.getLevel().getServer());
        }
        List<Grave> mine = graves.graves(p.getUUID()).join();
        h.assertTrue(mine.size() == 1, "one grave from the real death: " + mine.size());
        Grave g = mine.get(0);
        List<String> slots = contents(g).entries().stream().map(GraveContents.Entry::slot).sorted().toList();
        h.assertTrue(slots.equals(List.of("inv:2", "inv:38")), "the apples and the chestplate with their slots: " + slots);
        h.assertTrue(g.xp() == 35, "the 35 points are in the grave: " + g.xp());
        h.assertTrue(g.placed() && h.getLevel().getBlockEntity(new BlockPos(g.x(), g.y(), g.z())) instanceof com.mrgregles.bsp_inventorysync.grave.GraveBlockEntity be
                && g.id().equals(be.graveId()), "the grave block stands where the grave says, naming it");
        AABB around = new AABB(p.blockPosition()).inflate(3);
        h.assertTrue(h.getLevel().getEntitiesOfClass(ItemEntity.class, around).isEmpty(), "no item fell on the ground");
        h.assertTrue(h.getLevel().getEntitiesOfClass(ExperienceOrb.class, around).isEmpty(), "and no orb");
        h.succeed();
    }
}
