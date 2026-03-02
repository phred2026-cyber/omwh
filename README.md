# OMWH — /home and /spawn for Minecraft

A Fabric mod adding `/home` and `/spawn` teleport commands with configurable cooldowns, command aliases, and custom messages.

Used on the [PyreHaven Minecraft Server](https://pyrehaven.xyz).

## Features
- `/home` — teleport to your bed/anchor spawn point (same dimension only)
- `/spawn` — teleport to world spawn
- **PvP cooldown** — blocks teleport after combat (default: 45s)
- **Damage cooldown** — blocks after taking damage (default: 10s)
- **Join cooldown** — blocks right after joining (default: 30s)
- **Regular cooldown** — between teleports (default: 30s)
- Works singleplayer or **server-side only** (clients don't need the mod)
- Vehicle/mount support — teleports with your ride
- Fully configurable via `config/omwh.json`

## Installation
1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 1.21.x
2. Install [Fabric API](https://modrinth.com/mod/fabric-api)
3. Drop the JAR into your `mods/` folder
4. Config auto-generates at `config/omwh.json` on first launch

## Configuration
Edit `config/omwh.json` — auto-generated with defaults on first run.

| Field | Default | Description |
|---|---|---|
| `homeCommand` | `"home"` | Command name for /home (change alias here) |
| `spawnCommand` | `"spawn"` | Command name for /spawn |
| `regularCooldownSeconds` | `30` | Seconds between teleports |
| `pvpCooldownSeconds` | `45` | Seconds after combat |
| `damageCooldownSeconds` | `10` | Seconds after taking damage |
| `joinCooldownSeconds` | `30` | Seconds after joining |
| `playTeleportSound` | `true` | Play enderman teleport sound |
| `spawnTeleportParticles` | `true` | Show portal particles |
| All message fields | (see config) | Fully customizable with § color codes |

Set any cooldown to `0` to disable it.

## Community
- 🌐 [pyrehaven.xyz](https://pyrehaven.xyz)
- 💬 [Discord](https://discord.gg/tZ6Hx2ETA3)
- 📦 [Source](https://github.com/phred2026-cyber/omwh)
