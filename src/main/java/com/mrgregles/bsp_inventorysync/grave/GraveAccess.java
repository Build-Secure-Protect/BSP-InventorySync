package com.mrgregles.bsp_inventorysync.grave;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.compat.DankStorage;
import com.mrgregles.bsp_inventorysync.compat.SophisticatedBackpacks;
import com.mrgregles.bsp_inventorysync.give.Room;
import com.mrgregles.bsp_inventorysync.storage.Grave;
import com.mrgregles.bsp_inventorysync.storage.GraveStore;
import com.mrgregles.bsp_inventorysync.sync.SyncService;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Opening graves and taking from them (D25 2b, 3a, 23a, 24a).
 *
 * <p>Who may open one: its owner at any time; anyone else once it is public ({@code graves.privateMinutes} after the
 * death, as its block shows). The first to open it gets all its experience points. Every opening counts as touching
 * it (the 7-day and 30-day clocks start again). A player whose own data is still arriving opens nothing.
 *
 * <p>Taking is written before it is handed over: the grave without the taken stacks is stored at the revision the
 * screen was showing, and only once the store has accepted it do the stacks go into the player's inventory. So a
 * stack is never taken twice, by two players or on two servers. A write is never given up on: when the store answers
 * with an error, the grave is read back to learn whether the write landed after all (a slow database commits late).
 * An emptied grave stays in the store as an empty marker (never deleted here), so no old copy can bring it back.
 * Stacks that no longer fit when the answer comes, or whose taker left or died meanwhile, go into a new grave of the
 * taker's (no block, recovered with {@code /grave} at once), with their backpack and dank contents: nothing is ever
 * dropped or lost.
 */
public final class GraveAccess {
    private static final int VERIFY_TRIES = 5;
    private static final long VERIFY_DELAY_MS = 2_000;

    private final GraveService graves;
    private final MinecraftServer server;
    /** The screens open on each grave on this server, so a take in one shows in the others. */
    private final Map<UUID, Set<GraveMenu>> open = new HashMap<>();

    GraveAccess(GraveService graves, MinecraftServer server) {
        this.graves = graves;
        this.server = server;
    }

    /**
     * Runs {@code then} on the server thread when {@code f} completes: at once if it already has and this is the server
     * thread. Never with a timeout of its own: a write that is answered late has still happened.
     */
    <T> void onServer(CompletableFuture<T> f, BiConsumer<T, Throwable> then) {
        if (f.isDone() && server.isSameThread()) {
            T value = null;
            Throwable error = null;
            try {
                value = f.join();
            } catch (CompletionException | CancellationException e) {
                error = e.getCause() != null ? e.getCause() : e;
            }
            then.accept(value, error);
            return;
        }
        f.whenComplete((v, e) -> server.execute(() -> then.accept(v, e)));
    }

    /** What a write did: landed, refused (the grave changed first), or unknown (the store never told, even when asked again). */
    enum Written { YES, NO, UNKNOWN }

    /**
     * Stores {@code next} over the grave at its revision. An error is not taken as "not written": the grave is read
     * back (a few times, 2 s apart) and the write counts as landed when the stored grave is exactly {@code next} one
     * revision on, refused when it still has the old revision.
     */
    CompletableFuture<Written> write(GraveStore store, Grave next) {
        CompletableFuture<Written> result = new CompletableFuture<>();
        CompletableFuture<Boolean> f;
        try {
            f = store.updateGrave(next);
        } catch (RuntimeException e) {
            f = CompletableFuture.failedFuture(e);
        }
        f.whenComplete((ok, error) -> {
            if (error == null) {
                result.complete(Boolean.TRUE.equals(ok) ? Written.YES : Written.NO);
            } else {
                verify(store, next, VERIFY_TRIES, result);
            }
        });
        return result;
    }

