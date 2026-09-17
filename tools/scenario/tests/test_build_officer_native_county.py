"""인물 본관 縣 원장 빌더 (OPENSAM-255 / #775).

적색 프로브가 들어 있다 — ``--check`` 가 낡은 원장·바뀐 입력에서 실제로 빨개지는지 본다
(exit 0 은 게이트의 증거가 아니다).
"""

import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "tools" / "scenario"))

import build_officer_native_county as builder  # noqa: E402

CHAR_PAYLOAD = {
    "shinjitaiToTraditional": [{"from": "関", "to": "關"}, {"from": "呉", "to": "吳"}],
    "matchVariantFold": [{"from": "吕", "to": "呂"}],
    "placeSimplificationAdditions": [],
}
SIMPLIFICATION = {"table": {"東": "东", "縣": "县", "陽": "阳", "陰": "阴", "潁": "颍", "吳": "吴"}}
TILES = {
    "jurisdictionRecords": [
        {"id": "J1", "nameCh": "解县", "kind": "COUNTY"},
        {"id": "J2", "nameCh": "颍阴县", "kind": "COUNTY"},
        {"id": "J3", "nameCh": "吴县", "kind": "COUNTY"},
        {"id": "J4", "nameCh": "吴县", "kind": "COUNTY"},
        {"id": "J5", "nameCh": "解口", "kind": "STRATEGIC_SITE"},
    ],
    "commanderyRecords": [
        {"id": "P1", "nameCh": "河東郡", "jurisdictionIds": ["J1", "J5"]},
        {"id": "P2", "nameCh": "潁川郡", "jurisdictionIds": ["J2"]},
        {"id": "P3", "nameCh": "吳郡", "jurisdictionIds": ["J3", "J4"]},
        {"id": "P4", "nameCh": "南陽郡", "jurisdictionIds": []},
        {"id": "P5", "nameCh": "南郡", "jurisdictionIds": []},
        {"id": "P6", "nameCh": "右扶風", "jurisdictionIds": []},
        {"id": "P7", "nameCh": "潁陰郡", "jurisdictionIds": []},  # 縣과 이름이 같은 郡(宕渠縣/宕渠郡 꼴)
    ],
}


def _tables():
    return builder.CharTables(CHAR_PAYLOAD)


def _gazetteer():
    return builder.Gazetteer(TILES, SIMPLIFICATION, _tables())


def _hit(name, place, book="三國志", volume="卷01", zi="某"):
    return {"nameKanjiTraditional": name, "courtesyName": zi, "placeText": place, "form": "A", "book": book,
            "volume": volume, "title": "t", "quote": f"{name}字{zi}，{place}人也", "volumeSha256": "0"}


def _ledger(registry_names, hits, scenario=None):
    registry = [{"id": str(10001 + i), "name_kanji": n} for i, n in enumerate(registry_names)]
    name_map = [{"id": r["id"], "name_korean": f"장수{i}"} for i, r in enumerate(registry)]
    names = scenario or {row["name_korean"]: ["1020"] for row in name_map}
    extracts = {"schemaVersion": 1, "ledgerId": "officer-native-place-extracts-v1", "hits": hits}
    return builder.build_ledger(registry, name_map, names, _tables(), extracts, _gazetteer(), ["1020"])


