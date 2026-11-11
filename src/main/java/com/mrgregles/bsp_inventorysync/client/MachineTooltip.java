package com.mrgregles.bsp_inventorysync.client;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.compat.CompactMachines;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** One grey line under a marked Compact Machine's own tooltip: the server its room is on (D31 8 a). */
@Mod.EventBusSubscriber(modid = BSPInventorySync.MODID, value = Dist.CLIENT)
public final class MachineTooltip {
    private MachineTooltip() {}

    /** The line for a home, as shown. */
    public static String line(String home) {
        return "Room on " + home;
    }

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        CompactMachines.home(event.getItemStack()).ifPresent(home ->
                event.getToolTip().add(Component.literal(line(home)).withStyle(ChatFormatting.GRAY)));
    }
}
