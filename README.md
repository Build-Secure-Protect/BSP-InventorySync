# BSP-InventorySync

A server-side Forge mod (Minecraft 1.20.1) for the Build Secure Protect (BSP) network. It syncs each player's inventory and per-player data between the BSP servers through their shared MySQL database, with BSP-Core's Shatter Totems and BSP-Teleport's server transfers taken into account.

Companion to [BSP-Core](https://github.com/Build-Secure-Protect/BSP-Core), which it requires. BSP-Teleport, Curios, Sophisticated Backpacks and Waystones are optional.

## Status

Started 2026-10-17. Storage, data modules, the totem rules, the arrival handshake and the MySQL and Redis stores are built and covered by 28 headless GameTests. Nothing has run on a real server yet.

## Configuring

Server config: `<world>/serverconfig/bsp_inventorysync-server.toml`. The mod is off until `storage.mode = "mysql"`. By default it connects to the database named in BSP-Core's own `storage.*` settings and uses BSP-Core's `storage.serverId`, keeping its own tables under `bsp_is_`. Turn on `redis.enabled` with the network's Redis to make server switches instant; without Redis the switch waits only for the source server's save to land in MySQL. See `docs/API.md` for how other mods hook in.

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
