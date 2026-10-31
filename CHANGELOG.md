# Changelog

## 0.1.0 — 2026-10-30

Initial playable implementation of the registered-hit-zone Battle Bots design.

- NeoForge 1.21.1 / Java 21 mod with a pinned Create 6.0.10, Sable 2.0.4 and
  Simulated/Aeronautics/Offroad 1.3.2 runtime.
- Craftable transponder, combat armor, damage relay, garage and arena controller.
- Server-owned robots, arenas, two-competitor rounds, native collisions, bounded
  damage, transparent judging, ring-outs, reconnect grace, surrender and repair.
- Native garage interface, action-bar status, commands and optional read-only
  ComputerCraft combat telemetry.
- Immutable snapshots, synchronous atomic recovery records, protected arenas,
  original player-mode recovery, and auditable event logs.
- Java rule tests, native Sable GameTests, full service GameTests, CI, a launcher
  pack builder and a checksum-verifying dedicated-server installer.

See `docs/IMPLEMENTATION.md` for the recovery boundary and remaining design work.
