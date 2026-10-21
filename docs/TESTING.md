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
