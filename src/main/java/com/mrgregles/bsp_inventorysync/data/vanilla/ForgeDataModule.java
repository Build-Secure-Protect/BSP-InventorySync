package com.mrgregles.bsp_inventorysync.data.vanilla;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.api.DataModule;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.capabilities.CapabilityProvider;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Supplier;

/**
 * The generic module of D11: the player's persistent NBT ({@code ForgeData}: Waystones' known
 * waystones, Starter Kit's flag, BSP-Core's grants) and the player's capabilities ({@code ForgeCaps}:
 * Mekanism radiation, Compact Machines room history, and whatever else a mod attaches), so every mod
 * with per-player data is carried without an adapter of its own.
 *
 * <p>Exclusions are dotted paths into {@code ForgeData} (for example {@code PlayerPersisted.bsp_core:LastMainPos},
 * a position that only means something on the server it was saved on) and capability ids for
 * {@code ForgeCaps} (for example {@code curios:inventory}, which the Curios adapter carries instead).
 * An excluded key is neither saved nor touched on load.
 *
 * <p>Capabilities are read and written through Forge's own {@code serializeCaps} / {@code deserializeCaps}
 * on the player (protected, so by reflection; Forge's names are stable). A provider absent from the
 * snapshot is left as it is, which is also what Forge does when loading a player file.
 */
public final class ForgeDataModule implements DataModule {
    public static final String ID = "forge";
    private static final String DATA = "Data";
    private static final String CAPS = "Caps";

    @Nullable private static final Method SERIALIZE_CAPS = find("serializeCaps");
    @Nullable private static final Method DESERIALIZE_CAPS = find("deserializeCaps", CompoundTag.class);

    private final Supplier<List<? extends String>> dataExclusions;
    private final Supplier<List<? extends String>> capExclusions;

    public ForgeDataModule(Supplier<List<? extends String>> dataExclusions, Supplier<List<? extends String>> capExclusions) {
        this.dataExclusions = dataExclusions;
        this.capExclusions = capExclusions;
    }

    @Nullable
    private static Method find(String name, Class<?>... params) {
        try {
            Method m = CapabilityProvider.class.getDeclaredMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException | RuntimeException e) {
            BSPInventorySync.LOGGER.warn("BSP-InventorySync: Forge's {} is not reachable ({}); capabilities will not be synced", name, e.toString());
            return null;
        }
    }

    /** Whether capabilities can be carried on this Forge. */
    public static boolean capsSupported() {
        return SERIALIZE_CAPS != null && DESERIALIZE_CAPS != null;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void save(ServerPlayer player, CompoundTag out) {
        CompoundTag data = player.getPersistentData().copy();
        for (String path : dataExclusions.get()) {
            removePath(data, path);
        }
        out.put(DATA, data);
        if (SERIALIZE_CAPS != null) {
            CompoundTag caps = invokeSerialize(player);
            if (caps != null) {
                for (String id : capExclusions.get()) {
                    caps.remove(id);
                }
                out.put(CAPS, caps);
            }
        }
    }

    @Override
    public void load(ServerPlayer player, CompoundTag in) {
        if (in.contains(DATA, Tag.TAG_COMPOUND)) {
            CompoundTag incoming = in.getCompound(DATA).copy();
            CompoundTag live = player.getPersistentData();
            List<? extends String> excluded = dataExclusions.get();
            // keep this server's values for excluded paths, replace everything else
            CompoundTag kept = new CompoundTag();
            for (String path : excluded) {
                copyPath(live, kept, path);
                removePath(incoming, path);
            }
            for (String key : List.copyOf(live.getAllKeys())) {
                live.remove(key);
            }
            live.merge(incoming);
            live.merge(kept);
        }
        if (DESERIALIZE_CAPS != null && in.contains(CAPS, Tag.TAG_COMPOUND)) {
            CompoundTag caps = in.getCompound(CAPS).copy();
            for (String id : capExclusions.get()) {
                caps.remove(id);
            }
            if (!caps.isEmpty()) {
                try {
                    DESERIALIZE_CAPS.invoke(player, caps);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("deserializeCaps failed", e.getCause() != null ? e.getCause() : e);
                }
            }
        }
    }

    @Nullable
    private static CompoundTag invokeSerialize(ServerPlayer player) {
        try {
            return (CompoundTag) SERIALIZE_CAPS.invoke(player);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("serializeCaps failed", e.getCause() != null ? e.getCause() : e);
        }
    }

    // ------------------------------------------------------------------ dotted paths

    /** Removes {@code a.b.c} from the tag, if present; a path of one segment removes a top-level key. */
    static void removePath(CompoundTag tag, String path) {
        String[] parts = path.split("\\.");
        CompoundTag at = tag;
        for (int i = 0; i < parts.length - 1; i++) {
            if (!at.contains(parts[i], Tag.TAG_COMPOUND)) {
                return;
            }
            at = at.getCompound(parts[i]);
        }
        at.remove(parts[parts.length - 1]);
    }

    /** Copies the value at {@code a.b.c} from {@code from} into {@code to}, creating the compounds on the way. */
    static void copyPath(CompoundTag from, CompoundTag to, String path) {
        String[] parts = path.split("\\.");
        CompoundTag src = from;
        for (int i = 0; i < parts.length - 1; i++) {
            if (!src.contains(parts[i], Tag.TAG_COMPOUND)) {
                return;
            }
            src = src.getCompound(parts[i]);
        }
        Tag value = src.get(parts[parts.length - 1]);
        if (value == null) {
            return;
        }
        CompoundTag dst = to;
        for (int i = 0; i < parts.length - 1; i++) {
            if (!dst.contains(parts[i], Tag.TAG_COMPOUND)) {
                dst.put(parts[i], new CompoundTag());
            }
            dst = dst.getCompound(parts[i]);
        }
        dst.put(parts[parts.length - 1], value.copy());
    }
}
