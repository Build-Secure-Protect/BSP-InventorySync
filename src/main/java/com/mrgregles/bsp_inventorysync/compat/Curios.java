package com.mrgregles.bsp_inventorysync.compat;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.api.DataModule;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Curios (optional, reached by reflection, never compiled against): the worn curios through Curios'
 * own {@code ICuriosItemHandler.writeTag()} / {@code readTag(Tag)}, which handle slot counts that
 * differ between servers. Curios syncs the handler to the client on its next tick when the stacks
 * changed, so nothing more is needed after a restore. The generic module skips the
 * {@code curios:inventory} capability so the data travels once.
 */
public final class Curios {
    public static final String MOD_ID = "curios";
    public static final String CAPABILITY_ID = "curios:inventory";

    @Nullable private static final Method GET_INVENTORY;
    @Nullable private static final Method WRITE_TAG;
    @Nullable private static final Method READ_TAG;

    static {
        Class<?> api = Reflect.type("top.theillusivec4.curios.api.CuriosApi");
        Class<?> handler = Reflect.type("top.theillusivec4.curios.api.type.capability.ICuriosItemHandler");
        GET_INVENTORY = Reflect.method(api, "getCuriosInventory", LivingEntity.class);
        WRITE_TAG = Reflect.method(handler, "writeTag");
        READ_TAG = Reflect.method(handler, "readTag", Tag.class);
    }

    private Curios() {}

    public static boolean present() {
        return ModList.get().isLoaded(MOD_ID) && GET_INVENTORY != null && WRITE_TAG != null && READ_TAG != null;
    }

    /** The player's Curios slots as Curios saves them, or null when Curios is absent or has nothing; for other adapters to look through. */
    @Nullable
    public static Tag tag(ServerPlayer player) {
        if (!present()) {
            return null;
        }
        Object h = handler(player);
        Object tag = h == null ? null : Reflect.call(WRITE_TAG, h);
        return tag instanceof Tag t ? t : null;
    }

    @Nullable
    static Object handler(ServerPlayer player) {
        Object lazy = Reflect.call(GET_INVENTORY, null, player);
        if (lazy instanceof LazyOptional<?> lo) {
            Optional<?> resolved = lo.resolve();
            return resolved.orElse(null);
        }
        return null;
    }

    /** The data module; registered only when Curios is loaded. */
    public static final class Module implements DataModule {
        public static final String ID = "curios";
        private static final String TAG = "Curios";

        @Override
        public String id() {
            return ID;
        }

        @Override
        public void save(ServerPlayer player, CompoundTag out) {
            Object h = handler(player);
            if (h == null) {
                return;
            }
            Object tag = Reflect.call(WRITE_TAG, h);
            if (tag instanceof Tag t) {
                out.put(TAG, t.copy()); // a copy: the snapshot is compressed off the server thread
            }
        }

        @Override
        public void load(ServerPlayer player, CompoundTag in) {
            if (!in.contains(TAG)) {
                return;
            }
            Object h = handler(player);
            if (h == null) {
                BSPInventorySync.LOGGER.warn("BSP-InventorySync: {} has no Curios inventory here; curios not restored", player.getGameProfile().getName());
                return;
            }
            Reflect.call(READ_TAG, h, in.get(TAG));
        }
    }
}
