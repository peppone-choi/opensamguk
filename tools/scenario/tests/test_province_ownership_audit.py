from __future__ import annotations

import unittest

from tools.scenario.province_ownership_audit import audit_assignments, topology_from_map
from tools.scenario.province_ownership_contract import AuditAllowlistEntry
from tools.scenario.province_ownership_materializer import ProvinceAssignment


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
