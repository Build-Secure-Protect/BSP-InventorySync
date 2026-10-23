package com.mrgregles.bsp_inventorysync.compat;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.data.ItemRules;
import com.mrgregles.bsp_core.api.BspTransfer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Every call into BSP-Core, in one place. Only BSP-Core's {@code api} package is compiled against.
 * Two calls this mod needs are not in the api yet (requests R1 and R2 in the vault): until they land,
 * this adapter reaches them in this order, so a BSP-Core rename breaks one file:
 * <ol>
 * <li>the api method, looked up by reflection so the code compiles against today's BSP-Core and
 * switches over by itself the day it appears;</li>
 * <li>the internal {@code totem.TotemInventories} method, by reflection;</li>
 * <li>a fallback of this mod's own: the totem rule spelt out (the item {@code bsp_core:shatter_totem}
 * without a {@code Spent} tag), and {@link BspTransfer#handBack} for the hand-back.</li>
 * </ol>
 */
public final class BspCore {
    private static final ResourceLocation TOTEM_ITEM = ResourceLocation.parse("bsp_core:shatter_totem");
    private static final String TAG_SPENT = "Spent";
    private static final String API_TOTEMS = "com.mrgregles.bsp_core.api.BspTotems";
    private static final String INTERNAL_INVENTORIES = "com.mrgregles.bsp_core.totem.TotemInventories";

    @Nullable private static final MethodHandle IS_TOTEM;
    @Nullable private static final MethodHandle GIVE_BACK;
    private static final String IS_TOTEM_SOURCE;
    private static final String GIVE_BACK_SOURCE;

    static {
        MethodHandle is = find(API_TOTEMS, "isTotem", MethodType.methodType(boolean.class, ItemStack.class));
        String isSource = "api";
        if (is == null) {
            is = find(INTERNAL_INVENTORIES, "isTotem", MethodType.methodType(boolean.class, ItemStack.class));
            isSource = is == null ? "own rule" : "internal";
        }
        IS_TOTEM = is;
        IS_TOTEM_SOURCE = isSource;

        MethodHandle give = find(API_TOTEMS, "giveBack", MethodType.methodType(void.class, ServerPlayer.class, ItemStack.class));
        String giveSource = "api";
        if (give == null) {
            give = find(INTERNAL_INVENTORIES, "giveBack", MethodType.methodType(void.class, Player.class, ItemStack.class));
            giveSource = give == null ? "BspTransfer.handBack" : "internal";
        }
        GIVE_BACK = give;
        GIVE_BACK_SOURCE = giveSource;
    }

    private BspCore() {}

    @Nullable
    private static MethodHandle find(String className, String method, MethodType type) {
        try {
            Class<?> cls = Class.forName(className);
            return MethodHandles.publicLookup().findStatic(cls, method, type);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e) {
            return null;
        }
    }

    /** Called once when the mod loads: the live-totem rule joins the items a snapshot never carries (D4). */
    public static void install() {
        ItemRules.addExclusion(BspCore::isLiveTotem);
        BSPInventorySync.LOGGER.info("BSP-InventorySync: BSP-Core totem rule via {}, hand-back via {}", IS_TOTEM_SOURCE, GIVE_BACK_SOURCE);
    }

    /** Where the two calls come from today ("api", "internal" or the fallback); for {@code /invsync status} and the tests. */
    public static String sources() {
        return "isTotem=" + IS_TOTEM_SOURCE + ", giveBack=" + GIVE_BACK_SOURCE;
    }

    /** A real Shatter Totem: the item, and not a spent husk. Never written into a snapshot. */
    public static boolean isLiveTotem(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (IS_TOTEM != null) {
            try {
                return (boolean) IS_TOTEM.invoke(stack);
            } catch (Throwable t) {
                BSPInventorySync.LOGGER.error("BSP-InventorySync: BSP-Core isTotem failed; using the fallback rule", t);
            }
        }
        Item item = ForgeRegistries.ITEMS.getValue(TOTEM_ITEM);
        if (item == null || !stack.is(item)) {
            return false;
        }
        CompoundTag tag = stack.getTag();
        return tag == null || !tag.getBoolean(TAG_SPENT);
    }

    /**
     * Takes every live totem out of the player's inventory (main, armour, offhand) and off the cursor,
     * and returns them. Used just before a restore is applied, so a totem BSP-Core handed back a few
     * ticks after login is kept and put back afterwards instead of being overwritten (D4).
     */
    public static List<ItemStack> takeLiveTotems(ServerPlayer player) {
        List<ItemStack> taken = new ArrayList<>();
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (isLiveTotem(stack)) {
                taken.add(stack.copy());
                inv.setItem(i, ItemStack.EMPTY);
            }
        }
        ItemStack cursor = player.containerMenu.getCarried();
        if (isLiveTotem(cursor)) {
            taken.add(cursor.copy());
            player.containerMenu.setCarried(ItemStack.EMPTY);
        }
        // the ender chest too: a totem never goes into a snapshot, so the restore would clear it away there
        net.minecraft.world.inventory.PlayerEnderChestContainer ender = player.getEnderChestInventory();
        for (int i = 0; i < ender.getContainerSize(); i++) {
            ItemStack stack = ender.getItem(i);
            if (isLiveTotem(stack)) {
                taken.add(stack.copy());
                ender.setItem(i, ItemStack.EMPTY);
            }
        }
        return taken;
    }

    /** Puts a totem back through BSP-Core: the hand slot, then any free slot, never dropped. */
    public static void giveBack(ServerPlayer player, ItemStack totem) {
        if (GIVE_BACK != null) {
            try {
                GIVE_BACK.invoke(player, totem);
                return;
            } catch (Throwable t) {
                BSPInventorySync.LOGGER.error("BSP-InventorySync: BSP-Core giveBack failed; using BspTransfer.handBack", t);
            }
        }
        BspTransfer.handBack(player, List.of(totem), "message.bsp_core.transfer.returned");
    }

    /**
     * A value of BSP-Core's server config ({@code storage.*}), by the name of the field on its
     * {@code BSPConfig} class ({@code STORAGE_SERVER_ID}, {@code STORAGE_HOST}, ...). Empty when the
     * field is missing or the config is not loaded. Reflection, because the config is not in the api;
     * every caller has a fallback of its own.
     */
    public static <T> java.util.Optional<T> storageSetting(String field, Class<T> type) {
        try {
            Object value = Class.forName("com.mrgregles.bsp_core.BSPConfig").getField(field).get(null);
            if (value instanceof net.minecraftforge.common.ForgeConfigSpec.ConfigValue<?> cv) {
                Object got = cv.get();
                return type.isInstance(got) ? java.util.Optional.of(type.cast(got)) : java.util.Optional.empty();
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            BSPInventorySync.LOGGER.debug("BSP-InventorySync: BSP-Core setting {} not readable: {}", field, e.toString());
        }
        return java.util.Optional.empty();
    }

    /** BSP-Core's {@code storage.serverId}, if set. */
    public static java.util.Optional<String> serverId() {
        return storageSetting("STORAGE_SERVER_ID", String.class).filter(s -> !s.isEmpty());
    }

    /** Whether BSP-Core has a server transfer in progress for the player (announced by BSP-Teleport). */
    public static boolean isTransferring(UUID player) {
        return BspTransfer.isTransferring(player);
    }
}
