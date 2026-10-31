# Implementation contract — 0.1.0

This document separates implemented behavior from the broader design proposal.

## Implemented game loop

- A server-owned registry maps immutable robot IDs to owners and native Sable body
  IDs. One transponder defines the core. Native declared child connections can be
  included; explicit attachment covers additional bodies. World and plot positions
  are transformed at the adapter boundary.
- Two registered robots occupy a named, bounded arena. The server verifies bodies,
  node integrity, placement, ownership and safety budgets before persisting a
  snapshot and starting the countdown.
- During active combat, Sable's real per-block collision callback queues immutable
  observations. It does no gameplay work on the native physics thread. The server
  verifies both participants and the world-space arena boundary before damage.
- Transponders, Combat Armor, and Damage Relays are combat surfaces. Armor collapses
  to a thin remnant when disabled; this preserves a sole-body collider for native recovery. Failed relays emit redstone 15 so builders
  can disengage a clutch. Core failure is a knockout. Ordinary terrain and Create
  machinery are never selected for arbitrary damage by the resolver.
- Finite energy proxies, minimum speed, per-surface cooldowns, hit caps, a bounded
  contact queue, and a shared per-tick failure budget bound damage work.
- Both robots' observations are judged together. The engine handles simultaneous
  failures, ties, ring-outs, surrender, human reconnect grace and timed decisions.
  Intentional stationary tactics are not treated as failure. A generic movement
  command signal is unavailable from arbitrary third-party controls, so automatic
  immobilization detection is not enabled by the Minecraft adapter.
- Component failures supply damage points. Actual post-contact displacement
  supplies bounded control points. Purposeful observed engagement supplies
  cooldown-limited aggression points. These are explicit game rules, not a claim
  of exact engineering simulation or a final tournament balance.
- Recovery restores own-node state and the original poses of the same bodies,
  clears momentum, preserves native joints and inventories, checks identity and
  structure, and releases locks only after a durable completion checkpoint.
- Autonomous pilots have manual interaction/mounting blocked and become spectators.
  A persistent player marker and match snapshot preserve their original modes.
  Onboard redstone or Lua controllers continue through the upstream controls.

## Deliberate recovery boundary

The full design proposes arbitrary part destruction, fragment retirement, complete
native blueprint reconstruction, and reconstruction of every possible third-party
constraint. This release uses the design's permitted **registered hit zones and
bounded component disable** fallback. Disabled cells stay in the original plot;
the mod never duplicates a native body or replays an inventory into a replacement.

A complete blueprint with block states and block-entity data is retained as an
immutable diagnostic backup. The automatic repair path does not blindly replay
that data over a running machine. Doing so while arbitrary modded inventories can
transfer items would risk duplication or deletion. Existing containers stay live.

The mod detects missing bodies, changed block identities, extra/missing structural
blocks and observed split lineage. Such a match remains in recovery. An operator
must correct the external change or restore a world backup; the mod does not
pretend it can reconstruct unknown physics objects.

## Native integration details

Sable 2.0.4 exposes block collision callbacks, persistent sublevel UUIDs, native
poses, velocities, mass, bounds, force-load tickets, and connection declarations.
The integration is written against those APIs. No mixins or copied upstream
implementation are required.

The upstream native callback supplies the **magnitude** of normal point velocity;
it includes rotational motion and discards its original sign. Battle Bots does
not add `omega × radius` a second time. Collision observations are genuine, while
their energy conversion remains a capped gameplay proxy. It cannot recover the
contact normal or impulse discarded by the upstream callback.

`getSplitFromSubLevel` is transient metadata; it is not used as a permanent graph.
Declared native connections and explicit body registration establish ownership.
Nearby bodies are never claimed merely because they overlap.

## Deferred design features

- Arbitrary block destruction, visible free fragments, and general hierarchy
  reconstruction after external damage.
- Best-of-three tournament brackets, matchmaking, event-specific mass/RPM tiers,
  replay visualizations, Ponder tutorials and educational graphs.
- Dedicated Avionics/CC:Sable driving adapters. The combat peripheral supplies
  telemetry; those projects' normal driving APIs remain separate optional tools.
- Comprehensive competition enforcement against every third-party remote-control
  packet implementation. The pinned stock controls and normal player interaction
  paths are the supported baseline.
- Broad modpack compatibility and prolonged public-server load/balance testing.

The pinned native solver has limited precision millions of blocks from the world
origin; tested arenas and physics fixtures use coordinates near the origin. This
is an upstream numerical limit, not a robot-class restriction.

These are not represented by success-returning stubs or advertised as finished.
