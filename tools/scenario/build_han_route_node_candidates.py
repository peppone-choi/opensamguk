#!/usr/bin/env python3
# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
# ─── How to run ───
# uv run tools/scenario/build_han_route_node_candidates.py
# uv run tools/scenario/build_han_route_node_candidates.py --output /tmp/candidates.json
# uv run tools/scenario/build_han_route_node_candidates.py --output /tmp/candidates.json --check

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

SCENARIO_MODULE_DIR = Path(__file__).resolve().parent
if str(SCENARIO_MODULE_DIR) not in sys.path:
    sys.path.insert(0, str(SCENARIO_MODULE_DIR))

from han_route_node_candidates import (
    CandidateContractError,
    JsonObject,
    LegacyNode,
    required_dict,
    required_list,
)
from han_route_node_candidates import (
    build_candidates as build_candidate_rows,
)

ROOT = Path(__file__).resolve().parents[2]
CATALOG = ROOT / "data/curated/han/administrative-units.json"
OVERLAY = ROOT / "data/curated/han/administrative-place-bindings-v1.json"
TILES = ROOT / "data/map/han-780-v1-tiles.json"
HAN = ROOT / "infra/src/main/resources/map/han-780-v1.json"
SCENARIOS = ROOT / "infra/src/main/resources/scenario"


def load_document(path: Path) -> JsonObject:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise CandidateContractError(f"{path.name} root must be an object")
    return value


