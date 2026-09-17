#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""포위·보급 탐색 기준선 — 재설계 §15.2 S2 2차분, 이슈 #778 / OPENSAM-258.

엔진 밖 계산이다. 여기 나오는 어떤 값도 게임 수치가 아니다(status=EXPLORATORY).
사용자가 포위 기간·전쟁 비용의 목표 범위를 고를 수 있게 후보를 나란히 돌린 비교 표다.

  A. 원정 곡물 소요 — 승인된 행군 템포(march-tempo-targets-v1.json)로 잰 소요 순
     × 「1순 = N일」 후보 × 병력 후보 × 1인 월 식량 후보. 출발 郡 戶數(경제 입력 원장)와 견준다.
  B. 보급선 길이별 도착 비율 — 간선당 손실 후보의 거듭제곱, 그리고 사료 수치만 쓰는 木牛 모델.
  C. 포위 기간 — 사료상 실제 기간을 「1순 = N일」 후보별 순으로 환산, 포위 기간 후보와 대조.

사료 인용은 SOURCES 에 있다. 로컬 색인(shiliao)으로 원문을 확인한 것만 PRIMARY 다.
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import march_tempo as M  # noqa: E402  (고치지 않고 import 만 한다)

ROOT = M.ROOT
TEMPO = ROOT / "data/curated/han/march-tempo-targets-v1.json"
ECONOMY = ROOT / "data/curated/han/county-economy-inputs-v1.json"
STATUS = "EXPLORATORY"

HAN_LI_KM = 0.4158          # 漢里 = 1800尺 × 23.1 cm (Dubs). SOURCES["hanLi"]
HU_LITRES = 19.968          # 漢 1斛. SOURCES["hu"]
LUNAR_MONTH_DAYS = 29.5     # 사료의 「N월」을 일로 바꾸는 근사

# ── 후보값. 전부 EXPLORATORY ──────────────────────────────────────────────
# 1순의 세계 안 길이(일). 미정(spec §3 은 1년 36순만 정했다).
#   2.4 = 승인 템포 30 km/순 ÷ 사료 행군 속도 30里/日(12.5 km) 의 역산값
#   10  = 역법 그대로(1년 36순 → 1순 ≈ 10일)
#   5   = 그 사이 값(근거 없음, 비교용)
DAYS_PER_TURN = (2.4, 5.0, 10.0)
TROOPS = (10_000, 30_000, 50_000, 100_000)
# 1인 월 식량(斛/人/月). 곡종이 다르다(穀 = 겉곡, 米 = 찧은 것) — 서로 환산하지 않는다.
RATIONS = (
    ("李固 5升/日(米)", 1.5, "liGu"),
    ("趙充國 2.66斛/月(穀)", 27363 / 10281, "zhaoChongguo"),
    ("居延 3.2斛/月(2차 문헌)", 3.2, "juyan"),
)
RATION_TABLE_TROOPS = 30_000
LOSS_PER_EDGE = (0.01, 0.02, 0.05, 0.10)   # 간선당 손실 후보. 사료 근거 없음 — 격자일 뿐이다.
EXTRA_EDGES = (1, 2)
SIEGE_TURN_CANDIDATES = (3, 6, 9, 18, 36)
MEDIAN_EDGE_KM = 32         # 행군 노트 실측: 중원 간선 711개 중앙값

