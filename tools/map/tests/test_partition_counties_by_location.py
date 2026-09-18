"""partition_counties_by_location: 합성 격자에서 규칙 0–7 과 불변식, 그리고 불변식마다 적색 프로브 (GH #806 S1).

실데이터 테스트는 하나뿐이다(peel + 전체 분할 ≈ 15–20 s). 커밋본을 읽기만 하고 아무것도 쓰지 않는다.
"""
import copy
import json
import random
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import numpy as np

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT))

from tools.map import partition_counties_by_location as pcl  # noqa: E402
from tools.map.measure_province_seat_offset import gate, measure  # noqa: E402

PROJECTION = {"cell": 1.0, "k": 1.0, "x0": 0.0, "pad": 0.0, "y1": 100.0}


def lonlat(row, col):
    """칸 (row, col) 한가운데로 투영되는 경위도."""
    return col + 0.5, PROJECTION["y1"] - row - 0.5


def make(parent_rows, counties, stand_ins=()):
    """parent_rows: 郡 색인 격자(-1 = 물). counties: [(id, 郡, (row, col) 실제 칸)]. 입력 owner 는 일부러
    지리와 무관하게 채운다(郡의 모든 칸을 그 郡 첫 縣에) — 분할기가 옛 owner 에 기대지 않는 것을 같이 본다."""
    parent = np.asarray(parent_rows, dtype=np.int32)
    rows, cols = parent.shape
    cities, provinces, jurisdictions = [], [], []
    first_of_parent = {}
    for cid, k, (r, c) in counties:
        lon, lat = lonlat(r, c)
        cities.append({"id": cid, "name": cid, "nameCh": cid, "kind": "COUNTY", "seat": False,
                       "col": 0, "row": 0, "lon": lon, "lat": lat})
        provinces.append({"id": cid, "displayName": cid, "nameCh": cid, "administrativeSystem": "HAN_COMMANDERY",
                          "kind": "SPATIAL_PROVINCE", "parentRegionId": f"PARENT-{k:04d}",
                          "cityIndex": len(cities) - 1, "geometryBasis": "HISTORICAL_SEAT_ADAPTED",
                          "jurisdictionId": cid, "assignmentBasis": "HISTORICAL_SEAT"})
        jurisdictions.append({"id": cid, "displayName": cid, "nameCh": cid, "kind": "COUNTY",
                              "commanderyId": f"PARENT-{k:04d}", "seatPlaceId": cid, "provinceIds": [cid]})
        first_of_parent.setdefault(k, len(provinces) - 1)
    for jid, k, city_id, (r, c) in stand_ins:
        cities.append({"id": city_id, "name": city_id, "nameCh": city_id, "kind": "COMMANDERY", "seat": True,
                       "col": c, "row": r, "lon": 0.0, "lat": 0.0})  # lon/lat 은 쓰이면 안 된다
        pid = f"DIRECT-PARENT-{k:04d}-old"
        provinces.append({"id": pid, "displayName": jid, "nameCh": jid, "administrativeSystem": "HAN_COMMANDERY",
                          "kind": "SPATIAL_PROVINCE", "parentRegionId": f"PARENT-{k:04d}", "cityIndex": None,
                          "geometryBasis": "BALANCED_PARENT_PARTITION", "jurisdictionId": jid,
                          "assignmentBasis": "REVIEWED_PARENT_SEAT_RECOVERY"})
        jurisdictions.append({"id": jid, "displayName": jid, "nameCh": jid, "kind": "COUNTY",
                              "commanderyId": f"PARENT-{k:04d}", "seatPlaceId": city_id, "provinceIds": [pid]})
        first_of_parent.setdefault(k, len(provinces) - 1)
    owner = np.full(parent.shape, -1, dtype=np.int32)
    for k, index in first_of_parent.items():
        owner[parent == k] = index
    return {
        "_meta": {"rows": rows, "cols": cols, "projection": PROJECTION, "counts": {},
                  "terrainLegend": {"1": "PLAIN"}},
        "terrain": ["1" * cols] * rows,
        "owner": pcl.encode(owner), "parentOwner": pcl.encode(parent),
        "cities": cities, "provinceRecords": provinces, "jurisdictionRecords": jurisdictions,
        "parentRegions": [{"id": f"PARENT-{k:04d}"} for k in range(int(parent.max()) + 1)],
        "commanderyRecords": [{"id": f"PARENT-{k:04d}",
                               "seatJurisdictionId": min(j["id"] for j in jurisdictions
                                                         if j["commanderyId"] == f"PARENT-{k:04d}"),
                               "jurisdictionIds": sorted(j["id"] for j in jurisdictions
                                                         if j["commanderyId"] == f"PARENT-{k:04d}")}
                              for k in range(int(parent.max()) + 1)],
        "adjacency": {"county": [], "commandery": []},
    }


