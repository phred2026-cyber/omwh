# OMWH Feature Contract

## Status

This file is the accepted gameplay contract for the in-development OMWH rewrite. Its `1.1.3-dev` artifact is not a release candidate and must not be published or installed as the playable `1.1.3` release. The eight production owners and four grouped regression files are defined in `ARCHITECTURE.md`.

## Runtime scope

- OMWH is server-authoritative. It must work on dedicated Fabric servers and integrated singleplayer servers.
- Players do not need OMWH on their clients. There is no client-only gameplay owner or client entrypoint.
- Both commands are player commands. Non-player command sources cannot invoke teleport behavior.
- Server owners decide whether valid `/home` destinations and eligible `/spawn` routes may cross dimensions.

## `/home`

- The configured home command defaults to `/home`.
- `/home --force` uses the same authoritative vanilla respawn position while deliberately bypassing
  destination-safety checks. It remains subject to player-source, cooldown, missing-home, and
  dimension-admission rules, and exists only while `enableForceOverride` is enabled.
- A home exists only when Minecraft has a valid configured respawn point for the player: a bed, a charged respawn anchor, or a forced respawn point.
- OMWH asks Minecraft for its normal respawn placement without consuming a respawn-anchor charge.
- A missing or destroyed respawn point is not replaced with world spawn.
- A valid respawn transition into another dimension is accepted only while `enableCrossDimensionTeleport` is enabled. Otherwise it is refused with the configured cross-dimension message.
- `/home` never substitutes Nether spawn, End spawn, or Overworld spawn for a missing or destroyed home, or for a cross-dimension home denied by the setting.
- Unmounted players use Minecraft's accepted respawn position only when its occupied space and
  supporting layer contain no fluid or configured hazard such as lava, fire, magma, cactus, sweet
  berry bush, wither rose, or powder snow.
- Mounted use first requires the root vehicle to fit at that position. The two blocks of the configured bed may be exempted from the extra mounted-clearance margin, but no unrelated collision is exempt.
- If that exact position cannot hold a mounted group, one alternate is permitted only for an uncovered, non-forced bed: the position centered one block above the configured bed. There is no wider, random, vertical, or compatibility search.
- A blocked home, unsafe destination, or vehicle that cannot fit is denied before mutation and leaves every entity where it was. A failure after recursive teleport begins has no rollback guarantee.

## `/spawn`

- The configured spawn command defaults to `/spawn`.
- `/spawn --force` uses the raw spawn block-center feet position of the destination selected by the same routing settings as normal `/spawn`, without running the ordinary safe-location search. In the End it uses the built-in End arrival position and
  platform behavior. It remains subject to player-source and cooldown rules, and exists only while
  `enableForceOverride` is enabled.
- In the Overworld, `/spawn` uses Overworld spawn only while `enableOverworldSpawn` is enabled. If it is disabled, the command is denied without selecting another dimension.
- In the Nether, enabled `enableNetherSpawn` always selects Nether spawn, regardless of `enableCrossDimensionTeleport`.
- In the End, enabled `enableEndSpawn` always selects the End arrival point and platform, regardless of `enableCrossDimensionTeleport`.
- If Nether or End spawn is disabled, `/spawn` selects Overworld spawn only when both `enableCrossDimensionTeleport` and `enableOverworldSpawn` are enabled. Otherwise it reports that spawn teleporting is disabled for that dimension and performs no mutation.
- In the End, OMWH recreates Minecraft 26.2's obsidian arrival platform at the built-in End spawn point and places the group at the platform feet position with vanilla's west-facing orientation. It does not run the ordinary spawn search there.
- Outside the End-specific rule, OMWH searches deterministically near the selected world's spawn for the nearest accepted feet position. The search is bounded to a horizontal radius of 64 blocks and vertical offsets from 2 blocks below through 10 blocks above spawn. If reading the selected world's spawn throws, the released locator uses `(0, 64, 0)` as its fallback search center.
- The destination must support and contain the square footprint derived from the player or root vehicle size, with enough clear height for that root.
- If the mounted group cannot fit but an unmounted player could, the command explains that the vehicle is too large and asks the player to dismount. If no safe destination exists, it reports the configured unsafe-spawn failure.

## Shared destination safety

`DestinationSafety` owns the safety primitives used by both destination policies. Normal command
forms apply their destination policies; the literal `--force` forms deliberately skip those safety
checks and no other admission rule.

