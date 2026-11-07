# In-game testing checklist

One dated section per task, newest last. The GameTests run headless, without a Minecraft client; the owner runs these checks in game and reports back. The real test for this mod is two servers sharing a database.

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

## 2026-10-28: passwords out of the server config

Covered headlessly (2 new GameTests, 63 in all, run twice; and one start of the test server with a password left in the old file: the log named `storage.password` only, the value moved to the network file and was gone from the server file). On each backend:

- [ ] Start the server with the new jar. If this mod had its own connection settings, the log has one warning "moved storage.... from bsp_inventorysync-server.toml to config/bsp_inventorysync-network.toml", with key names only, no password.
- [ ] `config/bsp_inventorysync-network.toml` exists in the server's `config/` folder (not under `world/`) and holds the database and Redis settings; `world/serverconfig/bsp_inventorysync-server.toml` has empty passwords and "Moved to ..." comments.
- [ ] `/invsync status` shows "store ping: ok" and, with Redis on, "connected"; the store line names "config/bsp_inventorysync-network.toml" or, with `useBspCoreDatabase = true`, "BSP-Core's config/bsp_core-network.toml".
- [ ] After every BSP mod is updated, change the database and Redis passwords in the network files only, and restart.

## 2026-11-02: ED-1 the inventory editor (players on the same server)

Covered headlessly (8 GameTests in `gametest/EditorTests`, 71 in all, run twice): list to inventory only on Confirm; the admin's own inventory locked against click, shift-click, number key, throw and outside clicks, and the cursor dropped on close; the list never replaces an item taken from the player; armour slots take only what can be worn there; a totem can be trashed and is never offered; Confirm refused after the player's inventory changed; the before-state pinned and the edit saved as an admin snapshot; search. Not covered headlessly: how the real client shows the screen and handles fast clicks. On a backend, as an operator of level 3, with a second account online on the same server:

- [ ] `/invsync edit <name>` opens "Editing <name>": the inventory tab shows their main inventory, hotbar, armour (empty slots as grey panes named Helmet, Chestplate, Leggings, Boots, Offhand) and the toolbar.
- [ ] Items tab: 45 items a page, arrows turn pages, clicking Items again moves to the next creative tab; left-click takes a stack, right-click one, shift-click puts a stack straight into their inventory.
- [ ] Move items around their inventory, trash one, put armour on (non-armour is refused). Your own inventory below cannot be touched (clicks, shift-clicks, number keys do nothing).
- [ ] Close the screen while holding an item: it is gone, not in your inventory; nothing changed for the player.
- [ ] Save: the Added and Removed rows match; Back returns with the changes; Confirm: the player sees the change at once; chat says what changed and, a moment later, the snapshot versions; the server log has one "edited <name>'s inventory" line.
- [ ] `/invsync restore <name> <the pinned version>` undoes it.
- [ ] Open the editor, let the player pick something up, then Confirm: refused with "changed while you edited", nothing changed.
- [ ] `/invsync edit <name> diamond`: the Items tab opens on items containing "diamond"; no Shatter Totem is ever in the list.
- [ ] An operator of level 2 does not see `edit`; `/invsync edit <yourself>` is refused; a player who is offline or on another server gets "not online on this server".

## 2026-11-02: ED-2 editing players who are offline or on another server

Covered headlessly (3 more GameTests in `gametest/EditorTests`, 74 in all, run twice): an offline edit becomes the newest admin snapshot, pins the one it started from, keeps the ender chest, every other module and an unknown mod's item, and releases the checkout; it is refused if the player came online or a newer snapshot was saved; the editor will not open on a player online on another server, on a player with no data, or with the sync off. On two backends with the sync on:

