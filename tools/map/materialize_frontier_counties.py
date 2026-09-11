#!/usr/bin/env python3
"""변경 縣 원장을 han-tiles 의 실제 城·省·縣 관할로 세운다.

`data/curated/han/frontier-counties-v1.json` 은 樂浪·遼東·玄菟·遼東屬國·交趾·九真·日南 의
郡國志 屬縣 51 곳을 사료 인용과 좌표(나무위키 수확본, 전부 APPROXIMATE)로 적어 둔 검토 원장이다.
PR #698 은 이것을 **클라이언트 표식**(음수 id)으로만 그렸다 — 게임 세계에는 없는 城이었다.
이 도구는 그 원장을 지도 정본에 올린다:

  1. 縣마다 `cities[]` 행(물리 지점, id `fc-<郡 slug>-<郡國志 순번>`) 을 덧붙인다. 좌표는
     關·전장 표식과 같은 투영(`_meta.projection`)으로 lat/lon → col/row 를 얻는다.
  2. 그 칸의 `parentOwner` 가 후한 郡(HAN_COMMANDERY)이면 **그 郡** 이 세계 소속이다
     (`PARENT_OWNER_AT_PROJECTED_CELL`). 지도는 220 년 경계라 郡國志(140 년경)와 다를 수 있다 —
     樂浪 남부 6 縣(含資·列口·長岑·提奚·海冥·昭明)은 帶方郡 땅에 떨어지고, 이는 建安 연간
     公孫康의 帶方 분치와 맞는다. 郡國志 소속은 縣 id(`hhs:113:樂浪郡:004`)에 그대로 남는다.
  3. 칸이 물·비플레이 영역이거나 郡國 밖 세력(卒本 등)의 땅이면 郡國志 소속 郡의 땅에서
     가장 가까운 육지 칸으로 옮긴다(`SNAPPED_INTO_HHS_COMMANDERY`). 좌표가 없는 縣은 세우지 않는다.
  4. 영향받는 郡(7 郡 + 帶方郡)의 省 구획을 다시 나눈다 — 郡治 城 과 縣 城 을 고정 앵커로 둔
     `balanced_parent_labels` 다. 郡治도 이제 자기 省을 얻는다(그동안 변경 7 郡治는 省이 없어
     경로 그래프에서 고립돼 있었다 — han-world-v3 720·730·736·745 connections=[]).
     구획 수는 앵커 수(郡治 + 縣)와 같다 — 城 없는 DIRECT 잔여 구획은 만들지 않는다(잔여 구획은
     郡治 관할을 본체와 떼어 놓았다). 어느 앵커에도 육로로 닿지 않는 섬 조각은 종전에 그 섬을
     들고 있던 DIRECT 省을 id 그대로 섬 크기로 남긴다 — territory-disconnection 원장의 섬 판정
     행(交趾郡 Vân Đồn 2·4셀, 帶方郡 7셀)이 같은 省 id·셀 수로 계속 맞는다.
  5. 縣마다 관할(jurisdictionRecords) 한 행, 郡의 jurisdictionIds, owner 격자, 인접 요약,
     `_meta.counts` 를 다시 쓴다. 지형·parentOwner·seatOwner·juns 는 손대지 않는다.

배치 결과는 `data/curated/han/frontier-county-placements-v1.json` 에 縣별로 적는다(어느 칸에,
어느 郡에, 무슨 근거로). 원장의 `priorStage` 는 이 단계의 복원 정보다 — 입력 문서 지문
(province-relocations-v1 의 outputDocumentSha256 과 같은 digest), 영향 郡 칸의 종전 owner 省 id
런, 종전 省·관할 행, 종전 counts. `restore_document` 가 이것으로 세우기 전 문서를 바이트 단위로
되돌리므로 뒤따르는 검사(territory audit·fragment adjudication·parent reconciliation)가 relocation
단계까지 같은 방식으로 거슬러 올라간다. 이 도구는 멱등이다 — 이미 세운 문서는 먼저 복원한 뒤
다시 세워 같은 문서가 나오는지 본다. `--check` 는 추적 중인 han-tiles 와 배치 원장이 그렇게
다시 만든 것과 같은지 본다.

    /usr/local/bin/python3 tools/map/materialize_frontier_counties.py            # 쓰기
    /usr/local/bin/python3 tools/map/materialize_frontier_counties.py --check    # CI

경로 노드(route-node) 쪽 등록은 별도 원장(`route-node-frontier-county-*`)이 맡는다 — 여기서
만든 물리 지점 id 가 그 원장의 physicalPlaceId(`curated:frontier-county-v1:fc-…`) 꼬리다.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))

from build_terrain_grid import derive_world_adjacency  # noqa: E402
from province_quality import balanced_parent_labels, repair_label_connectivity  # noqa: E402
from rebalance_han_tiles import encode_rle, expand_rle, jun_seat_coordinates  # noqa: E402
from world_province_geometry import _direct_id, validate_materialized_hierarchy  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
TILES = ROOT / "data" / "map" / "han-tiles.json"
LEDGER = ROOT / "data" / "curated" / "han" / "frontier-counties-v1.json"
PLACEMENTS = ROOT / "data" / "curated" / "han" / "frontier-county-placements-v1.json"

PLACE_ID_PREFIX = "fc-"
PHYSICAL_PLACE_NAMESPACE = "curated:frontier-county-v1"
COMMANDERY_SLUGS = {
    "遼東郡": "liaodong",
    "玄菟郡": "xuantu",
    "樂浪郡": "lelang",
    "遼東屬國": "liaodong-shuguo",
    "交趾郡": "jiaozhi",
    "九真郡": "jiuzhen",
    "日南郡": "rinan",
}
# han-tiles COUNTY 행의 level 은 CHGIS 계층값이고 縣은 5 다(956/961). 게임 등급은 build_han_world 가 정한다.
COUNTY_TILE_LEVEL = 5
WATER_OR_OUT_OF_SCOPE = {0, 3, 4, 9}  # SEA, RIVER, LAKE, OUT_OF_SCOPE
MINIMUM_AREA = 8  # rebalance_han_tiles 와 같은 최소 省 면적

BASIS_AT_CELL = "PARENT_OWNER_AT_PROJECTED_CELL"
BASIS_SNAPPED = "SNAPPED_INTO_HHS_COMMANDERY"


class FrontierMaterializationError(ValueError):
    pass


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def place_id(county: dict) -> str:
    slug = COMMANDERY_SLUGS[county["commanderyHan"]]
    ordinal = county["id"].rsplit(":", 1)[-1]
    return f"{PLACE_ID_PREFIX}{slug}-{ordinal}"


def physical_place_ref(county: dict) -> str:
    return f"{PHYSICAL_PLACE_NAMESPACE}:{place_id(county)}"


def project_cell(projection: dict, latitude: float, longitude: float) -> tuple[float, float]:
    """web/shared/src/HanMapCanvas.tsx projectBattlefieldTarget 과 같은 식이다."""
    col = (longitude * projection["k"] - projection["x0"] + projection["pad"]) / projection["cell"]
    row = (projection["y1"] + projection["pad"] - latitude) / projection["cell"]
    return col, row


def strip_frontier_counties(document: dict) -> dict:
    """이미 세운 縣 城·省·관할을 걷어내 멱등 재생성의 출발점을 만든다."""
    stripped = dict(document)
    stripped["cities"] = [
        row for row in document["cities"] if not str(row["id"]).startswith(PLACE_ID_PREFIX)
    ]
    stripped["jurisdictionRecords"] = [
        row for row in document["jurisdictionRecords"]
        if not str(row["id"]).startswith(PLACE_ID_PREFIX)
    ]
    stripped["commanderyRecords"] = [
        dict(row, jurisdictionIds=[
            item for item in row["jurisdictionIds"] if not str(item).startswith(PLACE_ID_PREFIX)
        ])
        for row in document["commanderyRecords"]
    ]
    return stripped


def digest(document: dict) -> str:
    """relocate_han_province.digest 와 같은 문서 지문(정렬 키, 최소 구분자)."""
    blob = json.dumps(document, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(blob.encode("utf-8")).hexdigest()


def has_frontier_counties(document: dict) -> bool:
    return any(str(row["id"]).startswith(PLACE_ID_PREFIX) for row in document["cities"])


def restored_to_prior_stage(document: dict) -> dict:
    """縣 51곳을 세우기 **전** 단계(=Geuk 재배치 산출)로 되돌린다. 이미 그 단계면 그대로 준다.

    이 단계를 앵커로 쓰는 이전 판정들(조각 판정·관할 구체화·영역 단절)은 자기 해시를
    그 단계 문서에 대고 못박아 뒀다. 縣 단계는 그 위에 얹힌 나중 단계라, 앵커를 갈아끼우지
    말고 원장의 priorStage 로 복원해서 비교한다."""
    if not has_frontier_counties(document):
        return document
    return restore_document(document, json.loads(PLACEMENTS.read_text(encoding="utf-8")))


def _affected_mask(document: dict, affected: list[str], owner: np.ndarray, parent_owner: np.ndarray) -> np.ndarray:
    parent_index_by_id = {row["id"]: index for index, row in enumerate(document["parentRegions"])}
    return np.isin(parent_owner, [parent_index_by_id[parent_id] for parent_id in affected]) & (owner >= 0)


def _prior_stage(document: dict, affected: list[str]) -> dict:
    """세우기 전 문서에서 복원에 필요한 만큼만 뽑는다(영향 郡 칸의 owner 省 id 런·省·관할·counts)."""
    meta = document["_meta"]
    rows, cols = meta["rows"], meta["cols"]
    owner = expand_rle(document["owner"], rows, cols)
    parent_owner = expand_rle(document["parentOwner"], rows, cols)
    province_ids = [row["id"] for row in document["provinceRecords"]]
    runs: list[list[object]] = []
    for value in owner[_affected_mask(document, affected, owner, parent_owner)].tolist():
        province_id = province_ids[value]
        if runs and runs[-1][0] == province_id:
            runs[-1][1] += 1
        else:
            runs.append([province_id, 1])
    return {
        "inputDocumentSha256": digest(document),
        "outputDocumentSha256": None,
        "affectedParentRegionIds": list(affected),
        "ownerBeforeRuns": runs,
        "provinceRecordsBefore": [
            dict(row) for row in document["provinceRecords"] if row["parentRegionId"] in affected
        ],
        "jurisdictionRecordsBefore": [
            dict(row) for row in document["jurisdictionRecords"] if row["commanderyId"] in affected
        ],
        "countsBefore": dict(meta["counts"]),
    }


def restore_document(document: dict, placements: dict) -> dict:
    """세운 문서를 배치 원장의 priorStage 로 세우기 전 문서로 되돌린다. 지문이 맞아야만 한다."""
    stage = placements.get("priorStage")
    if not isinstance(stage, dict):
        raise FrontierMaterializationError("placement ledger carries no priorStage to restore from")
    if digest(document) != stage["outputDocumentSha256"]:
        raise FrontierMaterializationError("document is not the pinned frontier-county output")
    affected = list(stage["affectedParentRegionIds"])
    stripped = strip_frontier_counties(document)
    meta = document["_meta"]
    rows, cols = meta["rows"], meta["cols"]
    terrain = np.array([[int(value) for value in line] for line in document["terrain"]], dtype=np.int8)
    owner = expand_rle(document["owner"], rows, cols)
    parent_owner = expand_rle(document["parentOwner"], rows, cols)
    provinces = sorted(
        [row for row in document["provinceRecords"] if row["parentRegionId"] not in affected]
        + [dict(row) for row in stage["provinceRecordsBefore"]],
        key=lambda row: row["id"],
    )
    index_by_id = {row["id"]: index for index, row in enumerate(provinces)}
    current_ids = [row["id"] for row in document["provinceRecords"]]
    affected_mask = _affected_mask(document, affected, owner, parent_owner)
    restored_owner = np.full(owner.shape, -1, dtype=np.int32)
    kept = (owner >= 0) & ~affected_mask
    remap = np.array([index_by_id.get(province_id, -1) for province_id in current_ids], dtype=np.int32)
    restored_owner[kept] = remap[owner[kept]]
    if int((restored_owner[kept] < 0).sum()):
        raise FrontierMaterializationError("unaffected cells reference a province the prior stage lacks")
    values: list[int] = []
    for province_id, length in stage["ownerBeforeRuns"]:
        values.extend([index_by_id[province_id]] * int(length))
    if len(values) != int(affected_mask.sum()):
        raise FrontierMaterializationError("prior owner runs do not cover the affected cells")
    restored_owner[affected_mask] = np.array(values, dtype=np.int32)
    jurisdictions = sorted(
        [row for row in stripped["jurisdictionRecords"] if row["commanderyId"] not in affected]
        + [dict(row) for row in stage["jurisdictionRecordsBefore"]],
        key=lambda row: row["id"],
    )
    restored = dict(stripped)
    restored["owner"] = encode_rle(restored_owner)
    restored["provinceRecords"] = provinces
    restored["jurisdictionRecords"] = jurisdictions
    county_adjacency, commandery_adjacency = derive_world_adjacency(
        terrain, jun_seat_coordinates(restored, restored["cities"]), restored_owner, parent_owner,
    )
    restored["adjacency"] = {"county": county_adjacency, "commandery": commandery_adjacency}
    restored["_meta"] = dict(meta)
    restored["_meta"]["counts"] = dict(stage["countsBefore"])
    if digest(restored) != stage["inputDocumentSha256"]:
        raise FrontierMaterializationError("restored document differs from the pinned prior stage")
    return restored


def _label_pieces(labels: np.ndarray, mask: np.ndarray, label: int) -> list[list[tuple[int, int]]]:
    """4-이웃 연결 조각. repair_label_connectivity 와 같은 규칙이다."""
    unseen = mask & (labels == label)
    rows, cols = labels.shape
    pieces: list[list[tuple[int, int]]] = []
    for start_row, start_col in np.argwhere(unseen):
        if not unseen[start_row, start_col]:
            continue
        unseen[start_row, start_col] = False
        pending = [(int(start_row), int(start_col))]
        piece: list[tuple[int, int]] = []
        while pending:
            row, col = pending.pop()
            piece.append((row, col))
            for next_row, next_col in ((row - 1, col), (row + 1, col), (row, col - 1), (row, col + 1)):
                if 0 <= next_row < rows and 0 <= next_col < cols and unseen[next_row, next_col]:
                    unseen[next_row, next_col] = False
                    pending.append((next_row, next_col))
        pieces.append(piece)
    return pieces


def _nearest_cell(
    candidates: np.ndarray, row: float, col: float,
) -> tuple[int, int, float]:
    distances = (candidates[:, 0] - row) ** 2 + (candidates[:, 1] - col) ** 2
    order = np.lexsort((candidates[:, 1], candidates[:, 0], distances))
    best = candidates[order[0]]
    return int(best[0]), int(best[1]), float(np.sqrt(distances[order[0]]))


def place_counties(document: dict, ledger: dict) -> list[dict]:
    """縣마다 칸·세계 郡·근거를 정한다. 문서는 바꾸지 않는다."""
    meta = document["_meta"]
    rows, cols = meta["rows"], meta["cols"]
    projection = meta["projection"]
    terrain = np.array([[int(value) for value in line] for line in document["terrain"]], dtype=np.int8)
    owner = expand_rle(document["owner"], rows, cols)
    parent_owner = expand_rle(document["parentOwner"], rows, cols)
    parents = document["parentRegions"]
    parent_index_by_name = {row["nameCh"]: index for index, row in enumerate(parents)}
    land = (owner >= 0) & ~np.isin(terrain, sorted(WATER_OR_OUT_OF_SCOPE))
    occupied = {(int(row["row"]), int(row["col"])) for row in document["cities"]}

    placements: list[dict] = []
    for county in ledger["counties"]:
        coordinates = county.get("coordinates") or {}
        latitude, longitude = coordinates.get("latitude"), coordinates.get("longitude")
        if not isinstance(latitude, (int, float)) or not isinstance(longitude, (int, float)):
            raise FrontierMaterializationError(f"frontier county has no coordinates: {county['id']}")
        if county["commanderyHan"] not in parent_index_by_name:
            raise FrontierMaterializationError(
                f"frontier county commandery is not a parent region: {county['id']}"
            )
        hhs_parent = parent_index_by_name[county["commanderyHan"]]
        projected_col, projected_row = project_cell(projection, latitude, longitude)
        cell_col, cell_row = int(projected_col), int(projected_row)
        if not (0 <= cell_col < cols and 0 <= cell_row < rows):
            raise FrontierMaterializationError(f"frontier county projects outside the grid: {county['id']}")
        at_cell = int(parent_owner[cell_row, cell_col])
        at_cell_is_han = (
            at_cell >= 0 and parents[at_cell]["administrativeSystem"] == "HAN_COMMANDERY"
        )
        if land[cell_row, cell_col] and at_cell_is_han and (cell_row, cell_col) not in occupied:
            world_parent, basis, distance = at_cell, BASIS_AT_CELL, 0.0
            final_row, final_col = cell_row, cell_col
        else:
            # 물·비플레이·郡國 밖 세력 땅·이미 城이 선 칸: 郡國志 郡의 빈 육지 칸 중 가장 가까운 곳.
            mask = land & (parent_owner == hhs_parent)
            candidates = np.array([
                cell for cell in np.argwhere(mask) if (int(cell[0]), int(cell[1])) not in occupied
            ], dtype=np.int64)
            if len(candidates) == 0:
                raise FrontierMaterializationError(
                    f"no free land cell in the HHS commandery for: {county['id']}"
                )
            final_row, final_col, distance = _nearest_cell(candidates, projected_row, projected_col)
            world_parent, basis = hhs_parent, BASIS_SNAPPED
        occupied.add((final_row, final_col))
        placements.append({
            "id": county["id"],
            "nameHan": county["nameHan"],
            "physicalPlaceId": place_id(county),
            "physicalPlaceRef": physical_place_ref(county),
            "projectedCol": round(projected_col, 3),
            "projectedRow": round(projected_row, 3),
            "col": final_col,
            "row": final_row,
            "terrain": int(terrain[final_row, final_col]),
            "hhsCommanderyHan": county["commanderyHan"],
            "hhsParentRegionId": parents[hhs_parent]["id"],
            "worldParentRegionId": parents[world_parent]["id"],
            "worldCommanderyHan": parents[world_parent]["nameCh"],
            "parentOwnerAtProjectedCell": parents[at_cell]["nameCh"] if at_cell >= 0 else None,
            "basis": basis,
            "snapDistanceCells": round(distance, 3),
        })
    return placements


def _province_record(
    *, province_id: str, display_name: str, name_ch: str, parent_id: str, city_index: int | None,
    geometry_basis: str, confidence: str, jurisdiction_id: str, assignment_basis: str,
    assignment_confidence: str,
) -> dict:
    return {
        "id": province_id, "displayName": display_name, "nameCh": name_ch,
        "administrativeSystem": "HAN_COMMANDERY", "kind": "SPATIAL_PROVINCE",
        "parentRegionId": parent_id, "cityIndex": city_index,
        "geometryBasis": geometry_basis, "confidence": confidence,
        "jurisdictionId": jurisdiction_id, "assignmentBasis": assignment_basis,
        "assignmentConfidence": assignment_confidence,
    }


def materialize_frontier_counties(document: dict, ledger: dict) -> tuple[dict, dict]:
    if has_frontier_counties(document):
        raise FrontierMaterializationError(
            "document already carries frontier counties; restore_document(...) first"
        )
    base = document
    placements = place_counties(base, ledger)
    meta = base["_meta"]
    rows, cols = meta["rows"], meta["cols"]
    terrain = np.array([[int(value) for value in line] for line in base["terrain"]], dtype=np.int8)
    owner = expand_rle(base["owner"], rows, cols)
    parent_owner = expand_rle(base["parentOwner"], rows, cols)
    parents = base["parentRegions"]
    parent_index_by_id = {row["id"]: index for index, row in enumerate(parents)}

    counties_by_id = {row["id"]: row for row in ledger["counties"]}
    cities = [dict(row) for row in base["cities"]]
    city_index_by_place: dict[str, int] = {}
    for placement in placements:
        county = counties_by_id[placement["id"]]
        city_index_by_place[placement["physicalPlaceId"]] = len(cities)
        cities.append({
            "id": placement["physicalPlaceId"],
            "name": f"{county['nameKo']}현",
            "nameCh": f"{county['nameHan']}縣",
            "level": COUNTY_TILE_LEVEL,
            "kind": "COUNTY",
            "seat": False,
            "zhi": False,
            "col": placement["col"],
            "row": placement["row"],
            "lon": county["coordinates"]["longitude"],
            "lat": county["coordinates"]["latitude"],
        })

    affected = sorted({placement["worldParentRegionId"] for placement in placements})
    prior_stage = _prior_stage(base, affected)
    old_provinces = base["provinceRecords"]
    jurisdictions = {row["id"]: dict(row) for row in base["jurisdictionRecords"]}
    commanderies = {row["id"]: dict(row) for row in base["commanderyRecords"]}
    kept_provinces = [row for row in old_provinces if row["parentRegionId"] not in affected]
    kept_old_index = {
        index: row["id"] for index, row in enumerate(old_provinces)
        if row["parentRegionId"] not in affected
    }
    new_provinces: list[dict] = list(kept_provinces)
    new_owner = np.full(owner.shape, -1, dtype=np.int32)
    label_cells: dict[str, np.ndarray] = {}

    for parent_id in affected:
        parent_index = parent_index_by_id[parent_id]
        parent = parents[parent_index]
        commandery = commanderies[parent_id]
        seat_jurisdiction_id = commandery["seatJurisdictionId"]
        seat_jurisdiction = jurisdictions[seat_jurisdiction_id]
        seat_city_index = base["juns"][parent_index]["seat"]
        seat_city = cities[seat_city_index]
        if seat_jurisdiction["seatPlaceId"] != seat_city["id"]:
            raise FrontierMaterializationError(
                f"seat jurisdiction does not point at the jun seat city: {parent_id}"
            )
        mask = (parent_owner == parent_index) & (owner >= 0)
        anchors: list[tuple[int, int]] = [(int(seat_city["row"]), int(seat_city["col"]))]
        anchored_rows: list[dict] = [_province_record(
            province_id=str(seat_city["id"]), display_name=seat_jurisdiction["displayName"],
            name_ch=seat_jurisdiction["nameCh"], parent_id=parent_id, city_index=seat_city_index,
            geometry_basis="HISTORICAL_SEAT_ADAPTED", confidence="REVIEWED",
            jurisdiction_id=seat_jurisdiction_id, assignment_basis="HISTORICAL_SEAT",
            assignment_confidence="REVIEWED",
        )]
        for placement in placements:
            if placement["worldParentRegionId"] != parent_id:
                continue
            county = counties_by_id[placement["id"]]
            city_index = city_index_by_place[placement["physicalPlaceId"]]
            anchors.append((placement["row"], placement["col"]))
            anchored_rows.append(_province_record(
                province_id=placement["physicalPlaceId"], display_name=f"{county['nameKo']}현",
                name_ch=f"{county['nameHan']}縣", parent_id=parent_id, city_index=city_index,
                geometry_basis="HISTORICAL_SEAT_ADAPTED", confidence="IDENTIFIED",
                jurisdiction_id=placement["physicalPlaceId"], assignment_basis="HISTORICAL_SEAT",
                assignment_confidence="IDENTIFIED",
            ))
            jurisdictions[placement["physicalPlaceId"]] = {
                "id": placement["physicalPlaceId"],
                "displayName": f"{county['nameKo']}현",
                "nameCh": f"{county['nameHan']}縣",
                "kind": "COUNTY",
                "commanderyId": parent_id,
                "seatPlaceId": placement["physicalPlaceId"],
                "provinceIds": [placement["physicalPlaceId"]],
            }
        for row, col in anchors:
            if not mask[row, col]:
                raise FrontierMaterializationError(
                    f"anchor falls outside its parent's playable cells: {parent_id} @{col}:{row}"
                )
        count = len(anchors)
        labels, _ = balanced_parent_labels(
            mask, count, fixed_anchors=tuple(anchors), minimum_anchor_area=MINIMUM_AREA,
        )
        label_of_anchor = [int(labels[row, col]) for row, col in anchors]
        if len(set(label_of_anchor)) != len(anchors):
            raise FrontierMaterializationError(f"anchors share a partition label: {parent_id}")
        labels = repair_label_connectivity(
            labels, mask, count,
            fixed_anchor_by_label={label: anchor for label, anchor in zip(label_of_anchor, anchors)},
        )
        # 앵커에 육로로 닿지 않는 조각은 섬뿐이어야 한다. 섬은 종전에 그 섬을 들고 있던
        # DIRECT 省을 id 그대로 남긴다(원장의 섬 판정 행이 그 id 를 가리킨다). 城이 있던 省의
        # 섬이면 郡治 省으로 보낸다.
        island_cells: dict[str, list[tuple[int, int]]] = {}
        for label, anchor in zip(label_of_anchor, anchors):
            for piece in _label_pieces(labels, mask, label):
                if anchor in piece:
                    continue
                neighbours = set()
                for row, col in piece:
                    for next_row, next_col in ((row - 1, col), (row + 1, col), (row, col - 1), (row, col + 1)):
                        if 0 <= next_row < rows and 0 <= next_col < cols and mask[next_row, next_col]:
                            neighbours.add(int(labels[next_row, next_col]))
                neighbours.discard(label)
                if neighbours:
                    raise FrontierMaterializationError(
                        f"detached partition piece survived repair: {parent_id} @{piece[0][1]}:{piece[0][0]}"
                    )
                previous = {old_provinces[int(owner[row, col])]["id"] for row, col in piece}
                previous_record = None
                if len(previous) == 1:
                    previous_record = next(
                        record for record in old_provinces if record["id"] == next(iter(previous))
                    )
                if previous_record is not None and previous_record["cityIndex"] is None:
                    island_cells.setdefault(previous_record["id"], []).extend(piece)
                    for row, col in piece:
                        labels[row, col] = -1
                else:
                    for row, col in piece:
                        labels[row, col] = label_of_anchor[0]
        parent_rows: list[tuple[str, dict, int | None]] = []
        for label, record in zip(label_of_anchor, anchored_rows):
            parent_rows.append((record["id"], record, label))
        for island_id in sorted(island_cells):
            previous_record = next(record for record in old_provinces if record["id"] == island_id)
            if previous_record["jurisdictionId"] != seat_jurisdiction_id:
                raise FrontierMaterializationError(f"island province is not under the seat jurisdiction: {island_id}")
            parent_rows.append((island_id, dict(previous_record), None))
        seat_jurisdiction["provinceIds"] = sorted(
            record["id"] for _, record, _ in parent_rows
            if record["jurisdictionId"] == seat_jurisdiction_id
        )
        for province_id, record, label in parent_rows:
            new_provinces.append(record)
            if label is None:
                cells = np.zeros(mask.shape, dtype=bool)
                for row, col in island_cells[province_id]:
                    cells[row, col] = True
                label_cells[province_id] = cells
            else:
                label_cells[province_id] = (labels == label) & mask
        commandery["jurisdictionIds"] = sorted({
            *commandery["jurisdictionIds"],
            *(record["jurisdictionId"] for _, record, _ in parent_rows),
        })

    new_provinces.sort(key=lambda row: row["id"])
    new_index_by_id = {row["id"]: index for index, row in enumerate(new_provinces)}
    remap = np.full(len(old_provinces), -1, dtype=np.int32)
    for old_index, province_id in kept_old_index.items():
        remap[old_index] = new_index_by_id[province_id]
    kept_mask = owner >= 0
    for parent_id in affected:
        kept_mask &= parent_owner != parent_index_by_id[parent_id]
    new_owner[kept_mask] = remap[owner[kept_mask]]
    for province_id, cells in label_cells.items():
        new_owner[cells] = new_index_by_id[province_id]
    if int(np.count_nonzero(new_owner >= 0)) != int(np.count_nonzero(owner >= 0)):
        raise FrontierMaterializationError("playable cell count changed while repartitioning")

    jurisdiction_rows = sorted(jurisdictions.values(), key=lambda row: row["id"])
    commandery_rows = [commanderies[row["id"]] for row in base["commanderyRecords"]]
    validate_materialized_hierarchy(new_provinces, jurisdiction_rows, commandery_rows)

    county_adjacency, commandery_adjacency = derive_world_adjacency(
        terrain, jun_seat_coordinates(base, cities), new_owner, parent_owner,
    )
    updated = dict(base)
    updated["cities"] = cities
    updated["owner"] = encode_rle(new_owner)
    updated["provinceRecords"] = new_provinces
    updated["jurisdictionRecords"] = jurisdiction_rows
    updated["commanderyRecords"] = commandery_rows
    updated["adjacency"] = {"county": county_adjacency, "commandery": commandery_adjacency}
    updated["_meta"] = dict(meta)
    counts = dict(meta["counts"])
    kinds = {kind: 0 for kind in ("COUNTY", "EXTERNAL_PLACE", "COMMANDERY", "KINGDOM", "PROVINCE")}
    for row in cities:
        kinds[row["kind"]] = kinds.get(row["kind"], 0) + 1
    counts.update(
        cities=len(cities), **kinds,
        adjCounty=len(county_adjacency), adjCommandery=len(commandery_adjacency),
        provinces=len(new_provinces), jurisdictions=len(jurisdiction_rows),
    )
    updated["_meta"]["counts"] = counts

    placement_document = {
        "schemaVersion": 1,
        "ledgerId": "han-frontier-county-placements-v1",
        "generator": "tools/map/materialize_frontier_counties.py",
        "note": (
            "생성물이다. frontier-counties-v1 의 좌표를 han-tiles 투영으로 칸에 앉힌 결과와 세계 소속 郡이다. "
            "PARENT_OWNER_AT_PROJECTED_CELL = 투영 칸의 220 년 郡 경계를 따른다(郡國志 소속과 다를 수 있다). "
            "SNAPPED_INTO_HHS_COMMANDERY = 칸이 물·비플레이·郡國 밖 세력 땅이라 郡國志 郡의 가장 가까운 빈 육지 칸으로 옮겼다."
        ),
        "inputs": {
            "frontierCountyLedger": {"path": "data/curated/han/frontier-counties-v1.json"},
            "projection": dict(projection_of(meta)),
        },
        "summary": {
            "placedCount": len(placements),
            "basisCounts": {
                basis: sum(1 for row in placements if row["basis"] == basis)
                for basis in (BASIS_AT_CELL, BASIS_SNAPPED)
            },
            "worldCommanderyCounts": {
                name: sum(1 for row in placements if row["worldCommanderyHan"] == name)
                for name in sorted({row["worldCommanderyHan"] for row in placements})
            },
            "hhsCommanderyReassignedCount": sum(
                1 for row in placements if row["worldParentRegionId"] != row["hhsParentRegionId"]
            ),
            "affectedParentRegionIds": affected,
        },
        "placements": placements,
        # outputCityOrder — 이 단계 산출 문서의 cities[] 순서다. 배열 순서만 뒤바꾼 문서를
        # 받은 쪽(재조정 원장의 순서 무관 계약)이 앞 단계로 되돌리기 전에 제 순서로
        # 되돌리는 데 쓴다. relocate_han_province.canonicalize_city_order 의 inputCityOrder
        # 와 같은 역할이고, 값은 지문에 이미 들어 있는 것을 펴 적은 것뿐이다.
        "priorStage": dict(
            prior_stage,
            outputDocumentSha256=digest(updated),
            outputCityOrder=[row["id"] for row in updated["cities"]],
        ),
    }
    return updated, placement_document


def projection_of(meta: dict) -> dict:
    return {key: meta["projection"][key] for key in ("cell", "k", "x0", "y1", "pad", "cols", "rows")}


def _dump(document: dict) -> str:
    return json.dumps(document, ensure_ascii=False, separators=(",", ":")) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--tiles", type=Path, default=TILES)
    parser.add_argument("--ledger", type=Path, default=LEDGER)
    parser.add_argument("--placements", type=Path, default=PLACEMENTS)
    args = parser.parse_args()
    document = json.loads(args.tiles.read_text(encoding="utf-8"))
    ledger = json.loads(args.ledger.read_text(encoding="utf-8"))
    if has_frontier_counties(document):
        if not args.placements.is_file():
            print("han-tiles already carries frontier counties but no placement ledger exists to restore from",
                  file=sys.stderr)
            return 1
        committed = json.loads(args.placements.read_text(encoding="utf-8"))
        base = restore_document(document, committed)
        input_tiles_sha256 = committed["priorStage"]["inputTilesSha256"]
    else:
        base = document
        input_tiles_sha256 = _sha256(args.tiles)
    updated, placement_document = materialize_frontier_counties(base, ledger)
    placement_document["inputs"]["frontierCountyLedger"]["sha256"] = _sha256(args.ledger)
    placement_document["priorStage"] = {
        "inputTilesSha256": input_tiles_sha256, **placement_document["priorStage"],
    }
    tiles_blob = _dump(updated)
    placement_blob = json.dumps(placement_document, ensure_ascii=False, indent=2) + "\n"
    if args.check:
        failures = []
        if updated != document:
            failures.append("data/map/han-tiles.json is not the materialized frontier-county document")
        if not args.placements.is_file() or args.placements.read_text(encoding="utf-8") != placement_blob:
            failures.append(f"{args.placements} is stale")
        for failure in failures:
            print(failure, file=sys.stderr)
        return 1 if failures else 0
    args.tiles.write_text(tiles_blob, encoding="utf-8")
    args.placements.write_text(placement_blob, encoding="utf-8")
    summary = placement_document["summary"]
    print(
        f"placed {summary['placedCount']} frontier counties: {summary['basisCounts']} · "
        f"reassigned {summary['hhsCommanderyReassignedCount']} · provinces {len(updated['provinceRecords'])} · "
        f"cities {len(updated['cities'])} · jurisdictions {len(updated['jurisdictionRecords'])}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
