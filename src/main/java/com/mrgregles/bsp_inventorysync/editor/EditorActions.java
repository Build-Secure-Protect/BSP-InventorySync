package com.mrgregles.bsp_inventorysync.editor;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.data.PlayerSnapshotCodec;
import com.mrgregles.bsp_inventorysync.data.vanilla.EnderChestModule;
import com.mrgregles.bsp_inventorysync.data.vanilla.InventoryModule;
import com.mrgregles.bsp_inventorysync.data.vanilla.Slots;
import com.mrgregles.bsp_inventorysync.storage.SaveCause;
import com.mrgregles.bsp_inventorysync.storage.Snapshot;
import com.mrgregles.bsp_inventorysync.sync.Sync;
import com.mrgregles.bsp_inventorysync.sync.SyncService;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /invsync edit <player> [word]} (D21): opens the editor for a player online on this server,
 * and writes the edit back on Confirm; for a player who is offline, on their newest snapshot (ED-2). The write is refused when the player has left or is switching
 * servers, or when their inventory changed while the admin edited (nothing is overwritten). With the
 * sync on, the player's state just before the edit is saved and pinned, so {@code /invsync restore}
 * can undo it, and the edit itself is saved as an admin snapshot; one log line names the admin, the
 * player and the changes.
 */
public final class EditorActions {
    private EditorActions() {}

    /**
     * Minecraft drops every inventory click of a spectator (it only sends the screen again), so the editor
     * would show but never respond: it is not opened for one, and closes if its admin turns spectator.
     */
    static final String SPECTATOR = "Leave spectator mode to use the editor: Minecraft ignores a spectator's clicks in inventory screens.";

    /** Opens the editor; returns null when it opened, or the reason it did not. */
    @Nullable
    public static String open(ServerPlayer admin, ServerPlayer target, @Nullable String word) {
        if (admin.isSpectator()) {
            return SPECTATOR;
        }
        if (admin.getUUID().equals(target.getUUID())) {
            return "You cannot edit your own inventory here.";
        }
        if (SyncService.isSyncing(target.getUUID())) {
            return target.getGameProfile().getName() + "'s data is still arriving; try again in a moment.";
        }
        List<ItemCatalog.Category> categories = word == null || word.isBlank() ? ItemCatalog.categories(admin.server) : List.of(ItemCatalog.search(word.trim()));
        EditorSession session = new EditorSession(target, categories);
        if (word != null && !word.isBlank()) {
            session.click(EditorSession.PAGE + EditorSession.T_ITEMS, 0, false); // start on the search results
        }
        open(admin, session);
        return null;
    }

    static void open(ServerPlayer admin, EditorSession session) {
        admin.openMenu(new SimpleMenuProvider((id, inv, p) -> new EditorMenu(id, admin, session, () -> {
            admin.closeContainer();
            confirm(admin, session).forEach(line -> admin.sendSystemMessage(line));
        }), Component.literal("Editing " + session.targetName())));
    }

    /** Writes the edit to the player if nothing stands in the way; returns the lines for the admin (more may follow when the saves land). */
    public static List<Component> confirm(ServerPlayer admin, EditorSession session) {
        String name = session.targetName();
        EditorSession.Diff diff = session.computeDiff();
        if (diff.isEmpty()) {
            return List.of(Component.literal("No changes to " + name + "."));
        }
        ServerPlayer target = admin.server.getPlayerList().getPlayer(session.target());
        if (target == null || SyncService.isSyncing(session.target())) {
            return List.of(Component.literal(name + " left or is switching servers; nothing was changed.").withStyle(ChatFormatting.RED));
        }
        return confirm(admin, session, target, Sync.active());
    }

