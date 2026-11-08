package com.mrgregles.bsp_inventorysync.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.mrgregles.bsp_inventorysync.grave.GraveBlock;
import com.mrgregles.bsp_inventorysync.grave.GraveBlockEntity;
import com.mrgregles.bsp_inventorysync.grave.GraveLean;
import com.mrgregles.bsp_inventorysync.grave.GraveShape;
import com.mrgregles.bsp_inventorysync.grave.GraveText;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.SkullModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.SkullBlockRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * What the block model cannot draw, on each player's own screen:
 * <ul>
 *   <li>The owner's head in front of the stone (D26 2a, 5a): vanilla's player head and the owner's skin as it was at
 *   the death (Steve or Alex until the skin has loaded, and for a grave from before 0.6.0), full size, sunk to the
 *   chin, facing out of the front and leaning the way the block state says, from {@link GraveShape}'s numbers.</li>
 *   <li>The floating text (D25 19a), turned to face the player like a name tag: the owner's name, then "Right-click to
 *   open" (gold) for whoever may open it now or "Public in 58:12" (grey) for everyone else. Within 32 blocks. The
 *   lines come from {@link GraveText}; the countdown uses the server's clock.</li>
 * </ul>
 */
public final class GraveRenderer implements BlockEntityRenderer<GraveBlockEntity> {
    private static final int WHITE = 0xFFFFFF, GOLD = 0xFFD23A, GREY = 0xAAAAAA;
    /** Where the text's lower line sits: as far above the cap as it sat above the 0.5.0 headstone. */
    static final double TEXT_Y = GraveShape.TOP / 16 + 0.51;
    private static final double TEXT_DISTANCE = 32;
    private final Font font;
    private final SkullModel head;

    public GraveRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.getFont();
        this.head = new SkullModel(context.bakeLayer(ModelLayers.PLAYER_HEAD));
    }

    @Override
    public void render(GraveBlockEntity grave, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        Minecraft mc = Minecraft.getInstance();
        BlockState state = grave.getBlockState();
        if (state.getBlock() instanceof GraveBlock) {
            renderHead(grave, state.getValue(GraveBlock.FACING), state.getValue(GraveBlock.LEAN), pose, buffers, light);
        }
        if (mc.player == null || grave.ownerName().isEmpty()
                || mc.gameRenderer.getMainCamera().getPosition().distanceToSqr(Vec3.atCenterOf(grave.getBlockPos())) > TEXT_DISTANCE * TEXT_DISTANCE) {
            return;
        }
        GraveText.Lines lines = GraveText.lines(mc.player.getUUID(), grave.owner(), grave.ownerName(), grave.publicAt(), grave.serverNow(System.currentTimeMillis()));
        pose.pushPose();
        pose.translate(0.5, TEXT_Y, 0.5);
        pose.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
        pose.scale(-0.025F, -0.025F, 0.025F);
        Matrix4f matrix = pose.last().pose();
        int background = (int) (mc.options.getBackgroundOpacity(0.25F) * 255.0F) << 24;
        draw(lines.name(), -10, WHITE, matrix, buffers, background);
        draw(lines.status(), 0, lines.mayOpen() ? GOLD : GREY, matrix, buffers, background);
        pose.popPose();
    }

    /**
     * The head, in the frame of a grave facing north (the block state turns the model clockwise seen from above, so
     * this turns the same way): its middle at x 8 and z 4.5 on the ground, leaning about that point, then sunk.
     * Vanilla's skull model has its bottom at the origin and, under the skull renderer's flip, faces north at yaw 0.
     */
    private void renderHead(GraveBlockEntity grave, Direction facing, GraveLean lean, PoseStack pose, MultiBufferSource buffers, int light) {
        RenderType type = SkullBlockRenderer.getRenderType(SkullBlock.Types.PLAYER, grave.headProfile());
        pose.pushPose();
        pose.translate(0.5, 0, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(180F - facing.toYRot()));
        pose.translate(GraveShape.HEAD_X / 16 - 0.5, 0, GraveShape.HEAD_Z / 16 - 0.5);
        pose.mulPose(Axis.ZP.rotationDegrees(lean.degrees()));
        pose.translate(0, -GraveShape.HEAD_SINK / 16, 0);
        pose.scale(-1F, -1F, 1F);
        head.setupAnim(0F, 0F, 0F);
        head.renderToBuffer(pose, buffers.getBuffer(type), light, OverlayTexture.NO_OVERLAY, 1F, 1F, 1F, 1F);
        pose.popPose();
    }

    private void draw(String text, int y, int colour, Matrix4f matrix, MultiBufferSource buffers, int background) {
        float x = -font.width(text) / 2.0F;
        font.drawInBatch(text, x, y, colour, false, matrix, buffers, Font.DisplayMode.NORMAL, background, LightTexture.FULL_BRIGHT);
    }

    /** The head is part of the grave, so it shows as far as block entities do; the text keeps to 32 blocks. */
    @Override
    public int getViewDistance() {
        return 64;
    }
}
