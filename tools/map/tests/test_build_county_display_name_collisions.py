"""같은 郡 안 한글 표시명 충돌 목록(#838) 게이트."""
import copy
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import build_county_display_name_collisions as B  # noqa: E402


def _load(path):
    return json.loads(path.read_text(encoding="utf-8"))


class CommittedListTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tiles, cls.world = _load(B.TILES), _load(B.WORLD)
        cls.table, cls.units = _load(B.TABLE), _load(B.UNITS)
        cls.doc = B.build(cls.tiles, cls.world, cls.table, cls.units)

    def test_committed_outputs_are_current(self):
        self.assertEqual(B.OUTPUT.read_text(encoding="utf-8"), B.render_json(self.doc))
        self.assertEqual(B.WEB_OUTPUT.read_text(encoding="utf-8"), B.render_web(self.doc))

    def test_issue_pair_is_listed_with_traditional_glosses(self):
        # 반드시 걸릴 것: 영천군 「양성현」 = 陽城·襄城 (#838). 조회가 살아 있다는 증거다.
        pair = [c for c in self.doc["collisions"] if c["commanderyNameCh"] == "颍川郡" or c["commanderyDisplayName"] == "영천군"]
        self.assertEqual(len(pair), 1)
        self.assertEqual(pair[0]["displayName"], "양성현")
        self.assertEqual(sorted(m["gloss"] for m in pair[0]["members"]), ["襄城", "陽城"])
        self.assertEqual(pair[0]["classification"], "HOMOPHONE")

    def test_every_member_really_shares_commandery_and_name(self):
        by_id = {r["id"]: r for r in self.tiles["jurisdictionRecords"]}
        for c in self.doc["collisions"]:
            self.assertGreaterEqual(len(c["members"]), 2)
            for m in c["members"]:
                record = by_id[m["jurisdictionId"]]
                self.assertEqual((record["commanderyId"], record["displayName"]), (c["commanderyId"], c["displayName"]))
                self.assertFalse(m["gloss"].endswith(("县", "縣")))
            glosses = [m["gloss"] for m in c["members"]]
            self.assertEqual(len(glosses), len(set(glosses)), "병기로도 안 갈리면 병기가 쓸모없다")

    def test_list_is_exhaustive(self):
        # 목록과 독립된 셈: 전수 (郡, 이름) 계수에서 2 이상인 쌍 수가 목록 크기와 같아야 한다.
        import collections
        counts = collections.Counter((r["commanderyId"], r["displayName"]) for r in self.tiles["jurisdictionRecords"])
        self.assertEqual(sum(1 for n in counts.values() if n > 1), len(self.doc["collisions"]))

    def test_reviewed_readings_remove_two_original_collisions_and_revert_restores_them(self):
        # #842: the three name readings are tied to ids, not row order or a name join.
        expected = {"41305": "선성현", "40663": "시평현", "40775": "시녕현"}
        by_id = {r["id"]: r for r in self.tiles["jurisdictionRecords"]}
        province_by_id = {r["id"]: r for r in self.tiles["provinceRecords"]}
        city_by_id = {r["id"]: r for r in self.tiles["cities"]}
        runtime = {c["spatialProvinceId"]: c for c in self.world["cities"] if c.get("spatialProvinceId")}
        for jid, reading in expected.items():
            self.assertEqual(by_id[jid]["displayName"], reading)
            self.assertEqual(province_by_id[jid]["displayName"], reading)
            self.assertEqual(city_by_id[jid]["name"], reading)
            self.assertTrue(runtime[jid]["meta"]["displayName"].endswith(reading))

        original_commandery_names = {"영천군", "영릉군", "여강군", "단양군", "회계군"}
        reviewed = [c for c in self.doc["collisions"] if c["commanderyDisplayName"] in original_commandery_names]
        self.assertEqual(len(reviewed), 3)
        self.assertEqual(self.doc["summary"]["sameCommanderyCollisionGroups"], 6)

        reverted = copy.deepcopy(self.tiles)
        previous = {"41305": "완릉현", "40663": "시령현", "40775": "시령현"}
        for record in reverted["jurisdictionRecords"]:
            if record["id"] in previous:
                record["displayName"] = previous[record["id"]]
        red = B.build(reverted, self.world, self.table, self.units)
        red_original = [c for c in red["collisions"] if c["commanderyDisplayName"] in original_commandery_names]
        self.assertEqual(len(red_original), 5)
        self.assertEqual(red["summary"]["sameCommanderyCollisionGroups"], 8)
        self.assertEqual({(c["commanderyDisplayName"], c["displayName"]) for c in red_original} -
                         {(c["commanderyDisplayName"], c["displayName"]) for c in reviewed},
                         {("단양군", "완릉현"), ("회계군", "시령현")})


