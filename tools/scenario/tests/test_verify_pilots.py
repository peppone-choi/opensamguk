import copy
import sys
import unittest
from pathlib import Path


SCENARIO_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCENARIO_DIR))

from verify_pilots import verify


LORD_IDS = [
    10001,
    10002,
    10071,
    10072,
    10073,
    10074,
    10075,
    10076,
    10077,
    10078,
    10234,
    10235,
    10236,
    10237,
    10405,
    10682,
    10732,
    10733,
    10734,
    10735,
    10736,
]

REPRESENTATIVES = {
    10071: ("유비", "君主", "劉備", "平原", "평원", 3, 12),
    10146: ("여포", "太守", "董卓", "虎牢関", "사수", 16, 4),
    10174: ("주유", "一般", "孫堅", "長沙", "장사", 11, 0),
    10405: ("조조", "君主", "曹操", "陳留", "진류", 15, 12),
    10732: ("원소", "君主", "袁紹", "南皮", "남피", 17, 12),
}


def general(
    officer_id: int,
    name: str,
    nation_id: int,
    city: str,
    level: int,
) -> list[object]:
    return [
        0,
        name,
        officer_id,
        nation_id,
        city,
        70,
        70,
        70,
        level,
        100,
        200,
        None,
        None,
        None,
        70,
        70,
    ]


def representative_rows() -> list[dict]:
    return [
        {
            "id": 10071,
            "name": "유비",
            "source_status": "君主",
            "source_faction": "劉備",
            "source_location": "平原",
            "mapped_city": "평원",
            "runtime_nation_id": 3,
            "officer_level": 12,
            "emitted": True,
        },
        {
            "id": 10146,
            "name": "여포",
            "source_status": "太守",
            "source_faction": "董卓",
            "source_location": "虎牢関",
            "mapped_city": "사수",
            "runtime_nation_id": 16,
            "officer_level": 4,
            "emitted": True,
        },
        {
            "id": 10174,
            "name": "주유",
            "source_status": "一般",
            "source_faction": "孫堅",
            "source_location": "長沙",
            "mapped_city": "장사",
            "runtime_nation_id": 11,
            "officer_level": 0,
            "emitted": True,
        },
        {
            "id": 10246,
            "name": "손권",
            "source_status": "未登場",
            "source_faction": None,
            "source_location": "呉",
            "mapped_city": "오",
            "runtime_nation_id": None,
            "officer_level": None,
            "emitted": False,
        },
        {
            "id": 10405,
            "name": "조조",
            "source_status": "君主",
            "source_faction": "曹操",
            "source_location": "陳留",
            "mapped_city": "진류",
            "runtime_nation_id": 15,
            "officer_level": 12,
            "emitted": True,
        },
        {
            "id": 10732,
            "name": "원소",
            "source_status": "君主",
            "source_faction": "袁紹",
            "source_location": "南皮",
            "mapped_city": "남피",
            "runtime_nation_id": 17,
            "officer_level": 12,
            "emitted": True,
        },
    ]


def fixture() -> tuple[dict, dict, list[dict], dict, set[str]]:
    nation_by_lord = {lord_id: index for index, lord_id in enumerate(LORD_IDS, start=1)}
    output_ids = set(LORD_IDS) | {10146, 10174}
    for officer_id in range(10001, 11001):
        if officer_id != 10246 and officer_id not in output_ids:
            output_ids.add(officer_id)
        if len(output_ids) == 249:
            break

    tuples: list[list[object]] = []
    refined: list[dict] = []
    for officer_id in sorted(output_ids):
        if officer_id in REPRESENTATIVES:
            name, status, faction, location, city, nation_id, level = REPRESENTATIVES[officer_id]
        elif officer_id in nation_by_lord:
            nation_id = nation_by_lord[officer_id]
            name = f"군주{officer_id}"
            status = "君主"
            faction = f"세력{officer_id}"
            location = f"도시{nation_id}"
            city = location
            level = 12
        else:
            nation_id = 1
            name = f"장수{officer_id}"
            status = "一般"
            faction = "세력10001"
            location = "도시1"
            city = location
            level = 0
        tuples.append(general(officer_id, name, nation_id, city, level))
        refined.append({
            "id": officer_id,
            "name_kanji": faction if status == "君主" else f"원문{officer_id}",
            "name_korean": name,
            "scenarios": [{
                "year_month": "190.1",
                "status": status,
                "faction": faction,
                "location": location,
            }],
        })

    refined.append({
        "id": 10246,
        "name_kanji": "孫権",
        "name_korean": "손권",
        "scenarios": [{
            "year_month": "190.1",
            "status": "未登場",
            "faction": None,
            "location": "呉",
        }],
    })

    nations = [
        [f"국가{index}", "#8B0000", 6000, 6000, "", 550, "중립", 3, [f"도시{index}"]]
        for index in range(1, 22)
    ]
    scenario = {
        "title": "검증 시나리오",
        "startYear": 190,
        "life": 1,
        "fiction": 0,
        "map": {"mapName": "han-world-v2", "unitSet": "han"},
        "seedContract": {"activeGenerals": {"base": 249, "extended": 249}},
        "const": {"defaultMaxGeneral": 600},
        "stored_icons": {".": {str(row[2]): f"{row[2]}.png" for row in tuples}},
        "nation": nations,
        "general": tuples,
        "general_ex": [],
        "general_neutral": [],
        "diplomacy": [],
    }
    report = {
        "affiliated_count": 249,
        "neutral_count": 0,
        "importer_eligible_total": 249,
        "importer_lifecycle": {
            "roster_total": 249,
            "active_at_start": 249,
            "deferred_underage": 0,
            "dead_at_start": 0,
        },
        "seed_readiness": {
            "importer_ruler_gap_nation_ids": [],
            "seed_ready": True,
            "reason": None,
        },
        "unresolved_locations": [],
        "korean_name_fallbacks": [],
        "city_relocations": [],
        "city_overlap_count": 0,
        "representatives": representative_rows(),
        "officers": [
            {
                "id": row[2],
                "kind": "affiliated",
                "nation_id": row[3],
                "city": row[4],
                "officer_level": row[8],
                "picture_id": row[2],
            }
            for row in tuples
        ],
    }
    manifest = {"code": "scenario_3190", "year_month": "190.1"}
    che_cities = {f"도시{index}" for index in range(1, 22)} | {"진류", "남피", "평원", "사수", "장사"}
    return scenario, report, refined, manifest, che_cities


