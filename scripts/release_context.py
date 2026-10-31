#!/usr/bin/env python3
"""Select a version-changing push without replacing an existing GitHub release."""

import json
import os
from pathlib import Path
import re
import subprocess
import urllib.error
import urllib.parse
import urllib.request


def version_from_properties(contents: str) -> str:
    versions = re.findall(r"^mod_version\s*=\s*(\S+)\s*$", contents, re.MULTILINE)
    if len(versions) != 1:
        raise ValueError("Expected exactly one mod_version in gradle.properties")
    version = versions[0]
    if not re.fullmatch(r"(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?", version):
        raise ValueError(f"Invalid release version: {version!r}")
    return version


def api_exists(path: str) -> bool:
    request = urllib.request.Request(
        os.environ["GITHUB_API_URL"] + path,
        headers={
            "Authorization": "Bearer " + os.environ["GITHUB_TOKEN"],
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            json.load(response)
            return True
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return False
        raise


def main() -> None:
    version = version_from_properties(Path("gradle.properties").read_text())
    tag = "v" + version
    ref_type = os.environ["GITHUB_REF_TYPE"]
    ref_name = os.environ["GITHUB_REF_NAME"]
    reason = None
    if ref_type == "tag":
        if ref_name != tag:
            raise ValueError(f"Pushed tag {ref_name!r} does not match mod_version={version}")
    elif ref_type == "branch" and ref_name == "main":
        before = os.environ["BEFORE_SHA"]
        if not re.fullmatch(r"[0-9a-f]{40,64}", before):
            raise ValueError("Missing or invalid previous push commit")
        if set(before) != {"0"}:
            subprocess.run(["git", "cat-file", "-e", before + "^{commit}"], check=True)
            previous_path = subprocess.check_output(
                ["git", "ls-tree", "--name-only", before, "--", "gradle.properties"], text=True
            ).strip()
            if previous_path:
                previous = subprocess.check_output(["git", "show", before + ":gradle.properties"], text=True)
                if version_from_properties(previous) == version:
                    reason = "mod_version did not change"
    else:
        raise ValueError(f"Unsupported release reference: {ref_type}/{ref_name}")

    repository = os.environ["GITHUB_REPOSITORY"]
    encoded_tag = urllib.parse.quote(tag, safe="")
    if reason is None and ref_type == "branch" and api_exists(f"/repos/{repository}/git/ref/tags/{encoded_tag}"):
        reason = f"tag {tag} already exists; it will not be moved or republished"
    if reason is None and api_exists(f"/repos/{repository}/releases/tags/{encoded_tag}"):
        reason = f"release {tag} already exists; its assets will not be replaced"

    outputs = {
        "version": version,
        "tag": tag,
        "prerelease": str(version.startswith("0.") or "-" in version).lower(),
        "should_publish": str(reason is None).lower(),
    }
    with Path(os.environ["GITHUB_OUTPUT"]).open("a") as output:
        for key, value in outputs.items():
            output.write(f"{key}={value}\n")
    print(f"Skipping publication: {reason}" if reason else f"Testing new release {tag} from this exact commit")


if __name__ == "__main__":
    main()
