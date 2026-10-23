package com.mrgregles.bsp_inventorysync.sync;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.CommandEvent;
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

    /**
     * By the time the event fires the stack has already left the inventory (or the cursor), and a
     * cancelled toss spawns nothing, so the item goes back into the inventory here and the toss is
     * cancelled only when all of it fit; what does not fit is dropped as usual and never lost.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onToss(ItemTossEvent event) {
        if (frozen(event.getPlayer())) {
            ItemStack stack = event.getEntity().getItem();
            event.getPlayer().getInventory().add(stack);
            if (stack.isEmpty()) {
                event.setCanceled(true);
            }
        }
    }

    /**
     * A command run now would act on the stale copy (a bank deposit takes coins that the restore then
     * puts back), so commands wait too. The freeze normally lasts a few milliseconds.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onCommand(CommandEvent event) {
        if (event.getParseResults().getContext().getSource().getEntity() instanceof ServerPlayer player && SyncService.isSyncing(player.getUUID())) {
            event.setCanceled(true);
            player.displayClientMessage(Component.literal("Your items are still loading. Try again in a moment.").withStyle(ChatFormatting.GOLD), false);
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
