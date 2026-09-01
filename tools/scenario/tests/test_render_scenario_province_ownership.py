from __future__ import annotations

import unittest

from tools.scenario.province_ownership_materializer import ProvinceAssignment
from tools.scenario.render_scenario_province_ownership import (
    OUTSIDE_RGB,
    WATER_RGB,
    UNOWNED_RGB,
    nation_fill_rgb,
    render_map,
)


def assignment(province_id: str, owner_id: int | None) -> ProvinceAssignment:
    return ProvinceAssignment(
        scenario_code=1010,
        province_id=province_id,
        owner_nation_id=owner_id,
        owner_nation_key=None if owner_id is None else f"N{owner_id}",
        controller_city_id=None,
        winning_claim_id=f"C-{province_id}",
        claim_trace=(f"C-{province_id}",),
        basis_type="SCENARIO_BASELINE_UNOWNED" if owner_id is None else "ADMIN_REGION_CONTROL",
        evidence_ids=("E",),
        confidence="EXPLICIT_UNOWNED" if owner_id is None else "ADMIN_SCOPE",
        rationale="fixture",
    )


class RenderScenarioProvinceOwnershipTest(unittest.TestCase):
    def test_renderer_distinguishes_water_from_non_playable_black(self):
        map_doc = {
            "_meta": {
                "cols": 3,
                "rows": 1,
                "terrainLegend": {"0": "SEA", "4": "LAKE", "9": "OUT_OF_SCOPE"},
            },
            "terrain": ["049"],
            "owner": [[-1, 3]],
            "provinceRecords": [],
        }

        image = render_map(map_doc, (), {}, scale=1, draw_borders=False)

        self.assertEqual(WATER_RGB, image.getpixel((0, 0))[:3])
        self.assertEqual(WATER_RGB, image.getpixel((1, 0))[:3])
        self.assertEqual(OUTSIDE_RGB, image.getpixel((2, 0))[:3])

    def test_renderer_uses_black_outside_and_neutral_unowned(self):
        map_doc = {
            "_meta": {"cols": 3, "rows": 1},
            "owner": [[-1, 1], [0, 1], [1, 1]],
            "provinceRecords": [{"id": "U"}, {"id": "A"}],
        }

        image = render_map(
            map_doc,
            (assignment("U", None), assignment("A", 1)),
            {1: "#ff0000"},
            scale=3,
            draw_borders=False,
        )

        self.assertEqual(OUTSIDE_RGB, image.getpixel((1, 1))[:3])
        self.assertEqual(UNOWNED_RGB, image.getpixel((4, 1))[:3])
        self.assertEqual(nation_fill_rgb("#ff0000"), image.getpixel((7, 1))[:3])

    def test_fill_never_bleeds_across_province_identity(self):
        map_doc = {
            "_meta": {"cols": 2, "rows": 1},
            "owner": [[0, 1], [1, 1]],
            "provinceRecords": [{"id": "A"}, {"id": "B"}],
        }

        image = render_map(
            map_doc,
            (assignment("A", 1), assignment("B", 2)),
            {1: "#ff0000", 2: "#0000ff"},
            scale=4,
            draw_borders=False,
        )

        self.assertEqual(nation_fill_rgb("#ff0000"), image.getpixel((3, 2))[:3])
        self.assertEqual(nation_fill_rgb("#0000ff"), image.getpixel((4, 2))[:3])

    def test_border_keeps_a_visible_interior_for_one_cell_province(self):
        map_doc = {
            "_meta": {"cols": 1, "rows": 1},
            "owner": [[0, 1]],
            "provinceRecords": [{"id": "A"}],
        }

        image = render_map(map_doc, (assignment("A", 1),), {1: "#ff0000"}, scale=3)

        self.assertEqual(nation_fill_rgb("#ff0000"), image.getpixel((1, 1))[:3])


if __name__ == "__main__":
    unittest.main()
