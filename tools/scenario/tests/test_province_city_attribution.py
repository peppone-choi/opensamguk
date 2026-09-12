"""省 → 城 귀속 원장 계약.

빵꾸(색칠 안 되는 프로빈스)와 「북쪽·조선반도·남만·서북으로 이동이 안 된다」는 뿌리가
같다 — 城이 없는 縣의 땅이 어느 城에도 귀속되지 않았다. 여기서 거는 것은 그 귀속 규칙의
순서(사료 우선)와, 郡 경계를 넘는 칸이 T5 하나뿐이라는 것, 그리고 커밋된 원장이 현재
입력에서 그대로 다시 나온다는 것이다.
"""

from __future__ import annotations

import json
import unittest
from collections import Counter
from pathlib import Path

from tools.scenario import build_province_city_attribution as attribution

ROOT = Path(__file__).resolve().parents[3]


class ProvinceCityAttributionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.tiles = json.loads(attribution.TILES.read_text(encoding="utf-8"))
        cls.selection = json.loads(attribution.SELECTION.read_text(encoding="utf-8"))
        cls.rows, cls.basis, cls.gaps = attribution.build_rows(cls.tiles, cls.selection)

    def test_committed_ledger_is_the_regenerated_ledger(self) -> None:
        committed = json.loads(attribution.LEDGER.read_text(encoding="utf-8"))
        self.assertEqual(self.rows, committed["rows"])
        self.assertEqual(
            attribution.sha256_path(attribution.TILES),
            committed["provenance"]["tilesSha256"],
        )
        self.assertEqual(
            attribution.sha256_path(attribution.SELECTION),
            committed["provenance"]["selectionSha256"],
        )

    def test_every_province_appears_exactly_once_in_record_order(self) -> None:
        provinces = self.tiles["provinceRecords"]
        self.assertEqual(len(provinces), len(self.rows))
        self.assertEqual(
            [row["provinceId"] for row in self.rows],
            [row["id"] for row in provinces],
        )
        self.assertEqual(
            [row["provinceIndex"] for row in self.rows], list(range(len(provinces)))
        )

    def test_measured_basis_counts(self) -> None:
        # 실측 기준선이다(2026-09-12). 임계값이 아니라 「지금 이 데이터가 이렇다」는 핀이다.
        # 앞 판(1082/155/55/206/22)에서 옮긴 것은 城을 하나도 못 받던 郡 3곳(朔方·西河·定襄)의
        # 治所가 城 833–835 로 서면서다. 그 郡 땅 29 省이 남의 郡 城을 보던 T5 에서 제 縣 治所를
        # 보는 OWN_COUNTY_SEAT 로 돌아왔다(206 → 177, 1082 → 1111).
        self.assertEqual(
            {
                "OWN_COUNTY_SEAT": 1111,
                "SAME_COMMANDERY_SEAT": 155,
                "SAME_COMMANDERY_NEAREST": 55,
                "ADJACENT_COMMANDERY_NEAREST": 177,
                "COMMANDERY_HAS_NO_CITY": 22,
            },
            dict(self.basis),
        )

    def test_attribution_never_crosses_a_commandery_boundary(self) -> None:
        jurisdictions = {
            str(row["id"]): row for row in self.tiles["jurisdictionRecords"]
        }
        place_to_jurisdiction = {
            str(row["seatPlaceId"]): str(row["id"])
            for row in self.tiles["jurisdictionRecords"]
        }
        for row in self.rows:
            if row["routeNodeId"] is None:
                continue
            if row["basis"] == attribution.CROSSES_COMMANDERY_BOUNDARY:
                # T5 만 郡 경계를 넘는다 — 그 郡에는 城이 하나도 없어서 넘을 수밖에 없다.
                continue
            owner = place_to_jurisdiction[str(row["cityPlaceId"])]
            self.assertEqual(
                row["commanderyId"],
                jurisdictions[owner]["commanderyId"],
                row,
            )

    def test_unattributed_rows_carry_no_city(self) -> None:
        for row in self.rows:
            if row["basis"] == "COMMANDERY_HAS_NO_CITY":
                self.assertIsNone(row["routeNodeId"], row)
                self.assertIsNone(row["cityPlaceId"], row)
            else:
                self.assertIsNotNone(row["routeNodeId"], row)

    def test_own_county_rows_point_at_their_own_county_seat(self) -> None:
        seat_of = {
            str(row["id"]): str(row["seatPlaceId"])
            for row in self.tiles["jurisdictionRecords"]
        }
        for row in self.rows:
            if row["basis"] != "OWN_COUNTY_SEAT":
                continue
            self.assertEqual(seat_of[row["jurisdictionId"]], str(row["cityPlaceId"]), row)

    def test_one_county_may_own_several_provinces(self) -> None:
        # 「한 縣에 한 省」이 아니다 — 계약이 그것을 허용해야 한다.
        per_county = Counter(row["jurisdictionId"] for row in self.rows)
        self.assertGreater(max(per_county.values()), 1)
        self.assertEqual(len(self.tiles["jurisdictionRecords"]), len(per_county))

    def test_cityless_commanderies_are_named_with_their_seat_place(self) -> None:
        # 52 → 51 은 南鄉郡(PARENT-0113)이 治所를 얻은 것이고(南鄉縣이 동명이지 陝西 鎮巴에서
        # 제자리 河南 淅川으로 돌아왔다, county-misbinding-rebindings-v1.json), 51 → 48 은
        # 朔方·西河·定襄 治所가 경로 노드로 선 것이다
        # (tools/scenario/append_cityless_commandery_seat_ledgers.py).
        self.assertEqual(48, len(self.gaps))
        for gap in self.gaps:
            self.assertIsNotNone(gap["seatPlaceId"], gap)
            self.assertGreater(gap["provinceCount"], 0, gap)
        self.assertEqual(
            199, sum(gap["provinceCount"] for gap in self.gaps)
        )

    def test_cityless_land_is_walked_out_to_the_nearest_city(self) -> None:
        # T5 는 城 없는 郡의 땅이 빵꾸로 남지 않게 하는 칸이다. 붙은 省은 반드시 城을 갖고,
        # 바퀴 수(hop)가 1 이상이며, 제 郡 밖의 城을 본다.
        walked = [
            row for row in self.rows
            if row["basis"] == attribution.CROSSES_COMMANDERY_BOUNDARY
        ]
        self.assertEqual(177, len(walked))
        jurisdictions = {
            str(row["id"]): row for row in self.tiles["jurisdictionRecords"]
        }
        place_to_jurisdiction = {
            str(row["seatPlaceId"]): str(row["id"])
            for row in self.tiles["jurisdictionRecords"]
        }
        for row in walked:
            self.assertIsNotNone(row["routeNodeId"], row)
            self.assertGreaterEqual(row["adjacencyHops"], 1, row)
            owner = place_to_jurisdiction[str(row["cityPlaceId"])]
            self.assertNotEqual(
                row["commanderyId"], jurisdictions[owner]["commanderyId"], row
            )

    def test_only_sea_separated_polities_stay_unattributed(self) -> None:
        # 남은 22 省은 전부 바다 건너다 — 육로 인접이 없으니 걸어서 닿을 城이 없다.
        # 뭍에 붙은 땅은 하나도 안 남는다는 것이 이 검사의 핵심이다.
        stranded = Counter(
            row["commanderyNameCh"] for row in self.rows
            if row["basis"] == "COMMANDERY_HAS_NO_CITY"
        )
        self.assertEqual(
            {"邪馬壹國": 12, "夷洲": 6, "于山國": 1, "州胡": 1, "狗邪國": 1, "流求": 1},
            dict(stranded),
        )

    def test_no_coordinates_leak_into_the_ledger(self) -> None:
        # 원장은 좌표를 적지 않는다(build_external_places 규칙과 같다).
        forbidden = {"col", "row", "lat", "lon", "x", "y"}
        for row in self.rows:
            self.assertEqual(set(), forbidden & set(row), row)


if __name__ == "__main__":
    unittest.main()
