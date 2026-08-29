"""Reviewed temporal parent transitions applied to terrain commandery ownership."""

from __future__ import annotations

import copy
import inspect
import json
import tempfile
import unittest
from pathlib import Path

import numpy as np

from tools.map import build_terrain_grid as terrain_builder
try:
    from tools.map import han_temporal_parent_runtime as temporal_runtime
except ImportError:  # RED: the production runtime does not exist yet.
    temporal_runtime = None


ROOT = Path(__file__).resolve().parents[3]
RUNTIME_PRESEAT = temporal_runtime is not None and "owner" not in inspect.signature(
    temporal_runtime.apply_reviewed_temporal_parents
).parameters
FINALIZE_HAS_TEMPORAL = "temporal_seed_overrides" in inspect.signature(
    terrain_builder.derive_commandery_ownership
).parameters if hasattr(terrain_builder, "derive_commandery_ownership") else False


class TemporalParentRuntimeApiRedTest(unittest.TestCase):
    def test_reviewed_temporal_parent_runtime_exists(self):
        self.assertIsNotNone(temporal_runtime)
        self.assertTrue(hasattr(temporal_runtime, "load_reviewed_temporal_parent_context"))
        self.assertTrue(hasattr(temporal_runtime, "apply_reviewed_temporal_parents"))
        self.assertTrue(RUNTIME_PRESEAT)

    def test_terrain_exposes_pre_dijkstra_temporal_seed_derivation(self):
        self.assertTrue(hasattr(terrain_builder, "derive_commandery_ownership"))
        self.assertTrue(FINALIZE_HAS_TEMPORAL)


@unittest.skipUnless(RUNTIME_PRESEAT, "pre-seat runtime is intentionally absent during RED")
class TemporalParentRuntimeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.context = temporal_runtime.load_reviewed_temporal_parent_context()

    def state(self):
        return {
            "places": [
                {
                    "id": "85083", "nameCh": "卫国", "nameFt": "衛國",
                    "typeCh": "国", "begYr": 37, "endYr": 265,
                    "lon": 115.11117, "lat": 35.88519, "gx": 422, "gy": 209,
                },
                {
                    "id": "east-hub", "nameCh": "東郡治", "nameFt": "東郡治",
                    "gx": 419, "gy": 209, "lon": 114.9, "lat": 35.9,
                },
                {
                    "id": "wei-hub", "nameCh": "魏郡治", "nameFt": "魏郡治",
                    "gx": 425, "gy": 209, "lon": 115.3, "lat": 35.9,
                },
            ],
            "jun_of": np.array([0, 0, 1], dtype=np.int32),
            "jun_names": ["東郡", "魏郡", "頓丘"],
        }

    def apply(self, year, **changes):
        state = self.state()
        state.update(changes)
        return temporal_runtime.apply_reviewed_temporal_parents(
            **state,
            year=year,
            context=self.context,
        )

    def test_transition_boundary_resolves_211_to_east_and_212_220_to_wei(self):
        expected = {211: 0, 212: 1, 220: 1}
        for year, parent_index in expected.items():
            with self.subTest(year=year):
                result = self.apply(year)
                self.assertEqual(parent_index, int(result["junOf"][0]))
                self.assertNotEqual(2, int(result["junOf"][0]))

    def test_override_returns_exact_pre_dijkstra_seed_label_only(self):
        result = self.apply(220)

        self.assertEqual([1, 0, 1], result["junOf"].tolist())
        self.assertEqual(1, len(result["seedOverrides"]))
        override = result["seedOverrides"][0]
        self.assertEqual((0, 1, "衞"), (
            override.place_index, override.parent_index, override.history_child_name,
        ))
        self.assertEqual("衛", override.junguozhi_child_name)
        self.assertEqual("東郡", override.initial_source_parent_name)

    def test_every_reviewed_physical_witness_field_is_fail_closed(self):
        mutations = {
            "nameCh": "錯國", "nameFt": "錯國", "typeCh": "县",
            "begYr": 38, "endYr": 264, "lon": 115.11118,
            "lat": 35.88518, "gx": 421, "gy": 208,
        }
        for field, value in mutations.items():
            places = copy.deepcopy(self.state()["places"])
            places[0][field] = value
            with self.subTest(field=field), self.assertRaisesRegex(
                ValueError, "physical witness"
            ):
                self.apply(220, places=places)

    def test_missing_identity_unknown_jun_and_canonical_year_mismatch_fail_closed(self):
        mutations = [
            ("missing physical", {
                "places": self.state()["places"][1:],
                "jun_of": np.array([0, 1], dtype=np.int32),
            }),
            ("missing parent jun", {
                "jun_names": ["東郡", "頓丘"],
                "jun_of": np.array([0, 0, 1], dtype=np.int32),
            }),
            ("duplicate parent jun", {"jun_names": ["東郡", "魏郡", "魏郡"]}),
            ("wrong initial parent", {"jun_of": np.array([2, 0, 1], dtype=np.int32)}),
        ]
        for label, changes in mutations:
            with self.subTest(label=label), self.assertRaises(ValueError):
                self.apply(220, **changes)
        with self.assertRaisesRegex(ValueError, "reference year"):
            temporal_runtime.apply_reviewed_temporal_parents(
                **self.state(),
                year=212,
                context=self.context,
                require_reference_year=True,
            )

    def test_unknown_history_parent_and_forbidden_identity_mutations_fail_in_loader(self):
        temporal_path = ROOT / "data/curated/han/administrative-temporal-adjudications-v1.json"
        history_path = ROOT / "data/map/han-administrative-history.json"
        temporal = json.loads(temporal_path.read_text(encoding="utf-8"))
        history = json.loads(history_path.read_text(encoding="utf-8"))
        cases = []
        unknown_parent = copy.deepcopy(temporal)
        unknown_parent["adjudications"][0]["parentIntervals"][1]["parentId"] = (
            "commandery:hhs:110:999"
        )
        cases.append(("unknown parent", unknown_parent, history))
        forbidden = copy.deepcopy(temporal)
        forbidden["adjudications"][0]["administrativeUnitId"] = "hhs:111:東郡:004"
        cases.append(("forbidden 頓丘 identity", forbidden, history))
        mismatched_history = copy.deepcopy(history)
        relation = next(
            row for row in mismatched_history["relations"]
            if row["childId"] == "county:hhs:111:24:14"
        )
        relation["parentId"] = "commandery:hhs:111:24"
        cases.append(("history mismatch", temporal, mismatched_history))

        for label, temporal_document, history_document in cases:
            with self.subTest(label=label), tempfile.TemporaryDirectory() as directory:
                directory = Path(directory)
                temporal_copy = directory / "temporal.json"
                history_copy = directory / "history.json"
                temporal_copy.write_text(json.dumps(temporal_document), encoding="utf-8")
                history_copy.write_text(json.dumps(history_document), encoding="utf-8")
                with self.assertRaises(ValueError):
                    temporal_runtime.load_reviewed_temporal_parent_context(
                        temporal_path=temporal_copy,
                        history_path=history_copy,
                    )

    def test_loader_rejects_a_coordinated_physical_witness_mutation(self):
        temporal = json.loads((
            ROOT / "data/curated/han/administrative-temporal-adjudications-v1.json"
        ).read_text(encoding="utf-8"))
        temporal["adjudications"][0]["physicalWitness"].update(
            nameCh="錯國", nameFt="錯國"
        )
        with tempfile.TemporaryDirectory() as directory:
            temporal_copy = Path(directory) / "temporal.json"
            temporal_copy.write_text(json.dumps(temporal), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "physical witness"):
                temporal_runtime.load_reviewed_temporal_parent_context(
                    temporal_path=temporal_copy
                )


