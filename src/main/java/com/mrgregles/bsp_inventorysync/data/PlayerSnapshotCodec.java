package com.mrgregles.bsp_inventorysync.data;

import com.mrgregles.bsp_inventorysync.api.DataModule;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Takes and applies snapshots: one compound per player holding one compound per {@link DataModule},
 * and the bytes that go to the store (GZIP-compressed NBT, one blob per snapshot, D6).
 *
 * <pre>
 * { Format: 1, Modules: { inventory: {...}, ender_chest: {...}, ... }, Failed: ["id", ...] }
 * </pre>
 *
 * Each module runs on its own: one that throws is logged, named in {@code Failed} (on save) or in the
 * result (on load), and the others still run, so one broken integration never costs a player the rest
 * of their data. A module missing from a snapshot (an older snapshot, a module since added) is left
 * untouched on load.
 */
public final class PlayerSnapshotCodec {
    public static final int FORMAT = 1;
    private static final String KEY_FORMAT = "Format";
    private static final String KEY_MODULES = "Modules";
    private static final String KEY_FAILED = "Failed";

    /** What happened on load: the modules applied, those that threw, and those the snapshot lacked. */
    public record Result(List<String> applied, List<String> failed, List<String> missing) {
        public boolean clean() {
            return failed.isEmpty();
        }
    }

    private PlayerSnapshotCodec() {}

    /** The player's state right now, through every registered module. */
    public static CompoundTag capture(ServerPlayer player) {
        CompoundTag root = new CompoundTag();
        root.putInt(KEY_FORMAT, FORMAT);
        CompoundTag modules = new CompoundTag();
        ListTag failed = new ListTag();
        for (DataModule module : Modules.all()) {
            CompoundTag out = new CompoundTag();
            try {
                module.save(player, out);
                modules.put(module.id(), out);
            } catch (Throwable t) {
                BSPInventorySync.LOGGER.error("BSP-InventorySync: module '{}' failed to save {}; the rest is saved without it", module.id(), player.getGameProfile().getName(), t);
                failed.add(StringTag.valueOf(module.id()));
            }
        }
        root.put(KEY_MODULES, modules);
        if (!failed.isEmpty()) {
            root.put(KEY_FAILED, failed);
        }
        return root;
    }

    /** Applies a snapshot to the player, module by module, with no restore context (tests, same-server use). */
    public static Result apply(ServerPlayer player, CompoundTag root) {
        return apply(player, root, null);
    }

    /** Applies a snapshot to the player, module by module; {@code context} tells modules where it came from. */
    public static Result apply(ServerPlayer player, CompoundTag root, RestoreContext context) {
        RestoreContext.set(context);
        try {
            return applyModules(player, root);
        } finally {
            RestoreContext.set(null);
        }
    }

    private static Result applyModules(ServerPlayer player, CompoundTag root) {
        CompoundTag modules = root.getCompound(KEY_MODULES);
        List<String> applied = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (DataModule module : Modules.all()) {
            if (!modules.contains(module.id(), Tag.TAG_COMPOUND)) {
                missing.add(module.id());
                continue;
            }
            try {
                module.load(player, modules.getCompound(module.id()));
                applied.add(module.id());
            } catch (Throwable t) {
                BSPInventorySync.LOGGER.error("BSP-InventorySync: module '{}' failed to load for {}; the rest is applied", module.id(), player.getGameProfile().getName(), t);
                failed.add(module.id());
            }
        }
        return new Result(List.copyOf(applied), List.copyOf(failed), List.copyOf(missing));
    }

    /** The module ids that failed when the snapshot was taken. */
    public static List<String> failedOnSave(CompoundTag root) {
        List<String> out = new ArrayList<>();
        for (Tag t : root.getList(KEY_FAILED, Tag.TAG_STRING)) {
            out.add(t.getAsString());
        }
        return out;
    }

    /** The module compound inside a snapshot, or null. */
    public static CompoundTag module(CompoundTag root, String id) {
        CompoundTag modules = root.getCompound(KEY_MODULES);
        return modules.contains(id, Tag.TAG_COMPOUND) ? modules.getCompound(id) : null;
    }

    /** GZIP-compressed NBT. */
    public static byte[] encode(CompoundTag root) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(4096);
        try {
            NbtIo.writeCompressed(root, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    public static CompoundTag decode(byte[] data) {
        try {
            return NbtIo.readCompressed(new ByteArrayInputStream(data));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
