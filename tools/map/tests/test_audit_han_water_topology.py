from __future__ import annotations

import copy
import json
import subprocess
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
MODULE = ROOT / "tools" / "map" / "audit_han_water_topology.py"
sys.path.insert(0, str(ROOT / "tools" / "map"))


class HanWaterTopologyAuditTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        import audit_han_water_topology as audit
        import build_han_water_topology as builder
        cls.audit = audit
        cls.builder = builder

    def test_committed_pilot_is_valid_and_river_activation_remains_blocked(self):
        result = self.audit.audit_materialized()

        self.assertEqual(1_524, result["counts"]["landProvinceIds"])
        self.assertEqual(2, result["counts"]["waterZones"])
        self.assertEqual({"COASTAL_SEA": 1, "LAKE_BASIN": 1}, result["zoneKinds"])
        self.assertEqual({}, result["edgeModes"])
        self.assertEqual(0, result["counts"]["traversalEdges"])
        self.assertEqual(0, result["counts"]["riverBarriers"])
        self.assertFalse(result["activation"]["riverCrossingReady"])
        self.assertEqual(
            ["NO_REVIEWED_RIVER_CROSSING_EVIDENCE"],
            result["activation"]["blockerCodes"],
        )

    def test_manifest_or_artifact_drift_is_rejected(self):
        inputs = self.builder.load_inputs()
        artifact_bytes, manifest_bytes = self.builder.render_outputs()
        artifact = json.loads(artifact_bytes)
        manifest = json.loads(manifest_bytes)

        wrong_manifest = copy.deepcopy(manifest)
        wrong_manifest["files"]["waterTopology"]["sha256"] = "a" * 64
        with self.assertRaisesRegex(ValueError, "manifest|sha256"):
            self.audit.audit_documents(*inputs, artifact, artifact_bytes, wrong_manifest)

        wrong_artifact = copy.deepcopy(artifact)
        wrong_artifact["waterZones"][0]["sourceRefs"] = []
        wrong_bytes = self.builder.canonical_json_bytes(wrong_artifact)
        with self.assertRaisesRegex(ValueError, "sourceRefs|artifact"):
            self.audit.audit_documents(*inputs, wrong_artifact, wrong_bytes, manifest)

    def test_materialized_artifact_rejects_per_tile_ids_and_deep_sea_scope(self):
        tiles, tiles_bytes, adjudications, _ = self.builder.load_inputs()
        artifact = json.loads(self.builder.render_outputs()[0])

        per_tile = copy.deepcopy(artifact)
        per_tile["waterZones"][0]["id"] = "water-zone:coastal-cell-543-305"
        with self.assertRaisesRegex(ValueError, "per-water-tile"):
            self.audit.validate_artifact(tiles, tiles_bytes, adjudications, per_tile)

        deep_sea = copy.deepcopy(artifact)
        deep_sea["geometryComponents"][0]["waterScope"] = "OPEN_SEA"
        with self.assertRaisesRegex(ValueError, "deep-sea|waterScope"):
            self.audit.validate_artifact(tiles, tiles_bytes, adjudications, deep_sea)

        one_cell = copy.deepcopy(artifact)
        one_cell["geometryComponents"][0]["cellRuns"] = [
            {"row": 543, "startCol": 305, "endCol": 305}
        ]
        one_cell["geometryComponents"][0]["cellCount"] = 1
        with self.assertRaisesRegex(ValueError, "minimum component|per-water-tile"):
            self.audit.validate_artifact(tiles, tiles_bytes, adjudications, one_cell)

        disconnected = copy.deepcopy(artifact)
        disconnected["geometryComponents"][0]["cellRuns"] = [
            {"row": 51, "startCol": 731, "endCol": 731},
            {"row": 668, "startCol": 0, "endCol": 0},
        ]
        disconnected["geometryComponents"][0]["cellCount"] = 2
        with self.assertRaisesRegex(ValueError, "connected waterbody"):
            self.audit.validate_artifact(tiles, tiles_bytes, adjudications, disconnected)

        open_sea = copy.deepcopy(artifact)
        open_sea["geometryComponents"][0]["cellRuns"] = [
            {"row": 55, "startCol": 729, "endCol": 730}
        ]
        open_sea["geometryComponents"][0]["cellCount"] = 2
        with self.assertRaisesRegex(ValueError, "coastal.*owner boundary|shoreline"):
            self.audit.validate_artifact(tiles, tiles_bytes, adjudications, open_sea)

    def test_source_selector_must_resolve_one_upheld_dossier_row(self):
        tiles, tiles_bytes, adjudications, _ = self.builder.load_inputs()
        artifact = json.loads(self.builder.render_outputs()[0])
        missing = copy.deepcopy(adjudications)
        missing["sourceCatalog"][0]["sourceId"] = "territory-disconnection:MISSING"
        missing["sourceCatalog"][0]["selector"]["componentKey"] = "MISSING"

        with self.assertRaisesRegex(ValueError, "existing evidence row"):
            self.audit.validate_artifact(tiles, tiles_bytes, missing, artifact)

    def test_source_unit_and_member_coverage_must_match_dossier_exactly(self):
        tiles, tiles_bytes, adjudications, _ = self.builder.load_inputs()
        artifact = self.builder.build_water_topology(tiles, tiles_bytes, adjudications)
        wrong = copy.deepcopy(adjudications)
        wrong["sourceCatalog"][0]["memberIds"] = ["200012"]

        with self.assertRaisesRegex(ValueError, "memberIds|source coverage"):
            self.audit.validate_artifact(tiles, tiles_bytes, wrong, artifact)

    def test_bypass_artifact_coastal_geometry_must_touch_cited_component_members(self):
        tiles, tiles_bytes, adjudications, _ = self.builder.load_inputs()
        artifact = self.builder.build_water_topology(tiles, tiles_bytes, adjudications)
        coast = next(row for row in artifact["waterZones"] if row["kind"] == "COASTAL_SEA")
        geometry = next(
            row for row in artifact["geometryComponents"] if row["id"] == coast["geometryRef"]
        )
        geometry["cellRuns"] = [{"row": 51, "startCol": 731, "endCol": 732}]
        geometry["cellCount"] = 2

        with self.assertRaisesRegex(ValueError, "source.*member.*boundary|cited.*boundary"):
            self.audit.validate_artifact(tiles, tiles_bytes, adjudications, artifact)

    def test_bypass_artifact_rejects_nonadjacent_land_edge_from_decoded_owner_grid(self):
        tiles, tiles_bytes, adjudications, _ = self.builder.load_inputs()
        artifact = self.builder.build_water_topology(tiles, tiles_bytes, adjudications)
        artifact["traversalEdges"] = [{
            "id": "traversal-edge:invalid-dry-shortcut",
            "from": {"kind": "LAND_PROVINCE", "id": "42524"},
            "to": {"kind": "LAND_PROVINCE", "id": "42444"},
            "mode": "LAND", "directed": False, "movementCost": 1,
            "capacity": 1, "riskBand": "LOW", "seasonalAvailability": "ALWAYS",
            "supplyAllowed": False,
            "sourceRefs": [
                "territory-disconnection:42524@367:500",
                "territory-disconnection:PARENT-0101@340:544",
            ],
            "confidence": "REVIEWED", "barrierId": None, "directionPairKey": None,
        }]

        with self.assertRaisesRegex(ValueError, "decoded.*adjacent|owner.*adjacent"):
            self.audit.validate_artifact(tiles, tiles_bytes, adjudications, artifact)

    def test_bypass_artifact_rejects_crossing_while_river_activation_is_blocked(self):
        tiles, tiles_bytes, adjudications, _ = self.builder.load_inputs()
        artifact = self.builder.build_water_topology(tiles, tiles_bytes, adjudications)
        refs = [
            "territory-disconnection:42524@367:500",
            "territory-disconnection:PARENT-0101@340:544",
        ]
        artifact["riverBarriers"] = [{
            "id": "river-barrier:invalid-coastal-as-river",
            "firstLandProvinceId": "42524", "secondLandProvinceId": "42444",
            "sourceRefs": refs, "confidence": "REVIEWED",
        }]
        artifact["traversalEdges"] = [{
            "id": "traversal-edge:invalid-ford-across-sea",
            "from": {"kind": "LAND_PROVINCE", "id": "42524"},
            "to": {"kind": "LAND_PROVINCE", "id": "42444"},
            "mode": "FORD", "directed": False, "movementCost": 1,
            "capacity": 1, "riskBand": "LOW", "seasonalAvailability": "ALWAYS",
            "supplyAllowed": False, "sourceRefs": refs, "confidence": "REVIEWED",
            "barrierId": "river-barrier:invalid-coastal-as-river",
            "directionPairKey": None,
        }]

        with self.assertRaisesRegex(ValueError, "activation blocker|river activation"):
            self.audit.validate_artifact(tiles, tiles_bytes, adjudications, artifact)

    def test_bypass_artifact_rejects_coastal_source_as_river_evidence(self):
        tiles, tiles_bytes, adjudications, _ = self.builder.load_inputs()
        artifact = self.builder.build_water_topology(tiles, tiles_bytes, adjudications)
        artifact["activationBlockers"] = []
        source_ref = "territory-disconnection:PARENT-0101@340:544"
        artifact["riverBarriers"] = [{
            "id": "river-barrier:invalid-coastal-as-river",
            "firstLandProvinceId": "42424", "secondLandProvinceId": "42444",
            "sourceRefs": [source_ref], "confidence": "REVIEWED",
        }]

        with self.assertRaisesRegex(ValueError, "RIVER_BARRIER|source type"):
            self.audit.validate_artifact(tiles, tiles_bytes, adjudications, artifact)

    def test_blocked_candidate_does_not_satisfy_explicit_zone_isolation(self):
        tiles, tiles_bytes, adjudications, _ = self.builder.load_inputs()
        artifact = self.builder.build_water_topology(tiles, tiles_bytes, adjudications)
        coast = next(row for row in artifact["waterZones"] if row["kind"] == "COASTAL_SEA")
        coast["connectionStatus"] = "CONNECTED"

        with self.assertRaisesRegex(ValueError, "legal traversal edge"):
            self.audit.validate_artifact(tiles, tiles_bytes, adjudications, artifact)

    def test_approved_embark_must_touch_decoded_owner_boundary(self):
        tiles, tiles_bytes, adjudications, _ = self.builder.load_inputs()
        artifact = self.builder.build_water_topology(tiles, tiles_bytes, adjudications)
        coast = next(row for row in artifact["waterZones"] if row["kind"] == "COASTAL_SEA")
        coast["connectionStatus"] = "CONNECTED"
        artifact["traversalEdges"] = [{
            "id": "traversal-edge:unreviewed-hepu-embark",
            "from": {"kind": "LAND_PROVINCE", "id": "42524"},
            "to": {"kind": "WATER_ZONE", "id": coast["id"]},
            "mode": "EMBARK", "directed": False, "movementCost": 1,
            "capacity": 1, "riskBand": "HIGH", "seasonalAvailability": "ALWAYS",
            "supplyAllowed": False,
            "sourceRefs": [
                "territory-disconnection:42524@367:500",
                "territory-disconnection:PARENT-0101@340:544",
            ],
            "confidence": "REVIEWED", "barrierId": None, "directionPairKey": None,
        }]

        with self.assertRaisesRegex(ValueError, "owner boundary|touch"):
            self.audit.validate_artifact(tiles, tiles_bytes, adjudications, artifact)

    def test_blocked_route_land_endpoints_require_source_coverage(self):
        tiles, tiles_bytes, adjudications, _ = self.builder.load_inputs()
        artifact = self.builder.build_water_topology(tiles, tiles_bytes, adjudications)
        route = artifact["routeCandidates"][0]
        route["fromLandProvinceId"] = "200012"
        route["toLandProvinceId"] = "200026"
        route["sourceRefs"] = ["territory-disconnection:PARENT-0053@446:337"]

        with self.assertRaisesRegex(ValueError, "source coverage"):
            self.audit.validate_artifact(tiles, tiles_bytes, adjudications, artifact)

        route["sourceRefs"] = ["territory-disconnection:MISSING"]
        with self.assertRaisesRegex(ValueError, "sourceRefs"):
            self.audit.validate_artifact(tiles, tiles_bytes, adjudications, artifact)

    def test_duplicate_route_candidate_ids_are_rejected_independently(self):
        tiles, tiles_bytes, adjudications, _ = self.builder.load_inputs()
        artifact = json.loads(self.builder.render_outputs()[0])
        artifact["routeCandidates"].append(copy.deepcopy(artifact["routeCandidates"][0]))

        with self.assertRaisesRegex(ValueError, "duplicate route candidate"):
            self.audit.validate_artifact(tiles, tiles_bytes, adjudications, artifact)

    def test_cli_check_passes_but_explicit_river_activation_gate_fails(self):
        checked = subprocess.run(
            [sys.executable, str(MODULE), "--check"], cwd=ROOT,
            text=True, capture_output=True, check=False,
        )
        self.assertEqual(0, checked.returncode, checked.stderr)
        self.assertIn("waterZones=2", checked.stdout)
        self.assertIn("zoneKinds=COASTAL_SEA:1,LAKE_BASIN:1", checked.stdout)
        self.assertIn("edgeModes=none", checked.stdout)
        self.assertIn("evidenceSources=3", checked.stdout)
        self.assertIn("riverCrossingReady=false", checked.stdout)

        blocked = subprocess.run(
            [sys.executable, str(MODULE), "--require-river-activation"], cwd=ROOT,
            text=True, capture_output=True, check=False,
        )
        self.assertEqual(1, blocked.returncode)
        self.assertIn("NO_REVIEWED_RIVER_CROSSING_EVIDENCE", blocked.stdout)

    def test_summary_prints_deterministic_kind_mode_and_evidence_counts(self):
        result = {
            "counts": {
                "landProvinceIds": 3, "waterZones": 2, "riverBarriers": 0,
                "traversalEdges": 0, "evidenceSources": 3,
            },
            "zoneKinds": {"LAKE_BASIN": 1, "COASTAL_SEA": 1},
            "edgeModes": {},
            "activation": {"riverCrossingReady": False, "blockerCodes": ["NO_RIVER"]},
        }

        summary = self.audit._summary(result)

        self.assertIn("zoneKinds=COASTAL_SEA:1,LAKE_BASIN:1", summary)
        self.assertIn("edgeModes=none", summary)
        self.assertIn("evidenceSources=3", summary)


if __name__ == "__main__":
    unittest.main()
