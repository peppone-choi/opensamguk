#!/usr/bin/env python3
"""郡 기반 한나라 지도를 게임 월드 파일 두 개로 굽는다.

입력(모두 저장소 안의 실재 파일이다 — 지어낸 수치는 하나도 없다):
  data/map/han-tiles.json      175 郡(juns) · 1144 점(cities, `zhi`=郡國志에 실린 縣)
                               + 인접(adjacency.county/commandery) + seatOwner(郡 소유 격자)
  data/unitset/units.json      han 병종의 ReqRegions/ForbidRegions 게이트 키 원장
  data/map/junguozhi.json      續漢書 郡國志 106 郡의 戶(households)
  tools/map/build_junguozhi.py CANON_105 — 저장소 유일의 郡→州 출처(州 이름은 뒤 주석)

출력:
  infra/src/main/resources/map/han.json                              (che.json 과 동일 스키마)
  common/src/main/kotlin/opensamguk/common/constants/HanCityConst.kt (생성물, 손편집 금지)
  common/src/main/kotlin/opensamguk/common/constants/HanGateIndex.kt (생성물, 손편집 금지)
    — 城 id → 그 城이 가진 게이트 키(漢字) 집합. han 병종의 ReqRegions/ForbidRegions 해석에 쓴다.

    python3 tools/scenario/build_han_world.py
    python3 tools/scenario/build_han_world.py --check   # 손편집 드리프트 검사
    python3 tools/scenario/build_han_world.py --check-gate  # 현재 han.json ID 기준 게이트 검사 (CI)

--- 정한 규칙 (전부 파일에서 유도했다) -----------------------------------------
level  · EXTERNAL_PLACE 治所 = '이'(4). che 가 남만·산월·오환을 그렇게 두는 것과 같다.
       · 그 외 郡國志 戶가 있는 郡 = 戶 백분위로 '소'(5)/'중'(6)/'대'(7)/'특'(8).
         che 의 1~3('수'/'진'/'관')은 관문·요새 전용이라 郡에는 대응물이 없어 쓰지 않는다.
       · 郡國志에 없는 郡(曹魏 신설·屬國·판도 밖) = '소'(5). 戶가 없으니 최소 등급으로
         두고 지어낸 戶를 만들지 않는다.
max    · che.json 의 같은 level 도시들의 항목별 중앙값. che 를 그대로 베끼는 셈이라
         밸런스 기준이 실재 파일에서 온다. RawCity 쪽은 이 값의 1/100(generateCities 가 ×100).
initial· CityConst.buildInit[level] 그대로. che.json 이 level 별로 정확히 그 값을 쓴다(검증됨).
x,y    · col,row 를 균일 배율로 축소(768→700, 669→610). che 의 폭 700 을 그대로 쓰고
         높이는 격자 비율을 지켜 왜곡을 없앤다. MapViewer 는 width/height 박스를 캔버스
         폭에 맞춰 균일 확대할 뿐이라 박스 비율만 맞으면 된다.
region · 州 인덱스. CANON_105 에 있는 106 郡은 그대로, 나머지는 col,row 가 가장 가까운
         '州가 정해진 郡'의 州를 따른다. 이민족 거점도 마찬가지로 가까운 州에 붙고,
         東夷傳 권역(동이)만 州와 같은 층위의 지역으로 따로 둔다.
name   · 縣급 이름에서 끝의 '현'·'후국'·'국'·'읍'·'도'를 뗀 것. 충돌하면 소속 郡을 접두로 붙인다.
"""

from __future__ import annotations

import argparse
import json
import re
import statistics
import sys
from collections import Counter, defaultdict, deque
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
try:
    from tools.map.build_tile_grid import CANONICAL_PLACE_NAME_NORMALIZATIONS
except ModuleNotFoundError:  # pragma: no cover - direct script compatibility
    sys.path.insert(0, str(ROOT))
    from tools.map.build_tile_grid import CANONICAL_PLACE_NAME_NORMALIZATIONS

TILES = ROOT / "data" / "map" / "han-tiles.json"
JUNGUOZHI = ROOT / "data" / "map" / "junguozhi.json"
CANON_SRC = ROOT / "tools" / "map" / "build_junguozhi.py"
CHE = ROOT / "infra" / "src" / "main" / "resources" / "map" / "che.json"
UNITS = ROOT / "data" / "unitset" / "units.json"

OUT_JSON = ROOT / "infra" / "src" / "main" / "resources" / "map" / "han.json"
OUT_KT = ROOT / "common" / "src" / "main" / "kotlin" / "opensamguk" / "common" / "constants" / "HanCityConst.kt"
OUT_GATE = ROOT / "common" / "src" / "main" / "kotlin" / "opensamguk" / "common" / "constants" / "HanGateIndex.kt"

WIDTH = 700           # che.json 의 표시 폭을 그대로 쓴다.
HEIGHT = 610          # 700 * 669/768 반올림 — 격자 비율 유지.

# CANON_105 주석의 州 한자 → 한글. 州 이름 자체는 사료 고유명사라 표로 둔다.
JU_KR = {
    "司隸": "사예", "豫州": "예주", "冀州": "기주", "兗州": "연주", "徐州": "서주",
    "青州": "청주", "荊州": "형주", "揚州": "양주", "益州": "익주", "涼州": "량주",
    "并州": "병주", "幽州": "유주", "交州": "교주",
}
# 東夷傳 권역. 부여·고구려(국내성·졸본)는 만주라 '한반도'로 묶을 수 없어 傳 이름을 쓴다.
DONGYI = "동이"      # 부여·고구려·옥저·예·삼한·주호·왜 — 『三國志』魏書 東夷傳이 묶은 그대로
# 이민족 거점(오환·선비·흉노·강·저·애뢰·산월)에는 따로 지역을 두지 않는다(2026-08-19 사용자
# 지시). 다른 州 밖 郡과 똑같이 가장 가까운 州로 흡수시킨다 — 오환은 유주, 강은 량주 하는 식.

