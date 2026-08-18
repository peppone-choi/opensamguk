#!/usr/bin/env python3
"""CHGIS 커버리지 밖 지점 — 좌표를 Wikidata(CC0)에서 받는다.

입력  없음 (Wikidata SPARQL)
출력  data/map/external-places.json

**왜 필요한가.** CHGIS V6 는 현대 중화인민공화국 국경 안만 담는다. 그래서 後漢의 정식
행정구역인데도 소스에 아예 없는 곳이 있다 — 交州 남부 3郡(베트남), 樂浪·帶方(한반도).
격자가 잘라낸 게 아니라 원본에 없다. 여기에 郡國志에 없는 주변 세력까지 더한다.

**좌표를 손으로 적지 않는다.** 손으로 적으면 출처가 나고, 그건 출처가 아니다.
비정(어느 현대 지점인가)은 문헌이 정하고, 좌표(그 지점이 어디인가)는 Wikidata P625 가
준다. QID 를 같이 적어 사후에 남이 검증할 수 있게 한다. 후보가 여럿이면 임의로 고르지
않고 unresolved 로 남긴다 — 지도에 안 실리는 편이 틀린 자리에 실리는 것보다 낫다.

**소국은 적게 찍는다.** 三國志 魏書 東夷傳이 이름을 남긴 것 중 위치 비정이 굳은 것만
넣는다. 邑落 단위까지 찍으면 지도가 이름으로 덮이고, 대부분은 비정이 갈린다.

usage:  python3 tools/map/build_external_places.py [--check]
"""
from __future__ import annotations

import argparse, json, sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from resolve_city_coords import sparql          # noqa: E402  (같은 SPARQL 헬퍼를 쓴다)

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "data" / "map" / "external-places.json"

CN, KR, KP, VN, JP, RU, TW = "Q148", "Q884", "Q423", "Q881", "Q17", "Q159", "Q865"

