#!/usr/bin/env python3
"""Attach reviewed runtime-city → canonical province identities to han.json.

The runtime city array still uses the approved legacy gameplay namespace, while
provinceRecords uses physical place IDs.  This joins the two only through the
same legacy source row and exact physical place ID.  It never guesses a county
from proximity.  Commandery points without a county footprint are emitted to an
explicit unresolved ledger and remain on the legacy presentation fallback.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
TILES = ROOT / "data/map/han-tiles.json"
WORLD = ROOT / "infra/src/main/resources/map/han.json"
UNRESOLVED = ROOT / "data/curated/han/runtime-province-identity-unresolved-v1.json"


def build() -> tuple[str, str, dict]:
    tiles = json.loads(TILES.read_text(encoding="utf-8"))
    world = json.loads(WORLD.read_text(encoding="utf-8"))
    legacy = tiles["legacyGameplay"]
    cols = tiles["_meta"]["cols"]
    rows = tiles["_meta"]["rows"]

    runtime_key_to_legacy: dict[tuple[int, int, str], list[dict]] = {}
    for city in legacy["cities"]:
        key = (
            round(city["col"] * world["width"] / cols),
            round(city["row"] * world["height"] / rows),
            city["nameCh"],
        )
        runtime_key_to_legacy.setdefault(key, []).append(city)

    canonical_cities = tiles["cities"]
    province_by_physical_id = {
        canonical_cities[record["cityIndex"]]["id"]: province_id
        for province_id, record in enumerate(tiles["provinceRecords"])
        if record.get("cityIndex") is not None
    }

    unresolved = []
    bound = 0
    for city in world["cities"]:
        key = (city["x"], city["y"], city["meta"]["nameCh"])
        candidates = runtime_key_to_legacy.get(key, [])
        if len(candidates) != 1:
            raise ValueError(f"runtime city {city['id']} has {len(candidates)} exact legacy rows: {key}")
        physical_place_id = candidates[0]["id"]
        city["physicalPlaceId"] = physical_place_id
        province_id = province_by_physical_id.get(physical_place_id)
        if province_id is None:
            city.pop("provinceId", None)
            unresolved.append({
                "runtimeCityId": city["id"],
                "runtimeName": city["name"],
                "physicalPlaceId": physical_place_id,
                "commanderyName": city["meta"]["jun"],
                "commanderyNameCh": city["meta"]["junCh"],
                "reason": "COMMANDERY_POINT_HAS_NO_COUNTY_PROVINCE_FOOTPRINT",
            })
        else:
            city["provinceId"] = province_id
            bound += 1

    province_ids = [city["provinceId"] for city in world["cities"] if "provinceId" in city]
    if len(province_ids) != len(set(province_ids)):
        raise ValueError("exact physical-place join assigned more than one runtime city to a province")

    world["_meta"]["provinceIdentity"] = {
        "method": "EXACT_LEGACY_SOURCE_ROW_PHYSICAL_PLACE_ID",
        "provinceRecordSource": "data/map/han-tiles.json provinceRecords[]",
        "bound": bound,
        "unresolved": len(unresolved),
        "unresolvedLedger": "data/curated/han/runtime-province-identity-unresolved-v1.json",
    }
    ledger = {
        "schemaVersion": 1,
        "ledgerId": "han-runtime-province-identity-unresolved-v1",
        "policy": {
            "forbidNearestGeometryAssignment": True,
            "resolutionRequirement": "reviewed county-seat physicalPlaceId with a canonical province footprint",
        },
        "summary": {"runtimeCities": len(world["cities"]), "bound": bound, "unresolved": len(unresolved)},
        "rows": unresolved,
    }
    return (
        json.dumps(world, ensure_ascii=False, indent=2) + "\n",
        json.dumps(ledger, ensure_ascii=False, indent=2) + "\n",
        ledger["summary"],
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    world_blob, ledger_blob, summary = build()
    if args.write:
        WORLD.write_text(world_blob, encoding="utf-8")
        UNRESOLVED.write_text(ledger_blob, encoding="utf-8")
    else:
        drift = []
        if WORLD.read_text(encoding="utf-8") != world_blob:
            drift.append(str(WORLD.relative_to(ROOT)))
        if not UNRESOLVED.exists() or UNRESOLVED.read_text(encoding="utf-8") != ledger_blob:
            drift.append(str(UNRESOLVED.relative_to(ROOT)))
        if drift:
            raise SystemExit("drift: " + ", ".join(drift))
    print(json.dumps(summary, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
