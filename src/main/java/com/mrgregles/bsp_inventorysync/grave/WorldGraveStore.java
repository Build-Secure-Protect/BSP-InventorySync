package com.mrgregles.bsp_inventorysync.grave;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.storage.Grave;
import com.mrgregles.bsp_inventorysync.storage.GraveStore;
import com.mrgregles.bsp_inventorysync.storage.GraveTable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The graves kept in this world's own data ({@code data/bsp_inventorysync_graves.dat}), saved with the world.
 * Two jobs: on a server without the network database (single player, a test server) it is where graves live
 * (D25 13a); on a synced server it holds each new grave until the database has it, so a database outage never
 * costs a grave: whatever is here is sent again until it lands. Server thread only; every future is already
 * complete.
 */
public final class WorldGraveStore extends SavedData implements GraveStore {
    public static final String NAME = BSPInventorySync.MODID + "_graves";
    private static final String GRAVES = "Graves";

    private final GraveTable table = new GraveTable();

    /** This world's grave data, loaded on first use. */
    public static WorldGraveStore get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(WorldGraveStore::load, WorldGraveStore::new, NAME);
    }

    public static WorldGraveStore load(CompoundTag tag) {
        WorldGraveStore store = new WorldGraveStore();
        List<Grave> loaded = new ArrayList<>();
        for (Tag t : tag.getList(GRAVES, Tag.TAG_COMPOUND)) {
            try {
                loaded.add(fromTag((CompoundTag) t));
            } catch (RuntimeException e) {
                BSPInventorySync.LOGGER.error("BSP-InventorySync: a grave in this world's data could not be read and is kept aside: {}", e.toString());
                store.unreadable.add(t.copy());
            }
        }
        store.table.replaceAll(loaded);
        return store;
    }

    /** Entries that could not be read: written back as they were, never dropped. */
    private final ListTag unreadable = new ListTag();

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Grave g : table.all()) {
            list.add(toTag(g));
        }
        list.addAll(unreadable);
        tag.put(GRAVES, list);
        return tag;
    }

    static CompoundTag toTag(Grave g) {
        CompoundTag t = new CompoundTag();
        t.putUUID("Id", g.id());
        t.putUUID("Owner", g.owner());
        t.putString("OwnerName", g.ownerName());
        t.putString("Server", g.serverId());
        t.putString("Dimension", g.dimension());
        t.putInt("X", g.x());
        t.putInt("Y", g.y());
        t.putInt("Z", g.z());
        t.putBoolean("Placed", g.placed());
        t.putLong("DiedAt", g.diedAt());
        t.putLong("TouchedAt", g.touchedAt());
        t.putLong("ArchivedAt", g.archivedAt());
        t.putInt("Xp", g.xp());
        t.putInt("Stacks", g.stacks());
        t.putLong("Revision", g.revision());
        t.putByteArray("Data", g.data());
        return t;
    }

    static Grave fromTag(CompoundTag t) {
        if (!t.hasUUID("Id") || !t.hasUUID("Owner") || !t.contains("Data", Tag.TAG_BYTE_ARRAY)) {
            throw new IllegalArgumentException("a grave without its id, owner or contents");
        }
        return new Grave(t.getUUID("Id"), t.getUUID("Owner"), t.getString("OwnerName"), t.getString("Server"), t.getString("Dimension"),
                t.getInt("X"), t.getInt("Y"), t.getInt("Z"), t.getBoolean("Placed"), t.getLong("DiedAt"), t.getLong("TouchedAt"),
                t.getLong("ArchivedAt"), t.getInt("Xp"), t.getInt("Stacks"), t.getLong("Revision"), t.getByteArray("Data"));
    }

    private <T> CompletableFuture<T> changed(T value, boolean dirty) {
        if (dirty) {
            setDirty();
        }
        return CompletableFuture.completedFuture(value);
    }

    @Override
    public CompletableFuture<Boolean> addGrave(Grave grave) {
        boolean added = table.add(grave);
        return changed(added, added);
    }

    @Override
    public CompletableFuture<Optional<Grave>> grave(UUID id) {
        return CompletableFuture.completedFuture(table.get(id));
    }

    @Override
    public CompletableFuture<List<Grave>> graves(UUID owner) {
        return CompletableFuture.completedFuture(table.of(owner));
    }

    @Override
    public CompletableFuture<Boolean> updateGrave(Grave grave) {
        boolean ok = table.update(grave);
        return changed(ok, ok);
    }

    @Override
    public CompletableFuture<Boolean> deleteGrave(UUID id, long revision) {
        boolean ok = table.delete(id, revision);
        return changed(ok, ok);
    }

    @Override
    public CompletableFuture<List<Grave>> gravesTouchedBefore(long before, int limit) {
        return CompletableFuture.completedFuture(table.touchedBefore(before, limit));
    }

    /** Writes this data to disk now, not at the next world save. */
    public void saveNow(MinecraftServer server) {
        java.io.File file = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data").resolve(NAME + ".dat").toFile();
        file.getParentFile().mkdirs();
        save(file);
    }

    /** Every grave held here (the ones still to be sent, on a synced server). */
    public Collection<Grave> all() {
        return table.all();
    }

    public int size() {
        return table.size();
    }
}
