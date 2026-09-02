#!/usr/bin/env python3
from __future__ import annotations

import copy
import hashlib
import json
import subprocess
import sys
import unittest
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
MAP_TOOLS = ROOT / "tools" / "map"
sys.path.insert(0, str(ROOT))

from tools.map.adjudicate_han_province_fragments import (  # noqa: E402
    expand_rle,
    materialize_document,
)


TILES = ROOT / "data" / "map" / "han-tiles.json"
LEDGER = ROOT / "data" / "curated" / "han" / "province-fragment-adjudications-v1.json"


def rle(values: list[int]) -> list[list[int]]:
    runs: list[list[int]] = []
    for value in values:
        if runs and runs[-1][0] == value:
            runs[-1][1] += 1
        else:
            runs.append([value, 1])
    return runs


def grid_digest(grid: list[list[int]]) -> str:
    return hashlib.sha256(
        json.dumps(grid, separators=(",", ":")).encode("utf-8")
    ).hexdigest()


def cell_set_digest(cells: list[list[int]]) -> str:
    return hashlib.sha256(
        json.dumps(sorted(cells), separators=(",", ":")).encode("utf-8")
    ).hexdigest()


def synthetic_document() -> dict:
    rows = cols = 7
    owner = [-1] * (rows * cols)
    terrain = [[0] * cols for _ in range(rows)]
    for row in range(1, 6):
        for col in range(1, 6):
            owner[row * cols + col] = 1
            terrain[row][col] = 1
    for col, row in ((1, 1), (2, 1), (1, 2), (2, 2), (4, 4)):
        owner[row * cols + col] = 0
    parent_owner = [value for value in owner]
    return {
        "_meta": {
            "rows": rows,
            "cols": cols,
            "terrainLegend": {"0": "SEA", "1": "PLAIN", "9": "OUT_OF_SCOPE"},
            "counts": {"adjCounty": 1, "adjCommandery": 1},
        },
        "terrain": ["".join(str(value) for value in row) for row in terrain],
        "owner": rle(owner),
        "parentOwner": rle(parent_owner),
        "seatOwner": [[9, rows * cols]],
        "adjacency": {
            "county": [{"a": 0, "b": 1, "cells": 8}],
            "commandery": [{"a": 0, "b": 1, "cells": 8, "cross": "LAND"}],
        },
        "provinceRecords": [
            {
                "id": "P-SOURCE",
                "displayName": "원현",
                "parentRegionId": "C-SOURCE",
                "jurisdictionId": "J-SOURCE",
                "cityIndex": 0,
            },
            {
                "id": "P-TARGET",
                "displayName": "대상현",
                "parentRegionId": "C-TARGET",
                "jurisdictionId": "J-TARGET",
                "cityIndex": 1,
            },
        ],
        "parentRegions": [
            {"id": "C-SOURCE", "displayName": "원군"},
            {"id": "C-TARGET", "displayName": "대상군"},
        ],
        "cities": [
            {"id": "P-SOURCE", "nameCh": "甲县", "col": 1, "row": 1},
            {"id": "P-TARGET", "nameCh": "乙县", "col": 3, "row": 3},
        ],
        "juns": [
            {"seat": 0, "col": 1, "row": 1},
            {"seat": 1, "col": 3, "row": 3},
        ],
    }


def synthetic_ledger() -> dict:
    document = synthetic_document()
    owner = expand_rle(document["owner"], 7, 7)
    patched_owner = copy.deepcopy(owner)
    patched_owner[4][4] = 1
    return {
        "contractId": "han-province-fragment-adjudications-v1",
        "minimumProvinceArea": 1,
        "inputOwnerSha256": grid_digest(owner),
        "outputOwnerSha256": grid_digest(patched_owner),
        "seatOwnerSha256": hashlib.sha256(
            json.dumps(document["seatOwner"], separators=(",", ":")).encode("utf-8")
        ).hexdigest(),
        "reassignments": [
            {
                "sourceProvinceId": "P-SOURCE",
                "targetProvinceId": "P-TARGET",
                "cells": [[4, 4]],
                "classification": "INLAND_FULLY_ENCLOSED_FRAGMENT",
                "evidence": {
                    "componentCellCount": 1,
                    "terrainClasses": ["PLAIN"],
                    "surroundingProvinceIds": ["P-TARGET"],
                    "containsSourceSeat": False,
                    "touchesNegative": False,
                },
            }
        ],
        "deferred": [],
    }


def pin_ledger_to_document(ledger: dict, document: dict) -> None:
    rows, cols = document["_meta"]["rows"], document["_meta"]["cols"]
    owner = expand_rle(document["owner"], rows, cols)
    patched_owner = copy.deepcopy(owner)
    province_index = {
        record["id"]: index for index, record in enumerate(document["provinceRecords"])
    }
    for decision in ledger["reassignments"]:
        target_index = province_index[decision["targetProvinceId"]]
        for col, row in decision["cells"]:
            patched_owner[row][col] = target_index
    ledger["inputOwnerSha256"] = grid_digest(owner)
    ledger["outputOwnerSha256"] = grid_digest(patched_owner)


