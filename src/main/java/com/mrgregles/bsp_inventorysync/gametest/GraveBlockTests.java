package com.mrgregles.bsp_inventorysync.gametest;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.VersionCheck;
import com.mrgregles.bsp_inventorysync.grave.GraveBlock;
import com.mrgregles.bsp_inventorysync.grave.GraveBlockEntity;
import com.mrgregles.bsp_inventorysync.grave.GraveKind;
import com.mrgregles.bsp_inventorysync.grave.GraveRegistry;
import com.mrgregles.bsp_inventorysync.grave.GraveService;
import com.mrgregles.bsp_inventorysync.grave.GraveText;
import com.mrgregles.bsp_inventorysync.grave.WorldGraveStore;
import com.mrgregles.bsp_inventorysync.storage.Grave;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The grave block (D25 17 to 21, task GR-2): a death puts it up facing the way the player faced, of the stone that
 * suits the place, naming its grave and when it is public; nothing breaks, blows up, pushes or washes it away; it has
 * no item and drops nothing; the clients get its owner and times (never its contents) with the server's clock; the
 * floating text says who may open it; and the version check wants this exact version. The model, the renderer and
 * the client's refusal are not covered here (no client in the GameTest server).
 */
@GameTestHolder(BSPInventorySync.MODID)
@PrefixGameTestTemplate(false)
public class GraveBlockTests {
    private static final String EMPTY = "empty";

    private static BlockState graveAt(GameTestHelper h, BlockPos abs) {
        return h.getLevel().getBlockState(abs);
    }

