package com.mrgregles.bsp_inventorysync.data.vanilla;

import com.mrgregles.bsp_inventorysync.api.DataModule;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

/**
 * Game mode and abilities (flight and the rest, as vanilla saves them). The game mode is applied first
 * because changing it resets the abilities.
 *
 * <p>The respawn point (bed or anchor) is not synced (D16): each server has its own world, so a bed on
 * one is nowhere on another, and a point applied there would be cleared at the next death and that
 * cleared point carried back. Each server keeps the point the player set in its own world, in its own
 * player file, as vanilla does. A {@code Respawn} entry in a snapshot taken before D16 is ignored.
 */
public final class GameModule implements DataModule {
    public static final String ID = "game";
    private static final String GAME_MODE = "GameMode";
    private static final String ABILITIES = "Abilities";

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
    }
}
