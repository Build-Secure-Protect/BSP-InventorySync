package com.mrgregles.bsp_inventorysync.gametest;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.api.DataModule;
import com.mrgregles.bsp_inventorysync.data.ItemCodec;
import com.mrgregles.bsp_inventorysync.data.ItemRules;
import com.mrgregles.bsp_inventorysync.data.Modules;
import com.mrgregles.bsp_inventorysync.data.PlayerSnapshotCodec;
import com.mrgregles.bsp_inventorysync.data.vanilla.EnderChestModule;
import com.mrgregles.bsp_inventorysync.data.vanilla.ExperienceModule;
import com.mrgregles.bsp_inventorysync.data.vanilla.GameModule;
import com.mrgregles.bsp_inventorysync.data.vanilla.InventoryModule;
import com.mrgregles.bsp_inventorysync.data.vanilla.VitalsModule;
import net.minecraft.advancements.Advancement;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.function.Predicate;

/**
 * Round trips through the vanilla data modules: a mock player is set up, captured, changed, restored
 * from the snapshot and compared. Also the placeholder rule for unknown items, the exclusion rule the
 * totem filter will use, the isolation of a failing module, and the bytes the store receives.
 */
@GameTestHolder(BSPInventorySync.MODID)
@PrefixGameTestTemplate(false)
public class DataModuleTests {
    private static final String EMPTY = "empty";

    private static void same(GameTestHelper h, ItemStack expected, ItemStack actual, String what) {
        h.assertTrue(ItemStack.matches(expected, actual), what + ": expected " + expected + " " + expected.getTag() + ", got " + actual + " " + actual.getTag());
    }

