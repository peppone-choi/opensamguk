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


def json_digest(value: object) -> str:
    return hashlib.sha256(
        json.dumps(value, separators=(",", ":")).encode("utf-8")
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
        "inputCitiesSha256": json_digest(document["cities"]),
        "outputCitiesSha256": json_digest(document["cities"]),
        "inputJunsSha256": json_digest(document["juns"]),
        "outputJunsSha256": json_digest(document["juns"]),
        "seatOwnerSha256": hashlib.sha256(
            json.dumps(document["seatOwner"], separators=(",", ":")).encode("utf-8")
        ).hexdigest(),
        "preAppliedReassignmentCount": 0,
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
        "anchoredReassignments": [],
        "preservations": [],
    }


def synthetic_anchor_document() -> dict:
    document = synthetic_document()
    document["cities"].append(
        {
            "id": "COMMANDERY-SOURCE",
            "nameCh": "甲郡",
            "kind": "COMMANDERY",
            "col": 4,
            "row": 4,
        }
    )
    document["juns"][0] = {"seat": 2, "col": 4, "row": 4}
    document["jurisdictionRecords"] = [
        {
            "id": "J-SOURCE",
            "commanderyId": "C-SOURCE",
            "seatPlaceId": "P-SOURCE",
            "provinceIds": ["P-SOURCE"],
        },
        {
            "id": "J-TARGET",
            "commanderyId": "C-TARGET",
            "seatPlaceId": "P-TARGET",
            "provinceIds": ["P-TARGET"],
        },
    ]
    document["commanderyRecords"] = [
        {"id": "C-SOURCE", "seatJurisdictionId": "J-SOURCE"},
        {"id": "C-TARGET", "seatJurisdictionId": "J-TARGET"},
    ]
    return document


def synthetic_anchor_ledger() -> dict:
    document = synthetic_anchor_document()
    owner = expand_rle(document["owner"], 7, 7)
    patched_owner = copy.deepcopy(owner)
    patched_owner[4][4] = 1
    patched_cities = copy.deepcopy(document["cities"])
    patched_cities[2]["col"] = 1
    patched_cities[2]["row"] = 1
    patched_juns = copy.deepcopy(document["juns"])
    patched_juns[0]["col"] = 1
    patched_juns[0]["row"] = 1
    return {
        "contractId": "han-province-fragment-adjudications-v1",
        "minimumProvinceArea": 1,
        "inputOwnerSha256": grid_digest(owner),
        "outputOwnerSha256": grid_digest(patched_owner),
        "inputCitiesSha256": json_digest(document["cities"]),
        "outputCitiesSha256": json_digest(patched_cities),
        "inputJunsSha256": json_digest(document["juns"]),
        "outputJunsSha256": json_digest(patched_juns),
        "seatOwnerSha256": json_digest(document["seatOwner"]),
        "preAppliedReassignmentCount": 0,
        "reassignments": [],
        "anchoredReassignments": [
            {
                "sourceProvinceId": "P-SOURCE",
                "anchorPlaceId": "COMMANDERY-SOURCE",
                "anchorFrom": [4, 4],
                "anchorTo": [1, 1],
                "componentCells": [[4, 4]],
                "allowedTargetProvinceIds": ["P-TARGET"],
                "targetAssignments": [
                    {"targetProvinceId": "P-TARGET", "cells": [[4, 4]]}
                ],
                "classification": "COMMANDERY_ANCHOR_NORMALIZATION",
                "assignmentMethod": "NEAREST_TARGET_SEAT_EUCLIDEAN_SQUARED",
                "evidence": {
                    "sourceComponentCellCount": 1,
                    "terrainClasses": ["PLAIN"],
                    "surroundingProvinceIds": ["P-TARGET"],
                    "containsJurisdictionSeat": False,
                    "containedRuntimePlaceIds": ["COMMANDERY-SOURCE"],
                    "commanderyId": "C-SOURCE",
                    "seatJurisdictionId": "J-SOURCE",
                    "canonicalSeatPlaceId": "P-SOURCE",
                },
                "reason": "The commandery marker created a detached cell away from its canonical county seat.",
            }
        ],
        "deferred": [],
        "preservations": [],
    }


