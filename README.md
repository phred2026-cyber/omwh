# OMWH — On My Way Home

**O**n **M**y **W**ay **H**ome — a Fabric mod adding `/home` and `/spawn` teleport commands with configurable cooldowns, aliases, and custom messages.

Built by **[PyreHaven](https://pyrehaven.xyz)** and used on the [PyreHaven Minecraft Server](https://pyrehaven.xyz).

> 💬 **Questions?** Join the [PyreHaven Discord](https://discord.gg/tZ6Hx2ETA3) — ask anything, get help, and chat with the community
> 🌐 **More about PyreHaven:** [pyrehaven.xyz](https://pyrehaven.xyz) — the organization behind this mod

---

## Features

- `/home` — teleport to your bed/anchor spawn point (same dimension only)
- `/spawn` — teleport to world spawn
- **PvP cooldown** — blocks teleport after combat (default: 45s)
- **Damage cooldown** — blocks after taking damage (default: 10s)
- **Join cooldown** — blocks right after joining (default: 30s)
- **Regular cooldown** — between teleports (default: 30s)
- Works **singleplayer** or **server-side only** (clients don't need the mod)
- Vehicle/mount support — teleports with your ride
- Safe teleport — won't drop you into the void
- Fully configurable via `config/omwh.json`

---

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 1.21.x
2. Install [Fabric API](https://modrinth.com/mod/fabric-api)
3. Drop the JAR into your `mods/` folder
4. Config auto-generates at `config/omwh.json` on first launch

---

## Configuration

Edit `config/omwh.json` — auto-generated with defaults on first run.

| Field | Default | Description |
|---|---|---|
| `homeCommand` | `"home"` | Command name for /home |
| `spawnCommand` | `"spawn"` | Command name for /spawn |
| `regularCooldownSeconds` | `30` | Seconds between teleports |
| `pvpCooldownSeconds` | `45` | Seconds after combat |
| `damageCooldownSeconds` | `10` | Seconds after taking damage |
| `joinCooldownSeconds` | `30` | Seconds after joining |
| `playTeleportSound` | `true` | Play enderman teleport sound |
| `spawnTeleportParticles` | `true` | Show portal particles |
| All message fields | (see config) | Fully customizable with § color codes |

Set any cooldown to `0` to disable it.

---

## Links

- 💬 [PyreHaven Discord](https://discord.gg/tZ6Hx2ETA3) — ask questions and get help
- 🌐 [PyreHaven Website](https://pyrehaven.xyz) — learn more about the organization
- 🐙 [Source Code](https://github.com/phred2026-cyber/omwh)
- 🐛 [Issues & Suggestions](https://github.com/phred2026-cyber/omwh/issues)

---

## License

MIT — see [LICENSE](LICENSE)

---

*Built by [PyreHaven](https://pyrehaven.xyz) — Chaotic Worlds, Safe Community.*
