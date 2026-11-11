package com.mrgregles.bsp_inventorysync.compat;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compact Machines 6.0.3 (D31, BSP-Mod Fixes' request R-3): a bound machine item names its room by chunk position alone
 * (the int array {@value #ROOM} in the item's tag, written by {@code CompactMachineItem.setRoom}), and every server
 * numbers its rooms alike, so the same item placed on another server opens that server's room at the same position.
 * This mod marks each bound machine with the server its room is on ({@value #HOME}, BSP-Core's server id) and refuses
 * to place it anywhere else ({@link com.mrgregles.bsp_inventorysync.machine.MachineGuard}). Unbound machines (no {@value #ROOM}) are never marked or refused.
 *
 * <p>Nothing here calls Compact Machines: the items are recognised by id and the room by its tag, so this works the
 * same whether or not the mod is installed (without it no item has these ids). {@value #ROOM} is never changed.
 */
public final class CompactMachines {
    public static final String MOD_ID = "compactmachines";
    /** Compact Machines' own tag: the room's chunk x and z. Present means bound (its own check is {@code contains}). */
    public static final String ROOM = "room_pos";
    /** This mod's tag beside it: the id of the server the room is on. */
    public static final String HOME = BSPInventorySync.MODID + ":home";

    /** The six machine items of Compact Machines 6.0.3 (its {@code Machines} registry). */
    public static final Set<String> MACHINE_IDS = Set.of(
            "compactmachines:machine_tiny", "compactmachines:machine_small", "compactmachines:machine_normal",
            "compactmachines:machine_large", "compactmachines:machine_giant", "compactmachines:machine_maximum");

    /** Item ids the GameTests treat as machines (the dev run has no Compact Machines). Empty in play. */
    private static final Set<String> TEST_IDS = ConcurrentHashMap.newKeySet();
    /** GameTests only: whether to act as if Compact Machines were loaded ({@code null}: as it really is). */
    @Nullable
    private static volatile Boolean loadedForTests;

    private CompactMachines() {}

    /** GameTests only: treat this item id as a machine item, or stop doing so. */
    public static void treatAsMachineForTests(String id, boolean on) {
        if (on) {
            TEST_IDS.add(id);
        } else {
            TEST_IDS.remove(id);
        }
    }

    /** GameTests only: act as if Compact Machines were (or were not) loaded; {@code null} to stop. */
    public static void loadedForTests(@Nullable Boolean loaded) {
        loadedForTests = loaded;
    }

    /** Whether Compact Machines is installed here: only then can a room be here, so only then does this server mark with its own id. */
    public static boolean loaded() {
        Boolean forTests = loadedForTests;
        if (forTests != null) {
            return forTests;
        }
        return net.minecraftforge.fml.ModList.get() != null && net.minecraftforge.fml.ModList.get().isLoaded(MOD_ID);
    }

    /**
     * The id this server marks machines with: its own id where Compact Machines is installed, else empty (a server without
     * the mod holds no room, so it never claims one; AUD-8 4 a). A restore still marks with the snapshot's server.
     */
    public static String localMark(@Nullable String serverId) {
        return serverId == null || !loaded() ? "" : serverId;
    }

    public static boolean isMachineId(String id) {
        return MACHINE_IDS.contains(id) || (!TEST_IDS.isEmpty() && TEST_IDS.contains(id));
    }

    public static boolean isMachine(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return key != null && isMachineId(key.toString());
    }

    /** A machine item bound to a room. */
    public static boolean isBound(ItemStack stack) {
        return isMachine(stack) && stack.hasTag() && stack.getTag().contains(ROOM);
    }

    /** The server a bound machine's room is on, when it is marked. */
    public static Optional<String> home(ItemStack stack) {
        if (!isBound(stack)) {
            return Optional.empty();
        }
        String home = stack.getTag().getString(HOME);
        return home.isEmpty() ? Optional.empty() : Optional.of(home);
    }

    /**
     * Whether this stack is a bound machine marked for another server than {@code localServer} (ids compared ignoring
     * case, as everywhere in the network). An unmarked one counts as local (D31 5 a); with no local id (sync off, single
     * player) nothing is away from home.
     */
    public static boolean awayFromHome(ItemStack stack, @Nullable String localServer) {
        if (localServer == null || localServer.isEmpty()) {
            return false;
        }
        Optional<String> home = home(stack);
        return home.isPresent() && !home.get().equalsIgnoreCase(localServer);
    }

    /**
     * Marks with {@code server} the stack, if it is an unmarked bound machine, and every unmarked bound machine stored
     * inside it at any depth (a shulker box's or a bundle's contents, anything that keeps items in its own tag). A mark
     * already there is never changed. Returns how many were marked.
     */
    public static int mark(ItemStack stack, @Nullable String server) {
        if (server == null || server.isEmpty() || stack.isEmpty()) {
            return 0;
        }
        int marked = 0;
        if (isBound(stack) && stack.getTag().getString(HOME).isEmpty()) {
            stack.getTag().putString(HOME, server);
            marked++;
        }
        if (stack.hasTag()) {
            marked += markInside(stack.getTag(), server, 0);
        }
        return marked;
    }

    /**
     * Marks with {@code server} every unmarked bound machine item compound ({@code id} a machine, {@code tag} holding
     * {@value #ROOM}) anywhere in this tree: a snapshot, a grave's contents, a backpack's saved contents. A mark already
     * there is never changed. Returns how many were marked.
     */
    public static int markTree(@Nullable Tag tree, @Nullable String server) {
        if (tree == null || server == null || server.isEmpty()) {
            return 0;
        }
        return walk(tree, server, 0);
    }

    /** Sets the home of a bound machine (the admin fix, D31 9 a); false for anything else. */
    public static boolean setHome(ItemStack stack, String server) {
        if (!isBound(stack) || server.isEmpty()) {
            return false;
        }
        stack.getTag().putString(HOME, server);
        return true;
    }

    /** Removes the home mark of a machine; true if it had one. An unmarked bound machine then counts as local again. */
    public static boolean clearHome(ItemStack stack) {
        if (!isMachine(stack) || !stack.hasTag() || !stack.getTag().contains(HOME)) {
            return false;
        }
        stack.getTag().remove(HOME);
        return true;
    }

    private static final int MAX_DEPTH = 64; // nesting far beyond any real container, against a malformed tag

    private static int markInside(CompoundTag itemTag, String server, int depth) {
        int marked = 0;
        for (String key : itemTag.getAllKeys()) {
            if (!key.equals(ROOM) && !key.equals(HOME)) {
                marked += walk(itemTag.get(key), server, depth + 1);
            }
        }
        return marked;
    }

    private static int walk(@Nullable Tag tag, String server, int depth) {
        if (tag == null || depth > MAX_DEPTH) {
            return 0;
        }
        int marked = 0;
        if (tag instanceof CompoundTag c) {
            if (c.contains("id", Tag.TAG_STRING) && isMachineId(c.getString("id")) && c.contains("tag", Tag.TAG_COMPOUND)) {
                CompoundTag itemTag = c.getCompound("tag");
                if (itemTag.contains(ROOM) && itemTag.getString(HOME).isEmpty()) {
                    itemTag.putString(HOME, server);
                    marked++;
                }
            }
            for (String key : c.getAllKeys()) {
                Tag child = c.get(key);
                if (child instanceof CompoundTag || child instanceof ListTag) {
                    marked += walk(child, server, depth + 1);
                }
            }
        } else if (tag instanceof ListTag list) {
            if (list.getElementType() == Tag.TAG_COMPOUND || list.getElementType() == Tag.TAG_LIST) {
                for (Tag child : list) {
                    marked += walk(child, server, depth + 1);
                }
            }
        }
        return marked;
    }
}
