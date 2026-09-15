#!/usr/bin/env python3
"""수(水)·진(鎭)·관(關) 거점에 제 省을 떼어 준다 — han-tiles 의 마지막 단계.

ADR-LITE-052 는 縣이 아닌 거점(關·津·鎭)에 프로빈스와 점령·이동을 주되 기존 城 등급 체계
아래(수 1·진 2·관 3)에 두기로 했다. 사용자는 거점 省을 **늘 소속 縣 省에서 떼어 내라**고
정했다(2026-09-15). 이 도구가 그 기하를 만든다.

입력 원장 둘:
  - data/curated/han/strategic-strongholds-v1.json  수(FERRY)·진(FORT), 正史 근거 + 좌표
  - data/curated/han/strategic-passes-v1.json       관(PASS), 正史 근거 + 좌표

규칙(좌표를 지어내지 않는다):
  1. 원장 좌표를 `_meta.projection` 으로 투영한 칸이 앵커다(關·전장 표식과 같은 식).
  2. 앵커 칸이 수역(owner -1)이면 MAXIMUM_DISPLACEMENT 칸 안의 가장 가까운 육지 칸을 앵커로 삼고
     옮긴 사실을 적는다(舒口 — 巢湖 칸). 그 안에 육지가 없으면 WATER_OR_OUT_OF_SCOPE.
  3. 앵커 칸의 省은 縣 관할(COUNTY) 또는 郡國 밖 세력 취락(EXTERNAL_SETTLEMENT)이어야 한다 —
     魏–高句麗 전쟁의 安平口·梁口처럼 거점이 郡國 밖 땅에 서는 것은 사료가 말하는 자리다(2026-09-15 사용자 결정).
  4. 앵커에서 거리 순으로 FOOTPRINT 칸을 자라게 해 떼어 낸다. 城 점이 선 칸은 건드리지 않고,
     남는 기증 省은 조각 수가 늘지 않고 MINIMUM_AREA 칸 이상 남아야 한다.
  5. 투영 칸에서 그렇게 뗄 수 없으면(城 점이 서 있거나, 같은 縣의 앞 거점이 가져갔거나, 목이 좁아
     기증 省이 갈라지면) 같은 縣 省 안에서 MAXIMUM_DISPLACEMENT 칸 이내의 가까운 칸을 차례로 시도하고
     옮긴 사실(displacedFrom)을 원장에 적는다. 기증 省이 가늘어 8칸으로는 어디서도 안 되면 발자국을
     MINIMUM_FOOTPRINT 칸까지 줄여 다시 시도한다(carvedCellCount 에 남는다). 끝내 못 하면 DONOR_TOO_SMALL.
     떼어 낸 발자국은 남는 기증 省과 마른땅 경계(DRY_TERRAIN 칸끼리 맞닿은 변)를 하나 이상 공유해야 한다 —
     런타임 보급망은 마른땅 경계만 잇는다(projectHanDryLandEdges). 강 칸으로만 붙으면 거점이 제 縣에서
     끊긴다(孟津 — 河陰縣과 黃河 칸으로만 닿아 1020 보급이 잘렸다).
  6. 새 省·관할·城 점은 배열 **끝에** 붙인다 — 기존 省 인덱스(게임 provinceId)는 한 칸도 밀리지 않는다.

이 단계는 오배정 縣 재바인딩보다 나중이다. 앞 단계 검사들은 `peel()` 로 이 단계를 벗긴 문서를
본다. 원장의 `geometry.stages` 가 셀 델타와 덧붙인 행을 핀으로 박아 되돌리기를 바이트 단위로 보장한다.

    python3 tools/map/carve_strategic_site_provinces.py --prepare --output data/map/han-tiles.json
    python3 tools/map/carve_strategic_site_provinces.py --check
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import math
import sys
from collections import deque
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from tools.map.build_terrain_grid import Proj, adjacency  # noqa: E402
from tools.map.rebind_misbound_counties import encode, expand, neighbours  # noqa: E402
from tools.map.world_province_geometry import _rederive_parent_surfaces  # noqa: E402

TILES = ROOT / "data/map/han-tiles.json"
STRONGHOLDS = ROOT / "data/curated/han/strategic-strongholds-v1.json"
PASSES = ROOT / "data/curated/han/strategic-passes-v1.json"
LEDGER = ROOT / "data/curated/han/strategic-site-province-carves-v1.json"

SITE_PREFIX = "ss-"
SITE_KIND = "STRATEGIC_SITE"
ROLE_LEVEL = {"FERRY": 1, "FORT": 2, "PASS": 3}
FOOTPRINT = 8
# 기증 縣 省이 가는 띠 모양이면 8칸을 떼는 순간 나머지가 끊긴다(河陰縣 20칸 — 孟津).
# 그때만 발자국을 이 크기까지 줄여 다시 시도한다. 거점을 모양 탓으로 빼지 않기 위한 규칙이다.
MINIMUM_FOOTPRINT = 4
DONOR_JURISDICTION_KINDS = frozenset({"COUNTY", "EXTERNAL_SETTLEMENT"})
MINIMUM_AREA = 8
# 런타임 보급망이 잇는 지형 이름(HanStrategicTopologyJson dryNames 와 같은 집합). 코드는 terrainLegend 로 푼다.
DRY_TERRAIN_NAMES = frozenset({"PLAIN", "MOUNTAIN", "DESERT", "PLATEAU", "BASIN", "HILL"})
TILE_PLACE_LEVEL = 5  # han-tiles cities[].level 은 CHGIS 계층값이다. 게임 등급은 build_han_world 가 정한다.


def digest(document: dict) -> str:
    return hashlib.sha256(json.dumps(document, ensure_ascii=False, sort_keys=True,
                                     separators=(",", ":")).encode()).hexdigest()


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_sites(strongholds: dict, passes: dict) -> list[dict]:
    """두 원장을 한 목록으로. 정렬은 id 순 — 새 省 인덱스 발급 순서가 된다."""
    sites = []
    for row in strongholds["strongholds"]:
        if ROLE_LEVEL.get(row["role"]) != row["cityLevel"]:
            raise ValueError(f"{row['id']}: role {row['role']} does not match cityLevel {row['cityLevel']}")
        sites.append({"id": row["id"], "nameKo": row["nameKo"], "nameHan": row["nameHan"], "role": row["role"],
                      "latitude": row["coordinates"]["latitude"], "longitude": row["coordinates"]["longitude"]})
    for row in passes["passes"]:
        if row["role"] != "PASS":
            raise ValueError(f"{row['id']}: passes ledger row is not a PASS")
        sites.append({"id": row["id"], "nameKo": row["nameKo"], "nameHan": row["nameHan"], "role": "PASS",
                      "latitude": row["coordinates"]["latitude"], "longitude": row["coordinates"]["longitude"]})
    ids = [row["id"] for row in sites]
    if len(ids) != len(set(ids)):
        raise ValueError("strategic site ids must be unique across the stronghold and pass ledgers")
    return sorted(sites, key=lambda row: row["id"])


MAXIMUM_DISPLACEMENT = 6  # 앵커를 옮겨도 되는 최대 칸 거리(유클리드). 넘으면 세우지 않는다.


def _anchor_candidates(owner: np.ndarray, donor: int, cell: tuple[int, int], taken: set[tuple[int, int]]):
    """투영 칸부터 거리 순으로, 아직 그 縣 省에 남아 있고 城 점이 없는 칸."""
    candidates = [(int(r), int(c)) for r, c in np.argwhere(owner == donor) if (int(r), int(c)) not in taken]
    candidates = [rc for rc in candidates
                  if (rc[0] - cell[0]) ** 2 + (rc[1] - cell[1]) ** 2 <= MAXIMUM_DISPLACEMENT ** 2]
    return sorted(candidates, key=lambda rc: ((rc[0] - cell[0]) ** 2 + (rc[1] - cell[1]) ** 2, rc))


def _components(cells: set[tuple[int, int]], rows: int, cols: int) -> int:
    seen: set[tuple[int, int]] = set()
    count = 0
    for start in cells:
        if start in seen:
            continue
        count += 1
        seen.add(start)
        queue = deque([start])
        while queue:
            cell = queue.popleft()
            for nxt in neighbours(cell[0], cell[1], rows, cols):
                if nxt in cells and nxt not in seen:
                    seen.add(nxt)
                    queue.append(nxt)
    return count


def _dry_codes(document: dict) -> frozenset[str]:
    legend = document["_meta"]["terrainLegend"]
    return frozenset(code for code, name in legend.items() if name in DRY_TERRAIN_NAMES)


def _shares_dry_border(carved: set[tuple[int, int]], remainder: set[tuple[int, int]], terrain: list[str],
                       dry: frozenset[str], rows: int, cols: int) -> bool:
    return any(terrain[cell[0]][cell[1]] in dry and nxt in remainder and terrain[nxt[0]][nxt[1]] in dry
               for cell in carved for nxt in neighbours(cell[0], cell[1], rows, cols))


def _carve(owner: np.ndarray, donor: int, anchor: tuple[int, int], protected: set[tuple[int, int]],
           footprint: int = FOOTPRINT, terrain: list[str] | None = None, dry: frozenset[str] = frozenset()):
    """앵커에서 거리 순으로 FOOTPRINT 칸. 기증 省의 조각 수를 늘리지 않고 최소 면적을 지킨다.

    기증 省이 원래 여러 조각이면(섬·월경지 — territory-disconnection 원장이 판정한 것) 그 조각 수
    그대로를 지키면 된다. 「한 덩어리」를 요구하면 이미 갈라진 省에서는 한 칸도 못 뗀다.
    """
    rows, cols = owner.shape
    donor_cells = {(int(r), int(c)) for r, c in np.argwhere(owner == donor)}
    if len(donor_cells) - footprint < MINIMUM_AREA:
        return None
    pieces = _components(donor_cells, rows, cols)
    carved = {anchor}
    remainder = donor_cells - carved
    if _components(remainder, rows, cols) > pieces:
        return None
    while len(carved) < footprint:
        frontier = sorted(
            {cell for taken in carved for cell in neighbours(taken[0], taken[1], rows, cols)
             if cell in remainder and cell not in protected},
            key=lambda cell: ((cell[0] - anchor[0]) ** 2 + (cell[1] - anchor[1]) ** 2, cell),
        )
        for candidate in frontier:
            if _components(remainder - {candidate}, rows, cols) <= pieces:
                carved.add(candidate)
                remainder.discard(candidate)
                break
        else:
            return None
    if terrain is not None and not _shares_dry_border(carved, remainder, terrain, dry, rows, cols):
        return None
    return carved


def apply_carves(source: dict, sites: list[dict]) -> tuple[dict, dict]:
    document = copy.deepcopy(source)
    meta = document["_meta"]
    rows, cols = meta["rows"], meta["cols"]
    projection = Proj(meta["projection"])
    owner = expand(document["owner"], rows, cols)
    before = owner.copy()
    provinces = document["provinceRecords"]
    jurisdictions = {row["id"]: row for row in document["jurisdictionRecords"]}
    commanderies = {row["id"]: row for row in document["commanderyRecords"]}
    point_cells = {(row["row"], row["col"]) for row in document["cities"]}
    terrain, dry = document["terrain"], _dry_codes(document)
    placements, excluded = [], []
    for site in sites:
        base = {"siteId": site["id"], "nameHan": site["nameHan"], "role": site["role"]}
        col, row = projection.to_cell(site["longitude"], site["latitude"])
        cell = (math.floor(row), math.floor(col))
        if not (0 <= cell[0] < rows and 0 <= cell[1] < cols):
            excluded.append({**base, "reason": "WATER_OR_OUT_OF_SCOPE", "anchorCell": {"col": cell[1], "row": cell[0]}})
            continue
        projected_cell = cell
        if int(before[cell]) < 0:
            land = [(int(r), int(c)) for r, c in np.argwhere(before >= 0)
                    if (int(r) - cell[0]) ** 2 + (int(c) - cell[1]) ** 2 <= MAXIMUM_DISPLACEMENT ** 2]
            if not land:
                excluded.append({**base, "reason": "WATER_OR_OUT_OF_SCOPE", "anchorCell": {"col": cell[1], "row": cell[0]}})
                continue
            cell = min(land, key=lambda rc: ((rc[0] - cell[0]) ** 2 + (rc[1] - cell[1]) ** 2, rc))
        # 기증 省은 원래 소유 격자로 정한다 — 같은 縣의 앞 거점이 이 칸을 이미 떼어 갔을 수 있다.
        donor = int(before[cell])
        donor_record = provinces[donor]
        jurisdiction = jurisdictions[donor_record["jurisdictionId"]]
        if jurisdiction["kind"] not in DONOR_JURISDICTION_KINDS:
            excluded.append({**base, "reason": "ANCHOR_OUTSIDE_COUNTY_JURISDICTION",
                             "donorProvinceId": donor_record["id"], "jurisdictionKind": jurisdiction["kind"]})
            continue
        anchor, carved = None, None
        for footprint in range(FOOTPRINT, MINIMUM_FOOTPRINT - 1, -1):
            for candidate in _anchor_candidates(owner, donor, cell, point_cells):
                carved = _carve(owner, donor, candidate, point_cells, footprint, terrain, dry)
                if carved is not None:
                    anchor = candidate
                    break
            if carved is not None:
                break
        if carved is None:
            excluded.append({**base, "reason": "DONOR_TOO_SMALL", "donorProvinceId": donor_record["id"],
                             "donorCellCount": int((owner == donor).sum())})
            continue
        place_id = f"{SITE_PREFIX}{site['id']}"
        city_index = len(document["cities"])
        province_index = len(provinces)
        document["cities"].append({
            "id": place_id, "name": site["nameKo"], "nameCh": site["nameHan"], "level": TILE_PLACE_LEVEL,
            "kind": SITE_KIND, "seat": False, "zhi": False, "col": anchor[1], "row": anchor[0],
            "lon": site["longitude"], "lat": site["latitude"],
        })
        provinces.append({
            "id": place_id, "displayName": site["nameKo"], "nameCh": site["nameHan"],
            "administrativeSystem": donor_record["administrativeSystem"], "kind": "SPATIAL_PROVINCE",
            "parentRegionId": donor_record["parentRegionId"], "cityIndex": city_index,
            "geometryBasis": "STRATEGIC_SITE_LOCAL_CARVE", "confidence": "APPROXIMATE",
            "jurisdictionId": place_id, "assignmentBasis": "STRATEGIC_SITE_ANCHOR",
            "assignmentConfidence": "APPROXIMATE",
        })
        new_jurisdiction = {
            "id": place_id, "displayName": site["nameKo"], "nameCh": site["nameHan"], "kind": SITE_KIND,
            "commanderyId": jurisdiction["commanderyId"], "seatPlaceId": place_id, "provinceIds": [place_id],
        }
        document["jurisdictionRecords"].append(new_jurisdiction)
        jurisdictions[place_id] = new_jurisdiction
        commanderies[jurisdiction["commanderyId"]]["jurisdictionIds"].append(place_id)
        displaced_reason = ("CITY_POINT_ON_PROJECTED_CELL" if cell in point_cells
                            else "CELL_TAKEN_BY_EARLIER_SITE" if int(owner[cell]) != donor
                            else "PROJECTED_CELL_WOULD_SPLIT_DONOR")
        for carved_cell in carved:
            owner[carved_cell] = province_index
        point_cells.add(anchor)
        placement = {**base, "cityLevel": ROLE_LEVEL[site["role"]], "placeId": place_id,
                     "provinceIndex": province_index, "donorProvinceId": donor_record["id"],
                     "donorJurisdictionId": jurisdiction["id"], "commanderyId": jurisdiction["commanderyId"],
                     "anchorCell": {"col": anchor[1], "row": anchor[0]}, "carvedCellCount": len(carved)}
        if projected_cell != cell:
            displaced_reason = "PROJECTED_CELL_IN_WATER"
        if anchor != projected_cell:
            cell = projected_cell
            placement["displacedFrom"] = {"col": cell[1], "row": cell[0], "reason": displaced_reason,
                                          "cellDistance": round(math.hypot(anchor[0] - cell[0], anchor[1] - cell[1]), 2)}
        placements.append(placement)
    document["owner"] = encode(owner)
    document["adjacency"]["county"] = adjacency(owner, min_shared_edges=1)
    _rederive_parent_surfaces(document)
    counts = document["_meta"]["counts"]
    counts["cities"] = len(document["cities"])
    counts["provinces"] = len(provinces)
    counts["jurisdictions"] = len(document["jurisdictionRecords"])
    counts["adjCounty"] = len(document["adjacency"]["county"])
    counts[SITE_KIND] = sum(1 for row in document["cities"] if row["kind"] == SITE_KIND)
    owner_delta = [{"col": int(position % cols), "row": int(position // cols),
                    "before": provinces[old]["id"], "after": provinces[new]["id"]}
                   for position, (old, new) in enumerate(zip(before.ravel().tolist(), owner.ravel().tolist()))
                   if old != new]
    return document, {"placements": placements, "excluded": excluded, "ownerDelta": owner_delta}


def _canonical_order(document: dict, stage: dict) -> dict:
    """cities[] 배열 순서만 뒤바뀐 문서를 이 단계가 낸 순서로 되돌린다(앞 단계 outputCityOrder 계약과 같다)."""
    from tools.map import relocate_han_province as relocation
    order = stage.get("outputCityOrder")
    if order and set(order) == {row["id"] for row in document.get("cities", [])}:
        return relocation.canonicalize_city_order(document, {"inputCityOrder": order})
    return document


def stage_for(document: dict, ledger: dict) -> dict | None:
    fingerprint = digest(document)
    for stage in ledger.get("geometry", {}).get("stages", []):
        if stage["outputDocumentSha256"] in (fingerprint, digest(_canonical_order(document, stage))):
            return stage
    return None


def restore_document(document: dict, ledger: dict) -> dict:
    """이 단계를 되돌려 입력 문서로. 덧붙인 행을 떼고 셀을 기증 省으로 돌린다."""
    stage = stage_for(document, ledger)
    if stage is None:
        raise ValueError("document is not a pinned strategic-site carve output")
    restored = copy.deepcopy(_canonical_order(document, stage))
    meta = restored["_meta"]
    added = {row["placeId"] for row in stage["placements"]}
    provinces = restored["provinceRecords"]
    index_by_id = {row["id"]: index for index, row in enumerate(provinces)}
    owner = expand(restored["owner"], meta["rows"], meta["cols"])
    for entry in stage["ownerDelta"]:
        if provinces[int(owner[entry["row"], entry["col"]])]["id"] != entry["after"]:
            raise ValueError("carved cell no longer belongs to its strategic-site province")
        owner[entry["row"], entry["col"]] = index_by_id[entry["before"]]
    for collection in ("cities", "provinceRecords", "jurisdictionRecords"):
        tail = restored[collection][-len(added):] if added else []
        if {row["id"] for row in tail} != added:
            raise ValueError(f"{collection} tail is not the carved strategic-site rows")
        restored[collection] = restored[collection][: len(restored[collection]) - len(added)]
    for commandery in restored["commanderyRecords"]:
        commandery["jurisdictionIds"] = [value for value in commandery["jurisdictionIds"] if value not in added]
    restored["owner"] = encode(owner)
    restored["adjacency"]["county"] = adjacency(owner, min_shared_edges=1)
    _rederive_parent_surfaces(restored)
    restored["_meta"]["counts"] = copy.deepcopy(stage["inputCounts"])
    if digest(restored) != stage["inputDocumentSha256"]:
        raise ValueError("restored document differs from the pinned strategic-site carve input")
    return restored


def peel(document: dict) -> tuple[dict, dict | None]:
    """이 단계가 얹혀 있으면 벗긴 문서와 원장을, 아니면 (문서, None) 을 준다."""
    if not LEDGER.is_file():
        return document, None
    ledger = json.loads(LEDGER.read_text(encoding="utf-8"))
    if stage_for(document, ledger) is None:
        return document, None
    return restore_document(document, ledger), ledger


def reapply(document: dict, ledger: dict) -> dict:
    """벗겨 낸 뒤 앞 단계 검사가 다시 세운 문서 위에 이 단계를 똑같이 얹는다."""
    rebuilt, _ = apply_carves(document, _ledger_sites(ledger))
    return rebuilt


def _ledger_sites(ledger: dict) -> list[dict]:
    return load_sites(json.loads(STRONGHOLDS.read_text(encoding="utf-8")),
                      json.loads(PASSES.read_text(encoding="utf-8")))


def build_stage(source: dict) -> tuple[dict, dict]:
    sites = load_sites(json.loads(STRONGHOLDS.read_text(encoding="utf-8")),
                       json.loads(PASSES.read_text(encoding="utf-8")))
    document, result = apply_carves(source, sites)
    stage = {
        "inputDocumentSha256": digest(source), "outputDocumentSha256": digest(document),
        "inputCounts": copy.deepcopy(source["_meta"]["counts"]),
        "outputCityOrder": [row["id"] for row in document["cities"]],
        "placements": result["placements"], "excluded": result["excluded"], "ownerDelta": result["ownerDelta"],
    }
    ledger = {
        "schemaVersion": 1,
        "ledgerId": "strategic-site-province-carves-v1",
        "authority": "ADR-LITE-052 비현 거점 + user-approval-2026-09-15 「縣 프로빈스를 쪼개 거점 省을 준다」",
        "inputs": {"strongholds": {"path": "data/curated/han/strategic-strongholds-v1.json", "sha256": _sha256(STRONGHOLDS)},
                   "passes": {"path": "data/curated/han/strategic-passes-v1.json", "sha256": _sha256(PASSES)}},
        "rule": {"footprintCells": FOOTPRINT, "minimumFootprintCells": MINIMUM_FOOTPRINT, "minimumDonorArea": MINIMUM_AREA,
                 "donorBorderTerrain": sorted(DRY_TERRAIN_NAMES),
                 "roleLevels": ROLE_LEVEL, "placeIdPrefix": SITE_PREFIX,
                 "boundaryBasis": "LOCAL_ADAPTED_PARTITION_NOT_HISTORICAL_BOUNDARY"},
        "geometry": {"stages": [stage]},
    }
    return document, ledger


def check(document: dict, ledger: dict) -> list[str]:
    stage = stage_for(document, ledger)
    if stage is None:
        return ["han-tiles.json is not the reviewed strategic-site carve output"]
    problems = []
    for name, path in (("strongholds", STRONGHOLDS), ("passes", PASSES)):
        if ledger["inputs"][name]["sha256"] != _sha256(path):
            problems.append(f"{ledger['inputs'][name]['path']} changed since the carve was prepared")
    rebuilt, result = apply_carves(restore_document(_canonical_order(document, stage), ledger), _ledger_sites(ledger))
    for key in ("placements", "excluded", "ownerDelta"):
        if result[key] != stage[key]:
            problems.append(f"strategic-site carve {key} differs from the reviewed stage")
    if digest(rebuilt) != stage["outputDocumentSha256"]:
        problems.append("re-applied strategic-site carve does not reproduce han-tiles.json")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--source", type=Path, default=TILES)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--prepare", action="store_true")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    source = json.loads(args.source.read_text(encoding="utf-8"))
    if args.check:
        problems = check(source, json.loads(LEDGER.read_text(encoding="utf-8")))
        for problem in problems:
            print(problem, file=sys.stderr)
        return 1 if problems else 0
    if LEDGER.is_file():
        source, _ = peel(source)
    document, ledger = build_stage(source)
    if args.prepare:
        LEDGER.write_text(json.dumps(ledger, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if args.output:
        args.output.write_text(json.dumps(document, ensure_ascii=False, separators=(",", ":")) + "\n",
                               encoding="utf-8")
    stage = ledger["geometry"]["stages"][0]
    print(f"carved {len(stage['placements'])} strategic-site provinces · excluded {len(stage['excluded'])} · "
          f"cells moved {len(stage['ownerDelta'])}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
