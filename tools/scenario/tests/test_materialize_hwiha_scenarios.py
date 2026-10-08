import ast
import copy
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from tools.scenario import materialize_hwiha_scenarios as materializer
from tools.scenario.materialize_hwiha_scenarios import (
    ACTIVE_CODES, CLASSIC_ARCHIVE_CODES, ALTERNATE_ARCHIVE_CODES,
    ARCHIVE, PACKAGED, ROOT, inputs, materialize, materialized,
    prepared_materialization, main,
    REVIEWED_RTK14_ALIASES, REVIEWED_RTK14_YEAR_VARIANTS,
    REVIEWED_RTK14_ROSTER_VARIANTS,
    REVIEWED_RTK14_PROFILE_VARIANTS,
    reviewed_rtk14_binding, materialize_reviewed_person, project_reviewed_roster,
)


class HistoricalHwihaMaterializationTest(unittest.TestCase):
    def test_reviewed_variant_literal_keys_are_unique(self):
        source = ast.parse((ROOT / "tools/scenario/materialize_hwiha_scenarios.py").read_text())
        for assignment in source.body:
            if not isinstance(assignment, ast.Assign) or not isinstance(assignment.value, ast.Dict):
                continue
            names = [target.id for target in assignment.targets if isinstance(target, ast.Name)]
            if not any(name.startswith("REVIEWED_RTK14_") for name in names):
                continue
            keys = [ast.literal_eval(key) for key in assignment.value.keys]
            self.assertEqual(len(keys), len(set(keys)), names)

    def test_profile_specific_roster_variants_keep_their_own_scenario_allowlists(self):
        rtk14 = json.loads((ROOT / PACKAGED / "scenario_3190.json").read_text())
        index = {"people": {row[1]: row for row in rtk14["general"]},
                 "policies": {row["name"]: row for row in rtk14["personPolicies"]}}
        for (name, birth, death, profile), (target, officer_id, scenarios) in REVIEWED_RTK14_PROFILE_VARIANTS.items():
            for code in scenarios:
                with self.subTest(name=name, code=code):
                    source = json.loads((ROOT / ARCHIVE / f"scenario_{code}.json").read_text())
                    matches = [row for row in source["general"] + source.get("general_ex", [])
                               if (row[1], row[9], row[10], tuple(row[5:8])) ==
                               (name, birth, death, profile)]
                    self.assertEqual(1, len(matches))
                    row = matches[0]
                    binding = reviewed_rtk14_binding(row, index, code)
                    self.assertIsNotNone(binding)
                    self.assertEqual((target, officer_id), (binding[0][1], binding[1]["officerId"]))
                    altered = row.copy()
                    altered[5] += 1
                    self.assertIsNone(reviewed_rtk14_binding(altered, index, code))
                    self.assertIsNone(reviewed_rtk14_binding(row, index, 990002))

    def test_rtk14_identity_binding_requires_the_same_person_and_exact_years(self):
        rtk14 = json.loads((ROOT / PACKAGED / "scenario_3190.json").read_text())
        index = {
            "people": {row[1]: row for row in rtk14["general"]},
            "policies": {row["name"]: row for row in rtk14["personPolicies"]},
        }
        archived = [row for code in ACTIVE_CODES
                    for campaign in [json.loads((ROOT / ARCHIVE / f"scenario_{code}.json").read_text())]
                    for row in campaign["general"] + campaign.get("general_ex", []) + campaign.get("general_neutral", [])]
        for (archive_name, birth, death), rtk14_name in REVIEWED_RTK14_ALIASES.items():
            with self.subTest(name=archive_name):
                source = next(row for row in archived if row[1] == archive_name and row[9:11] == [birth, death])
                bound = reviewed_rtk14_binding(source, index)
                self.assertIsNotNone(bound)
                self.assertEqual(rtk14_name, bound[0][1])
                self.assertEqual(rtk14_name, bound[1]["name"])
                converted, declaration = materialize_reviewed_person(source, bound)
                self.assertEqual(source[1], converted[1])
                self.assertEqual(source[3:5], converted[3:5])
                self.assertEqual(bound[0][5:8] + bound[0][14:16], converted[5:8] + converted[14:16])
                self.assertEqual(archive_name, declaration["name"])
                self.assertEqual(bound[1]["stats"], declaration["stats"])
                self.assertEqual(bound[0][2], converted[2])
                changed_year = source.copy()
                changed_year[10] += 1
                self.assertIsNone(reviewed_rtk14_binding(changed_year, index))
        self.assertIsNone(reviewed_rtk14_binding(next(row for row in archived if row[1] == "유굉"), index))
        date_conflicts = [row for row in archived
                          if row[1] in index["people"] and index["people"][row[1]][9:11] != row[9:11]
                          and (row[1], row[9], row[10]) not in REVIEWED_RTK14_ALIASES]
        self.assertTrue(date_conflicts)
        for row in date_conflicts:
            with self.subTest(conflicting_name=row[1], years=row[9:11]):
                self.assertIsNone(reviewed_rtk14_binding(row, index))

    def test_all_reviewed_scenarios_preserve_roster_territory_and_resources(self):
        city_ids, template = inputs()
        county_ids = {row["countyId"] for row in template["warehouses"]}
        outputs, unresolved, missing_rulers = prepared_materialization()
        self.assertEqual(set(ACTIVE_CODES), set(outputs))
        self.assertTrue(unresolved)
        self.assertTrue(missing_rulers)
        with self.assertRaisesRegex(ValueError, "unreviewed ruler policies"):
            materialized()
        for code in ACTIVE_CODES:
            with self.subTest(code=code):
                source = json.loads((ROOT / ARCHIVE / f"scenario_{code}.json").read_text())
                generated = materialize(code, source, city_ids, template)
                reviewed, _ = project_reviewed_roster(
                    code, generated, json.loads((ROOT / PACKAGED / "scenario_3190.json").read_text()))
                self.assertEqual(reviewed, json.loads(outputs[code]))
                self.assertEqual(generated["nation"], reviewed["nation"])
                self.assertEqual(generated["rulers"], reviewed["rulers"])
                self.assertEqual("GENERAL_RETAINER_CAMPAIGN", generated["worldFormat"])
                self.assertEqual(source["map"], generated["map"])
                self.assertEqual(source["general"], generated["general"])
                self.assertEqual(source.get("general_ex"), generated.get("general_ex"))
                self.assertEqual([n[8] for n in source["nation"]], [n[8] for n in generated["nation"]])
                self.assertEqual(len(source["nation"]), len(generated["rulers"]))
                self.assertEqual(len(source["nation"]), len(generated["lords"]))
                owners = [city for nation in generated["nation"] for city in nation[8]]
                self.assertEqual(len(owners), len(set(owners)))
                self.assertTrue(set(owners) <= city_ids)
                stock = {row["countyId"]: row["stock"] for row in generated["warehouses"]["warehouses"]}
                self.assertEqual(county_ids, set(stock))
                for old, new in zip(source["nation"], generated["nation"], strict=True):
                    if old[8]:
                        self.assertEqual([0, 0], new[2:4])
                        self.assertEqual(old[2], stock[old[8][0]]["money"])
                        self.assertEqual(old[3], stock[old[8][0]]["grain"])
                    else:
                        self.assertEqual(old[2:4], new[2:4])
                self.assertEqual(sum(n[2] for n in source["nation"]),
                                 sum(n[2] for n in generated["nation"]) +
                                 sum(row["money"] for row in stock.values()))
                self.assertEqual(sum(n[3] for n in source["nation"]),
                                 sum(n[3] for n in generated["nation"]) +
                                 sum(row["grain"] for row in stock.values()))

    def test_reviewed_year_variants_bind_only_the_named_ruler_in_allowed_scenarios(self):
        rtk14 = json.loads((ROOT / PACKAGED / "scenario_3190.json").read_text())
        index = {
            "people": {row[1]: row for row in rtk14["general"]},
            "policies": {row["name"]: row for row in rtk14["personPolicies"]},
        }
        for (name, birth, death), (target_name, officer_id, scenarios) in REVIEWED_RTK14_YEAR_VARIANTS.items():
            for code in scenarios:
                with self.subTest(name=name, years=(birth, death), code=code):
                    source = json.loads((ROOT / ARCHIVE / f"scenario_{code}.json").read_text())
                    rows = [row for row in source["general"] + source.get("general_ex", [])
                            if row[1] == name and row[9:11] == [birth, death] and row[8] == 12]
                    self.assertEqual(1, len(rows))
                    row = rows[0]
                    expected_nation = {"장각": "황건적", "장양1": "장양", "맹획": "남중 반란군"}.get(name, name)
                    self.assertEqual(expected_nation, source["nation"][row[3] - 1][0])
                    self.assertIsNone(reviewed_rtk14_binding(row, index))
                    binding = reviewed_rtk14_binding(row, index, code)
                    self.assertIsNotNone(binding)
                    self.assertEqual(target_name, binding[0][1])
                    self.assertEqual(officer_id, binding[1]["officerId"])
                    converted, declaration = materialize_reviewed_person(row, binding)
                    self.assertEqual([birth, death], converted[9:11])
                    self.assertEqual(binding[0][5:8] + binding[0][14:16], converted[5:8] + converted[14:16])
                    self.assertEqual(name, declaration["name"])
                    self.assertIsNone(reviewed_rtk14_binding(row, index, 990002))
        for code in ACTIVE_CODES:
            source = json.loads((ROOT / ARCHIVE / f"scenario_{code}.json").read_text())
            bound_ids = [binding[1]["officerId"] for row in
                         source["general"] + source.get("general_ex", []) + source.get("general_neutral", [])
                         if (binding := reviewed_rtk14_binding(row, index, code)) is not None]
            self.assertEqual(len(bound_ids), len(set(bound_ids)), code)

    def test_reviewed_roster_date_variant_requires_identity_profile_and_scenario(self):
        rtk14 = json.loads((ROOT / PACKAGED / "scenario_3190.json").read_text())
        index = {
            "people": {row[1]: row for row in rtk14["general"]},
            "policies": {row["name"]: row for row in rtk14["personPolicies"]},
        }
        for (name, birth, death), (target, officer_id, scenarios, profiles) in REVIEWED_RTK14_ROSTER_VARIANTS.items():
            for code in scenarios:
                with self.subTest(name=name, code=code):
                    source = json.loads((ROOT / ARCHIVE / f"scenario_{code}.json").read_text())
                    matches = [row for row in source["general"] + source.get("general_ex", [])
                               if row[1] == name and row[9:11] == [birth, death]]
                    self.assertEqual(1, len(matches))
                    row = matches[0]
                    self.assertIn(tuple(row[5:8]), profiles)
                    self.assertIsNone(reviewed_rtk14_binding(row, index))
                    binding = reviewed_rtk14_binding(row, index, code)
                    self.assertIsNotNone(binding)
                    self.assertEqual(target, binding[0][1])
                    self.assertEqual(officer_id, binding[1]["officerId"])
                    converted, declaration = materialize_reviewed_person(row, binding)
                    self.assertEqual(row[9:11], converted[9:11])
                    self.assertEqual(binding[0][5:8] + binding[0][14:16], converted[5:8] + converted[14:16])
                    self.assertEqual(row[1], declaration["name"])
                    altered = row.copy()
                    altered[5] += 1
                    self.assertIsNone(reviewed_rtk14_binding(altered, index, code))
                    self.assertIsNone(reviewed_rtk14_binding(row, index, 990002))
            for code in set(ACTIVE_CODES) - set(scenarios):
                source = json.loads((ROOT / ARCHIVE / f"scenario_{code}.json").read_text())
                for row in source["general"] + source.get("general_ex", []):
                    if row[1] == name and row[9:11] == [birth, death] and not (
                            (ruler := REVIEWED_RTK14_YEAR_VARIANTS.get((name, birth, death)))
                            and code in ruler[2] and row[8] == 12) and not (
                            (profile := REVIEWED_RTK14_PROFILE_VARIANTS.get(
                                (name, birth, death, tuple(row[5:8])))) and code in profile[2]):
                        self.assertIsNone(reviewed_rtk14_binding(row, index, code))

    def test_source_bound_projection_preserves_archive_identity_and_remains_incomplete(self):
        rtk14 = json.loads((ROOT / PACKAGED / "scenario_3190.json").read_text())
        bound_identities = set()
        unbound_identities = set()
        for code in ACTIVE_CODES:
            source = json.loads((ROOT / ARCHIVE / f"scenario_{code}.json").read_text())
            projected, unresolved = project_reviewed_roster(code, source, rtk14)
            self.assertTrue(unresolved, code)
            unbound_identities.update(unresolved)
            self.assertEqual(len(projected["personPolicies"]),
                             len({p["officerId"] for p in projected["personPolicies"]}), code)
            policy_by_name = {p["name"]: p for p in projected["personPolicies"]}
            for group in ("general", "general_ex", "general_neutral"):
                for old, new in zip(source.get(group, []), projected.get(group, []), strict=True):
                    self.assertEqual(old[0:2], new[0:2])
                    self.assertEqual(old[3:5], new[3:5])
                    self.assertEqual(old[8:13], new[8:13])
                    if len(old) > 13:
                        self.assertEqual(old[13], new[13])
                    identity = (old[1], old[9], old[10])
                    if old[1] in policy_by_name:
                        bound_identities.add(identity)
                        policy = policy_by_name[old[1]]
                        self.assertEqual("rtk14-workbook:190.1", policy["statSourceId"])
                        self.assertEqual([policy["stats"][key] for key in
                                          ("leadership", "strength", "intelligence", "politics", "charm")],
                                         new[5:8] + new[14:16])
                    else:
                        self.assertEqual(old, new)
        self.assertEqual(946, len(bound_identities | unbound_identities))
        self.assertEqual(881, len(bound_identities - unbound_identities))
        self.assertEqual(65, len(unbound_identities))

    def test_classic_archive_remaining_people_are_unresolved(self):
        rtk14 = json.loads((ROOT / PACKAGED / "scenario_3190.json").read_text())
        for code in CLASSIC_ARCHIVE_CODES:
            with self.subTest(code=code):
                source = json.loads((ROOT / ARCHIVE / f"scenario_{code}.json").read_text())
                _, unresolved = project_reviewed_roster(code, source, rtk14)
                self.assertEqual(22 if code == 1010 else 21, len(unresolved))

    def test_alternate_archive_ambiguous_identities_stay_unbound(self):
        rtk14 = json.loads((ROOT / PACKAGED / "scenario_3190.json").read_text())
        index = {"people": {row[1]: row for row in rtk14["general"]},
                 "policies": {row["name"]: row for row in rtk14["personPolicies"]}}
        ambiguous = {
            ("공지", 178, 197), ("두씨", 172, 236), ("손환", 166, 228),
            ("유순", 172, 255), ("유씨", 164, 215), ("유주", 172, 233),
            ("이적", 162, 273), ("장의", 153, 215), ("장초", 168, 196),
            ("조우", 199, 278), ("조절", 190, 260), ("학맹", 156, 300),
        }
        for code in ALTERNATE_ARCHIVE_CODES:
            with self.subTest(code=code):
                source = json.loads((ROOT / ARCHIVE / f"scenario_{code}.json").read_text())
                rows = [row for group in ("general", "general_ex", "general_neutral")
                        for row in source.get(group, [])
                        if (row[1], row[9], row[10]) in ambiguous]
                expected = ambiguous - ({("학맹", 156, 300)} if code == 1041 else set())
                self.assertEqual(expected, {(row[1], row[9], row[10]) for row in rows})
                self.assertTrue(all(reviewed_rtk14_binding(row, index, code) is None for row in rows))

    def test_small_and_landless_factions_keep_their_reviewed_territory(self):
        city_ids, template = inputs()
        for code, expected in {
            1020: {"마등": []}, 1021: {"장양": [], "마등": []},
            1030: {"공주": []}, 1041: {"엄백호": []},
            1050: {"유비": []}, 1060: {"유비": [419]},
            1080: {"마초": []},
        }.items():
            scenario = materialize(code, json.loads((ROOT / ARCHIVE / f"scenario_{code}.json").read_text()),
                                   city_ids, template)
            for nation in scenario["nation"]:
                if nation[0] in expected:
                    self.assertEqual(expected[nation[0]], nation[8], (code, nation[0]))

    def test_1031_liu_biao_ruler_uses_the_archived_identity(self):
        city_ids, template = inputs()
        source = json.loads((ROOT / ARCHIVE / "scenario_1031.json").read_text())
        generated = materialize(1031, source, city_ids, template)
        self.assertIn({"nation": "유표", "general": "유표1"}, generated["rulers"])
        self.assertEqual(next(row[8] for row in generated["general"] if row[1] == "유표1"), 1)


class HistoricalSeedEntryTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        archived = json.loads((ROOT / ARCHIVE / "scenario_1010.json").read_text())
        self.source = copy.deepcopy(archived)
        nation = copy.deepcopy(next(row for row in archived["nation"] if row[0] == "황건적"))
        nation[8] = nation[8][:1]
        officer = copy.deepcopy(next(row for row in archived["general"] if row[1] == "장각"))
        officer[3] = 1
        self.source.update(nation=[nation], general=[officer], general_ex=[], general_neutral=[], cities=[])
        county_ids = {nation[8][0], officer[4]}
        county_ids.discard(None)
        self.write(materializer.CLAIMS, {"activeScenarioCodes": [1010]})
        self.write(materializer.MAP, {"cities": [{"id": county} for county in sorted(county_ids)]})
        self.write(PACKAGED / "scenario_990002.json", {"warehouses": {
            "topologyHash": "test-only", "warehouses": [
                {"countyId": county, "stock": {"money": 0, "grain": 0, "iron": 0,
                                                "timber": 0, "horses": 0}}
                for county in sorted(county_ids)]}})
        self.write(PACKAGED / "scenario_3190.json",
                   json.loads((ROOT / PACKAGED / "scenario_3190.json").read_text()))
        self.write(ARCHIVE / "scenario_1010.json", self.source)
        self.target = self.root / PACKAGED / "scenario_1010.json"

    def write(self, relative: Path, document: dict) -> None:
        target = self.root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(document, ensure_ascii=False), encoding="utf-8")

    def test_write_and_check_use_confirmed_five_stats_and_preserve_placement(self):
        with mock.patch.object(materializer, "ACTIVE_CODES", (1010,)):
            main(["--write"], self.root)
            main(["--check"], self.root)
        generated = json.loads(self.target.read_text())
        old = self.source["general"][0]
        new = generated["general"][0]
        rtk14 = json.loads((ROOT / PACKAGED / "scenario_3190.json").read_text())
        approved = next(row for row in rtk14["general"] if row[1] == "장각")
        policy = next(row for row in rtk14["personPolicies"] if row["name"] == "장각")
        self.assertEqual(approved[2], new[2])
        self.assertEqual(approved[5:8] + approved[14:16], new[5:8] + new[14:16])
        self.assertEqual([policy], generated["personPolicies"])
        self.assertEqual(old[0:2], new[0:2])
        self.assertEqual(old[3:5], new[3:5])
        self.assertEqual(old[8:13], new[8:13])
        self.assertEqual(self.source["nation"][0][8], generated["nation"][0][8])
        self.assertEqual([{"nation": "황건적", "general": "장각"}], generated["rulers"])
        original = self.target.read_bytes()
        self.target.write_text("invalid packaged seed", encoding="utf-8")
        with mock.patch.object(materializer, "ACTIVE_CODES", (1010,)):
            with self.assertRaisesRegex(SystemExit, "differs"):
                main(["--check"], self.root)
        self.assertEqual(b"invalid packaged seed", self.target.read_bytes())
        self.target.write_bytes(original)

    def test_unreviewed_or_duplicate_input_cannot_overwrite_a_seed(self):
        self.target.write_text("existing packaged seed", encoding="utf-8")
        changed = copy.deepcopy(self.source)
        changed["general"][0][9] += 1
        self.write(ARCHIVE / "scenario_1010.json", changed)
        with mock.patch.object(materializer, "ACTIVE_CODES", (1010,)):
            with self.assertRaisesRegex(SystemExit, "unreviewed ruler policies"):
                main(["--write"], self.root)
        self.assertEqual("existing packaged seed", self.target.read_text())

        duplicate = copy.deepcopy(self.source)
        duplicate["general"].append(copy.deepcopy(duplicate["general"][0]))
        self.write(ARCHIVE / "scenario_1010.json", duplicate)
        with mock.patch.object(materializer, "ACTIVE_CODES", (1010,)):
            with self.assertRaisesRegex(SystemExit, "duplicate officer names"):
                main(["--write"], self.root)
        self.assertEqual("existing packaged seed", self.target.read_text())

    def test_later_incomplete_scenario_cannot_partially_overwrite_earlier_seed(self):
        self.target.write_text("existing first seed", encoding="utf-8")
        second = self.root / PACKAGED / "scenario_1020.json"
        second.write_text("existing second seed", encoding="utf-8")
        second_source = copy.deepcopy(self.source)
        second_source["general"][0][9] += 1
        self.write(ARCHIVE / "scenario_1020.json", second_source)
        self.write(materializer.CLAIMS, {"activeScenarioCodes": [1010, 1020]})
        with mock.patch.object(materializer, "ACTIVE_CODES", (1010, 1020)):
            with self.assertRaisesRegex(SystemExit, "unreviewed ruler policies"):
                main(["--write"], self.root)
            with self.assertRaisesRegex(SystemExit, "unreviewed ruler policies"):
                main(["--check"], self.root)
        self.assertEqual("existing first seed", self.target.read_text())
        self.assertEqual("existing second seed", second.read_text())


if __name__ == "__main__":
    unittest.main()
