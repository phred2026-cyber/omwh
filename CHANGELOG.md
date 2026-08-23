# Changelog

## 1.2.0

OMWH 1.2.0 expands dimension support, gives players an optional safety override, and makes mounted teleports and destination checks more predictable. This entry compares 1.2.0 with the official 1.1.3 release.

### Added

- `/home` can now use a valid bed, charged respawn anchor, or server-set respawn point in another dimension. Cross-dimension homes are enabled by default through `enableCrossDimensionTeleport`.
- Added `/home force` and `/spawn force`. They are enabled by default and available to every player who can run the parent command. Force skips destination safety and vehicle-size checks only; it does not bypass cooldowns, missing homes, dimension routing, disabled spawn destinations, unavailable worlds, or teleport failures.
- Added separate spawn settings for each dimension type. The exact defaults are:

  | Destination | Default |
  |---|---:|
  | Overworld | Enabled |
  | Nether | Disabled |
  | End | Disabled |
  | Modded dimensions | Disabled |

  When the current dimension's destination is disabled, `/spawn` may fall back to Overworld spawn only when both cross-dimension teleporting and Overworld spawn are enabled.
- All command feedback can now be configured, including success, cooldown, safety, disabled-dimension, pending-search, passenger-limit, group-verification, and internal-error messages. Messages support `&` and `§` color codes plus the documented `{time}`, `{command}`, `{player}`, `{destination}`, and conditional `{forceGuidance}` placeholders.

### Changed

- Nether `/spawn` now applies Minecraft's normal 1:8 Overworld-to-Nether coordinate scaling and clamps the result to the Nether world border. For example, Overworld spawn at `(2400, 64, -1600)` maps near `(300, 64, -200)` in the Nether instead of using the raw Overworld X/Z values.
- End `/spawn` now uses Minecraft's vanilla End arrival. Minecraft recreates the obsidian platform and supplies the normal arrival height, west-facing orientation, portal sound, and related transition behavior. Normal `/spawn` checks that exact destination; force keeps the vanilla transition but skips OMWH's placement checks.
- Normal `/spawn` searches loaded chunks only, within 48 blocks of the spawn anchor on each axis. It never loads or generates terrain, and longer searches resume across server ticks. This protects the server thread, but it also means `/spawn` may refuse a destination that 1.1.3 could reach. The relevant spawn chunks must be loaded when the command runs; pregeneration alone is not enough.
- A second normal `/spawn` is refused while that player's search is pending. `/home` or `/spawn force` cancels the pending search and continues. OMWH also cancels a pending search when the player disconnects or respawns, or when their dimension, vehicle geometry, or passenger tree changes.
- Passenger groups are limited to 64 entities. Larger groups are refused before movement. Normal mounted searches also refuse roots wider than 14 blocks or requiring more than 16 blocks of clear height rather than scanning beyond those bounds.
- Cooldown state now clears when a player disconnects. This also clears the PvP cooldown: a player who reconnects receives the join cooldown instead. Servers concerned about combat logging should compare `joinCooldownSeconds` with `pvpCooldownSeconds`.
- OMWH no longer logs an INFO line for every `/spawn` use.
- The built-in `unsafeHomeMessage` and `unsafeSpawnMessage` now read `It is not safe to teleport here.` with force guidance when force is enabled. Existing configuration files are not rewritten. A configured old message remains unchanged; the new text applies to newly generated configs and existing configs that omit those fields.

### Fixed

- Mounted teleports now refresh Minecraft's tracking for the moved vehicle and passenger group, so boats, minecarts, horses, riders, and nested passengers remain visible and usable without relogging.
- Normal `/home` now rejects fluids and hazardous blocks in the player's or vehicle's destination space and supporting layer before anything moves.
- Hazard checks now match exact blocks. Blocks such as fire coral, or modded blocks whose names merely contain `fire`, `lava`, or `magma`, are no longer treated as hazards by name alone.
- Self-inflicted damage now uses the normal damage cooldown instead of the PvP cooldown.
- When event cooldowns overlap, the longer unexpired restriction wins. A shorter damage or join restriction can no longer replace a longer PvP restriction.
- If Minecraft starts moving a group but OMWH cannot verify every passenger afterward, players are told to check the group and the regular cooldown starts. A refusal before movement still starts no regular cooldown.

### Upgrade notes for server owners

- OMWH has no permission nodes. All player command sources can run the configured base commands, and any player who can run a base command can use its `force` child while `enableForceOverride` is enabled. Force can be toggled globally, but it cannot be permissioned separately.
- Existing configs are not rewritten. New fields omitted from an existing file use their built-in defaults, including Nether, End, and modded-dimension spawn being disabled.
- Configuration is loaded only during startup. Restart the server after editing `config/omwh.json`; OMWH has no reload command.
- Known config fields now require the documented JSON type and valid value. Quoted numbers or booleans, `null`, negative cooldowns, invalid or duplicate command names, malformed JSON, and a non-object root stop startup with a specific configuration error. Omitted fields still use defaults, and unknown fields are still ignored.
- Remove the old OMWH jar before installing 1.2.0. The Java package and Fabric entrypoint moved internally, but the mod ID remains `omwh`; leaving old and new jars together causes Fabric Loader to stop on a duplicate mod ID.
- Check that the relevant spawn chunks stay loaded after upgrading. The new search cannot use pregenerated chunks once Minecraft has unloaded them.

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
