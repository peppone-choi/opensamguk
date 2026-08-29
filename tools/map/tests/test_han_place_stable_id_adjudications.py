#!/usr/bin/env python3
import sys
import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
MAP_TOOLS = ROOT / "tools" / "map"
sys.path.insert(0, str(MAP_TOOLS))


class ReviewedLifecycleTest(unittest.TestCase):
    def test_builder_resolution_applies_review_before_dbf_year_filter(self):
        try:
            from build_han_places import resolve_classification
        except ImportError:
            self.fail("build_han_places has not integrated stable-ID adjudication")
        from han_place_stable_id_adjudications import load_adjudications

        row = {
            "SYS_ID": "82709", "NAME_CH": "新成侯国", "NAME_FT": "新成侯國",
            "TYPE_CH": "国", "BEG_YR": "-7", "END_YR": "626",
        }
        entries = load_adjudications()
        self.assertIsNone(resolve_classification(row, "cnty", 220, entries))
        self.assertEqual(
            ("新成侯国", "新成侯國", "COUNTY", 5, -7, -6),
            resolve_classification(row, "cnty", -6, entries),
        )

    def test_reviewed_lifecycle_overrides_flat_dbf_range(self):
        """Removing the reviewed range must make the 220 record leak back into the build."""
        try:
            from han_place_stable_id_adjudications import adjudicate_record
        except ModuleNotFoundError:
            self.fail("stable-ID adjudication runtime is missing")

        row = {
            "SYS_ID": "82709",
            "NAME_CH": "新成侯国",
            "NAME_FT": "新成侯國",
            "TYPE_CH": "国",
            "BEG_YR": "-7",
            "END_YR": "626",
        }
        entries = {
            "82709": {
                "physicalPlaceId": "82709",
                "sourceLayer": "cnty",
                "sourceIdentity": {
                    "nameCh": "新成侯国",
                    "nameFt": "新成侯國",
                    "typeCh": "国",
                },
                "classification": {
                    "kind": "COUNTY",
                    "level": 5,
                    "outputNameCh": "新成侯国",
                    "outputNameFt": "新成侯國",
                },
                "reviewedActiveRange": {"begYr": -7, "endYr": -6},
                "outsideRangeDisposition": "DROP_OUT_OF_PERIOD",
            }
        }

        self.assertFalse(adjudicate_record(row, "cnty", 220, entries)["include"])
        active = adjudicate_record(row, "cnty", -6, entries)
        self.assertTrue(active["include"])
        self.assertEqual((-7, -6), (active["begYr"], active["endYr"]))

    def test_identity_mismatch_fails_closed(self):
        from han_place_stable_id_adjudications import adjudicate_record

        row = {
            "SYS_ID": "82709", "NAME_CH": "新成侯国", "NAME_FT": "新成侯國",
            "TYPE_CH": "侯国", "BEG_YR": "-7", "END_YR": "626",
        }
        entries = {
            "82709": {
                "physicalPlaceId": "82709", "sourceLayer": "cnty",
                "sourceIdentity": {"nameCh": "新成侯国", "nameFt": "新成侯國", "typeCh": "国"},
                "classification": {"kind": "COUNTY", "level": 5,
                                   "outputNameCh": "新成侯国", "outputNameFt": "新成侯國"},
                "reviewedActiveRange": {"begYr": -7, "endYr": -6},
                "outsideRangeDisposition": "DROP_OUT_OF_PERIOD",
            }
        }
        with self.assertRaisesRegex(ValueError, "identity mismatch"):
            adjudicate_record(row, "cnty", -7, entries)

    def test_rang_is_not_a_merge_alias_for_xincheng(self):
        from han_place_stable_id_adjudications import adjudicate_record, load_adjudications

        rang = {
            "SYS_ID": "82696", "NAME_CH": "穰县", "NAME_FT": "穰縣",
            "TYPE_CH": "县", "BEG_YR": "-206", "END_YR": "265",
        }
        self.assertIsNone(adjudicate_record(rang, "cnty", 220, load_adjudications()))


