package com.mrgregles.bsp_inventorysync.panel;

import com.mrgregles.bsp_inventorysync.api.Edit;
import com.mrgregles.bsp_inventorysync.api.EditResult;
import com.mrgregles.bsp_inventorysync.data.ItemCodec;
import com.mrgregles.bsp_inventorysync.give.Gifts;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Decides one {@link Edit} against an area's slots without changing them, so a live change can save the
 * state before it first (D29 and its audit): either a refusal, or the slots to write and a line describing
 * the change. A Shatter Totem is never added or taken, not even inside another item ({@code carriesTotem});
 * an add never brings a backpack's, a dank's or a storage link's id with it (two items would share one
 * inventory).
 */
final class Planner {
    private Planner() {}

    /** A refusal (nothing to write), or the writes. */
    record Plan(@Nullable EditResult.Outcome refused, String message, Map<Integer, ItemStack> writes) {
        static Plan refuse(EditResult.Outcome outcome, String message) {
            return new Plan(outcome, message, Map.of());
        }

        /**
         * Writes the slots; if one write fails (a mod's own handler threw), the slots already written get their
         * old stacks back and the failure is passed on.
         */
        void commit(SlotView view) {
            Map<Integer, ItemStack> before = new LinkedHashMap<>();
            try {
                for (Map.Entry<Integer, ItemStack> w : writes.entrySet()) {
                    before.put(w.getKey(), view.get(w.getKey()).copy());
                    view.set(w.getKey(), w.getValue());
                }
            } catch (RuntimeException e) {
                for (Map.Entry<Integer, ItemStack> b : before.entrySet()) {
                    try {
                        view.set(b.getKey(), b.getValue());
                    } catch (RuntimeException ignored) {
                        // reported with the first failure
                    }
                }
                throw e;
            }
        }
    }

    /** What a request must have before anything is looked up; null when it is well formed. */
    @Nullable
    static Plan malformed(@Nullable Edit edit) {
        if (edit == null || edit.area() == null) {
            return Plan.refuse(EditResult.Outcome.FAILED, "No change given.");
        }
        if (SnapshotItems.kind(edit.area()) == null) {
            return Plan.refuse(EditResult.Outcome.FAILED, "There is no area called " + edit.area() + ".");
        }
        if (SnapshotItems.kind(edit.area()) == com.mrgregles.bsp_inventorysync.api.ItemArea.Kind.GRAVE) {
            return Plan.refuse(EditResult.Outcome.REFUSED, "A grave is not part of a player's items: it is only read (graveItems) and returned whole (returnGrave).");
        }
        if (edit instanceof Edit.Remove r && r.expected() == null || edit instanceof Edit.Add a && a.stack() == null) {
            return Plan.refuse(EditResult.Outcome.FAILED, "The change names no item.");
        }
        return null;
    }

    static Plan plan(String areaId, @Nullable SlotView view, boolean addable, boolean removable, Edit edit, Predicate<ItemStack> carriesTotem) {
        Plan bad = malformed(edit);
        if (bad != null) {
            return bad;
        }
        if (view == null) {
            return Plan.refuse(EditResult.Outcome.CHANGED, "There is no " + areaId + " any more; read the player again.");
        }
        if (edit instanceof Edit.Remove r) {
            return remove(areaId, view, removable, r, carriesTotem);
        }
        return add(areaId, view, addable, (Edit.Add) edit, carriesTotem);
    }

