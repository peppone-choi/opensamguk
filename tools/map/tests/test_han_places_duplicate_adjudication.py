#!/usr/bin/env python3
"""OPENSAM-234C1: reviewed CHGIS identity choices precede span fallback."""
import copy
import hashlib
import json
import shutil
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path
from unittest import mock


ROOT = Path(__file__).resolve().parents[3]
MAP_TOOLS = ROOT / "tools" / "map"
sys.path.insert(0, str(MAP_TOOLS))

import build_han_places as builder  # noqa: E402


LEDGER_PATH = ROOT / "data/curated/han/han-place-duplicate-adjudications-v1.json"
ROUTE_REVIEW_PATH = ROOT / "data/curated/han/route-node-location-adjudications-v1.json"
ROUTE_SELECTION_PATH = ROOT / "data/curated/han/route-node-selection-v1.json"
EXPECTED_PAIRS = {
    "han-place-duplicate:85168-87267": ("85168", frozenset({"87267"})),
    "han-place-duplicate:42901-85581": ("42901", frozenset({"85581"})),
}


def source_dir():
    candidates = (
        ROOT / "data/chgis-source",
        ROOT.parents[2] / "projects/opensamguk/data/chgis-source",
    )
    return next((path for path in candidates if path.is_dir()), None)


def place(pid, name="测试县", name_ft="測試縣", lon=110.0, lat=30.0,
          beg=0, end=500, pres="same"):
    return {
        "id": str(pid), "nameCh": name, "nameFt": name_ft, "namePy": "test",
        "typeCh": "县", "kind": "COUNTY", "level": 5, "lon": lon, "lat": lat,
        "presLoc": pres, "begYr": beg, "endYr": end,
    }


