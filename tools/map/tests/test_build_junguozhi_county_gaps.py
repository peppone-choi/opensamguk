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

    def test_all_formerly_absent_counties_are_present(self):
        dong = {c["sourceName"]: c["status"] for c in self.by["東郡"]["counties"]}
        self.assertEqual(dong["濮陽"], "IN_OWN_COMMANDERY")
        self.assertEqual(dong["燕"], "IN_OWN_COMMANDERY")
        ying = {c["sourceName"]: c["status"] for c in self.by["潁川郡"]["counties"]}
        self.assertEqual(ying["潁陰"], "IN_OWN_COMMANDERY")  # 繁→簡 접기(潁→颍, 陰→阴)가 죽으면 빨개진다

    def test_names_ending_in_yi_dao_guo_are_not_truncated(self):
        # 邑·道·國은 郡國志에서 이름의 일부다(安邑·狄道·安國). 떼면 타일의 「狄道县」과 조용히 어긋난다.
        for commandery, county in (("河東郡", "安邑"), ("隴西郡", "狄道"), ("山陽郡", "昌邑"), ("梁國", "下邑"), ("常山國", "高邑")):
            status = {c["sourceName"]: c["status"] for c in self.by[commandery]["counties"]}
            self.assertEqual(status[county], "IN_OWN_COMMANDERY", f"{commandery} {county}")

    def test_agrees_with_audit_county_coverage(self):
        # 독립 구현이었을 때 783 vs 841 로 58건 어긋났다. 같은 (郡,縣) 대조면 같은 수가 나와야 한다.
        import audit_county_coverage
        self.assertEqual(self.doc["totals"]["IN_OWN_COMMANDERY"], audit_county_coverage.audit()["totals"]["placed"])

    def test_former_near_match_is_its_own_game_city(self):
        yan = {c["sourceName"]: c for c in self.by["鴈門郡"]["counties"]}
        self.assertEqual(yan["汪陶"]["status"], "IN_OWN_COMMANDERY")
        self.assertNotIn("nearMatchInOwnCommandery", yan["汪陶"])
        self.assertNotIn("nearMatchInOwnCommandery", {c["sourceName"]: c for c in self.by["東郡"]["counties"]}["濮陽"])

    def test_only_user_excluded_undeciphered_rows_remain_absent_and_red_probe(self):
        self.assertEqual(self.doc["totals"].get("ABSENT", 0), 3)
        absent = {(g["commandery"], c["sourceName"]) for g in self.doc["commanderies"]
                  for c in g["counties"] if c["status"] == "ABSENT"}
        self.assertEqual(absent, {("北地郡", "参[�]"), ("武威郡", "朴[B459]"),
                                  ("交趾郡", "朱[B42B]")})
        self.assertEqual(self.doc["totals"]["undisposedAbsent"], 0)
        self.assertTrue(all(c.get("disposition") == "USER_EXCLUDED_UNDECIPHERED_NAME_FROM_MAP"
                            for g in self.doc["commanderies"] for c in g["counties"]
                            if c["status"] == "ABSENT"))
        ledger = json.loads((B.ROOT / "data/curated/han/gap-counties-v1.json").read_text(encoding="utf-8"))
        synthetic = [row for row in ledger["counties"] if row.get("positionStatus") == "SYNTHETIC"]
        self.assertEqual(len(synthetic), 223)
        self.assertEqual({row["sourceName"] for row in ledger["excludedUndeciphered"]},
                         {name for _, name in absent})
        self.assertTrue(all(not row["syntheticPlacement"]["historicalSeatClaim"] for row in synthetic))
        units, tiles, world = [json.loads(p.read_text(encoding="utf-8")) for p in (B.UNITS, B.TILES, B.WORLD)]
        excluded_ids = {row["placeId"] for row in ledger["excludedUndeciphered"]}
        self.assertEqual(excluded_ids, {"gc-g0071-005", "gc-g0072-007", "gc-g0102-010"})
        self.assertTrue(all(row["sourceCitation"]["snapshotSha256"] for row in ledger["excludedUndeciphered"]))
        self.assertFalse(excluded_ids & {city["id"] for city in tiles["cities"]})
        self.assertFalse(excluded_ids & {city["meta"].get("physicalPlaceRef", "").split(":")[-1]
                                         for city in world["cities"]})
        tiles = {**tiles, "jurisdictionRecords": [row for row in tiles["jurisdictionRecords"]
                                                 if row["id"] != "gc-g0023-001"]}
        world = {**world, "cities": [row for row in world["cities"] if row["id"] != 1440]}
        broken = B.build(units, tiles, world)
        self.assertEqual(broken["totals"]["ABSENT"], 4)
        self.assertEqual(broken["totals"]["undisposedAbsent"], 1)

    # 검토된 異體字 쌍(#813) 하나당 고정점 하나. 글자표에서 그 쌍을 빼면 그 행만 ABSENT 로 돌아가 빨개진다.
    VARIANT_FIXED_POINTS = (
        ("荧", "河南尹", "荧阳"), ("菀", "河南尹", "菀陵"), ("匽", "河南尹", "匽师"), ("睾", "河內郡", "平睾"),
        ("板", "河東郡", "蒲板"), ("雒", "京兆尹", "上雒"), ("渝", "右扶風", "渝麋"), ("愼", "汝南郡", "愼陽"),
        ("郎", "汝南郡", "郎陵"), ("襃", "汝南郡", "襃信"), ("襃", "漢中郡", "襃中"), ("憙", "中山國", "安憙"),
        ("已", "陳留郡", "己吾"), ("髙", "泰山郡", "奉髙"), ("髙", "廣陵郡", "髙郵"), ("鄕", "山陽郡", "金鄕"),
        ("楡", "東海郡", "贛楡"), ("菑", "齊國", "臨菑"), ("湼", "南陽郡", "涅陽"), ("很", "南郡", "很山"),
        ("荼", "長沙郡", "荼陵"), ("榖", "丹陽郡", "春穀"), ("昬", "豫章郡", "海昬"), ("稾", "牂牁郡", "谈稾"),
        ("莋", "越巂郡", "定莋"), ("莋", "越巂郡", "莋秦"), ("巂", "永昌郡", "巂唐"), ("驪", "玄菟郡", "高句骊"),
        ("賓", "遼東屬國", "宾徒"), ("谿", "蒼梧郡", "端谿"),
    )

    def test_reviewed_variant_pairs_place_their_counties(self):
        for _glyph, commandery, county in self.VARIANT_FIXED_POINTS:
            row = {c["sourceName"]: c for c in self.by[commandery]["counties"]}[county]
            # basis 까지 본다 — 匽师 는 城 meta(CITY_SOURCE_JUN)로도 맞아서 status 만 보면 글자표가 죽어도 초록이다.
            self.assertEqual((row["status"], row.get("basis")), ("IN_OWN_COMMANDERY", "TILE_COMMANDERY"), f"{commandery} {county}")

    def test_every_reviewed_variant_pair_has_a_fixed_point_and_red_probe(self):
        import audit_county_coverage as A
        table_doc = json.loads(A.TABLE_PATH.read_text(encoding="utf-8"))
        added = {r["from"] for r in table_doc["reviewedVariantAdditions"]} - {"呉", "毘"}  # 앞선 두 쌍은 吳郡 테스트가 쥔다
        self.assertEqual(added, {g for g, _, _ in self.VARIANT_FIXED_POINTS})
        inputs = [json.loads(p.read_text(encoding="utf-8")) for p in (B.UNITS, B.TILES, B.WORLD)]
        original = A.TABLE_PATH
        for glyph in sorted(added):
            broken = json.loads(json.dumps(table_doc))
            broken["table"].pop(glyph)
            broken["reviewedVariantAdditions"] = [r for r in broken["reviewedVariantAdditions"] if r["from"] != glyph]
            with tempfile.TemporaryDirectory() as tmp:
                A.TABLE_PATH = Path(tmp) / "table.json"
                A.TABLE_PATH.write_text(json.dumps(broken, ensure_ascii=False), encoding="utf-8")
                try:
                    by = {g["commandery"]: g for g in B.build(*inputs)["commanderies"]}
                finally:
                    A.TABLE_PATH = original
            for g, commandery, county in self.VARIANT_FIXED_POINTS:
                row = {c["sourceName"]: c for c in by[commandery]["counties"]}[county]
                if g == glyph:
                    self.assertNotEqual(row.get("basis"), "TILE_COMMANDERY", f"{glyph} 를 빼도 {county} 가 맞는다 — 고정점이 죽었다")

    def test_variant_additions_reject_disagreement_and_chains(self):
        import audit_county_coverage as A
        doc = json.loads(A.TABLE_PATH.read_text(encoding="utf-8"))
        row = {"from": "陽", "to": "陰", "witness": "x"}  # 표는 陽→阳
        chain = {"from": "㐀", "to": "陽", "witness": "x"}  # to 가 다시 접힌다
        for bad in (row, chain, {"from": "㐀", "to": "㐁"}):
            with tempfile.TemporaryDirectory() as tmp:
                path = Path(tmp) / "t.json"
                path.write_text(json.dumps({**doc, "reviewedVariantAdditions": [bad]}, ensure_ascii=False), encoding="utf-8")
                with self.assertRaises(ValueError):
                    A.load_fold_table(path)

    def test_removing_reviewed_glyph_exposes_absence_without_promotion(self):
        # 사용자 승인 별도 城이 생긴 뒤에도 愼→慎 접기를 지우면 그 행이 다시 ABSENT 로 보인다.
        import audit_county_coverage as A
        doc = json.loads(A.TABLE_PATH.read_text(encoding="utf-8"))
        doc["table"].pop("愼")
        doc["reviewedVariantAdditions"] = [r for r in doc["reviewedVariantAdditions"] if r["from"] != "愼"]
        inputs = [json.loads(p.read_text(encoding="utf-8")) for p in (B.UNITS, B.TILES, B.WORLD)]
        original = A.TABLE_PATH
        with tempfile.TemporaryDirectory() as tmp:
            A.TABLE_PATH = Path(tmp) / "table.json"
            A.TABLE_PATH.write_text(json.dumps(doc, ensure_ascii=False), encoding="utf-8")
            try:
                built = B.build(*inputs)
            finally:
                A.TABLE_PATH = original
        ru = {c["sourceName"]: c for g in built["commanderies"] if g["commandery"] == "汝南郡" for c in g["counties"]}
        self.assertEqual(ru["愼陽"]["status"], "ABSENT")
        self.assertEqual(ru["愼陽"]["nearMatchInOwnCommandery"], ["慎阳"])
        for g in self.doc["commanderies"]:
            seen = [f for c in g["counties"] for f in c.get("nearMatchInOwnCommandery", [])]
            self.assertEqual(len(seen), len(set(seen)), g["commandery"])

    def test_human_review_list_is_exactly_the_remaining_near_rows(self):
        review = json.loads((B.ROOT / "data/curated/han/junguozhi-county-name-review-v1.json").read_text(encoding="utf-8"))
        want = {(g["commandery"], c["sourceName"], tuple(c["nearMatchInOwnCommandery"]))
                for g in self.doc["commanderies"] for c in g["counties"] if c.get("nearMatchInOwnCommandery")}
        have = {(r["commandery"], r["sourceName"], tuple(r["tileCandidates"])) for r in review["rows"]}
        self.assertEqual(have, want)
        self.assertTrue(all(r["review"] == "NEEDS_HUMAN" for r in review["rows"]))

    def test_rows_stay_unreviewed(self):
        self.assertEqual(self.doc["review"], "UNREVIEWED_NAME_MATCH")

    def test_check_gate_red_probe(self):
        original = B.OUTPUT
        with tempfile.TemporaryDirectory() as tmp:
            stale = Path(tmp) / "stale.json"
            doc = json.loads(original.read_text(encoding="utf-8"))
            doc["totals"]["IN_OWN_COMMANDERY"] -= 1
            stale.write_text(B.render(doc), encoding="utf-8")
            B.OUTPUT = stale
            try:
                self.assertEqual(B.main(["--check"]), 1)
            finally:
                B.OUTPUT = original
        self.assertEqual(B.main(["--check"]), 0)


if __name__ == "__main__":
    unittest.main()