def owner_ids(document):
    meta = document["_meta"]
    grid = pcl.expand(document["owner"], meta["rows"], meta["cols"])
    ids = [row["id"] for row in document["provinceRecords"]]
    return [[ids[v] if v >= 0 else None for v in line] for line in grid.tolist()]


def shuffled(document, seed):
    """cities[] 와 jurisdictionRecords[] 를 뒤섞는다(cityIndex 는 따라간다)."""
    out = copy.deepcopy(document)
    rng = random.Random(seed)
    order = list(range(len(out["cities"])))
    rng.shuffle(order)
    out["cities"] = [out["cities"][i] for i in order]
    new_index = {old: new for new, old in enumerate(order)}
    for row in out["provinceRecords"]:
        if row.get("cityIndex") is not None:
            row["cityIndex"] = new_index[row["cityIndex"]]
    rng.shuffle(out["jurisdictionRecords"])
    return out


class RuleTest(unittest.TestCase):
    def test_cells_go_to_the_nearest_true_location_not_the_old_owner(self):
        source = make([[0] * 9] * 3, [("A", 0, (1, 1)), ("B", 0, (1, 7))])
        document, report = pcl.partition(source, min_area=1)
        grid = owner_ids(document)
        self.assertEqual(grid[1][:4], ["A"] * 4)
        self.assertEqual(grid[1][5:], ["B"] * 4)
        self.assertEqual(grid[1][4], "A")  # 동률 칸은 id 사전순
        city = {row["id"]: (row["row"], row["col"]) for row in document["cities"]}
        self.assertEqual(city, {"A": (1, 1), "B": (1, 7)})  # 규칙 7: 씨앗 = 실제 칸
        self.assertEqual(report["seedExceptions"], [])
        self.assertEqual(gate(measure(document)), {"Q1": [], "Q1b": []})

    def test_input_with_displaced_owner_is_q1_red_before_and_green_after(self):
        source = make([[0] * 9] * 3, [("A", 0, (1, 1)), ("B", 0, (1, 7))])
        self.assertEqual([r["id"] for r in gate(measure(source))["Q1"]], ["B"])  # 적색: B 의 땅이 없다
        document, _ = pcl.partition(source, min_area=1)
        self.assertEqual(gate(measure(document))["Q1"], [])

    def test_diagonal_costs_14_not_20(self):
        # (0,0) 에서 (3,3): 8-이웃 42, 4-이웃이면 60. (3,6)→(3,3) 은 30. 8-이웃이어야 A 가 (3,3) 을 못 갖고,
        # (2,2)(28) 는 A, 4-이웃(40 vs B 50)과 구별되는 칸은 (3,2): A 8-이웃 38 < B 40, 4-이웃이면 50 > 40.
        source = make([[0] * 7] * 4, [("A", 0, (0, 0)), ("B", 0, (3, 6))])
        document, _ = pcl.partition(source, min_area=1)
        self.assertEqual(owner_ids(document)[3][2], "A")

    def test_distance_is_a_path_inside_the_mask_not_euclid(self):
        # A 와 (0,2) 사이가 물이다. 유클리드로는 A 가 가깝지만 걸어가면 B 가 가깝다.
        parent = [[0, -1, 0, 0, 0],
                  [0, -1, -1, -1, 0],
                  [0, 0, 0, 0, 0]]
        source = make(parent, [("A", 0, (0, 0)), ("B", 0, (0, 4))])
        document, _ = pcl.partition(source, min_area=1)
        self.assertEqual(owner_ids(document)[0][2], "B")

    def test_commandery_border_is_never_crossed(self):
        parent = [[0, 0, 0, 1, 1, 1, 1, 1, 1]] * 3
        source = make(parent, [("A", 0, (1, 0)), ("B", 1, (1, 8))])
        document, _ = pcl.partition(source, min_area=1)
        self.assertEqual(owner_ids(document)[1], ["A"] * 3 + ["B"] * 6)
        self.assertEqual(pcl.check_parent_unchanged(source, document), [])

    def test_seed_outside_own_commandery_moves_to_nearest_own_cell_with_a_ledger_row(self):
        parent = [[0, 0, 0, 1, 1, 1]] * 2
        source = make(parent, [("A", 0, (0, 4)), ("B", 1, (1, 5))])  # A 의 실제 칸은 郡 1 땅이다
        document, report = pcl.partition(source, min_area=1)
        [row] = report["seedExceptions"]
        self.assertEqual((row["jurisdictionId"], row["class"]), ("A", "SOURCE_PARENT_NOT_RASTER_PARENT"))
        self.assertEqual(row["seedCell"], {"col": 2, "row": 0})
        self.assertEqual(row["cellDistance"], 2.0)
        failures = gate(measure(document))
        self.assertEqual([r["id"] for r in failures["Q1"]], ["A"])  # 예외 없이는 Q1 적색
        self.assertEqual(gate(measure(document), frozenset({"A"}))["Q1"], [])

    def test_seed_in_water_is_classified(self):
        parent = [[0, 0, -1, -1]]
        source = make(parent, [("A", 0, (0, 3))])
        _, report = pcl.partition(source, min_area=1)
        self.assertEqual(report["seedExceptions"][0]["class"], "WATER_OR_OFF_GRID")

    def test_seed_collision_is_never_adjudicated_automatically(self):
        source = make([[0] * 5] * 3, [("B", 0, (1, 2)), ("A", 0, (1, 2))])
        document, report = pcl.partition(source, min_area=1)
        [row] = report["seedExceptions"]
        self.assertEqual((row["jurisdictionId"], row["class"]), ("B", "SEED_COLLISION"))
        self.assertNotIn("ruling", row)  # 판정은 county-seed-collisions-v1 에만 산다 — 분할기는 짓지 않는다
        self.assertEqual(row["sharedWith"], ["A"])
        self.assertEqual(row["seedCell"], {"col": 2, "row": 0})  # 가장 가까운 빈 칸, 동률은 (row, col)
        self.assertEqual(pcl.check_cover(source, document, report), [])

    def test_stand_in_seat_keeps_its_point_and_its_direct_prefix(self):
        parent = [[0] * 4 + [1] * 6] * 4
        source = make(parent, [("A", 0, (0, 0))], stand_ins=[("JURISDICTION-PARENT-0001-SEAT", 1, "X1", (2, 7))])
        document, report = pcl.partition(source, min_area=1, max_area=10)
        ids = [row["id"] for row in document["provinceRecords"]]
        direct = [i for i in ids if i.startswith("DIRECT-PARENT-0001-")]
        self.assertEqual(len(direct), 3)  # 24칸 / 10 → 3조각, 전부 DIRECT-
        self.assertFalse([i for i in ids if i.startswith("SUB-JURISDICTION")])
        self.assertEqual(report["seedExceptions"], [])
        stand_in_city = next(row for row in document["cities"] if row["id"] == "X1")
        self.assertEqual((stand_in_city["row"], stand_in_city["col"]), (2, 7))

    def test_oversized_county_is_subdivided_inside_itself(self):
        source = make([[0] * 12] * 4, [("A", 0, (1, 1))])
        document, report = pcl.partition(source, min_area=1, max_area=20)
        self.assertEqual(report["subdivisions"], [{"jurisdictionId": "A", "nameCh": "A", "componentCells": 48,
                                                   "pieces": 3}])
        [juris] = document["jurisdictionRecords"]
        self.assertEqual(juris["provinceIds"], sorted(juris["provinceIds"]))
        self.assertEqual([pid for pid in juris["provinceIds"] if not pid.startswith("SUB-A-")], ["A"])
        self.assertEqual(owner_ids(document)[1][1], "A")  # 城이 든 조각이 seat 省
        self.assertEqual(pcl.check_area(document, min_area=1, max_area=20), [])
        sub = document["provinceRecords"][-1]
        self.assertEqual((sub["cityIndex"], sub["assignmentBasis"], sub["jurisdictionId"]),
                         (None, "WITHIN_COUNTY_SUBDIVISION", "A"))

    def test_small_county_borrows_nearest_cells_and_records_them(self):
        # B 는 A·C 사이에 끼어 1열뿐이다.
        source = make([[0] * 9] * 2, [("A", 0, (0, 3)), ("B", 0, (0, 4)), ("C", 0, (0, 5))])
        document, report = pcl.partition(source, min_area=4)
        [row] = report["minAreaBorrowed"]
        self.assertEqual((row["jurisdictionId"], row["satisfied"], row["areaAfter"]), ("B", True, 4))
        self.assertEqual(sum(row["donors"].values()), row["borrowedCells"])
        self.assertEqual(pcl.check_cover(source, document, report), [])
        self.assertEqual(gate(measure(document))["Q1"], [])  # 빌려도 남의 씨앗 칸은 안 가져간다

    def test_seedless_component_goes_to_nearest_county_and_is_ledgered(self):
        parent = [[0, 0, 0, -1, 0, 0, 0, 0, 0, 0, 0, 0]] * 2  # 오른쪽 16칸에는 씨앗이 없다
        source = make(parent, [("A", 0, (0, 0)), ("B", 0, (1, 2))])
        document, report = pcl.partition(source, min_area=2)
        [row] = report["components"]
        self.assertEqual((row["jurisdictionId"], row["cells"], row["reason"], row["disposition"]),
                         ("B", 16, "WATER_SEPARATED", "OWN_PROVINCE"))
        self.assertEqual(row["reasonBasis"], "MECHANICAL_UNREVIEWED")
        self.assertEqual(pcl.check_cover(source, document, report), [])


