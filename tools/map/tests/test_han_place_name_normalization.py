#!/usr/bin/env python3
from __future__ import annotations

import copy
import json
import unittest
from pathlib import Path

from tools.map.build_tile_grid import (
    normalize_han_place_names,
    validate_han_place_names,
)
from tools.map.materialize_han_place_names import normalize_city_const


ROOT = Path(__file__).resolve().parents[3]


class HanPlaceNameNormalizationTest(unittest.TestCase):
    def test_normalizes_reviewed_stable_ids_across_all_materialized_views(self) -> None:
        document = {
            "cities": [
                {"id": "85377", "name": "[\uac74\uad81\ud604]\ud604", "nameCh": "[\u5dfe\u5f13\u7384]\u53bf"},
                {"id": "85448", "name": "\uace1\uc131\uff08\uc131\uff09\ud604", "nameCh": "\u66f2\u6210\uff08\u57ce\uff09\u53bf"},
            ],
            "provinceRecords": [
                {"id": "85377", "displayName": "[\uac74\uad81\ud604]\ud604", "nameCh": "[\u5dfe\u5f13\u7384]\u53bf"},
            ],
            "jurisdictionRecords": [
                {"id": "85448", "displayName": "\uace1\uc131\uff08\uc131\uff09\ud604", "nameCh": "\u66f2\u6210\uff08\u57ce\uff09\u53bf"},
            ],
            "legacyGameplay": {
                "cities": [
                    {"id": "85377", "name": "[\uac74\uad81\ud604]\ud604", "nameCh": "[\u5dfe\u5f13\u7384]\u53bf"},
                ],
            },
        }

        normalized = normalize_han_place_names(document)

        self.assertEqual(("\uacac\ud604", "\u3849\u53bf"), (
            normalized["cities"][0]["name"], normalized["cities"][0]["nameCh"],
        ))
        self.assertEqual(("\uace1\uc131\ud604", "\u66f2\u6210\u53bf"), (
            normalized["cities"][1]["name"], normalized["cities"][1]["nameCh"],
        ))
        self.assertEqual("\uacac\ud604", normalized["provinceRecords"][0]["displayName"])
        self.assertEqual("\uace1\uc131\ud604", normalized["jurisdictionRecords"][0]["displayName"])
        self.assertEqual("\uacac\ud604", normalized["legacyGameplay"]["cities"][0]["name"])

    def test_rejects_upstream_text_drift_for_a_reviewed_id(self) -> None:
        document = {
            "cities": [{"id": "85377", "name": "\uc784\uc758", "nameCh": "\uc784\uc758"}],
        }

        with self.assertRaisesRegex(ValueError, "85377.*source name drift"):
            normalize_han_place_names(document)

    def test_canonical_tiles_have_no_source_annotation_names(self) -> None:
        document = json.loads((ROOT / "data/map/han-tiles.json").read_text(encoding="utf-8"))

        validate_han_place_names(document)

    def test_runtime_city_catalog_uses_the_same_canonical_names(self) -> None:
        document = json.loads(
            (ROOT / "infra/src/main/resources/map/han.json").read_text(encoding="utf-8")
        )
        by_physical_id = {row["physicalPlaceId"]: row for row in document["cities"]}

        self.assertEqual(("청하국 영", "灵县"), (
            by_physical_id["85202"]["name"],
            by_physical_id["85202"]["meta"]["nameCh"],
        ))
        self.assertEqual(("동래군 곡성", "曲成县"), (
            by_physical_id["85448"]["name"],
            by_physical_id["85448"]["meta"]["nameCh"],
        ))
        runtime_names = [row["name"] for row in document["cities"]]
        self.assertEqual(1, runtime_names.count("청하국 영"))
        self.assertEqual(1, runtime_names.count("동래군 곡성"))

        city_const = (
            ROOT
            / "common/src/main/kotlin/opensamguk/common/constants/HanCityConst.kt"
        ).read_text(encoding="utf-8")
        self.assertNotIn("영현（영현）", city_const)
        self.assertNotIn("곡성（성）", city_const)

    def test_runtime_constant_rejects_partial_source_name_drift(self) -> None:
        source = "\n".join(
            [f'RawCity({row_id}, "영현（영현）", listOf())' for row_id in (199, 200, 255, 274, 360)]
            + [f'RawCity({row_id}, "곡성（성）", listOf())' for row_id in (367, 371, 373)]
        )

        with self.assertRaisesRegex(ValueError, "occurrence drift"):
            normalize_city_const(source)

    def test_runtime_constant_rejects_post_normalization_target_drift(self) -> None:
        source = normalize_city_const((
            ROOT
            / "common/src/main/kotlin/opensamguk/common/constants/HanCityConst.kt"
        ).read_text(encoding="utf-8"))
        corrupted = source.replace('RawCity(200, "청하국 영",', 'RawCity(200, "임의",')

        with self.assertRaisesRegex(ValueError, "canonical runtime"):
            normalize_city_const(corrupted)

    def test_validation_rejects_new_annotation_pollution(self) -> None:
        document = {
            "cities": [
                {"id": "NEW-1", "name": "[부수]현", "nameCh": "[木]县"},
                {"id": "NEW-2", "name": "곡성(성)현", "nameCh": "曲成(城)县"},
            ],
            "provinceRecords": [],
            "jurisdictionRecords": [],
        }

        with self.assertRaisesRegex(ValueError, "source annotation"):
            validate_han_place_names(document)

    def test_normalization_does_not_merge_ethnic_ailao_with_ailao_county(self) -> None:
        document = {
            "cities": [
                {"id": "X060", "name": "\ub0a8\ub9cc", "nameCh": "\u54c0\u7262"},
                {"id": "80004", "name": "\uc560\ub8b0\ud604", "nameCh": "\u54c0\u7262\u53bf"},
            ],
        }

        normalized = normalize_han_place_names(copy.deepcopy(document))

        self.assertEqual(document, normalized)
        self.assertEqual({"X060", "80004"}, {row["id"] for row in normalized["cities"]})


if __name__ == "__main__":
    unittest.main()
