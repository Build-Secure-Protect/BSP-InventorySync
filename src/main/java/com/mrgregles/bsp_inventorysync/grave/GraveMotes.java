package com.mrgregles.bsp_inventorysync.grave;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.function.Function;

/**
 * The gold and neon green motes drifting up around a grave (D26 3a, 7a): about 3 a second, only while the player
 * watching is within 16 blocks, made on that player's own client through {@code level.addParticle}, so their
 * Particles setting (All, Decreased, Minimal) applies as to any vanilla particle. Nothing is sent from the server.
 * The block entity's ticker runs this on the client only ({@link GraveBlock#getTicker}).
 */
public final class GraveMotes {
    /** #FFD23A and #39FF14. */
    public static final DustParticleOptions GOLD = new DustParticleOptions(new Vector3f(0xFF / 255F, 0xD2 / 255F, 0x3A / 255F), 0.75F),
            GREEN = new DustParticleOptions(new Vector3f(0x39 / 255F, 0xFF / 255F, 0x14 / 255F), 0.75F);
    public static final double RANGE = 16;
    public static final float PER_SECOND = 3;

    /** Where the player watching a level stands, or null: set by the client's setup; on a server nobody watches. */
    private static volatile Function<Level, Vec3> viewer = level -> null;

    private GraveMotes() {}

    /** Set by the client: where its own player is, when in that level. */
    public static void setViewer(Function<Level, Vec3> where) {
        viewer = where;
    }

    /** One mote: what it is, where it starts, which way it is pushed (dust slows it and drifts it upwards). */
    public record Mote(DustParticleOptions dust, double x, double y, double z, double dx, double dy, double dz) {}

    /** The client's tick of a grave: perhaps one mote. Never on a server. */
    public static void tick(Level level, BlockPos pos, BlockState state, GraveBlockEntity grave) {
        if (!level.isClientSide) {
            return;
        }
        Mote m = next(level.random, pos, state.getShape(level, pos), viewer.apply(level));
        if (m != null) {
            level.addParticle(m.dust(), m.x(), m.y(), m.z(), m.dx(), m.dy(), m.dz());
        }
    }

    /**
     * This tick's mote, or null: none unless a player watches from within 16 blocks of the grave; then, on average,
     * 3 a second (20 ticks), gold or green alike, somewhere over the grave's footprint from the ground to the cap, in
     * the open air around the stone and the head ({@code shape}, the grave's own, relative to its block): a mote
     * started inside them would never be seen.
     */
    @Nullable
    public static Mote next(RandomSource random, BlockPos pos, VoxelShape shape, @Nullable Vec3 watcher) {
        if (watcher == null || watcher.distanceToSqr(Vec3.atCenterOf(pos)) > RANGE * RANGE) {
            return null;
        }
        if (random.nextFloat() >= PER_SECOND / 20F) {
            return null;
        }
        DustParticleOptions dust = random.nextBoolean() ? GOLD : GREEN;
        java.util.List<net.minecraft.world.phys.AABB> solid = shape.toAabbs();
        for (int tries = 0; tries < 16; tries++) {
            double x = (2 + random.nextDouble() * 12) / 16, z = (2 + random.nextDouble() * 12) / 16, y = random.nextDouble() * GraveShape.TOP / 16;
            Vec3 at = new Vec3(x, y, z);
            if (solid.stream().noneMatch(b -> b.contains(at))) {
                return new Mote(dust, pos.getX() + x, pos.getY() + y, pos.getZ() + z, 0, 1, 0);
            }
        }
        return null;
    }
}
