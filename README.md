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

`/spawn` takes you to the spawn destination selected for your current world. Server owners can control Overworld, Nether, End, and modded-dimension spawn travel separately.

Outside the End, OMWH checks already-loaded positions in expanding hollow 3D cubes around spawn, up to 48 blocks on every axis, for solid ground and enough clear space for the player or mounted vehicle. Each new cube checks only its outer surface; previously checked interior positions aren't repeated. Admission, mounted-group validation, search, final safety checks, and completion share one server-wide allowance for OMWH's bounded block, collision, passenger, effect, and bookkeeping operations. If synchronous admission would exceed the current tick's allowance, OMWH asks the player to try again; longer searches resume across ticks. OMWH also cancels a pending result if the player or mounted group changes before it completes. Normal mounted safety checks support roots up to 14 blocks wide with 16 blocks of clear height; larger modded vehicles are reported as too large before OMWH scans their full bounds. Nether spawn uses Minecraft's normal Overworld-to-Nether coordinate scaling and world-border limits. In the End, Minecraft recreates and chooses the normal portal arrival platform, then OMWH checks that exact destination for environmental danger and enough room without searching elsewhere.

### Bringing vehicles and friends

When you use `/home` or `/spawn` while mounted, OMWH moves the vehicle or mount and everyone riding it. This includes boats, minecarts, horses, other mounted entities, and player passengers.

The whole group stays attached during the teleport. Passenger trees are limited to 64 entities so validation and completion remain bounded; OMWH stops consuming the tree as soon as a 65th member is found, and larger groups are refused before movement with a clear message. If the destination does not have enough room, OMWH refuses the teleport rather than separating the riders or placing the vehicle inside blocks.

### Force commands

Server owners can enable `/home force` and `/spawn force`. Any player who can use `/home` or `/spawn` can use its force form. Force skips only the destination safety check; cooldowns, missing homes, dimension routing, disabled spawn destinations, unavailable worlds, and teleport failures still apply.

When a normal command refuses an unsafe destination, OMWH shows the configured unsafe message. If force is enabled, it also tells the player which configured command to use with `force`.

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

Players do not need to install OMWH on their clients. OMWH also works in singleplayer. Immediate accepted teleports prepare a fixed area of 25 destination chunks. That caps how many chunks OMWH requests; Minecraft's terrain-generation time is not a fixed-duration operation.

## Configuration

Edit `config/omwh.json` after the first launch, then restart the server. OMWH does not reload configuration while the server is running. See [CONFIGURATION.md](CONFIGURATION.md) for the complete field, message, placeholder, color-code, and validation reference.

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
| `enableForceOverride` | `true` | Enable `/home force` and `/spawn force` for players who can use the parent commands |
| `enableCrossDimensionTeleport` | `true` | Allow valid cross-dimension homes and eligible spawn travel |
| `enableOverworldSpawn` | `true` | Allow the Overworld spawn destination |
| `enableNetherSpawn` | `false` | Allow the Nether spawn destination |
| `enableEndSpawn` | `false` | Allow the End spawn destination |
| `enableModdedDimensionSpawn` | `false` | Allow `/spawn` to stay in the current modded dimension |
| Message fields | See [CONFIGURATION.md](CONFIGURATION.md) | Every player-facing command outcome, including success, safety, passenger, partial, and internal-error feedback |

Set a cooldown duration to `0` to disable it. Existing configuration files can omit newer fields; OMWH uses the default value for anything missing. This means omitted Nether, End, and modded-dimension spawn settings are disabled. Unknown fields are ignored. Known settings must use the documented JSON type, and invalid command names, negative cooldowns, malformed JSON, or other invalid known values stop startup with a clear configuration error instead of being silently ignored.

## Links

- [Download on Modrinth](https://modrinth.com/mod/omwh)
- [Issues and suggestions](https://github.com/ff-tech-xyz/omwh/issues)
- [PyreHaven Discord](https://discord.gg/tZ6Hx2ETA3)

## License

[MIT](LICENSE)

Made by PyreHaven. Find out more about us at [PyreHaven.xyz](https://pyrehaven.xyz).
