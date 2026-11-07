package com.mrgregles.bsp_inventorysync;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * D25 21b: a player needs exactly the server's version of this mod (the grave block lives on both sides). Forge
 * compares the version of every network channel at login: a server with the mod refuses a client whose version differs
 * or that has no such channel (the mod missing), naming this mod. A client with the mod may still join a server
 * without it (a lobby, another modded server). The channel carries no messages.
 */
public final class VersionCheck {
    private static SimpleChannel channel;
    private static String version = "";

    private VersionCheck() {}

    public static void register(String modVersion) {
        version = modVersion;
        channel = NetworkRegistry.ChannelBuilder.named(new ResourceLocation(BSPInventorySync.MODID, "main"))
                .networkProtocolVersion(() -> modVersion)
                .clientAcceptedVersions(NetworkRegistry.acceptMissingOr(modVersion::equals)) // a client may still join servers without the mod
                .serverAcceptedVersions(modVersion::equals)
                .simpleChannel();
    }

    /** What a client accepts from a server (tests): this version, or a server without the mod. */
    public static boolean clientAccepts(String serverVersion) {
        return NetworkRegistry.acceptMissingOr(version::equals).test(serverVersion);
    }

    /** What a server accepts from a client (tests): exactly this version. */
    public static boolean serverAccepts(String clientVersion) {
        return version.equals(clientVersion);
    }

    /** The version both sides must have, for the tests and the log. */
    public static String version() {
        return version;
    }
}
