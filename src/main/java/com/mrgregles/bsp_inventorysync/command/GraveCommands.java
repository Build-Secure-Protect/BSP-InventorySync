package com.mrgregles.bsp_inventorysync.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.grave.GraveActions;
import com.mrgregles.bsp_inventorysync.grave.GraveService;
import com.mrgregles.bsp_inventorysync.storage.Grave;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
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

/**
 * {@code /grave} for every player (D25 14a): the list and {@code /grave recover <id>}; and the admin grave commands
 * under {@code /invsync} (level 2): {@code graves <player>}, {@code grave open|give|remove|return <player> <id> [to]}. The work is
 * in {@link GraveActions}; replies come on the server thread when the store answers.
 */
@Mod.EventBusSubscriber(modid = BSPInventorySync.MODID)
public final class GraveCommands {
    private GraveCommands() {}

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("grave")
                .executes(GraveCommands::list)
                .then(Commands.literal("recover").then(Commands.argument("id", StringArgumentType.word())
                        .suggests((ctx, builder) -> suggestOwn(ctx.getSource(), builder))
                        .executes(ctx -> recover(ctx, StringArgumentType.getString(ctx, "id"))))));
    }

    /** {@code graves <player>} and {@code grave open|give|remove|return}, joined into {@code /invsync} by {@link InvSyncCommands}. */
    static List<LiteralArgumentBuilder<CommandSourceStack>> adminNodes() {
        return List.of(
                Commands.literal("graves").then(Commands.argument("player", GameProfileArgument.gameProfile()).executes(GraveCommands::adminList)),
                Commands.literal("grave")
                        .then(Commands.literal("open").then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .then(Commands.argument("id", StringArgumentType.word()).executes(GraveCommands::adminOpen))))
                        .then(Commands.literal("give").then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .then(Commands.argument("to", GameProfileArgument.gameProfile()).executes(GraveCommands::adminGive)))))
                        .then(Commands.literal("remove").then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .then(Commands.argument("id", StringArgumentType.word()).executes(GraveCommands::adminRemove))))
                        .then(Commands.literal("return").then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .then(Commands.argument("id", StringArgumentType.word()).executes(GraveCommands::adminReturn)))));
    }

    private static GraveService service(CommandSourceStack source) {
        GraveService s = GraveService.active();
        if (s == null) {
            source.sendFailure(Component.literal("Graves are off on this server."));
        }
        return s;
    }

    private static void lines(CommandSourceStack source, CompletableFuture<List<String>> f) {
        MinecraftServer server = source.getServer();
        f.whenComplete((lines, error) -> server.execute(() -> {
            if (error != null) {
                source.sendFailure(Component.literal("Graves cannot be reached right now: " + error.getMessage()));
                return;
            }
            lines.forEach(l -> source.sendSystemMessage(Component.literal(l)));
        }));
    }

    private static void refusal(CommandSourceStack source, CompletableFuture<String> f) {
        MinecraftServer server = source.getServer();
        f.whenComplete((why, error) -> server.execute(() -> {
            if (error != null) {
                source.sendFailure(Component.literal("Graves cannot be reached right now: " + error.getMessage()));
            } else if (why != null) {
                source.sendSystemMessage(Component.literal(why).withStyle(ChatFormatting.GOLD));
            }
        }));
    }

    private static int list(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        GraveService s = service(ctx.getSource());
        if (s == null) {
            return 0;
        }
        MinecraftServer server = ctx.getSource().getServer();
        GraveActions.list(s, p.getUUID(), System.currentTimeMillis()).whenComplete((lines, error) -> server.execute(() -> {
            if (error != null) {
                ctx.getSource().sendFailure(Component.literal("Graves cannot be reached right now."));
            } else {
                lines.forEach(l -> ctx.getSource().sendSystemMessage(l));
            }
        }));
        return 1;
    }

    private static int recover(CommandContext<CommandSourceStack> ctx, String id) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        GraveService s = service(ctx.getSource());
        if (s == null) {
            return 0;
        }
        refusal(ctx.getSource(), GraveActions.recover(s, p, id, System.currentTimeMillis()));
        return 1;
    }

    private static CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestOwn(CommandSourceStack source, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        GraveService s = GraveService.active();
        if (s == null || !(source.getEntity() instanceof ServerPlayer p)) {
            return builder.buildFuture();
        }
        // answered when the store answers, never waited for on the server thread
        return s.graves(p.getUUID())
                .thenCompose(all -> SharedSuggestionProvider.suggest(all.stream().filter(g -> !g.archived() && !g.empty()).map(Grave::shortId).toList(), builder))
                .exceptionally(e -> builder.build());
    }

    private static GameProfile one(CommandContext<CommandSourceStack> ctx, String arg) throws CommandSyntaxException {
        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(ctx, arg);
        if (profiles.size() != 1) {
            throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(Component.literal("name one player")).create();
        }
        return profiles.iterator().next();
    }

    private static int adminList(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        GameProfile owner = one(ctx, "player");
        GraveService s = service(ctx.getSource());
        if (s == null) {
            return 0;
        }
        lines(ctx.getSource(), GraveActions.adminList(s, owner.getId(), owner.getName(), System.currentTimeMillis()));
        return 1;
    }

    /** {@code /invsync grave return <player> <id>} (D30): the grave back into the player's inventory, now or at their next login. */
    private static int adminReturn(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        GameProfile owner = one(ctx, "player");
        GraveService s = service(ctx.getSource());
        if (s == null) {
            return 0;
        }
        String by = ctx.getSource().getTextName();
        lines(ctx.getSource(), GraveActions.returnGrave(s, owner.getId(), owner.getName(), StringArgumentType.getString(ctx, "id"), by).thenApply(r -> List.of(r.message())));
        return 1;
    }

    private static int adminOpen(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer admin = ctx.getSource().getPlayerOrException();
        GameProfile owner = one(ctx, "player");
        GraveService s = service(ctx.getSource());
        if (s == null) {
            return 0;
        }
        refusal(ctx.getSource(), GraveActions.adminOpen(s, admin, owner.getId(), owner.getName(), StringArgumentType.getString(ctx, "id")));
        return 1;
    }

    private static int adminGive(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        GameProfile owner = one(ctx, "player"), to = one(ctx, "to");
        GraveService s = service(ctx.getSource());
        if (s == null) {
            return 0;
        }
        lines(ctx.getSource(), GraveActions.adminGive(s, ctx.getSource().getServer(), owner.getId(), owner.getName(), StringArgumentType.getString(ctx, "id"),
                to.getId(), to.getName(), ctx.getSource().getTextName()));
        return 1;
    }

    private static int adminRemove(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        GameProfile owner = one(ctx, "player");
        GraveService s = service(ctx.getSource());
        if (s == null) {
            return 0;
        }
        lines(ctx.getSource(), GraveActions.adminRemove(s, ctx.getSource().getServer(), owner.getId(), owner.getName(), StringArgumentType.getString(ctx, "id"), ctx.getSource().getTextName()));
        return 1;
    }
}
