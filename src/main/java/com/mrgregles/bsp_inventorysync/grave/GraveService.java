package com.mrgregles.bsp_inventorysync.grave;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.SyncConfig;
import com.mrgregles.bsp_inventorysync.compat.BspCore;
import com.mrgregles.bsp_inventorysync.compat.DankStorage;
import com.mrgregles.bsp_inventorysync.compat.SophisticatedBackpacks;
import com.mrgregles.bsp_inventorysync.data.ItemRules;
import com.mrgregles.bsp_inventorysync.storage.Grave;
import com.mrgregles.bsp_inventorysync.storage.GraveStore;
import com.mrgregles.bsp_inventorysync.storage.SaveCause;
import com.mrgregles.bsp_inventorysync.sync.Sync;
import com.mrgregles.bsp_inventorysync.sync.SyncService;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Graves (D25), taking over from Simply Graves: a death puts what would drop into a grave of its own.
 *
 * <p>At the death ({@code LivingDeathEvent}, lowest priority, after BSP-Core has taken the Shatter Totems) the
 * layout of the inventory is noted. At the drops ({@code LivingDropsEvent}, lowest priority, after every other mod
 * has decided what drops) each dropped stack goes into the grave with the slot it came from, except a live totem,
 * anything this mod never saves, and anything tagged {@code bsp_inventorysync:no_grave}. The experience points go
 * in too: vanilla drops the orbs just before the drops event, so the orbs are held back at
 * {@code LivingExperienceDropEvent} and given back as orbs where the player died if no grave is made. No grave is made:
 * <ul>
 * <li>for a PvP death (D25 4c, 5a): killed by another player, directly or with a projectile, as BSP-Core's totem
 * rule counts it; the drops fall as in vanilla, for the killer;</li>
 * <li>while the player's data is still arriving: what this server holds of them then is a stale copy;</li>
 * <li>when nothing drops (keepInventory, an empty inventory).</li>
 * </ul>
 *
 * <p>The spot (6b): the death spot, grounded as BSP-Core grounds a dropped totem, or the nearest good spot within
 * {@code graves.searchRadius} ({@link GraveSpot}), never where BSP-Core's dropped totem lies; failing that, near the
 * last place the player stood on solid ground; failing that, the grave has no spot and is recovered by command.
 *
 * <p>Storage: a new grave goes into this world's own data at once ({@link WorldGraveStore}), then, on a synced
 * server, to the network database; once the database has it, the world's copy is removed and the death is saved
 * as a snapshot right away (the inventory is empty by then), so a crash cannot bring the items back as well. A
 * database that does not answer only delays that: whatever is in the world's data is sent again every 30 s. With
 * no network, the world's data is where graves live.
 */
public final class GraveService implements com.mrgregles.bsp_inventorysync.sync.GraveCarrier {
    public static final TagKey<Item> NO_GRAVE = TagKey.create(Registries.ITEM, new ResourceLocation(BSPInventorySync.MODID, "no_grave"));
    public static final String SIMPLY_GRAVES = "simplygraves";
    static final String PACKS = "Packs", DANKS = "Danks";
    private static final long RETRY_MS = 30_000;
    private static final int GROUND_EVERY_TICKS = 10;
    private static final int GROUND_SEARCH = 2;

    @Nullable private static volatile GraveService active;

    /** Where a player last stood on solid ground. */
    public record Ground(ResourceKey<Level> dimension, BlockPos pos) {}

    private final MinecraftServer server;
    private final WorldGraveStore local;
    private final Supplier<SyncService> sync;
    private volatile int radius;
    private volatile long privateMs = 60 * 60_000L;
    private volatile long recoverAfterMs = 7 * DAY, archiveAfterMs = 30 * DAY, deleteAfterMs = 30 * DAY;
    private long nextSweepAt, nextNoticeAt;
    static final long DAY = 86_400_000L;
    private static final long SWEEP_MS = 10 * 60_000L, NOTICE_MS = 60_000L;
    /** The extra blob holding lines for a player who was not on the server where something happened to their grave. */
    public static final String NOTICE = "grave_notice";
    /** A death under way: the inventory's layout at the death and whether it was PvP. */
    private record Death(List<GraveContents.Entry> layout, boolean pvp) {}

