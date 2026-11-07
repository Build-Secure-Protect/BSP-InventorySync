package com.mrgregles.bsp_inventorysync.grave;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.storage.Grave;
import com.mrgregles.bsp_inventorysync.storage.GraveStore;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * What {@code /grave} and the admin grave commands do (D25 7b, 14a), as plain methods returning lines, so the
 * GameTests drive them and the command tree stays a thin layer. Graves are named by their id (the first eight
 * characters are enough), never by a place in a list, so a grave made or emptied in between never shifts what a
 * command acts on. Whatever happens after the store answers happens on the server thread.
 *
 * <ul>
 * <li>{@code /grave}: the player's graves on every server, newest first, with [Recover] on those they may recover from
 * anywhere (one that found no spot, or whose block is gone, at once; any other after {@code graves.recoverAfterDays}).</li>
 * <li>{@code /grave recover <id>}: opens that grave's screen wherever the player is.</li>
 * <li>{@code /invsync graves <player>}: every grave of a player, put-away ones too, with their ids.</li>
 * <li>{@code /invsync grave open <player> <id>}: the grave screen for the admin, whatever its age.</li>
 * <li>{@code /invsync grave give <player> <id> <to>}: the grave becomes {@code to}'s (no block; they recover it with
 * {@code /grave} at once), in one write, so it can never be given twice or half given, contents and XP included.</li>
 * <li>{@code /invsync grave remove <player> <id>}: empties it for good (its block too), with a log line listing every stack.</li>
 * </ul>
 */
public final class GraveActions {
    private GraveActions() {}

    static String where(Grave g) {
        String server = g.serverId().isEmpty() ? "" : g.serverId() + ", ";
        return g.placed() ? server + GraveService.dimensionName(g.dimension()) + " " + g.x() + " " + g.y() + " " + g.z() : server + "could not be placed";
    }

    static String days(long ms) {
        long d = (ms + GraveService.DAY - 1) / GraveService.DAY;
        return d <= 1 ? "1 day" : d + " days";
    }

    /** {@code f}'s answer, then {@code then} on the server thread. */
    private static <T, R> CompletableFuture<R> onServer(GraveService s, CompletableFuture<T> f, Function<T, CompletableFuture<R>> then) {
        CompletableFuture<R> out = new CompletableFuture<>();
        s.access().onServer(f, (value, error) -> {
            if (error != null) {
                out.completeExceptionally(error);
                return;
            }
            try {
                then.apply(value).whenComplete((r, e) -> {
                    if (e != null) {
                        out.completeExceptionally(e);
                    } else {
                        out.complete(r);
                    }
                });
            } catch (RuntimeException e) {
                out.completeExceptionally(e);
            }
        });
        return out;
    }

    /** May the owner recover it from anywhere now: the time rule, or its block on this server is gone (a crash rolled the world back). */
    static boolean recoverable(GraveService s, Grave g, long now) {
        return s.recoverable(g, now) || (!g.archived() && s.access().blockMissing(g));
    }

    // ------------------------------------------------------------------ /grave