class CleanAndExtractTest(unittest.TestCase):
    def test_notes_links_and_variant_markup_are_stripped(self):
        raw = "{{header2|title=[[../]]}}\n荀彧字文若，[[潁川]]潁陰人也。{{*|《續漢書》曰：{{YL|建武|33年}}淑有-{才}-}}法正字孝直，（右）扶風郿人也"
        cleaned = builder.clean_wikitext(raw)
        self.assertNotIn("續漢書", cleaned)
        self.assertIn("荀彧字文若，潁川潁陰人也", cleaned)
        self.assertIn("右扶風郿人也", cleaned)

    def test_inline_text_templates_are_unwrapped_not_deleted(self):
        # 三國志 卷17 원문 꼴. 틀을 통째로 지우면 이름·地名이 사라져 張遼가 「열전 서두 없음」으로 실린다.
        raw = ("{{ProperNoun|張遼}}字{{ProperNoun|文遠}}，{{ProperNoun|雁門}}{{ProperNoun|馬邑}}人也。"
               "{{ul|倭人}}在{{另|郡|部}}{{quote|臣聞}}{{color|blue|論曰}}{{--|興子}}{{gap}}")
        removed: dict = {}
        cleaned = builder.clean_wikitext(raw, removed)
        self.assertIn("張遼字文遠，雁門馬邑人也。", cleaned)
        self.assertIn("倭人在郡臣聞論曰", cleaned)
        self.assertNotIn("興子", cleaned)
        self.assertEqual(removed, {})  # 아는 틀만 있었다

    def test_paired_note_markers_and_ref_notes_are_removed(self):
        # 三國志 卷19: 裴注가 {{*s}}…{{*e}} 짝으로 달려 있다. 새면 「楊脩字德祖…」가 열전 서두로 읽힌다.
        raw = ("植益內不自安。{{*s}}《典略》曰：楊脩字德祖，弘農華陰人也。{{*e}}二十四年"
               "，六十還之，<ref>《周禮》{{校|鄉|卿}}大夫職曰</ref>甲<ref name=\"a\" /><!--linked-->乙{{別|禦|御}}我")
        cleaned = builder.clean_wikitext(raw, {})
        self.assertEqual(cleaned, "植益內不自安。二十四年，六十還之，甲乙禦我")

    def test_unknown_template_is_dropped_but_counted(self):
        removed: dict = {}
        self.assertEqual(builder.clean_wikitext("甲{{모르는틀|乙}}丙", removed), "甲丙")
        self.assertEqual(removed, {"모르는틀": 1})

    def test_forms(self):
        text = ("關羽字雲長，本字長生，河東解人也。\n典韋，陳留己吾人也。\n夏侯惇字元讓，沛國譙人，夏侯嬰之後也。\n"
                "先主姓劉，諱備，字玄德，涿郡涿縣人，\n太祖武皇帝，沛國譙人也，姓曹，諱操，字孟德\n羊祜，字叔子，泰山南城人也")
        hits = {(n, p, f) for n, _, p, f, _ in builder.find_hits(text, _tables().fold(text))}
        # 廟號(太祖武皇帝)도 B 꼴로 잡히지만 등록부 이름이 아니라 추출 단계에서 버려진다.
        self.assertLessEqual({("關羽", "河東解", "A"), ("典韋", "陳留己吾", "B"), ("夏侯惇", "沛國譙", "D"),
                                ("劉備", "涿郡涿縣", "E"), ("曹操", "沛國譙", "F"), ("羊祜", "泰山南城", "A")}, hits)

    def test_mid_sentence_mentions_are_not_biography_openings(self):
        text = "太祖問曰張遼字文遠，雁門馬邑人也"
        self.assertEqual(list(builder.find_hits(text, text)), [])

    def test_fold_preserves_length_and_shinjitai_is_not_applied_to_variants(self):
        tables = _tables()
        self.assertEqual(tables.to_traditional("関羽"), "關羽")
        self.assertEqual(tables.to_traditional("吕布"), "吕布")
        self.assertEqual(tables.fold("吕布"), "呂布")
        self.assertEqual(len(tables.fold("呉関吕")), 3)


class GazetteerTest(unittest.TestCase):
    def test_statuses(self):
        g = _gazetteer()
        self.assertEqual(g.resolve("河東解")["jurisdictionId"], "J1")  # 요충지 解口 는 縣 이 아니다
        self.assertEqual(g.resolve("潁川潁陰")["matchStatus"], "MATCHED")
        self.assertEqual(g.resolve("吳郡吳")["matchStatus"], "AMBIGUOUS")
        self.assertIsNone(g.resolve("吳郡吳")["jurisdictionId"])
        self.assertEqual(g.resolve("南陽")["matchStatus"], "NO_COUNTY")
        self.assertEqual(g.resolve("南陽")["commanderyId"], "P4")  # 南郡 의 「南」이 먼저 먹지 않는다
        self.assertEqual(g.resolve("天水冀")["matchStatus"], "UNSPLIT")
        self.assertEqual(g.resolve("扶風郿")["commanderyId"], "P6")
        not_here = g.resolve("南陽解")
        self.assertEqual(not_here["matchStatus"], "COUNTY_NOT_IN_COMMANDERY")
        self.assertIsNone(not_here["jurisdictionId"])  # 다른 郡의 同名 縣으로 넘겨짚지 않는다
        self.assertEqual(not_here["candidateJurisdictionIds"], ["J1"])


