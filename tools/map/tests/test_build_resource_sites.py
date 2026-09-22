"""build_resource_sites.py — 산지 원장 게이트(GH #776 / OPENSAM-256)."""

from __future__ import annotations

import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import build_resource_sites as brs  # noqa: E402


def run(argv: list[str]) -> tuple[int, str]:
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = brs.main(argv)
    return code, out.getvalue() + err.getvalue()


class CommittedLedgerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.ledger = brs.load_json(brs.LEDGER_PATH)
        cls.entries = cls.ledger["entries"]

    def select(self, **where):
        return [e for e in self.entries if all(e.get(k) == v for k, v in where.items())]

    def test_check_is_green_on_committed_ledger(self) -> None:
        code, output = run(["--check"])
        self.assertEqual(code, 0, output)

    def test_build_is_deterministic(self) -> None:
        extracts = brs.load_json(brs.EXTRACTS_PATH)
        self.assertEqual(brs.dump_json(brs.build_ledger(extracts)), brs.dump_json(brs.build_ledger(extracts)))

    def test_later_han_iron_keeps_all_34_counties(self) -> None:
        iron = self.select(resource="IRON", era="LATER_HAN", level="COUNTY")
        self.assertEqual(len(iron), 34)
        self.assertEqual(sum(1 for e in iron if e["sourceMarker"] in ("出铁", "出鐵")), 4)
        unmatched = [e for e in iron if e["jurisdictionId"] is None]
        self.assertTrue(unmatched, "못 붙인 縣이 사라졌다 — 지우지 말고 사유와 함께 남겨라")
        for entry in unmatched:
            self.assertTrue(entry["matchStatus"].startswith("UNMATCHED"))
            self.assertTrue(entry["matchReason"])

    def test_homonyms_are_split_by_commandery_heading(self) -> None:
        expected = {("河東郡", "平阳"): "PARENT-0002", ("右扶風", "漆"): "PARENT-0006", ("潁川郡", "陽城"): "PARENT-0007"}
        tiles = brs.load_json(brs.TILES_PATH)
        parent = {j["id"]: j["commanderyId"] for j in tiles["jurisdictionRecords"]}
        for (group, name), commandery in expected.items():
            (entry,) = self.select(resource="IRON", era="LATER_HAN", sourceCommandery=group, sourceName=name)
            self.assertEqual(entry["matchStatus"], "MATCHED")
            self.assertEqual(parent[entry["jurisdictionId"]], commandery)
            self.assertEqual(entry["commanderyId"], commandery)

    def test_name_only_match_never_gets_an_id(self) -> None:
        for entry in self.entries:
            if entry["matchStatus"] == "NAME_ONLY_PARENT_UNVERIFIED":
                self.assertIsNone(entry["jurisdictionId"])
                self.assertTrue(entry["candidateJurisdictionIds"])

    def test_second_witness_agrees_per_commandery(self) -> None:
        witness = self.ledger["crossWitness"]
        self.assertEqual(witness["disagreeingCommanderies"], [])
        self.assertEqual(witness["witnessTotal"], 34)

    def test_former_han_office_counts_match_source_page(self) -> None:
        def count(resource):
            return len(self.select(resource=resource, era="FORMER_HAN")) - len(
                [e for e in self.select(resource=resource, era="FORMER_HAN") if e["matchStatus"].startswith("UNKNOWN")])
        self.assertEqual((count("IRON"), count("SALT"), count("HORSE"), count("WOOD")), (38, 22, 1, 1))

    def test_quotes_are_short_and_cited(self) -> None:
        for entry in self.entries:
            if entry["evidence"] is None:
                self.assertTrue(entry["matchStatus"].startswith("UNKNOWN"))
                self.assertTrue(entry["missing"])
                continue
            self.assertLessEqual(len(entry["evidence"]["quote"]), brs.QUOTE_MAX)
            self.assertTrue(entry["evidence"]["url"].startswith("https://zh.wikisource.org/"))

    def test_wood_and_lower_dilizhi_are_explicit_unknowns(self) -> None:
        unknown = {e["id"] for e in self.entries if e["matchStatus"] == "UNKNOWN_NO_SOURCE"}
        self.assertEqual(unknown, {"UNKNOWN:WOOD:LATER_HAN", "UNKNOWN:HORSE:PASTURE_SITES",
                                   "UNKNOWN:FORMER_HAN:DILIZHI_LOWER"})

    def test_horse_is_region_and_commandery_level_only(self) -> None:
        horses = [e for e in self.select(resource="HORSE", era="LATER_HAN") if e["evidence"]]
        self.assertEqual(sorted(e["level"] for e in horses), ["COMMANDERY"] * 6 + ["REGION"])
        self.assertTrue(all(e["commanderyId"] for e in horses if e["level"] == "COMMANDERY"))