class SyntheticTest(unittest.TestCase):
    def _tiles(self, records):
        return {"commanderyRecords": [{"id": "P1", "displayName": "영천군", "nameCh": "颍川郡"},
                                      {"id": "P2", "displayName": "여릉군", "nameCh": "庐陵郡"}],
                "jurisdictionRecords": records}

    def _rec(self, jid, com, name, ch):
        return {"id": jid, "commanderyId": com, "displayName": name, "nameCh": ch, "kind": "COUNTY"}

    TABLE = {"table": {"陽": "阳", "寧": "宁", "甯": "宁"}}
    UNITS = {"groups": [{"units": [{"sourceName": "陽城"}]}]}
    WORLD = {"cities": []}

    def test_same_commandery_collides_other_commandery_does_not(self):
        doc = B.build(self._tiles([
            self._rec("1", "P1", "양성현", "阳城县"),
            self._rec("2", "P1", "양성현", "襄城县"),
            self._rec("3", "P2", "양성현", "阳城县"),
        ]), self.WORLD, self.TABLE, self.UNITS)
        self.assertEqual(len(doc["collisions"]), 1)
        self.assertEqual([m["jurisdictionId"] for m in doc["collisions"][0]["members"]], ["1", "2"])
        self.assertEqual(doc["summary"]["displayNamesSharedAcrossCommanderies"], 1)

    def test_red_probe_new_collision_changes_the_output(self):
        # 적색 프로브의 단위판: 표시명 하나를 겹치게 바꾸면 목록이 달라진다(→ --check 적색).
        base = [self._rec("1", "P1", "양성현", "阳城县"), self._rec("2", "P1", "양적현", "阳翟县")]
        before = B.build(self._tiles(base), self.WORLD, self.TABLE, self.UNITS)
        mutated = copy.deepcopy(base)
        mutated[1]["displayName"] = "양성현"
        after = B.build(self._tiles(mutated), self.WORLD, self.TABLE, self.UNITS)
        self.assertEqual(before["collisions"], [])
        self.assertEqual(len(after["collisions"]), 1)
        self.assertNotEqual(B.render_web(before), B.render_web(after))

    def test_ambiguous_traditional_form_fails_closed(self):
        resolve = B.traditional_resolver(self.TABLE, {"groups": []})
        self.assertEqual(resolve("阳城"), ("陽城", "SIMPLIFICATION_TABLE"))
        self.assertEqual(resolve("始宁"), ("始寧", "REVIEWED"))
        with self.assertRaises(ValueError):
            resolve("永宁")  # 寧·甯 후보, 정본에도 판정에도 없다

    def test_runtime_disagreement_is_classified(self):
        world = {"cities": [
            {"id": 10, "spatialProvinceId": "1", "meta": {"displayName": "영천군 양성현(阳城)"}},
            {"id": 11, "spatialProvinceId": "2", "meta": {"displayName": "영천군 선성현"}},
        ]}
        doc = B.build(self._tiles([
            self._rec("1", "P1", "양성현", "阳城县"),
            self._rec("2", "P1", "양성현", "襄城县"),
        ]), world, self.TABLE, self.UNITS)
        self.assertEqual(doc["collisions"][0]["classification"], "TILES_READING_DIVERGES_FROM_RUNTIME")


if __name__ == "__main__":
    unittest.main()
