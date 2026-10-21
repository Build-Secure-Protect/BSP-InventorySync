package com.mrgregles.bsp_inventorysync.sync;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.api.event.InventoryRestoredEvent;
import com.mrgregles.bsp_inventorysync.storage.SaveCause;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/**
 * Forge events into the {@link SyncService}. Priorities matter: the login runs at LOWEST so
 * BSP-Teleport's landing (normal) has moved the player first and the freeze anchors there; the
 * logout runs at LOWEST so BSP-Core (HIGHEST) has taken the carried totems before the snapshot.
 */
@Mod.EventBusSubscriber(modid = BSPInventorySync.MODID)
public final class SyncEvents {
    private SyncEvents() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        Sync.start(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        Sync.stop();
    }

    /**
     * With the sync off on this server there is nothing to restore, but the restore event still fires
     * once (version 0): BSP-Core holds coins, the season check and owed items until it sees it.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        SyncService s = Sync.active();
        if (s != null) {
            s.onLogin(player, System.currentTimeMillis());
        } else {
            MinecraftForge.EVENT_BUS.post(new InventoryRestoredEvent(player, 0, "", false, List.of(), 0));
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        SyncService s = Sync.active();
        if (s != null && event.getEntity() instanceof ServerPlayer player) {
            s.onLogout(player);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        SyncService s = Sync.active();
        if (s != null && event.getEntity() instanceof ServerPlayer player) {
            s.onRespawn(player, event.isEndConquered(), System.currentTimeMillis());
        }
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent event) {
        SyncService s = Sync.active();
        if (s != null && event.phase == TickEvent.Phase.END) {
            s.tick(System.currentTimeMillis());
        }
    }

    /** The world save (once, on the overworld) saves every settled player too (D7). */
    @SubscribeEvent
    public static void onWorldSave(LevelEvent.Save event) {
        SyncService s = Sync.active();
        if (s != null && event.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD) {
            s.saveAll(level.getServer().getPlayerList().getPlayers(), SaveCause.WORLD_SAVE);
        }
    }
}
