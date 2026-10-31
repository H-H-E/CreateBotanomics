# Build your first Battle Bots round

Battle Bots runs in a singleplayer world, an opened-to-LAN world, or a dedicated
NeoForge server. Install the same pinned mod stack on the server and every client;
see the repository README for installation and tested versions.

The mod supplies the arena, hit zones, scoring, and repair loop. Build the machine
with Create, Aeronautics, and Offroad parts, then assemble it through those mods.
Its shape, steering, flight, and walking mechanism remain your engineering choices.

## 1. Prepare a place to fight

Start in a creative test world with cheats enabled, or ask a server operator to set
up an arena. Build a sturdy floor and enough space for both machines to turn. Give
flying bots a generous ceiling. These example bounds cover a 49 × 34 × 49 volume:

```mcfunction
/battlebots arena create workshop -24 62 -24 24 95 24
```

Use coordinates appropriate to your world. Arena coordinates are ordinary world
coordinates; both corner blocks are included. Arena volumes must not overlap.
Place a Garage Console or Arena Controller inside the arena to open its controls.
The console must remain within 4.5 blocks of the player using it.

`/battlebots arena list` shows the existing arenas. Operators can remove an unused
arena with `/battlebots arena remove workshop`.

## 2. Build two machines

Each competitor needs a Battle Transponder on its assembled machine. The
transponder identifies the bot and provides its knockout target. Protect it, but
leave contact surfaces that can be hit during a match.

The other Battle Bots components are:

| Block | What it does |
| --- | --- |
| Combat Armor | A physical hit zone that collapses when disabled, collapsing to a thin remnant until repaired. |
| Damage Relay | A physical hit zone that emits redstone level 15 when disabled. Connect that output to a Create clutch to make damage stop a drive or weapon. |
| Garage Console | Opens the match controls and instructions. |
| Arena Controller | Opens the same controls, with a visually distinct block for arena builds. |

These blocks are craftable and appear in the **Create: Battle Bots** creative tab.
Place the transponder and hit zones before assembly. Each bot owner should place
their own transponder, then register their machine while standing nearby.

Use the upstream mods' own Ponder scenes to learn their assembly and controls.
There is no Battle Bots chassis recipe or required wheel arrangement.

## 3. Register the assembled bots

Right-click a transponder to inspect its position and state. Assembled Sable blocks
have plot coordinates, which differ from their visible world coordinates. Use the
transponder's reported position for registration; do not substitute the player's
F3 position.

```mcfunction
/battlebots bot register wedge <transponder-x> <transponder-y> <transponder-z> ground
/battlebots bot register spinner <transponder-x> <transponder-y> <transponder-z> ground
```

Replace the angle-bracketed arguments with the actual numbers; do not type the
brackets. The optional class is `ground`, `aerial`, `walking`, or `hybrid`. Classes
are labels and do not restrict your construction.

If wheels, rotors, limbs, or other moving assemblies are separate Sable bodies,
registration includes bodies declared by the native connection API. Use explicit attachment for any additional body before a match:

```mcfunction
/battlebots bot attach wedge <part-x> <part-y> <part-z>
```

The position must identify a block in that body's plot. Explicit body registration
avoids guessing ownership from which structures happen to be close together.
`/battlebots bot list` shows the registered body count.

Keep both machines inside the arena, separated and stopped in the positions you
want them to return to. Those pre-match positions become the restoration poses.

## 4. Join and start

Each owner enters their own bot:

```mcfunction
/battlebots join workshop wedge
/battlebots join workshop spinner
```

An operator then starts the round from the console or a command:

```mcfunction
/battlebots start workshop human
```

The defaults are a five-second countdown and a three-minute round. The server
captures recovery data before starting. Once combat begins, pilot the machines
using their normal Create/Aeronautics controls.

For an autonomous round, install and configure your onboard controller beforehand,
then use:

```mcfunction
/battlebots start workshop autonomous
```

Autonomous competitors use onboard redstone/computer control. Human interaction is
locked for the round. A computer's presence alone does not qualify a bot as
autonomous; it must actually run its control program.

Singleplayer uses the same rules. You can register two machines for local practice,
control one while the other follows a prepared mechanism/program, or invite a
second player through LAN. Multiplayer human rounds work best with one pilot per
bot.

## 5. Fight, inspect, and iterate

Impact damage applies to registered Battle Bots hit zones during active combat.
Disabled relays let your own redstone circuit turn a scored hit into a functional
failure. Combat Armor can collapse; ordinary machine blocks are not arbitrarily
deleted by this mod.

The round can end through core knockout, ring-out, surrender, an unrecoverable
physics fault, a human pilot's expired reconnect grace, or the judges' decision
when time expires. Simultaneous failures and exact tied decisions draw.

```mcfunction
/battlebots status workshop
/battlebots surrender
```

The status readout reports the official match state and scores. Damage points come
from verified opponent-caused component failures; engagement and control are
separate factors. Repeated callbacks for one contact cannot farm points without
cooldowns.

After combat, the server restores the controlled damage and returns the registered
bodies to their saved poses. Build changes belong between rounds. Remove a bot
from the roster before rebuilding it:

```mcfunction
/battlebots leave workshop wedge
/battlebots bot repair wedge
```

If a redesign changes the transponder or assembled body identities, unregister and
register it again:

```mcfunction
/battlebots bot unregister wedge
```

## Recovery and referee commands

| Command | Purpose |
| --- | --- |
| `/battlebots stop <arena>` | Stop the round and begin restoration. Operator only. |
| `/battlebots recover <arena>` | Retry a blocked recovery. Operator only. |
| `/battlebots diagnostics` | Report installed integration/runtime details. |
| `/battlebots help` | Show the in-game command guide. |
| `/battlebots bot repair <bot>` | Repair an owned bot outside combat. |

A failed recovery stays locked instead of reporting success. Missing native bodies,
external block edits, or incompatible destructive mods require inspection before a
retry. Recovery restores the same native bodies; it does not clone an arbitrary
third-party joint hierarchy or synthesize a missing physics body.

The intended stack uses **one arena damage controller**. Disable other collision,
explosion, or destruction systems that can bypass Battle Bots' controlled hit-zone
policy; otherwise their changes are outside this mod's restoration contract.

Server safety limits and match timing are in the world's generated
`serverconfig/createbattlebots-server.toml`. Changes apply to subsequent matches.
