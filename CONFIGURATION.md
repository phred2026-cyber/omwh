# OMWH Configuration

OMWH reads `config/omwh.json` from the Minecraft server directory. On first startup, it creates the file with every built-in default. OMWH reads the file only during startup; there is no reload command. Restart the server after editing it.

## Example

This example keeps the default command names, enables Nether spawn routing, and changes a few messages:

```json
{
  "homeCommand": "home",
  "spawnCommand": "spawn",
  "enableForceOverride": true,
  "enableCrossDimensionTeleport": true,
  "enableOverworldSpawn": true,
  "enableNetherSpawn": true,
  "enableEndSpawn": false,
  "enableModdedDimensionSpawn": false,
  "regularCooldownSeconds": 30,
  "homeSuccessMessage": "&aWelcome home!",
  "unsafeHomeMessage": "&cThat home is unsafe.{forceGuidance}",
  "forceGuidanceMessage": "\n&eUse /{command} force to teleport anyway."
}
```

JSON uses `\n` for a newline inside a message. A literal backslash in JSON must be escaped as `\\`.

## Commands, cooldowns, effects, and routing

| Field | Type | Default | Behavior |
|---|---|---|---|
| `homeCommand` | string | `"home"` | Literal command name for the home command. |
| `spawnCommand` | string | `"spawn"` | Literal command name for the spawn command. |
| `enableRegularCooldown` | boolean | `true` | Enables the cooldown recorded after a successful or possibly partial teleport. |
| `regularCooldownSeconds` | integer | `30` | Regular cooldown duration. `0` disables this cooldown. |
| `enablePvpCooldown` | boolean | `true` | Enables the cooldown after OMWH allows incoming player-versus-player damage. |
| `pvpCooldownSeconds` | integer | `45` | PvP cooldown duration. `0` disables this cooldown. |
| `enableDamageCooldown` | boolean | `true` | Enables the cooldown after OMWH allows other incoming damage. |
| `damageCooldownSeconds` | integer | `10` | Other-damage cooldown duration. `0` disables this cooldown. |
| `joinCooldownSeconds` | integer | `30` | Cooldown after joining. `0` disables it. |
| `playTeleportSound` | boolean | `true` | Plays the teleport sound before an accepted teleport mutation. |
| `spawnTeleportParticles` | boolean | `true` | Spawns portal particles before an accepted teleport mutation. |
| `enableForceOverride` | boolean | `true` | Registers `force` under both configured commands. Force skips destination safety only. |
| `enableCrossDimensionTeleport` | boolean | `true` | Allows valid cross-dimension homes and eligible fallback routing to Overworld spawn. |
| `enableOverworldSpawn` | boolean | `true` | Allows Overworld spawn as a current or selected destination. |
| `enableNetherSpawn` | boolean | `false` | Allows `/spawn` to use a Nether destination while the player is in the Nether. |
| `enableEndSpawn` | boolean | `false` | Allows `/spawn` to use the vanilla End arrival destination while the player is in the End. |
| `enableModdedDimensionSpawn` | boolean | `false` | Allows `/spawn` to remain in the player's current modded dimension. |

### Dimension routing

Overworld spawn and cross-dimension teleporting are enabled by default. Nether, End, and modded-dimension spawn destinations are disabled by default, including when those fields are omitted from an older configuration.

In the Overworld, disabling `enableOverworldSpawn` denies `/spawn`. In the Nether or End, an enabled dimension-specific setting keeps the destination in that dimension even if cross-dimension teleporting is disabled. If that setting is disabled, OMWH routes to Overworld spawn only when both `enableCrossDimensionTeleport` and `enableOverworldSpawn` are enabled; otherwise it denies the command. Modded dimensions follow the same stay-or-fall-back rule through `enableModdedDimensionSpawn`.

`enableCrossDimensionTeleport` also controls whether `/home` may use a valid saved respawn point in another dimension. It does not make a missing home valid.

Force is available to every player who can use the parent command when `enableForceOverride` is enabled. It bypasses destination safety and vehicle-size checks only. It does not bypass cooldowns, missing homes, dimension routing, unavailable worlds, command-source checks, or teleport failures.

## Messages

