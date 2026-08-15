# OMWH

OMWH adds `/home` and `/spawn` to Fabric servers without turning them into cross-dimension warps.

- `/home` returns you to a valid bed or respawn anchor in your current dimension.
- `/spawn` finds safe ground near the current dimension's spawn.
- Mounts, vehicles, and their passengers travel with you when there is room.
- Server owners can configure cooldowns, command names, messages, sounds, and particles.
- Players do not need to install OMWH on their clients.

Built by [PyreHaven](https://pyrehaven.xyz).

## Teleport rules

`/home` asks Minecraft for the exact bed, respawn-anchor, or forced-respawn destination it would normally use, without consuming an anchor charge. The destination must be in the current dimension. An unmounted player uses that vanilla transition directly; a mounted group moves only when the root vehicle fits at that exact position with OMWH's clearance margin. OMWH does not search for another home position or place vehicles above beds.

`/spawn` performs one bounded, deterministic nearest-first search around the current dimension's spawn. A candidate needs safe support and enough collision-free space for the exact translated bounding box of every attached entity.

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 26.2.
2. Install [Fabric API](https://modrinth.com/mod/fabric-api).
3. Put the OMWH jar in the `mods` folder.
4. Start the server or game once to create `config/omwh.json`.

OMWH also works in singleplayer.

## Configuration

Edit `config/omwh.json` after the first launch.

| Field | Default | Purpose |
|---|---:|---|
| `homeCommand` | `"home"` | Name of the home command |
| `spawnCommand` | `"spawn"` | Name of the spawn command |
| `enableRegularCooldown` | `true` | Enable the normal teleport cooldown |
| `regularCooldownSeconds` | `30` | Cooldown between normal teleports |
| `enablePvpCooldown` | `true` | Enable the cooldown after PvP |
| `pvpCooldownSeconds` | `45` | Cooldown after PvP |
| `enableDamageCooldown` | `true` | Enable the cooldown after other damage |
| `damageCooldownSeconds` | `10` | Cooldown after other damage |
| `joinCooldownSeconds` | `30` | Cooldown after joining |
| `playTeleportSound` | `true` | Play a sound after teleporting |
| `spawnTeleportParticles` | `true` | Show particles after teleporting |
| `homeSuccessMessage` | `"§aTeleported to your home!"` | Successful `/home` message |
| `spawnSuccessMessage` | `"§aTeleported to world spawn!"` | Successful `/spawn` message |
| `noHomepointMessage` | `"§cYou don't have a spawn point set!"` | Missing or invalid home message |
| `crossDimensionMessage` | `"§cYou are not powerful enough to bend space between dimensions. Use a portal first, then try again!"` | Cross-dimension `/home` denial |
| `unsafeHomeMessage` | `"§cThere is no safe spot at your home to bring you to."` | Blocked mounted-home message |
| `unsafeSpawnMessage` | `"§cCannot find a safe spawn location - please contact an administrator!"` | No safe spawn candidate message |
| `pvpCooldownMessage` | `"§cYou were recently in combat! Please wait {time} seconds before teleporting."` | PvP cooldown message |
| `damageCooldownMessage` | `"§cYou recently took damage! Please wait {time} seconds before teleporting."` | Other-damage cooldown message |
| `joinCooldownMessage` | `"§cYou must wait {time} seconds after joining before teleporting!"` | Join cooldown message |
| `regularCooldownMessage` | `"§cYou recently teleported! Please wait {time} seconds before trying again."` | Normal teleport cooldown message |

Set a cooldown duration to `0` to disable it. Setting `enableRegularCooldown`, `enablePvpCooldown`, or `enableDamageCooldown` to `false` disables that cooldown regardless of its duration.

## Links

- [Download on Modrinth](https://modrinth.com/mod/omwh)
- [Issues and suggestions](https://github.com/ff-tech-xyz/omwh/issues)
- [PyreHaven Discord](https://discord.gg/tZ6Hx2ETA3)

## License

[MIT](LICENSE)
