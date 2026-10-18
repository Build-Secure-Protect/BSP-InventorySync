package com.mrgregles.bsp_inventorysync.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
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
 * {@code pin <player> <version>}, {@code unpin <player> <version>}. Players are named as in vanilla
 * commands (names, selectors, known offline players). Replies come on the server thread when the
 * store answers.
 */
@Mod.EventBusSubscriber(modid = BSPInventorySync.MODID)
public final class InvSyncCommands {
    private InvSyncCommands() {}

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("invsync")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("status").executes(ctx -> withService(ctx, AdminActions::status)))
                .then(Commands.literal("reload").executes(InvSyncCommands::reload))
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
            reply(ctx.getSource(), List.of("inventory sync is off on this server (storage.mode is not mysql)"), false);
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
            return action.apply(s, player);
        });
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        String result = Sync.reload(server);
        reply(ctx.getSource(), List.of(result), true);
        return 1;
    }
}
