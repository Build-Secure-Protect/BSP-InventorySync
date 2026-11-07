package com.mrgregles.bsp_inventorysync.give;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.SyncConfig;
import com.mrgregles.bsp_inventorysync.data.ItemCodec;
import com.mrgregles.bsp_inventorysync.storage.Give;
import com.mrgregles.bsp_inventorysync.storage.MemoryStore;
import com.mrgregles.bsp_inventorysync.storage.SyncStore;
import com.mrgregles.bsp_inventorysync.sync.Sync;
import com.mrgregles.bsp_inventorysync.sync.SyncService;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code /invsync give} (D23, D24): items for a player, handed over on whichever server they are on.
 *
 * <p>A player on this server whose data has arrived gets what fits at once. Everything else waits in the
 * store, one row per stack ({@link Give}), so it survives restarts and server switches: every
 * {@code give.pollSeconds} each server looks up the rows of its settled players, and at an arrival it looks
 * at once. A waiting stack that fits whole goes straight in; what does not fit is shown in the waiting-items
 * popup ({@link WaitingMenu}), which opens only when the player has no other screen open. Each row is
 * removed from the store ({@link SyncStore#takeGive}) before its stack goes into the inventory, so it can
 * never arrive twice; a crash at that moment loses that one stack rather than duplicating it.
 *
 * <p>Cancel (or Esc) puts the rest off until the player's next join to the network: the choice is kept in the
 * store as an extra blob, so every server knows it, and a join clears it. A server switch through
 * BSP-Teleport (saved as a transfer) is not a join. With the sync off, the same rules run on a store in
 * memory, for this server only.
 */
public final class GiveService {
    /** The extra blob that says the player cancelled the popup: kept until their next join. */
    public static final String DECLINED = "give_declined";
    /** The popup shows this many waiting stacks at once (two chest rows). */
    public static final int SHOWN = 18;

    @Nullable private static volatile GiveService local;

    private final MinecraftServer server;
    private final SyncStore store;
    /** This server's id on the network, to tell "on the way to another server" from "offline"; null with the sync off. */
    @Nullable private final String serverId;
    private volatile long pollMs;
    private long nextPollAt;
    private boolean pollInFlight;
    /** Players who cancelled; read from the store at each arrival, or before their first hand-over here. */
    private final Set<UUID> declined = ConcurrentHashMap.newKeySet();
    /**
     * Players whose Cancel this server has read (or written). Anyone else (online when the sync started or was
     * reloaded, or whose read failed) has it read before anything is handed over or asked.
     */
    private final Set<UUID> known = ConcurrentHashMap.newKeySet();
    /** Players with a hand-over or an arrival check in flight: the poll leaves them alone. */
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();

    public GiveService(MinecraftServer server, SyncStore store, @Nullable String serverId, long pollMs) {
        this.server = server;
        this.store = store;
        this.serverId = serverId;
        this.pollMs = pollMs;
    }

    /** The service for this server: the sync's when it is on, else one on a store in memory. */
    public static GiveService get(MinecraftServer server) {
        GiveService synced = Sync.gives();
        if (synced != null) {
            return synced;
        }
        GiveService l = local;
        if (l == null || l.server != server) {
            l = new GiveService(server, new MemoryStore(), null, SyncConfig.getOr(SyncConfig.GIVE_POLL_SECONDS, 5) * 1000L);
            local = l;
        }
        return l;
    }

    /** The service if one exists, without creating one (the tick and the login of a server that never gave anything). */
    @Nullable
    public static GiveService peek() {
        GiveService synced = Sync.gives();
        return synced != null ? synced : local;
    }

    public SyncStore store() {
        return store;
    }

    public void setPollMs(long ms) {
        pollMs = ms;
    }

    // ------------------------------------------------------------------ giving

    /**
     * What a give did, for the reply and the log: how many went in at once, how many wait and where those are
     * ("on the way to BSP-Frostwarf"), and how many could not be stored (the store failed: give those again).
     */
    public record Result(int handed, int waiting, int failed, String where) {}

    /**
     * Gives {@code count} of the item to the player: what fits at once when they are on this server and
     * settled, the rest queued. Server thread. The future completes when the rows are stored.
     */
    public CompletableFuture<Result> give(UUID player, String name, ItemStack item, int count, String by) {
        return give(server.getPlayerList().getPlayer(player), player, item, count, by);
    }

    /** As {@link #give(UUID, String, ItemStack, int, String)} with the player on this server given (null: not here); tests. */
    public CompletableFuture<Result> give(@Nullable ServerPlayer online, UUID player, ItemStack item, int count, String by) {
        List<ItemStack> stacks = split(item, count);
        int handed = 0;
        List<ItemStack> rest = new ArrayList<>();
        if (online != null && ready(online)) {
            for (ItemStack s : stacks) {
                handed += Room.insert(online.getInventory(), s); // what fits; s keeps the rest
                if (!s.isEmpty()) {
                    rest.add(s);
                }
            }
            if (handed > 0) {
                online.sendSystemMessage(Component.literal("You received " + handed + " " + item.getHoverName().getString() + " from " + by + ".").withStyle(ChatFormatting.GREEN));
            }
        } else {
            rest.addAll(stacks);
        }
        int handedNow = handed;
        if (rest.isEmpty()) {
            return CompletableFuture.completedFuture(new Result(handedNow, 0, 0, "given"));
        }
        // each row on its own: when the store takes some and fails on others, the reply says exactly how many to give again
        List<CompletableFuture<Boolean>> rows = rest.stream().map(s -> store.addGive(player, encode(s), by).handle((g, e) -> e == null)).toList();
        CompletableFuture<int[]> stored = CompletableFuture.allOf(rows.toArray(CompletableFuture[]::new)).thenApply(v -> {
            int ok = 0, lost = 0;
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).join()) {
                    ok += rest.get(i).getCount();
                } else {
                    lost += rest.get(i).getCount();
                }
            }
            return new int[] {ok, lost};
        });
        if (online != null && ready(online)) {
            return stored.thenApply(n -> {
                if (n[0] > 0) {
                    server.execute(() -> check(online));
                }
                return new Result(handedNow, n[0], n[1], "waiting until they make room");
            });
        }
        if (online != null) {
            return stored.thenApply(n -> new Result(handedNow, n[0], n[1], "waiting until their data has arrived"));
        }
        if (serverId == null) {
            return stored.thenApply(n -> new Result(handedNow, n[0], n[1], "waiting for their next login"));
        }
        return stored.thenCompose(n -> store.presence(player).handle((p, e) -> {
            if (e != null) {
                return new Result(handedNow, n[0], n[1], "waiting for them (where they are could not be read)");
            }
            return p.isPresent() && p.get().online() && !p.get().serverId().equalsIgnoreCase(serverId)
                    ? new Result(handedNow, n[0], n[1], "on the way to " + p.get().serverId())
                    : new Result(handedNow, n[0], n[1], "waiting for their next login");
        }));
    }

    /** Stacks of at most the item's stack size. */
    static List<ItemStack> split(ItemStack item, int count) {
        List<ItemStack> out = new ArrayList<>();
        int max = Math.max(1, item.getMaxStackSize());
        for (int left = count; left > 0; left -= max) {
            out.add(item.copyWithCount(Math.min(max, left)));
        }
        return out;
    }

    /** Whether the player's own data is in place and they can take items: arrived, alive, connected. */
    static boolean ready(ServerPlayer p) {
        return !SyncService.isSyncing(p.getUUID()) && p.isAlive() && !p.hasDisconnected();
    }

    // ------------------------------------------------------------------ handing over

    /** Once a tick: every {@code give.pollSeconds}, looks up the waiting stacks of the players who can take them. */
    public void tick(long now) {
        if (pollInFlight || now < nextPollAt) {
            return;
        }
        nextPollAt = now + pollMs;
        List<ServerPlayer> players = server.getPlayerList().getPlayers().stream().filter(this::candidate).toList();
        if (players.isEmpty()) {
            return;
        }
        pollInFlight = true;
        pollFor(players).whenComplete((v, e) -> server.execute(() -> pollInFlight = false));
    }

    /** Hands over this player's waiting stacks now (an arrival, or a give while they are here). */
    public CompletableFuture<Void> check(ServerPlayer player) {
        return candidate(player) ? pollFor(List.of(player)) : CompletableFuture.completedFuture(null);
    }

    private boolean candidate(ServerPlayer p) {
        UUID id = p.getUUID();
        return ready(p) && !busy.contains(id) && !declined.contains(id) && !(p.containerMenu instanceof WaitingMenu);
    }

    private CompletableFuture<Void> pollFor(List<ServerPlayer> players) {
        List<ServerPlayer> unread = players.stream().filter(p -> !known.contains(p.getUUID())).toList();
        if (!unread.isEmpty()) {
            return readCancels(unread).thenCompose(v -> {
                List<ServerPlayer> next = players.stream().filter(p -> known.contains(p.getUUID()) && candidate(p)).toList();
                return next.isEmpty() ? CompletableFuture.completedFuture(null) : pollFor(next);
            });
        }
        List<UUID> ids = players.stream().map(ServerPlayer::getUUID).toList();
        busy.addAll(ids);
        CompletableFuture<Void> done = new CompletableFuture<>();
        store.gives(ids).whenComplete((rows, error) -> server.execute(() -> {
            if (error != null) {
                busy.removeAll(ids);
                BSPInventorySync.LOGGER.warn("BSP-InventorySync: could not read the waiting items: {}", error.toString());
                done.complete(null);
                return;
            }
            Map<UUID, List<Give>> byPlayer = new LinkedHashMap<>();
            for (Give g : rows) {
                byPlayer.computeIfAbsent(g.player(), k -> new ArrayList<>()).add(g);
            }
            List<CompletableFuture<Void>> each = new ArrayList<>();
            for (ServerPlayer p : players) {
                List<Give> mine = byPlayer.get(p.getUUID());
                if (mine == null || !ready(p) || declined.contains(p.getUUID())) {
                    busy.remove(p.getUUID());
                    continue;
                }
                each.add(handOver(p, mine).whenComplete((v, e) -> server.execute(() -> {
                    busy.remove(p.getUUID());
                    List<Give> left = v == null ? List.of() : v;
                    if (!left.isEmpty() && ready(p) && !declined.contains(p.getUUID()) && p.containerMenu == p.inventoryMenu) {
                        openPopup(p, left);
                    }
                })).thenApply(v -> null));
            }
            CompletableFuture.allOf(each.toArray(CompletableFuture[]::new)).whenComplete((v, e) -> done.complete(null));
        }));
        return done;
    }

    /**
     * Hands over every waiting stack that fits whole now, oldest first; returns the rows still waiting.
     * Each row is removed from the store before its stack goes in. Server thread.
     */
    public CompletableFuture<List<Give>> handOver(ServerPlayer p, List<Give> rows) {
        List<ItemStack> room = Room.of(p);
        List<Give> fit = new ArrayList<>(), left = new ArrayList<>();
        for (Give g : rows) {
            (Room.place(room, decode(g)) ? fit : left).add(g);
        }
        if (fit.isEmpty()) {
            return CompletableFuture.completedFuture(left);
        }
        List<CompletableFuture<Boolean>> takes = fit.stream().map(g -> store.takeGive(g.id())).toList();
        CompletableFuture<List<Give>> out = new CompletableFuture<>();
        CompletableFuture.allOf(takes.toArray(CompletableFuture[]::new)).whenComplete((v, e) -> server.execute(() -> {
            Map<String, Integer> received = new LinkedHashMap<>();
            Set<String> from = new java.util.LinkedHashSet<>();
            // checked again now the store has answered: a player who logged out, switched or died meanwhile has been saved
            // (or has dropped everything), so a stack put in now would be lost; it waits again instead
            boolean here = ready(p);
            try {
                for (int i = 0; i < fit.size(); i++) {
                    Give g = fit.get(i);
                    CompletableFuture<Boolean> take = takes.get(i);
                    if (take.isCompletedExceptionally() || !Boolean.TRUE.equals(take.join())) {
                        continue; // taken by another server, or the store failed (the row stays): not ours to hand over
                    }
                    if (!here) {
                        waitAgain(p, g.item(), g.by());
                        continue;
                    }
                    try {
                        ItemStack s = decode(g);
                        String name = s.getHoverName().getString();
                        int in = Room.insert(p.getInventory(), s);
                        if (in > 0) {
                            received.merge(name, in, Integer::sum);
                            from.add(g.by());
                        }
                        if (!s.isEmpty()) {
                            waitAgain(p, encode(s), g.by()); // the inventory filled meanwhile: the rest waits again
                        }
                    } catch (RuntimeException ex) {
                        BSPInventorySync.LOGGER.error("BSP-InventorySync: could not hand {} a waiting stack; it waits again", p.getGameProfile().getName(), ex);
                        waitAgain(p, g.item(), g.by());
                    }
                }
                if (!received.isEmpty()) {
                    p.sendSystemMessage(Component.literal("You received " + describe(received) + " from " + String.join(", ", from) + ".").withStyle(ChatFormatting.GREEN));
                    BSPInventorySync.LOGGER.info("BSP-InventorySync: handed {} {} from the waiting items", p.getGameProfile().getName(), describe(received));
                }
            } finally {
                out.complete(left); // always: the player is never left busy, and the popup's Deposit never stuck
            }
        }));
        return out;
    }

    /** Puts a stack taken from the store back as a new row; if even that fails, the log keeps the item so it can be given again. */
    private void waitAgain(ServerPlayer p, byte[] item, String by) {
        store.addGive(p.getUUID(), item, by).whenComplete((g, e) -> {
            if (e != null) {
                BSPInventorySync.LOGGER.error("BSP-InventorySync: a stack for {} from {} could not be stored again and is lost; give it again: {}",
                        p.getGameProfile().getName(), by, safeDescribe(item), e);
            }
        });
    }

    private static String safeDescribe(byte[] item) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(item))) {
            return NbtIo.read(in, NbtAccounter.UNLIMITED).toString();
        } catch (IOException | RuntimeException e) {
            return item.length + " bytes";
        }
    }

    private void openPopup(ServerPlayer p, List<Give> rows) {
        p.openMenu(new SimpleMenuProvider((id, inv, who) -> new WaitingMenu(id, p, this, rows), Component.literal("Items waiting for you")));
    }

    /** Reads the players' Cancel from the store; completes on the server thread. A failed read leaves the player unread, to try again. */
    private CompletableFuture<Void> readCancels(List<ServerPlayer> players) {
        List<UUID> ids = players.stream().map(ServerPlayer::getUUID).toList();
        busy.addAll(ids);
        List<CompletableFuture<java.util.Optional<byte[]>>> reads = ids.stream().map(id -> store.extra(DECLINED, id)).toList();
        CompletableFuture<Void> done = new CompletableFuture<>();
        CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).whenComplete((v, e) -> server.execute(() -> {
            try {
                for (int i = 0; i < ids.size(); i++) {
                    UUID id = ids.get(i);
                    busy.remove(id);
                    CompletableFuture<java.util.Optional<byte[]>> read = reads.get(i);
                    if (read.isCompletedExceptionally()) {
                        continue; // unread: tried again at the next poll, and nothing is handed over or asked meanwhile
                    }
                    known.add(id);
                    if (read.join().isPresent()) {
                        declined.add(id);
                    } else {
                        declined.remove(id);
                    }
                }
            } finally {
                done.complete(null);
            }
        }));
        return done;
    }

    /** Deposit in the popup: hands over what fits now and returns what still waits. */
    CompletableFuture<List<Give>> deposit(ServerPlayer p, List<Give> rows) {
        busy.add(p.getUUID());
        return handOver(p, rows).whenComplete((v, e) -> busy.remove(p.getUUID()));
    }

    /** Cancel or Esc in the popup: nothing more is asked or handed over until their next join. */
    void decline(ServerPlayer p, int stacks) {
        declined.add(p.getUUID());
        known.add(p.getUUID());
        store.putExtra(DECLINED, p.getUUID(), new byte[] {1}).whenComplete((v, e) -> {
            if (e != null) {
                BSPInventorySync.LOGGER.warn("BSP-InventorySync: could not record {}'s Cancel for the other servers: {}", p.getGameProfile().getName(), e.toString());
            }
        });
        p.sendSystemMessage(Component.literal(stacks + (stacks == 1 ? " stack is" : " stacks are") + " waiting for you; you will be asked again at your next login.").withStyle(ChatFormatting.GOLD));
    }

    // ------------------------------------------------------------------ arrivals and logouts

    /**
     * A player's data has arrived ({@code join}: they joined the network, not a switch through BSP-Teleport).
     * A join clears a Cancel; a switch keeps the one recorded on the server they came from. Then their
     * waiting stacks are handed over.
     */
    public CompletableFuture<Void> arrived(ServerPlayer player, boolean join) {
        UUID id = player.getUUID();
        busy.add(id);
        known.remove(id);
        CompletableFuture<Boolean> flag = join
                ? store.deleteExtra(DECLINED, id).handle((x, e) -> {
                    if (e != null) {
                        BSPInventorySync.LOGGER.warn("BSP-InventorySync: could not clear {}'s Cancel at their join: {}", player.getGameProfile().getName(), e.toString());
                    }
                    return false; // a join clears it here whatever the store says
                })
                : store.extra(DECLINED, id).thenApply(java.util.Optional::isPresent);
        CompletableFuture<Void> done = new CompletableFuture<>();
        flag.whenComplete((isDeclined, error) -> server.execute(() -> {
            busy.remove(id);
            if (error == null) {
                known.add(id);
                if (Boolean.TRUE.equals(isDeclined)) {
                    declined.add(id);
                } else {
                    declined.remove(id);
                }
            } // a failed read leaves them unread: the check below reads it again before handing anything over
            check(player).whenComplete((v, e) -> done.complete(null));
        }));
        return done;
    }

    public void loggedOut(UUID player) {
        declined.remove(player);
        known.remove(player);
        busy.remove(player);
    }

    /** Whether the player cancelled the popup and waits for their next join (tests, status). */
    public boolean declined(UUID player) {
        return declined.contains(player);
    }

    // ------------------------------------------------------------------ the rows' items

    public static byte[] encode(ItemStack stack) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes)) {
            NbtIo.write(ItemCodec.save(stack), out);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The stack a row holds; an item whose mod this server lacks comes back as the placeholder, as in a snapshot. */
    public static ItemStack decode(Give g) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(g.item()))) {
            CompoundTag tag = NbtIo.read(in, NbtAccounter.UNLIMITED);
            return ItemCodec.load(tag);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String describe(Map<String, Integer> counts) {
        List<String> parts = new ArrayList<>();
        counts.forEach((name, n) -> parts.add(n + " " + name));
        return String.join(", ", parts);
    }
}
