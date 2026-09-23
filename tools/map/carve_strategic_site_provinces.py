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
     MINIMUM_FOOTPRINT 칸까지 줄여 다시 시도한다(carvedCellCount 에 남는다). 그래도 안 되면 앵커를 포함한 연결
     발자국을 전수로 보며 1칸까지 줄인다(사용자 결정 2026-09-18 「빼지 말고 발자국을 줄여 세운다」 — 襄陽縣의
     樊城). 같은 기증 省에 뒤에 설 거점 몫(MINIMUM_FOOTPRINT × 수)은 남겨 둔다. 끝내 못 하면 DONOR_TOO_SMALL.
     떼어 낸 발자국은 남는 기증 省과 마른땅 경계(DRY_TERRAIN 칸끼리 맞닿은 변)를 하나 이상 공유해야 한다 —
     런타임 보급망은 마른땅 경계만 잇는다(projectHanDryLandEdges). 강 칸으로만 붙으면 거점이 제 縣과 같은
     주인이어도 끊긴다. 이 조건 때문에 孟津(5칸)·樊城(7칸)은 최소 면적 아래로 선다.
  6. 새 省·관할·城 점은 배열 **끝에** 붙인다 — 기존 省 인덱스(게임 provinceId)는 한 칸도 밀리지 않는다.

이 단계는 오배정 縣 재바인딩보다 나중이다. 앞 단계 검사들은 `peel()` 로 이 단계를 벗긴 문서를
본다. 원장의 `geometry.stages` 가 셀 델타와 덧붙인 행을 핀으로 박아 되돌리기를 바이트 단위로 보장한다.

    python3 tools/map/carve_strategic_site_provinces.py --prepare --output data/map/han-tiles.json
    python3 tools/map/carve_strategic_site_provinces.py --check
