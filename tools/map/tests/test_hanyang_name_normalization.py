import importlib.util
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location(
    "hanyang_coverage", ROOT / "tools/map/audit_county_coverage.py"
)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class HanyangNameNormalizationTest(unittest.TestCase):
    def test_existing_chengji_coordinate_matches_canonical_county(self):
        canonical = json.loads(MODULE.CANON_PATH.read_text())
        ledger = json.loads(MODULE.NAMU_PATH.read_text())
        group = next(g for g in canonical["groups"] if g["canonicalGroup"] == "漢陽郡")
        county = next(u for u in group["units"] if u["sourceName"] == "成纪")
        rows = [r for r in ledger["rows"]
                if r["canonicalGroup"] == "漢陽郡" and r["sourceName"] in ("成紀", "成纪")]
        self.assertEqual(len(rows), 1, "Reuse the existing coordinate, without a duplicate row")
        self.assertEqual((rows[0]["lat"], rows[0]["lon"]), (35.2415, 105.68042))
        normalize = MODULE.make_normalizer()
        self.assertEqual(
            (normalize(rows[0]["canonicalGroup"]), normalize(rows[0]["sourceName"])),
            (normalize(county["canonicalGroup"]), normalize(county["sourceName"])),
        )
        coverage = next(g for g in MODULE.audit()["groups"] if g["canonicalGroup"] == "漢陽郡")
        self.assertNotIn(normalize(county["sourceName"]), coverage["unlocated"])
