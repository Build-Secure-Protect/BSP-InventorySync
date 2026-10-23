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

    static {
        Class<?> storage = Reflect.type("net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackStorage");
        GET_STORAGE = Reflect.method(storage, "get");
        GET_CONTENTS = Reflect.method(storage, "getBackpackContents", UUID.class);
        SET_CONTENTS = Reflect.method(storage, "setBackpackContents", UUID.class, CompoundTag.class);
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
            ListTag packs = new ListTag();
            Set<UUID> todo = new LinkedHashSet<>(carried(player));
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
            out.put(PACKS, packs);
        }

        @Override
        public void load(ServerPlayer player, CompoundTag in) {
            int n = 0;
            for (Tag t : in.getList(PACKS, Tag.TAG_COMPOUND)) {
                CompoundTag entry = (CompoundTag) t;
                if (!entry.hasUUID(UUID_KEY) || !entry.contains(CONTENTS, Tag.TAG_COMPOUND)) {
                    continue;
                }
                setContents(entry.getUUID(UUID_KEY), entry.getCompound(CONTENTS).copy());
                n++;
            }
            if (n > 0) {
                BSPInventorySync.LOGGER.debug("BSP-InventorySync: {} backpack(s) restored for {}", n, player.getGameProfile().getName());
            }
        }
    }
}
