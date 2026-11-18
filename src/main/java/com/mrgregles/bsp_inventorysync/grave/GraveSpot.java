package com.mrgregles.bsp_inventorysync.grave;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where a grave can stand (D25 6b). A good spot is inside the world, in a loaded chunk, free (air, grass,
 * a thin snow layer: what a block placed there would replace), dry (no water or lava in it), on top of
 * something solid, with headroom for the cap (D28: nothing to bump into in the block above, as the cap reaches 3/16
 * into it), not a spot to avoid (where BSP-Core's dropped Shatter Totem lies, so the totem can
 * always place itself), and inside the world border with {@code graves.borderMargin} whole blocks to spare
 * ({@link GraveBorder}, fix GB-1). The search looks outward from the spot, nearest first.
 */
public final class GraveSpot {
    private static final Map<Integer, List<BlockPos>> OFFSETS = new ConcurrentHashMap<>();

    private GraveSpot() {}

    public static boolean good(ServerLevel level, BlockPos pos, Set<BlockPos> avoid) {
        return good(level, pos, avoid, GraveBorder.of(level));
    }

    /** As above, with the world's border already read (a search reads it once). */
    static boolean good(ServerLevel level, BlockPos pos, Set<BlockPos> avoid, com.mrgregles.bsp_inventorysync.compat.RegionGuard.Square border) {
        if (!GraveBorder.inside(border, pos)) {
            return false; // GB-1: never outside the border, nor closer to it than graves.borderMargin
        }
        if (!level.isInWorldBounds(pos) || !level.hasChunkAt(pos) || avoid.contains(pos) || endReserved(level, pos)) {
            return false;
        }
        BlockState here = level.getBlockState(pos);
        if (!here.canBeReplaced() || !here.getFluidState().isEmpty()) {
            return false;
        }
        BlockPos below = pos.below();
        if (!level.isInWorldBounds(below) || !level.hasChunkAt(below)) {
            return false;
        }
        if (!headroom(level, pos)) {
            return false;
        }
        return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
    }

    /**
     * Room above a grave at {@code pos} for its cap (D28): the block above has nothing to bump into, and nothing of
     * its outline where the cap reaches into it ({@link GraveShape#CAP_REACH}), which also catches the blocks that let
     * things through (scaffolding, powder snow) and a hanging lantern; air, or a torch high on a wall, leaves room.
     * Above the world's top there is always room.
     */
    public static boolean headroom(ServerLevel level, BlockPos pos) {
        BlockPos above = pos.above();
        if (!level.isInWorldBounds(above)) {
            return true;
        }
        BlockState state = level.getBlockState(above);
        return state.getCollisionShape(level, above).isEmpty()
                && !net.minecraft.world.phys.shapes.Shapes.joinIsNotEmpty(state.getShape(level, above), GraveShape.CAP_REACH, net.minecraft.world.phys.shapes.BooleanOp.AND);
    }

    /**
     * Places in the End that vanilla rebuilds with a plain setBlock, wiping whatever stands there: the obsidian arrival
     * platform (rebuilt each time a player arrives), the exit portal's podium (built when the dragon dies and again when
     * it is summoned), and the space above the obsidian pillars (rebuilt when the dragon is summoned). A grave is never
     * put there.
     */
    public static boolean endReserved(net.minecraft.world.level.Level level, BlockPos pos) {
        if (level.dimension() != net.minecraft.world.level.Level.END) {
            return false;
        }
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        if (x >= 97 && x <= 103 && z >= -3 && z <= 3 && y >= 48 && y <= 53) {
            return true; // the arrival platform: x 98..102, z -2..2, the obsidian at y 49 and air from 50 to 52, with a block's margin
        }
        if (Math.abs(x) <= 4 && Math.abs(z) <= 4) {
            return true; // the podium and its portal, at any height
        }
        for (int i = 0; i < 10; i++) {
            int sx = net.minecraft.util.Mth.floor(42.0 * Math.cos(2.0 * (-Math.PI + Math.PI / 10.0 * i)));
            int sz = net.minecraft.util.Mth.floor(42.0 * Math.sin(2.0 * (-Math.PI + Math.PI / 10.0 * i)));
            if (Math.abs(x - sx) <= 7 && Math.abs(z - sz) <= 7 && y >= 60) {
                return true; // above an obsidian pillar (radius up to 5), cleared when the dragon is summoned
            }
        }
        return false;
    }

