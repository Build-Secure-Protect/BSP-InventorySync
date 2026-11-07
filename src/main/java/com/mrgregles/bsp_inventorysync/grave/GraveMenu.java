package com.mrgregles.bsp_inventorysync.grave;

import com.mrgregles.bsp_inventorysync.storage.Grave;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The grave screen (D25 2b, 23a): a vanilla six-row chest, so nothing is drawn by the client. The top five rows show
 * the grave laid out like the inventory it came from: the main rows, the hotbar row, then helmet, chestplate,
 * leggings, boots and offhand; stacks from nowhere in the inventory (Curios, other mods' drops) fill the free places,
 * and more pages follow if a grave ever holds more than fits. The bottom row has Close (red) on the left, an info
 * paper in the middle and Take all (lime) on the right, with arrows when there are pages.
 *
 * <p>The grave's stacks are pictures: a click on one takes it (into the slot it came from when that is free, else into
 * the main inventory), Take all takes everything that fits, and nothing can be put in. The player's own inventory
 * below works as usual, so they can make room right there. While a take is being written, clicks on the grave wait.
 */
public final class GraveMenu extends ChestMenu {
    public static final int ROWS = 6, SLOTS = 54, ITEMS = 45, PER_PAGE = 45;
    public static final int CLOSE = 45, PREV = 46, INFO = 49, NEXT = 52, TAKE_ALL = 53;

    private final ServerPlayer player;
    private final GraveAccess access;
    private final SimpleContainer view;
    /** Where it was opened from (a grave block), or null (a command); the screen closes when the player walks away from it. */
    @Nullable private final ResourceKey<Level> blockLevel;
    @Nullable private final BlockPos blockPos;
    private Grave grave;
    private GraveContents contents;
    private int page;
    private boolean busy, finished;
    /** Menu slot to entry index, for the page shown. */
    private final Map<Integer, Integer> shown = new HashMap<>();

    GraveMenu(int id, ServerPlayer player, GraveAccess access, Grave grave, @Nullable ResourceKey<Level> blockLevel, @Nullable BlockPos blockPos) {
        this(id, player, access, grave, blockLevel, blockPos, new SimpleContainer(SLOTS));
    }

    private GraveMenu(int id, ServerPlayer player, GraveAccess access, Grave grave, @Nullable ResourceKey<Level> blockLevel, @Nullable BlockPos blockPos, SimpleContainer view) {
        super(MenuType.GENERIC_9x6, id, player.getInventory(), view, ROWS);
        this.player = player;
        this.access = access;
        this.view = view;
        this.blockLevel = blockLevel;
        this.blockPos = blockPos;
        this.grave = grave;
        this.contents = GraveContents.decode(grave.data());
        com.mrgregles.bsp_inventorysync.PictureSlots.lock(this, SLOTS);
        draw();
    }

    public Grave grave() {
        return grave;
    }

    public GraveContents contents() {
        return contents;
    }

    public ServerPlayer player() {
        return player;
    }

    public boolean busy() {
        return busy;
    }

    void setBusy(boolean b) {
        busy = b;
    }

    /** The entry shown in a menu slot of this page, or -1. */
    public int entryAt(int slot) {
        return shown.getOrDefault(slot, -1);
    }

    public int page() {
        return page;
    }

    public int pages() {
        return Math.max(1, layout(contents.entries()).values().stream().mapToInt(p -> p[0]).max().orElse(0) + 1);
    }

    /** Shows the grave as it now is (after a take here or by someone else). */
    void show(Grave now) {
        grave = now;
        contents = GraveContents.decode(now.data());
        page = Math.min(page, pages() - 1);
        draw();
        sendAllDataToRemote();
    }

    /** The grave is gone (emptied): the screen closes. */
    void finish() {
        finished = true;
        if (player.containerMenu == this) {
            player.closeContainer();
        }
    }

    // ------------------------------------------------------------------ layout

    /** The place for an inventory slot on the first page, or -1: main rows on top, the hotbar under them, then armour and offhand. */
    static int home(int invIndex) {
        if (invIndex >= 9 && invIndex <= 35) {
            return invIndex - 9;
        }
        if (invIndex >= 0 && invIndex <= 8) {
            return 27 + invIndex;
        }
        return switch (invIndex) {
            case 39 -> 36; // helmet
            case 38 -> 37;
            case 37 -> 38;
            case 36 -> 39; // boots
            case 40 -> 40; // offhand
            default -> -1;
        };
    }

