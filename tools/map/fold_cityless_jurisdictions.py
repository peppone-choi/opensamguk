#!/usr/bin/env python3
"""城 없는 縣 관할을 城 있는 관할에 접는다 — han-tiles 의 마지막 단계.

사용자 결정(2026-09-17): 「절대 소속 없는 프로빈스가 있어선 안 된다.」 런타임(MapAdministrativeOwnership)은
城이 선 관할의 省만 점령으로 칠한다. 城이 없는 관할의 省은 시나리오 초기 배정 색만 받고 영원히 주인이
바뀌지 않는다. 그런 관할 가운데 **이미 같은 실체의 城이 서 있는 곳**은 새 城을 세우면 같은 縣이 두 번 서게
되므로(route-node-jurisdiction-claims-v1 excluded 의 followUp FOLD_PROVINCES_INTO_ROUTE_NODE_JURISDICTION),
그 관할의 省을 城 있는 관할에 넘긴다.

규칙(기하를 지어내지 않는다):
  1. 省 칸(owner)·省 행·郡 기하(parentRegionId·parentOwner)는 그대로다. 바뀌는 것은 관할 소속뿐이다 —
     접히는 관할의 provinceIds 가 대상 관할 provinceIds 끝에 붙고, 그 省들의 jurisdictionId 가 대상으로 바뀐다.
  2. 대상은 원장이 적는다. 같은 실체 城의 관할이 접는 관할과 맞닿으면 그 관할, 맞닿지 않으면(汶山) 맞닿은
     城 있는 관할이다. 예외는 같은 縣이 두 번 선 중복(DUPLICATE_ROUTE_NODE)뿐이다 — 北地郡 富平의 寄治 땅은
     떨어져 있어도 富平 관할이다(allowNonAdjacent). 맞닿음은 이 도구가 다시 재서 원장에 적는다.
  3. 접힌 관할 행은 jurisdictionRecords 에서 빠진다. 그 郡의 jurisdictionIds 에서도 빠지고, 郡 治所 관할이었으면
     원장이 적은 새 治所 관할로 바뀐다. 郡에 관할이 하나도 안 남으면(新平·毗陵典農校尉·汶山·章武 — 郡國志 뒤의
     郡) 郡 행은 남기되 jurisdictionIds 가 비고 seatJurisdictionId 가 null 이다. 郡 행·parentRegions·juns 는 인덱스가
     맞물려 있어 지우지 않는다.

같은 단계가 **省 이관**(provinceTransfers)도 싣는다. 城 없던 郡國 밖 취락이 城을 받으면서, 그 취락 관할에 기하
배정(NEAREST_SEAT_WITHIN_PARENT)으로만 묶여 있던 漢 郡縣 땅이 드러난다 — 압록강 하구 省 1304 는 卒本 관할이었지만
사료의 遼東 西安平 자리다. 省 하나를 사료가 지목한 맞닿은 관할로 옮기고, 원래 관할에 省이 남아 있어야 한다.

이 단계는 거점 省 분할(carve_strategic_site_provinces)보다 나중이다. 앞 단계 검사들은 `peel()` 로 이 단계를
먼저 벗긴다. 원장의 `geometry.stages` 가 입력·출력 digest 와 되돌리기에 필요한 행을 핀으로 박는다.

    python3 tools/map/fold_cityless_jurisdictions.py --prepare --output data/map/han-tiles.json
    python3 tools/map/fold_cityless_jurisdictions.py --check
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from tools.map.rebind_misbound_counties import expand, neighbours  # noqa: E402

TILES = ROOT / "data/map/han-tiles.json"
LEDGER = ROOT / "data/curated/han/cityless-jurisdiction-folds-v1.json"
DECISIONS = ROOT / "data/curated/han/cityless-jurisdiction-fold-decisions-v1.json"


def digest(document: dict) -> str:
    return hashlib.sha256(json.dumps(document, ensure_ascii=False, sort_keys=True,
                                     separators=(",", ":")).encode()).hexdigest()


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def shared_edges(document: dict, source_id: str, target_id: str) -> int:
    """두 관할 省 칸이 맞닿은 변의 수(4-이웃)."""
    meta = document["_meta"]
    rows, cols = meta["rows"], meta["cols"]
    owner = expand(document["owner"], rows, cols)
    jurisdiction_of = [row["jurisdictionId"] for row in document["provinceRecords"]]
    count = 0
    for r in range(rows):
        for c in range(cols):
            value = int(owner[r, c])
            if value < 0 or jurisdiction_of[value] != source_id:
                continue
            for nr, nc in neighbours(r, c, rows, cols):
                other = int(owner[nr, nc])
                if other >= 0 and jurisdiction_of[other] == target_id:
                    count += 1
    return count


def apply_folds(source: dict, decisions: list[dict], transfers_in: list[dict] | None = None) -> tuple[dict, dict]:
    document = copy.deepcopy(source)
    jurisdictions = document["jurisdictionRecords"]
    by_id = {row["id"]: row for row in jurisdictions}
    commanderies = {row["id"]: row for row in document["commanderyRecords"]}
    provinces = document["provinceRecords"]
    province_index = {row["id"]: index for index, row in enumerate(provinces)}
    removed, commanderies_before, targets_before, adjacency = [], {}, {}, []
    sources = [row["sourceJurisdictionId"] for row in decisions]
    if len(sources) != len(set(sources)):
        raise ValueError("a jurisdiction may be folded only once")
    for decision in decisions:
        source_id, target_id = decision["sourceJurisdictionId"], decision["targetJurisdictionId"]
        if source_id not in by_id or target_id not in by_id or target_id in sources:
            raise ValueError(f"fold {source_id} -> {target_id} references a missing or folded jurisdiction")
        edges = shared_edges(document, source_id, target_id)
        # 떨어진 대상은 같은 縣의 중복(寄治 땅 — 北地郡 富平)에만 허용한다. 그 밖에는 맞닿은 관할이어야 한다.
        if edges == 0 and not (decision.get("allowNonAdjacent") is True and decision["reason"] == "DUPLICATE_ROUTE_NODE"):
            raise ValueError(f"fold {source_id} -> {target_id}: the two jurisdictions do not touch")
        adjacency.append({"sourceJurisdictionId": source_id, "targetJurisdictionId": target_id, "sharedEdges": edges})
        source_row, target_row = by_id[source_id], by_id[target_id]
        commandery = commanderies[source_row["commanderyId"]]
        commanderies_before.setdefault(commandery["id"], copy.deepcopy(commandery))
        targets_before.setdefault(target_id, list(target_row["provinceIds"]))
        for province_id in source_row["provinceIds"]:
            provinces[province_index[province_id]]["jurisdictionId"] = target_id
        target_row["provinceIds"] = target_row["provinceIds"] + source_row["provinceIds"]
        commandery["jurisdictionIds"] = [value for value in commandery["jurisdictionIds"] if value != source_id]
        if commandery["seatJurisdictionId"] == source_id:
            seat = decision.get("commanderySeatJurisdictionId")
            if commandery["jurisdictionIds"]:
                if seat not in commandery["jurisdictionIds"]:
                    raise ValueError(f"fold {source_id}: commandery {commandery['id']} needs a surviving seat")
                commandery["seatJurisdictionId"] = seat
            else:
                if seat is not None:
                    raise ValueError(f"fold {source_id}: emptied commandery {commandery['id']} cannot name a seat")
                commandery["seatJurisdictionId"] = None
        elif decision.get("commanderySeatJurisdictionId") is not None:
            raise ValueError(f"fold {source_id}: commandery seat is unchanged, do not name one")
        index = jurisdictions.index(source_row)
        removed.append({"index": index, "record": copy.deepcopy(source_row)})
        del jurisdictions[index]
        del by_id[source_id]
    transfers = []
    for transfer in transfers_in or []:
        province_id, source_id, target_id = (transfer["provinceId"], transfer["fromJurisdictionId"],
                                             transfer["toJurisdictionId"])
        record = provinces[province_index[province_id]]
        source_row, target_row = by_id.get(source_id), by_id.get(target_id)
        if record["jurisdictionId"] != source_id or source_row is None or target_row is None:
            raise ValueError(f"transfer {province_id}: {source_id} -> {target_id} does not match the document")
        if province_id == source_row["seatPlaceId"] or len(source_row["provinceIds"]) < 2:
            raise ValueError(f"transfer {province_id}: the source jurisdiction must keep its seat province")
        before_source, before_target = list(source_row["provinceIds"]), list(target_row["provinceIds"])
        record["jurisdictionId"] = target_id
        source_row["provinceIds"] = [value for value in source_row["provinceIds"] if value != province_id]
        target_row["provinceIds"] = target_row["provinceIds"] + [province_id]
        touching = _province_touches(document, province_id, before_target)
        if not touching:
            raise ValueError(f"transfer {province_id}: the province does not touch {target_id}")
        transfers.append({"provinceId": province_id, "fromJurisdictionId": source_id, "toJurisdictionId": target_id,
                          "sourceProvinceIdsBefore": before_source, "targetProvinceIdsBefore": before_target,
                          "sharedEdges": touching})
    document["_meta"]["counts"]["jurisdictions"] = len(jurisdictions)
    return document, {"removedJurisdictions": removed, "commanderiesBefore": list(commanderies_before.values()),
                      "targetProvinceIdsBefore": targets_before, "adjacency": adjacency, "transfers": transfers}


def _province_touches(document: dict, province_id: str, other_province_ids: list[str]) -> int:
    meta = document["_meta"]
    rows, cols = meta["rows"], meta["cols"]
    owner = expand(document["owner"], rows, cols)
    index = {row["id"]: i for i, row in enumerate(document["provinceRecords"])}
    target, others = index[province_id], {index[value] for value in other_province_ids}
    count = 0
    for r in range(rows):
        for c in range(cols):
            if int(owner[r, c]) != target:
                continue
            count += sum(1 for nr, nc in neighbours(r, c, rows, cols) if int(owner[nr, nc]) in others)
    return count


def stage_for(document: dict, ledger: dict) -> dict | None:
    fingerprint = digest(document)
    for stage in ledger.get("geometry", {}).get("stages", []):
        if stage["outputDocumentSha256"] == fingerprint:
            return stage
    return None


def restore_document(document: dict, ledger: dict) -> dict:
    stage = stage_for(document, ledger)
    if stage is None:
        raise ValueError("document is not a pinned cityless-jurisdiction fold output")
    restored = copy.deepcopy(document)
    province_by_id = {row["id"]: row for row in restored["provinceRecords"]}
    jurisdiction_by_id = {row["id"]: row for row in restored["jurisdictionRecords"]}
    for transfer in reversed(stage.get("transfers", [])):
        province_by_id[transfer["provinceId"]]["jurisdictionId"] = transfer["fromJurisdictionId"]
        jurisdiction_by_id[transfer["fromJurisdictionId"]]["provinceIds"] = list(transfer["sourceProvinceIdsBefore"])
        jurisdiction_by_id[transfer["toJurisdictionId"]]["provinceIds"] = list(transfer["targetProvinceIdsBefore"])
    jurisdictions = restored["jurisdictionRecords"]
    for entry in reversed(stage["removedJurisdictions"]):
        jurisdictions.insert(entry["index"], copy.deepcopy(entry["record"]))
    by_id = {row["id"]: row for row in jurisdictions}
    for target_id, province_ids in stage["targetProvinceIdsBefore"].items():
        by_id[target_id]["provinceIds"] = list(province_ids)
    province_index = {row["id"]: index for index, row in enumerate(restored["provinceRecords"])}
    for entry in stage["removedJurisdictions"]:
        for province_id in entry["record"]["provinceIds"]:
            restored["provinceRecords"][province_index[province_id]]["jurisdictionId"] = entry["record"]["id"]
    before = {row["id"]: row for row in stage["commanderiesBefore"]}
    restored["commanderyRecords"] = [copy.deepcopy(before.get(row["id"], row)) for row in restored["commanderyRecords"]]
    restored["_meta"]["counts"] = copy.deepcopy(stage["inputCounts"])
    if digest(restored) != stage["inputDocumentSha256"]:
        raise ValueError("restored document differs from the pinned cityless-jurisdiction fold input")
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
    rebuilt, _ = apply_folds(document, ledger["decisions"], ledger.get("provinceTransfers"))
    return rebuilt


def build_stage(source: dict, decisions_document: dict) -> tuple[dict, dict]:
    decisions = decisions_document["folds"]
    transfers = decisions_document.get("provinceTransfers", [])
    document, result = apply_folds(source, decisions, transfers)
    stage = {"inputDocumentSha256": digest(source), "outputDocumentSha256": digest(document),
             "inputCounts": copy.deepcopy(source["_meta"]["counts"]), **result}
    ledger = {
        "schemaVersion": 1,
        "ledgerId": "cityless-jurisdiction-folds-v1",
        "authority": decisions_document["authority"],
        "inputs": {"decisions": {"path": DECISIONS.relative_to(ROOT).as_posix(), "sha256": _sha256(DECISIONS)}},
        "decisions": decisions,
        "provinceTransfers": transfers,
        "geometry": {"stages": [stage]},
    }
    return document, ledger


def orphan_jurisdictions(document: dict, city_jurisdictions: set[str]) -> list[str]:
    """城이 서지 않은 관할 id — 이 단계와 경로 노드 선정이 끝나면 비어야 한다."""
    return sorted(row["id"] for row in document["jurisdictionRecords"] if row["id"] not in city_jurisdictions)


def check(document: dict, ledger: dict) -> list[str]:
    stage = stage_for(document, ledger)
    if stage is None:
        return ["han-tiles.json is not the reviewed cityless-jurisdiction fold output"]
    problems = []
    if ledger["inputs"]["decisions"]["sha256"] != _sha256(DECISIONS):
        problems.append(f"{ledger['inputs']['decisions']['path']} changed since the fold was prepared")
    reviewed = json.loads(DECISIONS.read_text(encoding="utf-8"))
    decisions, transfers = reviewed["folds"], reviewed.get("provinceTransfers", [])
    if decisions != ledger["decisions"] or transfers != ledger.get("provinceTransfers", []):
        problems.append("fold ledger decisions differ from the reviewed decision file")
    rebuilt, result = apply_folds(restore_document(document, ledger), decisions, transfers)
    for key in ("removedJurisdictions", "commanderiesBefore", "targetProvinceIdsBefore", "adjacency", "transfers"):
        if result[key] != stage[key]:
            problems.append(f"cityless-jurisdiction fold {key} differs from the reviewed stage")
    if digest(rebuilt) != stage["outputDocumentSha256"]:
        problems.append("re-applied cityless-jurisdiction fold does not reproduce han-tiles.json")
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
    document, ledger = build_stage(source, json.loads(DECISIONS.read_text(encoding="utf-8")))
    if args.prepare:
        LEDGER.write_text(json.dumps(ledger, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if args.output:
        args.output.write_text(json.dumps(document, ensure_ascii=False, separators=(",", ":")) + "\n",
                               encoding="utf-8")
    stage = ledger["geometry"]["stages"][0]
    emptied = Counter(row["id"] for row in document["commanderyRecords"] if not row["jurisdictionIds"])
    print(f"folded {len(stage['removedJurisdictions'])} jurisdictions · emptied commanderies {len(emptied)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
