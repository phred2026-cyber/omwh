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
│           ├── ConfigTest.java
│           │   # Config loading, defaults, validation, malformed files, and round trips.
│           ├── CommandsAndCooldownsTest.java
│           │   # Both commands, shared flow, messages, effects, and all cooldown rules.
│           ├── DestinationsTest.java
│           │   # Home, spawn, safety checks, mounted placement, and End behavior.
│           └── TeleportServiceTest.java
│               # Vehicle/passenger movement, failed preparation, and attachment preservation.
├── ARCHITECTURE.md
│   # This proposed file tree. Bug and verification checks compare code placement against it.
├── README.md
│   # Installation, commands, full config reference, and gameplay behavior.
├── CHANGELOG.md
├── LICENSE
├── .gitignore
│   # Keeps Gradle output, IDE state, and local runtime files out of source.
├── build.gradle
│   # Fabric Loom build and all verification tasks.
├── gradle.properties
│   # Minecraft, Fabric, Java, and mod versions.
├── settings.gradle
├── gradlew
├── gradlew.bat
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
