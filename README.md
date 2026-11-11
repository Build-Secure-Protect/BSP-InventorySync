# BSP-InventorySync

A Forge mod (Minecraft 1.20.1) for the Build Secure Protect (BSP) network, needed on the servers and, in exactly the same version, on every client (for the grave block). It syncs each player's inventory and per-player data between the BSP servers through their shared MySQL database, with BSP-Core's Shatter Totems and BSP-Teleport's server transfers taken into account.

Companion to [BSP-Core](https://github.com/Build-Secure-Protect/BSP-Core), which it requires. BSP-Teleport, Curios, Sophisticated Backpacks and Waystones are optional.

## Status

Started 2026-10-17. Storage, data modules, the totem rules, the arrival handshake and the MySQL and Redis stores are built and covered by 28 headless GameTests. Nothing has run on a real server yet.

## Configuring

Two files. The server config, `<world>/serverconfig/bsp_inventorysync-server.toml`, holds everything that is not secret: the mod is off until `storage.mode = "mysql"`; by default it connects to the database named in BSP-Core's `config/bsp_core-network.toml` and uses BSP-Core's `storage.serverId`, keeping its own tables under `bsp_is_`. The connection of its own (database host, port, user, password, SSL; Redis host, port, user, password) lives in `config/bsp_inventorysync-network.toml`, a common config that is never sent to players (Forge sends every server config to each player who joins). Values left in the old places are moved there at the first start. Never ship either network file in the client pack. Turn on `redis.enabled` with the network's Redis to make server switches instant; without Redis the switch waits only for the source server's save to land in MySQL. List every backend's server id in `sync.networkServers` (matched ignoring case) so a snapshot saved by any other server is never applied. The sync only ever runs on a dedicated server: single-player and LAN worlds are always off, whatever their config says, so keep the servers' configs (and their passwords) out of the client modpack's `defaultconfigs`. Operators of level 3 can edit a player's inventory with `/invsync edit <player>`, and operators (or the console) can give a player an item on any server with `/invsync give <player> <item> [count]`: what does not fit waits for them, and they are asked to make room. Graves take over from Simply Graves (remove it from the servers and the pack; graves stay off while it is installed): a death that is not PvP leaves a headstone holding everything (a block wide, with a glowing golden sigil, the owner's own head out of the ground in front and gold and green motes around it), opened by right-click; `/grave` lists every grave on every server and recovers one that found no spot or was left a week; admins have `/invsync graves` and `/invsync grave open|give|remove|return` (return puts a grave that could not be placed, or one put away, back into its owner's inventory, now or at their next login). A bound Compact Machine works only on the server its room is on: it is marked with that server and cannot be placed anywhere else (`/invsync machine` shows or changes the mark). Staff tools (Server Utilities' web panel) can read any player's items, Curios, backpacks and danks included, and add or remove items, live on the player's own server or on an offline player's newest snapshot, through the api (version 2). See `docs/API.md` for the commands and for how other mods hook in.

## Building

Build BSP-Core first (its jar is read from `../BSP-Core/build/libs`), then:

```
./gradlew build
```

The jar lands in `build/libs/`. Headless tests: `./gradlew runGameTestServer`.

## Licence

See `LICENSE`.

## CurseForge

`docs/curseforge/DESCRIPTION.md` and `docs/curseforge/CHANGELOG.md` are the project page texts; the images come from `python3 tools/gen_curseforge_images.py` (macOS Quick Look renders them) and are referenced by their raw GitHub URLs, so they must be committed.