    private void verify(GraveStore store, Grave next, int tries, CompletableFuture<Written> result) {
        CompletableFuture<Void> later = new CompletableFuture<>();
        CompletableFuture.delayedExecutor(VERIFY_DELAY_MS, TimeUnit.MILLISECONDS).execute(() -> later.complete(null));
        later.thenCompose(v -> store.grave(next.id())).whenComplete((found, error) -> {
            if (error != null) {
                if (tries > 1) {
                    verify(store, next, tries - 1, result);
                } else {
                    result.complete(Written.UNKNOWN);
                }
                return;
            }
            if (found.isPresent() && found.get().revision() == next.revision() + 1 && same(found.get(), next)) {
                result.complete(Written.YES);
            } else {
                result.complete(Written.NO);
            }
        });
    }

    private static boolean same(Grave stored, Grave written) {
        return stored.owner().equals(written.owner()) && stored.touchedAt() == written.touchedAt() && stored.xp() == written.xp()
                && stored.stacks() == written.stacks() && stored.archivedAt() == written.archivedAt() && Arrays.equals(stored.data(), written.data());
    }

    public boolean mayOpen(UUID viewer, Grave g, long now) {
        return mayOpen(viewer, g, now, g.diedAt() + graves.privateMs());
    }

    private boolean mayOpen(UUID viewer, Grave g, long now, long publicAt) {
        return viewer.equals(g.owner()) || now >= publicAt;
    }

    private static boolean gone(ServerPlayer p) {
        return p.isRemoved() || p.hasDisconnected() || !p.isAlive();
    }

    // ------------------------------------------------------------------ opening

