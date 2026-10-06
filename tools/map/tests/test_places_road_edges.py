"""The public place table preserves the pinned design road identity and order."""
import gzip
import json
import unittest

import numpy as np

from tools.map import bake_topdown_map as B
from tools.map import build_topdown_artifact as A
from tools.map.tests.test_bake_topdown_map import BakeFixture


class PlacesRoadEdgesTest(unittest.TestCase):
    def test_place_table_keeps_ordered_cells_and_unbuilt_status(self):
        docs = dict(
            sourceTiles=dict(provinceRecords=[], jurisdictionRecords=[],
                             commanderyRecords=[], parentRegions=[]),
            juIndex=dict(byTerrainSha256={"fixture": []}), tilesSha256="fixture",
            economy=dict(jurisdictions=[]), world=dict(cities=[]),
        )
        st = dict(castles=[], passes=[], spans={}, cls=np.full((4, 4), "L"),
                  road=np.zeros((4, 4), bool))
        roads = [dict(edgeId="edge:b", status="UNBUILT", cells=[[3, 1], [2, 2]]),
                 dict(edgeId="edge:a", status="BUILT", cells=[[0, 3], [1, 2], [2, 2]])]
        places = B.build_places(docs, [], st, np.zeros((4, 4), np.uint16), roads)
        self.assertEqual({
            "edge:a": dict(status="BUILT", cells=[[0, 3], [1, 2], [2, 2]]),
            "edge:b": dict(status="UNBUILT", cells=[[3, 1], [2, 2]]),
        }, places["roadEdges"])
        self.assertEqual([], places["passes"])
        self.assertEqual([], places["cities"])

    def test_duplicate_identity_cannot_silently_drop_a_road(self):
        roads = [dict(edgeId="edge:a", status="BUILT", cells=[[0, 1]]),
                 dict(edgeId="edge:a", status="UNBUILT", cells=[[1, 0]])]
        with self.assertRaisesRegex(ValueError, "duplicate"):
            B.public_road_edges(roads)

    def test_empty_source_is_explicit_and_is_not_a_fabricated_road(self):
        self.assertEqual({}, B.public_road_edges([]))


class RoadWindowGuardsTest(unittest.TestCase):
    def window(self, edge):
        layers = {key: np.zeros((4, 4), np.uint16) for key in
                  ("ground", "relief", "riverTier", "riverWidth", "landcover", "owner")}
        return B.export_window_inputs(layers, [edge])

    def test_unbuilt_diagonal_is_valid_but_does_not_become_raster_road(self):
        window = self.window(dict(edgeId="planned", status="UNBUILT", cells=[[0, 1], [1, 0]]))
        self.assertFalse(window["road"].any())

    def test_unknown_status_is_rejected(self):
        for status in ("PLANNED", "", None, True, []):
            with self.subTest(status=status), self.assertRaisesRegex(ValueError, "road status"):
                self.window(dict(edgeId="bad", status=status, cells=[[0, 0]]))

    def test_unbuilt_outside_map_is_rejected(self):
        for cell in ([4, 0], [0, 4], [-1, 0], [True, 0]):
            with self.subTest(cell=cell), self.assertRaises(ValueError):
                self.window(dict(edgeId="planned", status="UNBUILT", cells=[cell]))

    def test_unbuilt_nonadjacent_cells_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "non-adjacent"):
            self.window(dict(edgeId="planned", status="UNBUILT", cells=[[0, 0], [2, 2]]))

    def test_unbuilt_source_segments_are_validated_before_raster_selection(self):
        for segment in ([[0, 0], [3, 3]], [[4, 0]]):
            with self.subTest(segment=segment), self.assertRaises(ValueError):
                self.window(dict(edgeId="planned", status="UNBUILT", cells=[[0, 0]],
                                 fromTrail=segment, toTrail=[[0, 0]]))
        with self.assertRaisesRegex(ValueError, "only one source segment"):
            self.window(dict(edgeId="planned", status="UNBUILT", cells=[[0, 0]], fromTrail=[[0, 0]]))


class PublishedRoadEdgesTest(BakeFixture):
    def test_bake_wires_source_roads_and_check_rejects_rehashed_geometry(self):
        path = self.out / "places.json.gz"
        places = json.loads(gzip.decompress(path.read_bytes()))
        self.assertEqual([60, 40], places["roadEdges"]["fixture:0"]["cells"][0])
        self.assertEqual([60, 80], places["roadEdges"]["fixture:0"]["cells"][-1])
        self.assertEqual(4, len(places["roadEdges"]))
        packaged = B.package_bundle(self.root / "export", self.root / "kit", self.out,
                                    self.root / "published", log=lambda *_: None, repo=self.repo)
        audited = A.audit_bundle(packaged)
        self.assertEqual("PASS", audited["placesDisplayAudit"]["status"])
        self.assertFalse(audited["publicScope"]["publicationApproved"])
        self.assertEqual([], B.check(self.root / "export", self.root / "kit", self.out,
                                    log=lambda *_: None, repo=self.repo))

        # Updating both public hashes must not make different source geometry valid.
        places["roadEdges"]["fixture:0"]["cells"][0] = [40, 60]
        raw = json.dumps(places, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
        blob = B.gz(raw)
        path.write_bytes(blob)
        manifest_path = self.out / "manifest.json"
        manifest = json.loads(manifest_path.read_bytes())
        for entry in [manifest["places"], *manifest["files"]]:
            if entry["file"] == "places.json.gz":
                entry.update(sha256=B.sha256(blob), rawSha256=B.sha256(raw), bytes=len(blob))
        manifest_path.write_text(json.dumps(manifest))
        self.assertIn("public road edges differ from pinned export",
                      B.check(self.root / "export", self.root / "kit", self.out,
                              log=lambda *_: None, repo=self.repo))


if __name__ == "__main__":
    unittest.main()