class HanProvinceFragmentMaterializerTest(unittest.TestCase):
    def test_moves_only_the_exact_enclosed_component_and_is_idempotent(self) -> None:
        document = synthetic_document()
        before_owner = expand_rle(document["owner"], 7, 7)
        before_seat_owner = copy.deepcopy(document["seatOwner"])

        patched = materialize_document(document, synthetic_ledger())
        patched_owner = expand_rle(patched["owner"], 7, 7)
        patched_parent = expand_rle(patched["parentOwner"], 7, 7)

        changed = [
            [col, row]
            for row in range(7)
            for col in range(7)
            if patched_owner[row][col] != before_owner[row][col]
        ]
        self.assertEqual([[4, 4]], changed)
        self.assertEqual(1, patched_owner[4][4])
        self.assertEqual(1, patched_parent[4][4])
        self.assertEqual(before_seat_owner, patched["seatOwner"])
        self.assertEqual(1, patched["_meta"]["counts"]["adjCounty"])
        self.assertEqual(1, patched["_meta"]["counts"]["adjCommandery"])
        self.assertEqual(patched, materialize_document(patched, synthetic_ledger()))

    def test_rejects_stale_or_incomplete_component_coordinates(self) -> None:
        ledger = synthetic_ledger()
        ledger["reassignments"][0]["cells"] = [[4, 3]]

        with self.assertRaisesRegex(ValueError, "exact source component"):
            materialize_document(synthetic_document(), ledger)

    def test_rejects_a_component_that_contains_the_source_seat(self) -> None:
        ledger = synthetic_ledger()
        ledger["reassignments"][0]["cells"] = [[1, 1], [2, 1], [1, 2], [2, 2]]
        ledger["reassignments"][0]["evidence"].update(
            componentCellCount=4,
            containsSourceSeat=True,
        )

        with self.assertRaisesRegex(ValueError, "source seat"):
            materialize_document(synthetic_document(), ledger)

    def test_rejects_a_component_that_contains_any_runtime_place_anchor(self) -> None:
        document = synthetic_document()
        document["cities"].append(
            {"id": "COMMANDERY-MARKER", "nameCh": "丙郡", "col": 4, "row": 4}
        )

        with self.assertRaisesRegex(ValueError, "runtime place anchor"):
            materialize_document(document, synthetic_ledger())

    def test_rejects_negative_contact_even_when_the_ledger_claims_none(self) -> None:
        document = synthetic_document()
        owner = expand_rle(document["owner"], 7, 7)
        owner[4][4] = 1
        owner[3][5] = 0
        document["owner"] = rle([value for row in owner for value in row])
        parent = expand_rle(document["parentOwner"], 7, 7)
        parent[4][4] = 1
        parent[3][5] = 0
        document["parentOwner"] = rle([value for row in parent for value in row])
        ledger = synthetic_ledger()
        ledger["reassignments"][0]["cells"] = [[5, 3]]
        pin_ledger_to_document(ledger, document)

        with self.assertRaisesRegex(ValueError, "negative terrain"):
            materialize_document(document, ledger)

    def test_rejects_a_component_surrounded_by_multiple_provinces(self) -> None:
        document = synthetic_document()
        document["provinceRecords"].append(
            {
                "id": "P-OTHER",
                "displayName": "기타현",
                "parentRegionId": "C-TARGET",
                "jurisdictionId": "J-OTHER",
                "cityIndex": None,
            }
        )
        owner = expand_rle(document["owner"], 7, 7)
        owner[4][3] = 2
        document["owner"] = rle([value for row in owner for value in row])
        ledger = synthetic_ledger()
        pin_ledger_to_document(ledger, document)

        with self.assertRaisesRegex(ValueError, "single target province"):
            materialize_document(document, ledger)

    def test_rejects_parent_owner_that_disagrees_with_the_source_before_patch(self) -> None:
        document = synthetic_document()
        parent = expand_rle(document["parentOwner"], 7, 7)
        parent[4][4] = 1
        document["parentOwner"] = rle([value for row in parent for value in row])

        with self.assertRaisesRegex(ValueError, "parentOwner disagrees"):
            materialize_document(document, synthetic_ledger())

    def test_rejects_a_disconnected_province_missing_from_the_deferred_ledger(self) -> None:
        document = synthetic_document()
        document["provinceRecords"].append(
            {
                "id": "P-DEFERRED",
                "displayName": "보류현",
                "parentRegionId": "C-TARGET",
                "jurisdictionId": "J-DEFERRED",
                "cityIndex": None,
            }
        )
        owner = expand_rle(document["owner"], 7, 7)
        owner[1][3] = 2
        owner[5][5] = 2
        document["owner"] = rle([value for row in owner for value in row])
        ledger = synthetic_ledger()
        pin_ledger_to_document(ledger, document)

        with self.assertRaisesRegex(ValueError, "deferred ledger does not match"):
            materialize_document(document, ledger)

    def test_accepts_an_exact_deferred_component_fingerprint(self) -> None:
        document = synthetic_document()
        document["provinceRecords"].append(
            {
                "id": "P-DEFERRED",
                "displayName": "보류현",
                "parentRegionId": "C-TARGET",
                "jurisdictionId": "J-DEFERRED",
                "cityIndex": None,
            }
        )
        owner = expand_rle(document["owner"], 7, 7)
        owner[1][3] = 2
        owner[5][5] = 2
        document["owner"] = rle([value for row in owner for value in row])
        ledger = synthetic_ledger()
        ledger["deferred"] = [
            {
                "provinceId": "P-DEFERRED",
                "classification": "MARITIME_REVIEW_REQUIRED",
                "componentCellCounts": [1, 1],
                "cellSetSha256": cell_set_digest([[3, 1], [5, 5]]),
                "secondaryNegativeBoundaryTypes": ["SEA"],
                "secondarySurroundingProvinceIds": ["P-TARGET"],
                "reason": "The secondary component touches SEA and requires coastal review.",
            }
        ]
        pin_ledger_to_document(ledger, document)

        patched = materialize_document(document, ledger)

        self.assertEqual(patched, materialize_document(patched, ledger))

    def test_rejects_a_deferred_classification_that_disagrees_with_geometry(self) -> None:
        document = synthetic_document()
        document["provinceRecords"].append(
            {
                "id": "P-DEFERRED",
                "displayName": "보류현",
                "parentRegionId": "C-TARGET",
                "jurisdictionId": "J-DEFERRED",
                "cityIndex": None,
            }
        )
        owner = expand_rle(document["owner"], 7, 7)
        owner[1][3] = 2
        owner[5][5] = 2
        document["owner"] = rle([value for row in owner for value in row])
        ledger = synthetic_ledger()
        ledger["deferred"] = [
            {
                "provinceId": "P-DEFERRED",
                "classification": "INLAND_MULTI_NEIGHBOR_REVIEW_REQUIRED",
                "componentCellCounts": [1, 1],
                "cellSetSha256": cell_set_digest([[3, 1], [5, 5]]),
                "secondaryNegativeBoundaryTypes": [],
                "secondarySurroundingProvinceIds": ["P-TARGET"],
                "reason": "Incorrectly claims an inland separation.",
            }
        ]
        pin_ledger_to_document(ledger, document)

        with self.assertRaisesRegex(ValueError, "deferred classification drift"):
            materialize_document(document, ledger)


