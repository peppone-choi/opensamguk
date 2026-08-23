#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

python3 -c '
import re
import subprocess
import sys

def git(args: list[str]) -> bytes:
    proc = subprocess.run(["git", *args], check=False, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if proc.returncode != 0:
        message = proc.stderr.decode("utf-8", "replace").strip()
        print(f"BLOCKED: protected diff check failed: {message}", file=sys.stderr)
        raise SystemExit(2)
    return proc.stdout

def is_test(path: str) -> bool:
    original = path.replace("\\", "/").rsplit("/", 1)[-1]
    normalized = "/" + path.replace("\\", "/").lower().lstrip("/")
    name = normalized.rsplit("/", 1)[-1]
    return (
        any(segment in normalized for segment in ("/src/test/", "/test/", "/tests/", "/__tests__/"))
        or re.search(r"(?:^test_.*|.*_test)\.py$", name) is not None
        or re.search(r"(?:^|[._-])(?:test|spec)\.[^.]+(?:\.[^.]+)?$", name) is not None
        or re.search(r"(?:Test|Tests|IT)\.(?:kt|java|groovy)$", original) is not None
    )

def is_test_directory(path: str) -> bool:
    normalized = "/" + path.replace("\\", "/").lower().strip("/") + "/"
    return any(segment in normalized for segment in ("/src/test/", "/test/", "/tests/", "/__tests__/"))

def is_golden(path: str) -> bool:
    normalized = "/" + path.replace("\\", "/").lower().lstrip("/")
    return (
        "/resources/golden/" in normalized
        or ("/src/test/" in normalized and "/golden/" in normalized)
        or ("/src/test/" in normalized and normalized.endswith(("goldentest.kt", "goldenit.kt")))
    )

def parse_name_status(raw: bytes) -> list[tuple[str, ...]]:
    tokens = [token.decode("utf-8", "surrogateescape") for token in raw.split(b"\0") if token]
    entries: list[tuple[str, ...]] = []
    cursor = 0
    while cursor < len(tokens):
        status = tokens[cursor]
        width = 3 if status.startswith(("R", "C")) else 2
        if cursor + width > len(tokens):
            print("BLOCKED: malformed protected name-status stream", file=sys.stderr)
            raise SystemExit(2)
        entries.append(tuple(tokens[cursor:cursor + width]))
        cursor += width
    return entries

head_paths = git(["ls-tree", "-r", "--name-only", "HEAD"]).decode("utf-8", "surrogateescape").splitlines()
index_paths = git(["ls-files"]).decode("utf-8", "surrogateescape").splitlines()
baseline = frozenset(path for path in {*head_paths, *index_paths} if is_test(path) or is_golden(path))

diff_args = [
    ["diff", "--find-renames", "--name-status", "-z"],
    ["diff", "--cached", "--find-renames", "--name-status", "-z"],
]
base = subprocess.run(
    ["git", "merge-base", "origin/main", "HEAD"], check=False, text=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL
)
if base.returncode == 0 and base.stdout.strip():
    diff_args.insert(0, ["diff", "--find-renames", "--name-status", "-z", f"{base.stdout.strip()}...HEAD"])

violations: set[str] = set()
for args in diff_args:
    for entry in parse_name_status(git(args)):
        status, source = entry[:2]
        protected_source = source in baseline or is_test(source) or is_golden(source)
        if status == "D" and protected_source:
            violations.add(source)
        elif status.startswith("R") and len(entry) == 3 and protected_source:
            destination = entry[2]
            if is_golden(source) and not is_golden(destination):
                violations.add(f"{source} -> {destination}")
            elif not is_golden(source) and not (is_test_directory(destination) or is_test(destination)):
                violations.add(f"{source} -> {destination}")

if violations:
    for violation in sorted(violations):
        print(f"BLOCKED: frozen test/golden deletion or rename-out: {violation}", file=sys.stderr)
    raise SystemExit(2)
'

SUMMARY="$(scripts/agent/verify-changes.sh | sed -n '/== 필요한 최소 검증/,$p')"
printf '%s' "$SUMMARY" | python3 -c '
import json
import sys

summary = sys.stdin.read()
message = (
    "Codex Agent OS verification reminder. Run $os-verify or "
    "scripts/agent/verify-changes.sh --run before completion.\n"
    + summary[:3500]
)
print(json.dumps({"systemMessage": message}, ensure_ascii=False))
'
