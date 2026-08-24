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
import math
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
        """**`tools/scenario/han_ownership.json` 이 이 유일성에 의존한다. 깨지면 소유권이 조용히 덮인다.**

        그 파일은 세력별 보유 郡을 **郡名(한글)** 으로 적는다(`_comment`: 「郡名은 …
        juns[].name 만 쓴다」). 실측(2026-08-24): 郡名 문자열 **1156회, 서로 다른 이름 116개**.
        두 郡이 같은 한글명을 가지면 그 116개 중 하나가 어느 郡을 가리키는지 정해지지 않는다 —
        오류가 아니라 **조용한 오배정**으로 나온다.

        이 파일의 다른 단언들도 같은 성질에 의존한다: `{name: seat}` 는 이름이 겹치면 덮인다.
        **의존하는 성질을 검증하지 않으면 그 단언들의 통과가 아무것도 증명하지 않는다** —
        검사가 자기 전제를 안 재면 전제가 깨져도 초록이다(R47 × R48).
        실측 시점에 juns 175건은 `name`·`nameCh` 둘 다 중복 0 이다.
        (`cities` 쪽은 다르다 — U57, 아래 `KeySurfacesAreAmbiguous` 참조.)
        """
        names = [j["name"] for j in self.juns]
        self.assertEqual(len(names), len(set(names)), "juns 한글명이 겹친다 — han_ownership.json 의 소유권이 조용히 덮인다")

    def test_table_rows_are_wellformed(self) -> None:
        self.assertTrue(self.rows, "표가 비었다")

    def test_seat_matches_sources(self) -> None:
        """expectedSeat 이 있고 currentWrongSeat 이 없는 행만 「맞아야 한다」로 단언한다."""
        for row in self.rows:
            if not row.get("test") or not row.get("expectedSeat") or row.get("currentWrongSeat"):
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

    def test_unfixed_seats_are_still_wrong(self) -> None:
        """**미수정 결함 기준선.** 이 테스트가 초록인 건 「맞다」가 아니라 「아직 틀린 그대로다」다.

        currentWrongSeat 이 있는 행은 사료로 결함을 확증했지만 **아직 안 고친** 郡이다.
        han-tiles.json 의 juns[].seat 을 고치면 그 여파가 이 파일 밖으로 나간다 —
        juns[].col/row 가 治所 城의 col/row 와 같아야 하고(4건 모두 어긋난다),
        infra/.../map/han.json 이 seat 이름을 굽고, 무엇보다
        tools/scenario/validate_han_route_node_selection.py 의 legacyTileMap 앵커가
        han-tiles.json 해시에 핀돼 있어 **즉시 provenance 불일치로 빨개진다**(실측).
        그래서 데이터 수정은 앵커 재핀과 한 짝으로 별도 변경에서 한다.

        여기서는 결함을 **단언으로** 박아 둔다 — 주석이면 다음 사람이 지나치지만,
        단언이면 누가 seat 을 건드리는 순간 「이 행을 재판정해라」로 빨개진다.
        고쳤으면 currentWrongSeat 을 지워라. 그러면 위 test_seat_matches_sources 가
        그 행을 넘겨받는다.
        """
        for row in self.rows:
            wrong = row.get("currentWrongSeat")
            if not row.get("test") or not wrong:
                continue
            jun = row["jun"]
            with self.subTest(jun=jun):
                self.assertIn(jun, self.seat_of, f"{jun} 이 juns 에 없다")
                actual = self.cities[self.seat_of[jun]].get("nameCh")
                self.assertNotEqual(
                    row["expectedSeat"], wrong,
                    f"{jun}: currentWrongSeat 이 expectedSeat 과 같다 — 결함이 아니라 오기다")
                self.assertEqual(
                    wrong, actual,
                    f"\n{jun}: seat 이 {wrong} 에서 {actual} 로 바뀌었다."
                    f"\n  사료 治所는 {row['expectedSeat']} 다. 고친 것이면 이 행에서 "
                    f"currentWrongSeat 을 지워라 — 그러면 test_seat_matches_sources 가 받는다."
                    f"\n  다른 값으로 바꾼 것이면 사료를 다시 대라.")

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
        **縣 0 은 사료가 아니라 우리 결손이다.** 사료 근거는 U51 과 같은 줄에 있다:
        `dsfy-040.txt:349` 「漢末大亂…建安中曹公集荒郡之户以爲縣，聚之九原界，**立新興郡，領九原等縣**」.
        新興郡은 실재했고 九原縣을 거느렸다 — 이쪽이 「晉書 統縣五」보다 後漢말(220년 슬라이스)에 더 가깝다.
        縣이 붙으면 빨개진다 — 그때 값을 맞추지 말고 이 단언을 지우고 U53 을 닫아라.
        """
        members = [c["name"] for c in self.han_cities if (c.get("meta") or {}).get("jun") == "신흥군"]
        self.assertEqual(["신흥군"], members, "新興郡 소속이 변했다 — U53 을 재판정해라")


class KeySurfacesAreAmbiguous(unittest.TestCase):
    """U57 — **한글명은 유일하지 않다.** 한글명을 키로 쓰는 것이 생기면 이 숫자가 그 위험의 크기다.

    R48 형식이다: 아래 숫자는 옳아서 그 값인 게 아니라 **현재 그만큼 겹쳐 있어서** 그 값이다.
    줄어들면(정리됐으면) 빨개진다 — **그때 값을 맞추지 말고 이 단언을 지우고 U57 을 다시 판정해라.**

    지금 안전한 이유는 유일성이 지켜져서가 아니라 **키로 쓰는 표면이 아직 郡(175, 중복 0)뿐**이어서다.
    §13 이 縣을 이동 단위로 올리면 표면이 縣으로 넓어지고 그 순간 아래 숫자가 그대로 위험이 된다.
    """

    @classmethod
    def setUpClass(cls) -> None:
        cls.cities = json.loads(TILES_PATH.read_text(encoding="utf-8"))["cities"]
        cls.han_cities = json.loads(HAN_JSON_PATH.read_text(encoding="utf-8"))["cities"]

    @staticmethod
    def _collisions(names: list[str]) -> dict[str, list[int]]:
        by: dict[str, list[int]] = {}
        for i, name in enumerate(names):
            by.setdefault(name, []).append(i)
        return {k: v for k, v in by.items() if len(v) > 1}

    def test_u57_han_tiles_korean_names_collide(self) -> None:
        """han-tiles.json 1144 노드에서 한글명 **91개가 205노드**에 겹치고, **73개는 nameCh 가 실제로 다르다**.

        73 은 「표기만 다른 같은 곳」이 아니라 **서로 다른 縣이 같은 한글명을 쓰는** 건수다
        (임강현 `临江县`/`临羌县`, 경현 `京县`/`泾县`/`经县`, 신도현 `信都县`/`新都县` …).
        """
        dup = self._collisions([c["name"] for c in self.cities])
        nodes = sum(len(v) for v in dup.values())
        different = {k: v for k, v in dup.items() if len({self.cities[i].get("nameCh") for i in v}) > 1}
        self.assertEqual(91, len(dup), "한글명 충돌 이름 수가 변했다 — U57 을 재판정해라")
        self.assertEqual(205, nodes, "충돌에 걸린 노드 수가 변했다 — U57 을 재판정해라")
        self.assertEqual(
            73, len(different),
            f"nameCh 가 실제로 다른 충돌 수가 변했다 — U57 을 재판정해라: {sorted(different)}",
        )

    def test_u57_han_json_city_names_collide(self) -> None:
        """han.json 780 城에서 城名 **57개가 127노드**에 겹친다.

        `han_ownership.json` 이 城名도 키로 쓸 수 있다고 스스로 적어놨다(`_comment`).
        실측(2026-08-24) 현재 그 파일의 `cities[]` 항목은 **1건**이고 겹치는 이름은 **0건**이라 무해하다 —
        **무해 판정은 조건부다.** 조건은 `test_ownership_city_names_are_unambiguous` 가 지킨다.
        """
        dup = self._collisions([c["name"] for c in self.han_cities])
        self.assertEqual(57, len(dup), f"han.json 城名 충돌 수가 변했다 — U57 을 재판정해라: {sorted(dup)}")
        self.assertEqual(127, sum(len(v) for v in dup.values()), "han.json 城名 충돌 노드 수가 변했다")

    def test_ownership_city_names_are_unambiguous(self) -> None:
        """`han_ownership.json` 이 참조하는 城名은 han.json 780 안에서 유일해야 한다.

        **이건 R48 이 아니라 진짜 게이트다** — 위 두 개와 달리 「현재 상태 고정」이 아니라
        「이 조건이 깨지면 안 된다」를 지킨다. 겹치는 이름을 쓰면 어느 城인지 정해지지 않는다.
        """
        dup = set(self._collisions([c["name"] for c in self.han_cities]))
        payload = json.loads((ROOT / "tools/scenario/han_ownership.json").read_text(encoding="utf-8"))
        referenced: list[str] = []

        def walk(node: object) -> None:
            if isinstance(node, dict):
                for key, value in node.items():
                    if key == "cities" and isinstance(value, list):
                        referenced.extend(v for v in value if isinstance(v, str))
                    walk(value)
            elif isinstance(node, list):
                for value in node:
                    walk(value)

        walk(payload)
        bad = sorted(set(referenced) & dup)
        self.assertEqual([], bad, f"han_ownership.json 이 중복 城名을 참조한다 — 어느 城인지 정해지지 않는다: {bad}")


# U58 — 손으로 넣은 X-계열 郡 노드와 CHGIS 郡 노드가 같은 郡을 두 번 세운 쌍.
# 28건 전수 대조(2026-08-24)에서 나온 4쌍. 키는 juns 가 채택한 쪽(X-계열), 값은 판 밖에 남은 CHGIS 짝.
# 판정 축이 둘이었다 — 이름(이체자 정규화)으로 3쌍, 좌표+사료로 나머지 1쌍(汉嘉郡)이 나왔다.
XSERIES_TWINS = {
    "鉅鹿郡": "巨鹿郡",    # 讀史方輿紀要 卷014 「漢爲常山及鉅鹿郡」 / 後漢書 卷023 「薦融爲巨鹿太守」 — 이체자
    "牂牁郡": "牂柯郡",    # 讀史方輿紀要 卷070 「漢牂牁郡地」 / 元和郡縣圖志 卷30 「武帝置牂柯郡」 — 이체자
    "越巂郡": "越嶲郡",    # 後漢書 卷086 「以爲越巂郡」 / 華陽國志 卷三 「越嶲郡序」 — 이체자, 3km
    "蜀郡屬國": "汉嘉郡",  # 讀史方輿紀要 卷072 「漢延光初置蜀郡屬國，三國漢改漢嘉郡」 — 연대 다른 이름, 20km
}


class XSeriesCommanderyDuplicates(unittest.TestCase):
    """U58 — **한 郡이 지도 위에 두 번 서 있다.** 표기 문제가 아니라 동일성 문제다.

    `han-places.json` 의 `id` 가 `X` 로 시작하는 노드는 CHGIS 가 아니라 사람이 손으로 넣은
    보충 노드다(`begYr: -9999`, 한국어 `basis`). 그 28개 郡 노드를 CHGIS 105개와 전수 대조했다.

    **지금 무해한 이유는 구조가 막아줘서가 아니라 `juns` 가 옳은 쪽을 채택했기 때문이다.**
    채택이 뒤집히거나 CHGIS 짝이 판에 올라오면 조용히 틀린다 — 그래서 채택 자체를 단언으로 박는다.
    """

    @classmethod
    def setUpClass(cls) -> None:
        tiles = json.loads(TILES_PATH.read_text(encoding="utf-8"))
        cls.cities = tiles["cities"]
        cls.jun_nameCh = {j["name"]: j.get("nameCh") for j in tiles["juns"]}

    def test_u58_twins_are_off_board(self) -> None:
        """CHGIS 짝 4건은 전부 판 밖(`zhi=False`)이어야 한다. 판에 올라오면 같은 郡이 두 칸이 된다."""
        for adopted, twin in XSERIES_TWINS.items():
            with self.subTest(twin=twin):
                nodes = [c for c in self.cities if c.get("nameCh") == twin]
                self.assertEqual(1, len(nodes), f"{twin} 노드 수가 변했다 — U58 을 재판정해라")
                self.assertFalse(
                    nodes[0].get("zhi"),
                    f"{twin} 가 판에 올라왔다 — {adopted} 와 같은 郡이 두 칸이 된다(U58)",
                )

    def test_u58_jun_tiles_adopted_the_xseries_side(self) -> None:
        """**이 값은 우연히 맞은 것이다.** `juns` 175 가 4쌍 전부에서 X-계열 쪽을 채택했다.

        X-계열 쪽 seat 이 郡國志와 맞기 때문에 지금 오배정이 없다(거록군→廮陶县, 장가군→故且兰县,
        월휴군→邛都县, 촉군속국→汉嘉县). 채택이 CHGIS 쪽으로 뒤집히면 seat 이 같이 뒤집힌다.
        뒤집히면 빨개진다 — 그때 값을 맞추지 말고 이 단언을 지우고 U58 을 재판정해라.
        """
        adopted_by_tile = {v: k for k, v in self.jun_nameCh.items() if v in XSERIES_TWINS}
        self.assertEqual(
            sorted(XSERIES_TWINS), sorted(adopted_by_tile),
            f"郡 타일이 채택한 표기가 변했다 — U58 을 재판정해라: {adopted_by_tile}",
        )

    def test_u58_ownership_avoids_the_era_twin(self) -> None:
        """`좌풍익`(左馮翊, X015) 과 `풍익군`(馮翊郡, CHGIS 211473) 은 **같은 곳의 다른 시기 이름**이다.

        위 4쌍과 달리 이 쌍은 **둘 다 郡 타일이다** — 175 안에 같은 곳이 두 번 들어 있다.
        `han_ownership.json` 이 「이 표는 후한 표기인 좌풍익만 쓴다」고 `_caveats` 에 적어놨는데,
        **그건 주석이지 단언이 아니다**(R48). 규약이 깨지면 한 곳이 두 세력에 배정될 수 있다.
        """
        payload = json.loads((ROOT / "tools/scenario/han_ownership.json").read_text(encoding="utf-8"))
        used: list[str] = []

        def walk(node: object) -> None:
            if isinstance(node, dict):
                for key, value in node.items():
                    if key == "juns" and isinstance(value, list):
                        used.extend(v for v in value if isinstance(v, str))
                    walk(value)
            elif isinstance(node, list):
                for value in node:
                    walk(value)

        walk(payload)
        self.assertIn("좌풍익", used, "좌풍익이 안 쓰인다 — 규약이 바뀌었으면 _caveats 와 이 단언을 같이 고쳐라")
        self.assertNotIn(
            "풍익군", used,
            "han_ownership.json 이 풍익군을 쓴다 — 좌풍익과 같은 곳이라 한 곳이 두 번 배정된다",
        )

    def test_u59_closed_fengyi_duplicate_node_is_gone(self) -> None:
        """**U59 는 닫혔다.** `冯翊郡`(CHGIS 211473) 노드가 삭제됐다 — 값을 맞춘 게 아니라 원인을 뺐다.

        원래 단언(`test_u59_fengyi_jun_sits_on_the_jingzhao_coordinate`)은 그 노드가 `京兆尹` 과
        **같은 좌표**(108.93719/34.31799)에 있는 상태를 고정했다. CHGIS 자기 `presLoc` 이 스스로를
        부정하는 값이었고(「今陕西省大荔县」인데 좌표는 西安 북쪽), 약 103km 어긋났다.
        `.ai/plans/2026-08-24-province-state-restructure.md` §3.30.7 이 「병합이 들어오면 빨개진다.
        그때 값을 맞추지 말고 이 단언을 지우고 U59 를 닫아라」고 미리 적어둔 그 경로다.

        해소는 좌표를 지어내서가 아니라 **phantom 郡 노드 삭제**로 왔다(`f070cf36` 풍익군·북평군).
        後漢 표기 `좌풍익`(X015) 쪽만 남았다 — `test_u58_ownership_avoids_the_era_twin` 이 지키던
        「한 곳이 두 번 들어 있다」가 데이터에서 사라진 것이다.

        **여기서 새로 지키는 건 재발이다.** 노드가 다시 생기면 좌표 문제도 같이 돌아온다.
        빨개지면 값을 맞추지 말고 U59 를 다시 열어라.
        """
        fengyi = [c for c in self.cities if c.get("nameCh") in ("冯翊郡", "馮翊郡")]
        self.assertEqual([], fengyi, f"馮翊郡 郡 노드가 다시 생겼다 — U59 를 다시 열어라: {fengyi}")
        # 양성 대조 — 조회가 살아 있는지 본다. 이게 비면 위 0건은 「없다」가 아니라 조회가 죽은 것이다.
        self.assertTrue(
            [c for c in self.cities if c.get("nameCh") == "京兆尹"],
            "양성 대조(京兆尹)가 안 걸린다 — 조회 자체를 의심해라",
        )


class SelfSeatCommanderies(unittest.TestCase):
    """A 유형 — `juns` 중 seat 이 縣이 아니라 자기 자신인 것. **분류의 분모를 고정한다.**

    이 숫자는 세는 축에 따라 달라진다. §3.19 의 「66건」은 재현되지 않았다(§3.28).
    """

    @classmethod
    def setUpClass(cls) -> None:
        tiles = json.loads(TILES_PATH.read_text(encoding="utf-8"))
        cls.cities = tiles["cities"]
        cls.juns = tiles["juns"]

    def test_self_seat_count_by_kind_and_by_name_differ(self) -> None:
        """**축을 바꾸면 분모가 바뀐다.** kind 축 60, 이름 축 52 — 차이 8 은 繁簡 표기차다.

        2026-08-24 재판정: kind 축이 61 → **60**. 빠진 것은 정확히 `新成侯國` 하나이고,
        원인은 CANON_105 재판정으로 그 城 노드가 COMMANDERY → COUNTY 로 강등된 것이다
        (新成侯國은 CANON_105 에 없는 縣級 侯國이다). 이름 축은 52 그대로다 —
        이 郡의 `nameCh` 는 繁體 `新成侯國` 인데 城 쪽은 簡體 `新成侯国` 이라
        애초에 이름 축에서 안 잡히고 있었다. 그래서 차이가 9 → 8 로 줄었다.

        같은 郡인데 `nameCh` 가 繁/簡으로 갈린 9건이
        이름 축에서 샌다. **분류를 시작하기 전에 분모부터 축에 따라 흔들린다** — 그래서 둘 다 박는다.
        바뀌면 빨개진다 — 그때 값을 맞추지 말고 §3.28 분류를 다시 돌려라.
        """
        by_kind = [j for j in self.juns if self.cities[j["seat"]].get("kind") != "COUNTY"]
        by_name = [j for j in self.juns if self.cities[j["seat"]].get("nameCh") == j.get("nameCh")]
        self.assertEqual(60, len(by_kind), "A 유형(kind 축) 개수가 변했다 — §3.28 을 재판정해라")
        self.assertEqual(52, len(by_name), "A 유형(이름 축) 개수가 변했다 — 繁簡 누수가 달라졌다")

    def test_non_han_polities_have_no_seat_proposition(self) -> None:
        """A 61 중 **31 은 非漢 정치체**(`EXTERNAL_PLACE`)라 「治所」 명제가 성립하지 않는다.

        부여·사로국·야마일국·선비… 는 결손도 오류도 아니다. **분류 전에 이걸 빼야 나머지가 보인다** —
        「A = 治所 미상」으로 뭉뚱그리면 절반이 애초에 대상이 아닌 것으로 채워진다.
        """
        external = [
            j for j in self.juns
            if self.cities[j["seat"]].get("kind") == "EXTERNAL_PLACE"
        ]
        self.assertEqual(31, len(external), f"非漢 정치체 수가 변했다 — §3.28 을 재판정해라: {len(external)}")


# 邊郡·屬國의 治所 縣 이름. 값은 (사료가 말하는 治所, 출전). 전부 데이터에 **없다**(§3.29).
# 繁簡 양쪽으로 조회했고 같은 조회에 양성 대조를 넣어 조회가 살아있는 걸 확인했다.
MISSING_FRONTIER_SEATS = {
    "상군": ("膚施", "續漢書 郡國志 上郡"),
    "서하군": ("離石", "續漢書 郡國志 西河郡"),
    "정양군": ("善無", "續漢書 郡國志 定襄郡"),
    "삭방군": ("臨戎", "續漢書 郡國志 朔方郡"),
    "요동군": ("襄平", "續漢書 郡國志 遼東郡"),
    "현도군": ("高句驪", "續漢書 郡國志 玄菟郡"),
    "낙랑군": ("朝鮮", "續漢書 郡國志 樂浪郡"),
    "대방군": ("帶方", "帶方郡 치소"),
    "교지군": ("龍編", "交趾郡 치소"),
    "구진군": ("胥浦", "九真郡 치소"),
    "일남군": ("西捲", "日南郡 치소"),
    "요동속국": ("昌遼", "續漢書 郡國志 遼東屬國"),
    "구자속국": ("龜茲", "續漢書 郡國志 上郡 龜茲屬國"),
    "광위군": ("臨渭", "讀史方輿紀要 卷003 「魏廣魏郡也 … 領臨渭等縣」"),
}
# 繁 → 簡. 위 이름들이 簡體로 들어와 있어도 잡히게 한다.
_SIMPLIFY = str.maketrans({
    "膚": "肤", "離": "离", "無": "无", "臨": "临", "驪": "骊", "龍": "龙",
    "編": "编", "捲": "卷", "遼": "辽", "龜": "龟", "茲": "兹", "鮮": "鲜", "帶": "带", "蘭": "兰",
})


class FrontierSeatsAreMissing(unittest.TestCase):
    """§3.29 — 邊郡·屬國 14건의 治所 縣이 CHGIS 縣 레이어에 **없다**. (a) 결손 확정.

    **0건은 「그 표기로 0건」이지 「없다」가 아니다** — 그래서 繁簡 양쪽으로 걸고,
    같은 조회에 **반드시 걸려야 하는 양성 대조**를 넣는다. 대조가 안 걸리면 조회가 죽은 것이다.
    노드가 생기면 빨개진다 — **그때 값을 맞추지 말고 그 郡을 (b) 선택 오류로 옮겨라.**
    """

    @classmethod
    def setUpClass(cls) -> None:
        cls.cities = json.loads(TILES_PATH.read_text(encoding="utf-8"))["cities"]

    def _find(self, term: str) -> list[str]:
        forms = {term, term.translate(_SIMPLIFY)}
        return sorted({
            c["nameCh"] for c in self.cities
            if c.get("nameCh") and any(f in c["nameCh"] for f in forms)
        })

    def test_positive_control_is_alive(self) -> None:
        """양성 대조. 이게 빨개지면 아래 0건은 「없다」가 아니라 **조회가 죽었다**는 뜻이다."""
        self.assertEqual(["房陵县"], self._find("房陵"), "양성 대조가 안 걸린다 — 조회 자체를 의심해라")
        self.assertEqual(["故且兰县"], self._find("故且蘭"), "繁簡 변환 대조가 안 걸린다")

    def test_frontier_seat_counties_are_absent(self) -> None:
        for jun, (seat, source) in MISSING_FRONTIER_SEATS.items():
            with self.subTest(jun=jun):
                found = [n for n in self._find(seat) if not n.endswith(("郡", "屬國", "国", "國"))]
                self.assertEqual(
                    [], found,
                    f"{jun}: 治所 {seat}({source}) 노드가 생겼다. 결손이 해소됐으니 (b) 선택 오류로 옮겨라: {found}",
                )


class CoordinateAxisTolerance(unittest.TestCase):
    """§3.29 — **좌표 축을 「완전 동일」로 구현했더니 소수점 차이로 8건을 흘렸다.**

    郡 노드와 治所 縣 노드는 같은 점에 놓이는 게 원칙이지만 CHGIS 는 소수점 5자리에서 갈리는 쌍이 있다
    (`신흥군`↔`九原县` 0.03km, `신평군`↔`漆县` 0.02km). **0 을 조건으로 쓰면 그게 임계값이 된다.**
    이건 축을 바꿔서 고칠 문제가 아니라 게이트 범위가 좁았던 것이다(§0.7) — 그래서 넓히고, 넓힌 값을 박는다.
    2km 와 5km 가 같은 10 이라 **평탄면에서 끊었다** — 「개수를 통과 조건으로」가 아니라 「뽑고 나서 셌다」.
    """

    @classmethod
    def setUpClass(cls) -> None:
        tiles = json.loads(TILES_PATH.read_text(encoding="utf-8"))
        cls.cities = tiles["cities"]
        cls.juns = tiles["juns"]

    def _candidates(self, tol_km: float) -> list[str]:
        counties = [c for c in self.cities if c.get("kind") == "COUNTY" and c.get("lon") is not None]
        out = []
        for jun in self.juns:
            seat = self.cities[jun["seat"]]
            if seat.get("kind") in ("COUNTY", "EXTERNAL_PLACE") or seat.get("lon") is None:
                continue
            if any(
                math.hypot((seat["lon"] - c["lon"]) * 88, (seat["lat"] - c["lat"]) * 111) <= tol_km
                for c in counties
            ):
                out.append(jun["name"])
        return sorted(out)

    def test_candidate_count_plateaus(self) -> None:
        counts = {tol: len(self._candidates(tol)) for tol in (1.0, 2.0, 5.0)}
        self.assertEqual(
            {1.0: 8, 2.0: 10, 5.0: 10}, counts,
            f"좌표 축 후보 수가 변했다 — §3.29 분류를 다시 돌려라: {counts}",
        )


if __name__ == "__main__":
    unittest.main()
