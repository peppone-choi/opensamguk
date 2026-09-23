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
        # 2026-09-14: w1 11곳 편입으로 OWN 1111 → 1123.
        # 2026-09-14: 吳縣(847, 吳郡 군치)·毘陵(848) 편입으로 OWN 1123 → 1125,
        # 인근 4省 재귀속(T5 52 → 48, T4 146 → 148).
        # 2026-09-15: 城 없던 縣 관할 176곳(w2) 편입 + 대리 治所 城 12곳의 직할 省 연결로
        # OWN 1125 → 1346. 제 縣 城이 생긴 省이 郡治(T2 148 → 5)·같은 郡 최근접(T3 48 → 4)·
        # 이웃 郡 폴백(T5 177 → 143)에서 빠져나왔다. 城이 하나도 없는 섬 성분(22)은 그대로다.
        # 같은 날 거점 省 73곳이 縣 省에서 떨어져 나와 제 거점 城을 가져 OWN 1346 → 1419 이다(나머지 불변 —
        # 거점 城은 남의 省 귀속 대상이 되지 않는다). 2026-09-16 河南尹 平陰(1098) 이 떠난 자리 省을 제 城으로 가져 1419 → 1420.
        # 2026-09-17(ADR-LITE-056): 城 없던 관할 11곳을 같은 실체 城 관할에 접고 郡國 밖 취락 37곳이 城으로 서서
        # 모든 省이 제 관할 治所 城을 본다 — 1594 전부 OWN. 이제 빌더가 OWN 아닌 省을 원장으로 쓰지 않고 멈춘다.
        # 2026-09-23: 결손 縣 56곳이 제 省을 받아 1374 → 1430. 폴백은 그대로 전부 0 이다.
        self.assertEqual({"OWN_COUNTY_SEAT": 1653}, dict(self.basis))

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
        # 귀속 충돌 5곳 defer로 gap·T5 구조는 그대로다(w1-script-variant-county-join 11곳).
        # 48 → 34 는 w2 가 城 없던 郡 14곳(廣漢屬國·廣魏·廬陵·張掖屬國·新興·新都·樂平·樂陵·涪陵·漢昌·
        # 甘陵·西平·長樂·鮮卑)에 城을 세운 것이다.
        # 남은 34 중 30 은 郡國 밖 세력이고, 新平·毗陵典農校尉·汶山·章武 4곳은 治所가 기존 城과
        # 같은 자리라 새 城을 세우지 않았다(route-node-jurisdiction-claims-v1 excluded).
        # 2026-09-17: 남은 34 중 郡國 밖 세력 30은 城을 받고(w5), 新平·毗陵典農校尉·汶山·章武 4곳은 관할이 이웃 城 관할에
        # 접혀 省이 없다(fold_cityless_jurisdictions) — 0.
        self.assertEqual(0, len(self.gaps))
        for gap in self.gaps:
            self.assertIsNotNone(gap["seatPlaceId"], gap)
            self.assertGreater(gap["provinceCount"], 0, gap)
        self.assertEqual(
            0, sum(gap["provinceCount"] for gap in self.gaps)
        )

    def test_cityless_land_is_walked_out_to_the_nearest_city(self) -> None:
        # T5 는 城 없는 郡의 땅이 빵꾸로 남지 않게 하는 칸이다. 붙은 省은 반드시 城을 갖고,
        # 바퀴 수(hop)가 1 이상이며, 제 郡 밖의 城을 본다.
        walked = [
            row for row in self.rows
            if row["basis"] == attribution.CROSSES_COMMANDERY_BOUNDARY
        ]
        # 2026-09-17: 城 없는 郡의 땅이 남지 않아 T5 로 걸어 나갈 省이 없다(143 → 0).
        self.assertEqual(0, len(walked))
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
        # 2026-09-17: 바다 건너 22 省도 제 취락 城(邪馬壹國·夷洲·于山國·州胡·對馬·流求)을 받아 남지 않는다.
        self.assertEqual({}, dict(stranded))

    def test_no_coordinates_leak_into_the_ledger(self) -> None:
        # 원장은 좌표를 적지 않는다(build_external_places 규칙과 같다).
        forbidden = {"col", "row", "lat", "lon", "x", "y"}
        for row in self.rows:
            self.assertEqual(set(), forbidden & set(row), row)


if __name__ == "__main__":
    unittest.main()
