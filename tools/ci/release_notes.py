#!/usr/bin/env python3
"""Draft release notes from the pull requests merged into main between two points.

Reads git only (no network): the first-parent history of `--to` after `--from` (or `--since`), one entry per merged PR —
GitHub merge commits (`Merge pull request #N from …` + title as the first body line) and squash merges (`title (#N)`).
Commits that came in without a PR are listed separately.

Titles are copied verbatim. The script never reads PR bodies, labels or linked advisories, so a security fix appears
exactly as its (neutral) PR title. A `Revert "<title>"` PR cancels its original when both are in the range.

Sections follow the conventional prefix of the title (`feat(scope): …`). The output is a Markdown draft for a person
to edit before publishing a release.

  python3 tools/ci/release_notes.py --from <ref> [--to origin/main] [--version v0.1.0] [--out notes.md]
  python3 tools/ci/release_notes.py --since 2026-10-01
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SECTIONS = (
    ("feat", "새 기능"),
    ("fix", "고친 것"),
    ("perf", "성능"),
    ("design", "화면 설계"),
    ("refactor", "코드 정비"),
    ("docs", "문서"),
    ("dev", "개발 · 운영"),
    ("revert", "되돌림"),
    ("other", "그 밖"),
)
DEV_TYPES = {"ci", "test", "chore", "build", "style"}
MERGE = re.compile(r"^Merge pull request #(\d+) from (\S+)")
SQUASH = re.compile(r"^(?P<title>.*\S)\s+\(#(?P<number>\d+)\)$")
CONVENTIONAL = re.compile(r"^(?P<type>[a-z]+)(?:\((?P<scope>[^)]*)\))?!?:\s*(?P<summary>.+)$")
REVERT = re.compile(r'^Revert "(?P<title>.+)"$')


@dataclass
class Entry:
    number: int | None
    title: str
    sha: str

    @property
    def section(self) -> str:
        if REVERT.match(self.title):
            return "revert"
        match = CONVENTIONAL.match(self.title)
        if not match:
            return "other"
        kind = match.group("type")
        if kind in DEV_TYPES:
            return "dev"
        return kind if kind in {key for key, _ in SECTIONS} else "other"

    def line(self) -> str:
        """`- **scope** summary (#N)`; the dev section keeps the type (it mixes ci · test · chore …); no-PR commits stay verbatim."""
        match = CONVENTIONAL.match(self.title)
        text = self.title
        if self.number is not None and match and self.section not in ("other", "revert"):
            tags = [match.group("type")] if self.section == "dev" else []
            tags += [match.group("scope")] if match.group("scope") else []
            text = (f"**{' · '.join(tags)}** " if tags else "") + match.group("summary")
        ref = f"#{self.number}" if self.number is not None else self.sha[:9]
        return f"- {text} ({ref})"


def git(repo: Path, *args: str) -> str:
    return subprocess.run(["git", "-C", str(repo), *args], check=True, capture_output=True, text=True).stdout


def entries(repo: Path, start: str | None, end: str, since: str | None) -> tuple[list[Entry], list[Entry]]:
    """(PR entries, commits without a PR), oldest first."""
    spec = [f"{start}..{end}"] if start else [end]
    if since:
        spec = [f"--since={since}", *spec]
    log = git(repo, "log", "--first-parent", "--reverse", "--format=%H%x1f%s%x1f%b%x1e", *spec)
    prs, direct = [], []
    for record in log.split("\x1e"):
        record = record.strip("\n")
        if not record:
            continue
        sha, subject, body = (record.split("\x1f") + ["", ""])[:3]
        merge = MERGE.match(subject)
        if merge:
            title = next((line.strip() for line in body.splitlines() if line.strip()), merge.group(2))
            prs.append(Entry(int(merge.group(1)), title, sha))
            continue
        squash = SQUASH.match(subject)
        if squash:
            prs.append(Entry(int(squash.group("number")), squash.group("title"), sha))
        else:
            direct.append(Entry(None, subject, sha))
    return prs, direct


def cancel_reverts(prs: list[Entry]) -> tuple[list[Entry], list[tuple[Entry, Entry]]]:
    """Drop a revert and its original when both are in the range (the change never shipped)."""
    kept, pairs = list(prs), []
    for revert in prs:
        match = REVERT.match(revert.title)
        if not match or revert not in kept:
            continue
        original = next((e for e in kept if e.title == match.group("title") and e is not revert and e.sha != revert.sha), None)
        if original is not None and prs.index(original) < prs.index(revert):
            kept.remove(original)
            kept.remove(revert)
            pairs.append((original, revert))
    return kept, pairs


def render(prs: list[Entry], direct: list[Entry], pairs: list[tuple[Entry, Entry]], *, version: str, start: str,
           end: str) -> str:
    out = [f"# {version} 릴리스 노트 (초안)", "",
           f"범위: `{start}` → `{end}` · 병합 PR {len(prs) + 2 * len(pairs)}개"
           + (f"(그중 되돌린 짝 {len(pairs)}개는 뺐다)" if pairs else ""), "",
           "> 자동 생성 초안이다. PR 제목을 그대로 옮겼으니 공개 전에 사람이 다듬는다.", ""]
    for key, label in SECTIONS:
        lines = [entry.line() for entry in prs if entry.section == key]
        if lines:
            out += [f"## {label}", "", *lines, ""]
    if direct:
        out += ["## PR 없이 들어간 커밋", "", *[entry.line() for entry in direct], ""]
    if pairs:
        out += ["<details><summary>같은 범위 안에서 되돌린 PR</summary>", "",
                *[f"- #{original.number} ↔ #{revert.number}" for original, revert in pairs], "", "</details>", ""]
    return "\n".join(out).rstrip("\n") + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--repo", type=Path, default=ROOT)
    parser.add_argument("--from", dest="start", help="exclusive start (tag or commit); default: latest v* tag")
    parser.add_argument("--since", help="git date instead of --from (e.g. 2026-10-01)")
    parser.add_argument("--to", dest="end", default="origin/main")
    parser.add_argument("--version", default="다음 릴리스", help="heading label, e.g. v0.1.0")
    parser.add_argument("--out", type=Path, help="write here instead of stdout")
    args = parser.parse_args()
    repo = args.repo.resolve()
    start = args.start
    try:
        if not start and not args.since:
            tags = git(repo, "tag", "--merged", args.end, "--sort=-creatordate", "--list", "v*").split()
            if not tags:
                print("release notes: no v* tag before --to; pass --from <ref> or --since <date>", file=sys.stderr)
                return 2
            start = tags[0]
        prs, direct = entries(repo, start, args.end, args.since)
    except subprocess.CalledProcessError as exc:
        print(f"release notes: git failed: {exc.stderr.strip()}", file=sys.stderr)
        return 2
    kept, pairs = cancel_reverts(prs)
    text = render(kept, direct, pairs, version=args.version, start=start or f"since {args.since}", end=args.end)
    if args.out:
        args.out.write_text(text, encoding="utf-8")
    else:
        sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
