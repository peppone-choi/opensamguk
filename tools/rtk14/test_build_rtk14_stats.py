import copy
import csv
import hashlib
import json
import os
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_rtk14_stats as b


REAL_WORKBOOK_PATH = os.environ.get("RTK14_WORKBOOK_PATH")
REAL_WORKBOOK = Path(REAL_WORKBOOK_PATH) if REAL_WORKBOOK_PATH else None


def source_row(number, name=None, **changes):
    birth = 100 + number
    row = {
        "number": number,
        "name": name or f"장수{number}",
        "gender": "남" if number % 2 else "여",
        "birth": birth,
        "appearance": birth + 18,
        "death": birth + 68,
        "L": 30 + number % 40,
        "S": 31 + number % 40,
        "I": 32 + number % 40,
        "politics": 33 + number % 40,
        "charm": 34 + number % 40,
        "ideology": "왕도",
    }
    row.update(changes)
    if "lifespan" not in changes:
        row["lifespan"] = row["death"] - row["birth"] + 1
    if "activityYears" not in changes:
        row["activityYears"] = row["death"] - row["appearance"] + 1
    if "total" not in changes:
        row["total"] = sum(row[key] for key in ("L", "S", "I", "politics", "charm"))
    return row


def source_rows():
    return [source_row(number) for number in range(1, 1001)]


def portrait_fingerprint(row):
    values = [
        row["birth"], row["death"], row["L"], row["S"], row["I"],
        row["politics"], row["charm"],
    ]
    return hashlib.sha256(json.dumps(values, separators=(",", ":")).encode()).hexdigest()


def write_portrait_contract(directory, rows):
    registry = directory / "officer-id-registry.tsv"
    name_map = directory / "officer-name-map.tsv"
    with registry.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, delimiter="\t", lineterminator="\n")
        writer.writerow(("id", "stable_key", "fingerprint_sha256", "name_kanji", "name_reading"))
        for row in rows:
            writer.writerow((10000 + row["number"], f"key-{row['number']}", portrait_fingerprint(row), f"漢{row['number']}", f"reading-{row['number']}"))
    with name_map.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, delimiter="\t", lineterminator="\n")
        writer.writerow(("id", "name_korean"))
        for row in rows:
            writer.writerow((10000 + row["number"], row["name"]))
    return registry, name_map


def legacy_tuple(name, leadership=1, strength=2, intel=3, birth=150, death=220):
    return [777, name, "portrait", 7, "city", leadership, strength, intel, 9, birth, death, "ego", "special", "text"]


