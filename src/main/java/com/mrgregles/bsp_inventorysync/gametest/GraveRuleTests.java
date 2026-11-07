package com.mrgregles.bsp_inventorysync.gametest;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.SyncConfig;
import com.mrgregles.bsp_inventorysync.grave.GraveActions;
import com.mrgregles.bsp_inventorysync.grave.GraveContents;
import com.mrgregles.bsp_inventorysync.grave.GraveMenu;
import com.mrgregles.bsp_inventorysync.grave.GraveService;
import com.mrgregles.bsp_inventorysync.grave.WorldGraveStore;
import com.mrgregles.bsp_inventorysync.storage.Grave;
import com.mrgregles.bsp_inventorysync.storage.MemoryStore;
import com.mrgregles.bsp_inventorysync.sync.SyncService;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Graves, part 4 (D25 7b, 8b, 14a, 16a, 22b, task GR-4): recovering from anywhere after seven days (or at once for a
 * grave with no spot), the player's list, putting away after thirty days unopened and deleting thirty days later,
 * an opening restarting the clocks, put-away graves for admins only, the admin commands (list, open, give once, remove),
 * the owner told on another server, and graves on by default.
 */
@GameTestHolder(BSPInventorySync.MODID)
@PrefixGameTestTemplate(false)
public class GraveRuleTests {
    private static final String EMPTY = "empty";
    private static final long DAY = 86_400_000L;

    private static GraveService service(GameTestHelper h) {
        GraveService g = new GraveService(h.getLevel().getServer(), new WorldGraveStore(), () -> null, 2);
        g.setTimes(7 * DAY, 30 * DAY, 30 * DAY);
        return g;
    }