All message and destination-label fields are JSON strings. Both `&` and `§` Minecraft color codes are accepted. OMWH translates ampersands to section signs at the final send boundary. Use `\n` in JSON when a message should continue on a new line.

| Field | Default | Available placeholders |
|---|---|---|
| `homeSuccessMessage` | `§aTeleported to your home!` | `{command}` |
| `spawnSuccessMessage` | `§aTeleported to world spawn!` | `{command}` |
| `noHomepointMessage` | `§cYou don't have a spawn point set!` | `{command}` |
| `crossDimensionMessage` | `§cYou are not powerful enough to bend space between dimensions. Use a portal first, then try again!` | `{command}` |
| `unsafeHomeMessage` | `§cIt is not safe to teleport here.{forceGuidance}` | `{command}`, `{forceGuidance}` |
| `unsafeSpawnMessage` | `§cIt is not safe to teleport here.{forceGuidance}` | `{command}`, `{forceGuidance}` |
| `pvpCooldownMessage` | `§cYou were recently in combat! Please wait {time} seconds before teleporting.` | `{time}` |
| `damageCooldownMessage` | `§cYou recently took damage! Please wait {time} seconds before teleporting.` | `{time}` |
| `joinCooldownMessage` | `§cYou must wait {time} seconds after joining before teleporting!` | `{time}` |
| `regularCooldownMessage` | `§cYou recently teleported! Please wait {time} seconds before trying again.` | `{time}` |
| `internalErrorMessage` | `§cInternal error executing /{command}. Check server log.` | `{command}` |
| `vehicleTooLargeMessage` | `§cYour vehicle is too big. Dismount and try again.{forceGuidance}` | `{command}`, `{forceGuidance}` |
| `forceGuidanceMessage` | `\n§eUse /{command} force to teleport anyway.` | `{command}` |
| `partialTeleportMessage` | `§eTeleport may have partially completed, but OMWH could not verify every passenger attachment. Check your group before moving again.` | `{command}` |
| `spawnDisabledMessage` | `§cSpawn teleporting is disabled for this dimension.` | `{command}` |
| `spawnPendingMessage` | `§eA /{command} safety search is already in progress.` | `{command}` |
| `spawnAnchorChangedMessage` | `§cWorld spawn changed while OMWH was checking safety. Please try /{command} again.` | `{command}` |
| `busyMessage` | `§cOMWH reached its server work limit for this tick. Please try /{command} again.` | `{command}` |
| `passengerTreeTooLargeMessage` | `§cYour passenger group is too large for OMWH to teleport safely.` | `{command}` |
| `currentWorldUnavailableMessage` | `§cCannot determine your current world.` | `{command}` |
| `worldSpawnUnavailableMessage` | `§cCannot determine world spawn.` | `{command}` |
| `passengerNotificationMessage` | `§e{player} teleported you with their vehicle to {destination}.` | `{command}`, `{player}`, `{destination}` |
| `homePassengerDestination` | `their home` | None; inserted as `{destination}` for home passenger notifications. |
| `spawnPassengerDestination` | `spawn` | None; inserted as `{destination}` for spawn passenger notifications. |

`{forceGuidance}` becomes `forceGuidanceMessage` only when force is enabled; otherwise it becomes an empty string. For unsafe and vehicle-too-large outcomes, OMWH appends `forceGuidanceMessage` when an older configured message does not contain `{forceGuidance}`. This preserves force guidance for existing configuration files. `forceGuidanceMessage` cannot contain `{forceGuidance}`, which prevents recursive expansion. Placeholders apply only where listed. Other placeholder-like text is left unchanged.

## File handling and validation

- Missing file: OMWH creates a complete default file and starts with those defaults.
- Omitted field: OMWH uses that field's built-in default.
- Unknown field: OMWH ignores it.
- Known field with the wrong JSON type, `null`, an invalid command literal, duplicate command names, or a negative cooldown: startup fails with a configuration error.
- Malformed JSON or a non-object JSON root: startup fails with a configuration error.
- Empty file, unreadable file, or other configuration I/O failure: OMWH logs the problem and uses defaults for that server run.
- Failure to write the first default file: OMWH logs the problem and starts with defaults.

OMWH does not rewrite an existing configuration to add newly introduced fields. Add them only when you want values different from the documented defaults.
