# OMWH — On My Way Home

OMWH adds server-side `/home` and `/spawn` commands that use Minecraft's own saved-home and world-spawn destinations. Players can bring mounted vehicles and passengers without installing anything on their client.

## Commands

- `/home` goes to your bed or charged respawn anchor. Cross-dimension homes work when enabled.
- `/spawn` goes to the configured destination for your current dimension, or to Overworld spawn when fallback routing is enabled.
- `/home force` and `/spawn force` skip OMWH's destination-safety and vehicle-size checks when the server owner enables force commands. They do not bypass cooldowns, missing destinations, disabled dimension routes, unavailable worlds, or teleport failures.

Normal teleports check build height, world borders, support, fluids, hazards, collision, and the full mounted footprint. If terrain is not ready, OMWH spreads preparation and searching across server ticks instead of doing an unbounded burst of work. A force command still prepares Minecraft's destination terrain before moving anything.

## Cooldowns and feedback

Server owners can configure regular, PvP, damage, and join cooldown durations. A duration of `0` disables that cooldown; join follows this rule without a separate enable switch. Messages, command names, sound, particles, force access, cross-dimension homes, and per-dimension spawn routing are configurable in `config/omwh.json`.

See [BEHAVIOR.md](BEHAVIOR.md) for player and server-owner behavior, and [CONFIGURATION.md](CONFIGURATION.md) for every field, default, placeholder, and validation rule.

A small server-owner override can contain only the fields being changed. For example, this keeps `/spawn` in the Nether and disables force guidance while leaving all other fields at their defaults:

```json
{
  "enableNetherSpawn": true,
  "forceGuidanceMessage": ""
}
```

## Requirements

- Minecraft 26.2
- Fabric Loader 0.19.3 or newer
- Fabric API
- Java 21 or newer

Install OMWH and Fabric API in the server's `mods` directory. The mod is server-side; clients do not need it.

## Building

```bash
./gradlew clean regressionTest build
```

The dependency-free Java regression tasks are the canonical behavior suite.

## Links

- [PyreHaven](https://pyrehaven.xyz)
- [Download on Modrinth](https://modrinth.com/mod/omwh)
- [PyreHaven Discord](https://discord.gg/tZ6Hx2ETA3)
- [Source](https://github.com/ff-tech-xyz/omwh)
- [Issues](https://github.com/ff-tech-xyz/omwh/issues)

## License

[CC0 1.0 Universal](LICENSE)
