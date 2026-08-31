from __future__ import annotations

import json
import unittest
from pathlib import Path

from tools.scenario.province_ownership_audit import audit_assignments, topology_from_map
from tools.scenario.province_ownership_contract import AuditAllowlistEntry, parse_ownership_document
from tools.scenario.province_ownership_materializer import ProvinceAssignment, ProvinceCatalogEntry, materialize_all
from tools.scenario.build_scenario_province_ownership import runtime_scenario_catalog


ROOT = Path(__file__).resolve().parents[3]


def assignment(
    province_id: str,
    owner: str | None,
    *,
    confidence: str = "ADMIN_SCOPE",
) -> ProvinceAssignment:
    return ProvinceAssignment(
        scenario_code=1030,
        province_id=province_id,
        owner_nation_id=None if owner is None else {"A": 1, "B": 2}[owner],
        owner_nation_key=owner,
        controller_city_id=None,
        winning_claim_id=f"C-{province_id}",
        claim_trace=(f"C-{province_id}",),
        basis_type="SCENARIO_BASELINE_UNOWNED" if owner is None else "ADMIN_REGION_CONTROL",
        evidence_ids=(f"E-{province_id}",),
        confidence="EXPLICIT_UNOWNED" if owner is None else confidence,
        rationale=province_id,
    )


