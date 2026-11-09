package com.mrgregles.bsp_inventorysync.compat;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.api.DataModule;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Sophisticated Backpacks (optional, by reflection): a backpack's contents live in the world's saved
 * data ({@code BackpackStorage}) under the {@code contentsUuid} in the item's tag, so the item alone
 * would arrive empty. This module finds every backpack the player carries (inventory, cursor, ender
 * chest, and backpacks inside backpacks) and carries their contents with the snapshot; on load it
 * writes them back into this server's storage under the same UUID (globally unique, so no remap).
 * The UUID scan is this mod's own and also serves the tests without the mod.
 */
public final class SophisticatedBackpacks {
    public static final String MOD_ID = "sophisticatedbackpacks";
    public static final String CONTENTS_UUID = "contentsUuid";

    @Nullable private static final Method GET_STORAGE;
    @Nullable private static final Method GET_CONTENTS;
    @Nullable private static final Method SET_CONTENTS;
    @Nullable private static final Method GET_WRAPPER_CAPABILITY;
    @Nullable private static final Method GET_INVENTORY_HANDLER;

    static {
        Class<?> storage = Reflect.type("net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackStorage");
        GET_STORAGE = Reflect.method(storage, "get");
        GET_CONTENTS = Reflect.method(storage, "getBackpackContents", UUID.class);
        SET_CONTENTS = Reflect.method(storage, "setBackpackContents", UUID.class, CompoundTag.class);
        GET_WRAPPER_CAPABILITY = Reflect.method(Reflect.type("net.p3pp3rf1y.sophisticatedbackpacks.api.CapabilityBackpackWrapper"), "getCapabilityInstance");
        GET_INVENTORY_HANDLER = Reflect.method(Reflect.type("net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper"), "getInventoryHandler");
    }