class InvariantRedProbeTest(unittest.TestCase):
    def setUp(self):
        parent = [[0] * 6 + [1] * 6] * 5
        self.source = make(parent, [("A", 0, (1, 1)), ("B", 0, (3, 4)), ("C", 1, (2, 9)), ("D", 1, (4, 11))])
        self.document, self.report = pcl.partition(self.source, min_area=2)

    def grid(self, document=None):
        meta = self.document["_meta"]
        return pcl.expand((document or self.document)["owner"], meta["rows"], meta["cols"])

    def test_green(self):
        self.assertEqual(pcl.check_parent_unchanged(self.source, self.document), [])
        self.assertEqual(pcl.check_cover(self.source, self.document, self.report), [])
        self.assertEqual(pcl.check_area(self.document, min_area=2, max_area=620), [])

    def test_output_satisfies_the_existing_hierarchy_contract(self):
        # 1차 건식 실행에서 부모 재조정 도구가 여기서 죽었다(provinceIds 가 정렬이 아니었다).
        from tools.map.world_province_geometry import validate_materialized_hierarchy
        document, _ = pcl.partition(self.source, min_area=1, max_area=6)
        validate_materialized_hierarchy(document["provinceRecords"], document["jurisdictionRecords"],
                                        self.source["commanderyRecords"])
        broken = copy.deepcopy(document["jurisdictionRecords"])
        broken[0]["provinceIds"] = list(reversed(broken[0]["provinceIds"]))
        self.assertGreater(len(broken[0]["provinceIds"]), 1)
        with self.assertRaises(ValueError):
            validate_materialized_hierarchy(document["provinceRecords"], broken, self.source["commanderyRecords"])

    def test_red_probe_q2_one_parent_cell_tampered(self):
        parent = self.grid() * 0 + pcl.expand(self.document["parentOwner"], 5, 12)
        parent[0, 5] = 1
        tampered = {**self.document, "parentOwner": pcl.encode(parent)}
        self.assertEqual(pcl.check_parent_unchanged(self.source, tampered), ["Q2 parentOwner changed"])

    def test_red_probe_q3_orphan_land(self):
        owner = self.grid()
        owner[0, 0] = -1
        problems = pcl.check_cover(self.source, {**self.document, "owner": pcl.encode(owner)}, self.report)
        self.assertIn("Q3 orphan land cells: 1", problems)

    def test_red_probe_q3_detached_component(self):
        owner = self.grid()
        a, b = int(owner[1, 1]), int(owner[3, 4])
        self.assertEqual(int(owner[0, 0]), a)
        far = np.argwhere(owner == b)[-1]
        owner[tuple(far)] = a  # A 의 한 칸을 B 땅 한가운데 떼어 놓는다
        problems = pcl.check_cover(self.source, {**self.document, "owner": pcl.encode(owner)}, self.report)
        self.assertTrue(any(p.startswith("Q3 province A has 2 components") for p in problems), problems)

    def test_red_probe_q3_exception_needs_a_ledger_row(self):
        parent = [[0, 0, 0, -1, 0]] * 2
        source = make(parent, [("A", 0, (0, 0))])
        document, report = pcl.partition(source, min_area=4)  # 오른쪽 2칸 < min_area → seat 省에 붙는 다성분
        self.assertEqual(report["multiComponentProvinceIds"], ["A"])
        self.assertEqual(pcl.check_cover(source, document, report), [])
        self.assertEqual(pcl.check_cover(source, document, {}), ["Q3 province A has 2 components"])

    def test_red_probe_q4_area(self):
        problems = pcl.check_area(self.document, min_area=2, max_area=10)
        self.assertTrue(problems and all(p.startswith("Q4 ") for p in problems))

    def test_red_probe_q1_seed_moved_ten_cells(self):
        wide = make([[0] * 30] * 3, [("A", 0, (1, 2)), ("B", 0, (1, 27))])
        document, _ = pcl.partition(wide, min_area=1)
        self.assertEqual(gate(measure(document))["Q1"], [])
        city = next(row for row in document["cities"] if row["id"] == "A")
        city["lon"] += 20  # 실제 위치가 20칸 동쪽이면 제 관할 밖이다
        self.assertEqual([r["id"] for r in gate(measure(document))["Q1"]], ["A"])


