# OMWH Architecture

Status: current
Last verified: 2026-08-15
Applies to: the server-side Fabric mod in this repository

## Purpose and non-goals

OMWH owns configurable, same-dimension `/home` and `/spawn` commands, their temporary admission cooldowns, and safe movement of an attached mount tree. It works on dedicated and integrated servers; clients do not need the mod.

OMWH does not provide cross-dimension warps, persistence, a client UI, custom networking, alternate home placement, an above-bed fallback, or a Java compatibility API. Vanilla remains the authority for bed, respawn-anchor, and forced-respawn semantics.

## Owners and dependency direction

| Owner | Responsibility | May depend on |
|---|---|---|
| `OMWH` | Startup lifecycle; command, join/disconnect, and post-damage event registration | commands, configuration, cooldowns, Fabric/Minecraft APIs |
| `ConfigManager` / `OmwhConfig` | The sole config source, default creation, parsing, and validation | Gson, Fabric Loader |
| `CooldownManager` | The sole UUID-keyed transient cooldown state and restriction lookup | validated config |
| `HomeCommand` / `SpawnCommand` | Command-specific destination policy and user flow | `CommandSupport`, teleport preparation |
| `CommandSupport` | Shared admission, messages, successful completion, cooldown recording, and effects | config, cooldowns, Minecraft presentation APIs |
| `TeleportVehicles` | Mount-tree capture, destination geometry validation, exact chunk loading, and the single recursive root mutation | Minecraft world/entity APIs, pure geometry/search helpers |
| `SafeLocationPlanner`, `ChunkCoverage`, `EntityOwnership`, `VehicleClearanceBox`, `HomeRespawnDecision`, `MountTreeSnapshot` | Pure bounded ordering, coverage, ownership, and invariant checks | Java only |

Allowed direction is `Fabric events -> commands/application owners -> cooldown or teleport owner -> pure helpers`. Pure helpers must not import command, config, Fabric lifecycle, or presentation code. Commands must not load chunks or mutate passenger entities directly. No parallel config singleton, listener wrapper, cooldown map, spawn locator, teleport fallback, effects manager, or compatibility facade is allowed.

## Runtime flows and invariants

### Startup and events

1. `ConfigManager.load` either creates validated defaults or strictly parses the existing JSON file. Existing files must contain every documented key exactly once with its documented JSON token type; unknown, missing, duplicate, null, coerced, out-of-range, malformed, truncated, and trailing input is rejected before `OmwhConfig.validate` runs.
2. Any empty, unreadable, invalid, or uncreatable config fails startup; OMWH does not replace it silently.
3. `OMWH` constructs one cooldown owner and one command-support object, then registers the same server-side callbacks for dedicated and integrated servers.
4. Join records a join restriction. Disconnect removes all state. Accepted nonfatal damage is observed through `AFTER_DAMAGE`; fatal accepted damage through `AFTER_DEATH`.

### Cooldowns and presentation

- PvP, damage, and join restrictions share one timed slot: the latest expiry wins, with PvP then damage then join as equal-expiry precedence.
- Regular teleport cooldown expiry is independent, but an active combat/join restriction is presented first.
- Each command performs one authoritative `restriction(UUID)` lookup.
- Success messages, passenger notices, sound, and particles run only after teleport success. Post-mutation invariant failures remain exceptions and are never converted to ordinary unsafe-placement denials.

### `/home`

1. Admission succeeds before destination work begins.
2. Vanilla selects the exact respawn transition with anchor consumption disabled.
3. Missing and cross-dimension homes are denied.
4. An unmounted player uses the vanilla transition directly.
5. For a mounted player, `TeleportVehicles` translates only the root vehicle AABB to that exact position. Passenger boxes never expand home clearance. The actual root box must fit the build height and world border and be free of block, fluid/danger, and external entity collisions. Block checks inspect the one-block shell of neighboring collision-shape owners. The connected home bed is exempt only from the additional 0.5-block horizontal and 1.5-block upper policy margin, never from the actual root box. The policy margin remains block-collision and world-border checked.
6. There is no nearby, above-bed, or other fallback.

### `/spawn` and mounted teleport

1. `SafeLocationPlanner` sorts the square's actual horizontal offsets once and lazily merges one cursor per vertical offset. It emits the bounded radius-64 search in deterministic `squared distance, abs(dy), dy, dx, dz` order without allocating the full 3D volume or probing impossible squared combinations.
2. Each candidate preserves the complete attached tree's relative geometry. Build-height and world-border failures are rejected before loading. One chunk-key set lives for the `prepareSpawn` search, so every chunk containing the one-block shell of possible collision-shape owners is loaded at most once during the command. Loaded candidates then receive destination-position block collision, external entity collision, fluid/danger, and full root-footprint support checks. Captured tree members are excluded by identity from external-entity rejection.
3. The first valid candidate is the only prepared destination. There is no player-only retry or diagnostic search.
4. Preparation captures every parent-child edge before mutation. Teleport invokes Minecraft once on the attached root, zeroes root velocity, and throws if Minecraft returns no entity or changes the tree.

## Contracts

- Public player contracts: configurable command literals, cooldowns, messages, sound, particles, same-dimension behavior, and mounted passenger transport.
- Persisted contract: `config/omwh.json` fields documented in `README.md`; there is no config migration or silent repair path.
- Mod contract: Fabric `main` entrypoint only, environment `*`, no mixins, access widener, custom packets, or supported Java API.
- Threading/lifetime: event and command work runs synchronously on the server path; cooldown state lives only for the connected session and is discarded on disconnect.

## Verification and change protocol

| Boundary | Canonical check |
|---|---|
| Strict config schema; complete search ordering/count/performance; chunk geometry; home bounds/bed policy; cooldowns; mount-tree invariant | `./gradlew regressionTest` |
| Compile, resources, tests, remapped jars | `./gradlew clean build` |
| Patch hygiene | `git diff --check` plus obsolete-symbol and broad-catch searches |

Integrate changes into the owner above and remove superseded paths in the same change. A new dependency, state owner, event layer, fallback, compatibility surface, persistence/network boundary, or changed dependency direction requires an explicit architecture decision and an update to this file. There are no known migration gaps.
