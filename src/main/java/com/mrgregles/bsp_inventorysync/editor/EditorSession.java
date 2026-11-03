package com.mrgregles.bsp_inventorysync.editor;

import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One admin's edit of one player's inventory (D21, layout C of D22): a working copy of the player's
 * inventory (main, hotbar, armour, offhand) and ender chest, the item on the admin's cursor, and the
 * click rules. Nothing here touches the real player; {@link EditorActions#confirm} writes the copy
 * back after Save, then Confirm. Every item on the cursor or in the list is virtual: closing the
 * editor drops it, so nothing can be carried out of the editor.
 *
 * <p>The screen is a 6-row chest. Rows 1 to 5 (slots 0 to 44) show the open tab; row 6 (45 to 53) is
 * the toolbar: Inventory, Ender chest and Items tabs, then (on the Items tab) previous page, page,
 * next page, then Trash, Cancel and Save. The Inventory tab shows the main inventory in slots 0 to 26,
 * the hotbar in 27 to 35, then helmet, chestplate, leggings, boots and offhand in 36 to 40.
 */
public final class EditorSession {
    public enum Tab { INVENTORY, ENDER, ITEMS, CONFIRM }

    /** What a click asks the screen to do. */
    public enum Result { NONE, CLOSE, CONFIRM }

    public static final int SLOTS = 54, PAGE = 45, INVENTORY_SIZE = 41, ENDER_SIZE = 27;
    static final int T_INVENTORY = 0, T_ENDER = 1, T_ITEMS = 2, T_PREV = 3, T_PAGE = 4, T_NEXT = 5, T_TRASH = 6, T_CANCEL = 7, T_SAVE = 8;
    static final int C_BACK = 3, C_CANCEL = 5, C_CONFIRM = 8;
    private static final EquipmentSlot[] ARMOUR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final String[] ARMOUR_NAMES = {"Helmet", "Chestplate", "Leggings", "Boots", "Offhand"};

    private final UUID target;
    private final String targetName;
    private final List<ItemStack> inv = new ArrayList<>(), ender = new ArrayList<>();
    private final List<ItemStack> invBefore = new ArrayList<>(), enderBefore = new ArrayList<>();
    private final List<ItemCatalog.Category> categories;
    private Tab tab = Tab.INVENTORY;
    private Tab beforeConfirm = Tab.INVENTORY;
    private int category, page;
    private ItemStack cursor = ItemStack.EMPTY;
    private Diff diff = Diff.NONE;

    /** Item counts that differ between before and after, for the confirm screen and the log. */
    public record Diff(List<ItemStack> added, List<ItemStack> removed) {
        static final Diff NONE = new Diff(List.of(), List.of());

        public boolean isEmpty() {
            return added.isEmpty() && removed.isEmpty();
        }

        /** "+32 Diamond, -1 Diamond Sword" for the log and chat. */
        public String describe() {
            List<String> parts = new ArrayList<>();
            added.forEach(s -> parts.add("+" + s.getCount() + " " + s.getHoverName().getString()));
            removed.forEach(s -> parts.add("-" + s.getCount() + " " + s.getHoverName().getString()));
            return parts.isEmpty() ? "no changes" : String.join(", ", parts);
        }
    }

    /** A session over the player's inventory and ender chest as they are now. */
    public EditorSession(ServerPlayer player, List<ItemCatalog.Category> categories) {
        this(player.getUUID(), player.getGameProfile().getName(), copy(player.getInventory(), INVENTORY_SIZE), copy(player.getEnderChestInventory(), ENDER_SIZE), categories);
    }

    public EditorSession(UUID target, String targetName, List<ItemStack> inventory, List<ItemStack> enderChest, List<ItemCatalog.Category> categories) {
        this.target = target;
        this.targetName = targetName;
        for (int i = 0; i < INVENTORY_SIZE; i++) {
            ItemStack s = i < inventory.size() ? inventory.get(i) : ItemStack.EMPTY;
            inv.add(s.copy());
            invBefore.add(s.copy());
        }
        for (int i = 0; i < ENDER_SIZE; i++) {
            ItemStack s = i < enderChest.size() ? enderChest.get(i) : ItemStack.EMPTY;
            ender.add(s.copy());
            enderBefore.add(s.copy());
        }
        this.categories = categories.isEmpty() ? List.of(new ItemCatalog.Category("Items", List.of())) : categories;
    }

    private static List<ItemStack> copy(net.minecraft.world.Container c, int n) {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < n && i < c.getContainerSize(); i++) {
            out.add(c.getItem(i).copy());
        }
        return out;
    }

    public UUID target() {
        return target;
    }

    public String targetName() {
        return targetName;
    }

    public Tab tab() {
        return tab;
    }

    public ItemStack cursor() {
        return cursor;
    }

    public Diff diff() {
        return diff;
    }

    /** The edited inventory (41 slots, as {@code Inventory} numbers them) and ender chest; copies. */
    public List<ItemStack> inventory() {
        return inv.stream().map(ItemStack::copy).toList();
    }

    public List<ItemStack> enderChest() {
        return ender.stream().map(ItemStack::copy).toList();
    }

    /** Whether the player's real inventory and ender chest are still exactly as they were when the editor opened. */
    public boolean unchanged(List<ItemStack> inventoryNow, List<ItemStack> enderNow) {
        return same(invBefore, inventoryNow) && same(enderBefore, enderNow);
    }

    private static boolean same(List<ItemStack> a, List<ItemStack> b) {
        for (int i = 0; i < a.size(); i++) {
            if (!ItemStack.matches(a.get(i), i < b.size() ? b.get(i) : ItemStack.EMPTY)) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ clicks

    /**
     * A click on a slot of the top part (0 to 53); left ({@code button} 0) or right (1), with shift or
     * not. Anything else (the admin's own inventory, drags, number keys, throws) never reaches here.
     */
    public Result click(int slot, int button, boolean shift) {
        if (slot < 0 || slot >= SLOTS) {
            return Result.NONE;
        }
        if (tab == Tab.CONFIRM) {
            return clickConfirm(slot);
        }
        if (slot >= PAGE) {
            return clickToolbar(slot - PAGE);
        }
        switch (tab) {
            case INVENTORY -> {
                int i = inventoryIndex(slot);
                if (i >= 0 && !shift) {
                    clickEditable(inv, i, button, i >= 36 && i <= 39 ? armourFor(i) : null, i >= 36 && i <= 39 ? 1 : 64);
                }
            }
            case ENDER -> {
                if (slot < ENDER_SIZE && !shift) {
                    clickEditable(ender, slot, button, null, 64);
                }
            }
            case ITEMS -> clickList(slot, button, shift);
            default -> { }
        }
        return Result.NONE;
    }

    /** Inventory index for a slot of the Inventory tab, or -1. */
    static int inventoryIndex(int slot) {
        if (slot < 27) {
            return 9 + slot; // main
        }
        if (slot < 36) {
            return slot - 27; // hotbar
        }
        return switch (slot) {
            case 36 -> 39; // helmet
            case 37 -> 38; // chestplate
            case 38 -> 37; // leggings
            case 39 -> 36; // boots
            case 40 -> 40; // offhand
            default -> -1;
        };
    }

    private static EquipmentSlot armourFor(int index) {
        return ARMOUR[39 - index];
    }

    private void clickEditable(List<ItemStack> list, int i, int button, EquipmentSlot armour, int slotLimit) {
        ItemStack inSlot = list.get(i);
        if (armour != null && !cursor.isEmpty() && LivingEntity.getEquipmentSlotForItem(cursor) != armour) {
            return; // only what can be worn there
        }
        int limit = Math.min(slotLimit, (cursor.isEmpty() ? inSlot : cursor).getMaxStackSize());
        if (button == 1) {
            if (cursor.isEmpty()) {
                if (!inSlot.isEmpty()) {
                    cursor = inSlot.split((inSlot.getCount() + 1) / 2);
                    tidy(list, i);
                }
            } else if (inSlot.isEmpty()) {
                list.set(i, cursor.split(1));
            } else if (ItemStack.isSameItemSameTags(inSlot, cursor)) {
                if (inSlot.getCount() < limit) {
                    inSlot.grow(1);
                    cursor.shrink(1);
                }
            } else {
                swap(list, i, slotLimit);
            }
        } else {
            if (cursor.isEmpty()) {
                cursor = inSlot;
                list.set(i, ItemStack.EMPTY);
            } else if (inSlot.isEmpty()) {
                list.set(i, cursor.split(Math.min(cursor.getCount(), limit)));
            } else if (ItemStack.isSameItemSameTags(inSlot, cursor)) {
                int move = Math.min(cursor.getCount(), limit - inSlot.getCount());
                if (move > 0) {
                    inSlot.grow(move);
                    cursor.shrink(move);
                }
            } else {
                swap(list, i, slotLimit);
            }
        }
        if (cursor.isEmpty()) {
            cursor = ItemStack.EMPTY;
        }
    }

    private void swap(List<ItemStack> list, int i, int slotLimit) {
        if (cursor.getCount() <= slotLimit) {
            ItemStack was = list.get(i);
            list.set(i, cursor);
            cursor = was;
        }
    }

    private static void tidy(List<ItemStack> list, int i) {
        if (list.get(i).isEmpty()) {
            list.set(i, ItemStack.EMPTY);
        }
    }

    private void clickList(int slot, int button, boolean shift) {
        ItemStack entry = listEntry(slot);
        if (entry.isEmpty()) {
            return;
        }
        if (shift) {
            addToMain(entry.copyWithCount(entry.getMaxStackSize()));
            return;
        }
        if (!cursor.isEmpty() && !ItemStack.isSameItemSameTags(cursor, entry)) {
            return; // never replaces what is on the cursor: that may be an item taken from the player
        }
        if (button == 1) {
            if (cursor.isEmpty()) {
                cursor = entry.copyWithCount(1);
            } else if (cursor.getCount() < cursor.getMaxStackSize()) {
                cursor.grow(1);
            }
        } else {
            cursor = entry.copyWithCount(entry.getMaxStackSize());
        }
    }

    /** Into the main inventory and hotbar: onto matching stacks first, then the first empty slot; what does not fit is not added. */
    private void addToMain(ItemStack stack) {
        for (int i = 0; i < 36 && !stack.isEmpty(); i++) {
            ItemStack s = inv.get(i);
            if (!s.isEmpty() && ItemStack.isSameItemSameTags(s, stack) && s.getCount() < s.getMaxStackSize()) {
                int move = Math.min(stack.getCount(), s.getMaxStackSize() - s.getCount());
                s.grow(move);
                stack.shrink(move);
            }
        }
        for (int i = 0; i < 36 && !stack.isEmpty(); i++) {
            if (inv.get(i).isEmpty()) {
                inv.set(i, stack.copy());
                stack.setCount(0);
            }
        }
    }

    private ItemCatalog.Category currentCategory() {
        return categories.get(Math.floorMod(category, categories.size()));
    }

    private int pages() {
        return Math.max(1, (currentCategory().items().size() + PAGE - 1) / PAGE);
    }

    private ItemStack listEntry(int slot) {
        List<ItemStack> items = currentCategory().items();
        int i = page * PAGE + slot;
        return i >= 0 && i < items.size() ? items.get(i) : ItemStack.EMPTY;
    }

    private Result clickToolbar(int t) {
        switch (t) {
            case T_INVENTORY -> tab = Tab.INVENTORY;
            case T_ENDER -> tab = Tab.ENDER;
            case T_ITEMS -> {
                if (tab == Tab.ITEMS) {
                    category = Math.floorMod(category + 1, categories.size()); // again: the next creative tab
                    page = 0;
                }
                tab = Tab.ITEMS;
            }
            case T_PREV -> {
                if (tab == Tab.ITEMS && page > 0) {
                    page--;
                }
            }
            case T_NEXT -> {
                if (tab == Tab.ITEMS && page < pages() - 1) {
                    page++;
                }
            }
            case T_TRASH -> cursor = ItemStack.EMPTY;
            case T_CANCEL -> {
                return Result.CLOSE;
            }
            case T_SAVE -> {
                diff = computeDiff();
                beforeConfirm = tab;
                tab = Tab.CONFIRM;
            }
            default -> { }
        }
        return Result.NONE;
    }

    private Result clickConfirm(int slot) {
        if (slot < PAGE) {
            return Result.NONE;
        }
        return switch (slot - PAGE) {
            case C_BACK -> {
                tab = beforeConfirm;
                yield Result.NONE;
            }
            case C_CANCEL -> Result.CLOSE;
            case C_CONFIRM -> Result.CONFIRM;
            default -> Result.NONE;
        };
    }

    // ------------------------------------------------------------------ the difference

    /** What Save would change: items whose total count (inventory and ender chest together) went up or down. */
    public Diff computeDiff() {
        List<ItemStack> before = totals(invBefore, enderBefore);
        List<ItemStack> after = totals(inv, ender);
        List<ItemStack> added = new ArrayList<>(), removed = new ArrayList<>();
        for (ItemStack a : after) {
            int was = count(before, a);
            if (a.getCount() > was) {
                added.add(a.copyWithCount(a.getCount() - was));
            }
        }
        for (ItemStack b : before) {
            int now = count(after, b);
            if (b.getCount() > now) {
                removed.add(b.copyWithCount(b.getCount() - now));
            }
        }
        return new Diff(added, removed);
    }

    private static List<ItemStack> totals(List<ItemStack> a, List<ItemStack> b) {
        List<ItemStack> out = new ArrayList<>();
        for (List<ItemStack> list : List.of(a, b)) {
            for (ItemStack s : list) {
                if (s.isEmpty()) {
                    continue;
                }
                ItemStack match = out.stream().filter(o -> ItemStack.isSameItemSameTags(o, s)).findFirst().orElse(null);
                if (match == null) {
                    out.add(s.copy());
                } else {
                    match.grow(s.getCount()); // counts above 64 are fine here: these stacks are never placed
                }
            }
        }
        return out;
    }

    private static int count(List<ItemStack> totals, ItemStack key) {
        return totals.stream().filter(o -> ItemStack.isSameItemSameTags(o, key)).mapToInt(ItemStack::getCount).findFirst().orElse(0);
    }

    // ------------------------------------------------------------------ what the screen shows

    /** The stack shown in a slot of the top part for the current tab. */
    public ItemStack display(int slot) {
        if (tab == Tab.CONFIRM) {
            return confirmDisplay(slot);
        }
        if (slot >= PAGE) {
            return toolbar(slot - PAGE);
        }
        return switch (tab) {
            case INVENTORY -> {
                int i = inventoryIndex(slot);
                if (i < 0) {
                    yield filler();
                }
                ItemStack s = inv.get(i);
                yield s.isEmpty() && i >= 36 ? icon(Items.GRAY_STAINED_GLASS_PANE, ARMOUR_NAMES[slot - 36]) : s.copy();
            }
            case ENDER -> slot < ENDER_SIZE ? ender.get(slot).copy() : filler();
            case ITEMS -> listEntry(slot).copy();
            default -> ItemStack.EMPTY;
        };
    }

    private ItemStack toolbar(int t) {
        boolean items = tab == Tab.ITEMS;
        return switch (t) {
            case T_INVENTORY -> icon(Items.CHEST, (tab == Tab.INVENTORY ? "> " : "") + "Inventory");
            case T_ENDER -> icon(Items.ENDER_CHEST, (tab == Tab.ENDER ? "> " : "") + "Ender chest");
            case T_ITEMS -> icon(Items.COMPASS, (items ? "> " : "") + "Items: " + currentCategory().name(), items ? "Click again for the next creative tab." : "Take items from the creative list.");
            case T_PREV -> items ? icon(Items.ARROW, "Previous page") : filler();
            case T_PAGE -> items ? icon(Items.PAPER, "Page " + (page + 1) + " of " + pages()) : filler();
            case T_NEXT -> items ? icon(Items.ARROW, "Next page") : filler();
            case T_TRASH -> icon(Items.CAULDRON, "Trash", "Click with an item to delete it.");
            case T_CANCEL -> icon(Items.RED_CONCRETE, "Cancel", "Close without changing anything.");
            case T_SAVE -> icon(Items.LIME_CONCRETE, "Save", "Review the changes before they are made.");
            default -> filler();
        };
    }

    private ItemStack confirmDisplay(int slot) {
        if (slot >= PAGE) {
            return switch (slot - PAGE) {
                case C_BACK -> icon(Items.YELLOW_CONCRETE, "Back", "Return to the editor; your changes stay.");
                case C_CANCEL -> icon(Items.RED_CONCRETE, "Cancel", "Close without changing anything.");
                case C_CONFIRM -> icon(Items.LIME_CONCRETE, "Confirm", diff.isEmpty() ? "Nothing to change." : "Make these changes to " + targetName + ".");
                default -> filler();
            };
        }
        if (slot == 0) {
            return icon(Items.LIME_STAINED_GLASS_PANE, "Added");
        }
        if (slot == 9) {
            return icon(Items.RED_STAINED_GLASS_PANE, "Removed");
        }
        if (slot >= 1 && slot <= 8) {
            return entry(diff.added(), slot - 1);
        }
        if (slot >= 10 && slot <= 17) {
            return entry(diff.removed(), slot - 10);
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack entry(List<ItemStack> list, int i) {
        if (i == 7 && list.size() > 8) {
            return icon(Items.PAPER, "and " + (list.size() - 7) + " more");
        }
        if (i >= list.size()) {
            return ItemStack.EMPTY;
        }
        ItemStack s = list.get(i);
        ItemStack shown = s.copyWithCount(Math.min(s.getCount(), s.getMaxStackSize()));
        if (s.getCount() > s.getMaxStackSize()) {
            shown.setHoverName(Component.literal(s.getHoverName().getString() + " x" + s.getCount()).withStyle(st -> st.withItalic(false)));
        }
        return shown;
    }

    private static ItemStack filler() {
        return icon(Items.BLACK_STAINED_GLASS_PANE, " ");
    }

    /** A button or label: a named item with optional grey lore lines. */
    static ItemStack icon(Item item, String name, String... lore) {
        ItemStack s = new ItemStack(item);
        s.setHoverName(Component.literal(name).withStyle(st -> st.withItalic(false)));
        if (lore.length > 0) {
            ListTag lines = new ListTag();
            for (String l : lore) {
                lines.add(StringTag.valueOf(Component.Serializer.toJson(Component.literal(l).withStyle(st -> st.withItalic(false).withColor(net.minecraft.ChatFormatting.GRAY)))));
            }
            s.getOrCreateTagElement("display").put("Lore", lines);
        }
        return s;
    }
}