SOURCES = {
    "liGu": {"grade": "PRIMARY", "book": "後漢書 卷86 南蠻西南夷列傳", "quote": "軍行三十里爲程，而去日南九千餘里，三百日乃到，計人稟五昇，用米六十萬斛",
             "url": "https://zh.wikisource.org/zh-hant/後漢書/卷86", "note": "4만 명 × 300일 × 5升 = 60만 斛. 永和二年(137) 李固 의론"},
    "zhaoChongguo": {"grade": "PRIMARY", "book": "漢書 卷69 趙充國辛慶忌傳", "quote": "合凡萬二百八十一人，用穀月二萬七千三百六十三斛，鹽三百八斛",
                     "url": "https://zh.wikipedia.org/wiki/赵充国", "note": "前漢 神爵 원년(기원전 61) 屯田奏. 27363 ÷ 10281 = 2.66"},
    "juyan": {"grade": "SECONDARY_UNVERIFIED", "book": "居延漢簡(원문 미확인)", "quote": None,
              "url": "https://military-history.fandom.com/wiki/Military_of_the_Han_dynasty", "note": "2차 문헌의 요약 수치. 簡 번호 UNKNOWN"},
    "hu": {"grade": "SECONDARY", "quote": None, "url": "https://military-history.fandom.com/wiki/Military_of_the_Han_dynasty", "note": "1斛 = 19.968 L"},
    "hanLi": {"grade": "SECONDARY", "quote": None, "url": "https://kongming.net/novel/chinese_units/", "note": "1里 = 300步 ≈ 415.8 m"},
    "woodenOx": {"grade": "PRIMARY", "book": "三國志 卷35 諸葛亮傳 注(亮集)", "quote": "載一歲糧，日行二十里，而人不大勞",
                 "url": "https://zh.wikisource.org/zh-hant/三國志/卷35", "note": "木牛 1대 = 1인 1년치, 하루 20里"},
    "qin30zhong": {"grade": "PRIMARY", "book": "史記 卷112 平津侯主父列傳", "quote": "起於黃、腄、瑯邪負海之郡，轉輸北河，率三十鐘而致一石",
                   "url": "https://zh.wikisource.org/zh-hant/史記/卷112", "note": "1鍾 = 6斛4斗(https://zdic.net/hans/鍾) → 192 : 1. 秦代, 수사적 상한. 거리 UNKNOWN"},
    "xinan10zhong": {"grade": "PRIMARY", "book": "漢書 卷24 食貨志 (= 史記 卷30 平準書)", "quote": "千里負擔餽饟，率十餘鍾致一石",
                     "url": "https://zh.wikisource.org/zh-hant/漢書/卷024", "note": "「十餘鍾」을 10鍾으로 잡으면 64 : 1 — 실제는 그보다 나쁘다"},
    "wudu5to1": {"grade": "PRIMARY", "book": "後漢書 卷58 虞詡傳", "quote": "運道艱險，舟車不通，驢馬負載，僦五致一",
                 "url": "https://zh.wikisource.org/zh-hant/後漢書/卷58", "note": "李賢注: 5石 삯으로 1石 도착. 손실이 아니라 운임. 거리 UNKNOWN(「自沮至下辯數十里」는 개수 구간)"},
}

