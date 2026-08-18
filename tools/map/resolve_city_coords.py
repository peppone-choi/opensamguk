#!/usr/bin/env python3
"""도시 좌표 해석기 — Wikidata(CC0)에서 실제 위경도를 받아온다.

`data/map/geo-cities.json` 을 만든다. 게임 도시명 → 현대 비정 도시 → 실제 위경도.

    python3 tools/map/resolve_city_coords.py
    python3 tools/map/resolve_city_coords.py --check

**출처와 라이선스.** 좌표는 Wikidata `P625`이고 Wikidata 본문 데이터는 **CC0**다.
QID 를 함께 적어 사후 검증이 가능하게 한다. QID 를 코드에 손으로 박지 않는다 —
라벨로 조회해 받아온다(손으로 박은 QID 는 기억에 의존해 틀린다).

**CHGIS/TGAZ 는 사용 허용(ADR-LITE-039, 2026-08-18 사용자 지시).** 중국 본토 행정 치소는
CHGIS 를 쓴다 — `tools/map/build_han_places.py`. 이 스크립트는 CHGIS 커버리지 **밖**
(lon 94.10~122.01 / lat 19.94~41.84 초과)의 `EXTERNAL_PLACE`·`MARITIME_REMOTE_GATE`
— 한반도·왜·탐라·유구 — 를 Wikidata(CC0)로 채우는 용도로 남는다.
근거: `docs/superpowers/research/2026-08-18-chgis-coverage-and-place-taxonomy.md`.

**추정 금지.** 비정이 갈리는 지명과 도시가 아닌 것(강·저·흉노·남만·산월·오환·왜 등
이민족 지역)은 표에 넣지 않는다. 해석 실패는 `unresolved` 로 남기고 좌표를 지어내지
않는다. 미해결 도시는 좌표를 얻을 때까지 새 맵에 실리지 않는다.
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "data" / "map" / "geo-cities.json"
ENDPOINT = "https://query.wikidata.org/sparql"
UA = "opensamguk-map/1.0 (https://github.com/peppone-choi/opensamguk)"

CN, KR, VN, JP = "Q148", "Q884", "Q881", "Q17"

# 게임 도시명 → (현대 비정 영문 라벨, 국가 QID, 한자 원명)
# 논란 없는 비정만. 갈리는 것은 넣지 않는다 — 넣지 않은 것이 곧 "아직 모른다"이다.
CITIES: dict[str, tuple[str, str, str]] = {
    "낙양": ("Luoyang", CN, "洛陽"),
    "장안": ("Xi'an", CN, "長安"),
    "성도": ("Chengdu", CN, "成都"),
    "건업": ("Nanjing", CN, "建業"),
    "양양": ("Xiangyang", CN, "襄陽"),
    "업": ("Anyang", CN, "鄴"),
    "허창": ("Xuchang", CN, "許昌"),
    "장사": ("Changsha", CN, "長沙"),
    "남해": ("Guangzhou", CN, "南海郡"),
    "회계": ("Shaoxing", CN, "會稽"),
    "오": ("Suzhou", CN, "吳"),
    "진양": ("Taiyuan", CN, "晉陽"),
    "계": ("Beijing", CN, "薊"),
    "천수": ("Tianshui", CN, "天水"),
    "한중": ("Hanzhong", CN, "漢中"),
    "강주": ("Chongqing", CN, "江州"),
    "서주": ("Xuzhou", CN, "徐州"),
    "북해": ("Weifang", CN, "北海"),
    "무릉": ("Changde", CN, "武陵"),
    "영릉": ("Yongzhou", CN, "零陵"),
    "강하": ("Wuhan", CN, "江夏"),
    "여강": ("Lu'an", CN, "廬江"),
    "완": ("Nanyang", CN, "宛"),
    "초": ("Bozhou", CN, "譙"),
    "홍농": ("Sanmenxia", CN, "弘農"),
    "복양": ("Puyang", CN, "濮陽"),
    "평원": ("Dezhou", CN, "平原"),
    "계양": ("Chenzhou", CN, "桂陽"),
    "운남": ("Dali City", CN, "雲南"),
    "건녕": ("Qujing", CN, "建寧"),
    "자동": ("Mianyang", CN, "梓潼"),
    "덕양": ("Deyang", CN, "德陽"),
    "시상": ("Jiujiang", CN, "柴桑"),
    "교지": ("Hanoi", VN, "交趾"),
    "평양": ("Pyongyang", KR, "平壤"),
    "위례": ("Seoul", KR, "慰禮城"),
    "계림": ("Gyeongju", KR, "鷄林"),
    "사비": ("Buyeo County", KR, "泗沘"),
    "탐라": ("Jeju City", KR, "耽羅"),
}


def sparql(query: str, tries: int = 5) -> list[dict]:
    """공개 엔드포인트는 502/504/429 를 자주 뱉는다. 물러났다 다시 묻는다 —
    한 번 실패했다고 좌표를 비워두면 그 자리는 영영 UNKNOWN 으로 남는다."""
    import time
    url = ENDPOINT + "?" + urllib.parse.urlencode({"query": query, "format": "json"})
    req = urllib.request.Request(
        url, headers={"User-Agent": UA, "Accept": "application/sparql-results+json"})
    for k in range(tries):
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                return json.load(r)["results"]["bindings"]
        except urllib.error.HTTPError as e:
            if e.code not in (429, 500, 502, 503, 504) or k == tries - 1:
                raise
        except urllib.error.URLError:
            if k == tries - 1:
                raise
        time.sleep(5 * (k + 1))
    return []


def resolve() -> tuple[list[dict], list[str]]:
    """라벨+국가로 좌표를 조회한다. 후보가 여럿이면 실패로 남긴다(임의로 고르지 않는다)."""
    values = " ".join(
        f'("{name}" "{label}" wd:{country})' for name, (label, country, _) in CITIES.items()
    )
    query = f"""
