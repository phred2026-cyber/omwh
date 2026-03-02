# OMWH — On My Way Home

**O**n **M**y **W**ay **H**ome — a Fabric mod adding `/home` and `/spawn` teleport commands with configurable cooldowns, aliases, and custom messages.

Built by **[PyreHaven](https://pyrehaven.xyz)** and used on the [PyreHaven Minecraft Server](https://pyrehaven.xyz).

> 💬 **Questions?** Join the [PyreHaven Discord](https://discord.gg/tZ6Hx2ETA3) — ask anything, get help, and chat with the community
> 🌐 **More about PyreHaven:** [pyrehaven.xyz](https://pyrehaven.xyz) — the organization behind this mod

---

## Commands

### `/home`
Teleports you to your **respawn point** — your bed or respawn anchor, wherever it was last set. Works within the same dimension only (no cross-dimension teleport).

### `/spawn`
Teleports you to the **spawn point of your current dimension**:
- **Overworld** → world spawn
- **Nether** → Nether spawn
- **End** → the obsidian platform

Neither command crosses dimensions — you teleport to the spawn or home of whichever dimension you are already in.

### Mounts & Passengers
If you are riding a mount (horse, boat, pig, strider, etc.), it comes with you. Any **passengers or entities inside your vehicle** — including other players — also teleport along.

---

## Features

- **Configurable cooldowns** — regular, PvP, damage taken, and join cooldowns
- **Configurable aliases** (e.g. `/h`, `/s`)
- **Custom messages** with `{time}` placeholder support
- **Teleport effects** — optional sound and particles on arrival
- **Safe teleport** — won't drop you into the void
- Works **singleplayer** or **server-side only** (clients don't need the mod)
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
