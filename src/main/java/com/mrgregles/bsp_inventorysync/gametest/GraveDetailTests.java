package com.mrgregles.bsp_inventorysync.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.grave.GraveBlock;
import com.mrgregles.bsp_inventorysync.grave.GraveBlockEntity;
import com.mrgregles.bsp_inventorysync.grave.GraveKind;
import com.mrgregles.bsp_inventorysync.grave.GraveLean;
import com.mrgregles.bsp_inventorysync.grave.GraveMotes;
import com.mrgregles.bsp_inventorysync.grave.GraveRegistry;
import com.mrgregles.bsp_inventorysync.grave.GraveService;
import com.mrgregles.bsp_inventorysync.grave.GraveShape;
import com.mrgregles.bsp_inventorysync.grave.GraveSpot;
import com.mrgregles.bsp_inventorysync.grave.WorldGraveStore;
import com.mrgregles.bsp_inventorysync.storage.Grave;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The grave's detail (D26, D27; tasks GD-1 to GD-3): the size B headstone and its shape for every facing, the model
 * the generator writes agreeing with that shape and drawing the sigil full-bright. How it looks in game (the glow by
 * night, the skin, the motes) is not covered here: there is no client in the GameTest server.
 */
@GameTestHolder(BSPInventorySync.MODID)
@PrefixGameTestTemplate(false)
public class GraveDetailTests {
    private static final String EMPTY = "empty";

    /** Whether a point of a grave facing north, in sixteenths, is inside the shape of a grave facing {@code facing}. */
    static boolean inside(VoxelShape shape, Direction facing, double x, double y, double z) {
        double[] at = GraveShape.turn(x, z, facing);
        Vec3 p = new Vec3(at[0] / 16, y / 16, at[1] / 16);
        return shape.toAabbs().stream().anyMatch(b -> b.contains(p));
    }

    private static VoxelShape shapeOf(GameTestHelper h, Direction facing, BlockPos pos) {
        return shapeOf(h, facing, GraveLean.LEFT, pos);
    }

    private static VoxelShape shapeOf(GameTestHelper h, Direction facing, GraveLean lean, BlockPos pos) {
        BlockState state = GraveRegistry.GRAVE.get().stateFor(facing, GraveKind.STONE, lean);
        return state.getShape(h.getLevel(), pos);
    }

    /** A grave id whose head leans {@code lean}. */
    private static UUID idLeaning(GraveLean lean) {
        UUID id;
        do {
            id = UUID.randomUUID();
        } while (GraveLean.of(id) != lean);
        return id;
    }

