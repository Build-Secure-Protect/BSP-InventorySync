package com.mrgregles.bsp_inventorysync.client;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.grave.GraveMotes;
import com.mrgregles.bsp_inventorysync.grave.GraveRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/** The client's side of the mod: the grave's renderer (head and floating text) and who watches its motes. Never loaded on a dedicated server. */
@Mod.EventBusSubscriber(modid = BSPInventorySync.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {
    private ClientSetup() {}

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(GraveRegistry.GRAVE_ENTITY.get(), GraveRenderer::new);
    }

    /** The motes around a grave are for this client's own player, within 16 blocks of it. */
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        GraveMotes.setViewer(level -> {
            LocalPlayer player = Minecraft.getInstance().player;
            return player != null && player.level() == level ? player.position() : null;
        });
    }
}
