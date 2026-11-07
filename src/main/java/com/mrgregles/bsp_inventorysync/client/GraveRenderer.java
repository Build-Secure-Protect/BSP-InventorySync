package com.mrgregles.bsp_inventorysync.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mrgregles.bsp_inventorysync.grave.GraveBlockEntity;
import com.mrgregles.bsp_inventorysync.grave.GraveText;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import org.joml.Matrix4f;

/**
 * The floating text over a grave (D25 19a), drawn on each player's own screen and turned to face them like a name tag:
 * the owner's name, then "Right-click to open" (gold) for whoever may open it now or "Public in 58:12" (grey) for
 * everyone else. Within 32 blocks. The lines come from {@link GraveText}; the countdown uses the server's clock.
 */
public final class GraveRenderer implements BlockEntityRenderer<GraveBlockEntity> {
    private static final int WHITE = 0xFFFFFF, GOLD = 0xFFD23A, GREY = 0xAAAAAA;
    private final Font font;

    public GraveRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.getFont();
    }

    @Override
    public void render(GraveBlockEntity grave, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || grave.ownerName().isEmpty()) {
            return;
        }
        GraveText.Lines lines = GraveText.lines(mc.player.getUUID(), grave.owner(), grave.ownerName(), grave.publicAt(), grave.serverNow(System.currentTimeMillis()));
        pose.pushPose();
        pose.translate(0.5, 1.45, 0.5);
        pose.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
        pose.scale(-0.025F, -0.025F, 0.025F);
        Matrix4f matrix = pose.last().pose();
        int background = (int) (mc.options.getBackgroundOpacity(0.25F) * 255.0F) << 24;
        draw(lines.name(), -10, WHITE, matrix, buffers, background);
        draw(lines.status(), 0, lines.mayOpen() ? GOLD : GREY, matrix, buffers, background);
        pose.popPose();
    }

    private void draw(String text, int y, int colour, Matrix4f matrix, MultiBufferSource buffers, int background) {
        float x = -font.width(text) / 2.0F;
        font.drawInBatch(text, x, y, colour, false, matrix, buffers, Font.DisplayMode.NORMAL, background, LightTexture.FULL_BRIGHT);
    }

    @Override
    public int getViewDistance() {
        return 32;
    }
}