class Rtk14StatsBuilderTest(unittest.TestCase):
    def test_portrait_registry_joins_every_source_by_unique_fingerprint_and_name(self):
        rows = source_rows()
        rtk = b._source_rows_to_rtk(rows)
        with TemporaryDirectory() as td:
            registry, name_map = write_portrait_contract(Path(td), rows)

            b.attach_portrait_ids(rtk, registry, name_map)

        self.assertEqual(10001, rtk["장수1"][0]["portraitId"])
        self.assertEqual(11000, rtk["장수1000"][0]["portraitId"])
        self.assertEqual(rows, b.rtk_to_source_rows(rtk))

    def test_portrait_registry_picture_replaces_legacy_and_default_without_changing_source_number(self):
        rows = source_rows()
        rtk = b._source_rows_to_rtk(rows)
        with TemporaryDirectory() as td:
            registry, name_map = write_portrait_contract(Path(td), rows)
            b.attach_portrait_ids(rtk, registry, name_map)

        scenario = {
            "startYear": 180,
            "general": [legacy_tuple("장수1", leadership=31, strength=32, intel=33, birth=101, death=169)],
            "general_ex": [],
        }
        enriched, _ = b.enrich_scenario(scenario, rtk)

        self.assertEqual("10001.png", enriched["general"][0][2])
        self.assertEqual(1, enriched["general"][0][17])
        added = next(row for row in enriched["general"] if row[17] == 1000)
        self.assertEqual("11000.png", added[2])
        self.assertEqual(1000, added[17])

    def test_portrait_registry_uses_an_exact_unique_korean_name_when_stats_drift(self):
        contract_rows = source_rows()
        drifted_rows = copy.deepcopy(contract_rows)
        drifted_rows[27]["L"] += 1
        drifted_rows[27]["total"] += 1
        with TemporaryDirectory() as td:
            registry, name_map = write_portrait_contract(Path(td), contract_rows)

            mapping = b.attach_portrait_ids(b._source_rows_to_rtk(drifted_rows), registry, name_map)

        self.assertEqual(10028, mapping[28])

    def test_portrait_registry_requires_fingerprint_for_a_duplicate_korean_name(self):
        contract_rows = source_rows()
        contract_rows[1]["name"] = contract_rows[0]["name"]
        drifted_rows = copy.deepcopy(contract_rows)
        drifted_rows[1]["L"] += 1
        drifted_rows[1]["total"] += 1
        with TemporaryDirectory() as td:
            registry, name_map = write_portrait_contract(Path(td), contract_rows)

            with self.assertRaisesRegex(ValueError, "duplicate Korean name"):
                b.attach_portrait_ids(b._source_rows_to_rtk(drifted_rows), registry, name_map)

    def test_portrait_registry_fails_closed_on_name_or_identity_drift(self):
        rows = source_rows()
        with TemporaryDirectory() as td:
            directory = Path(td)
            registry, name_map = write_portrait_contract(directory, rows)
            name_lines = name_map.read_text(encoding="utf-8").splitlines()
            name_lines[1] = "10001\t다른장수"
            name_map.write_text("\n".join(name_lines) + "\n", encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "Korean name"):
                b.attach_portrait_ids(b._source_rows_to_rtk(rows), registry, name_map)

            registry, name_map = write_portrait_contract(directory, rows)
            registry_lines = registry.read_text(encoding="utf-8").splitlines()
            registry.write_text("\n".join(registry_lines[:-1]) + "\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "10001..11000"):
                b.attach_portrait_ids(b._source_rows_to_rtk(rows), registry, name_map)

    def test_portrait_registry_rejects_duplicate_stable_id_assignment(self):
        contract_rows = source_rows()
        duplicate_identity_rows = copy.deepcopy(contract_rows)
        duplicate_identity_rows[1] = {**duplicate_identity_rows[0], "number": 2}
        with TemporaryDirectory() as td:
            registry, name_map = write_portrait_contract(Path(td), contract_rows)

            with self.assertRaisesRegex(ValueError, "exactly 1000 portrait identities"):
                b.attach_portrait_ids(b._source_rows_to_rtk(duplicate_identity_rows), registry, name_map)

    def test_deploy_tests_classpath_baseline_before_materializing_image_rosters(self):
        workflow = (
            Path(__file__).resolve().parents[2] / ".github" / "workflows" / "deploy.yml"
        ).read_text(encoding="utf-8")

        self.assertLess(
            workflow.index("- name: Build + test JVM"),
            workflow.index("- name: Materialize RTK14 scenario stats for image build"),
        )

    def test_deploy_validates_materialized_rosters_with_runtime_importer(self):
        workflow = (
            Path(__file__).resolve().parents[2] / ".github" / "workflows" / "deploy.yml"
        ).read_text(encoding="utf-8")

        materialize = workflow.index("- name: Materialize RTK14 scenario stats for image build")
        validate = workflow.index("- name: Validate materialized scenario seed contracts")
        image_build = workflow.index("- name: Build + push game-engine image")
        self.assertLess(materialize, validate)
        self.assertLess(validate, image_build)
        self.assertIn(
            "opensamguk.infra.seed.ScenarioJsonTest.every committed runtime scenario uses the canonical Han world contract",
            workflow[validate:image_build],
        )

    def test_source_rows_round_trip_all_contract_columns(self):
        rows = source_rows()

        rtk = b._source_rows_to_rtk(rows)

        self.assertEqual(rows, b.rtk_to_source_rows(rtk))
        self.assertEqual(1000, len(b.source_rows(rtk)))
        self.assertEqual(set(range(1, 1001)), {row["number"] for row in b.source_rows(rtk)})

    def test_source_rows_fail_closed_on_bad_derived_values(self):
        rows = source_rows()
        rows[0]["activityYears"] += 1

        with self.assertRaisesRegex(ValueError, "activityYears"):
            b._source_rows_to_rtk(rows)

        rows = source_rows()
        rows[1]["number"] = rows[0]["number"]
        with self.assertRaisesRegex(ValueError, "duplicate"):
            b._source_rows_to_rtk(rows)

    def test_existing_tuple_gets_full_workbook_fields_without_identity_mutation(self):
        rows = source_rows()
        rows[0] = source_row(
            1, "구력거", birth=152, appearance=170, death=193,
            L=80, S=69, I=56, politics=12, charm=34, gender="남", ideology="패도",
        )
        rtk = b._source_rows_to_rtk(rows)
        original = legacy_tuple("구역거", leadership=51, strength=72, intel=49, birth=168, death=190)
        scenario = {"startYear": 181, "general": [original.copy()], "general_ex": []}

        enriched, audit = b.enrich_scenario(scenario, rtk)

        row = enriched["general"][0]
        self.assertEqual(original[:5], row[:5])
        self.assertEqual(original[8], row[8])
        self.assertEqual(original[11:14], row[11:14])
        self.assertEqual([80, 69, 56], row[5:8])
        self.assertEqual([152, 193], row[9:11])
        self.assertEqual([12, 34, 170, 1, "남", 42, 24, 251, "패도"], row[14:23])
        self.assertFalse(row[23])
        self.assertFalse(row[24])
        self.assertEqual(1000, len(enriched["general"]))
        self.assertNotIn("general_neutral", enriched)
        self.assertEqual(1000, audit["representedSourceRows"])
        self.assertEqual([], audit["missingSourceIds"])

    def test_all_workbook_rows_are_added_once_to_general(self):
        rows = source_rows()
        rtk = b._source_rows_to_rtk(rows)
        scenario = {"startYear": 180, "general": [legacy_tuple("장수1", leadership=31, strength=32, intel=33, birth=101, death=169)], "general_ex": []}

        enriched, audit = b.enrich_scenario(scenario, rtk)

        rows_by_section = [
            row
            for section in b.RUNTIME_SECTIONS
            for row in enriched.get(section, [])
            if isinstance(row, list) and len(row) > 17 and type(row[17]) is int
        ]
        source_numbers = [row[17] for row in rows_by_section]
        self.assertEqual(1000, len(source_numbers))
        self.assertEqual(set(range(1, 1001)), set(source_numbers))
        self.assertEqual(999, audit["addedRows"])
        self.assertEqual(1000, audit["representedSourceRows"])
        added_rows = [row for row in enriched["general"] if len(row) > 23 and row[23] is True]
        self.assertEqual(999, len(added_rows))
        self.assertTrue(all(
            row[3] == 0 and row[4] is None and row[8] == 0 and row[24] is False
            for row in added_rows
        ))

    def test_duplicate_source_name_uses_next_free_suffix_for_added_general(self):
        rows = source_rows()
        rows[0] = source_row(1, "동명", L=80, S=81, I=82)
        rows[1] = source_row(2, "동명", L=20, S=21, I=22)
        rtk = b._source_rows_to_rtk(rows)
        scenario = {"startYear": 180, "general": [legacy_tuple("동명1", leadership=80, strength=81, intel=82, birth=101, death=169)], "general_ex": []}

        enriched, audit = b.enrich_scenario(scenario, rtk)

        source_two = next(row for row in enriched["general"] if row[17] == 2)
        self.assertEqual("동명2", source_two[1])
        self.assertTrue(source_two[23])
        self.assertTrue(any(detail["sourceNumber"] == 2 and detail["assignedName"] == "동명2" for detail in audit["nameCollisions"]))

    def test_runtime_collision_fails_closed_without_exact_reviewed_override(self):
        rows = source_rows()
        rows[0] = source_row(1, "동명", L=31, S=32, I=33)
        rtk = b._source_rows_to_rtk(rows)
        scenario = {
            "startYear": 180,
            "general": [
                legacy_tuple("동명1", leadership=31, strength=32, intel=33, birth=101, death=169),
                legacy_tuple("동명2", leadership=1, strength=2, intel=3, birth=160, death=200),
            ],
            "general_ex": [],
        }

        with self.assertRaisesRegex(ValueError, "no explicit reviewed override"):
            b.enrich_scenario(scenario, rtk, scenario_identity="scenario_test.json", collision_overrides={})

    def test_runtime_collision_uses_exact_reviewed_override_and_keeps_source_ids_one_to_one(self):
        rows = source_rows()
        rows[0] = source_row(1, "동명", L=31, S=32, I=33)
        rtk = b._source_rows_to_rtk(rows)
        scenario = {
            "startYear": 180,
            "general": [
                legacy_tuple("동명1", leadership=31, strength=32, intel=33, birth=101, death=169),
                legacy_tuple("동명2", leadership=1, strength=2, intel=3, birth=160, death=200),
            ],
            "general_ex": [],
        }
        identity = b.runtime_identity(
            "scenario_test.json", "general", 1, "동명2", 1, 2, 3, 160, 200
        )

        enriched, audit = b.enrich_scenario(
            scenario,
            rtk,
            scenario_identity="scenario_test.json",
            collision_overrides={identity: {"politics": 71, "charm": 44, "rationale": "Reviewed exact collision fixture."}},
        )

        collided = enriched["general"][1]
        self.assertEqual([71, 44], collided[14:16])
        self.assertIsNone(collided[17])
        self.assertIsNone(collided[23])
        self.assertTrue(collided[24])
        self.assertEqual(identity, audit["legacyOnlyDetails"][0]["identity"])
        self.assertEqual("collision", audit["legacyOnlyDetails"][0]["overrideKind"])
        self.assertTrue(audit["legacyOnlyDetails"][0]["reviewedOverride"])
        self.assertEqual(1, len(audit["collisions"]))
        self.assertEqual(1, audit["legacyOnlyRows"])
        source_ids = [row[17] for row in enriched["general"] if row[17] is not None]
        self.assertEqual(1000, len(source_ids))
        self.assertEqual(1000, len(set(source_ids)))

    def test_second_enrichment_is_idempotent(self):
        rtk = b._source_rows_to_rtk(source_rows())
        scenario = {"startYear": 180, "general": [legacy_tuple("장수1", leadership=31, strength=32, intel=33, birth=101, death=169)], "general_ex": []}

        first, first_audit = b.enrich_scenario(scenario, rtk)
        second, second_audit = b.enrich_scenario(copy.deepcopy(first), rtk)

        self.assertEqual(first, second)
        self.assertEqual(999, first_audit["addedRows"])
        self.assertEqual(0, first_audit["addedGeneralNeutral"])
        self.assertEqual(0, second_audit["addedRows"])
        self.assertEqual(1000, second_audit["representedSourceRows"])
        self.assertEqual([], second_audit["missingSourceIds"])

    def test_enrichment_refreshes_seed_contract_for_materialized_roster(self):
        rtk = b._source_rows_to_rtk(source_rows())
        scenario = {
            "startYear": 180,
            "seedContract": {"activeGenerals": {"base": 1, "extended": 2}},
            "general": [],
            "general_ex": [
                legacy_tuple("장수12", leadership=42, strength=43, intel=44, birth=112, death=180),
                legacy_tuple("검토전용", leadership=67, strength=63, intel=61, birth=150, death=220),
            ],
        }

        enriched, audit = b.enrich_scenario(
            scenario,
            rtk,
            unmatched_overrides={
                "검토전용": {
                    "politics": 71,
                    "charm": 44,
                    "rationale": "Active legacy-only extended-row fixture.",
                },
            },
        )

        expected = {"base": 51, "extended": 52}
        self.assertEqual({"activeGenerals": expected}, enriched["seedContract"])
        self.assertEqual(expected, audit["activeGeneralContract"])

    def test_manual_override_preserves_runtime_first_three_stats_without_source_id(self):
        rtk = b._source_rows_to_rtk(source_rows())
        scenario = {"startYear": 220, "general": [legacy_tuple("유약", leadership=67, strength=63, intel=61, birth=206, death=260)], "general_ex": []}

        enriched, audit = b.enrich_scenario(scenario, rtk)

        row = enriched["general"][0]
        self.assertEqual([67, 63, 61], row[5:8])
        self.assertEqual(
            [b.UNMATCHED_OVERRIDES["유약"]["politics"], b.UNMATCHED_OVERRIDES["유약"]["charm"]],
            row[14:16],
        )
        self.assertEqual(25, len(row))
        self.assertIsNone(row[17])
        self.assertIsNone(row[23])
        self.assertTrue(row[24])
        self.assertEqual([], audit["existingSourceIdDuplicates"])

    def test_injected_unmatched_overrides_do_not_read_the_module_registry(self):
        rtk = b._source_rows_to_rtk(source_rows())
        scenario = {
            "startYear": 220,
            "general": [legacy_tuple("검토전용", leadership=67, strength=63, intel=61, birth=206, death=260)],
            "general_ex": [],
        }
        overrides = {
            "검토전용": {"politics": 71, "charm": 44, "rationale": "Injected fixture override."},
        }

        enriched, audit = b.enrich_scenario(scenario, rtk, unmatched_overrides=overrides)

        self.assertEqual([71, 44], enriched["general"][0][14:16])
        self.assertEqual("workbook_missing", audit["legacyOnlyDetails"][0]["overrideKind"])

    def test_added_name_allocator_reuses_precomputed_base_names(self):
        names = {"동명1"}
        base_names = {"동명"}

        first, first_collision = b._allocate_added_name("동명", names, base_names)
        second, second_collision = b._allocate_added_name("동명", names, base_names)

        self.assertEqual("동명2", first)
        self.assertEqual("동명3", second)
        self.assertEqual("동명2", first_collision["assignedName"])
        self.assertEqual("동명3", second_collision["assignedName"])

    def test_manual_override_registry_covers_every_known_runtime_name_and_uses_workbook_range(self):
        expected_names = {
            "강경", "건석", "곽씨", "관로", "교국로", "교현", "길평", "낙준", "남두", "단경", "독발수기능", "루반", "반임",
            "변장", "부동", "북궁백옥", "사마준", "사마휘", "사의관", "송건", "아들노숙", "악신", "예형", "우길", "원사",
            "원혁", "유굉", "유변", "유약", "유총", "유협", "이리", "장량", "주한", "지양군", "진복", "진온", "허소", "헌제", "화타", "황승언", "휴고",
        }
        self.assertEqual(expected_names, set(b.UNMATCHED_OVERRIDES))
        self.assertTrue(all(
            type(value[stat]) is int and b.STAT_MIN <= value[stat] <= b.STAT_MAX and value[stat] != 50
            for value in b.UNMATCHED_OVERRIDES.values()
            for stat in ("politics", "charm")
        ))
        self.assertEqual((44, 68), (b.UNMATCHED_OVERRIDES["장량"]["politics"], b.UNMATCHED_OVERRIDES["장량"]["charm"]))

    def test_build_all_copies_settings_byte_for_byte_and_excludes_normalized_archives(self):
        rtk = b._source_rows_to_rtk(source_rows())
        with TemporaryDirectory() as td:
            source_dir = Path(td) / "source"
            out_dir = Path(td) / "out"
            (source_dir / "nested").mkdir(parents=True)
            (source_dir / "scenario_1000.json").write_text(
                json.dumps({"startYear": 180, "general": [legacy_tuple("장수1", 31, 32, 33, 101, 169)], "general_ex": []}, ensure_ascii=False),
                encoding="utf-8",
            )
            (source_dir / "scenario_3000.json").write_bytes(b'{"const":{"year":100}}\n')
            (source_dir / "nested" / "scenario_2000.json").write_text(
                '{"generals":[{"name":"archive"}]}', encoding="utf-8"
            )

            report = b.build_all(source_dir, out_dir, rtk)

            self.assertEqual(3, report["totals"]["files"])
            self.assertEqual(1, report["totals"]["updatedFiles"])
            self.assertEqual(1, report["totals"]["untouchedFiles"])
            self.assertEqual(1, report["totals"]["excludedFiles"])
            per_file = {item["file"]: item for item in report["files"]}
            self.assertEqual(1000, per_file["scenario_1000.json"]["representedSourceRows"])
            self.assertEqual([], per_file["scenario_1000.json"]["missingSourceIds"])
            self.assertEqual(0, per_file["scenario_1000.json"]["addedGeneralNeutral"])
            self.assertEqual(1000, per_file["scenario_1000.json"]["finalRosterRows"])
            self.assertEqual("excluded_non_runtime_schema", per_file["nested/scenario_2000.json"]["status"])
            self.assertEqual("untouched_no_applicable_tuples", per_file["scenario_3000.json"]["status"])
            self.assertEqual(
                (source_dir / "scenario_3000.json").read_bytes(),
                (out_dir / "scenario_3000.json").read_bytes(),
            )
            self.assertFalse((out_dir / "nested" / "scenario_2000.json").exists())

    def test_build_all_dry_run_does_not_create_scenario_outputs(self):
        rtk = b._source_rows_to_rtk(source_rows())
        with TemporaryDirectory() as td:
            source_dir = Path(td) / "source"
            out_dir = Path(td) / "out"
            source_dir.mkdir()
            (source_dir / "scenario_1000.json").write_text(
                json.dumps({"startYear": 180, "general": [legacy_tuple("장수1", 31, 32, 33, 101, 169)], "general_ex": []}, ensure_ascii=False),
                encoding="utf-8",
            )
            (source_dir / "scenario_3000.json").write_text('{"const":{"year":100}}', encoding="utf-8")

            report = b.build_all(source_dir, out_dir, rtk, dry_run=True)

            self.assertFalse(out_dir.exists())
            self.assertEqual(1000, report["sourceRows"])
            self.assertEqual([], report["unresolvedMissingNames"])
            self.assertEqual(1000, next(item for item in report["files"] if item["file"] == "scenario_1000.json")["representedSourceRows"])

    def test_build_one_accepts_a_path_without_a_scenario_root(self):
        rtk = b._source_rows_to_rtk(source_rows())
        with TemporaryDirectory() as td:
            source = Path(td) / "scenario_1000.json"
            out_dir = Path(td) / "out"
            source.write_text(
                json.dumps({"startYear": 180, "general": [legacy_tuple("장수1", 31, 32, 33, 101, 169)], "general_ex": []}, ensure_ascii=False),
                encoding="utf-8",
            )

            output, _, _, _, detail = b.build_one(source, out_dir, rtk, dry_run=True)

        self.assertEqual(str(out_dir / "scenario_1000.json"), output)
        self.assertEqual("dry_run_would_update", detail["status"])

    def test_rtk_source_json_requires_full_valid_contract_rows(self):
        with TemporaryDirectory() as td:
            source = Path(td) / "rtk-source.json"
            source.write_text(json.dumps({"rows": source_rows()}, ensure_ascii=False), encoding="utf-8")

            rtk = b.read_rtk14_source_json(source)

            self.assertEqual(1000, len(b.source_rows(rtk)))
            self.assertEqual(source_rows(), b.rtk_to_source_rows(rtk))

    @unittest.skipUnless(
        REAL_WORKBOOK is not None and REAL_WORKBOOK.is_file(),
        "RTK14_WORKBOOK_PATH is not set to a readable private workbook",
    )
    def test_real_runtime_scenarios_have_reviewed_overrides_for_every_legacy_only_row(self):
        rtk = b.read_rtk14(REAL_WORKBOOK)
        scenario_dir = Path(__file__).resolve().parents[2] / "infra" / "src" / "main" / "resources" / "scenario"

        with TemporaryDirectory() as td:
            report = b.build_all(scenario_dir, Path(td) / "out", rtk, dry_run=True)

        self.assertEqual(1000, report["sourceRows"])
        self.assertEqual(30, report["totals"]["files"])
        self.assertEqual(15, report["totals"]["updatedFiles"])
        self.assertEqual(0, report["totals"]["excludedFiles"])
        self.assertEqual(15, report["totals"]["untouchedFiles"])
        self.assertEqual([], report["unresolvedMissingNames"])
        self.assertEqual(38, report["totals"]["collision"])
        self.assertEqual(report["totals"]["collision"], report["totals"]["collisionOverride"])
        updated = [detail for detail in report["files"] if detail["status"] == "dry_run_would_update"]
        self.assertEqual(15, len(updated))
        for detail in updated:
            self.assertEqual(1000, detail["representedSourceRows"])
            self.assertEqual([], detail["missingSourceIds"])
            self.assertEqual([], detail["unreviewedLegacyOnlyRows"])
            self.assertEqual(detail["legacyOnlyRows"], len(detail["legacyOnlyDetails"]))
            for row in detail["legacyOnlyDetails"]:
                self.assertTrue(row["reviewedOverride"])
                self.assertIn(row["overrideKind"], ("workbook_missing", "collision"))
                self.assertIsInstance(row["politics"], int)
                self.assertIsInstance(row["charm"], int)
                self.assertTrue(row["rationale"].strip())


if __name__ == "__main__":
    unittest.main()
