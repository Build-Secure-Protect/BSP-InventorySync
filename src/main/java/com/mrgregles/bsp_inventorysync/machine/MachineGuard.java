package com.mrgregles.bsp_inventorysync.machine;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.compat.CompactMachines;
import com.mrgregles.bsp_inventorysync.sync.Sync;
import com.mrgregles.bsp_inventorysync.sync.SyncService;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A bound Compact Machine works only on its home server (D31): placing one marked for another server is refused, the
 * item stays where it was and the player reads one action-bar line naming the home server (6 a). Unbound and unmarked
 * machines are untouched; with the sync off (single player, no server id) nothing is refused.
 *
 * <p>Two places refuse it, both before Compact Machines' {@code setPlacedBy} binds a block to a room:
 * <ul>
 * <li>{@link #refusesPlacement}, called by {@code mixin/BlockItemMixin} at the start of {@code BlockItem.place}, which
 * every placement of a block item goes through, whoever places it: a player's click, Dank Storage's construction mode
 * (it uses the selected item directly, so no event sees the machine), a Create deployer, any other mod (AUD-8 1 a);</li>
 * <li>the right click with a machine in hand ({@link #onUseOnBlock}, at the lowest priority so no other listener can
 * allow it again after, AUD-8 2 a), which denies the item's use before anything else happens; the clicked block still
 * works (a chest opens).</li>
 * </ul>
 * The player is told at most once a second (AUD-8 5 a); the hand's slot is sent again at every refused click, so the
 * client's own prediction (it placed the block and took the item) is corrected; vanilla corrects the block itself.
 *
 * <p>Also marks a bound machine that appears here as a dropped item without a mark (the machine block's own drop, a
 * player's throw) with this server, where Compact Machines is installed (5 a).
 */
@Mod.EventBusSubscriber(modid = BSPInventorySync.MODID)
public final class MachineGuard {
    /** GameTests only: the server id to act as, while a test runs its events in one go. */
    @Nullable
    private static volatile String localForTests;

    /** The server tick each player was last told, so a held right click does not flood the action bar. */
    private static final Map<UUID, Integer> LAST_TOLD = new ConcurrentHashMap<>();
    private static final int TELL_EVERY_TICKS = 20;

    private MachineGuard() {}

    /** The line shown when a machine is refused (D31 6 a, 7 a: the server named by its id). */
    public static String refusal(String home) {
        return "This Compact Machine's room is on " + home + ". It only works there.";
    }

    /** This server's id while the sync runs, else empty (nothing is away from home then). */
    public static String localServer() {
        String forTests = localForTests;
        if (forTests != null) {
            return forTests;
        }
        SyncService s = Sync.active();
        return s == null ? "" : s.serverId();
    }

    /** GameTests only: act as server {@code id} ({@code null} to stop). Set and cleared within one test step. */
    public static void actAsServerForTests(@Nullable String id) {
        localForTests = id;
    }

    /**
     * Whether this placement must not happen: a Compact Machine whose room is on another server. Called at the start of
     * {@code BlockItem.place} (server and client; only the server decides). Tells the player, or logs a placer that is
     * not one.
     */
    public static boolean refusesPlacement(BlockPlaceContext context) {
        if (context.getLevel().isClientSide()) {
            return false;
        }
        ItemStack stack = context.getItemInHand();
        if (!CompactMachines.awayFromHome(stack, localServer())) {
            return false;
        }
        String home = CompactMachines.home(stack).orElse("");
        Player placer = context.getPlayer();
        if (placer instanceof ServerPlayer player && !(placer instanceof FakePlayer)) {
            tell(player, home);
            player.containerMenu.sendAllDataToRemote(); // the stack may sit anywhere (a dank's selected slot): send them all
        } else if (shouldTell(placer == null ? null : placer.getUUID(), context.getLevel().getServer() == null ? 0 : context.getLevel().getServer().getTickCount())) {
            BSPInventorySync.LOGGER.info("BSP-InventorySync: refused to place a Compact Machine whose room is on {} at {} (placed by {})",
                    home, context.getClickedPos(), placer == null ? "no player" : placer.getGameProfile().getName());
        }
        return true;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onUseOnBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ItemStack stack = event.getItemStack();
        if (CompactMachines.awayFromHome(stack, localServer())) {
            event.setUseItem(Event.Result.DENY); // the clicked block still works (a chest opens); only the machine is not used
            resendHand(player, event.getHand());
            if (!(player instanceof FakePlayer)) {
                tell(player, CompactMachines.home(stack).orElse(""));
            }
        }
    }

    /** Marks an unmarked bound machine (also inside a shulker box or bundle) that appears here as a dropped item. */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        Entity entity = event.getEntity();
        if (event.getLevel().isClientSide() || !(entity instanceof ItemEntity item)) {
            return;
        }
        ItemStack stack = item.getItem();
        if (stack.hasTag()) {
            CompactMachines.mark(stack, CompactMachines.localMark(localServer()));
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_TOLD.remove(event.getEntity().getUUID());
    }

    private static void tell(ServerPlayer player, String home) {
        if (shouldTell(player.getUUID(), player.server.getTickCount())) {
            player.displayClientMessage(Component.literal(refusal(home)).withStyle(ChatFormatting.GOLD), true);
        }
    }

    /** True at most once every {@value #TELL_EVERY_TICKS} ticks per player (or per non-player placer, keyed by null as one). */
    private static boolean shouldTell(@Nullable UUID who, int tick) {
        UUID key = who == null ? new UUID(0, 0) : who;
        Integer last = LAST_TOLD.get(key);
        if (last != null && tick - last < TELL_EVERY_TICKS && tick >= last) {
            return false;
        }
        LAST_TOLD.put(key, tick);
        return true;
    }

    /** GameTests only: forget when each player was last told. */
    public static void forgetToldForTests() {
        LAST_TOLD.clear();
    }

    /** Sends the hand's slot again: the client took the item for the block it predicted. */
    private static void resendHand(ServerPlayer player, InteractionHand hand) {
        int slot = hand == InteractionHand.MAIN_HAND ? player.getInventory().selected : Inventory.SLOT_OFFHAND;
        player.connection.send(new ClientboundContainerSetSlotPacket(ClientboundContainerSetSlotPacket.PLAYER_INVENTORY, 0, slot, player.getItemInHand(hand).copy()));
    }
}
