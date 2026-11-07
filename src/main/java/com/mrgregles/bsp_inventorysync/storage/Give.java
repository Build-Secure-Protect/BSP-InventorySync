package com.mrgregles.bsp_inventorysync.storage;

import java.util.UUID;

/**
 * One stack waiting for a player ({@code /invsync give}, D23): the item as {@code ItemCodec} wrote it, who
 * gave it and when. It stays in the store until a server hands it over; {@link SyncStore#takeGive} removes
 * it first, so it is handed over once.
 */
public record Give(long id, UUID player, byte[] item, String by, long createdAt) {}
