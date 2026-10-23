package com.mrgregles.bsp_inventorysync.data;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * A placeholder ({@link ItemCodec}) is a barrier holding another item. Right-clicking with it would
 * place the barrier (an unbreakable block in survival, the original lost inside), so its use is
 * refused (D14) and the player told what it is. Clicking a block with one in hand still works on the
 * block (a chest opens); only the placeholder itself is not used.
 */
@Mod.EventBusSubscriber(modid = BSPInventorySync.MODID)
public final class PlaceholderGuard {
    static final String NOTICE = "This is a placeholder for a missing mod's item.";

    private PlaceholderGuard() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onUseOnBlock(PlayerInteractEvent.RightClickBlock event) {
        if (ItemCodec.isPlaceholder(event.getItemStack())) {
            event.setUseItem(Event.Result.DENY);
            tell(event.getEntity());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onUse(PlayerInteractEvent.RightClickItem event) {
        if (ItemCodec.isPlaceholder(event.getItemStack())) {
            event.setCanceled(true);
            tell(event.getEntity());
        }
    }

    private static void tell(Player player) {
        if (!player.level().isClientSide()) {
            player.displayClientMessage(Component.literal(NOTICE).withStyle(ChatFormatting.GRAY), true);
        }
    }
}
