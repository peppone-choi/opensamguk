#!/usr/bin/env python3
"""Audit administrative seats and game city properties without allocating map planes."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def seat_audit(tiles: dict, world: dict) -> dict:
    jurisdictions = {record["id"]: record for record in tiles["jurisdictionRecords"]}
    provinces = tiles["provinceRecords"]
    cities = {int(city["id"]): city for city in world["cities"]}
    place_cities = {}
    county_candidates = {}
    for city_id, city in cities.items():
        place = str(city.get("physicalPlaceRef", "")).split(":")[-1]
        if place:
            place_cities.setdefault(place, []).append(city_id)
        # These are explicit stable province indices, not geometric proximity.
        province = provinces[int(city["provinceId"])]
        county_candidates.setdefault(province.get("jurisdictionId"), []).append(city_id)
    counties = []
    for county_id, county in jurisdictions.items():
        seat_place = county.get("seatPlaceId")
        candidates = sorted(place_cities.get(str(seat_place), [])) if seat_place is not None else []
        # Some world records use spatialProvinceId directly instead of physicalPlaceRef.
        if not candidates and seat_place is not None:
            candidates = sorted(city_id for city_id in county_candidates.get(county_id, [])
                                if str(cities[city_id].get("spatialProvinceId")) == str(seat_place))
        counties.append(dict(id=county_id, seatPlaceId=seat_place, cityId=candidates[0] if len(candidates) == 1 else None,
                             seatCandidates=candidates, provinceCityIds=sorted(county_candidates.get(county_id, []))))
    county_by_id = {county["id"]: county for county in counties}
    administrative = []
    for commandery in tiles["commanderyRecords"]:
        county = county_by_id.get(commandery.get("seatJurisdictionId"))
        city_id = county["cityId"] if county else None
        administrative.append(dict(id=commandery["id"], name=commandery["displayName"],
            seatJurisdictionId=commandery.get("seatJurisdictionId"),
            seatPlaceId=county["seatPlaceId"] if county else None,
            seatCityId=city_id, candidateCityIds=county["seatCandidates"] if county else [],
            jurisdictionCount=len(commandery.get("jurisdictionIds", [])),
            gameIsSeat=(cities[city_id].get("meta") or {}).get("isSeat") if city_id is not None else None))
    administrative_ids = {record["seatCityId"] for record in administrative if record["seatCityId"] is not None}
    game_ids = {city_id for city_id, city in cities.items() if (city.get("meta") or {}).get("isSeat") is True}
    disagreement_ids = sorted(administrative_ids ^ game_ids)
    return dict(
        definitions=dict(administrative="commandery.seatJurisdictionId -> jurisdiction.seatPlaceId -> explicit world place binding",
                         game="world cities.meta.isSeat; no footprint/centre inference"),
        counts=dict(commanderyRecords=len(administrative), administrativeSeats=len(administrative_ids),
                    gameSeats=len(game_ids), intersection=len(administrative_ids & game_ids)),
        administrativeCityIds=sorted(administrative_ids), gameCityIds=sorted(game_ids),
        intersection=sorted(administrative_ids & game_ids), administrativeOnly=sorted(administrative_ids - game_ids),
        gameOnly=sorted(game_ids - administrative_ids), counties=counties, commanderies=administrative,
        disagreements=[dict(cityId=city_id, sourceName=cities[city_id]["name"],
                            displayName=(cities[city_id].get("meta") or {}).get("displayName"),
                            physicalPlaceRef=cities[city_id].get("physicalPlaceRef"),
                            spatialProvinceId=cities[city_id].get("spatialProvinceId"),
                            provinceIndex=cities[city_id]["provinceId"],
                            administrativeSeat=city_id in administrative_ids, gameSeat=city_id in game_ids)
                       for city_id in disagreement_ids])


def audit(root: Path) -> dict:
    paths = dict(sourceTiles=root / "data/map/province-tiles.json",
                 world=root / "infra/src/main/resources/map/han-world-v3.json",
                 economy=root / "data/curated/han/county-economy-inputs-v1.json")
    raw = {key: path.read_bytes() for key, path in paths.items()}
    docs = {key: json.loads(blob) for key, blob in raw.items()}
    result = seat_audit(docs["sourceTiles"], docs["world"])
    households = {int(record["cityId"]): record.get("households") for record in docs["economy"]["jurisdictions"]
                  if record.get("cityId") is not None}
    result.update(schemaVersion=1, inputFingerprint={key:hashlib.sha256(blob).hexdigest() for key, blob in raw.items()},
                  householdsUnknown=[int(city["id"]) for city in docs["world"]["cities"] if households.get(int(city["id"])) is None],
                  labelAnchorMissing=[record for record in result["commanderies"] if record["seatCityId"] is None])
    return result


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args(argv)
    args.out.write_text(json.dumps(audit(args.root), ensure_ascii=False, sort_keys=True, indent=1) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