class HanProvinceFragmentCanonicalTest(unittest.TestCase):
    def test_canonical_map_matches_the_adjudication_ledger(self) -> None:
        result = subprocess.run(
            [
                sys.executable,
                str(MAP_TOOLS / "adjudicate_han_province_fragments.py"),
                "--source",
                str(TILES),
                "--ledger",
                str(LEDGER),
                "--output",
                str(TILES),
                "--check",
            ],
            cwd=ROOT,
            text=True,
            capture_output=True,
        )

        self.assertEqual(0, result.returncode, result.stderr or result.stdout)
        tiles = json.loads(TILES.read_text(encoding="utf-8"))
        ledger = json.loads(LEDGER.read_text(encoding="utf-8"))
        rows, cols = tiles["_meta"]["rows"], tiles["_meta"]["cols"]
        owner = expand_rle(tiles["owner"], rows, cols)
        province_index = {
            record["id"]: index for index, record in enumerate(tiles["provinceRecords"])
        }
        areas = Counter(value for row in owner for value in row if value >= 0)

        self.assertEqual((1524, 1020, 172), (
            len(tiles["provinceRecords"]),
            len(tiles["jurisdictionRecords"]),
            len(tiles["commanderyRecords"]),
        ))
        self.assertGreaterEqual(min(areas.values()), 8)
        for decision in ledger["reassignments"]:
            target_index = province_index[decision["targetProvinceId"]]
            self.assertTrue(all(
                owner[row][col] == target_index for col, row in decision["cells"]
            ))
        self.assertEqual(29, len(ledger["deferred"]))
        self.assertEqual(
            {
                "MARITIME_REVIEW_REQUIRED": 18,
                "LACUSTRINE_REVIEW_REQUIRED": 5,
                "INLAND_MULTI_NEIGHBOR_REVIEW_REQUIRED": 3,
                "ANCHOR_CONTAINING_REVIEW_REQUIRED": 3,
            },
            dict(Counter(row["classification"] for row in ledger["deferred"])),
        )
        self.assertEqual(
            ledger["seatOwnerSha256"],
            hashlib.sha256(
                json.dumps(tiles["seatOwner"], separators=(",", ":")).encode("utf-8")
            ).hexdigest(),
        )


if __name__ == "__main__":
    unittest.main()
