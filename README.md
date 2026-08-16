# OMWH

> **Development rewrite:** artifacts built from this branch are marked `1.1.3-dev`. They are not the playable `1.1.3` release and are not ready for publication or production installation.

OMWH adds configurable `/home` and `/spawn` teleporting to Fabric servers.

- `/home` returns you to a valid bed or respawn anchor, including one in another dimension when the server allows it.
- `/spawn` uses separately controlled Overworld, Nether, and End destinations.
- `/home --force` and `/spawn --force` deliberately use each command's raw destination when you
  accept the risk. They still enforce player-only use, cooldowns, and each command's applicable home
  and dimension rules.
- Mounts, vehicles, and their passengers travel with you when there is room.
- Server owners can configure cooldowns, command names, messages, sounds, and particles.
- Players do not need to install OMWH on their clients.

Built by [PyreHaven](https://pyrehaven.xyz).

## Teleport rules

`/home` uses Minecraft's normal placement around your respawn point, then refuses fluids and unsafe
hazards such as lava at that position. If you are mounted and the vehicle cannot fit beside an
uncovered bed, OMWH may place the group directly above it. Invalid, blocked, or unsafe homes are refused. Cross-dimension homes are accepted only when `enableCrossDimensionTeleport` is enabled.

`/spawn` uses the current dimension's destination when that destination is enabled. Enabled Nether and End destinations win even when cross-dimension teleporting is disabled. If Nether or End spawn is disabled, the command can use Overworld spawn only when both cross-dimension teleporting and Overworld spawn are enabled. A disabled Overworld destination is denied rather than redirected elsewhere.

Outside the End, `/spawn` searches near the selected world's spawn for solid ground with enough clear space for the player or vehicle. When the End is selected, it recreates Minecraft 26.2's built-in obsidian arrival platform and uses the vanilla feet position and orientation instead of searching.

The literal `--force` form skips only destination-safety checks and teleports to the command's raw
destination. Server owners can remove both force forms by setting `enableForceOverride` to `false`.

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
| `enableRegularCooldown` | `true` | Enable the normal post-success cooldown |
| `regularCooldownSeconds` | `30` | Cooldown between normal teleports |
| `enablePvpCooldown` | `true` | Enable cooldowns when OMWH allows incoming player damage to continue |
| `pvpCooldownSeconds` | `45` | Cooldown after incoming PvP allowed by OMWH |
| `enableDamageCooldown` | `true` | Enable cooldowns when OMWH allows incoming non-player damage to continue |
| `damageCooldownSeconds` | `10` | Cooldown after incoming non-player damage allowed by OMWH |
| `joinCooldownSeconds` | `30` | Cooldown after joining |
| `playTeleportSound` | `true` | Play a sound after destination acceptance, before mutation |
| `spawnTeleportParticles` | `true` | Show particles after destination acceptance, before mutation |
| `enableForceOverride` | `true` | Register `/home --force` and `/spawn --force` |
| `enableCrossDimensionTeleport` | `true` | Allow valid cross-dimension homes and eligible Nether/End-to-Overworld spawn routes |
| `enableOverworldSpawn` | `true` | Allow the Overworld spawn destination |
| `enableNetherSpawn` | `true` | Allow Nether spawn while the player is in the Nether |
| `enableEndSpawn` | `true` | Allow the End arrival point and platform while the player is in the End |
| Message fields | See config | Text shown to players; supports Minecraft color codes and `{time}` |

Set a cooldown to `0` to disable it. Damage cooldowns begin in Fabric's `ALLOW_DAMAGE` callback when OMWH allows the incoming event to continue. That callback runs before mitigation; another listener may later cancel the event, and OMWH does not claim the damage was finally applied.

Omitted fields keep their defaults and unknown fields are ignored. Known fields must use the documented JSON type; for example, `30` and `true` are valid, while `"30"` and `"true"` are rejected rather than coerced.

Both `unsafeHomeMessage` and `unsafeSpawnMessage` default to
`§cIt is not safe to teleport here.`.

## Links

- [Download on Modrinth](https://modrinth.com/mod/omwh)
- [Issues and suggestions](https://github.com/ff-tech-xyz/omwh/issues)
- [PyreHaven Discord](https://discord.gg/tZ6Hx2ETA3)

## License

[MIT](LICENSE)
