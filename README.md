# Create: Battle Bots

Build a machine. Fight a round. Get it back. Make it better.

**Create: Battle Bots** adds server-authoritative robot matches to Minecraft
1.21.1 with Create, Simulated, Aeronautics, Offroad, and Sable. This repository is
named **CreateBotanomics**; the mod ID and distributable are **createbattlebots**.

## First fight in 0.2.0

**Press B in a world, choose Spinner starter or Walking starter, and follow the
three-step driving tutorial.** The server provides both robots and a separate
practice arena. Drive with W/S, steer with A/D, hold Space to spin the saw, and
fight Scrapbot. No commands, registration or inventory items are needed.

A module can genuinely break away: its native physical body keeps colliding and
its lost wheel or leg reduces traction and changes steering. After the result,
both robots are restored. Press B for one-click rematch, free test driving or
creative-mode workshop editing. R resets and starts a new round; during a fight
it counts as a forfeit. Returning home preserves your original inventory and
mode. The two starter workshops save separately.

The starter walker has real articulated legs with **arcade traction assistance**;
it is not presented as a purely mechanical gait simulation. Physical detachment
and joint reconstruction are limited to the starter's five tracked modules.
The existing unrestricted-build tournament workflow is still available and
still uses conservative registered hit-zone damage. General destruction and
reconstruction of arbitrary third-party machines is not claimed. See
[implementation scope](docs/IMPLEMENTATION.md).

## Install for local play

Use the `.mrpack` from this repository's GitHub Release with a launcher that imports
Modrinth packs. It installs the exact runtime versions below and our built mod.
Create a world and press B to play. Cheats are not required for starter practice.
An operator is required only for setting up custom tournament arenas. The same
instance can open its world to LAN.

Alternatively, install Minecraft **1.21.1**, **NeoForge 21.1.247**, and **Java 21**
in a fresh instance, then install the compiled `createbattlebots-0.2.0+mc1.21.1.jar`
and the dependencies listed in `modpack.lock.json`. A sources JAR is not playable.

The checksum-verifying installer can populate an existing instance:

```bash
python3 scripts/install_modpack.py --target /path/to/instance \
  --mod-jar build/libs/createbattlebots-0.2.0+mc1.21.1.jar
```

Every client and server needs this mod and the same required dependency versions.
ComputerCraft is optional; add `--without-computercraft` to omit it.

## Install a dedicated server

Extract the `-server-kit.zip` from the GitHub Release into a new folder. It contains
our mod, the dependency lock, the installer, and this guide. Run with Python 3.11+
and Java 21:

```bash
python3 scripts/install_modpack.py --server --target . \
  --mod-jar mods/createbattlebots-0.2.0+mc1.21.1.jar \
  --java /path/to/java21/bin/java
```

Read Minecraft's EULA and set `eula=true` in `eula.txt` if you accept it. Start with
`./run.sh nogui` on Linux/macOS or `run.bat nogui` on Windows. Use Java 21 for the
server process as well as installation. Start with 4 GB of server RAM, then tune
for your machines and player count. Keep the generated server in online mode for
normal public operation.

The installer downloads third-party files from their official published URLs and
checks their hashes; these dependencies are not bundled in our Git repository or
server kit. It refuses to overwrite conflicting dependency files or install two
Battle Bots versions together.

## Exact runtime

| Component | Version |
| --- | --- |
| Minecraft Java | 1.21.1 |
| Java | 21 |
| NeoForge | 21.1.247 |
| Create | 6.0.10 |
| Sable | 2.0.6 |
| Simulated / Aeronautics / Offroad | Official Aeronautics bundle 1.3.2 |
| CC: Tweaked, optional | 1.115.1 |
| Create: Lanterns, optional | Alpha 20.11 |

Development compiles against the exact released Sable 2.0.6 binary used by the
installable pack. Download URLs, sizes and hashes are in
[modpack.lock.json](modpack.lock.json). Lanterns is a separate optional download;
see [Lanterns compatibility and installation](docs/LANTERNS.md).

## Custom-build tournament rounds

Read [the first-round guide](docs/PLAYING.md) for the complete workflow.

1. Build two machines with the upstream mods. Put one Battle Transponder on each.
   Add Combat Armor and Damage Relays where you want meaningful damage. Wire a
   relay to a Create clutch: a disabled relay emits redstone level 15.
2. Assemble the machines. Right-click a transponder and use the suggested
   registration command. Registration uses the block's Sable plot coordinates;
   arena bounds use ordinary world coordinates.
3. An operator defines a protected arena. Each owner joins a robot to its roster.
4. Open a Garage Console inside the arena or use `/battlebots start <arena> human`.
   The default is a five-second countdown and a three-minute round.
