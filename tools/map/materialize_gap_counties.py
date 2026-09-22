#!/usr/bin/env python3
"""결손 縣 원장을 han-tiles 의 城·관할 명부에 세운다 (사슬 단계, ★ 바로 앞).

`data/curated/han/gap-counties-v1.json` 은 여섯 심사 원장이 220 년 존속을 확정하고 좌표까지 확보한
60 縣(33 郡)이다. 이 단계는 그것을 **명부 수준까지만** 올린다 — `cities[]` 물리 지점과
`jurisdictionRecords[]` 관할, 그리고 郡의 `jurisdictionIds`.

**省 기하는 건드리지 않는다** — `owner` 격자는 그대로 두고, 縣마다 **칸 없는 省 행 하나**만 더한다.
칸을 나누는 일은 다음 단계 ★(`partition_counties_by_location.py`)가 한다. 그쪽이 `owner`·
`provinceRecords`·`jurisdictionRecords`·`cities` 를 통째로 다시 쓴다.

省 행을 반드시 만들어야 한다 — 처음에 만들지 않았다가 ★ 가 죽었다.
`stand_in = seat_province is None or seat_province.get("cityIndex") is None` 이고 stand_in 경로는
`provinces_in[old_ids[0]]` 로 **기존 省을 템플릿으로 찾는다**. 새 관할의 `provinceIds` 가 비어 있으면
거기서 IndexError 다. 규칙 1b 의 `STAND_IN_SEAT_POINT` 는 씨앗 **칸**을 구하는 길일 뿐 템플릿을
주지 않는다. 그래서 `cityIndex` 가 박힌 省 행을 만들어 stand_in 을 피한다(변경 縣 도구와 같은 이유).
`owner` 에 칸이 없는 省 이 되지만 ★ 가 곧 칸을 준다 — 색인은 뒤에 붙이므로 기존 색인이 밀리지 않는다.

`materialize_frontier_counties` 를 쓰지 않는 이유가 있다. 그 도구는 영향 郡의 기존 省 레코드를
**전부 버리고** 「郡治 + 신규 縣」만을 앵커로 재구획한다. 변경 7 郡은 城이 없었으니 안전했지만
이 60 縣의 33 郡은 대부분 기존 縣을 갖고 있어(九江郡 12 縣 등) 그대로 쓰면 그 縣들의 省이 사라진다.

칸 정하기는 그 도구와 같은 규칙이다 — 좌표를 `_meta.projection` 으로 투영하고, 그 칸이 육지이고
후한 郡(HAN_COMMANDERY) 땅이며 비어 있으면 그대로 쓴다(`PARENT_OWNER_AT_PROJECTED_CELL`).
아니면 郡國志 郡의 가장 가까운 빈 육지 칸으로 옮긴다(`SNAPPED_INTO_HHS_COMMANDERY`).

멱등이다 — 이미 세운 문서는 `gc-` 행을 걷어낸 뒤 다시 세운다.

    /usr/local/bin/python3 tools/map/materialize_gap_counties.py --output <스크래치>
    /usr/local/bin/python3 tools/map/materialize_gap_counties.py --prepare --output data/map/han-tiles.json
    /usr/local/bin/python3 tools/map/materialize_gap_counties.py --check

`--prepare` 뒤에는 꼬리 5 단계를 **그 순서 그대로** 다시 구워야 한다. 하나라도 빼면 조용히 층이
날아간다 — `refine_korea_places` 를 빼면 城 35 개와 관할 35 개(1168→1133)가 사라진다:

    partition_counties_by_location --prepare --output data/map/han-tiles.json
    carve_strategic_site_provinces --prepare --output data/map/han-tiles.json
    fold_cityless_jurisdictions    --prepare --output data/map/han-tiles.json
    reclassify_han_lowland_terrain --prepare --source-is-upstream --output data/map/han-tiles.json
    refine_korea_places            --prepare
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))
from materialize_frontier_counties import (  # noqa: E402
    WATER_OR_OUT_OF_SCOPE, COUNTY_TILE_LEVEL, BASIS_AT_CELL, BASIS_SNAPPED,
    project_cell, _nearest_cell, projection_of,
)
from measure_province_seat_offset import expand_rle  # noqa: E402

TILES = ROOT / "data/map/han-tiles.json"
LEDGER = ROOT / "data/curated/han/gap-counties-v1.json"
PLACEMENTS = ROOT / "data/curated/han/gap-county-placements-v1.json"
PLACE_ID_PREFIX = "gc-"


class GapMaterializationError(ValueError):
    pass


def place_id(county: dict, parent_id: str) -> str:
    """`gc-<郡 번호>-<郡國志 순번>`. 郡 번호는 parentRegions 의 안정 id 꼬리다."""
    ordinal = county["id"].rsplit(":", 1)[-1]
    return f"{PLACE_ID_PREFIX}{parent_id.rsplit('-', 1)[-1]}-{ordinal}"


def strip_gap_counties(document: dict, *, counts_before: dict | None = None) -> dict:
    """`gc-` 행을 걷어낸다. counts_before 를 주면 `_meta.counts` 도 되돌린다 — 앞 단계의 지문이
    counts 를 포함하므로 그것까지 되돌려야 원복이 성립한다."""
    stripped = dict(document)
    stripped["cities"] = [r for r in document["cities"]
                          if not str(r["id"]).startswith(PLACE_ID_PREFIX)]
    stripped["provinceRecords"] = [r for r in document["provinceRecords"]
                                   if not str(r["id"]).startswith(PLACE_ID_PREFIX)]
    stripped["jurisdictionRecords"] = [r for r in document["jurisdictionRecords"]
                                       if not str(r["id"]).startswith(PLACE_ID_PREFIX)]
    stripped["commanderyRecords"] = [
        dict(r, jurisdictionIds=[i for i in r["jurisdictionIds"]
                                 if not str(i).startswith(PLACE_ID_PREFIX)])
        for r in document["commanderyRecords"]
    ]
    if counts_before is not None:
        meta = dict(document["_meta"])
        meta["counts"] = dict(counts_before)
        stripped["_meta"] = meta
    return stripped


def has_gap_counties(document: dict) -> bool:
    return any(str(r["id"]).startswith(PLACE_ID_PREFIX) for r in document["cities"])


def place_counties(document: dict, ledger: dict) -> list[dict]:
    """縣마다 칸·세계 郡을 정한다. 문서는 바꾸지 않는다."""
    meta = document["_meta"]
    rows, cols = meta["rows"], meta["cols"]
    projection = meta["projection"]
    terrain = np.array([[int(v) for v in line] for line in document["terrain"]], dtype=np.int8)
    owner = expand_rle(document["owner"], rows, cols)
    parent_owner = expand_rle(document["parentOwner"], rows, cols)
    parents = document["parentRegions"]
    parent_index_by_name = {row["nameCh"]: index for index, row in enumerate(parents)}
    land = (owner >= 0) & ~np.isin(terrain, sorted(WATER_OR_OUT_OF_SCOPE))
    occupied = {(int(r["row"]), int(r["col"])) for r in document["cities"]}

    placements: list[dict] = []
    for county in ledger["counties"]:
        coordinates = county.get("coordinates") or {}
        latitude, longitude = coordinates.get("latitude"), coordinates.get("longitude")
        if not isinstance(latitude, (int, float)) or not isinstance(longitude, (int, float)):
            raise GapMaterializationError(f"gap county has no coordinates: {county['id']}")
        if county["commanderyHan"] not in parent_index_by_name:
            raise GapMaterializationError(f"commandery is not a parent region: {county['id']}")
        hhs_parent = parent_index_by_name[county["commanderyHan"]]
        projected_col, projected_row = project_cell(projection, latitude, longitude)
        cell_col, cell_row = int(projected_col), int(projected_row)
        if not (0 <= cell_col < cols and 0 <= cell_row < rows):
            raise GapMaterializationError(f"county projects outside the grid: {county['id']}")
        at_cell = int(parent_owner[cell_row, cell_col])
        at_cell_is_han = at_cell >= 0 and parents[at_cell]["administrativeSystem"] == "HAN_COMMANDERY"
        if land[cell_row, cell_col] and at_cell_is_han and (cell_row, cell_col) not in occupied:
            world_parent, basis, distance = at_cell, BASIS_AT_CELL, 0.0
            final_row, final_col = cell_row, cell_col
        else:
            mask = land & (parent_owner == hhs_parent)
            candidates = np.array(
                [c for c in np.argwhere(mask) if (int(c[0]), int(c[1])) not in occupied],
                dtype=np.int64,
            )
            if len(candidates) == 0:
                raise GapMaterializationError(f"no free land cell in the HHS commandery: {county['id']}")
            final_row, final_col, distance = _nearest_cell(candidates, projected_row, projected_col)
            world_parent, basis = hhs_parent, BASIS_SNAPPED
        occupied.add((final_row, final_col))
        world_parent_id = parents[world_parent]["id"]
        placements.append({
            "id": county["id"],
            "nameHan": county["nameHan"],
            "physicalPlaceId": place_id(county, world_parent_id),
            "projectedCol": round(projected_col, 3),
            "projectedRow": round(projected_row, 3),
            "col": final_col,
            "row": final_row,
            "terrain": int(terrain[final_row, final_col]),
            "hhsCommanderyHan": county["commanderyHan"],
            "hhsParentRegionId": parents[hhs_parent]["id"],
            "worldParentRegionId": world_parent_id,
            "worldCommanderyHan": parents[world_parent]["nameCh"],
            "parentOwnerAtProjectedCell": parents[at_cell]["nameCh"] if at_cell >= 0 else None,
            "basis": basis,
            "snapDistanceCells": round(distance, 3),
        })
    return placements


def materialize(document: dict, ledger: dict) -> tuple[dict, dict]:
    if has_gap_counties(document):
        document = strip_gap_counties(document)
    placements = place_counties(document, ledger)
    counties_by_id = {row["id"]: row for row in ledger["counties"]}

    cities = [dict(row) for row in document["cities"]]
    provinces = [dict(row) for row in document["provinceRecords"]]
    # **정렬하지 않는다.** 입력 배열 순서가 앞 단계들의 지문 입력이다 — pre-★ 문서의 관할 배열은
    # id 순이 아니다(끝이 fc-xuantu-006, 82879). 전역 정렬을 걸었다가 걷어내도 원복이 안 됐다.
    jurisdictions = [dict(row) for row in document["jurisdictionRecords"]]
    commanderies = {row["id"]: dict(row) for row in document["commanderyRecords"]}
    for placement in placements:
        county = counties_by_id[placement["id"]]
        suffix = county["countySuffix"]
        cities.append({
            "id": placement["physicalPlaceId"],
            "name": f"{county['nameKo']}현",
            "nameCh": f"{county['nameHan']}{suffix}",
            "level": COUNTY_TILE_LEVEL,
            "kind": "COUNTY",
            "seat": False,
            "zhi": False,
            "col": placement["col"],
            "row": placement["row"],
            "lon": county["coordinates"]["longitude"],
            "lat": county["coordinates"]["latitude"],
        })
        parent_id = placement["worldParentRegionId"]
        provinces.append({
            "id": placement["physicalPlaceId"],
            "displayName": f"{county['nameKo']}현",
            "nameCh": f"{county['nameHan']}{suffix}",
            "administrativeSystem": "HAN_COMMANDERY",
            "kind": "SPATIAL_PROVINCE",
            "parentRegionId": parent_id,
            "cityIndex": len(cities) - 1,
            "geometryBasis": "HISTORICAL_SEAT_ADAPTED",
            "confidence": "IDENTIFIED",
            "jurisdictionId": placement["physicalPlaceId"],
            "assignmentBasis": "HISTORICAL_SEAT",
            "assignmentConfidence": "IDENTIFIED",
        })
        jurisdictions.append({
            "id": placement["physicalPlaceId"],
            "displayName": f"{county['nameKo']}현",
            "nameCh": f"{county['nameHan']}{suffix}",
            "kind": "COUNTY",
            "commanderyId": parent_id,
            "seatPlaceId": placement["physicalPlaceId"],
            "provinceIds": [placement["physicalPlaceId"]],
        })
        commandery = commanderies[parent_id]
        commandery["jurisdictionIds"] = sorted({
            *commandery["jurisdictionIds"], placement["physicalPlaceId"],
        })

    updated = dict(document)
    updated["cities"] = cities
    updated["provinceRecords"] = provinces
    updated["jurisdictionRecords"] = jurisdictions
    updated["commanderyRecords"] = [commanderies[r["id"]] for r in document["commanderyRecords"]]
    meta = dict(document["_meta"])
    counts = dict(meta["counts"])
    kinds: dict[str, int] = {}
    for row in cities:
        kinds[row["kind"]] = kinds.get(row["kind"], 0) + 1
    counts.update(cities=len(cities), **kinds,
                  provinces=len(provinces),
                  jurisdictions=len(updated["jurisdictionRecords"]))
    meta["counts"] = counts
    updated["_meta"] = meta

    from collections import Counter
    placement_document = {
        "schemaVersion": 1,
        "ledgerId": "han-gap-county-placements-v1",
        "generator": "tools/map/materialize_gap_counties.py",
        "note": ("생성물이다. gap-counties-v1 의 좌표를 han-tiles 투영으로 칸에 앉힌 결과와 세계 소속 郡이다. "
                 "省 구획은 여기서 정하지 않는다 — 다음 단계 ★(partition_counties_by_location) 가 관할마다 씨앗을 잡는다."),
        "inputs": {
            "gapCountyLedger": {"path": "data/curated/han/gap-counties-v1.json"},
            "projection": dict(projection_of(document["_meta"])),
        },
        "priorStage": {
            "countsBefore": dict(document["_meta"]["counts"]),
            "jurisdictionCountBefore": len(document["jurisdictionRecords"]),
            "cityCountBefore": len(document["cities"]),
            "provinceCountBefore": len(document["provinceRecords"]),
        },
        "summary": {
            "placedCount": len(placements),
            "basisCounts": dict(Counter(p["basis"] for p in placements)),
            "commanderyReassignments": sorted(
                f"{p['hhsCommanderyHan']}→{p['worldCommanderyHan']}: {p['nameHan']}"
                for p in placements if p["hhsParentRegionId"] != p["worldParentRegionId"]
            ),
        },
        "placements": placements,
    }
    return updated, placement_document


def _dump(document: dict) -> str:
    return json.dumps(document, ensure_ascii=False, separators=(",", ":")) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, default=TILES)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--prepare", action="store_true")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    ledger = json.loads(LEDGER.read_text(encoding="utf-8"))
    document = json.loads(args.source.read_text(encoding="utf-8"))

    if args.check:
        problems: list[str] = []
        # 이 단계의 출력은 ★ 의 **입력**이다. 커밋본을 그대로 보면 안 된다 — 뒤 단계가 城 칸을
        # 정당하게 옮긴다(기존 城과 겹친 6 건이 1 칸씩 밀렸다). ★ 의 stage_input 으로 이 단계
        # 자리의 문서까지 벗겨서 대조한다.
        from partition_counties_by_location import stage_input  # noqa: E402
        staged = stage_input(document)
        cities = {r["id"]: r for r in staged["cities"]}
        jurisdictions = {r["id"]: r for r in staged["jurisdictionRecords"]}
        parents_by_id = {r["id"]: r for r in staged["parentRegions"]}
        if not PLACEMENTS.exists():
            print(f"STALE {PLACEMENTS} (없다)")
            return 1
        placements = json.loads(PLACEMENTS.read_text(encoding="utf-8"))["placements"]
        if len(placements) != len(ledger["counties"]):
            problems.append(f"배치 행 {len(placements)} != 원장 縣 {len(ledger['counties'])}")
        for placement in placements:
            pid = placement["physicalPlaceId"]
            city = cities.get(pid)
            if city is None:
                problems.append(f"城 없음: {pid} ({placement['nameHan']})")
                continue
            if (int(city["row"]), int(city["col"])) != (placement["row"], placement["col"]):
                problems.append(f"칸 다름: {pid}")
            juris = jurisdictions.get(pid)
            if juris is None:
                problems.append(f"관할 없음: {pid}")
            elif juris["commanderyId"] != placement["worldParentRegionId"]:
                problems.append(f"郡 다름: {pid} {juris['commanderyId']} != {placement['worldParentRegionId']}")
            elif parents_by_id[juris["commanderyId"]]["nameCh"] != placement["worldCommanderyHan"]:
                problems.append(f"郡 이름 다름: {pid}")
        if problems:
            for problem in problems[:20]:
                print(f"  {problem}")
            print(f"STALE {TILES} — {len(problems)} 건")
            return 1
        print(f"OK {len(placements)} 결손 縣이 명부에 있다")
        return 0

    if args.output is None:
        parser.error("--output 이 필요하다")
    if not args.prepare and any(part in ("data", "infra") for part in args.output.parts):
        parser.error("--prepare 없이는 data/·infra/ 밑에 쓰지 않는다")
    updated, placement_document = materialize(document, ledger)
    args.output.write_text(_dump(updated), encoding="utf-8")
    if args.prepare:
        PLACEMENTS.write_text(
            json.dumps(placement_document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    summary = placement_document["summary"]
    print(f"세운 縣 {summary['placedCount']} · 근거 {summary['basisCounts']}")
    for row in summary["commanderyReassignments"]:
        print(f"  郡 재배정: {row}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
