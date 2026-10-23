package com.mrgregles.bsp_inventorysync.command;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.api.DataModule;
import com.mrgregles.bsp_inventorysync.api.InventorySyncApi;
import com.mrgregles.bsp_inventorysync.compat.BspCore;
import com.mrgregles.bsp_inventorysync.data.Modules;
import com.mrgregles.bsp_inventorysync.data.PlayerSnapshotCodec;
import com.mrgregles.bsp_inventorysync.storage.FastStore;
import com.mrgregles.bsp_inventorysync.storage.JdbcStore;
import com.mrgregles.bsp_inventorysync.storage.MemoryStore;
import com.mrgregles.bsp_inventorysync.storage.Snapshot;
import com.mrgregles.bsp_inventorysync.storage.SyncStore;
import com.mrgregles.bsp_inventorysync.sync.SyncService;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * What {@code /invsync} does (D9, D13), as plain methods returning lines of text, so the GameTests
 * drive them with mock players and the command tree stays a thin layer. Every store read is a
 * future; the lines come when it completes, on whatever thread, and the command hops to the server
 * thread to send them.
 */
public final class AdminActions {
    private AdminActions() {}

    static String age(long savedAt, long now) {
        long s = Math.max(0, (now - savedAt) / 1000);
        if (s < 90) {
            return s + " s ago";
        }
        if (s < 5400) {
            return (s / 60) + " min ago";
        }
        if (s < 172_800) {
            return (s / 3600) + " h ago";
        }
        return (s / 86_400) + " d ago";
    }

    static String size(int bytes) {
        return bytes < 2048 ? bytes + " B" : String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
    }

    /** One line per snapshot, newest first: version, cause, server, age, size, pin. */
    static String describe(Snapshot s, long now) {
        return "v" + s.version() + "  " + s.cause().id() + "  from " + s.serverId() + "  " + age(s.savedAt(), now) + "  " + size(s.size()) + (s.pinned() ? "  pinned" : "");
    }

    private static String storeLine(SyncStore store) {
        if (store instanceof FastStore f) {
            return storeLine(f.inner()) + "; " + f.redis().status();
        }
        if (store instanceof JdbcStore j) {
            return j.status();
        }
        if (store instanceof MemoryStore m) {
            return "in-memory store (nothing shared), " + m.playerCount() + " player(s)";
        }
        return store.getClass().getSimpleName();
    }

    /** The state of the sync on this server. The store's answer to a ping is appended when it comes. */
    public static CompletableFuture<List<String>> status(SyncService service) {
        List<String> lines = new ArrayList<>();
        lines.add("BSP-InventorySync on \"" + service.serverId() + "\", api " + InventorySyncApi.API_VERSION);
        lines.add("store: " + storeLine(service.store()));
        lines.add("waiting for their data: " + service.waiting() + "; auto-saves written " + service.writtenAutosaves() + ", skipped " + service.skippedAutosaves()
                + "; store timeouts " + service.storeTimeouts() + "; snapshots kept " + service.snapshotsKept());
        lines.add("arrival: timeout " + service.policy().timeoutMs() / 1000 + " s, then " + (service.policy().kickOnTimeout() ? "kick" : "apply the last known"));
        StringBuilder modules = new StringBuilder();
        for (DataModule m : Modules.all()) {
            modules.append(modules.length() == 0 ? "" : ", ").append(m.id());
        }
        lines.add("modules: " + modules);
        lines.add("BSP-Core: " + BspCore.sources());
        return service.store().ping().handle((ok, error) -> {
            lines.add("store ping: " + (error != null ? "failed (" + error.getMessage() + ")" : ok ? "ok" : "no answer"));
            return lines;
        });
    }

    /** Applies the player's newest snapshot again. */
    public static CompletableFuture<List<String>> resync(SyncService service, ServerPlayer player) {
        return service.restoreLatest(player).handle((restored, error) -> {
            if (error != null) {
                return List.of("could not read " + player.getGameProfile().getName() + "'s data: " + error.getMessage());
            }
            if (restored.isEmpty()) {
                return List.of(player.getGameProfile().getName() + " has no snapshot; nothing applied");
            }
            var r = restored.get();
            return List.of("applied v" + r.version() + " from " + r.sourceServer() + " to " + player.getGameProfile().getName()
                    + (r.modules().failed().isEmpty() ? "" : "; failed modules: " + r.modules().failed())
                    + (r.totemsKept() > 0 ? "; totems kept: " + r.totemsKept() : ""));
        });
    }

