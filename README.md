# OMWH

OMWH is a Minecraft server mod that adds `/home` and `/spawn` commands without creating permanent warp points.

There is no `/sethome` command. `/home` takes you wherever your Minecraft respawn is set, whether that is a bed or a respawn anchor. `/spawn` brings you to world spawn.

You can even bring friends with you when you use an OMWH command. If you are riding a vehicle or mount, such as a boat, minecart, or horse, it travels with you. Any other entities or players riding it come along too.

OMWH also has configurable cooldowns for normal command use, damage, PvP, and joining the server.

## Commands

### `/home`

`/home` uses the respawn point Minecraft already stores for you. It does not create a separate home or add a `/sethome` command.

A valid home can be:

- A bed
- A charged respawn anchor
- A respawn point set by another server system

Server owners can allow valid homes in other dimensions. Missing, destroyed, blocked, or unsafe homes are refused instead of sending the player somewhere else.

### `/spawn`

`/spawn` takes you to the spawn destination selected for your current world. Server owners can control Overworld, Nether, and End spawn travel separately.

Outside the End, OMWH checks a bounded set of already-loaded positions near spawn for solid ground and enough clear space for the player or mounted vehicle. In the End, it uses the existing arrival area by default; server owners can explicitly allow OMWH to rebuild Minecraft's obsidian platform.

### Bringing vehicles and friends

When you use `/home` or `/spawn` while mounted, OMWH moves the vehicle or mount and everyone riding it. This includes boats, minecarts, horses, other mounted entities, and player passengers.

The whole group stays attached during the teleport. If the destination does not have enough room, OMWH refuses the teleport rather than separating the riders or placing the vehicle inside blocks.

### Force commands

Server owners can enable `/home --force` and `/spawn --force`. Only operators with gamemaster-level command permission can use them. These forms skip the normal destination safety check, but they do not bypass cooldowns, missing homes, disabled dimensions, or other command rules.

## Cooldowns

Server owners can configure:

- A regular cooldown after a successful `/home` or `/spawn`
- A PvP cooldown after combat with another player
- A damage cooldown after other incoming damage
- A join cooldown after connecting to the server

Cooldowns can be changed or disabled in `config/omwh.json`.

Damage cooldowns start when OMWH allows an incoming damage event. Another mod can still cancel that damage afterward.

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 26.2.
2. Install [Fabric API](https://modrinth.com/mod/fabric-api) on the server.
3. Remove any older OMWH jar from the server's `mods` folder, then put the new jar there. Keeping both versions causes Fabric Loader to stop on a duplicate mod ID.
4. Start the server once to create `config/omwh.json`.

Players do not need to install OMWH on their clients. OMWH also works in singleplayer.

## Configuration

Edit `config/omwh.json` after the first launch.

| Field | Default | Purpose |
|---|---:|---|
| `homeCommand` | `"home"` | Name of the home command |
| `spawnCommand` | `"spawn"` | Name of the spawn command |
| `enableRegularCooldown` | `true` | Enable the cooldown after a successful teleport |
| `regularCooldownSeconds` | `30` | Cooldown between successful command uses |
| `enablePvpCooldown` | `true` | Enable the PvP cooldown |
| `pvpCooldownSeconds` | `45` | Cooldown after incoming PvP damage |
| `enableDamageCooldown` | `true` | Enable the non-player damage cooldown |
| `damageCooldownSeconds` | `10` | Cooldown after other incoming damage |
| `joinCooldownSeconds` | `30` | Cooldown after joining the server |
| `playTeleportSound` | `true` | Play the teleport sound |
| `spawnTeleportParticles` | `true` | Show teleport particles |
| `enableForceOverride` | `true` | Enable `/home --force` and `/spawn --force` |
| `enableCrossDimensionTeleport` | `true` | Allow valid cross-dimension homes and eligible spawn travel |
| `enableOverworldSpawn` | `true` | Allow the Overworld spawn destination |
| `enableNetherSpawn` | `true` | Allow the Nether spawn destination |
| `enableEndSpawn` | `true` | Allow the End spawn destination |
| `rebuildEndPlatform` | `false` | Allow `/spawn` in the End to rebuild Minecraft's obsidian arrival platform |
| Message fields | See config | Messages shown to players; supports Minecraft color codes and `{time}` |

Set a cooldown duration to `0` to disable it. Existing configuration files can omit newer fields; OMWH uses the default value for anything missing. Unknown fields are ignored. Known settings must use the documented JSON type, and invalid command names, negative cooldowns, malformed JSON, or other invalid known values stop startup with a clear configuration error instead of being silently ignored.

## Links

- [Download on Modrinth](https://modrinth.com/mod/omwh)
- [Issues and suggestions](https://github.com/ff-tech-xyz/omwh/issues)
- [PyreHaven Discord](https://discord.gg/tZ6Hx2ETA3)

## License

[MIT](LICENSE)

Made by PyreHaven. Find out more about us at [PyreHaven.xyz](https://pyrehaven.xyz).
