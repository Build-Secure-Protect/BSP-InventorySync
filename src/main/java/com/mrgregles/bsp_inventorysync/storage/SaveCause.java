package com.mrgregles.bsp_inventorysync.storage;

/** Why a snapshot was taken. Stored with the snapshot so an operator can tell a death save from an auto-save. */
public enum SaveCause {
    /** The player logged out (or the server stopped). */
    LOGOUT,
    /** A server transfer was announced before the logout (BSP-Teleport, request T1). */
    TRANSFER,
    /** The player died. */
    DEATH,
    /** The periodic save of a player whose data changed. */
    AUTOSAVE,
    /** The world was saved. */
    WORLD_SAVE,
    /** An operator asked for it. */
    ADMIN;

    /** The stored short name; stable, lower case. */
    public String id() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /** The cause for a stored name; {@code ADMIN} for anything unknown, so an old row never fails to load. */
    public static SaveCause byId(String id) {
        for (SaveCause c : values()) {
            if (c.id().equals(id)) {
                return c;
            }
        }
        return ADMIN;
    }
}
