package com.mrgregles.bsp_inventorysync.data.vanilla;

import com.mrgregles.bsp_inventorysync.api.DataModule;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

/** Experience level, progress within the level, total points, and the score shown on death. */
public final class ExperienceModule implements DataModule {
    public static final String ID = "experience";
    private static final String LEVEL = "Level";
    private static final String PROGRESS = "Progress";
    private static final String TOTAL = "Total";
    private static final String SCORE = "Score";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void save(ServerPlayer player, CompoundTag out) {
        out.putInt(LEVEL, player.experienceLevel);
        out.putFloat(PROGRESS, player.experienceProgress);
        out.putInt(TOTAL, player.totalExperience);
        out.putInt(SCORE, player.getScore());
    }

    @Override
    public void load(ServerPlayer player, CompoundTag in) {
        player.setExperienceLevels(Math.max(0, in.getInt(LEVEL))); // also marks the client's copy stale
        player.experienceProgress = Math.max(0f, Math.min(in.getFloat(PROGRESS), 0.999f));
        player.totalExperience = Math.max(0, in.getInt(TOTAL));
        player.setScore(in.getInt(SCORE));
    }
}
