package com.mrgregles.bsp_inventorysync.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.sync.Sync;
import com.mrgregles.bsp_inventorysync.sync.SyncService;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * {@code /invsync} for operators (permission level 2): {@code status}, {@code reload}, {@code save <player>},
 * {@code resync <player>}, {@code dump <player>}, {@code snapshots <player>}, {@code restore <player> <version>},
 * {@code pin <player> <version>}, {@code unpin <player> <version>}; and, for level 3, {@code edit <player> [word]}
 * (the inventory editor). Players are named as in vanilla
 * commands (names, selectors, known offline players). Replies come on the server thread when the
 * store answers.
 */
@Mod.EventBusSubscriber(modid = BSPInventorySync.MODID)
public final class InvSyncCommands {
    private InvSyncCommands() {}

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        register(event.getDispatcher(), event.getBuildContext());
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, net.minecraft.commands.CommandBuildContext context) {
        com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("invsync");
        GraveCommands.adminNodes().forEach(root::then);
        dispatcher.register(root
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("status").executes(InvSyncCommands::status))
                .then(Commands.literal("edit").requires(src -> src.hasPermission(3)).then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .executes(ctx -> edit(ctx, null))
                        .then(Commands.argument("search", StringArgumentType.greedyString()).executes(ctx -> edit(ctx, StringArgumentType.getString(ctx, "search"))))))
                .then(Commands.literal("give").then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .then(Commands.argument("item", net.minecraft.commands.arguments.item.ItemArgument.item(context))
                                .executes(ctx -> give(ctx, 1))
                                .then(Commands.argument("count", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                        .executes(ctx -> give(ctx, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "count")))))))
                .then(Commands.literal("reload").executes(InvSyncCommands::reload))
                .then(com.mrgregles.bsp_inventorysync.machine.MachineCommands.node())
                .then(Commands.literal("save").then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .executes(ctx -> withOnlinePlayer(ctx, AdminActions::save))))
                .then(Commands.literal("resync").then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .executes(ctx -> withOnlinePlayer(ctx, AdminActions::resync))))
                .then(Commands.literal("dump").then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .executes(ctx -> withOnlinePlayer(ctx, (s, p) -> CompletableFuture.completedFuture(AdminActions.dump(ctx.getSource().getServer(), p))))))
                .then(Commands.literal("snapshots").then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .executes(ctx -> withProfile(ctx, (s, profile) -> AdminActions.snapshots(s, profile.getId(), profile.getName(), System.currentTimeMillis())))))
                .then(Commands.literal("restore").then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .then(Commands.argument("version", LongArgumentType.longArg(1))
                                .executes(ctx -> withOnlinePlayer(ctx, (s, p) -> AdminActions.restore(s, ctx.getSource().getServer(), p, LongArgumentType.getLong(ctx, "version")))))))
                .then(Commands.literal("pin").then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .then(Commands.argument("version", LongArgumentType.longArg(1))
                                .executes(ctx -> withProfile(ctx, (s, profile) -> AdminActions.pin(s, profile.getId(), profile.getName(), LongArgumentType.getLong(ctx, "version"), true))))))
                .then(Commands.literal("unpin").then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .then(Commands.argument("version", LongArgumentType.longArg(1))
                                .executes(ctx -> withProfile(ctx, (s, profile) -> AdminActions.pin(s, profile.getId(), profile.getName(), LongArgumentType.getLong(ctx, "version"), false)))))));
    }

    private static void reply(CommandSourceStack source, List<String> lines, boolean ok) {
        for (String line : lines) {
            source.sendSystemMessage(Component.literal(line).withStyle(ok ? ChatFormatting.WHITE : ChatFormatting.RED));
        }
    }

    /** Sends the lines on the server thread when they come; a failure is one red line. */
    private static void replyLater(CommandSourceStack source, CompletableFuture<List<String>> lines) {
        MinecraftServer server = source.getServer();
        lines.whenComplete((result, error) -> server.execute(() -> {
            if (error != null) {
                reply(source, List.of("failed: " + error.getMessage()), false);
            } else {
                reply(source, result, true);
            }
        }));
    }

    private static int withService(CommandContext<CommandSourceStack> ctx, Function<SyncService, CompletableFuture<List<String>>> action) {
        SyncService s = Sync.active();
        if (s == null) {
            reply(ctx.getSource(), List.of("inventory sync is off on this server: storage.mode is not \"mysql\", or it could not start (see the server log)"), false);
            return 0;
        }
        replyLater(ctx.getSource(), action.apply(s));
        return 1;
    }

    private static int withProfile(CommandContext<CommandSourceStack> ctx, BiFunction<SyncService, GameProfile, CompletableFuture<List<String>>> action) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(ctx, "player");
        if (profiles.size() != 1) {
            reply(ctx.getSource(), List.of("name one player"), false);
            return 0;
        }
        GameProfile profile = profiles.iterator().next();
        return withService(ctx, s -> action.apply(s, profile));
    }

    private static int withOnlinePlayer(CommandContext<CommandSourceStack> ctx, BiFunction<SyncService, ServerPlayer, CompletableFuture<List<String>>> action) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return withProfile(ctx, (s, profile) -> {
            ServerPlayer player = ctx.getSource().getServer().getPlayerList().getPlayer(profile.getId());
            if (player == null) {
                return CompletableFuture.completedFuture(List.of(profile.getName() + " is not online here"));
            }
            if (SyncService.isSyncing(player.getUUID())) {
                return CompletableFuture.completedFuture(List.of(profile.getName() + "'s data is still arriving; try again in a moment"));
            }
            return action.apply(s, player);
        });
    }

    /**
     * {@code /invsync give <player> <item> [count]} (D23): on any server and from the console. The item as vanilla
     * {@code /give} takes it; at most 100 stacks' worth; Shatter Totems and items no snapshot carries are refused.
     * Where it went comes back as one line per player when the store has the rows.
     */
    private static int give(CommandContext<CommandSourceStack> ctx, int count) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(ctx, "player");
        ItemStack item = net.minecraft.commands.arguments.item.ItemArgument.getItem(ctx, "item").createItemStack(1, false);
        String itemName = item.getHoverName().getString();
        String refused = com.mrgregles.bsp_inventorysync.give.Gifts.refusal(item, count);
        if (refused != null) {
            reply(source, List.of(refused), false);
            return 0;
        }
        String by = source.getTextName();
        com.mrgregles.bsp_inventorysync.give.GiveService service = com.mrgregles.bsp_inventorysync.give.GiveService.get(source.getServer());
        for (GameProfile profile : profiles) {
            CompletableFuture<List<String>> line = service.give(profile.getId(), profile.getName(), item, count, by).thenApply(r -> {
                String what = count + " " + itemName;
                String text;
                if (r.waiting() == 0 && r.failed() == 0) {
                    text = "Gave " + what + " to " + profile.getName() + ".";
                } else {
                    List<String> parts = new java.util.ArrayList<>();
                    if (r.handed() > 0) {
                        parts.add(r.handed() + " went straight in");
                    }
                    if (r.waiting() > 0) {
                        parts.add(r.waiting() + " " + (r.waiting() == 1 ? "is " : "are ") + r.where());
                    }
                    if (r.failed() > 0) {
                        parts.add(r.failed() + " could not be stored (the database did not answer): give " + (r.failed() == 1 ? "it" : "those") + " again");
                    }
                    text = what + " for " + profile.getName() + ": " + String.join("; ", parts) + ".";
                }
                if (r.failed() > 0) {
                    BSPInventorySync.LOGGER.warn("BSP-InventorySync: {} gave {} to {}: {} handed over, {} waiting, {} NOT stored", by, what, profile.getName(), r.handed(), r.waiting(), r.failed());
                } else {
                    BSPInventorySync.LOGGER.info("BSP-InventorySync: {} gave {} to {}: {} handed over, {} {}", by, what, profile.getName(), r.handed(), r.waiting(), r.waiting() == 0 ? "waiting" : r.where());
                }
                return List.of(text);
            });
            replyLater(source, line);
        }
        return profiles.size();
    }

    /** {@code /invsync edit <player> [word]}: opens the editor for a player online on this server, or on the newest snapshot of one who is offline (operators of level 3). */
    private static int edit(CommandContext<CommandSourceStack> ctx, String word) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer admin = ctx.getSource().getPlayerOrException();
        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(ctx, "player");
        if (profiles.size() != 1) {
            reply(ctx.getSource(), List.of("name one player"), false);
            return 0;
        }
        GameProfile profile = profiles.iterator().next();
        ServerPlayer target = ctx.getSource().getServer().getPlayerList().getPlayer(profile.getId());
        if (target == null) {
            // not here: their newest snapshot, unless they are online on another server (edit them there)
            com.mrgregles.bsp_inventorysync.editor.EditorActions.openOffline(admin, profile.getId(), profile.getName(), word, Sync.active())
                    .thenAccept(refused -> ctx.getSource().getServer().execute(() -> {
                        if (refused != null) {
                            reply(ctx.getSource(), List.of(refused), false);
                        }
                    }));
            return 1;
        }
        String refused = com.mrgregles.bsp_inventorysync.editor.EditorActions.open(admin, target, word);
        if (refused != null) {
            reply(ctx.getSource(), List.of(refused), false);
            return 0;
        }
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        String result = Sync.reload(server);
        reply(ctx.getSource(), List.of(result, com.mrgregles.bsp_inventorysync.grave.GraveService.restart(server)), true);
        return 1;
    }

    /** The sync's state (when it is on) and the graves'. */
    private static int status(CommandContext<CommandSourceStack> ctx) {
        com.mrgregles.bsp_inventorysync.grave.GraveService g = com.mrgregles.bsp_inventorysync.grave.GraveService.active();
        reply(ctx.getSource(), List.of(g == null ? "graves are off on this server" : g.status()), true);
        if (Sync.active() == null) {
            reply(ctx.getSource(), List.of("inventory sync is off on this server: storage.mode is not \"mysql\", or it could not start (see the server log)"), false);
            return 0;
        }
        return withService(ctx, AdminActions::status);
    }
}
