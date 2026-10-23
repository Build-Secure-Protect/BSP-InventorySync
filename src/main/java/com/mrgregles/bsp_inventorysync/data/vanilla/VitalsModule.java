package com.mrgregles.bsp_inventorysync.data.vanilla;

import com.mrgregles.bsp_inventorysync.api.DataModule;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;

/**
 * Health, absorption, air, hunger (food level, saturation, exhaustion and the food tick timer, as
 * vanilla saves them) and the active effects. Runs after the inventory module, because armour and
 * modded items change the maximum health the restored health is clamped to.
 */
public final class VitalsModule implements DataModule {
    public static final String ID = "vitals";
    private static final String HEALTH = "Health";
    private static final String ABSORPTION = "Absorption";
    private static final String AIR = "Air";
    private static final String FOOD = "Food";
    private static final String EFFECTS = "Effects";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void save(ServerPlayer player, CompoundTag out) {
        out.putFloat(HEALTH, player.getHealth());
        out.putFloat(ABSORPTION, player.getAbsorptionAmount());
        out.putInt(AIR, player.getAirSupply());
        CompoundTag food = new CompoundTag();
        player.getFoodData().addAdditionalSaveData(food);
        out.put(FOOD, food);
        ListTag effects = new ListTag();
        for (MobEffectInstance effect : player.getActiveEffects()) {
            effects.add(effect.save(new CompoundTag()));
        }
        out.put(EFFECTS, effects);
    }

    /** The health a vitals compound holds, or -1 when there is none (or no compound). */
    public static float savedHealth(@javax.annotation.Nullable CompoundTag module) {
        return module != null && module.contains(HEALTH, Tag.TAG_FLOAT) ? module.getFloat(HEALTH) : -1f;
    }

    @Override
    public void load(ServerPlayer player, CompoundTag in) {
        if (in.contains(FOOD, Tag.TAG_COMPOUND)) {
            player.getFoodData().readAdditionalSaveData(in.getCompound(FOOD));
        }
        player.removeAllEffects();
        for (Tag t : in.getList(EFFECTS, Tag.TAG_COMPOUND)) {
            MobEffectInstance effect = MobEffectInstance.load((CompoundTag) t);
            if (effect != null) {
                player.addEffect(effect);
            }
        }
        if (in.contains(HEALTH, Tag.TAG_FLOAT)) {
            float health = in.getFloat(HEALTH);
            player.setHealth(Math.max(1f, Math.min(health, player.getMaxHealth()))); // a restore never lands a dead player
        }
        player.setAbsorptionAmount(Math.max(0f, in.getFloat(ABSORPTION)));
        if (in.contains(AIR, Tag.TAG_INT)) {
            player.setAirSupply(in.getInt(AIR));
        }
    }
}
