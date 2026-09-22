#!/usr/bin/env python3
"""han-tiles.json · han-world-v3.json 에 묶인 커밋 산출물의 결합 목록과 일괄 --check (GH #818).

han-tiles 를 바꾸는 PR 은 아래 COUPLED 의 산출물을 같은 PR 에서 재생성해야 한다. 2026-09-17 에
PR #804 하나가 縣 경제 입력(main contracts 적색)·행군 템포 노트·열린 PR #816 을 한꺼번에 낡게 만들었다.

    python3 tools/map/check_han_tiles_coupled.py --list        # 결합 목록(산출물·재생성 명령)
    python3 tools/map/check_han_tiles_coupled.py --check       # 전부 돌고 낡은 것을 전부 지목(중간에 멈추지 않음)
    python3 tools/map/check_han_tiles_coupled.py --regenerate  # 재생성 명령이 있는 항목을 순서대로 재생성

`slow` 항목은 contracts 잡에 이미 개별 스텝으로 배선돼 있어 기본 --check 에서 빠진다(--include-slow 로 포함).
`regenerate` 가 None 인 항목은 사람이 판정하는 원장·han-tiles 자체의 단계라 자동 재생성이 없다 — 적색이면
지목된 원장을 검토해 고친다. 목록의 완전성은 tools/map/tests/test_check_han_tiles_coupled.py 가 지킨다.
"""
from __future__ import annotations

import argparse
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PY = "python3"


@dataclass(frozen=True)
class Coupled:
    key: str
    artifacts: tuple[str, ...]          # 낡는 커밋 산출물·문서
    check: tuple[str, ...]              # 적색이면 낡았다
    regenerate: tuple[str, ...] | None  # None = 사람 판정(자동 재생성 없음)
    slow: bool = False                  # contracts 잡의 개별 스텝으로만 돈다
    local_only: bool = False            # gitignored 입력이 있어야 돈다. 없으면 exit 77 = SKIPPED(통과 아님)


SKIPPED_EXIT = 77


def _t(*a: str) -> tuple[str, ...]:
    return (PY, *a)