class DeterminismTest(unittest.TestCase):
    def setUp(self):
        # 동률이 많은 대칭 격자 — 순서 의존이 있으면 여기서 드러난다.
        self.source = make([[0] * 9] * 9, [("D", 0, (0, 0)), ("C", 0, (0, 8)), ("B", 0, (8, 0)), ("A", 0, (8, 8))])

    def test_two_runs_are_byte_identical(self):
        first, _ = pcl.partition(self.source, min_area=1, max_area=10)
        second, _ = pcl.partition(copy.deepcopy(self.source), min_area=1, max_area=10)
        self.assertEqual(pcl.dumps(first), pcl.dumps(second))

    def test_shuffled_cities_and_jurisdictions_give_the_same_geometry(self):
        expected = pcl.geometry_digest(pcl.partition(self.source, min_area=1, max_area=10)[0])
        for seed in range(5):
            document, _ = pcl.partition(shuffled(self.source, seed), min_area=1, max_area=10)
            self.assertEqual(pcl.geometry_digest(document), expected, f"shuffle seed {seed}")

    def test_red_probe_input_order_dependence_is_caught(self):
        expected = pcl.geometry_digest(pcl.partition(self.source, min_area=1)[0])
        with mock.patch.object(pcl, "_jurisdiction_order", lambda document: list(document["jurisdictionRecords"])):
            digests = {pcl.geometry_digest(pcl.partition(shuffled(self.source, seed), min_area=1)[0])
                       for seed in range(5)}
        self.assertTrue(digests - {expected}, "순서 의존을 주입했는데 지문이 그대로다 — 프로브가 이빨이 없다")