class CommittedLedgerContractTest(unittest.TestCase):
    def test_committed_ledger_has_exact_reviewed_identity_set(self):
        from han_place_stable_id_adjudications import load_adjudications

        entries = load_adjudications(ROOT / "data/curated/han/han-place-stable-id-adjudications-v1.json")
        self.assertEqual(
            {
                "82709", "85083", "82687", "83034",
                "210345", "210359", "87534", "210496", "210522", "210537",
                "210769", "210791", "33427", "87611", "33425", "210466",
            },
            set(entries),
        )

    def test_kingdom_and_county_outputs_are_stable_id_exact(self):
        from han_place_stable_id_adjudications import load_adjudications

        entries = load_adjudications(ROOT / "data/curated/han/han-place-stable-id-adjudications-v1.json")
        expected_kingdom_names = {
            "210345": "常山國", "210359": "趙國", "87534": "中山國",
            "210496": "齊國", "210522": "北海國", "210537": "琅邪國",
            "210769": "梁國", "210791": "陳國", "33427": "下邳國",
            "87611": "河間國", "33425": "彭城國", "210466": "樂安國",
        }
        for place_id, name_ft in expected_kingdom_names.items():
            with self.subTest(place_id=place_id):
                self.assertEqual("KINGDOM", entries[place_id]["classification"]["kind"])
                self.assertEqual(name_ft, entries[place_id]["classification"]["outputNameFt"])
        for place_id in ("82687", "83034", "85083"):
            with self.subTest(place_id=place_id):
                self.assertEqual(
                    {"kind": "COUNTY", "level": 5},
                    {k: entries[place_id]["classification"][k] for k in ("kind", "level")},
                )

    def test_xincheng_has_no_merge_target_and_uses_reviewed_two_year_range(self):
        from han_place_stable_id_adjudications import load_adjudications

        entry = load_adjudications(
            ROOT / "data/curated/han/han-place-stable-id-adjudications-v1.json"
        )["82709"]
        self.assertNotIn("mergeTarget", entry)
        self.assertEqual({"begYr": -7, "endYr": -6}, entry["reviewedActiveRange"])
        self.assertEqual("DROP_OUT_OF_PERIOD", entry["outsideRangeDisposition"])

    def test_unknown_fields_are_rejected_instead_of_enabling_a_merge(self):
        from han_place_stable_id_adjudications import validate_ledger

        ledger = json.loads(
            (ROOT / "data/curated/han/han-place-stable-id-adjudications-v1.json").read_text()
        )
        ledger["adjudications"][0]["mergeTarget"] = "82696"
        with self.assertRaisesRegex(ValueError, "exact keys"):
            validate_ledger(ledger)

    def test_reviewed_range_must_stay_inside_source_identity_range(self):
        from han_place_stable_id_adjudications import validate_ledger

        ledger = json.loads(
            (ROOT / "data/curated/han/han-place-stable-id-adjudications-v1.json").read_text()
        )
        ledger["adjudications"][0]["reviewedActiveRange"]["begYr"] = -8
        with self.assertRaisesRegex(ValueError, "inside sourceIdentity"):
            validate_ledger(ledger)

    def test_evidence_rejects_freeform_and_pseudo_references(self):
        from han_place_stable_id_adjudications import validate_ledger

        original = json.loads(
            (ROOT / "data/curated/han/han-place-stable-id-adjudications-v1.json").read_text()
        )
        mutations = []
        freeform = json.loads(json.dumps(original))
        freeform["adjudications"][0]["evidenceRefs"] = ["x"]
        mutations.append(freeform)
        pseudo = json.loads(json.dumps(original))
        pseudo["adjudications"][0]["evidenceRefs"][0]["locator"] = "pseudo-ref"
        mutations.append(pseudo)
        drifted_source = json.loads(json.dumps(original))
        drifted_source["trackedSources"][0]["sha256"] = "0" * 64
        mutations.append(drifted_source)
        for ledger in mutations:
            with self.subTest(mutation=mutations.index(ledger)):
                with self.assertRaisesRegex(ValueError, "evidence|tracked source"):
                    validate_ledger(ledger)

    def test_pengcheng_guard_does_not_stand_in_for_lean_kingdom(self):
        from han_place_stable_id_adjudications import load_adjudications

        entries = load_adjudications()
        self.assertEqual("彭城國", entries["33425"]["classification"]["outputNameFt"])
        self.assertEqual("樂安國", entries["210466"]["classification"]["outputNameFt"])
        self.assertNotEqual(
            entries["33425"]["sourceIdentity"], entries["210466"]["sourceIdentity"]
        )


if __name__ == "__main__":
    unittest.main()
