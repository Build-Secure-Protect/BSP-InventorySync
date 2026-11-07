package com.mrgregles.bsp_inventorysync.give;

import com.mrgregles.bsp_inventorysync.storage.Give;
import com.mrgregles.bsp_inventorysync.sync.Sync;
import com.mrgregles.bsp_inventorysync.sync.SyncService;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
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

import java.util.List;

/**
 * The waiting-items popup (D24, layout B): a vanilla three-row chest, so nothing is installed on the
 * client. The top two rows show up to {@link GiveService#SHOWN} waiting stacks (a paper "+N more" in the
 * last one when there are more); the bottom row has Cancel (red concrete) on the left, an info paper in the
 * middle saying how many free slots are still needed, and Deposit (lime concrete) on the right.
 *
 * <p>The waiting stacks are only pictures: nothing can be taken from or put into the top part, which is
 * sent again after every click on it. The player's own inventory below works as usual, so they can make room
 * right there (move items, drop them with Q or by dragging out of the screen); shift-clicking into the top
 * does nothing. Deposit hands over what fits and keeps the popup open with the rest; when everything is in it
 * closes. Cancel, Esc or any other close with stacks left is a Cancel (asked again at the next join), except a
 * logout or a server switch, which leave everything as it was.
 */
public final class WaitingMenu extends ChestMenu {
    public static final int ROWS = 3, SLOTS = 27;
    public static final int CANCEL = 18, INFO = 22, DEPOSIT = 26;

    private final ServerPlayer player;
    private final GiveService service;
    private final SimpleContainer view;
    private List<Give> rows;
    private boolean depositing, finished;
    /** Closed as a Cancel while a Deposit was still being answered: the Cancel is decided when it has been, on what is left. */
    private boolean cancelAfterDeposit;
    private int ticks;

    WaitingMenu(int id, ServerPlayer player, GiveService service, List<Give> rows) {
        this(id, player, service, rows, new SimpleContainer(SLOTS));
    }

    private WaitingMenu(int id, ServerPlayer player, GiveService service, List<Give> rows, SimpleContainer view) {
        super(MenuType.GENERIC_9x3, id, player.getInventory(), view, ROWS);
        this.player = player;
        this.service = service;
        this.view = view;
        this.rows = List.copyOf(rows);
        draw();
    }

    /** The stacks still waiting in this popup (tests). */
    public List<Give> rows() {
        return rows;
    }

    @Override
    public void clicked(int slotId, int button, ClickType type, Player who) {
        if (slotId >= 0 && slotId < SLOTS) {
            if (type == ClickType.PICKUP && (button == 0 || button == 1)) {
                if (slotId == DEPOSIT) {
                    deposit();
                } else if (slotId == CANCEL) {
                    player.closeContainer();
                    return;
                }
            }
            if (!finished) {
                draw();
                sendAllDataToRemote(); // the client may have predicted a move in the top part
            }
            return;
        }
        super.clicked(slotId, button, type, who); // the player's own inventory, and drops outside the screen
    }

    private void deposit() {
        if (depositing) {
            return;
        }
        depositing = true;
        service.deposit(player, rows).whenComplete((left, error) -> player.server.execute(() -> {
            depositing = false;
            if (left != null) {
                rows = List.copyOf(left);
            }
            if (cancelAfterDeposit) {
                finished = true;
                if (!rows.isEmpty() && !player.hasDisconnected()) {
                    service.decline(player, rows.size());
                }
                return;
            }
            if (rows.isEmpty()) {
                finished = true;
                if (player.containerMenu == this) {
                    player.closeContainer();
                }
            } else if (player.containerMenu == this) {
                draw();
                sendAllDataToRemote();
            }
        }));
    }

    private void draw() {
        int shown = Math.min(rows.size(), GiveService.SHOWN);
        for (int i = 0; i < GiveService.SHOWN; i++) {
            ItemStack s = ItemStack.EMPTY;
            if (i < shown) {
                s = rows.size() > GiveService.SHOWN && i == GiveService.SHOWN - 1
                        ? icon(Items.PAPER, "+" + (rows.size() - GiveService.SHOWN + 1) + " more waiting")
                        : GiveService.decode(rows.get(i));
            }
            view.setItem(i, s);
        }
        for (int i = GiveService.SHOWN; i < SLOTS; i++) {
            view.setItem(i, icon(Items.BLACK_STAINED_GLASS_PANE, " "));
        }
        view.setItem(CANCEL, icon(Items.RED_CONCRETE, "Cancel", "Ask again at my next login"));
        view.setItem(DEPOSIT, icon(Items.LIME_CONCRETE, "Deposit", "Put what fits into my inventory"));
        drawInfo();
    }

    private void drawInfo() {
        int missing = Room.missing(player, rows.stream().map(GiveService::decode).toList());
        view.setItem(INFO, missing == 0
                ? icon(Items.PAPER, "Everything fits: click Deposit")
                : icon(Items.PAPER, "Make room in your inventory, then click Deposit",
                        missing + " more free " + (missing == 1 ? "slot" : "slots") + " needed"));
    }

    /** Keeps the info paper current while the player makes room (every half second). */
    @Override
    public void broadcastChanges() {
        if (++ticks % 10 == 0 && !finished) {
            drawInfo();
        }
        super.broadcastChanges();
    }

    private static ItemStack icon(Item item, String name, String... lore) {
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

    @Override
    public ItemStack quickMoveStack(Player who, int index) {
        return ItemStack.EMPTY; // shift-click never moves anything into the popup
    }

    @Override
    public boolean canDragTo(Slot slot) {
        return slot.container != view;
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
        return slot.container != view;
    }

    @Override
    public boolean stillValid(Player who) {
        return true;
    }

    @Override
    public void removed(Player who) {
        super.removed(who); // the player's own cursor item goes back to their inventory, as on any close
        if (finished || rows.isEmpty() || player.hasDisconnected()) {
            return;
        }
        SyncService sync = Sync.active();
        if (sync != null && sync.transferTarget(player.getUUID()).isPresent()) {
            return; // switching servers: asked again there
        }
        if (depositing) {
            cancelAfterDeposit = true; // decided when the Deposit has been answered, on the stacks really left
            return;
        }
        service.decline(player, rows.size());
    }
}