def synthetic_maritime_document() -> dict:
    rows = cols = 7
    owner = [[-1] * cols for _ in range(rows)]
    terrain = [[0] * cols for _ in range(rows)]
    for row, col in ((1, 1), (1, 2), (2, 1), (2, 2), (4, 4), (4, 5), (5, 4), (5, 5)):
        owner[row][col] = 0
        terrain[row][col] = 1
    flat = [value for row in owner for value in row]
    return {
        "_meta": {
            "rows": rows,
            "cols": cols,
            "terrainLegend": {"0": "SEA", "1": "PLAIN", "9": "OUT_OF_SCOPE"},
            "counts": {"adjCounty": 0, "adjCommandery": 0},
        },
        "terrain": ["".join(str(value) for value in row) for row in terrain],
        "owner": rle(flat),
        "parentOwner": rle(flat),
        "seatOwner": [[0, rows * cols]],
        "adjacency": {"county": [], "commandery": []},
        "provinceRecords": [
            {
                "id": "X-SOURCE",
                "displayName": "해상국",
                "administrativeSystem": "WA",
                "parentRegionId": "C-SOURCE",
                "jurisdictionId": "J-SOURCE",
                "cityIndex": 0,
            }
        ],
        "parentRegions": [{"id": "C-SOURCE", "displayName": "해상국"}],
        "jurisdictionRecords": [
            {
                "id": "J-SOURCE",
                "kind": "EXTERNAL_SETTLEMENT",
                "commanderyId": "C-SOURCE",
                "seatPlaceId": "X-SOURCE",
                "provinceIds": ["X-SOURCE"],
            }
        ],
        "commanderyRecords": [
            {"id": "C-SOURCE", "seatJurisdictionId": "J-SOURCE"}
        ],
        "cities": [
            {"id": "X-SOURCE", "nameCh": "海上國", "kind": "EXTERNAL_PLACE", "col": 4, "row": 4}
        ],
        "juns": [{"seat": 0, "col": 4, "row": 4}],
    }