    private final Map<UUID, Death> deaths = new HashMap<>();
    private final Map<UUID, Ground> lastGround = new HashMap<>();
    /** Orbs held back from a death under way, until its drops decide whether there is a grave. */
    private final Map<UUID, Integer> heldXp = new HashMap<>();
    private final Set<UUID> sending = new HashSet<>();
    private long nextRetryAt;
    private Function<UUID, ServerPlayer> live;

    /** {@code sync} answers the running sync, or null: no network, the graves stay in the world's data. */
    public GraveService(MinecraftServer server, WorldGraveStore local, Supplier<SyncService> sync, int radius) {
        this.server = server;
        this.local = local;
        this.sync = sync;
        this.radius = radius;
        this.live = id -> server.getPlayerList().getPlayer(id);
        this.access = new GraveAccess(this, server);
    }

    private final GraveAccess access;

    /** Opening graves and taking from them. */
    public GraveAccess access() {
        return access;
    }

    public long privateMs() {
        return privateMs;
    }

    /** D25 7b and 22b: recover from anywhere after {@code recoverMs} unopened, put away after {@code archiveMs}, deleted {@code deleteMs} later. */
    public void setTimes(long recoverMs, long archiveMs, long deleteMs) {
        recoverAfterMs = recoverMs;
        archiveAfterMs = archiveMs;
        deleteAfterMs = deleteMs;
    }

    /** Whether its owner may recover it from anywhere: one that found no spot at once, any other after the days unopened; never one put away. */
    public boolean recoverable(Grave g, long now) {
        return !g.archived() && (!g.placed() || now - g.touchedAt() >= recoverAfterMs);
    }

    /** How long until it is {@link #recoverable}; 0 when it is. */
    public long recoverableIn(Grave g, long now) {
        return recoverable(g, now) ? 0 : Math.max(0, g.touchedAt() + recoverAfterMs - now);
    }

    /** Where graves are listed and swept: the network database, or this world's data without one. */
    public GraveStore mainStore() {
        GraveStore net = network();
        return net == null ? local : net;
    }

    /** Restarts the service with the config as it is now ({@code /invsync reload}); one line for the operator. */
    public static String restart(MinecraftServer server) {
        GraveService before = active;
        stop();
        start(server);
        GraveService now = active;
        if (before != null && now != null) {
            now.pending.putAll(before.pending); // graves on their way to the database are kept across the reload
        }
        GraveService s = active;
        return s == null ? "graves are off on this server (graves.enabled = false, or Simply Graves is installed)"
                : "graves on, kept in " + (Sync.active() != null ? "the network database" : "this world's own data");
    }

    /** For {@code /invsync status}. */
    public String status() {
        return "graves on, kept in " + (network() != null ? "the network database" : "this world's own data")
                + (unsent() > 0 ? "; " + unsent() + " waiting to reach the database" : "");
    }

    /** This server's id on the network now ("" without one): graves made here carry it. */
    public String serverIdNow() {
        return serverId();
    }

    /**
     * Where a grave is to be read and changed: the network database, or this world's data without one. Null while the
     * grave has not reached the database yet (on its way with the owner's next snapshot, or an old one in this world's
     * data): it is not changed until it is there.
     */
    @Nullable
    public GraveStore storeFor(UUID id) {
        GraveStore net = network();
        if (net == null) {
            return local;
        }
        if (pending.values().stream().anyMatch(list -> list.stream().anyMatch(g -> g.id().equals(id)))) {
            return null;
        }
        return local.grave(id).join().isPresent() ? null : net;
    }

    /** Whether the network database is connected (a grave block placed while it was is only read from it). */
    public boolean networked() {
        return network() != null;
    }

    @Nullable
    public static GraveService active() {
        return active;
    }