# 한반도·왜·부여·이주·유구는 '이민족'(level '이')에서 뺀다(2026-08-19 사용자 지시). 州와 같은 층위의
# 자기 권역으로 떼고, 등급은 다른 郡과 똑같이 戶數 사분위로 매긴다.
# 戶數는 전부 『三國志』魏書 東夷傳/韓傳 원문 수치다. 원문에 수치가 없는 곳은 None 으로 두어
# 최저 등급('소')으로 떨어뜨린다 — 없는 숫자를 지어내지 않는다.
# region 이 None 이면 지역을 따로 두지 않고 가까운 州에 흡수시킨다(등급만 戶數로 매긴다).
FRONTIER: dict[str, tuple[str | None, int | None, str]] = {
    "國內城": (DONGYI, 30000, "東夷傳 高句麗 「戶三萬」"),
    "卒本":   (DONGYI, None,  "東夷傳에 卒本 자체의 戶數는 없다 — 高句麗 옛 도읍"),
    "東沃沮": (DONGYI, 5000,  "東夷傳 東沃沮 「戶五千」"),
    "北沃沮": (DONGYI, None,  "東夷傳 北沃沮에 戶數 기록 없음"),
    "濊":     (DONGYI, 20000, "東夷傳 濊 「戶二萬」"),
    "州胡":   (DONGYI, None,  "韓傳 州胡에 戶數 기록 없음"),
    # 마한 — 韓傳 「大國萬餘家，小國數千家」. 目支國은 「辰王治月支國」이라 大國으로 본다.
    "目支國":   (DONGYI, 10000, "韓傳 마한 大國 「萬餘家」 · 「辰王治月支國」"),
    "伯濟國":   (DONGYI, 3000,  "韓傳 마한 小國 「數千家」"),
    "辟卑離國": (DONGYI, 3000,  "韓傳 마한 小國 「數千家」"),
    # 진한·변한 — 韓傳 「弁辰韓合二十四國 … 大國四五千家，小國六七百家」.
    "斯盧國":     (DONGYI, 4500, "韓傳 변진한 大國 「四五千家」 · 진한의 중심국"),
    "狗邪國":     (DONGYI, 4500, "韓傳 변진한 大國 「四五千家」 · 「弁辰狗邪國」"),
    "安邪國":     (DONGYI, 4500, "韓傳 변진한 大國 「四五千家」 · 「弁辰安邪國」"),
    "悉直國":     (DONGYI, 650,  "韓傳 변진한 小國 「六七百家」"),
    "押督國":     (DONGYI, 650,  "韓傳 변진한 小國 「六七百家」"),
    "召文國":     (DONGYI, 650,  "韓傳 변진한 小國 「六七百家」"),
    "于山國":     (DONGYI, None, "『삼국사기』 신라본기 于山國에 戶數 기록 없음"),
    "古資彌凍國": (DONGYI, 650,  "韓傳 변진한 小國 「六七百家」"),
    "大伽耶":     (DONGYI, 650,  "韓傳 변진한 小國 「六七百家」"),
    "星山伽耶":   (DONGYI, 650,  "韓傳 변진한 小國 「六七百家」"),
    "古寧伽耶":   (DONGYI, 650,  "韓傳 변진한 小國 「六七百家」"),
    "夫餘": (DONGYI, 80000, "東夷傳 夫餘 「戶八萬」"),
    "邪馬壹國": (DONGYI, 70000, "東夷傳 倭 「邪馬壹國 … 可七萬餘戶」"),
    # 夷洲(대만)·流求(류큐) — 둘 다 바다 건너지만 사료가 揚州에서 건너간 곳으로 적는다.
    # 좌표만 보면 夷洲가 交州에 더 가깝지만, 사료를 따라 둘 다 揚州에 붙인다.
    # 『吳志』孫權傳 「遣衛溫諸葛直 … 浮海求夷洲」(臨海郡 = 揚州), 『隋書』流求國傳 「當建安郡東」
    # (建安郡 = 揚州). 지역은 따로 두지 않고 가까운 州(양주)에 붙인다.
    # 어느 쪽도 戶數 기록이 없어 최저 등급으로 둔다.
    "夷洲": ("양주", None, "吳志 孫權傳 「浮海求夷洲」 — 戶數 기록 없음"),
    "流求": ("양주", None, "隋書 流求國傳 「當建安郡東」 — 戶數 기록 없음"),
}

# 郡급 사다리(이·소·중·대·특·경)와 縣급 사다리(영현·장현)는 **별개**다. 여기 붙는 숫자
# id 는 크기 순서가 아니다 — 10·11 이 9(경)보다 크지만 縣은 京師보다 작다. 숫자를 크기로
# 비교하는 자리는 정복 가능(>3)·건국 후보(5~6) 판정뿐이고, 縣이 건국 후보에서 빠지는 건
# 오히려 맞다. 표시·밸런스는 전부 라벨로 간다.
LEVELS = ["수", "진", "관", "이", "소", "중", "대", "특", "경", "영현", "장현"]

# 京師. 後漢書 光武帝紀 「定都洛陽」, 前漢의 京師 長安은 後漢에서도 西京으로 남아
# 兩京으로 불렸다. 戶數가 아니라 지위로 정해지는 자리라 이 두 곳만 따로 뽑는다.
CAPITALS = {
    "河南尹": "洛陽 — 後漢의 京師",
    "京兆尹": "長安 — 前漢의 京師이자 後漢의 西京",
}
LEVEL_ID = {name: i + 1 for i, name in enumerate(LEVELS)}
HOUSEHOLD_LEVELS = ["소", "중", "대", "특"]

# CityConst.buildInit (common/.../CityConst.kt:223-232) 을 그대로 옮긴 것.
# che.json 의 level 별 initial 이 이 값과 정확히 일치함을 확인하고 베꼈다.
BUILD_INIT = {
    "수": (5000, 100, 100, 100, 500, 500),
    "진": (5000, 100, 100, 100, 500, 500),
    "관": (10000, 100, 100, 100, 1000, 1000),
    "이": (50000, 1000, 1000, 1000, 1000, 1000),
    "소": (100000, 1000, 1000, 1000, 2000, 2000),
    "중": (100000, 1000, 1000, 1000, 3000, 3000),
    "대": (150000, 1000, 1000, 1000, 4000, 4000),
    "특": (150000, 1000, 1000, 1000, 5000, 5000),
    # '경'은 che 에 없다. che 계단(def/wall +1000, pop 100k→150k)을 한 칸 더 이은 밸런스값이고,
    # CityConstRegistry.HanCityConstVariant.hanBuildInit 과 같은 값이어야 한다.
    "경": (200000, 1000, 1000, 1000, 6000, 6000),
    # 縣 두 등급. 續漢書 百官志 「萬戶以上為令，不滿為長」의 1만 戶 경계를 그대로 쓴다.
    # pop 은 戶를 사람 수로 옮긴 것 — 郡國志의 戶·口 비율 중앙값이 4.98 이라 1호 ≈ 5인이다.
    # 令縣 1만 戶 × 5 = 5만, 長縣은 그 절반 아래로 잡은 2만(이쪽은 밸런스값이다).
    # def/wall 은 che 계단에 없는 자리라 밸런스값이고,
    # CityConstRegistry.HanCityConstVariant.hanBuildInit 과 같은 값이어야 한다.
    "영현": (50000, 1000, 1000, 1000, 1500, 1500),
    "장현": (20000, 500, 500, 500, 1000, 1000),
}

# 續漢書 百官志 「萬戶以上為令，不滿為長」 — 縣 등급을 가르는 유일한 사료 경계.
LING_HOUSEHOLDS = 10000
STAT_KEYS = ("population", "agriculture", "commerce", "security", "defence", "wall")