    /** The player's graves, put-away and emptied ones left out, newest first. */
    public static CompletableFuture<List<Component>> list(GraveService s, UUID owner, long now) {
        return onServer(s, s.graves(owner), all -> {
            List<Grave> mine = all.stream().filter(g -> !g.archived() && !g.empty()).toList();
            List<Component> out = new ArrayList<>();
            if (mine.isEmpty()) {
                out.add(Component.literal("You have no graves.").withStyle(ChatFormatting.GRAY));
                return CompletableFuture.completedFuture(out);
            }
            out.add(Component.literal("Your graves (" + mine.size() + ")").withStyle(ChatFormatting.YELLOW));
            for (int i = 0; i < mine.size(); i++) {
                Grave g = mine.get(i);
                MutableComponent line = Component.literal((i + 1) + ". " + where(g) + ", " + GraveMenu.ago(now - g.diedAt()) + ", "
                        + g.stacks() + (g.stacks() == 1 ? " stack" : " stacks") + " ");
                if (recoverable(s, g, now)) {
                    line.append(Component.literal("[Recover]").withStyle(st -> st.withColor(ChatFormatting.AQUA)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/grave recover " + g.shortId()))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Open it here")))));
                } else {
                    line.append(Component.literal("(from anywhere in " + days(s.recoverableIn(g, now)) + ")").withStyle(ChatFormatting.GRAY));
                }
                out.add(line);
            }
            return CompletableFuture.completedFuture(out);
        });
    }

    /** The grave of {@code owner} whose id starts with {@code id} (eight characters or more), emptied ones never. */
    static Optional<Grave> byId(List<Grave> all, String id) {
        String wanted = id.trim().toLowerCase();
        if (wanted.length() < 8) {
            return Optional.empty();
        }
        return all.stream().filter(g -> !g.empty() && g.id().toString().startsWith(wanted)).findFirst();
    }

    /**
     * Opens the player's grave named by its id where they are, if they may recover it from anywhere. Completes with null
     * when the screen opened (or the opening itself told them why not), else the reason.
     */
    public static CompletableFuture<String> recover(GraveService s, ServerPlayer p, String id, long now) {
        return onServer(s, s.graves(p.getUUID()), all -> {
            Optional<Grave> found = byId(all.stream().filter(g -> !g.archived()).toList(), id);
            if (found.isEmpty()) {
                return CompletableFuture.completedFuture("No grave of yours has that id: see /grave.");
            }
            Grave g = found.get();
            if (!recoverable(s, g, now)) {
                return CompletableFuture.completedFuture("Go to it: " + where(g) + ". It can be recovered from anywhere in " + days(s.recoverableIn(g, now)) + ".");
            }
            BSPInventorySync.LOGGER.info("BSP-InventorySync: {} recovers grave {} by command", p.getGameProfile().getName(), g.shortId());
            return s.access().open(p, g.id(), false, null, null).thenApply(menu -> (String) null);
        });
    }

    // ------------------------------------------------------------------ admins

    /** Every grave of the player, put-away ones too, newest first, with the ids the other admin commands take. */
    public static CompletableFuture<List<String>> adminList(GraveService s, UUID owner, String name, long now) {
        return onServer(s, s.graves(owner), all -> {
            List<Grave> live = all.stream().filter(g -> !g.empty()).toList();
            List<String> out = new ArrayList<>();
            if (live.isEmpty()) {
                out.add(name + " has no graves.");
                return CompletableFuture.completedFuture(out);
            }
            out.add(name + "'s graves (" + live.size() + "), by id:");
            for (Grave g : live) {
                out.add(g.shortId() + "  " + where(g) + ", died " + GraveMenu.ago(now - g.diedAt()) + ", " + g.stacks() + " stack(s)"
                        + (g.xp() > 0 ? ", " + g.xp() + " xp" : "") + (g.archived() ? ", put away" : recoverable(s, g, now) ? ", recoverable anywhere" : ""));
            }
            return CompletableFuture.completedFuture(out);
        });
    }

    private static String noSuch(String name, String id) {
        return name + " has no grave with the id " + id + ": see /invsync graves " + name + ".";
    }

    /** The grave screen for an admin (whatever its age, put away or not); null when it opened, else why not. */
    public static CompletableFuture<String> adminOpen(GraveService s, ServerPlayer admin, UUID owner, String name, String id) {
        return onServer(s, s.graves(owner), all -> {
            Optional<Grave> g = byId(all, id);
            if (g.isEmpty()) {
                return CompletableFuture.completedFuture(noSuch(name, id));
            }
            BSPInventorySync.LOGGER.info("BSP-InventorySync: {} opens {}'s grave {} as an admin", admin.getGameProfile().getName(), name, g.get().shortId());
            return s.access().open(admin, g.get().id(), true, null, null).thenApply(menu -> (String) null);
        });
    }

    /**
     * Gives the whole grave to {@code to}: it becomes theirs, with no block (they recover it with /grave at once,
     * wherever they are, contents, backpacks and XP included), in one write at its revision.
     */
    public static CompletableFuture<List<String>> adminGive(GraveService s, MinecraftServer server, UUID owner, String name, String id,
                                                            UUID to, String toName, String by) {
        return onServer(s, s.graves(owner), all -> {
            Optional<Grave> found = byId(all, id);
            if (found.isEmpty()) {
                return CompletableFuture.completedFuture(List.of(noSuch(name, id)));
            }
            Grave g = found.get();
            GraveStore store = s.storeFor(g.id());
            if (store == null) {
                return CompletableFuture.completedFuture(List.of("That grave is still being saved; try again in a moment."));
            }
            CompletableFuture<List<String>> done = new CompletableFuture<>();
            s.access().write(store, g.withOwner(to, toName, System.currentTimeMillis())).whenComplete((written, error) -> server.execute(() -> {
                if (written != GraveAccess.Written.YES) {
                    done.complete(List.of(written == GraveAccess.Written.UNKNOWN
                            ? "The database did not confirm the give; look with /invsync graves " + toName + " before trying again."
                            : "That grave changed meanwhile (someone took from it); look again with /invsync graves " + name + "."));
                    return;
                }
                s.access().removeBlockOf(g);
                BSPInventorySync.LOGGER.info("BSP-InventorySync: {} gave {}'s grave {} to {}: {}{}", by, name, g.shortId(), toName, GraveService.describe(g),
                        g.xp() > 0 ? " and " + g.xp() + " xp" : "");
                s.notify(to, "An admin gave you a grave: see /grave.");
                if (!to.equals(owner)) {
                    s.notify(owner, "An admin gave your grave to " + toName + ".");
                }
                done.complete(List.of("Gave " + name + "'s grave " + g.shortId() + " (" + g.stacks() + " stack(s)) to " + toName + ": it is theirs now, recovered with /grave."));
            }));
            return done;
        });
    }

    /** Empties the grave for good (its block too) with a log line listing every stack. */
    public static CompletableFuture<List<String>> adminRemove(GraveService s, MinecraftServer server, UUID owner, String name, String id, String by) {
        return onServer(s, s.graves(owner), all -> {
            Optional<Grave> found = byId(all, id);
            if (found.isEmpty()) {
                return CompletableFuture.completedFuture(List.of(noSuch(name, id)));
            }
            Grave g = found.get();
            GraveStore store = s.storeFor(g.id());
            if (store == null) {
                return CompletableFuture.completedFuture(List.of("That grave is still being saved; try again in a moment."));
            }
            Grave emptied = g.withContents(0, 0, new GraveContents(List.of(), new net.minecraft.nbt.CompoundTag()).encode(), System.currentTimeMillis());
            CompletableFuture<List<String>> done = new CompletableFuture<>();
            s.access().write(store, emptied).whenComplete((written, error) -> server.execute(() -> {
                if (written != GraveAccess.Written.YES) {
                    done.complete(List.of(written == GraveAccess.Written.UNKNOWN
                            ? "The database did not confirm the removal; look with /invsync graves " + name + "."
                            : "That grave changed meanwhile; look again with /invsync graves " + name + "."));
                    return;
                }
                s.access().removeBlockOf(g);
                BSPInventorySync.LOGGER.warn("BSP-InventorySync: {} removed {}'s grave {}: {}{}", by, name, g.shortId(), GraveService.describe(g), g.xp() > 0 ? " and " + g.xp() + " xp" : "");
                s.notify(owner, "An admin removed your grave" + (g.serverId().isEmpty() ? "" : " on " + g.serverId()) + ".");
                done.complete(List.of("Removed " + name + "'s grave " + g.shortId() + " (" + g.stacks() + " stack(s)); the log lists what it held."));
            }));
            return done;
        });
    }
}
