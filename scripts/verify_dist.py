#!/usr/bin/env python3
"""Validate the distributable rather than mistaking a sources/test JAR for a mod."""

import argparse
import hashlib
import json
from pathlib import Path
import tomllib
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path)
    args = parser.parse_args()
    with zipfile.ZipFile(args.jar) as archive:
        names = archive.namelist()
        assert len(names) == len(set(names)), "Duplicate archive entries"
        assert "education/poiesis/createbattlebots/CreateBattleBots.class" in names, "Missing mod entry point"
        assert not any("GameTests.class" in p or "/gametest/" in p or p.startswith("META-INF/jarjar/") for p in names), "Test classes or upstream libraries leaked into release"
        metadata = tomllib.loads(archive.read("META-INF/neoforge.mods.toml").decode())
        assert metadata["mods"][0]["modId"] == "createbattlebots"
        assert "${" not in metadata["mods"][0]["version"], "Unexpanded version"
        assert metadata["mods"][0]["version"].endswith("+mc1.21.1")
        for name in names:
            if name.endswith(".json"):
                json.loads(archive.read(name))
        assert any(p.startswith("assets/createbattlebots/") for p in names), "Missing client resources"
        print(f"Verified {args.jar.name}: {len(names)} entries, version {metadata['mods'][0]['version']}")
        print("SHA256 " + hashlib.sha256(args.jar.read_bytes()).hexdigest())


if __name__ == "__main__":
    main()
