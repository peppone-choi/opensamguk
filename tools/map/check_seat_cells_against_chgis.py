#!/usr/bin/env python3
"""CHGIS 독립 축 — 城의 제자리(Q1)를 han-tiles 의 lon/lat 이 아닌 CHGIS 원본 좌표로 다시 잰다 (GH #806, 로컬 전용).

왜: Q1 게이트(`measure_province_seat_offset.py --check`)와 ★ 지리 재분할은 **같은 축**(han-tiles `cities[].lon/lat`)
을 쓴다. 그 좌표가 틀렸으면 분할도 검사도 같이 틀리고 초록이 나온다(「검사가 버그를 공유한다」). 이 검사는 입력을
바꾼다: gitignored CHGIS V6 DBF(`data/chgis-source/v6_time_{cnty,pref}_pts_utf_wgs84.dbf`)의 `X_COOR/Y_COOR` 를
SYS_ID 로 직접 읽어, 이 파일 안의 식으로 투영하고(측정 도구의 함수를 가져다 쓰지 않는다), 그 칸이 han-tiles 에서
그 城의 관할 안인지 본다.

원본은 ADR-LITE-039/040 대로 미커밋이다 → **CI 에서는 돌 수 없다.** 입력이 없으면 `SKIPPED` 를 찍고 exit 77 로
끝난다(통과가 아니다). 결합 목록은 77 을 SKIP 으로 따로 센다. 재취득: Harvard Dataverse CHGIS V6 time-series
(cnty/pref pts, curl 로 열린다).

판정:
  * CHGIS 칸 ∈ 제 관할                                  → 일치
  * 아니지만 Q1 예외 원장 행(★ seedExceptions · 거점 displacedFrom)   → 예외(같은 사유)
  * 아니지만 han-tiles 좌표가 CHGIS 와 다르다(판정 원장이 좌표를 고침) → `COORDINATE_OVERRIDDEN` 로 **따로 센다** —
    고친 근거가 커밋된 원장에 있어야 한다(오배정 재결속·劇 이전·동명이지 판정). 없으면 적색.
  * 그 밖                                               → 적색

  python3 tools/map/check_seat_cells_against_chgis.py            # 보고
  python3 tools/map/check_seat_cells_against_chgis.py --check    # 적색이면 exit 1, 입력 없으면 exit 77
  python3 tools/map/check_seat_cells_against_chgis.py --check --shift-degrees 1   # 적색 프로브: CHGIS 좌표를 1° 옮긴다
"""
from __future__ import annotations

import argparse
import json
import math
import struct
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
TILES = ROOT / "data/map/han-tiles.json"
SOURCES = (ROOT / "data/chgis-source/v6_time_cnty_pts_utf_wgs84.dbf",
           ROOT / "data/chgis-source/v6_time_pref_pts_utf_wgs84.dbf")
EXCEPTION_LEDGERS = (ROOT / "data/curated/han/county-location-partition-v1.json",
                     ROOT / "data/curated/han/strategic-site-province-carves-v1.json")
# han-tiles 좌표를 CHGIS 와 다르게 고친 근거가 사는 원장(파일 안에 그 SYS_ID 가 나와야 한다).
OVERRIDE_LEDGERS = (ROOT / "data/curated/han/county-misbinding-rebindings-v1.json",
                    ROOT / "data/curated/han/province-relocations-v1.json",
                    ROOT / "data/curated/han/route-node-location-adjudications-v1.json",
                    ROOT / "data/curated/han/han-place-duplicate-adjudications-v1.json")
SKIPPED = 77
SAME_COORDINATE_DEGREES = 1e-4  # 좌표 표기 반올림(소수 5자리) 허용. 임계값이 아니라 「같은 점인가」의 표기 오차다.


def read_dbf(path: Path) -> list[dict]:
    buf = path.read_bytes()
    nrec, hlen, rlen = struct.unpack("<IHH", buf[4:12])
    fields, off = [], 32
    while buf[off] != 0x0D:
        fields.append((buf[off:off + 11].split(b"\0")[0].decode("ascii"), buf[off + 16]))
        off += 32
    rows = []
    for i in range(nrec):
        rec = buf[hlen + i * rlen: hlen + (i + 1) * rlen]
        if rec[:1] == b"*":
            continue
        row, p = {}, 1
        for name, size in fields:
            row[name] = rec[p:p + size].decode("utf-8", "replace").strip()
            p += size
        rows.append(row)
    return rows


def chgis_points(paths) -> dict[str, tuple[float, float]]:
    points = {}
    for path in paths:
        for row in read_dbf(path):
            try:
                points[row["SYS_ID"]] = (float(row["X_COOR"]), float(row["Y_COOR"]))
            except (KeyError, ValueError):
                continue
    return points


