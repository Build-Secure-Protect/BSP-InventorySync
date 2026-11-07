package com.mrgregles.bsp_inventorysync.client;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.grave.GraveRegistry;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** The client's side of the mod: only the grave's floating text. Never loaded on a dedicated server. */
@Mod.EventBusSubscriber(modid = BSPInventorySync.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {
    private ClientSetup() {}

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(GraveRegistry.GRAVE_ENTITY.get(), GraveRenderer::new);
    }
}