    /** At server start (after the sync's): on unless {@code graves.enabled} is false or Simply Graves is installed. */
    public static void start(MinecraftServer server) {
        if (!SyncConfig.getOr(SyncConfig.GRAVES_ENABLED, false)) {
            BSPInventorySync.LOGGER.info("BSP-InventorySync: graves are off (graves.enabled = false)");
            return;
        }
        if (ModList.get().isLoaded(SIMPLY_GRAVES)) {
            BSPInventorySync.LOGGER.warn("BSP-InventorySync: Simply Graves is installed, so this mod's graves stay off. Remove Simply Graves from the servers and the pack to switch over.");
            return;
        }
        WorldGraveStore world = WorldGraveStore.get(server);
        GraveService s = new GraveService(server, world, () -> {
            SyncService sync = Sync.active();
            if (sync != null) {
                sync.setGraveCarrier(active); // a sync started (or reloaded) after the graves still carries them
            }
            return sync;
        }, SyncConfig.getOr(SyncConfig.GRAVE_SEARCH_RADIUS, 8));
        s.setPrivateMs(SyncConfig.getOr(SyncConfig.GRAVE_PRIVATE_MINUTES, 60) * 60_000L);
        s.setTimes(SyncConfig.getOr(SyncConfig.GRAVE_RECOVER_DAYS, 7) * DAY, SyncConfig.getOr(SyncConfig.GRAVE_ARCHIVE_DAYS, 30) * DAY,
                SyncConfig.getOr(SyncConfig.GRAVE_DELETE_DAYS, 30) * DAY);
        active = s;
        BSPInventorySync.LOGGER.info("BSP-InventorySync: graves on, kept in {}{}", Sync.active() != null ? "the network database" : "this world's own data",
                Sync.active() != null && world.size() > 0 ? "; " + world.size() + " grave(s) in this world's data are sent to it" : "");
    }

    public static void stop() {
        active = null;
    }

    /** Tests: makes {@code s} the running service (the one the Forge events reach) and returns the one it replaced. */
    @Nullable
    public static GraveService swapActive(@Nullable GraveService s) {
        GraveService before = active;
        active = s;
        return before;
    }

    public WorldGraveStore local() {
        return local;
    }

    public void setRadius(int r) {
        radius = r;
    }

    /** How long a new grave is its owner's alone (D25 3a: an hour). */
    public void setPrivateMs(long ms) {
        privateMs = ms;
    }

    /** Tests: how to find the player's live entity (mock players are not in the player list). */
    public void setLivePlayerLookup(Function<UUID, ServerPlayer> lookup) {
        live = lookup;
    }

    @Nullable
    private GraveStore network() {
        SyncService s = sync.get();
        return s == null ? null : s.store();
    }

    private String serverId() {
        SyncService s = sync.get();
        return s == null ? "" : s.serverId();
    }

    // ------------------------------------------------------------------ the death