SELECT ?key ?item ?coord WHERE {{
  VALUES (?key ?label ?country) {{ {values} }}
  ?item rdfs:label ?l ; wdt:P17 ?country ; wdt:P625 ?coord .
  FILTER(STR(?l) = ?label && LANG(?l) = "en")
}}"""
    hits: dict[str, set[tuple[str, float, float]]] = {}
    for b in sparql(query):
        key = b["key"]["value"]
        qid = b["item"]["value"].rsplit("/", 1)[-1]
        lon, lat = b["coord"]["value"].removeprefix("Point(").removesuffix(")").split()
        hits.setdefault(key, set()).add((qid, float(lon), float(lat)))

    rows, unresolved = [], []
    for name, (label, country, hanja) in CITIES.items():
        cand = hits.get(name, set())
        if len(cand) != 1:
            unresolved.append(f"{name} ({label}): 후보 {len(cand)}개 — 임의 선택하지 않음")
            continue
        qid, lon, lat = next(iter(cand))
        rows.append({
            "name": name, "hanja": hanja, "modern": label,
            "lon": round(lon, 5), "lat": round(lat, 5), "wikidata": qid,
        })
    rows.sort(key=lambda r: r["name"])
    return rows, unresolved


def build() -> dict:
    rows, unresolved = resolve()
    return {
        "_meta": {
            "source": "Wikidata P625 (CC0) — https://query.wikidata.org/sparql",
            "generator": "tools/map/resolve_city_coords.py",
            "note": "게임 도시명의 현대 비정 좌표. 비정이 갈리는 지명과 이민족 지역은 표에 없다.",
            "forbidden": "CHGIS/TGAZ 는 EULA 로 번들 금지(CLAUDE.md).",
            "resolved": len(rows), "unresolved": len(unresolved),
        },
        "cities": rows,
        "unresolved": unresolved,
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()
    blob = json.dumps(build(), ensure_ascii=False, indent=1) + "\n"
    if args.check:
        if not OUT.exists() or OUT.read_text() != blob:
            print(f"드리프트: {OUT.relative_to(ROOT)}")
            return 1
        print("좌표 일치.")
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(blob)
    m = json.loads(blob)["_meta"]
    print(f"wrote {OUT.relative_to(ROOT)} — 해결 {m['resolved']} / 미해결 {m['unresolved']}")
    for u in json.loads(blob)["unresolved"]:
        print("  ", u)
    return 0


if __name__ == "__main__":
    sys.exit(main())
