from __future__ import annotations

import copy
import hashlib
import json
import subprocess
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
MODULE = ROOT / "tools" / "map" / "build_han_water_topology.py"
sys.path.insert(0, str(ROOT / "tools" / "map"))


def canonical_bytes(value: object) -> bytes:
    return (json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ) + "\n").encode("utf-8")


def base_document() -> dict:
    return {
        "_meta": {
            "cols": 6,
            "rows": 4,
            "projection": {
                "cell": 1.0, "cols": 6, "k": 1.0, "lat0": 0.0,
                "pad": 0.0, "rows": 4, "x0": 0.0, "y1": 4.0,
            },
            "terrainLegend": {
                "0": "SEA", "1": "PLAIN", "3": "RIVER", "4": "LAKE",
            },
        },
        "terrain": ["141111", "143311", "100001", "111111"],
        "owner": [
            [1, 1], [-1, 1], [2, 4],
            [1, 1], [-1, 3], [2, 2],
            [1, 1], [-1, 4], [2, 1], [0, 6],
        ],
        "provinceRecords": [{"id": "P3"}, {"id": "P1"}, {"id": "P2"}],
    }


def source(
    source_id: str,
    component_key: str,
    unit_id: str,
    member_ids: list[str],
    path: str = "data/curated/han/territory-disconnection-adjudications-v1.json",
) -> dict:
    return {
        "sourceId": source_id,
        "path": path,
        "selector": {"componentKey": component_key},
        "claim": f"reviewed evidence for {component_key}",
        "reviewState": "UPHELD",
        "unitId": unit_id,
        "memberIds": member_ids,
    }


def zone(
    stable_key: str,
    kind: str,
    geometry_selector: dict,
    source_id: str,
) -> dict:
    return {
        "stableKey": stable_key,
        "kind": kind,
        "geometrySelector": geometry_selector,
        "sourceRefs": [source_id],
        "confidence": "REVIEWED",
        "flowDirection": None,
        "depthBand": None,
        "seasonalAvailability": "ALWAYS",
        "status": "APPROVED",
        "connectionStatus": "ISOLATED_NO_REVIEWED_CONNECTION",
    }


def valid_ledger(builder) -> dict:
    base = base_document()
    base_bytes = canonical_bytes(base)
    binding = builder.water_overlay_base_binding(base, base_bytes)
    lake_source = "territory-disconnection:LAKE-1"
    coast_source = "territory-disconnection:COAST-1"
    return {
        "schemaVersion": 1,
        "ledgerId": "han-water-topology-adjudications-v1",
        "topologyRevision": "han-water-topology-v1",
        "base": binding,
        "sourceCatalog": [
            source(lake_source, "LAKE-1", "P3", ["P1", "P2", "P3"]),
            source(coast_source, "COAST-1", "COAST-UNIT", ["P1", "P2"]),
        ],
        "zoneAdjudications": [
            zone(
                "lake-test-basin",
                "LAKE_BASIN",
                {
                    "kind": "TERRAIN_COMPONENT", "terrainCode": 4,
                    "seedRow": 1, "seedCol": 1, "expectedCellCount": 2,
                },
                lake_source,
            ),
            zone(
                "coastal-test-strait",
                "COASTAL_SEA",
                {
                    "kind": "CELL_RANGES", "terrainCode": 0,
                    "cellRuns": [{"row": 2, "startCol": 1, "endCol": 4}],
                    "expectedCellCount": 4,
                },
                coast_source,
            ),
        ],
        "barrierAdjudications": [],
        "edgeAdjudications": [],
        "routeCandidates": [{
            "stableKey": "coastal-p1-p2",
            "fromLandProvinceId": "P1",
            "toLandProvinceId": "P2",
            "viaZoneStableKey": "coastal-test-strait",
            "mode": "COASTAL",
            "status": "BLOCKED_PENDING_REVIEW",
            "blockerCode": "NO_REVIEWED_PORT_OR_LANDING_EVIDENCE",
            "sourceRefs": [coast_source],
        }],
        "activationBlockers": [{
            "feature": "RIVER_CROSSING",
            "status": "BLOCKED",
            "code": "NO_REVIEWED_RIVER_CROSSING_EVIDENCE",
            "requiredEvidence": ["named river reach", "crossing type", "two reviewed land endpoints"],
        }],
    }