class ScratchOnlyTest(unittest.TestCase):
    def test_refuses_to_write_under_committed_roots_without_prepare(self):
        for path in (pcl.TILES, pcl.ROOT / "infra/src/main/resources/map/x.json"):
            with self.assertRaises(SystemExit):
                pcl._refuse_committed_path(path)
        with tempfile.TemporaryDirectory() as scratch:
            pcl._refuse_committed_path(Path(scratch) / "out.json")
        pcl._refuse_committed_path(pcl.ROOT / "build/province-partition/out.json")


class StrongholdReservationTest(unittest.TestCase):
    """사용자 결정 2026-09-18 ②: 거점 기증 縣의 최소 넓이 = min_area + 거점 수 × 최소 발자국."""

    def test_fill_minimum_uses_the_reserved_floor_and_never_breaks_a_neighbours_floor(self):
        source = make([[0] * 8] * 4, [("A", 0, (0, 0)), ("B", 0, (0, 1))])
        _, plain = pcl.partition(source, min_area=2)
        self.assertEqual(plain["minAreaBorrowed"], [])
        import numpy as np
        label = np.zeros((4, 8), dtype=np.int32)
        label[:, 1:] = 1
        order = sorted(source["jurisdictionRecords"], key=lambda row: row["id"])
        rows = pcl._fill_minimum(label, label >= 0, np.zeros((4, 8), dtype=np.int32), order,
                                 {"A": (0, 0), "B": (0, 1)}, 4, {0: 4})
        [row] = rows
        self.assertEqual((row["jurisdictionId"], row["floor"], row["areaAfter"], row["satisfied"]), ("A", 8, 8, True))
        # 적색 프로브: 이웃이 제 최소 넓이(예약 포함)에 닿아 있으면 빌리지 못하고 satisfied=False 로 남는다.
        label[:, :] = 1
        label[:, 0] = 0
        [row] = pcl._fill_minimum(label, label >= 0, np.zeros((4, 8), dtype=np.int32), order,
                                  {"A": (0, 0), "B": (0, 1)}, 4, {0: 4, 1: 24})
        self.assertEqual((row["borrowedCells"], row["satisfied"]), (0, False))


