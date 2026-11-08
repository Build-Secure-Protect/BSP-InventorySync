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
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;

/**
 * A grave (D25 17 to 20): an upright headstone on a low base, of the stone that suits where it stands, facing the way
 * the player faced when they died, with a faint glow (light 4). It cannot be broken, blown up, pushed, burnt or washed
 * away, drops nothing and has no item: it goes when its grave is emptied. Its block entity names the grave; the
 * contents live in the grave store. Opening it comes with the grave screen (GR-3).
 *
 * <p>Model (sixteenths, facing north; size B of D27): base 0..16 x 0..2 x 8..16; stone 1..15 x 2..16 x 10..14; cap
 * 3..13 x 16..19 x 10..14; a glowing golden sigil on the stone's front and back (D26). The models come from
 * {@code tools/gen_grave_assets.py}; the owner's head in front, leaning {@link #LEAN}, is drawn by the client's grave
 * renderer and is solid (D26 6a): the shapes, {@link GraveShape}'s, include it, so a click on it opens the grave.
 */
public final class GraveBlock extends Block implements EntityBlock {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final EnumProperty<GraveKind> KIND = EnumProperty.create("kind", GraveKind.class);
    /** Which way the owner's head leans (D27 2a): from the grave's id, set when the grave is put up. */
    public static final EnumProperty<GraveLean> LEAN = EnumProperty.create("lean", GraveLean.class);
    public static final int LIGHT = 4;

    public GraveBlock() {
        super(BlockBehaviour.Properties.of().mapColor(MapColor.STONE).sound(SoundType.STONE)
                .strength(-1.0F, 3_600_000.0F).noLootTable().noOcclusion().forceSolidOn().pushReaction(PushReaction.BLOCK)
                .lightLevel(s -> LIGHT).isValidSpawn((s, l, p, e) -> false));
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(KIND, GraveKind.STONE).setValue(LEAN, GraveLean.LEFT));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, KIND, LEAN);
    }

    /** The state for a grave facing {@code facing} (any horizontal direction; up or down count as north), its head leaning left. */
    public BlockState stateFor(Direction facing, GraveKind kind) {
        return stateFor(facing, kind, GraveLean.LEFT);
    }

    /** As above, its head leaning {@code lean}. */
    public BlockState stateFor(Direction facing, GraveKind kind, GraveLean lean) {
        Direction f = facing.getAxis().isHorizontal() ? facing : Direction.NORTH;
        return defaultBlockState().setValue(FACING, f).setValue(KIND, kind).setValue(LEAN, lean);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return GraveShape.of(state.getValue(FACING), state.getValue(LEAN));
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

    /** The motes (D26 3a): a ticker on the client only; a server never ticks a grave. */
    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? clientTicker(type) : null;
    }

    /** The client's ticker for a grave's block entity type: {@link GraveMotes#tick}; null for any other type. */
    @Nullable
    @SuppressWarnings("unchecked")
    public static <T extends BlockEntity> BlockEntityTicker<T> clientTicker(BlockEntityType<T> type) {
        return type == GraveRegistry.GRAVE_ENTITY.get() ? (BlockEntityTicker<T>) (BlockEntityTicker<GraveBlockEntity>) GraveMotes::tick : null;
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