    /** Captures the player now, writes the full SNBT to a file under the world folder, and sums it up per module. */
    public static List<String> dump(MinecraftServer server, ServerPlayer player) {
        CompoundTag snapshot = PlayerSnapshotCodec.capture(player);
        byte[] encoded = PlayerSnapshotCodec.encode(snapshot);
        List<String> lines = new ArrayList<>();
        lines.add(player.getGameProfile().getName() + ": " + size(encoded.length) + " compressed" + (SyncService.isSyncing(player.getUUID()) ? " (data still arriving: this is the stale local copy)" : ""));
        CompoundTag modules = snapshot.getCompound("Modules");
        for (String id : modules.getAllKeys()) {
            lines.add("  " + id + ": " + size(rawSize(modules.getCompound(id))));
        }
        List<String> failed = PlayerSnapshotCodec.failedOnSave(snapshot);
        if (!failed.isEmpty()) {
            lines.add("  failed to save: " + failed);
        }
        try {
            Path dir = server.getWorldPath(LevelResource.ROOT).resolve("bsp_inventorysync").resolve("dumps");
            Files.createDirectories(dir);
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path file = dir.resolve(player.getGameProfile().getName() + "-" + stamp + ".snbt");
            Files.writeString(file, snapshot.toString(), StandardCharsets.UTF_8);
            lines.add("written to " + server.getWorldPath(LevelResource.ROOT).relativize(file));
        } catch (IOException e) {
            lines.add("could not write the dump file: " + e.getMessage());
            BSPInventorySync.LOGGER.warn("BSP-InventorySync: dump file not written", e);
        }
        return lines;
    }

    private static int rawSize(CompoundTag tag) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            NbtIo.write(tag, new java.io.DataOutputStream(out));
            return out.size();
        } catch (IOException e) {
            return 0;
        }
    }

    /** The player's snapshots, newest first. */
    public static CompletableFuture<List<String>> snapshots(SyncService service, UUID player, String name, long now) {
        return service.store().history(player).handle((history, error) -> {
            if (error != null) {
                return List.of("could not read " + name + "'s snapshots: " + error.getMessage());
            }
            if (history.isEmpty()) {
                return List.of(name + " has no snapshots");
            }
            List<String> lines = new ArrayList<>();
            lines.add(name + ": " + history.size() + " snapshot(s)");
            for (Snapshot s : history) {
                lines.add("  " + describe(s, now));
            }
            return lines;
        });
    }

    /** Applies one snapshot by version to an online player (a rollback). */
    public static CompletableFuture<List<String>> restore(SyncService service, MinecraftServer server, ServerPlayer player, long version) {
        return service.store().snapshot(player.getUUID(), version).thenCompose(found -> {
            CompletableFuture<List<String>> out = new CompletableFuture<>();
            server.execute(() -> {
                try {
                    if (found.isEmpty()) {
                        out.complete(List.of(player.getGameProfile().getName() + " has no snapshot v" + version));
                        return;
                    }
                    var r = service.restore(player, found.get(), false);
                    String rolled = "rolled " + player.getGameProfile().getName() + " back to v" + r.version() + " (" + found.get().cause().id() + " from " + r.sourceServer() + ")"
                            + (r.modules().failed().isEmpty() ? "" : "; failed modules: " + r.modules().failed());
                    // saved at once as the newest snapshot, so a switch, stop or crash before their next save keeps the rollback
                    service.save(player, com.mrgregles.bsp_inventorysync.storage.SaveCause.ADMIN).whenComplete((saved, error) -> out.complete(List.of(
                            rolled + (error != null ? "; NOT saved yet (" + error.getMessage() + "): it will be lost if they switch server before their next save" : "; saved as v" + saved.version()))));
                } catch (Throwable t) {
                    out.complete(List.of("restore failed: " + t));
                }
            });
            return out;
        }).exceptionally(error -> List.of("could not read the snapshot: " + error.getMessage()));
    }

    /** Pins or unpins a snapshot so rotation keeps or may remove it. */
    public static CompletableFuture<List<String>> pin(SyncService service, UUID player, String name, long version, boolean pinned) {
        return service.store().pin(player, version, pinned).handle((ok, error) -> {
            if (error != null) {
                return List.of("could not " + (pinned ? "pin" : "unpin") + ": " + error.getMessage());
            }
            return List.of(ok ? name + " v" + version + (pinned ? " pinned; rotation keeps it" : " unpinned") : name + " has no snapshot v" + version);
        });
    }

    /** Takes a snapshot of the player now, as an admin save. */
    public static CompletableFuture<List<String>> save(SyncService service, ServerPlayer player) {
        return service.save(player, com.mrgregles.bsp_inventorysync.storage.SaveCause.ADMIN).handle((s, error) ->
                error != null ? List.of("save failed: " + error.getMessage()) : List.of("saved " + player.getGameProfile().getName() + " as v" + s.version() + " (" + size(s.size()) + ")"));
    }

    static Optional<ServerPlayer> online(MinecraftServer server, UUID id) {
        return Optional.ofNullable(server.getPlayerList().getPlayer(id));
    }
}
