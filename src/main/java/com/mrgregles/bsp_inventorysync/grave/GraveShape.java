package com.mrgregles.bsp_inventorysync.grave;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * What a grave's outline and collision follow (D27 4a): the headstone, its base and cap, and the owner's head in front.
 * Boxes in sixteenths of a block for a grave facing north, the same as {@code tools/gen_grave_assets.py} writes into
 * the model; the other facings are these turned as the block state turns the model (clockwise seen from above).
 *
 * <p>The head (D26 2a, drawn by the client's grave renderer from the same numbers): a full-size player head, 8 by 8 by
 * 8, centred at x 8 and z 4.5, sunk 2 into the ground so it shows to the chin, facing out of the front, and leaning
 * {@link GraveLean#DEGREES} about the point on the ground under its middle. Its part of the shape is the box around
 * what shows above the ground.
 */
public final class GraveShape {
    /** {from x, y, z, to x, y, z}, facing north. */
    static final double[] BASE = {0, 0, 8, 16, 2, 16}, STONE = {1, 2, 10, 15, 16, 14}, CAP = {3, 16, 10, 13, 19, 14};
    /** The top of the cap, in sixteenths (a little over a block). */
    public static final double TOP = CAP[4];
    /** The owner's head, in sixteenths: its size, its middle (x, z) on the ground, how deep it is sunk. */
    public static final double HEAD_SIZE = 8, HEAD_X = 8, HEAD_Z = 4.5, HEAD_SINK = 2;

    private static final Map<GraveLean, Map<Direction, VoxelShape>> SHAPES = new EnumMap<>(GraveLean.class);

    static {
        for (GraveLean lean : GraveLean.values()) {
            Map<Direction, VoxelShape> byFacing = new EnumMap<>(Direction.class);
            double[] head = head(lean);
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                byFacing.put(facing, Shapes.or(turned(BASE, facing), turned(STONE, facing), turned(CAP, facing), turned(head, facing)));
            }
            SHAPES.put(lean, byFacing);
        }
    }

    /**
     * Where the cap reaches into the block above (its top 3/16), for any facing: the four turned cap boxes, moved down a
     * block. A spot whose block above has anything there has no headroom (D28).
     */
    public static final VoxelShape CAP_REACH;

    static {
        VoxelShape reach = Shapes.empty();
        double[] low = {CAP[0], 0, CAP[2], CAP[3], CAP[4] - 16, CAP[5]};
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            reach = Shapes.or(reach, turned(low, facing));
        }
        CAP_REACH = reach.optimize();
    }

    private GraveShape() {}

    /** The base, the stone and the cap, facing north, {from x, y, z, to x, y, z} in sixteenths (copies). */
    public static List<double[]> boxes() {
        return List.of(BASE.clone(), STONE.clone(), CAP.clone());
    }

    /** The shape of a grave facing {@code facing} (up or down count as north) whose head leans {@code lean}. */
    public static VoxelShape of(Direction facing, GraveLean lean) {
        Map<Direction, VoxelShape> byFacing = SHAPES.get(lean);
        return byFacing.getOrDefault(facing, byFacing.get(Direction.NORTH));
    }

    /**
     * The box around the part of the leaning head above the ground, facing north: the head's square (seen from in
     * front) turned about the point on the ground under its middle, cut at the ground.
     */
    public static double[] head(GraveLean lean) {
        double a = Math.toRadians(lean.degrees()), cos = Math.cos(a), sin = Math.sin(a), half = HEAD_SIZE / 2;
        double[][] corners = {{-half, -HEAD_SINK}, {half, -HEAD_SINK}, {half, HEAD_SIZE - HEAD_SINK}, {-half, HEAD_SIZE - HEAD_SINK}};
        double[][] turned = new double[4][];
        for (int i = 0; i < 4; i++) {
            turned[i] = new double[] {corners[i][0] * cos - corners[i][1] * sin, corners[i][0] * sin + corners[i][1] * cos};
        }
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = 0;
        for (int i = 0; i < 4; i++) {
            double[] p = turned[i], q = turned[(i + 1) % 4];
            if (p[1] >= 0) {
                minX = Math.min(minX, p[0]);
                maxX = Math.max(maxX, p[0]);
                maxY = Math.max(maxY, p[1]);
            }
            if ((p[1] < 0) != (q[1] < 0)) { // the edge crosses the ground
                double x = p[0] + (q[0] - p[0]) * (-p[1] / (q[1] - p[1]));
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
            }
        }
        return new double[] {HEAD_X + minX, 0, HEAD_Z - half, HEAD_X + maxX, maxY, HEAD_Z + half};
    }

    /** A box given facing north, turned to {@code facing}. */
    static VoxelShape turned(double[] b, Direction facing) {
        double[] from = turn(b[0], b[2], facing), to = turn(b[3], b[5], facing);
        return Shapes.box(Math.min(from[0], to[0]) / 16, b[1] / 16, Math.min(from[1], to[1]) / 16,
                Math.max(from[0], to[0]) / 16, b[4] / 16, Math.max(from[1], to[1]) / 16);
    }

    /** A point (x, z) of a grave facing north, in sixteenths, where it is for a grave facing {@code facing}. */
    public static double[] turn(double x, double z, Direction facing) {
        return switch (facing) {
            case EAST -> new double[] {16 - z, x};
            case SOUTH -> new double[] {16 - x, 16 - z};
            case WEST -> new double[] {z, 16 - x};
            default -> new double[] {x, z};
        };
    }
}
