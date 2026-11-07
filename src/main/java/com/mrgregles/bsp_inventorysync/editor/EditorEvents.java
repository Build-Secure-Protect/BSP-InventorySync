package com.mrgregles.bsp_inventorysync.editor;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Closes the editor when its admin turns spectator, since their clicks would be ignored from then on;
 * nothing is changed. Forge posts the change before making it, and other mods may cancel it or change the
 * new mode: this runs last, is not called for a cancelled change, and reads the mode that will be set.
 */
@Mod.EventBusSubscriber(modid = BSPInventorySync.MODID)
public final class EditorEvents {
    private EditorEvents() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onGameModeChange(PlayerEvent.PlayerChangeGameModeEvent event) {
        if (event.getNewGameMode() == GameType.SPECTATOR && event.getEntity() instanceof ServerPlayer admin && admin.containerMenu instanceof EditorMenu) {
            admin.closeContainer();
            admin.sendSystemMessage(Component.literal("The editor closed and nothing was changed. " + EditorActions.SPECTATOR).withStyle(ChatFormatting.RED));
        }
    }
}