def serialized(document: JsonObject) -> str:
    return json.dumps(document, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def _included_tiles(tiles: JsonObject) -> dict[tuple[str, str, bool], JsonObject]:
    gameplay = tiles.get("legacyGameplay", tiles)
    if not isinstance(gameplay, dict):
        raise CandidateContractError("legacyGameplay must be an object")
    cities = required_list(gameplay, "cities")
    juns = required_list(gameplay, "juns")
    meta = required_dict(tiles, "_meta")
    cols, rows = meta.get("cols"), meta.get("rows")
    if not isinstance(cols, int) or not isinstance(rows, int) or cols <= 0 or rows <= 0:
        raise CandidateContractError("tile grid dimensions are malformed")
    grid: list[int] = []
    for run in required_list(gameplay, "seatOwner"):
        if not isinstance(run, list) or len(run) != 2 or not all(isinstance(value, int) for value in run):
            raise CandidateContractError("seatOwner run is malformed")
        owner, count = run
        if count < 0:
            raise CandidateContractError("seatOwner run length must be non-negative")
        grid.extend([owner] * count)
    if len(grid) != cols * rows:
        raise CandidateContractError("seatOwner grid length does not match dimensions")
    seat_of: dict[int, int] = {}
    active_parent_names = gameplay.get("activeParentNames")
    if active_parent_names is not None and (
        not isinstance(active_parent_names, list)
        or not all(isinstance(value, str) for value in active_parent_names)
    ):
        raise CandidateContractError("activeParentNames must be an array of strings")
    active_parent_names = set(active_parent_names) if active_parent_names is not None else None
    for index, raw_jun in enumerate(juns):
        if not isinstance(raw_jun, dict) or not isinstance(raw_jun.get("seat"), int):
            raise CandidateContractError("commandery seat is malformed")
        if active_parent_names is not None and raw_jun.get("nameCh") not in active_parent_names:
            continue
        seat_index = raw_jun["seat"]
        if seat_index in seat_of or not 0 <= seat_index < len(cities):
            raise CandidateContractError("duplicate or out-of-range commandery seat")
        seat_of[seat_index] = index
    included: dict[tuple[str, str, bool], JsonObject] = {}
    tile_ids: set[str] = set()
    for city_index, raw_city in enumerate(cities):
        if not isinstance(raw_city, dict):
            raise CandidateContractError("tile city must be an object")
        tile_id = raw_city.get("id")
        if not isinstance(tile_id, str) or not tile_id or tile_id in tile_ids:
            raise CandidateContractError(f"duplicate or malformed tile id: {tile_id}")
        tile_ids.add(tile_id)
        if city_index in seat_of:
            owner_index, is_seat = seat_of[city_index], True
        elif raw_city.get("zhi") is True and raw_city.get("kind") == "COUNTY":
            col, row = raw_city.get("col"), raw_city.get("row")
            if not isinstance(col, int) or not isinstance(row, int) or not (0 <= col < cols and 0 <= row < rows):
                raise CandidateContractError(f"tile city grid position is malformed: {tile_id}")
            owner_index, is_seat = grid[row * cols + col], False
            if owner_index < 0:
                continue
        else:
            continue
        if not 0 <= owner_index < len(juns) or not isinstance(juns[owner_index], dict):
            raise CandidateContractError(f"tile city owner is out of range: {tile_id}")
        name_ch, owner_ch = raw_city.get("nameCh"), juns[owner_index].get("nameCh")
        if not isinstance(name_ch, str) or not isinstance(owner_ch, str):
            raise CandidateContractError(f"tile city historical names are malformed: {tile_id}")
        key = (name_ch, owner_ch, is_seat)
        if key in included:
            raise CandidateContractError(f"duplicate legacy node match key: {key}")
        included[key] = raw_city
    return included


def _legacy_nodes(tiles: JsonObject, han: JsonObject) -> list[LegacyNode]:
    included = _included_tiles(tiles)
    nodes: list[LegacyNode] = []
    ids: set[int] = set()
    used_tile_ids: set[str] = set()
    for raw_city in required_list(han, "cities"):
        if not isinstance(raw_city, dict) or not isinstance(raw_city.get("id"), int):
            raise CandidateContractError("legacy city id is malformed")
        legacy_id = raw_city["id"]
        if legacy_id in ids:
            raise CandidateContractError(f"duplicate legacy city id: {legacy_id}")
        ids.add(legacy_id)
        meta = required_dict(raw_city, "meta")
        key = (meta.get("nameCh"), meta.get("junCh"), meta.get("isSeat"))
        tile_city = included.get(key)
        if tile_city is None:
            raise CandidateContractError(f"legacy city does not match exactly one included tile: {legacy_id}")
        tile_id = str(tile_city["id"])
        if tile_id in used_tile_ids:
            raise CandidateContractError(f"included tile is used by multiple legacy cities: {tile_id}")
        used_tile_ids.add(tile_id)
        nodes.append((legacy_id, tile_city, str(meta["junCh"]), bool(meta["isSeat"])))
    nodes.sort(key=lambda row: row[0])
    if [row[0] for row in nodes] != list(range(1, len(nodes) + 1)):
        raise CandidateContractError("legacy city ids must be the exact sequence 1..N")
    if len(used_tile_ids) != len(included):
        raise CandidateContractError("legacy map and included tile selection counts differ")
    return nodes


def build_candidates(
    catalog: JsonObject,
    overlay: JsonObject,
    tiles: JsonObject,
    han: JsonObject,
    scenario_catalog: list[JsonObject] | None = None,
) -> JsonObject:
    return build_candidate_rows(catalog, overlay, _legacy_nodes(tiles, han), scenario_catalog)


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _source_label(path: Path) -> str:
    resolved = path.resolve()
    try:
        return resolved.relative_to(ROOT).as_posix()
    except ValueError:
        return f"external:{resolved.name}"


def build_scenario_catalog(scenario_dir: Path) -> list[JsonObject]:
    if not scenario_dir.is_dir():
        raise FileNotFoundError(scenario_dir)
    rows: list[JsonObject] = []
    codes: set[str] = set()
    for path in scenario_dir.glob("scenario_*.json"):
        code = path.stem.removeprefix("scenario_")
        if not code.isdecimal() or code in codes:
            raise CandidateContractError(f"duplicate or malformed scenario code: {code}")
        document = load_document(path)
        start_year, map_config = document.get("startYear"), document.get("map")
        if (not isinstance(map_config, dict)
                or map_config.get("mapName") not in {"han", "han-world-v2", "han-world-v3"}):
            continue
        if not isinstance(start_year, int):
            raise CandidateContractError(f"Han scenario is not dated: {path.name}")
        codes.add(code)
        rows.append({
            "code": code, "startYear": start_year, "resourcePath": _source_label(path),
            "resourceSha256": _sha256(path),
        })
    return sorted(rows, key=lambda row: int(str(row["code"])))


def build_document(
    catalog_path: Path,
    overlay_path: Path,
    tiles_path: Path,
    han_path: Path,
    scenario_dir: Path = SCENARIOS,
) -> JsonObject:
    paths = {
        "administrativeCatalog": catalog_path, "administrativePlaceOverlay": overlay_path,
        "legacyTileMap": tiles_path, "legacyHanMap": han_path,
    }
    scenario_catalog = build_scenario_catalog(scenario_dir)
    document = build_candidates(
        *(load_document(path) for path in paths.values()), scenario_catalog=scenario_catalog,
    )
    document["provenance"] = {
        "generator": "tools/scenario/build_han_route_node_candidates.py",
        "inputs": {
            name: {"path": _source_label(path), "sha256": _sha256(path)} for name, path in paths.items()
        },
        "scenarioResourceCount": len(scenario_catalog),
    }
    return document


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--catalog", type=Path, default=CATALOG)
    parser.add_argument("--overlay", type=Path, default=OVERLAY)
    parser.add_argument("--tiles", type=Path, default=TILES)
    parser.add_argument("--han", type=Path, default=HAN)
    parser.add_argument("--scenario-dir", type=Path, default=SCENARIOS)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    try:
        document = build_document(args.catalog, args.overlay, args.tiles, args.han, args.scenario_dir)
        summary = required_dict(document, "summary")
        if summary.get("legacyNodeCount") != 780 or summary.get("replacementPoolCount") != 1180:
            raise CandidateContractError("real candidate build must contain 780 current and 1,180 replacement rows")
        if len(required_list(document, "scenarioCatalog")) != 31:
            raise CandidateContractError("real candidate build must contain exactly 31 active Han scenarios")
        blob = serialized(document)
        if args.check:
            if args.output is None:
                raise CandidateContractError("--check requires --output")
            if not args.output.is_file() or args.output.read_text(encoding="utf-8") != blob:
                print("han route-node candidate drift", file=sys.stderr)
                return 1
            print("han route-node candidates: no drift")
            return 0
        if args.output is None:
            sys.stdout.write(blob)
        else:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(blob, encoding="utf-8")
            print(f"{args.output}: {summary}")
        return 0
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f"han route-node candidate build failed: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
