# OMWH Structure

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
│   │   │   │   # cooldown → destination → teleport → message/effect flow.
│   │   │   ├── Cooldowns.java
│   │   │   │   # Sole owner of regular, PvP, damage, and join cooldown state plus the Fabric
│   │   │   │   # callbacks that update it. Commands receive one blocking result from this file.
│   │   │   ├── HomeDestination.java
│   │   │   │   # /home-only policy: same-dimension vanilla bed/anchor/forced-home placement,
│   │   │   │   # mounted clearance, and the single uncovered-bed above-bed fallback.
│   │   │   ├── SpawnDestination.java
│   │   │   │   # /spawn-only policy: current-world spawn, End obsidian platform behavior, and
│   │   │   │   # the bounded deterministic safe-location search.
│   │   │   ├── DestinationSafety.java
│   │   │   │   # Shared placement owner for chunks, build limits, world border, support, hazards,
│   │   │   │   # fluids, collision, and the required player or mounted-tree footprint.
│   │   │   └── TeleportService.java
│   │   │       # Sole entity-mutation owner. Captures the root/passenger tree, performs one
│   │   │       # same-dimension recursive root teleport, and verifies attachments afterward.
│   │   └── resources/
│   │       ├── fabric.mod.json
│   │       │   # Fabric metadata, dependencies, version, icon, and Omwh entrypoint.
│   │       └── assets/omwh/icon.png
│   │           # Packaged OMWH icon. No mixin or access-widener file without a feature that needs it.
│   └── test/
│       └── java/xyz/pyrehaven/omwh/
│           ├── OmwhConfigTest.java
│           │   # Every config field, defaults, validation, malformed files, and round trips.
│           ├── CommandsTest.java
│           │   # Both configured commands use one shared flow with correct messages and effects.
│           ├── CooldownsTest.java
│           │   # Regular, PvP, damage, and join timing, priority, expiry, and event updates.
│           ├── HomeDestinationTest.java
│           │   # Vanilla homes, same-dimension rule, mounted fit, and above-bed fallback cases.
│           ├── SpawnDestinationTest.java
│           │   # Deterministic bounds/order, exhaustion, footprint selection, and End behavior.
│           ├── DestinationSafetyTest.java
│           │   # Shared chunk, border, support, hazard, fluid, collision, and footprint rules.
│           └── TeleportServiceTest.java
│               # One root movement, passenger-tree preservation, and failed-preparation safety.
├── ARCHITECTURE.md
│   # This proposed file tree. Bug and verification checks compare code placement against it.
├── README.md
│   # Installation, commands, full config reference, and gameplay behavior.
├── CHANGELOG.md
├── LICENSE
├── build.gradle
│   # Fabric Loom build and all verification tasks.
├── gradle.properties
│   # Minecraft, Fabric, Java, and mod versions.
├── settings.gradle
├── gradlew
├── gradlew.bat
└── gradle/wrapper/
    ├── gradle-wrapper.jar
    └── gradle-wrapper.properties
```
