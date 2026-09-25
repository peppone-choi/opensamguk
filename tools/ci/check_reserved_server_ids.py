#!/usr/bin/env python3
"""Keep public server IDs separate from game route names in every entry point."""

from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
TYPESCRIPT = (
    "web/game/middleware.ts",
    "web/game/lib/serverGameUrl.ts",
    "web/gateway/lib/serverGameUrl.ts",
    "web/gateway/app/admin/page.tsx",
)
WORKFLOWS = (
    ".github/workflows/reset-game-server.yml",
    ".github/workflows/promote-game-server.yml",
    ".github/workflows/deploy.yml",
)


def read_ids(path: str) -> set[str]:
    source = (ROOT / path).read_text(encoding="utf-8")
    if path in TYPESCRIPT:
        match = re.search(r"const RESERVED_(?:PATH|PUBLIC)_SERVER_IDS = new Set\(\[(.*?)\]\);", source, re.S)
        if match is None:
            raise ValueError(f"reserved ID list missing: {path}")
        return set(re.findall(r"'([^']+)'", match.group(1)))
    match = re.search(r"readonly RESERVED_PUBLIC_SERVER_IDS='([^']+)'", source)
    if match is None:
        raise ValueError(f"reserved ID list missing: {path}")
    return set(match.group(1).split())


def built_screens() -> set[str]:
    source = (ROOT / "web/game/lib/campaign-screens.ts").read_text(encoding="utf-8")
    match = re.search(r"CAMPAIGN_BUILT_SLUGS:.*?= new Set\(\[(.*?)\]\);", source, re.S)
    if match is None:
        raise ValueError("campaign screen list missing")
    return set(re.findall(r"'([^']+)'", match.group(1)))


def main() -> int:
    paths = TYPESCRIPT + WORKFLOWS
    expected = read_ids(paths[0])
    errors = []
    for path in paths[1:]:
        actual = read_ids(path)
        if actual != expected:
            errors.append(f"{path}: missing={sorted(expected - actual)} extra={sorted(actual - expected)}")
    unreserved = built_screens() - expected
    if unreserved:
        errors.append(f"campaign screens not reserved: {sorted(unreserved)}")
    if errors:
        print("\n".join(errors))
        return 1
    print(f"OK {len(expected)} reserved IDs across {len(paths)} lists; campaign screens reserved")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