    /** Entry index to {page, slot}: each stack at its home on page 1 when that is free, the rest in the free places, page by page. */
    static Map<Integer, int[]> layout(List<GraveContents.Entry> entries) {
        Map<Integer, int[]> out = new HashMap<>();
        boolean[] firstPage = new boolean[PER_PAGE];
        List<Integer> homeless = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            int h = home(GraveContents.invIndex(entries.get(i).slot()));
            if (h >= 0 && !firstPage[h]) {
                firstPage[h] = true;
                out.put(i, new int[] {0, h});
            } else {
                homeless.add(i);
            }
        }
        int p = 0, s = 0;
        for (int i : homeless) {
            while (p == 0 && s < PER_PAGE && firstPage[s]) {
                s++;
            }
            if (s >= PER_PAGE) {
                p++;
                s = 0;
            }
            out.put(i, new int[] {p, s});
            s++;
        }
        return out;
    }

    private void draw() {
        shown.clear();
        for (int i = 0; i < ITEMS; i++) {
            view.setItem(i, ItemStack.EMPTY);
        }
        for (Map.Entry<Integer, int[]> e : layout(contents.entries()).entrySet()) {
            if (e.getValue()[0] == page) {
                shown.put(e.getValue()[1], e.getKey());
                view.setItem(e.getValue()[1], contents.entries().get(e.getKey()).stack().copy());
            }
        }
        for (int i = ITEMS; i < SLOTS; i++) {
            view.setItem(i, icon(Items.BLACK_STAINED_GLASS_PANE, " "));
        }
        view.setItem(CLOSE, icon(Items.RED_CONCRETE, "Close"));
        view.setItem(TAKE_ALL, icon(Items.LIME_CONCRETE, "Take all", "Everything that fits, back where it was"));
        int pages = pages();
        if (pages > 1) {
            if (page > 0) {
                view.setItem(PREV, icon(Items.ARROW, "Page " + page));
            }
            if (page < pages - 1) {
                view.setItem(NEXT, icon(Items.ARROW, "Page " + (page + 2)));
            }
        }
        view.setItem(INFO, icon(Items.PAPER, grave.ownerName() + "'s grave",
                (grave.serverId().isEmpty() ? "" : grave.serverId() + ", ") + ago(System.currentTimeMillis() - grave.diedAt()) + ".",
                contents.stacks() + (contents.stacks() == 1 ? " stack." : " stacks.") + (pages > 1 ? " Page " + (page + 1) + " of " + pages + "." : ""),
                "Click a stack to take it, or Take all."));
    }

    /** "just now", "12 min ago", "2 h ago", "3 days ago". */
    static String ago(long ms) {
        long min = Math.max(0, ms) / 60_000;
        if (min < 1) {
            return "just now";
        }
        if (min < 60) {
            return min + " min ago";
        }
        long h = min / 60;
        if (h < 48) {
            return h + " h ago";
        }
        return (h / 24) + " days ago";
    }

    static ItemStack icon(Item item, String name, String... lore) {
        ItemStack s = new ItemStack(item);
        s.setHoverName(Component.literal(name).withStyle(st -> st.withItalic(false)));
        if (lore.length > 0) {
            ListTag lines = new ListTag();
            for (String line : lore) {
                lines.add(StringTag.valueOf(Component.Serializer.toJson(Component.literal(line).withStyle(ChatFormatting.GRAY).withStyle(st -> st.withItalic(false)))));
            }
            s.getOrCreateTagElement("display").put("Lore", lines);
        }
        return s;
    }

    // ------------------------------------------------------------------ clicks

    @Override
    public void clicked(int slotId, int button, ClickType type, Player who) {
        if (slotId >= 0 && slotId < SLOTS) {
            if (!busy && !finished && (type == ClickType.PICKUP || type == ClickType.QUICK_MOVE) && (button == 0 || button == 1)) {
                int entry = entryAt(slotId);
                if (slotId < ITEMS && entry >= 0) {
                    access.take(this, List.of(entry));
                } else if (slotId == TAKE_ALL) {
                    List<Integer> all = new ArrayList<>();
                    for (int i = 0; i < contents.entries().size(); i++) {
                        all.add(i);
                    }
                    access.take(this, all);
                } else if (slotId == CLOSE) {
                    player.closeContainer();
                    return;
                } else if (slotId == PREV && page > 0) {
                    page--;
                } else if (slotId == NEXT && page < pages() - 1) {
                    page++;
                }
            }
            if (!finished && player.containerMenu == this) {
                draw();
                sendAllDataToRemote(); // the client may have predicted a move in the top part
            }
            return;
        }
        super.clicked(slotId, button, type, who); // the player's own inventory, and drops outside the screen
    }

    @Override
    public ItemStack quickMoveStack(Player who, int index) {
        return ItemStack.EMPTY; // shift-click never moves anything into the grave
    }

    @Override
    public boolean canDragTo(Slot slot) {
        return slot.container != view;
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
        return slot.container != view;
    }

    /** Opened at a grave block: only while the player stays near it (8 blocks), in its world. */
    @Override
    public boolean stillValid(Player who) {
        if (blockPos == null) {
            return true;
        }
        return who.level().dimension() == blockLevel && who.distanceToSqr(blockPos.getX() + 0.5, blockPos.getY() + 0.5, blockPos.getZ() + 0.5) <= 64.0;
    }

    @Override
    public void removed(Player who) {
        super.removed(who);
        access.closed(this);
    }
}