class LedgerRuleTest(unittest.TestCase):
    def test_direct_and_missing(self):
        ledger = _ledger(["関羽", "兀突骨"], [_hit("關羽", "河東解")])
        first, second = ledger["officers"]
        self.assertEqual((first["method"], first["jurisdictionId"], first["nameKanjiTraditional"]), ("DIRECT", "J1", "關羽"))
        self.assertLessEqual(len(first["evidence"]["quote"]), 40)
        self.assertEqual((second["method"], second["missingReason"]), ("MISSING", "NO_BIOGRAPHY_STATEMENT"))

    def test_registry_homonyms_are_never_resolved(self):
        ledger = _ledger(["馬忠", "馬忠"], [_hit("馬忠", "河東解")])
        self.assertEqual({e["missingReason"] for e in ledger["officers"]}, {"AMBIGUOUS_PERSON"})

    def test_two_sanguozhi_biographies_with_different_places(self):
        ledger = _ledger(["張某"], [_hit("張某", "河東解"), _hit("張某", "潁川潁陰", volume="卷02")])
        self.assertEqual(ledger["officers"][0]["missingReason"], "AMBIGUOUS_PERSON")
        self.assertIsNone(ledger["officers"][0]["jurisdictionId"])

    def test_other_book_only_is_queued_not_promoted(self):
        ledger = _ledger(["張純"], [_hit("張純", "河東解", book="後漢書")])
        entry = ledger["officers"][0]
        self.assertEqual((entry["method"], entry["missingReason"]), ("MISSING", "HOMONYM_UNVERIFIED"))
        self.assertEqual(entry["candidates"][0]["book"], "後漢書")

    def test_sanguozhi_wins_and_disagreement_is_recorded(self):
        ledger = _ledger(["賈逵"], [_hit("賈逵", "河東解"), _hit("賈逵", "潁川潁陰", book="後漢書")])
        entry = ledger["officers"][0]
        self.assertEqual(entry["jurisdictionId"], "J1")
        self.assertEqual(entry["otherBookDisagreement"][0]["book"], "後漢書")

    def test_county_only_statement_sharing_a_commandery_name_is_not_a_contradiction(self):
        # 王平: 三國志 「巴西宕渠人也」 + 華陽國志 「宕渠人也」. 縣 이름이 後代 郡 이름과 같아도 어긋난 게 아니다.
        ledger = _ledger(["王某"], [_hit("王某", "潁川潁陰"), _hit("王某", "潁陰", book="華陽國志")])
        entry = ledger["officers"][0]
        self.assertNotIn("otherBookDisagreement", entry)
        self.assertEqual(entry["corroboration"][0]["book"], "華陽國志")

    def test_less_specific_statement_is_not_a_contradiction(self):
        ledger = _ledger(["潘某"], [_hit("潘某", "河東"), _hit("潘某", "河東解", volume="卷02")])
        self.assertEqual(ledger["officers"][0]["jurisdictionId"], "J1")

    def test_suffixed_scenario_homonyms_link_every_registry_candidate(self):
        registry = [{"id": "10001", "name_kanji": "李豊"}, {"id": "10002", "name_kanji": "関羽"}]
        name_map = [{"id": "10001", "name_korean": "이풍"}, {"id": "10002", "name_korean": "관우"}]
        linked, unlinked = builder.link_officers(registry, name_map, {"이풍1": ["1020"], "관우": ["1020"], "화타": ["1020"]})
        self.assertEqual([(r["id"], link) for r, _, link, _ in linked], [("10001", "HOMONYM"), ("10002", "EXACT")])
        self.assertEqual(unlinked, ["화타"])

    def test_bad_extract_rows_fail(self):
        bad = _hit("關羽", "河東解")
        bad["quote"] = "關羽字雲長，" + "長" * 40
        with self.assertRaises(builder.LedgerError):
            _ledger(["関羽"], [bad])
        bad = _hit("關羽", "河東解")
        bad["quote"] = "張飛字益德，河東解人也"
        with self.assertRaises(builder.LedgerError):
            _ledger(["関羽"], [bad])


