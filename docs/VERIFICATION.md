# Verification — 0.2.0 playability release

Date: 31 October 2026. The requested Minecraft Agent Skills modding, testing and
CI/release guidance was used; the implementation was checked against the actual
pinned dependency binaries and exercised in Minecraft rather than inferred from
API names.

## Automated proof

- **41 JUnit tests passed**, including eight new input, dead-man timeout, AI and
  sensor-based tutorial tests.
- **15 native Minecraft GameTests passed** with Sable 2.0.6, Create 6.0.10,
  Aeronautics/Simulated/Offroad 1.3.2, Lanterns Alpha 20.11 and ComputerCraft.
- The four new native tests prove forward driving, turning, rotor motion, a
  walking starter, actual wheel/leg detachment, twenty detach/repair cycles,
  preservation of all original body identities and a chest containing seventeen
  diamonds, plus AI pursuit causing actual collision-driven detachment through
  the same damage path used by live practice matches.
- Both no-optional-mod and Lanterns-without-ComputerCraft suites also passed
  before the last damage-balance change. The committed CI matrix reruns all four
  combinations against the submitted revision; the release job independently
  gates publication on those same configurations.

Machine-readable local results and the tested JAR checksum are in
[verification/0.2.0/summary.json](verification/0.2.0/summary.json). GitHub Actions
uploads the full per-configuration reports instead of relying on this document
as a claim of CI success.

## Rendered gameplay and recovery

A real Minecraft client loaded the packaged mod JAR (not a gametest-only
implementation) with Lanterns installed. The B-key garage, generated workshop,
three-step movement/turn/saw tutorial, automatic countdown, AI approach, impact
feedback, detached modules, result screen and automatic repair were exercised
using keyboard/mouse input. Read-only runtime inspection recorded the HUD/session
state alongside the rendered game.

A completed post-balance fight reported **126 impacts and three detached parts**,
ended by decision, and restored both cores to 120 with the rematch control ready.
The live player lost two modules; the remaining native bodies were not replaced.
A prior completed fight ended by ring-out. The existing saved workshop was then
reloaded through a fresh packaged-client process and re-entered successfully.
The native repair test separately proves twenty resets without extra bodies or
inventory duplication.

Two defects found during actual play were fixed rather than hidden: starter
copper decoration oxidized and correctly failed the snapshot identity check, so
it now uses waxed copper; and Sable's impulse methods use local coordinates while
its velocity readback is world-space, so arcade steering/drive impulses now
explicitly transform between frames. The AI collision test covers rotated bots.
The initial damage balance also produced too few module failures; the tuned
setting is covered by a real-contact detachment assertion, not a synthetic hit.

A dedicated NeoForge server with the new dependency stack and practice dimension
reached its ready state. The final native suites run server-side without client
classes. The packaged-client runs were on Linux x86_64 using a software renderer;
this is not a performance benchmark for a player's GPU. Sable emitted transient
late movement-packet warnings while entering a previously loaded workshop; no
session failure occurred in the completed round. Windows/macOS native execution,
long public-server load tests and new-release multi-client play have not been
claimed from these checks.

The physical destruction guarantee is deliberately scoped to the starter's
tracked native modules. The walker uses arcade traction assistance. Do not infer
arbitrary Lanterns construct restoration or every third-party joint graph from a
successful co-load test. See [LANTERNS.md](LANTERNS.md) and
[IMPLEMENTATION.md](IMPLEMENTATION.md).

---

## Archived evidence for the earlier 0.1.0 release

The following is historical evidence, not a report of additional 0.2.0 runs.

# Verification — 0.1.0

These are executed results for the initial release, not a checklist of intended tests.

## Artifact

`createbattlebots-0.1.0+mc1.21.1.jar`

SHA-256:

```text
918d8b5775c0e51a3328fdc1cbfb427b7dc6d4d3a260e354a1239f65aaf2822b
```

The final Gradle build passed. The distributable validator found 119 entries,
correct expanded metadata, valid JSON resources, and no test mod or bundled
upstream JAR. The client pack and server kit contain this same first-party JAR.

## Executed automated checks