# (한자, 종류, 등급, 현대 비정지 영문 라벨, 국가, 소속郡, 소속州, 비정 근거, 상위행정구역, 허용반경km)
#
# 상위행정구역은 동명이지를 가른다 — Ji'an 은 길림과 강서에 둘 다 있다. 허용반경은
# 후보들이 그 안에 모이면 같은 곳으로 본다. 유목 王庭처럼 애초에 점이 아닌 것은 넓게 준다.
#
# COMMANDERY/COUNTY = 後漢 정식 행정구역. CHGIS 지점과 완전히 동격이고 정복 대상이다.
# EXTERNAL_PLACE    = 漢 밖 세력. 정복이 아니라 외교·교역·이민족 경로. lv4('이').
#
# 비정 근거는 반드시 남긴다. 근거 없는 줄은 넣지 마라 — 없는 줄이 곧 "아직 모른다"다.
PLACES = [
    # --- 交州 남부 3郡. 郡國志 卷23. CHGIS 미커버(베트남) ---
    ("交趾郡", "COMMANDERY", 6, "Bắc Ninh", VN, "交趾郡", "交州",
     "치소 龍編. 羸𨻻(Luy Lâu) 성터가 있는 박닌", "province of Vietnam", 40),
    ("九真郡", "COMMANDERY", 6, "Thanh Hóa", VN, "九真郡", "交州",
     "치소 胥浦. 馬江(Sông Mã) 하류 타인호아 시가", "provincial city of Vietnam", 20),
    ("日南郡", "COMMANDERY", 6, "Quảng Trị", VN, "日南郡", "交州",
     "치소 西捲. 192년 象林이 이탈해 林邑(참파)이 된 제국 최남단",
     "province of Vietnam", 40),
    # --- 한반도. 樂浪은 정식 郡, 帶方은 204년 공손강이 낙랑 남부를 갈라 세웠다 ---
    ("樂浪郡", "COMMANDERY", 6, "Pyongyang", KP, "樂浪郡", "幽州",
     "치소 朝鮮. 평양 낙랑토성", None, 20),
    ("帶方郡", "COMMANDERY", 6, "Sariwon", KP, "帶方郡", "幽州",
     "치소 帶方. 황해도 사리원~봉산 일대", None, 20),
    # --- CHGIS 220년 레이어에 治所가 없어 앵커가 만들어지지 않는 漢 郡 ---
    # 앵커가 없으면 MAX_KM 필터가 통째로 꺼져 동명이인 縣을 1500km 밖에서 물어온다
    # (遼東 襄平이 하남에, 雲中 武進이 강소성에 찍히던 이유). 治所를 사료로 비정해 넣는다.
    ("遼東郡", "COMMANDERY", 6, "Liaoyang", CN, None, None,
     "郡國志 遼東郡 治 襄平. 요양 일대", "Liaoning", 30),
    ("玄菟郡", "COMMANDERY", 6, "Fushun", CN, None, None,
     "郡國志 玄菟郡 治 高句驪. 3차 이치 후 무순 일대", "Liaoning", 30),
    ("遼東屬國", "COMMANDERY", 6, "Yi County", CN, None, None,
     "郡國志 遼東屬國 治 昌遼(昌黎). 금주 의현 일대", "Liaoning", 40),
    ("上谷郡", "COMMANDERY", 6, "Huailai County", CN, None, None,
     "郡國志 上谷郡 治 沮陽. 하북 회래", "Hebei", 30),
    ("雲中郡", "COMMANDERY", 6, "Togtoh County", CN, None, None,
     "郡國志 雲中郡 治 雲中. 내몽골 托克托", "Inner Mongolia", 40),
    ("五原郡", "COMMANDERY", 6, "Baotou", CN, None, None,
     "郡國志 五原郡 治 九原. 포두 일대", "Inner Mongolia", 40),
    ("朔方郡", "COMMANDERY", 6, "Linhe District", CN, None, None,
     "郡國志 朔方郡 治 臨戎. 하투 임하", "Inner Mongolia", 50),
    ("鉅鹿郡", "COMMANDERY", 6, "Xingtai", CN, None, None,
     "郡國志 鉅鹿郡 治 廮陶 = 하북 寧晉. 상위 邢台로 잡는다", "Hebei", 45),
    ("東郡", "COMMANDERY", 6, "Puyang", CN, None, None,
     "郡國志 東郡 治 濮陽", "Henan", 30),
    ("魯國", "COMMANDERY", 6, "Qufu", CN, None, None,
     "郡國志 魯國 治 魯縣 = 곡부", "Shandong", 25),
    ("左馮翊", "COMMANDERY", 6, "Gaoling District", CN, None, None,
     "郡國志 左馮翊 治 高陵", "Shaanxi", 30),
    ("右扶風", "COMMANDERY", 6, "Xingping", CN, None, None,
     "郡國志 右扶風 治 槐里 = 흥평", "Shaanxi", 30),
    ("廬江郡", "COMMANDERY", 6, "Lujiang County", CN, None, None,
     "郡國志 廬江郡 治 舒", "Anhui", 30),
    ("越巂郡", "COMMANDERY", 6, "Xichang", CN, None, None,
     "郡國志 越巂郡 治 邛都 = 서창", "Sichuan", 30),
    ("牂牁郡", "COMMANDERY", 6, "Huangping County", CN, None, None,
     "郡國志 牂牁郡 治 且蘭. 귀주 황평 비정 채택 — 貴陽설과 미결", "Guizhou", 60),
    ("蜀郡屬國", "COMMANDERY", 6, "Ya'an", CN, None, None,
     "郡國志 蜀郡屬國 治 漢嘉. 아안 일대", "Sichuan", 40),
    ("張掖居延屬國", "COMMANDERY", 6, "Ejin Banner", CN, None, None,
     "郡國志 張掖居延屬國 治 居延. 額濟納 거연택", "Inner Mongolia", 60),

    # --- 漢 밖 세력. 三國志 魏書30 烏丸鮮卑東夷傳 · 後漢書 卷85 東夷列傳 ---
    # 중요 세력은 郡治급(lv6)으로 올린다 — 도로 간선의 허브가 되어 실제로 오갈 수 있어야 한다.
    ("高句麗", "EXTERNAL_PLACE", 6, "Ji'an", CN, None, None,
     "국내성 = 지린성 지안. 유리왕 3년 천도 이후 도읍", "Jilin", 10),
    ("夫餘", "EXTERNAL_PLACE", 6, "Nong'an County", CN, None, None,
     "쑹화강 유역 눙안 일대. 東夷傳 「夫餘在長城之北，去玄菟千里」", None, 20),
    ("東沃沮", "EXTERNAL_PLACE", 6, "Hamhung", KP, None, None,
     "東夷傳 「東沃沮在高句麗蓋馬大山之東，濱大海而居」. 함흥 일대", None, 20),
    ("濊", "EXTERNAL_PLACE", 6, "Gangneung", KR, None, None,
     "東夷傳 「濊南與辰韓，北與高句麗、沃沮接，東窮大海」. 영동 강릉", "Gangwon Province", 15),
    ("挹婁", "EXTERNAL_PLACE", 4, "Ussuriysk", RU, None, None,
     "東夷傳 「挹婁在夫餘東北千餘里，濱大海」. 연해주 남부", None, 30),
    ("馬韓", "EXTERNAL_PLACE", 6, "Iksan", KR, None, None,
     "東夷傳 「馬韓在西，其民土著，有五十四國」. 目支國 익산~직산 중 익산 채택", None, 20),
    ("伯濟國", "EXTERNAL_PLACE", 6, "Seoul", KR, None, None,
     "韓傳 마한 54국 목록의 伯濟國. 한강 하류 풍납토성 일대 — 백제의 모태", None, 20),
    ("州胡", "EXTERNAL_PLACE", 6, "Jeju City", KR, None, None,
     "韓傳 「又有州胡在馬韓之西海中大島上 … 乘船往來，巿買韓中」. 탐라", None, 30),
    ("辰韓", "EXTERNAL_PLACE", 6, "Gyeongju", KR, None, None,
     "東夷傳 「辰韓在馬韓之東，十有二國」. 斯盧國 = 경주", None, 15),
    ("弁韓", "EXTERNAL_PLACE", 6, "Gimhae", KR, None, None,
     "東夷傳 弁辰十二국. 狗邪國 = 김해. 倭 여정의 출발점 狗邪韓國", None, 15),
    # 倭 여정. 東夷傳이 帶方에서 邪馬壹國까지 里程을 그대로 적어놨다 —
    #   「從郡至倭，循海岸水行，歷韓國，乍南乍東，到其北岸狗邪韓國，七千餘里，
    #    始渡一海，千餘里至對馬國 … 又渡一海，千餘里至末盧國 … 東南陸行五百里，到伊都國 …
    #    東南至奴國百里 … 南至投馬國，水行二十日 … 南至邪馬壹國，女王之所都」
    # 여정의 앞부분은 고고학 비정이 굳어 있다. 뒷부분(投馬·邪馬壹)만 논쟁이다.
    ("對馬國", "EXTERNAL_PLACE", 4, "Tsushima", JP, None, None,
     "東夷傳 狗邪韓國에서 바다 건너 천여 리. 쓰시마", "Nagasaki Prefecture", 30),
    ("一大國", "EXTERNAL_PLACE", 4, "Iki", JP, None, None,
     "東夷傳 對馬國 남쪽 천여 리 瀚海. 이키섬 하루노쓰지 유적", "Nagasaki Prefecture", 20),
    ("末盧國", "EXTERNAL_PLACE", 4, "Karatsu", JP, None, None,
     "東夷傳 「又渡一海，千餘里至末盧國，有四千餘戶，濵山海居」. 가라쓰", None, 20),
    ("伊都國", "EXTERNAL_PLACE", 4, "Itoshima", JP, None, None,
     "東夷傳 「東南陸行五百里，到伊都國」. 이토시마 미쿠모 유적", None, 20),
    ("奴國", "EXTERNAL_PLACE", 4, "Kasuga", JP, None, None,
     "後漢書 「建武中元二年，倭奴國奉貢朝賀 … 倭國之極南界也」. 金印은 志賀島 출토지만 "
     "왕도 후보는 須玖岡本 유적의 가스가", "Fukuoka Prefecture", 20),
    ("邪馬壹國", "EXTERNAL_PLACE", 6, "Yoshinogari", JP, None, None,
     "三國志는 邪馬壹國, 後漢書는 邪馬臺國으로 적는다(원문에 壹/臺 이체자 주석). "
     "규슈설 채택 — 기나이설(나라 분지)과 미결", None, 15),
    # --- 남해. 후한 판도 밖이지만 사료가 항로 끝을 적어놨다 ---
    ("夷洲", "EXTERNAL_PLACE", 6, "Tainan", TW, None, None,
     "吳主傳 「遣將軍衞溫、諸葛直將甲士萬人，浮海求夷洲及亶洲」. 資治通鑑은 "
     "「欲俘其民以益衆」이라 적었다 — 인구 약탈이 목적이었다. 대만 비정은 미결", None, 60),
    ("流求", "EXTERNAL_PLACE", 6, "Naha", JP, None, None,
     "隋書 卷81 東夷 流求國. 후한대 기록이 아니라 게임적 허용으로 남해 항로 끝에 둔다",
     "Okinawa Prefecture", 30),
    # --- 기존 devsam 맵이 "이민족"으로 뭉뚱그린 곳. 사료로 비정해 되돌린다 ---
    ("西羌", "EXTERNAL_PLACE", 6, "Xining", CN, None, None,
     "後漢書 卷87 西羌傳 「濱於賜支，至乎河首，綿地千里」. 賜支河曲 = 황하 상류 청해",
     "Qinghai", 40),
    ("白馬氐", "EXTERNAL_PLACE", 6, "Longnan", CN, None, None,
     "元和郡縣圖志 39 「戰國時，白馬氐居焉，氐即西戎之別種也」. 武都 = 隴南",
     "Gansu", 40),
    ("哀牢", "EXTERNAL_PLACE", 6, "Baoshan", CN, None, None,
     "後漢書 卷86 「哀牢夷者，其先有婦人名沙壹，居於牢山」. 永昌郡 — devsam 맵의 南蠻",
     "Yunnan", 40),
    ("山越", "EXTERNAL_PLACE", 6, "Huangshan", CN, None, None,
     "三國志 55·60 「諸山越不賔，有寇難之縣」. 丹陽 산지 黟·歙 일대 — 부족 영역이라 넓다",
     "Anhui", 60),
    # --- 초원 ---
    ("烏桓", "EXTERNAL_PLACE", 6, "Chifeng", CN, None, None,
     "東夷傳 「烏丸者，東胡也 … 餘類保烏丸山」. 蹋頓의 柳城은 遼西郡이라 CHGIS 에 있다",
     "Inner Mongolia", 20),
    ("鮮卑", "EXTERNAL_PLACE", 6, "Zhangjiakou", CN, None, None,
     "檀石槐 王庭 = 高柳 북쪽 삼백여 리. 軻比能은 雲中·五原 동쪽을 다 거뒀다", None, 20),
    ("南匈奴", "EXTERNAL_PLACE", 6, "Ordos City", CN, None, None,
     "單于庭 美稷 = 西河郡 오르도스 남부. 王庭은 애초에 점이 아니라 넓게 잡는다",
     "Inner Mongolia", 40),
]

