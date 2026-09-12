"""省 → 城 귀속 원장 계약.

빵꾸(색칠 안 되는 프로빈스)와 「북쪽·조선반도·남만·서북으로 이동이 안 된다」는 뿌리가
같다 — 城이 없는 縣의 땅이 어느 城에도 귀속되지 않았다. 여기서 거는 것은 그 귀속 규칙의
순서(사료 우선)와, 郡 경계를 넘지 않는다는 것, 그리고 커밋된 원장이 현재 입력에서
그대로 다시 나온다는 것이다.
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
        # 앞 판(1082/154/55/229)에서 한 칸만 옮겼다 — 오배정 縣 재바인딩으로 南鄉縣(71022)이
        # 동명이지(漢中)에서 南鄉郡(PARENT-0113)으로 돌아가 그 郡이 城을 얻었고, 郡에 남아
        # 있던 直屬 省 하나가 COMMANDERY_HAS_NO_CITY 에서 SAME_COMMANDERY_SEAT 로 넘어갔다.
        # 縣 제 省은 옮겨 가서도 제 治所를 그대로 써서 OWN_COUNTY_SEAT 수는 안 변한다.
        self.assertEqual(
            {
                "OWN_COUNTY_SEAT": 1082,
                "SAME_COMMANDERY_SEAT": 155,
                "SAME_COMMANDERY_NEAREST": 55,
                "COMMANDERY_HAS_NO_CITY": 228,
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
        # 52 에서 51 로 내려온 건 南鄉郡(PARENT-0113)이 治所를 얻었기 때문이다 —
        # 南鄉縣이 동명이지(陝西 鎮巴)에 묶여 漢中郡 땅에 서 있다가 제자리(河南 淅川)로
        # 돌아왔다. data/curated/han/county-misbinding-rebindings-v1.json 참조.
        self.assertEqual(51, len(self.gaps))
        for gap in self.gaps:
            self.assertIsNotNone(gap["seatPlaceId"], gap)
            self.assertGreater(gap["provinceCount"], 0, gap)
        self.assertEqual(
            228, sum(gap["provinceCount"] for gap in self.gaps)
        )

    def test_no_coordinates_leak_into_the_ledger(self) -> None:
        # 원장은 좌표를 적지 않는다(build_external_places 규칙과 같다).
        forbidden = {"col", "row", "lat", "lon", "x", "y"}
        for row in self.rows:
            self.assertEqual(set(), forbidden & set(row), row)


if __name__ == "__main__":
    unittest.main()
