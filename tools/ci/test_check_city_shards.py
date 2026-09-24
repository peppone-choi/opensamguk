"""Cross-job city shard manifest checks."""

import json
import tempfile
import unittest
from pathlib import Path

from check_city_shards import check


class CityShardCoverageTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def write(self, index, selected):
        (self.root / f"shard-{index}.json").write_text(json.dumps({
            "shardIndex": index,
            "shardCount": 2,
            "allCityIds": [1, 2, 3],
            "selectedCityIds": selected,
        }), encoding="utf-8")

    def test_complete_union(self):
        self.write(0, [1, 3])
        self.write(1, [2])
        self.assertEqual(check(self.root), (2, 3))

    def test_missing_city_fails(self):
        self.write(0, [1])
        self.write(1, [2])
        with self.assertRaisesRegex(ValueError, "union differs"):
            check(self.root)

    def test_duplicate_city_fails(self):
        self.write(0, [1, 3])
        self.write(1, [2, 3])
        with self.assertRaisesRegex(ValueError, "union differs"):
            check(self.root)

    def test_missing_shard_fails(self):
        self.write(0, [1, 3])
        with self.assertRaisesRegex(ValueError, "indices incomplete"):
            check(self.root)


if __name__ == "__main__":
    unittest.main()
