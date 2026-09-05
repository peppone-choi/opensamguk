"""API and daemon images must receive the same authoritative route inputs."""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[3]
REQUIRED = {
    "data/map/han-tiles.json",
    "data/map/han-world-v3-manifest-v1.json",
    "data/map/han-water-topology-v1.json",
    "data/map/han-strategic-topology-manifest-v1.json",
    "data/curated/han/route-node-selection-v1.json",
    "data/curated/han/route-node-migration-v1.json",
    "data/curated/han/water-topology-adjudications-v1.json",
}


def exact_copies(document):
    return {
        parts[1] for line in document.splitlines()
        if len(parts := line.split()) == 3 and parts[0] == "COPY"
        and parts[2] == f"/app/{parts[1]}"
    }


class StrategicTopologyPackagingTest(unittest.TestCase):
    def test_both_images_package_exactly_addressable_canonical_inputs(self):
        for name in ("game-api", "game-engine"):
            with self.subTest(image=name):
                document = (ROOT / "docker" / f"{name}.Dockerfile").read_text()
                self.assertTrue(REQUIRED <= exact_copies(document))
                for path in REQUIRED:
                    self.assertTrue((ROOT / path).is_file(), path)
        for resource in ("han-world-v3.json", "han-780-v1.json"):
            self.assertTrue((ROOT / "infra/src/main/resources/map" / resource).is_file())

    def test_removed_or_misdirected_copy_is_detected(self):
        document = (ROOT / "docker/game-engine.Dockerfile").read_text()
        for path in REQUIRED:
            with self.subTest(path=path):
                misplaced = document.replace(f"/app/{path}", f"/app/missing/{path}")
                self.assertFalse(REQUIRED <= exact_copies(misplaced))


if __name__ == "__main__":
    unittest.main()