def edge(
    stable_key: str,
    mode: str,
    from_kind: str,
    from_id: str,
    to_kind: str,
    to_id: str,
    source_id: str,
    **overrides,
) -> dict:
    value = {
        "stableKey": stable_key,
        "from": {"kind": from_kind, "id": from_id},
        "to": {"kind": to_kind, "id": to_id},
        "mode": mode,
        "directed": mode in {"RIVER_UP", "RIVER_DOWN"},
        "movementCost": 2,
        "capacity": 10,
        "riskBand": "LOW",
        "seasonalAvailability": "ALWAYS",
        "supplyAllowed": False,
        "sourceRefs": [source_id],
        "confidence": "REVIEWED",
        "status": "APPROVED",
        "barrierStableKey": None,
        "directionPairKey": None,
    }
    value.update(overrides)
    return value


class HanWaterTopologyBuilderTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        import build_han_water_topology as builder
        cls.builder = builder

    def build(self, ledger=None):
        base = base_document()
        base_bytes = canonical_bytes(base)
        return self.builder.build_water_topology(
            base, base_bytes, ledger or valid_ledger(self.builder)
        )

    def test_stable_ids_and_bytes_ignore_input_array_and_source_ref_order(self):
        ledger = valid_ledger(self.builder)
        first = self.builder.render_artifact(base_document(), canonical_bytes(base_document()), ledger)
        shuffled = copy.deepcopy(ledger)
        for field in (
            "sourceCatalog", "zoneAdjudications", "barrierAdjudications",
            "edgeAdjudications", "routeCandidates", "activationBlockers",
        ):
            shuffled[field].reverse()
        for row in shuffled["zoneAdjudications"] + shuffled["routeCandidates"]:
            row["sourceRefs"].reverse()

        second = self.builder.render_artifact(
            base_document(), canonical_bytes(base_document()), shuffled
        )

        self.assertEqual(first, second)
        artifact = json.loads(first)
        self.assertEqual(
            ["water-zone:coastal-test-strait", "water-zone:lake-test-basin"],
            [row["id"] for row in artifact["waterZones"]],
        )

    def test_artifact_has_the_exact_closed_schema_and_no_land_ownership_projection(self):
        artifact = self.build()
        self.assertEqual(
            {
                "schemaVersion", "artifactId", "topologyRevision", "base",
                "landProvinceIds", "geometryComponents", "waterZones",
                "riverBarriers", "traversalEdges", "routeCandidates",
                "activationBlockers",
            },
            set(artifact),
        )
        self.assertEqual(["P1", "P2", "P3"], artifact["landProvinceIds"])
        self.assertNotIn("owner", artifact)
        self.assertNotIn("provinceRecords", artifact)
        self.assertEqual([], artifact["traversalEdges"])
        self.assertEqual([], artifact["riverBarriers"])

    def test_evidence_endpoint_mode_pair_and_crossing_contracts_fail_closed(self):
        ledger = valid_ledger(self.builder)
        ledger["zoneAdjudications"][0]["sourceRefs"] = []
        with self.assertRaisesRegex(ValueError, "sourceRefs"):
            self.build(ledger)

        ledger = valid_ledger(self.builder)
        source_id = ledger["sourceCatalog"][0]["sourceId"]
        ledger["edgeAdjudications"] = [
            edge("missing-zone", "LAKE", "WATER_ZONE", "missing", "WATER_ZONE", "lake-test-basin", source_id)
        ]
        with self.assertRaisesRegex(ValueError, "endpoint"):
            self.build(ledger)

        ledger = valid_ledger(self.builder)
        ledger["edgeAdjudications"] = [
            edge("one-way-road", "LAND", "LAND_PROVINCE", "P1", "LAND_PROVINCE", "P2", source_id, directed=True)
        ]
        with self.assertRaisesRegex(ValueError, "directed|mode"):
            self.build(ledger)

        ledger = valid_ledger(self.builder)
        barrier_source_id = "river-barrier:P2-P3"
        crossing_source_id = "river-crossing:P2-P3"
        ledger["sourceCatalog"].extend([
            source(
                barrier_source_id, "P2-P3", "P2", ["P2", "P3"],
                "data/curated/han/river-barrier-adjudications-v1.json",
            ),
            source(
                crossing_source_id, "P2-P3", "P2", ["P2", "P3"],
                "data/curated/han/river-crossing-adjudications-v1.json",
            ),
        ])
        ledger["activationBlockers"] = []
        ledger["barrierAdjudications"] = [{
            "stableKey": "river-p2-p3", "firstLandProvinceId": "P2",
            "secondLandProvinceId": "P3", "sourceRefs": [barrier_source_id],
            "confidence": "REVIEWED", "status": "APPROVED",
        }]
        ledger["edgeAdjudications"] = [
            edge(
                "unapproved-ford", "FORD", "LAND_PROVINCE", "P2",
                "LAND_PROVINCE", "P3", crossing_source_id,
                barrierStableKey="river-p2-p3", status="CANDIDATE",
            )
        ]
        with self.assertRaisesRegex(ValueError, "unapproved crossing"):
            self.build(ledger)

    def test_reviewed_river_flow_requires_a_reverse_up_down_pair(self):
        ledger = valid_ledger(self.builder)
        source_id = ledger["sourceCatalog"][0]["sourceId"]
        ledger["zoneAdjudications"].extend([
            zone("river-upper", "RIVER_REACH", {
                "kind": "TERRAIN_COMPONENT", "terrainCode": 3,
                "seedRow": 1, "seedCol": 2, "expectedCellCount": 2,
            }, source_id),
            zone("river-lower", "RIVER_REACH", {
                "kind": "TERRAIN_COMPONENT", "terrainCode": 3,
                "seedRow": 1, "seedCol": 2, "expectedCellCount": 2,
            }, source_id),
        ])
        ledger["zoneAdjudications"][-2]["connectionStatus"] = "CONNECTED"
        ledger["zoneAdjudications"][-1]["connectionStatus"] = "CONNECTED"
        ledger["edgeAdjudications"] = [
            edge(
                "river-down", "RIVER_DOWN", "WATER_ZONE", "river-upper",
                "WATER_ZONE", "river-lower", source_id,
                directionPairKey="river-pair",
            )
        ]
        with self.assertRaisesRegex(ValueError, "directed-flow pair"):
            self.build(ledger)

        ledger["edgeAdjudications"].append(
            edge(
                "river-up", "RIVER_UP", "WATER_ZONE", "river-lower",
                "WATER_ZONE", "river-upper", source_id,
                directionPairKey="river-pair",
            )
        )
        artifact = self.build(ledger)
        self.assertEqual(
            ["traversal-edge:river-down", "traversal-edge:river-up"],
            [row["id"] for row in artifact["traversalEdges"]],
        )

    def test_reviewed_crossing_requires_and_materializes_its_exact_barrier(self):
        ledger = valid_ledger(self.builder)
        barrier_source_id = "river-barrier:P2-P3"
        crossing_source_id = "river-crossing:P2-P3"
        ledger["sourceCatalog"].extend([
            source(
                barrier_source_id, "P2-P3", "P2", ["P2", "P3"],
                "data/curated/han/river-barrier-adjudications-v1.json",
            ),
            source(
                crossing_source_id, "P2-P3", "P2", ["P2", "P3"],
                "data/curated/han/river-crossing-adjudications-v1.json",
            ),
        ])
        ledger["activationBlockers"] = []
        crossing = edge(
            "reviewed-ford", "FORD", "LAND_PROVINCE", "P2",
            "LAND_PROVINCE", "P3", crossing_source_id,
            barrierStableKey="river-p2-p3",
        )
        ledger["edgeAdjudications"] = [crossing]
        with self.assertRaisesRegex(ValueError, "requires a reviewed river barrier"):
            self.build(ledger)

        ledger["barrierAdjudications"] = [{
            "stableKey": "river-p2-p3", "firstLandProvinceId": "P2",
            "secondLandProvinceId": "P3", "sourceRefs": [barrier_source_id],
            "confidence": "REVIEWED", "status": "APPROVED",
        }]
        artifact = self.build(ledger)
        self.assertEqual("river-barrier:river-p2-p3", artifact["riverBarriers"][0]["id"])
        self.assertEqual("traversal-edge:reviewed-ford", artifact["traversalEdges"][0]["id"])
        self.assertEqual(
            "river-barrier:river-p2-p3", artifact["traversalEdges"][0]["barrierId"]
        )

    def test_per_tile_zone_ids_and_open_sea_geometry_are_forbidden(self):
        ledger = valid_ledger(self.builder)
        ledger["zoneAdjudications"][0]["stableKey"] = "lake-cell-1-1"
        with self.assertRaisesRegex(ValueError, "per-water-tile"):
            self.build(ledger)

        ledger = valid_ledger(self.builder)
        ledger["zoneAdjudications"][1]["geometrySelector"] = {
            "kind": "TERRAIN_COMPONENT", "terrainCode": 0,
            "seedRow": 2, "seedCol": 1, "expectedCellCount": 4,
        }
        with self.assertRaisesRegex(ValueError, "deep-sea|CELL_RANGES"):
            self.build(ledger)

    def test_single_cell_named_zone_cannot_masquerade_as_a_waterbody(self):
        ledger = valid_ledger(self.builder)
        ledger["zoneAdjudications"][0]["stableKey"] = "lake-r1-c1"
        ledger["zoneAdjudications"][0]["geometrySelector"] = {
            "kind": "CELL_RANGES", "terrainCode": 4,
            "cellRuns": [{"row": 1, "startCol": 1, "endCol": 1}],
            "expectedCellCount": 1,
        }

        with self.assertRaisesRegex(ValueError, "minimum component|per-water-tile"):
            self.build(ledger)

    def test_reviewed_cell_ranges_must_form_one_connected_waterbody(self):
        ledger = valid_ledger(self.builder)
        ledger["zoneAdjudications"][1]["geometrySelector"] = {
            "kind": "CELL_RANGES", "terrainCode": 0,
            "cellRuns": [
                {"row": 2, "startCol": 1, "endCol": 1},
                {"row": 2, "startCol": 4, "endCol": 4},
            ],
            "expectedCellCount": 2,
        }

        with self.assertRaisesRegex(ValueError, "connected waterbody"):
            self.build(ledger)

    def test_coastal_zone_must_touch_a_decoded_land_owner_boundary(self):
        base = base_document()
        base["owner"] = [[1, 1], [-1, 1], [2, 4], [1, 1], [-1, 17]]
        base_bytes = canonical_bytes(base)
        ledger = valid_ledger(self.builder)
        ledger["base"] = self.builder.water_overlay_base_binding(base, base_bytes)

        with self.assertRaisesRegex(ValueError, "coastal.*owner boundary|shoreline"):
            self.builder.build_water_topology(base, base_bytes, ledger)

    def test_production_coastal_geometry_must_touch_its_cited_component_members(self):
        tiles, tiles_bytes, ledger, _ = self.builder.load_inputs()
        coast = next(
            row for row in ledger["zoneAdjudications"] if row["kind"] == "COASTAL_SEA"
        )
        coast["geometrySelector"] = {
            "kind": "CELL_RANGES", "terrainCode": 0,
            "cellRuns": [{"row": 51, "startCol": 731, "endCol": 732}],
            "expectedCellCount": 2,
        }

        with self.assertRaisesRegex(ValueError, "source.*member.*boundary|cited.*boundary"):
            self.builder.build_water_topology(tiles, tiles_bytes, ledger)

    def test_production_nonadjacent_land_edge_is_rejected_from_decoded_owner_grid(self):
        tiles, tiles_bytes, ledger, _ = self.builder.load_inputs()
        dry_edge = edge(
            "invalid-dry-shortcut", "LAND", "LAND_PROVINCE", "42524",
            "LAND_PROVINCE", "42444", "territory-disconnection:42524@367:500",
        )
        dry_edge["sourceRefs"] = [
            "territory-disconnection:42524@367:500",
            "territory-disconnection:PARENT-0101@340:544",
        ]
        ledger["edgeAdjudications"] = [dry_edge]

        with self.assertRaisesRegex(ValueError, "decoded.*adjacent|owner.*adjacent"):
            self.builder.build_water_topology(tiles, tiles_bytes, ledger)

    def test_river_activation_blocker_rejects_any_executable_barrier_or_crossing(self):
        tiles, tiles_bytes, ledger, _ = self.builder.load_inputs()
        refs = [
            "territory-disconnection:42524@367:500",
            "territory-disconnection:PARENT-0101@340:544",
        ]
        ledger["barrierAdjudications"] = [{
            "stableKey": "invalid-coastal-as-river",
            "firstLandProvinceId": "42524", "secondLandProvinceId": "42444",
            "sourceRefs": refs, "confidence": "REVIEWED", "status": "APPROVED",
        }]
        ford = edge(
            "invalid-ford-across-sea", "FORD", "LAND_PROVINCE", "42524",
            "LAND_PROVINCE", "42444", refs[0],
            barrierStableKey="invalid-coastal-as-river",
        )
        ford["sourceRefs"] = refs
        ledger["edgeAdjudications"] = [ford]

        with self.assertRaisesRegex(ValueError, "activation blocker|river activation"):
            self.builder.build_water_topology(tiles, tiles_bytes, ledger)

    def test_coastal_disconnection_source_cannot_masquerade_as_river_evidence(self):
        ledger = valid_ledger(self.builder)
        ledger["activationBlockers"] = []
        coast_source = ledger["sourceCatalog"][1]["sourceId"]
        ledger["barrierAdjudications"] = [{
            "stableKey": "invalid-coastal-as-river",
            "firstLandProvinceId": "P1", "secondLandProvinceId": "P2",
            "sourceRefs": [coast_source], "confidence": "REVIEWED", "status": "APPROVED",
        }]

        with self.assertRaisesRegex(ValueError, "RIVER_BARRIER|source type"):
            self.build(ledger)

    def test_zone_without_legal_edge_requires_explicit_isolation_adjudication(self):
        ledger = valid_ledger(self.builder)
        del ledger["zoneAdjudications"][0]["connectionStatus"]

        with self.assertRaisesRegex(ValueError, "explicit isolation|connectionStatus"):
            self.build(ledger)

    def test_blocked_route_candidate_is_not_a_legal_zone_connection(self):
        ledger = valid_ledger(self.builder)
        for row in ledger["zoneAdjudications"]:
            row["connectionStatus"] = "ISOLATED_NO_REVIEWED_CONNECTION"
        ledger["zoneAdjudications"][1]["connectionStatus"] = "CONNECTED"

        with self.assertRaisesRegex(ValueError, "legal traversal edge"):
            self.build(ledger)

    def test_blocked_route_endpoints_must_be_covered_by_exact_source_members(self):
        ledger = valid_ledger(self.builder)
        unrelated_source = "territory-disconnection:OTHER-1"
        ledger["sourceCatalog"].append(
            source(unrelated_source, "OTHER-1", "P3", ["P3"])
        )
        ledger["routeCandidates"][0]["sourceRefs"] = [unrelated_source]

        with self.assertRaisesRegex(ValueError, "source coverage"):
            self.build(ledger)

    def test_approved_land_water_edge_must_touch_decoded_owner_boundary(self):
        ledger = valid_ledger(self.builder)
        lake_source = ledger["sourceCatalog"][0]["sourceId"]
        ledger["zoneAdjudications"][0]["connectionStatus"] = "CONNECTED"
        ledger["edgeAdjudications"] = [edge(
            "p3-lake-embark", "EMBARK", "LAND_PROVINCE", "P3",
            "WATER_ZONE", "lake-test-basin", lake_source,
        )]

        with self.assertRaisesRegex(ValueError, "owner boundary|touch"):
            self.build(ledger)

    def test_approved_edge_land_endpoint_requires_related_source_member(self):
        ledger = valid_ledger(self.builder)
        unrelated_source = "territory-disconnection:OTHER-1"
        ledger["sourceCatalog"].append(
            source(unrelated_source, "OTHER-1", "P3", ["P3"])
        )
        ledger["zoneAdjudications"][1]["connectionStatus"] = "CONNECTED"
        ledger["edgeAdjudications"] = [edge(
            "p1-coast-embark", "EMBARK", "LAND_PROVINCE", "P1",
            "WATER_ZONE", "coastal-test-strait", unrelated_source,
        )]

        with self.assertRaisesRegex(ValueError, "source coverage"):
            self.build(ledger)

    def test_committed_outputs_are_byte_identical_to_a_fresh_render(self):
        artifact_bytes, manifest_bytes = self.builder.render_outputs()
        self.assertTrue(self.builder.OUTPUT.is_file(), "water topology artifact is not materialized")
        self.assertTrue(self.builder.MANIFEST.is_file(), "water topology manifest is not materialized")
        self.assertEqual(self.builder.OUTPUT.read_bytes(), artifact_bytes)
        self.assertEqual(self.builder.MANIFEST.read_bytes(), manifest_bytes)
        self.assertEqual(
            hashlib.sha256(artifact_bytes).hexdigest(),
            json.loads(manifest_bytes)["files"]["waterTopology"]["sha256"],
        )

    def test_duplicate_route_candidate_stable_ids_are_rejected(self):
        ledger = valid_ledger(self.builder)
        ledger["routeCandidates"].append(copy.deepcopy(ledger["routeCandidates"][0]))

        with self.assertRaisesRegex(ValueError, "duplicate route candidate"):
            self.build(ledger)

    def test_cli_exposes_write_and_check(self):
        result = subprocess.run(
            [sys.executable, str(MODULE), "--help"], cwd=ROOT,
            text=True, capture_output=True, check=False,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("--write", result.stdout)
        self.assertIn("--check", result.stdout)


if __name__ == "__main__":
    unittest.main()
