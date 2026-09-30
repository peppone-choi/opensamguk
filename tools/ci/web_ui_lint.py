#!/usr/bin/env python3
"""Ratchet web UI rules that keep mobile the same game (ADR-LITE-049 2026-09-26 amendment 「모바일」).

- title_attr: a `title=` on an intrinsic JSX element — information only a mouse hover can show.
  Reasons for a disabled control open on press (ReasonTooltip), never through `title`.
- native_disabled: a native `disabled` attribute on an intrinsic element. It swallows taps, so the reason
  can never open; controls use `aria-disabled` instead.
- dimmed_disabled: a CSS `:disabled` / `[aria-disabled]` rule that fades the control with opacity.
  Unavailable controls keep full contrast with a dashed border and a reason (rule (7)).
- adhoc_breakpoint: a width media condition other than the three bands in web/shared/src/breakpoints.ts
  (768 · 1200, and 767.98 · 1199.98 for the upper edges).

Counts only go down: a new violation fails, and a fix must lower the baseline in the same change.
Comments are skipped (web_copy_lint.strip_comments); tests, e2e and generated files are skipped.
"""

from __future__ import annotations

import argparse
import json
import os
import re
from collections import Counter
from pathlib import Path

from web_copy_lint import strip_comments

ROOT = Path(__file__).resolve().parents[2]
BASELINE = Path(__file__).with_name("web_ui_lint_baseline.json")
SOURCE_ROOTS = ("web/game", "web/gateway", "web/shared")
CODE_SUFFIXES = {".ts", ".tsx"}
STYLE_SUFFIXES = {".css", ".scss"}
SKIP_DIRS = {".git", ".next", "build", "dist", "node_modules", "coverage", "public",
             "__tests__", "e2e", "test-results", "playwright-report"}
TEST_NAME = re.compile(r"\.(?:test|spec)\.tsx?$")
KINDS = ("title_attr", "native_disabled", "dimmed_disabled", "adhoc_breakpoint")
ALLOWED_WIDTHS = {"768", "1200", "767.98", "1199.98"}

JSX_ATTR = {
    "title_attr": re.compile(r"(?<![-\w.])title\s*="),
    "native_disabled": re.compile(r"(?<![-\w.])disabled(?=\s*(?:=|/?>|\s))"),
}
DIMMED = re.compile(r"(?::disabled|\[aria-disabled[^\]]*\])[^{};]*\{[^}]*\bopacity\s*:", re.S)
WIDTH = re.compile(r"\((?:max|min)-width\s*:\s*([\d.]+)px\s*\)")
MEDIA_BLOCK = re.compile(r"@media[^{]*")
MATCH_MEDIA = re.compile(r"""matchMedia\(\s*(['"`])(.*?)\1""", re.S)
TAG_OPEN = re.compile(r"<([A-Za-z][\w.]*)")


def source_files(root: Path):
    for source_root in SOURCE_ROOTS:
        base = root / source_root
        if not base.is_dir():
            continue
        for directory, dirs, files in os.walk(base):
            dirs[:] = sorted(d for d in dirs if d not in SKIP_DIRS)
            for name in sorted(files):
                path = Path(directory) / name
                suffix = path.suffix
                if suffix not in CODE_SUFFIXES | STYLE_SUFFIXES or TEST_NAME.search(name) or name.endswith(".d.ts"):
                    continue
                if path.is_file() and not path.is_symlink():
                    yield path


def strip_css_comments(text: str) -> str:
    return re.sub(r"/\*.*?\*/", lambda m: "".join("\n" if ch == "\n" else " " for ch in m.group()), text, flags=re.S)


def enclosing_tag(code: str, index: int) -> str | None:
    """Name of the JSX tag whose attribute list contains `index`, or None if the attribute is outside a tag."""
    start = code.rfind("<", 0, index)
    if start == -1:
        return None
    if ">" in code[start:index].replace("=>", "").replace("->", ""):
        return None
    match = TAG_OPEN.match(code, start)
    return match.group(1) if match else None


def line_of(text: str, index: int) -> int:
    return text.count("\n", 0, index) + 1


def scan(root: Path) -> tuple[Counter, dict[str, list[str]]]:
    counts: Counter = Counter({kind: 0 for kind in KINDS})
    findings: dict[str, list[str]] = {kind: [] for kind in KINDS}

    def add(kind: str, relative: str, text: str, index: int, what: str) -> None:
        counts[kind] += 1
        findings[kind].append(f"{relative}:{line_of(text, index)}:{what}")

    for path in source_files(root):
        relative = path.relative_to(root).as_posix()
        raw = path.read_text(encoding="utf-8", errors="replace")
        if path.suffix in STYLE_SUFFIXES:
            css = strip_css_comments(raw)
            for match in DIMMED.finditer(css):
                add("dimmed_disabled", relative, css, match.start(), match.group().split("{", 1)[0].strip())
            for block in MEDIA_BLOCK.finditer(css):
                for width in WIDTH.finditer(block.group()):
                    if width.group(1) not in ALLOWED_WIDTHS:
                        add("adhoc_breakpoint", relative, css, block.start(), f"{width.group(1)}px")
            continue
        code = strip_comments(raw)
        if path.suffix == ".tsx":
            for kind, pattern in JSX_ATTR.items():
                for match in pattern.finditer(code):
                    tag = enclosing_tag(code, match.start())
                    if tag and tag[0].islower() and "." not in tag:
                        add(kind, relative, code, match.start(), f"<{tag}>")
        for call in MATCH_MEDIA.finditer(code):
            for width in WIDTH.finditer(call.group(2)):
                if width.group(1) not in ALLOWED_WIDTHS:
                    add("adhoc_breakpoint", relative, code, call.start(), f"matchMedia {width.group(1)}px")
        for block in re.finditer(r"@media[^{`'\"]*", code):
            for width in WIDTH.finditer(block.group()):
                if width.group(1) not in ALLOWED_WIDTHS:
                    add("adhoc_breakpoint", relative, code, block.start(), f"{width.group(1)}px")
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
    parser.add_argument("--counts", action="store_true", help="print measured counts as JSON")
    parser.add_argument("--by-file", action="store_true", help="print per-file counts")
    args = parser.parse_args()
    try:
        counts, findings = scan(args.root.resolve())
        if args.counts or args.by_file:
            if args.by_file:
                per_file: Counter = Counter()
                for kind in KINDS:
                    for finding in findings[kind]:
                        per_file[(kind, finding.split(":", 1)[0])] += 1
                for (kind, name), count in per_file.most_common():
                    print(f"{count:5} {kind:17} {name}")
            print(json.dumps(dict(counts), indent=2, sort_keys=True))
            return 0
        baseline = json.loads(args.baseline.read_text(encoding="utf-8"))
        messages = check(counts, baseline)
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as exc:
        print(f"web UI lint configuration error: {exc}")
        return 2
    for message in messages:
        print(message)
    for kind in KINDS:
        if counts[kind] > baseline[kind]:
            print(f"{kind} examples: " + ", ".join(findings[kind][:10]))
    return int(any(counts[kind] != baseline[kind] for kind in KINDS))


if __name__ == "__main__":
    raise SystemExit(main())
