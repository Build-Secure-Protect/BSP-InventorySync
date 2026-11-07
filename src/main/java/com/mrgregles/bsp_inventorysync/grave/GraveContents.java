package com.mrgregles.bsp_inventorysync.grave;

import com.mrgregles.bsp_inventorysync.data.ItemCodec;
import com.mrgregles.bsp_inventorysync.data.PlayerSnapshotCodec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * What a grave holds, as stored ({@code Grave.data}): GZIP NBT, like a snapshot.
 *
 * <pre>
 * { Format: 1, Items: [ { Slot: "inv:12", Item: {...} }, ... ], Extras: { Packs: [...], Danks: [...] } }
 * </pre>
 *
 * Each stack keeps the slot it was in when the player died ({@code inv:0} to {@code inv:40}: the hotbar, the
 * main rows, the armour and the offhand, as vanilla numbers them), so Take all can put it back there; an empty
 * slot name means it came from somewhere else (a Curios slot, another mod's drops). Items are written with
 * {@link ItemCodec}, so an item whose mod a server lacks survives as a placeholder. {@code Extras} carries what
 * lives outside the items: backpack contents and dank contents, read on the server where the player died.
 */
public final class GraveContents {
    public static final int FORMAT = 1;
    public static final String INV = "inv:";
    private static final String KEY_FORMAT = "Format", ITEMS = "Items", SLOT = "Slot", ITEM = "Item", EXTRAS = "Extras";

    /** One stack and where it came from. */
    public record Entry(String slot, ItemStack stack) {}

    private final List<Entry> entries;
    private final CompoundTag extras;

    public GraveContents(List<Entry> entries, CompoundTag extras) {
        this.entries = List.copyOf(entries);
        this.extras = extras;
    }

    public List<Entry> entries() {
        return entries;
    }

    public CompoundTag extras() {
        return extras;
    }

    public int stacks() {
        return entries.size();
    }

    /** The slot name for an index of the player's inventory. */
    public static String invSlot(int index) {
        return INV + index;
    }

    /** The inventory index a slot name stands for, or -1 when it is not one. */
    public static int invIndex(String slot) {
        if (!slot.startsWith(INV)) {
            return -1;
        }
        try {
            int i = Integer.parseInt(slot.substring(INV.length()));
            return i >= 0 && i <= 40 ? i : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public byte[] encode() {
        CompoundTag root = new CompoundTag();
        root.putInt(KEY_FORMAT, FORMAT);
        ListTag items = new ListTag();
        for (Entry e : entries) {
            if (e.stack().isEmpty()) {
                continue;
            }
            CompoundTag t = new CompoundTag();
            t.putString(SLOT, e.slot());
            t.put(ITEM, ItemCodec.save(e.stack()));
            items.add(t);
        }
        root.put(ITEMS, items);
        root.put(EXTRAS, extras.copy());
        return PlayerSnapshotCodec.encode(root);
    }

    public static GraveContents decode(byte[] data) {
        CompoundTag root = PlayerSnapshotCodec.decode(data);
        List<Entry> entries = new ArrayList<>();
        for (Tag t : root.getList(ITEMS, Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            ItemStack stack = ItemCodec.load(c.getCompound(ITEM));
            if (!stack.isEmpty()) {
                entries.add(new Entry(c.getString(SLOT), stack));
            }
        }
        return new GraveContents(entries, root.getCompound(EXTRAS));
    }
}
