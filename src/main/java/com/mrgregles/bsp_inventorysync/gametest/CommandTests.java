package com.mrgregles.bsp_inventorysync.gametest;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.command.AdminActions;
import com.mrgregles.bsp_inventorysync.storage.MemoryStore;
import com.mrgregles.bsp_inventorysync.storage.SaveCause;
import com.mrgregles.bsp_inventorysync.storage.Snapshot;
import com.mrgregles.bsp_inventorysync.sync.ArrivalPolicy;
import com.mrgregles.bsp_inventorysync.sync.SyncService;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The operator actions behind {@code /invsync} (D9, D13) and the command tree's wiring. */
@GameTestHolder(BSPInventorySync.MODID)
@PrefixGameTestTemplate(false)
public class CommandTests {
    private static final String EMPTY = "empty";
    private static final String HUB = "hub";

    /** A command source that keeps what it is told. */
    static final class Capture implements CommandSource {
        final List<String> lines = new ArrayList<>();

        @Override
        public void sendSystemMessage(Component component) {
            lines.add(component.getString());
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }

        CommandSourceStack stack(GameTestHelper h, int permission) {
            return new CommandSourceStack(this, Vec3.ZERO, Vec2.ZERO, h.getLevel(), permission, "test", Component.literal("test"), h.getLevel().getServer(), null);
        }
    }

    private static ServerPlayer settled(GameTestHelper h, SyncService service) {
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        service.onLogin(p, 0);
        service.tick(0);
        return p;
    }

    private static String joined(List<String> lines) {
        return String.join("\n", lines);
    }

