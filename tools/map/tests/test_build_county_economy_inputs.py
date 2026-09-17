import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import build_county_economy_inputs as B  # noqa: E402


class LargestRemainderTest(unittest.TestCase):
    def test_sum_is_exact_and_deterministic(self):
        out = B.largest_remainder(208486, [12.0 * 5, 1.0 * 5, 1.5 * 40.2, 1.0 * 33.3])
        self.assertEqual(sum(out), 208486)
        self.assertEqual(out, B.largest_remainder(208486, [12.0 * 5, 1.0 * 5, 1.5 * 40.2, 1.0 * 33.3]))

    def test_rejects_zero_weights(self):
        with self.assertRaises(ValueError):
            B.largest_remainder(10, [0.0, 0.0])


class CommittedLedgerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.doc = json.loads(B.OUTPUT.read_text(encoding="utf-8"))
        cls.rows = {r["nameCh"]: r for r in cls.doc["jurisdictions"]}

    def test_every_jurisdiction_has_exactly_one_row(self):
        tiles = json.loads(B.TILES.read_text(encoding="utf-8"))
        self.assertEqual(
            sorted(r["jurisdictionId"] for r in self.doc["jurisdictions"]),
            sorted(j["id"] for j in tiles["jurisdictionRecords"]),
        )

    def test_commandery_households_are_conserved(self):
        sys.path.insert(0, str(B.ROOT / "tools" / "scenario"))
        import build_han_world

        source = build_han_world.junguozhi_groups()
        by_com = {}
        for r in self.doc["jurisdictions"]:
            if r["households"] is not None:
                by_com[r["commanderyNameCh"]] = by_com.get(r["commanderyNameCh"], 0) + r["households"]
        self.assertGreater(len(by_com), 90)
        for name, total in by_com.items():
            self.assertEqual(total, int(source[name]["households"]), name)

    def test_capital_is_not_starved_by_area_weighting(self):
        # 2026-09-17 실측: 면적·지형 가중만 쓰면 雒陽 1,621호. 治所 위계 가중이 빠지면 이 단언이 빨개진다.
        luoyang = self.rows["雒阳县"]
        henan = [r for r in self.doc["jurisdictions"] if r["commanderyNameCh"] == "河南尹"]
        self.assertEqual(luoyang["households"], max(r["households"] for r in henan))

    def test_no_allocated_county_is_zero(self):
        self.assertFalse([r["nameCh"] for r in self.doc["jurisdictions"] if r["households"] == 0])

    def test_params_stay_marked_exploratory(self):
        params = json.loads(B.PARAMS.read_text(encoding="utf-8"))
        self.assertEqual(params["status"], "EXPLORATORY")
        self.assertEqual(self.doc["paramsStatus"], "EXPLORATORY")


class CheckGateRedProbeTest(unittest.TestCase):
    def test_check_fails_on_stale_ledger(self):
        original = B.OUTPUT
        with tempfile.TemporaryDirectory() as tmp:
            stale = Path(tmp) / "stale.json"
            doc = json.loads(original.read_text(encoding="utf-8"))
            doc["jurisdictions"][0]["households"] = 1
            stale.write_text(B.render(doc), encoding="utf-8")
            B.OUTPUT = stale
            try:
                self.assertEqual(B.main(["--check"]), 1)
            finally:
                B.OUTPUT = original
        self.assertEqual(B.main(["--check"]), 0)


if __name__ == "__main__":
    unittest.main()
