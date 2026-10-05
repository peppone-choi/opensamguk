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
    "app/game-engine/src/test/kotlin/opensamguk/engine/boot/ExpandedCityPersistenceIT.kt",
    "app/game-engine/src/test/kotlin/opensamguk/engine/boot/CityPathGuardTest.kt",
    "tools/ci/changed_paths.py",
    "tools/ci/check_city_shards.py",
    "tools/ci/test_check_city_shards.py",
}
OUTPUT_KEYS = ("jvm", "contracts", "map", "map_slow", "external_places", "web", "city")
# Every path the Han map/scenario gates read (contracts map steps, map-slow-tests). Measured
# 2026-09-30 by tracing open/scandir/subprocess of each step on a clean checkout, plus a static
# pass for stat-only reads. No map gate reads app/, logic/, gradle files, or common/ and infra/
# outside these entries, so a Kotlin-only PR skips ~15 minutes of map checks. Every tools/**/*.py
# also counts (test_check_map_inputs.py rglobs them); other tools/ files only in the
# directories below (e.g. tools/ci/naming_lint_baseline.json is not a map input). Add the path
# here when a gate starts reading a new file; unknown top-level paths still run everything.
MAP_INPUTS = (
    "data/",
    ".github/workflows/ci.yml",
    ".github/workflows/map-artifact.yml",
    "tools/map/",
    "tools/scenario/",
    "tools/sim/",
    "tools/corpus/",
    "tools/e2e/fixtures/",
    "common/src/main/kotlin/opensamguk/common/constants/",
    "infra/src/main/resources/map/",
    "infra/src/main/resources/campaign/",
    "infra/src/main/resources/scenario/",
    "infra/src/main/kotlin/opensamguk/infra/seed/",
    "infra/src/test/kotlin/opensamguk/infra/seed/",
    "app/game-api/src/main/kotlin/opensamguk/gameapi/read/TopdownMapArtifacts.kt",
    "app/game-api/src/main/kotlin/opensamguk/gameapi/controller/TopdownMapController.kt",
    "web/game/public/map/",
    "web/gateway/public/map/",
    "web/shared/src/iso/countyNameGloss.generated.ts",
    "docs/superpowers/research/2026-09-17-march-tempo-baseline.md",
    "docs/superpowers/research/2026-09-17-siege-supply-baseline.md",
    ".ai/research/2026-08-24-namu-places-crosscheck.md",
)
# Non-map contracts steps that read files outside the JVM/tools prefixes.
CONTRACT_INPUTS = MAP_INPUTS + (
    "web/gateway/app/admin/page.tsx",        # Verify JWT rollout contract
    "docs/admin/game-server-recovery.md",   # Verify game server recovery behavioral guards
    # next build 의 ESLint 오류 게이트를 지키는 시험(test_ci_workflow.test_next_build_still_fails_on_eslint_errors)이 읽는 파일.
    # 이 파일만 바뀐 PR(예: lint 를 끄는 PR)에서도 contracts 가 돌아야 한다(#1306 리뷰, 2026-10-04).
    "web/game/next.config.mjs", "web/gateway/next.config.mjs",
    "web/game/package.json", "web/gateway/package.json",
    "web/game/.eslintrc.json", "web/gateway/.eslintrc.json",
    "web/game/.eslintignore", "web/gateway/.eslintignore",
)


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


# Server sources that web tests read directly (a second axis against the screen's own tables):
# web/shared recordSections.test.ts and gameEvents.test.ts parse EventKind.kt, and gameEvents.test.ts
# scans the engine for `EventKind.X` writers. A server PR that starts writing a new kind must turn
# web-shared red in that PR, not in the next front PR (#1126 review, 2026-10-01).
WEB_SERVER_INPUTS = (
    "logic/src/main/kotlin/opensamguk/logic/record/EventKind.kt",
    "app/game-engine/src/main/kotlin/",
)
# 2026-10-05 K10(ADR-LITE-070): web-shared 잡의 프론트 층 규칙 수 세기(dependency-cruiser)가 읽는 도구 · 기준선.
# 기준선만 내리는 래칫 PR 도 그 잡을 깨워야 한다.
WEB_GATE_INPUTS = (
    "tools/ci/depcruise_",
    "tools/ci/test_depcruise_",
    "tools/ci/ratchet.py",
)


def is_map_input(path: str) -> bool:
    return path.startswith(MAP_INPUTS) or (path.startswith("tools/") and path.endswith(".py"))


def classify(paths: list[str], patterns: dict[str, list[str]]) -> dict[str, bool]:
    outputs = dict.fromkeys(OUTPUT_KEYS, False)
    city_globs = patterns["data"] + patterns["code"]
    for path in paths:
        if path in CITY_INFRASTRUCTURE or any(fnmatch.fnmatchcase(path, glob) for glob in city_globs):
            outputs["city"] = True
        if path.startswith(("data/", "tools/", ".github/", "common/", "logic/", "infra/", "app/")) or (
            path.endswith(".gradle.kts") or path.startswith("gradle/") or path in ("gradlew", "gradlew.bat")
        ):
            outputs["jvm"] = outputs["contracts"] = True
        if path.startswith(CONTRACT_INPUTS):
            outputs["contracts"] = True
        if is_map_input(path):
            outputs["map"] = outputs["map_slow"] = True
        if path.startswith(("data/", "tools/map/", "tools/scenario/", ".github/")):
            outputs["external_places"] = True
        if path.startswith(("web/", "tools/web/", "data/", "infra/src/main/resources/map/", ".github/") + WEB_SERVER_INPUTS
                           + WEB_GATE_INPUTS):
            outputs["web"] = True
        # Unknown source/config paths run broad checks rather than silently passing.
        if not path.startswith(("docs/", "reports/", ".ai/", "web/", "data/", "tools/", ".github/",
                                "common/", "logic/", "infra/", "app/")) and path not in (
                                    "README.md", "AGENTS.md", "CLAUDE.md", "LICENSE",
                                    "NOTICE.md", "CONTRIBUTING.md", "SECURITY.md",
                                ):
            outputs.update(jvm=True, contracts=True, map=True, map_slow=True, web=True)
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
        outputs = dict.fromkeys(OUTPUT_KEYS, True)
    print(f"changed files: {len(paths)}; " + ", ".join(f"{key}={value}" for key, value in outputs.items()))
    if target := os.environ.get("GITHUB_OUTPUT"):
        with open(target, "a", encoding="utf-8") as stream:
            for key, value in outputs.items():
                stream.write(f"{key}={str(value).lower()}\n")


if __name__ == "__main__":
    main()
