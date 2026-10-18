package com.mrgregles.bsp_inventorysync.data.vanilla;

import com.mrgregles.bsp_inventorysync.api.DataModule;
import com.mrgregles.bsp_inventorysync.data.ItemCodec;
import com.mrgregles.bsp_inventorysync.data.ItemRules;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * The player's inventory: the 36 main slots, the four armour slots and the offhand (slots 0 to 40 of
 * {@link Inventory}), the selected hotbar slot and the item on the cursor. Excluded items (a live
 * Shatter Totem) are left out of the snapshot by {@link ItemRules}.
 */
public final class InventoryModule implements DataModule {
    public static final String ID = "inventory";
    private static final String ITEMS = "Items";
    private static final String SELECTED = "Selected";
    private static final String CURSOR = "Cursor";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void save(ServerPlayer player, CompoundTag out) {
        Inventory inv = player.getInventory();
        out.put(ITEMS, Slots.save(inv));
        out.putInt(SELECTED, inv.selected);
        ItemStack cursor = player.containerMenu.getCarried();
        if (!cursor.isEmpty() && !ItemRules.excluded(cursor)) {
            out.put(CURSOR, ItemCodec.save(cursor));
        }
    }

    @Override
    public void load(ServerPlayer player, CompoundTag in) {
        Inventory inv = player.getInventory();
        Slots.load(inv, in.getList(ITEMS, Tag.TAG_COMPOUND));
        int selected = in.getInt(SELECTED);
        inv.selected = Inventory.isHotbarSlot(selected) ? selected : 0;
        player.containerMenu.setCarried(in.contains(CURSOR, Tag.TAG_COMPOUND) ? ItemCodec.load(in.getCompound(CURSOR)) : ItemStack.EMPTY);
        if (player.connection != null) {
            player.connection.send(new ClientboundSetCarriedItemPacket(inv.selected));
        }
        player.inventoryMenu.sendAllDataToRemote();
        if (player.containerMenu != player.inventoryMenu) {
            player.containerMenu.sendAllDataToRemote();
        }
    }
}
