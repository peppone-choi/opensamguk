"""Export source pins and ordered edges without allocating the world raster."""
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from tools.map import build_map_design as B


class ExportSourceTest(unittest.TestCase):
    def test_fingerprint_uses_actual_bytes_and_rejects_frozen_source_drift(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            paths = {}
            for key, filename in {"HAN_TILES": "tiles.json", "WORLD": "world.json", "ROADS": "roads.json",
                                  "DEM": "dem.png", "ECONOMY": "economy.json"}.items():
                paths[key] = root / filename
                paths[key].write_bytes(filename.encode())
            catalog = root / "data/map/han-world-v3-1428-artifacts-v1/catalog.json"
            catalog.parent.mkdir(parents=True)
            catalog.write_text(json.dumps(dict(artifactId="fixture-map", files=[
                dict(path=paths[key].relative_to(root).as_posix(), sha256=B.sha256_bytes(paths[key].read_bytes()))
                for key in ("HAN_TILES", "WORLD", "ROADS")])) )
            design = root / "design"; design.mkdir(); source = design / "placements-v1.json"
            source.write_bytes(b'{"placements":[]}\n')
            with patch.multiple(B, ROOT=root, **paths):
                release, fingerprint = B.export_input_fingerprint(design)
                self.assertEqual("fixture-map", release)
                self.assertEqual(B.sha256_bytes(paths["DEM"].read_bytes()), fingerprint["demSha256"])
                self.assertEqual({"design/placements-v1.json": B.sha256_bytes(source.read_bytes())}, fingerprint["designJsonSha256"])
                paths["HAN_TILES"].write_bytes(b"changed source")
                with self.assertRaisesRegex(ValueError, "frozen map release"):
                    B.export_input_fingerprint(design)

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