    /** A grave standing at the relative spot, made at {@code when}. */
    private static Grave die(GameTestHelper h, GraveService graves, ServerPlayer p, int x, int z, long when, ItemStack... stacks) {
        h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
        BlockPos abs = h.absolutePos(new BlockPos(x, 1, z));
        p.moveTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5);
        graves.recordLayout(p, h.getLevel().damageSources().fall());
        List<ItemEntity> drops = new ArrayList<>();
        for (ItemStack s : stacks) {
            drops.add(new ItemEntity(h.getLevel(), p.getX(), p.getY(), p.getZ(), s.copy()));
        }
        Grave g = graves.onDrops(p, h.getLevel().damageSources().fall(), drops, when);
        h.assertTrue(g != null && g.placed(), "a grave");
        return g;
    }

    private static Grave noSpot(GraveService graves, UUID owner, long when) {
        Grave g = new Grave(UUID.randomUUID(), owner, "Steve", "", "minecraft:overworld", 0, -70, 0, false, when, when, 0, 0, 1, 0,
                new GraveContents(List.of(new GraveContents.Entry("inv:0", new ItemStack(Items.DIAMOND, 3))), new CompoundTag()).encode());
        graves.local().addGrave(g).join();
        return g;
    }

    private static String text(List<Component> lines) {
        return String.join(" | ", lines.stream().map(Component::getString).toList());
    }

    @GameTest(template = EMPTY)
    public static void a_grave_left_unopened_for_seven_days_is_recovered_from_anywhere(GameTestHelper h) {
        GraveService graves = service(h);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        long t0 = System.currentTimeMillis();
        Grave g = die(h, graves, p, 3, 3, t0, new ItemStack(Items.DIAMOND, 4));
        String early = GraveActions.recover(graves, p, g.shortId(), t0 + 6 * DAY).join();
        h.assertTrue(early != null && early.startsWith("Go to it:") && early.contains("1 day"), "after six days: go to it, one day to wait: " + early);
        h.assertTrue(p.containerMenu == p.inventoryMenu, "no screen");
        h.assertTrue(GraveActions.recover(graves, p, g.shortId(), t0 + 7 * DAY + 1).join() == null, "after seven days it opens");
        h.assertTrue(p.containerMenu instanceof GraveMenu m && m.grave().id().equals(g.id()), "its screen, wherever the player is");
        p.closeContainer();
        h.assertTrue(GraveActions.recover(graves, p, "ffffffff", t0 + 8 * DAY).join().startsWith("No grave of yours"), "an unknown id");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_grave_that_found_no_spot_is_recovered_at_once_and_the_list_shows_where_each_one_is(GameTestHelper h) {
        GraveService graves = service(h);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        long now = System.currentTimeMillis();
        Grave standing = die(h, graves, p, 3, 3, now - 2 * 60_000L, new ItemStack(Items.BREAD, 4));
        Grave lost = noSpot(graves, p.getUUID(), now - 60_000L);
        Grave away = noSpot(graves, p.getUUID(), now - 120 * DAY);
        graves.local().updateGrave(away.withArchivedAt(now)).join();

        List<Component> lines = GraveActions.list(graves, p.getUUID(), now).join();
        String all = text(lines);
        h.assertTrue(lines.size() == 3 && lines.get(0).getString().equals("Your graves (2)"), "two graves, the put-away one left out: " + all);
        h.assertTrue(lines.get(1).getString().contains("could not be placed") && lines.get(1).getString().contains("[Recover]"), "the newest, with no spot, has [Recover]: " + lines.get(1).getString());
        ClickEvent click = lines.get(1).getSiblings().get(0).getStyle().getClickEvent();
        h.assertTrue(click != null && click.getValue().equals("/grave recover " + lost.shortId()), "the link runs /grave recover <id>: " + (click == null ? null : click.getValue()));
        h.assertTrue(lines.get(2).getString().contains(standing.x() + " " + standing.y() + " " + standing.z()) && lines.get(2).getString().contains("(from anywhere in 7 days)"),
                "the standing one says where it is and when it can be recovered: " + lines.get(2).getString());
        h.assertTrue(GraveActions.recover(graves, p, lost.shortId(), now).join() == null && p.containerMenu instanceof GraveMenu, "no spot: recovered at once");
        p.closeContainer();
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void thirty_days_unopened_puts_a_grave_away_and_thirty_more_delete_it(GameTestHelper h) {
        GraveService graves = service(h);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        long t0 = System.currentTimeMillis();
        Grave g = die(h, graves, p, 3, 3, t0, new ItemStack(Items.EMERALD, 6));
        BlockPos at = new BlockPos(g.x(), g.y(), g.z());
        h.assertTrue(graves.sweep(t0 + 29 * DAY).join() == 0, "nothing at 29 days");
        h.assertTrue(graves.sweep(t0 + 31 * DAY).join() == 1, "put away at 31 days");
        Grave away = graves.local().grave(g.id()).join().orElseThrow();
        h.assertTrue(away.archived() && away.touchedAt() == t0 + 31 * DAY, "archived, its clock restarted");
        h.assertTrue(h.getLevel().getBlockState(at).isAir(), "its block is gone");
        h.assertTrue(GraveActions.list(graves, p.getUUID(), t0 + 31 * DAY).join().get(0).getString().equals("You have no graves."), "the owner no longer sees it");
        h.assertTrue(String.join(" ", GraveActions.adminList(graves, p.getUUID(), "Steve", t0 + 31 * DAY).join()).contains("put away"), "an admin does");
        h.assertTrue(graves.sweep(t0 + 60 * DAY).join() == 0 && graves.local().grave(g.id()).join().isPresent(), "still kept 29 days after");
        h.assertTrue(graves.sweep(t0 + 62 * DAY).join() == 1 && graves.local().grave(g.id()).join().isEmpty(), "deleted for good 31 days after");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void opening_a_grave_restarts_its_clocks(GameTestHelper h) {
        GraveService graves = service(h);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        long now = System.currentTimeMillis();
        Grave g = die(h, graves, p, 3, 3, now - 40 * DAY, new ItemStack(Items.COAL, 2));
        GraveMenu m = graves.access().open(p, g.id(), false, null, null).join();
        h.assertTrue(m != null, "opened");
        p.closeContainer();
        h.assertTrue(graves.sweep(now + 1_000).join() == 0, "not put away: opened just now");
        h.assertTrue(!graves.recoverable(graves.local().grave(g.id()).join().orElseThrow(), now + 1_000), "and the seven days start again");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_put_away_grave_is_for_admins_only(GameTestHelper h) {
        GraveService graves = service(h);
        ServerPlayer owner = TestPlayers.mockServerPlayer(h), admin = TestPlayers.mockServerPlayer(h);
        Grave g = noSpot(graves, owner.getUUID(), System.currentTimeMillis() - DAY);
        graves.local().updateGrave(g.withArchivedAt(System.currentTimeMillis())).join();
        h.assertTrue(graves.access().open(owner, g.id(), false, null, null).join() == null, "its owner is refused");
        h.assertTrue(GraveActions.adminOpen(graves, admin, owner.getUUID(), "Steve", g.shortId()).join() == null && admin.containerMenu instanceof GraveMenu, "an admin opens it");
        admin.closeContainer();
        h.assertTrue(GraveActions.adminOpen(graves, admin, owner.getUUID(), "Steve", "ffffffff").join().contains("no grave with the id"), "ids that do not exist are refused");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void an_admin_gives_a_whole_grave_once_by_making_it_theirs(GameTestHelper h) {
        GraveService graves = service(h);
        ServerPlayer owner = TestPlayers.mockServerPlayer(h), to = TestPlayers.mockServerPlayer(h);
        Grave g = die(h, graves, owner, 3, 3, System.currentTimeMillis(), new ItemStack(Items.DIAMOND, 20), new ItemStack(Items.IRON_INGOT, 5));
        BlockPos at = new BlockPos(g.x(), g.y(), g.z());
        List<String> reply = GraveActions.adminGive(graves, h.getLevel().getServer(), owner.getUUID(), "Steve", g.shortId(), to.getUUID(), "Alex", "Admin").join();
        Grave theirs = graves.local().grave(g.id()).join().orElseThrow();
        h.assertTrue(theirs.owner().equals(to.getUUID()) && !theirs.placed() && theirs.stacks() == 2, "the grave is Alex's now, with no block: " + reply);
        h.assertTrue(h.getLevel().getBlockState(at).isAir(), "its block is gone");
        h.assertTrue(GraveActions.list(graves, to.getUUID(), System.currentTimeMillis()).join().get(1).getString().contains("[Recover]"), "Alex can recover it at once");
        List<String> again = GraveActions.adminGive(graves, h.getLevel().getServer(), owner.getUUID(), "Steve", g.shortId(), to.getUUID(), "Alex", "Admin").join();
        h.assertTrue(again.get(0).contains("no grave with the id"), "never twice: " + again);
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_given_grave_keeps_its_backpack_contents(GameTestHelper h) {
        GraveService graves = service(h);
        ServerPlayer owner = TestPlayers.mockServerPlayer(h);
        CompoundTag extras = new CompoundTag();
        ListTag packs = new ListTag();
        CompoundTag pack = new CompoundTag();
        pack.putUUID("Uuid", UUID.randomUUID());
        pack.put("Contents", new CompoundTag());
        packs.add(pack);
        extras.put("Packs", packs);
        Grave g = new Grave(UUID.randomUUID(), owner.getUUID(), "Steve", "", "minecraft:overworld", 0, 0, 0, false, 1, 1, 0, 0, 1, 0,
                new GraveContents(List.of(new GraveContents.Entry("inv:0", new ItemStack(Items.CHEST))), extras).encode());
        graves.local().addGrave(g).join();
        UUID elsewhere = UUID.randomUUID();
        List<String> reply = GraveActions.adminGive(graves, h.getLevel().getServer(), owner.getUUID(), "Steve", g.shortId(), elsewhere, "Elsewhere", "Admin").join();
        Grave theirs = graves.local().grave(g.id()).join().orElseThrow();
        h.assertTrue(theirs.owner().equals(elsewhere) && !GraveContents.decode(theirs.data()).extras().getList("Packs", 10).isEmpty(),
                "given to a player on another server, its backpack contents go with it: " + reply);
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void an_admin_removes_a_grave_and_its_block(GameTestHelper h) {
        GraveService graves = service(h);
        ServerPlayer owner = TestPlayers.mockServerPlayer(h);
        Grave older = die(h, graves, owner, 2, 2, System.currentTimeMillis() - 60_000, new ItemStack(Items.STICK));
        Grave newer = die(h, graves, owner, 5, 5, System.currentTimeMillis(), new ItemStack(Items.APPLE));
        List<String> listed = GraveActions.adminList(graves, owner.getUUID(), "Steve", System.currentTimeMillis()).join();
        h.assertTrue(listed.size() == 3 && listed.get(1).startsWith(newer.shortId()) && listed.get(2).startsWith(older.shortId()), "listed by id, newest first: " + listed);
        List<String> reply = GraveActions.adminRemove(graves, h.getLevel().getServer(), owner.getUUID(), "Steve", older.shortId(), "Admin").join();
        h.assertTrue(reply.get(0).startsWith("Removed") && graves.local().grave(older.id()).join().orElseThrow().empty(), "emptied, kept as a marker: " + reply);
        h.assertTrue(GraveActions.adminList(graves, owner.getUUID(), "Steve", System.currentTimeMillis()).join().size() == 2, "and no longer listed");
        h.assertTrue(h.getLevel().getBlockState(new BlockPos(older.x(), older.y(), older.z())).isAir(), "its block too");
        h.assertTrue(graves.local().grave(newer.id()).join().isPresent(), "the other stays");
        h.succeed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void the_owner_hears_on_another_server_when_someone_opens_their_grave(GameTestHelper h) {
        MemoryStore memory = new MemoryStore();
        SyncService syncA = new SyncService(h.getLevel().getServer(), memory, "a"), syncB = new SyncService(h.getLevel().getServer(), memory, "b");
        GraveService onA = new GraveService(h.getLevel().getServer(), new WorldGraveStore(), () -> syncA, 2);
        GraveService onB = new GraveService(h.getLevel().getServer(), new WorldGraveStore(), () -> syncB, 2);
        onA.setPrivateMs(0);
        ServerPlayer owner = TestPlayers.mockServerPlayer(h), other = TestPlayers.mockServerPlayer(h);
        onA.setLivePlayerLookup(id -> null); // the owner is not on server a
        onB.setLivePlayerLookup(id -> id.equals(owner.getUUID()) ? owner : null);
        Grave g = die(h, onA, owner, 3, 3, System.currentTimeMillis(), new ItemStack(Items.DIAMOND, 2));
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(memory.graveCount() == 1, "in the database"))
                .thenExecute(() -> {
                    other.moveTo(owner.getX(), owner.getY(), owner.getZ());
                    h.assertTrue(onA.access().open(other, g.id(), false, null, null).join() != null, "someone else opens it on a");
                    other.closeContainer();
                })
                .thenWaitUntil(() -> h.assertTrue(memory.extra(GraveService.NOTICE, owner.getUUID()).join().isPresent(), "a notice waits for the owner"))
                .thenExecute(() -> {
                    h.assertTrue(onB.deliverNotices(List.of(owner)).join() == 1, "server b hands it to them");
                    h.assertTrue(memory.extra(GraveService.NOTICE, owner.getUUID()).join().isEmpty(), "and it is gone");
                    h.assertTrue(onB.deliverNotices(List.of(owner)).join() == 0, "never twice");
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY)
    public static void graves_are_on_by_default(GameTestHelper h) {
        h.assertTrue(SyncConfig.GRAVES_ENABLED.getDefault(), "graves.enabled defaults to true");
        h.assertTrue(SyncConfig.GRAVE_RECOVER_DAYS.getDefault() == 7 && SyncConfig.GRAVE_ARCHIVE_DAYS.getDefault() == 30 && SyncConfig.GRAVE_DELETE_DAYS.getDefault() == 30
                && SyncConfig.GRAVE_PRIVATE_MINUTES.getDefault() == 60, "7, 30, 30 days and 60 minutes");
        h.succeed();
    }
}
