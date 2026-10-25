# In-game testing checklist

One dated section per task, newest last. Claude cannot run the Minecraft client; the owner runs these checks and reports back. The real test for this mod is two servers sharing a database.

## 2026-10-17: skeleton

- [ ] The server starts with `bsp-inventorysync-0.1.0.jar` and BSP-Core 1.1.0 installed and logs "BSP-InventorySync loaded (skeleton; nothing is synced yet)".

## 2026-10-17: IS-1 storage layer

Nothing to check in game: the storage rules are covered by the GameTests (`./gradlew runGameTestServer`, result line "All N required tests passed" in `run/logs/latest.log`). The two-server run comes with IS-9.

## 2026-10-17: IS-2 vanilla data modules

Covered headlessly by 8 GameTests (13 in all). Not yet checkable in game: nothing is wired to logins, so no player's data moves yet. What the headless tests cannot show, for the first in-game pass once IS-4 wires logins:

- [ ] After a restore the client shows the right hotbar selection, cursor item, flight state, XP bar and effects without relogging.
- [ ] A placeholder (sheet of paper named "Missing item: <id>") appears for an item whose mod the server lacks, and turns back into the item on a server that has the mod.
- [ ] Advancements restored from a snapshot show in the client's advancement screen and grant no XP or items again.

## 2026-10-17: IS-3 totem rules

Covered headlessly by 4 GameTests against BSP-Core 1.1.0's real Shatter Totem item (17 in all). Not yet checkable in game (no login flow). For the first in-game pass once IS-4 wires logins, on two servers:

- [ ] Carry a totem and switch servers through BSP-Teleport: exactly one totem arrives (handed back by BSP-Core), the rest of the inventory arrives from the sync, nothing is duplicated and nothing is dropped.
- [ ] Log out normally while carrying a totem: BSP-Core places it; the synced inventory on the next login has no totem.
- [ ] Switch servers with a full inventory and a totem: the totem is still in the inventory after arrival (BSP-Core displaces a non-totem item if it must).

## 2026-10-17: IS-4 arrival handshake

Covered headlessly by 7 GameTests with two services sharing one in-memory store (24 in all). Until the MySQL store exists (IS-5) nothing is shared between real servers, so the two-server checks wait. What can be checked now on one server with `storage.mode = "mysql"` in `serverconfig/bsp_inventorysync-server.toml` (the in-memory store makes it a single-server sync that forgets everything on restart):

- [ ] Log in: the log shows the arrival, you can move at once (no visible freeze on a first login).
- [ ] Put items in the inventory, log out, log in again: the items are there (restored from the snapshot, not from the player file: the log line names the version).
- [ ] Die and respawn: the log shows a `death` save.
- [ ] With a totem in hand, log out: BSP-Core places it; log in: no second totem.

## 2026-10-18: IS-5 MySQL store and Redis link

Covered headlessly by 4 GameTests (28 in all), but **no test has run against a real MySQL or Redis**: the JDBC store was only checked for clean failure with nothing listening. The first real run is the two-server test (IS-9). Until then, a single-server check against the real database:

- [ ] In `serverconfig/bsp_inventorysync-server.toml` set `storage.mode = "mysql"` (BSP-Core's database settings are used; set `storage.serverId` or rely on BSP-Core's). Start the server: the log says "tables ready on mysql ..." and "sync started as ...". Check the three tables `bsp_is_players`, `bsp_is_snapshots`, `bsp_is_extra` exist.
- [ ] Log in, put items in the inventory, log out: a row appears in `bsp_is_snapshots` (cause `logout`), and `bsp_is_players` shows `online = 0`. Log in again: the items are back, the log names the version.
- [ ] Log out and in 17 times: never more than 16 unpinned rows per player.
- [ ] With `redis.enabled = true` and the server's Redis settings: the log says "redis host:port connected"; after a logout `redis-cli KEYS 'bsp_is:bsp:*'` shows `presence:<uuid>`, `snap:<uuid>` and `held:<server>`.
- [ ] Stop Redis while playing: the log warns once, logins and logouts still work; start Redis again: the log says "connected again".

## 2026-10-18: IS-6 generic Forge data and the item-mod adapters

