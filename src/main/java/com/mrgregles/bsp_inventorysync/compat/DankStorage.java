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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
    @Nullable private static final Method SET_FREQUENCY;
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
        SET_FREQUENCY = Reflect.method(utils, "setFrequency", ItemStack.class, int.class);
        GET_BAG_INVENTORY = Reflect.method(utils, "getBagInventory", ItemStack.class, Level.class);
        ASSIGN_NEXT_ID = Reflect.method(DANK_ITEM, "assignNextId", ItemStack.class);
        SAVE = Reflect.method(inventory, "save");
        READ = Reflect.method(inventory, "read", CompoundTag.class);
    }

    @Nullable private static final Method GET_ITEM = Reflect.method(Reflect.type("tfar.dankstorage.inventory.DankInterface"), "getItemDank", int.class);
    @Nullable private static final Method SET_ITEM = Reflect.method(Reflect.type("tfar.dankstorage.inventory.DankInterface"), "setItemDank", int.class, ItemStack.class);
    @Nullable private static final Method SIZE = Reflect.method(Reflect.type("tfar.dankstorage.inventory.DankInterface"), "getContainerSizeDank");
    @Nullable private static final Method LIMIT = Reflect.method(Reflect.type("tfar.dankstorage.inventory.DankInterface"), "getMaxStackSizeSensitive", ItemStack.class);
    @Nullable private static final Method HAS_GHOST = Reflect.method(Reflect.type("tfar.dankstorage.inventory.DankInterface"), "hasGhostItem", int.class);
    @Nullable private static final Method GET_GHOST = Reflect.method(Reflect.type("tfar.dankstorage.inventory.DankInterface"), "getGhostItem", int.class);
    @Nullable private static final Method CHANGED = Reflect.method(Reflect.type("tfar.dankstorage.inventory.DankInterface"), "setChangedDank");

    private DankStorage() {}

    /**
     * The live contents of a dank the online player carries (inventory, ender chest, cursor, as the module
     * saves them), for a staff change (D29). Dank Storage keeps one inventory object per frequency in the
     * world's saved data and every open screen uses it, so a change made here is what the player sees.
     * Null when the mod is absent or no carried dank has that frequency.
     */
    @Nullable
    public static Live live(ServerPlayer player, int frequency) {
        if (!present() || GET_ITEM == null || SET_ITEM == null || SIZE == null || LIMIT == null || CHANGED == null) {
            return null;
        }
        ItemStack dank = Module.carried(player).get(frequency);
        Object inv = dank == null ? null : Reflect.call(GET_BAG_INVENTORY, null, dank, player.level());
        return inv == null ? null : new Live(inv);
    }

    /** One dank's live inventory. */
    public static final class Live {
        private final Object inv;

        private Live(Object inv) {
            this.inv = inv;
        }

        public int size() {
            return Reflect.call(SIZE, inv) instanceof Integer n ? n : 0;
        }

        public ItemStack get(int slot) {
            return Reflect.call(GET_ITEM, inv, slot) instanceof ItemStack s ? s : ItemStack.EMPTY;
        }

        public void set(int slot, ItemStack stack) {
            Reflect.call(SET_ITEM, inv, slot, stack);
            Reflect.call(CHANGED, inv);
        }

        /** How many of this item the slot holds; 0 when the dank refuses it there (its blacklist) or the slot is locked to another item. */
        public int limit(int slot, ItemStack stack) {
            if (inv instanceof net.minecraftforge.items.IItemHandler h && !h.isItemValid(slot, stack)) {
                return 0;
            }
            if (HAS_GHOST != null && GET_GHOST != null && Boolean.TRUE.equals(Reflect.call(HAS_GHOST, inv, slot))
                    && Reflect.call(GET_GHOST, inv, slot) instanceof ItemStack ghost && !ghost.is(stack.getItem())) {
                return 0;
            }
            return Reflect.call(LIMIT, inv, stack) instanceof Integer n ? n : stack.getMaxStackSize();
        }
    }

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
            out.put(DANKS, danks(carried(player), player.level()));
        }

        /**
         * The contents of every dank among these stacks, keyed by the frequency it has on this server, in the form
         * {@link #load} reads: a grave carries them this way (D25). Empty without the mod.
         */
        public static ListTag danksIn(java.util.Collection<ItemStack> stacks, Level level) {
            if (!present()) {
                return new ListTag();
            }
            Map<Integer, ItemStack> byFrequency = new HashMap<>();
            for (ItemStack s : stacks) {
                if (isDank(s) && frequency(s) != INVALID) {
                    byFrequency.putIfAbsent(frequency(s), s);
                }
            }
            return danks(byFrequency, level);
        }

        /**
         * Gives danks that come from another server's grave their contents here (D25): each one read by {@link #danksIn}
         * gets a fresh frequency on this server (copies of one dank share it) and its contents written under it. The
         * stacks are changed in place, before they go into an inventory. Returns how many danks got contents.
         */
        public static int restoreDanks(java.util.Collection<ItemStack> stacks, ListTag danks, Level level) {
            if (!present() || danks.isEmpty()) {
                return 0;
            }
            Map<Integer, CompoundTag> byFrequency = new HashMap<>();
            for (Tag t : danks) {
                CompoundTag entry = (CompoundTag) t;
                byFrequency.put(entry.getInt(FREQUENCY), entry.getCompound(CONTENTS));
            }
            Map<Integer, Integer> given = new HashMap<>();
            int n = 0;
            for (ItemStack s : stacks) {
                if (!isDank(s)) {
                    continue;
                }
                int f = frequency(s);
                CompoundTag contents = byFrequency.get(f);
                if (contents == null) {
                    continue;
                }
                Integer here = given.get(f);
                if (here == null) {
                    Reflect.call(ASSIGN_NEXT_ID, null, s);
                    given.put(f, frequency(s));
                } else {
                    Reflect.call(SET_FREQUENCY, null, s, here);
                }
                Object inv = Reflect.call(GET_BAG_INVENTORY, null, s, level);
                if (inv != null) {
                    Reflect.call(READ, inv, contents.copy());
                    n++;
                }
            }
            return n;
        }

        private static ListTag danks(Map<Integer, ItemStack> byFrequency, Level level) {
            ListTag danks = new ListTag();
            for (Map.Entry<Integer, ItemStack> e : byFrequency.entrySet()) {
                Object inv = Reflect.call(GET_BAG_INVENTORY, null, e.getValue(), level);
                if (inv == null) {
                    continue;
                }
                Object saved = Reflect.call(SAVE, inv);
                if (saved instanceof CompoundTag c) {
                    CompoundTag entry = new CompoundTag();
                    entry.putInt(FREQUENCY, e.getKey());
                    entry.put(CONTENTS, c.copy()); // a copy: the snapshot is compressed off the server thread
                    danks.add(entry);
                }
            }
            return danks;
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
            List<ItemStack> stacks = new ArrayList<>();
            for (Container container : new Container[] {player.getInventory(), player.getEnderChestInventory()}) {
                for (int i = 0; i < container.getContainerSize(); i++) {
                    stacks.add(container.getItem(i));
                }
            }
            stacks.add(player.containerMenu.getCarried()); // the cursor too, as save() reads it
            for (ItemStack s : stacks) {
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
                        Reflect.call(SET_FREQUENCY, null, s, given);
                    }
                }
                Object inv = Reflect.call(GET_BAG_INVENTORY, null, s, player.level());
                if (inv != null) {
                    Reflect.call(READ, inv, contents.copy());
                    restored++;
                }
            }
            if (restored > 0) {
                BSPInventorySync.LOGGER.debug("BSP-InventorySync: {} dank(s) restored for {}{}", restored, player.getGameProfile().getName(), remap ? " with new frequencies" : "");
            }
        }
    }
}
