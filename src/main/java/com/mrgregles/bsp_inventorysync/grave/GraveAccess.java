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
                keepForTaker(p, g, leftover, c.extras(), 0);
            }
        }));
    }

    /** In a grave's extras: who asked for it to be returned to its owner at their next arrival (D30, audit 4a). */
    static final String RETURN_BY = "ReturnBy";

    /** Whether an admin may return the grave (D30 5b): one that could not be placed, or one put away. A standing headstone is opened where it stands. */
    boolean returnable(Grave g) {
        return !g.placed() || g.archived();
    }

    /** Who marked the grave to be returned at its owner's next arrival, or null. */
    @Nullable
    static String returnMark(Grave g) {
        try {
            CompoundTag extras = GraveContents.decode(g.data()).extras();
            return extras.contains(RETURN_BY, Tag.TAG_STRING) ? extras.getString(RETURN_BY) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Marks the grave to be returned to its owner at their next arrival (D30 2b): written at the grave's revision, so it
     * never races another change. Completes with whether it was written (false: the grave changed meanwhile, or the
     * store did not answer).
     */
    CompletableFuture<Boolean> markReturn(Grave g, String by) {
        GraveStore store = graves.storeFor(g.id());
        if (store == null) {
            return CompletableFuture.completedFuture(false);
        }
        GraveContents c = GraveContents.decode(g.data());
        CompoundTag extras = c.extras().copy();
        extras.putString(RETURN_BY, by);
        return write(store, g.withContents(g.stacks(), g.xp(), new GraveContents(c.entries(), extras).encode(), g.touchedAt())).thenApply(w -> w == Written.YES);
    }

    /**
     * An admin returns the grave to its owner, online on this server with their data arrived (D30 and its audit): through
     * the same steps as Take all (the grave is written without what fits first, and only then is that handed over), every
     * stack goes back to the slot it came from when that is free, else into the inventory; armour is worn again, backpack
     * and dank contents come with it, and the grave's XP goes to them. What does not fit stays in the grave, which stays
     * recoverable with {@code /grave}: a put-away grave is brought back with no block for it, even when nothing fits.
     * Stacks that could not be handed over after all (the player left, or the inventory changed meanwhile) go into a new
     * grave of theirs with the XP and count as left. The return mark, if any, is cleared in the same write. The player is
     * told, one log line, and they are saved at once. A failure answers {@code FAILED}. Server thread.
     */
    public CompletableFuture<com.mrgregles.bsp_inventorysync.api.GraveReturn> giveBack(ServerPlayer p, UUID graveId, String by) {
        CompletableFuture<com.mrgregles.bsp_inventorysync.api.GraveReturn> out = new CompletableFuture<>();
        try {
            giveBack(p, graveId, by, out);
        } catch (RuntimeException e) {
            fail(out, e, graveId);
        }
        return out;
    }

    private void giveBack(ServerPlayer p, UUID graveId, String by, CompletableFuture<com.mrgregles.bsp_inventorysync.api.GraveReturn> out) {
        String name = p.getGameProfile().getName();
        SyncService sync = graves.syncNow();
        if (SyncService.isSyncing(p.getUUID()) || (sync != null && !sync.settled(p.getUUID())) || gone(p)) {
            out.complete(answer(com.mrgregles.bsp_inventorysync.api.GraveReturn.Outcome.BUSY, name + "'s data is not settled here (still arriving, or their login was refused), or they are not alive; try again later.", 0, 0, 0));
            return;
        }
        GraveStore store = graves.storeFor(graveId);
        if (store == null) {
            graves.retry();
            out.complete(answer(com.mrgregles.bsp_inventorysync.api.GraveReturn.Outcome.BUSY, "That grave is still being saved; try again in a moment.", 0, 0, 0));
            return;
        }
        onServer(store.grave(graveId), (found, error) -> {
            try {
                if (error != null) {
                    out.complete(answer(com.mrgregles.bsp_inventorysync.api.GraveReturn.Outcome.FAILED, "Graves cannot be reached right now: " + error.getMessage(), 0, 0, 0));
                    return;
                }
                if (found.isEmpty() || found.get().empty() || !found.get().owner().equals(p.getUUID())) {
                    out.complete(answer(com.mrgregles.bsp_inventorysync.api.GraveReturn.Outcome.NOT_FOUND, name + " has no such grave (or it has been emptied).", 0, 0, 0));
                    return;
                }
                Grave g = found.get();
                if (!returnable(g)) {
                    out.complete(answer(com.mrgregles.bsp_inventorysync.api.GraveReturn.Outcome.REFUSED, name + "'s grave " + g.shortId() + " still stands ("
                            + GraveActions.where(g) + "): it is opened where it stands.", 0, 0, 0));
                    return;
                }
                GraveContents c = GraveContents.decode(g.data());
                List<Integer> all = new ArrayList<>();
                for (int i = 0; i < c.entries().size(); i++) {
                    all.add(i);
                }
                Plan plan = plan(p.getInventory(), c, all);
                long now = System.currentTimeMillis();
                CompoundTag extras = c.extras().copy();
                extras.remove(RETURN_BY);
                Grave next = g.withContents(plan.left().size(), 0, new GraveContents(plan.left(), extras).encode(), now);
                if (next.archived() && !plan.left().isEmpty()) {
                    // put away, not all of it back: brought back, with no block (it went when it was put away), so /grave recovers the rest at once
                    next = next.withArchivedAt(0).withPlaced(false);
                }
                Grave planned = next;
                write(store, next).whenComplete((written, err) -> server.execute(() -> {
                    try {
                        handed(p, g, c, plan, planned, store, written, by, out);
                    } catch (RuntimeException e) {
                        fail(out, e, graveId);
                    }
                }));
            } catch (RuntimeException e) {
                fail(out, e, graveId);
            }
        });
    }

    /** After the write: hands over what was planned, or says why not. */
    private void handed(ServerPlayer p, Grave g, GraveContents c, Plan plan, Grave planned, GraveStore store, Written written, String by,
                        CompletableFuture<com.mrgregles.bsp_inventorysync.api.GraveReturn> out) {
        String name = p.getGameProfile().getName();
        if (written != Written.YES) {
            if (written == Written.UNKNOWN) {
                BSPInventorySync.LOGGER.error("BSP-InventorySync: the database never confirmed {} returning grave {} ({}'s); if the grave no longer holds {}, give them back by hand",
                        by, g.shortId(), g.ownerName(), describe(plan.taken()));
                out.complete(answer(com.mrgregles.bsp_inventorysync.api.GraveReturn.Outcome.FAILED, "The database did not answer; look with /invsync graves " + name + " before trying again.", 0, 0, 0));
            } else {
                out.complete(answer(com.mrgregles.bsp_inventorysync.api.GraveReturn.Outcome.BUSY, "That grave changed meanwhile (someone took from it); try again.", 0, 0, 0));
            }
            return;
        }
        Grave stored = planned.nextRevision();
        List<ItemStack> leftover = handOver(p, plan, g);
        int xp = g.xp();
        boolean away = gone(p);
        if (xp > 0 && !away) {
            p.giveExperiencePoints(xp);
        }
        if (stored.empty()) {
            removeBlockOf(g);
            for (GraveMenu m : List.copyOf(open.getOrDefault(g.id(), Set.of()))) {
                m.finish();
            }
            open.remove(g.id());
        } else {
            refreshOthers(null, stored);
        }
        Grave kept = null;
        int keptXp = away ? xp : 0;
        if (!leftover.isEmpty() || keptXp > 0) {
            kept = keepForTaker(p, g, leftover, c.extras(), keptXp); // the XP goes with them when the player left meanwhile
        }
        int returned = plan.taken().size() - leftover.size();
        int left = plan.left().size() + leftover.size();
        int xpGiven = away ? 0 : xp;
        BSPInventorySync.LOGGER.info("BSP-InventorySync: {} returned grave {} ({}'s) to them: {} stack(s){}{}{}", by, g.shortId(), g.ownerName(), returned,
                xpGiven > 0 ? ", " + xpGiven + " xp" : "", plan.left().isEmpty() ? "" : "; " + plan.left().size() + " stack(s) did not fit and stay in it",
                kept == null ? "" : "; " + leftover.size() + " stack(s)" + (keptXp > 0 ? " and " + keptXp + " xp" : "") + " wait in a new grave " + kept.shortId());
        if (!away) {
            p.sendSystemMessage(Component.literal("An admin returned your grave: " + returned + (returned == 1 ? " stack" : " stacks") + (xpGiven > 0 ? ", " + xpGiven + " xp" : "") + "."
                    + (left > 0 ? " " + left + (left == 1 ? " stack" : " stacks") + " did not fit and wait for you: see /grave." : "")).withStyle(ChatFormatting.GOLD));
            SyncService sync = graves.syncNow();
            if (sync != null && sync.settled(p.getUUID()) && (returned > 0 || xpGiven > 0)) {
                sync.save(p, com.mrgregles.bsp_inventorysync.storage.SaveCause.ADMIN); // the grave is already emptied: a crash before the next auto-save must not lose the items
            }
        }
        String where = kept == null ? "" : " " + leftover.size() + " stack(s) went into a new grave " + kept.shortId() + " (the inventory changed or they left meanwhile).";
        out.complete(answer(left > 0 ? com.mrgregles.bsp_inventorysync.api.GraveReturn.Outcome.PARTIAL : com.mrgregles.bsp_inventorysync.api.GraveReturn.Outcome.OK,
                "Returned " + name + "'s grave " + g.shortId() + ": " + returned + " stack(s)" + (xpGiven > 0 ? ", " + xpGiven + " xp" : "")
                        + (plan.left().isEmpty() ? "." : "; " + plan.left().size() + " stack(s) did not fit and stay in it, recoverable with /grave.") + where,
                returned, left, xpGiven));
    }

    private void fail(CompletableFuture<com.mrgregles.bsp_inventorysync.api.GraveReturn> out, RuntimeException e, UUID graveId) {
        BSPInventorySync.LOGGER.error("BSP-InventorySync: returning grave {} failed", graveId.toString().substring(0, 8), e);
        out.complete(answer(com.mrgregles.bsp_inventorysync.api.GraveReturn.Outcome.FAILED, "Returning that grave failed: " + e + "; see the server log.", 0, 0, 0));
    }

    private com.mrgregles.bsp_inventorysync.api.GraveReturn answer(com.mrgregles.bsp_inventorysync.api.GraveReturn.Outcome outcome, String message, int returned, int left, int xp) {
        return new com.mrgregles.bsp_inventorysync.api.GraveReturn(outcome, message, returned, left, xp, graves.serverIdNow());
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
    private Grave keepForTaker(ServerPlayer p, Grave from, List<ItemStack> stacks, CompoundTag extras, int xp) {
        List<GraveContents.Entry> entries = new ArrayList<>();
        stacks.forEach(s -> entries.add(new GraveContents.Entry("", s)));
        long now = System.currentTimeMillis();
        CompoundTag kept0 = extras.copy();
        kept0.remove(RETURN_BY);
        // the server id of the grave the contents came from (D30 audit 9): its backpack and dank contents belong to that server,
        // and are written into another server's storage only when the new grave is recovered there
        Grave kept = new Grave(UUID.randomUUID(), p.getUUID(), p.getGameProfile().getName(), from.serverId(), from.dimension(), from.x(), from.y(), from.z(), false,
                now, now, 0, xp, entries.size(), 0, new GraveContents(entries, kept0).encode());
        graves.keep(kept);
        BSPInventorySync.LOGGER.info("BSP-InventorySync: {} stack(s){} {} could not take from grave {} wait for them in grave {}: {}", stacks.size(),
                xp > 0 ? " and " + xp + " xp" : "", p.getGameProfile().getName(), from.shortId(), kept.shortId(), describe(entries));
        if (!p.hasDisconnected() && !stacks.isEmpty()) {
            p.displayClientMessage(Component.literal(stacks.size() + (stacks.size() == 1 ? " stack" : " stacks") + " did not fit; they wait for you in a grave: see /grave.")
                    .withStyle(ChatFormatting.GOLD), false);
        }
        return kept;
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