- [ ] With a player logged out, `/invsync edit <name>`: the title says "(offline)", chat says which version and server it opened; their last inventory is shown.
- [ ] Change something and Confirm: chat says "Changed <name>'s saved inventory" and the versions; `/invsync snapshots <name>` shows a new `admin` snapshot and the one before pinned.
- [ ] The player logs in on either server: they have the change.
- [ ] With the player online on the other backend, `/invsync edit <name>` answers "<name> is on <server>; edit them there."
- [ ] Open the offline editor, let the player log in somewhere, then Confirm: refused ("came online ... while you edited"), nothing changed.


## 2026-11-02: BSP-Core audit, server ids, BSP-Core keys, failed restores

Covered headlessly (4 new GameTests, 78 in all, run twice): a list entry "BSP-Saferoom" matches snapshots saved by "BSP-SafeRoom" (and this server's own id counts in any case), other ids are still refused; with `sync.excludeForgeData` empty, BSP-Core's `LastMainDim`, `LastMainPos`, `LastMainServer` and `ZoneHeldTotems` are neither saved nor overwritten while its other keys travel; a player whose only snapshot comes from outside the network gets exactly one version-0 restore event; a restore that throws refuses the login, posts no event, saves nothing and releases the checkout. Not tested in game or on two real servers.

- [ ] On all 7 servers, in `<world>/serverconfig/bsp_inventorysync-server.toml` under `[sync]`, set `networkServers = ["BSP-SafeRoom", "BSP-Spawn", "BSP-Sanctuary", "BSP-Frostwarf", "BSP-Boulderbrook", "BSP-Moongrove", "BSP-Stormfall"]` and `excludeForgeData = ["PlayerPersisted.bsp_core:LastMainDim", "PlayerPersisted.bsp_core:LastMainPos", "PlayerPersisted.bsp_core:LastMainServer", "PlayerPersisted.bsp_core:ZoneHeldTotems"]`; update the `defaultconfigs/` copies too. `/invsync reload` or restart; the log shows no "networkServers is empty" or "correct the spelling" warning.
- [ ] Put a marked item in your inventory on SafeRoom, switch to Frostwarf: the item is there and `/invsync snapshots <you>` on Frostwarf shows the arrival used SafeRoom's newest version.
- [ ] Set a base position on one server (BSP-Core's last main position), switch servers and back: the position on each server is that server's own.
- [ ] Die in an Anti Totem zone with a totem, switch servers from the death screen if possible, respawn: the totem is handed back on the server where you died and never appears on the other.
- [ ] A new player's first join: BSP-Core's first totem and guide book arrive within a second or two, not after 30 seconds (no "No inventory restore ... within storage.restoreWaitSeconds" line in BSP-Core's log).


## 2026-11-05: the XP bar and effects after a teleport into another dimension

Covered headlessly (1 new GameTest, 79 in all, run twice): Forge's dimension-change event, which `teleportTo` into another level posts, sends the player's real XP (level, total, progress) once and one packet per active effect (type, amplifier, duration) to their client, and leaves the server's XP and effects untouched. The test was checked with each half switched off and fails there ("one XP packet sent: 0", "one packet per effect, for this player: 0"). The vanilla side (`teleportTo` rebuilds the client player but re-sends neither; a portal re-sends both; the client's respawn handler keeps neither) was read from Forge 47.4.10's compiled code, not seen in game.

- [ ] Get some levels in the Nether, then return to the Overworld with a BSP-Teleport teleport (home, warp or spawn): the XP bar shows the same level at once.
- [ ] The same with BSP-Core's totem recall, a Region Guard border link or trigger into another dimension, and `/execute in minecraft:the_nether run tp @s ~ ~ ~`.
- [ ] Drink a potion of slow falling or night vision in the Nether, then teleport to the Overworld with BSP-Teleport: the icon stays, night vision stays bright, and slow falling still slows a fall.
- [ ] A portal trip still shows the right level and effects.


## 2026-11-07: the inventory editor and spectator mode

Covered headlessly (2 new GameTests, 81 in all, run twice): the editor through the real screen (an end-to-end test sending the client's click packets to the server and rebuilding the admin's view from the packets sent back: the player's items show, the Ender chest tab switches, items move); and a spectator admin is refused for online and offline players, while an open editor closes without changes when its admin turns spectator. The spectator test was checked with each check switched off and fails there. After the audit (2 more GameTests, 83 in all, run twice): an offline editor is refused when the admin turns spectator while the snapshot is read (a store that answers late), and the close follows the game mode other mods settle on (a cancelled switch leaves the editor open; a switch another mod turns into spectator closes it). Both were checked with their fix undone and fail there. That vanilla drops a spectator's clicks was read from Forge 47.4.10's `handleContainerClick` and reproduced with a spectator mock admin, not seen in game.

- [ ] In spectator mode, `/invsync edit <player>`: the red line "Leave spectator mode to use the editor ..." and no screen.
- [ ] In creative or survival, `/invsync edit <player>`: the player's items show; click Ender chest, then Inventory: the tabs switch; take an item from the Items tab and place it in an empty slot; Save, Confirm: the player has it.
- [ ] With the editor open, switch to spectator (`/gamemode spectator` from another operator or a command block): the editor closes with the red line and the player's inventory is unchanged.


## 2026-11-07: /invsync give and the waiting-items popup

Covered headlessly (9 new GameTests, 92 in all, run twice), with one in-memory store shared by services standing for two servers: a player here with room gets it all; a full inventory keeps the rest waiting, and creative mode deletes nothing (vanilla's insert would have reported 70 handed over and deleted 66, shown with the fix undone); Deposit puts in what fits, stays open with the rest and closes when everything is in; the popup's stacks cannot be taken, shift-click moves nothing into it, and the player's own inventory works (pick up, drop outside); Cancel and Esc put it off (in the store), a switch keeps that, a join clears it and asks again; a logout with the popup open is not a Cancel; a player on another server is handed it there, once (the same rows again hand over nothing); an offline player's waits; the arrival hands over after the restore and tells a join from a switch; totems and more than 100 stacks are refused. Four of these were checked with their rule broken and fail there. Not covered headlessly: the poll every `give.pollSeconds` over the server's player list (test players are not in it; `check` is called directly), the command's argument parsing, MySQL, and the real client.

- [ ] Start every server with the new jar: the log has "tables ready" with no error, and `bsp_is_gives` exists in the database.
- [ ] `/invsync give <you> diamond 64` on the server you are on: 64 diamonds, "You received 64 Diamond from <you>", and the reply "Gave 64 Diamond to <you>".
- [ ] From the console of another server: `invsync give <you> golden_apple 3`. The reply says "on the way to <your server>"; within 5 seconds you receive them, "from Server".
- [ ] Fill your inventory, then `/invsync give <you> emerald 128`: the popup opens with two stacks; the info paper says 2 free slots are needed. Drop items from your own inventory in the popup (Q or drag out), watch the paper count down, click Deposit: what fits goes in; clear more and Deposit again: it closes.
- [ ] With items waiting, click Cancel (or Esc): the gold line says you will be asked again at your next login. Switch servers with BSP-Teleport: not asked. Log out and back in: asked again.
- [ ] `/invsync give <offline player> bread 5`: "waiting for their next login"; when they join any server, they get it after their inventory has arrived.
- [ ] In creative mode with a full inventory, give something: nothing disappears; the popup opens.
- [ ] `/invsync give <you> bsp_core:shatter_totem`: refused.
- [ ] With items waiting and the popup cancelled, run `/invsync reload`: you are not asked again within 5 seconds.

After the audit (6 more GameTests, 98 in all, run twice), against a store made to fail or answer late: a failed removal leaves that stack waiting, hands over the rest and the next check delivers it; a player who logs out while the store answers gets nothing put into the saved copy and the stack waits again; a fresh service (as after `/invsync reload`) reads a Cancel before asking; a give stored only in part reports what to give again; a failed read of the Cancel at a switch is read again before anything is asked; closing during a Deposit decides the Cancel on what is left. All six were checked with their fix undone and fail there.


## 2026-11-07: graves, part 1 (GR-1): the death and the storage

Covered headlessly (16 new GameTests, 114 in all, run twice): the grave stores' rules on the in-memory store and the world's data (every grave kept, the same id never stored twice, updates and removals only at the revision read), the world's data across a save (an unreadable entry kept, never dropped), the contents (stacks with their tags and slots); a death puts the drops in a grave with the slots they came from; a second death makes a second grave; a PvP death makes none and leaves the drops and orbs; the XP goes into the grave, or back as orbs when no grave is made; live totems stay in the drops; nothing while the player's data is arriving; a death in water or inside a wall finds the nearest dry spot; BSP-Core's dropped-totem entity's spot is left free; with no spot near, the last solid ground; with none, a grave without a spot; a grave reaches the database, leaves the world's data and the death is saved; a database that is down keeps the grave in the world's data and gets it at the next try, once; and one real death through Forge's own events (the drops, the slots, the 35 XP points, nothing on the ground). Five rules were checked broken and fail there (PvP, the totem's spot, the world's copy first, the orbs given back, nothing while arriving). Not covered headlessly: MySQL (`bsp_is_graves`), a real client, Curios and the backpack and dank adapters with their mods, the 30-second resend on the server tick.

Only on a test server, and only if you want to check this part now: graves cannot be opened until GR-3 and GR-4, so items that go into a grave now wait there until then (they are kept). With Simply Graves removed from that server and `graves.enabled = true` in its `bsp_inventorysync-server.toml`:

- [ ] Start it: the log says "graves on, kept in the network database" (or "this world's own data" without the sync) and "tables ready" with no error; `bsp_is_graves` exists.
- [ ] With Simply Graves still installed, start it: "Simply Graves is installed, so this mod's graves stay off".
- [ ] Die from a fall with a few items and some levels: nothing drops, no orbs; chat says "Your grave is at x y z in the Overworld on <server>"; the database has one row in `bsp_is_graves` with those stacks and the XP.
- [ ] Die in water, then in lava: the grave's position in chat is a dry spot beside it.
- [ ] Die in the void: chat says the grave found nowhere to stand and everything is kept.
- [ ] Die twice without collecting: two rows.
- [ ] Get killed by another player: no grave, the items and orbs drop for them.
- [ ] Die with a Shatter Totem (not PvP): BSP-Core drops the totem as usual and it places itself after its timer; the grave's spot in chat is not the totem's.
- [ ] Die with a backpack holding items: the row exists; (opening it comes with GR-3).


## 2026-11-07: graves, part 2 (GR-2): the grave block

Covered headlessly (6 new GameTests, 120 in all, run twice): a death puts the block up where the grave says, facing the way the player faced, of the stone for the place, its block entity naming the grave, the owner and the time it turns public (an hour after the death); the stone follows the place (stone, deepslate below y 0, blackstone in the Nether, end stone in the End); breaking is refused, an explosion's block list loses the grave (and only the grave), pistons, water, lava, fire, the wither, the dragon and growing features leave it, it glows at 4, has no item and drops nothing; the clients get the owner and times with the server's clock (a client ten minutes slow still counts right) and never the contents; the floating text's lines for the owner, for others before and after it turns public; and the version check carries this exact mod version. Four rules were checked broken and fail there (breaking, the explosion filter, the facing, the placing). The fluid guard could not be shown failing: this headstone's size sits exactly on vanilla's own threshold for a solid block (35/48), so vanilla already keeps water and lava out; the two guards are there in case the shape changes. Not covered headlessly (no client in the GameTest server): the model and its textures, the floating text on screen, the glow as seen, Forge refusing a client with another version or without the mod.

On a test server with `graves.enabled = true` (and Simply Graves removed), with the new jar on the server and in your client:

- [ ] Join with the same jar: fine. Join with the 0.4.0 jar (or none) in the client: refused, with BSP InventorySync named.
- [ ] Die from a fall in the Overworld above y 0: a stone headstone where you died, its broad side facing the way you looked; your name over it and "Right-click to open" in gold; it glows faintly at night.
- [ ] Die below y 0, in the Nether and in the End: deepslate, blackstone, end stone.
- [ ] A second player looks at your grave: your name and "Public in 59:xx" in grey, counting down; walk 33 blocks away: the text goes.
- [ ] Try to break it in survival and in creative: it stays, with the red line "A grave cannot be broken". Light TNT next to it: it stays. Push it with a piston, pour water and lava on it, light a fire beside it: it stays.
- [ ] Middle-click it in creative: nothing is picked.
- [ ] Right-click it: nothing yet (the grave screen is GR-3).


## 2026-11-07: graves, part 3 (GR-3): the grave screen

Covered headlessly (13 new GameTests, 133 in all, run twice): the owner opens at once and others only after the hour; the layout (main rows, hotbar row, armour and offhand; 51 stacks make two pages); a click takes a stack back to its slot, the last one removes the grave and its block and closes the screen; Take all wears the armour again, puts the sword back in its hotbar slot, leaves what does not fit in the grave and keeps the screen open, in creative mode too; part of a stack when only part fits; the XP to the first opener only; two screens never take one stack twice, and a screen that missed another server's change takes nothing until it is shown the grave again; nothing can be put in (shift-click, drag, a click with an item on the cursor); a player who leaves while the database answers finds the stack back in the grave, never in their saved inventory; a grave still on its way to the database is not opened; an empty grave's block goes at a right-click, and a right-click a claim already refused still opens the grave; the screen closes 12 blocks away; the backpacks chosen to travel with the stacks taken (and the ones inside them). Eight rules were checked broken and fail there. Found and fixed by the tests: Take all let a stack whose own slot was full take another stack's free home slot (now every stack whose slot is free goes home first). Not covered headlessly: the real client's screen, Curios slots (their items come back into the main inventory, not the Curios slot), backpack and dank contents with their mods, MySQL.

On a test server with `graves.enabled = true` (Simply Graves removed), the new jar on the server and in your client:

- [ ] Die with armour, a full hotbar and some main-inventory items; respawn, walk back, right-click the grave: the screen shows your things laid out as they were, armour in the bottom row.
- [ ] Take all with an empty inventory: everything is back where it was, armour worn; the grave and its block are gone with a puff.
- [ ] Die again; fill your inventory with blocks first, then Take all: what fits comes back, the rest stays; drop some blocks from your own inventory in the screen and click the stacks left.
- [ ] Die with 10 levels: the first opening gives the XP back; open again: no more.
- [ ] A second player right-clicks your grave within the hour: "only <you> may open it for another mm:ss". After the hour (or with `privateMinutes = 0`): they can, and you see "<them> opened your grave".
- [ ] Two players with the screen open at once: a stack one takes disappears from the other's screen.
- [ ] Right-click your grave inside someone's claim or a protected region: it opens.
- [ ] Walk away with the screen open: it closes.
- [ ] Shift-click and drag your own items toward the grave: nothing goes in.


## 2026-11-07: graves, part 4 (GR-4): /grave, the admin commands and the 7-day and 30-day rules

Covered headlessly (10 new GameTests, 143 in all, run twice): after six days "go to it", after seven days `/grave recover` opens the screen wherever the player is; a grave with no spot is recovered at once; the list (newest first, the put-away one left out, [Recover] linking to `/grave recover <id>` only where allowed, "(from anywhere in 7 days)" otherwise); at 31 days a grave is put away (its block gone, the owner's list empty, the admin list says so), 29 days later it is still kept and 31 days later deleted; opening restarts the clocks; a put-away grave is refused to its owner and opened by an admin; an admin gives a whole grave once (the player has it all, the grave and block gone, a second give finds nothing); a grave with backpacks is not given to a player on another server; an admin removes a grave and its block, numbered newest first; the owner on another server gets the notice once; graves on by default with 7, 30, 30 days and 60 minutes. Seven rules were checked broken and fail there. Not covered headlessly: the command parsing and tab completion, the click on [Recover] in a real client, the sweep every 10 minutes and the notice poll every minute on the server tick (called directly), MySQL.

On a test server (Simply Graves removed; check `graves.enabled = true` in `bsp_inventorysync-server.toml`, a test build may have written false):

- [ ] `/invsync status`: "graves on, kept in the network database".
- [ ] Die twice in different places, then `/grave`: both listed newest first with where they are and "(from anywhere in 7 days)".
- [ ] Die in the void: `/grave` shows it with [Recover]; click it: the screen opens where you are.
- [ ] Set `graves.recoverAfterDays = 0`, `/invsync reload`, `/grave`: every grave has [Recover]; recover one standing on another server: you get the items; the block there goes at its next right-click.
- [ ] `/invsync graves <you>`: numbered list. `/invsync grave open <you> 1`: the screen, for you as the admin.
- [ ] `/invsync grave give <you> 1 <someone>` with them online: they get everything (what does not fit in the waiting popup); the block goes; you get "An admin gave your grave to ...". Run it again: "no grave number".
- [ ] `/invsync grave remove <you> 1`: gone, block too; the log lists every stack.
- [ ] Set `graves.archiveAfterDays = 1` and `deleteArchivedAfterDays = 1` on a test server with an old grave (or wait): within 10 minutes of a day without opening, it is put away (block gone, "Your grave ... was put away"); a day later it is deleted and the log lists what it held.
- [ ] Have a second player open your grave after the hour while you are on another server: within a minute you see "<them> opened your grave on <server>".


## 2026-11-07: graves audit, before 0.5.0

An audit by three independent reviews found loss and duplication paths and a few rough edges; all fixed. Covered headlessly (12 new GameTests, 155 in all, run twice): a take the database answers with an error after it landed is read back and handed over; an emptied grave stays as a marker so an old copy never brings it back; a new grave reaches the database only with a snapshot, and is dropped (with its block) when the logout save fails; drops another mod cancelled give the held orbs back; the screen's picture slots refuse every take and place; the End's arrival platform, podium and pillar tops are never a grave's spot; a creative player never breaks a grave; a client with the mod joins servers without it while a server takes only the same version; a networked grave's block survives a server that cannot reach the database; a player whose data is arriving opens nothing and takes no XP; an unreadable grave is refused untouched; a grave whose block a crash lost is recovered at once. The admin commands take ids now, and give makes the grave the recipient's (the tests for those were rewritten). Every fix was checked undone and its test fails there. Also fixed: the XP tests counted merged orbs once (vanilla merges equal orbs into one that counts twice). Not covered headlessly: a real MySQL transaction (`saveWithGraves`), the real client (creative left-click, the text at screen edges), the End's rebuild in play.

- [ ] On a synced test server, die and quit at once from the death screen: rejoin (here or elsewhere): the grave is there and your inventory is empty; nothing doubled.
- [ ] Stop MySQL, die, start MySQL again within a minute, respawn: the grave appears in `/grave` (it rode on the respawn save).
- [ ] In creative mode, left-click a grave: it stays and its name stays over it.
- [ ] `/invsync graves <you>` shows ids; `/invsync grave give <you> <id> <someone>`: in their `/grave` with [Recover]; the block is gone.
- [ ] Join a server without the mod (a vanilla test server) with the 0.5.0 client: allowed.
- [ ] Die on the End's obsidian arrival platform: the grave is set beside it, not on it.

