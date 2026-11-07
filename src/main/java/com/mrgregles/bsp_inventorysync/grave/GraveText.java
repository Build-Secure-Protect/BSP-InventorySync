package com.mrgregles.bsp_inventorysync.grave;

import java.util.UUID;

/**
 * The two lines floating over a grave (D25 19a), worked out on each player's own screen: the owner's name, then
 * "Right-click to open" for whoever may open it now (its owner always, anyone once it is public), or the time
 * until it is public for everyone else. Plain logic, so the server's tests check it; the client's renderer draws it.
 */
public final class GraveText {
    /** What a viewer sees: the name, the status line, and whether it is the "open" line (drawn gold) or the wait (grey). */
    public record Lines(String name, String status, boolean mayOpen) {}

    private GraveText() {}

    public static Lines lines(UUID viewer, UUID owner, String ownerName, long publicAt, long now) {
        boolean mayOpen = viewer.equals(owner) || now >= publicAt;
        return new Lines(ownerName, mayOpen ? "Right-click to open" : "Public in " + clock(publicAt - now), mayOpen);
    }

    /** 58:12, or 1:02:09 from an hour up. */
    public static String clock(long ms) {
        long s = Math.max(0, (ms + 999) / 1000);
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, sec) : String.format("%02d:%02d", m, sec);
    }
}
