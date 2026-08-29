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
