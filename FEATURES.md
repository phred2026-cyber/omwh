# OMWH Feature Contract

## Status

This file is the accepted gameplay contract for the OMWH 1.2.0 release candidate. The version is selected for local test-server deployment, but official publication still waits for connected runtime verification and Elijah's explicit approval. The eight production owners and four grouped regression files are defined in `ARCHITECTURE.md`.

## Runtime scope

- OMWH is server-authoritative. It must work on dedicated Fabric servers and integrated singleplayer servers.
- Players do not need OMWH on their clients. There is no client-only gameplay owner or client entrypoint.
- Both commands are player commands. Non-player command sources cannot invoke teleport behavior.
- Server owners decide whether valid `/home` destinations and eligible `/spawn` routes may cross dimensions.

## `/home`

- The configured home command defaults to `/home`.
- `/home force` uses the same authoritative vanilla respawn position while deliberately bypassing
  destination-safety checks. It remains subject to player-source, cooldown, missing-home, and
  dimension-admission rules, and exists only while `enableForceOverride` is enabled. Every player
  who can use `/home` can use this child command.
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
- `/spawn force` uses the raw spawn block-center feet position of the destination selected by the same routing settings as normal `/spawn`, without running the ordinary safe-location search. In the End it uses the built-in End arrival position. It rebuilds the obsidian platform only when `rebuildEndPlatform` is enabled. It remains subject to player-source and cooldown rules, and exists only while
  `enableForceOverride` is enabled. Every player who can use `/spawn` can use this child command.
- In the Overworld, `/spawn` uses Overworld spawn only while `enableOverworldSpawn` is enabled. If it is disabled, the command is denied without selecting another dimension.
- In the Nether, enabled `enableNetherSpawn` always selects Nether spawn, regardless of `enableCrossDimensionTeleport`.
- In the End, enabled `enableEndSpawn` always selects the End arrival destination, regardless of `enableCrossDimensionTeleport`.
- If Nether or End spawn is disabled, `/spawn` selects Overworld spawn only when both `enableCrossDimensionTeleport` and `enableOverworldSpawn` are enabled. Otherwise it reports that spawn teleporting is disabled for that dimension and performs no mutation.
- In the End, OMWH uses Minecraft 26.2's built-in arrival position with vanilla's west-facing orientation. By default it checks the existing blocks and refuses an obstructed or unsupported destination without changing the world. When `rebuildEndPlatform` is enabled, it may recreate Minecraft's obsidian arrival platform after side-effect-free acceptance checks. It does not run the ordinary spawn search there.
- Outside the End-specific rule, OMWH checks at most 4,096 deterministic nearest-first candidates within a horizontal radius of 64 blocks and vertical offsets from 2 blocks below through 10 blocks above spawn. Candidate checks read only already-loaded chunks and never generate terrain. If world spawn cannot be read, the command fails explicitly instead of inventing fallback coordinates.
- The destination must support and contain the square footprint derived from the player or root vehicle size, with enough clear height for that root.
- If the mounted group cannot fit but an unmounted player could, the command explains that the vehicle is too large and asks the player to dismount. If no safe destination exists, it reports the configured unsafe-spawn failure.

## Shared destination safety

`DestinationSafety` owns the safety primitives used by both destination policies. Normal command
forms apply their destination policies; the literal `force` children deliberately skip those safety
checks and no other admission rule.

- Unmounted `/home` additionally rejects fluids and hazards in the translated player space or its
  supporting layer, even when Minecraft supplied the respawn position.
- Mounted `/home` checks the translated root-vehicle bounds, the required horizontal and upper clearance margins, block collision, fluids and hazards in the vehicle space or supporting layer, the configured-bed exemption, and the world border. Passenger hitboxes do not enlarge this clearance volume.
- `/spawn` requires full-block support beneath the complete square root footprint and clear occupied blocks through the required root height.
- `/spawn` rejects fluids and hazardous blocks, including exact fire, soul fire, lava, magma, cactus, sweet berry bush, wither rose, and powder snow blocks. Names that merely contain those words, such as fire coral, are not hazards.
- After selecting any accepted home or spawn destination, OMWH loads the fixed five-by-five chunk area around it before teleport mutation.
- Released placement checks do not reject external entities. Self-collision and broader passenger-tree collision rules are decisions for later feature work, not preserved behavior.
- Safety decisions are deterministic. An unsafe candidate is a denial, not permission to use an unchecked fallback.

## Mounted and passenger teleports