class ProvinceOwnershipAuditTest(unittest.TestCase):
    def test_yizhou_jingzhou_fuling_corridor_is_not_left_unowned(self):
        map_doc = json.loads((ROOT / "data/map/han-tiles.json").read_text(encoding="utf-8"))
        ownership = json.loads(
            (ROOT / "data/map/han-scenario-province-ownership-v1.json").read_text(encoding="utf-8")
        )
        fuling_ids = {
            row["id"] for row in map_doc["provinceRecords"]
            if row["parentRegionId"] == "PARENT-0150"
        }
        self.assertTrue(fuling_ids)

        by_code = {row["scenarioCode"]: row["assignments"] for row in ownership["scenarios"]}
        failures = {
            code: sorted(
                row["provinceId"] for row in by_code[code]
                if row["provinceId"] in fuling_ids and row["ownerNationKey"] is None
            )
            for code in (1020, 1021, 1030, 1031, 1040, 1041, 1050, 1060, 1070, 1080, 1090, 1100, 1110, 1120)
        }
        self.assertEqual({}, {code: ids for code, ids in failures.items() if ids})

    def test_all_active_scenarios_have_no_unreviewed_interior_holes(self):
        map_doc = json.loads((ROOT / "data/map/han-tiles.json").read_text(encoding="utf-8"))
        raw = json.loads(
            (ROOT / "data/curated/han/scenario-province-claims-v1.json").read_text(encoding="utf-8")
        )
        scenarios = {
            code: runtime_scenario_catalog(json.loads(
                (ROOT / f"infra/src/main/resources/scenario/scenario_{code}.json").read_text(encoding="utf-8")
            ))
            for code in raw["activeScenarioCodes"]
        }
        parsed = parse_ownership_document(
            raw,
            {
                "provinceIds": [row["id"] for row in map_doc["provinceRecords"]],
                "parentRegionIds": [row["id"] for row in map_doc["parentRegions"]],
            },
            scenarios,
        )
        catalog = tuple(
            ProvinceCatalogEntry(row["id"], row["parentRegionId"])
            for row in map_doc["provinceRecords"]
        )
        assignments = materialize_all(parsed, catalog)
        topology = topology_from_map(map_doc)

        failures: dict[int, list[tuple[str, ...]]] = {}
        for code in raw["activeScenarioCodes"]:
            audit = audit_assignments(
                assignments[code],
                topology.graph,
                exterior_province_ids=topology.exterior_province_ids,
                allowlist=parsed.scenarios[code].audit_allowlist,
                province_areas=topology.province_areas,
            )
            holes = [
                row.province_ids
                for row in audit.errors
                if row.code == "UNALLOWLISTED_HOLE"
            ]
            if holes:
                failures[code] = holes

        self.assertEqual({}, failures)

    def test_surrounded_unowned_component_is_a_hole(self):
        graph = {
            "N": {"C"}, "E": {"C"}, "S": {"C"}, "W": {"C"},
            "C": {"N", "E", "S", "W"},
        }
        rows = tuple(assignment(pid, None if pid == "C" else "A") for pid in graph)

        audit = audit_assignments(rows, graph)

        self.assertIn("UNALLOWLISTED_HOLE", {error.code for error in audit.errors})

    def test_hole_touching_the_map_exterior_is_not_surrounded(self):
        graph = {"A": {"U"}, "U": {"A"}}
        rows = (assignment("A", "A"), assignment("U", None))

        audit = audit_assignments(rows, graph, exterior_province_ids=frozenset({"U"}))

        self.assertNotIn("UNALLOWLISTED_HOLE", {error.code for error in audit.errors})

    def test_isolated_owner_speck_requires_direct_evidence_or_allowlist(self):
        graph = {"A1": {"A2", "B"}, "A2": {"A1", "B"}, "B": {"A1", "A2", "S"}, "S": {"B"}}
        rows = (
            assignment("A1", "A"), assignment("A2", "A"),
            assignment("B", "B"), assignment("S", "A"),
        )

        audit = audit_assignments(rows, graph)

        self.assertIn("UNEXPLAINED_ISOLATED_COMPONENT", {error.code for error in audit.errors})

    def test_directly_attested_enclave_is_preserved(self):
        graph = {"A1": {"A2", "B"}, "A2": {"A1", "B"}, "B": {"A1", "A2", "S"}, "S": {"B"}}
        rows = (
            assignment("A1", "A"), assignment("A2", "A"),
            assignment("B", "B"), assignment("S", "A", confidence="DIRECT"),
        )

        audit = audit_assignments(rows, graph)

        self.assertFalse(audit.errors)

    def test_allowlist_match_is_exact_not_owner_wide(self):
        graph = {"A1": {"A2", "B"}, "A2": {"A1", "B"}, "B": {"A1", "A2", "S"}, "S": {"B"}}
        rows = (
            assignment("A1", "A"), assignment("A2", "A"),
            assignment("B", "B"), assignment("S", "A"),
        )
        allowlist = (
            AuditAllowlistEntry(
                audit_kind="UNEXPLAINED_ISOLATED_COMPONENT",
                province_ids=("S",),
                evidence_ids=("E-S",),
                rationale="Reviewed enclave",
            ),
        )

        allowed = audit_assignments(rows, graph, allowlist=allowlist)
        wrong = audit_assignments(
            rows,
            graph,
            allowlist=(AuditAllowlistEntry(
                audit_kind="UNEXPLAINED_ISOLATED_COMPONENT",
                province_ids=("A1",),
                evidence_ids=("E-A1",),
                rationale="Wrong component",
            ),),
        )

        self.assertFalse(allowed.errors)
        self.assertTrue(wrong.errors)

    def test_missing_assignment_is_always_fatal(self):
        graph = {"A": {"B"}, "B": {"A"}}

        audit = audit_assignments((assignment("A", "A"),), graph)

        self.assertEqual("MISSING_ASSIGNMENT", audit.errors[0].code)

    def test_map_topology_uses_catalog_ids_and_rle_for_area_and_exterior(self):
        map_doc = {
            "_meta": {"cols": 3, "rows": 2},
            "provinceRecords": [
                {"id": "A", "parentRegionId": "P1"},
                {"id": "B", "parentRegionId": "P2"},
            ],
            "adjacency": {"county": [{"a": 0, "b": 1, "cells": 2}]},
            "owner": [[0, 2], [1, 2], [-1, 2]],
        }

        topology = topology_from_map(map_doc)

        self.assertEqual({"A": {"B"}, "B": {"A"}}, topology.graph)
        self.assertEqual({"A": 2, "B": 2}, topology.province_areas)
        self.assertEqual(frozenset({"A", "B"}), topology.exterior_province_ids)

    def test_internal_void_contact_counts_as_playable_exterior(self):
        map_doc = {
            "_meta": {"cols": 5, "rows": 5},
            "provinceRecords": [
                {"id": "OUTER", "parentRegionId": "P1"},
                {"id": "INNER", "parentRegionId": "P2"},
            ],
            "adjacency": {"county": [{"a": 0, "b": 1, "cells": 4}]},
            "owner": [[0, 7], [1, 1], [0, 4], [-1, 1], [0, 12]],
        }

        topology = topology_from_map(map_doc)

        self.assertIn("INNER", topology.exterior_province_ids)


if __name__ == "__main__":
    unittest.main()