# 縣 인접은 육지 보로노이라 섬 郡治는 연결이 0개로 나온다. 그 상태면 이동·출병이 전부
# 인접 기반이라 도달 경로 자체가 없고, checkEmperior 가 「전 城 소유」를 요구하므로
# 천하통일이 불가능해진다(측정: 780성 중 5성 고립).
#
# 그렇다고 섬을 가까운 육지에 기계적으로 붙이지 않는다. 사서가 항로를 적어 둔 쌍만 잇고,
# 근거가 없는 것은 UNKNOWN 으로 명시한 채 최근접 육지 治所에 붙인다 — 지어낸 근거로
# 채우지 않는다. 인용은 data/corpus 색인에서 직접 뽑은 원문이다.
SEA_LINKS: list[tuple[str, str, str]] = [
    ("夷洲", "會稽郡",
     "讀史方輿紀要 卷94 인용 後漢書 東夷傳 「會稽海外有夷洲、亶洲」 + "
     "三國志 吳書 孫權傳 黃龍二年 「遣將軍衞溫、諸葛直將甲士萬人，浮海求夷洲及亶洲」"),
    ("流求", "會稽郡",
     "隋書 卷81 東夷 流求國 「流求國，居海島之中，當建安郡東，水行五日而至」 — "
     "建安郡은 260년 會稽에서 분치됐으므로 220년 시점의 관할은 會稽郡이다"),
    ("州胡", "辟卑離國",
     "三國志 魏書30 韓 「又有州胡在馬韓之西海中大島上 … 乘船往來，巿買韓中」 — "
     "지도에 馬韓 자체는 없고 馬韓 소국만 있어 그중 최근접(257km)인 辟卑離國에 붙인다"),
    ("邪馬壹國", "狗邪國",
     "三國志 魏書30 倭人 「從郡至倭，循海岸水行，歷韓國 … 到其北岸狗邪韓國，七千餘里，"
     "始渡一海，千餘里至對馬國」 — 도해 기점이 狗邪韓國이다"),
    ("于山國", "悉直國",
     "UNKNOWN — 색인된 사서(三國志·後漢書·晉書·隋書·資治通鑑·讀史方輿紀要 등)에 "
     "于山國·鬱陵 용례 0건이다(三國史記 미수록). 근거 없이 최근접 육지 治所(152km)에 붙인다"),
]


def canon_ju() -> dict[str, str]:
    """build_junguozhi.py 의 CANON_105 를 파싱해 郡→州(한자) 를 만든다.

    소스를 AST 로 읽어 문자열 조각과 그 줄의 뒤 주석(州 이름)을 짝짓는다. 외우지 않는다.
    """
    src = CANON_SRC.read_text(encoding="utf-8").splitlines()
    start = next(i for i, l in enumerate(src) if l.startswith("CANON_105"))
    end = next(i for i in range(start, len(src)) if src[i].startswith(")."))

    out: dict[str, str] = {}
    pending: list[str] = []
    for line in src[start:end + 1]:
        body = re.findall(r"'([^']*)'", line)          # 그 줄의 문자열 조각
        comment = re.search(r"#\s*([\u4e00-\u9fff]{2,3})\s*$", line)   # 그 줄의 州 주석
        for frag in body:
            pending.extend(frag.split())
        if comment:                                    # 주석이 달린 줄에서 州가 확정된다.
            for jun in pending:
                out[jun] = comment.group(1)
            pending = []
    if pending:
        sys.exit(f"CANON_105 마지막 {len(pending)}개 郡의 州 주석을 못 찾았다: {pending}")
    return out


def level_thresholds(households: list[int]) -> list[int]:
    """戶 백분위 경계 3개 — '소'/'중'/'대'/'특' 를 가른다.

    사분위로 자르면 네 등급이 같은 수가 되어 大·特이 통째로 넘쳐난다. 대신 che 의 등급
    분포(소28·중18·대9·특7)를 목표 모양으로 삼아 그 누적 비율에서 자른다. max 값도 che
    의 등급별 중앙값을 쓰고 있으니 밸런스 기준을 che 하나로 통일하는 셈이다.
    """
    xs = sorted(households)
    out = []
    for frac in che_level_shares():
        i = frac * (len(xs) - 1)
        lo = int(i)
        hi = min(lo + 1, len(xs) - 1)
        out.append(round(xs[lo] + (xs[hi] - xs[lo]) * (i - lo)))
    return out


def che_level_shares() -> list[float]:
    """che.json 의 소/중/대/특 누적 비율 3개(소 끝, 중 끝, 대 끝)."""
    che = json.loads(CHE.read_text(encoding="utf-8"))
    n = [sum(1 for c in che["cities"] if c["level"] == LEVEL_ID[lv]) for lv in HOUSEHOLD_LEVELS]
    total = sum(n)
    return [sum(n[:k]) / total for k in (1, 2, 3)]


def che_max_by_level() -> dict[str, dict[str, int]]:
    """che.json 의 level 별 max 중앙값. 이 지도의 밸런스 기준값이다."""
    che = json.loads(CHE.read_text(encoding="utf-8"))
    by = defaultdict(list)
    for c in che["cities"]:
        by[c["level"]].append(c)
    out = {}
    for lv, group in by.items():
        out[LEVELS[lv - 1]] = {
            k: int(statistics.median(c["max"][k] for c in group)) for k in STAT_KEYS
        }
    # '경'은 che 에 없다. 특-대 증가분을 특에 한 번 더 얹어 계단을 잇는다(밸런스값).
    out["경"] = {k: out["특"][k] + (out["특"][k] - out["대"][k]) for k in STAT_KEYS}
    # 縣 두 등급도 che 에 대응물이 없다. 郡治 최소 등급('소')에 비례로 깎은 밸런스값이다.
    out["영현"] = {k: int(out["소"][k] * 0.5) for k in STAT_KEYS}
    out["장현"] = {k: int(out["소"][k] * 0.2) for k in STAT_KEYS}
    return out


