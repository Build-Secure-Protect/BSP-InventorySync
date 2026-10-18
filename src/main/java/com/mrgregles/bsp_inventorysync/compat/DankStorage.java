package com.mrgregles.bsp_inventorysync.compat;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.api.DataModule;
import com.mrgregles.bsp_inventorysync.data.RestoreContext;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Dank Storage (optional, by reflection): a dank's contents live in the world's saved data under a
 * <b>frequency</b>, an integer counted up per world, so the same number means different contents on
 * different servers. This module carries each carried dank's contents keyed by its frequency; on a
 * restore from another server it gives the dank a fresh frequency here ({@code CDankItem.assignNextId})
 * and writes the contents under it. A restore on the same server (a relog) keeps the frequency and
 * just rewrites the contents. The frequency the source used stays in that server's saved data,
 * unused, until Dank Storage's own clean-up.
 */
public final class DankStorage {
    public static final String MOD_ID = "dankstorage";

    @Nullable private static final Class<?> DANK_ITEM;
    @Nullable private static final Method GET_FREQUENCY;
    @Nullable private static final Method GET_BAG_INVENTORY;
    @Nullable private static final Method ASSIGN_NEXT_ID;
    @Nullable private static final Method SAVE;
    @Nullable private static final Method READ;
    private static final int INVALID = -1;

    static {
        DANK_ITEM = Reflect.type("tfar.dankstorage.item.CDankItem");
        Class<?> utils = Reflect.type("tfar.dankstorage.utils.CommonUtils");
        Class<?> inventory = Reflect.type("tfar.dankstorage.inventory.DankInterface");
        GET_FREQUENCY = Reflect.method(utils, "getFrequency", ItemStack.class);
        GET_BAG_INVENTORY = Reflect.method(utils, "getBagInventory", ItemStack.class, Level.class);
        ASSIGN_NEXT_ID = Reflect.method(DANK_ITEM, "assignNextId", ItemStack.class);
        SAVE = Reflect.method(inventory, "save");
        READ = Reflect.method(inventory, "read", CompoundTag.class);
    }

    private DankStorage() {}

    public static boolean present() {
        return ModList.get().isLoaded(MOD_ID) && DANK_ITEM != null && GET_FREQUENCY != null && GET_BAG_INVENTORY != null && ASSIGN_NEXT_ID != null && SAVE != null && READ != null;
    }

    static boolean isDank(ItemStack stack) {
        return DANK_ITEM != null && !stack.isEmpty() && DANK_ITEM.isInstance(stack.getItem());
    }

    static int frequency(ItemStack stack) {
        Object f = Reflect.call(GET_FREQUENCY, null, stack);
        return f instanceof Integer i ? i : INVALID;
    }

    /** The data module; registered only when the mod is loaded. Runs after the inventory modules. */
    public static final class Module implements DataModule {
        public static final String ID = "dank";
        private static final String DANKS = "Danks";
        private static final String FREQUENCY = "Frequency";
        private static final String CONTENTS = "Contents";

        @Override
        public String id() {
            return ID;
        }

        private static void scan(Container container, ServerPlayer player, Map<Integer, ItemStack> out) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack s = container.getItem(i);
                if (isDank(s)) {
                    int f = frequency(s);
                    if (f != INVALID) {
                        out.putIfAbsent(f, s);
                    }
                }
            }
        }

        /** Every carried dank with a frequency, one stack per frequency. */
        static Map<Integer, ItemStack> carried(ServerPlayer player) {
            Map<Integer, ItemStack> out = new HashMap<>();
            scan(player.getInventory(), player, out);
            scan(player.getEnderChestInventory(), player, out);
            ItemStack cursor = player.containerMenu.getCarried();
            if (isDank(cursor) && frequency(cursor) != INVALID) {
                out.putIfAbsent(frequency(cursor), cursor);
            }
            return out;
        }

        @Override
        public void save(ServerPlayer player, CompoundTag out) {
            ListTag danks = new ListTag();
            for (Map.Entry<Integer, ItemStack> e : carried(player).entrySet()) {
                Object inv = Reflect.call(GET_BAG_INVENTORY, null, e.getValue(), player.level());
                if (inv == null) {
                    continue;
                }
                Object saved = Reflect.call(SAVE, inv);
                if (saved instanceof CompoundTag c) {
                    CompoundTag entry = new CompoundTag();
                    entry.putInt(FREQUENCY, e.getKey());
                    entry.put(CONTENTS, c);
                    danks.add(entry);
                }
            }
            out.put(DANKS, danks);
        }

        @Override
        public void load(ServerPlayer player, CompoundTag in) {
            Map<Integer, CompoundTag> byFrequency = new HashMap<>();
            for (Tag t : in.getList(DANKS, Tag.TAG_COMPOUND)) {
                CompoundTag entry = (CompoundTag) t;
                byFrequency.put(entry.getInt(FREQUENCY), entry.getCompound(CONTENTS));
            }
            if (byFrequency.isEmpty()) {
                return;
            }
            RestoreContext ctx = RestoreContext.current();
            boolean remap = ctx != null && ctx.fromAnotherServer();
            Map<Integer, Integer> newFrequencies = new HashMap<>(); // source frequency -> frequency given here (one per dank, shared by copies)
            int restored = 0;
            for (Container container : new Container[] {player.getInventory(), player.getEnderChestInventory()}) {
                for (int i = 0; i < container.getContainerSize(); i++) {
                    ItemStack s = container.getItem(i);
                    if (!isDank(s)) {
                        continue;
                    }
                    int f = frequency(s);
                    CompoundTag contents = byFrequency.get(f);
                    if (contents == null) {
                        continue;
                    }
                    if (remap) {
                        Integer given = newFrequencies.get(f);
                        if (given == null) {
                            Reflect.call(ASSIGN_NEXT_ID, null, s);
                            given = frequency(s);
                            newFrequencies.put(f, given);
                        } else {
                            Reflect.call(Reflect.method(Reflect.type("tfar.dankstorage.utils.CommonUtils"), "setFrequency", ItemStack.class, int.class), null, s, given);
                        }
                    }
                    Object inv = Reflect.call(GET_BAG_INVENTORY, null, s, player.level());
                    if (inv != null) {
                        Reflect.call(READ, inv, contents.copy());
                        restored++;
                    }
                }
            }
            if (restored > 0) {
                BSPInventorySync.LOGGER.debug("BSP-InventorySync: {} dank(s) restored for {}{}", restored, player.getGameProfile().getName(), remap ? " with new frequencies" : "");
            }
        }
    }
}