    /** As {@link #confirm(ServerPlayer, EditorSession)} with the player and the sync given (tests). */
    public static List<Component> confirm(ServerPlayer admin, EditorSession session, ServerPlayer target, @Nullable SyncService sync) {
        String name = session.targetName();
        Inventory inv = target.getInventory();
        PlayerEnderChestContainer ender = target.getEnderChestInventory();
        if (!session.unchanged(contents(inv, EditorSession.INVENTORY_SIZE), contents(ender, EditorSession.ENDER_SIZE))) {
            return List.of(Component.literal(name + "'s inventory changed while you edited; nothing was changed. Open the editor again to see it.").withStyle(ChatFormatting.RED));
        }
        EditorSession.Diff diff = session.computeDiff();
        CompletableFuture<Snapshot> before = sync == null ? null : sync.save(target, SaveCause.ADMIN);
        List<ItemStack> newInv = session.inventory();
        List<ItemStack> newEnder = session.enderChest();
        for (int i = 0; i < EditorSession.INVENTORY_SIZE; i++) {
            inv.setItem(i, newInv.get(i));
        }
        for (int i = 0; i < EditorSession.ENDER_SIZE; i++) {
            ender.setItem(i, newEnder.get(i));
        }
        target.inventoryMenu.broadcastChanges();
        target.containerMenu.broadcastChanges();
        String admins = admin.getGameProfile().getName();
        BSPInventorySync.LOGGER.info("BSP-InventorySync: {} edited {}'s inventory: {}", admins, name, diff.describe());
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("Changed " + name + "'s inventory: " + diff.describe() + "."));
        if (sync != null) {
            CompletableFuture<Snapshot> after = sync.save(target, SaveCause.ADMIN);
            before.thenCompose(b -> sync.store().pin(target.getUUID(), b.version(), true).thenApply(x -> b))
                    .thenCombine(after, (b, a) -> "Saved as v" + a.version() + "; the state before is v" + b.version() + " (pinned): /invsync restore " + name + " " + b.version() + " undoes it.")
                    .exceptionally(t -> "The change is made but could not be saved yet (" + t.getMessage() + "); it is saved with " + name + "'s next save.")
                    .thenAccept(line -> admin.server.execute(() -> admin.sendSystemMessage(Component.literal(line).withStyle(ChatFormatting.GRAY))));
        }
        return lines;
    }

    // ------------------------------------------------------------------ players who are not on this server (ED-2)

    /** The snapshot an offline edit started from, decoded. */
    public record Opened(Snapshot snapshot, CompoundTag tag) {}

    /**
     * Opens the editor on the newest snapshot of a player who is not on this server. Refused (the future
     * gives the reason) when the sync is off, when the player is online on another server (edit them
     * there), or when they have no saved data. The reason is null when the editor opened.
     */
    public static CompletableFuture<String> openOffline(ServerPlayer admin, UUID id, String name, @Nullable String word, @Nullable SyncService sync) {
        if (admin.isSpectator()) {
            return CompletableFuture.completedFuture(SPECTATOR);
        }
        if (sync == null) {
            return CompletableFuture.completedFuture(name + " is not online here, and editing offline players needs the sync on.");
        }
        return sync.store().presence(id).thenCompose(p -> {
            if (p.isPresent() && p.get().online() && !p.get().serverId().equals(sync.serverId())) {
                return CompletableFuture.completedFuture(name + " is on " + p.get().serverId() + "; edit them there.");
            }
            return sync.networkLatest(id).thenApply(latest -> latest.map(s -> new Opened(s, PlayerSnapshotCodec.decode(s.data()))));
        }).thenCompose(result -> {
            CompletableFuture<String> out = new CompletableFuture<>();
            admin.server.execute(() -> {
                if (result instanceof String reason) {
                    out.complete(reason);
                    return;
                }
                @SuppressWarnings("unchecked")
                java.util.Optional<Opened> opened = (java.util.Optional<Opened>) result;
                if (opened.isEmpty()) {
                    out.complete(name + " has no saved data to edit.");
                    return;
                }
                if (admin.server.getPlayerList().getPlayer(id) != null) {
                    out.complete(name + " just joined this server; run the command again to edit them live.");
                    return;
                }
                if (admin.isSpectator()) {
                    out.complete(SPECTATOR); // turned spectator while the snapshot was read
                    return;
                }
                List<ItemCatalog.Category> categories = word == null || word.isBlank() ? ItemCatalog.categories(admin.server) : List.of(ItemCatalog.search(word.trim()));
                EditorSession session = offlineSession(id, name, opened.get().tag(), categories);
                if (word != null && !word.isBlank()) {
                    session.click(EditorSession.PAGE + EditorSession.T_ITEMS, 0, false);
                }
                admin.openMenu(new SimpleMenuProvider((cid, inv, pl) -> new EditorMenu(cid, admin, session, () -> {
                    admin.closeContainer();
                    confirmOffline(admin, session, opened.get(), sync).thenAccept(lines -> admin.server.execute(() -> lines.forEach(admin::sendSystemMessage)));
                }), Component.literal("Editing " + name + " (offline)")));
                admin.sendSystemMessage(Component.literal("Editing " + name + "'s saved inventory: v" + opened.get().snapshot().version() + ", saved on " + opened.get().snapshot().serverId()
                        + ". The change reaches them at their next login.").withStyle(ChatFormatting.GRAY));
                out.complete(null);
            });
            return out;
        }).exceptionally(t -> "Could not read " + name + "'s data: " + t.getMessage());
    }

    /** A session over a snapshot's inventory and ender chest (unknown items come in as placeholders, and go back as the original). */
    public static EditorSession offlineSession(UUID id, String name, CompoundTag tag, List<ItemCatalog.Category> categories) {
        CompoundTag invModule = PlayerSnapshotCodec.module(tag, InventoryModule.ID);
        CompoundTag enderModule = PlayerSnapshotCodec.module(tag, EnderChestModule.ID);
        List<ItemStack> inv = Slots.fromTag(invModule == null ? new ListTag() : invModule.getList("Items", Tag.TAG_COMPOUND), EditorSession.INVENTORY_SIZE);
        List<ItemStack> ender = Slots.fromTag(enderModule == null ? new ListTag() : enderModule.getList("Items", Tag.TAG_COMPOUND), EditorSession.ENDER_SIZE);
        return new EditorSession(id, name, inv, ender, categories);
    }

    /**
     * Writes an offline edit as the player's newest snapshot. Refused when the player has come online
     * anywhere, or when a newer snapshot of theirs has been saved since the editor opened. While writing,
     * this server holds the player's checkout in the store (as a server switch does), so a server they log
     * in to meanwhile waits for the edit instead of reading the snapshot before it. The checkout is claimed
     * before the version is checked, in one step (D29): no arrival can slip in between the check and the
     * write. An arrival on this server waits as well ({@link SyncService#beginWrite}). The snapshot the
     * edit started from is pinned. Server thread.
     */
    public static CompletableFuture<List<Component>> confirmOffline(ServerPlayer admin, EditorSession session, Opened opened, SyncService sync) {
        String name = session.targetName();
        UUID id = session.target();
        EditorSession.Diff diff = session.computeDiff();
        if (diff.isEmpty()) {
            return CompletableFuture.completedFuture(List.of(Component.literal("No changes to " + name + ".")));
        }
        if (admin.server.getPlayerList().getPlayer(id) != null) {
            return refuse(name + " joined this server while you edited; nothing was changed. Edit them live.");
        }
        long from = opened.snapshot().version();
        return com.mrgregles.bsp_inventorysync.sync.OfflineWrite.<List<Component>>run(sync, id,
                () -> refuse("Another change to " + name + " is being written on this server, or it is still saving them; nothing was changed. Try again in a moment."),
                held -> refuse(name + " came online on " + held.serverId() + " while you edited; nothing was changed. Edit them there."),
                () -> sync.networkLatest(id).thenCompose(latest -> {
                if (latest.isEmpty() || latest.get().version() != from) {
                    return refuse(name + "'s data changed while you edited (now v" + latest.map(Snapshot::version).orElse(0L) + ", you opened v" + from + "); nothing was changed. Open the editor again.");
                }
                CompoundTag edited = editedTag(opened.tag(), session);
                byte[] data = PlayerSnapshotCodec.encode(edited);
                // saved under the server whose snapshot it changes (D29 audit): a dank's frequency belongs to that server,
                // and the restore renumbers it only when the snapshot comes from another server than the one restoring it
                return sync.store().save(id, latest.get().serverId(), SaveCause.ADMIN, data)
                        .thenCompose(saved -> sync.store().pin(id, from, true).handle((pinned, pinError) -> {
                            BSPInventorySync.LOGGER.info("BSP-InventorySync: {} edited {}'s saved inventory (v{} to v{}): {}", admin.getGameProfile().getName(), name, from, saved.version(), diff.describe());
                            String undo = pinError == null && Boolean.TRUE.equals(pinned)
                                    ? "the state before is v" + from + " (pinned): /invsync restore " + name + " " + from + " undoes it while they are online."
                                    : "the state before is v" + from + ", but it could not be pinned (" + (pinError == null ? "not found" : pinError.getMessage()) + "): pin it with /invsync pin " + name + " " + from + ".";
                            return List.<Component>of(Component.literal("Changed " + name + "'s saved inventory: " + diff.describe() + "."),
                                    Component.literal("Saved as v" + saved.version() + "; " + undo + " They get the change at their next login.").withStyle(ChatFormatting.GRAY));
                        }));
                })).exceptionally(t -> List.of(Component.literal("Could not save the change to " + name + ": " + t.getMessage() + ". Nothing was changed.").withStyle(ChatFormatting.RED)));
    }

    private static CompletableFuture<List<Component>> refuse(String line) {
        return CompletableFuture.completedFuture(List.of(Component.literal(line).withStyle(ChatFormatting.RED)));
    }

    /** The snapshot the edit started from with its inventory and ender chest replaced; every other module (and the selected slot and cursor) kept. */
    static CompoundTag editedTag(CompoundTag original, EditorSession session) {
        CompoundTag tag = original.copy();
        tag.remove(PlayerSnapshotCodec.KEY_NOTE); // a staff panel's note belongs to its own change, not to this one
        CompoundTag modules = tag.getCompound("Modules");
        CompoundTag inv = modules.getCompound(InventoryModule.ID);
        inv.put("Items", Slots.toTag(session.inventory()));
        modules.put(InventoryModule.ID, inv);
        CompoundTag ender = modules.getCompound(EnderChestModule.ID);
        ender.put("Items", Slots.toTag(session.enderChest()));
        modules.put(EnderChestModule.ID, ender);
        tag.put("Modules", modules);
        return tag;
    }

    private static List<ItemStack> contents(net.minecraft.world.Container c, int n) {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < n && i < c.getContainerSize(); i++) {
            out.add(c.getItem(i));
        }
        return out;
    }
}
