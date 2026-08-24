"""판별자 5 — 사료가 명기한 郡 治所와 han-tiles.json 의 seat 가 맞는지 본다.

**이 테스트는 발견 도구가 아니라 회귀 방지 도구다.** tools/map/seat_sources.json 의
행들에서 결함을 「찾은」 건 사람이 사료를 읽어서지 이 테스트가 아니다.
**표에 없는 郡은 이 테스트가 초록이어도 아무것도 보장되지 않는다.**
초록인 상태와 治所 배정이 옳은 상태는 다른 상태다.

축: 판별자 1~4 는 전부 데이터 안에서 데이터를 본다. 5 만 사료를 축으로 쓴다.
다만 **사료도 시대가 다르면 다른 축이 아니라 틀린 축이다** — 그래서 모든 행에 era 가 필수다.
(牂柯郡: 晉代 華陽國志만 보고 고쳤으면 220년 기준으로 멀쩡한 seat 을 깨뜨렸을 것이다.)
"""

import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
TABLE_PATH = ROOT / "tools/map/seat_sources.json"
TILES_PATH = ROOT / "data/map/han-tiles.json"


def load_table() -> list[dict]:
    """행 규칙을 강제한다. 출전·연대 없는 행은 여기서 거부된다 — 없으면 표가 오염된다."""
    rows = json.loads(TABLE_PATH.read_text(encoding="utf-8"))["rows"]
    for i, row in enumerate(rows):
        where = f"rows[{i}] ({row.get('jun', '?')})"
        for field in ("jun", "era", "verdict", "sources"):
            if not row.get(field):
                raise ValueError(f"{where}: '{field}' 가 없다")
        for j, src in enumerate(row["sources"]):
            for field in ("book", "seat", "citation"):
                if not src.get(field):
                    raise ValueError(f"{where}.sources[{j}]: '{field}' 가 없다 — 출전 없는 행은 축을 다시 같게 만든다")
        if row.get("test"):
            if not (row.get("expectedSeat") or row.get("seatNodeMissing")):
                raise ValueError(f"{where}: test=true 인데 expectedSeat/seatNodeMissing 둘 다 없다")
        elif not row.get("excludeReason"):
            raise ValueError(f"{where}: test=false 인데 excludeReason 이 없다 — 빼는 이유를 적어라")
    return rows


class SeatSourceContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.rows = load_table()
        tiles = json.loads(TILES_PATH.read_text(encoding="utf-8"))
        cls.cities = tiles["cities"]
        cls.seat_of = {j["name"]: j["seat"] for j in tiles["juns"]}
        cls.names = {c.get("nameCh") for c in cls.cities}

    def test_table_rows_are_wellformed(self) -> None:
        self.assertTrue(self.rows, "표가 비었다")

    def test_seat_matches_sources(self) -> None:
        for row in self.rows:
            if not row.get("test") or not row.get("expectedSeat"):
                continue
            jun = row["jun"]
            with self.subTest(jun=jun):
                self.assertIn(jun, self.seat_of, f"{jun} 이 juns 에 없다")
                actual = self.cities[self.seat_of[jun]].get("nameCh")
                cites = " · ".join(f"{s['book']} {s['seat']} ({s['citation']})" for s in row["sources"])
                self.assertEqual(
                    row["expectedSeat"], actual,
                    f"\n{jun}: 사료 治所 {row['expectedSeat']} 인데 seat 은 {actual}"
                    f"\n  연대: {row['era']}\n  판정: {row['verdict']}\n  출전: {cites}",
                )

    def test_missing_seat_nodes_are_still_missing(self) -> None:
        """U54형(결손). 노드가 생기면 이 행을 다시 판정해야 하므로 그때 빨개진다."""
        for row in self.rows:
            missing = row.get("seatNodeMissing")
            if not row.get("test") or not missing:
                continue
            with self.subTest(jun=row["jun"]):
                self.assertNotIn(
                    missing, self.names,
                    f"\n{row['jun']}: {missing} 노드가 생겼다. 결손이 해소됐으니 이 행을 "
                    f"seatNodeMissing 에서 expectedSeat 으로 옮기고 seat 을 배정해라.",
                )


if __name__ == "__main__":
    unittest.main()
