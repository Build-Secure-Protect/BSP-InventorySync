package com.mrgregles.bsp_inventorysync.grave;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.living.LivingExperienceDropEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Forge events into the {@link GraveService}. The death and the drops run at LOWEST: BSP-Core (HIGHEST) has taken
 * the Shatter Totems by then, and every other mod has decided what drops (Curios, keep-on-death enchantments).
 * Cancelled deaths and drops (a totem of undying, a mod that keeps the inventory) are never seen. The service
 * starts after the sync (LOW), so it finds the sync's store.
 */
@Mod.EventBusSubscriber(modid = BSPInventorySync.MODID)
public final class GraveEvents {
    private GraveEvents() {}

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onServerStarted(ServerStartedEvent event) {
        GraveService.start(event.getServer());
    }

    /** D30: graves an admin returned while the player was offline come back after their data has arrived here. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRestored(com.mrgregles.bsp_inventorysync.api.event.InventoryRestoredEvent event) {
        GraveService s = GraveService.active();
        if (s != null) {
            s.deliverReturns(java.util.List.of(event.getEntity()), true);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        GraveService.stop();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        GraveService s = GraveService.active();
        if (s != null && event.getEntity() instanceof ServerPlayer player) {
            s.recordLayout(player, event.getSource());
        }
    }

    /** Seen even when another mod cancelled it: then no grave, and the orbs held back drop after all. */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onDrops(LivingDropsEvent event) {
        GraveService s = GraveService.active();
        if (s != null && event.getEntity() instanceof ServerPlayer player) {
            if (event.isCanceled()) {
                s.dropsCancelled(player);
            } else {
                s.onDrops(player, event.getSource(), event.getDrops(), System.currentTimeMillis());
            }
        }
    }

    /** A death's orbs drop just before its drops event: held back for the grave (given back as orbs if none is made). */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onExperienceDrop(LivingExperienceDropEvent event) {
        GraveService s = GraveService.active();
        if (s != null && event.getEntity() instanceof ServerPlayer player && s.holdXp(player, event.getDroppedExperience())) {
            event.setDroppedExperience(0);
            event.setCanceled(true);
        }
    }

    /**
     * A right-click on a grave opens it (D25 2b), whatever the hand holds; it runs first and even when a claim or a
     * region has already refused the click (15a: claims never keep a grave out of reach).
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onUse(net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock event) {
        if (!event.getLevel().getBlockState(event.getPos()).is(GraveRegistry.GRAVE.get())) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
        if (event.getLevel().isClientSide() || event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND
                || !(event.getEntity() instanceof ServerPlayer player) || !(event.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }
        GraveService s = GraveService.active();
        if (s == null) {
            player.displayClientMessage(net.minecraft.network.chat.Component.literal("Graves are off on this server.").withStyle(net.minecraft.ChatFormatting.GOLD), true);
            return;
        }
        s.access().openAt(player, level, event.getPos());
    }

    /** Nobody breaks a grave, not even in creative mode: it goes when it is emptied (or by an admin's command). */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onBreak(net.minecraftforge.event.level.BlockEvent.BreakEvent event) {
        if (event.getState().is(GraveRegistry.GRAVE.get())) {
            event.setCanceled(true);
            if (event.getPlayer() != null) {
                event.getPlayer().displayClientMessage(net.minecraft.network.chat.Component.literal("A grave cannot be broken: it goes when it is emptied.")
                        .withStyle(net.minecraft.ChatFormatting.RED), true);
            }
        }
    }

    /** Whatever an explosion's power, it leaves graves standing. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onExplosion(net.minecraftforge.event.level.ExplosionEvent.Detonate event) {
        event.getAffectedBlocks().removeIf(pos -> event.getLevel().getBlockState(pos).is(GraveRegistry.GRAVE.get()));
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        GraveService s = GraveService.active();
        if (s != null) {
            s.respawned(event.getEntity().getUUID());
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        GraveService s = GraveService.active();
        if (s != null) {
            s.loggedOut(event.getEntity().getUUID());
        }
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent event) {
        GraveService s = GraveService.active();
        if (event.phase == TickEvent.Phase.END && s != null) {
            s.tick(System.currentTimeMillis());
        }
    }
}
