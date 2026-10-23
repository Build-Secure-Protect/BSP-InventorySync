# BSP-InventorySync API for other mods

Package `com.mrgregles.bsp_inventorysync.api`. Compile against the BSP-InventorySync jar from `build/libs/` (ForgeGradle: a `flatDir` repository and `compileOnly fg.deobf("blank:bsp-inventorysync:<version>")`); keep every call in one adapter class on your side and reach it only when `bsp_inventorysync` is loaded. These classes are the only ones meant for other mods; anything else in the jar may change without notice.

`InventorySyncApi.API_VERSION` is `1`. It is a compile-time constant, so catch `LinkageError` to detect an older BSP-InventorySync.

| Call | What |
|---|---|
| `InventorySyncApi.registerModule(DataModule)` | Adds your per-player data to every snapshot. Implement `DataModule`: `id()` (letters, digits, underscores), `save(ServerPlayer, CompoundTag out)`, `load(ServerPlayer, CompoundTag in)`. Both run on the server thread; a module that throws is logged and skipped while the rest still run. Register during common setup. |
| `InventorySyncApi.excludeItems(Predicate<ItemStack>)` | Items matching the rule are never written into a snapshot; their slot is saved empty. BSP-Core's live Shatter Totem rule is installed this way by BSP-InventorySync itself. |
| `InventorySyncApi.isSyncing(UUID)` | Whether the player's data is still arriving (between login and the restore, or the timeout fallback). The player is frozen while true: no movement, interaction, pickup, drop, block breaking or damage. Treat the inventory as not yet theirs. |
| `InventorySyncApi.isEnabled()` | Whether this server syncs at all (`storage.mode` is `mysql`). |
| `InventorySyncApi.beginTransfer(ServerPlayer, String targetServer)` | For a teleport mod about to send the player to another server: call it after BSP-Core's `BspTransfer.beginTransfer` and before the proxy switch. Takes a snapshot now, so the save is in flight when the player leaves; the logout saves once more, marked as a transfer. Returns false when sync is off here or the player's own data is still arriving. |
| `InventorySyncApi.cancelTransfer(ServerPlayer)` | The announced transfer did not happen; the next logout is an ordinary one. |

## Events

Package `com.mrgregles.bsp_inventorysync.api.event`, posted on `MinecraftForge.EVENT_BUS` on the server thread; notifications only, never cancelable.

| Event | When | Carries |
|---|---|---|
| `InventoryRestoredEvent` (a `PlayerEvent`) | once per restore on this server, after every data module has run and after any Shatter Totem the player held before the restore has been given back through BSP-Core. Also fires with version 0 when the player had no snapshot (a first login) and when the sync is off on this server, so listeners can always rely on exactly one per login. None fires for a login that ends before the restore (the player left, was refused at the timeout, or was refused because their data could not be read). | `getEntity()` the `ServerPlayer`; `getVersion()` the snapshot's per-player version (0 = none); `getSourceServer()` the saving server's id; `isFallback()` true when the arrival wait timed out and the last known snapshot was applied; `getFailedModules()`; `getTotemsKept()` |
| `InventorySavedEvent` | once the shared store confirms a snapshot was written; may be after the player left | `getPlayer()` uuid, `getPlayerName()`, `getCause()` (`SaveCause`: logout, transfer, death, autosave, world_save, admin), `getVersion()`, `getBytes()`, `getServerId()` |

**BSP-Core** (request R3, built in BSP-Core 1.4.1): with this mod loaded, a login holds the coin strip for an older season, the season check and owed items until `InventoryRestoredEvent`, because they touch the inventory and saved data the restore replaces; its start-up log says "Coins, the season check and owed items wait for BSP-InventorySync's restore event at login". If no event comes within its `storage.restoreWaitSeconds` (30), it settles by itself. A player who arrives dead gets their data, and the event, at the respawn, on the respawned entity; BSP-Core (request R4) never settles on a dead player, its countdown stands still on the death screen and restarts at the respawn. From BSP-Core 1.4.2 (request R5) a totem it holds for a player meanwhile is handed over through its totem route (ledger arrival, never dropped); use 1.4.2 or later with this mod, since on 1.4.1 such a totem can be dropped or spent. With BSP-Core 1.4.1 or later the totem rule and hand-back use its api (`BspTotems.isTotem(ItemStack)`, `BspTotems.giveBack(ServerPlayer, ItemStack)`, requests R1 and R2); older versions are reached through its internal methods.

## The arrival handshake

A server switch is a logout on the source and a login on the target within the same second. The shared store's presence row is the checkout: a server marks a player online on itself once it has applied their data, and offline only after its last save of them has landed. The target freezes the arriving player and polls the row every tick; the moment the source has released them it takes the row, reads the newest snapshot and applies it. If the source has not released them within `arrival.timeoutSeconds` (10), the last known snapshot is applied and the player told it may be out of date (`arrival.kickOnTimeout = false`), or the login is refused so they can try again (`true`). A player who leaves before the restore is never saved. A player who arrives dead gets their data at the respawn. The login listener runs at `EventPriority.LOWEST` so BSP-Teleport's landing has happened first; the logout listener at `LOWEST` so BSP-Core has taken the carried totems first.

## What BSP-InventorySync never syncs

A live Shatter Totem (`bsp_core:shatter_totem` without the `Spent` flag): BSP-Core places or stores it itself when the player leaves. A totem found on the player at restore time (handed back by BSP-Core a few ticks after login) is kept and put back through BSP-Core's own hand-back, never dropped. Statistics and the recipe book are not synced (design decision D3).

## Operator commands

`/invsync` (permission level 2): `status` (server id, store and Redis state, counters, modules, totem rule source, a store ping), `reload` (config re-read, stores reopened, online players adopted), `save <player>`, `resync <player>` (apply the newest snapshot again), `dump <player>` (per-module sizes; the full SNBT goes to `world/bsp_inventorysync/dumps/`), `snapshots <player>` (newest first: version, cause, server, age, size, pin), `restore <player> <version>` (a rollback, player online), `pin` / `unpin <player> <version>` (rotation never removes a pinned snapshot).

## What travels besides the vanilla data

The `forge` module carries the player's persistent NBT (`ForgeData`) and capabilities (`ForgeCaps`), so a mod's per-player data needs no adapter unless it lives outside the player (world saved data). Paths and capability ids listed in the server config (`sync.excludeForgeData`, `sync.excludeCapabilities`) are neither saved nor overwritten; the defaults exclude BSP-Core's server-local positions and the Curios inventory (carried by the Curios adapter instead). Adapters exist for Curios, Sophisticated Backpacks (contents by `contentsUuid`) and Dank Storage (contents by frequency, remapped on arrival from another server); each registers only when its mod is loaded. A mod whose per-player data lives in world saved data should register a `DataModule` of its own.

## Snapshot format

One GZIP-compressed NBT compound per snapshot: `{Format: 1, Modules: {<module id>: {...}}, Failed: [<module ids that threw on save>]}`. Module ids: `inventory`, `ender_chest`, `experience`, `game`, `vitals`, `advancements`, `forge`, and when loaded `curios`, `backpacks`, `dank`. An item whose id is not registered on the restoring server becomes a placeholder (a barrier named "Missing item: <id>"; paper before D14, still recognised) carrying the original compound in the tag `bsp_inventorysync:original`; it turns back into the item when saved again and restored where the item exists.