@unittest.skipUnless(
    RUNTIME_PRESEAT and FINALIZE_HAS_TEMPORAL,
    "terrain integration is intentionally absent during RED",
)
class TerrainTemporalParentIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.context = temporal_runtime.load_reviewed_temporal_parent_context()
    class IdentityProj:
        @staticmethod
        def to_cell(lon, lat):
            if (lon, lat) == (115.11117, 35.88519):
                return 422.9744, 209.0746
            if (lon, lat) == (115.0365, 35.86877):
                return 421.0, 209.0
            return lon, lat

    def test_prelabel_override_drives_initial_seat_owner_dijkstra_and_adjacency(self):
        state = TemporalParentRuntimeTest().state()
        temporal = temporal_runtime.apply_reviewed_temporal_parents(
            **state, year=220, context=self.context, require_reference_year=True,
        )
        field = np.ones((215, 430), dtype=float)
        junguo = [{
            "name": "東郡",
            "counties": [
                {"name": "衛", "lon": 115.11117, "lat": 35.88519},
                {"name": "頓丘", "lon": 115.0365, "lat": 35.86877},
            ],
        }]

        seat_owner = terrain_builder.derive_commandery_ownership(
            field=field,
            places=state["places"],
            jun_of=temporal["junOf"],
            jun_names=state["jun_names"],
            junguo=junguo,
            proj=self.IdentityProj(),
            temporal_seed_overrides=temporal["seedOverrides"],
        )

        self.assertEqual(1, int(seat_owner[209, 422]))
        self.assertEqual(1, int(seat_owner[208, 422]))
        edges = {
            frozenset((edge["a"], edge["b"]))
            for edge in terrain_builder.adjacency(seat_owner)
        }
        self.assertIn(frozenset((0, 1)), edges)

    def test_conflicting_junguozhi_seed_at_reviewed_cell_fails_closed(self):
        state = TemporalParentRuntimeTest().state()
        temporal = temporal_runtime.apply_reviewed_temporal_parents(
            **state, year=220, context=self.context,
        )
        junguo = [{
            "name": "東郡",
            "counties": [{"name": "頓丘", "lon": 115.11117, "lat": 35.88519}],
        }]
        with self.assertRaisesRegex(ValueError, "reviewed temporal seed"):
            terrain_builder.derive_commandery_ownership(
                field=np.ones((215, 430), dtype=float),
                places=state["places"], jun_of=temporal["junOf"],
                jun_names=state["jun_names"], junguo=junguo,
                proj=self.IdentityProj(),
                temporal_seed_overrides=temporal["seedOverrides"],
            )

    def test_reviewed_junguozhi_child_at_wrong_coordinate_fails_closed(self):
        state = TemporalParentRuntimeTest().state()
        temporal = temporal_runtime.apply_reviewed_temporal_parents(
            **state, year=220, context=self.context,
        )
        junguo = [{
            "name": "東郡",
            "counties": [{"name": "衛", "lon": 115.11118, "lat": 35.88519}],
        }]
        with self.assertRaisesRegex(ValueError, "mismatched 郡國志 coordinate"):
            terrain_builder.derive_commandery_ownership(
                field=np.ones((215, 430), dtype=float),
                places=state["places"], jun_of=temporal["junOf"],
                jun_names=state["jun_names"], junguo=junguo,
                proj=self.IdentityProj(),
                temporal_seed_overrides=temporal["seedOverrides"],
            )

    def test_reviewed_junguozhi_seed_must_be_consumed_exactly_once_under_initial_parent(self):
        state = TemporalParentRuntimeTest().state()
        temporal = temporal_runtime.apply_reviewed_temporal_parents(
            **state, year=220, context=self.context,
        )
        reviewed_row = {"name": "衛", "lon": 115.11117, "lat": 35.88519}
        ordinary_row = {"name": "頓丘", "lon": 115.0365, "lat": 35.86877}
        mutations = {
            "deleted": [{"name": "東郡", "counties": [ordinary_row]}],
            "moved to active parent": [
                {"name": "東郡", "counties": [ordinary_row]},
                {"name": "魏郡", "counties": [reviewed_row]},
            ],
            "duplicated": [{
                "name": "東郡", "counties": [reviewed_row, reviewed_row, ordinary_row],
            }],
        }
        for label, junguo in mutations.items():
            with self.subTest(label=label), self.assertRaisesRegex(
                ValueError, "reviewed temporal seed"
            ):
                terrain_builder.derive_commandery_ownership(
                    field=np.ones((215, 430), dtype=float),
                    places=state["places"], jun_of=temporal["junOf"],
                    jun_names=state["jun_names"], junguo=junguo,
                    proj=self.IdentityProj(),
                    temporal_seed_overrides=temporal["seedOverrides"],
                )

    def test_reviewed_junguozhi_projection_must_match_bounded_physical_cell(self):
        state = TemporalParentRuntimeTest().state()
        temporal = temporal_runtime.apply_reviewed_temporal_parents(
            **state, year=220, context=self.context,
        )
        junguo = [{
            "name": "東郡",
            "counties": [{"name": "衛", "lon": 115.11117, "lat": 35.88519}],
        }]

        class WrongInBoundsProj:
            @staticmethod
            def to_cell(lon, lat):
                return 400.0, 200.0

        class OutOfBoundsProj:
            @staticmethod
            def to_cell(lon, lat):
                return 500.0, 300.0

        for label, proj in (
            ("wrong in-bounds cell", WrongInBoundsProj()),
            ("out-of-bounds cell", OutOfBoundsProj()),
        ):
            with self.subTest(label=label), self.assertRaisesRegex(
                ValueError, "reviewed temporal seed"
            ):
                terrain_builder.derive_commandery_ownership(
                    field=np.ones((215, 430), dtype=float),
                    places=state["places"], jun_of=temporal["junOf"],
                    jun_names=state["jun_names"], junguo=junguo, proj=proj,
                    temporal_seed_overrides=temporal["seedOverrides"],
                )

    def test_non_target_junguozhi_seed_behavior_is_unchanged_without_overrides(self):
        state = TemporalParentRuntimeTest().state()
        seat_owner = terrain_builder.derive_commandery_ownership(
            field=np.ones((215, 430), dtype=float),
            places=state["places"], jun_of=state["jun_of"],
            jun_names=state["jun_names"],
            junguo=[{
                "name": "東郡",
                "counties": [{"name": "頓丘", "lon": 115.0365, "lat": 35.86877}],
            }],
            proj=self.IdentityProj(), temporal_seed_overrides=(),
        )

        self.assertEqual(0, int(seat_owner[209, 421]))


if __name__ == "__main__":
    unittest.main()
