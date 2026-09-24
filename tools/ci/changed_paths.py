#!/usr/bin/env python3
"""Classify PR changes for CI jobs; main pushes and scheduled runs execute all jobs."""

from __future__ import annotations

import argparse
import fnmatch
import os
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CITY_PATHS = ROOT / ".github/city-paths.txt"
CITY_INFRASTRUCTURE = {
    ".github/city-paths.txt",
    ".github/workflows/ci.yml",
    "app/game-engine/build.gradle.kts",
    "app/game-engine/src/test/kotlin/opensamguk/engine/boot/HanExpandedCityCommandRoundTripIT.kt",
    "app/game-engine/src/test/kotlin/opensamguk/engine/boot/CityPathGuardTest.kt",
    "tools/ci/changed_paths.py",
    "tools/ci/check_city_shards.py",
    "tools/ci/test_check_city_shards.py",
}


def city_patterns(path: Path = CITY_PATHS) -> dict[str, list[str]]:
    sections: dict[str, list[str]] = {"data": [], "code": []}
    section: str | None = None
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line in ("[data]", "[code]"):
            section = line[1:-1]
        elif section is None or line.startswith("[") or line.startswith("/") or ".." in Path(line).parts:
            raise ValueError(f"invalid city path entry: {line}")
        else:
            sections[section].append(line)
    if not all(sections.values()):
        raise ValueError("city paths need nonempty data and code sections")
    if sum(map(len, sections.values())) != len(set(sum(sections.values(), []))):
        raise ValueError("duplicate city path entry")
    return sections


def changed_files(base: str, head: str) -> list[str]:
    output = subprocess.check_output(
        ["git", "diff", "--name-only", "-z", f"{base}...{head}"], cwd=ROOT
    )
    return [name.decode() for name in output.split(b"\0") if name]


def classify(paths: list[str], patterns: dict[str, list[str]]) -> dict[str, bool]:
    outputs = {key: False for key in ("jvm", "contracts", "map_slow", "external_places", "web", "city")}
    city_globs = patterns["data"] + patterns["code"]
    for path in paths:
        if path in CITY_INFRASTRUCTURE or any(fnmatch.fnmatchcase(path, glob) for glob in city_globs):
            outputs["city"] = True
        if path.startswith(("data/", "tools/", ".github/", "common/", "logic/", "infra/", "app/")) or (
            path.endswith(".gradle.kts") or path.startswith("gradle/") or path in ("gradlew", "gradlew.bat")
        ):
            outputs["jvm"] = outputs["contracts"] = outputs["map_slow"] = True
        if path.startswith(("data/", "tools/map/", "tools/scenario/", ".github/")):
            outputs["external_places"] = True
        if path.startswith(("web/", "data/", "infra/src/main/resources/map/", ".github/")):
            outputs["web"] = True
        # Unknown source/config paths run broad checks rather than silently passing.
        if not path.startswith(("docs/", "reports/", ".ai/", "web/", "data/", "tools/", ".github/",
                                "common/", "logic/", "infra/", "app/")) and path not in (
                                    "README.md", "AGENTS.md", "CLAUDE.md", "LICENSE"
                                ):
            outputs["jvm"] = outputs["contracts"] = outputs["map_slow"] = outputs["web"] = True
    return outputs


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--event", choices=("pull_request", "push", "schedule"), required=True)
    parser.add_argument("--base")
    parser.add_argument("--head")
    args = parser.parse_args()
    patterns = city_patterns()  # Validate the shared allowlist on every run.
    if args.event == "pull_request":
        if not args.base or not args.head:
            parser.error("pull_request requires --base and --head")
        paths = changed_files(args.base, args.head)
        outputs = classify(paths, patterns)
    else:
        paths = []
        outputs = dict.fromkeys(("jvm", "contracts", "map_slow", "external_places", "web", "city"), True)
    print(f"changed files: {len(paths)}; " + ", ".join(f"{key}={value}" for key, value in outputs.items()))
    if target := os.environ.get("GITHUB_OUTPUT"):
        with open(target, "a", encoding="utf-8") as stream:
            for key, value in outputs.items():
                stream.write(f"{key}={str(value).lower()}\n")


if __name__ == "__main__":
    main()
