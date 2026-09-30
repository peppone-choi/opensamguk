#!/usr/bin/env python3
"""Fail closed when a selected turn-boundary source still has forced exceptions."""

import argparse
from pathlib import Path
import re
import sys


FORCED = re.compile(r"check(NotNull)?\(|require(NotNull)?\(|error\(|!!")


def scan(paths: list[Path]) -> list[str]:
    if not paths:
        raise ValueError("at least one source file is required")
    hits = []
    for path in paths:
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if FORCED.search(line):
                hits.append(f"{path}:{number}:{line}")
    return hits


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("paths", nargs="+", type=Path)
    args = parser.parse_args()
    try:
        hits = scan(args.paths)
    except (OSError, UnicodeError, ValueError) as exc:
        print(f"boundary source scan failed: {exc}", file=sys.stderr)
        return 2
    args.output.write_text("\n".join(hits) + ("\n" if hits else ""), encoding="utf-8")
    if hits:
        print(f"forced phase-boundary exceptions remain: {len(hits)}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
