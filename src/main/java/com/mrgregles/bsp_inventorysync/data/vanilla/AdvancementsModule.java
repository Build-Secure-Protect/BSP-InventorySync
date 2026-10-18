package com.mrgregles.bsp_inventorysync.data.vanilla;

import com.mrgregles.bsp_inventorysync.api.DataModule;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Advancement progress, carried as the player's own advancements file. Saving asks vanilla to write
 * the file ({@code world/advancements/<uuid>.json}) and reads it back; loading writes the file and
 * asks vanilla to reload it, which starts every criterion without granting any reward again (the
 * rewards were granted on the server where the advancement was earned) and sends the client the full
 * set. The JSON is kept as UTF-8 bytes here and compressed with the rest of the snapshot.
 *
 * <p>A dirty flag (IS-7) spares the file: the bytes last written are reused until Forge reports a
 * criterion granted or revoked for the player ({@code sync.DirtyTracking}), so the auto-save of an
 * idle player touches no file.
 */
public final class AdvancementsModule implements DataModule {
    public static final String ID = "advancements";
    private static final String JSON = "Json";

    /** The last bytes written per player, reused while no criterion changed (the dirty flag below). */
    private static final Map<UUID, byte[]> CACHE = new ConcurrentHashMap<>();
    private static final Set<UUID> DIRTY = ConcurrentHashMap.newKeySet();
    private static final AtomicLong FILE_WRITES = new AtomicLong();

    /** A criterion was granted or revoked: the next save must go through the file again. */
    public static void markDirty(UUID player) {
        DIRTY.add(player);
    }

    /** The player left: nothing to reuse. */
    public static void forget(UUID player) {
        CACHE.remove(player);
        DIRTY.remove(player);
    }

    /** How often vanilla was asked to write an advancements file; for the tests and {@code /invsync status}. */
    public static long fileWrites() {
        return FILE_WRITES.get();
    }

    @Override
    public String id() {
        return ID;
    }

    static Path file(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        return server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR).resolve(player.getUUID() + ".json");
    }

    @Override
    public void save(ServerPlayer player, CompoundTag out) {
        UUID id = player.getUUID();
        byte[] cached = CACHE.get(id);
        if (cached != null && !DIRTY.contains(id)) {
            out.putByteArray(JSON, cached); // nothing changed since the last save: no file round trip
            return;
        }
        player.getAdvancements().save();
        FILE_WRITES.incrementAndGet();
        Path file = file(player);
        if (!Files.isRegularFile(file)) {
            return; // nothing earned yet: the module compound stays empty, and load leaves the progress alone
        }
        try {
            byte[] json = Files.readAllBytes(file);
            out.putByteArray(JSON, json);
            CACHE.put(id, json);
            DIRTY.remove(id);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void load(ServerPlayer player, CompoundTag in) {
        if (!in.contains(JSON, Tag.TAG_BYTE_ARRAY)) {
            return;
        }
        byte[] json = in.getByteArray(JSON);
        if (json.length == 0 || !isJsonObject(json)) {
            return;
        }
        Path file = file(player);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        player.getAdvancements().reload(player.getServer().getAdvancements());
        CACHE.put(player.getUUID(), json);
        DIRTY.remove(player.getUUID());
    }

    private static boolean isJsonObject(byte[] json) {
        String s = new String(json, 0, Math.min(json.length, 64), StandardCharsets.UTF_8).trim();
        return s.startsWith("{");
    }
}
