#!/usr/bin/env python3
"""Materialize reviewed stable-ID place names onto the committed Han tiles."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

try:
    from tools.map.build_tile_grid import (
        CANONICAL_PLACE_NAME_NORMALIZATIONS,
        normalize_han_place_names,
    )
except ModuleNotFoundError:  # pragma: no cover - direct script compatibility
    from build_tile_grid import (
        CANONICAL_PLACE_NAME_NORMALIZATIONS,
        normalize_han_place_names,
    )


ROOT = Path(__file__).resolve().parents[2]
TILES = ROOT / "data" / "map" / "han-tiles.json"
HAN_WORLD = ROOT / "infra" / "src" / "main" / "resources" / "map" / "han.json"
HAN_CITY_CONST = (
    ROOT
    / "common"
    / "src"
    / "main"
    / "kotlin"
    / "opensamguk"
    / "common"
    / "constants"
    / "HanCityConst.kt"
)


def normalize_runtime_world(document: dict) -> dict:
    result = json.loads(json.dumps(document, ensure_ascii=False))
    for row in result.get("cities", []):
        normalization = CANONICAL_PLACE_NAME_NORMALIZATIONS.get(
            str(row.get("physicalPlaceId", ""))
        )
        if normalization is None or "runtimeName" not in normalization:
            continue
        actual = (row.get("name"), row.get("meta", {}).get("nameCh"))
        source = (normalization["runtimeSourceName"], normalization["sourceNameCh"])
        canonical = (normalization["runtimeName"], normalization["nameCh"])
        previous = (normalization.get("runtimePreviousName"), normalization["nameCh"])
        if actual not in {source, previous, canonical}:
            raise ValueError(
                f"{row['physicalPlaceId']} runtime source name drift: {actual!r}"
            )
        row["name"], row["meta"]["nameCh"] = canonical
    return result


def normalize_city_const(source: str) -> str:
    lines = source.splitlines(keepends=True)
    for normalization in CANONICAL_PLACE_NAME_NORMALIZATIONS.values():
        old = normalization.get("runtimeSourceName")
        new = normalization.get("runtimeName")
        if not isinstance(old, str) or not isinstance(new, str):
            continue
        previous = normalization.get("runtimePreviousName")
        expected = normalization["runtimeSourceOccurrences"]
        row_ids = set(normalization["runtimeCityConstRowIds"])
        replacements = 0
        for index, line in enumerate(lines):
            match = re.search(r"RawCity\((\d+),", line)
            if match is None or int(match.group(1)) not in row_ids:
                continue
            candidates = [value for value in (old, previous) if isinstance(value, str)]
            hits = sum(line.count(f'"{value}"') for value in candidates)
            if hits == 1:
                for value in candidates:
                    line = line.replace(f'"{value}"', f'"{new}"')
                lines[index] = line
                replacements += 1
        result = "".join(lines)
        if replacements not in {0, expected}:
            raise ValueError(
                f"HanCityConst runtime source occurrence drift for {old}: "
                f"expected {expected} or 0 reviewed rows, got {replacements}"
            )
        canonical_occurrences = result.count(f'"{new}"')
        if canonical_occurrences != normalization["runtimeCanonicalOccurrences"]:
            raise ValueError(
                f"HanCityConst canonical runtime occurrence drift for {new}: "
                f"expected {normalization['runtimeCanonicalOccurrences']}, "
                f"got {canonical_occurrences}"
            )
        target = f'RawCity({normalization["runtimeCityId"]}, "{new}",'
        if result.count(target) != 1:
            raise ValueError(
                f"HanCityConst canonical runtime city drift for "
                f"{normalization['runtimeCityId']}: {new}"
            )
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    tile_blob = json.dumps(
        normalize_han_place_names(json.loads(TILES.read_text(encoding="utf-8"))),
        ensure_ascii=False,
        separators=(",", ":"),
    ) + "\n"
    world_blob = json.dumps(
        normalize_runtime_world(json.loads(HAN_WORLD.read_text(encoding="utf-8"))),
        ensure_ascii=False,
        indent=2,
    ) + "\n"
    city_const_blob = normalize_city_const(HAN_CITY_CONST.read_text(encoding="utf-8"))
    if args.check:
        drift = [
            path.relative_to(ROOT)
            for path, expected in (
                (TILES, tile_blob),
                (HAN_WORLD, world_blob),
                (HAN_CITY_CONST, city_const_blob),
            )
            if path.read_text(encoding="utf-8") != expected
        ]
        if not drift:
            print("드리프트 없음.")
            return 0
        print("드리프트: " + ", ".join(str(path) for path in drift))
        return 1
    TILES.write_text(tile_blob, encoding="utf-8")
    HAN_WORLD.write_text(world_blob, encoding="utf-8")
    HAN_CITY_CONST.write_text(city_const_blob, encoding="utf-8")
    print("reviewed Han place names materialized")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
