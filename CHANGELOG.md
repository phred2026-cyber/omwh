# Changelog

## 1.2.0 - 2026-08-21

- `/home` can now use a valid bed or respawn anchor in another dimension when the server allows cross-dimension travel.
- Server owners can control Overworld, Nether, and End `/spawn` destinations separately.
- Added `/home force` and `/spawn force` for every player who can use the normal commands. Force bypasses destination safety, but it still obeys cooldowns, home availability, dimension and spawn settings, world availability, and teleport failures. When a normal command refuses an unsafe destination, it points players to the configured force command if force is enabled.
- End `/spawn` no longer rebuilds the obsidian arrival platform by default. Server owners can enable `rebuildEndPlatform` when they want Minecraft's platform recreated.
- Normal `/home` now refuses fluids and exact hazardous blocks at the destination before moving the player or mounted group.
- Nether `/spawn` now keeps the world spawn X/Z but searches from the Nether generator's lava-sea level when the saved spawn belongs to the Overworld. It checks the full 64-block shore plane before its bounded nearby-height search, so an unrelated Overworld Y-coordinate or the old vertical budget cannot hide safe ground.
- Vehicles, riders, and nested passengers stay together during allowed same-dimension and cross-dimension teleports. After a verified move, OMWH refreshes Minecraft's entity tracking so boats and riders remain visible and usable without relogging. If Minecraft reports a possible partial move, OMWH warns the group and applies the normal cooldown before another attempt.
- Invalid known configuration values stop startup with a specific error. Existing files may omit newer fields, but quoted booleans or numbers, negative cooldowns, invalid command names, and malformed JSON must be corrected.
- Replace the old OMWH jar when updating. Leaving both jars in the `mods` folder causes Fabric Loader to reject the duplicate mod ID.

## 1.1.3

- Fixed `/home` moving mounted players and vehicles upward, sometimes onto roofs, when the home did not have enough room.
- Fixed mounted `/home` and `/spawn` teleports sometimes leaving clients out of sync with vehicles and passengers.
- `/spawn` now chooses nearby safe ground with enough support and clear space for the player or vehicle.

## 1.1.0

- Added `config/omwh.json` for cooldowns, command names, messages, sounds, and particles.
- Added Minecraft color-code and `{time}` support to configurable messages.

## 1.0.0

- Added `/home` and `/spawn` with PvP, damage, join, and regular cooldowns.
- Added teleport support for vehicles and mounts.
