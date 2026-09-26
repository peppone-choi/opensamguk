#!/usr/bin/env python3
"""Check reviewed FERRY anchors against water terrain without moving anchors."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
TILES = ROOT / "data/map/han-tiles.json"
STRONGHOLDS = ROOT / "data/curated/han/strategic-strongholds-v1.json"
LEDGER = ROOT / "data/curated/han/ferry-water-adjudications-v1.json"
WATER = frozenset("034")
EXCEPTIONS = frozenset({"HISTORICAL_COURSE_PENDING", "UNKNOWN_EXCEPTION"})


def nearest_water(terrain: list[str], row: int, col: int) -> tuple[int, tuple[int, int]]:
    """Find the nearest water tile by Chebyshev distance, with a stable tie break."""
    height, width = len(terrain), len(terrain[0])
    if not (0 <= row < height and 0 <= col < width):
        raise ValueError(f"anchor outside terrain: {row},{col}")
    for distance in range(max(height, width)):
        candidates = []
        for r in range(max(0, row - distance), min(height, row + distance + 1)):
            for c in range(max(0, col - distance), min(width, col + distance + 1)):
                if max(abs(r - row), abs(c - col)) != distance:
                    continue
                if terrain[r][c] in WATER:
                    candidates.append((abs(r - row) + abs(c - col), r, c))
        if candidates:
            _, r, c = min(candidates)
            return distance, (r, c)
    raise ValueError("terrain contains no water cells")


def check(tiles: dict, strongholds: dict, ledger: dict) -> list[str]:
    errors = []
    if tiles["_meta"].get("resolutionScale", 1) > 1:
        from tools.map.korea_map_extension import base_frame
        tiles = base_frame(tiles)
    terrain = tiles["terrain"]
    ferries = {site["id"]: site for site in strongholds["strongholds"] if site.get("role") == "FERRY"}
    rows = ledger["rows"]
    ids = [row["siteId"] for row in rows]
    if len(ids) != len(set(ids)) or set(ids) != set(ferries):
        errors.append(f"FERRY inventory mismatch: missing={sorted(set(ferries) - set(ids))}, extra={sorted(set(ids) - set(ferries))}")
    for row in rows:
        site = ferries.get(row["siteId"])
        if site is None:
            continue
        anchor = site["tileAnchor"]
        r, c = row["anchor"]["row"], row["anchor"]["col"]
        if (r, c) != (anchor["row"], anchor["col"]):
            errors.append(f"{site['id']}: anchor moved")
            continue
        if row["nameHan"] != site["nameHan"]:
            errors.append(f"{site['id']}: name drift")
        if anchor["terrain"]["code"] != terrain[r][c]:
            errors.append(f"{site['id']}: stronghold terrain snapshot drift")
        distance, _ = nearest_water(terrain, r, c)
        if row["currentWaterDistance"] != distance:
            errors.append(f"{site['id']}: measured distance {distance} != ledger {row['currentWaterDistance']}")
        disposition = row["disposition"]
        if row["baselineWaterDistance"] >= 2:
            if row["crossedWater"] == "NOT_REQUIRED":
                errors.append(f"{site['id']}: far baseline crossing has no water review")
            if disposition == "UNKNOWN_EXCEPTION":
                if row["crossedWater"] != "UNKNOWN" or not row.get("exceptionReason"):
                    errors.append(f"{site['id']}: UNKNOWN exception lacks its reason")
            elif row["crossedWater"] == "UNKNOWN" or not row.get("evidence"):
                errors.append(f"{site['id']}: named water lacks a source")
        elif disposition != "ALREADY_NEAR_WATER":
            errors.append(f"{site['id']}: near baseline has unexpected disposition")
        if disposition == "LOCAL_RIVER_CELL":
            if terrain[r][c] != "3" or row.get("terrainBefore") in WATER or distance > 1:
                errors.append(f"{site['id']}: local RIVER anchor was lost")
        elif distance >= 2:
            if disposition not in EXCEPTIONS or not row.get("exceptionReason"):
                errors.append(f"{site['id']}: distance {distance} has no reviewed exception")
        elif disposition in EXCEPTIONS:
            errors.append(f"{site['id']}: exception remains although water is now within one cell")
    summary = ledger["summary"]
    actual = {
        "ferryCount": len(ferries),
        "baselineFarCount": sum(row["baselineWaterDistance"] >= 2 for row in rows),
        "localRiverAnchorCount": sum(row["disposition"] == "LOCAL_RIVER_CELL" for row in rows),
        "historicalCoursePendingCount": sum(row["disposition"] == "HISTORICAL_COURSE_PENDING" for row in rows),
        "unknownExceptionCount": sum(row["disposition"] == "UNKNOWN_EXCEPTION" for row in rows),
    }
    if summary != actual:
        errors.append(f"summary drift: {actual} != {summary}")
    # Positive controls keep an accidental empty/relaxed distance query red.
    by_id = {row["siteId"]: row for row in rows}
    if by_id.get("yanjin", {}).get("baselineWaterDistance", 0) < 2:
        errors.append("positive control 延津 must be far in the baseline")
    if by_id.get("mengjin", {}).get("baselineWaterDistance") != 0:
        errors.append("positive control 孟津 must be on water in the baseline")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", required=True)
    parser.parse_args()
    documents = [json.loads(path.read_text(encoding="utf-8")) for path in (TILES, STRONGHOLDS, LEDGER)]
    errors = check(*documents)
    for error in errors:
        print(error)
    if errors:
        return 1
    print(f"FERRY water gate: {len(documents[2]['rows'])} accounted, exceptions reviewed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