    /**
     * The spot straight below {@code from} that stands on something (through air, never through water or lava),
     * as BSP-Core grounds a dropped totem; {@code from} itself when there is nothing below (the void).
     */
    public static BlockPos grounded(ServerLevel level, BlockPos from) {
        BlockPos.MutableBlockPos p = from.mutable();
        while (p.getY() > level.getMinBuildHeight()) {
            BlockPos below = p.below();
            if (!level.hasChunkAt(below)) {
                return from;
            }
            BlockState under = level.getBlockState(below);
            if (!under.getCollisionShape(level, below).isEmpty() || !under.getFluidState().isEmpty()) {
                return p.immutable();
            }
            p.move(0, -1, 0);
        }
        return from;
    }

    /** The nearest good spot within {@code radius} blocks of {@code centre} (a cube), if any. */
    public static Optional<BlockPos> near(ServerLevel level, BlockPos centre, int radius, Set<BlockPos> avoid) {
        com.mrgregles.bsp_inventorysync.compat.RegionGuard.Square border = GraveBorder.of(level);
        for (BlockPos offset : offsets(radius)) {
            BlockPos pos = centre.offset(offset);
            if (good(level, pos, avoid, border)) {
                return Optional.of(pos);
            }
        }
        return Optional.empty();
    }

    /**
     * Where a grave goes for a death (or a standing grave) at {@code from}: the nearest good spot around it grounded, as
     * always. When that point is outside the world border or closer to it than {@code graves.borderMargin} (fix GB-1, the
     * owner's answers 3 a, 4 a, 5 a): the search starts instead at the nearest column far enough in, at the same height
     * grounded, however far that is (its chunks are loaded); failing that, at the top of that column (not in a world with
     * a ceiling, the Nether, whose top is its bedrock roof). Empty when nothing will do.
     */
    public static Optional<BlockPos> nearInside(ServerLevel level, BlockPos from, int radius, Set<BlockPos> avoid) {
        com.mrgregles.bsp_inventorysync.compat.RegionGuard.Square border = GraveBorder.of(level);
        BlockPos start = level.hasChunkAt(from) ? grounded(level, from) : from;
        if (GraveBorder.inside(border, start)) {
            return near(level, start, radius, avoid);
        }
        BlockPos column = GraveBorder.clampInside(border, from);
        loadAround(level, column, radius);
        BlockPos in = grounded(level, column);
        Optional<BlockPos> spot = near(level, in, radius, avoid);
        if (spot.isEmpty() && !level.dimensionType().hasCeiling()) {
            int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ());
            BlockPos surface = new BlockPos(column.getX(), top, column.getZ());
            if (!surface.equals(in)) {
                spot = near(level, surface, radius, avoid);
            }
        }
        return spot;
    }

    /** Whether a grave at {@code pos} would be outside the border or too close to it (the point {@link #nearInside} moves from). */
    public static boolean nearBorder(ServerLevel level, BlockPos pos) {
        return !GraveBorder.inside(GraveBorder.of(level), pos);
    }

    /** Loads the chunks the search around {@code centre} reaches (a death far outside the border lands where nobody is). */
    private static void loadAround(ServerLevel level, BlockPos centre, int radius) {
        int r = Math.max(0, Math.min(radius, 16));
        for (int cx = (centre.getX() - r) >> 4; cx <= (centre.getX() + r) >> 4; cx++) {
            for (int cz = (centre.getZ() - r) >> 4; cz <= (centre.getZ() + r) >> 4; cz++) {
                level.getChunk(cx, cz);
            }
        }
    }

    /** Every offset in the cube, nearest first; at equal distance the one higher up first (out of a pit rather than into the rock). */
    static List<BlockPos> offsets(int radius) {
        int r = Math.max(0, Math.min(radius, 16));
        return OFFSETS.computeIfAbsent(r, k -> {
            List<BlockPos> out = new ArrayList<>();
            for (int dx = -k; dx <= k; dx++) {
                for (int dy = -k; dy <= k; dy++) {
                    for (int dz = -k; dz <= k; dz++) {
                        out.add(new BlockPos(dx, dy, dz));
                    }
                }
            }
            out.sort(Comparator.<BlockPos>comparingInt(o -> o.getX() * o.getX() + o.getY() * o.getY() + o.getZ() * o.getZ())
                    .thenComparing(Comparator.comparingInt((BlockPos o) -> o.getY()).reversed()));
            return List.copyOf(out);
        });
    }
}