# --- 게이트 키 별칭 표 -------------------------------------------------------
# units.json(han 병종)의 ReqRegions/ForbidRegions 키는 지도(han-tiles.json)의 표기와
# 다르다. 아래 표가 그 대응의 전부이며, 표에 없는 키는 매칭 실패로 stderr 에 보고한다.
# 값은 지도의 郡 nameCh 또는 城 nameCh 이고, 城 이름은 seatOwner 격자로 소속 郡을 푼다.
# 州 키(幽州·涼州·并州·益州·揚州·青州·冀州)는 이 표가 아니라 위 州 배정 결과에서 온다.
GATE_PLACES: dict[str, list[str]] = {
    # 郡·縣 — 표기만 다르거나 縣이라 郡 목록에 안 잡히는 것들.
    "漢中": ["漢中郡"],
    "河東": ["河東郡"],
    "巴西": ["巴西郡"],
    "日南": ["日南郡"],
    "丹楊": ["丹陽郡"],          # units.json 은 楊, 지도는 陽. 같은 丹陽郡(治 宛陵)이다.
    "雒陽": ["雒阳县"],          # 河南尹 治所. 지도 城 이름은 간체 표기.
    "秭歸": ["秭归县"],          # 南郡 소속 縣(治所 아님) — seatOwner 격자로 南郡에 붙는다.
    "巴": ["巴郡", "巴西郡", "宕渠郡"],   # 巴人의 땅 = 巴郡에서 갈라진 세 郡.
    # 益州 남부 = 南中. 지도에 牂牁/牂柯, 越巂/越嶲 두 표기가 다 있어 둘 다 넣는다.
    "南中": ["益州郡", "永昌郡", "牂牁郡", "牂柯郡", "越巂郡", "越嶲郡", "犍為屬國", "哀牢"],
    # 南中 郡 자체를 요구하는 郡+부족 병종(#529)이 써야 하는 郡 키 — units.json 이
    # "南中" 대신 개별 郡 이름을 쓰므로 "南中"과 별도로 郡별 키를 둔다.
    "武陵": ["武陵郡"],
    "牂牁": ["牂牁郡", "牂柯郡"],
    "越巂": ["越巂郡", "越嶲郡"],
    "永昌": ["永昌郡"],
    "鬱林": ["鬱林郡"],
    # 부족·외부 세력.
    "烏桓": ["烏桓"],
    "烏丸": ["烏桓"],            # 같은 부족의 이표기(三國志 烏丸 / 後漢書 烏桓). 지도 거점은 烏桓.
    "鮮卑": ["鮮卑"],
    "山越": ["山越"],
    "匈奴": ["南匈奴"],          # 지도 거점 이름이 南匈奴(후한에 내부한 남흉노).
    "屠各": ["南匈奴", "西河郡"],  # 休屠各은 흉노 별종으로 并州 西河 일대에 살았다.
    # 羌 자체는 지도 거점 西羌(涼州) 하나뿐이지만 益州 益州(越巂)에도 羌人이 있었다
    # (三國志 蜀書一 劉二牧傳「焉出青羌與戰」— 益州牧 劉焉이 부린 青羌도 이 羌이다).
    # 아래 青羌 항목 그대로 越巂郡을 더한다.
    "羌": ["西羌", "越巂郡", "越嶲郡"],
    "青羌": ["越巂郡", "越嶲郡"],  # 青羌은 越巂의 羌人 — 무당비군의 출신지.
    "胡": ["金城郡"],            # 湟中義從胡의 근거지 湟中이 金城郡이다.
    # 賨人(板楯蠻)의 본거지가 巴郡 宕渠縣, 뒤에 宕渠郡. 華陽國志 卷九 李特志
    # 「賨人敬信…値天下大亂，自巴西之宕[渠]」— 巴西에서 宕渠로 옮겨간 것이니 巴西도 근거지다.
    "賨": ["宕渠郡", "巴郡", "巴西郡"],
    "叟": ["益州郡", "越巂郡", "越嶲郡", "永昌郡"],   # 叟兵은 南中 이민족 병력이다.
    # 蠻 거점은 지도에 哀牢 하나뿐이지만 사료에 蠻이 직접 붙는 郡이 셋 더 있다.
    # 後漢書 卷003 孝章帝紀「永昌哀牢夷叛。…武陵郡兵討叛蠻，破…」(武陵蠻),
    # 卷004 孝和孝殤帝紀「日南象林蠻夷反」({{*|象林，縣，屬日南郡}} — 象林이 日南郡 소속 縣임을
    # 주석이 명시), 卷007 孝桓帝紀「日南蠻賊率衆詣郡降」, 卷086 南蠻西南夷列傳「日南蠻夷千餘人
    # 復攻燒縣邑」(日南蠻 셋 다 사서 고유명이고 대상 縣이 日南郡 소속이라 郡 자체를 태깅한다),
    # 卷008 孝靈帝紀「鬱林烏滸民相率內屬」(烏滸도 卷086이 「今烏滸人是也」로 蠻과 같이 묶는다).
    "蠻": ["哀牢", "武陵郡", "鬱林郡", "日南郡"],
    # 夷 자체는 지도에 대응 거점이 없다. 資治通鑑 卷070「牂柯太守朱褒、越巂夷王高定皆叛」
    # (牂柯의 夷)과 後漢書 卷003「永昌哀牢夷叛」(永昌의 夷)를 근거로 南中 두 郡에 둔다.
    "夷": ["牂牁郡", "牂柯郡", "永昌郡"],
    # 高句麗 도읍(國內城·卒本)은 幽州 밖(동이)이지만, 高句驪는 원래 幽州 玄菟郡의 縣이었다
    # (漢書 卷028 地理志「玄菟郡…縣三：高句驪，上殷台，西蓋馬」). 幽州 쪽 근거로 玄菟郡을 더한다.
    "高句麗": ["國內城", "卒本", "玄菟郡"],
    "沃沮": ["東沃沮", "北沃沮"],
    "濊": ["濊"],
    "夫餘": ["夫餘"],
    "挹婁": ["挹婁"],            # 治所가 아닌 城 — seatOwner 격자로 北沃沮에 붙는다.
    "馬韓": ["目支國", "伯濟國", "辟卑離國"],   # 마한 54국 중 지도에 있는 셋(맹주는 目支國).
    "弁韓": ["狗邪國", "安邪國", "古資彌凍國", "大伽耶", "星山伽耶", "古寧伽耶"],
    "辰韓": ["斯盧國", "悉直國", "押督國", "召文國"],
    "邪馬壹國": ["邪馬壹國"],
    "奴國": ["奴國"],            # 治所가 아닌 城 — seatOwner 격자로 邪馬壹國에 붙는다.
    # 投馬國은 지도에 대응 거점이 없다 — 표에 두지 않고 미매칭으로 보고한다.
}
KR_JU = {v: k for k, v in JU_KR.items()}


def unit_gate_keys() -> list[str]:
    """units.json 의 han 병종이 실제로 쓰는 게이트 키 전부(등장 순서)."""
    doc = json.loads(UNITS.read_text(encoding="utf-8"))
    out: list[str] = []
    for crew in doc["crewTypes"]:
        if crew.get("set") != "han":
            continue
        for c in crew.get("reqConstraints", []):
            for k in list(c.get("reqRegions", [])) + list(c.get("forbidRegions", [])):
                if k not in out:
                    out.append(k)
    return out


def seat_owner_grid(tiles: dict) -> list[int]:
    """seatOwner 런렝스를 펼친 郡 소유 격자(row-major). -1 = 바다."""
    grid: list[int] = []
    for place, count in tiles["seatOwner"]:
        grid.extend([place] * count)
    return grid


def build_seat_of(juns: list[dict]) -> dict[int, int]:
    """城 인덱스(治所) -> 郡 인덱스. 두 郡이 같은 seat 城을 가리키면 dict 구성이
    last-write-wins로 조용히 하나를 지운다(크래시도 없고 개수도 안 틀린다) — 신흥군을
    오원군과 같은 city로 잘못 재지정했을 때 오원군이 통째로 사라진 것도 이 경로였다.
    같은 실수를 다시 조용히 삼키지 않도록 여기서 시끄럽게 죽는다.
    """
    seat_of = {j["seat"]: i for i, j in enumerate(juns)}
    if len(seat_of) != len(juns):
        seat_counts = Counter(j["seat"] for j in juns)
        dup = {s: [j["name"] for j in juns if j["seat"] == s] for s, c in seat_counts.items() if c > 1}
        raise AssertionError(f"중복 seat: {dup}")
    return seat_of


