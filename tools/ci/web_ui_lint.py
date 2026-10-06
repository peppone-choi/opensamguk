#!/usr/bin/env python3
"""Ratchet web UI rules that keep mobile the same game (ADR-LITE-049 2026-09-26 amendment 「모바일」).

- title_attr: a `title=` on an intrinsic JSX element — information only a mouse hover can show.
  Reasons for a disabled control open on press (ReasonTooltip), never through `title`.
- native_disabled: a native `disabled` attribute on an intrinsic element. It swallows taps, so the reason
  can never open; controls use `aria-disabled` instead.
- dimmed_disabled: a CSS `:disabled` / `[aria-disabled]` rule that fades the control with opacity.
  Unavailable controls keep full contrast with a dashed border and a reason (rule (7)).
- adhoc_breakpoint: a width media condition other than the three bands in web/shared/src/breakpoints.ts
  (768 · 1200, and 767.98 · 1199.98 for the upper edges) — `(max|min)-width`, range syntax `(width >= N)`, and any em/rem width.

JSX attributes are read by walking each intrinsic opening tag, so a variable named `disabled` or `title` inside an
expression (`aria-disabled={disabled || busy}`) is not an attribute. Selectors inside `:not(...)` are ignored.

Counts only go down: a PR fails above min(baseline, counts at its merge base) (--base-ref, tools/ci/ratchet.py).
A fix does not touch the baseline JSON in the same PR; lowering it is a separate ratchet PR (--write-baseline).
Comments are skipped (web_copy_lint.strip_comments); tests, e2e and generated files are skipped.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import os
import re
from collections import Counter
from pathlib import Path

from lint_files import git_visible_files, is_visible
from ratchet import judge, tree_at, write_baseline

from web_copy_lint import strip_comments

ROOT = Path(__file__).resolve().parents[2]
BASELINE = Path(__file__).with_name("web_ui_lint_baseline.json")
SOURCE_ROOTS = ("web/game", "web/gateway", "web/shared")
CODE_SUFFIXES = {".ts", ".tsx"}
STYLE_SUFFIXES = {".css", ".scss"}
SKIP_DIRS = {".git", ".next-topdown-screens", ".next", "build", "dist", "node_modules", "coverage", "public",
             "__tests__", "e2e", "test-results", "playwright-report"}
TEST_NAME = re.compile(r"\.(?:test|spec)\.tsx?$")
KINDS = ("title_attr", "native_disabled", "dimmed_disabled", "adhoc_breakpoint")
ALLOWED_WIDTHS = {"768", "1200", "767.98", "1199.98"}

JSX_ATTR_KINDS = {"title": "title_attr", "disabled": "native_disabled"}
CSS_RULE = re.compile(r"([^{}]+)\{([^{}]*)\}")
NOT_GROUP = re.compile(r":not\((?:[^()]|\([^()]*\))*\)")
DISABLED_SELECTOR = re.compile(r":disabled|\[aria-disabled")
# 폭 조건: (max|min)-width: N 단위, 범위 문법 (width >= N) · (N <= width). px 는 세 단 값만 허용, em · rem 은 늘 센다.
WIDTH = re.compile(r"\((?:max|min)-width\s*:\s*([\d.]+)(px|em|rem)\s*\)"
                   r"|\(\s*width\s*[<>]=?\s*([\d.]+)(px|em|rem)\s*\)"
                   r"|\(\s*([\d.]+)(px|em|rem)\s*[<>]=?\s*width\b")
MEDIA_BLOCK = re.compile(r"@media[^{]*")
MATCH_MEDIA = re.compile(r"""matchMedia\(\s*(['"`])(.*?)\1""", re.S)
TAG_OPEN = re.compile(r"<([A-Za-z][\w.]*)")
ATTR_NAME = re.compile(r"[A-Za-z_$][-\w:.$]*")
IDENT_TAIL = set("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_$)]")


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
                suffix = path.suffix
                if suffix not in CODE_SUFFIXES | STYLE_SUFFIXES or TEST_NAME.search(name) or name.endswith(".d.ts"):
                    continue
                if path.is_file() and not path.is_symlink() and is_visible(path, root, visible):
                    yield path


def strip_css_comments(text: str) -> str:
    return re.sub(r"/\*.*?\*/", lambda m: "".join("\n" if ch == "\n" else " " for ch in m.group()), text, flags=re.S)


def skip_braces(code: str, i: int) -> int:
    """code[i] == '{' — 짝이 맞는 '}' 다음 자리. 문자열 · 템플릿(${} 중첩)은 건너뛴다."""
    depth, n = 0, len(code)
    while i < n:
        c = code[i]
        if c in "\"'":
            j = i + 1
            while j < n and code[j] != c and code[j] != "\n":
                j += 2 if code[j] == "\\" else 1
            i = j + 1
            continue
        if c == "`":
            j = i + 1
            while j < n and code[j] != "`":
                if code[j] == "\\":
                    j += 2
                elif code.startswith("${", j):
                    j = skip_braces(code, j + 1)
                else:
                    j += 1
            i = j + 1
            continue
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    return n


def jsx_attributes(code: str, start: int) -> tuple[str, list[tuple[str, int]]] | None:
    """code[start] == '<' 인 JSX 여는 태그의 (이름, [(속성 이름, 자리)]). 속성 값 · {…} 식 · 펼침은 건너뛴다.
    태그가 아니거나 읽을 수 없으면 None — 식 안의 변수 이름을 속성으로 세지 않는다."""
    if start > 0 and code[start - 1] in IDENT_TAIL:
        return None  # 제네릭 Array<T> · 비교 a<b (한글 글 바로 뒤 <select> 는 태그다)
    match = TAG_OPEN.match(code, start)
    if not match:
        return None
    name, i, n, attrs = match.group(1), match.end(), len(code), []
    while i < n:
        c = code[i]
        if c.isspace():
            i += 1
        elif c == ">" or code.startswith("/>", i):
            return name, attrs
        elif c == "{":
            i = skip_braces(code, i)
        else:
            attr = ATTR_NAME.match(code, i)
            if not attr:
                return None
            attrs.append((attr.group(), i))
            j = attr.end()
            while j < n and code[j].isspace():
                j += 1
            if j < n and code[j] == "=":
                j += 1
                while j < n and code[j].isspace():
                    j += 1
                if j < n and code[j] in "\"'":
                    end = code.find(code[j], j + 1)
                    if end == -1:
                        return None
                    i = end + 1
                elif j < n and code[j] == "{":
                    i = skip_braces(code, j)
                else:
                    return None
            else:
                i = j
    return None


def widths(text: str) -> list[str]:
    """폭 조건 가운데 세 단 밖의 것(「900px」 · 「48em」)."""
    out = []
    for m in WIDTH.finditer(text):
        value, unit = next((m.group(k), m.group(k + 1)) for k in (1, 3, 5) if m.group(k))
        if unit != "px" or value not in ALLOWED_WIDTHS:
            out.append(f"{value}{unit}")
    return out


def line_of(text: str, index: int) -> int:
    return text.count("\n", 0, index) + 1


def scan(root: Path) -> tuple[Counter, dict[str, list[str]]]:
    counts: Counter = Counter({kind: 0 for kind in KINDS})
    findings: dict[str, list[str]] = {kind: [] for kind in KINDS}

    def add(kind: str, relative: str, text: str, index: int, what: str) -> None:
        counts[kind] += 1
        findings[kind].append(f"{relative}:{line_of(text, index)}:{what}")

    def fades(body: str) -> bool:
        # opacity 가 1(100%) 미만이면 흐리기다. `opacity: 1` 은 다른 규칙의 흐리기를 되돌리는 것이라 세지 않는다.
        # 숫자가 아닌 값(var() 등)은 알 수 없으니 센다.
        for value in re.findall(r"\bopacity\s*:\s*([^;}]+)", body):
            number = re.fullmatch(r"\s*([0-9]*\.?[0-9]+)\s*(%?)\s*(!important)?\s*", value)
            if not number:
                return True
            amount = float(number.group(1)) / (100 if number.group(2) else 1)
            if amount < 1:
                return True
        return False

    for path in source_files(root):
        relative = path.relative_to(root).as_posix()
        raw = path.read_text(encoding="utf-8", errors="replace")
        if path.suffix in STYLE_SUFFIXES:
            css = strip_css_comments(raw)
            for rule in CSS_RULE.finditer(css):
                selector = NOT_GROUP.sub("", rule.group(1))
                if DISABLED_SELECTOR.search(selector) and fades(rule.group(2)):
                    add("dimmed_disabled", relative, css, rule.start() + len(rule.group(1)) - len(rule.group(1).lstrip()),
                        " ".join(rule.group(1).split())[:60])
            for block in MEDIA_BLOCK.finditer(css):
                for width in widths(block.group()):
                    add("adhoc_breakpoint", relative, css, block.start(), width)
            continue
        code = strip_comments(raw)
        if path.suffix == ".tsx":
            for tag_open in TAG_OPEN.finditer(code):
                parsed = jsx_attributes(code, tag_open.start())
                if not parsed:
                    continue
                tag, attrs = parsed
                if not tag[0].islower() or "." in tag:
                    continue  # 컴포넌트 prop(<Panel title> · <Button disabled>)은 세지 않는다
                for attr, at in attrs:
                    if attr in JSX_ATTR_KINDS:
                        add(JSX_ATTR_KINDS[attr], relative, code, at, f"<{tag}>")
        for call in MATCH_MEDIA.finditer(code):
            for width in widths(call.group(2)):
                add("adhoc_breakpoint", relative, code, call.start(), f"matchMedia {width}")
        for block in re.finditer(r"@media[^{`'\"]*", code):
            for width in widths(block.group()):
                add("adhoc_breakpoint", relative, code, block.start(), width)
    return counts, findings


def check(counts: Counter, baseline: dict[str, int], base: dict[str, int] | None = None) -> list[str]:
    if set(baseline) != set(KINDS) or any(type(value) is not int or value < 0 for value in baseline.values()):
        raise ValueError("baseline must have a nonnegative integer for every kind")
    return judge(counts, baseline, base, KINDS, BASELINE.name)[0]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--baseline", type=Path, default=BASELINE)
    parser.add_argument("--counts", action="store_true", help="print measured counts as JSON")
    parser.add_argument("--base-ref", help="merge-base commit: fail only above min(baseline, counts at this commit) — tools/ci/ratchet.py")
    parser.add_argument("--repo", type=Path, default=ROOT, help="git repository holding --base-ref")
    parser.add_argument("--write-baseline", action="store_true", help="write measured counts to the baseline (ratchet PR)")
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
        if args.write_baseline:
            write_baseline(args.baseline, counts, KINDS)
            print(f"wrote {args.baseline.name}: " + json.dumps({kind: counts[kind] for kind in KINDS}, sort_keys=True))
            return 0
        baseline = json.loads(args.baseline.read_text(encoding="utf-8"))
        base = None
        if args.base_ref:
            with tree_at(args.base_ref, args.repo.resolve(), SOURCE_ROOTS + ("tools/ci",)) as base_root:
                base_counts, _ = scan(base_root)
            base = {kind: base_counts[kind] for kind in KINDS}
        messages = check(counts, baseline, base)
    except (OSError, ValueError, KeyError, json.JSONDecodeError, subprocess.CalledProcessError) as exc:
        print(f"web UI lint configuration error: {exc}")
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
