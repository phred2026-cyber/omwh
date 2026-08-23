# OMWH Structure

This tree describes the OMWH 1.2.0 release candidate. The version is selected, but publication still waits for connected runtime verification and Elijah's explicit release approval.

`/home` and `/spawn` differ only in destination policy. `Commands`, `Cooldowns`,
`DestinationSafety`, and `TeleportService` are their shared owners for command flow, cooldown state,
placement safety, and teleport mutation.

```text
omwh/
├── src/
│   ├── main/
│   │   ├── java/xyz/pyrehaven/omwh/
│   │   │   ├── Omwh.java
│   │   │   │   # Fabric entrypoint and composition root. Loads config, constructs the owners below,
│   │   │   │   # and registers them for dedicated and integrated servers. No gameplay lives here.
│   │   │   ├── OmwhConfig.java
│   │   │   │   # Sole owner of config/omwh.json: command names, messages, effects, cooldowns,
│   │   │   │   # defaults, loading, creation, and validation.
│   │   │   ├── Commands.java
│   │   │   │   # Registers the configured /home and /spawn names and owns their shared
│   │   │   │   # cooldown → destination → teleport → message/effect flow, including pending
│   │   │   │   # /spawn lifecycles advanced fairly under one server-wide tick budget.
│   │   │   ├── Cooldowns.java
│   │   │   │   # Sole owner of regular, PvP, damage, and join cooldown state plus the Fabric
│   │   │   │   # callbacks that update it. Commands receive one blocking result from this file.
│   │   │   ├── HomeDestination.java
│   │   │   │   # /home-only policy: vanilla saved-respawn admission and placement across allowed
│   │   │   │   # dimensions, mounted clearance, and the single uncovered-bed above-bed fallback.
│   │   │   ├── SpawnDestination.java
│   │   │   │   # /spawn-only policy: Overworld/Nether/End/modded-dimension routing, vanilla End
│   │   │   │   # transition behavior, and resumable deterministic safe-location search state.
│   │   │   ├── DestinationSafety.java
│   │   │   │   # Shared placement owner for chunks, build limits, world border, support, hazards,
│   │   │   │   # fluids, collision, and the required player or mounted-tree footprint.
│   │   │   └── TeleportService.java
│   │   │       # Sole entity-mutation owner. Captures UUIDs, exact passenger edges, source validity,
│   │   │       # and player identities; performs one same- or cross-dimension recursive root
│   │   │       # teleport; then reconciles the complete returned tree before reporting success.
│   │   └── resources/
│   │       ├── fabric.mod.json
│   │       │   # Fabric metadata, dependencies, version, icon, and Omwh entrypoint.
│   │       └── assets/omwh/icon.png
│   │           # Packaged OMWH icon. No mixin or access-widener file without a feature that needs it.
│   └── test/
│       └── java/xyz/pyrehaven/omwh/
│           ├── ConfigTest.java
│           │   # Config loading, defaults, validation, malformed files, and round trips.
│           ├── CommandsAndCooldownsTest.java
│           │   # Both commands, shared flow, messages, effects, and all cooldown rules.
│           ├── DestinationsTest.java
│           │   # Home, spawn, safety checks, mounted placement, and End behavior.
│           └── TeleportServiceTest.java
│               # Vehicle/passenger movement, failed preparation, and attachment preservation.
├── .github/workflows/ci.yml
│   # Runs the canonical Gradle build and regression suites on pushes and pull requests.
├── ARCHITECTURE.md
│   # This proposed file tree. Bug and verification checks compare code placement against it.
├── CONFIGURATION.md
│   # Complete server-owner reference for config fields, defaults, messages, and file handling.
├── FEATURES.md
│   # Accepted behavior contract preserved while the implementation is rebuilt.
├── README.md
│   # Installation, commands, concise configuration summary, and gameplay behavior.
├── CHANGELOG.md
├── LICENSE
├── .gitignore
│   # Keeps Gradle output, IDE state, and local runtime files out of source.
├── build.gradle
│   # Fabric Loom build and all verification tasks.
├── gradle.properties
│   # Minecraft, Fabric, Java, and mod versions plus the preserved com.omwh Maven coordinate.
├── settings.gradle
├── gradlew
└── gradle/
    ├── minecraft/
    │   ├── 26.2-custom.json
    │   │   # Loom-compatible Minecraft 26.2 metadata used by the build.
    │   └── identity-official-26.2.jar
    │       # Pinned official-name mapping input used by the build.
    └── wrapper/
        ├── gradle-wrapper.jar
        └── gradle-wrapper.properties
```
