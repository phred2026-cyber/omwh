# OMWH Architecture

Status: current-with-migration-gaps
Last verified: 2026-08-15
Applies to: the server-side Fabric mod in this repository

## Purpose

OMWH provides configurable, same-dimension `/home` and `/spawn` commands for dedicated and
integrated Fabric servers. It owns temporary teleport restrictions, safe spawn-location selection,
and movement of an attached vehicle/passenger tree. Clients do not need the mod installed.

This contract separates current behavior from the intended code boundaries. Migration work must
preserve accepted behavior unless a separate decision explicitly changes it.

## Non-goals

- Cross-dimension warps.
- Persistent homes, cooldowns, or player data.
- Client UI, custom packets, or a public Java API.
- Speculative service, repository, factory, or plugin layers.
- Replacing Minecraft as the authority for bed, respawn-anchor, or forced-respawn placement.

## Current implementation

| Current component | Responsibility and state |
|---|---|
| `OMWH` | Fabric entrypoint, configuration load, command/event registration, and three static global services. |
| `command` | `/home` and `/spawn` admission, destination orchestration, feedback, effects, and cooldown completion. |
| `ConfigManager` / `OmwhConfig` | Static mutable configuration loaded from `config/omwh.json`; existing JSON is parsed permissively and some failures fall back to defaults. |
| `PlayerListener` | Join and allowed-damage callbacks that write cooldown state; its respawn-copy callback is a no-op. |
| `CooldownManager` | Four UUID-keyed timestamp maps for regular, PvP, damage, and join restrictions. Expiry cleanup is lazy. |
| `TeleportVehicles` | Mounted-home clearance, passenger-tree capture, destination chunk load, recursive root movement, and attachment verification. |
| `SafeLocationPlanner` / `SafeTeleportUtils` | Pure candidate ordering plus Minecraft block-footprint probing and several convenience/compatibility methods. |
| `SpawnLocator` | World-spawn lookup and fallback coordinates; `/spawn` uses only `getSpawnCenter`. |
| Pure policy helpers | Home acceptance, above-bed fallback, clearance geometry, and mount-tree edge checks. |
| Presentation helpers | Configured messages, sounds, and particles. |

There is no scheduler, tick loop, custom networking, UI state, or persistence beyond the config file.

## Target boundaries

The filenames below are a migration sketch, not a requirement to manufacture classes. Existing
classes should be moved, combined, renamed, or deleted only when the result leaves one clearer owner.

```text
Fabric / Brigadier callbacks
            |
            v
  bootstrap and command adapters ---> configuration owner
            |                              |
            v                              v
        command flow ----------------> cooldown owner
            |
            v
        teleport owner -------------> pure teleport policies
            |
            v
     Minecraft world/entity APIs
```

| Boundary | Sole responsibility | Must not own |
|---|---|---|
| Bootstrap (`OMWH`) | Load configuration, construct one runtime graph, register adapters | Gameplay policy, state maps, destination search, messages |
| Configuration (`config`) | Config path, defaults, parsing policy, and the current config value | Commands, cooldown state, world access |
| Cooldowns (`cooldown`) | All transient restriction state and one authoritative restriction result | Fabric registration, messages, teleporting |
| Events (`event`) | Translate accepted Fabric callbacks into cooldown operations | A second state map or new gameplay policy |
| Commands (`command`) | Command-specific policy, admission, result-to-message mapping, and completion | Chunk loading, collision scans, direct entity movement |
| Teleport (`teleport`) | Destination preparation, chunk/collision policy, mount-tree capture, and the only entity movement path | Config loading, cooldowns, command registration, player copy |
| Pure policies (`teleport` or a nested policy package) | Deterministic search order, decisions, and geometry | Fabric lifecycle, config globals, logging, mutable world state |

Required dependency rules:

- Commands receive their owners; they do not reach back through static `OMWH` globals.
- Events call cooldown operations and never inspect cooldown maps.
- Every direct entity movement, chunk load for a teleport, and teleport collision query routes through
  the teleport owner.
- Pure policy code depends on Java values or narrow inputs, not Fabric lifecycle or presentation code.
- Do not add a parallel listener, config source, cooldown map, spawn search, compatibility facade, or
  fallback path while the old owner remains active.

## Accepted behavior contracts

### Startup and state

- Configuration loads before command/event registration.
- Dedicated and integrated server support remain required.
- Command and event work runs on Minecraft's server execution path.
- Cooldowns are process-memory state keyed by UUID, survive reconnects until expiry under current
  behavior, and are removed lazily when queried.
- There is no runtime config-reload contract.

### `/home`

1. Minecraft supplies the bed, respawn-anchor, or forced-respawn transition without consuming an
   anchor charge.
2. Missing, invalid, and cross-dimension homes are refused.
3. Unmounted players use Minecraft's accepted destination through OMWH's root movement path.
4. Mounted players preserve the attached tree and require the root vehicle to fit.
5. Current behavior permits one fallback centered one block above an uncovered, non-forced bed when
   Minecraft's adjacent position cannot fit the root. There is no broader nearby search.
6. The attached root moves once and captured parent-child edges are checked afterward.

### `/spawn`

1. The current world's spawn data supplies the search center. The documented End contract uses the
   End obsidian platform.