# 사료상 포위·대치. months/days 중 하나만 채운다. None = 사료가 수를 주지 않는다(UNKNOWN).
# lowerBound=True 는 「…餘」「歲餘」처럼 하한만 아는 경우.
SIEGES = (
    {"name": "雍丘 195", "kind": "포위", "months": 4, "days": None, "lowerBound": False, "outcome": "함락",
     "book": "三國志 卷1 武帝紀", "quote": "秋八月，圍雍丘。…十二月，雍丘潰", "url": "https://zh.wikisource.org/zh-hant/三國志/卷01"},
    {"name": "下邳 198", "kind": "포위(수공)", "months": 3, "days": None, "lowerBound": False, "outcome": "내부 항복",
     "book": "三國志 卷7 呂布傳", "quote": "太祖塹圍之三月，上下離心", "url": "https://en.wikipedia.org/wiki/Battle_of_Xiapi"},
    {"name": "官渡 200", "kind": "대치(포위 아님)", "months": 2, "days": None, "lowerBound": False, "outcome": "보급 기지 피습으로 붕괴",
     "book": "三國志 卷1 武帝紀", "quote": "八月，紹連營稍前…冬十月，紹遣車運穀", "url": "https://en.wikipedia.org/wiki/Battle_of_Guandu"},
    {"name": "鄴 204(공격 개시부터)", "kind": "포위(수공)", "months": 6, "days": None, "lowerBound": False, "outcome": "내응으로 함락",
     "book": "三國志 卷1 武帝紀", "quote": "二月…攻鄴，爲土山、地道…八月，審配兄子榮夜開所守城東門內兵", "url": "https://en.wikipedia.org/wiki/Battle_of_Ye"},
    {"name": "鄴 204(圍壍부터)", "kind": "포위(수공)", "months": 3, "days": None, "lowerBound": False, "outcome": "내응으로 함락",
     "book": "三國志 卷6 袁紹傳", "quote": "決漳水以灌之，自五月至八月，城中餓死者過半", "url": "https://en.wikipedia.org/wiki/Battle_of_Ye"},
    {"name": "合肥 208", "kind": "포위", "months": None, "days": 100, "lowerBound": True, "outcome": "해제(공격 측 철수)",
     "book": "三國志 卷15 劉馥傳", "quote": "孫權率十萬衆攻圍合肥城百餘日", "url": "https://en.wikipedia.org/wiki/Battle_of_Hefei_(208)"},
    {"name": "江陵 208–209", "kind": "포위·대치", "months": 12, "days": None, "lowerBound": True, "outcome": "수비 측 철수",
     "book": "三國志 卷47 吳主傳", "quote": "十四年，瑜、仁相守歳餘，所殺傷甚眾。仁委城走", "url": "https://en.wikipedia.org/wiki/Battle_of_Jiangling_(208)"},
    {"name": "樊(襄陽) 219", "kind": "포위(홍수)", "months": 2, "days": None, "lowerBound": True, "outcome": "구원군이 해제",
     "book": "三國志 卷1 武帝紀", "quote": "八月，漢水溢，灌禁軍…遂圍仁…冬十月…晃攻羽，破之，羽走，仁圍解", "url": "https://en.wikipedia.org/wiki/Battle_of_Fancheng"},
    {"name": "陳倉 228–229", "kind": "강공", "months": None, "days": 20, "lowerBound": True, "outcome": "해제(공격 측 糧盡)",
     "book": "三國志 卷3 明帝紀 注(魏略)", "quote": "晝夜相攻拒二十餘日，亮無計，救至，引退", "url": "https://en.wikipedia.org/wiki/Siege_of_Chencang"},
    {"name": "襄平 238", "kind": "포위", "months": 2, "days": None, "lowerBound": False, "outcome": "함락",
     "book": "三國志 卷8 公孫淵傳", "quote": "六月，軍至遼東…爲圍塹。會霖雨三十餘日…八月…壬午，淵眾潰", "url": "https://zh.wikisource.org/zh-hant/三國志/卷08"},
    {"name": "合肥新城 253", "kind": "포위", "months": None, "days": None, "lowerBound": False, "outcome": "해제(역병)",
     "book": "三國志 卷64 諸葛恪傳", "quote": "攻守連月，城不拔。士卒疲勞…病者大半", "url": "https://zh.wikisource.org/zh-hant/三國志/卷64"},
    {"name": "壽春 257–258", "kind": "포위", "months": 8, "days": None, "lowerBound": False, "outcome": "함락(성 안 糧盡)",
     "book": "三國志 卷28 諸葛誕傳 · 卷4 三少帝紀", "quote": "六月，車駕東征…督中外諸軍二十六萬眾，臨淮討之 / 三年春二月，大將軍司馬文王陷壽春城", "url": "https://en.wikipedia.org/wiki/Zhuge_Dan%27s_Rebellion"},
    {"name": "東武陽(臧洪, 연도 UNKNOWN)", "kind": "포위", "months": 12, "days": None, "lowerBound": True, "outcome": "함락(성 안 糧盡)",
     "book": "三國志 卷7 臧洪傳", "quote": "紹興兵圍之，歷年不下", "url": "https://zh.wikisource.org/zh-hant/三國志/卷07"},
)


def load_json(path: Path) -> dict:
    return json.loads(Path(path).read_text(encoding="utf-8"))  # 없으면 FileNotFoundError — 조용히 기본값을 쓰지 않는다


def approved_tempo(tempo: dict) -> tuple[float, float]:
    if tempo.get("status") != "OWNER_APPROVED_BASELINE":
        raise ValueError(f"march tempo targets are not owner-approved: {tempo.get('status')!r}")
    return float(tempo["baseSpeedKmPerTurn"]), float(tempo["roughTerrainFactor"])


def commandery_households(economy: dict, name_ch: str) -> dict:
    """출발 縣이 속한 郡의 戶數 합. 원장에 戶數가 없는 郡(永和五年 뒤 신설)은 None 이다 — 0 이 아니다."""
    hits = [j for j in economy["jurisdictions"] if j["nameCh"] == name_ch]
    if len(hits) != 1:
        raise ValueError(f"county {name_ch!r} matched {len(hits)} economy rows")
    cid = hits[0]["commanderyId"]
    members = [j for j in economy["jurisdictions"] if j["commanderyId"] == cid]
    known = [j["households"] for j in members if j.get("households") is not None]
    return {"commandery": hits[0]["commanderyNameCh"], "households": sum(known) if len(known) == len(members) else None}


def marches(tiles: dict, tempo: dict) -> list[dict]:
    speed, rough = approved_tempo(tempo)
    g = M.Graph(tiles)
    out = []
    for a, b in M.ROUTES:
        r = g.shortest(M.county(tiles, a)["province"], M.county(tiles, b)["province"], rough)
        if r is None:
            raise ValueError(f"route {a}→{b} unreachable")
        cost, real, edges = r
        out.append({"from": a, "to": b, "km": round(real, 1), "edges": edges, "turns": math.ceil(cost / speed)})
    return out


def person_months(troops: int, turns: int, days_per_turn: float) -> float:
    return troops * turns * days_per_turn / 30.0