class PresenceAndControlledSourceRedTest(unittest.TestCase):
    def test_generic_ledger_and_builder_api_exist(self):
        self.assertTrue(LEDGER_PATH.is_file(), f"missing ledger: {LEDGER_PATH}")
        self.assertTrue(hasattr(builder, "load_duplicate_adjudications"))
        self.assertTrue(hasattr(builder, "fold_duplicate_places"))

    @unittest.skipUnless(source_dir(), "controlled CHGIS source is not installed")
    def test_controlled_source_generation_uses_reviewed_identities(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_root = Path(tmp)
            out = tmp_root / "map/han-places.json"
            out.parent.mkdir(parents=True)
            shutil.copy2(ROOT / "data/map/external-places.json", out.parent)
            argv = ["build_han_places.py", "--year", "220", "--out", str(out)]
            with mock.patch.object(builder, "SRC", str(source_dir())), mock.patch.object(sys, "argv", argv):
                stdout = StringIO()
                with redirect_stdout(stdout):
                    builder.main()
            document = json.loads(out.read_text(encoding="utf-8"))
            indexed = {str(row["id"]): row for row in document["places"]}
            self.assertEqual(1144, document["count"])
            self.assertEqual(97, document["nudged"])
            self.assertEqual((436, 181), (indexed["85168"]["gx"], indexed["85168"]["gy"]))
            self.assertEqual((491, 230), (indexed["42901"]["gx"], indexed["42901"]["gy"]))
            self.assertEqual((490, 229), (indexed["33406"]["gx"], indexed["33406"]["gy"]))
            self.assertNotIn("87267", indexed)
            self.assertNotIn("85581", indexed)
            self.assertNotIn("82709", indexed)
            self.assertIn("82696", indexed)
            for place_id in ("82687", "83034", "85083"):
                with self.subTest(place_id=place_id):
                    self.assertEqual(("COUNTY", 5), (indexed[place_id]["kind"], indexed[place_id]["level"]))
            kingdom_names = {
                "210345": "常山國", "210359": "趙國", "87534": "中山國",
                "210496": "齊國", "210522": "北海國", "210537": "琅邪國",
                "210769": "梁國", "210791": "陳國", "33427": "下邳國",
                "87611": "河間國", "33425": "彭城國", "210466": "樂安國",
            }
            for place_id, name_ft in kingdom_names.items():
                with self.subTest(place_id=place_id):
                    self.assertEqual((name_ft, "KINGDOM", 6), (
                        indexed[place_id]["nameFt"], indexed[place_id]["kind"], indexed[place_id]["level"],
                    ))
            self.assertEqual(1, sum(str(row["id"]) == "85168" for row in document["places"]))
            self.assertEqual(1, sum(str(row["id"]) == "42901" for row in document["places"]))


@unittest.skipUnless(
    LEDGER_PATH.is_file()
    and hasattr(builder, "load_duplicate_adjudications")
    and hasattr(builder, "fold_duplicate_places"),
    "duplicate adjudication support not implemented yet",
)
class DuplicateAdjudicationContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ledger = json.loads(LEDGER_PATH.read_text(encoding="utf-8"))
        builder.validate_duplicate_adjudications(cls.ledger)

    def validate_copy(self, mutate):
        ledger = copy.deepcopy(self.ledger)
        mutate(ledger)
        return builder.validate_duplicate_adjudications(ledger)

    def test_locked_pairs_and_tracked_review_hashes_agree_independently(self):
        pairs = {}
        for group in self.ledger["adjudications"]:
            selected = [m["physicalPlaceId"] for m in group["members"] if m["disposition"] == "SELECTED"]
            rejected = frozenset(m["physicalPlaceId"] for m in group["members"] if m["disposition"] == "REJECTED")
            pairs[group["groupId"]] = (selected[0], rejected)
        self.assertEqual(EXPECTED_PAIRS, pairs)
        self.assertEqual(
            {('东武城县', '東武城縣'), ('利城县', '利城縣')},
            {(group["sourceNameCh"], group["sourceNameFt"])
             for group in self.ledger["adjudications"]},
        )

        pinned = self.ledger["trackedReviewInputs"]
        for path in (ROUTE_REVIEW_PATH, ROUTE_SELECTION_PATH):
            relative = path.relative_to(ROOT).as_posix()
            self.assertEqual(relative, pinned[relative]["path"])
            self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), pinned[relative]["sha256"])

    def test_validator_rejects_tracked_review_file_content_drift(self):
        with tempfile.TemporaryDirectory() as raw_directory:
            root = Path(raw_directory)
            for relative in builder.EXPECTED_TRACKED_REVIEW_INPUTS:
                target = root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes((ROOT / relative).read_bytes())
            first = root / next(iter(builder.EXPECTED_TRACKED_REVIEW_INPUTS))
            first.write_bytes(first.read_bytes() + b"\n")

            with mock.patch.object(builder, "REPOSITORY_ROOT", root):
                with self.assertRaisesRegex(ValueError, "actual file SHA-256 mismatch"):
                    builder.validate_duplicate_adjudications(self.ledger)

        review = json.loads(ROUTE_REVIEW_PATH.read_text(encoding="utf-8"))
        review_pairs = {
            (row["selectedPhysicalPlaceId"].split(":")[-1], frozenset(
                item.split(":")[-1] for item in row["rejectedPhysicalPlaceIds"]
            ))
            for row in review["adjudications"]
            if isinstance(row.get("selectedPhysicalPlaceId"), str)
            and row["selectedPhysicalPlaceId"].split(":")[-1] in {"85168", "42901"}
        }
        selection = json.loads(ROUTE_SELECTION_PATH.read_text(encoding="utf-8"))
        selection_pairs = {
            (row["locationAdjudication"]["selectedPhysicalPlaceRef"].split(":")[-1], frozenset(
                item.split(":")[-1] for item in row["locationAdjudication"]["rejectedPhysicalPlaceRefs"]
            ))
            for row in selection["routeNodes"]
            if row.get("locationAdjudication")
            and isinstance(row["locationAdjudication"].get("selectedPhysicalPlaceRef"), str)
            and row["locationAdjudication"]["selectedPhysicalPlaceRef"].split(":")[-1] in {"85168", "42901"}
        }
        expected = set(EXPECTED_PAIRS.values())
        self.assertEqual(expected, review_pairs)
        self.assertEqual(expected, selection_pairs)

    def test_exact_schemas_closed_enums_and_identity_fields_fail_closed(self):
        mutations = [
            lambda d: d.update(extra=True),
            lambda d: d["groupingContract"].update(extra=True),
            lambda d: d["trackedReviewInputs"][next(iter(d["trackedReviewInputs"]))].update(digest="0" * 64),
            lambda d: d["adjudications"][0].update(displayName="forbidden"),
            lambda d: d["adjudications"][0].update(sourceName="legacy-alias"),
            lambda d: d["adjudications"][0]["members"][0].update(runtimeId=7),
            lambda d: d["adjudications"][0]["members"][0].pop("sourceNameCh"),
            lambda d: d["adjudications"][0]["members"][0].update(sourceNameFt="漂移縣"),
            lambda d: d["adjudications"][0]["members"][0]["activeRange"].update(extra=1),
            lambda d: d["adjudications"][0]["members"][0]["coordinate"].update(extra=1),
            lambda d: d.update(schemaVersion=2),
            lambda d: d.update(ledgerId="wrong"),
            lambda d: d.update(baselineYear=221),
            lambda d: d["trackedReviewInputs"][next(iter(d["trackedReviewInputs"]))].update(
                role="UNKNOWN_REVIEW"
            ),
            lambda d: d["adjudications"][0].update(reviewState="PENDING"),
            lambda d: d["adjudications"][0]["members"][0].update(disposition="PENDING"),
        ]
        for mutate in mutations:
            with self.subTest(mutate=mutate), self.assertRaises(ValueError):
                self.validate_copy(mutate)

    def test_conflicting_selection_shapes_fail_closed(self):
        mutations = [
            lambda d: [m.update(disposition="REJECTED") for m in d["adjudications"][0]["members"]],
            lambda d: [m.update(disposition="SELECTED") for m in d["adjudications"][0]["members"]],
            lambda d: d["adjudications"][0]["members"].append(copy.deepcopy(d["adjudications"][0]["members"][0])),
            lambda d: d["adjudications"].append(copy.deepcopy(d["adjudications"][0])),
        ]
        for mutate in mutations:
            with self.subTest(mutate=mutate), self.assertRaises(ValueError):
                self.validate_copy(mutate)

    def test_provenance_graph_rejects_bogus_redirect_missing_extra_and_hash_drift(self):
        def redirect_all_refs_to_bogus(document):
            bogus = "data/curated/han/bogus-review.json"
            document["trackedReviewInputs"][bogus] = {
                "path": bogus,
                "sha256": "0" * 64,
                "role": "BOGUS_REVIEW",
            }
            for group in document["adjudications"]:
                for member in group["members"]:
                    member["evidenceRefs"] = [bogus]

        def add_bogus_extra_ref(document):
            bogus = "data/curated/han/bogus-review.json"
            document["trackedReviewInputs"][bogus] = {
                "path": bogus,
                "sha256": "0" * 64,
                "role": "BOGUS_REVIEW",
            }
            document["adjudications"][0]["members"][0]["evidenceRefs"].append(bogus)

        mutations = [
            redirect_all_refs_to_bogus,
            add_bogus_extra_ref,
            lambda d: d["adjudications"][0]["members"][0]["evidenceRefs"].pop(),
            lambda d: d["trackedReviewInputs"][next(iter(d["trackedReviewInputs"]))].update(
                sha256="0" * 64
            ),
        ]
        for mutate in mutations:
            with self.subTest(mutate=mutate), self.assertRaises(ValueError):
                self.validate_copy(mutate)

    def test_member_array_order_is_semantically_irrelevant(self):
        reordered = copy.deepcopy(self.ledger)
        reordered["adjudications"].reverse()
        for group in reordered["adjudications"]:
            group["members"].reverse()
            for member in group["members"]:
                member["evidenceRefs"].reverse()
        self.assertEqual(
            builder.validate_duplicate_adjudications(self.ledger),
            builder.validate_duplicate_adjudications(reordered),
        )

    def test_builder_has_no_route_policy_runtime_dependency(self):
        with mock.patch("builtins.open", wraps=open) as open_spy:
            builder.load_duplicate_adjudications()
        opened = [str(call.args[0]) for call in open_spy.call_args_list]
        self.assertEqual([str(builder.DUPLICATE_ADJUDICATIONS)], opened)
        self.assertFalse(any("route-node-" in path for path in opened))


