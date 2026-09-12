from __future__ import annotations

import hashlib
import importlib.util
import json
import subprocess
import sys
import unittest
from collections import Counter, defaultdict, deque
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
MODULE_PATH = ROOT / "tools/scenario/build_han_world.py"
SPEC = importlib.util.spec_from_file_location("build_han_world", MODULE_PATH)
assert SPEC and SPEC.loader
build_han_world = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(build_han_world)


class HanWorldV3Test(unittest.TestCase):
    def test_replaced_nodes_derive_visible_names_from_selected_physical_identity(self) -> None:
        selection = json.loads(
            (ROOT / "data/curated/han/route-node-selection-v1.json").read_text()
        )["routeNodes"]
        tiles = json.loads((ROOT / "data/map/han-tiles.json").read_text())
        legacy = json.loads(
            (ROOT / "infra/src/main/resources/map/han-780-v1.json").read_text()
        )
        world = json.loads(
            (ROOT / "infra/src/main/resources/map/han-world-v3.json").read_text()
        )
        physical_by_id = {str(city["id"]): city for city in tiles["cities"]}
        legacy_by_id = {city["id"]: city for city in legacy["cities"]}
        world_by_id = {city["id"]: city for city in world["cities"]}

        base_names: dict[int, str] = {}
        for node in selection:
            city_id = node["numericCityId"]
            if node.get("legacyDisposition") == "REPLACED" or city_id > 780:
                place_id = node["physicalPlaceRef"].rsplit(":", 1)[-1]
                physical_name = physical_by_id[place_id]["name"]
                base_names[city_id] = next(
                    (
                        physical_name.removesuffix(tail)
                        for tail in ("후국", "현", "국", "읍", "도")
                        if physical_name.endswith(tail) and len(physical_name) > len(tail)
                    ),
                    physical_name,
                )
            else:
                base_names[city_id] = legacy_by_id[city_id]["name"]
        base_counts = Counter(base_names.values())
        expected_names = {
            node["numericCityId"]: (
                f'{base_names[node["numericCityId"]]}({node["parentName"]})'
                if base_counts[base_names[node["numericCityId"]]] > 1
                else base_names[node["numericCityId"]]
            )
            for node in selection
        }
        qualified_counts = Counter(expected_names.values())
        expected_names = {
            city_id: f"{name}#{city_id}" if qualified_counts[name] > 1 else name
            for city_id, name in expected_names.items()
        }

        replaced = [node for node in selection if node.get("legacyDisposition") == "REPLACED"]
        self.assertEqual(101, len(replaced))
        for node in replaced:
            city_id = node["numericCityId"]
            self.assertEqual(expected_names[city_id], world_by_id[city_id]["name"], node)
        self.assertEqual("수춘", world_by_id[543]["name"])
        self.assertEqual(
            {93: "정강", 211: "낙평", 311: "곡양(下邳國)", 437: "안중"},
            {city_id: world_by_id[city_id]["name"] for city_id in (93, 211, 311, 437)},
        )

        retained = [node for node in selection if node.get("legacyDisposition") == "RETAINED"]
        for node in retained:
            city_id = node["numericCityId"]
            self.assertEqual(expected_names[city_id], world_by_id[city_id]["name"], node)
        # level·max·initial 은 더 이상 legacy 780 판에서 물려받지 않는다. 물려받으면
        # 「그 번호가 옛 세계에서 무엇이었는가」가 등급이 되어, 郡 이 172 → 100 으로
        # 줄면서 縣 704 중 93 이 郡 등급을 달고 있었다. 지금은 選定의 seatRole 과
        # 郡國志 戶口에서 다시 세운다 —
        # test_v3_levels_follow_seat_role_and_carry_matching_stats 가 그쪽을 건다.
        self.assertNotEqual(
            [legacy_by_id[node["numericCityId"]]["level"] for node in retained],
            [world_by_id[node["numericCityId"]]["level"] for node in retained],
        )

    def test_county_adjacency_endpoints_are_spatial_province_indices(self) -> None:
        # 귀속은 호출자가 省 인덱스로 명시해 넘긴다. cities 배열 서수는 여기 끼어들지 않는다.
        tiles = {
            "cities": [
                {"id": "place-b"},
                {"id": "unrelated"},
                {"id": "place-a"},
            ],
            "provinceRecords": [
                {"id": "jurisdiction-a", "cityIndex": 2},
                {"id": "jurisdiction-b", "cityIndex": 0},
            ],
            "adjacency": {"county": [{"a": 0, "b": 1, "cells": 6}]},
        }

        edges = build_han_world.project_county_adjacency(tiles, {0: 781, 1: 273})

        self.assertEqual([(273, 781, 6)], edges)

    def test_unattributed_province_drops_its_edges(self) -> None:
        # 城이 하나도 없는 郡의 땅이다(귀속 원장 COMMANDERY_HAS_NO_CITY). 지어낸 城으로
        # 메우지 않고 간선을 버린다 — 옛 규칙과 같은 결론이고, 다른 점은 「城 없는 縣」이
        # 더는 여기 오지 않는다는 것뿐이다.
        tiles = {
            "cities": [],
            "provinceRecords": [{"id": "a"}, {"id": "b"}, {"id": "c"}],
            "adjacency": {
                "county": [
                    {"a": 0, "b": 1, "cells": 6},
                    {"a": 1, "b": 2, "cells": 4},
                ]
            },
        }

        edges = build_han_world.project_county_adjacency(tiles, {0: 10, 1: 20})

        self.assertEqual([(10, 20, 6)], edges)

    def test_shared_city_merges_boundary_cells_instead_of_dropping_one(self) -> None:
        # 한 城이 여러 省을 거느리므로 같은 城 쌍이 여러 경계에서 나온다. 접한 칸수는
        # 그 경계들의 합이다 — 하나를 골라 버리면 길 굵기가 왜곡된다.
        tiles = {
            "cities": [],
            "provinceRecords": [{"id": "a"}, {"id": "a2"}, {"id": "b"}],
            "adjacency": {
                "county": [
                    {"a": 0, "b": 2, "cells": 6},
                    {"a": 1, "b": 2, "cells": 4},
                ]
            },
        }

        edges = build_han_world.project_county_adjacency(tiles, {0: 10, 1: 10, 2: 20})

        self.assertEqual([(10, 20, 10)], edges)

    def test_real_boundary_projection_links_lu_but_not_lu_county_to_licheng(self) -> None:
        tiles = json.loads((ROOT / "data/map/han-tiles.json").read_text())
        province_by_id = {
            str(row["id"]): index
            for index, row in enumerate(tiles["provinceRecords"])
        }

        edges = build_han_world.project_county_adjacency(
            tiles,
            {
                province_by_id["45098"]: 273,
                province_by_id["45022"]: 781,
                province_by_id["45180"]: 999,
            },
        )

        self.assertEqual([(273, 781, 6)], edges)

        province_index = {
            str(row["id"]): index for index, row in enumerate(tiles["provinceRecords"])
        }
        graph: dict[int, list[int]] = defaultdict(list)
        for edge in tiles["adjacency"]["county"]:
            graph[edge["a"]].append(edge["b"])
            graph[edge["b"]].append(edge["a"])
        source, destination = province_index["45180"], province_index["45022"]
        queue = deque([(source, 0)])
        visited = {source}
        distance = None
        while queue:
            current, hops = queue.popleft()
            if current == destination:
                distance = hops
                break
            for neighbor in graph[current]:
                if neighbor not in visited:
                    visited.add(neighbor)
                    queue.append((neighbor, hops + 1))
        self.assertEqual(4, distance)

    def test_committed_v3_is_the_reviewed_selection_with_canonical_licheng_edge(self) -> None:
        selection = json.loads(
            (ROOT / "data/curated/han/route-node-selection-v1.json").read_text()
        )
        world = json.loads(
            (ROOT / "infra/src/main/resources/map/han-world-v3.json").read_text()
        )
        manifest = json.loads(
            (ROOT / "data/map/han-world-v3-manifest-v1.json").read_text()
        )
        expected = sorted(
            (
                node["routeNodeKey"],
                node["numericCityId"],
                node["physicalPlaceRef"],
            )
            for node in selection["routeNodes"]
        )
        actual = sorted(
            (city["routeNodeKey"], city["id"], city["physicalPlaceRef"])
            for city in world["cities"]
        )
        self.assertEqual(expected, actual)
        selection_by_id = {
            node["numericCityId"]: node for node in selection["routeNodes"]
        }
        # 選定 원장의 parentName 은 續漢書 郡國志가 그 縣을 실은 郡이고, 월드의 meta.junCh 는
        # 그 縣이 실제로 선 칸의 han-tiles parentOwner 다. 변경 縣 51곳 중 12곳은 두 값이
        # 어긋난다 — 배치 원장이 그 12건을 worldParentRegionId != hhsParentRegionId 로
        # 명시해 두었으므로, 그 집합과 **정확히** 같은지만 허용한다.
        placements = json.loads(
            (ROOT / "data/curated/han/frontier-county-placements-v1.json").read_text()
        )["placements"]
        expected_reassigned = {
            (row["physicalPlaceRef"], row["hhsCommanderyHan"], row["worldCommanderyHan"])
            for row in placements
            if row["worldParentRegionId"] != row["hhsParentRegionId"]
        }
        self.assertEqual(12, len(expected_reassigned))
        reassigned = set()
        for city in world["cities"]:
            node = selection_by_id[city["id"]]
            if node["parentName"] != city["meta"]["junCh"]:
                reassigned.add(
                    (city["physicalPlaceRef"], node["parentName"], city["meta"]["junCh"])
                )
            self.assertEqual(
                node["seatRole"] == "COMMANDERY_SEAT",
                city["meta"]["isSeat"],
            )
        self.assertEqual(expected_reassigned, reassigned)
        self.assertEqual(832, len(actual))
        tiles = json.loads((ROOT / "data/map/han-tiles.json").read_text())
        physical = {str(city["id"]): city for city in tiles["cities"]}
        for city in world["cities"]:
            place = physical[city["physicalPlaceRef"].rsplit(":", 1)[-1]]
            self.assertEqual(
                round(place["col"] * world["width"] / tiles["_meta"]["cols"]), city["x"]
            )
            self.assertEqual(
                round(place["row"] * world["height"] / tiles["_meta"]["rows"]), city["y"]
            )
        self.assertEqual(expected, sorted([
            (row["routeNodeKey"], row["numericCityId"], row["physicalPlaceRef"])
            for row in manifest["routeNodes"]
        ]))

        by_id = {city["id"]: city for city in world["cities"]}
        self.assertIn(781, by_id[273]["connections"])
        self.assertIn(273, by_id[781]["connections"])
        edge = next(
            edge for edge in manifest["countyAdjacency"]
            if {edge["a"], edge["b"]} == {273, 781}
        )
        self.assertEqual(6, edge["sharedBoundaryCells"])
        output_paths = {
            "worldJsonSha256": ROOT / "infra/src/main/resources/map/han-world-v3.json",
            "cityConstSha256": ROOT / "common/src/main/kotlin/opensamguk/common/constants/HanWorldV3CityConst.kt",
            "gateIndexSha256": ROOT / "common/src/main/kotlin/opensamguk/common/constants/HanWorldV3GateIndex.kt",
        }
        for field, path in output_paths.items():
            self.assertEqual(
                manifest["outputs"][field], hashlib.sha256(path.read_bytes()).hexdigest()
            )

    def test_v3_check_is_separate_and_legacy_artifacts_remain_pinned(self) -> None:
        # 2026-09-11: 縣 51곳이 들어와 han-tiles provinceRecords 배열이 1,524→1,520 으로
        # 재구성됐다. legacy han.json 의 provinceId 는 그 배열의 **인덱스**라, 재바인딩
        # (materialize_runtime_province_identity.py --write) 없이 두면 城이 남의 省을
        # 가리킨다. 城 집합·이름·좌표·연결은 불변이고 바뀐 것은 provinceId 뿐이다.
        expected = {
            "infra/src/main/resources/map/han.json": "13744d62cefe3b946398cb4ce6f2dc68f1024c8f45db8a9e3e66a5a476b9a08d",
            "infra/src/main/resources/map/han-780-v1.json": "a61cbd8aa6fd0dd2f7f794df6d0ebdc026c0b6c351568c60efb8d115f54b3670",
        }
        for rel, digest in expected.items():
            self.assertEqual(digest, hashlib.sha256((ROOT / rel).read_bytes()).hexdigest())
        result = subprocess.run(
            [sys.executable, str(MODULE_PATH), "--target", "han-world-v3", "--check"],
            cwd=ROOT,
            text=True,
            capture_output=True,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_junguozhi_household_table_reproduces_the_v2_world_it_replaces(self) -> None:
        """커밋된 원장에서 읽은 戶口가 junguozhi.json 과 같은 답을 낸다.

        `data/map/junguozhi.json` 은 .gitignore 대상이라 CI 에 없다. v3 는 대신
        `administrative-units.json` 의 郡國志 인용문에서 戶數를, `declaredCities` 에서
        縣 수를 읽는다. 그 표가 v2 han.json 의 등급을 그대로 재현하는지 본다 —
        v2 는 junguozhi.json 으로 만들어진 세계라, 맞으면 두 사본이 같다는 뜻이다.

        검사와 대상이 출처를 공유하지 않는다: 이쪽은 위키문헌 코퍼스 인용,
        저쪽은 ctext HTML 파싱이다.
        """
        groups = build_han_world.junguozhi_groups()
        thresholds = build_han_world.level_thresholds(
            [row["households"] for row in groups.values() if row["households"]]
        )
        levels = build_han_world.LEVELS
        v2 = json.loads((ROOT / "infra/src/main/resources/map/han.json").read_text())

        seat_hits = 0
        seat_mismatch: set[str] = set()
        county_hits = county_missing = 0
        for city in v2["cities"]:
            jun = city["meta"]["junCh"]
            group = groups.get(jun) or {}
            actual = levels[city["level"] - 1]
            if city["meta"]["isSeat"]:
                if jun in build_han_world.CAPITALS:
                    expected = "경"
                else:
                    households = (
                        build_han_world.FRONTIER[jun][1]
                        if jun in build_han_world.FRONTIER
                        else group.get("households")
                    )
                    expected = (
                        build_han_world.HOUSEHOLD_LEVELS[
                            sum(households > t for t in thresholds)
                        ] if households else "소"
                    )
                seat_hits += 1
                if expected != actual:
                    seat_mismatch.add(jun)
            else:
                households, counties = group.get("households"), group.get("counties")
                if not (households and counties):
                    county_missing += 1
                    continue
                expected = (
                    "영현"
                    if households // counties >= build_han_world.LING_HOUSEHOLDS
                    else "장현"
                )
                county_hits += 1
                self.assertEqual(expected, actual, jun)

        # 郡治 172 중 어긋나는 것은 郡國 밖 세력 7 곳뿐이다 — 그쪽은 戶數가 아니라
        # 治所의 kind(EXTERNAL_PLACE)로 '이' 등급을 받는 가지라 이 표와 무관하다.
        self.assertEqual(172, seat_hits)
        self.assertEqual(
            {"山越", "哀牢", "白馬氐", "西羌", "南匈奴", "烏桓", "鮮卑"}, seat_mismatch,
        )
        # 재현되는 표본이 실제로 크다는 것을 같이 못박는다 — 0 건이 통과로 읽히면 안 된다.
        self.assertGreater(county_hits, 500)
        self.assertLess(county_missing, 60)

    def test_v3_levels_follow_seat_role_and_carry_matching_stats(self) -> None:
        """郡治는 郡 등급, 縣은 縣 등급. max·initial 도 그 등급의 값이다.

        고치기 전 실측(2026-09-10): 縣 704 중 93 이 郡 등급(소 79·중 10·대 4)을,
        7 이 이민족 등급('이')을 legacy 780 판에서 번호째 물려받고 있었고, 郡治 77 중
        2 는 거꾸로 縣 등급이었다.
        """
        selection = json.loads(
            (ROOT / "data/curated/han/route-node-selection-v1.json").read_text()
        )["routeNodes"]
        world = json.loads(
            (ROOT / "infra/src/main/resources/map/han-world-v3.json").read_text()
        )
        seat_role = {node["numericCityId"]: node["seatRole"] for node in selection}
        levels = build_han_world.LEVELS
        maxes = build_han_world.che_max_by_level()

        commandery_grades = {"소", "중", "대", "특", "경"}
        county_grades = {"영현", "장현"}
        seats = counties = 0
        for city in world["cities"]:
            name = levels[city["level"] - 1]
            if seat_role[city["id"]] == "COMMANDERY_SEAT":
                self.assertIn(name, commandery_grades, city["name"])
                seats += 1
            else:
                self.assertIn(name, county_grades, city["name"])
                counties += 1
            self.assertEqual(maxes[name], city["max"], city["name"])
            self.assertEqual(
                dict(zip(build_han_world.STAT_KEYS, build_han_world.BUILD_INIT[name])),
                city["initial"], city["name"],
            )
        self.assertEqual(77, seats)
        # 704 + 변경 縣 51 = 755 (郡治 51곳은 이미 서 있어 縣으로 오지 않는다).
        self.assertEqual(755, counties)
        # '이'(이민족)는 v3 에 남지 않는다 — 選定 원장이 郡國 밖 세력을 통째로 뺐다.
        self.assertNotIn(4, {city["level"] for city in world["cities"]})

    def test_23_commanderies_still_have_no_seat_in_the_world(self) -> None:
        """아직 못 고친 결함을 숫자로 못박아 둔다.

        v3 의 郡 100 중 23 은 治所가 世界에 아예 없다. 그중 太原郡 晉陽 · 廣陽郡 薊 ·
        東郡 濮陽 처럼 CHGIS 에 점 자체가 없는 곳이 있고, 齊國 臨淄(85234) ·
        泰山郡 奉高(85697) · 東海郡 郯城(85649) · 吳郡 吳(40404) · 魯國 魯(45180) ·
        鉅鹿郡 廮陶(87061) 처럼 지형에는 있는데 選定에서 빠진 곳이 있다.
        치소를 새로 세우는 것은 選定 원장을 고치는 별건이라 여기서는 현황만 고정한다.
        """
        selection = json.loads(
            (ROOT / "data/curated/han/route-node-selection-v1.json").read_text()
        )["routeNodes"]
        by_parent: dict[str, list[str]] = defaultdict(list)
        for node in selection:
            by_parent[node["parentName"]].append(node["seatRole"])
        seatless = sorted(
            parent for parent, roles in by_parent.items()
            if "COMMANDERY_SEAT" not in roles
        )
        self.assertEqual(100, len(by_parent))
        self.assertEqual(23, len(seatless))
        self.assertIn("太原郡", seatless)
        self.assertIn("齊國", seatless)



class DisplayNameTest(unittest.TestCase):
    """화면·로그에 적을 이름 규칙. web/shared/src/iso/cityName.ts 와 같은 판정이어야 한다.

    값은 전부 han-world-v3 실측이다 — 지어낸 城 은 없다.
    """

    def test_name_ch_tail_beats_level(self) -> None:
        display = build_han_world.display_name
        # 弘農郡 治所. 등급은 「소」라 등급 규칙으로는 안 잡히고 nameCh 꼬리가 잡는다.
        self.assertEqual("홍농현", display(22, "홍농", "소", "弘农县"))
        # 侯國은 縣 한 급이다(百官志 「列侯所食縣曰國」). 등급 6 이어도 縣이다.
        self.assertEqual("낙평현", display(211, "낙평", "중", "乐平侯国"))
        # 屬國은 郡 한 급이다. 등급이 「장현」이어도 縣을 붙이면 없는 縣을 만든다.
        self.assertEqual("구자속국", display(429, "구자속국", "장현", "龜茲屬國"))
        # 縣 기록이 없는 郡. 「감릉현」을 새로 만들지 않는다.
        self.assertEqual("감릉", display(240, "감릉", "중", "甘陵郡"))

    def test_qualifier_is_stripped_and_hyeon_is_not_doubled(self) -> None:
        display = build_han_world.display_name
        # 한글 독음이 겹쳐 붙인 식별자용 한정자는 뗀다(「군을 빼」 2026-09-10).
        self.assertEqual("의씨현", display(2, "의씨(河東郡)", "장현", "猗氏县"))
        self.assertEqual("영릉현", display(9999, "영릉#9999", "장현", "零陵县"))
        # 이미 「현」으로 끝나면 덧붙이지 않는다.
        self.assertEqual("동평릉현", display(123, "동평릉현", "장현", "东平陵县"))
        # 郡國 밖 세력은 음수 번호로 온다 — 縣이 아니다.
        self.assertEqual("우산국", display(-35, "우산국", "이", ""))

    def test_shipped_world_carries_the_same_value(self) -> None:
        """실려 나간 값이 규칙을 다시 돌린 결과와 한 곳도 다르지 않다."""
        world = json.loads(
            (ROOT / "infra/src/main/resources/map/han-world-v3.json").read_text()
        )
        levels = build_han_world.LEVELS
        mismatched = [
            (city["id"], city["name"], city["meta"]["displayName"])
            for city in world["cities"]
            if city["meta"]["displayName"] != build_han_world.display_name(
                city["id"], city["name"], levels[city["level"] - 1], city["meta"]["nameCh"],
            )
        ]
        self.assertEqual([], mismatched)
        # 0 건이 「전부 이름이 같아서」가 아님을 같이 못박는다.
        changed = [
            city for city in world["cities"]
            if city["meta"]["displayName"] != city["name"]
        ]
        self.assertEqual(832, len(world["cities"]))
        self.assertEqual(823, len(changed))

    def test_kotlin_table_carries_the_display_name(self) -> None:
        """RawCity 14 번째 인자로 실려 나간다 — 로그가 읽는 자리가 여기다."""
        kt = (ROOT / "common/src/main/kotlin/opensamguk/common/constants"
              / "HanWorldV3CityConst.kt").read_text()
        self.assertIn('RawCity(1, "장안(京兆尹)"', kt)
        self.assertIn('), "장안현"),', kt)


if __name__ == "__main__":
    unittest.main()
