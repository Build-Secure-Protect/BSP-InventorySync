package com.mrgregles.bsp_inventorysync.grave;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * What a grave block knows about its grave: the grave's id (the contents stay in the grave store, never in the
 * world), its owner, and when it becomes public. Sent to the clients for the floating text, with the server's
 * clock, so the countdown is right even when a player's own clock is off; and the owner's game profile as it was at
 * the death, with its skin, for the head in front of the stone (D26 2a, 5a).
 */
public final class GraveBlockEntity extends BlockEntity {
    private static final String GRAVE = "Grave", OWNER = "Owner", OWNER_NAME = "OwnerName", DIED_AT = "DiedAt", PUBLIC_AT = "PublicAt", SERVER_NOW = "ServerNow",
            PROFILE = "OwnerProfile", TEXTURES = "textures";

    @Nullable private UUID graveId;
    private UUID owner = new UUID(0, 0);
    private String ownerName = "";
    private long diedAt, publicAt;
    /** Its grave was written to the network database (a server not connected to it must not take the block for an empty grave). */
    private boolean networked;
    /** Client side: the server's clock minus this client's, taken when the data arrived. */
    private long clockOffset;
    /** The owner's profile at the death, skin and all; null for a grave from before 0.6.0 (or one put up without it). */
    @Nullable private GameProfile profile;
    /** Its stand-in when there is none: the owner's id and name, so the default skin for that id. */
    @Nullable private GameProfile plain;

    public GraveBlockEntity(BlockPos pos, BlockState state) {
        super(GraveRegistry.GRAVE_ENTITY.get(), pos, state);
    }

    public void setGrave(UUID graveId, UUID owner, String ownerName, long diedAt, long publicAt) {
        setGrave(graveId, owner, ownerName, diedAt, publicAt, false);
    }

    /** As below with no profile: the head shows the default skin. It replaces any profile the block had. */
    public void setGrave(UUID graveId, UUID owner, String ownerName, long diedAt, long publicAt, boolean networked) {
        setGrave(graveId, owner, ownerName, diedAt, publicAt, networked, null);
    }

    public void setGrave(UUID graveId, UUID owner, String ownerName, long diedAt, long publicAt, boolean networked, @Nullable GameProfile profile) {
        this.profile = profile == null ? null : copy(profile);
        this.plain = null;
        this.networked = networked;
        this.graveId = graveId;
        this.owner = owner;
        this.ownerName = ownerName;
        this.diedAt = diedAt;
        this.publicAt = publicAt;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Nullable
    public UUID graveId() {
        return graveId;
    }

    public UUID owner() {
        return owner;
    }

    public String ownerName() {
        return ownerName;
    }

    public long diedAt() {
        return diedAt;
    }

    public long publicAt() {
        return publicAt;
    }

    public boolean networked() {
        return networked;
    }

    /** The owner's profile as it was at the death (with the skin), or null when the grave has none. */
    @Nullable
    public GameProfile profile() {
        return profile;
    }

    /** The profile the head is drawn with: the saved one, else the owner's id and name (the default skin for that id). */
    public GameProfile headProfile() {
        if (profile != null) {
            return profile;
        }
        String name = ownerName.isEmpty() ? "?" : ownerName;
        if (plain == null || !plain.getId().equals(owner) || !plain.getName().equals(name)) {
            plain = new GameProfile(owner, name);
        }
        return plain;
    }

    /**
     * A copy of a profile with only its skin (the {@code textures} property, signature and all): the player's own
     * profile object stays theirs, and nothing else a proxy may have put on it goes into the world or to the clients.
     */
    private static GameProfile copy(GameProfile from) {
        GameProfile to = new GameProfile(from.getId(), from.getName());
        to.getProperties().putAll(TEXTURES, from.getProperties().get(TEXTURES));
        return to;
    }

    /**
     * The floating text sits above the block (a long name is over two blocks wide) and the owner's head reaches into
     * the ground below: drawn whenever any of that is in view, not only the headstone.
     */
    @Override
    public net.minecraft.world.phys.AABB getRenderBoundingBox() {
        return new net.minecraft.world.phys.AABB(worldPosition.getX() - 1, worldPosition.getY() - 0.25, worldPosition.getZ() - 1,
                worldPosition.getX() + 2, worldPosition.getY() + 2.5, worldPosition.getZ() + 2);
    }

    /** The server's time now, as best this side knows it (the client corrects its own clock by the offset it was sent). */
    public long serverNow(long localNow) {
        return localNow + clockOffset;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        writeGrave(tag);
    }

    private void writeGrave(CompoundTag tag) {
        if (graveId != null) {
            tag.putUUID(GRAVE, graveId);
        }
        tag.putUUID(OWNER, owner);
        tag.putString(OWNER_NAME, ownerName);
        tag.putLong(DIED_AT, diedAt);
        tag.putLong(PUBLIC_AT, publicAt);
        tag.putBoolean("Networked", networked);
        if (profile != null) {
            tag.put(PROFILE, NbtUtils.writeGameProfile(new CompoundTag(), profile));
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        graveId = tag.hasUUID(GRAVE) ? tag.getUUID(GRAVE) : null;
        owner = tag.hasUUID(OWNER) ? tag.getUUID(OWNER) : new UUID(0, 0);
        ownerName = tag.getString(OWNER_NAME);
        diedAt = tag.getLong(DIED_AT);
        publicAt = tag.getLong(PUBLIC_AT);
        networked = tag.getBoolean("Networked");
        GameProfile read = tag.contains(PROFILE, net.minecraft.nbt.Tag.TAG_COMPOUND) ? NbtUtils.readGameProfile(tag.getCompound(PROFILE)) : null;
        profile = read == null ? null : copy(read);
        plain = null;
        if (tag.contains(SERVER_NOW)) {
            clockOffset = tag.getLong(SERVER_NOW) - System.currentTimeMillis();
        }
    }

    /** What the clients get: the grave's owner, times and profile (never its contents) and the server's clock. */
    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();
        writeGrave(tag);
        tag.putLong(SERVER_NOW, System.currentTimeMillis());
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