def grain_hu(troops: int, turns: int, days_per_turn: float, hu_per_month: float) -> int:
    return round(person_months(troops, turns, days_per_turn) * hu_per_month)


def arrival(loss_per_edge: float, edges: int) -> float:
    if not 0 <= loss_per_edge < 1 or edges < 0:
        raise ValueError("loss must be in [0,1) and edges >= 0")
    return (1 - loss_per_edge) ** edges


def wooden_ox_arrival(km: float) -> float:
    """木牛 모델: 짐 = 1인 1년치(365일분), 하루 20里, 수송자가 제 짐에서 왕복분을 먹는다. 0 미만은 0."""
    days_round_trip = 2 * km / (20 * HAN_LI_KM)
    return max(0.0, 1 - days_round_trip / 365)


def implied_loss(ratio_in_per_out: float, edges: int) -> float:
    return 1 - (1 / ratio_in_per_out) ** (1 / edges)


def siege_days(s: dict):
    if s["months"] is not None:
        return round(s["months"] * LUNAR_MONTH_DAYS)
    return s["days"]


def build(tiles: dict, tempo: dict, economy: dict) -> dict:
    ms = marches(tiles, tempo)
    expedition = []
    for m in ms:
        origin = commandery_households(economy, m["from"])
        for d in DAYS_PER_TURN:
            cells = {}
            for t in TROOPS:
                pm = person_months(t, m["turns"], d)
                cells[str(t)] = {"personMonths": round(pm), "perHundredHouseholds": None if origin["households"] is None else round(100 * pm / origin["households"], 1)}
            expedition.append({**m, **origin, "daysPerTurn": d, "marchDays": round(m["turns"] * d, 1), "byTroops": cells})
    grain = [{"from": m["from"], "to": m["to"], "turns": m["turns"], "ration": label, "huPerMonth": round(hu, 2),
              "hu": {str(d): grain_hu(RATION_TABLE_TROOPS, m["turns"], d, hu) for d in DAYS_PER_TURN}}
             for m in ms for label, hu, _ in RATIONS]
    edge_counts = sorted(set(EXTRA_EDGES) | {m["edges"] for m in ms})
    arrivals = [{"edges": e, "arrival": {str(p): round(arrival(p, e), 3) for p in LOSS_PER_EDGE}} for e in edge_counts]
    ox = [{"from": m["from"], "to": m["to"], "km": m["km"], "arrival": round(wooden_ox_arrival(m["km"]), 3)} for m in ms]
    thousand_li_edges = round(1000 * HAN_LI_KM / MEDIAN_EDGE_KM)
    anchors = [{"source": "xinan10zhong", "ratio": 64, "km": round(1000 * HAN_LI_KM, 1), "edges": thousand_li_edges,
                "impliedLossPerEdge": round(implied_loss(64, thousand_li_edges), 3),
                "woodenOxArrival": round(wooden_ox_arrival(1000 * HAN_LI_KM), 3)}]
    sieges = []
    for s in SIEGES:
        days = siege_days(s)
        sieges.append({**s, "approxDays": days, "turns": {str(d): None if days is None else math.ceil(days / d) for d in DAYS_PER_TURN}})
    candidates = [{"turns": t, "days": {str(d): round(t * d, 1) for d in DAYS_PER_TURN}} for t in SIEGE_TURN_CANDIDATES]
    return {"status": STATUS, "expedition": expedition, "grain": grain, "arrivals": arrivals, "woodenOx": ox,
            "anchors": anchors, "sieges": sieges, "siegeCandidates": candidates}


def _d(x: float) -> str:
    return f"{x:g}"


