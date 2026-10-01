#!/usr/bin/env python3
"""Ratchet product naming and retired SAMMO references (ADR-LITE-066)."""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import os
import re
from collections import Counter
from pathlib import Path

from lint_files import git_visible_files, is_visible
from ratchet import allowlist_growth, judge, tree_at, write_baseline

ROOT = Path(__file__).resolve().parents[2]
BASELINE = Path(__file__).with_name("naming_lint_baseline.json")
ALLOWLIST = Path(__file__).with_name("naming_lint_allowlist.json")
SOURCE_ROOTS = ("common", "logic", "infra", "app", "web", "tools")
SOURCE_SUFFIXES = {".kt", ".kts", ".java", ".ts", ".tsx", ".js", ".mjs", ".py", ".php", ".sh", ".sql", ".json", ".yml", ".yaml"}
SKIP_DIRS = {".git", ".next-topdown-screens", ".gradle", ".next", "build", "dist", "node_modules", "__pycache__", "coverage"}
PATTERNS = {
    # Product identifiers are ASCII; Unicode word boundaries differ between Python releases.
    "product_identifier": re.compile(r"\b(?:Hwiha|hwiha|V2|v2)[A-Z][A-Za-z0-9_]*\b", re.ASCII),
    "retired_reference": re.compile(r"SAMMO|(?<![A-Za-z0-9_])che_|CommandRegistry|(?:Public)?AlphaCommandCatalog"),
}
PACKAGE = re.compile(r"^\s*package\s+(opensamguk(?:\.[A-Za-z_][A-Za-z0-9_]*)+)\s*$")
FORBIDDEN_PACKAGE_PARTS = {"hwiha", "v2"}
KINDS = ("product_identifier", "product_path", "package_name", "retired_reference")


def source_files(root: Path):
    # git 이 무시하는 파일(로컬 e2e 결과 · 생성물)은 세지 않는다 — lint_files 참고. 비 git 트리는 전부 훑는다.
    visible = git_visible_files(root)
    for source_root in SOURCE_ROOTS:
        base = root / source_root
        if not base.is_dir():
            continue
        for directory, dirs, files in os.walk(base):
            dirs[:] = sorted(d for d in dirs if d not in SKIP_DIRS)
            for name in sorted(files):
                path = Path(directory) / name
                if (path.suffix in SOURCE_SUFFIXES and path.is_file() and not path.is_symlink()
                        and is_visible(path, root, visible)):
                    yield path


def load_allowlist(path: Path, root: Path) -> dict[str, dict]:
    entries = json.loads(path.read_text(encoding="utf-8"))["paths"]
    allowed = {}
    for entry in entries:
        name = entry["path"]
        if name in allowed or name.startswith("/") or ".." in Path(name).parts:
            raise ValueError(f"invalid or duplicate allowlist path: {name}")
        if not entry.get("reason") or not (root / name).is_file():
            raise ValueError(f"allowlist needs an existing file and reason: {name}")
        digest = entry.get("sha256")
        if digest and hashlib.sha256((root / name).read_bytes()).hexdigest() != digest:
            raise ValueError(f"allowed immutable file changed: {name}")
        allowed[name] = entry
    return allowed


def scan(root: Path, allowlist: dict[str, dict]) -> tuple[Counter, dict[str, list[str]]]:
    counts: Counter = Counter({kind: 0 for kind in KINDS})
    findings: dict[str, list[str]] = {kind: [] for kind in KINDS}
    for path in source_files(root):
        relative = path.relative_to(root).as_posix()
        exemptions = set(allowlist.get(relative, {}).get("kinds", ()))
        if exemptions - set(KINDS):
            raise ValueError(f"unknown allowlist kind: {relative}")
        if "product_path" not in exemptions:
            parts = path.relative_to(root).parts
            if any(part.lower() in FORBIDDEN_PACKAGE_PARTS for part in parts[:-1]) or re.match(r"^(?:Hwiha|hwiha|V2[A-Z])", path.stem):
                counts["product_path"] += 1
                findings["product_path"].append(relative)
        content = path.read_text(encoding="utf-8", errors="replace")
        for line_number, line in enumerate(content.splitlines(), 1):
            for kind, pattern in PATTERNS.items():
                if kind in exemptions:
                    continue
                for match in pattern.finditer(line):
                    counts[kind] += 1
                    findings[kind].append(f"{relative}:{line_number}:{match.group()}")
            if "package_name" not in exemptions:
                package = PACKAGE.match(line)
                if package and FORBIDDEN_PACKAGE_PARTS.intersection(package.group(1).lower().split(".")):
                    counts["package_name"] += 1
                    findings["package_name"].append(f"{relative}:{line_number}:{package.group(1)}")
    return counts, findings


def check(counts: Counter, baseline: dict[str, int], base: dict[str, int] | None = None) -> list[str]:
    if set(baseline) != set(KINDS) or any(type(value) is not int or value < 0 for value in baseline.values()):
        raise ValueError("baseline must have a nonnegative integer for every kind")
    return judge(counts, baseline, base, KINDS, BASELINE.name)[0]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--baseline", type=Path, default=BASELINE)
    parser.add_argument("--allowlist", type=Path, default=ALLOWLIST)
    parser.add_argument("--counts", action="store_true", help="print measured counts as JSON")
    parser.add_argument("--base-ref", help="merge-base commit: fail only above min(baseline, counts at this commit) — tools/ci/ratchet.py")
    parser.add_argument("--repo", type=Path, default=ROOT, help="git repository holding --base-ref")
    parser.add_argument("--write-baseline", action="store_true", help="write measured counts to the baseline (ratchet PR)")
    args = parser.parse_args()
    root = args.root.resolve()
    try:
        allowed = load_allowlist(args.allowlist, root)
        counts, findings = scan(root, allowed)
        if args.counts:
            print(json.dumps(dict(counts), indent=2, sort_keys=True))
            return 0
        if args.write_baseline:
            write_baseline(args.baseline, counts, KINDS)
            print(f"wrote {args.baseline.name}: " + json.dumps({kind: counts[kind] for kind in KINDS}, sort_keys=True))
            return 0
        baseline = json.loads(args.baseline.read_text(encoding="utf-8"))
        base = None
        notes: list[str] = []
        if args.base_ref:
            with tree_at(args.base_ref, args.repo.resolve(), SOURCE_ROOTS + ("tools/ci",)) as base_root:
                base_allowed = load_allowlist(base_root / "tools/ci" / args.allowlist.name, base_root)
                base_counts, _ = scan(base_root, base_allowed)
            base = {kind: base_counts[kind] for kind in KINDS}
            notes = allowlist_growth(allowed, base_allowed, args.allowlist.name)
        messages = check(counts, baseline, base) + notes
    except (OSError, ValueError, KeyError, json.JSONDecodeError, subprocess.CalledProcessError) as exc:
        print(f"naming lint configuration error: {exc}")
        return 2
    for message in messages:
        print(message)
    failed = [message for message in messages if message.startswith("FAIL ")]
    for kind in KINDS:
        if any(message.startswith(f"FAIL {kind}:") for message in failed):
            print(f"{kind} examples: " + ", ".join(findings[kind][:10]))
    return int(bool(failed))


if __name__ == "__main__":
    raise SystemExit(main())