    /** A right-click on a grave block. */
    public void openAt(ServerPlayer viewer, ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof GraveBlockEntity be) || be.graveId() == null) {
            removeBlock(level, pos);
            viewer.displayClientMessage(Component.literal("This grave is empty.").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        if (be.networked() && !graves.networked()) {
            // its grave is in the network database, which this server is not connected to now: never take its block for an empty one
            viewer.displayClientMessage(Component.literal("This grave is kept in the network database, which this server cannot reach right now.")
                    .withStyle(ChatFormatting.GOLD), true);
            return;
        }
        open(viewer, be.graveId(), false, level.dimension(), pos, be.publicAt());
    }

    /**
     * Opens a grave's screen for the viewer (a block at {@code blockPos}, or none for a command). {@code admin} skips the
     * owner-first hour and lets an admin see an archived grave. Completes with the screen, or null when it was refused
     * (the viewer is told why).
     */
    public CompletableFuture<GraveMenu> open(ServerPlayer viewer, UUID graveId, boolean admin, @Nullable ResourceKey<Level> blockLevel, @Nullable BlockPos blockPos) {
        return open(viewer, graveId, admin, blockLevel, blockPos, null);
    }

    private CompletableFuture<GraveMenu> open(ServerPlayer viewer, UUID graveId, boolean admin, @Nullable ResourceKey<Level> blockLevel, @Nullable BlockPos blockPos,
                                              @Nullable Long publicAt) {
        CompletableFuture<GraveMenu> result = new CompletableFuture<>();
        if (server.isSameThread()) {
            open(viewer, graveId, admin, blockLevel, blockPos, publicAt, result, 2);
        } else {
            server.execute(() -> open(viewer, graveId, admin, blockLevel, blockPos, publicAt, result, 2));
        }
        return result;
    }

    private void open(ServerPlayer viewer, UUID graveId, boolean admin, @Nullable ResourceKey<Level> blockLevel, @Nullable BlockPos blockPos,
                      @Nullable Long publicAt, CompletableFuture<GraveMenu> result, int tries) {
        if (SyncService.isSyncing(viewer.getUUID())) {
            refuse(viewer, "Your own data is still arriving. Try again in a moment.", result);
            return;
        }
        GraveStore store = graves.storeFor(graveId);
        if (store == null) {
            refuse(viewer, "This grave is still being saved. Try again in a moment.", result);
            graves.retry();
            return;
        }
        onServer(store.grave(graveId), (found, error) -> {
            if (error != null) {
                refuse(viewer, "Graves cannot be reached right now. Try again in a moment.", result);
                return;
            }
            if (found.isEmpty() || found.get().empty()) {
                if (blockPos != null && blockLevel != null) {
                    removeBlock(server.getLevel(blockLevel), blockPos);
                }
                refuse(viewer, "This grave is empty.", result);
                return;
            }
            Grave g = found.get();
            long now = System.currentTimeMillis();
            if (g.archived() && !admin) {
                if (blockPos != null && blockLevel != null) {
                    removeBlock(server.getLevel(blockLevel), blockPos); // put away on another server: its block goes here too
                }
                refuse(viewer, "This grave has been put away. An admin can still return it.", result);
                return;
            }
            long until = publicAt != null ? publicAt : g.diedAt() + graves.privateMs();
            if (!admin && !mayOpen(viewer.getUUID(), g, now, until)) {
                refuse(viewer, g.ownerName() + "'s grave: only " + g.ownerName() + " may open it for another " + GraveText.clock(until - now) + ".", result);
                return;
            }
            try {
                GraveContents.decode(g.data());
            } catch (RuntimeException e) {
                BSPInventorySync.LOGGER.error("BSP-InventorySync: grave {} ({}'s) cannot be read: {}", g.shortId(), g.ownerName(), e.toString());
                refuse(viewer, "This grave cannot be read. An admin can see the server log.", result);
                return;
            }
            Grave touched = g.withContents(g.stacks(), 0, g.data(), now);
            write(store, touched).whenComplete((written, err) -> server.execute(() -> {
                if (written != Written.YES) {
                    if (written == Written.NO && tries > 0) {
                        open(viewer, graveId, admin, blockLevel, blockPos, publicAt, result, tries - 1); // someone else touched it first: read it again
                    } else {
                        if (written == Written.UNKNOWN) {
                            BSPInventorySync.LOGGER.error("BSP-InventorySync: opening grave {} ({}'s) was not confirmed by the database; if its {} xp are gone, they were never given",
                                    g.shortId(), g.ownerName(), g.xp());
                        }
                        refuse(viewer, "This grave could not be opened. Try again in a moment.", result);
                    }
                    return;
                }
                Grave fresh = touched.nextRevision();
                if (gone(viewer)) {
                    giveXpBack(store, fresh, g.xp()); // left or died while it was written
                    result.complete(null);
                    return;
                }
                BSPInventorySync.LOGGER.info("BSP-InventorySync: {} opened grave {} ({}'s, {} stack(s))", viewer.getGameProfile().getName(), g.shortId(), g.ownerName(), g.stacks());
                if (!viewer.getUUID().equals(g.owner())) {
                    graves.notify(g.owner(), viewer.getGameProfile().getName() + " opened your grave" + (g.serverId().isEmpty() ? "" : " on " + g.serverId()) + ".");
                }
                viewer.openMenu(new SimpleMenuProvider((id, inv, p) -> new GraveMenu(id, viewer, this, fresh, blockLevel, blockPos),
                        Component.literal(g.ownerName() + "'s grave")));
                if (g.xp() > 0) {
                    viewer.giveExperiencePoints(g.xp());
                    BSPInventorySync.LOGGER.info("BSP-InventorySync: {} got the {} xp of grave {} ({}'s)", viewer.getGameProfile().getName(), g.xp(), g.shortId(), g.ownerName());
                }
                if (viewer.containerMenu instanceof GraveMenu menu && menu.grave().id().equals(graveId)) {
                    open.computeIfAbsent(graveId, k -> new LinkedHashSet<>()).add(menu);
                    refreshOthers(menu, fresh);
                    result.complete(menu);
                } else {
                    result.complete(null);
                }
            }));
        });
    }

    /** The XP of a grave whose opener left before it was handed over goes back into the grave. */
    private void giveXpBack(GraveStore store, Grave now, int xp) {
        if (xp <= 0) {
            return;
        }
        write(store, now.withContents(now.stacks(), now.xp() + xp, now.data(), now.touchedAt())).whenComplete((w, e) -> {
            if (w != Written.YES) {
                BSPInventorySync.LOGGER.error("BSP-InventorySync: the {} xp of grave {} could not be put back after its opener left", xp, now.shortId());
            }
        });
    }

    private void refuse(ServerPlayer viewer, String why, CompletableFuture<GraveMenu> result) {
        viewer.displayClientMessage(Component.literal(why).withStyle(ChatFormatting.GOLD), false);
        result.complete(null);
    }

    void closed(GraveMenu menu) {
        Set<GraveMenu> menus = open.get(menu.grave().id());
        if (menus != null) {
            menus.remove(menu);
            if (menus.isEmpty()) {
                open.remove(menu.grave().id());
            }
        }
    }

    private void refreshOthers(GraveMenu except, Grave now) {
        for (GraveMenu m : List.copyOf(open.getOrDefault(now.id(), Set.of()))) {
            if (m != except && m.player().containerMenu == m) {
                if (now.empty()) {
                    m.finish();
                } else {
                    m.show(now);
                }
            }
        }
    }

    // ------------------------------------------------------------------ taking

    /** What a take does: the stacks that go to the player (each with the inventory slot it goes to, or -1 for anywhere), and what stays. */
    record Plan(List<GraveContents.Entry> taken, List<Integer> targets, List<GraveContents.Entry> left) {}

    /**
     * Works out a take on a copy of the player's inventory: first each chosen stack whose own slot is free goes back
     * there, then the others go into the main inventory (merging first); the part that does not fit stays in the grave.
     */
    static Plan plan(Inventory inv, GraveContents c, Collection<Integer> chosen) {
        List<ItemStack> slots = new ArrayList<>();
        for (int i = 0; i < 41; i++) {
            slots.add(inv.getItem(i).copy());
        }
        Set<Integer> pick = new HashSet<>(chosen);
        List<GraveContents.Entry> taken = new ArrayList<>(), left = new ArrayList<>();
        List<Integer> targets = new ArrayList<>();
        // first every chosen stack whose own slot is free goes home, so another stack never takes a slot its owner wants back
        Set<Integer> wentHome = new HashSet<>();
        for (int i = 0; i < c.entries().size(); i++) {
            int home = GraveContents.invIndex(c.entries().get(i).slot());
            if (pick.contains(i) && home >= 0 && slots.get(home).isEmpty()) {
                slots.set(home, c.entries().get(i).stack().copy());
                wentHome.add(i);
            }
        }
        for (int i = 0; i < c.entries().size(); i++) {
            GraveContents.Entry e = c.entries().get(i);
            if (!pick.contains(i)) {
                left.add(e);
                continue;
            }
            ItemStack stack = e.stack();
            if (wentHome.contains(i)) {
                taken.add(e);
                targets.add(GraveContents.invIndex(e.slot()));
                continue;
            }
            int fit = placeOnMain(slots, stack);
            if (fit >= stack.getCount()) {
                taken.add(e);
                targets.add(-1);
            } else if (fit > 0) {
                taken.add(new GraveContents.Entry(e.slot(), stack.copyWithCount(fit)));
                targets.add(-1);
                left.add(new GraveContents.Entry(e.slot(), stack.copyWithCount(stack.getCount() - fit)));
            } else {
                left.add(e);
            }
        }
        return new Plan(taken, targets, left);
    }

    /** Places as much of the stack as fits on the copy's main inventory (0 to 35), merging first; how many. */
    private static int placeOnMain(List<ItemStack> slots, ItemStack stack) {
        int max = Math.min(stack.getMaxStackSize(), 64);
        int left = stack.getCount();
        for (int i = 0; i < Room.MAIN && left > 0; i++) {
            ItemStack s = slots.get(i);
            if (!s.isEmpty() && ItemStack.isSameItemSameTags(s, stack)) {
                int move = Math.min(left, max - s.getCount());
                if (move > 0) {
                    s.grow(move);
                    left -= move;
                }
            }
        }
        for (int i = 0; i < Room.MAIN && left > 0; i++) {
            if (slots.get(i).isEmpty()) {
                int move = Math.min(left, max);
                slots.set(i, stack.copyWithCount(move));
                left -= move;
            }
        }
        return stack.getCount() - left;
    }

    /** A click on a stack, or Take all: writes the grave without what fits, then hands that over. */
    void take(GraveMenu menu, List<Integer> chosen) {
        ServerPlayer p = menu.player();
        Grave g = menu.grave();
        GraveContents c = menu.contents();
        Plan plan = plan(p.getInventory(), c, chosen);
        if (plan.taken().isEmpty()) {
            p.displayClientMessage(Component.literal("Make room in your inventory first.").withStyle(ChatFormatting.GOLD), true);
            return;
        }
        GraveStore store = graves.storeFor(g.id());
        if (store == null) {
            p.displayClientMessage(Component.literal("This grave is still being saved. Try again in a moment.").withStyle(ChatFormatting.GOLD), true);
            return;
        }
        long now = System.currentTimeMillis();
        // an emptied grave is written as empty, never deleted here: it stays as a marker so no old copy can bring it back
        Grave next = g.withContents(plan.left().size(), g.xp(), new GraveContents(plan.left(), c.extras()).encode(), now);
        boolean empties = next.empty();
        menu.setBusy(true);
        write(store, next).whenComplete((written, error) -> server.execute(() -> {
            menu.setBusy(false);
            if (written != Written.YES) {
                if (written == Written.UNKNOWN) {
                    BSPInventorySync.LOGGER.error("BSP-InventorySync: the database never confirmed {} taking from grave {} ({}'s); if the grave no longer holds {}, give them back by hand",
                            p.getGameProfile().getName(), g.shortId(), g.ownerName(), describe(plan.taken()));
                    p.displayClientMessage(Component.literal("The database did not answer; nothing was taken. Try again in a moment.").withStyle(ChatFormatting.GOLD), true);
                } else {
                    p.displayClientMessage(Component.literal("The grave changed meanwhile; here it is as it is now.").withStyle(ChatFormatting.GOLD), true);
                }
                reread(menu, store);
                return;
            }
            List<ItemStack> leftover = handOver(p, plan, g);
            BSPInventorySync.LOGGER.info("BSP-InventorySync: {} took {} stack(s) from grave {} ({}'s){}", p.getGameProfile().getName(), plan.taken().size(),
                    g.shortId(), g.ownerName(), empties ? "; it is empty and gone" : "");
            Grave stored = next.nextRevision();
            if (empties) {
                removeBlockOf(g);
                for (GraveMenu m : List.copyOf(open.getOrDefault(g.id(), Set.of()))) {
                    m.finish();
                }
                open.remove(g.id());
            } else {
                menu.show(stored);
                refreshOthers(menu, stored);
            }
            if (!leftover.isEmpty()) {
                keepForTaker(p, g, leftover, c.extras());
            }
        }));
    }

    private static String describe(List<GraveContents.Entry> entries) {
        List<String> parts = new ArrayList<>();
        entries.forEach(e -> parts.add(e.stack().getCount() + " " + e.stack().getHoverName().getString()));
        return String.join(", ", parts);
    }

    /**
     * Puts the planned stacks into the inventory now. Each one is checked against the inventory as it is at this moment
     * (it may have changed while the store answered): what fits goes in, with its backpack and dank contents written
     * here first when the grave comes from another server; what no longer fits, or everything when the player has left
     * or died meanwhile, is returned untouched.
     */
    private List<ItemStack> handOver(ServerPlayer p, Plan plan, Grave g) {
        List<ItemStack> leftover = new ArrayList<>();
        if (gone(p)) {
            plan.taken().forEach(e -> leftover.add(e.stack().copy()));
            return leftover;
        }
        Inventory inv = p.getInventory();
        List<ItemStack> room = new ArrayList<>();
        for (int i = 0; i < 41; i++) {
            room.add(inv.getItem(i).copy());
        }
        List<ItemStack> fits = new ArrayList<>();
        List<Integer> where = new ArrayList<>();
        for (int i = 0; i < plan.taken().size(); i++) {
            ItemStack s = plan.taken().get(i).stack().copy();
            int target = plan.targets().get(i);
            if (target >= 0 && room.get(target).isEmpty()) {
                room.set(target, s.copy());
                fits.add(s);
                where.add(target);
                continue;
            }
            int fit = placeOnMain(room, s);
            if (fit > 0) {
                fits.add(s.copyWithCount(fit));
                where.add(-1);
            }
            if (fit < s.getCount()) {
                leftover.add(s.copyWithCount(s.getCount() - fit));
            }
        }
        restoreExtras(p.serverLevel(), fits, GraveContents.decode(g.data()).extras(), g.serverId()); // only for what really goes in
        for (int i = 0; i < fits.size(); i++) {
            ItemStack s = fits.get(i);
            if (where.get(i) >= 0) {
                inv.setItem(where.get(i), s);
            } else {
                Room.insert(inv, s);
                if (!s.isEmpty()) {
                    leftover.add(s); // cannot happen (it was checked just above); kept rather than lost
                }
            }
        }
        inv.setChanged();
        p.inventoryMenu.broadcastChanges();
        return leftover;
    }

    /**
     * Stacks a player took but could not receive go into a new grave of theirs, with no block (recovered with /grave at
     * once), carrying the backpack and dank contents of the grave they came from. On a synced server it goes straight to
     * the database (the player's own inventory never held them, so no snapshot is involved), tried again if need be and
     * kept in this world's data meanwhile.
     */
    private void keepForTaker(ServerPlayer p, Grave from, List<ItemStack> stacks, CompoundTag extras) {
        List<GraveContents.Entry> entries = new ArrayList<>();
        stacks.forEach(s -> entries.add(new GraveContents.Entry("", s)));
        long now = System.currentTimeMillis();
        Grave kept = new Grave(UUID.randomUUID(), p.getUUID(), p.getGameProfile().getName(), graves.serverIdNow(), from.dimension(), from.x(), from.y(), from.z(), false,
                now, now, 0, 0, entries.size(), 0, new GraveContents(entries, extras.copy()).encode());
        graves.keep(kept);
        BSPInventorySync.LOGGER.info("BSP-InventorySync: {} stack(s) {} could not take from grave {} wait for them in grave {}: {}", stacks.size(),
                p.getGameProfile().getName(), from.shortId(), kept.shortId(), describe(entries));
        if (!p.hasDisconnected()) {
            p.displayClientMessage(Component.literal(stacks.size() + (stacks.size() == 1 ? " stack" : " stacks") + " did not fit; they wait for you in a grave: see /grave.")
                    .withStyle(ChatFormatting.GOLD), false);
        }
    }

    private void reread(GraveMenu menu, GraveStore store) {
        onServer(store.grave(menu.grave().id()), (found, error) -> {
            if (error != null) {
                return; // the screen stays as it was; the next click tries again
            }
            if (found.isEmpty() || found.get().archived() || found.get().empty()) {
                menu.finish();
            } else {
                menu.show(found.get());
            }
        });
    }

    // ------------------------------------------------------------------ contents from another server

    /**
     * Backpack and dank contents travel with the grave: taken on another server than the one where the player died,
     * the backpacks among the stacks (and the backpacks inside them) get their contents written into this server's
     * storage, and the danks get a frequency here with their contents. Only for stacks that go into an inventory.
     */
    void restoreExtras(ServerLevel level, List<ItemStack> stacks, CompoundTag extras, String graveServer) {
        if (graveServer.equals(graves.serverIdNow()) || extras.isEmpty() || stacks.isEmpty()) {
            return;
        }
        try {
            ListTag packs = packsFor(stacks, extras.getList(GraveService.PACKS, Tag.TAG_COMPOUND));
            if (!packs.isEmpty()) {
                SophisticatedBackpacks.Module.restorePacks(packs);
            }
        } catch (Throwable t) {
            BSPInventorySync.LOGGER.error("BSP-InventorySync: backpack contents from a grave could not be written here", t);
        }
        try {
            DankStorage.Module.restoreDanks(stacks, extras.getList(GraveService.DANKS, Tag.TAG_COMPOUND), level);
        } catch (Throwable t) {
            BSPInventorySync.LOGGER.error("BSP-InventorySync: dank contents from a grave could not be written here", t);
        }
    }

    /** The saved backpacks that belong to these stacks: the ones they hold and, in turn, the ones inside those. */
    static ListTag packsFor(Collection<ItemStack> stacks, ListTag packs) {
        Set<UUID> wanted = new HashSet<>();
        for (ItemStack s : stacks) {
            if (s.hasTag()) {
                SophisticatedBackpacks.collectUuids(s.getTag(), wanted);
            }
        }
        ListTag out = new ListTag();
        Set<UUID> chosen = new HashSet<>();
        boolean more = true;
        while (more) {
            more = false;
            for (Tag t : packs) {
                CompoundTag entry = (CompoundTag) t;
                if (!entry.hasUUID("Uuid")) {
                    continue;
                }
                UUID id = entry.getUUID("Uuid");
                if (wanted.contains(id) && chosen.add(id)) {
                    out.add(entry.copy());
                    SophisticatedBackpacks.collectUuids(entry.getCompound("Contents"), wanted);
                    more = true;
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ the block

    /** Removes a grave's block when it stands on this server and is loaded (an emptied grave). */
    void removeBlockOf(Grave g) {
        if (!g.placed() || !g.serverId().equals(graves.serverIdNow())) {
            return;
        }
        ResourceLocation dim = ResourceLocation.tryParse(g.dimension());
        ServerLevel level = dim == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, dim));
        BlockPos pos = new BlockPos(g.x(), g.y(), g.z());
        if (level != null && level.hasChunkAt(pos) && level.getBlockEntity(pos) instanceof GraveBlockEntity be && g.id().equals(be.graveId())) {
            removeBlock(level, pos);
        }
    }

    /** Whether a placed grave of this server has lost its block (a crash rolled the world back): it is then recovered at once. */
    boolean blockMissing(Grave g) {
        if (!g.placed() || !g.serverId().equals(graves.serverIdNow())) {
            return false;
        }
        ResourceLocation dim = ResourceLocation.tryParse(g.dimension());
        ServerLevel level = dim == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, dim));
        BlockPos pos = new BlockPos(g.x(), g.y(), g.z());
        if (level == null || !level.hasChunkAt(pos)) {
            return false;
        }
        return !(level.getBlockEntity(pos) instanceof GraveBlockEntity be && g.id().equals(be.graveId()));
    }

    /** The grave block at the spot goes, with the usual break puff. */
    static void removeBlock(@Nullable ServerLevel level, BlockPos pos) {
        if (level == null) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (state.is(GraveRegistry.GRAVE.get())) {
            level.levelEvent(2001, pos, Block.getId(state));
            level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    /** For tests and the admin screen: the screens open on a grave here. */
    public int openScreens(UUID graveId) {
        return open.getOrDefault(graveId, Set.of()).size();
    }

    /** A player left: their screens are forgotten. */
    void loggedOut(UUID player) {
        open.values().forEach(menus -> menus.removeIf(m -> m.player().getUUID().equals(player)));
        open.values().removeIf(Set::isEmpty);
    }
}