- Unmounted `/home` additionally rejects fluids and hazards in the translated player space or its
  supporting layer, even when Minecraft supplied the respawn position.
- Mounted `/home` checks the translated root-vehicle bounds, the required horizontal and upper clearance margins, block collision, fluids and hazards in the vehicle space or supporting layer, the configured-bed exemption, and the world border. Passenger hitboxes do not enlarge this clearance volume.
- `/spawn` requires full-block support beneath the complete square root footprint and clear occupied blocks through the required root height.
- `/spawn` rejects fluids and hazardous blocks, including fire, lava, magma, cactus, sweet berry bushes, wither roses, and powder snow.
- After selecting any accepted home or spawn destination, OMWH loads the fixed five-by-five chunk area around it before teleport mutation.
- Released placement checks do not reject external entities. Self-collision and broader passenger-tree collision rules are decisions for later feature work, not preserved behavior.
- Safety decisions are deterministic. An unsafe candidate is a denial, not permission to use an unchecked fallback.

## Mounted and passenger teleports

- Teleporting a mounted player moves the root vehicle and its complete recursive passenger tree, including non-player entities and other player passengers.
- `TeleportService` captures entity identity and every parent-child attachment before mutation.
- Same- and cross-dimension movement is performed by one recursive teleport of the root entity. OMWH does not manually dismount, move, and remount the tree.
- Destination placement uses feet coordinates and preserves the captured attachment graph. A successful move clears carried root velocity.
- A null teleport result, a runtime exception during mutation, or any changed attachment after mutation is logged by `TeleportService` and returned to the command as teleport failure. The released behavior does not promise rollback after mutation has started.
- Player passengers receive a message naming the command user and whether the group traveled to home or spawn.

## Cooldowns and events

Cooldown state is keyed by player UUID and owned only by `Cooldowns`.

- Regular cooldown: defaults to 30 seconds, is configurable and independently enabled, and begins only after a successful `/home` or `/spawn` teleport.
- PvP cooldown: defaults to 45 seconds, is configurable and independently enabled, and applies to both player attacker and player victim when incoming player-versus-player damage reaches OMWH's `ALLOW_DAMAGE` callback and OMWH allows it to continue.
- Damage cooldown: defaults to 10 seconds, is configurable and independently enabled, and applies to a player when incoming non-player damage reaches OMWH's `ALLOW_DAMAGE` callback and OMWH allows it to continue.
- Join cooldown: defaults to 30 seconds and applies when a player joins. A value of `0` disables it.
- A disabled cooldown or a duration of `0` does not block teleporting.
- When event cooldowns overlap, the longer unexpired event restriction wins; a shorter event cannot reduce an existing longer restriction.
- Blocking priority and message selection are PvP, damage, join, then regular. Remaining time replaces `{time}` in the selected message.
- Failed or denied command attempts do not begin the regular cooldown.
- Cooldowns live only in process memory. UUID keys let active restrictions survive player-object replacement, respawn, and reconnect while the server process remains alive; expired entries are removed lazily when queried.
- PvP and other damage restrictions are recorded from Fabric's `ALLOW_DAMAGE` callback when OMWH allows the incoming damage event to continue. This callback runs before mitigation and does not observe the final event result: another listener may later cancel the damage, but the OMWH cooldown has already begun.

## Messages and effects

- Command feedback is sent as server system messages.
- Configured messages support section-sign color codes and ampersand color codes. Cooldown messages support the `{time}` placeholder.
- Each command has a configurable success message. Missing home, cross-dimension home, unsafe home, unsafe spawn, PvP cooldown, damage cooldown, join cooldown, and regular cooldown each have a distinct configurable message.
- Vehicle-too-large, passenger notification, and unexpected internal-error messages remain explicit command outcomes even though they are not currently configurable fields.
- Teleport attempts may play the Enderman teleport sound at volume `0.5` and pitch `1.0`, controlled by `playTeleportSound`.
- Teleport attempts may spawn 40 portal particles in a one-block ring around the command player, controlled by `spawnTeleportParticles`.
- Effects run after destination acceptance but before entity mutation, so a mutation failure may still produce them. A denial before that point produces none.
- A denial or failed mutation must not send a success message or begin the regular cooldown. Unexpected failures are logged with their cause and return command failure rather than being reported as success.

## Configuration