def stable_city_sort_name(city: dict) -> str:
    """런타임 city id 정렬에 쓰는 변경 불가 원천 표기.

    플레이어에게 보이는 ``nameCh``는 검토된 정규명으로 고치되, 이미 배포된 city id는
    그 수정 전 CHGIS 원천 표기의 정렬 순서를 유지한다. 물리 place id가 없는 항목이나
    정규화 대상이 아닌 항목은 현재 표기를 그대로 쓴다.
    """
    normalization = CANONICAL_PLACE_NAME_NORMALIZATIONS.get(str(city.get("id")))
    return normalization["sourceNameCh"] if normalization else city["nameCh"]


def gate_index(tiles: dict, region_of: list[str]) -> tuple[dict[int, list[str]], list[str]]:
    """**郡 인덱스** → 게이트 키 집합, 그리고 지도에서 못 찾은 게이트 키 목록.

    키는 郡 단위로 붙는다. 그 郡의 治所든 縣이든 같은 州·같은 부족 접경이므로,
    build() 가 이 표를 그 郡에 속한 城 전부에 그대로 뿌린다.
    """
    juns, cities = tiles["juns"], tiles["cities"]
    cols = tiles["_meta"]["cols"]
    grid = seat_owner_grid(tiles)

    # 지도 이름 → 郡 인덱스들. 郡 이름이 우선, 그다음 城 이름(소속 郡을 격자로 푼다).
    place_to_juns: dict[str, set[int]] = defaultdict(set)
    for i, j in enumerate(juns):
        place_to_juns[j["nameCh"]].add(i)
    seat_of = build_seat_of(juns)
    for ci, c in enumerate(cities):
        if ci in seat_of:                      # 治所는 그 郡에 확정으로 붙인다.
            place_to_juns[c["nameCh"]].add(seat_of[ci])
            continue
        cell = c["row"] * cols + c["col"]
        owner = grid[cell] if 0 <= cell < len(grid) else -1
        if owner >= 0:
            place_to_juns[c["nameCh"]].add(owner)

    keys: dict[int, set[str]] = defaultdict(set)
    # 州 키 — 위에서 배정한 州(동이는 州가 아니라 키가 없다).
    for i, region in enumerate(region_of):
        if region in KR_JU:
            keys[i].add(KR_JU[region])

    missing: list[str] = []
    for key in unit_gate_keys():
        if key in KR_JU.values() or key in JU_KR:      # 州 키는 위에서 이미 붙였다.
            continue
        hit = False
        for place in GATE_PLACES.get(key, []):
            for i in place_to_juns.get(place, ()):
                keys[i].add(key)
                hit = True
        if not hit:
            missing.append(key)
    return {jn: sorted(v) for jn, v in sorted(keys.items())}, missing


def kotlin_gate(index: dict[int, list[str]]) -> str:
    rows = "\n".join(
        f'        {cid} to setOf({", ".join(chr(34) + k + chr(34) for k in ks)}),'
        for cid, ks in index.items()
    )
    return (
        "package opensamguk.common.constants\n"
        "\n"
        "/**\n"
        " * GENERATED — `python3 tools/scenario/build_han_world.py` 산출물이다. 손으로 고치지 마라.\n"
        " * 고칠 것이 있으면 생성기(GATE_PLACES 별칭 표)를 고치고 다시 돌려라(`--check` 가 드리프트를 잡는다).\n"
        " *\n"
        " * 'han' 지도 城 id → 그 城이 가진 게이트 키(漢字) 집합 — 州 · 郡 · 治所 縣 · 이민족 거점.\n"
        " * han 병종의 ReqRegions(보유 판정) / ForbidRegions(주둔지 판정) 해석에 쓴다.\n"
        " * che 는 이 표를 쓰지 않으며(빈 집합), 기존 regionIdByName 경로 그대로 돈다.\n"
        " */\n"
        "object HanGateIndex {\n"
        "    val keysByCityId: Map<Int, Set<String>> = mapOf(\n"
        + rows + "\n"
        "    )\n"
        "\n"
        "    fun keys(cityId: Int): Set<String> = keysByCityId[cityId].orEmpty()\n"
        "}\n"
    )


def build_gate_skeleton() -> dict:
    """게이트 계산에 필요한 최소 뼈대 — TILES + CANON_105(tools/map/build_junguozhi.py 안의
    파이썬 리터럴, JUNGUOZHI 산출물이 아니다) 만 있으면 된다. JUNGUOZHI(郡國志 戶 사료)와
    CHE(che.json) 는 level/max 계산에만 쓰이고 州 배정·城 목록·id 배정에는 안 닿는다
    (`build_gate()`/`--check-gate` 가 이 뼈대만으로 HanGateIndex.kt 드리프트를 잡는 이유 —
    실측: JUNGUOZHI 를 훼손해 재생성해도 HanGateIndex.kt 는 바이트 단위로 그대로였다).
    """
    tiles = json.loads(TILES.read_text(encoding="utf-8"))
    juns, cities = tiles["juns"], tiles["cities"]
    jun_ju = canon_ju()

    # --- 州 배정 -------------------------------------------------------------
    region_of: list[str | None] = [None] * len(juns)
    for i, j in enumerate(juns):
        if j["nameCh"] in jun_ju:
            region_of[i] = JU_KR[jun_ju[j["nameCh"]]]
    anchored = [i for i, r in enumerate(region_of) if r]
    unresolved: list[tuple[str, str]] = []
    for i, j in enumerate(juns):
        if region_of[i]:
            continue
        if FRONTIER.get(j["nameCh"], (None,))[0]:
            region_of[i] = FRONTIER[j["nameCh"]][0]
            continue
        near = min(anchored, key=lambda a: (juns[a]["col"] - j["col"]) ** 2
                                           + (juns[a]["row"] - j["row"]) ** 2)
        region_of[i] = region_of[near]
        unresolved.append((j["name"], f"{region_of[i]}←{juns[near]['name']}"))

    ju_order = [JU_KR[k] for k in
                ["司隸", "豫州", "冀州", "兗州", "徐州", "青州", "荊州", "揚州",
                 "益州", "涼州", "并州", "幽州", "交州"]] + [DONGYI]

    # --- 城 목록: 郡治 175 + 郡國志에 실린 縣 -------------------------------
    # 郡治만 城으로 두면 縣은 배경이 된다. 사료(續漢書 郡國志)에 이름이 실린 縣만
    # 거점으로 올린다 — CHGIS 에만 있는 縣은 소속 郡도 戶 근거도 없어 배경으로 남긴다.
    cols = tiles["_meta"]["cols"]
    grid = seat_owner_grid(tiles)
    seat_of = build_seat_of(juns)
    included: list[tuple[int, int]] = []          # (城 인덱스, 소속 郡 인덱스)
    seaborne: list[str] = []
    for ci, c in enumerate(cities):
        if ci in seat_of:
            included.append((ci, seat_of[ci]))
            continue
        if not (c.get("zhi") and c["kind"] == "COUNTY"):
            continue
        cell = c["row"] * cols + c["col"]
        owner = grid[cell] if 0 <= cell < len(grid) else -1
        if owner < 0:                             # 소유 격자가 바다 — 조용히 버리지 않는다
            seaborne.append(c["name"])
            continue
        included.append((ci, owner))

    # --- 정렬(州 → 郡 → 治所 먼저 → 縣 이름) 후 id 1..N ----------------------
    order = sorted(included, key=lambda t: (ju_order.index(region_of[t[1]]),
                                            juns[t[1]]["nameCh"],
                                            0 if t[0] == juns[t[1]]["seat"] else 1,
                                            stable_city_sort_name(cities[t[0]])))
    id_of = {ci: n + 1 for n, (ci, _) in enumerate(order)}

    return {
        "tiles": tiles, "juns": juns, "cities": cities,
        "region_of": region_of, "ju_order": ju_order, "unresolved": unresolved,
        "included": included, "seaborne": seaborne, "order": order, "id_of": id_of,
    }


