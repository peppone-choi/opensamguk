from __future__ import annotations

import unittest

import numpy as np

from tools.map.province_quality import (
    ProvinceQualityPolicy,
    measure_province_shapes,
    validate_province_quality,
)


def validate_fixture(rows: list[list[int]], *, policy: ProvinceQualityPolicy | None = None) -> None:
    owner = np.asarray(rows, dtype=np.int32)
    records = [
        {"id": f"P-{index}", "parentRegionId": "R-A"}
        for index in sorted(set(owner.ravel()) - {-1})
    ]
    report = measure_province_shapes(owner, records)
    validate_province_quality(report, policy or ProvinceQualityPolicy(), [])


class ProvinceQualityTest(unittest.TestCase):
    def test_rejects_province_below_absolute_area_floor(self) -> None:
        with self.assertRaisesRegex(ValueError, "below absolute area floor"):
            validate_fixture(
                [[0, 0], [0, -1]],
                policy=ProvinceQualityPolicy(min_area=4),
            )

    def test_rejects_province_above_absolute_area_cap(self) -> None:
        with self.assertRaisesRegex(ValueError, "exceeds absolute area cap"):
            validate_fixture(
                [[0, 0, 0], [0, 0, 0], [0, 0, 0]],
                policy=ProvinceQualityPolicy(max_area=8),
            )

    def test_rejects_both_small_and_large_parent_relative_outliers(self) -> None:
        owner = np.full((10, 12), -1, dtype=np.int32)
        owner[0, 0] = 0
        owner[0:5, 1:5] = 1
        owner[:, 5:12] = 2
        records = [
            {"id": f"P-{index}", "parentRegionId": "R-A"}
            for index in range(3)
        ]
        report = measure_province_shapes(owner, records)
        with self.assertRaisesRegex(
            ValueError,
            "P-0 is below parent median area band.*P-2 exceeds parent median area band",
        ):
            validate_province_quality(
                report,
                ProvinceQualityPolicy(
                    min_parent_median_ratio=0.5,
                    max_parent_median_ratio=2.0,
                    corridor_min_length=10_000,
                ),
                [],
            )

    def test_rejects_disconnected_mainland(self) -> None:
        with self.assertRaisesRegex(ValueError, "disconnected"):
            validate_fixture([[0, 0, -1, 0, 0]])

    def test_rejects_one_cell_corridor(self) -> None:
        rows = [[-1] * 12 for _ in range(7)]
        for row in range(3):
            for col in range(3):
                rows[row][col] = 0
        for col in range(3, 11):
            rows[1][col] = 0
        with self.assertRaisesRegex(ValueError, "corridor"):
            validate_fixture(rows)

    def test_rejects_extreme_aspect_ratio(self) -> None:
        with self.assertRaisesRegex(ValueError, "aspect ratio"):
            validate_fixture([[0] * 9])

    def test_accepts_compact_connected_shape(self) -> None:
        validate_fixture([[0, 0, 0], [0, 0, 0], [0, 0, 0]])

    def test_exact_versioned_exception_can_waive_one_metric(self) -> None:
        owner = np.asarray([[0] * 13 for _ in range(3)], dtype=np.int32)
        report = measure_province_shapes(owner, [{"id": "P-0", "parentRegionId": "R-A"}])
        validate_province_quality(report, ProvinceQualityPolicy(), [{
            "provinceId": "P-0", "metric": "aspectRatio", "reason": "river valley",
            "evidence": "reviewed map", "effectiveMapVersion": "han-world-v2",
        }], map_version="han-world-v2")


if __name__ == "__main__":
    unittest.main()
