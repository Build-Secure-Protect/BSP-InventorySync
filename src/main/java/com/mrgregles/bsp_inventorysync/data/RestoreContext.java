package com.mrgregles.bsp_inventorysync.data;

import javax.annotation.Nullable;

/**
 * What a module may ask while a snapshot is being applied (server thread): which server took it and
 * which server this is. A module that keys data by server-local ids (Dank Storage's frequencies)
 * remaps only when the two differ. Empty outside a restore.
 */
public record RestoreContext(String sourceServer, String localServer) {
    private static final ThreadLocal<RestoreContext> CURRENT = new ThreadLocal<>();

    public boolean fromAnotherServer() {
        return !sourceServer.isEmpty() && !sourceServer.equals(localServer);
    }

    @Nullable
    public static RestoreContext current() {
        return CURRENT.get();
    }

    static void set(@Nullable RestoreContext ctx) {
        if (ctx == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(ctx);
        }
    }
}