    private static Plan remove(String areaId, SlotView view, boolean removable, Edit.Remove r, Predicate<ItemStack> carriesTotem) {
        if (r.slot() < 0 || r.slot() >= view.size()) {
            return Plan.refuse(EditResult.Outcome.FAILED, areaId + " has no slot " + r.slot() + ".");
        }
        if (r.count() < 1) {
            return Plan.refuse(EditResult.Outcome.FAILED, "Remove at least 1.");
        }
        if (!removable) {
            return Plan.refuse(EditResult.Outcome.REFUSED, areaId + " is a linked backpack: its items live in another backpack, so it is read-only here.");
        }
        ItemStack now = view.get(r.slot());
        if (!now.isEmpty() && carriesTotem.test(now)) {
            return Plan.refuse(EditResult.Outcome.REFUSED, "A Shatter Totem is locked (in this slot or inside its item): it can only be taken by BSP-Core's own rules.");
        }
        if (now.isEmpty() || !same(now, r.expected()) || now.getCount() < r.count()) {
            String held = now.isEmpty() ? "nothing" : now.getCount() + " " + name(now);
            return Plan.refuse(EditResult.Outcome.CHANGED, areaId + " slot " + r.slot() + " now holds " + held + "; read the player again.");
        }
        ItemStack after = now.getCount() == r.count() ? ItemStack.EMPTY : now.copyWithCount(now.getCount() - r.count());
        return new Plan(null, "-" + r.count() + " " + name(now) + " from " + areaId + " slot " + r.slot(), Map.of(r.slot(), after));
    }

    private static Plan add(String areaId, SlotView view, boolean addable, Edit.Add a, Predicate<ItemStack> carriesTotem) {
        ItemStack stack = a.stack();
        if (stack.isEmpty()) {
            return Plan.refuse(EditResult.Outcome.FAILED, "Nothing to add.");
        }
        if (!addable) {
            return Plan.refuse(EditResult.Outcome.REFUSED, "Items can only be removed from " + areaId + " here; add them to the inventory or the ender chest"
                    + " (a backpack or dank takes items only while its owner is online on the server making the change).");
        }
        if (ItemCodec.isPlaceholder(stack)) {
            return Plan.refuse(EditResult.Outcome.REFUSED, "A missing mod's item cannot be added.");
        }
        if (carriesTotem.test(stack)) {
            return Plan.refuse(EditResult.Outcome.REFUSED, "A Shatter Totem cannot be added, not even inside another item.");
        }
        if (SnapshotItems.carriesStorageId(stack)) {
            return Plan.refuse(EditResult.Outcome.REFUSED, "This item carries a backpack's or dank's contents id: two items would share one inventory. Add an empty one instead.");
        }
        String refusal = Gifts.refusal(stack, stack.getCount());
        if (refusal != null) {
            return Plan.refuse(EditResult.Outcome.REFUSED, refusal);
        }
        int left = stack.getCount();
        Map<Integer, ItemStack> writes = new LinkedHashMap<>();
        for (int pass = 0; pass < 2 && left > 0; pass++) {
            for (int i = 0; i < view.size() && left > 0; i++) {
                ItemStack now = view.get(i);
                boolean fits = pass == 0 ? !now.isEmpty() && same(now, stack) : now.isEmpty();
                if (!fits) {
                    continue;
                }
                int room = view.limit(i, stack) - now.getCount();
                if (room <= 0) {
                    continue;
                }
                int put = Math.min(room, left);
                writes.put(i, stack.copyWithCount(now.getCount() + put));
                left -= put;
            }
        }
        if (left > 0) {
            int fit = stack.getCount() - left;
            return Plan.refuse(EditResult.Outcome.NO_ROOM, "Room for only " + fit + " of " + stack.getCount() + " " + name(stack) + " in " + areaId + "; nothing was added.");
        }
        return new Plan(null, "+" + stack.getCount() + " " + name(stack) + " to " + areaId, writes);
    }

    /** The same item and tags, counts aside; a placeholder matches the same original item. */
    static boolean same(ItemStack a, ItemStack b) {
        if (ItemCodec.isPlaceholder(a) || ItemCodec.isPlaceholder(b)) {
            return ItemCodec.isPlaceholder(a) && ItemCodec.isPlaceholder(b) && original(a).equals(original(b));
        }
        return ItemStack.isSameItemSameTags(a, b);
    }

    private static CompoundTag original(ItemStack placeholder) {
        CompoundTag o = placeholder.getTag().getCompound(ItemCodec.ORIGINAL).copy();
        for (String key : new String[] {"Count", "Slot", "realCount", "ExtendedCount"}) {
            o.remove(key);
        }
        return o;
    }

    static String name(ItemStack stack) {
        return stack.getHoverName().getString();
    }
}