5. Fight using normal controls. A knockout, ring-out, surrender, expired reconnect
   grace, or timed decision ends the round. Both robots are repaired automatically.

Flying and walking machines use the same server rules; class labels do not impose
a chassis. Jointed bodies must all belong to the registration. The implementation
tracks native declared connections and supports explicit `bot attach` for bodies
that an add-on does not declare through Sable's connection API.

For autonomous rounds, prepare onboard redstone/computer control and use
`/battlebots start <arena> autonomous`. Pilots become spectators for the round,
with their original game modes retained for normal completion and crash recovery.
The optional `battle_transponder` ComputerCraft peripheral supplies match state,
time, damage, scores, and recent hits. It does not expose world-editing or network
privileges. See [the Lua example](examples/lua/arena_controller.lua).

## Controls and administration

| Command | Access |
| --- | --- |
| `B` garage / `/battlebots practice [walker]`, `/battlebots leave` | Everyone; practice enabled |
| `/battlebots help`, `diagnostics`, `status [arena]` | Everyone |
| `/battlebots bot register <name> <x> <y> <z> [class]` | Nearby transponder owner |
| `/battlebots bot attach <name> <x> <y> <z>` | Owner; no active match |
| `/battlebots bot list`, `arena list` | Everyone |
| `/battlebots bot repair <name>`, `bot unregister <name>` | Owner/operator; no active match |
| `/battlebots join <arena> <bot>`, `leave <arena> <bot>` | Owner/operator |
| `/battlebots surrender` | Current competitor |
| `/battlebots arena create <name> <min> <max>`, `arena remove <name>` | Operator |
| `/battlebots start <arena> human\|autonomous`, `stop <arena>`, `recover <arena>` | Operator |

Settings are in the world's `serverconfig/createbattlebots-server.toml`. They
include round/countdown duration, ring-outs, automatic repair, snapshot/body
budgets, contact limits and damage tuning. These limits protect server computation;
they are separate from optional tournament build restrictions.

## Build and verify

```bash
./gradlew build
./gradlew runGameTestServer
./gradlew runGameTestServer -PwithComputerCraft=false
./gradlew runGameTestServer -PwithLanterns=true
python3 scripts/package_release.py
```

Windows uses `gradlew.bat`. JDK 21 is required. The checked-in Gradle wrapper has a
pinned distribution checksum. Build output:

- `build/libs/createbattlebots-0.2.0+mc1.21.1.jar` — installable mod.
- `build/distributions/*.mrpack` — local-play launcher pack.
- `build/distributions/*-server-kit.zip` — dedicated-server setup kit.

`runClient`, `runServer`, and `runGameTestServer` are configured development runs.
The GameTests are in a separate source set and never enter the production JAR.
CI checks all four combinations of ComputerCraft and Lanterns present/absent. A
version change on `main` or an explicit version tag publishes only after those
configurations pass. The
workflow validates `mod_version` and never replaces an existing version's assets.

See [verification evidence](docs/VERIFICATION.md) for what was actually run and
which play scenarios still need broader playtesting. Compilation alone is not a
multiplayer or gameplay-quality test.

## Practice server limits

`[practice] enabled` controls access to the isolated solo workshop;
`maxActiveSessions` defaults to four, with twelve native bodies per session.
There are sixteen saved starter slots per world (spinner and walker each use a
slot). These bounds do not restrict the separate free-build tournament system.
The server validates every input, applies a ten-tick dead-man timeout, and owns
all damage, joint changes and results. Only a creative owner can edit their own
starter between rounds; generated bots cannot be farmed for survival items.

## Recovery and compatibility

Before combat, the server writes an immutable, checksummed snapshot and a checked,
atomic registry checkpoint under the world's `battlebots/` directory. Each custom tournament match
also writes an auditable JSONL event log. Practice has its own bounded, atomic,
checksummed registry and backup in the same world data directory. Keep this directory with your world
backups. Failed restoration retains locks and an operator-visible reason; use
`/battlebots recover <arena>` after resolving the problem.

Recovery preserves the existing native bodies, joints and live inventories. It
does not replay arbitrary inventory NBT or create a second robot. Missing bodies,
unexpected structural edits and external destruction are detected rather than
silently overwritten. Use the locked pack for tournaments. Other collision-damage
mods or tools that destroy native structures fall outside this recovery contract.

## Development and license

Original code and JSON models: [MIT](LICENSE). Third-party dependencies retain
their own licenses and artwork. No upstream Sable/Simulated source or artwork is
copied or redistributed by this project.

Development followed the requested
[Minecraft Agent Skills](https://github.com/Jahrome907/minecraft-agent-skills)
modding, testing, and CI guidance, checked against this project's pinned APIs.
The original design is preserved in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
