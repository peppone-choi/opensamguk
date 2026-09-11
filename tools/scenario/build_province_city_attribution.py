#!/usr/bin/env python3
"""省(프로빈스) → 城(라우트 노드) 귀속 원장을 굽는다.

## 왜 필요한가

han-tiles 는 1,520 省 전부에 소속 縣(`provinceRecords[].jurisdictionId`)을 이미 달아 두고
있고, game-api 가 그 쌍방 무결성을 검사한다(MapAdministrativeOwnership.loadCanonicalData —
소속 縣 없는 省은 부팅에서 터진다). 문제는 그 다음 칸이다. 1,071 縣 중 게임 城(라우트 노드)이
선정된 縣은 832 곳뿐이라, 나머지 239 縣의 땅은 게임 쪽에서 「어느 城의 땅인가」를 답할 수
없었다. 그 결과가 두 가지로 나왔다(2026-09-11 실측):

  · 이동   `build_han_world.project_county_adjacency` 는 양쪽 省 중 하나라도 城이 없으면
           인접 간선을 버린다. 그래서 832 城 그래프가 **성분 36 개**로 쪼개져 174 城이
           본토에서 닿지 않았다(origin/main 781 城도 성분 42 · 미도달 144 — 예전부터다).
           樂浪·帶方 15, 巴郡 40, 益州南部+九真 39, 金城·隴西 11 … 이 그렇게 끊겼다.
  · 색칠   城 없는 縣의 땅은 어느 나라 색도 받지 못해 지도에 빵꾸로 남았다.

## 귀속 규칙 — 사료·고증이 일순위, 기하는 문서화된 최후 수단

  T1 OWN_COUNTY_SEAT       省의 소속 縣이 城을 가졌다 → 그 城.
                           근거는 han-tiles 의 縣 귀속 자체다(assignmentBasis
                           HISTORICAL_SEAT · REVIEWED_PARENT_SEAT_RECOVERY 등, 이미 심사됨).
  T2 SAME_COMMANDERY_SEAT  소속 縣에 城이 없고, 그 縣이 든 郡의 郡治에 城이 있다 → 郡治 城.
                           근거는 續漢書 郡國志의 「縣은 郡에 속한다」 와 郡治가 그 郡의
                           治所라는 사실이다. 縣을 건너뛰는 게 아니라 縣의 상급으로 올린다.
  T3 SAME_COMMANDERY_NEAREST 郡에 郡治 城이 없고 다른 屬縣 城만 있다 → 같은 郡 안에서
                           가장 가까운 城. 여기만 기하다. 사료가 답을 주지 못하는 칸이라
                           basis 로 드러내 두고, 郡 경계는 절대 넘지 않는다.
  T4 COMMANDERY_HAS_NO_CITY  그 郡 전체에 城이 하나도 없다 → 귀속 없음(null).
                           52 郡 229 省이 여기다. 대부분 郡國 밖 세력(夫餘·烏桓·南匈奴·
                           鮮卑·邪馬壹國·삼한 소국 등 31 곳)과 城이 선정되지 않은
                           郡(朔方·西河·定襄·涪陵·廣漢屬國 등)이다. 지어낸 城으로 메우지
                           않는다 — 城 승격은 라우트 노드 키 발급이 따르는 별도 단계다.

## 산출·검사

    python3 tools/scenario/build_province_city_attribution.py
    python3 tools/scenario/build_province_city_attribution.py --check
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import sys
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TILES = ROOT / "data" / "map" / "han-tiles.json"
SELECTION = ROOT / "data" / "curated" / "han" / "route-node-selection-v1.json"
LEDGER = ROOT / "data" / "curated" / "han" / "province-city-attribution-v1.json"

BASIS_ORDER = (
    "OWN_COUNTY_SEAT",
    "SAME_COMMANDERY_SEAT",
    "SAME_COMMANDERY_NEAREST",
    "COMMANDERY_HAS_NO_CITY",
)


def sha256_path(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def province_centroids(tiles: dict) -> dict[int, tuple[float, float]]:
    """省별 격자 무게중심. 좌표를 원장에 적지는 않는다 — T3 순서를 정하는 데만 쓴다."""
    cols = tiles["_meta"]["cols"]
    sums: dict[int, list[float]] = defaultdict(lambda: [0.0, 0.0, 0.0])
    position = 0
    for province_index, count in tiles["owner"]:
        if province_index >= 0:
            # 런은 행을 넘어갈 수 있다. 셀 단위로 더해야 무게중심이 맞다.
            bucket = sums[province_index]
            for cell in range(position, position + count):
                bucket[0] += cell % cols
                bucket[1] += cell // cols
                bucket[2] += 1
        position += count
    return {
        index: (bucket[0] / bucket[2], bucket[1] / bucket[2])
        for index, bucket in sums.items()
    }


def build_rows(tiles: dict, selection: dict) -> tuple[list[dict], Counter, list[dict]]:
    provinces = tiles["provinceRecords"]
    jurisdictions = {str(row["id"]): row for row in tiles["jurisdictionRecords"]}
    parents = {row["id"]: row for row in tiles["parentRegions"]}
    nodes_by_place = {
        node["physicalPlaceRef"].rsplit(":", 1)[-1]: node
        for node in selection["routeNodes"]
    }

    county_node: dict[str, dict] = {}
    for jurisdiction_id, jurisdiction in jurisdictions.items():
        node = nodes_by_place.get(str(jurisdiction["seatPlaceId"]))
        if node is not None:
            county_node[jurisdiction_id] = node

    seat_province_index: dict[str, int] = {}
    for index, province in enumerate(provinces):
        jurisdiction = jurisdictions[str(province["jurisdictionId"])]
        if province["id"] == str(jurisdiction["seatPlaceId"]):
            seat_province_index[str(province["jurisdictionId"])] = index

    commandery_nodes: dict[str, list[tuple[dict, int | None]]] = defaultdict(list)
    commandery_seat_node: dict[str, dict] = {}
    for jurisdiction_id, node in county_node.items():
        commandery_id = jurisdictions[jurisdiction_id]["commanderyId"]
        commandery_nodes[commandery_id].append(
            (node, seat_province_index.get(jurisdiction_id))
        )
        if node.get("seatRole") == "COMMANDERY_SEAT":
            commandery_seat_node.setdefault(commandery_id, node)

    centroids = province_centroids(tiles)
    rows: list[dict] = []
    basis_counts: Counter = Counter()
    unattributed: dict[str, int] = defaultdict(int)

    for index, province in enumerate(provinces):
        jurisdiction_id = str(province["jurisdictionId"])
        commandery_id = province["parentRegionId"]
        node: dict | None = None
        if jurisdiction_id in county_node:
            basis = "OWN_COUNTY_SEAT"
            node = county_node[jurisdiction_id]
        elif commandery_id in commandery_seat_node:
            basis = "SAME_COMMANDERY_SEAT"
            node = commandery_seat_node[commandery_id]
        elif commandery_nodes.get(commandery_id):
            basis = "SAME_COMMANDERY_NEAREST"
            here = centroids.get(index)
            node = min(
                commandery_nodes[commandery_id],
                key=lambda pair: (
                    _distance(here, centroids.get(pair[1])),
                    pair[0]["numericCityId"],
                ),
            )[0]
        else:
            basis = "COMMANDERY_HAS_NO_CITY"
            unattributed[commandery_id] += 1
        basis_counts[basis] += 1
        rows.append(
            {
                "provinceId": province["id"],
                "provinceIndex": index,
                "jurisdictionId": jurisdiction_id,
                "commanderyId": commandery_id,
                "commanderyNameCh": parents[commandery_id]["nameCh"],
                "basis": basis,
                "routeNodeId": node["numericCityId"] if node else None,
                "routeNodeKey": node["nodeKey"] if node and "nodeKey" in node else None,
                "cityPlaceId": (
                    node["physicalPlaceRef"].rsplit(":", 1)[-1] if node else None
                ),
            }
        )

    gaps = [
        {
            "commanderyId": commandery_id,
            "commanderyNameCh": parents[commandery_id]["nameCh"],
            "provinceCount": count,
            "seatPlaceId": _commandery_seat_place(tiles, parents[commandery_id]["nameCh"]),
        }
        for commandery_id, count in sorted(
            unattributed.items(), key=lambda item: (-item[1], item[0])
        )
    ]
    return rows, basis_counts, gaps


def _distance(a: tuple[float, float] | None, b: tuple[float, float] | None) -> float:
    if a is None or b is None:
        return math.inf
    return math.dist(a, b)


def _commandery_seat_place(tiles: dict, name_ch: str) -> str | None:
    for jun in tiles["juns"]:
        if jun["nameCh"] == name_ch and isinstance(jun.get("seat"), int):
            return str(tiles["cities"][jun["seat"]]["id"])
    return None


def build_ledger() -> dict:
    tiles = json.loads(TILES.read_text(encoding="utf-8"))
    selection = json.loads(SELECTION.read_text(encoding="utf-8"))
    rows, basis_counts, gaps = build_rows(tiles, selection)
    return {
        "schemaVersion": 1,
        "attributionId": "province-city-attribution-v1",
        "policy": {
            "orderedBasis": list(BASIS_ORDER),
            "note": (
                "省의 소속 縣은 han-tiles 가 정본이다. 이 원장은 그 縣을 게임 城으로 내리는 "
                "마지막 칸만 정한다. 사료 근거는 T1(縣 귀속 심사) · T2(郡國志 郡 소속)이고, "
                "T3 만 기하 폴백이며 郡 경계를 넘지 않는다. T4 는 城이 없어 비워 둔다."
            ),
        },
        "provenance": {
            "generator": "tools/scenario/build_province_city_attribution.py",
            "tilesSha256": sha256_path(TILES),
            "selectionSha256": sha256_path(SELECTION),
        },
        "summary": {
            "provinceCount": len(rows),
            "basisCounts": {key: basis_counts.get(key, 0) for key in BASIS_ORDER},
            "attributedProvinceCount": sum(
                1 for row in rows if row["routeNodeId"] is not None
            ),
            "distinctRouteNodeCount": len(
                {row["routeNodeId"] for row in rows if row["routeNodeId"] is not None}
            ),
            "commanderiesWithoutCity": gaps,
        },
        "rows": rows,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="커밋된 원장과 비교만 한다")
    args = parser.parse_args()

    ledger = build_ledger()
    payload = json.dumps(ledger, ensure_ascii=False, indent=2, sort_keys=False) + "\n"
    if args.check:
        if not LEDGER.exists():
            print(f"원장이 없다: {LEDGER}", file=sys.stderr)
            return 1
        if LEDGER.read_text(encoding="utf-8") != payload:
            print("원장이 입력과 어긋난다 — 재생성해라", file=sys.stderr)
            return 1
        print("province-city-attribution-v1 --check OK")
    else:
        LEDGER.write_text(payload, encoding="utf-8")
        print(f"wrote {LEDGER.relative_to(ROOT)} ({len(payload)} bytes)")
    summary = ledger["summary"]
    print(
        "省 {provinceCount} · 귀속 {attributedProvinceCount} · 城 {distinctRouteNodeCount} · "
        "{basisCounts}".format(**summary)
    )
    print(f"城 없는 郡 {len(summary['commanderiesWithoutCity'])}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