    @GameTest(template = EMPTY)
    public static void the_headstone_is_size_b_and_its_shape_follows_it_for_every_facing(GameTestHelper h) {
        BlockPos pos = h.absolutePos(new BlockPos(1, 1, 1));
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            BlockState state = GraveRegistry.GRAVE.get().stateFor(facing, GraveKind.STONE);
            VoxelShape shape = shapeOf(h, facing, pos);
            String f = facing.getName();
            h.assertTrue(inside(shape, facing, 8, 1, 12) && inside(shape, facing, 0.5, 1, 15.5) && inside(shape, facing, 15.5, 1, 8.5),
                    f + ": the base fills the block's width over its back half");
            h.assertTrue(inside(shape, facing, 8, 10, 12) && inside(shape, facing, 1.5, 15.5, 10.5) && inside(shape, facing, 14.5, 2.5, 13.5),
                    f + ": the stone, 14 wide and 14 tall");
            h.assertTrue(inside(shape, facing, 8, 18.5, 12) && inside(shape, facing, 3.5, 16.5, 12), f + ": the cap above it");
            h.assertTrue(!inside(shape, facing, 8, 19.5, 12) && !inside(shape, facing, 2.5, 18, 12), f + ": nothing above or beside the cap");
            h.assertTrue(!inside(shape, facing, 0.5, 10, 12) && !inside(shape, facing, 8, 10, 15) && !inside(shape, facing, 8, 10, 9),
                    f + ": nothing beside, behind or in front of the stone");
            h.assertTrue(!inside(shape, facing, 1, 1, 4) && !inside(shape, facing, 15, 1, 4), f + ": the front corners are open");
            h.assertTrue(Math.abs(shape.max(Direction.Axis.Y) - 19 / 16.0) < 1e-9, f + ": a little over a block tall: " + shape.max(Direction.Axis.Y));
            h.assertTrue(state.getCollisionShape(h.getLevel(), pos).toAabbs().equals(shape.toAabbs()), f + ": what you bump into is what you click");
        }
        // the same, written out in the world's own terms: the stone stands across the back of the block, the back being
        // the side away from the way the grave faces (as the block state turns the model)
        double[][] stoneAt = {{8, 12}, {4, 8}, {8, 4}, {12, 8}}, frontAt = {{8, 4}, {12, 8}, {8, 12}, {4, 8}};
        Direction[] order = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
        for (int i = 0; i < 4; i++) {
            VoxelShape shape = shapeOf(h, order[i], pos);
            Vec3 stone = new Vec3(stoneAt[i][0] / 16, 10 / 16.0, stoneAt[i][1] / 16), front = new Vec3(frontAt[i][0] / 16, 10 / 16.0, frontAt[i][1] / 16);
            h.assertTrue(shape.toAabbs().stream().anyMatch(b -> b.contains(stone)) && shape.toAabbs().stream().noneMatch(b -> b.contains(front)),
                    order[i].getName() + ": the stone at the back, open in front");
        }
        h.assertTrue(GraveRegistry.GRAVE.get().stateFor(Direction.UP, GraveKind.STONE).getValue(GraveBlock.FACING) == Direction.NORTH, "up counts as north");
        h.succeed();
    }

    private static JsonObject resource(String path) throws java.io.IOException {
        Path file = ModList.get().getModFileById(BSPInventorySync.MODID).getFile().findResource(path);
        return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    }

    private static double[] box(JsonObject element) {
        JsonArray from = element.getAsJsonArray("from"), to = element.getAsJsonArray("to");
        return new double[] {from.get(0).getAsDouble(), from.get(1).getAsDouble(), from.get(2).getAsDouble(),
                to.get(0).getAsDouble(), to.get(1).getAsDouble(), to.get(2).getAsDouble()};
    }

    @GameTest(template = EMPTY)
    public static void the_model_is_the_shape_with_a_full_bright_sigil_front_and_back(GameTestHelper h) throws java.io.IOException {
        List<double[]> boxes = GraveShape.boxes();
        for (GraveKind kind : GraveKind.values()) {
            String name = kind.name().toLowerCase(Locale.ROOT);
            JsonObject model = resource("assets/" + BSPInventorySync.MODID + "/models/block/grave_" + name + ".json");
            JsonArray elements = model.getAsJsonArray("elements");
            for (int i = 0; i < boxes.size(); i++) {
                h.assertTrue(java.util.Arrays.equals(box(elements.get(i).getAsJsonObject()), boxes.get(i)),
                        name + ": the model's box " + i + " is the shape's: " + elements.get(i));
            }
            h.assertTrue("minecraft:cutout".equals(model.get("render_type").getAsString()), name + ": drawn in the cutout layer, not mipmapped (the sigil's strokes would fade)");
            h.assertTrue((BSPInventorySync.MODID + ":block/grave_sigil").equals(model.getAsJsonObject("textures").get("sigil").getAsString()), name + ": the sigil texture");
            int sigils = 0;
            boolean front = false, back = false;
            double[] stone = boxes.get(1);
            for (JsonElement e : elements) {
                JsonObject el = e.getAsJsonObject();
                if (!el.has("forge_data")) {
                    continue;
                }
                sigils++;
                JsonObject light = el.getAsJsonObject("forge_data");
                h.assertTrue(light.get("block_light").getAsInt() == 15 && light.get("sky_light").getAsInt() == 15 && !el.get("shade").getAsBoolean(),
                        name + ": the sigil is full-bright and unshaded: " + el);
                double[] b = box(el);
                h.assertTrue(b[0] == stone[0] && b[3] == stone[3] && b[1] == stone[1] && b[4] == stone[4] && b[2] == b[5], name + ": flat, over the stone's whole face: " + el);
                JsonObject faces = el.getAsJsonObject("faces");
                front |= faces.has("north") && b[2] < stone[2] && b[2] >= stone[2] - 0.25;
                back |= faces.has("south") && b[2] > stone[5] && b[2] <= stone[5] + 0.25;
                h.assertTrue(faces.size() == 1 && faces.entrySet().iterator().next().getValue().getAsJsonObject().get("texture").getAsString().equals("#sigil"), name + ": one face, the sigil");
            }
            h.assertTrue(sigils == 2 && front && back, name + ": a sigil just off the front and just off the back, nothing else glows");
        }
        Path png = ModList.get().getModFileById(BSPInventorySync.MODID).getFile().findResource("assets/" + BSPInventorySync.MODID + "/textures/block/grave_sigil.png");
        h.assertTrue(Files.isRegularFile(png), "the sigil texture is in the jar");
        h.assertTrue(GraveRegistry.GRAVE.get().defaultBlockState().getLightEmission() == GraveBlock.LIGHT && GraveBlock.LIGHT == 4, "the block's own light stays 4");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_death_puts_the_owners_profile_and_skin_on_the_grave_for_the_head(GameTestHelper h) {
        GraveService graves = new GraveService(h.getLevel().getServer(), new WorldGraveStore(), () -> null, 2);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        p.getGameProfile().getProperties().put("textures", new Property("textures", "c2tpbi1vZi10aGUtb3duZXI=", "c2lnbmVk"));
        p.getGameProfile().getProperties().put("proxy_extra", new Property("proxy_extra", "not for the world"));
        h.setBlock(new BlockPos(3, 0, 3), Blocks.STONE);
        BlockPos abs = h.absolutePos(new BlockPos(3, 1, 3));
        p.moveTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5, 0F, 0F);
        graves.recordLayout(p, h.getLevel().damageSources().fall());
        List<ItemEntity> drops = new ArrayList<>(List.of(new ItemEntity(h.getLevel(), p.getX(), p.getY(), p.getZ(), new ItemStack(Items.DIAMOND))));
        Grave g = graves.onDrops(p, h.getLevel().damageSources().fall(), drops, 1_000_000);
        h.assertTrue(g != null && g.placed() && h.getLevel().getBlockEntity(abs) instanceof GraveBlockEntity, "a grave block");
        GraveBlockEntity be = (GraveBlockEntity) h.getLevel().getBlockEntity(abs);
        GameProfile profile = be.profile();
        h.assertTrue(profile != null && profile.getId().equals(p.getUUID()) && profile.getName().equals(p.getGameProfile().getName()), "the owner's profile: " + profile);
        Property skin = profile.getProperties().get("textures").stream().findFirst().orElse(null);
        h.assertTrue(skin != null && skin.getValue().equals("c2tpbi1vZi10aGUtb3duZXI=") && "c2lnbmVk".equals(skin.getSignature()), "with the skin, signed: " + skin);
        h.assertTrue(profile != p.getGameProfile(), "a copy: the player's own profile stays theirs");
        h.assertTrue(profile.getProperties().keySet().equals(Set.of("textures")) && profile.getProperties().size() == 1, "the skin and nothing else: " + profile.getProperties());
        h.assertTrue(be.headProfile() == profile, "the head is drawn with it");

        // the world keeps it and the clients get it
        GraveBlockEntity reloaded = new GraveBlockEntity(abs, be.getBlockState());
        reloaded.load(be.saveWithoutMetadata());
        GraveBlockEntity client = new GraveBlockEntity(abs, be.getBlockState());
        client.load(be.getUpdateTag());
        for (GraveBlockEntity copy : List.of(reloaded, client)) {
            GameProfile got = copy.profile();
            h.assertTrue(got != null && got.getId().equals(p.getUUID()) && got.getProperties().size() == 1 && got.getProperties().get("textures").stream()
                    .anyMatch(t -> t.getValue().equals("c2tpbi1vZi10aGUtb3duZXI=")), (copy == client ? "the clients get" : "the world keeps") + " the profile and skin");
        }

        // a grave from before 0.6.0 has none: the head is the owner's default skin, from their id
        CompoundTag old = be.saveWithoutMetadata();
        old.remove("OwnerProfile");
        GraveBlockEntity before = new GraveBlockEntity(abs, be.getBlockState());
        before.load(old);
        h.assertTrue(before.profile() == null && before.headProfile().getId().equals(p.getUUID()) && before.headProfile().getName().equals(p.getGameProfile().getName())
                && before.headProfile().getProperties().isEmpty(), "an older grave draws the default skin for the owner's id");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void the_head_leans_18_degrees_left_or_right_the_same_way_for_the_same_grave(GameTestHelper h) {
        Set<GraveLean> seen = EnumSet.noneOf(GraveLean.class);
        for (int i = 0; i < 64; i++) {
            UUID id = UUID.randomUUID();
            GraveLean lean = GraveLean.of(id);
            h.assertTrue(GraveLean.of(UUID.fromString(id.toString())) == lean, "the same id leans the same way");
            seen.add(lean);
        }
        h.assertTrue(seen.size() == 2, "graves lean both ways: " + seen);
        UUID even = UUID.fromString("6f1c2d3e-4a5b-4c6d-8e7f-901a2b3c4d5e"), odd = UUID.fromString("6f1c2d3e-4a5b-4c6d-8e7f-901a2b3c4d5f");
        h.assertTrue(GraveLean.of(even) == GraveLean.LEFT && GraveLean.of(odd) == GraveLean.RIGHT, "a given grave always leans the same way, whatever the run");
        h.assertTrue(GraveLean.LEFT.degrees() == -18F && GraveLean.RIGHT.degrees() == 18F, "18 degrees, left or right");
        // a grave from 0.5.0 has no lean in the world's data: it reads as left
        CompoundTag old = new CompoundTag();
        old.putString("Name", BSPInventorySync.MODID + ":grave");
        CompoundTag props = new CompoundTag();
        props.putString("facing", "east");
        props.putString("kind", "stone");
        old.put("Properties", props);
        BlockState read = net.minecraft.nbt.NbtUtils.readBlockState(h.getLevel().holderLookup(net.minecraft.core.registries.Registries.BLOCK), old);
        h.assertTrue(read.is(GraveRegistry.GRAVE.get()) && read.getValue(GraveBlock.LEAN) == GraveLean.LEFT && read.getValue(GraveBlock.FACING) == Direction.EAST,
                "a 0.5.0 grave reads as leaning left: " + read);

        h.setBlock(new BlockPos(2, 0, 2), Blocks.STONE);
        h.setBlock(new BlockPos(4, 0, 2), Blocks.STONE);
        BlockPos a = h.absolutePos(new BlockPos(2, 1, 2)), b = h.absolutePos(new BlockPos(4, 1, 2));
        UUID left = idLeaning(GraveLean.LEFT), right = idLeaning(GraveLean.RIGHT);
        GraveService.placeBlock(h.getLevel(), a, Direction.NORTH, left, UUID.randomUUID(), "Steve", 0, 0);
        GraveService.placeBlock(h.getLevel(), b, Direction.NORTH, right, UUID.randomUUID(), "Alex", 0, 0);
        h.assertTrue(h.getLevel().getBlockState(a).getValue(GraveBlock.LEAN) == GraveLean.LEFT && h.getLevel().getBlockState(b).getValue(GraveBlock.LEAN) == GraveLean.RIGHT,
                "a grave is put up leaning the way its id says");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void the_shape_takes_in_the_head_for_every_facing_and_lean(GameTestHelper h) {
        BlockPos pos = h.absolutePos(new BlockPos(1, 1, 1));
        double[] left = GraveShape.head(GraveLean.LEFT), right = GraveShape.head(GraveLean.RIGHT);
        // worked by hand: the head's corner on the leaning side ends at 8 + 4 cos 18 + 6 sin 18 = 13.66, its top at 4 sin 18 + 6 cos 18 = 6.94
        h.assertTrue(Math.abs(left[3] - 13.66) < 0.01 && Math.abs(left[4] - 6.94) < 0.01 && Math.abs(left[0] - 3.79) < 0.02, "the head's box, leaning left: " + java.util.Arrays.toString(left));
        h.assertTrue(Math.abs(right[0] - (16 - left[3])) < 1e-9 && Math.abs(right[3] - (16 - left[0])) < 1e-9 && right[4] == left[4], "leaning right is its mirror");
        h.assertTrue(left[1] == 0 && left[2] == 0.5 && left[5] == 8.5, "from the ground up, 8 deep, its back half a pixel into the base");
        for (GraveLean lean : GraveLean.values()) {
            double near = lean == GraveLean.LEFT ? 13 : 3, far = lean == GraveLean.LEFT ? 3 : 13;
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                VoxelShape shape = shapeOf(h, facing, lean, pos);
                String f = facing.getName() + ", " + lean.getSerializedName();
                h.assertTrue(inside(shape, facing, 8, 3, 4.5) && inside(shape, facing, 8, 6.5, 1) && inside(shape, facing, near, 4, 8), f + ": the head is solid");
                h.assertTrue(!inside(shape, facing, far, 6, 4.5), f + ": open on the side it leans away from, up high");
                h.assertTrue(!inside(shape, facing, 8, 7.5, 4.5) && !inside(shape, facing, 1, 1, 4) && !inside(shape, facing, 15, 1, 4) && !inside(shape, facing, 8, 3, 0.25),
                        f + ": nothing above, beside or in front of it");
                h.assertTrue(Math.abs(shape.max(Direction.Axis.Y) - 19 / 16.0) < 1e-9, f + ": the cap is still the top");
            }
        }
        h.succeed();
    }

    /**
     * Which side the head leans to, in the world's own terms (not through {@link GraveShape#turn}): someone standing in
     * front of the grave, looking at it, sees a left-leaning head tip to their left. Points in sixteenths of the block:
     * {facing, solid point x, y, z (the side it leans to, 4 up), open point x, y, z (the other side, 6 up)}.
     */
    @GameTest(template = EMPTY)
    public static void the_head_leans_to_the_left_of_someone_in_front_whichever_way_the_grave_faces(GameTestHelper h) {
        BlockPos pos = h.absolutePos(new BlockPos(1, 1, 1));
        Object[][] left = {
                {Direction.NORTH, 13.0, 4.0, 4.5, 3.0, 6.0, 4.5},   // looking south at it: their left is east (+x)
                {Direction.EAST, 11.5, 4.0, 13.0, 11.5, 6.0, 3.0},  // looking west: their left is south (+z)
                {Direction.SOUTH, 3.0, 4.0, 11.5, 13.0, 6.0, 11.5}, // looking north: their left is west (-x)
                {Direction.WEST, 4.5, 4.0, 3.0, 4.5, 6.0, 13.0},    // looking east: their left is north (-z)
        };
        for (Object[] row : left) {
            Direction facing = (Direction) row[0];
            Vec3 tip = new Vec3((double) row[1] / 16, (double) row[2] / 16, (double) row[3] / 16), away = new Vec3((double) row[4] / 16, (double) row[5] / 16, (double) row[6] / 16);
            VoxelShape leftShape = shapeOf(h, facing, GraveLean.LEFT, pos), rightShape = shapeOf(h, facing, GraveLean.RIGHT, pos);
            h.assertTrue(leftShape.toAabbs().stream().anyMatch(b -> b.contains(tip)) && leftShape.toAabbs().stream().noneMatch(b -> b.contains(away)),
                    facing.getName() + ": leaning left, the head is on the left of someone in front");
            h.assertTrue(rightShape.toAabbs().stream().anyMatch(b -> b.contains(away.add(0, -2 / 16.0, 0))) && rightShape.toAabbs().stream().noneMatch(b -> b.contains(tip.add(0, 2 / 16.0, 0))),
                    facing.getName() + ": leaning right, on their right");
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_click_on_the_head_is_a_click_on_the_grave(GameTestHelper h) {
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        // each where nothing else lies in front of it
        java.util.Map<Direction, BlockPos> at = java.util.Map.of(Direction.NORTH, new BlockPos(2, 1, 3), Direction.EAST, new BlockPos(5, 1, 1),
                Direction.SOUTH, new BlockPos(2, 1, 5), Direction.WEST, new BlockPos(5, 1, 5));
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            h.setBlock(at.get(facing).below(), Blocks.STONE);
            BlockPos abs = h.absolutePos(at.get(facing));
            GraveService.placeBlock(h.getLevel(), abs, facing, UUID.randomUUID(), UUID.randomUUID(), "Steve", 0, 0);
            Vec3 aim = Vec3.atBottomCenterOf(abs).add(0, 3 / 16.0, 0);
            Vec3 from = aim.add(facing.getStepX() * 1.5, 0, facing.getStepZ() * 1.5);
            BlockHitResult hit = h.getLevel().clip(new ClipContext(from, aim.add(facing.getStepX() * -0.49, 0, facing.getStepZ() * -0.49), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
            double ahead = hit.getLocation().subtract(Vec3.atBottomCenterOf(abs)).dot(new Vec3(facing.getStepX(), 0, facing.getStepZ()));
            h.assertTrue(hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(abs) && Math.abs(ahead - 7.5 / 16) < 0.01,
                    facing.getName() + ": looking at the head from in front hits the grave, on the head's face: " + hit.getType() + " " + hit.getLocation() + " " + ahead);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void the_motes_are_the_clients_alone(GameTestHelper h) {
        BlockState state = GraveRegistry.GRAVE.get().stateFor(Direction.NORTH, GraveKind.STONE);
        h.assertTrue(state.getTicker(h.getLevel(), GraveRegistry.GRAVE_ENTITY.get()) == null, "a server never ticks a grave");
        h.assertTrue(GraveBlock.clientTicker(GraveRegistry.GRAVE_ENTITY.get()) != null, "a client does, for the motes");
        h.assertTrue(GraveBlock.clientTicker(BlockEntityType.CHEST) == null, "and only a grave's block entity");
        // and should a server ever run it, it returns at once (a server level draws no particles anyway: this only shows it does not throw)
        h.setBlock(new BlockPos(2, 0, 2), Blocks.STONE);
        BlockPos abs = h.absolutePos(new BlockPos(2, 1, 2));
        GraveService.placeBlock(h.getLevel(), abs, Direction.NORTH, UUID.randomUUID(), UUID.randomUUID(), "Steve", 0, 0);
        GraveMotes.tick(h.getLevel(), abs, h.getLevel().getBlockState(abs), (GraveBlockEntity) h.getLevel().getBlockEntity(abs));
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void gold_and_green_motes_about_3_a_second_for_a_player_within_16_blocks(GameTestHelper h) {
        BlockPos pos = new BlockPos(100, 64, -200);
        Vec3 centre = Vec3.atCenterOf(pos);
        RandomSource random = RandomSource.create(42);
        VoxelShape shape = GraveShape.of(Direction.EAST, GraveLean.RIGHT);
        for (int i = 0; i < 2_000; i++) {
            h.assertTrue(GraveMotes.next(random, pos, shape, null) == null, "nobody watching: none");
            h.assertTrue(GraveMotes.next(random, pos, shape, centre.add(16.2, 0, 0)) == null && GraveMotes.next(random, pos, shape, centre.add(0, 12, 11)) == null,
                    "nobody within 16 blocks: none");
        }
        int ticks = 20 * 600, gold = 0, green = 0;
        Vec3 near = centre.add(15.9, 0, 0);
        for (int i = 0; i < ticks; i++) {
            GraveMotes.Mote m = GraveMotes.next(random, pos, shape, i % 2 == 0 ? near : centre.add(3, 1, -2));
            if (m == null) {
                continue;
            }
            if (m.dust() == GraveMotes.GOLD) {
                gold++;
            } else if (m.dust() == GraveMotes.GREEN) {
                green++;
            }
            h.assertTrue(m.x() >= pos.getX() + 2 / 16.0 && m.x() <= pos.getX() + 14 / 16.0 && m.z() >= pos.getZ() + 2 / 16.0 && m.z() <= pos.getZ() + 14 / 16.0
                    && m.y() >= pos.getY() && m.y() <= pos.getY() + 19 / 16.0, "over the grave, from the ground to the cap: " + m);
            h.assertTrue(m.dy() > 0 && m.dx() == 0 && m.dz() == 0, "drifting upwards: " + m);
            Vec3 local = new Vec3(m.x() - pos.getX(), m.y() - pos.getY(), m.z() - pos.getZ());
            h.assertTrue(shape.toAabbs().stream().noneMatch(b -> b.contains(local)), "in the open, never inside the stone or the head: " + m);
        }
        double perSecond = (gold + green) / 600.0;
        h.assertTrue(perSecond > 2.7 && perSecond < 3.3, "about 3 a second: " + perSecond);
        h.assertTrue(Math.abs(gold - green) < 0.1 * (gold + green), "gold and green alike: " + gold + " / " + green);
        h.assertTrue(GraveMotes.GOLD.getColor().equals(new org.joml.Vector3f(0xFF / 255F, 0xD2 / 255F, 0x3A / 255F))
                && GraveMotes.GREEN.getColor().equals(new org.joml.Vector3f(0x39 / 255F, 0xFF / 255F, 0x14 / 255F)), "gold #FFD23A and neon green #39FF14");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_grave_is_never_put_where_its_cap_would_be_in_the_ceiling(GameTestHelper h) {
        // a floor of stone, a ceiling of stone one block above it over x 0..3, open beyond
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                h.setBlock(new BlockPos(x, 2, z), x <= 3 ? Blocks.STONE : Blocks.AIR);
            }
        }
        BlockPos low = h.absolutePos(new BlockPos(1, 1, 4)), open = h.absolutePos(new BlockPos(6, 1, 4));
        h.assertTrue(!GraveSpot.headroom(h.getLevel(), low) && !GraveSpot.good(h.getLevel(), low, Set.of()), "a space one block high is not a grave's spot");
        h.assertTrue(GraveSpot.headroom(h.getLevel(), open) && GraveSpot.good(h.getLevel(), open, Set.of()), "open sky is");
        h.setBlock(new BlockPos(6, 2, 4), Blocks.WALL_TORCH);
        h.assertTrue(GraveSpot.good(h.getLevel(), open, Set.of()), "nothing to bump into above (a torch on a wall) leaves room");
        h.setBlock(new BlockPos(6, 2, 4), Blocks.STONE_SLAB);
        h.assertTrue(!GraveSpot.good(h.getLevel(), open, Set.of()), "a slab above does not");
        h.setBlock(new BlockPos(6, 2, 4), Blocks.SCAFFOLDING);
        h.assertTrue(!GraveSpot.good(h.getLevel(), open, Set.of()), "nor scaffolding");
        h.setBlock(new BlockPos(6, 2, 4), Blocks.POWDER_SNOW);
        h.assertTrue(!GraveSpot.good(h.getLevel(), open, Set.of()), "nor powder snow");
        h.setBlock(new BlockPos(6, 2, 4), Blocks.AIR);

        // at the top of the world there is always room above
        BlockPos top = new BlockPos(open.getX(), h.getLevel().getMaxBuildHeight() - 1, open.getZ());
        h.getLevel().setBlock(top.below(), Blocks.STONE.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
        h.getLevel().setBlock(top, Blocks.AIR.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
        boolean atTop = GraveSpot.headroom(h.getLevel(), top) && GraveSpot.good(h.getLevel(), top, Set.of());
        h.getLevel().setBlock(top.below(), Blocks.AIR.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL); // the world persists between runs
        h.assertTrue(atTop, "the highest spot in the world has room above");

        // a death under the low ceiling, a block from its edge: the grave goes to the nearest spot with room, just
        // past the edge (on top of the one-block roof would be two away)
        BlockPos underEdge = h.absolutePos(new BlockPos(3, 1, 4));
        java.util.Optional<BlockPos> spot = GraveSpot.near(h.getLevel(), underEdge, 8, Set.of());
        h.assertTrue(spot.isPresent() && spot.get().equals(h.absolutePos(new BlockPos(4, 1, 4))),
                "the nearest spot with headroom, just past the ceiling's edge: " + spot.map(h::relativePos));
        h.succeed();
    }
}