DISPUTED = {"邪馬壹國", "馬韓", "鮮卑", "南匈奴"}          # 비정이 갈리는 것. 게임은 견디지만 기록은 못 견딘다.
FIELDS = ("nameFt", "kind", "level", "modern", "country", "jun", "prov", "basis", "adm", "tol")


def spread_km(cand):
    """후보들이 얼마나 흩어져 있나. 위도 1도 = 111km, 경도는 cos 로 누른다."""
    import math
    worst = 0.0
    for i in range(len(cand)):
        for j in range(i + 1, len(cand)):
            _, x1, y1 = cand[i]
            _, x2, y2 = cand[j]
            dy, dx = (y1 - y2) * 111.0, (x1 - x2) * 111.0 * math.cos(math.radians((y1 + y2) / 2))
            worst = max(worst, math.hypot(dx, dy))
    return worst


def resolve():
    """라벨+국가로 좌표를 조회한다. 후보가 여럿이면 실패로 남긴다(임의로 고르지 않는다)."""
    # 라벨을 언어 태그 리터럴로 직접 매칭한다. FILTER(STR(?l)=...) 는 전체 라벨을 훑어
    # 504 로 죽는다 — 태그 리터럴은 색인을 타서 즉시 돌아온다.
    # 한 번에 다 물으면 504 가 난다. 20행씩 끊어 묻고 합친다.
    rows = []
    for k in range(0, len(PLACES), 20):
        values = " ".join(f'("{r[3]}"@en wd:{r[4]})' for r in PLACES[k:k + 20])
        rows += sparql(f"""
SELECT ?label ?item ?coord WHERE {{
  VALUES (?label ?country) {{ {values} }}
  ?item rdfs:label ?label ; wdt:P17 ?country ; wdt:P625 ?coord .
}}""")
    # 동명이지는 상위 행정구역(P131*)이나 종류(P31)로 가른다 — Ji'an 은 길림과 강서에
    # 둘 다 있고, Thanh Hóa 는 省과 省都와 面이 같은 이름을 쓴다.
    adms = {r[3]: r[8] for r in PLACES if r[8]}
    if adms:
        keep, items = set(), list(adms.items())
        for k in range(0, len(items), 8):     # P131* 재귀는 무거워 8행씩 끊는다
            av = " ".join(f'("{lab}"@en "{adm}"@en)' for lab, adm in items[k:k + 8])
            keep |= {(b["label"]["value"], b["item"]["value"].rsplit("/", 1)[-1])
                     for b in sparql(f"""
SELECT ?label ?item WHERE {{
  VALUES (?label ?adm) {{ {av} }}
  ?item rdfs:label ?label .
  {{ ?item wdt:P131* ?a . ?a rdfs:label ?adm . }}
  UNION
  {{ ?item wdt:P31 ?t . ?t rdfs:label ?adm . }}
}}""")}
        rows = [b for b in rows if b["label"]["value"] not in adms
                or (b["label"]["value"], b["item"]["value"].rsplit("/", 1)[-1]) in keep]
    hits = {}
    for b in rows:
        lon, lat = b["coord"]["value"].removeprefix("Point(").removesuffix(")").split()
        hits.setdefault(b["label"]["value"], []).append(
            (b["item"]["value"].rsplit("/", 1)[-1], float(lon), float(lat)))

    out, unresolved = [], []
    for i, row in enumerate(PLACES):
        p = dict(zip(FIELDS, row))
        cand = hits.get(p["modern"], [])
        # 같은 곳을 여러 항목이 조금씩 다르게 적어둔 경우가 많다(시청 좌표 대 중심 좌표).
        # 후보가 10km 안에 모여 있으면 같은 곳으로 보고 첫 항목을 쓴다. 흩어져 있으면
        # 서로 다른 동명 지점이라는 뜻이므로 고르지 않는다 — Ji'an(길림/강서)이 그렇다.
        if not cand or spread_km(cand) > p["tol"]:
            unresolved.append(f'{p["nameFt"]} ({p["modern"]}): 후보 {len(cand)}개'
                              f'{"" if not cand else f" · 최대 {spread_km(cand):.0f}km 흩어짐"}'
                              " — 임의 선택하지 않음")
            continue
        qid, lon, lat = cand[0]
        lon, lat = round(lon, 5), round(lat, 5)
        p.pop("adm"); p.pop("tol")
        p.update(id=f"X{i:03d}", nameCh=p["nameFt"], namePy="", typeCh="",
                 lon=lon, lat=lat, wikidata=qid, begYr=-9999, endYr=9999,
                 conf="DISPUTED" if p["nameFt"] in DISPUTED else "IDENTIFIED",
                 presLoc=p.pop("modern"))
        p.pop("country")
        out.append(p)
    return out, unresolved


def build():
    places, unresolved = resolve()
    return {
        "_meta": {
            "source": "비정 = 三國志 魏書 東夷傳·後漢書 郡國志 · 좌표 = Wikidata P625 (CC0)",
            "generator": "tools/map/build_external_places.py",
            "note": "CHGIS V6 커버리지(현대 중국 국경) 밖 지점. 좌표는 손으로 적지 않는다.",
            "resolved": len(places), "unresolved": len(unresolved),
        },
        "places": places,
        "unresolved": unresolved,
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    a = ap.parse_args()
    doc = build()
    blob = json.dumps(doc, ensure_ascii=False, indent=1, sort_keys=True) + "\n"
    if a.check:
        same = OUT.exists() and OUT.read_text() == blob
        print("드리프트 없음." if same else f"드리프트: {OUT}")
        return 0 if same else 1
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(blob)
    m = doc["_meta"]
    print(f'{OUT.relative_to(ROOT)}: 해결 {m["resolved"]} · 미해결 {m["unresolved"]}')
    for u in doc["unresolved"]:
        print(f"  미해결  {u}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