The generic module is covered headlessly (4 GameTests, 32 in all). **The three adapters have never run with their mods**: Curios, Sophisticated Backpacks and Dank Storage are not in the dev run, so only their absence is tested. First in-game checks, on one server with `storage.mode = "mysql"` and the full pack:

- [ ] Wear a curio (a ring, an elytra in the elytraslot), log out and in: it is still worn and shown on the client.
- [ ] Put items in a Sophisticated Backpack, put the backpack in the inventory, log out and in: the contents are there. Then a backpack inside a backpack.
- [ ] Fill a Dank, log out and in on the same server: contents kept, same frequency (no new id). Later, on two servers: the dank arrives full with a new frequency.
- [ ] Mekanism radiation: get irradiated, log out and in: still irradiated (the generic capability sync).
- [ ] Waystones: activate a waystone, log out and in: still known. Starter Kit: no second kit on a relog.
- [ ] The log has no "module ... failed" lines for `forge`, `curios`, `backpacks` or `dank`.

## 2026-10-18: IS-7 auto-save and the watchdog

Covered headlessly (3 GameTests, 35 in all). To see on a real server with `storage.mode = "mysql"`:

- [ ] Stand still for two minutes: no new row in `bsp_is_snapshots`. Move an item in the inventory and wait a minute: one new row with cause `autosave`.
- [ ] Stop MySQL while a player is online, then log out and in: the log shows "the store did not answer within 5000 ms" lines, the player is unfrozen with the red "could not be loaded" message within about 15 s, and nothing hangs. Start MySQL again: saves work.

## 2026-10-18: IS-8 the /invsync commands

Covered headlessly (5 GameTests, 40 in all). In game, as an operator:

- [ ] `/invsync status` shows the server id, the store (and Redis when on), the modules and "store ping: ok".
- [ ] `/invsync snapshots <you>` lists your snapshots newest first; `/invsync restore <you> <older version>` puts that inventory back at once; `/invsync resync <you>` returns to the newest.
- [ ] `/invsync pin <you> <version>`, then log out and in 20 times: the pinned version is still listed.
- [ ] `/invsync dump <you>` names a file under `world/bsp_inventorysync/dumps/` that opens as readable SNBT.
- [ ] `/invsync reload` while online: the reply says you were adopted; a logout afterwards still saves (a new row).

## 2026-10-18: first in-game run (owner), finding 1

- Two backends (BSP-SafeRoom, BSP-Spawn), Redis connected, but `/invsync status` showed `mysql localhost:3306` with "Communications link failure" and the arrival ended "could not be loaded from the network": `storage.useBspCoreDatabase = true` took BSP-Core's database settings, which still pointed at localhost on those servers. Not a mod fault; the fallback behaved as designed. Fix: `useBspCoreDatabase = false` (own fields apply) or BSP-Core's `storage.host` set to the real database. The BSP-Spawn file also had a trailing space inside the password.
- Change: the store description (start-up log line and `/invsync status`) now names where the database settings came from.
- [ ] Redo: after the fix, `/invsync status` shows the real host, "settings from this mod's ..." and "store ping: ok"; then logout, switch, arrival carries the inventory.

## 2026-10-21: SU-IS1 a tossed item comes back while syncing

