# Runtime smoke evidence

These screenshots were captured from actual Minecraft 1.21.1 windows, rendered
using Mesa llvmpipe/OpenGL 4.5 on Xvfb. They are not mockups.

## Observed checks

- A packaged Battle Bots JAR loaded on a production NeoForge 21.1.247 dedicated
  server with the official locked Create 6.0.10, Aeronautics/Simulated/Offroad
  1.3.2, Sable 2.0.4, and CC: Tweaked 1.115.1 releases. Native Rapier scenes
  initialized and `/battlebots diagnostics` executed successfully.
- A real client created a creative single-player world, joined its integrated
  server, and rendered a placed Garage Console. The final packaged JAR then
  reopened that world successfully. See [local-world.png](local-world.png).
- Two separate rendered clients, `BattlePilotOne` and `BattlePilotTwo`, joined
  the dedicated server simultaneously. Both completed the NeoForge handshake
  and Sable UDP authentication. The server's `list` command reported both
  players. See [two-player-server.png](two-player-server.png).
- Opening the Garage Console over that network connection displayed the real
  registered menu. Its Instructions button returned the server's gameplay help.
- The final packaged JAR also opened the updated Garage Console in single-player,
  displayed its bound arena's Workshop state, and handled its Instructions
  button. The complete panel fits the 854 × 480 window at GUI scale 2, with
  tutorials disabled. See [garage-menu.png](garage-menu.png).
- Both clients then closed normally with exit code 0. The dedicated server
  saved its world and stopped normally.
- The dedicated server restarted with the final JAR, recovered the saved arena,
  reported zero active/recovery records, saved again, and exited with code 0.
- The final build passed all 33 JUnit tests. Both actual Minecraft integration
  runs passed all 11 GameTests, with CC: Tweaked present and with it absent.

The final packaged-client and dedicated-server checks used
`createbattlebots-0.1.0+mc1.21.1.jar` with SHA-256
`918d8b5775c0e51a3328fdc1cbfb427b7dc6d4d3a260e354a1239f65aaf2822b`.
The packaged-client run excluded the main source-set classes and resources from
its runtime classpath and loaded the JAR from its `mods` directory. The
two-client connection check preceded the last persistence and menu refinements;
the final JAR checks then exercised the revised code on both runtime sides.

The screenshots document startup, local world loading, block rendering,
two-client connectivity, and menu interaction. They do **not** establish that
two human players completed every robot class or weapon combination. Match,
restoration, and native collision assertions are covered separately by the
repository's automated tests and their reported results.

## Reproducing two-client testing

Use Java 21 and an isolated development server bound to `127.0.0.1`. An offline
test profile is appropriate only for that private loopback server; retain normal
account authentication on a public server.

```sh
./gradlew runClient -PplayerName=BattlePilotOne \
  -PclientDirectory=run/client-one -PjoinServer=127.0.0.1:25574 \
  -PrunMaxMemory=1200m

./gradlew runClient -PplayerName=BattlePilotTwo \
  -PclientDirectory=run/client-two -PjoinServer=127.0.0.1:25574 \
  -PrunMaxMemory=1200m
```

Use separate terminals/displays and lower view distance for a small test
machine. Run GameTests before or after this session. Running several additional
Minecraft/Gradle JVMs at once exhausted the 10 GB test environment during initial
attempts; the scheduled, lower-memory client checks above completed cleanly.

The cloud test environment had no sound device or signed-in Minecraft account.
Sound playback and public-server account authentication were not exercised.

## Reproducing the packaged-client check

Build the JAR, copy the primary artifact (not the sources JAR) into
`run/packaged-client/mods`, then use `./gradlew runPackagedClient`. This isolated
run loads the JAR instead of the project's compiled main classes. The optional
`-PjoinWorld=WorldFolderName` opens an existing world in that run directory.
