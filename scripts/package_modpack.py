#!/usr/bin/env python3
"""Create a Modrinth-compatible local-play .mrpack with verified upstream downloads.

Only this project's own compiled mod is bundled under overrides/mods. Third-party
mods remain references to their official, SHA-512-verified Modrinth downloads.
"""

import argparse
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parent.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mod-jar", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    lock = json.loads((ROOT / "modpack.lock.json").read_text())
    jar = args.mod_jar.resolve(strict=True)
    if jar.name.endswith("-sources.jar") or not jar.name.startswith("createbattlebots-"):
        parser.error("Select the compiled mod JAR")
    version = jar.name.removeprefix("createbattlebots-").removesuffix(".jar")
    index = {
        "formatVersion": 1,
        "game": "minecraft",
        "versionId": version,
        "name": "Create: Battle Bots",
        "summary": "Poiesis robot arenas with Create Aeronautics and automatic match restoration.",
        "dependencies": {"minecraft": lock["minecraft"], "neoforge": lock["loader"]["version"]},
        "files": [{
            "path": "mods/" + mod["filename"],
            "hashes": {"sha512": mod["sha512"], "sha1": mod["sha1"]},
            "env": {"client": "optional" if mod["optional"] else "required",
                    "server": "optional" if mod["optional"] else "required"},
            "downloads": [mod["url"]],
            "fileSize": mod["size"],
        } for mod in lock["mods"] if mod.get("default", True)],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.output, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("modrinth.index.json", json.dumps(index, indent=2) + "\n")
        archive.write(jar, "overrides/mods/" + jar.name)
        for name in ("README.md", "LICENSE", "modpack.lock.json", "docs/PLAYING.md",
                     "docs/IMPLEMENTATION.md", "docs/VERIFICATION.md", "examples/lua/arena_controller.lua"):
            if (ROOT / name).is_file():
                archive.write(ROOT / name, "overrides/battlebots/" + name)
    print(f"Created {args.output}; SHA256 {hashlib.sha256(args.output.read_bytes()).hexdigest()}")


if __name__ == "__main__":
    main()