# 순서 = 재생성 순서(귀속 원장 → 월드 → 그 뒤에 얹히는 것). han-tiles 자체의 단계 검사가 맨 앞이다.
COUPLED: tuple[Coupled, ...] = (
    Coupled("northeast-elevation", ("web/game/public/map/elevation/manifest.json",), _t("tools/map/build_northeast_elevation.py", "--check"), None),
    Coupled("tiles-stage-korea-places", ("data/map/han-tiles.json",),
            _t("tools/map/refine_korea_places.py", "--check"), None),
    Coupled("tiles-stage-lowland-terrain", ("data/map/han-tiles.json",),
            _t("tools/map/reclassify_han_lowland_terrain.py", "--check"), None),
    Coupled("tiles-stage-cityless-fold", ("data/map/han-tiles.json",),
            _t("tools/map/fold_cityless_jurisdictions.py", "--check"), None),
    Coupled("tiles-stage-strategic-carve", ("data/map/han-tiles.json",),
            _t("tools/map/carve_strategic_site_provinces.py", "--check"), None),
    # ★ 지리 재분할(GH #806): 단계 핀 + 재현 + Q2(郡 불변)·Q3(덮개)·Q4(넓이 — 예외는 결정 원장 행과 정확히 일치).
    Coupled("tiles-stage-county-location-partition",
            ("data/map/han-tiles.json", "data/curated/han/county-location-partition-v1.json",
             "data/curated/han/county-location-partition-v1.input.json.gz"),
            _t("tools/map/partition_counties_by_location.py", "--check"), None),
    # Q1(城의 실제 칸 ∈ 제 관할)·Q1b(실제 칸이 저지면 제 省에 저지 ≥ 1칸). 예외는 원장 행뿐이다.
    Coupled("tiles-seat-in-place-q1", ("data/map/han-tiles.json",),
            _t("tools/map/measure_province_seat_offset.py", "--check", "--exceptions",
               "data/curated/han/county-location-partition-v1.json",
               "data/curated/han/strategic-site-province-carves-v1.json"), None),
    # 같은 Q1 을 CHGIS 원본 좌표(독립 축)로 다시 잰다. 원본은 gitignored 라 CI 에서는 SKIPPED 다.
    Coupled("tiles-seat-in-place-chgis-axis", ("data/map/han-tiles.json",),
            _t("tools/map/check_seat_cells_against_chgis.py", "--check"), None, local_only=True),
    Coupled("tiles-stage-place-names", ("data/map/han-tiles.json",),
            _t("tools/map/materialize_han_place_names.py", "--check"), None),
    Coupled("tiles-stage-county-rebindings", ("data/curated/han/county-misbinding-rebindings-v1.json",),
            _t("tools/map/rebind_misbound_counties.py", "--check"), None),
    Coupled("frontier-county-materialization", ("data/map/han-tiles.json",),
            _t("tools/map/materialize_frontier_counties.py", "--check"), None, slow=True),
    Coupled("territory-disconnection-ledger", ("data/curated/han/territory-disconnection-adjudications-v1.json",),
            _t("tools/map/audit_territory_disconnections.py", "--check"), None, slow=True),
    Coupled("administrative-parent-reconciliation", ("data/curated/han/administrative-parent-reconciliation-v1.json",),
            _t("tools/map/build_han_parent_reconciliation.py", "--check"),
            _t("tools/map/build_han_parent_reconciliation.py", "--write"), slow=True),
    Coupled("province-city-attribution", ("data/curated/han/province-city-attribution-v1.json",),
            _t("tools/scenario/build_province_city_attribution.py", "--check"),
            _t("tools/scenario/build_province_city_attribution.py")),
    # 수로 망은 han-world-v3 의 입력이다(강 뱃길 = portLinks). 세계 파일보다 먼저 굽는다.
    Coupled("waterway-network", ("data/map/han-waterway-network-v1.json",),
            _t("tools/map/build_han_waterway_network.py", "--check"),
            _t("tools/map/build_han_waterway_network.py", "--write")),
    Coupled("han-world-v3", ("infra/src/main/resources/map/han-world-v3.json", "data/map/han-world-v3-manifest-v1.json"),
            _t("tools/scenario/build_han_world.py", "--target", "han-world-v3", "--check"),
            _t("tools/scenario/build_han_world.py", "--target", "han-world-v3")),
    Coupled("scenario-materialization", ("infra/src/main/resources/scenario/",),
            _t("tools/scenario/apply_han_world.py", "--map", "han-world-v3", "--check"),
            _t("tools/scenario/apply_han_world.py", "--map", "han-world-v3")),
    Coupled("route-node-candidates", ("data/curated/han/route-node-selection-candidates-v1.json",),
            _t("tools/scenario/build_han_route_node_candidates.py", "--output",
               "data/curated/han/route-node-selection-candidates-v1.json", "--check"),
            _t("tools/scenario/build_han_route_node_candidates.py", "--output",
               "data/curated/han/route-node-selection-candidates-v1.json")),
    Coupled("route-node-selection", ("data/curated/han/route-node-selection-v1.json",),
            _t("tools/scenario/materialize_han_route_node_selection.py", "--check"),
            _t("tools/scenario/materialize_han_route_node_selection.py")),
    Coupled("route-node-selection-validator", ("data/curated/han/route-node-selection-v1.json",),
            _t("tools/scenario/validate_han_route_node_selection.py"), None),
    Coupled("commandery-supply-links", ("data/map/han-commandery-supply-links-v1.json",),
            _t("tools/map/build_commandery_supply_links.py", "--check"),
            _t("tools/map/build_commandery_supply_links.py")),
    Coupled("supply-disagreements", ("data/curated/han/supply-disconnection-adjudications-v3.json",),
            _t("tools/scenario/audit_han_supply_disagreements.py", "--map", "han-world-v3", "--check"), None),
    Coupled("scenario-seed-supply-baseline", ("data/curated/han/scenario-seed-supply-baseline-v1.json",),
            _t("tools/scenario/audit_scenario_seed_supply.py", "--check"), None),
    Coupled("water-topology", ("data/map/han-water-topology-v1.json",),
            _t("tools/map/build_han_water_topology.py", "--check"),
            _t("tools/map/build_han_water_topology.py", "--write")),
    Coupled("water-topology-audit", ("data/map/han-water-topology-v1.json",),
            _t("tools/map/audit_han_water_topology.py", "--check"), None),
    Coupled("resource-sites", ("data/curated/han/resource-sites-v1.json",),
            _t("tools/map/build_resource_sites.py", "--check"),
            _t("tools/map/build_resource_sites.py")),
    # 산지 위치는 위 근거 원장에서, 산출량은 게임 설계에서 온다. 郡 단위 말 산지를 治所 縣으로 옮기므로
    # han-tiles 의 commanderyRecords 를 읽는다 — 지도가 바뀌면 이 원장도 낡는다.
    Coupled("hwiha-resource-production", ("data/curated/han/hwiha-resource-production-v1.json",),
            _t("tools/map/build_hwiha_resource_production.py", "--check"),
            _t("tools/map/build_hwiha_resource_production.py")),
    Coupled("officer-native-county", ("data/curated/han/officer-native-county-v1.json",),
            _t("tools/scenario/build_officer_native_county.py", "--check"),
            _t("tools/scenario/build_officer_native_county.py")),
    Coupled("county-economy-inputs", ("data/curated/han/county-economy-inputs-v1.json",),
            _t("tools/map/build_county_economy_inputs.py", "--check"),
            _t("tools/map/build_county_economy_inputs.py")),
    # 같은 郡 안 한글 표시명 충돌 목록 + 웹 병기 표(#838). han-world-v3 를 두 번째 축으로 읽으므로 그 뒤에 굽는다.
    Coupled("county-display-name-collisions",
            ("data/curated/han/county-display-name-collisions-v1.json", "web/shared/src/iso/countyNameGloss.generated.ts"),
            _t("tools/map/build_county_display_name_collisions.py", "--check"),
            _t("tools/map/build_county_display_name_collisions.py")),
    Coupled("junguozhi-county-gaps", ("data/curated/han/junguozhi-county-gaps-v1.json",),
            _t("tools/map/build_junguozhi_county_gaps.py", "--check"),
            _t("tools/map/build_junguozhi_county_gaps.py")),
    Coupled("administrative-topology-audit", ("data/curated/han/administrative-topology-audit-v1.json",),
            _t("tools/map/audit_han_admin_topology.py", "--check"),
            _t("tools/map/audit_han_admin_topology.py")),
    # claims 가 ownership 의 입력이다(claimsSha256) — 순서를 지킨다.
    Coupled("scenario-province-claims", ("data/curated/han/scenario-province-claims-v1.json",),
            _t("tools/scenario/migrate_han_ownership_claims.py", "--check"),
            _t("tools/scenario/migrate_han_ownership_claims.py", "--write")),
    Coupled("scenario-province-ownership", ("data/map/han-scenario-province-ownership-v1.json",),
            _t("tools/scenario/build_scenario_province_ownership.py", "--check"),
            _t("tools/scenario/build_scenario_province_ownership.py")),
    # ★ 지리 재분할(GH #806)의 씨앗 충돌 원장 초안. 기계 필드만 다시 뽑고 사람 판정 필드는 보존한다.
    Coupled("county-seed-collisions", ("data/curated/han/county-seed-collisions-v1.json",),
            _t("tools/map/draft_county_seed_collisions.py", "--check"),
            _t("tools/map/draft_county_seed_collisions.py")),
    # 손으로 검토한 원장이다. 적색이면 해시만 갈지 말고 후보 셀의 투영·지형 검사가 새 타일에서도 통과하는지 본다.
    Coupled("strategic-site-anchor-review", ("data/curated/han/strategic-site-anchor-review-v1.json",),
            _t("tools/map/validate_han_strategic_site_anchors.py", "--check"), None),
    Coupled("iso3d-assets", ("web/game/public/models/iso3d/", "web/gateway/public/models/iso3d/"),
            _t("tools/assets/build_iso3d_assets.py", "--check"),
            _t("tools/assets/build_iso3d_assets.py")),
    # 노트는 도구 출력을 옮겨 적은 문서다 — 적색이면 도구를 다시 돌려 표의 행을 손으로 고친다.
    Coupled("march-tempo-and-siege-supply-notes",
            ("docs/superpowers/research/2026-09-17-march-tempo-baseline.md",
             "docs/superpowers/research/2026-09-17-siege-supply-baseline.md"),
            (PY, "-m", "unittest", "discover", "-s", "tools/sim/tests", "-p", "test_*.py"), None),
    Coupled("korea-manchuria-coverage", ("data/curated/han/korea-manchuria-coverage-v1.json",),
            _t("tools/map/audit_korea_manchuria.py", "--check"),
            _t("tools/map/audit_korea_manchuria.py")),
    # Latest release must reproduce current inputs; historical 1133 integrity remains separately tested.
    Coupled("release-1168-bundle", ("data/map/han-world-v3-1168-artifacts-v1/catalog.json",),
            _t("tools/map/build_han_1168_bundle.py", "--check"), None),
)


