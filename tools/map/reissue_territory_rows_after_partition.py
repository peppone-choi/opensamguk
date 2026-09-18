#!/usr/bin/env python3
"""영토 단절 원장을 ★ 지리 재분할(GH #806) 뒤 문서에 대해 **재발행**한다 — 1회용 이관 도구.

spec §4: 「★ 앞 행은 옛 문서에 대해 그대로 성립해야 하고, ★ 뒤는 새 행이다. 옛 행의 근거는 같은 단위의 새 행으로
옮기되 자동 승계 금지(검토 표시)」. 이 도구는 판정을 **짓지 않는다.** 하는 일은 넷뿐이다:

  CARRIED_EXACT        조각(키·칸 수·구성원·이름)이 그대로인 옛 행 — 손대지 않고 옮긴다.
  CARRIED_MEMBERS      칸 집합은 같고(郡 래스터는 ★ 가 안 건드린다) 구성 縣만 바뀐 행 — 구성원 필드만 새로 읽고
                       `partitionCarry.pendingReview = true` 를 단다. 판정·근거·표결은 옛 행 그대로다.
  CARRIED_SAME_CELLS   칸 집합이 같은데 단위(관할)가 바뀐 조각(섬이 가장 가까운 다른 縣으로 감) — 옛 행의 판정·근거를
                       새 단위 행으로 옮기고 pendingReview 를 단다.
  NEW_UNVERIFIED       옛 행과 칸 집합이 맞는 것이 없는 새 조각 — 격자에서 기계로 읽히는 것만 적는다: 육지 이웃이 없고
                       물로 둘러싸였으면 WATER_SEPARATED, 아니면 GEOMETRY_DEFECT(郡 마스크가 갈라 놓은 조각 — spec 규칙 3
                       의 「구조적 다성분」). 둘 다 review.state = UNVERIFIED · confidence LOW 다. 사료 판정이 아니다.

출력: data/curated/han/territory-disconnection-adjudications-partition-v1.json (★ 출력 = 거점 분할 앞 문서 기준).
거점 분할·접기 단계의 투영은 감사 도구가 그대로 한다. 사람이 행을 고친 뒤에는 이 도구를 다시 돌리지 않는다
(--force 없이는 기존 파일을 덮어쓰지 않는다).

  python3 tools/map/reissue_territory_rows_after_partition.py [--force]
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from tools.map import audit_territory_disconnections as audit  # noqa: E402
from tools.map import partition_counties_by_location as partition  # noqa: E402

OUTPUT = ROOT / "data/curated/han/territory-disconnection-adjudications-partition-v1.json"
STEPS4 = ((1, 0), (-1, 0), (0, 1), (0, -1))


def _grids(document: dict) -> dict[str, np.ndarray]:
    meta = document["_meta"]
    owner = partition.expand(document["owner"], meta["rows"], meta["cols"])
    parent = partition.expand(document["parentOwner"], meta["rows"], meta["cols"])
    index = {row["id"]: i for i, row in enumerate(document["jurisdictionRecords"])}
    lookup = np.asarray([index[row["jurisdictionId"]] for row in document["provinceRecords"]] + [-1])
    return {"COMMANDERY": parent, "JURISDICTION": lookup[owner]}


def _cells(grids: dict[str, np.ndarray], kind: str, key: str) -> frozenset[tuple[int, int]]:
    grid = grids[kind]
    col, row = (int(v) for v in key.split("@")[1].split(":"))
    value, seen, stack = grid[row, col], {(row, col)}, [(row, col)]
    while stack:
        r, c = stack.pop()
        for dr, dc in STEPS4:
            n = (r + dr, c + dc)
            if 0 <= n[0] < grid.shape[0] and 0 <= n[1] < grid.shape[1] and n not in seen and grid[n] == value:
                seen.add(n)
                stack.append(n)
    return frozenset(seen)


def _bbox(cells) -> str:
    rows = [r for r, _ in cells]
    cols = [c for _, c in cells]
    return f"col {min(cols)}–{max(cols)} / row {min(rows)}–{max(rows)}"


def _new_row(comp: dict, cells) -> dict:
    boundary = comp["negativeBoundary"]
    water = (not comp["landNeighbourIds"] and set(boundary) & audit.WATER_TERRAIN
             and set(boundary) <= audit.WATER_BOUNDARY_ALLOWED)
    grid_ref = (f"map:han-tiles(★ 뒤) 조각 {comp['componentKey']} {comp['cellCount']}칸 {_bbox(cells)}, "
                f"육지 이웃 {comp['landNeighbourIds'] or '없음'}, 음수 경계 {boundary}")
    row = {
        "unitKind": comp["unitKind"], "unitId": comp["unitId"], "unitNameCh": comp["unitNameCh"],
        "componentKey": comp["componentKey"], "cellCount": comp["cellCount"], "memberIds": comp["memberIds"],
        "memberNamesCh": comp["memberNamesCh"], "holdsSeat": comp["holdsSeat"],
        "verdict": "WATER_SEPARATED" if water else "GEOMETRY_DEFECT", "confidence": "LOW",
        "effectiveFrom": None, "effectiveTo": None,
        "ifRule": "WATER_ROUTE_ONLY" if water else "DEFECT_PRESERVE_PENDING_GEOMETRY_PR",
        "evidenceRefs": [grid_ref],
        "rationale": ("지리 재분할(GH #806)로 새로 생긴 조각이다. 격자에서 기계로 읽히는 사실만 적었다 — "
                      + ("육지 이웃이 없고 경계가 전부 물이다." if water else
                         "같은 단위의 본체와 떨어져 있고 육지 이웃이 있다. 郡 래스터(parentOwner)가 갈라 놓은 구조적 조각이다"
                         "(spec 2026-09-17-province-geography-first §3 규칙 3).")
                      + " 사료 판정이 아니며 적대적 검증을 거치지 않았다."),
        "review": {"state": "UNVERIFIED", "judgedBy": "reissue_territory_rows_after_partition (mechanical)",
                   "originalVerdict": "WATER_SEPARATED" if water else "GEOMETRY_DEFECT", "voteCount": 1,
                   "unavailableVerifiers": 3,
                   "votes": [{"lens": "mechanical", "refuted": False,
                              "reason": "격자 재계산만 했다(칸 수·육지 이웃·경계 지형). source·geography·chronology 렌즈는 돌지 않았다."}]},
        "partitionCarry": {"mode": "NEW_UNVERIFIED", "from": None, "pendingReview": True},
    }
    if not water:
        row["defectNote"] = ("★ 지리 재분할은 郡 마스크 안에서만 縣 경계를 자른다. 이 조각은 제 단위의 본체와 郡 마스크·물로 "
                             "끊겨 있다. county-location-partition-v1 의 components 행과 같은 사실이다.")
    return row


def build(new_tiles: dict, ledger: dict, later_stage_rows: list[dict] = ()) -> dict:
    staged = partition.peel_later_stages(new_tiles)          # ★ 출력(거점 분할 앞)
    if partition.stage_for(staged, json.loads(partition.LEDGER.read_text(encoding="utf-8"))) is None:
        raise SystemExit("han-tiles 에 ★ 단계가 얹혀 있지 않다")
    old_staged, _ = partition.peel(staged)                   # ★ 입력 = 옛 사슬의 같은 자리(입력 blob 으로 복원)
    old_rows, _ = audit._reviewed_rows(old_staged, ledger, audit.validate_ledger(ledger))
    old_grids, new_grids = _grids(old_staged), _grids(staged)
    inventory = {row["componentKey"]: row for row in audit.inventory(staged)}
    old_cells = {row["componentKey"]: _cells(old_grids, row["unitKind"], row["componentKey"]) for row in old_rows}
    by_cells = {}
    for row in old_rows:
        by_cells.setdefault((row["unitKind"], old_cells[row["componentKey"]]), row)
    # 접기 결정 원장의 territoryAdjudications 중 郡 조각 행도 후보다: 郡 래스터(parentOwner)는 단계와 무관하게 같으므로
    # 칸 집합으로 맞출 수 있다(北地郡 633칸 조각 — 옛 사슬에서는 접기 단계에서야 조각이 됐다). 관할 조각은 접기 뒤
    # 관할 격자 기준이라 여기서 맞추지 않는다.
    for row in later_stage_rows:
        if row["unitKind"] == "COMMANDERY":
            by_cells.setdefault((row["unitKind"], _cells(old_grids, "COMMANDERY", row["componentKey"])), row)
    out, counts = [], {"CARRIED_EXACT": 0, "CARRIED_MEMBERS": 0, "CARRIED_SAME_CELLS": 0, "NEW_UNVERIFIED": 0}
    old_by_key = {row["componentKey"]: row for row in old_rows}
    for key in sorted(inventory):
        comp = inventory[key]
        cells = _cells(new_grids, comp["unitKind"], key)
        old = old_by_key.get(key)
        if old is not None and old_cells[key] != cells:
            old = None
        mode = None
        if old is None:
            old = by_cells.get((comp["unitKind"], cells))
            mode = "CARRIED_SAME_CELLS" if old is not None else None
        if old is None:
            out.append(_new_row(comp, cells))
            counts["NEW_UNVERIFIED"] += 1
            continue
        fields = ("unitKind", "unitId", "unitNameCh", "componentKey", "cellCount", "memberIds", "memberNamesCh", "holdsSeat")
        changed = [f for f in fields if old[f] != comp[f]]
        row = {**old, **{f: comp[f] for f in fields}}
        if changed:
            mode = mode or "CARRIED_MEMBERS"
            row["partitionCarry"] = {"mode": mode, "from": old["componentKey"], "changedFields": changed,
                                     "pendingReview": True}
        else:
            mode = "CARRIED_EXACT"
        counts[mode] += 1
        out.append(row)
    retired = sorted(key for key in old_by_key
                     if key not in inventory and (old_by_key[key]["unitKind"], old_cells[key]) not in
                     {(inventory[k]["unitKind"], _cells(new_grids, inventory[k]["unitKind"], k)) for k in inventory})
    return {
        "schemaVersion": 1,
        "ledgerId": "territory-disconnection-adjudications-partition-v1",
        "authority": "spec 2026-09-17-province-geography-first §4 「영토 단절 원장 — 재심사, 자동 승계 금지(검토 표시)」",
        "generator": "tools/map/reissue_territory_rows_after_partition.py (1회용 이관 — 사람이 고친 뒤에는 다시 돌리지 않는다)",
        "scope": "★ 지리 재분할 출력(거점 분할 앞 문서)의 단절 조각 전수. ★ 앞 문서의 행은 territory-disconnection-adjudications-v1 에 그대로 있다.",
        "partitionStageOutputSha256": partition.digest(staged),
        "counts": {**counts, "rows": len(out), "pendingReview": sum(1 for r in out if r.get("partitionCarry", {}).get("pendingReview"))},
        "retiredComponentKeys": [{"componentKey": key, "unitNameCh": old_by_key[key]["unitNameCh"],
                                  "cellCount": old_by_key[key]["cellCount"], "verdict": old_by_key[key]["verdict"]}
                                 for key in retired],
        "adjudications": out,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--force", action="store_true")
    parser.add_argument("--fold-rows-rev", default="e20f3f06", help="★ 앞 접기 결정 원장을 읽을 git 리비전")
    args = parser.parse_args()
    if OUTPUT.is_file() and not args.force:
        raise SystemExit(f"{OUTPUT.relative_to(ROOT)} 가 이미 있다 — 사람 판정을 덮어쓰지 않는다(--force)")
    ledger = json.loads(audit.DEFAULT_LEDGER.read_text(encoding="utf-8"))
    from tools.map import fold_cityless_jurisdictions as folding
    # 접기 결정 원장은 이 이관과 함께 고쳐진다 — 옛 행은 git 의 이전 판에서 읽는다.
    import subprocess
    previous = subprocess.run(["git", "-C", str(ROOT), "show", f"{args.fold_rows_rev}:{folding.DECISIONS.relative_to(ROOT).as_posix()}"],
                              capture_output=True, text=True, check=True).stdout
    document = build(json.loads(partition.TILES.read_text(encoding="utf-8")), ledger,
                     json.loads(previous)["territoryAdjudications"])
    audit.validate_ledger({**ledger, "adjudications": document["adjudications"]})
    OUTPUT.write_text(json.dumps(document, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(json.dumps(document["counts"], ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