def _expand(runs, rows: int, cols: int) -> np.ndarray:
    values = np.asarray([int(a) for a, _ in runs], dtype=np.int32)
    counts = np.asarray([int(b) for _, b in runs], dtype=np.int64)
    return np.repeat(values, counts).reshape(rows, cols)


def _cell(projection: dict, lon: float, lat: float) -> tuple[int, int]:
    col = (lon * projection["k"] - projection["x0"] + projection["pad"]) / projection["cell"]
    row = (projection["y1"] + projection["pad"] - lat) / projection["cell"]
    return math.floor(row), math.floor(col)


def _exception_ids() -> set[str]:
    ids: set[str] = set()
    for path in EXCEPTION_LEDGERS:
        if not path.is_file():
            continue
        document = json.loads(path.read_text(encoding="utf-8"))
        ids |= {row["jurisdictionId"] for row in document.get("seedExceptions", [])}
        for stage in document.get("geometry", {}).get("stages", []):
            ids |= {row["placeId"] for row in stage.get("placements", []) if "displacedFrom" in row}
    return ids


def evaluate(document: dict, points: dict[str, tuple[float, float]], exception_ids: set[str],
             override_text: str, shift: float = 0.0) -> dict:
    meta = document["_meta"]
    owner = _expand(document["owner"], meta["rows"], meta["cols"])
    juris_of = {row["id"]: row["jurisdictionId"] for row in document["provinceRecords"]}
    index_juris = [row["jurisdictionId"] for row in document["provinceRecords"]]
    out = {"compared": 0, "notInChgis": 0, "agree": 0, "exception": [], "coordinateOverridden": [], "red": []}
    for province in document["provinceRecords"]:
        if province.get("cityIndex") is None:
            continue
        city = document["cities"][province["cityIndex"]]
        point = points.get(city["id"])
        if point is None:
            out["notInChgis"] += 1   # 변경 51縣(fc-*)·외부 취락(X*)·거점(ss-*) — CHGIS SYS_ID 가 아니다
            continue
        out["compared"] += 1
        lon, lat = point[0] + shift, point[1] + shift
        row, col = _cell(meta["projection"], lon, lat)
        inside = 0 <= row < meta["rows"] and 0 <= col < meta["cols"] and owner[row, col] >= 0
        if inside and index_juris[int(owner[row, col])] == juris_of[province["id"]]:
            out["agree"] += 1
            continue
        record = {"cityId": city["id"], "nameCh": city["nameCh"], "jurisdictionId": juris_of[province["id"]],
                  "chgisLonLat": [point[0], point[1]], "tilesLonLat": [city["lon"], city["lat"]],
                  "chgisCell": {"col": col, "row": row},
                  "chgisCellOwner": index_juris[int(owner[row, col])] if inside else None}
        moved = (abs(city["lon"] - point[0]) > SAME_COORDINATE_DEGREES
                 or abs(city["lat"] - point[1]) > SAME_COORDINATE_DEGREES)
        if juris_of[province["id"]] in exception_ids and not shift:
            out["exception"].append(record)
        elif moved and not shift and f'"{city["id"]}"' in override_text:
            out["coordinateOverridden"].append(record)
        else:
            out["red"].append(record)
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--tiles", type=Path, default=TILES)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--shift-degrees", type=float, default=0.0, help="적색 프로브: CHGIS 좌표를 이만큼 옮긴다")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    missing = [path for path in SOURCES if not path.is_file()]
    if missing:
        print("SKIPPED — gitignored CHGIS 원본이 없다(통과 아님): " + ", ".join(p.relative_to(ROOT).as_posix() for p in missing))
        return SKIPPED
    override_text = "".join(path.read_text(encoding="utf-8") for path in OVERRIDE_LEDGERS if path.is_file())
    result = evaluate(json.loads(args.tiles.read_text(encoding="utf-8")), chgis_points(SOURCES), _exception_ids(),
                      override_text, args.shift_degrees)
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=1))
    else:
        print(f"compared {result['compared']} (CHGIS SYS_ID 없는 城 {result['notInChgis']}) | agree {result['agree']} | "
              f"exception {len(result['exception'])} | coordinateOverridden {len(result['coordinateOverridden'])} | "
              f"red {len(result['red'])}")
        for key in ("coordinateOverridden", "red"):
            for row in result[key][:40]:
                print(f"  {key} {row['cityId']} {row['nameCh']} chgis={row['chgisLonLat']} tiles={row['tilesLonLat']} "
                      f"cellOwner={row['chgisCellOwner']}")
    return 1 if args.check and result["red"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