class RedProbeTest(unittest.TestCase):
    """게이트가 실제로 빨개지는지 — exit 0 은 증거가 아니다."""

    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.dir = Path(self.tmp.name)

    def test_same_commandery_count_cannot_hide_wrong_county(self) -> None:
        extracts = brs.load_json(brs.EXTRACTS_PATH)
        target = next(e for e in extracts["extracts"] if e["sourceName"] == "泉州")
        # Keep two iron sites in 漁陽郡 but fabricate one at 狐奴 instead of 泉州.
        target.update(sourceName="狐奴", administrativeUnitId="hhs:113:漁陽郡:002",
                      extractId="hhs:113:漁陽郡:002:IRON", quote="〖狐奴〗有铁。")
        target["locator"]["line"] = 792
        forged = self.dir / "extracts.json"
        forged.write_text(brs.dump_json(extracts), encoding="utf-8")
        output = self.dir / "forged-ledger.json"
        code, message = run(["--extracts", str(forged), "--output", str(output)])
        self.assertEqual(code, 1, message)
        self.assertIn("county witness", message)
        self.assertFalse(output.exists())

    def test_source_identity_cannot_disagree_with_binding_id(self) -> None:
        extracts = brs.load_json(brs.EXTRACTS_PATH)
        target = next(e for e in extracts["extracts"] if e["sourceName"] == "泉州")
        target["administrativeUnitId"] = "hhs:113:漁陽郡:002"
        forged = self.dir / "extracts.json"
        forged.write_text(brs.dump_json(extracts), encoding="utf-8")
        code, message = run(["--extracts", str(forged), "--output", str(self.dir / "out.json")])
        self.assertEqual(code, 1, message)
        self.assertIn("county identity", message)

    def test_county_witness_rejects_identity_and_quote_mutations(self) -> None:
        for field, value in (
            ("sourceName", "狐奴"), ("snapshotSha256", "0" * 64),
            ("marker", "出鐵"),
            ("quote", "〖狐奴〗有铁。"), ("quote", "〖泉州〗有盐。"),
            ("locator", {"corpusPath": "data/corpus/hhs-113.txt", "line": 792}),
        ):
            with self.subTest(field=field):
                extracts = brs.load_json(brs.EXTRACTS_PATH)
                target = next(e for e in extracts["extracts"] if e["sourceName"] == "泉州")
                target[field] = value
                self.assertTrue(brs.validate_county_witnesses(extracts))

    def test_witness_reassignment_preserves_counts_but_fails_county_check(self) -> None:
        units = brs.load_json(brs.UNITS_PATH)
        before = brs.cross_witness_counts(units)
        group = next(g for g in units["groups"] if g["canonicalGroup"] == "漁陽郡")
        for evidence in group["evidence"]:
            evidence["quote"] = evidence["quote"].replace("泉\n州\n有鐵", "泉\n州")
            evidence["quote"] = evidence["quote"].replace("狐奴", "狐奴有鐵")
        self.assertEqual(before, brs.cross_witness_counts(units))
        witness = self.dir / "units.json"
        witness.write_text(brs.dump_json(units), encoding="utf-8")
        with patch.object(brs, "UNITS_PATH", witness):
            errors = brs.validate_county_witnesses(brs.load_json(brs.EXTRACTS_PATH))
        self.assertTrue(any("hhs:113:漁陽郡:005" in e and "county witness" in e for e in errors), errors)

    def test_variant_pairs_do_not_accept_additional_words(self) -> None:
        for uid in brs.COUNTY_WITNESS_VARIANTS:
            with self.subTest(uid=uid):
                extracts = brs.load_json(brs.EXTRACTS_PATH)
                target = next(e for e in extracts["extracts"] if e.get("administrativeUnitId") == uid)
                target["quote"] = target["quote"].replace("有鐵", "古有鐵")
                self.assertTrue(brs.validate_county_witnesses(extracts))

    def test_two_extracts_cannot_consume_one_witness_marker(self) -> None:
        extracts = brs.load_json(brs.EXTRACTS_PATH)
        target = next(e for e in extracts["extracts"] if e["sourceName"] == "泉州")
        extracts["extracts"].append(dict(target))
        self.assertTrue(any("already attributed" in e for e in brs.validate_county_witnesses(extracts)))

    def test_hand_edited_ledger_goes_red(self) -> None:
        ledger = brs.load_json(brs.LEDGER_PATH)
        target = next(e for e in ledger["entries"] if e["matchStatus"] == "UNMATCHED_NO_JURISDICTION")
        target["jurisdictionId"] = "82216"  # 못 붙인 縣에 id 를 손으로 찍는다
        stale = self.dir / "ledger.json"
        stale.write_text(brs.dump_json(ledger), encoding="utf-8")
        code, output = run(["--check", "--output", str(stale)])
        self.assertEqual(code, 1)
        self.assertIn("STALE", output)

    def test_ledger_behind_its_extracts_goes_red(self) -> None:
        extracts = brs.load_json(brs.EXTRACTS_PATH)
        dropped = extracts["extracts"].pop(0)  # 추출본이 바뀌었는데 원장은 그대로
        newer = self.dir / "extracts.json"
        newer.write_text(brs.dump_json(extracts), encoding="utf-8")
        code, output = run(["--check", "--extracts", str(newer)])
        self.assertEqual(code, 1, dropped["extractId"])

    def test_missing_ledger_goes_red(self) -> None:
        code, _ = run(["--check", "--output", str(self.dir / "absent.json")])
        self.assertEqual(code, 1)

    def test_same_copy_stays_green(self) -> None:
        copy = self.dir / "ledger.json"
        copy.write_bytes(brs.LEDGER_PATH.read_bytes())
        code, output = run(["--check", "--output", str(copy)])
        self.assertEqual(code, 0, output)

    def test_validator_rejects_unknown_jurisdiction(self) -> None:
        ledger = brs.load_json(brs.LEDGER_PATH)
        ledger["entries"][0]["jurisdictionId"] = "no-such-county"
        self.assertTrue(any("no-such-county" in e for e in brs.validate_ledger(ledger)))


