package com.mrgregles.bsp_inventorysync.fix;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraftforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * A vanilla 1.20.1 bug, fixed here because every server runs this mod. A teleport into another dimension
 * through {@code ServerPlayer.teleportTo(ServerLevel, ...)} (vanilla {@code /tp}, BSP-Teleport, BSP-Core's
 * totem recall, Region Guard's border links and triggers) sends a respawn packet, so the client builds a
 * new player with no experience and no effects, and then sends neither again: the XP bar shows 0 until
 * the experience changes, and the effects are gone from the client (icons, night vision, and the
 * movement it predicts itself: slow falling, levitation, jump boost) while the server still has them.
 * A portal ({@code changeDimension}) sends both, so after a portal trip they are sent twice, harmlessly.
 * The player's real experience and effects on the server are only read. Runs whether the sync is on or off.
 */
@Mod.EventBusSubscriber(modid = BSPInventorySync.MODID)
public final class TeleportResend {
    private TeleportResend() {}

    /** Posted by Forge at the end of both teleports, after the respawn packet, so these land on the new client player. */
    @SubscribeEvent
    public static void onChangedDimension(PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            resend(player);
        }
    }

    /** Sends the player's own experience and active effects to their client, as vanilla's portal trip does. */
    public static void resend(ServerPlayer player) {
        if (player.connection == null) {
            return;
        }
        player.connection.send(new ClientboundSetExperiencePacket(player.experienceProgress, player.totalExperience, player.experienceLevel));
        for (MobEffectInstance effect : player.getActiveEffects()) {
            player.connection.send(new ClientboundUpdateMobEffectPacket(player.getId(), effect));
        }
    }
}