| Check | Result |
| --- | --- |
| Java 21 `./gradlew build` | 33 unit tests passed; zero failures, errors or skips |
| Minecraft GameTests, CC: Tweaked 1.115.1 present | 11 required tests passed; server exit 0 |
| Minecraft GameTests, ComputerCraft absent | The same 11 required tests passed; server exit 0 |
| Dependency publication match | All Create Java classes and Sable Java/native binaries match the official pinned runtime releases |
| Distribution ZIPs and mod metadata | Valid; the primary JAR is distinct from the sources JAR |
| Minecraft skill test-layout validator | Passed, zero warnings |

The native and service tests run in real Minecraft/NeoForge with the pinned
physics libraries. Service player fixtures are real server player objects with
NeoForge's supported mock network connections; they are not substitutes for the
separate two-rendered-client network check below.

### Three native physics tests

1. Twenty repair cycles on the original body preserve its UUID, block/entity
   counts, placement, and all 17 diamonds in a chest. Native holding-index, block
   state, block entity and pose data are flushed to disk and read back. Stale
   own loading tickets are removed while active and operator tickets remain.
2. Twenty cycles on three bodies preserve two actual native rotary constraints,
   including a child whose only armor collider is disabled for a real physics
   tick before repair. No duplicate bodies or orphan fragments appear.
3. Two physically moving bodies produce real, correctly attributed block contact
   callbacks with finite impact speeds, while terrain remains untouched.

### Eight service tests

1. Register two assembled robots, start, count down, enter active combat, stop,
   repair and release locks. Inventory, native identity and exact placement are
   preserved. A disabled relay outputs 15 and a repaired relay returns to 0.
2. Reject wrong owners, missing referee privileges, forged menu actions, excessive
   console distance, and stale packets after the console block changes.
3. Protect an active arena against creative breaking, opponent interactions,
   inventory access and bucket interaction.
4. Clear the live controllers, reload the actual on-disk registry, recover both
   robots, and restore different original pilot game modes. A stale spectator
   marker still restores its owner after the recovery registry has committed.
5. Corrupt an immutable snapshot, retain protection and recovery state, then retry
   successfully with the verified original.
6. Reject unexpected structural additions before partially repairing or deleting
   unrelated blocks.
7. Route an actual native two-robot collision through the authoritative resolver,
   damage a registered target, and expose the contact through combat telemetry.
8. Corrupt the checked registry payload, refuse an empty/unlocked fallback, leave
   the damaged bytes untouched, and retain the pending recovery after restoration
   of the valid file.

The tests caught real implementation issues that were corrected before this
release: native loading policy, destruction of zero-collider bodies, center-of-mass
changes during repair, asynchronous vanilla save behavior, recovery checkpoint
ordering, and manual-control authorization paths.

## Real client and production server checks

- An installed NeoForge dedicated server loaded the official release dependencies
  and the built mod, initialized native physics, and executed diagnostics.
- Two separate rendered clients joined the loopback server simultaneously,
  completed the NeoForge/Sable handshakes, appeared together in the player list,
  opened the garage and received server-generated help. Both exited cleanly.
- The final exact JAR subsequently passed a production-server restart with a
  persisted arena, diagnostics, save-all and clean shutdown.
- An isolated packaged-client run loaded that exact JAR without main source-set
  classes/resources, entered a real local integrated world, rendered the console,
  opened the complete garage at 854 × 480 / GUI scale 2, used its Instructions
  button, saved and exited cleanly.

See [runtime evidence and screenshots](verification/README.md).

## Practical limits of this evidence

This establishes installation, native collision/repair behavior, server rules,
recovery, local-world operation, and two-client connectivity. It is not prolonged
public-server load testing or a claim that humans played every possible flying,
walking, weapon and third-party controller combination.

Use arenas near the world origin. In testing, the pinned native solver lost useful
movement precision at a randomly generated GameTest origin around 5.6 million
blocks; the same physical contact tests pass consistently near the origin. The
mod does not impose an arbitrary distance cap or fix the upstream solver's float
precision.

The cloud runtime supplied no sound device or signed-in Minecraft account, so
audio playback and public-server account authentication were not exercised. The
two-client check used an isolated loopback test server. Use normal online account
authentication for a public server.

Arbitrary destruction and reconstruction of third-party machines remains outside
the implemented hit-zone recovery contract; see [implementation scope](IMPLEMENTATION.md).
