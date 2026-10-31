#!/usr/bin/env python3
"""Install the locked runtime without redistributing third-party mod binaries.

Client: python3 scripts/install_modpack.py --target /path/to/instance --mod-jar build/libs/createbattlebots-0.2.0+mc1.21.1.jar
Server: add --server, and read https://aka.ms/MinecraftEULA before --accept-eula.
The client instance must already use the locked NeoForge version and Java 21.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import urllib.parse


ROOT = Path(__file__).resolve().parent.parent


def digest(path: Path, algorithm: str) -> str:
    value = hashlib.new(algorithm)
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def download(url: str, destination: Path, checksum: str, algorithm: str, size: int | None = None) -> None:
    if not url.startswith("https://"):
        raise ValueError("Locked downloads must use HTTPS")
    if destination.exists() and digest(destination, algorithm) == checksum:
        print(f"Verified existing {destination.name}")
        return
    if destination.exists():
        raise ValueError(f"{destination} exists but differs from the lock. Move it aside before retrying.")
    destination.parent.mkdir(parents=True, exist_ok=True)
    request = urllib.request.Request(url, headers={"User-Agent": "Poiesis-CreateBattleBots/0.1.0"})
    temporary: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(dir=destination.parent, suffix=".download", delete=False) as output:
            temporary = Path(output.name)
            with urllib.request.urlopen(request, timeout=120) as response:
                shutil.copyfileobj(response, output)
        if size is not None and temporary.stat().st_size != size:
            raise ValueError(f"Unexpected download length for {destination.name}")
        if digest(temporary, algorithm) != checksum:
            raise ValueError(f"Checksum mismatch for {destination.name}; downloaded file discarded")
        temporary.replace(destination)
        print(f"Downloaded and verified {destination.name}")
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def java21(executable: str) -> None:
    result = subprocess.run([executable, "-version"], text=True, capture_output=True, check=True)
    match = re.search(r'version "(\d+)', result.stderr + result.stdout)
    if match is None or int(match.group(1)) != 21:
        raise ValueError("Select Java 21 with --java /path/to/java. The locked runtime is tested on Java 21.")


def java_proxy_arguments() -> list[str]:
    """Respect the caller's configured network path; Java does not read HTTP(S)_PROXY."""
    result = []
    for scheme in ("http", "https"):
        raw = os.environ.get(scheme.upper() + "_PROXY") or os.environ.get(scheme + "_proxy")
        if not raw:
            continue
        proxy = urllib.parse.urlparse(raw)
        if not proxy.hostname:
            raise ValueError(f"Invalid {scheme.upper()}_PROXY URL")
        if proxy.username or proxy.password:
            raise ValueError("Authenticated installer proxies must be configured through the JVM; credentials are never added to command arguments")
        result.extend((f"-D{scheme}.proxyHost={proxy.hostname}", f"-D{scheme}.proxyPort={proxy.port or 80}"))
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", type=Path, required=True, help="Minecraft instance or new dedicated-server folder")
    parser.add_argument("--mod-jar", type=Path, required=True, help="Built Create: Battle Bots distributable JAR")
    parser.add_argument("--without-computercraft", action="store_true", help="Skip the optional Lua-computer mod")
    parser.add_argument("--server", action="store_true", help="Install the locked NeoForge dedicated server")
    parser.add_argument("--accept-eula", action="store_true", help="Confirm you accept https://aka.ms/MinecraftEULA")
    parser.add_argument("--java", default="java", help="Java 21 executable, used for server installation")
    args = parser.parse_args()
    lock = json.loads((ROOT / "modpack.lock.json").read_text(encoding="utf-8"))
    if lock["format"] != 1:
        raise ValueError("Unsupported modpack lock format")
    source = args.mod_jar.resolve(strict=True)
    if source.suffix != ".jar" or source.name.endswith("-sources.jar") or not source.name.startswith("createbattlebots-"):
        raise ValueError("--mod-jar must point to the compiled createbattlebots JAR, not the sources JAR")
    target = args.target.resolve()
    target.mkdir(parents=True, exist_ok=True)
    mods = target / "mods"
    mods.mkdir(exist_ok=True)
    other_versions = [p for p in mods.glob("createbattlebots-*.jar") if p.name != source.name]
    if other_versions:
        raise ValueError("Move the previous Battle Bots version out of mods before upgrading: " + ", ".join(map(str, other_versions)))

    for mod in lock["mods"]:
        if mod["optional"] and args.without_computercraft:
            continue
        name = mod["filename"]
        if Path(name).name != name:
            raise ValueError("Unsafe filename in modpack lock")
        download(mod["url"], mods / name, mod["sha512"], "sha512", mod["size"])

    destination = mods / source.name
    if destination.resolve() != source:
        shutil.copy2(source, destination)
    print(f"Installed {source.name}; SHA256 {digest(source, 'sha256')}")

    if args.server:
        java21(args.java)
        loader = lock["loader"]
        installer = target / f"neoforge-{loader['version']}-installer.jar"
        download(loader["installer_url"], installer, loader["installer_sha256"], "sha256")
        subprocess.run([args.java, *java_proxy_arguments(), "-jar", str(installer), "--installServer"], cwd=target, check=True)
        if args.accept_eula:
            (target / "eula.txt").write_text("# Accepted via --accept-eula. https://aka.ms/MinecraftEULA\neula=true\n", encoding="utf-8")
        else:
            print("Read https://aka.ms/MinecraftEULA and set eula=true in eula.txt before starting the server.")
        print("Start the server from its folder with ./run.sh nogui (Windows: run.bat nogui).")
    else:
        print(f"Launch this instance with Minecraft {lock['minecraft']}, NeoForge {lock['loader']['version']}, and Java {lock['java']}.")
    print("Use the same locked mod versions on every connected client and server.")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"Installation stopped: {error}", file=sys.stderr)
        sys.exit(1)
