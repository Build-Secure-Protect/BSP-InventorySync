# BSP-InventorySync 0.6.0: graves you can spot

Minecraft 1.20.1, Forge 47.4. Requires BSP-Core 1.1.0 or newer. Needed on the client in exactly the server's version: update the modpack together with the servers.

## Changed

- The headstone is bigger: a block wide and a little over a block tall, with a glowing golden sigil on its front and back that shows at night and underground.
- A grave needs a little room above it now: dying in a tunnel one block high puts the grave at the nearest spot with room, just outside the tunnel or on the surface above. The death message tells you where.
- Graves from 0.5.0 get the new look where they stand.

## Added

- The owner's own head, with their skin, out of the ground in front of the stone, leaning a little to the left or right. It is part of the grave: right-click it to open the grave.
- Gold and neon green motes drift up around a grave for players within 16 blocks; your Particles setting applies.

---

# BSP-InventorySync 0.5.0: graves

Minecraft 1.20.1, Forge 47.4. Requires BSP-Core 1.1.0 or newer. **Now needed on the client too**, in exactly the server's version: put the same jar in the modpack. Replaces Simply Graves: remove it from the servers and the pack (and collect its graves first).

## Graves

- A death that is not PvP leaves a headstone where it happened, holding everything that would have dropped and the experience. Every death gets its own grave.
- Right-click opens a chest screen laid out like the inventory. Take a stack, or Take all: everything goes back to the slot it came from, armour worn again; what does not fit stays in the grave. Nothing is dropped or deleted, in creative mode either.
- Only its owner for the first hour (`graves.privateMinutes`), then anyone; the first to open it gets the experience; the owner is told on whichever server they are when someone else opens it.
- No grave for a PvP death: the drops fall for the killer. Shatter Totems never go into a grave, and a grave never takes the spot of a dropped totem.
- Nothing breaks it: creative mode, explosions, pistons, fluids, fire, the wither, the dragon. Claims and regions never block it. It glows faintly; its stone suits the place (stone, deepslate, blackstone, end stone).
- In lava, water or a wall, the nearest dry spot within 8 blocks; failing that, where the player last stood on solid ground; failing that, kept safe without a block and recovered with `/grave`.
- `/grave` lists every grave on every server; [Recover] opens one that found no spot, or that nobody opened for 7 days, wherever the player is. Backpack and dank contents travel with their items.
- Unopened for 30 days, a grave is put away (admins still see it); 30 days later it is deleted, with every stack in the log.
- Admins: `/invsync graves <player>`, `/invsync grave open|give|remove <player> <id>`.
- Kept in the network database (`bsp_is_graves`), written together with the player's snapshot after the death, or in the world's own data on a server without it, single player included. A crash, an outage or a slow database never duplicates or loses a grave; two servers can never take the same stack.

## Changed

- The client needs the mod, in the same version as the server (a client with it can still join servers without it).

---

# BSP-InventorySync 0.1.0

First release. Minecraft 1.20.1, Forge 47.4. Server-side only: players need nothing installed. Requires BSP-Core 1.1.0 or newer.

## What it does

Your inventory and everything about your character follow you when you move between the servers of the Build Secure Protect network. The server you leave saves a snapshot; the server you reach waits for that save, then hands you your things. You are held still while it does, usually for a few milliseconds.

## Features

**Player data that travels**
- Inventory, armour, offhand, selected hotbar slot and the item on the cursor.
- Ender chest.
- Experience, level progress and score.
- Health, absorption, air, hunger, saturation and active potion effects.
- Game mode and flight. The respawn point (bed or anchor) stays per server, since each has its own world.
- Advancements, without granting their rewards a second time.
- Every mod's per-player data: persistent NBT and player capabilities, so mods such as Waystones, Starter Kit, Mekanism (radiation) and Compact Machines need no special support. A config list excludes what must stay on one server.
- Curios slots (rings, elytra slot, jetpacks and the like) through Curios' own data.
- Sophisticated Backpacks contents, including backpacks inside backpacks.
- Dank Storage contents, with a fresh frequency on the server you arrive at.
- Items from a mod a server lacks become a named barrier (it cannot be crafted, traded or placed) that keeps the original data, and turn back into the item on a server that has the mod. Nothing is thrown away.

**Built for BSP-Core**
- A live Shatter Totem is never written into a snapshot. BSP-Core carries totems its own way, so a totem can never be duplicated by the sync.
- A totem BSP-Core hands back on arrival is kept and put back through BSP-Core, never dropped or overwritten.
- BSP-Core delivers pending items and runs its season coin check only after the restore, through the `InventoryRestoredEvent`.
- Database settings and the server id are taken from BSP-Core's config by default.

**The arrival handshake**
- The server you leave releases you only after its save has landed; the server you reach polls for that release every tick and applies the newest snapshot the moment it sees it.
- While waiting you are frozen: no movement, interaction, pickup, drop, block breaking or damage, so a stale local copy of your inventory can never be duplicated.
- A configurable timeout (10 s): then the last known snapshot is applied and you are told, or the login is refused, as the admin prefers.
- Players who leave before their data arrived are never saved over. Players who arrive dead get their data at the respawn.

**Storage**
- MySQL through plain JDBC, with the mod's own tables under its own prefix; no database driver is bundled.
- Optional Redis fast path with the mod's own small client: the checkout and the newest snapshot of every player are cached, so a switch never waits on MySQL. MySQL stays the source of truth; a Redis outage is logged once and otherwise invisible.
- A watchdog on every database and Redis call: a frozen database never leaves a player frozen or the server hanging on shutdown.

**Saves**
- On logout, on server transfer, on death (at the respawn), on world save, and every 60 seconds for players whose data changed since their last save. Unchanged players cost no database write.
- One compressed record per snapshot. A rotating history of 16 snapshots per player, each with its cause; pinned snapshots are kept for ever.

**Operator commands** (`/invsync`, permission level 2)
- `status`: server id, database and Redis state, who is waiting, counters, modules, a store ping.
- `snapshots <player>`: the player's snapshots, newest first, with cause, server, age and size.
- `restore <player> <version>`: a rollback to any snapshot. `resync <player>`: back to the newest.
- `pin` / `unpin <player> <version>`, `save <player>`, `dump <player>` (readable copy of the player's data for a bug report), `reload`.
- `give <player> <item> [count]`: an item on any server of the network, and from the console; what does not fit waits, and the player is asked to make room in a small chest screen with Deposit and Cancel.
- `edit <player> [word]` (level 3): the player's inventory and ender chest in a chest screen with a creative item list.

**For mod developers**
- A small public api: register your own data module, exclude items from snapshots, ask whether a player is still syncing, announce a transfer. Two Forge events: `InventorySavedEvent` and `InventoryRestoredEvent`. See `docs/API.md` in the repository.

## Known limits

- Statistics and the recipe book are not synced.
- A Dank Storage container moved between servers gets a new frequency on arrival; the old one stays unused in the source server's world data.
- Frequency-keyed block storage (EnderStorage, EnderChests and EnderTanks, Functional Storage ender drawers, AE2 ender drives) is world data on each server and is not synced.
