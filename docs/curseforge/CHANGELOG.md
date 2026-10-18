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
- Game mode, flight and the respawn point (bed or anchor).
- Advancements, without granting their rewards a second time.
- Every mod's per-player data: persistent NBT and player capabilities, so mods such as Waystones, Starter Kit, Mekanism (radiation) and Compact Machines need no special support. A config list excludes what must stay on one server.
- Curios slots (rings, elytra slot, jetpacks and the like) through Curios' own data.
- Sophisticated Backpacks contents, including backpacks inside backpacks.
- Dank Storage contents, with a fresh frequency on the server you arrive at.
- Items from a mod a server lacks become a named sheet of paper that keeps the original data, and turn back into the item on a server that has the mod. Nothing is thrown away.

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

**For mod developers**
- A small public api: register your own data module, exclude items from snapshots, ask whether a player is still syncing, announce a transfer. Two Forge events: `InventorySavedEvent` and `InventoryRestoredEvent`. See `docs/API.md` in the repository.

## Known limits

- Statistics and the recipe book are not synced.
- A Dank Storage container moved between servers gets a new frequency on arrival; the old one stays unused in the source server's world data.
- Frequency-keyed block storage (EnderStorage, EnderChests and EnderTanks, Functional Storage ender drawers, AE2 ender drives) is world data on each server and is not synced.
