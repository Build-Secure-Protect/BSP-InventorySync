package com.mrgregles.bsp_inventorysync.grave;

import net.minecraft.util.StringRepresentable;

import java.util.UUID;

/**
 * Which way the owner's head leans in the dirt in front of the stone (D26 2a, D27 2a and 3a): 18 degrees to the left
 * or to the right as seen by someone standing in front of the grave, chosen from the grave's id so a row of graves
 * does not look stamped out, and the same every time for the same grave.
 */
public enum GraveLean implements StringRepresentable {
    LEFT("left", -1),
    RIGHT("right", 1);

    /** How far the head leans. */
    public static final float DEGREES = 18F;

    private final String name;
    private final int sign;

    GraveLean(String name, int sign) {
        this.name = name;
        this.sign = sign;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    /**
     * The turn about the grave's front-to-back axis (+z for a grave facing north, right-handed), in degrees: left is
     * negative, which tips the top of the head towards +x, the left of someone looking at the front.
     */
    public float degrees() {
        return sign * DEGREES;
    }

    /** The lean of a grave: from its id, so the same grave always leans the same way. */
    public static GraveLean of(UUID graveId) {
        return (graveId.getLeastSignificantBits() & 1) == 0 ? LEFT : RIGHT;
    }
}
