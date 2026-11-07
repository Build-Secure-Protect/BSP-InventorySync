package com.mrgregles.bsp_inventorysync.grave;

import net.minecraft.core.BlockPos;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.Level;

/**
 * The stone a grave is made of (D25 18a): it follows where the grave stands. Each kind has its own model,
 * written by {@code tools/gen_grave_assets.py} from vanilla textures.
 */
public enum GraveKind implements StringRepresentable {
    STONE("stone"),
    DEEPSLATE("deepslate"),
    BLACKSTONE("blackstone"),
    END_STONE("end_stone");

    private final String name;

    GraveKind(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    /** Blackstone in the Nether, end stone in the End, deepslate below y 0, stone everywhere else. */
    public static GraveKind forPlace(Level level, BlockPos pos) {
        if (level.dimension() == Level.NETHER) {
            return BLACKSTONE;
        }
        if (level.dimension() == Level.END) {
            return END_STONE;
        }
        return pos.getY() < 0 ? DEEPSLATE : STONE;
    }
}
