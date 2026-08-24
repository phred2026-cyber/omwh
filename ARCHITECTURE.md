# OMWH architecture

OMWH is a small server-side Fabric mod. Production behavior stays in one Java package so ownership is visible without an extra service or framework layer.

```text
.
├── .github/workflows/ci.yml         # Canonical CI build and regression gate
├── .gitignore                       # Generated and local-file exclusions
├── ARCHITECTURE.md                  # Contributor map and ownership notes
├── BEHAVIOR.md                      # Player and server-owner contract
├── CHANGELOG.md                     # Versioned player-facing changes
├── CONFIGURATION.md                 # Persisted configuration contract
├── LICENSE                          # CC0-1.0 legal text
├── README.md                        # Installation and command overview
├── build.gradle                     # Build plus canonical JavaExec regression tasks
├── gradle.properties                # Project and dependency versions
├── gradlew                          # Reproducible Unix Gradle launcher
├── settings.gradle                  # Gradle project identity
├── gradle/
│   ├── minecraft/
│   │   ├── 26.2-custom.json         # Custom target-version metadata consumed by Loom
│   │   └── identity-official-26.2.jar # Pinned identity mapping input
│   └── wrapper/
│       ├── gradle-wrapper.jar        # Reproducible wrapper bootstrap
│       └── gradle-wrapper.properties # Wrapper distribution and checksum
└── src/
    ├── main/
    │   ├── java/xyz/pyrehaven/omwh/
    │   │   ├── Omwh.java             # Fabric entrypoint and event registration
    │   │   ├── OmwhConfig.java       # JSON loading, defaults, and validation
    │   │   ├── OmwhCommands.java     # Command admission, pending scheduler, feedback, effects
    │   │   ├── Cooldowns.java        # Cooldown timestamps and admission results
    │   │   ├── HomeDestination.java  # Saved-home policy and Minecraft adapter
    │   │   ├── SpawnDestination.java # Dimension routing and spawn search state machine
    │   │   ├── DestinationSafety.java # Terrain ownership, geometry, probes, and tickets
    │   │   └── TeleportService.java  # Root/passenger lifecycle and sole movement owner
    │   └── resources/
    │       ├── fabric.mod.json        # Fabric metadata and runtime requirements
    │       └── assets/omwh/icon.png   # Packaged icon
    └── test/java/xyz/pyrehaven/omwh/
        ├── ConfigTest.java            # Parsing, defaults, and persisted validation
        ├── CommandsAndCooldownsTest.java # Commands, events, scheduling, cleanup, feedback
        ├── DestinationsTest.java      # Home/spawn policy, terrain, probes, and work bounds
        └── TeleportServiceTest.java   # Passenger lifecycle, mutation, and reconciliation
```

## Dependency direction

`Omwh` wires the runtime. `OmwhCommands` coordinates policy owners but does not duplicate destination or movement logic. `HomeDestination` and `SpawnDestination` produce prepared destinations through `DestinationSafety`. Only `TeleportService` mutates the root/passenger tree. `Cooldowns` and `OmwhConfig` do not depend on command scheduling.

Tests exercise dependency-free policy seams and the production scheduler. A policy seam is useful only when the corresponding Minecraft adapter calls it; narrow fixture collaborators belong in tests rather than production overloads.

## Contributor notes

Minecraft 26.2's saved-respawn and End-arrival algorithms are version-coupled boundaries. Recheck the mapped vanilla reads, order, and side effects on a Minecraft update. Work and chunk constants sit beside the production loops they bound, and traversal/state-machine comments record invariants that are not obvious from local syntax.

Before submitting a change, run:

```bash
./gradlew clean regressionTest build
git diff --check
```

Do not add generated Gradle output to the source tree.
