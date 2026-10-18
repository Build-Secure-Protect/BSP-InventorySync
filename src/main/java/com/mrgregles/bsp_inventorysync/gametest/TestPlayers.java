package com.mrgregles.bsp_inventorysync.gametest;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import java.util.UUID;

/**
 * Server players for tests (Region Guard's pattern). {@link GameTestHelper#makeMockServerPlayerInLevel()}
 * gives its connection no channel, and Forge's packet listener touches the channel in its constructor,
 * so this builds the player on an in-memory netty channel instead. Packets sent to the player go nowhere.
 * The player is not added to the level or the player list: the data modules never need that.
 */
final class TestPlayers {
    private static int counter;

    private TestPlayers() {}

    static ServerPlayer mockServerPlayer(GameTestHelper h) {
        return mockServerPlayer(h, UUID.randomUUID());
    }

    /** The same player again (a login on another server, or a respawn): same id, a new entity. */
    static ServerPlayer mockServerPlayer(GameTestHelper h, UUID uuid) {
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection); // registers and activates the handler, which gives the connection its channel
        String name = "is_test_" + (++counter);
        ServerPlayer player = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), new GameProfile(uuid, name));
        player.connection = new ServerGamePacketListenerImpl(h.getLevel().getServer(), connection, player);
        return player;
    }
}
