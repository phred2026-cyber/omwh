# Changelog

## Unreleased

- `/home` can now use a valid bed or respawn anchor in another dimension when the server allows cross-dimension travel.
- Server owners can control Overworld, Nether, and End `/spawn` destinations separately.
- Added optional `/home --force` and `/spawn --force` commands for bypassing destination safety without bypassing cooldowns or command rules.
- Normal `/home` now refuses fluids and hazards at the destination before moving the player or mounted group.
- Vehicles, riders, and nested passengers stay together during allowed same-dimension and cross-dimension teleports.
- Existing configuration files keep working. New settings use their default values when omitted.

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
