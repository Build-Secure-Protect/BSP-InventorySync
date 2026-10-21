# Changelog

All notable changes to BSP-InventorySync are recorded here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- Project skeleton: Forge 47.4.10 / Minecraft 1.20.1 Gradle build, mod id `bsp_inventorysync`, BSP-Core as a required dependency, a `@Mod` entry class that only logs.
- Storage layer: the `SyncStore` interface (snapshots per player with versions, causes, pinning and rotation; who is online where; extra blobs for data kept outside the player's NBT, such as backpack contents), `Snapshot`, `Presence`, `SaveCause`, and the in-memory `MemoryStore` used by the tests and by servers without a shared database.
- Forge GameTests (`./gradlew runGameTestServer`) for the storage rules, with the empty template written by `tools/gen_gametest_structures.py`.
- Data modules: `DataModule` and the `Modules` registry, `PlayerSnapshotCodec` (one compound per module, GZIP-compressed NBT per snapshot, every module isolated so one failure never costs the rest), `ItemRules` (items a snapshot never carries) and `ItemCodec` (an item whose mod is missing becomes a named sheet of paper holding the original NBT, and turns back into the item when saved again).
- Vanilla modules: inventory (main, armour, offhand, selected slot, cursor), ender chest, experience, game (game mode, abilities, respawn point), vitals (health, absorption, air, hunger, effects), advancements (through the player's advancements file, no rewards re-granted).
- GameTests for every module's round trip, the placeholder rule, item exclusions, module isolation and the GZIP bytes, through a mock server player.
- Totem rules: a live Shatter Totem is never written into a snapshot (`compat/BspCore` installs the rule, reaching BSP-Core's api when present and its internal check otherwise); a restore keeps every live totem the player holds and gives it back through BSP-Core afterwards, never dropping one.
- `SyncService`: save a player to the store and restore the newest snapshot, posting the api events.
- Public api (`docs/API.md`): `InventorySyncApi` (`registerModule`, `excludeItems`, `isSyncing`, `API_VERSION` 1), `DataModule`, `InventoryRestoredEvent`, `InventorySavedEvent`.
- GameTests for the totem rules and the events (17 in all).
- The arrival handshake: an arriving player is frozen (no movement, interaction, pickup, drop, block breaking or damage) while the server waits for the one they came from to finish saving, then gets the newest snapshot; a configurable timeout applies the last known snapshot with a warning or refuses the login. Players who leave mid-sync are never saved; dead arrivals are restored at the respawn; deaths save at the respawn; a world save saves every settled player. Server start and stop release the players this server held.
- `beginTransfer` / `cancelTransfer` in the api for BSP-Teleport (an early snapshot before the proxy switch).
- Server config `bsp_inventorysync-server.toml`: `storage.mode` (`off` by default), `storage.serverId`, `arrival.timeoutSeconds`, `arrival.kickOnTimeout`.
- GameTests for the handshake and its races, with two services sharing one store (24 in all).
- The MySQL store (`JdbcStore`): plain JDBC on one worker thread, no bundled driver (BSP-Core's full jar or Mohist provides one), tables `players`, `snapshots` and `extra` under the `bsp_is_` prefix, created on start. Database settings taken from BSP-Core's own `storage.*` config by default.
- The Redis link (`RedisClient`, this mod's own small client, nothing bundled) and `FastStore`: the checkout and the newest snapshot of every player live in Redis too, so a server switch never waits on MySQL; MySQL stays the source of truth and the safety net when Redis is down.
- Config: `storage.useBspCoreDatabase`, `storage.host/port/database/user/password/useSsl`, `storage.tablePrefix`, `storage.snapshotsKept` (16); `redis.enabled/host/port/username/password/cluster/snapshotTtlSeconds`. The server id falls back to BSP-Core's `storage.serverId`.
- Snapshots are pruned after every save to the configured count; pinned ones are kept.
- GameTests for the Redis client (against a fake Redis on a local socket, with an outage), the fast store's cache and fallback, the handshake through the fast store, and the JDBC store against nothing listening (28 in all).
- The generic Forge data module: every mod's per-player persistent NBT (`ForgeData`) and capabilities (`ForgeCaps`) travel with the player, with exclusion lists in the config (`sync.excludeForgeData`, `sync.excludeCapabilities`); excluded paths keep this server's values.
- Adapters, each only when its mod is loaded and none compiled against: Curios (worn curios through Curios' own tag), Sophisticated Backpacks (contents by `contentsUuid`, including backpacks inside backpacks, written into this server's storage), Dank Storage (contents by frequency; a dank from another server gets a fresh frequency here).
- GameTests for the generic module (persistent NBT with exclusions, capabilities through Forge with a test capability), the backpack UUID scan and the restore context (32 in all).
- Auto-save: every `storage.autosaveSeconds` (60) each settled player is captured and written only if anything changed since their last save; unchanged players cost one capture and no database call. The advancements file is reused until a criterion changes.
- The watchdog: any database or Redis call that takes longer than `storage.timeoutSeconds` (5) is given up as failed, so a frozen database never leaves a player frozen, a save hanging or the shutdown waiting.
- GameTests for the auto-save, the advancements dirty flag and the watchdog against a store that never answers (35 in all).
- `/invsync` for operators: `status`, `reload` (re-reads the config, reopens the stores, adopts the players online), `save <player>`, `resync <player>`, `dump <player>` (per-module sizes and the full SNBT written under `world/bsp_inventorysync/dumps/`), `snapshots <player>`, `restore <player> <version>`, `pin` and `unpin <player> <version>`.
- GameTests for the actions and the command wiring (40 in all).
- The start-up log line and `/invsync status` say where the database settings came from (BSP-Core's config or this mod's).

### Changed
- Compiled against BSP-Core 1.4.1 (`bsp_core_version`); any BSP-Core from 1.1.0 on is still accepted.
- With BSP-Core 1.4.1 or later the totem rule and the totem hand-back use BSP-Core's api (`BspTotems.isTotem(ItemStack)`, `BspTotems.giveBack(ServerPlayer, ItemStack)`); the start-up log says "totem rule via api, hand-back via api". Older BSP-Core versions keep using its internal methods as before. A restore into a full inventory keeps every totem the player holds; BSP-Core drops the stack each one displaces at the player's feet (GameTest `a_restore_into_a_full_inventory_keeps_every_totem_and_drops_what_they_displace`).
- `InventoryRestoredEvent` now also fires once, with version 0, when a player logs in on a server where the sync is off (or its store failed to start). BSP-Core 1.4.1 holds coins from an older season, the season check and owed items until it sees this event (request R3), so without it every login on such a server waited `storage.restoreWaitSeconds` (30 s). GameTest `bsp_core_settles_a_login_on_the_one_restore_event_with_the_sync_on_or_off` checks BSP-Core's wait ends on the event with the sync off and on.
- A player who arrives dead gets their restore event after the respawn, on the living player; with BSP-Core 1.4.1 (request R4) BSP-Core keeps waiting through the death screen and hands owed items and totems to the respawned player. GameTest `a_dead_arrival_past_bsp_cores_wait_gets_its_data_and_owed_totem_after_the_respawn`.

### Fixed
- An item tossed while the player's data is still arriving (Q, or thrown from the cursor) is no longer deleted. Forge 47.4.10 had already taken the stack out of the inventory and spawns nothing for a cancelled toss, so `sync/Freeze` now puts the stack back into the inventory and cancels only when all of it fit; what does not fit drops on the ground as a normal toss (request SU-IS1 from BSP-Server Utilities). GameTest `a_frozen_player_who_tosses_an_item_gets_it_back_and_a_full_inventory_drops_the_rest`.
