package com.mrgregles.bsp_inventorysync.machine;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.compat.CompactMachines;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import com.mrgregles.bsp_inventorysync.sync.SyncService;

import javax.annotation.Nullable;

/**
 * {@code /invsync machine [home <server> | clear]} (D31 9 a, level 2 like the rest of {@code /invsync}): shows, sets or
 * clears the home of the Compact Machine in the player's main hand, for a renamed server or a machine marked wrongly.
 * A cleared bound machine counts as this server's: the next save here marks it so, and placed here it opens this
 * server's room at its position, so clear a machine only on its own server.
 */
public final class MachineCommands {
    private MachineCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("machine")
                .executes(MachineCommands::show)
                .then(Commands.literal("home").then(Commands.argument("server", StringArgumentType.word()).executes(MachineCommands::setHome)))
                .then(Commands.literal("clear").executes(MachineCommands::clear));
    }

    /** The line describing a machine stack, or why it is not one. */
    public static String describe(ItemStack stack) {
        if (!CompactMachines.isMachine(stack)) {
            return "Hold a Compact Machine in your main hand.";
        }
        if (!CompactMachines.isBound(stack)) {
            return "This machine has no room yet: it works on any server.";
        }
        return CompactMachines.home(stack).map(h -> "This machine's room is on " + h + ".")
                .orElse("This machine has no home mark: it counts as this server's until a server saves it.");
    }

    /** Sets the home of the machine in hand; the reply line. */
    public static String setHome(ServerPlayer player, String server, String by) {
        return setHome(player, server, by, com.mrgregles.bsp_inventorysync.sync.Sync.active());
    }

    /**
     * As {@link #setHome(ServerPlayer, String, String)} checked against {@code sync}'s server list: an id that is neither this
     * server nor in {@code sync.networkServers} is still set (AUD-8 7 a), with a warning, since a typo makes the machine
     * refused everywhere until it is set again.
     */
    public static String setHome(ServerPlayer player, String server, String by, @Nullable SyncService sync) {
        ItemStack stack = player.getMainHandItem();
        if (!CompactMachines.isBound(stack)) {
            return CompactMachines.isMachine(stack) ? "This machine has no room yet: there is no home to set." : describe(stack);
        }
        String before = CompactMachines.home(stack).orElse("(none)");
        CompactMachines.setHome(stack, server);
        BSPInventorySync.LOGGER.info("BSP-InventorySync: {} set the home of a Compact Machine held by {} from {} to {}", by, player.getGameProfile().getName(), before, server);
        String reply = "This machine's room is now on " + server + " (was " + before + ").";
        if (sync == null) {
            return reply + " The sync is off here, so the id was not checked.";
        }
        if (!sync.isKnownServer(server)) {
            return reply + " Warning: " + server + " is not this server and not in sync.networkServers. If it is misspelt, the machine will be refused on every server until its home is set again.";
        }
        return reply;
    }

    /** Clears the home of the machine in hand; the reply line. */
    public static String clear(ServerPlayer player, String by) {
        ItemStack stack = player.getMainHandItem();
        if (!CompactMachines.isMachine(stack)) {
            return describe(stack);
        }
        String before = CompactMachines.home(stack).orElse("");
        if (!CompactMachines.clearHome(stack)) {
            return "This machine has no home mark to clear.";
        }
        BSPInventorySync.LOGGER.info("BSP-InventorySync: {} cleared the home ({}) of a Compact Machine held by {}", by, before, player.getGameProfile().getName());
        String here = MachineGuard.localServer();
        return "Home mark cleared (was " + before + "). It now counts as this server's" + (here.isEmpty() ? "" : " (" + here + ")")
                + ": the next save here marks it so, and placed here it opens this server's room at its position, not the one on " + before + ".";
    }

    private static int show(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        reply(ctx, describe(player.getMainHandItem()));
        return 1;
    }

    private static int setHome(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        reply(ctx, setHome(player, StringArgumentType.getString(ctx, "server"), ctx.getSource().getTextName()));
        return 1;
    }

    private static int clear(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        reply(ctx, clear(player, ctx.getSource().getTextName()));
        return 1;
    }

    private static void reply(CommandContext<CommandSourceStack> ctx, String line) {
        ctx.getSource().sendSystemMessage(Component.literal(line).withStyle(ChatFormatting.WHITE));
    }
}