def render(r: dict) -> str:
    L = []
    L.append("### A1. 원정 인·월과 출발 郡 戶數 (편도 행군만)")
    L.append("")
    L.append("| 구간 | 소요 순 | 1순=N일 | 행군 일수 | 출발 郡 | 郡 戶數 | " + " | ".join(f"{t:,}명" for t in TROOPS) + " |")
    L.append("|---|---|---|---|---|---|" + "---|" * len(TROOPS))
    for e in r["expedition"]:
        hh = "UNKNOWN" if e["households"] is None else f"{e['households']:,}"
        cells = []
        for t in TROOPS:
            c = e["byTroops"][str(t)]
            pct = "UNKNOWN" if c["perHundredHouseholds"] is None else f"{c['perHundredHouseholds']}"
            cells.append(f"{c['personMonths']:,} ({pct})")
        L.append(f"| {e['from']}→{e['to']} | {e['turns']} | {_d(e['daysPerTurn'])} | {_d(e['marchDays'])} | {e['commandery']} | {hh} | " + " | ".join(cells) + " |")
    L.append("")
    L.append(f"### A2. 원정 곡물 소요(斛) — 병력 {RATION_TABLE_TROOPS:,}명, 편도 행군만")
    L.append("")
    L.append("| 구간 | 소요 순 | 1인 월 식량 후보 | " + " | ".join(f"1순={_d(d)}일" for d in DAYS_PER_TURN) + " |")
    L.append("|---|---|---|" + "---|" * len(DAYS_PER_TURN))
    for g in r["grain"]:
        L.append(f"| {g['from']}→{g['to']} | {g['turns']} | {g['ration']} | " + " | ".join(f"{g['hu'][str(d)]:,}" for d in DAYS_PER_TURN) + " |")
    L.append("")
    L.append("### B1. 간선당 손실 후보별 도착 비율")
    L.append("")
    L.append("| 보급선 간선 수 | " + " | ".join(f"손실 {p:.0%}/간선" for p in LOSS_PER_EDGE) + " |")
    L.append("|---|" + "---|" * len(LOSS_PER_EDGE))
    for a in r["arrivals"]:
        L.append(f"| {a['edges']} | " + " | ".join(f"{a['arrival'][str(p)]:.1%}" for p in LOSS_PER_EDGE) + " |")
    L.append("")
    L.append("### B2. 木牛 모델 도착 비율(1인 1년치 적재 · 하루 20里 · 왕복분을 짐에서 먹음)")
    L.append("")
    L.append("| 구간 | 경로 km | 도착 비율 |")
    L.append("|---|---|---|")
    for o in r["woodenOx"]:
        L.append(f"| {o['from']}→{o['to']} | {o['km']} | {o['arrival']:.1%} |")
    L.append("")
    L.append("### B3. 사료 기준점 역산")
    L.append("")
    L.append("| 사료 | 투입 : 도착 | 거리 km | 간선 수(중앙값 32 km) | 그 비율을 내는 간선당 손실 | 같은 거리의 木牛 모델 도착 비율 |")
    L.append("|---|---|---|---|---|---|")
    for a in r["anchors"]:
        L.append(f"| {SOURCES[a['source']]['book']} 「千里…率十餘鍾致一石」 | {a['ratio']} : 1 이상 | {a['km']} | {a['edges']} | {a['impliedLossPerEdge']:.1%} 이상 | {a['woodenOxArrival']:.1%} |")
    L.append("")
    L.append("### C1. 사료상 포위·대치 기간의 순 환산")
    L.append("")
    L.append("| 사례 | 유형 | 사료 기간 | 약 일수 | " + " | ".join(f"1순={_d(d)}일" for d in DAYS_PER_TURN) + " | 결말 | 출처 |")
    L.append("|---|---|---|---|" + "---|" * len(DAYS_PER_TURN) + "---|---|")
    for s in r["sieges"]:
        if s["approxDays"] is None:
            span, days, turns = "UNKNOWN(「連月」)", "UNKNOWN", ["UNKNOWN"] * len(DAYS_PER_TURN)
        else:
            span = (f"{s['months']}개월" if s["months"] is not None else f"{s['days']}일") + (" 이상" if s["lowerBound"] else "")
            days = f"{s['approxDays']}" + ("+" if s["lowerBound"] else "")
            turns = [f"{s['turns'][str(d)]}" + ("+" if s["lowerBound"] else "") for d in DAYS_PER_TURN]
        L.append(f"| {s['name']} | {s['kind']} | {span} | {days} | " + " | ".join(turns) + f" | {s['outcome']} | {s['book']} |")
    L.append("")
    L.append("### C2. 포위 기간 후보(순)의 세계 안 일수")
    L.append("")
    L.append("| 포위 기간 후보(순) | " + " | ".join(f"1순={_d(d)}일" for d in DAYS_PER_TURN) + " |")
    L.append("|---|" + "---|" * len(DAYS_PER_TURN))
    for c in r["siegeCandidates"]:
        L.append(f"| {c['turns']} | " + " | ".join(f"{_d(c['days'][str(d)])}일" for d in DAYS_PER_TURN) + " |")
    return "\n".join(L) + "\n"


def run() -> dict:
    return build(load_json(M.TILES), load_json(TEMPO), load_json(ECONOMY))


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args(argv)
    r = run()
    if args.json:
        print(json.dumps({**r, "sources": SOURCES}, ensure_ascii=False, indent=1))
    else:
        sys.stdout.write(render(r))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