    @GameTest(template = EMPTY)
    public static void inventory_round_trips_items_armour_offhand_selected_slot_and_cursor(GameTestHelper h) {
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        Inventory inv = p.getInventory();
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.enchant(Enchantments.SHARPNESS, 3);
        sword.setDamageValue(17);
        ItemStack cobble = new ItemStack(Items.COBBLESTONE, 64);
        ItemStack boots = new ItemStack(Items.IRON_BOOTS);
        ItemStack helmet = new ItemStack(Items.GOLDEN_HELMET);
        ItemStack shield = new ItemStack(Items.SHIELD);
        ItemStack apple = new ItemStack(Items.APPLE, 3);
        inv.setItem(0, sword.copy());
        inv.setItem(9, cobble.copy());
        inv.setItem(35, new ItemStack(Items.TORCH, 7));
        inv.setItem(36, boots.copy());   // feet
        inv.setItem(39, helmet.copy());  // head
        inv.setItem(40, shield.copy());  // offhand
        inv.selected = 5;
        p.containerMenu.setCarried(apple.copy());

        CompoundTag snapshot = PlayerSnapshotCodec.capture(p);
        h.assertTrue(PlayerSnapshotCodec.failedOnSave(snapshot).isEmpty(), "every module saved");
        CompoundTag module = PlayerSnapshotCodec.module(snapshot, InventoryModule.ID);
        h.assertTrue(module != null && module.getList("Items", Tag.TAG_COMPOUND).size() == 6, "six filled slots recorded: " + module);

        inv.clearContent();
        inv.setItem(2, new ItemStack(Items.DIRT)); // something the restore must remove
        inv.selected = 0;
        p.containerMenu.setCarried(ItemStack.EMPTY);

        PlayerSnapshotCodec.Result result = PlayerSnapshotCodec.apply(p, snapshot);
        h.assertTrue(result.clean() && result.applied().contains(InventoryModule.ID), "inventory applied: " + result);
        same(h, sword, inv.getItem(0), "enchanted, damaged sword");
        same(h, cobble, inv.getItem(9), "a full stack");
        h.assertTrue(inv.getItem(35).is(Items.TORCH) && inv.getItem(35).getCount() == 7, "last main slot");
        same(h, boots, inv.getItem(36), "boots");
        same(h, helmet, inv.getItem(39), "helmet");
        same(h, shield, inv.getItem(40), "offhand");
        h.assertTrue(inv.getItem(2).isEmpty(), "the dirt added after the snapshot is gone");
        h.assertTrue(inv.selected == 5, "selected slot restored, got " + inv.selected);
        same(h, apple, p.containerMenu.getCarried(), "cursor");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void ender_chest_round_trips(GameTestHelper h) {
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        var chest = p.getEnderChestInventory();
        ItemStack pearls = new ItemStack(Items.ENDER_PEARL, 16);
        ItemStack book = new ItemStack(Items.WRITABLE_BOOK);
        book.getOrCreateTag().putString("title", "notes");
        chest.setItem(0, pearls.copy());
        chest.setItem(26, book.copy());

        CompoundTag snapshot = PlayerSnapshotCodec.capture(p);
        chest.clearContent();
        chest.setItem(5, new ItemStack(Items.STONE));
        PlayerSnapshotCodec.Result result = PlayerSnapshotCodec.apply(p, snapshot);
        h.assertTrue(result.applied().contains(EnderChestModule.ID), "ender chest applied: " + result);
        same(h, pearls, chest.getItem(0), "pearls");
        same(h, book, chest.getItem(26), "book with NBT in the last slot");
        h.assertTrue(chest.getItem(5).isEmpty(), "the stone added after the snapshot is gone");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void vitals_experience_game_mode_abilities_and_respawn_round_trip(GameTestHelper h) {
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        p.setHealth(7.5f);
        p.setAbsorptionAmount(4f);
        p.setAirSupply(120);
        p.getFoodData().setFoodLevel(3);
        p.getFoodData().setSaturation(1.5f);
        p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 600, 1));
        p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 1200, 0, true, false));
        p.setExperienceLevels(12);
        p.experienceProgress = 0.4f;
        p.totalExperience = 1234;
        p.setScore(99);
        p.setGameMode(GameType.CREATIVE);
        p.getAbilities().flying = true;
        p.onUpdateAbilities();
        BlockPos bed = new BlockPos(10, 64, -20);
        p.setRespawnPosition(Level.NETHER, bed, 90f, true, false);

        CompoundTag snapshot = PlayerSnapshotCodec.capture(p);

        p.setGameMode(GameType.SURVIVAL);
        p.setHealth(20f);
        p.setAbsorptionAmount(0f);
        p.setAirSupply(300);
        p.getFoodData().setFoodLevel(20);
        p.getFoodData().setSaturation(5f);
        p.removeAllEffects();
        p.setExperienceLevels(0);
        p.experienceProgress = 0f;
        p.totalExperience = 0;
        p.setScore(0);
        p.setRespawnPosition(Level.OVERWORLD, null, 0f, false, false);

        PlayerSnapshotCodec.Result result = PlayerSnapshotCodec.apply(p, snapshot);
        h.assertTrue(result.clean(), "all modules applied: " + result);
        h.assertTrue(result.applied().containsAll(List.of(VitalsModule.ID, ExperienceModule.ID, GameModule.ID)), "vitals, experience and game applied: " + result);
        h.assertTrue(p.getHealth() == 7.5f, "health, got " + p.getHealth());
        h.assertTrue(p.getAbsorptionAmount() == 4f, "absorption, got " + p.getAbsorptionAmount());
        h.assertTrue(p.getAirSupply() == 120, "air, got " + p.getAirSupply());
        h.assertTrue(p.getFoodData().getFoodLevel() == 3 && p.getFoodData().getSaturationLevel() == 1.5f, "food and saturation, got " + p.getFoodData().getFoodLevel() + " / " + p.getFoodData().getSaturationLevel());
        MobEffectInstance speed = p.getEffect(MobEffects.MOVEMENT_SPEED);
        MobEffectInstance night = p.getEffect(MobEffects.NIGHT_VISION);
        h.assertTrue(speed != null && speed.getAmplifier() == 1 && speed.getDuration() == 600, "speed effect, got " + speed);
        h.assertTrue(night != null && night.isAmbient() && !night.isVisible(), "night vision with its flags, got " + night);
        h.assertTrue(p.getActiveEffects().size() == 2, "exactly the two effects, got " + p.getActiveEffects());
        h.assertTrue(p.experienceLevel == 12 && Math.abs(p.experienceProgress - 0.4f) < 0.001f && p.totalExperience == 1234, "experience, got " + p.experienceLevel + " " + p.experienceProgress + " " + p.totalExperience);
        h.assertTrue(p.getScore() == 99, "score, got " + p.getScore());
        h.assertTrue(p.gameMode.getGameModeForPlayer() == GameType.CREATIVE, "game mode, got " + p.gameMode.getGameModeForPlayer());
        h.assertTrue(p.getAbilities().flying && p.getAbilities().mayfly, "flying restored");
        h.assertTrue(bed.equals(p.getRespawnPosition()) && p.getRespawnDimension() == Level.NETHER && p.getRespawnAngle() == 90f && p.isRespawnForced(), "respawn point, got " + p.getRespawnPosition() + " in " + p.getRespawnDimension());
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void unknown_items_become_placeholders_and_come_back_as_the_original(GameTestHelper h) {
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        CompoundTag snapshot = PlayerSnapshotCodec.capture(p);
        CompoundTag original = new CompoundTag();
        original.putString("id", "nosuchmod:widget");
        original.putByte("Count", (byte) 2);
        CompoundTag tag = new CompoundTag();
        tag.putInt("Charge", 41);
        original.put("tag", tag);
        CompoundTag entry = new CompoundTag();
        entry.putInt("Slot", 4);
        entry.put("Item", original.copy());
        ListTag items = PlayerSnapshotCodec.module(snapshot, InventoryModule.ID).getList("Items", Tag.TAG_COMPOUND);
        items.add(entry);

        PlayerSnapshotCodec.Result result = PlayerSnapshotCodec.apply(p, snapshot);
        h.assertTrue(result.clean(), "applied: " + result);
        ItemStack placeholder = p.getInventory().getItem(4);
        h.assertTrue(placeholder.is(Items.PAPER) && placeholder.getCount() == 2, "a sheet of paper with the original count, got " + placeholder);
        h.assertTrue(ItemCodec.isPlaceholder(placeholder), "marked as a placeholder");
        h.assertTrue(placeholder.getHoverName().getString().contains("nosuchmod:widget"), "named after the missing item, got " + placeholder.getHoverName().getString());
        h.assertTrue(placeholder.getTag().getCompound(ItemCodec.ORIGINAL).getCompound("tag").getInt("Charge") == 41, "the original NBT is kept");

        placeholder.setCount(1); // the player split the stack while it was paper
        CompoundTag again = PlayerSnapshotCodec.capture(p);
        ListTag savedItems = PlayerSnapshotCodec.module(again, InventoryModule.ID).getList("Items", Tag.TAG_COMPOUND);
        h.assertTrue(savedItems.size() == 1, "one slot saved: " + savedItems);
        CompoundTag saved = savedItems.getCompound(0).getCompound("Item");
        h.assertTrue(saved.getString("id").equals("nosuchmod:widget") && saved.getByte("Count") == 1 && saved.getCompound("tag").getInt("Charge") == 41, "the original comes back with the new count, got " + saved);
        h.assertTrue(ItemCodec.load(ItemCodec.save(new ItemStack(Items.STONE, 5))).is(Items.STONE), "known items are untouched by the codec");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void excluded_items_are_left_out_of_the_snapshot(GameTestHelper h) {
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        Predicate<ItemStack> noBedrock = s -> s.is(Items.BEDROCK);
        ItemRules.addExclusion(noBedrock);
        try {
            p.getInventory().setItem(3, new ItemStack(Items.BEDROCK));
            p.getInventory().setItem(4, new ItemStack(Items.DIRT));
            p.getInventory().setItem(40, new ItemStack(Items.BEDROCK));
            p.containerMenu.setCarried(new ItemStack(Items.BEDROCK));
            CompoundTag snapshot = PlayerSnapshotCodec.capture(p);
            CompoundTag module = PlayerSnapshotCodec.module(snapshot, InventoryModule.ID);
            ListTag items = module.getList("Items", Tag.TAG_COMPOUND);
            h.assertTrue(items.size() == 1 && items.getCompound(0).getInt("Slot") == 4, "only the dirt is saved: " + items);
            h.assertTrue(!module.contains("Cursor"), "an excluded cursor item is not saved");
            p.getInventory().clearContent();
            PlayerSnapshotCodec.apply(p, snapshot);
            h.assertTrue(p.getInventory().getItem(3).isEmpty() && p.getInventory().getItem(40).isEmpty() && p.getInventory().getItem(4).is(Items.DIRT), "restored without the excluded items");
        } finally {
            ItemRules.removeExclusion(noBedrock);
        }
        h.assertTrue(!ItemRules.excluded(new ItemStack(Items.BEDROCK)), "the rule is gone again");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_failing_module_does_not_stop_the_others(GameTestHelper h) {
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        DataModule broken = new DataModule() {
            @Override
            public String id() {
                return "test_broken";
            }

            @Override
            public void save(ServerPlayer player, CompoundTag out) {
                throw new IllegalStateException("save boom");
            }

            @Override
            public void load(ServerPlayer player, CompoundTag in) {
                throw new IllegalStateException("load boom");
            }
        };
        DataModule quiet = new DataModule() {
            @Override
            public String id() {
                return "test_quiet";
            }

            @Override
            public void save(ServerPlayer player, CompoundTag out) {
                out.putBoolean("Saved", true);
            }

            @Override
            public void load(ServerPlayer player, CompoundTag in) {
            }
        };
        Modules.register(broken);
        Modules.register(quiet);
        try {
            p.getInventory().setItem(1, new ItemStack(Items.GOLD_INGOT, 9));
            CompoundTag snapshot = PlayerSnapshotCodec.capture(p);
            h.assertTrue(PlayerSnapshotCodec.failedOnSave(snapshot).equals(List.of("test_broken")), "the broken module is named: " + PlayerSnapshotCodec.failedOnSave(snapshot));
            h.assertTrue(PlayerSnapshotCodec.module(snapshot, "test_broken") == null, "and left out");
            h.assertTrue(PlayerSnapshotCodec.module(snapshot, "test_quiet").getBoolean("Saved"), "a module after it still saved");
            h.assertTrue(PlayerSnapshotCodec.module(snapshot, InventoryModule.ID) != null, "the inventory saved");

            // give the broken module a compound so load is attempted
            snapshot.getCompound("Modules").put("test_broken", new CompoundTag());
            p.getInventory().clearContent();
            PlayerSnapshotCodec.Result result = PlayerSnapshotCodec.apply(p, snapshot);
            h.assertTrue(result.failed().equals(List.of("test_broken")) && result.applied().contains("test_quiet") && result.applied().contains(InventoryModule.ID), "load isolation: " + result);
            h.assertTrue(p.getInventory().getItem(1).is(Items.GOLD_INGOT), "the inventory was still restored");
            h.assertTrue(result.missing().isEmpty(), "nothing missing: " + result);

            snapshot.getCompound("Modules").remove("test_quiet");
            PlayerSnapshotCodec.Result partial = PlayerSnapshotCodec.apply(p, snapshot);
            h.assertTrue(partial.missing().equals(List.of("test_quiet")), "a module absent from the snapshot is reported missing, not failed: " + partial);
        } finally {
            Modules.unregister("test_broken");
            Modules.unregister("test_quiet");
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void encode_and_decode_are_gzip_and_round_trip(GameTestHelper h) {
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        p.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 12));
        CompoundTag snapshot = PlayerSnapshotCodec.capture(p);
        byte[] bytes = PlayerSnapshotCodec.encode(snapshot);
        h.assertTrue(bytes.length > 2 && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B, "GZIP magic bytes");
        CompoundTag back = PlayerSnapshotCodec.decode(bytes);
        h.assertTrue(back.equals(snapshot), "decode gives the same compound");
        h.assertTrue(back.getInt("Format") == PlayerSnapshotCodec.FORMAT, "format recorded");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void advancements_round_trip_through_the_player_file(GameTestHelper h) {
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        Advancement root = p.getServer().getAdvancements().getAdvancement(ResourceLocation.parse("minecraft:story/root"));
        h.assertTrue(root != null, "vanilla's story/root advancement exists");
        String criterion = root.getCriteria().keySet().iterator().next();
        p.getAdvancements().getOrStartProgress(root).grantProgress(criterion); // progress without the reward
        h.assertTrue(p.getAdvancements().getOrStartProgress(root).isDone(), "done before the snapshot");

        CompoundTag snapshot = PlayerSnapshotCodec.capture(p);
        h.assertTrue(PlayerSnapshotCodec.failedOnSave(snapshot).isEmpty(), "advancements saved: " + PlayerSnapshotCodec.failedOnSave(snapshot));
        h.assertTrue(PlayerSnapshotCodec.module(snapshot, "advancements").getByteArray("Json").length > 2, "the file is in the snapshot");

        p.getAdvancements().getOrStartProgress(root).revokeProgress(criterion);
        h.assertTrue(!p.getAdvancements().getOrStartProgress(root).isDone(), "revoked");
        PlayerSnapshotCodec.Result result = PlayerSnapshotCodec.apply(p, snapshot);
        h.assertTrue(result.applied().contains("advancements"), "advancements applied: " + result);
        h.assertTrue(p.getAdvancements().getOrStartProgress(root).isDone(), "done again after the restore");
        h.succeed();
    }
}