`OmwhConfig` is the only owner of `config/omwh.json`. A missing file creates a complete default file. Existing files remain permissive about omitted fields and unknown keys: omitted fields retain their built-in defaults and unknown keys are ignored. Known fields must use their documented JSON primitive type; quoted numbers, quoted booleans, nulls, and other incompatible values fail startup instead of being coerced. An empty file or an I/O failure logs the problem and uses defaults for that server run. Malformed JSON propagates as a startup failure. Failure to write the initial default file is logged.

| Field | Default | Contract |
|---|---:|---|
| `homeCommand` | `"home"` | Literal name registered for the home command |
| `spawnCommand` | `"spawn"` | Literal name registered for the spawn command |
| `enableRegularCooldown` | `true` | Enables the post-teleport cooldown |
| `regularCooldownSeconds` | `30` | Regular cooldown duration; `0` disables it |
| `enablePvpCooldown` | `true` | Enables PvP event cooldowns |
| `pvpCooldownSeconds` | `45` | PvP cooldown duration; `0` disables it |
| `enableDamageCooldown` | `true` | Enables non-player-damage cooldowns |
| `damageCooldownSeconds` | `10` | Damage cooldown duration; `0` disables it |
| `joinCooldownSeconds` | `30` | Join cooldown duration; `0` disables it |
| `playTeleportSound` | `true` | Enables the pre-mutation teleport-attempt sound |
| `spawnTeleportParticles` | `true` | Enables pre-mutation teleport-attempt particles |
| `enableForceOverride` | `true` | Registers the literal `--force` form for both commands |
| `enableCrossDimensionTeleport` | `true` | Allows valid cross-dimension homes and eligible Nether/End-to-Overworld spawn routes |
| `enableOverworldSpawn` | `true` | Allows Overworld spawn as the current or selected destination |
| `enableNetherSpawn` | `true` | Allows Nether spawn while the player is in the Nether |
| `enableEndSpawn` | `true` | Allows the End arrival point and platform while the player is in the End |
| `homeSuccessMessage` | `"§aTeleported to your home!"` | `/home` success text |
| `spawnSuccessMessage` | `"§aTeleported to world spawn!"` | `/spawn` success text |
| `noHomepointMessage` | `"§cYou don't have a spawn point set!"` | Missing or invalid home text |
| `crossDimensionMessage` | `"§cYou are not powerful enough to bend space between dimensions. Use a portal first, then try again!"` | Cross-dimension refusal text |
| `unsafeHomeMessage` | `"§cIt is not safe to teleport here."` | Unsafe-home text |
| `unsafeSpawnMessage` | `"§cIt is not safe to teleport here."` | Unsafe-spawn text |
| `pvpCooldownMessage` | `"§cYou were recently in combat! Please wait {time} seconds before teleporting."` | PvP block text |
| `damageCooldownMessage` | `"§cYou recently took damage! Please wait {time} seconds before teleporting."` | Damage block text |
| `joinCooldownMessage` | `"§cYou must wait {time} seconds after joining before teleporting!"` | Join block text |
| `regularCooldownMessage` | `"§cYou recently teleported! Please wait {time} seconds before trying again."` | Regular block text |

The configuration path is not strict whole-document schema validation: unknown keys remain allowed and omitted keys retain defaults. It is strict only about the JSON primitive types and domain validation of known fields.

## Failure contract

- Expected denials use the most specific player-facing message and return command failure without teleport mutation.
- Force bypasses destination safety only. It does not bypass command-source, cooldown, missing-home,
  cross-dimension admission, disabled spawn settings, destination-world, or teleport-mutation failure handling.
- Destination discovery and the checks required by that command complete before teleport mutation begins.
- No command catches an invariant violation and retries through another destination or mutation path.
- Unexpected command exceptions are logged with context, send the command user an internal-error message, and return command failure.
- Teleport-service mutation failures are logged and surfaced to the command as its configured unsafe-home or unsafe-spawn failure. The released behavior does not distinguish partial post-mutation failure for the command user.
- `/spawn` reports an explicit failure when it cannot determine the current world. It also has a world-spawn failure outcome if the locator returns no center, although spawn-read exceptions normally take the released `(0, 64, 0)` fallback instead.
- A successful result means the recursive root teleport returned an entity, the captured attachments remained intact, success feedback was sent, and the regular cooldown was recorded.