@unittest.skipUnless(
    LEDGER_PATH.is_file() and hasattr(builder, "fold_duplicate_places"),
    "duplicate adjudication support not implemented yet",
)
class DuplicateFoldingBehaviorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ledger = builder.load_duplicate_adjudications()

    def adjudicated_records(self, group_index=0):
        rows = []
        group = self.ledger["adjudications"][group_index]
        for member in group["members"]:
            rows.append(place(
                member["physicalPlaceId"], group["sourceNameCh"], group["sourceNameFt"],
                member["coordinate"]["lon"], member["coordinate"]["lat"],
                member["activeRange"]["begYr"], member["activeRange"]["endYr"],
                "different-present-location-" + member["physicalPlaceId"],
            ))
        return rows

    def fold(self, records, ledger=None, year=220):
        kept, _folded, _ties = builder.fold_duplicate_places(records, year, ledger or self.ledger)
        return kept

    def test_curated_choice_precedes_narrower_temporal_span_and_is_order_independent(self):
        for group_index, expected in ((0, "85168"), (1, "42901")):
            records = self.adjudicated_records(group_index)
            ledger = copy.deepcopy(self.ledger)
            ledger["adjudications"] = [ledger["adjudications"][group_index]]
            self.assertGreater(
                next(p for p in records if p["id"] == expected)["endYr"]
                - next(p for p in records if p["id"] == expected)["begYr"],
                min(p["endYr"] - p["begYr"] for p in records if p["id"] != expected),
            )
            for ordered in (records, list(reversed(records))):
                self.assertEqual([expected], [p["id"] for p in self.fold(ordered, ledger=ledger)])

    def test_unreviewed_group_keeps_temporal_span_fallback_independent_of_input_order(self):
        broad = place("900", beg=-100, end=500)
        narrow = place("100", lon=110.1, lat=30.1, beg=20, end=300, pres="other")
        unreviewed = copy.deepcopy(self.ledger)
        unreviewed["adjudications"] = []
        for ordered in ([broad, narrow], [narrow, broad]):
            self.assertEqual(["100"], [p["id"] for p in self.fold(ordered, ledger=unreviewed)])

    def test_missing_drift_wrong_group_and_inactive_records_fail_closed(self):
        base = self.adjudicated_records(0)
        mutations = [
            lambda rows: rows.pop(0),
            lambda rows: rows.pop(1),
            lambda rows: rows[0].update(nameFt="漂移縣"),
            lambda rows: rows[0].update(lon=rows[0]["lon"] + 0.001),
            lambda rows: rows[0].update(begYr=rows[0]["begYr"] + 1),
            lambda rows: rows[1].update(lon=rows[0]["lon"] + 2),
        ]
        for mutate in mutations:
            rows = copy.deepcopy(base)
            mutate(rows)
            with self.subTest(mutate=mutate), self.assertRaises(ValueError):
                self.fold(rows)
        with self.assertRaises(ValueError):
            self.fold(base, year=999)

    def test_joint_name_ch_drift_fails_even_when_name_ft_still_matches(self):
        rows = self.adjudicated_records(0)
        ledger = copy.deepcopy(self.ledger)
        ledger["adjudications"] = [ledger["adjudications"][0]]
        for row in rows:
            row["nameCh"] = "共同漂移县"
        with self.assertRaises(ValueError):
            self.fold(rows, ledger=ledger)

    def test_extra_member_in_reviewed_real_duplicate_group_fails_closed(self):
        rows = self.adjudicated_records(0)
        rows.append(place("99999", rows[0]["nameCh"], rows[0]["nameFt"],
                          rows[0]["lon"] + 0.01, rows[0]["lat"] + 0.01,
                          -100, 500, "third"))
        with self.assertRaises(ValueError):
            self.fold(rows)


if __name__ == "__main__":
    unittest.main()