"""
from __future__ import annotations

import argparse
import copy
import functools
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
# 결손 縣 원장. **이 단계가 縣도 함께 잘라낸다** — 새 사슬 단계를 만들지 않는 이유가 있다.
# 사슬에 층을 하나 끼우면 그 아래 모든 단계의 peel 이 그 층을 몰라 지문이 깨진다(거점 분할이
# 추가될 때 변경 縣 도구가 `carving.peel` 을 배운 것이 그 증거다). 이 단계는 이미 비파괴 국소
# carve 이고(기존 省을 다시 자르지 않는다) 새 행을 배열 끝에 붙여 省 인덱스를 밀지 않으므로,
# 縣을 여기에 태우면 사슬 모양이 그대로다. 앞서 변경 縣 단계에서 33 郡을 재구획했더니
# 거점이 73→12 로 떨어지고 접기가 죽었다 — 재구획은 위 단계의 기하를 깨뜨린다.
GAP_COUNTIES = ROOT / "data/curated/han/gap-counties-v1.json"

SITE_PREFIX = "ss-"
GAP_PREFIX = "gc-"
GAP_KIND = "COUNTY"
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


def load_gap_counties(ledger: dict) -> list[dict]:
    """결손 縣을 거점과 같은 항목 모양으로 눕힌다. 원장 순서는 append-only 省 발급 순서다."""
    counties = []
    for row in ledger["counties"]:
        counties.append({
            "id": f"{row['placeSlug']}-{row['id'].rsplit(':', 1)[-1]}",
            "nameKo": row.get("gameNameKo", row["nameKo"]),
            "nameHan": f"{row.get('gameNameHan', row['nameHan'])}{row['countySuffix']}",
            "role": None,
            "canonicalId": row["id"],
            "hhsCommanderyHan": row["commanderyHan"],
            "latitude": row["coordinates"]["latitude"],
            "longitude": row["coordinates"]["longitude"],
            "positionStatus": row["positionStatus"],
            "coordinateBasis": row["coordinateBasis"],
        })
    ids = [row["id"] for row in counties]
    if len(ids) != len(set(ids)):
        raise ValueError("gap county place ids must be unique")
    return counties


MAXIMUM_DISPLACEMENT = 6  # 앵커를 옮겨도 되는 최대 칸 거리(유클리드). 넘으면 세우지 않는다.
# 결손 縣의 郡 귀속은 郡國志 표제(1차 증거)이고 좌표는 APPROXIMATE 다. 지도의 郡 기하는 NE
# 폴리곤에서 온 것이라 경계가 거칠어(鄴·安邑은 10–17칸 밀려 있다) 경계 縣의 투영 칸이 옆 郡에
# 떨어진다 — 그대로 세우면 弘農郡에 河南尹 新城이 붙어 기존 新成과 한글 표기까지 충돌했다.
# 그럴 때 자기 郡 땅으로 앵커를 당긴다. 실측 필요 거리는 7건 중 최대 6.71칸(河南尹 梁縣)이다.
MAXIMUM_COMMANDERY_SNAP = 8


@functools.lru_cache(maxsize=1)
def _commandery_normalizer():
    """繁簡·異體字 접기. 자체 규칙을 쓰면 邑·道·國 접미를 잘라 허위 불일치가 난다 — audit 도구를 쓴다."""
    from tools.map.audit_county_coverage import make_normalizer
    return make_normalizer(group=True)


def _commandery_fold(name: str) -> str:
    return _commandery_normalizer()(name)


def _commandery_parent_ids(document: dict) -> dict[str, list[str]]:
    """郡 이름(繁簡·異體 접기) → parentRegion id 들. 원장은 繁體, 지도는 簡體가 섞여 있다."""
    index: dict[str, list[str]] = {}
    for row in document["parentRegions"]:
        index.setdefault(_commandery_fold(row["nameCh"]), []).append(row["id"])
    return index


def _nearest_cell_in_commandery(before: np.ndarray, provinces: list[dict], parent_ids: list[str],
                                cell: tuple[int, int], rows: int, cols: int):
    """cell 에서 가장 가까운, parent_ids 郡에 속한 칸. MAXIMUM_COMMANDERY_SNAP 안에서만 본다."""
    wanted = {index for index, row in enumerate(provinces) if row["parentRegionId"] in parent_ids}
    if not wanted:
        return None
    reach = MAXIMUM_COMMANDERY_SNAP
    window = before[max(cell[0] - reach, 0):cell[0] + reach + 1,
                    max(cell[1] - reach, 0):cell[1] + reach + 1]
    origin = (max(cell[0] - reach, 0), max(cell[1] - reach, 0))
    best = None
    for r, c in np.argwhere(np.isin(window, list(wanted))):
        candidate = (origin[0] + int(r), origin[1] + int(c))
        distance = (candidate[0] - cell[0]) ** 2 + (candidate[1] - cell[1]) ** 2
        if distance <= reach ** 2 and (best is None or (distance, candidate) < best[0]):
            best = ((distance, candidate), candidate)
    return None if best is None else best[1]


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
           footprint: int = FOOTPRINT, terrain: list[str] | None = None, dry: frozenset[str] = frozenset(),
           reserve: int = 0):
    """앵커에서 거리 순으로 FOOTPRINT 칸. 기증 省의 조각 수를 늘리지 않고 최소 면적을 지킨다.

    기증 省이 원래 여러 조각이면(섬·월경지 — territory-disconnection 원장이 판정한 것) 그 조각 수
    그대로를 지키면 된다. 「한 덩어리」를 요구하면 이미 갈라진 省에서는 한 칸도 못 뗀다.
    """
    rows, cols = owner.shape
    donor_cells = {(int(r), int(c)) for r, c in np.argwhere(owner == donor)}
    # reserve = 같은 기증 省에서 아직 설 거점들의 최소 발자국 합. 앞 거점이 8칸을 다 가져가 뒤 거점이
    # DONOR_TOO_SMALL 로 빠지는 것을 막는다(사용자 결정 2026-09-18: 빼지 말고 발자국을 줄여 세운다).
    if len(donor_cells) - footprint < MINIMUM_AREA + reserve:
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


def _carve_exhaustive(owner: np.ndarray, donor: int, anchor: tuple[int, int], protected: set[tuple[int, int]],
                      footprint: int, terrain: list[str], dry: frozenset[str], reserve: int):
    """_carve 는 한 칸씩 자라며 매 걸음 기증 省이 안 갈라지기를 요구한다. 뱀처럼 가는 기증 省에서는 끝 덩어리를
    통째로 떼면 되는데도 중간 걸음이 갈라져 실패한다(襄陽縣 — 樊城). 최소 발자국에서만, 앵커를 포함한 연결
    발자국을 전수로 보고 (앵커 거리 제곱합, 칸 목록) 최소를 고른다. 조건은 _carve 와 같다."""
    rows, cols = owner.shape
    donor_cells = {(int(r), int(c)) for r, c in np.argwhere(owner == donor)}
    if len(donor_cells) - footprint < MINIMUM_AREA + reserve or anchor in protected:
        return None
    pieces = _components(donor_cells, rows, cols)
    shapes = {frozenset({anchor})}
    for _ in range(footprint - 1):
        shapes = {shape | {cell} for shape in shapes for taken in shape
                  for cell in neighbours(taken[0], taken[1], rows, cols)
                  if cell in donor_cells and cell not in protected and cell not in shape}
    best = None
    for shape in shapes:
        remainder = donor_cells - shape
        if _components(remainder, rows, cols) > pieces:
            continue
        if not _shares_dry_border(set(shape), remainder, terrain, dry, rows, cols):
            continue
        key = (sum((r - anchor[0]) ** 2 + (c - anchor[1]) ** 2 for r, c in shape), sorted(shape))
        if best is None or key < best[0]:
            best = (key, set(shape))
    return best[1] if best else None


def _site_cell(before: np.ndarray, site: dict, projection, rows: int, cols: int):
    """(투영 칸, 앵커로 삼을 육지 칸 | None). 규칙 1·2."""
    col, row = projection.to_cell(site["longitude"], site["latitude"])
    cell = (math.floor(row), math.floor(col))
    if not (0 <= cell[0] < rows and 0 <= cell[1] < cols):
        return cell, None
    if int(before[cell]) >= 0:
        return cell, cell
    land = [(int(r), int(c)) for r, c in np.argwhere(before >= 0)
            if (int(r) - cell[0]) ** 2 + (int(c) - cell[1]) ** 2 <= MAXIMUM_DISPLACEMENT ** 2]
    if not land:
        return cell, None
    return cell, min(land, key=lambda rc: ((rc[0] - cell[0]) ** 2 + (rc[1] - cell[1]) ** 2, rc))


def _pending_by_donor(before: np.ndarray, sites: list[dict], projection, rows: int, cols: int) -> dict[int, list[str]]:
    pending: dict[int, list[str]] = {}
    for site in sites:
        _, cell = _site_cell(before, site, projection, rows, cols)
        if cell is not None:
            pending.setdefault(int(before[cell]), []).append(site["id"])
    return pending


def apply_carves(source: dict, sites: list[dict], counties: list[dict] | None = None) -> tuple[dict, dict]:
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
    pending = _pending_by_donor(before, sites, projection, rows, cols)
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
        pending[donor].remove(site["id"])
        reserve = MINIMUM_FOOTPRINT * len(pending[donor])
        for footprint in range(FOOTPRINT, MINIMUM_FOOTPRINT - 1, -1):
            for candidate in _anchor_candidates(owner, donor, cell, point_cells):
                carved = _carve(owner, donor, candidate, point_cells, footprint, terrain, dry, reserve)
                if carved is not None:
                    anchor = candidate
                    break
            if carved is not None:
                break
        if carved is None:
            # 사용자 결정(2026-09-18, GH #806): 기증 縣이 작아 못 서는 거점은 빼지 않고 발자국을 더 줄여 세운다.
            # 기증 省의 최소 넓이·연결·마른땅 경계 조건은 그대로다. 줄어든 크기는 carvedCellCount 에 남는다.
            for footprint in range(MINIMUM_FOOTPRINT, 0, -1):
                for candidate in _anchor_candidates(owner, donor, cell, point_cells):
                    carved = _carve_exhaustive(owner, donor, candidate, point_cells, footprint, terrain, dry, reserve)
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
    # ── 결손 縣. 거점 고리 뒤에 따로 돈다 — 거점의 `pending` 예약을 縣이 늘리면 기존 73 건의
    # carve 결과가 달라진다(회귀). 기증 省의 최소 넓이·연결·마른땅 경계 조건은 같은 함수가 지킨다.
    county_placements, county_excluded = [], []
    commandery_parents = _commandery_parent_ids(document) if counties else {}
    for county in counties or ():
        base = {"countyId": county["canonicalId"], "nameHan": county["nameHan"],
                "hhsCommanderyHan": county["hhsCommanderyHan"]}
        col, row = projection.to_cell(county["longitude"], county["latitude"])
        cell = (math.floor(row), math.floor(col))
        if not (0 <= cell[0] < rows and 0 <= cell[1] < cols):
            county_excluded.append({**base, "reason": "WATER_OR_OUT_OF_SCOPE",
                                    "anchorCell": {"col": cell[1], "row": cell[0]}})
            continue
        projected_cell = cell
        if int(before[cell]) < 0:
            land = [(int(r), int(c)) for r, c in np.argwhere(before >= 0)
                    if (int(r) - cell[0]) ** 2 + (int(c) - cell[1]) ** 2 <= MAXIMUM_DISPLACEMENT ** 2]
            if not land:
                county_excluded.append({**base, "reason": "WATER_OR_OUT_OF_SCOPE",
                                        "anchorCell": {"col": cell[1], "row": cell[0]}})
                continue
            cell = min(land, key=lambda rc: ((rc[0] - cell[0]) ** 2 + (rc[1] - cell[1]) ** 2, rc))
        # 郡 귀속은 사료가 말하는 것이고 郡 경계 기하는 근사다. 투영 칸이 옆 郡에 떨어지면
        # 칸을 옮기고(자기 郡 안의 최근접), 자기 郡에 닿지 못하면 세우지 않는다.
        target_parents = commandery_parents.get(_commandery_fold(county["hhsCommanderyHan"]))
        if not target_parents:
            county_excluded.append({**base, "reason": "LEDGER_COMMANDERY_NOT_ON_MAP"})
            continue
        commandery_snapped_from = None
        if provinces[int(before[cell])]["parentRegionId"] not in target_parents:
            inside = _nearest_cell_in_commandery(before, provinces, target_parents, cell, rows, cols)
            if inside is None:
                county_excluded.append({
                    **base, "reason": "ANCHOR_OUTSIDE_LEDGER_COMMANDERY",
                    "anchorCell": {"col": cell[1], "row": cell[0]},
                    "worldCommanderyHan": next(
                        r["nameCh"] for r in document["parentRegions"]
                        if r["id"] == provinces[int(before[cell])]["parentRegionId"])})
                continue
            commandery_snapped_from, cell = cell, inside
        donor = int(before[cell])
        donor_record = provinces[donor]
        jurisdiction = jurisdictions[donor_record["jurisdictionId"]]
        if jurisdiction["kind"] not in DONOR_JURISDICTION_KINDS:
            county_excluded.append({**base, "reason": "ANCHOR_OUTSIDE_COUNTY_JURISDICTION",
                                    "donorProvinceId": donor_record["id"],
                                    "jurisdictionKind": jurisdiction["kind"]})
            continue
        anchor, carved = None, None
        for footprint in range(FOOTPRINT, MINIMUM_FOOTPRINT - 1, -1):
            for candidate in _anchor_candidates(owner, donor, cell, point_cells):
                carved = _carve(owner, donor, candidate, point_cells, footprint, terrain, dry, 0)
                if carved is not None:
                    anchor = candidate
                    break
            if carved is not None:
                break
        if carved is None:
            for footprint in range(MINIMUM_FOOTPRINT, 0, -1):
                for candidate in _anchor_candidates(owner, donor, cell, point_cells):
                    carved = _carve_exhaustive(owner, donor, candidate, point_cells, footprint, terrain, dry, 0)
                    if carved is not None:
                        anchor = candidate
                        break
                if carved is not None:
                    break
        if carved is None:
            county_excluded.append({**base, "reason": "DONOR_TOO_SMALL", "donorProvinceId": donor_record["id"],
                                    "donorCellCount": int((owner == donor).sum())})
            continue
        place_id = f"{GAP_PREFIX}{county['id']}"
        city_index = len(document["cities"])
        province_index = len(provinces)
        document["cities"].append({
            "id": place_id, "name": f"{county['nameKo']}현", "nameCh": county["nameHan"],
            "level": TILE_PLACE_LEVEL, "kind": GAP_KIND, "seat": False, "zhi": False,
            "col": anchor[1], "row": anchor[0],
            "lon": county["longitude"], "lat": county["latitude"],
            "locationBasis": county["coordinateBasis"],
        })
        provinces.append({
            "id": place_id, "displayName": f"{county['nameKo']}현", "nameCh": county["nameHan"],
            "administrativeSystem": donor_record["administrativeSystem"], "kind": "SPATIAL_PROVINCE",
            "parentRegionId": donor_record["parentRegionId"], "cityIndex": city_index,
            "geometryBasis": "GAP_COUNTY_LOCAL_CARVE", "confidence": county["positionStatus"],
            "jurisdictionId": place_id, "assignmentBasis": "GAP_COUNTY_ANCHOR",
            "assignmentConfidence": county["positionStatus"],
        })
        new_jurisdiction = {
            "id": place_id, "displayName": f"{county['nameKo']}현", "nameCh": county["nameHan"],
            "kind": GAP_KIND, "commanderyId": jurisdiction["commanderyId"],
            "seatPlaceId": place_id, "provinceIds": [place_id],
        }
        document["jurisdictionRecords"].append(new_jurisdiction)
        jurisdictions[place_id] = new_jurisdiction
        commanderies[jurisdiction["commanderyId"]]["jurisdictionIds"].append(place_id)
        for carved_cell in carved:
            owner[carved_cell] = province_index
        point_cells.add(anchor)
        placement = {**base, "placeId": place_id, "provinceIndex": province_index,
                     "donorProvinceId": donor_record["id"], "donorJurisdictionId": jurisdiction["id"],
                     "commanderyId": jurisdiction["commanderyId"],
                     "worldCommanderyHan": next(
                         r["nameCh"] for r in document["parentRegions"]
                         if r["id"] == jurisdiction["commanderyId"]),
                     "anchorCell": {"col": anchor[1], "row": anchor[0]}, "carvedCellCount": len(carved)}
        if anchor != projected_cell:
            placement["displacedFrom"] = {
                "col": projected_cell[1], "row": projected_cell[0],
                "reason": ("PROJECTED_CELL_IN_OTHER_COMMANDERY" if commandery_snapped_from is not None
                           else "PROJECTED_CELL_IN_WATER" if projected_cell != cell
                           else "CITY_POINT_ON_PROJECTED_CELL" if projected_cell in point_cells
                           else "PROJECTED_CELL_WOULD_SPLIT_DONOR"),
                "cellDistance": round(math.hypot(anchor[0] - projected_cell[0],
                                                 anchor[1] - projected_cell[1]), 2)}
        county_placements.append(placement)

    document["owner"] = encode(owner)
    document["adjacency"]["county"] = adjacency(owner, min_shared_edges=1)
    _rederive_parent_surfaces(document)
    counts = document["_meta"]["counts"]
    counts["cities"] = len(document["cities"])
    counts["provinces"] = len(provinces)
    counts["jurisdictions"] = len(document["jurisdictionRecords"])
    counts["adjCounty"] = len(document["adjacency"]["county"])
    counts[SITE_KIND] = sum(1 for row in document["cities"] if row["kind"] == SITE_KIND)
    counts[GAP_KIND] = sum(1 for row in document["cities"] if row["kind"] == GAP_KIND)
    owner_delta = [{"col": int(position % cols), "row": int(position // cols),
                    "before": provinces[old]["id"], "after": provinces[new]["id"]}
                   for position, (old, new) in enumerate(zip(before.ravel().tolist(), owner.ravel().tolist()))
                   if old != new]
    return document, {"placements": placements, "excluded": excluded, "ownerDelta": owner_delta,
                      "gapCountyPlacements": county_placements, "gapCountyExcluded": county_excluded}


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
    # 거점 행과 결손 縣 행을 함께 걷는다 — 둘 다 이 단계가 배열 끝에 붙인 것이다(거점 뒤에 縣).
    added = {row["placeId"] for row in stage["placements"]}
    added |= {row["placeId"] for row in stage.get("gapCountyPlacements", ())}
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


PARTITION_KEY = "_countyLocationPartitionLedger"


def peel_only(document: dict) -> tuple[dict, dict | None]:
    """이 단계만 벗긴다 — ★ 지리 재분할(이 단계의 입력)은 그대로 둔다. ★ 자신과 영토 단절 감사가 쓴다."""
    if not LEDGER.is_file():
        return document, None
    ledger = json.loads(LEDGER.read_text(encoding="utf-8"))
    if stage_for(document, ledger) is None:
        return document, None
    return restore_document(document, ledger), ledger


def peel(document: dict) -> tuple[dict, dict | None]:
    """이 단계가 얹혀 있으면 벗긴 문서와 원장을, 아니면 (문서, None) 을 준다.

    ★ 지리 재분할(partition_counties_by_location, GH #806)은 변경 51縣 과 이 단계 사이에 끼어 있다. 앞 단계
    검사들은 모두 이 함수를 거치므로 여기서 ★ 도 함께 벗기고, 벗긴 원장을 돌려주는 원장에 실어 reapply() 가
    ★ → 거점 순으로 다시 얹게 한다(접기 단계가 저지 지형 단계를 싣는 것과 같은 방식).
    """
    from tools.map import partition_counties_by_location as partition
    peeled, ledger = peel_only(document)
    if ledger is None:
        return document, None
    restored, partition_ledger = partition.peel(peeled)
    if partition_ledger is not None:
        ledger[PARTITION_KEY] = partition_ledger
    return restored, ledger


def reapply(document: dict, ledger: dict) -> dict:
    """벗겨 낸 뒤 앞 단계 검사가 다시 세운 문서 위에 (★ 와) 이 단계를 똑같이 얹는다."""
    if ledger.get(PARTITION_KEY) is not None:
        from tools.map import partition_counties_by_location as partition
        document = partition.reapply(document, ledger[PARTITION_KEY])
    # 결손 縣도 같이 얹는다 — 빼면 앞 단계(변경 縣·★) 검사의 왕복이 커밋본을 재현하지 못한다.
    rebuilt, _ = apply_carves(document, _ledger_sites(ledger), _ledger_gap_counties())
    return rebuilt


def _ledger_sites(ledger: dict) -> list[dict]:
    return load_sites(json.loads(STRONGHOLDS.read_text(encoding="utf-8")),
                      json.loads(PASSES.read_text(encoding="utf-8")))


def _ledger_gap_counties() -> list[dict]:
    if not GAP_COUNTIES.is_file():
        return []
    return load_gap_counties(json.loads(GAP_COUNTIES.read_text(encoding="utf-8")))


def build_stage(source: dict) -> tuple[dict, dict]:
    sites = load_sites(json.loads(STRONGHOLDS.read_text(encoding="utf-8")),
                       json.loads(PASSES.read_text(encoding="utf-8")))
    counties = _ledger_gap_counties()
    document, result = apply_carves(source, sites, counties)
    stage = {
        "inputDocumentSha256": digest(source), "outputDocumentSha256": digest(document),
        "inputCounts": copy.deepcopy(source["_meta"]["counts"]),
        "outputCityOrder": [row["id"] for row in document["cities"]],
        "placements": result["placements"], "excluded": result["excluded"], "ownerDelta": result["ownerDelta"],
        "gapCountyPlacements": result["gapCountyPlacements"], "gapCountyExcluded": result["gapCountyExcluded"],
    }
    ledger = {
        "schemaVersion": 1,
        "ledgerId": "strategic-site-province-carves-v1",
        "authority": "ADR-LITE-052 비현 거점 + user-approval-2026-09-15 「縣 프로빈스를 쪼개 거점 省을 준다」",
        "inputs": {"strongholds": {"path": "data/curated/han/strategic-strongholds-v1.json", "sha256": _sha256(STRONGHOLDS)},
                   "passes": {"path": "data/curated/han/strategic-passes-v1.json", "sha256": _sha256(PASSES)},
                   **({"gapCounties": {"path": "data/curated/han/gap-counties-v1.json",
                                       "sha256": _sha256(GAP_COUNTIES)}} if GAP_COUNTIES.is_file() else {})},
        "rule": {"footprintCells": FOOTPRINT, "minimumFootprintCells": MINIMUM_FOOTPRINT, "minimumDonorArea": MINIMUM_AREA,
                 "laterSiteReserve": "minimumFootprintCells × 같은 기증 省에 아직 설 거점 수 (user-decision-2026-09-18)",
                 "donorBorderTerrain": sorted(DRY_TERRAIN_NAMES),
                 "roleLevels": ROLE_LEVEL, "placeIdPrefix": SITE_PREFIX,
                 "gapCountyPlaceIdPrefix": GAP_PREFIX,
                 "gapCountyReserve": ("0 — 縣 고리는 거점 고리 뒤에 따로 돌며 예약을 쓰지 않는다. "
                                      "거점의 예약에 縣을 더하면 기존 73 건의 carve 결과가 달라진다(회귀)."),
                 "gapCountyBoundaryBasis": "LOCAL_ADAPTED_PARTITION_NOT_HISTORICAL_BOUNDARY — 복구한 縣은 "
                                           "이웃 縣 省에서 최소 발자국만 떼어 선다. 郡 안을 다시 자르지 않는다.",
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
    if "gapCounties" in ledger["inputs"] and ledger["inputs"]["gapCounties"]["sha256"] != _sha256(GAP_COUNTIES):
        problems.append("data/curated/han/gap-counties-v1.json changed since the carve was prepared")
    rebuilt, result = apply_carves(restore_document(_canonical_order(document, stage), ledger),
                                  _ledger_sites(ledger), _ledger_gap_counties())
    for key in ("placements", "excluded", "ownerDelta", "gapCountyPlacements", "gapCountyExcluded"):
        # 결손 縣 키는 이 단계가 縣을 태우기 전에 구운 원장에는 없다 — 그때는 빈 목록과 같다.
        if result[key] != stage.get(key, []):
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
    # 城 없는 관할 접기(fold_cityless_jurisdictions)는 이 단계보다 나중이다. 먼저 벗기고 끝에 다시 얹는다.
    from tools.map import fold_cityless_jurisdictions as folding
    source, folded = folding.peel(source)
    if args.check:
        problems = check(source, json.loads(LEDGER.read_text(encoding="utf-8")))
        for problem in problems:
            print(problem, file=sys.stderr)
        return 1 if problems else 0
    if LEDGER.is_file():
        source, _ = peel_only(source)
    document, ledger = build_stage(source)
    if args.prepare:
        LEDGER.write_text(json.dumps(ledger, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if folded is not None:
        document = folding.reapply(document, folded)
    if args.output:
        args.output.write_text(json.dumps(document, ensure_ascii=False, separators=(",", ":")) + "\n",
                               encoding="utf-8")
    stage = ledger["geometry"]["stages"][0]
    print(f"carved {len(stage['placements'])} strategic-site provinces · excluded {len(stage['excluded'])} · "
          f"cells moved {len(stage['ownerDelta'])}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