    @GameTest(template = EMPTY)
    public static void status_describes_the_service_and_the_store(GameTestHelper h) {
        SyncService service = new SyncService(h.getLevel().getServer(), new MemoryStore(), HUB, new ArrivalPolicy(10_000, false));
        List<String> lines = AdminActions.status(service).join();
        String all = joined(lines);
        h.assertTrue(all.contains("\"hub\"") && all.contains("api 1"), "names the server and the api: " + all);
        h.assertTrue(all.contains("in-memory store"), "describes the store");
        h.assertTrue(all.contains("modules: inventory, ender_chest, experience, game, vitals, advancements, forge"), "lists the modules: " + all);
        h.assertTrue(all.contains("timeout 10 s, then apply the last known"), "states the arrival policy");
        h.assertTrue(all.contains("BSP-Core: isTotem=") && all.contains("store ping: ok"), "totem rule source and a ping");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void the_command_tree_is_registered_and_refuses_when_sync_is_off(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        Capture op = new Capture();
        int result = server.getCommands().performPrefixedCommand(op.stack(h, 2), "invsync status");
        h.assertTrue(result == 0 && joined(op.lines).contains("inventory sync is off on this server"), "sync is off in this dev run and the command says so: " + op.lines);

        Capture nobody = new Capture();
        server.getCommands().performPrefixedCommand(nobody.stack(h, 0), "invsync status");
        h.assertTrue(!joined(nobody.lines).contains("inventory sync"), "a player without permission 2 gets only the usual unknown-command error: " + nobody.lines);

        Capture unknown = new Capture();
        server.getCommands().performPrefixedCommand(unknown.stack(h, 2), "invsync snapshots no_such_player_here");
        h.assertTrue(!unknown.lines.isEmpty() && !joined(unknown.lines).contains("snapshot(s)"), "an unknown player name is an error from the argument: " + unknown.lines);
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void snapshots_restore_pin_resync_and_save_through_the_actions(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        MemoryStore store = new MemoryStore(() -> 1_000_000);
        SyncService service = new SyncService(server, store, HUB, new ArrivalPolicy(1_000, false));
        ServerPlayer p = settled(h, service);
        String name = p.getGameProfile().getName();
        UUID id = p.getUUID();
        p.getInventory().setItem(0, new ItemStack(Items.APPLE, 1));
        service.save(p, SaveCause.LOGOUT).join();
        p.getInventory().setItem(0, new ItemStack(Items.APPLE, 2));
        service.save(p, SaveCause.DEATH).join();

        List<String> listed = AdminActions.snapshots(service, id, name, 1_060_000).join();
        h.assertTrue(listed.size() == 3 && listed.get(0).equals(name + ": 2 snapshot(s)"), "header line: " + listed);
        h.assertTrue(listed.get(1).startsWith("  v2  death  from hub  60 s ago") && listed.get(2).startsWith("  v1  logout  from hub"), "newest first with cause, server and age: " + listed);
        h.assertTrue(AdminActions.snapshots(service, UUID.randomUUID(), "ghost", 0).join().equals(List.of("ghost has no snapshots")), "no snapshots line");

        List<String> rolled = AdminActions.restore(service, server, p, 1).join();
        h.assertTrue(rolled.get(0).startsWith("rolled " + name + " back to v1 (logout from hub)"), "rollback line: " + rolled);
        h.assertTrue(p.getInventory().getItem(0).getCount() == 1, "v1's inventory applied");
        h.assertTrue(AdminActions.restore(service, server, p, 9).join().equals(List.of(name + " has no snapshot v9")), "unknown version");

        h.assertTrue(AdminActions.pin(service, id, name, 1, true).join().get(0).contains("v1 pinned"), "pin line");
        Snapshot v1 = store.snapshot(id, 1).join().orElseThrow();
        h.assertTrue(v1.pinned(), "pinned in the store");
        h.assertTrue(AdminActions.snapshots(service, id, name, 1_060_000).join().get(2).endsWith("pinned"), "listed as pinned");
        h.assertTrue(AdminActions.pin(service, id, name, 7, true).join().equals(List.of(name + " has no snapshot v7")), "pinning an unknown version");
        h.assertTrue(AdminActions.pin(service, id, name, 1, false).join().get(0).contains("unpinned"), "unpin line");

        List<String> resynced = AdminActions.resync(service, p).join();
        h.assertTrue(resynced.get(0).startsWith("applied v2 from hub to " + name), "resync applies the newest: " + resynced);
        h.assertTrue(p.getInventory().getItem(0).getCount() == 2, "v2's inventory applied");
        ServerPlayer fresh = TestPlayers.mockServerPlayer(h);
        h.assertTrue(AdminActions.resync(service, fresh).join().get(0).endsWith("has no snapshot; nothing applied"), "resync with nothing stored");

        List<String> saved = AdminActions.save(service, p).join();
        h.assertTrue(saved.get(0).startsWith("saved " + name + " as v3"), "admin save line: " + saved);
        h.assertTrue(store.latest(id).join().orElseThrow().cause() == SaveCause.ADMIN, "as an admin save");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void dump_sums_up_the_modules_and_writes_a_file(GameTestHelper h) throws IOException {
        MinecraftServer server = h.getLevel().getServer();
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        p.getInventory().setItem(0, new ItemStack(Items.NETHERITE_SWORD));
        List<String> lines = AdminActions.dump(server, p);
        String all = joined(lines);
        h.assertTrue(lines.get(0).startsWith(p.getGameProfile().getName() + ": ") && lines.get(0).contains("compressed"), "header with the compressed size: " + lines.get(0));
        h.assertTrue(all.contains("  inventory: ") && all.contains("  forge: "), "one line per module: " + all);
        String last = lines.get(lines.size() - 1);
        h.assertTrue(last.startsWith("written to bsp_inventorysync/dumps/" + p.getGameProfile().getName()), "the file path is reported: " + last);
        Path file = server.getWorldPath(LevelResource.ROOT).resolve(last.substring("written to ".length()));
        h.assertTrue(Files.isRegularFile(file) && Files.readString(file).contains("netherite_sword"), "the SNBT file holds the inventory");
        Files.delete(file);
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void adopted_players_are_checked_in_and_saved_on_logout(GameTestHelper h) {
        MemoryStore store = new MemoryStore();
        SyncService service = new SyncService(h.getLevel().getServer(), store, HUB, new ArrivalPolicy(1_000, false));
        ServerPlayer p = TestPlayers.mockServerPlayer(h); // online before the service existed: never went through the handshake
        p.getInventory().setItem(0, new ItemStack(Items.BEACON));
        int adopted = service.adopt(List.of(p), 0);
        h.assertTrue(adopted == 1, "one adopted");
        var presence = store.presence(p.getUUID()).join().orElseThrow();
        h.assertTrue(presence.online() && presence.serverId().equals(HUB), "checked in here: " + presence);
        h.assertTrue(service.autosaveDue(List.of(p), 60_000) == 1, "their auto-save turn comes one interval after the adoption");
        service.onLogout(p).join();
        h.assertTrue(store.history(p.getUUID()).join().size() == 2 && !store.presence(p.getUUID()).join().orElseThrow().online(), "the logout saved and released as usual");
        h.succeed();
    }
}