class VerifyPilotsTest(unittest.TestCase):
    def test_baseline_fixture_passes_all_five_gates(self) -> None:
        self.assertEqual(verify(*fixture()), [])

    def test_exact_active_count_gate_rejects_missing_affiliated_general(self) -> None:
        scenario, report, refined, manifest, che_cities = fixture()
        scenario["general"].pop()
        scenario["stored_icons"]["."].popitem()
        scenario["seedContract"]["activeGenerals"] = {"base": 248, "extended": 248}
        report["officers"].pop()

        errors = verify(scenario, report, refined, manifest, che_cities)

        self.assertTrue(any(error.startswith("affiliated count:") for error in errors), errors)

    def test_death_after_start_remains_importer_eligible(self) -> None:
        scenario, report, refined, manifest, che_cities = fixture()
        scenario["general"][0][10] = scenario["startYear"] + 1

        self.assertEqual(verify(scenario, report, refined, manifest, che_cities), [])

    def test_adult_age_at_start_remains_importer_eligible(self) -> None:
        scenario, report, refined, manifest, che_cities = fixture()
        scenario["general"][0][9] = scenario["startYear"] - 14

        self.assertEqual(verify(scenario, report, refined, manifest, che_cities), [])

    def test_death_at_start_remains_emitted_but_reduces_importer_eligible_total(self) -> None:
        scenario, report, refined, manifest, che_cities = fixture()
        row = scenario["general"][0]
        officer_id = row[2]
        row[10] = scenario["startYear"]
        scenario["seedContract"]["activeGenerals"] = {"base": 248, "extended": 248}
        report["importer_eligible_total"] = len(scenario["general"]) - 1
        report["importer_lifecycle"] = {
            "roster_total": len(scenario["general"]),
            "active_at_start": len(scenario["general"]) - 1,
            "deferred_underage": 0,
            "dead_at_start": 1,
        }
        report["seed_readiness"] = {
            "importer_ruler_gap_nation_ids": [row[3]],
            "seed_ready": False,
            "reason": "pending v2 PHP postBuild promotion parity",
        }

        self.assertIn(row, scenario["general"])
        self.assertTrue(any(officer["id"] == officer_id for officer in report["officers"]))
        self.assertEqual(verify(scenario, report, refined, manifest, che_cities), [])

    def test_underage_general_remains_emitted_but_reduces_importer_eligible_total(self) -> None:
        scenario, report, refined, manifest, che_cities = fixture()
        row = scenario["general"][0]
        officer_id = row[2]
        row[9] = scenario["startYear"] - 13
        scenario["seedContract"]["activeGenerals"] = {"base": 248, "extended": 248}
        report["importer_eligible_total"] = len(scenario["general"]) - 1
        report["importer_lifecycle"] = {
            "roster_total": len(scenario["general"]),
            "active_at_start": len(scenario["general"]) - 1,
            "deferred_underage": 1,
            "dead_at_start": 0,
        }
        report["seed_readiness"] = {
            "importer_ruler_gap_nation_ids": [row[3]],
            "seed_ready": False,
            "reason": "pending v2 PHP postBuild promotion parity",
        }

        self.assertIn(row, scenario["general"])
        self.assertTrue(any(officer["id"] == officer_id for officer in report["officers"]))
        self.assertEqual(verify(scenario, report, refined, manifest, che_cities), [])

    def test_stale_importer_eligible_total_is_rejected(self) -> None:
        scenario, report, refined, manifest, che_cities = fixture()
        row = scenario["general"][0]
        row[10] = scenario["startYear"]
        scenario["seedContract"]["activeGenerals"] = {"base": 248, "extended": 248}
        report["importer_lifecycle"] = {
            "roster_total": len(scenario["general"]),
            "active_at_start": len(scenario["general"]) - 1,
            "deferred_underage": 0,
            "dead_at_start": 1,
        }
        report["seed_readiness"] = {
            "importer_ruler_gap_nation_ids": [row[3]],
            "seed_ready": False,
            "reason": "pending v2 PHP postBuild promotion parity",
        }

        errors = verify(scenario, report, refined, manifest, che_cities)

        self.assertTrue(any(error.startswith("report importer_eligible_total:") for error in errors), errors)

    def test_seed_readiness_gate_rejects_unreported_effective_ruler_gap(self) -> None:
        scenario, report, refined, manifest, che_cities = fixture()
        row = scenario["general"][0]
        row[10] = scenario["startYear"]
        scenario["seedContract"]["activeGenerals"] = {"base": 248, "extended": 248}
        report["importer_eligible_total"] = len(scenario["general"]) - 1
        report["importer_lifecycle"] = {
            "roster_total": len(scenario["general"]),
            "active_at_start": len(scenario["general"]) - 1,
            "deferred_underage": 0,
            "dead_at_start": 1,
        }

        errors = verify(scenario, report, refined, manifest, che_cities)

        self.assertTrue(any(error.startswith("report seed_readiness:") for error in errors), errors)

    def test_importer_lifecycle_gate_rejects_stale_boundary_breakdown(self) -> None:
        scenario, report, refined, manifest, che_cities = fixture()
        row = scenario["general"][0]
        row[10] = scenario["startYear"]
        scenario["seedContract"]["activeGenerals"] = {"base": 248, "extended": 248}
        report["importer_eligible_total"] = len(scenario["general"]) - 1
        report["seed_readiness"] = {
            "importer_ruler_gap_nation_ids": [row[3]],
            "seed_ready": False,
            "reason": "pending v2 PHP postBuild promotion parity",
        }

        errors = verify(scenario, report, refined, manifest, che_cities)

        self.assertTrue(any(error.startswith("report importer_lifecycle:") for error in errors), errors)

    def test_representative_gate_rejects_stable_id_city_mismatch(self) -> None:
        scenario, report, refined, manifest, che_cities = fixture()
        row = next(row for row in scenario["general"] if row[2] == 10405)
        row[4] = "도시1"
        report_row = next(row for row in report["officers"] if row["id"] == 10405)
        report_row["city"] = "도시1"

        errors = verify(scenario, report, refined, manifest, che_cities)

        self.assertTrue(any(error.startswith("representative 10405:") for error in errors), errors)

    def test_representative_report_gate_rejects_stale_evidence(self) -> None:
        scenario, report, refined, manifest, che_cities = fixture()
        representative = next(row for row in report["representatives"] if row["id"] == 10405)
        representative["mapped_city"] = "도시1"

        errors = verify(scenario, report, refined, manifest, che_cities)

        self.assertTrue(any(error.startswith("report representatives:") for error in errors), errors)

    def test_representative_report_gate_rejects_malformed_evidence(self) -> None:
        scenario, report, refined, manifest, che_cities = fixture()
        report["representatives"][0] = {"id": 10071}

        errors = verify(scenario, report, refined, manifest, che_cities)

        self.assertTrue(any(error.startswith("report representatives:") for error in errors), errors)

    def test_unresolved_location_gate_rejects_nonempty_report(self) -> None:
        scenario, report, refined, manifest, che_cities = fixture()
        report["unresolved_locations"] = ["알수없음"]

        errors = verify(scenario, report, refined, manifest, che_cities)

        self.assertTrue(any(error.startswith("unresolved locations:") for error in errors), errors)

    def test_korean_fallback_gate_rejects_nonempty_report(self) -> None:
        scenario, report, refined, manifest, che_cities = fixture()
        report["korean_name_fallbacks"] = [{"id": 10001, "name": "fallback"}]

        errors = verify(scenario, report, refined, manifest, che_cities)

        self.assertTrue(any(error.startswith("Korean name fallbacks:") for error in errors), errors)

    def test_tuple_schema_gate_rejects_wrong_general_tuple_length(self) -> None:
        scenario, report, refined, manifest, che_cities = fixture()
        scenario["general"][0] = scenario["general"][0][:-1]

        errors = verify(scenario, report, refined, manifest, che_cities)

        self.assertTrue(any(error.startswith("scenario schema:") for error in errors), errors)


if __name__ == "__main__":
    unittest.main()
