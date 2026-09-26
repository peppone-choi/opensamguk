#!/usr/bin/env python3
"""Ratchet player-facing web copy rules (ADR-LITE-049 2026-09-26 amendment, ADR-LITE-057).

Screens use Hangul first (縣→현, 郡→군, 城→성, 省→구역), 금·쌀 for money and grain, 「부」 for the
retired concept 「휘하」, and no retired 삼모-only terms. Comments are not player copy and are skipped;
everything else in web source (string literals, JSX text, template text) is counted.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASELINE = Path(__file__).with_name("web_copy_lint_baseline.json")
ALLOWLIST = Path(__file__).with_name("web_copy_lint_allowlist.json")
SOURCE_ROOTS = ("web/game", "web/gateway", "web/shared")
SOURCE_SUFFIXES = {".ts", ".tsx"}
SKIP_DIRS = {".git", ".next", "build", "dist", "node_modules", "coverage", "public",
             "__tests__", "e2e", "test-results", "playwright-report"}
TEST_NAME = re.compile(r"\.(?:test|spec)\.tsx?$")
PATTERNS = {
    # CJK ideographs (Extension A, Unified, Compatibility). One run of ideographs is one finding.
    "hanja": re.compile("[㐀-䶿一-鿿豈-﫿]+"),
    # Longest first so 군량매매 is one finding, not two.
    "retired_term": re.compile("숙련전환|군량매매|자금|군량|병량|국고|세율|빙의|삭턴|벌점|휘하"),
}
KINDS = tuple(PATTERNS)


def source_files(root: Path):
    for source_root in SOURCE_ROOTS:
        base = root / source_root
        if not base.is_dir():
            continue
        for directory, dirs, files in os.walk(base):
            dirs[:] = sorted(d for d in dirs if d not in SKIP_DIRS)
            for name in sorted(files):
                path = Path(directory) / name
                if (path.suffix in SOURCE_SUFFIXES and not TEST_NAME.search(name) and not name.endswith(".d.ts")
                        and path.is_file() and not path.is_symlink()):
                    yield path


def strip_comments(text: str) -> str:
    """Blank out // and /* */ comments, keeping newlines so line numbers stay put.

    Strings and template literals (with ${} nesting) are followed so a // inside a URL string is kept.
    Regex literals are not recognised; a rare misread only drops the rest of that line.
    """
    out: list[str] = []
    stack: list[str] = ["code"]  # code | tpl ; "code" entries pushed by ${ carry brace depth below
    depth: list[int] = [0]
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        state = stack[-1]
        if state == "tpl":
            if c == "\\" and i + 1 < n:
                out.append(text[i:i + 2]); i += 2; continue
            if c == "`":
                stack.pop(); depth.pop(); out.append(c); i += 1; continue
            if c == "$" and i + 1 < n and text[i + 1] == "{":
                stack.append("code"); depth.append(0); out.append("${"); i += 2; continue
            out.append(c); i += 1; continue
        # code
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            end = text.find("\n", i)
            end = n if end == -1 else end
            out.append(" " * (end - i)); i = end; continue
        if c == "/" and i + 1 < n and text[i + 1] == "*":
            end = text.find("*/", i + 2)
            end = n if end == -1 else end + 2
            out.append("".join("\n" if ch == "\n" else " " for ch in text[i:end])); i = end; continue
        if c in ("'", '"'):
            j = i + 1
            while j < n and text[j] != c and text[j] != "\n":
                j += 2 if text[j] == "\\" else 1
            j = min(j + 1, n)
            out.append(text[i:j]); i = j; continue
        if c == "`":
            stack.append("tpl"); depth.append(0); out.append(c); i += 1; continue
        if c == "{":
            depth[-1] += 1
        elif c == "}":
            if depth[-1] == 0 and len(stack) > 1:
                stack.pop(); depth.pop(); out.append(c); i += 1; continue
            depth[-1] -= 1
        out.append(c); i += 1
    return "".join(out)


def load_allowlist(path: Path, root: Path) -> dict[str, dict]:
    entries = json.loads(path.read_text(encoding="utf-8"))["paths"]
    allowed = {}
    for entry in entries:
        name = entry["path"]
        if name in allowed or name.startswith("/") or ".." in Path(name).parts:
            raise ValueError(f"invalid or duplicate allowlist path: {name}")
        if not entry.get("reason") or not (root / name).is_file():
            raise ValueError(f"allowlist needs an existing file and reason: {name}")
        if set(entry.get("kinds", ())) - set(KINDS) or not entry.get("kinds"):
            raise ValueError(f"allowlist needs known kinds: {name}")
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
        code = strip_comments(path.read_text(encoding="utf-8", errors="replace"))
        for line_number, line in enumerate(code.splitlines(), 1):
            for kind, pattern in PATTERNS.items():
                if kind in exemptions:
                    continue
                for match in pattern.finditer(line):
                    counts[kind] += 1
                    findings[kind].append(f"{relative}:{line_number}:{match.group()}")
    return counts, findings


def check(counts: Counter, baseline: dict[str, int]) -> list[str]:
    if set(baseline) != set(KINDS) or any(type(value) is not int or value < 0 for value in baseline.values()):
        raise ValueError("baseline must have a nonnegative integer for every kind")
    messages = []
    for kind in KINDS:
        current, limit = counts[kind], baseline[kind]
        if current > limit:
            messages.append(f"FAIL {kind}: {current} > baseline {limit}; remove new violations")
        elif current < limit:
            messages.append(f"LOWER {kind}: {current} < baseline {limit}; update {BASELINE.name} to {current}")
        else:
            messages.append(f"OK {kind}: {current} = baseline {limit}")
    return messages


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--baseline", type=Path, default=BASELINE)
    parser.add_argument("--allowlist", type=Path, default=ALLOWLIST)
    parser.add_argument("--counts", action="store_true", help="print measured counts as JSON")
    parser.add_argument("--by-file", action="store_true", help="print per-file counts")
    args = parser.parse_args()
    root = args.root.resolve()
    try:
        allowed = load_allowlist(args.allowlist, root)
        counts, findings = scan(root, allowed)
        if args.counts or args.by_file:
            if args.by_file:
                per_file: Counter = Counter()
                for kind in KINDS:
                    for finding in findings[kind]:
                        per_file[(kind, finding.split(":", 1)[0])] += 1
                for (kind, name), count in per_file.most_common():
                    print(f"{count:5} {kind:13} {name}")
            print(json.dumps(dict(counts), indent=2, sort_keys=True))
            return 0
        baseline = json.loads(args.baseline.read_text(encoding="utf-8"))
        messages = check(counts, baseline)
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as exc:
        print(f"web copy lint configuration error: {exc}")
        return 2
    for message in messages:
        print(message)
    for kind in KINDS:
        if counts[kind] > baseline[kind]:
            print(f"{kind} examples: " + ", ".join(findings[kind][:10]))
    return int(any(counts[kind] != baseline[kind] for kind in KINDS))


if __name__ == "__main__":
    raise SystemExit(main())
