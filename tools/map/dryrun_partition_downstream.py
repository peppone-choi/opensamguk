#!/usr/bin/env python3
"""S1.5 건식 실행 — ★ 지리 재분할 산출을 하류에 흘려 보고 **세기만 한다** (GH #806, 고치지 않는다).

저장소를 건드리지 않는다. `git archive HEAD` 로 저장소 밖 샌드박스 두 벌(control·trial)을 만들고:

  control  아무것도 안 바꾸고 같은 검사를 돈다. 여기서 빨간 검사는 「이 환경에서 원래 못 도는 것」
           (gitignored 입력 부재 등)이라 파손 수에서 뺀다 — 기준선 없는 빨강은 증거가 아니다.
  trial    ★ 분할 → 거점 분할 --prepare → 접기(결정별로 따로 시도) → 저지 지형 --prepare 를
           샌드박스 안 han-tiles 에 얹고, check_han_tiles_coupled 의 결합 목록 전부 + 단계 검사를 돈다.

  python3 tools/map/dryrun_partition_downstream.py --sandbox /tmp/opensamguk-806-dryrun

산출: <sandbox>/summary.json 과 표준 출력. 샌드박스는 저장소 밖이어야 한다.
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from tools.map.check_han_tiles_coupled import COUPLED  # noqa: E402

# 결합 목록 밖이지만 han-tiles 를 읽는 검사(단계 사슬·감사·핀 원장).
EXTRA_CHECKS = (
    ("stage-province-fragments", ("python3", "tools/map/adjudicate_han_province_fragments.py", "--check")),
    ("parent-reconciliation", ("python3", "tools/map/build_han_parent_reconciliation.py", "--check")),
    ("owner-locality-Q7", ("python3", "-m", "unittest", "tools/map/tests/test_han_tiles_owner_locality.py")),
    ("tiles-contract-test", ("python3", "-m", "unittest", "tools/map/tests/test_han_tiles_contract.py")),
)
FOLD_PROBE = r"""
import json, sys
sys.path.insert(0, '.')
from tools.map import fold_cityless_jurisdictions as F
doc = json.load(open(sys.argv[1]))
decisions = json.load(open('data/curated/han/cityless-jurisdiction-fold-decisions-v1.json'))
out = {'folds': [], 'provinceTransfers': [], 'jurisdictionCommanderyMoves': []}
good = []
for row in decisions['folds']:
    key = f"{row['sourceJurisdictionId']}→{row['targetJurisdictionId']}"
    try:
        F.apply_folds(doc, [row], None, None); good.append(row); out['folds'].append({'row': key, 'ok': True})
    except Exception as error:
        out['folds'].append({'row': key, 'ok': False, 'error': str(error)[:300]})
for name, index in (('provinceTransfers', 2), ('jurisdictionCommanderyMoves', 3)):
    for row in decisions[name]:
        args = [doc, [], None, None]; args[index] = [row]
        try:
            F.apply_folds(*args); out[name].append({'row': row, 'ok': True})
        except Exception as error:
            out[name].append({'row': {k: row[k] for k in list(row)[:3]}, 'ok': False, 'error': repr(error)[:300]})
