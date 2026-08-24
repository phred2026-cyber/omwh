# OMWH behavior

This document describes what players and server owners can rely on. Configuration field details are in [CONFIGURATION.md](CONFIGURATION.md).

## Player behavior

### `/home`

`/home` uses Minecraft's saved respawn configuration. A missing or invalid bed or anchor is not replaced with world spawn. If OMWH cannot identify the player's current world, it reports that the current world is unavailable. If the saved home's world is unavailable, the home is treated as missing. Neither outcome suggests `force`, because force cannot make a world available.

Normal `/home` asks Minecraft to resolve the saved bed, bunk bed, or charged respawn anchor, then checks the player or mounted root at that exact result. An uncovered ordinary bed may use the single above-bed fallback when the root vehicle's geometry fits there. Passenger hitboxes do not enlarge that footprint. Covered beds and bunk beds do not gain that fallback.

### `/spawn`

`/spawn` follows the enabled route for the player's current dimension. Normal non-End routes search a deterministic origin-first sequence for a safe position. End routing uses Minecraft's End-arrival result and checks that exact destination rather than searching away from it.

A force command skips OMWH's safety and mounted-size policy only. It does not create missing homes or world spawns, enable disabled dimensions, bypass cooldowns, repair unavailable worlds, or hide teleport failures.

### Mounted groups

OMWH moves one root vehicle and its passenger tree as a unit. The root's live geometry determines destination fit. Oversized or changing groups fail closed. A partial movement warning tells the player to check the group when Minecraft began moving it but OMWH could not verify every passenger.

### Cooldowns

Regular cooldown starts after a successful teleport. Join cooldown starts when the player joins. Damage and PvP cooldowns start only after damage is accepted and applied. Disabling the PvP cooldown does not convert player-caused damage into the ordinary damage cooldown. A configured duration of `0` disables that cooldown. Join deliberately uses the same duration rule instead of a separate `enableJoinCooldown` switch.

## Server-owner behavior

Normal searches and terrain preparation are bounded and may continue across server ticks. `/home`, `/spawn`, disconnect, and respawn cancellation share one scheduler and release retained chunk tickets through the same cleanup accounting. A stale request cannot move a player after their world, root vehicle, passenger tree, or relevant destination state changes.

Non-End spawn search retains at most 64 search chunks. An accepted spawn or immediate destination prepares the fixed five-by-five destination area, at most 25 chunks, before movement. Home preparation loads only the chunks required by Minecraft's saved-respawn reads and OMWH's exact safety footprint.

The saved-respawn terrain plan and End-arrival behavior are coupled to Minecraft 26.2. Contributors updating Minecraft must compare the mapped vanilla resolution paths before changing those constants or traversal rules.

## Messages

Messages accept Minecraft color codes with either `§` or `&`. Write `&&` for a literal ampersand; other `&` characters continue to start color codes. For example, `"&aHome && safe"` displays as green `Home & safe`.

Only the placeholders listed for a field in [CONFIGURATION.md](CONFIGURATION.md) are expanded. The sole exception is `{forceGuidance}`: OMWH always consumes that token, replacing it with guidance only for the unsafe and vehicle-too-large outcomes while force is enabled, and with an empty string otherwise. An empty `forceGuidanceMessage` therefore disables guidance. Other unknown placeholder-like text remains unchanged.