    /** The inventory as it is at the death, after BSP-Core took the totems: which slot each stack is in. */
    public void recordLayout(ServerPlayer player, DamageSource source) {
        Inventory inv = player.getInventory();
        List<GraveContents.Entry> layout = new ArrayList<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty()) {
                layout.add(new GraveContents.Entry(GraveContents.invSlot(i), s.copy()));
            }
        }
        deaths.put(player.getUUID(), new Death(layout, killedByPlayer(player, source)));
    }

    /**
     * The orbs of a death under way (vanilla drops them just before the drops event): held back for the grave
     * unless the death is PvP or the player's data is still arriving. True when they are held (the drop is then
     * cancelled); a death that ends with no grave gives them back as orbs.
     */
    public boolean holdXp(ServerPlayer player, int points) {
        Death d = deaths.get(player.getUUID());
        if (d == null || d.pvp() || points <= 0 || SyncService.isSyncing(player.getUUID())) {
            return false;
        }
        heldXp.merge(player.getUUID(), points, Integer::sum);
        return true;
    }

    /** Killed by another player, directly or with a projectile (BSP-Core's totem rule). */
    public static boolean killedByPlayer(ServerPlayer player, DamageSource source) {
        return source.getEntity() instanceof ServerPlayer killer && !killer.getUUID().equals(player.getUUID());
    }

    /**
     * The drops of a death: makes the grave and takes its stacks out of {@code drops}. Returns the grave, or null
     * when none is made (see the class comment); {@code drops} is then untouched.
     */
    @Nullable
    public Grave onDrops(ServerPlayer player, DamageSource source, Collection<ItemEntity> drops, long now) {
        UUID id = player.getUUID();
        String name = player.getGameProfile().getName();
        Death death = deaths.remove(id);
        List<GraveContents.Entry> layout = death == null ? null : death.layout();
        int xp = heldXp.getOrDefault(id, 0);
        heldXp.remove(id);
        Grave grave = makeGrave(player, source, drops, layout, xp, now);
        if (grave == null && xp > 0) {
            ExperienceOrb.award(player.serverLevel(), player.position(), xp); // no grave after all: the orbs drop as vanilla would have
        }
        return grave;
    }

    @Nullable
    private Grave makeGrave(ServerPlayer player, DamageSource source, Collection<ItemEntity> drops, @Nullable List<GraveContents.Entry> layout, int xp, long now) {
        UUID id = player.getUUID();
        String name = player.getGameProfile().getName();
        if (killedByPlayer(player, source)) {
            BSPInventorySync.LOGGER.info("BSP-InventorySync: {} was killed by {}: no grave, the drops fall (PvP)", name, source.getEntity().getName().getString());
            return null;
        }
        if (SyncService.isSyncing(id)) {
            BSPInventorySync.LOGGER.warn("BSP-InventorySync: {} died while their data was still arriving: no grave (this server's copy of them is stale)", name);
            return null;
        }
        List<ItemEntity> taken = new ArrayList<>();
        List<GraveContents.Entry> entries = new ArrayList<>();
        boolean[] used = new boolean[layout == null ? 0 : layout.size()];
        for (ItemEntity drop : drops) {
            ItemStack stack = drop.getItem();
            if (stack.isEmpty() || ItemRules.excluded(stack) || stack.is(NO_GRAVE)) {
                continue;
            }
            taken.add(drop);
            entries.add(new GraveContents.Entry(slotOf(stack, layout, used), stack.copy()));
        }
        if (entries.isEmpty()) {
            return null;
        }

        ServerLevel level = player.serverLevel();
        BlockPos deathPos = player.blockPosition();
        Set<BlockPos> avoid = totemSpots(level, deathPos);
        ServerLevel graveLevel = level;
        Optional<BlockPos> spot = GraveSpot.near(level, GraveSpot.grounded(level, deathPos), radius, avoid);
        if (spot.isEmpty()) {
            Ground ground = lastGround.get(id);
            ServerLevel groundLevel = ground == null ? null : server.getLevel(ground.dimension());
            if (groundLevel != null) {
                spot = GraveSpot.near(groundLevel, ground.pos(), GROUND_SEARCH, groundLevel == level ? avoid : Set.of());
                if (spot.isPresent()) {
                    graveLevel = groundLevel;
                }
            }
        }
        BlockPos where = spot.orElse(deathPos);
        GraveContents contents = new GraveContents(entries, extras(entries.stream().map(GraveContents.Entry::stack).toList(), level, name));
        UUID graveId = UUID.randomUUID();
        boolean placed = spot.isPresent() && placeBlock(graveLevel, where, player.getDirection(), graveId, id, name, now, now + privateMs, sync.get() != null, player.getGameProfile());
        Grave grave = new Grave(graveId, id, name, serverId(), graveLevel.dimension().location().toString(),
                where.getX(), where.getY(), where.getZ(), placed, now, now, 0, xp, entries.size(), 0, contents.encode());

        SyncService synced = sync.get();
        if (synced != null) {
            // rides on the player's next snapshot, saved now (the inventory is empty by then): both land, or neither
            pending.computeIfAbsent(id, k -> new ArrayList<>()).add(grave);
            synced.setGraveCarrier(this);
            synced.save(player, SaveCause.DEATH);
        } else {
            local.addGrave(grave); // this world's data, saved with the world (and with the player's own data)
        }
        drops.removeAll(taken);
        tell(player, grave);
        BSPInventorySync.LOGGER.info("BSP-InventorySync: grave {} for {}: {} stack(s), {} xp, {}", grave.shortId(), name, grave.stacks(), xp,
                grave.placed() ? "at " + where.getX() + " " + where.getY() + " " + where.getZ() + " in " + grave.dimension()
                        : "no spot near " + deathPos.getX() + " " + deathPos.getY() + " " + deathPos.getZ() + " in " + level.dimension().location() + " (recovered by command)");
        return grave;
    }

    /**
     * Puts the grave block up: facing the way the player faced, of the stone that suits the place, naming its grave.
     * False when the block could not be set (the grave is then kept without a spot).
     */
    public static boolean placeBlock(ServerLevel level, BlockPos pos, net.minecraft.core.Direction facing, UUID graveId, UUID owner, String ownerName,
                                     long diedAt, long publicAt) {
        return placeBlock(level, pos, facing, graveId, owner, ownerName, diedAt, publicAt, false);
    }

    /** As above; {@code networked}: its grave goes to the network database. No profile: the head shows the default skin. */
    public static boolean placeBlock(ServerLevel level, BlockPos pos, net.minecraft.core.Direction facing, UUID graveId, UUID owner, String ownerName,
                                     long diedAt, long publicAt, boolean networked) {
        return placeBlock(level, pos, facing, graveId, owner, ownerName, diedAt, publicAt, networked, null);
    }

    /**
     * As above; {@code profile}: the owner's game profile with its skin (the {@code textures} property), for the head
     * in front of the stone (D26 2a, 5a); null draws the default skin for the owner's id. The head leans the way the
     * grave's id says ({@link GraveLean#of}).
     */
    public static boolean placeBlock(ServerLevel level, BlockPos pos, net.minecraft.core.Direction facing, UUID graveId, UUID owner, String ownerName,
                                     long diedAt, long publicAt, boolean networked, @Nullable com.mojang.authlib.GameProfile profile) {
        net.minecraft.world.level.block.state.BlockState state = GraveRegistry.GRAVE.get().stateFor(facing, GraveKind.forPlace(level, pos), GraveLean.of(graveId));
        if (!level.setBlock(pos, state, net.minecraft.world.level.block.Block.UPDATE_ALL)) {
            return false;
        }
        if (level.getBlockEntity(pos) instanceof GraveBlockEntity be) {
            be.setGrave(graveId, owner, ownerName, diedAt, publicAt, networked, profile);
            return true;
        }
        level.removeBlock(pos, false);
        return false;
    }

    /** The slot a dropped stack came from: the first unused slot of the layout holding exactly that stack, else "". */
    private static String slotOf(ItemStack stack, @Nullable List<GraveContents.Entry> layout, boolean[] used) {
        if (layout == null) {
            return "";
        }
        for (int i = 0; i < layout.size(); i++) {
            if (!used[i] && ItemStack.matches(layout.get(i).stack(), stack)) {
                used[i] = true;
                return layout.get(i).slot();
            }
        }
        return "";
    }

    /** Where BSP-Core's dropped Shatter Totems lie near the death (each lies where it will place itself): never a grave's spot. */
    static Set<BlockPos> totemSpots(ServerLevel level, BlockPos deathPos) {
        Set<BlockPos> out = new HashSet<>();
        for (net.minecraft.world.entity.Entity e : level.getEntities((net.minecraft.world.entity.Entity) null, new AABB(deathPos).inflate(3), BspCore::isDroppedTotem)) {
            out.add(e.blockPosition());
            out.add(GraveSpot.grounded(level, e.blockPosition()));
        }
        return out;
    }

    /** Backpack and dank contents of the grave's stacks, read on this server; an adapter that fails is logged and left out. */
    private static CompoundTag extras(List<ItemStack> stacks, ServerLevel level, String name) {
        CompoundTag extras = new CompoundTag();
        try {
            ListTag packs = SophisticatedBackpacks.Module.packsIn(stacks);
            if (!packs.isEmpty()) {
                extras.put(PACKS, packs);
            }
        } catch (Throwable t) {
            BSPInventorySync.LOGGER.error("BSP-InventorySync: backpack contents for {}'s grave could not be read; the backpacks are kept without them", name, t);
        }
        try {
            ListTag danks = DankStorage.Module.danksIn(stacks, level);
            if (!danks.isEmpty()) {
                extras.put(DANKS, danks);
            }
        } catch (Throwable t) {
            BSPInventorySync.LOGGER.error("BSP-InventorySync: dank contents for {}'s grave could not be read; the danks are kept without them", name, t);
        }
        return extras;
    }

    private void tell(ServerPlayer player, Grave g) {
        Component line;
        if (g.placed()) {
            String server = g.serverId().isEmpty() ? "" : " on " + g.serverId();
            line = Component.literal("Your grave is at ").append(Component.literal(g.x() + " " + g.y() + " " + g.z()).withStyle(ChatFormatting.GREEN))
                    .append(" in ").append(Component.literal(dimensionName(g.dimension())).withStyle(ChatFormatting.GREEN))
                    .append(Component.literal(server)).append(".");
        } else {
            line = Component.literal("Your grave found nowhere to stand. Everything is kept safe: recover it with /grave.").withStyle(ChatFormatting.GOLD);
        }
        player.sendSystemMessage(line);
    }

    /** "the Overworld", "the Nether", "the End", else the dimension's id. */
    public static String dimensionName(String id) {
        return switch (id) {
            case "minecraft:overworld" -> "the Overworld";
            case "minecraft:the_nether" -> "the Nether";
            case "minecraft:the_end" -> "the End";
            default -> id;
        };
    }

    /**
     * Another mod cancelled the drops (a keep-inventory rule, another grave mod): no grave, and the orbs held back for
     * one drop where the player died after all.
     */
    public void dropsCancelled(ServerPlayer player) {
        deaths.remove(player.getUUID());
        Integer xp = heldXp.remove(player.getUUID());
        if (xp != null && xp > 0) {
            ExperienceOrb.award(player.serverLevel(), player.position(), xp);
        }
    }

    /**
     * Keeps a grave that rides on no snapshot (stacks a player took but could not receive): in the network database
     * at once, tried again a few times, and meanwhile (or if the database stays away) in this world's data, sent from
     * there like any grave made without a network. Without a network, in this world's data.
     */
    public void keep(Grave g) {
        GraveStore net = network();
        if (net == null) {
            local.addGrave(g);
            return;
        }
        local.addGrave(g); // the fall-back copy; removed once the database has it (an id it already has is left alone)
        local.saveNow(server); // on disk at once: these stacks are in no snapshot and no other grave any more
        send(g, null);
    }

    /** A respawn: whatever was left of a death under way is gone (a death whose drops never came). */
    public void respawned(UUID player) {
        deaths.remove(player);
        heldXp.remove(player);
    }

    public void loggedOut(UUID player) {
        access.loggedOut(player);
        deaths.remove(player);
        heldXp.remove(player);
        lastGround.remove(player);
    }

    // ------------------------------------------------------------------ the database

    /**
     * Sends a grave from this world's data to the network database: one made while this server had no network (it was
     * saved with the world and the player's own data then), now that it has one. New graves on a synced server never
     * go this way: they ride on the owner's snapshot. Server thread.
     */
    private void send(Grave grave, @Nullable ServerPlayer died) {
        GraveStore net = network();
        if (net == null || !sending.add(grave.id())) {
            return;
        }
        CompletableFuture<Boolean> f;
        try {
            f = net.addGrave(grave);
        } catch (RuntimeException e) {
            f = CompletableFuture.failedFuture(e);
        }
        f.whenComplete((added, error) -> server.execute(() -> {
            sending.remove(grave.id());
            if (error != null) {
                BSPInventorySync.LOGGER.warn("BSP-InventorySync: grave {} for {} is not in the database yet ({}); it is kept in this world's data and sent again",
                        grave.shortId(), grave.ownerName(), error.toString());
                return;
            }
            Optional<Grave> here = local.grave(grave.id()).join();
            if (here.isPresent() && here.get().revision() == grave.revision()) {
                local.deleteGrave(grave.id(), grave.revision());
            }
        }));
    }

    // ------------------------------------------------------------------ graves riding on snapshots

    /** New graves not yet in the database, per owner: they go with the owner's next snapshot. */
    private final Map<UUID, List<Grave>> pending = new LinkedHashMap<>();

    @Override
    public List<Grave> pendingFor(UUID player) {
        return List.copyOf(pending.getOrDefault(player, List.of()));
    }

    @Override
    public void landed(UUID player, List<Grave> graves) {
        List<Grave> list = pending.get(player);
        if (list != null) {
            list.removeIf(g -> graves.stream().anyMatch(l -> l.id().equals(g.id())));
            if (list.isEmpty()) {
                pending.remove(player);
            }
        }
        for (Grave g : graves) {
            BSPInventorySync.LOGGER.info("BSP-InventorySync: grave {} ({}'s) is in the database with their snapshot", g.shortId(), g.ownerName());
        }
    }

    @Override
    public void logoutSaveFailed(UUID player) {
        List<Grave> dropped = pending.remove(player);
        if (dropped == null) {
            return;
        }
        for (Grave g : dropped) {
            access.removeBlockOf(g);
            BSPInventorySync.LOGGER.warn("BSP-InventorySync: grave {} ({}'s) dropped: their logout save failed, so the database still has them as before "
                    + "the death and they get those items back at their next login. It held {}", g.shortId(), g.ownerName(), describe(g));
        }
    }

    /** Graves on their way to the database (with a snapshot, or old ones in this world's data); none without a network. */
    public int unsent() {
        return network() == null ? 0 : local.size() + pending.values().stream().mapToInt(List::size).sum();
    }

    /** Every grave of the owner: the database's, any on their way to it, and any in this world's data; newest death first. */
    public CompletableFuture<List<Grave>> graves(UUID owner) {
        List<Grave> here = new ArrayList<>(local.graves(owner).join());
        here.addAll(pending.getOrDefault(owner, List.of()));
        here.sort(Comparator.comparingLong(Grave::diedAt).reversed());
        GraveStore net = network();
        if (net == null) {
            return CompletableFuture.completedFuture(here);
        }
        return net.graves(owner).thenApply(remote -> {
            Map<UUID, Grave> all = new LinkedHashMap<>();
            remote.forEach(g -> all.put(g.id(), g));
            here.forEach(g -> all.putIfAbsent(g.id(), g));
            List<Grave> out = new ArrayList<>(all.values());
            out.sort(Comparator.comparingLong(Grave::diedAt).reversed());
            return out;
        });
    }

    // ------------------------------------------------------------------ the tick

    public void tick(long now) {
        if (server.getTickCount() % GROUND_EVERY_TICKS == 0) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                noteGround(p);
            }
        }
        if (now >= nextRetryAt) {
            nextRetryAt = now + RETRY_MS;
            retry();
        }
        if (now >= nextSweepAt) {
            nextSweepAt = now + SWEEP_MS;
            sweep(now);
        }
        if (now >= nextNoticeAt) {
            nextNoticeAt = now + NOTICE_MS;
            deliverNotices(server.getPlayerList().getPlayers());
        }
    }

    // ------------------------------------------------------------------ the 30-day rules

    /**
     * D25 8b and 22b: a grave nobody opened for {@code archiveAfterDays} is put away (its block goes, its owner no
     * longer sees it, admins still do), and a put-away grave is deleted {@code deleteArchivedAfterDays} later with a
     * log line listing every stack. Putting away also restarts the grave's clock, so it counts the days since then.
     * Every server sweeps; a grave changes only at the revision read, so two servers never both act on it. Completes
     * with how many graves were put away or deleted.
     */
    public CompletableFuture<Integer> sweep(long now) {
        GraveStore store = mainStore();
        CompletableFuture<Integer> done = new CompletableFuture<>();
        access.onServer(store.gravesTouchedBefore(now - Math.min(archiveAfterMs, deleteAfterMs), 200), (due, error) -> {
            if (error != null) {
                done.complete(0);
                return;
            }
            List<CompletableFuture<Boolean>> acts = new ArrayList<>();
            for (Grave g : due) {
                if (g.empty()) {
                    // an emptied grave's marker: kept long enough that no old copy can come back, then removed
                    CompletableFuture<Boolean> f = new CompletableFuture<>();
                    acts.add(f);
                    access.onServer(store.deleteGrave(g.id(), g.revision()), (ok, err) -> f.complete(err == null && Boolean.TRUE.equals(ok)));
                } else if (!g.archived() && now - g.touchedAt() >= archiveAfterMs) {
                    Grave away = g.withArchivedAt(now).withContents(g.stacks(), g.xp(), g.data(), now);
                    CompletableFuture<Boolean> f = new CompletableFuture<>();
                    acts.add(f);
                    access.onServer(store.updateGrave(away), (ok, err) -> {
                        boolean did = err == null && Boolean.TRUE.equals(ok);
                        if (did) {
                            access.removeBlockOf(g);
                            BSPInventorySync.LOGGER.info("BSP-InventorySync: grave {} ({}'s, {} stack(s)) put away: nobody opened it for {} days", g.shortId(), g.ownerName(), g.stacks(), archiveAfterMs / DAY);
                            notify(g.owner(), "Your grave" + (g.serverId().isEmpty() ? "" : " on " + g.serverId()) + " was put away: nobody opened it for " + archiveAfterMs / DAY + " days. An admin can still return it.");
                        }
                        f.complete(did);
                    });
                } else if (g.archived() && now - g.touchedAt() >= deleteAfterMs) {
                    CompletableFuture<Boolean> f = new CompletableFuture<>();
                    acts.add(f);
                    access.onServer(store.deleteGrave(g.id(), g.revision()), (ok, err) -> {
                        boolean did = err == null && Boolean.TRUE.equals(ok);
                        if (did) {
                            BSPInventorySync.LOGGER.warn("BSP-InventorySync: grave {} ({}'s) deleted for good, {} days after it was put away; it held {}{}", g.shortId(), g.ownerName(),
                                    deleteAfterMs / DAY, describe(g), g.xp() > 0 ? " and " + g.xp() + " xp" : "");
                        }
                        f.complete(did);
                    });
                }
            }
            CompletableFuture.allOf(acts.toArray(CompletableFuture[]::new))
                    .whenComplete((v, e) -> done.complete((int) acts.stream().filter(f -> f.getNow(false)).count()));
        });
        return done;
    }

    /** Every stack of a grave, for the log: "3 Diamond, 1 Iron Sword, ...". */
    public static String describe(Grave g) {
        try {
            List<String> parts = new ArrayList<>();
            for (GraveContents.Entry e : GraveContents.decode(g.data()).entries()) {
                parts.add(e.stack().getCount() + " " + e.stack().getHoverName().getString());
            }
            return parts.isEmpty() ? "nothing" : String.join(", ", parts);
        } catch (RuntimeException e) {
            return g.stacks() + " stack(s) (unreadable: " + e + ")";
        }
    }

    // ------------------------------------------------------------------ notices (D25 16a)

    /**
     * Tells the owner something about their grave: at once if they are on this server, else on whichever server they
     * are on within a minute (kept in the store until then; with no network, only here).
     */
    public void notify(UUID owner, String line) {
        ServerPlayer here = live.apply(owner);
        if (here != null) {
            here.sendSystemMessage(Component.literal(line).withStyle(ChatFormatting.GOLD));
            return;
        }
        SyncService s = sync.get();
        if (s == null) {
            return;
        }
        com.mrgregles.bsp_inventorysync.storage.SyncStore store = s.store();
        store.extra(NOTICE, owner)
                .thenCompose(old -> {
                    String before = old.map(b -> new String(b, java.nio.charset.StandardCharsets.UTF_8) + "\n").orElse("");
                    return store.putExtra(NOTICE, owner, (before + line).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                })
                .whenComplete((v, e) -> {
                    if (e != null) {
                        BSPInventorySync.LOGGER.warn("BSP-InventorySync: a grave notice for {} could not be stored: {}", owner, e.toString());
                    }
                });
    }

    /** Hands the waiting notices to these players (each server, every minute); completes with how many players got some. */
    public CompletableFuture<Integer> deliverNotices(Collection<ServerPlayer> players) {
        SyncService s = sync.get();
        if (s == null || players.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        com.mrgregles.bsp_inventorysync.storage.SyncStore store = s.store();
        List<CompletableFuture<Boolean>> each = new ArrayList<>();
        for (ServerPlayer p : players) {
            CompletableFuture<Boolean> f = new CompletableFuture<>();
            each.add(f);
            access.onServer(store.extra(NOTICE, p.getUUID()), (found, error) -> {
                if (error != null || found == null || found.isEmpty()) {
                    f.complete(false);
                    return;
                }
                access.onServer(store.deleteExtra(NOTICE, p.getUUID()), (gone, err) -> {
                    if (err != null || !Boolean.TRUE.equals(gone)) {
                        f.complete(false); // another server delivered it first
                        return;
                    }
                    for (String line : new String(found.get(), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                        p.sendSystemMessage(Component.literal(line).withStyle(ChatFormatting.GOLD));
                    }
                    f.complete(true);
                });
            });
        }
        return CompletableFuture.allOf(each.toArray(CompletableFuture[]::new)).thenApply(v -> (int) each.stream().filter(f -> f.getNow(false)).count());
    }

    /** Sends again whatever is still in the world's data. */
    public void retry() {
        if (network() == null) {
            return;
        }
        for (Grave g : local.all()) {
            send(g, null);
        }
    }

    /** Notes where the player stands, if it is solid ground: the fall-back spot for a grave that finds none near the death. */
    public void noteGround(ServerPlayer p) {
        if (p.isAlive() && !p.isSpectator() && p.onGround() && !p.isInWater() && !p.isInLava() && !p.isPassenger()) {
            lastGround.put(p.getUUID(), new Ground(p.level().dimension(), p.blockPosition()));
        }
    }

    @Nullable
    public Ground lastGround(UUID player) {
        return lastGround.get(player);
    }
}
