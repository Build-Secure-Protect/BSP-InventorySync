package com.mrgregles.bsp_inventorysync.editor;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The editor's screen: a vanilla 6-row chest, so the admin's client needs nothing installed. The top
 * part shows what {@link EditorSession} draws; the bottom part is the admin's own inventory, which is
 * locked. Vanilla's click handling is never used: plain left, right and shift clicks on the top part go
 * to the session, every other click (the admin's own slots, drags, number keys, throws, double clicks,
 * middle clicks) does nothing, and the whole screen is sent again after each click so the client never
 * keeps a move it predicted. Closing the screen drops whatever is on the cursor: it is virtual, so
 * nothing ever reaches the admin's own inventory.
 */
public final class EditorMenu extends ChestMenu {
    private final EditorSession session;
    private final ServerPlayer admin;
    private final SimpleContainer view;
    private final Runnable onConfirm;

    public EditorMenu(int id, ServerPlayer admin, EditorSession session, Runnable onConfirm) {
        this(id, admin, session, onConfirm, new SimpleContainer(EditorSession.SLOTS));
    }

    private EditorMenu(int id, ServerPlayer admin, EditorSession session, Runnable onConfirm, SimpleContainer view) {
        super(MenuType.GENERIC_9x6, id, admin.getInventory(), view, 6);
        this.session = session;
        this.admin = admin;
        this.view = view;
        this.onConfirm = onConfirm;
        draw();
    }

    public EditorSession session() {
        return session;
    }

    @Override
    public void clicked(int slotId, int button, ClickType type, Player player) {
        if (slotId >= 0 && slotId < EditorSession.SLOTS && (button == 0 || button == 1) && (type == ClickType.PICKUP || type == ClickType.QUICK_MOVE)) {
            EditorSession.Result r = session.click(slotId, button, type == ClickType.QUICK_MOVE);
            if (r == EditorSession.Result.CLOSE) {
                admin.closeContainer();
                return;
            }
            if (r == EditorSession.Result.CONFIRM) {
                onConfirm.run();
                return;
            }
        }
        draw();
    }

    /** Puts the session's picture on the screen and sends all of it, the cursor included. */
    private void draw() {
        for (int i = 0; i < EditorSession.SLOTS; i++) {
            view.setItem(i, session.display(i));
        }
        setCarried(session.cursor().copy());
        sendAllDataToRemote();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canDragTo(Slot slot) {
        return false;
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
        return false;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void removed(Player player) {
        setCarried(ItemStack.EMPTY); // virtual: never handed to the admin
        super.removed(player);
    }
}
