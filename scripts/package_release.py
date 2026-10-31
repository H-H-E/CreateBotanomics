#!/usr/bin/env python3
"""Package one verified primary JAR, an importable local-play pack, and a server kit."""

from pathlib import Path
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parent.parent
JARS = [path for path in (ROOT / "build/libs").glob("createbattlebots-*.jar") if not path.name.endswith("-sources.jar")]
if len(JARS) != 1:
    raise SystemExit(f"Expected exactly one primary JAR after a clean build, found {len(JARS)}")
jar = JARS[0]
subprocess.run([sys.executable, str(ROOT / "scripts/verify_dist.py"), str(jar)], check=True)
destination = ROOT / "build/distributions"
destination.mkdir(parents=True, exist_ok=True)
subprocess.run([sys.executable, str(ROOT / "scripts/package_modpack.py"), "--mod-jar", str(jar),
                "--output", str(destination / (jar.stem + ".mrpack"))], check=True)
with zipfile.ZipFile(destination / (jar.stem + "-server-kit.zip"), "w", zipfile.ZIP_DEFLATED) as archive:
    archive.write(jar, "mods/" + jar.name)
    for name in ("README.md", "LICENSE", "modpack.lock.json", "scripts/install_modpack.py",
                 "docs/PLAYING.md", "docs/IMPLEMENTATION.md", "docs/VERIFICATION.md",
                 "examples/lua/arena_controller.lua"):
        archive.write(ROOT / name, name)
print(f"Packaged server kit in {destination}")
