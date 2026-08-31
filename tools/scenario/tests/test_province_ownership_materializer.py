from __future__ import annotations

import json
import unittest
from pathlib import Path
from types import MappingProxyType

from tools.scenario.province_ownership_contract import (
    OwnershipContractError,
    OwnershipDocument,
    ScenarioClaims,
    TerritoryClaim,
    parse_ownership_document,
)
from tools.scenario.province_ownership_materializer import (
    ProvinceCatalogEntry,
    materialize_all,
    materialize_scenario,
)
from tools.scenario.build_scenario_province_ownership import canonical_bytes, generate_document


ROOT = Path(__file__).resolve().parents[3]


def claim(
    claim_id: str,
    kind: str,
    owner: str | None,
    *,
    provinces: tuple[str, ...] = (),
    parents: tuple[str, ...] = (),
    all_provinces: bool = False,
    overrides: tuple[str, ...] = (),
) -> TerritoryClaim:
    return TerritoryClaim(
        claim_id=claim_id,
        claim_kind=kind,
        owner_nation_key=owner,
        province_ids=provinces,
        parent_region_ids=parents,
        all_provinces=all_provinces,
        evidence_ids=(f"E-{claim_id}",),
        overrides_claim_ids=overrides,
        rationale=claim_id,
    )


def document(claims: tuple[TerritoryClaim, ...]) -> OwnershipDocument:
    scenario = ScenarioClaims(
        scenario_code=1030,
        effective_year=194,
        placement_basis="HISTORICAL",
        nation_ids=MappingProxyType({"CAO": 1, "LU": 2}),
        claims=claims,
        audit_allowlist=(),
    )
    return OwnershipDocument(
        schema_version=1,
        map_id="han-world-v2",
        unit_set="han",
        active_scenario_codes=(1030,),
        evidence=MappingProxyType({}),
        scenarios=MappingProxyType({1030: scenario}),
    )


CATALOG = (
    ProvinceCatalogEntry("JUANCHENG", "YANZHOU"),
    ProvinceCatalogEntry("PUYANG", "YANZHOU"),
)
BASELINE = claim("BASE", "SCENARIO_BASELINE_UNOWNED", None, all_provinces=True)


class ProvinceOwnershipMaterializerTest(unittest.TestCase):
    def test_direct_province_override_beats_broad_parent_claim(self):
        broad = claim("LU-YANZHOU", "ADMIN_REGION_CONTROL", "LU", parents=("YANZHOU",), overrides=("BASE",))
        direct = claim("CAO-JUANCHENG", "PROVINCE_DIRECT", "CAO", provinces=("JUANCHENG",), overrides=("BASE", "LU-YANZHOU"))

        assignments = materialize_scenario(document((direct, BASELINE, broad)), 1030, CATALOG)
        by_id = {row.province_id: row for row in assignments}

        self.assertEqual("CAO", by_id["JUANCHENG"].owner_nation_key)
        self.assertEqual(("LU-YANZHOU", "CAO-JUANCHENG"), by_id["JUANCHENG"].claim_trace)
        self.assertEqual("LU", by_id["PUYANG"].owner_nation_key)

    def test_same_tier_conflict_fails_in_any_input_order(self):
        cao = claim("CAO-YANZHOU", "ADMIN_REGION_CONTROL", "CAO", parents=("YANZHOU",), overrides=("BASE",))
        lu = claim("LU-YANZHOU", "ADMIN_REGION_CONTROL", "LU", parents=("YANZHOU",), overrides=("BASE",))

        for rows in ((BASELINE, cao, lu), (lu, cao, BASELINE)):
            with self.subTest(rows=tuple(row.claim_id for row in rows)):
                with self.assertRaisesRegex(OwnershipContractError, "CLAIM_CONFLICT"):
                    materialize_scenario(document(rows), 1030, CATALOG)

    def test_same_tier_more_specific_claim_requires_and_uses_override_edge(self):
        broad = claim("IF-LU-YANZHOU", "IF_SCENARIO", "LU", parents=("YANZHOU",), overrides=("BASE",))
        direct = claim(
            "IF-CAO-JUANCHENG",
            "IF_SCENARIO",
            "CAO",
            provinces=("JUANCHENG",),
            overrides=("BASE", "IF-LU-YANZHOU"),
        )

        rows = materialize_scenario(document((direct, broad, BASELINE)), 1030, CATALOG)
        by_id = {row.province_id: row for row in rows}

        self.assertEqual("CAO", by_id["JUANCHENG"].owner_nation_key)
        self.assertEqual(
            ("IF-LU-YANZHOU", "IF-CAO-JUANCHENG"),
            by_id["JUANCHENG"].claim_trace,
        )

    def test_cross_tier_override_must_name_the_winning_claim(self):
        broad = claim("LU-YANZHOU", "ADMIN_REGION_CONTROL", "LU", parents=("YANZHOU",), overrides=("BASE",))
        direct = claim("CAO-JUANCHENG", "PROVINCE_DIRECT", "CAO", provinces=("JUANCHENG",), overrides=("BASE",))

        with self.assertRaisesRegex(OwnershipContractError, "MISSING_OVERRIDE_EDGE"):
            materialize_scenario(document((BASELINE, broad, direct)), 1030, CATALOG)

    def test_unclaimed_province_retains_explicit_baseline(self):
        rows = materialize_scenario(document((BASELINE,)), 1030, CATALOG)

        self.assertTrue(all(row.owner_nation_id is None for row in rows))
        self.assertTrue(all(row.claim_trace == ("BASE",) for row in rows))
        self.assertTrue(all(row.confidence == "EXPLICIT_UNOWNED" for row in rows))

    def test_production_document_has_1524_rows_per_active_scenario(self):
        map_doc = json.loads((ROOT / "data/map/han-tiles.json").read_text(encoding="utf-8"))
        raw = json.loads(
            (ROOT / "data/curated/han/scenario-province-claims-v1.json").read_text(encoding="utf-8")
        )
        scenario_catalog = {
            code: {
                "nations": json.loads(
                    (ROOT / f"data/extracted/scenario/scenario_{code}.json").read_text(encoding="utf-8")
                )["nations"]
            }
            for code in raw["activeScenarioCodes"]
        }
        parsed = parse_ownership_document(
            raw,
            {
                "provinceIds": [row["id"] for row in map_doc["provinceRecords"]],
                "parentRegionIds": [row["id"] for row in map_doc["parentRegions"]],
            },
            scenario_catalog,
        )
        catalog = tuple(
            ProvinceCatalogEntry(row["id"], row["parentRegionId"])
            for row in map_doc["provinceRecords"]
        )

        generated = materialize_all(parsed, catalog)

        self.assertEqual(15, len(generated))
        self.assertEqual(22_860, sum(len(rows) for rows in generated.values()))
        self.assertTrue(all(len(rows) == 1_524 for rows in generated.values()))

    def test_generated_artifact_is_canonical_complete_and_path_independent(self):
        first = generate_document(ROOT)
        second = generate_document(ROOT)

        self.assertEqual(canonical_bytes(first), canonical_bytes(second))
        self.assertEqual(15, len(first["scenarios"]))
        self.assertEqual(
            22_860,
            sum(len(scenario["assignments"]) for scenario in first["scenarios"]),
        )
        self.assertNotIn(str(ROOT), canonical_bytes(first).decode("utf-8"))
        self.assertEqual(
            [1010, 1020, 1021, 1030, 1031, 1040, 1041, 1050, 1060, 1070, 1080, 1090, 1100, 1110, 1120],
            [row["scenarioCode"] for row in first["scenarios"]],
        )


if __name__ == "__main__":
    unittest.main()
