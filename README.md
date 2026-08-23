# OMWH

OMWH is a Minecraft server mod that adds `/home` and `/spawn` without creating permanent warp points.

There is no `/sethome` command. `/home` uses the respawn point Minecraft already stores for you, such as a bed or charged respawn anchor. `/spawn` brings you to the spawn destination selected for your current dimension.

OMWH can bring your vehicle, mount, friends, and other passengers along. It also has configurable cooldowns for normal command use, damage, PvP, and joining the server.

## Commands

### `/home`

`/home` returns you to your valid Minecraft respawn point. Missing, destroyed, blocked, or unsafe homes are refused instead of sending you somewhere else.

Cross-dimension homes are enabled by default. Server owners can disable them with `enableCrossDimensionTeleport`.

### `/spawn`

`/spawn` uses the destination allowed for the player's current dimension. Overworld spawn is enabled by default; Nether, End, and modded-dimension spawn destinations are disabled by default. A disabled dimension may fall back to Overworld spawn only when both cross-dimension teleporting and Overworld spawn are enabled.

Nether destinations use Minecraft's normal coordinate scaling. End destinations use the vanilla End arrival platform. For normal Overworld, Nether, and modded-dimension travel, OMWH searches already-loaded chunks within 48 blocks of the spawn anchor on each axis. It never loads or generates terrain, so `/spawn` may refuse if the relevant chunks are not loaded. Pregeneration alone is not enough once Minecraft unloads them.

### Vehicles and friends

Use `/home` or `/spawn` while mounted and OMWH moves the root vehicle or mount with its full passenger group. This includes boats, minecarts, horses, other mounted entities, and player passengers.

Passenger groups are limited to 64 entities. If the destination cannot fit the vehicle, OMWH refuses the teleport rather than separating riders or placing the group inside blocks.

### Force commands

`/home force` and `/spawn force` are enabled by default. Any player who can use the parent command can use its force form.

Force skips only destination safety and vehicle-size checks. Cooldowns, missing homes, dimension routing, disabled spawn destinations, unavailable worlds, and teleport failures still apply. Server owners can turn force off globally, but OMWH has no permission nodes and cannot restrict force separately from the parent command.

## Cooldowns

Server owners can configure:

- A regular cooldown after `/home` or `/spawn` moves the player or group
- A PvP cooldown after a player takes damage from another player
- A damage cooldown after a player takes other damage, including self-damage
- A join cooldown after connecting to the server

When cooldowns overlap, the longer event restriction wins. Cooldown state is kept in memory and clears when a player disconnects, so reconnecting also clears a PvP cooldown and replaces it with the configured join cooldown.

Self-damage uses the normal damage cooldown, not the PvP cooldown.

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 26.2.
2. Install [Fabric API](https://modrinth.com/mod/fabric-api) on the server.
3. Remove any older OMWH jar from the server's `mods` folder, then put the new jar there. Keeping both versions causes Fabric Loader to stop on a duplicate mod ID.
4. Start the server once to create `config/omwh.json`.

Players do not need OMWH on their clients. OMWH also works in singleplayer.

## Configuration

Edit `config/omwh.json`, then restart the server. OMWH reads configuration only during startup and has no reload command.

Existing files are not rewritten when new settings are added. Omitted fields use their built-in defaults. Known fields must use the documented JSON type and valid values; invalid known settings or malformed JSON stop startup rather than being silently replaced.

See [CONFIGURATION.md](CONFIGURATION.md) for all settings, defaults, messages, color codes, placeholders, and validation rules. Detailed command and search behavior is documented in [FEATURES.md](FEATURES.md).

## Server owners should know

- OMWH has no permission nodes. All player sources can use the configured base commands.
- Force is enabled for those players by default and can only be toggled globally.
- Cross-dimension `/home` is enabled by default.
- Nether, End, and modded-dimension spawn destinations are disabled by default.
- Cooldowns and pending searches clear when a player disconnects.
- Normal `/spawn` searches loaded chunks only within 48 blocks of its anchor and may refuse when the relevant spawn chunks are not loaded.

## Links

- [Download on Modrinth](https://modrinth.com/mod/omwh)
- [Issues and suggestions](https://github.com/ff-tech-xyz/omwh/issues)
- [PyreHaven Discord](https://discord.gg/tZ6Hx2ETA3)

## License

[MIT](LICENSE)

Made by PyreHaven. Find out more about us at [PyreHaven.xyz](https://pyrehaven.xyz).
