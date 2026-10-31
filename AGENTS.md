# Create: Battle Bots development

This is the original Poiesis add-on in the CreateBotanomics repository, not an
upstream fork. Keep Java 21, Minecraft 1.21.1 and the versions in gradle.properties
and modpack.lock.json aligned. Do not copy examples for newer Minecraft APIs.

Read docs/IMPLEMENTATION.md before changing damage, ownership or recovery. The
current game uses registered combat nodes and the same native Sable bodies. Do not
replace it with inventory replay, generic block destruction, or body cloning
without implementing and testing the full corresponding recovery transaction.

Use public APIs from the exact pinned dependency artifacts. Keep client classes
behind physical-client registration and optional ComputerCraft references behind
the loaded-mod guard. Physics callbacks may only hand off bounded immutable data;
world changes and registry operations belong on the server thread.

Build and verify meaningful changes with:

```sh
./gradlew build
./gradlew runGameTestServer
./gradlew runGameTestServer -PwithComputerCraft=false
python3 scripts/package_release.py
```

Native GameTests, normal dedicated-server boot, a local integrated world, and
actual client connections prove different things. Record evidence accurately in
docs/VERIFICATION.md. Test-only mods/classes must not enter the release JAR.

Keep third-party mod binaries out of Git and the server kit; dependency installers use
the official, checksummed download lock. Do not change release destinations or
publish to other mod marketplaces without a user request.
