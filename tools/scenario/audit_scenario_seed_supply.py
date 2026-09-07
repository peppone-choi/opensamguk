#!/usr/bin/env python3
"""시나리오 개시 시점의 보급 절단을 **절대 축**으로 감사한다.

**왜 별도의 게이트인가.** `audit_han_supply_disagreements.py` 는 CityConst 그래프와 spatial
프로빈스 망이라는 **두 모델의 불일치**만 보고 실패한다. 두 모델이 「이 城은 끊겼다」에 함께
동의하면(`BOTH_UNSUPPLIED`) 아무 비용 없이 통과한다. 실제로 그 상태로 초록이었다:

    scenario_1020 (프로덕션 라이브) — 공융의 北海國 16城 중 **14城** 이 개시부터 절단.

개시부터 끊긴 城은 플레이어가 무엇을 하든 잃도록 예정된 城이다. 매턴 10% 쇠퇴 →
민심 30 미만 → 중립화 → 그 도시 관직자 강등. 그걸 아무도 보고 있지 않았다.

**모델은 빌려 쓴다.** 절단 판정은 반드시 엔진이 실제로 쓰는 망이라야 한다(han-world-v3 는
spatial 프로빈스 망이다). 그래서 여기서 BFS 를 다시 짜지 않고 위 감사의
`--include-unsupplied` 출력을 읽는다 — CityConst 그래프로 따로 세면 v3 에서 61城이 거짓
경보로 뜬다(실측).

**임계값이 아니라 실측 기준선이다.** 「몇 % 이하면 통과」 같은 수를 지어내지 않는다.
`data/curated/han/scenario-seed-supply-baseline-v1.json` 에 시나리오별 **현재 실측치**를
핀으로 박고, 그보다 나빠지면 실패한다. 좋아지면 핀을 낮추라고 알려준다(정직한 기준선은
줄어들기만 해야 한다).

사용:
    python3 tools/scenario/audit_scenario_seed_supply.py --check
    python3 tools/scenario/audit_scenario_seed_supply.py --json
    python3 tools/scenario/audit_scenario_seed_supply.py --write-baseline
"""

from __future__ import annotations

import argparse
import json
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

import audit_han_supply_disagreements as disagreements

ROOT = Path(__file__).resolve().parents[2]
BASELINE_PATH = ROOT / "data/curated/han/scenario-seed-supply-baseline-v1.json"
SCENARIO_DIR = ROOT / "infra/src/main/resources/scenario"


def _owner_by_city(scenario_code: int) -> dict[int, str]:
    scenario = json.loads(
        (SCENARIO_DIR / f"scenario_{scenario_code}.json").read_text(encoding="utf-8")
    )
    owners: dict[int, str] = {}
    for nation in scenario.get("nation", []):
        if len(nation) > 8 and isinstance(nation[8], list):
            for city_id in nation[8]:
                owners[city_id] = nation[0]
    return owners


def measure(map_name: str = "han-world-v3") -> tuple[dict[int, int], list[dict[str, Any]]]:
    result = disagreements.audit_repository(map_name, include_unsupplied=True)
    cut = [row for row in result.rows if row["verdict"] == "BOTH_UNSUPPLIED"]
    per_scenario = Counter(row["scenarioCode"] for row in cut)

    owners_cache: dict[int, dict[int, str]] = {}
    detail: list[dict[str, Any]] = []
    by_nation: dict[tuple[int, str], int] = defaultdict(int)
    for row in cut:
        code = row["scenarioCode"]
        owners = owners_cache.setdefault(code, _owner_by_city(code))
        nation = owners.get(row["runtimeCityId"], "(중립)")
        by_nation[(code, nation)] += 1
        detail.append({"scenarioCode": code, "cityId": row["runtimeCityId"], "nation": nation})

    for (code, nation), count in sorted(by_nation.items()):
        for entry in detail:
            if entry["scenarioCode"] == code and entry["nation"] == nation:
                entry["nationCutCount"] = count
    return dict(per_scenario), detail


def _baseline() -> dict[int, int]:
    if not BASELINE_PATH.exists():
        return {}
    raw = json.loads(BASELINE_PATH.read_text(encoding="utf-8"))
    return {int(k): int(v) for k, v in raw["seedUnsuppliedByScenario"].items()}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--map", default="han-world-v3")
    parser.add_argument("--check", action="store_true", help="기준선보다 나빠지면 exit 1")
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--write-baseline", action="store_true", help="현재 실측치를 핀으로 박는다")
    args = parser.parse_args()

    measured, detail = measure(args.map)

    if args.write_baseline:
        BASELINE_PATH.write_text(
            json.dumps(
                {
                    "schemaVersion": 1,
                    "note": (
                        "시나리오별 개시 시점 보급 절단 城 수(BOTH_UNSUPPLIED) 실측 기준선. "
                        "임계값이 아니다 — 나빠지면 CI 가 실패하고, 좋아지면 이 핀을 낮춰라."
                    ),
                    "map": args.map,
                    "seedUnsuppliedByScenario": {str(k): v for k, v in sorted(measured.items())},
                },
                ensure_ascii=False,
                indent=2,
            )
            + "\n",
            encoding="utf-8",
        )
        print(f"기준선을 {BASELINE_PATH.relative_to(ROOT)} 에 기록했다.")
        return 0

    baseline = _baseline()
    errors: list[str] = []
    for code, count in sorted(measured.items()):
        pinned = baseline.get(code)
        if pinned is None:
            errors.append(f"scenario {code} has no pinned baseline (run --write-baseline)")
        elif count > pinned:
            errors.append(f"scenario {code} seed-unsupplied {count} > baseline {pinned} — 나빠졌다")
    for code in sorted(set(baseline) - set(measured)):
        if baseline[code] != 0:
            errors.append(f"scenario {code} baseline {baseline[code]} but no measurement — 핀을 0 으로 낮춰라")

    if args.json:
        print(json.dumps({"measured": measured, "baseline": baseline, "errors": errors,
                          "rows": detail}, ensure_ascii=False, indent=2, sort_keys=True))
    else:
        worst: dict[tuple[int, str], int] = {}
        for entry in detail:
            worst[(entry["scenarioCode"], entry["nation"])] = entry.get("nationCutCount", 0)
        for code, count in sorted(measured.items()):
            pin = baseline.get(code)
            print(f"{code}: seed-unsupplied={count} baseline={pin if pin is not None else '-'}")
        print("가장 크게 절단된 세력 10")
        for (code, nation), count in sorted(worst.items(), key=lambda kv: -kv[1])[:10]:
            print(f"  scenario {code} {nation}: {count}城")
        print(f"errors={len(errors)}")
        for message in errors:
            print(f"  {message}")
    return 1 if (args.check and errors) else 0


if __name__ == "__main__":
    raise SystemExit(main())
