#!/usr/bin/env python3
"""산으로 칠해진 이름 있는 저지(盆地·平原)를 표고로 되돌린다 — han-tiles 의 마지막 단계.

결함(2026-09-17 실측): han-tiles 의 지형 클래스는 표고가 아니라 Natural Earth 지리구역 폴리곤에서 온다
(build_terrain_grid.REGION_TERRAIN). 폴리곤은 거칠고 `Range/mtn` 이 `Plain` 위에 덮이므로, 산맥 폴리곤 안에
든 분지·산전 평원이 통째로 MOUNTAIN 이 됐다. 雒陽縣 22칸이 전부 MOUNTAIN 이고 PLAIN+BASIN 이 0칸인 관할이
195곳이었다.

이 단계는 지형 클래스와 **독립된 두 번째 축**인 커밋된 ETOPO1 표고(`web/game/public/map/elevation/
han-world-v3-metres.png`, tools/map/build_elevation_grid.py)로 그 칸을 고친다.

규칙(지어내지 않는다):
  1. 고치는 범위는 검토 원장(lowland-terrain-decisions-v1)이 **이름을 적은 저지 단위**의 경위도 상자 안뿐이다.
     전역 규칙이 아니다. 상자가 겹치면 원장에 먼저 적힌 단위가 그 칸을 갖는다.
  2. 상자 안에서 sourceClasses(MOUNTAIN·HILL) 칸 가운데 3×3 창 기복(최고−최저 표고)이 maxReliefM 이하인 칸만
     단위의 targetClass(PLAIN·BASIN)로 바꾼다. maxReliefM 은 임계값을 지은 것이 아니라 실측 기준선이다 —
     Natural Earth `Plain/Lowland` 폴리곤 안 PLAIN 칸의 3×3 기복 p90 (`--measure-baseline` 이 다시 잰다).
     단위에 maxElevationM 이 있으면 그 표고 이하인 칸만이다. 이 상한은 출처가 적은 바닥 고도의 윗값을
     그대로 옮긴 것이고(maxElevationBasis), 출처가 바닥 고도를 말하지 않은 단위에는 상한을 두지 않는다.
  3. 물(SEA·RIVER·LAKE)·DESERT·PLATEAU·OUT_OF_SCOPE 는 건드리지 않는다. 사막은 원래 평평해서 기복으로는
     평지와 가를 수 없고, 고원은 정당한 클래스다. 런타임(HanStrategicTopologyJson)은 지형을 마른 땅/물로만
     읽으므로 마른 땅 → 마른 땅 교체는 省 인접·수계 위상을 바꾸지 않는다.
  4. owner·seatOwner·parentOwner·모든 행 표는 그대로다. 바뀌는 것은 terrain 문자열뿐이다.
  5. 한 칸도 못 고치는 단위는 오류다(죽은 원장 행 금지).

이 단계는 城 없는 관할 접기(fold_cityless_jurisdictions)보다 나중이다. 앞 단계 검사들은 folding.peel() 을
거쳐 이 단계를 먼저 벗긴다. 원장의 `geometry.stages` 가 입력·출력 digest 와 되돌리기에 필요한 칸을 핀으로 박는다.

    python3 tools/map/reclassify_han_lowland_terrain.py --prepare --output data/map/han-tiles.json
    python3 tools/map/reclassify_han_lowland_terrain.py --check
    python3 tools/map/reclassify_han_lowland_terrain.py --report
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import sys
from collections import Counter
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

TILES = ROOT / "data/map/han-tiles.json"
DECISIONS = ROOT / "data/curated/han/lowland-terrain-decisions-v1.json"
LEDGER = ROOT / "data/curated/han/lowland-terrain-reclassifications-v1.json"
NE_REGIONS = ROOT / "data/natural-earth/ne_10m_geography_regions_polys.geojson"

METRE_PNG_OFFSET = 32768
DRY_MUTABLE = {"MOUNTAIN", "HILL"}
TARGETS = {"PLAIN", "BASIN"}
LOWLAND = ("PLAIN", "BASIN")


def digest(document: dict) -> str:
    return hashlib.sha256(json.dumps(document, ensure_ascii=False, sort_keys=True,
                                     separators=(",", ":")).encode()).hexdigest()


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_elevation(path: Path, rows: int, cols: int) -> np.ndarray:
    from PIL import Image
    grid = np.asarray(Image.open(path)).astype(np.int32) - METRE_PNG_OFFSET
    if grid.shape != (rows, cols):
        raise ValueError(f"elevation grid {grid.shape} does not match the tile grid {(rows, cols)}")
    return grid


def relief3(elevation: np.ndarray) -> np.ndarray:
    """3×3 창의 최고−최저 표고(m). 가장자리는 격자 안쪽 칸만 본다."""
    rows, cols = elevation.shape
    high = np.pad(elevation, 1, constant_values=np.iinfo(np.int32).min)
    low = np.pad(elevation, 1, constant_values=np.iinfo(np.int32).max)
    top, bottom = elevation.copy(), elevation.copy()
    for dy in range(3):
        for dx in range(3):
            top = np.maximum(top, high[dy:dy + rows, dx:dx + cols])
            bottom = np.minimum(bottom, low[dy:dy + rows, dx:dx + cols])
    return top - bottom


def cell_lonlat(projection: dict, rows: int, cols: int) -> tuple[np.ndarray, np.ndarray]:
    """칸 중심의 경위도. build_terrain_grid.Proj.cell_lonlat 과 같은 역산이다."""
    gx, gy = np.meshgrid(np.arange(cols), np.arange(rows))
    lon = ((gx + 0.5) * projection["cell"] - projection["pad"] + projection["x0"]) / projection["k"]
    lat = projection["y1"] + projection["pad"] - (gy + 0.5) * projection["cell"]
    return lon, lat


def _terrain_grid(document: dict) -> np.ndarray:
    return np.array([[int(code) for code in row] for row in document["terrain"]], dtype=np.int8)


def _runs(mask: np.ndarray, terrain: np.ndarray) -> list[list[int]]:
    """[row, colStart, colEndInclusive, oldCode] — 되돌리기에 필요한 옛 코드를 같이 적는다."""
    runs: list[list[int]] = []
    for row in np.unique(np.nonzero(mask)[0]).tolist():
        cols = np.nonzero(mask[row])[0].tolist()
        start = previous = cols[0]
        for col in cols[1:] + [None]:
            if col is not None and col == previous + 1 and terrain[row, col] == terrain[row, start]:
                previous = col
                continue
            runs.append([row, start, previous, int(terrain[row, start])])
            start = previous = col
    return runs


def validate_decisions(decisions: dict, legend: dict) -> None:
    names = set(legend.values())
    criterion = decisions["criterion"]
    if not set(criterion["sourceClasses"]) <= DRY_MUTABLE or not criterion["sourceClasses"]:
        raise ValueError(f"sourceClasses must be a non-empty subset of {sorted(DRY_MUTABLE)}")
    if type(criterion["maxReliefM"]) is not int or criterion["maxReliefM"] <= 0:
        raise ValueError("maxReliefM must be a positive integer")
    seen = set()
    for unit in decisions["units"]:
        if unit["id"] in seen:
            raise ValueError(f"duplicate lowland unit {unit['id']}")
        seen.add(unit["id"])
        if unit["targetClass"] not in TARGETS or unit["targetClass"] not in names:
            raise ValueError(f"{unit['id']}: targetClass must be one of {sorted(TARGETS)}")
        box = unit["bbox"]
        if not (box["west"] < box["east"] and box["south"] < box["north"]):
            raise ValueError(f"{unit['id']}: empty bbox")
        evidence = unit.get("evidence")
        if not isinstance(evidence, dict) or not evidence.get("sources") or not evidence.get("note"):
            raise ValueError(f"{unit['id']}: a reviewed unit needs evidence sources and a note")
        ceiling = unit.get("maxElevationM")
        if ceiling is not None and type(ceiling) is not int:
            raise ValueError(f"{unit['id']}: maxElevationM must be an integer or null")
        if (ceiling is None) != (unit.get("maxElevationBasis") is None):
            raise ValueError(f"{unit['id']}: maxElevationM and maxElevationBasis go together")


def apply_reclassification(source: dict, decisions: dict, elevation: np.ndarray) -> tuple[dict, dict]:
    meta = source["_meta"]
    rows, cols, legend = meta["rows"], meta["cols"], meta["terrainLegend"]
    validate_decisions(decisions, legend)
    code_of = {name: int(code) for code, name in legend.items()}
    terrain = _terrain_grid(source)
    if terrain.shape != (rows, cols):
        raise ValueError("terrain rows do not fill the grid")
    criterion = decisions["criterion"]
    flat = relief3(elevation) <= criterion["maxReliefM"]
    eligible = np.isin(terrain, [code_of[name] for name in criterion["sourceClasses"]]) & flat
    lon, lat = cell_lonlat(meta["projection"], rows, cols)
    taken = np.zeros((rows, cols), dtype=bool)
    updated = terrain.copy()
    units = []
    for unit in decisions["units"]:
        box = unit["bbox"]
        mask = (eligible & ~taken & (lon >= box["west"]) & (lon <= box["east"])
                & (lat >= box["south"]) & (lat <= box["north"]))
        if unit.get("maxElevationM") is not None:
            mask &= elevation <= unit["maxElevationM"]
        count = int(mask.sum())
        if count == 0:
            raise ValueError(f"{unit['id']}: no cell qualifies — a dead ledger row is not allowed")
        taken |= mask
        updated[mask] = code_of[unit["targetClass"]]
        metres = elevation[mask]
        units.append({
            "id": unit["id"], "targetClass": unit["targetClass"], "cellCount": count,
            "fromClasses": {legend[str(code)]: int(n) for code, n in sorted(Counter(terrain[mask].tolist()).items())},
            "elevationM": {"min": int(metres.min()), "median": int(np.median(metres)), "max": int(metres.max())},
            "cellRuns": _runs(mask, terrain),
        })
    document = copy.deepcopy(source)
    document["terrain"] = ["".join(str(code) for code in row) for row in updated.tolist()]
    return document, {"units": units, "cellCount": int(taken.sum())}


def _load_inputs(decisions: dict, meta: dict) -> np.ndarray:
    pin = decisions["elevation"]
    path = ROOT / pin["path"]
    if _sha256(path) != pin["sha256"]:
        raise ValueError(f"{pin['path']} changed since the lowland decisions were reviewed")
    return load_elevation(path, meta["rows"], meta["cols"])


def stage_for(document: dict, ledger: dict) -> dict | None:
    fingerprint = digest(document)
    for stage in ledger.get("geometry", {}).get("stages", []):
        if stage["outputDocumentSha256"] == fingerprint:
            return stage
    return None


def restore_document(document: dict, ledger: dict) -> dict:
    stage = stage_for(document, ledger)
    if stage is None:
        raise ValueError("document is not a pinned lowland-terrain reclassification output")
    legend = document["_meta"]["terrainLegend"]
    code_of = {name: int(code) for code, name in legend.items()}
    terrain = _terrain_grid(document)
    for unit in stage["units"]:
        target = code_of[unit["targetClass"]]
        for row, start, end, old in unit["cellRuns"]:
            if not (terrain[row, start:end + 1] == target).all():
                raise ValueError(f"{unit['id']}: terrain cell no longer matches the pinned reclassification")
            terrain[row, start:end + 1] = old
    restored = copy.deepcopy(document)
    restored["terrain"] = ["".join(str(code) for code in row) for row in terrain.tolist()]
    if digest(restored) != stage["inputDocumentSha256"]:
        raise ValueError("restored document differs from the pinned lowland-terrain input")
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
    decisions = {key: ledger[key] for key in ("criterion", "elevation", "units")}
    rebuilt, _ = apply_reclassification(document, decisions, _load_inputs(decisions, document["_meta"]))
    return rebuilt


def build_stage(source: dict, decisions: dict) -> tuple[dict, dict]:
    document, result = apply_reclassification(source, decisions, _load_inputs(decisions, source["_meta"]))
    stage = {"inputDocumentSha256": digest(source), "outputDocumentSha256": digest(document), **result}
    ledger = {
        "schemaVersion": 1,
        "ledgerId": "lowland-terrain-reclassifications-v1",
        "authority": decisions["authority"],
        "inputs": {"decisions": {"path": DECISIONS.relative_to(ROOT).as_posix(), "sha256": _sha256(DECISIONS)}},
        "criterion": decisions["criterion"],
        "elevation": decisions["elevation"],
        "units": decisions["units"],
        "geometry": {"stages": [stage]},
    }
    return document, ledger


def check(document: dict, ledger: dict) -> list[str]:
    stage = stage_for(document, ledger)
    if stage is None:
        return ["han-tiles.json is not the reviewed lowland-terrain reclassification output"]
    problems = []
    if ledger["inputs"]["decisions"]["sha256"] != _sha256(DECISIONS):
        problems.append(f"{ledger['inputs']['decisions']['path']} changed since the reclassification was prepared")
    reviewed = json.loads(DECISIONS.read_text(encoding="utf-8"))
    for key in ("criterion", "elevation", "units"):
        if reviewed[key] != ledger[key]:
            problems.append(f"lowland ledger {key} differs from the reviewed decision file")
    restored = restore_document(document, ledger)
    for key in ("owner", "seatOwner", "parentOwner"):
        if restored[key] != document[key]:
            problems.append(f"lowland stage must not move {key}")
    rebuilt, result = apply_reclassification(restored, reviewed, _load_inputs(reviewed, document["_meta"]))
    if result["units"] != stage["units"] or result["cellCount"] != stage["cellCount"]:
        problems.append("lowland-terrain units differ from the reviewed stage")
    if digest(rebuilt) != stage["outputDocumentSha256"]:
        problems.append("re-applied lowland-terrain reclassification does not reproduce han-tiles.json")
    return problems


def lowland_report(document: dict) -> dict:
    """관할별 PLAIN+BASIN 칸 수 — 0칸 관할과 그 가운데 郡治를 센다."""
    meta = document["_meta"]
    legend = meta["terrainLegend"]
    lowland = [int(code) for code, name in legend.items() if name in LOWLAND]
    terrain = _terrain_grid(document).ravel()
    values, counts = zip(*((int(a), int(b)) for a, b in document["owner"]))
    owner = np.repeat(np.asarray(values, dtype=np.int32), counts)
    index = {row["id"]: number for number, row in enumerate(document["jurisdictionRecords"])}
    province_jurisdiction = np.array([index[row["jurisdictionId"]] for row in document["provinceRecords"]])
    land = owner >= 0
    jurisdiction = province_jurisdiction[owner[land]]
    total = np.bincount(jurisdiction, minlength=len(index))
    low = np.bincount(jurisdiction[np.isin(terrain[land], lowland)], minlength=len(index))
    dominant = {}
    for number in np.nonzero((total > 0) & (low == 0))[0].tolist():
        codes = Counter(terrain[land][jurisdiction == number].tolist())
        dominant[number] = legend[str(codes.most_common(1)[0][0])]
    seats = {row["seatJurisdictionId"]: row["nameCh"] for row in document["commanderyRecords"]
             if row["seatJurisdictionId"]}
    records = document["jurisdictionRecords"]
    return {
        "jurisdictions": int((total > 0).sum()),
        "zeroLowland": len(dominant),
        "zeroLowlandDominant": dict(Counter(dominant.values()).most_common()),
        "zeroLowlandSeats": [{"commandery": seats[records[n]["id"]], "seat": records[n]["nameCh"],
                              "jurisdictionId": records[n]["id"], "dominant": dominant[n]}
                             for n in sorted(dominant) if records[n]["id"] in seats],
    }


def measure_baseline(document: dict, elevation: np.ndarray, regions: Path = NE_REGIONS) -> dict:
    """maxReliefM 의 출처를 다시 잰다. Natural Earth 원본은 미커밋(ADR-LITE-039)이라 로컬에서만 돈다."""
    from PIL import Image, ImageDraw
    meta = document["_meta"]
    rows, cols, projection = meta["rows"], meta["cols"], meta["projection"]
    image = Image.new("L", (cols, rows), 0)
    draw = ImageDraw.Draw(image)
    for feature in json.loads(regions.read_text(encoding="utf-8"))["features"]:
        if feature["properties"].get("FEATURECLA") not in ("Plain", "Lowland"):
            continue
        geometry = feature["geometry"]
        polygons = geometry["coordinates"] if geometry["type"] == "MultiPolygon" else [geometry["coordinates"]]
        for polygon in polygons:
            ring = np.asarray(polygon[0], dtype=float)
            gx = (ring[:, 0] * projection["k"] - projection["x0"] + projection["pad"]) / projection["cell"]
            gy = (projection["y1"] + projection["pad"] - ring[:, 1]) / projection["cell"]
            if gx.max() < 0 or gx.min() > cols or gy.max() < 0 or gy.min() > rows:
                continue
            draw.polygon(list(zip(np.clip(gx, -1e4, 1e4).tolist(), np.clip(gy, -1e4, 1e4).tolist())), fill=1)
    plain = int(next(code for code, name in meta["terrainLegend"].items() if name == "PLAIN"))
    mask = np.asarray(image, dtype=bool) & (_terrain_grid(document) == plain)
    values = relief3(elevation)[mask]
    return {"cells": int(mask.sum()),
            "relief3M": {f"p{q}": int(np.percentile(values, q)) for q in (50, 75, 90, 95, 99)}}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--source", type=Path, default=TILES)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--prepare", action="store_true")
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--report", action="store_true")
    parser.add_argument("--measure-baseline", action="store_true")
    parser.add_argument("--source-is-upstream", action="store_true",
                        help="the source has no lowland stage on it (skip the pinned-output requirement)")
    parser.add_argument("--ne-regions", type=Path, default=NE_REGIONS)
    args = parser.parse_args()
    source = json.loads(args.source.read_text(encoding="utf-8"))
    if args.check:
        problems = check(source, json.loads(LEDGER.read_text(encoding="utf-8")))
        for problem in problems:
            print(problem, file=sys.stderr)
        return 1 if problems else 0
    peeled, peeled_ledger = peel(source)
    if LEDGER.is_file() and peeled_ledger is None and not args.source_is_upstream:
        # peel() 은 핀이 안 맞으면 조용히 (문서, None) 을 준다. 그대로 가면 이미 재분류된 문서 위에 다시 얹으려다
        # 엉뚱한 오류(dead ledger row)로 죽는다. 앞 단계를 다시 구운 뒤라면 그 단계의 --prepare 가 이 원장도 같이
        # 다시 쓴다(fold_cityless_jurisdictions.main). 이 단계가 없는 문서를 넘기는 것이면 --source-is-upstream 을 준다.
        print("the source is not the pinned lowland-terrain output; re-prepare the upstream stage (it rewrites this "
              "ledger) or pass --source-is-upstream for a document without this stage", file=sys.stderr)
        return 1
    if args.report:
        print(json.dumps({"before": lowland_report(peeled), "after": lowland_report(source)},
                         ensure_ascii=False, indent=2))
        return 0
    decisions = json.loads(DECISIONS.read_text(encoding="utf-8"))
    if args.measure_baseline:
        print(json.dumps(measure_baseline(peeled, _load_inputs(decisions, peeled["_meta"]), args.ne_regions), ensure_ascii=False))
        return 0
    document, ledger = build_stage(peeled, decisions)
    if args.prepare:
        LEDGER.write_text(json.dumps(ledger, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if args.output:
        args.output.write_text(json.dumps(document, ensure_ascii=False, separators=(",", ":")) + "\n",
                               encoding="utf-8")
    stage = ledger["geometry"]["stages"][0]
    print(f"reclassified {stage['cellCount']} cells in {len(stage['units'])} named lowlands")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