def synthetic_maritime_ledger() -> dict:
    document = synthetic_maritime_document()
    owner = expand_rle(document["owner"], 7, 7)
    return {
        "contractId": "han-province-fragment-adjudications-v1",
        "minimumProvinceArea": 1,
        "inputOwnerSha256": grid_digest(owner),
        "outputOwnerSha256": grid_digest(owner),
        "inputCitiesSha256": json_digest(document["cities"]),
        "outputCitiesSha256": json_digest(document["cities"]),
        "inputJunsSha256": json_digest(document["juns"]),
        "outputJunsSha256": json_digest(document["juns"]),
        "seatOwnerSha256": json_digest(document["seatOwner"]),
        "preAppliedReassignmentCount": 0,
        "reassignments": [],
        "anchoredReassignments": [],
        "deferred": [],
        "preservations": [
            {
                "provinceId": "X-SOURCE",
                "classification": "MARITIME_ANCHORED_COMPONENT_PRESERVED",
                "componentCellCounts": [4, 4],
                "cellSetSha256": cell_set_digest(
                    [[1, 1], [2, 1], [1, 2], [2, 2], [4, 4], [5, 4], [4, 5], [5, 5]]
                ),
                "secondaryNegativeBoundaryTypes": ["SEA"],
                "secondarySurroundingProvinceIds": [],
                "containedRuntimePlaceIds": ["X-SOURCE"],
                "reason": "Both sea-separated polity components exceed the minimum area.",
            }
        ],
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
    ledger["inputCitiesSha256"] = json_digest(document["cities"])
    ledger["outputCitiesSha256"] = json_digest(document["cities"])
    ledger["inputJunsSha256"] = json_digest(document["juns"])
    ledger["outputJunsSha256"] = json_digest(document["juns"])


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
        ledger = synthetic_ledger()
        ledger["inputCitiesSha256"] = json_digest(document["cities"])
        ledger["outputCitiesSha256"] = json_digest(document["cities"])

        with self.assertRaisesRegex(ValueError, "runtime place anchor"):
            materialize_document(document, ledger)

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

    def test_normalizes_a_commandery_anchor_and_partitions_its_component(self) -> None:
        document = synthetic_anchor_document()
        before_seat_owner = copy.deepcopy(document["seatOwner"])

        patched = materialize_document(document, synthetic_anchor_ledger())
        owner = expand_rle(patched["owner"], 7, 7)
        parent_owner = expand_rle(patched["parentOwner"], 7, 7)

        self.assertEqual(1, owner[4][4])
        self.assertEqual(1, parent_owner[4][4])
        self.assertEqual((1, 1), (patched["cities"][2]["col"], patched["cities"][2]["row"]))
        self.assertEqual((1, 1), (patched["juns"][0]["col"], patched["juns"][0]["row"]))
        self.assertEqual(before_seat_owner, patched["seatOwner"])
        self.assertEqual(patched, materialize_document(patched, synthetic_anchor_ledger()))

    def test_rejects_anchor_normalization_when_the_canonical_seat_drifts(self) -> None:
        document = synthetic_anchor_document()
        document["commanderyRecords"][0]["seatJurisdictionId"] = "J-TARGET"
        ledger = synthetic_anchor_ledger()
        ledger["inputCitiesSha256"] = json_digest(document["cities"])
        ledger["inputJunsSha256"] = json_digest(document["juns"])

        with self.assertRaisesRegex(ValueError, "seatJurisdictionId"):
            materialize_document(document, ledger)

    def test_rejects_anchor_target_partition_that_is_not_nearest_to_seat(self) -> None:
        document = synthetic_anchor_document()
        document["provinceRecords"].append(
            {
                "id": "P-OTHER",
                "displayName": "기타현",
                "parentRegionId": "C-TARGET",
                "jurisdictionId": "J-OTHER",
                "cityIndex": 3,
            }
        )
        document["cities"].append(
            {"id": "P-OTHER", "nameCh": "丙县", "col": 5, "row": 4}
        )
        document["jurisdictionRecords"].append(
            {
                "id": "J-OTHER",
                "commanderyId": "C-TARGET",
                "seatPlaceId": "P-OTHER",
                "provinceIds": ["P-OTHER"],
            }
        )
        owner = expand_rle(document["owner"], 7, 7)
        owner[4][5] = 2
        document["owner"] = rle([value for row in owner for value in row])
        ledger = synthetic_anchor_ledger()
        decision = ledger["anchoredReassignments"][0]
        decision["allowedTargetProvinceIds"] = ["P-OTHER", "P-TARGET"]
        decision["evidence"]["surroundingProvinceIds"] = ["P-OTHER", "P-TARGET"]
        decision["targetAssignments"] = [
            {"targetProvinceId": "P-TARGET", "cells": [[4, 4]]}
        ]
        ledger["inputOwnerSha256"] = grid_digest(owner)
        ledger["inputCitiesSha256"] = json_digest(document["cities"])
        ledger["inputJunsSha256"] = json_digest(document["juns"])

        with self.assertRaisesRegex(ValueError, "nearest target seat"):
            materialize_document(document, ledger)

    def test_preserves_an_exact_anchored_maritime_polity(self) -> None:
        document = synthetic_maritime_document()
        ledger = synthetic_maritime_ledger()

        self.assertEqual(document, materialize_document(document, ledger))

    def test_rejects_maritime_preservation_when_sea_evidence_drifts(self) -> None:
        document = synthetic_maritime_document()
        terrain = [list(row) for row in document["terrain"]]
        terrain[3][4] = "9"
        document["terrain"] = ["".join(row) for row in terrain]

        with self.assertRaisesRegex(ValueError, "preservation .* drift"):
            materialize_document(document, synthetic_maritime_ledger())


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
        self.assertEqual(26, len(ledger["deferred"]))
        self.assertEqual(
            {
                "MARITIME_REVIEW_REQUIRED": 18,
                "LACUSTRINE_REVIEW_REQUIRED": 5,
                "INLAND_MULTI_NEIGHBOR_REVIEW_REQUIRED": 3,
            },
            dict(Counter(row["classification"] for row in ledger["deferred"])),
        )
        self.assertEqual(2, len(ledger["anchoredReassignments"]))
        self.assertEqual(
            ["X055"], [row["provinceId"] for row in ledger["preservations"]]
        )
        city_by_id = {city["id"]: city for city in tiles["cities"]}
        self.assertEqual((423, 386), (city_by_id["32540"]["col"], city_by_id["32540"]["row"]))
        self.assertEqual((435, 174), (city_by_id["210314"]["col"], city_by_id["210314"]["row"]))
        self.assertEqual(ledger["outputCitiesSha256"], json_digest(tiles["cities"]))
        self.assertEqual(ledger["outputJunsSha256"], json_digest(tiles["juns"]))
        self.assertEqual(
            ledger["seatOwnerSha256"],
            hashlib.sha256(
                json.dumps(tiles["seatOwner"], separators=(",", ":")).encode("utf-8")
            ).hexdigest(),
        )


if __name__ == "__main__":
    unittest.main()
