from __future__ import annotations

import copy
import json
import unittest
from pathlib import Path

from tools.map import build_terrain_grid as terrain_builder


ROOT = Path(__file__).resolve().parents[3]
REGISTRY = ROOT / "data" / "curated" / "han" / "commandery-id-registry-v1.json"
TILES = ROOT / "data" / "map" / "han-tiles.json"


def registry(entries: list[dict], next_ordinal: int) -> dict:
    return {
        "schemaVersion": 1,
        "registryId": "han-commandery-id-registry-v1",
        "nextOrdinal": next_ordinal,
        "entries": entries,
    }


def entry(identity: str, ordinal: int, status: str = "ACTIVE") -> dict:
    return {
        "identity": identity,
        "commanderyId": f"PARENT-{ordinal:04d}",
        "status": status,
    }


class CommanderyIdRegistryTest(unittest.TestCase):
    def setUp(self) -> None:
        self.document = registry(
            [entry("甲郡", 0), entry("乙郡", 1), entry("丙郡", 2)],
            next_ordinal=3,
        )

    def load(self, roster: list[str], document: dict | None = None) -> dict[str, str]:
        return terrain_builder.load_commandery_id_registry(
            document or self.document,
            roster,
        )

    def test_source_roster_order_does_not_change_ids(self) -> None:
        self.assertEqual(
            {"甲郡": "PARENT-0000", "乙郡": "PARENT-0001", "丙郡": "PARENT-0002"},
            self.load(["丙郡", "甲郡", "乙郡"]),
        )

    def test_retired_identity_preserves_its_gap_and_later_ids(self) -> None:
        retired = copy.deepcopy(self.document)
        retired["entries"][1]["status"] = "RETIRED"

        self.assertEqual(
            {"甲郡": "PARENT-0000", "丙郡": "PARENT-0002"},
            self.load(["甲郡", "丙郡"], retired),
        )

    def test_new_identity_must_take_the_next_never_issued_id(self) -> None:
        appended = copy.deepcopy(self.document)
        appended["entries"].append(entry("丁郡", 3))
        appended["nextOrdinal"] = 4

        self.assertEqual("PARENT-0003", self.load(["甲郡", "乙郡", "丙郡", "丁郡"], appended)["丁郡"])

        skipped = copy.deepcopy(self.document)
        skipped["entries"].append(entry("丁郡", 4))
        skipped["nextOrdinal"] = 5
        with self.assertRaisesRegex(ValueError, "never-issued|contiguous"):
            self.load(["甲郡", "乙郡", "丙郡", "丁郡"], skipped)

    def test_duplicate_missing_and_retired_reuse_fail_closed(self) -> None:
        duplicate_identity = copy.deepcopy(self.document)
        duplicate_identity["entries"].append(entry("甲郡", 3))
        duplicate_identity["nextOrdinal"] = 4
        with self.assertRaisesRegex(ValueError, "duplicate identity"):
            self.load(["甲郡", "乙郡", "丙郡"], duplicate_identity)

        duplicate_id = copy.deepcopy(self.document)
        duplicate_id["entries"].append(entry("丁郡", 1))
        with self.assertRaisesRegex(ValueError, "duplicate commandery ID|retired-ID reuse"):
            self.load(["甲郡", "乙郡", "丙郡", "丁郡"], duplicate_id)

        with self.assertRaisesRegex(ValueError, "missing current identity"):
            self.load(["甲郡", "乙郡", "丙郡", "丁郡"])

        retired = copy.deepcopy(self.document)
        retired["entries"][1]["status"] = "RETIRED"
        with self.assertRaisesRegex(ValueError, "retired current identity"):
            self.load(["甲郡", "乙郡", "丙郡"], retired)

    def test_committed_registry_exactly_matches_all_generated_parent_surfaces(self) -> None:
        registry_document = json.loads(REGISTRY.read_text(encoding="utf-8"))
        tiles = json.loads(TILES.read_text(encoding="utf-8"))
        roster = [row["nameCh"] for row in tiles["parentRegions"]]
        mapping = self.load(roster, registry_document)

        self.assertEqual(172, len(mapping))
        self.assertEqual(
            [mapping[name] for name in roster],
            [row["id"] for row in tiles["parentRegions"]],
        )
        parent_ids = set(mapping.values())
        self.assertEqual(parent_ids, {row["id"] for row in tiles["commanderyRecords"]})
        self.assertTrue(all(row["parentRegionId"] in parent_ids for row in tiles["provinceRecords"]))


if __name__ == "__main__":
    unittest.main()
