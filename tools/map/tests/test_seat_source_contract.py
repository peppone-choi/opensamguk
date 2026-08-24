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
HAN_JSON_PATH = ROOT / "infra/src/main/resources/map/han.json"


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
        cls.juns = tiles["juns"]
        cls.seat_of = {j["name"]: j["seat"] for j in tiles["juns"]}
        cls.names = {c.get("nameCh") for c in cls.cities}

    def test_jun_names_are_unique(self) -> None:
        """이 파일의 다른 단언들이 `{name: seat}` 매핑에 의존한다 — 이름이 겹치면 조용히 덮인다.

        의존하는 성질을 검증하지 않으면 그 단언들의 통과가 아무것도 증명하지 않는다.
        실측 시점(2026-08-24)에 juns 175건은 `name`·`nameCh` 둘 다 중복 0 이다.
        (다만 `cities` 쪽은 다르다 — 한글명 91개가 205노드에 겹친다. U57.)
        """
        names = [j["name"] for j in self.juns]
        self.assertEqual(len(names), len(set(names)), "juns 한글명이 겹친다 — seat_of 매핑이 조용히 덮인다")

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


    def test_forbidden_coords_are_not_on_the_board(self) -> None:
        """R48 확장 — 「모른다」뿐 아니라 「이 답은 틀렸다」도 단언으로 적는다.

        부재만 기록하면 다음 사람이 CHGIS 를 조회해 같은 오답을 다시 찾아낸다.
        누가 이 좌표로 노드를 만들면 즉시 빨개진다.
        """
        for row in self.rows:
            for bad in row.get("forbiddenCoords", []):
                lon, lat = bad["lonLat"]
                for city in self.cities:
                    if city.get("lon") is None or city.get("lat") is None:
                        continue
                    if abs(city["lon"] - lon) < 0.01 and abs(city["lat"] - lat) < 0.01:
                        self.fail(
                            f"\n{row['jun']}: 금지 좌표 {bad['name']} {bad['lonLat']} 에 "
                            f"노드 {city.get('nameCh')} 가 있다.\n  {bad['why']}"
                        )


class KnownDefectsAreStillBroken(unittest.TestCase):
    """R48 — 「모른다」와 「아직 안 고쳤다」를 주석이 아니라 단언으로 적는다(§3.23).

    **아래 단언들은 전부 현재의 깨진 상태를 고정한다. 옳아서 그 값인 게 아니라 틀렸는데 그 값이다.**
    고치면 빨개진다 — **그때 값을 맞추지 말고 그 단언을 지워라.**
    값을 맞추는 것과 단언을 지우는 것은 다르다. 값만 맞추면 결함이 사라진 게 아니라 기록이 사라진다.
    """

    @classmethod
    def setUpClass(cls) -> None:
        tiles = json.loads(TILES_PATH.read_text(encoding="utf-8"))
        cls.cities = tiles["cities"]
        cls.han_cities = json.loads(HAN_JSON_PATH.read_text(encoding="utf-8"))["cities"]

    def test_u46_jiangxia_has_two_offboard_commanderies(self) -> None:
        """**이 값은 결함이다.** `江夏郡` 이 COMMANDERY 노드로 둘이다(U46).

        둘 다 `zhi=False` 라 판 밖이어서 지금은 무해하다 — **무해 판정은 조건부다.**
        이 단언은 「고쳐졌다」가 아니라 **「아직 안 고쳐졌다」를 지킨다.**
        하나로 합치거나 판에 올리면 빨개진다 — 그때 값을 맞추지 말고 이 단언을 지워라.
        """
        nodes = [c for c in self.cities if c.get("nameCh") == "江夏郡"]
        self.assertEqual(2, len(nodes), "江夏郡 COMMANDERY 노드 수가 변했다 — U46 을 재판정해라")
        for node in nodes:
            self.assertFalse(node.get("zhi"), f"江夏郡 {node.get('name')} 이 판에 올라왔다 — U46 이 무해가 아니게 됐다")

    def test_u47_shiping_two_nodes_have_different_korean_names(self) -> None:
        """**이 값은 결함이다.** `始平县` 두 노드의 한글명이 `시평현`/`시령현` 으로 다르다(U47).

        `nameCh` 는 같은데 `name` 이 다르다. **어느 쪽이 오기인지 확인 안 했다** — 그래서 고치지 않고 고정한다.
        정정하면 빨개진다 — 그때 값을 맞추지 말고 이 단언을 지우고 U47 을 닫아라.
        """
        names = sorted(c.get("name") for c in self.cities if c.get("nameCh") == "始平县")
        self.assertEqual(["시령현", "시평현"], names, "始平县 한글명이 변했다 — U47 을 재판정해라")

    def test_u48_offboard_homonyms_are_still_offboard(self) -> None:
        """**이 값은 결함이다.** `zhi=False` 동명 縣이 15개 이름에 걸쳐 있다(U48).

        **「지금은 무해하다」는 시간이 지나면 조용히 거짓이 되는 문장이다.**
        §13 확장이 이 縣들을 판에 올리는 순간 동명 충돌이 나는데, 조건이 주석에만 있으면 아무 일도 안 일어난다.
        개수가 변하면 빨개진다 — 그때 값을 맞추지 말고 §13 에서 조사 1 을 다시 돌려라.
        """
        by_name: dict[str, list[dict]] = {}
        for city in self.cities:
            by_name.setdefault(city.get("nameCh"), []).append(city)
        clashing = sorted(
            name for name, group in by_name.items()
            if any(c.get("zhi") for c in group) and any(not c.get("zhi") for c in group)
        )
        self.assertEqual(15, len(clashing), f"동명 충돌 후보가 변했다 — U48 을 재판정해라: {clashing}")

    def test_u53_xinxing_commandery_has_no_counties(self) -> None:
        """**이 값은 결함이다.** `新興郡` 은 晉書 「統縣五」인데 판에는 소속이 **1** 이다(U53).

        게다가 그 1 은 縣이 아니라 **郡 자기 노드**다 — 실제 縣은 0 이다.
        사료 근거는 U51 과 같은 줄에 있다: `dsfy-040.txt:349` 「立新興郡，領九原等縣」.
        縣이 붙으면 빨개진다 — 그때 값을 맞추지 말고 이 단언을 지우고 U53 을 닫아라.
        """
        members = [c["name"] for c in self.han_cities if (c.get("meta") or {}).get("jun") == "신흥군"]
        self.assertEqual(["신흥군"], members, "新興郡 소속이 변했다 — U53 을 재판정해라")


if __name__ == "__main__":
    unittest.main()