- Teleporting a mounted player moves the root vehicle and its complete recursive passenger tree, including non-player entities and other player passengers.
- `TeleportService` captures entity identity and every parent-child attachment before mutation.
- Same- and cross-dimension movement is performed by one recursive teleport of the root entity. OMWH does not manually dismount, move, and remount the tree.
- Destination placement uses feet coordinates and preserves the captured attachment graph. A successful move clears carried root velocity.
- A null teleport result or teleport exception supplies no moved root and is logged as an internal command failure with no passenger notification or regular cooldown. Once Minecraft returns a moved root, a failed passenger-tree reconciliation is reported as a possible partial teleport; OMWH notifies trustworthy moved player passengers, records the regular cooldown to prevent free retries, and does not promise rollback.
- Player passengers receive a message naming the command user and whether the group traveled to home or spawn.

## Cooldowns and events

Cooldown state is keyed by player UUID and owned only by `Cooldowns`.

- Regular cooldown: defaults to 30 seconds, is configurable and independently enabled, and begins after a successful teleport or a possible partial move past the mutation boundary.
- PvP cooldown: defaults to 45 seconds, is configurable and independently enabled, and applies to both player attacker and player victim when incoming player-versus-player damage reaches OMWH's `ALLOW_DAMAGE` callback and OMWH allows it to continue.
- Damage cooldown: defaults to 10 seconds, is configurable and independently enabled, and applies when incoming non-player or self-inflicted damage reaches OMWH's `ALLOW_DAMAGE` callback and OMWH allows it to continue.
- Join cooldown: defaults to 30 seconds and applies when a player joins. A value of `0` disables it.
- A disabled cooldown or a duration of `0` does not block teleporting.
- When event cooldowns overlap, the longer unexpired event restriction wins; a shorter event cannot reduce an existing longer restriction.
- Blocking priority and message selection are PvP, damage, join, then regular. Remaining time replaces `{time}` in the selected message.
- Failed or denied command attempts do not begin the regular cooldown.
- Cooldowns live only in process memory. UUID keys let active restrictions survive player-object replacement and respawn. State is removed when the player disconnects, and expired entries are removed lazily when queried.
- PvP and other damage restrictions are recorded from Fabric's `ALLOW_DAMAGE` callback when OMWH allows the incoming damage event to continue. This callback runs before mitigation and does not observe the final event result: another listener may later cancel the damage, but the OMWH cooldown has already begun.

## Messages and effects

- Command feedback is sent as server system messages.
- Configured messages support section-sign color codes and ampersand color codes. Cooldown messages support the `{time}` placeholder.
- Each command has a configurable success message. Missing home, cross-dimension home, unsafe home, unsafe spawn, PvP cooldown, damage cooldown, join cooldown, and regular cooldown each have a distinct configurable message.
- Vehicle-too-large, passenger notification, and unexpected internal-error messages remain explicit command outcomes even though they are not currently configurable fields.
- Teleport attempts may play the Enderman teleport sound at volume `0.5` and pitch `1.0`, controlled by `playTeleportSound`.
- Teleport attempts may spawn 40 portal particles in a one-block ring around the command player, controlled by `spawnTeleportParticles`.
- Effects run after destination acceptance but before entity mutation, so a mutation failure may still produce them. A denial before that point produces none.
- A denial or pre-mutation failure must not send a success message or begin the regular cooldown. A possible partial move uses its warning and cooldown instead. Unexpected failures are logged with their cause and return command failure rather than being reported as success.

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
| `enableForceOverride` | `true` | Registers the player-usable literal `force` child for both commands |
| `enableCrossDimensionTeleport` | `true` | Allows valid cross-dimension homes and eligible Nether/End-to-Overworld spawn routes |
| `enableOverworldSpawn` | `true` | Allows Overworld spawn as the current or selected destination |
| `enableNetherSpawn` | `true` | Allows Nether spawn while the player is in the Nether |
| `enableEndSpawn` | `true` | Allows the End arrival destination while the player is in the End |
| `rebuildEndPlatform` | `false` | Allows End `/spawn` to rebuild Minecraft's obsidian arrival platform after acceptance checks |
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
- Destination-safety denials use the configured unsafe-home or unsafe-spawn message. When
  `enableForceOverride` is enabled, only these denials add a second line pointing to the configured
  command name followed by `force`. Disabled force commands are never advertised. Vehicle-too-large,
  missing-home, cross-dimension, disabled-spawn, unavailable-world, cooldown, mutation/internal,
  partial, and success feedback do not include this guidance.
- Teleport invariant failures with no returned moved root use the command-specific internal-error
  message. They do not begin the regular cooldown or notify passengers. Reconciliation failures after
  a non-null moved root use a distinct partial-teleport warning, notify trustworthy moved player
  passengers, and begin the regular cooldown.
- `/spawn` reports an explicit failure when it cannot determine the current world or read the selected world's spawn. It never substitutes fabricated fallback coordinates.
- A successful result means the recursive root teleport returned an entity, the captured attachments remained intact, success feedback was sent, and the regular cooldown was recorded. A possible partial result is not success, but it still records the cooldown because movement may already have happened.
