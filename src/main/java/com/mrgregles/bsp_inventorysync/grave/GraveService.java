package com.mrgregles.bsp_inventorysync.grave;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.SyncConfig;
import com.mrgregles.bsp_inventorysync.compat.BspCore;
import com.mrgregles.bsp_inventorysync.compat.CompactMachines;
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
    /** The extra blob hinting that a player has a grave marked for return (D30 audit 4a); the marks themselves are on the graves. */
    public static final String RETURN = "grave_return";
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
        GraveBorder.setMargin(SyncConfig.getOr(SyncConfig.GRAVE_BORDER_MARGIN, 5));
        active = s;
        BSPInventorySync.LOGGER.info("BSP-InventorySync: graves on, kept in {}{}", Sync.active() != null ? "the network database" : "this world's own data",
                Sync.active() != null && world.size() > 0 ? "; " + world.size() + " grave(s) in this world's data are sent to it" : "");
        s.moveFromBorders(); // GB-1 7 b: graves already standing outside the border or too close to it
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
            ItemStack kept = stack.copy();
            CompactMachines.mark(kept, CompactMachines.localMark(serverId())); // D31: a machine bound here keeps its home, wherever the grave is recovered
            entries.add(new GraveContents.Entry(slotOf(stack, layout, used), kept));
        }
        if (entries.isEmpty()) {
            return null;
        }

        ServerLevel level = player.serverLevel();
        BlockPos deathPos = player.blockPosition();
        Set<BlockPos> avoid = totemSpots(level, deathPos);
        ServerLevel graveLevel = level;
        boolean atBorder = GraveSpot.nearBorder(level, deathPos);
        Optional<BlockPos> spot = GraveSpot.nearInside(level, deathPos, radius, avoid); // GB-1: inside the world border, with the margin
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
        CompoundTag extraData = extras(entries.stream().map(GraveContents.Entry::stack).toList(), level, name);
        CompactMachines.markTree(extraData, CompactMachines.localMark(serverId())); // machines inside backpacks and danks
        GraveContents contents = new GraveContents(entries, extraData);
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
        BSPInventorySync.LOGGER.info("BSP-InventorySync: grave {} for {}: {} stack(s), {} xp, {}{}", grave.shortId(), name, grave.stacks(), xp,
                grave.placed() ? "at " + where.getX() + " " + where.getY() + " " + where.getZ() + " in " + grave.dimension()
                        : "no spot near " + deathPos.getX() + " " + deathPos.getY() + " " + deathPos.getZ() + " in " + level.dimension().location() + " (recovered by command)",
                atBorder ? "; they died at " + deathPos.getX() + " " + deathPos.getY() + " " + deathPos.getZ() + ", outside the world border or within "
                        + GraveBorder.margin() + " blocks of it, so the grave was put inside" : "");
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

    /**
     * PR18: the player was reset. Their graves kept on this server from before {@code cut} are written as empty markers
     * ({@code emptyData}), as an emptied grave is, so no old copy can bring them back: those riding on their next snapshot
     * (the marker rides instead) and those in this world's data (every grave without a network, or ones on their way to the
     * database, which then carry the marker there). Each headstone here goes and each grave is logged with what it held.
     * Returns how many were emptied. Server thread.
     */
    public int forgetLocal(UUID owner, long cut, byte[] emptyData) {
        int gone = 0;
        long now = System.currentTimeMillis();
        List<Grave> riding = pending.get(owner);
        if (riding != null) {
            for (int i = 0; i < riding.size(); i++) {
                Grave g = riding.get(i);
                if (g.diedAt() <= cut && !g.empty()) {
                    riding.set(i, g.withContents(0, 0, emptyData, now));
                    forgotten(g);
                    gone++;
                }
            }
        }
        for (Grave g : local.graves(owner).join()) {
            if (g.owner().equals(owner) && g.diedAt() <= cut && !g.empty() && Boolean.TRUE.equals(local.updateGrave(g.withContents(0, 0, emptyData, now)).join())) {
                forgotten(g);
                gone++;
            }
        }
        deaths.remove(owner);
        heldXp.remove(owner);
        return gone;
    }

    /**
     * SR10: a season reset. {@link #forgetLocal} for every owner with a grave kept on this server (riding on a snapshot or in
     * this world's data). Returns how many were emptied. Server thread.
     */
    public int forgetLocalAll(long cut, byte[] emptyData) {
        java.util.Set<UUID> owners = new java.util.LinkedHashSet<>(pending.keySet());
        for (Grave g : local.gravesTouchedBefore(Long.MAX_VALUE, Integer.MAX_VALUE).join()) {
            owners.add(g.owner());
        }
        int gone = 0;
        for (UUID owner : owners) {
            gone += forgetLocal(owner, cut, emptyData);
        }
        return gone;
    }

    private void forgotten(Grave g) {
        access.removeBlockOf(g);
        BSPInventorySync.LOGGER.warn("BSP-InventorySync: {}'s grave {} kept on this server emptied at their reset; it held {}{}", g.ownerName(), g.shortId(), describe(g),
                g.xp() > 0 ? " and " + g.xp() + " xp" : "");
    }

    /** PR18: removes the grave's headstone if it stands on this server (a reset player's grave, emptied in the database); true when it did. */
    public boolean removeBlock(Grave g) {
        return access.removeBlockOf(g);
    }

    public MinecraftServer server() {
        return server;
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
            deliverReturns(server.getPlayerList().getPlayers());
        }
    }

    /** The running sync, or null (no network). */
    @Nullable
    public SyncService syncNow() {
        return sync.get();
    }

    /** The player's name: online here, else from the server's profile cache, else the id. */
    public String nameOf(UUID id) {
        ServerPlayer p = live.apply(id);
        if (p != null) {
            return p.getGameProfile().getName();
        }
        var cache = server.getProfileCache();
        return cache == null ? id.toString() : cache.get(id).map(com.mojang.authlib.GameProfile::getName).orElse(id.toString());
    }

    /** The player on this server with that id, or null (the player list; tests set their own). */
    @Nullable
    public ServerPlayer livePlayer(UUID id) {
        return live.apply(id);
    }

    /**
     * Keeps an admin's return of the grave for its owner, who is offline or still arriving (D30 2b and its audit): the
     * grave itself is marked (written at its revision, so it never races another change), then the owner gets a hint in
     * the extra blob {@link #RETURN} so the minute round of whichever server they are on looks at their graves. Completes
     * with whether the mark was written (false: the grave changed meanwhile, is still being saved, or the store failed).
     * Server thread.
     */
    public CompletableFuture<Boolean> queueReturn(Grave g, String by) {
        SyncService s = sync.get();
        if (s == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("returning a grave to an offline player needs the sync on"));
        }
        com.mrgregles.bsp_inventorysync.storage.SyncStore store = s.store();
        return access.markReturn(g, by).thenCompose(marked -> {
            if (!marked) {
                return CompletableFuture.completedFuture(false);
            }
            return hint(store, g.owner()).thenApply(v -> true);
        });
    }

    /** The hint that the player has a grave marked for return; a failure is logged (the arrival still finds the mark). */
    private CompletableFuture<Void> hint(com.mrgregles.bsp_inventorysync.storage.SyncStore store, UUID owner) {
        return store.putExtra(RETURN, owner, new byte[] {1}).handle((v, e) -> {
            if (e != null) {
                BSPInventorySync.LOGGER.warn("BSP-InventorySync: the return hint for {} could not be stored ({}); the grave is returned at their next arrival", owner, e.toString());
            }
            return null;
        });
    }

    /**
     * Carries out the returns marked for these players (D30 and its audit). At an arrival ({@code arrival}) their graves
     * are always looked at; in the minute round only for players with a hint, which is deleted before the graves are read
     * (so a return marked meanwhile leaves a new hint, never lost). Only players whose data has settled here. Each marked
     * grave is returned in turn, so each sees the inventory the one before left; one that is busy gets a new hint for the
     * next round; one that fails keeps its mark for their next arrival. Completes with how many graves were returned.
     * Server thread.
     */
    public CompletableFuture<Integer> deliverReturns(Collection<ServerPlayer> players, boolean arrival) {
        SyncService s = sync.get();
        if (s == null || players.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        com.mrgregles.bsp_inventorysync.storage.SyncStore store = s.store();
        List<CompletableFuture<Integer>> each = new ArrayList<>();
        for (ServerPlayer p : players) {
            if (SyncService.isSyncing(p.getUUID()) || !s.settled(p.getUUID())) {
                continue; // after the arrival, and never to a player whose login was refused
            }
            CompletableFuture<Integer> f = new CompletableFuture<>();
            each.add(f);
            CompletableFuture<Boolean> look = arrival ? CompletableFuture.completedFuture(true)
                    : store.extra(RETURN, p.getUUID()).thenCompose(h -> h.isEmpty() ? CompletableFuture.completedFuture(false)
                            : store.deleteExtra(RETURN, p.getUUID()).thenApply(gone -> true));
            access.onServer(look, (go, error) -> {
                if (error != null || !Boolean.TRUE.equals(go)) {
                    f.complete(0);
                    return;
                }
                access.onServer(graves(p.getUUID()), (all, err) -> {
                    if (err != null) {
                        f.complete(0);
                        return;
                    }
                    List<Grave> marked = new ArrayList<>();
                    for (Grave g : all) {
                        if (!g.empty() && g.owner().equals(p.getUUID()) && GraveAccess.returnMark(g) != null
                                && !com.mrgregles.bsp_inventorysync.compat.PlayerReset.forgotten(server, p.getUUID(), p, g.diedAt())) { // PR18: never a grave from before their reset
                            marked.add(g);
                        }
                    }
                    returnInTurn(p, store, marked, 0, 0, f);
                });
            });
        }
        return CompletableFuture.allOf(each.toArray(CompletableFuture[]::new)).thenApply(v -> each.stream().mapToInt(x -> x.getNow(0)).sum());
    }

    /** The minute round and tests: {@link #deliverReturns(Collection, boolean)} for players with a hint. */
    public CompletableFuture<Integer> deliverReturns(Collection<ServerPlayer> players) {
        return deliverReturns(players, false);
    }

    private void returnInTurn(ServerPlayer p, com.mrgregles.bsp_inventorysync.storage.SyncStore store, List<Grave> marked, int i, int done, CompletableFuture<Integer> f) {
        if (i >= marked.size()) {
            f.complete(done);
            return;
        }
        Grave g = marked.get(i);
        String by = GraveAccess.returnMark(g);
        access.giveBack(p, g.id(), by == null ? "staff" : by).whenComplete((r, e) -> server.execute(() -> {
            var outcome = r == null ? com.mrgregles.bsp_inventorysync.api.GraveReturn.Outcome.FAILED : r.outcome();
            int now = done;
            switch (outcome) {
                case OK, PARTIAL -> now++;
                case BUSY -> hint(store, p.getUUID()); // the next minute round
                default -> BSPInventorySync.LOGGER.warn("BSP-InventorySync: {}'s return of grave {} to {} did not happen at their arrival: {}; it stays marked for their next arrival",
                        by, g.shortId(), p.getGameProfile().getName(), r == null ? e : r.message());
            }
            returnInTurn(p, store, marked, i + 1, now, f);
        }));
    }
    /**
     * GB-1 7 b (the owner's answer 2026-11-18, and the AUD-9 audit): this server's standing graves that are outside the
     * world border, or closer to it than {@code graves.borderMargin}, are moved to the nearest spot far enough in, found
     * as a new death's would be ({@link GraveSpot#nearInside}). Graves are taken from the main store and, on a synced
     * server, from this world's data while they wait to reach the database. They are moved one at a time, each spot
     * searched once the grave before has its headstone, so two never share a spot. Each is written at its new place first,
     * at the revision read (a lost answer is checked by reading the grave back); then the old headstone goes and a new one
     * stands, keeping its facing, when it becomes public, its network flag and its head. If the spot was taken meanwhile
     * (a death there), or no spot is found, the grave is kept without a block and can be recovered with /grave at once.
     * Each owner gets one notice listing their moved graves (an owner offline on a server without the network database is
     * not told; the log has every move). Runs at the graves' start, so also at {@code /invsync reload}; a grave whose write
     * was refused is tried again then. Server thread. Completes with how many graves were moved or taken off their spot.
     */
    public CompletableFuture<Integer> moveFromBorders() {
        CompletableFuture<Integer> done = new CompletableFuture<>();
        String here = serverId();
        GraveStore main = mainStore();
        GraveStore waiting = network() != null ? local : null; // graves still on their way to the database
        CompletableFuture<List<Grave>> fromMain, fromWaiting;
        try {
            fromMain = main.placedGraves(here);
            fromWaiting = waiting == null ? CompletableFuture.completedFuture(List.of()) : waiting.placedGraves(here);
        } catch (RuntimeException e) {
            fromMain = CompletableFuture.failedFuture(e);
            fromWaiting = CompletableFuture.completedFuture(List.of());
        }
        CompletableFuture<List<Grave>> w = fromWaiting;
        access.onServer(fromMain.thenCombine(w, (a, b) -> List.of(a, b)), (both, error) -> {
            if (error != null) {
                BSPInventorySync.LOGGER.warn("BSP-InventorySync: the graves standing here could not be read to check them against the world border ({}); "
                        + "they are checked again at the next start", error.toString());
                done.complete(0);
                return;
            }
            List<BorderMove> due = new ArrayList<>();
            Set<UUID> seen = new HashSet<>();
            for (int i = 0; i < 2; i++) {
                GraveStore store = i == 0 ? main : waiting;
                for (Grave g : both.get(i)) {
                    if (!g.placed() || g.archived() || g.empty() || !g.serverId().equals(here) || !seen.add(g.id())) {
                        continue;
                    }
                    ServerLevel level = levelOf(g.dimension());
                    if (level != null && GraveSpot.nearBorder(level, new BlockPos(g.x(), g.y(), g.z()))) {
                        due.add(new BorderMove(store, g, level));
                    }
                }
            }
            moveInTurn(due, 0, 0, new LinkedHashMap<>(), done);
        });
        return done;
    }

    /** A grave to move inside the border, and the store it is kept in. */
    private record BorderMove(GraveStore store, Grave grave, ServerLevel level) {}

    private void moveInTurn(List<BorderMove> due, int i, int moved, Map<UUID, List<String>> notices, CompletableFuture<Integer> done) {
        if (i >= due.size()) {
            notices.forEach((owner, lines) -> notify(owner, String.join("\n", lines))); // one notice per owner: two at once would overwrite each other
            done.complete(moved);
            return;
        }
        BorderMove m = due.get(i);
        CompletableFuture<Boolean> one;
        try {
            one = moveInside(m.store(), m.grave(), m.level(), notices);
        } catch (RuntimeException e) {
            BSPInventorySync.LOGGER.error("BSP-InventorySync: grave {} ({}'s) could not be moved inside the world border", m.grave().shortId(), m.grave().ownerName(), e);
            one = CompletableFuture.completedFuture(false);
        }
        one.whenComplete((ok, e) -> server.execute(() -> moveInTurn(due, i + 1, moved + (Boolean.TRUE.equals(ok) ? 1 : 0), notices, done)));
    }

    private CompletableFuture<Boolean> moveInside(GraveStore store, Grave g, ServerLevel level, Map<UUID, List<String>> notices) {
        BlockPos old = new BlockPos(g.x(), g.y(), g.z());
        level.getChunk(old); // loaded, so the old headstone is read
        net.minecraft.core.Direction facing = net.minecraft.core.Direction.NORTH;
        long publicAt = g.diedAt() + privateMs;
        boolean networked = network() != null;
        com.mojang.authlib.GameProfile profile = null;
        if (level.getBlockEntity(old) instanceof GraveBlockEntity be && g.id().equals(be.graveId())) {
            net.minecraft.world.level.block.state.BlockState state = level.getBlockState(old);
            if (state.hasProperty(GraveBlock.FACING)) {
                facing = state.getValue(GraveBlock.FACING);
            }
            publicAt = be.publicAt();
            networked = be.networked();
            profile = be.profile();
        }
        Optional<BlockPos> spot = GraveSpot.nearInside(level, old, radius, Set.of());
        Grave moved = spot.map(p -> g.withPosition(p.getX(), p.getY(), p.getZ())).orElse(g.withPlaced(false));
        String where = old.getX() + " " + old.getY() + " " + old.getZ() + " in " + g.dimension();
        String server = g.serverId().isEmpty() ? "" : " on " + g.serverId();
        CompletableFuture<Boolean> f = new CompletableFuture<>();
        net.minecraft.core.Direction face = facing;
        long becomesPublic = publicAt;
        boolean net = networked;
        com.mojang.authlib.GameProfile head = profile;
        access.onServer(heldForTests(access.write(store, moved)), (written, err) -> {
            if (err != null || written == GraveAccess.Written.NO) {
                BSPInventorySync.LOGGER.warn("BSP-InventorySync: grave {} ({}'s) at {} is outside the world border or within {} blocks of it, but was not moved ({}); "
                        + "it is tried again at the next start", g.shortId(), g.ownerName(), where, GraveBorder.margin(), err != null ? err.toString() : "it changed meanwhile");
                f.complete(false);
                return;
            }
            if (written == GraveAccess.Written.UNKNOWN) {
                BSPInventorySync.LOGGER.warn("BSP-InventorySync: the store did not confirm the move of grave {} ({}'s); it is carried out as written", g.shortId(), g.ownerName());
            }
            level.getChunk(old); // again: with a database the answer comes later, and the old chunk may have unloaded meanwhile
            access.removeBlockOf(g);
            BlockPos to = spot.orElse(null);
            // the spot again: a death may have put its grave there while the store answered
            boolean stands = to != null && GraveSpot.good(level, to, Set.of())
                    && placeBlock(level, to, face, g.id(), g.owner(), g.ownerName(), g.diedAt(), becomesPublic, net, head);
            if (stands) {
                BSPInventorySync.LOGGER.info("BSP-InventorySync: grave {} ({}'s) moved from {} to {} {} {}: it was outside the world border or within {} blocks of it",
                        g.shortId(), g.ownerName(), where, to.getX(), to.getY(), to.getZ(), GraveBorder.margin());
                notices.computeIfAbsent(g.owner(), k -> new ArrayList<>()).add("Your grave" + server + " stood at the edge of the world, out of reach, so it was moved to "
                        + to.getX() + " " + to.getY() + " " + to.getZ() + " in " + dimensionName(g.dimension()) + ".");
                f.complete(true);
                return;
            }
            Runnable told = () -> {
                BSPInventorySync.LOGGER.info("BSP-InventorySync: grave {} ({}'s) at {} was outside the world border or within {} blocks of it and {}; "
                        + "its headstone is gone and it can be recovered with /grave", g.shortId(), g.ownerName(), where, GraveBorder.margin(),
                        to == null ? "found no spot inside" : "its new spot " + to.getX() + " " + to.getY() + " " + to.getZ() + " was taken meanwhile");
                notices.computeIfAbsent(g.owner(), k -> new ArrayList<>()).add("Your grave" + server
                        + " stood at the edge of the world, out of reach. Everything is kept safe: recover it with /grave.");
                f.complete(true);
            };
            if (to == null) {
                told.run(); // already written without a block
                return;
            }
            // written at a spot where no headstone could stand: kept without a block instead, so /grave recovers it at once
            access.onServer(access.write(store, moved.nextRevision().withPlaced(false)), (again, err2) -> {
                if (err2 != null || again != GraveAccess.Written.YES) {
                    BSPInventorySync.LOGGER.warn("BSP-InventorySync: grave {} ({}'s) is written at {} {} {} where no headstone stands, and could not be marked as having none ({}); "
                            + "it shows as recoverable while that spot is loaded", g.shortId(), g.ownerName(), to.getX(), to.getY(), to.getZ(),
                            err2 != null ? err2.toString() : String.valueOf(again));
                }
                told.run();
            });
        });
        return f;
    }

    /**
     * GameTests only: when set, each answer of a border move's store write waits here until the test runs it, as a
     * database answering late would ({@code null} in play: answers pass straight through).
     */
    @Nullable
    private static volatile List<Runnable> heldAnswersForTests;

    /** GameTests only: holds border moves' store answers in {@code held} until the test runs them; null to stop. */
    public static void holdMoveAnswersForTests(@Nullable List<Runnable> held) {
        heldAnswersForTests = held;
    }

    private static <T> CompletableFuture<T> heldForTests(CompletableFuture<T> answer) {
        List<Runnable> held = heldAnswersForTests;
        if (held == null) {
            return answer;
        }
        CompletableFuture<T> late = new CompletableFuture<>();
        answer.whenComplete((v, e) -> held.add(() -> {
            if (e != null) {
                late.completeExceptionally(e);
            } else {
                late.complete(v);
            }
        }));
        return late;
    }

    @Nullable
    private ServerLevel levelOf(String dimension) {
        ResourceLocation dim = ResourceLocation.tryParse(dimension);
        return dim == null ? null : server.getLevel(ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dim));
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
            for (GraveContents.Entry e : GraveContents.of(g).entries()) {
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
