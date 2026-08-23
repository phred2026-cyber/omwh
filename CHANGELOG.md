# Changelog

## 1.2.0 - 2026-08-21

- `/home` can now use a valid bed or respawn anchor in another dimension when the server allows cross-dimension travel.
- Server owners can control Overworld, Nether, and End `/spawn` destinations separately.
- Server owners can also allow or disable `/spawn` in modded dimensions. When disabled, it can send players to Overworld spawn only if both Overworld spawn and cross-dimension travel are enabled.
- Added `/home force` and `/spawn force` for every player who can use the normal commands. Force bypasses destination safety, but it still obeys cooldowns, home availability, dimension and spawn settings, world availability, and teleport failures. When a normal command refuses an unsafe destination, it points players to the configured force command if force is enabled.
- End `/spawn` now asks Minecraft for the normal End-portal arrival, including platform regeneration, arrival height, facing direction, sound, and other portal behavior. Normal use then checks that exact platform destination for danger and enough room; force skips those checks.
- Normal `/home` now refuses fluids and exact hazardous blocks at the destination before moving the player or mounted group.
- `/spawn` now starts from the Overworld's saved world-spawn block in every dimension. Nether X/Z coordinates use Minecraft's normal portal scaling and world-border limits, while Overworld and modded dimensions keep the saved coordinates unchanged.
- Normal `/spawn` checks expanding hollow three-dimensional cubes up to 48 blocks from the anchor on every axis. Each new shell checks only its outer surface, so positions inside earlier cubes aren't scanned again. Large searches read loaded terrain under one server-wide tick budget, cancel if the player or mounted group changes, and recheck the final destination before moving anything.
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
