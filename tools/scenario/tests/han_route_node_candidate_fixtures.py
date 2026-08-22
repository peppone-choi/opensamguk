from __future__ import annotations

from typing import TypeAlias


JsonValue: TypeAlias = str | int | float | bool | None | list["JsonValue"] | dict[str, "JsonValue"]
JsonObject: TypeAlias = dict[str, JsonValue]


def unit(group_name: str, ordinal: int, *, volume: int = 109) -> JsonObject:
    return {
        "sourceVolume": volume,
        "canonicalGroup": group_name,
        "ordinal": ordinal,
        "sourceName": f"縣{ordinal}",
        "sourceNameStatus": "SOURCE_LITERAL",
        "unitType": "COUNTY",
        "sourceCitation": {"corpusPath": "fixture.txt", "line": ordinal},
    }


def group(name: str, units: list[JsonObject], *, alias: str | None = None) -> JsonObject:
    return {
        "sourceVolume": units[0]["sourceVolume"],
        "sourceGroupName": alias or name,
        "canonicalGroup": name,
        "units": units,
    }


def catalog(groups: list[JsonObject]) -> JsonObject:
    unit_count = sum(len(row["units"]) for row in groups)
    return {
        "schemaVersion": 1,
        "catalogId": "fixture-catalog",
        "expectedGroupCount": len(groups),
        "expectedUnitCount": unit_count,
        "detectedGroupCount": len(groups),
        "detectedUnitCount": unit_count,
        "groups": groups,
    }


def admin_id(row: JsonObject) -> str:
    return f'hhs:{row["sourceVolume"]}:{row["canonicalGroup"]}:{row["ordinal"]:03d}'


def overlay_row(row: JsonObject, status: str, physical_ids: list[str]) -> JsonObject:
    candidates: list[JsonObject] = [
        {
            "physicalPlaceId": physical_id,
            "chgisSysId": physical_id.rsplit(":", 1)[-1],
            "recordIndex": index + 1,
            "coordinate": [110.0 + index, 35.0],
            "presentLocation": "/private/external/location",
        }
        for index, physical_id in enumerate(physical_ids)
    ]
    result: JsonObject = {
        "administrativeUnitId": admin_id(row),
        "identity": {
            "sourceVolume": row["sourceVolume"],
            "canonicalGroup": row["canonicalGroup"],
            "ordinal": row["ordinal"],
        },
        "joinStatus": status,
        "candidateCount": len(candidates),
    }
    if status == "RESOLVED_POINT":
        result["selectedCandidate"] = candidates[0]
    elif status == "AMBIGUOUS_POINT":
        result["candidates"] = candidates
    return result


def overlay(rows: list[JsonObject]) -> JsonObject:
    return {
        "schemaVersion": 1,
        "catalogId": "fixture-catalog",
        "sourceYear": 220,
        "administrativeUnits": rows,
    }


def tile(
    tile_id: str,
    name_ch: str,
    *,
    kind: str = "COUNTY",
    seat: bool = True,
    zhi: bool = False,
) -> JsonObject:
    return {
        "id": tile_id,
        "name": name_ch,
        "nameCh": name_ch,
        "kind": kind,
        "seat": seat,
        "zhi": zhi,
        "col": 1,
        "row": 0,
        "lon": 110.0,
        "lat": 35.0,
    }


def tile_document(cities: list[JsonObject], owner_groups: list[str]) -> JsonObject:
    juns: list[JsonObject] = [
        {"name": name, "nameCh": name, "seat": index, "col": index, "row": 0}
        for index, name in enumerate(owner_groups)
    ]
    return {
        "_meta": {"cols": max(1, len(cities)), "rows": 1},
        "cities": cities,
        "juns": juns,
        "seatOwner": [[index, 1] for index in range(len(cities))],
    }


def han_document(cities: list[JsonObject], owner_groups: list[str]) -> JsonObject:
    return {
        "cities": [
            {
                "id": index + 1,
                "name": city["name"],
                "x": 10,
                "y": 20,
                "meta": {
                    "nameCh": city["nameCh"],
                    "junCh": owner_groups[index],
                    "isSeat": True,
                },
            }
            for index, city in enumerate(cities)
        ]
    }


def fixture(
    units: list[JsonObject],
    overlay_rows: list[JsonObject],
    cities: list[JsonObject],
    owner_groups: list[str],
    *,
    groups: list[JsonObject] | None = None,
) -> tuple[JsonObject, JsonObject, JsonObject, JsonObject]:
    catalog_groups = groups or [group(owner_groups[0], units)]
    return (
        catalog(catalog_groups),
        overlay(overlay_rows),
        tile_document(cities, owner_groups),
        han_document(cities, owner_groups),
    )
