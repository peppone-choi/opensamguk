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

    def test_source_selector_must_resolve_one_upheld_dossier_row(self):
        tiles, tiles_bytes, adjudications, _ = self.builder.load_inputs()
        artifact = json.loads(self.builder.render_outputs()[0])
        missing = copy.deepcopy(adjudications)
        missing["sourceCatalog"][0]["sourceId"] = "territory-disconnection:MISSING"
        missing["sourceCatalog"][0]["selector"]["componentKey"] = "MISSING"

        with self.assertRaisesRegex(ValueError, "existing evidence row"):
            self.audit.validate_artifact(tiles, tiles_bytes, missing, artifact)

    def test_cli_check_passes_but_explicit_river_activation_gate_fails(self):
        checked = subprocess.run(
            [sys.executable, str(MODULE), "--check"], cwd=ROOT,
            text=True, capture_output=True, check=False,
        )
        self.assertEqual(0, checked.returncode, checked.stderr)
        self.assertIn("waterZones=2", checked.stdout)
        self.assertIn("riverCrossingReady=false", checked.stdout)

        blocked = subprocess.run(
            [sys.executable, str(MODULE), "--require-river-activation"], cwd=ROOT,
            text=True, capture_output=True, check=False,
        )
        self.assertEqual(1, blocked.returncode)
        self.assertIn("NO_REVIEWED_RIVER_CROSSING_EVIDENCE", blocked.stdout)


if __name__ == "__main__":
    unittest.main()