    /**
     * The live item slots of a backpack the online player carries, for a staff change (D29); null when it is
     * not found or the mod is absent. A backpack's wrapper keeps its slots in memory once it has been used and
     * writes them back over the saved contents at its next change, so a change must go through that wrapper,
     * never through the saved data: the backpack's own stack is looked for (inventory, cursor, ender chest,
     * Curios, then inside the backpacks found there); a backpack inside another item (a shulker box) has no
     * wrapper in use and gets one from its stored item.
     */
    @Nullable
    public static net.minecraftforge.items.IItemHandlerModifiable liveContents(ServerPlayer player, UUID uuid) {
        if (!present() || GET_WRAPPER_CAPABILITY == null || GET_INVENTORY_HANDLER == null) {
            return null;
        }
        java.util.ArrayDeque<ItemStack> todo = new java.util.ArrayDeque<>();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            todo.add(player.getInventory().getItem(i));
        }
        todo.add(player.containerMenu.getCarried());
        for (int i = 0; i < player.getEnderChestInventory().getContainerSize(); i++) {
            todo.add(player.getEnderChestInventory().getItem(i));
        }
        todo.addAll(Curios.liveItems(player));
        Set<UUID> opened = new java.util.HashSet<>();
        java.util.List<ItemStack> others = new java.util.ArrayList<>();
        while (!todo.isEmpty()) {
            ItemStack stack = todo.poll();
            if (stack.isEmpty()) {
                continue;
            }
            Optional<UUID> id = contentsUuid(stack);
            if (id.isEmpty()) {
                others.add(stack);
                continue;
            }
            net.minecraftforge.items.IItemHandlerModifiable handler = handler(stack);
            if (id.get().equals(uuid)) {
                return handler;
            }
            if (handler != null && opened.add(id.get())) {
                for (int i = 0; i < handler.getSlots(); i++) {
                    todo.add(handler.getStackInSlot(i));
                }
            }
        }
        for (ItemStack stack : others) {
            CompoundTag nested = stack.hasTag() ? nestedItem(stack.getTag(), uuid) : null;
            if (nested != null) {
                return handler(ItemStack.of(nested));
            }
        }
        return null;
    }

    /** The item compound inside a tag tree whose own tag has this contents id, or null. */
    @Nullable
    private static CompoundTag nestedItem(Tag tag, UUID uuid) {
        if (tag instanceof CompoundTag c) {
            if (c.contains("id", Tag.TAG_STRING) && c.getCompound("tag").hasUUID(CONTENTS_UUID) && c.getCompound("tag").getUUID(CONTENTS_UUID).equals(uuid)) {
                return c;
            }
            for (String key : c.getAllKeys()) {
                CompoundTag found = nestedItem(c.get(key), uuid);
                if (found != null) {
                    return found;
                }
            }
        } else if (tag instanceof ListTag list) {
            for (Tag t : list) {
                CompoundTag found = nestedItem(t, uuid);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Nullable
    private static net.minecraftforge.items.IItemHandlerModifiable handler(ItemStack stack) {
        Object cap = Reflect.call(GET_WRAPPER_CAPABILITY, null);
        if (!(cap instanceof net.minecraftforge.common.capabilities.Capability<?> capability)) {
            return null;
        }
        Optional<?> wrapper = stack.getCapability(capability).resolve();
        Object handler = wrapper.isPresent() ? Reflect.call(GET_INVENTORY_HANDLER, wrapper.get()) : null;
        return handler instanceof net.minecraftforge.items.IItemHandlerModifiable m ? m : null;
    }

    /**
     * How many of this item a live backpack slot holds (its stack upgrades counted), 0 when the slot refuses the
     * item (a filter or a memorised slot). Through the backpack's own {@code getStackLimit} when it has one.
     */
    public static int stackLimit(net.minecraftforge.items.IItemHandler handler, int slot, ItemStack stack) {
        if (!handler.isItemValid(slot, stack)) {
            return 0;
        }
        Method limit = Reflect.method(handler.getClass(), "getStackLimit", int.class, ItemStack.class);
        Object n = limit == null ? null : Reflect.call(limit, handler, slot, stack);
        if (n instanceof Integer i) {
            return i;
        }
        return Math.max(stack.getMaxStackSize(), handler.getSlotLimit(slot) / 64 * stack.getMaxStackSize());
    }

    private SophisticatedBackpacks() {}

    public static boolean present() {
        return ModList.get().isLoaded(MOD_ID) && GET_STORAGE != null && GET_CONTENTS != null && SET_CONTENTS != null;
    }

    /** The contents UUID of a backpack stack, if it has one. */
    public static Optional<UUID> contentsUuid(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.hasUUID(CONTENTS_UUID) ? Optional.of(tag.getUUID(CONTENTS_UUID)) : Optional.empty();
    }

    /** Every contentsUuid in a tag tree (a backpack's contents hold the backpacks inside it). */
    public static void collectUuids(Tag tag, Set<UUID> out) {
        if (tag instanceof CompoundTag c) {
            if (c.hasUUID(CONTENTS_UUID)) {
                out.add(c.getUUID(CONTENTS_UUID));
            }
            for (String key : c.getAllKeys()) {
                collectUuids(c.get(key), out);
            }
        } else if (tag instanceof ListTag list) {
            for (Tag t : list) {
                collectUuids(t, out);
            }
        }
    }

    @Nullable
    static CompoundTag contents(UUID uuid) {
        Object storage = Reflect.call(GET_STORAGE, null);
        Object got = Reflect.call(GET_CONTENTS, storage, uuid);
        if (got instanceof Optional<?> o && o.isPresent() && o.get() instanceof CompoundTag c) {
            return c;
        }
        return null;
    }

    static void setContents(UUID uuid, CompoundTag contents) {
        Object storage = Reflect.call(GET_STORAGE, null);
        Reflect.call(SET_CONTENTS, storage, uuid, contents);
    }

    /** The data module; registered only when the mod is loaded. Runs after the inventory modules. */
    public static final class Module implements DataModule {
        public static final String ID = "backpacks";
        private static final String PACKS = "Packs";
        private static final String UUID_KEY = "Uuid";
        private static final String CONTENTS = "Contents";

        @Override
        public String id() {
            return ID;
        }

        /** The backpacks the player carries, including those nested in other backpacks. */
        /**
         * Every backpack the player carries: in the inventory, on the cursor, in the ender chest and in a
         * Curios slot (the back slot), each looked for through the whole item tag, so a backpack inside a
         * shulker box or another container item is found too.
         */
        public static Set<UUID> carried(ServerPlayer player) {
            Set<UUID> uuids = new LinkedHashSet<>();
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                scan(player.getInventory().getItem(i), uuids);
            }
            scan(player.containerMenu.getCarried(), uuids);
            for (int i = 0; i < player.getEnderChestInventory().getContainerSize(); i++) {
                scan(player.getEnderChestInventory().getItem(i), uuids);
            }
            Tag curios = Curios.tag(player);
            if (curios != null) {
                collectUuids(curios, uuids);
            }
            return uuids;
        }

        private static void scan(ItemStack stack, Set<UUID> out) {
            if (!stack.isEmpty() && stack.hasTag()) {
                collectUuids(stack.getTag(), out);
            }
        }

        @Override
        public void save(ServerPlayer player, CompoundTag out) {
            out.put(PACKS, packs(carried(player)));
        }

        /**
         * The contents of every backpack in these stacks (searched through each whole item tag, backpacks inside
         * backpacks included), in the form {@link #load} reads: a grave carries them this way (D25). Empty without
         * the mod.
         */
        public static ListTag packsIn(java.util.Collection<ItemStack> stacks) {
            if (!present()) {
                return new ListTag();
            }
            Set<UUID> uuids = new LinkedHashSet<>();
            for (ItemStack s : stacks) {
                scan(s, uuids);
            }
            return packs(uuids);
        }

        /** Writes contents read by {@link #packsIn} into this server's backpack storage; how many. */
        public static int restorePacks(ListTag packs) {
            if (!present()) {
                return 0;
            }
            CompoundTag in = new CompoundTag();
            in.put(PACKS, packs);
            return restore(in);
        }

        private static ListTag packs(Set<UUID> start) {
            ListTag packs = new ListTag();
            Set<UUID> todo = new LinkedHashSet<>(start);
            Set<UUID> done = new LinkedHashSet<>();
            while (!todo.isEmpty()) {
                UUID uuid = todo.iterator().next();
                todo.remove(uuid);
                if (!done.add(uuid)) {
                    continue;
                }
                CompoundTag contents = contents(uuid);
                if (contents == null) {
                    continue;
                }
                CompoundTag entry = new CompoundTag();
                entry.putUUID(UUID_KEY, uuid);
                entry.put(CONTENTS, contents.copy());
                packs.add(entry);
                Set<UUID> nested = new LinkedHashSet<>();
                collectUuids(contents, nested);
                nested.removeAll(done);
                todo.addAll(nested);
            }
            return packs;
        }

        @Override
        public void load(ServerPlayer player, CompoundTag in) {
            int n = restore(in);
            if (n > 0) {
                BSPInventorySync.LOGGER.debug("BSP-InventorySync: {} backpack(s) restored for {}", n, player.getGameProfile().getName());
            }
        }

        private static int restore(CompoundTag in) {
            int n = 0;
            for (Tag t : in.getList(PACKS, Tag.TAG_COMPOUND)) {
                CompoundTag entry = (CompoundTag) t;
                if (!entry.hasUUID(UUID_KEY) || !entry.contains(CONTENTS, Tag.TAG_COMPOUND)) {
                    continue;
                }
                setContents(entry.getUUID(UUID_KEY), entry.getCompound(CONTENTS).copy());
                n++;
            }
            return n;
        }
    }
}