2. One bounded, deterministic radius-64 search checks a square block footprint and required headroom.
3. Current checks cover full-block support, clear collision shapes, empty fluids, and named hazards.
4. A second player-only diagnostic search may distinguish an oversized vehicle from generally blocked
   terrain; it does not become a second movement path.
5. The command currently loads a fixed 5×5 chunk area before the teleport owner moves the root tree.

### Completion and failures

- Successful movement starts the regular cooldown and sends success/passenger messages.
- Failed movement does not start the regular cooldown or send a success message.
- Effects currently run before the movement attempt. Changing their timing is a gameplay decision.
- Null movement results and changed passenger trees currently become failed command results.
- Broad command/world catches and guessed spawn fallbacks are current behavior, not the desired code
  shape; changing their semantics requires characterization and approval.

## Configuration contract

`config/omwh.json` is operator-owned persisted data. The current generated schema contains:

```text
homeCommand, spawnCommand,
enableRegularCooldown, regularCooldownSeconds,
enablePvpCooldown, pvpCooldownSeconds,
enableDamageCooldown, damageCooldownSeconds, joinCooldownSeconds,
playTeleportSound, spawnTeleportParticles,
homeSuccessMessage, spawnSuccessMessage, noHomepointMessage, crossDimensionMessage,
unsafeHomeMessage, unsafeSpawnMessage, pvpCooldownMessage, damageCooldownMessage,
joinCooldownMessage, regularCooldownMessage
```

`README.md` documents the command names, cooldown durations, effects, and message category, but omits
three enable flags and the individual message keys. The migration must inventory existing config files
and reconcile that documentation gap before choosing strict parsing, required keys, ranges, unknown-key
handling, or fail-open/fail-closed behavior.

Platform contracts are Fabric Loader `>=0.19.3`, Minecraft `~26.2`, Java `>=21`, Fabric API, mod id
`omwh`, environment `*`, and entrypoint `com.omwh.OMWH`. Empty mixin/access-widener declarations and
public helper methods are not automatically supported APIs, but removal still requires caller/history
proof and a separate decision.

## Migration gaps

| Gap | Current evidence | Structural target | Required proof before change |
|---|---|---|---|
| Bootstrap is a service locator | `OMWH` exposes three globals and two environment registration paths | One composition root and lifecycle owner | Dedicated/integrated startup and single registration |
| Config ownership is mutable and permissive | `ConfigManager`, public fields in `OmwhConfig` | One config owner/value; strictness remains undecided | Every generated key, existing-file compatibility, malformed/I/O cases |
| Cooldown policy is repeated | Four maps plus repeated queries in both commands | One authoritative result from the existing state owner | Precedence, expiry, disabled values, current reconnect behavior |
| Event adapter contains no-op/swallowed paths | `PlayerListener` | One thin adapter preserving current callback timing first | Join, allowed-damage, respawn, dedicated/integrated behavior |
| Command completion is duplicated | `HomeCommand`, `SpawnCommand` | Share only proven admission/completion policy | Current messages, cooldowns, effects, and failure results |
| Teleport ownership is split | Commands and helpers load chunks; two classes can move entities | One preparation/mutation owner | Mechanical call inventory plus successful/failed flow fixtures |
| Spawn work is large and split | Full 3D candidate allocation, second diagnostic search, fixed chunk load | Centralize current behavior before considering algorithm changes | Search order/count, bounds, hazards, support, chunk edges |
| World compatibility hides failures | `WorldCompat`, `SpawnLocator`, broad `Throwable` catches | One supported world boundary; failure policy undecided | Current missing-world and spawn-fallback behavior |
| Utility package mixes owners and dead-looking helpers | `com.omwh.utils` | Domain packages matching the boundaries above | Caller/history scan and canonical build |
| Empty metadata and uncalled public helpers remain | mixin/access-widener files and helper methods | Decide removal separately after compatibility proof | Built-jar metadata, caller scan, startup |
| Tests cover only selected pure helpers | `RegressionTestSuite` | Add a focused seam before each migration slice | Config, clock-driven cooldown, command completion, adapter fixtures |

## Decisions this document does not make

The earlier anti-slop implementation was rejected as a whole. This document does not restore strict
fail-startup config parsing, remove the above-bed fallback, change damage/death callback timing, add
disconnect cleanup, move effect timing, replace the spawn algorithm, add broader mount/entity collision
rules, tighten post-teleport failures, or remove metadata/helpers. Each needs fresh characterization
and a separate decision. Structural movement alone must preserve current behavior.

## Verification and change protocol

- `./gradlew regressionTest` is the focused dependency-free harness.
- `./gradlew clean build` is the canonical build and produces the remapped runtime and sources jars.
- `./gradlew check` must include any architecture fitness check added without a new dependency.
- Run `git diff --check` and inspect tracked and untracked files before every commit.
- Migrate one vertical flow at a time. Add characterization first, consolidate the owner, delete the
  superseded path, and return the build to green before the next slice.
- Structural preparation and new gameplay belong in separate green commits.
- Update this file when an owner, dependency direction, lifecycle rule, or external contract changes.
- New dependencies, top-level owners, persistence/network boundaries, or public APIs require an
  explicit architecture decision.
