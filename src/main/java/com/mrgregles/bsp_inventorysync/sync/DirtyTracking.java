package com.mrgregles.bsp_inventorysync.sync;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.data.vanilla.AdvancementsModule;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.AdvancementEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * The dirty flags the modules keep (IS-7). Most data is cheap to capture and compared whole, so the
 * auto-save needs no flags for it; the advancements file is the exception and is marked here when a
 * criterion changes, and forgotten when the player leaves.
 */
@Mod.EventBusSubscriber(modid = BSPInventorySync.MODID)
public final class DirtyTracking {
    private DirtyTracking() {}

    @SubscribeEvent
    public static void onAdvancement(AdvancementEvent.AdvancementProgressEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            AdvancementsModule.markDirty(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        AdvancementsModule.forget(event.getEntity().getUUID());
    }
}
