# Create: Lanterns compatibility

Battle Bots 0.2.0 targets Minecraft 1.21.1, NeoForge 21.1.247, Create 6.0.10,
Aeronautics / Simulated / Offroad 1.3.2 and the **released Sable 2.0.6 JAR**.
The compile and installed runtime both use that same Modrinth release, not a
newer development snapshot with an assumed matching API.

## Optional installation

Install **Create: Lanterns Alpha 20.11**, CurseForge project 1752310, file 9360511,
on both client and server after installing the locked Battle Bots pack:

https://www.curseforge.com/minecraft/mc-mods/create-lanterns/files/9360511

Its mod metadata requires Sable `[2.0.6,3.0.0)`, NeoForge `[21.1.228,21.2)`,
Create 6.0.10 or newer, and Aeronautics / Simulated 1.3.1 or newer. The selected
versions satisfy these requirements. Other Lanterns releases are not implied
compatible by this check. Remove the old Sable 2.0.4 JAR rather than keeping both.

Lanterns is optional. Battle Bots must load and play without it. CI runs the
actual Minecraft integration suite with and without Lanterns, crossed with
ComputerCraft present and absent. To reproduce the co-load test:

```bash
./gradlew runGameTestServer -PwithLanterns=true
./gradlew runGameTestServer -PwithLanterns=true -PwithComputerCraft=false
```

The optional development dependency is fetched through CurseMaven. Lanterns is
not copied into our source tree, published JAR, server ZIP or Modrinth pack; its
own license continues to apply. Players obtain it from the author.

## Supported boundary

Compatibility here means the pinned mods co-load and Battle Bots' native
physics, starter modules and repair tests run with Lanterns installed. It is
not a promise to restore every possible Lanterns hard-light construct or
third-party joint graph. Use Lanterns normally outside Battle Bots matches.

Only the practice starter's explicitly owned wheel, leg and rotor joints are
physically detached and reconstructed by the new practice mode. Arbitrarily
splitting, deleting, welding or replacing native bodies with external tools
while a match is locked is unsupported and causes a safe recovery stop rather
than silent reconstruction. Keep backups before adding experimental physics
mods to an important world.
