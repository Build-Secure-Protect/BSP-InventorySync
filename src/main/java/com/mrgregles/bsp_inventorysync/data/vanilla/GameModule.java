package com.mrgregles.bsp_inventorysync.data.vanilla;

import com.mrgregles.bsp_inventorysync.api.DataModule;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;

/**
 * Game mode, abilities (flight and the rest, as vanilla saves them) and the respawn point (bed or
 * anchor: dimension, position, angle, forced). The game mode is applied first because changing it
 * resets the abilities.
 */
public final class GameModule implements DataModule {
    public static final String ID = "game";
    private static final String GAME_MODE = "GameMode";
    private static final String ABILITIES = "Abilities";
    private static final String RESPAWN = "Respawn";
    private static final String DIM = "Dim";
    private static final String POS = "Pos";
    private static final String ANGLE = "Angle";
    private static final String FORCED = "Forced";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void save(ServerPlayer player, CompoundTag out) {
        out.putInt(GAME_MODE, player.gameMode.getGameModeForPlayer().getId());
        CompoundTag abilities = new CompoundTag();
        player.getAbilities().addSaveData(abilities);
        out.put(ABILITIES, abilities);
        BlockPos pos = player.getRespawnPosition();
        if (pos != null) {
            CompoundTag respawn = new CompoundTag();
            respawn.putString(DIM, player.getRespawnDimension().location().toString());
            respawn.putLong(POS, pos.asLong());
            respawn.putFloat(ANGLE, player.getRespawnAngle());
            respawn.putBoolean(FORCED, player.isRespawnForced());
            out.put(RESPAWN, respawn);
        }
    }

    @Override
    public void load(ServerPlayer player, CompoundTag in) {
        if (in.contains(GAME_MODE, Tag.TAG_INT)) {
            player.setGameMode(GameType.byId(in.getInt(GAME_MODE)));
        }
        if (in.contains(ABILITIES, Tag.TAG_COMPOUND)) {
            player.getAbilities().loadSaveData(in.getCompound(ABILITIES));
            player.onUpdateAbilities();
        }
        if (in.contains(RESPAWN, Tag.TAG_COMPOUND)) {
            CompoundTag respawn = in.getCompound(RESPAWN);
            ResourceLocation dim = ResourceLocation.tryParse(respawn.getString(DIM));
            ResourceKey<Level> key = dim == null ? Level.OVERWORLD : ResourceKey.create(Registries.DIMENSION, dim);
            player.setRespawnPosition(key, BlockPos.of(respawn.getLong(POS)), respawn.getFloat(ANGLE), respawn.getBoolean(FORCED), false);
        } else {
            player.setRespawnPosition(Level.OVERWORLD, null, 0f, false, false);
        }
    }
}
