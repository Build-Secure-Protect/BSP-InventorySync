package com.mrgregles.bsp_inventorysync.data.vanilla;

import com.mrgregles.bsp_inventorysync.api.DataModule;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;

/** The ender chest (27 slots in vanilla; whatever size the server gives it). */
public final class EnderChestModule implements DataModule {
    public static final String ID = "ender_chest";
    private static final String ITEMS = "Items";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void save(ServerPlayer player, CompoundTag out) {
        out.put(ITEMS, Slots.save(player.getEnderChestInventory()));
    }

    @Override
    public void load(ServerPlayer player, CompoundTag in) {
        Slots.load(player.getEnderChestInventory(), in.getList(ITEMS, Tag.TAG_COMPOUND));
    }
}
