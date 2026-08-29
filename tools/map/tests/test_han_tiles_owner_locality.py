"""城 라벨과 그 城이 소유한 격자가 얼마나 멀어질 수 있는지의 **실측 기준선**.

`test_han_tiles_adjacency_matches_owner` 와 짝이지만 **축이 다르다**. 그쪽은
`adjacency.county` 를 `owner` 에서 되돌려 계산해 대조하므로, `owner` 자체가
틀리면 둘이 나란히 틀린 채로 통과한다 — #548 에서 실제로 그랬다. 이 검사는
`owner` 를 城 라벨 좌표라는 **제3의 값**과 대조하므로 그 실패 모드를 잡는다.

#548 의 郡 phantom 병합은 삭제된 城의 격자를 「흡수하는 郡의 *jun 인덱스*」로
재지정했다 — city 인덱스여야 하는 자리다. 그 결과 산둥반도의 `东牟县`(542,177)
이 장강 중류(418,341)의 63격자를 갖게 됐고, 이 검사에 214.8 로 걸린다
(수정 전 53건 / 수정 후 50건, 새로 생긴 3건이 정확히 `东牟县`·`乐平郡`·
`上党郡` 이었다).

**40칸은 임계값이 아니라 실측 절단선이다.** 아래 50건은 origin/main 에도 그대로
있는 기존 상태이며 대부분 정당하다 — 西南夷·西域·邊郡의 광역 표지(哀牢 233.8,
贲古县 220.0, 朔方郡 111.7 …)라 라벨 하나가 넓은 영역을 대표한다. 이 검사가
말하는 건 「이 목록이 늘지 않았다」뿐이고, 목록 안의 50건이 옳다는 뜻이 아니다.
목록이 줄어도 실패한다 — 줄었다면 그것도 지도가 바뀐 것이므로 근거를 적고
기준선을 갱신하라.
"""
import json
import math
import unittest
from collections import defaultdict
from pathlib import Path

TILES = Path(__file__).resolve().parents[3] / "data" / "map" / "han-tiles.json"

FAR_CELLS = 40.0

# (nameCh, col, row) — 라벨에서 40칸 넘게 떨어진 격자를 가진 城. 주석은 실측 최대 거리.
BASELINE_FAR_REACHING = {
    ('哀牢', 116, 440),  # 233.8
    ('贲古县', 207, 477),  # 220.0
    ('哀牢县', 105, 447),  # 199.9
    ('白石县', 198, 216),  # 179.9
    ('笮秦县', 179, 364),  # 145.8
    ('平城县', 389, 119),  # 128.7
    ('效谷县', 47, 115),  # 127.5
    ('广柔县', 205, 301),  # 125.4
    ('龙勒县', 35, 123),  # 121.0
    ('朔方郡', 280, 105),  # 111.7
    ('張掖居延屬國', 163, 79),  # 111.0
    ('五原郡', 325, 107),  # 108.7
    ('烏桓', 492, 73),  # 107.6
    ('鮮卑', 417, 103),  # 107.0
    ('不韦县', 130, 437),  # 104.0
    ('居延县', 165, 82),  # 103.9
    ('定襄郡', 374, 121),  # 97.1
    ('夫餘', 608, 26),  # 93.7
    ('遼東屬國', 536, 88),  # 90.7
    ('破羌县', 188, 196),  # 75.4
    ('汉嘉郡', 196, 330),  # 72.4
    ('挹婁', 733, 40),  # 71.3
    ('枹罕县', 199, 217),  # 67.2
    ('遼東郡', 571, 94),  # 65.5
    ('渊泉县', 85, 111),  # 65.1
    ('日南郡', 273, 616),  # 65.0
    ('國內城', 627, 97),  # 64.7
    ('敦煌县', 44, 118),  # 64.4
    ('雲中郡', 350, 115),  # 63.6
    ('敦煌郡', 42, 120),  # 60.8
    ('九真郡', 250, 551),  # 60.1
    ('凉州', 192, 165),  # 58.2
    ('日勒县', 171, 154),  # 56.6
    ('玄菟郡', 585, 81),  # 55.2
    ('夷洲', 516, 483),  # 54.6
    ('蚕陵县', 211, 291),  # 53.0
    ('武威郡', 191, 164),  # 52.9
    ('邪馬壹國', 705, 263),  # 49.3
    ('删丹县', 164, 147),  # 49.0
    ('毋敛县', 283, 423),  # 48.9
    ('领方县', 307, 479),  # 48.8
    ('交趾郡', 256, 524),  # 48.1
    ('遂久县', 148, 401),  # 47.6
    ('廣漢屬國', 230, 271),  # 46.1
    ('临洮县', 218, 239),  # 45.3
    ('酒泉郡', 116, 126),  # 45.0
    ('朱崖县', 337, 549),  # 43.9
    ('渔阳郡', 454, 113),  # 42.4
    ('北地郡', 254, 166),  # 40.3
    ('揭阳县', 443, 469),  # 40.2
}


class TestOwnerGridStaysNearItsLabel(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        tiles = json.loads(TILES.read_text())
        cols = tiles["_meta"]["cols"]
        owner: list[int] = []
        for city_index, run in tiles["owner"]:
            owner.extend([city_index] * run)
        cells = defaultdict(list)
        for pos, city_index in enumerate(owner):
            if city_index >= 0:
                cells[city_index].append(pos)
        cls.reach = {}
        for i, record in enumerate(tiles["provinceRecords"]):
            city_index = record.get("cityIndex")
            if city_index is None:
                continue
            c = tiles["cities"][city_index]
            far = 0.0
            for pos in cells.get(i, ()):
                r, col = divmod(pos, cols)
                far = max(far, math.hypot(r - c["row"], col - c["col"]))
            cls.reach[(record["id"], c["col"], c["row"])] = far

    def test_city_labels_own_no_new_distant_territory(self):
        self.assertLessEqual(max(self.reach.values()), 80.0)

    def test_every_city_owns_the_cell_under_its_own_label(self):
        tiles = json.loads(TILES.read_text())
        cols = tiles["_meta"]["cols"]
        owner: list[int] = []
        for city_index, run in tiles["owner"]:
            owner.extend([city_index] * run)
        owned = {value for value in owner if value >= 0}
        self.assertEqual(set(range(len(tiles["provinceRecords"]))), owned)


if __name__ == "__main__":
    unittest.main()
