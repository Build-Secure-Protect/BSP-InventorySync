package com.mrgregles.bsp_inventorysync.api;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

/**
 * One kind of player data the sync carries: the inventory, the ender chest, experience, and so on.
 * Each module writes its own compound on save and reads it back on load, and knows nothing of the
 * others. Modules run in registration order ({@code Modules}); a module that throws is logged and
 * skipped, the rest still run (the snapshot codec). Both methods run on the server thread.
 */
public interface DataModule {
    /** Stable key of the module's compound inside a snapshot; letters, digits and underscores. */
    String id();

    /** Writes the player's current state of this kind into {@code out} (an empty compound). */
    void save(ServerPlayer player, CompoundTag out);

    /** Replaces the player's state of this kind with what {@code in} holds. */
    void load(ServerPlayer player, CompoundTag in);
}
