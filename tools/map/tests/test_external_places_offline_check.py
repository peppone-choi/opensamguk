"""build_external_places.py --check-offline — 네트워크 없는 표↔산출물 대조 (GH #542)."""
from __future__ import annotations

import copy
import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "tools" / "map"))

import build_external_places as bep  # noqa: E402


def dump(doc) -> str:
    return json.dumps(doc, ensure_ascii=False, indent=1, sort_keys=True) + "\n"


class ExternalPlacesOfflineCheckTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = bep.OUT.read_text(encoding="utf-8")
        cls.doc = json.loads(cls.text)

    def mutated(self, fn) -> list[str]:
        doc = copy.deepcopy(self.doc)
        fn(doc)
        return bep.check_offline(dump(doc))

    def test_committed_artifact_passes(self):
        self.assertEqual(bep.check_offline(self.text), [])

    def test_kind_drift_is_caught(self):
        # #542 실측 재현: X014 魯國 이 COMMANDERY 인 채로 커밋돼 있었다.
        def f(d):
            row = next(p for p in d["places"] if p["nameFt"] == "魯國")
            self.assertEqual(row["kind"], "KINGDOM")
            row["kind"] = "COMMANDERY"
        errs = self.mutated(f)
        self.assertEqual(len(errs), 1)
        self.assertIn("kind", errs[0])

    def test_silently_dropped_row_is_caught_even_with_consistent_meta(self):
        def f(d):
            d["places"].pop()
            d["_meta"]["resolved"] -= 1
        self.assertTrue(any("unresolved 에도 없다" in e for e in self.mutated(f)))

    def test_unresolved_row_is_accepted(self):
        def f(d):
            row = d["places"].pop()
            d["unresolved"].append(f'{row["nameFt"]} ({row["presLoc"]}): 후보 0개 — 임의 선택하지 않음')
            d["_meta"]["resolved"] -= 1
            d["_meta"]["unresolved"] += 1
        self.assertEqual(self.mutated(f), [])

    def test_unknown_and_duplicate_rows_are_caught(self):
        def f(d):
            extra = copy.deepcopy(d["places"][0])
            extra["id"] = "X999"
            d["places"].append(extra)
            d["places"].append(copy.deepcopy(d["places"][0]))
        errs = self.mutated(f)
        self.assertTrue(any("X999" in e for e in errs))
        self.assertTrue(any("중복" in e for e in errs))

    def test_meta_bad_coordinate_and_bad_qid_are_caught(self):
        def f(d):
            d["_meta"]["resolved"] += 1
            d["places"][0]["lat"] = 121.0
            d["places"][1]["wikidata"] = "33408"
        errs = self.mutated(f)
        self.assertTrue(any("_meta" in e for e in errs))
        self.assertTrue(any("lon/lat" in e for e in errs))
        self.assertTrue(any("QID" in e for e in errs))

    def test_hand_formatted_artifact_is_caught(self):
        errs = bep.check_offline(json.dumps(self.doc, ensure_ascii=False, indent=2, sort_keys=True) + "\n")
        self.assertTrue(any("직렬화" in e for e in errs))

    def test_coordinate_value_staleness_is_out_of_scope(self):
        # 한계를 못박아 둔다: 유효 범위 안의 좌표 변조는 오프라인으로 못 잡는다(--check 의 몫).
        def f(d):
            d["places"][0]["lat"] += 0.1
        self.assertEqual(self.mutated(f), [])


if __name__ == "__main__":
    unittest.main()
