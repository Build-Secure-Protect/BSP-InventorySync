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
    @Nullable private static final Method GET_CURIOS;
    @Nullable private static final Method GET_STACKS;
    @Nullable private static final Method GET_COSMETIC_STACKS;

    static {
        Class<?> api = Reflect.type("top.theillusivec4.curios.api.CuriosApi");
        Class<?> handler = Reflect.type("top.theillusivec4.curios.api.type.capability.ICuriosItemHandler");
        Class<?> stacksHandler = Reflect.type("top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler");
        GET_INVENTORY = Reflect.method(api, "getCuriosInventory", LivingEntity.class);
        WRITE_TAG = Reflect.method(handler, "writeTag");
        READ_TAG = Reflect.method(handler, "readTag", Tag.class);
        GET_CURIOS = Reflect.method(handler, "getCurios");
        GET_STACKS = Reflect.method(stacksHandler, "getStacks");
        GET_COSMETIC_STACKS = Reflect.method(stacksHandler, "getCosmeticStacks");
    }

    /**
     * The live stacks of one Curios slot type of an online player (its cosmetic row when {@code cosmetic}),
     * through Curios' own handler, for a staff change (D29); null without Curios or without that type.
     */
    @Nullable
    public static net.minecraftforge.items.IItemHandlerModifiable liveStacks(ServerPlayer player, String type, boolean cosmetic) {
        if (!present() || GET_CURIOS == null || GET_STACKS == null || GET_COSMETIC_STACKS == null) {
            return null;
        }
        Object h = handler(player);
        Object curios = h == null ? null : Reflect.call(GET_CURIOS, h);
        if (!(curios instanceof java.util.Map<?, ?> map)) {
            return null;
        }
        Object stacks = map.get(type);
        Object row = stacks == null ? null : Reflect.call(cosmetic ? GET_COSMETIC_STACKS : GET_STACKS, stacks);
        return row instanceof net.minecraftforge.items.IItemHandlerModifiable m ? m : null;
    }

    /** Every live Curios stack of the player (worn and cosmetic), for finding a backpack in the back slot. */
    public static java.util.List<net.minecraft.world.item.ItemStack> liveItems(ServerPlayer player) {
        java.util.List<net.minecraft.world.item.ItemStack> out = new java.util.ArrayList<>();
        if (!present() || GET_CURIOS == null) {
            return out;
        }
        Object h = handler(player);
        Object curios = h == null ? null : Reflect.call(GET_CURIOS, h);
        if (curios instanceof java.util.Map<?, ?> map) {
            for (Object type : map.keySet()) {
                for (boolean cosmetic : new boolean[] {false, true}) {
                    net.minecraftforge.items.IItemHandlerModifiable row = liveStacks(player, String.valueOf(type), cosmetic);
                    for (int i = 0; row != null && i < row.getSlots(); i++) {
                        out.add(row.getStackInSlot(i));
                    }
                }
            }
        }
        return out;
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