def build_committed_world_gate() -> tuple[str, dict[int, list[str]], list[str]]:
    """커밋된 han.json의 city id·郡·州를 기준으로 게이트 인덱스를 만든다.

    전체 월드 3종을 다시 구울 때는 ``build_gate(sk)``가 새 정렬을 함께 적용한다.
    반면 게이트만 검사할 때 새 CANON_105 정렬을 먼저 적용하면 아직 재생성하지 않은
    HanCityConst/han.json과 숫자 id가 어긋난다. 따라서 독립 검사는 실제 런타임 월드의
    id와 소속을 정본으로 삼는다.
    """
    tiles = json.loads(TILES.read_text(encoding="utf-8"))
    legacy_gameplay = tiles.get("legacyGameplay")
    if isinstance(legacy_gameplay, dict):
        tiles = {**tiles, **legacy_gameplay}
    world = json.loads(OUT_JSON.read_text(encoding="utf-8"))
    jun_by_ch = {j["nameCh"]: i for i, j in enumerate(tiles["juns"])}
    region_by_jun = {c["meta"]["junCh"]: c["meta"]["ju"] for c in world["cities"]}

    unknown = sorted(set(region_by_jun) - set(jun_by_ch))
    if unknown:
        raise AssertionError(f"han.json에 han-tiles.json에 없는 郡이 있다: {unknown}")

    region_of = [region_by_jun.get(j["nameCh"]) for j in tiles["juns"]]
    by_jun, missing = gate_index(tiles, region_of)
    index = {
        c["id"]: by_jun[jun_by_ch[c["meta"]["junCh"]]]
        for c in world["cities"]
        if by_jun.get(jun_by_ch[c["meta"]["junCh"]])
    }
    index = dict(sorted(index.items()))
    return kotlin_gate(index), index, missing


def build_gate(sk: dict | None = None) -> tuple[str, dict[int, list[str]], list[str]]:
    """HanGateIndex.kt 문자열 + city-id 게이트 인덱스 + 매칭 안 된 게이트 키.

    ``sk``가 있으면 전체 월드 생성 중 새 city id 정렬을 사용한다. 없으면 커밋된
    han.json의 런타임 city id를 사용한다. 두 경로의 id 축을 섞지 않는다.
    """
    if sk is None:
        return build_committed_world_gate()
    by_jun, missing = gate_index(sk["tiles"], sk["region_of"])
    # 縣은 소속 郡의 게이트 키를 그대로 물려받는다.
    index = {sk["id_of"][ci]: by_jun[jn] for ci, jn in sk["order"] if by_jun.get(jn)}
    index = dict(sorted(index.items()))
    return kotlin_gate(index), index, missing


