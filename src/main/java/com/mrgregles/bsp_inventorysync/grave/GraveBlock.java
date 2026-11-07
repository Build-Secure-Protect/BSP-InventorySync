package com.mrgregles.bsp_inventorysync.grave;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;

/**
 * A grave (D25 17 to 20): an upright headstone on a low base, of the stone that suits where it stands, facing the way
 * the player faced when they died, with a faint glow (light 4). It cannot be broken, blown up, pushed, burnt or washed
 * away, drops nothing and has no item: it goes when its grave is emptied. Its block entity names the grave; the
 * contents live in the grave store. Opening it comes with the grave screen (GR-3).
 *
 * <p>Model (sixteenths, facing north): base 2..14 x 0..2 x 4..12; stone 3..13 x 2..13 x 6.5..9.5; cap 5..11 x 13..15
 * x 6.5..9.5. The models come from {@code tools/gen_grave_assets.py}.
 */
public final class GraveBlock extends Block implements EntityBlock {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final EnumProperty<GraveKind> KIND = EnumProperty.create("kind", GraveKind.class);
    public static final int LIGHT = 4;

    private static final VoxelShape ALONG_X = Shapes.or(box(2, 0, 4, 14, 2, 12), box(3, 2, 6.5, 13, 13, 9.5), box(5, 13, 6.5, 11, 15, 9.5));
    private static final VoxelShape ALONG_Z = Shapes.or(box(4, 0, 2, 12, 2, 14), box(6.5, 2, 3, 9.5, 13, 13), box(6.5, 13, 5, 9.5, 15, 11));

    public GraveBlock() {
        super(BlockBehaviour.Properties.of().mapColor(MapColor.STONE).sound(SoundType.STONE)
                .strength(-1.0F, 3_600_000.0F).noLootTable().noOcclusion().forceSolidOn().pushReaction(PushReaction.BLOCK)
                .lightLevel(s -> LIGHT).isValidSpawn((s, l, p, e) -> false));
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(KIND, GraveKind.STONE));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, KIND);
    }

    /** The state for a grave facing {@code facing} (any horizontal direction; up or down count as north). */
    public BlockState stateFor(Direction facing, GraveKind kind) {
        Direction f = facing.getAxis().isHorizontal() ? facing : Direction.NORTH;
        return defaultBlockState().setValue(FACING, f).setValue(KIND, kind);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(FACING).getAxis() == Direction.Axis.Z ? ALONG_X : ALONG_Z;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new GraveBlockEntity(pos, state);
    }

    /** Middle-click gives nothing: a grave has no item. */
    @Override
    public ItemStack getCloneItemStack(BlockState state, HitResult target, BlockGetter level, BlockPos pos, Player player) {
        return ItemStack.EMPTY;
    }

    /**
     * A player never removes it, not even in creative mode, on either side: the client does not predict the break
     * either, so the floating text (the block entity's data) is never lost on screen.
     */
    @Override
    public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player, boolean willHarvest, net.minecraft.world.level.material.FluidState fluid) {
        return false;
    }

    /** An explosion never removes it, whatever its power. */
    @Override
    public void onBlockExploded(BlockState state, Level level, BlockPos pos, Explosion explosion) {
    }

    @Override
    public boolean canDropFromExplosion(BlockState state, BlockGetter level, BlockPos pos, Explosion explosion) {
        return false;
    }

    @Override
    public boolean canEntityDestroy(BlockState state, BlockGetter level, BlockPos pos, net.minecraft.world.entity.Entity entity) {
        return false;
    }

    @Override
    public boolean isFlammable(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return false;
    }

    @Override
    public boolean canBeReplaced(BlockState state, net.minecraft.world.item.context.BlockPlaceContext context) {
        return false;
    }

    /** Water and lava never wash it away (a headstone is not a full block, so vanilla would let them). */
    @Override
    public boolean canBeReplaced(BlockState state, net.minecraft.world.level.material.Fluid fluid) {
        return false;
    }

    @Override
    public float getExplosionResistance(BlockState state, BlockGetter level, BlockPos pos, Explosion explosion) {
        return 3_600_000.0F;
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return true;
    }
}