Covered headlessly (1 GameTest, 41 in all): a frozen player who tosses a stack gets it back, and with a full inventory only the part that does not fit drops. In game, on a backend with `storage.mode = "mysql"`, while the "syncing" freeze is on (easiest by raising `arrival.timeoutSeconds` to 60 and setting the player's `online` back to 1 for another server in `bsp_is_players` just before logging in, so the arrival waits):

- [ ] Press Q (and Ctrl+Q) on a hotbar item: the item stays in the inventory, nothing lands on the ground, the client shows the right count.
- [ ] Open the inventory, pick up a stack on the cursor, click outside the window: the stack goes back into the inventory.
- [ ] With every inventory slot full, throw a stack from the cursor: what fits goes back, the rest lands on the ground and can be picked up once the freeze ends. Nothing is lost.

## 2026-10-21: BSP-Core 1.4.1, the totem api

Covered headlessly (1 new GameTest, 42 in all; also run against BSP-Core 1.3.0 to check the fallback). On a server with BSP-Core 1.4.1 and `storage.mode = "mysql"`:

- [ ] The start-up log says "BSP-Core totem rule via api, hand-back via api". On a server still on an older BSP-Core it says "via internal", and syncing works the same.
- [ ] Fill every inventory slot, then switch servers carrying a Shatter Totem (BSP-Teleport): on arrival the totem is in the inventory, and one stack it displaced lies at your feet and can be picked up once the freeze ends.

## 2026-10-21: BSP-Core 1.4.1, the restore event gates coins, the season check and owed items (R3)

Covered headlessly (1 new GameTest, 43 in all). Install BSP-Core 1.4.1 (slim jar on Mohist) next to this mod, then:

- [ ] BSP-Core's start-up log says "Coins, the season check and owed items wait for BSP-InventorySync's restore event at login".
- [ ] Switch servers with an item owed to you in BSP-Core (a pending item or a totem stored by the transfer): it arrives once, after your inventory is restored, and is still there after a relog. No "No inventory restore for ... within storage.restoreWaitSeconds" warning in BSP-Core's log.
- [ ] On a server with this mod installed but `storage.mode = "off"`: log in with an owed item; it is handed over at once (no 30 s wait, no warning).
- [ ] After a full season reset, log in on another server with old-season coins: they are stripped after the restore, not before (so they do not come back with the restored inventory).

## 2026-10-21: BSP-Core 1.4.1, dead arrivals (R4)

Covered headlessly (1 new GameTest, 44 in all): a player who arrives dead, with a totem owed by BSP-Core, stays on the death screen past BSP-Core's wait; nothing is settled on the dead entity; after the respawn the restore lands and the owed totem arrives exactly once. On two servers with BSP-Core 1.4.1:

- [ ] On server B, die and log out on the death screen (B keeps you dead in its own player file). Join server A (you arrive alive there), then teleport to B carrying a Shatter Totem (BSP-Teleport), so B owes you the totem.
- [ ] Arrive on B: the death screen shows. Wait more than 30 s, then respawn: your inventory from A is there, the totem is in it once, nothing lies where you died, and BSP-Core's log has no "No inventory restore for ..." warning.
- [ ] Same without the wait (respawn at once): the same result.

## 2026-10-21: BSP-Core 1.4.2, a totem into a full inventory (R5)

Covered headlessly (1 new GameTest, 45 in all): a full inventory arrives from another server while BSP-Core holds a transferred totem for the player and this server's ledger remembers an older instance of it; after the restore the totem is in the inventory once, live, recorded as arrived, and one cobblestone stack lies at the player's feet. The same test fails on BSP-Core 1.4.1 (the totem is dropped; with a free slot it is spent by BSP-Core's identity check). On two servers with BSP-Core 1.4.2 on both:

- [ ] Fill every inventory slot and teleport to the other server carrying a Shatter Totem (BSP-Teleport): on arrival the totem is in the inventory, one stack lies at your feet, nothing else is missing.
- [ ] Use or place the totem afterwards: it still works (no "spent" message, not a husk).
- [ ] Same with free slots: the totem arrives and still works.

## 2026-10-23: performance and safety check

Covered headlessly by 12 new GameTests (`gametest/SafetyTests`, 58 in all). Two fixes cannot be tested headlessly (the server stop and the action-bar text); they come first. All on two backends with `storage.mode = "mysql"` (Redis on where noted), both on this build.

- [ ] **Restart with players online.** Put an item in your inventory, wait under a minute (no auto-save yet), `/stop` the server. Join the other server: the item is there. `/invsync snapshots <you>` shows a `logout` snapshot taken at the stop. The log has no "store closed" errors at shutdown.
- [ ] **Cursor at logout.** Open your inventory, pick up a stack on the cursor, and disconnect (or teleport to the other server). On arrival the stack is in your inventory; back on the first server, nothing lies where you stood.
- [ ] **Crafting grid at logout.** Put items in the 2x2 crafting grid and disconnect: they arrive in your inventory. With a completely full inventory, they lie where you stood on the first server and are not in your inventory on the second (one place only).
- [ ] **Frozen arrival.** With the source slow to release (raise `arrival.timeoutSeconds` to 60 and set your `online` to 1 for the other server in `bsp_is_players` before joining): after a second the action bar says "Loading your items..." every second; a command (for example `/spawn`) answers "Your items are still loading. Try again in a moment." and does nothing; items put into the 2x2 grid are gone once your items load.
- [ ] **Unreadable data.** Stop MySQL and join: you are disconnected with "Your items could not be loaded from the network. Nothing was changed: please rejoin in a minute." Start MySQL and join: your items are as before (no newer snapshot was written while it was down).
- [ ] **Database outage.** Stop MySQL for a minute while playing, then start it: the log shows the connection failure once and then "waiting before the next try" lines at most every few seconds, not a flood; saves work again within 15 s of MySQL coming back.
- [ ] **Redis.** With Redis on, stop Redis, play a little, switch server, start Redis, and switch back within ten minutes: you arrive with your latest items, not the ones from before Redis stopped. If the cache was stale the log says "the cached snapshot of ... was vN but the database has vM".
- [ ] **Quick relog.** Disconnect and rejoin the same server at once, several times, moving an item each time: the item is always where you left it.
- [ ] **World save.** Stand still for 15 minutes: `/invsync snapshots <you>` shows no new `world_save` rows while nothing changed.
- [ ] **Totem in the ender chest.** Put a Shatter Totem in your ender chest and relog at once (before BSP-Core moves it out): the totem is in your inventory, not gone.
- [ ] **Backpacks.** Wear a Sophisticated Backpack in the Curios back slot, and put another inside a shulker box; switch server: both open with their contents.
- [ ] **Health.** With gear that raises maximum health (for example +10 hearts), switch server at full health: you arrive at full health a moment later, not capped at 10 hearts.
- [ ] **Admin rollback.** `/invsync restore <you> <older version>`: the reply ends "saved as vN"; switch server at once: you arrive with the rolled-back items.
- [ ] **Mixed mods** (needs three servers). With a mod missing on B only (for example Curios): change a curio on A, go to B, then to C: C shows the curio as you left it on A, not C's older copy.

## 2026-10-23: D16-1 the respawn point stays per server

Covered headlessly (the data-module round trip now checks that the snapshot carries no respawn point and that an older snapshot's is ignored; 58 in all). On two backends:

- [ ] Sleep in a bed on A, switch to B, die on B: you respawn at B's world spawn (or B's own bed), with no "You have no home bed" for A's bed.
- [ ] Back on A, die: you respawn at A's bed, which is still set.
- [ ] Sleep in a bed on B too, then switch back and forth: each server keeps its own bed.

## 2026-10-23: D14-1 the placeholder is a barrier

Covered headlessly (the placeholder round trip now expects a barrier with two lore lines and checks that an old paper placeholder still saves as its item; a new GameTest checks that placing or using a placeholder is refused while clicking a block still works, and that a real barrier is left alone; 59 in all). On two backends, with a mod (any item mod) installed on A only:

- [ ] Carry an item of that mod from A to B: on B it is a barrier named "Missing item: <id>" in red italic, with the two grey lore lines.
- [ ] On B, right-click the air and a block with it: nothing is placed, the action bar says "This is a placeholder for a missing mod's item."; right-clicking a chest with it in hand still opens the chest.
- [ ] In a crafting grid it makes nothing, in a furnace it does not smelt or burn, and no villager trade takes it; it can be dropped, stored in a chest and picked up again.
- [ ] Back on A: it is the original item again, with all its data.
- [ ] An old paper placeholder (from before this build), carried to a server without the mod, becomes a barrier there; on a server with the mod it is the original item.

## 2026-10-25: single-player worlds and servers outside the network

Covered headlessly (2 new GameTests, 61 in all): the game test server, which is not a dedicated server, refuses to start the sync even with `storage.mode = "mysql"` and a real server id; and a snapshot saved by a server not in `sync.networkServers` is passed over for the network's newest. The first test was checked against the build without the fix and fails there.

- [ ] Set `sync.networkServers` on every backend to the full list of the network's server ids (the same list everywhere), restart, and check the log has no "sync.networkServers is empty" warning.
- [ ] In a single-player world of the client pack (even one whose `serverconfig/bsp_inventorysync-server.toml` says `mode = "mysql"`), the log says "not a dedicated server (single player or LAN): inventory sync is off". Change your inventory there, then join the network: you arrive with your network inventory, not the single-player one.
- [ ] `/invsync snapshots <you>` on a backend after that: no new snapshot from the single-player session.
