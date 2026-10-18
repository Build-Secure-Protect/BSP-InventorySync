package com.mrgregles.bsp_inventorysync.sync;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * While a player's data is still arriving ({@link SyncService#isSyncing}) they can do nothing that
 * touches items or the world, and nothing can hurt them: the inventory they see is this server's
 * stale copy, and anything dropped or used from it would be duplicated. Movement is held by the
 * service's tick. Runs at HIGHEST so no other listener acts on the event first.
 */
@Mod.EventBusSubscriber(modid = BSPInventorySync.MODID)
public final class Freeze {
    private Freeze() {}

    private static boolean frozen(net.minecraft.world.entity.Entity entity) {
        return entity instanceof ServerPlayer player && SyncService.isSyncing(player.getUUID());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onInteract(PlayerInteractEvent event) {
        if (event.isCancelable() && frozen(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onAttack(AttackEntityEvent event) {
        if (frozen(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPickup(EntityItemPickupEvent event) {
        if (frozen(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onToss(ItemTossEvent event) {
        if (frozen(event.getPlayer())) {
            event.setCanceled(true); // Forge puts the item back
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (frozen(event.getPlayer())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onHurt(LivingAttackEvent event) {
        if (frozen(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onContainerOpen(PlayerContainerEvent.Open event) {
        if (frozen(event.getEntity()) && event.getEntity() instanceof ServerPlayer player && event.getContainer() != player.inventoryMenu) {
            player.closeContainer();
        }
    }
}
