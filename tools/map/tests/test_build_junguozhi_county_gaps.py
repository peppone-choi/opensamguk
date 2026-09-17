import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import build_junguozhi_county_gaps as B  # noqa: E402


class GapLedgerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.doc = json.loads(B.OUTPUT.read_text(encoding="utf-8"))
        cls.by = {g["commandery"]: g for g in cls.doc["commanderies"]}

    def test_every_junguozhi_county_is_classified_once(self):
        units = json.loads(B.UNITS.read_text(encoding="utf-8"))
        self.assertEqual(self.doc["totals"]["counties"], sum(len(g["units"]) for g in units["groups"]))
        self.assertEqual(len(self.doc["commanderies"]), len(units["groups"]))

    def test_known_present_and_absent_counties(self):
        dong = {c["sourceName"]: c["status"] for c in self.by["東郡"]["counties"]}
        self.assertEqual(dong["濮陽"], "ABSENT")          # 2026-09-17 실측: 東郡 治所가 1133 판에 없다
        self.assertEqual(dong["燕"], "IN_OWN_COMMANDERY")
        ying = {c["sourceName"]: c["status"] for c in self.by["潁川郡"]["counties"]}
        self.assertEqual(ying["潁陰"], "IN_OWN_COMMANDERY")  # 繁→簡 접기(潁→颍, 陰→阴)가 죽으면 빨개진다

    def test_fold_table_is_needed(self):
        # 글자표를 비우면 簡體 han-tiles 와 繁體 郡國志가 안 맞아 ABSENT 가 크게 는다.
        chars = {"icu": {}, "manual": {}}
        doc = B.build(*(json.loads(p.read_text(encoding="utf-8")) for p in (B.UNITS, B.TILES)), chars)
        self.assertGreater(doc["totals"]["ABSENT"], self.doc["totals"]["ABSENT"] + 100)

    def test_rows_stay_unreviewed(self):
        self.assertEqual(self.doc["review"], "UNREVIEWED_NAME_MATCH")

    def test_check_gate_red_probe(self):
        original = B.OUTPUT
        with tempfile.TemporaryDirectory() as tmp:
            stale = Path(tmp) / "stale.json"
            doc = json.loads(original.read_text(encoding="utf-8"))
            doc["totals"]["ABSENT"] -= 1
            stale.write_text(B.render(doc), encoding="utf-8")
            B.OUTPUT = stale
            try:
                self.assertEqual(B.main(["--check"]), 1)
            finally:
                B.OUTPUT = original
        self.assertEqual(B.main(["--check"]), 0)


if __name__ == "__main__":
    unittest.main()