class ExtractionTest(unittest.TestCase):
    def setUp(self) -> None:
        self.norm = brs.Normalizer(brs.load_json(brs.SIMPLIFICATION_PATH)["table"])

    def test_strip_templates_removes_nested_commentary(self) -> None:
        self.assertEqual(brs.strip_templates("漆有漆水。{{*|注{{YL|元年}}有鐵}}有铁。"), "漆有漆水。有铁。")

    def test_office_is_attributed_to_nearest_county_not_to_a_note_clause(self) -> None:
        page = ("<onlyinclude>\n亰兆尹，故秦內史。縣三：華陰，故陰晉，太華山在南，有祠。集靈宮，武帝起。"
                "鄭，周宣王弟鄭桓公邑。有鐵官。〔五〕湖，故曰胡。\n\n　　〔五〕　應劭曰：「有鐵官之說。」\n"
                "　　弘農郡，武帝置。有鐵官，在黽池。縣二：弘農，（故）〔古〕秦函谷關。宜陽，在黽池有鐵官也。\n</onlyinclude>")
        extracts, meta = brs.extract_former_han_offices(page.encode("utf-8"), {"华阴", "湖", "宜阳"}, self.norm)
        got = [(e["sourceCommandery"], e["sourceName"], e["attribution"]) for e in extracts]
        self.assertEqual(got, [("亰兆尹", "鄭", "PATTERN"), ("弘農郡", "黽池", "HEAD_LOCATIVE"),
                               ("弘農郡", "黽池", "CLAUSE_LOCATIVE")])
        self.assertEqual(meta["commanderies"], ["亰兆尹", "弘農郡"])

    def test_tile_suffix_rule_keeps_names_that_end_in_guo(self) -> None:
        self.assertEqual(self.norm.tile_name("平郭縣"), "平郭")
        self.assertEqual(self.norm.tile_name("林虑县"), "林虑")
        self.assertEqual(self.norm.commandery_base("齊郡"), self.norm.commandery_base("齊國"))




class WoodProcurementTest(unittest.TestCase):
    def test_procurement_keeps_event_scope_without_site_binding(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "hhs-042.txt"
            path.write_text("==中山簡王焉==\n立五十二年，{{YL|永元二年|90年}}薨。發常山、鉅鹿、涿郡柏黃腸雜木，<ref>黃腸，柏木黃心。</ref>三郡不能備\n")
            records = brs.extract_wood_procurement(Path(tmp))
            self.assertEqual([r["sourceName"] for r in records], ["常山", "鉅鹿", "涿郡"])
            doc = brs.load_json(brs.EXTRACTS_PATH)
            doc["extracts"] = [r for r in doc["extracts"] if r.get("attribution") != "EVENT_PROCUREMENT"] + records
            entries = [r for r in brs.build_ledger(doc)["entries"] if r.get("countyAttribution") == "EVENT_PROCUREMENT"]
            self.assertEqual(len(entries), 3)
            for r in entries:
                self.assertIsNone(r["jurisdictionId"])
                self.assertIsNone(r["commanderyId"])
                self.assertEqual(r["eventYear"], 90)
                self.assertEqual(r["matchStatus"], "UNREVIEWED_EVENT_PROCUREMENT")
                self.assertIn("지속 생산", r["claim"])
                self.assertEqual(r["evidence"]["locator"]["line"], 2)

    def test_missing_or_duplicate_procurement_clause_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "hhs-042.txt"
            for body in ("no witness", "發常山、鉅鹿、涿郡柏黃腸雜木" * 2, "==中山簡王焉==\n永元二年|90年\n發常山、鉅鹿、涿郡柏黃腸雜木，三郡不能備", "==另一人==\n永元二年|90年 發常山、鉅鹿、涿郡柏黃腸雜木，三郡不能備"):
                path.write_text(body)
                with self.subTest(body=body), self.assertRaises(SystemExit):
                    brs.extract_wood_procurement(Path(tmp))


if __name__ == "__main__":
    unittest.main()
