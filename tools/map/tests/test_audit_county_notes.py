import sys
import unittest
import json
import hashlib
import tempfile
from unittest.mock import patch
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import audit_junguozhi_source as audit


class CountyNoteAlignmentTest(unittest.TestCase):
    def test_committed_collation_covers_catalog_without_promoting_review(self):
        path = audit.ROOT / "data/curated/han/junguozhi-county-note-collation-v1.json"
        result = json.loads(path.read_text())
        catalog = json.loads(audit.CATALOG.read_text())
        self.assertFalse(result["s1GatePassed"])
        self.assertEqual(result["catalogSha256"], hashlib.sha256(audit.CATALOG.read_bytes()).hexdigest())
        self.assertEqual(result["characterTableSha256"], hashlib.sha256(audit.CHAR_TABLE.read_bytes()).hexdigest())
        self.assertEqual(len(result["groups"]), len(catalog["groups"]))
        count = 0
        for compared, group in zip(result["groups"], catalog["groups"]):
            self.assertEqual(compared["canonicalGroup"], group["canonicalGroup"])
            self.assertEqual(compared["committedWitnessQuote"], "\n".join(e["quote"] for e in group["evidence"]))
            self.assertEqual(len(compared["rows"]), len(group["units"]))
            for row, unit in zip(compared["rows"], group["units"]):
                self.assertEqual(row["sourceCitation"], unit["sourceCitation"])
                self.assertEqual(row["memberId"], audit.contract.stable_member_id(
                    unit["sourceVolume"], unit["canonicalGroup"], unit["ordinal"]))
                self.assertTrue(row["raw"])
                self.assertEqual(row["review"], "UNREVIEWED")
                count += 1
        self.assertEqual(count, 1180)
        self.assertEqual(sum(result["counts"].values()), count)

    def test_source_name_status_cannot_hide_a_placeholder_or_invent_one(self):
        raw = "洛有鐵"
        digest = hashlib.sha256(raw.encode()).hexdigest()
        citation = {"corpusPath": "data/corpus/hhs-109.txt", "sourceUrl": "https://zh.wikisource.org/wiki/後漢書/卷109",
                    "snapshotSha256": digest, "line": 1}
        unit = {"sourceName": "洛", "sourceNameStatus": "SOURCE_LITERAL", "sourceVolume": 109,
                "canonicalGroup": "河南尹", "ordinal": 1, "unitType": "COUNTY", "sourceCitation": citation}
        group = {"sourceVolume": 109, "sourceGroupName": "河南尹", "canonicalGroup": "河南尹",
                 "units": [unit], "evidence": [{"quote": raw}], "traditionalTextCitation": {}}
        parsed = [{"sourceVolume": 109, "sourceGroupName": "河南尹",
                   "units": [{"sourceName": "洛", "sourceLine": 1, "unitType": "COUNTY"}]}]
        with tempfile.TemporaryDirectory() as tmp, patch.object(audit, "EXPECTED_GROUP_COUNT", 1), \
                patch.object(audit, "EXPECTED_UNIT_COUNT", 1), \
                patch.object(audit.contract, "VOLUMES", (109,)), \
                patch.object(audit.contract, "CORPUS_SHA256", {109: digest}), \
                patch.object(audit.contract, "parse_groups", return_value=parsed):
            root = Path(tmp)
            (root / "hhs-109.txt").write_text(raw)
            catalog = root / "catalog.json"
            catalog.write_text(json.dumps({"groups": [group]}))
            self.assertEqual(sum(audit.collate_notes(root, catalog)["counts"].values()), 1)
            unit["sourceNameStatus"] = "SOURCE_PLACEHOLDER"
            catalog.write_text(json.dumps({"groups": [group]}))
            with self.assertRaises(audit.CatalogContractError):
                audit.collate_notes(root, catalog)

    def test_full_clause_is_candidate_not_historical_approval(self):
        rows, gaps = audit.align_note_bodies(
            [{"sourceName": "襄", "body": "襄，有養陰里。"},
             {"sourceName": "襄城", "body": "襄城，有西不羹。有汜城。"}],
            "潁川郡 襄有養陰里。襄城有西不羹。有氾城。", {})
        self.assertEqual(rows[0]["comparison"], "UNIQUE_CLAUSE_CANDIDATE")
        self.assertEqual(rows[1]["comparison"], "NO_EXACT_CLAUSE")
        self.assertTrue(all(r["review"] == "UNREVIEWED" for r in rows))
        self.assertTrue(gaps)

    def test_name_only_never_passes_on_substring_in_another_county(self):
        rows, _ = audit.align_note_bodies([{"sourceName": "襄", "body": "襄"}], "襄城有鐵", {})
        self.assertEqual(rows[0]["comparison"], "NAME_ONLY_REQUIRES_BOUNDARY_REVIEW")

    def test_duplicate_clause_and_reverse_order_are_not_unique_anchors(self):
        rows, _ = audit.align_note_bodies([{"sourceName": "甲", "body": "甲有鐵"}], "甲有鐵乙甲有鐵", {})
        self.assertEqual(rows[0]["comparison"], "MULTIPLE_CLAUSE_OCCURRENCES")
        rows, _ = audit.align_note_bodies(
            [{"sourceName": "甲", "body": "甲有鐵"}, {"sourceName": "乙", "body": "乙有水"}],
            "乙有水甲有鐵", {})
        self.assertTrue(all(r["comparison"] == "NON_MONOTONIC_OR_OVERLAPPING" for r in rows))

    def test_mapping_does_not_rewrite_retained_quotes(self):
        rows, _ = audit.align_note_bodies([{"sourceName": "陽城", "body": "陽城有鐵"}], "阳城有铁", {"陽": "阳", "鐵": "铁"})
        self.assertEqual(rows[0]["comparison"], "UNIQUE_CLAUSE_CANDIDATE")
        self.assertEqual(rows[0]["body"], "陽城有鐵")

    def test_placeholder_never_becomes_candidate(self):
        rows, _ = audit.align_note_bodies([{"sourceName": "参[�]", "body": "参[�]有鐵", "sourceNameStatus": "SOURCE_PLACEHOLDER"}], "参[�]有鐵", {})
        self.assertEqual(rows[0]["comparison"], "SOURCE_PLACEHOLDER")


if __name__ == "__main__":
    unittest.main()