def build() -> tuple[dict, str, str, dict]:
    sk = build_gate_skeleton()
    tiles, juns, cities = sk["tiles"], sk["juns"], sk["cities"]
    region_of, ju_order, unresolved = sk["region_of"], sk["ju_order"], sk["unresolved"]
    included, seaborne, order, id_of = sk["included"], sk["seaborne"], sk["order"], sk["id_of"]
    jun_of = {ci: jn for ci, jn in included}

    zhi = {p["name"]: p for p in json.loads(JUNGUOZHI.read_text(encoding="utf-8"))["places"]}
    maxes = che_max_by_level()

    households = [p["households"] for p in zhi.values() if p["households"]]
    t1, t2, t3 = level_thresholds(households)

    seat_kind = [cities[j["seat"]]["kind"] for j in juns]
    cols = tiles["_meta"]["cols"]

    # --- level ---------------------------------------------------------------
    def level_of(i: int) -> str:
        ch = juns[i]["nameCh"]
        if ch in CAPITALS:
            return "경"
        if ch in FRONTIER:                       # 동이 — 東夷傳 戶數로 다른 郡과 같이 잰다
            h = FRONTIER[ch][1]
            return HOUSEHOLD_LEVELS[(h > t1) + (h > t2) + (h > t3)] if h else "소"
        if seat_kind[i] == "EXTERNAL_PLACE":
            return "이"
        h = (zhi.get(ch) or {}).get("households")
        if not h:
            return "소"
        return HOUSEHOLD_LEVELS[(h > t1) + (h > t2) + (h > t3)]

    def county_level(jn: int) -> str:
        """縣 등급 — 續漢書 百官志 「萬戶以上為令，不滿為長」의 1만 戶 경계.

        한계: 縣 **개별** 戶數는 사료에 없다. 郡國志가 주는 것은 郡 총 戶와 縣 수뿐이라
        `郡戶 ÷ 縣수` 로 파생해 경계에 댄다. 그래서 한 郡의 縣은 통째로 令이나 長이 된다.
        거친 값이지만 지어낸 숫자가 아니라 사료의 경계선이고, 郡國志 추출이 보강되면
        저절로 세밀해진다.
        """
        p = zhi.get(juns[jn]["nameCh"])
        n = len(p.get("counties") or []) if p else 0
        h = (p or {}).get("households")
        return "영현" if h and n and h // n >= LING_HOUSEHOLDS else "장현"

    def level_at(ci: int, jn: int) -> str:
        return level_of(jn) if ci == juns[jn]["seat"] else county_level(jn)

    # --- 이름 ----------------------------------------------------------------
    # 縣·侯國·邑·道는 縣급 행정단위라 꼬리를 뗀다. 治所가 EXTERNAL_PLACE 인 곳
    # (于山國·伯濟國 …)은 나라 이름 자체라 건드리지 않는다.
    def base_name(ci: int) -> str:
        normalization = CANONICAL_PLACE_NAME_NORMALIZATIONS.get(str(cities[ci].get("id")))
        if normalization and isinstance(normalization.get("runtimeName"), str):
            return normalization["runtimeName"]
        n = cities[ci]["name"]
        if cities[ci]["kind"] != "COUNTY":
            return n
        for tail in ("후국", "현", "국", "읍", "도"):
            if n.endswith(tail) and len(n) > len(tail):
                return n[:-len(tail)]
        return n

    # 이름은 사료 그대로 둔다. 서로 다른 漢字가 같은 한글 독음이 되는 城이 여럿이지만
    # (零陵郡 零陵縣·梁國 寧陵縣이 둘 다 '영릉'), 시나리오·엔진·DB 는 城을 **id** 로 가리킨다.
    # 접두나 번호를 붙여 이름을 비트는 대신 겹치는 채로 두고, 몇 건인지만 보고한다.
    names: dict[int, str] = {ci: base_name(ci) for ci, _ in order}
    dup = Counter(names.values())
    collisions = sorted(f"{n}×{k}" for n, k in dup.items() if k > 1)

    # --- 연결 ----------------------------------------------------------------
    # 규칙 셋. 근거 없는 길을 지어내지 않으면서 治所가 고립되지 않게 하는 최소 조합이다.
    #  1. 縣 인접(adjacency.county) 중 양 끝이 모두 포함 城인 간선만 살린다. 郡 경계도
    #     가리지 않는다. 빠진 縣을 통과하는 축약은 하지 않는다 — 그 縣은 근거가 없어
    #     뺀 것이지 길이 아니다.
    #  2. 포함된 縣마다 자기 郡 治所와 직결선을 하나 놓는다. 縣 인접만으로는 治所에
    #     못 닿는 縣이 224개(58郡) 있어서 필수다.
    #  3. 郡끼리 맞닿았다는 이유만으로 治所를 잇지는 않는다. 위 1+2 그래프에서 두 治所
    #     사이 최단 홉을 재서 길이 없거나 4홉을 넘는 쌍만 治所 직결선으로 보정한다
    #     (縣 통로가 실제로 없는 경우만).
    adj: dict[int, set[int]] = defaultdict(set)
    for e in tiles["adjacency"]["county"]:
        adj[e["a"]].add(e["b"])
        adj[e["b"]].add(e["a"])
    inc_set = set(id_of)
    conn: dict[int, set[int]] = defaultdict(set)

    def link(a: int, b: int) -> None:
        if a != b:
            conn[a].add(b)
            conn[b].add(a)

    for a, nbs in adj.items():
        if a not in inc_set:
            continue
        for b in nbs:
            if b in inc_set:
                link(a, b)
    for ci, jn in included:
        link(ci, juns[jn]["seat"])

    def hops(src: int, dst: int, limit: int) -> int:
        if src == dst:
            return 0
        seen, front = {src}, [src]
        for d in range(1, limit + 1):
            nxt = []
            for u in front:
                for v in conn[u]:
                    if v in seen:
                        continue
                    if v == dst:
                        return d
                    seen.add(v)
                    nxt.append(v)
            if not nxt:
                break
            front = nxt
        return -1

    patched = 0
    for e in tiles["adjacency"]["commandery"]:
        a, b = juns[e["a"]]["seat"], juns[e["b"]]["seat"]
        if a not in inc_set or b not in inc_set or b in conn[a]:
            continue
        if hops(a, b, 4) < 0:
            link(a, b)
            patched += 1

    # 4. 섬 治所는 위 1~3 어디에도 걸리지 않는다(육지 인접이 없다). SEA_LINKS 만 잇는다.
    jun_by_ch = {j["nameCh"]: i for i, j in enumerate(juns)}
    sea_links: list[str] = []
    sea_missing: list[str] = []
    for island_ch, shore_ch, why in SEA_LINKS:
        ai, bi = jun_by_ch.get(island_ch), jun_by_ch.get(shore_ch)
        if ai is None or bi is None:
            sea_missing.append(f"{island_ch}↔{shore_ch}")
            continue
        a, b = juns[ai]["seat"], juns[bi]["seat"]
        if a not in inc_set or b not in inc_set:
            sea_missing.append(f"{island_ch}↔{shore_ch}")
            continue
        if b not in conn[a]:
            link(a, b)
        sea_links.append(f"{island_ch}↔{shore_ch}({why.split(' ')[0]})")

    out_cities = []
    raw_rows = []
    province_id_by_city_index = {
        record["cityIndex"]: province_id
        for province_id, record in enumerate(tiles.get("provinceRecords", []))
        if record.get("cityIndex") is not None
    }
    for ci, jn in order:
        cid = id_of[ci]
        lv = level_at(ci, jn)
        mx = maxes[lv]
        init = BUILD_INIT[lv]
        x = round(cities[ci]["col"] * WIDTH / cols)
        y = round(cities[ci]["row"] * HEIGHT / tiles["_meta"]["rows"])
        links = sorted(id_of[b] for b in conn[ci])
        out_city = {
            "id": cid, "name": names[ci], "x": x, "y": y,
            "level": LEVEL_ID[lv], "region": ju_order.index(region_of[jn]) + 1,
            "max": dict(mx),
            "initial": dict(zip(STAT_KEYS, init)),
            "connections": links,
            "meta": {"jun": juns[jn]["name"], "junCh": juns[jn]["nameCh"],
                     "ju": region_of[jn], "seat": cities[juns[jn]["seat"]]["name"],
                     "nameCh": cities[ci]["nameCh"], "isSeat": ci == juns[jn]["seat"]},
            "physicalPlaceId": cities[ci]["id"],
        }
        if ci in province_id_by_city_index:
            out_city["provinceId"] = province_id_by_city_index[ci]
        out_cities.append(out_city)
        raw_rows.append((cid, names[ci], lv,
                         [mx[k] // 100 for k in STAT_KEYS],
                         region_of[jn], x, y,
                         [names[b] for b in sorted(conn[ci], key=lambda b: id_of[b])]))

    doc = {
        "_meta": {
            "map": "han",
            "source": "data/map/han-tiles.json + data/map/junguozhi.json + tools/map/build_junguozhi.py CANON_105",
            "generator": "tools/scenario/build_han_world.py",
            "note": "생성물이다. 손으로 고치지 말고 생성기를 다시 돌려라. "
                    "城=郡治 + 續漢書 郡國志에 실린 縣. "
                    "level: 郡治는 郡國志 戶 백분위(che 등급 분포 기준, 동이는 東夷傳 戶數, "
                    "이민족 거점만 '이', 낙양·장안은 '경'), "
                    "縣은 百官志 「萬戶以上為令，不滿為長」 경계를 郡戶÷縣수에 대어 '영현'/'장현'. "
                    "max=che.json level 별 중앙값(경·영현·장현은 그 계단에서 파생한 밸런스값), "
                    "initial=CityConst.buildInit, x/y=격자 좌표 균일 축소.",
            "regions": ju_order,
        },
        "width": WIDTH, "height": HEIGHT, "cities": out_cities,
    }
    gate_kt, index, missing = build_gate(sk)
    stats = {
        "gateKeys": index,
        "gateMissing": missing,
        "juns": len(juns),
        "cityCount": len(out_cities),
        "seaborne": seaborne,
        "seaLinks": sea_links,
        "seaMissing": sea_missing,
        "byRegion": Counter(region_of[jn] for _, jn in order),
        "byLevel": Counter(c["level"] for c in out_cities),
        "connections": sum(len(v) for v in conn.values()) // 2,
        "patched": patched,
        "collisions": collisions,
        "unresolved": unresolved,
        "thresholds": (t1, t2, t3),
        "cities": out_cities,
    }
    return doc, kotlin(raw_rows), gate_kt, stats


def kotlin(rows) -> str:
    body = []
    for cid, name, lv, stats, region, x, y, path in rows:
        p = ", ".join(f'"{n}"' for n in path)
        s = ", ".join(str(v) for v in stats)
        body.append(f'        RawCity({cid}, "{name}", "{lv}", {s}, "{region}", {x}, {y}, listOf({p})),')
    return (
        "package opensamguk.common.constants\n"
        "\n"
        "import opensamguk.common.constants.CityConst.RawCity\n"
        "\n"
        "/**\n"
        " * GENERATED — `python3 tools/scenario/build_han_world.py` 산출물이다. 손으로 고치지 마라.\n"
        " * 고칠 것이 있으면 생성기를 고치고 다시 돌려라(`--check` 가 드리프트를 잡는다).\n"
        " *\n"
        " * 續漢書 郡國志 + CHGIS 격자에서 만든 'han' 지도 — 郡治와 郡國志에 실린 縣이 다 城이다.\n"
        " * che 와 달리 region 라벨이 州 이름이고 level 에 '경'·'영현'·'장현'이 더 있으므로,\n"
        " * 이 표를 CityConstRegistry 에 물릴 때 regionMap/levelMap 을 그 라벨까지 넓혀야 한다\n"
        " * (generateCities 가 regionMap/levelMap 의 getValue 로 라벨을 푼다). 배선은 이 파일의\n"
        " * 소관이 아니다 — HanCityConstVariant 가 한다.\n"
        " */\n"
        "object HanCityConst {\n"
        "    val initCity: List<RawCity> = listOf(\n"
        + "\n".join(body) + "\n"
        "    )\n"
        "}\n"
    )


def summary(stats: dict) -> None:
    e = sys.stderr
    print(f"城 {stats['cityCount']} (郡 {stats['juns']}) · 연결 {stats['connections']} · "
          f"戶 백분위 {stats['thresholds']}", file=e)
    print(f"縣 통로가 없어 治所끼리 직결로 보정한 인접 郡 쌍 {stats['patched']}", file=e)
    if stats["seaborne"]:
        print(f"소유 격자가 바다라 제외한 縣 {len(stats['seaborne'])}: "
              + ", ".join(stats["seaborne"]), file=e)
    print("州별: " + " · ".join(f"{k} {v}" for k, v in stats["byRegion"].items()), file=e)
    print("level: " + " · ".join(f"{LEVELS[k - 1]}({k}) {v}"
                                 for k, v in sorted(stats["byLevel"].items())), file=e)
    print(f"이름 충돌 {len(stats['collisions'])}: {', '.join(stats['collisions']) or '없음'}", file=e)
    covered = sum(1 for v in stats["gateKeys"].values() if v)
    print(f"게이트 키: {covered}/{stats['cityCount']} 城에 부여 · "
          f"매칭 안 되는 게이트 키 {len(stats['gateMissing'])}: "
          + (", ".join(stats["gateMissing"]) or "없음"), file=e)
    print(f"CANON_105 밖이라 州를 이웃에서 빌린 郡 {len(stats['unresolved'])}: "
          + ", ".join(f"{n}({r})" for n, r in stats["unresolved"]), file=e)
    # 연결 그래프 연결성 — 끊겨 있으면 조용히 잇지 않고 보고만 한다.
    by_id = {c["id"]: c for c in stats["cities"]}
    seen, q = {1}, deque([1])
    while q:
        for nxt in by_id[q.popleft()]["connections"]:
            if nxt not in seen:
                seen.add(nxt)
                q.append(nxt)
    if len(seen) != len(by_id):
        lost = sorted(set(by_id) - seen)
        print(f"경고: 그래프가 끊겼다. id 1 에서 {len(seen)}/{len(by_id)} 만 닿는다. "
              + "고립: " + ", ".join(f"{by_id[i]['name']}({i})" for i in lost), file=e)
    else:
        print(f"연결성: id 1 에서 {len(seen)}/{len(by_id)} 전부 도달.", file=e)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--check-gate", action="store_true",
                     help="현재 han.json city id 기준으로 HanGateIndex.kt 드리프트만 검사한다. "
                          "TILES·han.json·UNITS(전부 tracked)만 필요하고 JUNGUOZHI·CHE(둘 다 "
                          "gitignored, ADR-LITE-039)는 필요 없다 — CI가 부르는 경로.")
    ap.add_argument("--write-gate", action="store_true",
                    help="현재 han.json city id 기준 HanGateIndex.kt만 재생성한다.")
    args = ap.parse_args()
    if args.write_gate:
        for src in (TILES, OUT_JSON, UNITS):
            if not src.exists():
                sys.exit(f"{src.relative_to(ROOT)} 가 없다.")
        gate_kt, _, _ = build_gate()
        OUT_GATE.write_text(gate_kt, encoding="utf-8")
        print(f"{OUT_GATE.relative_to(ROOT)}")
        return 0
    if args.check_gate:
        for src in (TILES, OUT_JSON, UNITS):
            if not src.exists():
                sys.exit(f"{src.relative_to(ROOT)} 가 없다.")
        gate_kt, _, _ = build_gate()
        if OUT_GATE.exists() and OUT_GATE.read_text(encoding="utf-8") == gate_kt:
            print("드리프트 없음 (gate).")
            return 0
        print(f"드리프트: {OUT_GATE.relative_to(ROOT)}")
        return 1
    for src in (TILES, JUNGUOZHI, CANON_SRC, CHE, UNITS):
        if not src.exists():
            sys.exit(f"{src.relative_to(ROOT)} 가 없다.")
    doc, kt, gate, stats = build()
    blob = json.dumps(doc, ensure_ascii=False, indent=2) + "\n"
    if args.check:
        drift = [p.relative_to(ROOT) for p, want in ((OUT_JSON, blob), (OUT_KT, kt), (OUT_GATE, gate))
                 if not p.exists() or p.read_text(encoding="utf-8") != want]
        if drift:
            print("드리프트: " + ", ".join(str(p) for p in drift))
            return 1
        print("드리프트 없음.")
        return 0
    OUT_JSON.write_text(blob, encoding="utf-8")
    OUT_KT.write_text(kt, encoding="utf-8")
    OUT_GATE.write_text(gate, encoding="utf-8")
    summary(stats)
    print(f"{OUT_JSON.relative_to(ROOT)} · {OUT_KT.relative_to(ROOT)} · {OUT_GATE.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
