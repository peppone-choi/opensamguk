"""Export source pins and ordered edges without allocating the world raster."""
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from tools.map import build_map_design as B
from tools.map import bake_topdown_map as C
from tools.map import export_metadata as E
from tools.map.tests.test_bake_topdown_map import make_export, make_repo


class ExportSourceTest(unittest.TestCase):
    def test_fingerprint_uses_actual_bytes_and_rejects_frozen_source_drift(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            paths = {}
            for key, filename in {"MAP_TILES": "tiles.json", "WORLD": "world.json", "ROADS": "roads.json",
                                  "DEM": "dem.png", "ECONOMY": "economy.json"}.items():
                paths[key] = root / filename
                paths[key].write_bytes(filename.encode())
            catalog = root / "data/map/province-world-20261003-artifacts/catalog.json"
            catalog.parent.mkdir(parents=True)
            catalog.write_text(json.dumps(dict(artifactId="fixture-map", files=[
                dict(path=paths[key].relative_to(root).as_posix(), sha256=B.sha256_bytes(paths[key].read_bytes()))
                for key in ("MAP_TILES", "WORLD", "ROADS")])) )
            design = root / "design"; design.mkdir(); source = design / "placements-v1.json"
            source.write_bytes(b'{"placements":[]}\n')
            helper = root / "tools/map/export_metadata.py"
            helper.parent.mkdir(parents=True)
            helper_bytes = (B.ROOT / "tools/map/export_metadata.py").read_bytes()
            helper.write_bytes(helper_bytes)
            with patch.multiple(B, ROOT=root, **paths):
                release, fingerprint = B.export_input_fingerprint(design)
                self.assertEqual("fixture-map", release)
                self.assertEqual(B.sha256_bytes(paths["DEM"].read_bytes()), fingerprint["demSha256"])
                self.assertEqual({"design/placements-v1.json": B.sha256_bytes(source.read_bytes())}, fingerprint["designJsonSha256"])
                self.assertEqual(B.sha256_bytes(helper_bytes), fingerprint["exportMetadataSha256"])
                helper.write_bytes(helper_bytes + b"\n# changed fixture helper\n")
                _, changed = B.export_input_fingerprint(design)
                self.assertEqual(B.sha256_bytes(helper.read_bytes()), changed["exportMetadataSha256"])
                self.assertNotEqual(fingerprint["exportMetadataSha256"], changed["exportMetadataSha256"])
                paths["MAP_TILES"].write_bytes(b"changed source")
                with self.assertRaisesRegex(ValueError, "frozen map release"):
                    B.export_input_fingerprint(design)

    def test_consumer_rejects_export_after_actual_helper_bytes_change(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            export = root / "export"; export.mkdir(); make_export(export)
            repo_dir = root / "repo"; repo_dir.mkdir(); repo = make_repo(repo_dir)
            helper = repo_dir / "tools/map/export_metadata.py"
            helper.parent.mkdir(parents=True)
            helper.write_bytes((B.ROOT / "tools/map/export_metadata.py").read_bytes())
            repo["exportMetadata"] = helper
            _, hashes = C.repo_inputs(repo)
            path = export / E.MANIFEST_FILE
            manifest = json.loads(path.read_bytes())
            edges = manifest.pop("roadEdges")
            for edge in edges:
                edge.update(fromProvinceId="a", toProvinceId="b", fromTrail=edge["cells"], toTrail=[])
            manifest["roadEdgesFile"] = E.write_road_edges(export, edges)
            keys = dict(tilesSha256="sourceTiles", worldJsonSha256="world", roadsSha256="roads",
                        demSha256="dem", economySha256="economy", artifactCatalogSha256="artifactCatalog",
                        exportMetadataSha256="exportMetadata")
            manifest["inputFingerprint"] = {key:hashes["repo/"+name] for key,name in keys.items()}
            manifest["inputFingerprint"].update(designJsonSha256={},
                exportGeneratorSha256=C.sha256((B.ROOT / "tools/map/build_map_design.py").read_bytes()))
            path.write_text(json.dumps(manifest))
            kit = SimpleNamespace(input_hashes=lambda:{})
            C.current_inputs(export, kit, repo)
            helper.write_bytes(helper.read_bytes() + b"\n# changed fixture helper\n")
            with self.assertRaisesRegex(ValueError, "metadata helper fingerprint"):
                C.current_inputs(export, kit, repo)

    def test_export_preserves_source_segments_and_connected_trajectory(self):
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp) / "roads.json"
            source.write_text(json.dumps(dict(edges=[dict(id="edge:1", fromProvinceId="P1", toProvinceId="P2")])))
            roads = {"edge:1": dict(status="BUILT", fromTrail=[[1, 2], [2, 3]], toTrail=[[4, 5], [3, 4]])}
            with patch.object(B, "ROADS", source):
                edge = B.ordered_road_edges(roads)[0]
            self.assertEqual("edge:1", edge["edgeId"])
            self.assertEqual([[2, 1], [3, 2]], edge["fromTrail"])
            self.assertEqual([[5, 4], [4, 3]], edge["toTrail"])
            self.assertEqual([[2, 1], [3, 2], [4, 3], [5, 4]], edge["cells"])


if __name__ == "__main__":
    unittest.main()