def run_checks(include_slow: bool) -> int:
    stale: list[Coupled] = []
    skipped: list[str] = []
    for c in COUPLED:
        if c.slow and not include_slow:
            print(f"SKIP  {c.key} (slow — contracts 잡의 개별 스텝)")
            continue
        p = subprocess.run(c.check, cwd=ROOT, capture_output=True, text=True)
        if c.local_only and p.returncode == SKIPPED_EXIT:
            # 통과가 아니다. 로컬 전용 입력이 없어 못 돌았다는 사실을 따로 센다.
            print(f"SKIPPED {c.key} (local-only — gitignored 입력 없음, 통과 아님)")
            skipped.append(c.key)
            continue
        print(f"{'OK   ' if p.returncode == 0 else 'STALE'} {c.key}")
        if p.returncode != 0:
            stale.append(c)
            tail = (p.stdout + p.stderr).strip().splitlines()[-8:]
            print("\n".join(f"      | {line}" for line in tail))
    if stale:
        print(f"\nhan-tiles 결합 산출물 {len(stale)}건이 낡았다:", file=sys.stderr)
        for c in stale:
            fix = " ".join(c.regenerate) if c.regenerate else "사람 판정 — 원장·노트를 검토해 고친다"
            print(f"  - {c.key}: {', '.join(c.artifacts)}\n      재생성: {fix}", file=sys.stderr)
        return 1
    print("\nhan-tiles 결합 산출물: 전부 최신" + (f" (로컬 전용 {len(skipped)}건은 SKIPPED — 검증되지 않았다)" if skipped else ""))
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--list", action="store_true")
    g.add_argument("--check", action="store_true")
    g.add_argument("--regenerate", action="store_true")
    ap.add_argument("--include-slow", action="store_true")
    args = ap.parse_args()
    if args.list:
        for c in COUPLED:
            print(f"{c.key}{' [slow]' if c.slow else ''}\n  산출물: {', '.join(c.artifacts)}\n  check: {' '.join(c.check)}"
                  f"\n  재생성: {' '.join(c.regenerate) if c.regenerate else '(사람 판정)'}")
        return 0
    if args.regenerate:
        for c in COUPLED:
            if c.regenerate:
                print(f"REGEN {c.key}")
                subprocess.run(c.regenerate, cwd=ROOT, check=True)
        return run_checks(args.include_slow)
    return run_checks(args.include_slow)


if __name__ == "__main__":
    sys.exit(main())
