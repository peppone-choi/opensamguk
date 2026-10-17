#!/usr/bin/env python3
"""Lightweight check for agent instruction files (AGENTS.md, CLAUDE.md, .claude/rules).

Checks: line limit per file, link/@import targets exist, rule `paths` globs match a file,
protected rule phrases stay in their declared file, and the Codex AGENTS.md chain size.
Standard library only; run from anywhere: python3 tools/instructions/check_instructions.py
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

ENTRY_NAMES = {"AGENTS.md", "AGENTS.override.md", "CLAUDE.md"}
CODEX_DEFAULT_MAX_BYTES = 32 * 1024
LINK_RE = re.compile(r"\[[^\]]*\]\(([^)\s]+)\)")
IMPORT_RE = re.compile(r"(?:^|\s)@([\w./~-]+)")
FENCE_RE = re.compile(r"^\s*(```|~~~)")
CODE_SPAN_RE = re.compile(r"`[^`]*`")
TICK_PATH_RE = re.compile(r"`([^`\s]+)`")
GLOB_CHARS = set("*?{}[]<>$")


def list_files(root: Path) -> list[str]:
    try:
        out = subprocess.run(
            ["git", "-C", str(root), "ls-files", "--cached", "--others", "--exclude-standard"],
            check=True, capture_output=True, text=True).stdout
        return sorted(p for p in set(out.splitlines()) if (root / p).is_file())
    except (OSError, subprocess.CalledProcessError):
        found = []
        for dirpath, dirnames, filenames in os.walk(root):
            dirnames[:] = [d for d in dirnames if d not in {".git", "node_modules"}]
            found += [os.path.relpath(os.path.join(dirpath, f), root).replace(os.sep, "/") for f in filenames]
        return sorted(found)


def expand_braces(pattern: str) -> list[str]:
    match = re.search(r"\{([^{}]*)\}", pattern)
    if not match:
        return [pattern]
    head, tail = pattern[:match.start()], pattern[match.end():]
    return [p for alt in match.group(1).split(",") for p in expand_braces(head + alt + tail)]


def glob_regex(pattern: str) -> re.Pattern[str]:
    out, i = "", 0
    while i < len(pattern):
        if pattern.startswith("**/", i):
            out, i = out + "(?:.*/)?", i + 3
        elif pattern.startswith("**", i):
            out, i = out + ".*", i + 2
        elif pattern[i] == "*":
            out, i = out + "[^/]*", i + 1
        elif pattern[i] == "?":
            out, i = out + "[^/]", i + 1
        else:
            out, i = out + re.escape(pattern[i]), i + 1
    return re.compile(out + r"\Z")


def rule_paths(text: str) -> list[str]:
    lines = text.splitlines()
    if not lines or lines[0].strip() != "---":
        return []
    paths, in_paths = [], False
    for line in lines[1:]:
        if line.strip() == "---":
            break
        if re.match(r"^paths\s*:", line):
            in_paths = True
        elif in_paths and re.match(r"^\s+-\s+", line):
            paths.append(re.sub(r"^\s+-\s+", "", line).strip().strip("'\""))
        elif line.strip():
            in_paths = False
    return paths


def prose(text: str) -> str:
    """Text outside fenced code blocks, with inline code spans removed."""
    kept, fenced = [], False
    for line in text.splitlines():
        if FENCE_RE.match(line):
            fenced = not fenced
        elif not fenced:
            kept.append(CODE_SPAN_RE.sub("", line))
    return "\n".join(kept)


def resolve(root: Path, rel: str, refs: list[str]) -> list[str]:
    out = []
    for ref in refs:
        if re.match(r"^[a-z]+:", ref) or ref.startswith("#"):
            continue
        path = ref.split("#", 1)[0]
        resolved = Path(os.path.expanduser(path)) if path.startswith("~") else (root / rel).parent / path
        out.append(os.path.relpath(resolved, root).replace(os.sep, "/"))
    return out


def links(root: Path, rel: str, text: str) -> list[str]:
    return resolve(root, rel, [m.group(1) for m in LINK_RE.finditer(prose(text))])


def imports(root: Path, rel: str, text: str) -> list[str]:
    return resolve(root, rel, [m.group(1) for m in IMPORT_RE.finditer(prose(text))])


def instruction_set(root: Path, files: list[str]) -> list[str]:
    entries = [f for f in files if Path(f).name in ENTRY_NAMES or f == ".claude/CLAUDE.md"]
    chosen = set(entries) | {f for f in files if f.startswith(".claude/rules/") and f.endswith(".md")}
    queue = list(entries)
    while queue:  # @imports are followed transitively; links only one level from entry files
        rel = queue.pop()
        text = (root / rel).read_text(encoding="utf-8")
        for target in imports(root, rel, text):
            if (root / target).is_file() and target not in chosen:
                chosen.add(target)
                queue.append(target)
        if Path(rel).name in ENTRY_NAMES:
            chosen.update(t for t in links(root, rel, text) if t.endswith(".md") and (root / t).is_file())
    return sorted(chosen)


def check(root: Path, manifest: Path, max_lines: int, strict: bool) -> int:
    files = list_files(root)
    file_set = set(files)
    top = {f.split("/", 1)[0] for f in files}
    docs = instruction_set(root, files)
    fails, warns = [], []
    print(f"instruction files ({len(docs)}), limit {max_lines} lines:")
    for rel in docs:
        text = (root / rel).read_text(encoding="utf-8")
        count = len(text.splitlines())
        print(f"  {count:4d}  {rel}")
        if count > max_lines:
            fails.append(f"{rel}: {count} lines > {max_lines}")
        refs = links(root, rel, text) + (imports(root, rel, text) if Path(rel).name in ENTRY_NAMES else [])
        for target in refs:
            if not (root / target).exists():
                fails.append(f"{rel}: missing link/import target {target}")
        for token in TICK_PATH_RE.findall(text):
            token = token.rstrip(".,:;")
            if "/" in token and not GLOB_CHARS & set(token) and token.split("/", 1)[0] in top:
                if not (root / token).exists():
                    warns.append(f"{rel}: backtick path not found {token}")
        if rel.startswith(".claude/rules/"):
            for pattern in rule_paths(text):
                regexes = [glob_regex(p) for p in expand_braces(pattern)]
                if not any(r.match(f) for r in regexes for f in files):
                    fails.append(f"{rel}: paths glob matches no file: {pattern}")
    for rel in [f for f in files if Path(f).name in {"AGENTS.md", "AGENTS.override.md"}]:
        parent = Path(rel).parent
        chain = [f for f in files if Path(f).name == Path(rel).name and parent.is_relative_to(Path(f).parent)]
        size = sum((root / f).stat().st_size for f in chain)
        if size > CODEX_DEFAULT_MAX_BYTES:
            fails.append(f"{rel}: Codex AGENTS chain {size} bytes > {CODEX_DEFAULT_MAX_BYTES}")
    if manifest.is_file():
        seen = set()
        for number, line in enumerate(manifest.read_text(encoding="utf-8").splitlines(), 1):
            if not line.strip() or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) != 3 or parts[0] in seen:
                fails.append(f"{manifest.name}:{number}: need unique 'id<TAB>file<TAB>phrase'")
                continue
            rule_id, rel, phrase = parts
            seen.add(rule_id)
            if rel not in file_set or phrase not in (root / rel).read_text(encoding="utf-8"):
                fails.append(f"protected rule {rule_id} not found in {rel}")
        print(f"protected rules checked: {len(seen)}")
    else:
        warns.append(f"protected rule manifest not found: {manifest}")
    warns = list(dict.fromkeys(warns))
    for item in warns:
        print(f"WARN {item}")
    for item in fails:
        print(f"FAIL {item}")
    bad = fails or (strict and warns)
    print("RESULT " + ("FAIL" if bad else "PASS") + f" (fail={len(fails)} warn={len(warns)})")
    return 1 if bad else 0


def main() -> int:
    here = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", type=Path, default=here.parent.parent)
    parser.add_argument("--manifest", type=Path, default=None)
    parser.add_argument("--max-lines", type=int, default=200)
    parser.add_argument("--strict", action="store_true", help="treat WARN as FAIL")
    args = parser.parse_args()
    root = args.root.resolve()
    manifest = args.manifest or root / "tools/instructions/protected-rules.tsv"
    return check(root, manifest, args.max_lines, args.strict)


if __name__ == "__main__":
    sys.exit(main())