    @GameTest(template = EMPTY)
    public static void a_death_puts_up_a_headstone_facing_the_player_naming_its_grave(GameTestHelper h) {
        GraveService graves = new GraveService(h.getLevel().getServer(), new WorldGraveStore(), () -> null, 2);
        graves.setPrivateMs(60 * 60_000L);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        h.setBlock(new BlockPos(3, 0, 3), Blocks.STONE);
        BlockPos abs = h.absolutePos(new BlockPos(3, 1, 3));
        p.moveTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5, 270F, 0F); // looking east
        h.assertTrue(p.getDirection() == Direction.EAST, "facing east: " + p.getDirection());
        graves.recordLayout(p, h.getLevel().damageSources().fall());
        List<ItemEntity> drops = new ArrayList<>(List.of(new ItemEntity(h.getLevel(), p.getX(), p.getY(), p.getZ(), new ItemStack(Items.DIAMOND, 4))));
        Grave g = graves.onDrops(p, h.getLevel().damageSources().fall(), drops, 1_000_000);
        BlockState state = graveAt(h, abs);
        h.assertTrue(g != null && g.placed() && state.is(GraveRegistry.GRAVE.get()), "a grave block where the grave stands: " + state);
        h.assertTrue(state.getValue(GraveBlock.FACING) == Direction.EAST, "facing the way the player faced: " + state.getValue(GraveBlock.FACING));
        h.assertTrue(state.getValue(GraveBlock.KIND) == GraveKind.forPlace(h.getLevel(), abs), "of the stone for the place: " + state.getValue(GraveBlock.KIND));
        h.assertTrue(h.getLevel().getBlockEntity(abs) instanceof GraveBlockEntity be && g.id().equals(be.graveId()) && be.owner().equals(p.getUUID())
                && be.ownerName().equals(p.getGameProfile().getName()) && be.diedAt() == 1_000_000 && be.publicAt() == 1_000_000 + 3_600_000,
                "the block entity names the grave, its owner, and public an hour after the death");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void the_stone_follows_the_place(GameTestHelper h) {
        ServerLevel overworld = h.getLevel().getServer().overworld();
        h.assertTrue(GraveKind.forPlace(overworld, new BlockPos(0, 64, 0)) == GraveKind.STONE, "stone at y 64");
        h.assertTrue(GraveKind.forPlace(overworld, new BlockPos(0, 0, 0)) == GraveKind.STONE, "stone at y 0");
        h.assertTrue(GraveKind.forPlace(overworld, new BlockPos(0, -1, 0)) == GraveKind.DEEPSLATE, "deepslate below y 0");
        ServerLevel nether = h.getLevel().getServer().getLevel(Level.NETHER), end = h.getLevel().getServer().getLevel(Level.END);
        h.assertTrue(nether != null && GraveKind.forPlace(nether, new BlockPos(0, 64, 0)) == GraveKind.BLACKSTONE, "blackstone in the Nether");
        h.assertTrue(nether != null && GraveKind.forPlace(nether, new BlockPos(0, -10, 0)) == GraveKind.BLACKSTONE, "blackstone at any height there");
        h.assertTrue(end != null && GraveKind.forPlace(end, new BlockPos(0, 64, 0)) == GraveKind.END_STONE, "end stone in the End");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void nothing_breaks_blows_up_pushes_or_washes_away_a_grave(GameTestHelper h) {
        BlockPos abs = h.absolutePos(new BlockPos(4, 1, 4));
        h.setBlock(new BlockPos(4, 0, 4), Blocks.STONE);
        h.assertTrue(GraveService.placeBlock(h.getLevel(), abs, Direction.NORTH, UUID.randomUUID(), UUID.randomUUID(), "Steve", 0, 0), "placed");
        BlockState state = graveAt(h, abs);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);

        BlockEvent.BreakEvent br = new BlockEvent.BreakEvent(h.getLevel(), abs, state, p);
        MinecraftForge.EVENT_BUS.post(br);
        h.assertTrue(br.isCanceled(), "breaking it is refused (any game mode)");
        h.assertTrue(state.getDestroySpeed(h.getLevel(), abs) < 0, "unbreakable like bedrock");

        Explosion explosion = new Explosion(h.getLevel(), null, abs.getX() + 0.5, abs.getY() + 0.5, abs.getZ() + 0.5, 4F, false, Explosion.BlockInteraction.DESTROY);
        explosion.getToBlow().add(abs);
        explosion.getToBlow().add(abs.east());
        MinecraftForge.EVENT_BUS.post(new ExplosionEvent.Detonate(h.getLevel(), explosion, new ArrayList<>()));
        h.assertTrue(!explosion.getToBlow().contains(abs) && explosion.getToBlow().contains(abs.east()), "an explosion of any power leaves the grave out, and only the grave");
        h.assertTrue(state.getBlock().getExplosionResistance(state, h.getLevel(), abs, explosion) >= 3_600_000F, "and resists it anyway");

        h.assertTrue(state.getPistonPushReaction() == PushReaction.BLOCK, "pistons cannot move it");
        h.assertTrue(!state.canBeReplaced(Fluids.WATER) && !state.canBeReplaced(Fluids.LAVA) && state.isSolid(), "water and lava cannot wash it away");
        h.assertTrue(state.is(BlockTags.WITHER_IMMUNE) && state.is(BlockTags.DRAGON_IMMUNE) && state.is(BlockTags.FEATURES_CANNOT_REPLACE),
                "the wither, the dragon and growing trees leave it alone");
        h.assertTrue(!state.isFlammable(h.getLevel(), abs, Direction.UP), "fire cannot burn it");
        h.assertTrue(state.getLightEmission(h.getLevel(), abs) == GraveBlock.LIGHT, "a faint glow: " + state.getLightEmission(h.getLevel(), abs));
        h.assertTrue(GraveRegistry.GRAVE.get().asItem() == Items.AIR, "no item");
        h.assertTrue(GraveRegistry.GRAVE.get().getLootTable().equals(BuiltInLootTables.EMPTY), "drops nothing");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void clients_get_the_owner_and_times_with_the_servers_clock_and_never_the_contents(GameTestHelper h) {
        BlockPos abs = h.absolutePos(new BlockPos(2, 1, 2));
        h.setBlock(new BlockPos(2, 0, 2), Blocks.STONE);
        UUID grave = UUID.randomUUID(), owner = UUID.randomUUID();
        GraveService.placeBlock(h.getLevel(), abs, Direction.SOUTH, grave, owner, "Alex", 5_000, 3_605_000);
        GraveBlockEntity be = (GraveBlockEntity) h.getLevel().getBlockEntity(abs);
        CompoundTag sent = be.getUpdateTag();
        h.assertTrue(sent.hasUUID("Owner") && sent.getString("OwnerName").equals("Alex") && sent.getLong("PublicAt") == 3_605_000 && sent.contains("ServerNow"), "owner, name, time, clock: " + sent);
        h.assertTrue(sent.getAllKeys().stream().noneMatch(k -> k.toLowerCase().contains("item")), "no contents: " + sent.getAllKeys());

        // a client whose clock runs ten minutes slow still counts down from the server's time
        sent.putLong("ServerNow", System.currentTimeMillis() + 600_000);
        GraveBlockEntity client = new GraveBlockEntity(abs, be.getBlockState());
        client.load(sent);
        long off = client.serverNow(System.currentTimeMillis()) - System.currentTimeMillis();
        h.assertTrue(Math.abs(off - 600_000) < 2_000, "the client corrects its clock by the server's: " + off);
        CompoundTag saved = be.saveWithoutMetadata();
        h.assertTrue(!saved.contains("ServerNow") && grave.equals(saved.getUUID("Grave")), "the world's copy keeps the grave id and no clock");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void the_floating_text_says_who_may_open_it(GameTestHelper h) {
        UUID owner = UUID.randomUUID(), other = UUID.randomUUID();
        long publicAt = 10_000_000;
        GraveText.Lines mine = GraveText.lines(owner, owner, "Steve", publicAt, publicAt - 3_492_000);
        h.assertTrue(mine.name().equals("Steve") && mine.mayOpen() && mine.status().equals("Right-click to open"), "the owner may open it at once: " + mine);
        GraveText.Lines theirs = GraveText.lines(other, owner, "Steve", publicAt, publicAt - 3_492_000);
        h.assertTrue(!theirs.mayOpen() && theirs.status().equals("Public in 58:12"), "anyone else sees the wait: " + theirs);
        h.assertTrue(GraveText.lines(other, owner, "Steve", publicAt, publicAt - 3_729_000).status().equals("Public in 1:02:09"), "hours from an hour up");
        h.assertTrue(GraveText.lines(other, owner, "Steve", publicAt, publicAt).mayOpen(), "and may open it once it is public");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void players_need_this_exact_version_of_the_mod(GameTestHelper h) {
        String mine = ModList.get().getModContainerById(BSPInventorySync.MODID).orElseThrow().getModInfo().getVersion().toString();
        h.assertTrue(!VersionCheck.version().isEmpty() && VersionCheck.version().equals(mine), "the channel's version is the mod's: " + VersionCheck.version() + " vs " + mine);
        h.succeed();
    }
}