class CommittedStageTest(unittest.TestCase):
    """커밋된 ★ 단계(원장 + 입력 blob). 실데이터 분할 1회 ≈ 12 s 를 세 검사가 나눠 쓴다 — 임계가 아니라 실측 기준선."""

    @classmethod
    def setUpClass(cls):
        cls.committed_text = pcl.TILES.read_text(encoding="utf-8")
        cls.staged = pcl.peel_later_stages(json.loads(cls.committed_text))
        cls.ledger = json.loads(pcl.LEDGER.read_text(encoding="utf-8"))
        cls.problems = pcl.check(cls.staged, cls.ledger)

    def test_stage_check_is_green_and_the_chain_position_is_right(self):
        self.assertEqual(self.problems, [])
        source, ledger = pcl.peel(self.staged)
        self.assertIsNotNone(ledger)
        self.assertEqual(pcl.digest(source), self.ledger["geometry"]["stages"][0]["inputDocumentSha256"])
        self.assertEqual(pcl.check_parent_unchanged(source, self.staged), [])  # Q2
        self.assertEqual(pcl.TILES.read_text(encoding="utf-8"), self.committed_text)  # 검사는 커밋본을 안 건드린다

    def test_restore_keeps_key_order_for_the_earlier_stages(self):
        """조각 판정 단계는 cities 를 키 순서까지 지문으로 본다. blob 을 sort_keys 로 썼을 때 그 --check 가 죽었다."""
        source, _ = pcl.peel(self.staged)
        self.assertEqual(list(source["cities"][0]), list(self.staged["cities"][0]))

    def test_measured_baseline(self):
        stage = self.ledger["geometry"]["stages"][0]
        self.assertEqual(stage["counts"]["provinces"], 1260)
        self.assertEqual(len(self.ledger["seedExceptions"]), 40)
        rows = [r for r in measure(self.staged) if r.get("area")]
        exceptions = frozenset(row["jurisdictionId"] for row in self.ledger["seedExceptions"])
        self.assertEqual((len(gate(rows)["Q1"]), len(gate(rows)["Q1b"])), (40, 0))
        self.assertEqual(gate(rows, exceptions)["Q1"], [])  # 남은 40 은 전부 사유 행이 있다

    def test_red_probe_tampered_owner_cell_breaks_the_stage_pin(self):
        tampered = json.loads(json.dumps(self.staged))
        tampered["owner"][0][1] += 1
        tampered["owner"].insert(1, [tampered["owner"][0][0], -1])
        self.assertEqual(pcl.check(tampered, self.ledger),
                         ["han-tiles.json is not the reviewed county-location partition output"])

    def test_red_probe_q4_exception_rows_must_match_exactly(self):
        decisions = json.loads(pcl.DECISIONS.read_text(encoding="utf-8"))
        violations = self.ledger["areaViolations"]
        self.assertEqual(pcl.area_exception_problems(violations, decisions), [])
        missing = {**decisions, "areaExceptions": decisions["areaExceptions"][1:]}
        self.assertTrue(any("has no exception row" in p for p in pcl.area_exception_problems(violations, missing)))
        self.assertTrue(any("no longer violates" in p for p in pcl.area_exception_problems(violations[1:], decisions)))
        drift = [{**violations[0], "cells": violations[0]["cells"] + 1}, *violations[1:]]
        self.assertTrue(any("pins" in p for p in pcl.area_exception_problems(drift, decisions)))

    def test_committed_tiles_q4_and_red_probe(self):
        committed = json.loads(self.committed_text)
        self.assertEqual(pcl.committed_area_problems(committed), [])
        # 적색 프로브: 예외 행이 없는 省의 칸을 7개만 남기고 이웃 省에 넘긴 문서.
        meta = committed["_meta"]
        owner = pcl.expand(committed["owner"], meta["rows"], meta["cols"]).copy()
        index = next(i for i, row in enumerate(committed["provinceRecords"]) if row["nameCh"] == "邺县")
        cells = [tuple(rc) for rc in __import__("numpy").argwhere(owner == index).tolist()]
        other = next(i for i, row in enumerate(committed["provinceRecords"]) if row["nameCh"] == "内黄县")
        for cell in cells[7:]:
            owner[cell] = other
        committed["owner"] = pcl.encode(owner)
        [problem] = pcl.committed_area_problems(committed)
        self.assertIn("邺县 area 7", problem)

    def test_red_probe_tampered_input_blob_is_refused(self):
        from unittest import mock
        with tempfile.TemporaryDirectory() as scratch:
            fake = Path(scratch) / "blob.gz"
            fake.write_bytes(pcl.INPUT_BLOB.read_bytes()[:-8] + b"tampered")
            ledger = json.loads(json.dumps(self.ledger))
            with mock.patch.object(pcl, "ROOT", Path("/")):
                ledger["geometry"]["stages"][0]["inputBlob"]["path"] = str(fake).lstrip("/")
                with self.assertRaisesRegex(ValueError, "pinned partition input blob"):
                    pcl.restore_document(self.staged, ledger)


if __name__ == "__main__":
    unittest.main()
