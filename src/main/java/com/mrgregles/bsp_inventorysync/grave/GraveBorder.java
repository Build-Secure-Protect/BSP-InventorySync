package com.mrgregles.bsp_inventorysync.grave;

import com.mrgregles.bsp_inventorysync.compat.RegionGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.border.WorldBorder;

/**
 * The world border a grave keeps inside (fix GB-1, the owner's answers 2026-11-18): Minecraft's own border of the world
 * and BSP-Region Guard's border for it, whichever is tighter on each side (1 a), with at least {@code graves.borderMargin}
 * (5) whole blocks between the headstone and the edge (2 a, 6 a). A player outside the border cannot reach a grave there:
 * Region Guard pushes them back in, and in the Overworld Minecraft ignores a right-click on a block outside its own border
 * ({@code ServerLevel.mayInteract}). At a linked edge, Region Guard would also ask about travelling on.
 */
public final class GraveBorder {
    /** Whole blocks between a grave and the edge. */
    private static volatile int margin = 5;

    private GraveBorder() {}

    /** {@code graves.borderMargin}; set at the graves' start and by tests. */
    public static void setMargin(int blocks) {
        margin = Math.max(0, blocks);
    }

    public static int margin() {
        return margin;
    }

    /**
     * The edges a grave in this world keeps inside: Minecraft's border and Region Guard's, the tighter on each side.
     * Minecraft's counts at the smaller of its size now and the size it is shrinking to ({@code /worldborder set <size> <time>}),
     * so a grave placed during a shrink is not left outside when it ends (AUD-9 5 a). When the two do not overlap at all
     * (set apart, with Region Guard's {@code border.vanillaWall} off), Region Guard's alone, whose wall moves players (6 a).
     */
    public static RegionGuard.Square of(ServerLevel level) {
        WorldBorder wb = level.getWorldBorder();
        double half = Math.min(wb.getSize(), wb.getLerpTarget()) / 2.0, limit = wb.getAbsoluteMaxSize();
        double minX = Mth.clamp(wb.getCenterX() - half, -limit, limit), maxX = Mth.clamp(wb.getCenterX() + half, -limit, limit);
        double minZ = Mth.clamp(wb.getCenterZ() - half, -limit, limit), maxZ = Mth.clamp(wb.getCenterZ() + half, -limit, limit);
        RegionGuard.Square rg = RegionGuard.border(level);
        if (rg != null) {
            double x0 = Math.max(minX, rg.minX()), x1 = Math.min(maxX, rg.maxX());
            double z0 = Math.max(minZ, rg.minZ()), z1 = Math.min(maxZ, rg.maxZ());
            return x0 > x1 || z0 > z1 ? rg : new RegionGuard.Square(x0, x1, z0, z1);
        }
        return new RegionGuard.Square(minX, maxX, minZ, maxZ);
    }

    /**
     * Whether the block at {@code pos} has at least the margin of whole blocks between it and every edge. A border too
     * narrow for the margin on both sides takes the most it has room for, so its middle block still counts.
     */
    public static boolean inside(RegionGuard.Square b, BlockPos pos) {
        return pos.getX() >= low(b.minX(), b.maxX()) && pos.getX() <= high(b.minX(), b.maxX())
                && pos.getZ() >= low(b.minZ(), b.maxZ()) && pos.getZ() <= high(b.minZ(), b.maxZ());
    }

    /** The nearest column {@link #inside} the border, at the same height: {@code pos} itself when it already is. */
    public static BlockPos clampInside(RegionGuard.Square b, BlockPos pos) {
        int x = Mth.clamp(pos.getX(), low(b.minX(), b.maxX()), high(b.minX(), b.maxX()));
        int z = Mth.clamp(pos.getZ(), low(b.minZ(), b.maxZ()), high(b.minZ(), b.maxZ()));
        return x == pos.getX() && z == pos.getZ() ? pos : new BlockPos(x, pos.getY(), z);
    }

    /** The lowest block coordinate with the margin to the low edge (the block's near face {@code margin} from it). */
    private static int low(double min, double max) {
        return Mth.ceil(min + room(min, max));
    }

    /** The highest block coordinate with the margin to the high edge (the block's far face {@code margin} from it). */
    private static int high(double min, double max) {
        int lo = low(min, max);
        return Math.max(lo, Mth.floor(max - room(min, max)) - 1);
    }

    /** The margin, or less when the border is too narrow for it on both sides. */
    private static double room(double min, double max) {
        return Math.max(0, Math.min(margin, Math.floor((max - min - 1) / 2.0)));
    }
}