partial, _ = F.apply_folds(doc, good, None, None)
json.dump(partial, open(sys.argv[2], 'w'), ensure_ascii=False, separators=(',', ':'))
out['appliedFolds'] = len(good)
print(json.dumps(out, ensure_ascii=False))
"""
MEASURE = r"""
import json, sys, collections
sys.path.insert(0, '.')
import numpy as np
from tools.map import partition_counties_by_location as P
from tools.map.measure_province_seat_offset import gate, measure, percentiles
doc = json.load(open(sys.argv[1]))
exceptions = frozenset(json.loads(sys.argv[2]))
rows = measure(doc)
measured = [r for r in rows if r.get('area')]
raw, excused = gate(rows), gate(rows, exceptions)
owner = P.expand(doc['owner'], doc['_meta']['rows'], doc['_meta']['cols'])
areas = np.bincount(owner[owner >= 0], minlength=len(doc['provinceRecords']))
violations = P.check_area(doc)
print(json.dumps({
    'seatProvinces': len(measured), 'provinces': len(doc['provinceRecords']),
    'jurisdictions': len(doc['jurisdictionRecords']), 'adjCounty': len(doc['adjacency']['county']),
    'trueCellInProvince': sum(r['trueCellInProvince'] for r in measured),
    'trueCellInJurisdiction': sum(r['trueCellInJurisdiction'] for r in measured),
    'trueCellInParent': sum(r['trueCellInParent'] for r in measured),
    'Q1': len(raw['Q1']), 'Q1withExceptionRows': len(excused['Q1']),
    'Q1residualKinds': dict(collections.Counter(r.get('kind') for r in excused['Q1'])),
    'Q1b': len(raw['Q1b']),
    'nearestCell': percentiles([r['nearestCell'] for r in measured]),
    'seedOffset': percentiles([r['seedOffset'] for r in measured]),
    'centroidOffset': percentiles([r['centroidOffset'] for r in measured]),
    'Q4violations': len(violations), 'Q4belowMin': sum(int(v.rsplit(' ', 1)[1]) < P.MIN_AREA for v in violations),
    'Q4aboveMax': sum(int(v.rsplit(' ', 1)[1]) > P.MAX_AREA for v in violations), 'Q4rows': violations,
    'areaMedian': float(np.median(areas)), 'areaMax': int(areas.max()),
    'lowlandZeroSeatProvinces': sum(r['lowlandCells'] == 0 for r in measured),
}, ensure_ascii=False))
"""


def run(cmd, cwd: Path, timeout: int = 1800) -> dict:
    start = time.time()
    try:
        done = subprocess.run(list(cmd), cwd=cwd, capture_output=True, text=True, timeout=timeout)
        rc, out, err = done.returncode, done.stdout, done.stderr
    except subprocess.TimeoutExpired:
        rc, out, err = 124, "", "TIMEOUT"
    lines = [line for line in (err.strip().splitlines() or out.strip().splitlines()) if line.strip()]
    return {"rc": rc, "seconds": round(time.time() - start, 1), "tail": (lines[-1][:400] if lines else ""),
            "stdout": out, "stderr": err}


def make_sandbox(path: Path) -> None:
    if path.exists():
        shutil.rmtree(path)
    path.mkdir(parents=True)
    archive = subprocess.Popen(["git", "-C", str(ROOT), "archive", "HEAD"], stdout=subprocess.PIPE)
    subprocess.run(["tar", "-x", "-C", str(path)], stdin=archive.stdout, check=True)
    archive.wait()
    # 아직 커밋 안 된 작업 트리의 도구·원장도 같이 본다.
    changed = subprocess.run(["git", "-C", str(ROOT), "ls-files", "-m", "-o", "--exclude-standard", "tools", "data/curated"],
                             capture_output=True, text=True, check=True).stdout.split("\n")
    for name in filter(None, changed):
        (path / name).parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / name, path / name)


def checks(cwd: Path) -> dict:
    out = {}
    for entry in COUPLED:
        out[entry.key] = run(entry.check, cwd)
    for key, cmd in EXTRA_CHECKS:
        out[key] = run(cmd, cwd)
    return out


def territory_counts(result: dict) -> dict:
    text = result["stdout"] + result["stderr"]
    head = re.search(r"disconnected components (\d+) \| adjudicated (\d+)", text)
    kinds: dict[str, int] = {}
    for line in text.splitlines():
        match = re.match(r"\s+([A-Z_]{4,})\b", line)
        if match:
            kinds[match.group(1)] = kinds.get(match.group(1), 0) + 1
    return {"components": int(head.group(1)) if head else None, "stillAdjudicated": int(head.group(2)) if head else None,
            "problemRows": dict(sorted(kinds.items()))}


def id_references(old: dict, new: dict) -> dict:
    old_ids = [row["id"] for row in old["provinceRecords"]]
    new_ids = [row["id"] for row in new["provinceRecords"]]
    retired = set(old_ids) - set(new_ids)
    new_index = {pid: i for i, pid in enumerate(new_ids)}
    files = subprocess.run(["git", "-C", str(ROOT), "grep", "-l", "-E", "DIRECT-PARENT-[0-9]{4}-[0-9a-f]{12}", "--", ".",
                            ":!data/map/han-tiles.json"], capture_output=True, text=True).stdout.split("\n")
    per_file = {}
    for name in filter(None, files):
        ids = set(re.findall(r"DIRECT-PARENT-\d{4}-[0-9a-f]{12}", (ROOT / name).read_text(encoding="utf-8", errors="ignore")))
        if ids & retired:
            per_file[name] = {"directIds": len(ids), "retired": len(ids & retired)}
    links = json.loads((ROOT / "data/map/han-commandery-supply-links-v1.json").read_text(encoding="utf-8"))
    link_rows = next(v for v in links.values() if isinstance(v, list) and v and isinstance(v[0], dict))
    return {
        "provinceIdsBefore": len(old_ids), "provinceIdsAfter": len(new_ids), "retired": len(retired),
        "added": len(set(new_ids) - set(old_ids)),
        "retiredNonDirect": sorted(pid for pid in retired if not pid.startswith("DIRECT-")),
        "survivingIdsWithChangedIndex": sum(1 for i, pid in enumerate(old_ids) if pid in new_index and new_index[pid] != i),
        "filesReferencingRetiredDirectIds": dict(sorted(per_file.items())),
        "supplyLinkRows": len(link_rows), "supplyLinkRowsNote": "from/toProvinceIndex 는 인덱스 핀이다 — 省 순서가 바뀌면 전 행 재측정",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--sandbox", type=Path, required=True)
    args = parser.parse_args()
    sandbox = args.sandbox.resolve()
    if ROOT == sandbox or ROOT in sandbox.parents:
        raise SystemExit("샌드박스는 저장소 밖이어야 한다")
    control, trial = sandbox / "control", sandbox / "trial"
    make_sandbox(control)
    make_sandbox(trial)
    summary: dict = {"head": subprocess.run(["git", "-C", str(ROOT), "rev-parse", "HEAD"], capture_output=True,
                                            text=True).stdout.strip()}
    control_checks = checks(control)

    scratch = trial / "scratch"
    scratch.mkdir()
    stages = {}
    stages["partition"] = run(("python3", "tools/map/partition_counties_by_location.py", "--output", "scratch/p0.json"), trial)
    stages["partitionAgain"] = run(("python3", "tools/map/partition_counties_by_location.py", "--output", "scratch/p0b.json"), trial)
    summary["partitionByteIdenticalAcrossRuns"] = (scratch / "p0.json").read_bytes() == (scratch / "p0b.json").read_bytes()
    stages["carve"] = run(("python3", "tools/map/carve_strategic_site_provinces.py", "--source", "scratch/p0.json",
                           "--prepare", "--output", "scratch/p1.json"), trial)
    stages["foldAllAtOnce"] = run(("python3", "tools/map/fold_cityless_jurisdictions.py", "--source", "scratch/p1.json",
                                   "--output", "scratch/p2-all.json"), trial)
    fold_probe = run(("python3", "-c", FOLD_PROBE, "scratch/p1.json", "scratch/p2.json"), trial)
    stages["foldPerDecision"] = fold_probe
    stages["lowland"] = run(("python3", "tools/map/reclassify_han_lowland_terrain.py", "--source", "scratch/p2.json",
                             "--source-is-upstream", "--prepare", "--output", "scratch/p3.json"), trial)
    final = scratch / "p3.json"
    if not final.is_file():
        summary["stages"] = {k: {"rc": v["rc"], "tail": v["tail"]} for k, v in stages.items()}
        print(json.dumps(summary, ensure_ascii=False, indent=1))
        return 1
    shutil.copy2(final, trial / "data/map/han-tiles.json")
    trial_checks = checks(trial)

    report = json.loads((scratch / "p0.report.json").read_text(encoding="utf-8"))
    exception_ids = json.dumps([row["jurisdictionId"] for row in report["seedExceptions"]])
    carve_stage = json.loads((trial / "data/curated/han/strategic-site-province-carves-v1.json").read_text())["geometry"]["stages"][0]
    carve_before = json.loads((control / "data/curated/han/strategic-site-province-carves-v1.json").read_text())["geometry"]["stages"][0]

    def measured(path: str, cwd: Path) -> dict:
        result = run(("python3", "-c", MEASURE, path, exception_ids), cwd)
        return json.loads(result["stdout"]) if result["rc"] == 0 else {"error": result["tail"]}

    summary.update({
        "stages": {k: {"rc": v["rc"], "seconds": v["seconds"], "tail": v["tail"]} for k, v in stages.items()},
        "measure": {
            "committed": measured("data/map/han-tiles.json", control),
            "partitionOnly": measured("scratch/p0.json", trial),
            "afterCarveFoldLowland": measured("scratch/p3.json", trial),
        },
        "partitionReport": {**report["counts"],
                            "seedExceptionClasses": _count(report["seedExceptions"], "class"),
                            "componentRows": len(report["components"]),
                            "minAreaBorrowedRows": len(report["minAreaBorrowed"]),
                            "minAreaUnsatisfied": [r["nameCh"] for r in report["minAreaBorrowed"] if not r["satisfied"]],
                            "subdividedJurisdictions": len(report["subdivisions"]),
                            "largestSubdivisions": sorted(((r["nameCh"], r["componentCells"], r["pieces"])
                                                           for r in report["subdivisions"]), key=lambda r: -r[1])[:6]},
        "carve": {"placedBefore": len(carve_before["placements"]), "placedAfter": len(carve_stage["placements"]),
                  "excludedAfter": [(r["nameHan"], r["reason"], r.get("donorCellCount")) for r in carve_stage["excluded"]],
                  "donorTooSmall": sum(r["reason"] == "DONOR_TOO_SMALL" for r in carve_stage["excluded"]),
                  "displacedBefore": sum("displacedFrom" in r for r in carve_before["placements"]),
                  "displacedAfter": sum("displacedFrom" in r for r in carve_stage["placements"])},
        "fold": json.loads(fold_probe["stdout"]) if fold_probe["rc"] == 0 else {"error": fold_probe["tail"]},
        "territoryLedger": {"control": territory_counts(control_checks["territory-disconnection-ledger"]),
                            "trial": territory_counts(trial_checks["territory-disconnection-ledger"])},
        "ids": id_references(json.loads((control / "data/map/han-tiles.json").read_text()), json.loads(final.read_text())),
        "checks": {key: {"control": control_checks[key]["rc"], "trial": trial_checks[key]["rc"],
                         "seconds": trial_checks[key]["seconds"], "tail": trial_checks[key]["tail"]}
                   for key in trial_checks},
    })
    broken = [k for k, v in summary["checks"].items() if v["control"] == 0 and v["trial"] != 0]
    summary["brokenByPartition"] = broken
    summary["notRunnableHere"] = [k for k, v in summary["checks"].items() if v["control"] != 0]
    summary["stillGreen"] = [k for k, v in summary["checks"].items() if v["control"] == 0 and v["trial"] == 0]
    (sandbox / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(json.dumps({k: summary[k] for k in ("brokenByPartition", "notRunnableHere", "stillGreen")}, ensure_ascii=False, indent=1))
    print(f"summary: {sandbox / 'summary.json'}")
    return 0


def _count(rows: list[dict], key: str) -> dict:
    out: dict[str, int] = {}
    for row in rows:
        out[row[key]] = out.get(row[key], 0) + 1
    return dict(sorted(out.items()))


if __name__ == "__main__":
    raise SystemExit(main())