class CommittedArtifactsTest(unittest.TestCase):
    def _run(self, argv):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = builder.main(argv)
        return code, out.getvalue(), err.getvalue()

    def test_check_passes_on_committed_ledger(self):
        code, out, _ = self._run(["--check"])
        self.assertEqual(code, 0)
        self.assertIn("OK:", out)

    def test_char_map_only_lists_characters_the_registry_uses(self):
        payload = json.loads(builder.CHAR_MAP_PATH.read_text(encoding="utf-8"))
        names = "".join(row["name_kanji"] for row in builder._read_tsv(builder.REGISTRY_PATH))
        for row in payload["shinjitaiToTraditional"]:
            self.assertIn(row["from"], names, row)
            self.assertTrue(all(row["from"] in example for example in row["examples"]), row)
        for same_in_traditional in "範隆真強衛":
            self.assertNotIn(same_in_traditional, {r["from"] for r in payload["shinjitaiToTraditional"]})

    def test_ledger_invariants(self):
        ledger = json.loads(builder.LEDGER_PATH.read_text(encoding="utf-8"))
        tiles = json.loads(builder.TILES_PATH.read_text(encoding="utf-8"))
        jurisdiction_ids = {j["id"] for j in tiles["jurisdictionRecords"]}
        ids = [e["stableId"] for e in ledger["officers"]]
        self.assertEqual(len(ids), len(set(ids)))
        for entry in ledger["officers"]:
            if entry["method"] == "MISSING":
                self.assertIsNone(entry["jurisdictionId"], entry)
                self.assertIsNotNone(entry["missingReason"], entry)
            else:
                self.assertLessEqual(len(entry["evidence"]["quote"]), 40)
                self.assertEqual(entry["evidence"]["book"], "三國志")
                self.assertEqual(entry["jurisdictionId"] is not None, entry["matchStatus"] == "MATCHED", entry)
            if entry["jurisdictionId"] is not None:
                self.assertIn(entry["jurisdictionId"], jurisdiction_ids)

    # ---- 적색 프로브 ----

    def test_red_probe_stale_ledger_fails_check(self):
        ledger = json.loads(builder.LEDGER_PATH.read_text(encoding="utf-8"))
        victim = next(e for e in ledger["officers"] if e["method"] == "DIRECT")
        victim["jurisdictionId"] = "FORGED"
        with tempfile.TemporaryDirectory() as directory:
            stale = Path(directory) / "ledger.json"
            stale.write_text(builder._serialize(ledger), encoding="utf-8")
            with mock.patch.object(builder, "LEDGER_PATH", stale), mock.patch.object(builder, "ROOT", Path(directory)):
                code, _, err = self._run(["--check"])
        self.assertEqual(code, 1)
        self.assertIn("FAIL", err)

    def test_red_probe_changed_input_fails_check(self):
        extracts = json.loads(builder.EXTRACTS_PATH.read_text(encoding="utf-8"))
        ledger = json.loads(builder.LEDGER_PATH.read_text(encoding="utf-8"))
        used = next(e for e in ledger["officers"] if e["method"] == "DIRECT")["evidence"]["quote"]
        extracts["hits"] = [h for h in extracts["hits"] if h["quote"] != used]
        with tempfile.TemporaryDirectory() as directory:
            changed = Path(directory) / "extracts.json"
            changed.write_text(builder._serialize(extracts), encoding="utf-8")
            with mock.patch.object(builder, "EXTRACTS_PATH", changed):
                code, _, err = self._run(["--check"])
        self.assertEqual(code, 1)
        self.assertIn("낡았다", err)


if __name__ == "__main__":
    unittest.main()
