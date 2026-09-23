#!/usr/bin/env python3
"""위키백과 郡 문서에서 東漢 속현 목록을 수확해 郡國志 정본과 대조한다.

**위키백과는 정본이 아니라 두 번째 축이다.** 판정은 사료가 하고, 이 수확은 「정본과 어디가
다른가」를 드러내는 데 쓴다. 실제로 汝南郡 시험에서 37 대 37 중 35 가 맞고 둘이 형근자로
갈렸다(灈/灌, 成/城) — 繁簡 표가 잡지 못하는 축이다.

수확 방법
  - MediaWiki API 로 원문(wikitext)을 받는다. 한 번에 50 제목까지 되므로 105 郡이 세 번이면 끝난다.
  - 목록은 「…領三十七縣：平輿、新陽、…、城父。」 꼴이다. 東漢 문단의 것만 쓴다.
  - `{{僻字|㶏|…}}` 같은 틀은 **지우지 않고 첫 인자를 꺼낸다** — 틀을 지우면 그 글자가 사라진다.
  - 목록이 없는 문서가 많다(雁門郡은 治所만 적는다). 없으면 없다고 적는다 — 지어내지 않는다.

    python3 tools/map/harvest_wikipedia_commanderies.py [--out PATH]
"""
import argparse
import json
import re
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/map"))
import audit_county_coverage as acc  # noqa: E402

CANON = ROOT / "data/curated/han/administrative-units.json"
TABLE = ROOT / "data/curated/han/han-name-simplification-v1.json"
OUT = ROOT / "data/curated/han/wikipedia-commandery-crosscheck-v1.json"
API = "https://zh.wikipedia.org/w/api.php"
AGENT = "opensamguk-research/1.0 (Han commandery county cross-check)"

# 「領三十七縣：…。」 — 앞의 시기 표현은 문서마다 다르다.
LIST = re.compile(r"領([一二三四五六七八九十百]+)縣[：:]\s*([^。]+)。")
# 한 문서에 西漢·東漢·西晉 목록이 나란히 있다. 첫 번째를 집으면 西漢 을 집는다(汝南郡에서 실제로
# 女陰·女陽 같은 漢書 표기를 집었다). 郡國志 기준연도가 順帝 永和五年이므로 그 맥락의 목록만 쓰고,
# 어느 것인지 가려지지 않으면 **없다고 적는다** — 잘못된 목록으로 정본을 흔드는 것보다 낫다.
EASTERN_HAN_MARKS = ("順帝", "永和", "東漢", "东汉")
# 틀은 지우지 않고 첫 인자만 남긴다.
RARE = re.compile(r"\{\{\s*僻字\s*\|\s*([^|}]+)[^}]*\}\}")
LINK = re.compile(r"\[\[(?:[^\]|]*\|)?([^\]|]+)\]\]")
REF = re.compile(r"<ref[^>]*>.*?</ref>|<ref[^>]*/>", re.S)
TEMPLATE = re.compile(r"\{\{[^{}]*\}\}")
# 縣 이름이 아닌 조각을 거른다. 위키 문서의 목록 자리에는 통계 문장(「有4667戶」)과 줄바꿈된
# 다른 목록이 섞여 들어온다. 縣 이름은 한자 1~4 자다.
NAME_OK = re.compile(r"^[\u4e00-\u9fff\u3400-\u4dbf]{1,4}$")

NUM = {"一": 1, "二": 2, "三": 3, "四": 4, "五": 5, "六": 6, "七": 7, "八": 8, "九": 9}


def chinese_number(text: str) -> int | None:
    if not text:
        return None
    total, section = 0, 0
    for char in text:
        if char in NUM:
            section = NUM[char]
        elif char == "十":
            total += (section or 1) * 10
            section = 0
        elif char == "百":
            total += (section or 1) * 100
            section = 0
        else:
            return None
    return total + section


def clean(fragment: str) -> str:
    fragment = REF.sub("", fragment)
    fragment = RARE.sub(r"\1", fragment)
    fragment = LINK.sub(r"\1", fragment)
    # 남은 틀만 지운다 — 僻字 는 이미 위에서 글자를 꺼냈다.
    while TEMPLATE.search(fragment):
        fragment = TEMPLATE.sub("", fragment)
    return fragment


def fetch(titles: list[str]) -> dict[str, str]:
    query = urllib.parse.urlencode({
        "action": "query", "prop": "revisions", "rvprop": "content",
        "rvslots": "main", "format": "json", "titles": "|".join(titles),
    })
    request = urllib.request.Request(f"{API}?{query}", headers={"User-Agent": AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        payload = json.load(response)
    out = {}
    for page in payload.get("query", {}).get("pages", {}).values():
        revisions = page.get("revisions")
        if revisions:
            out[page["title"]] = revisions[0]["slots"]["main"]["*"]
    # 표제어가 넘겨주기면 normalized 로 온다 — 원래 이름으로 되돌린다.
    for item in payload.get("query", {}).get("normalized", []):
        if item["to"] in out:
            out[item["from"]] = out[item["to"]]
    return out


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, default=OUT)
    args = parser.parse_args(argv)

    fold = acc.make_normalizer()
    table = json.loads(TABLE.read_text(encoding="utf-8"))["table"]
    canon = json.loads(CANON.read_text(encoding="utf-8"))
    groups = [(g["canonicalGroup"], [u["sourceName"] for u in g["units"]]) for g in canon["groups"]]

    pages: dict[str, str] = {}
    titles = [name for name, _ in groups]
    for start in range(0, len(titles), 40):
        pages.update(fetch(titles[start:start + 40]))
        time.sleep(1)

    rows, no_list = [], []
    for name, canon_units in groups:
        text = pages.get(name)
        if not text:
            no_list.append({"commandery": name, "reason": "PAGE_NOT_FETCHED"})
            continue
        body = clean(text)
        matches = list(LIST.finditer(body))
        if not matches:
            no_list.append({"commandery": name, "reason": "NO_COUNTY_LIST"})
            continue
        # 앞 300 자에 東漢 표지가 있는 목록만 쓴다. 여러 개면 마지막(가장 나중 시기) 것.
        eastern = [
            m for m in matches
            if any(mark in body[max(0, m.start() - 300):m.start()] for mark in EASTERN_HAN_MARKS)
        ]
        if not eastern:
            no_list.append({"commandery": name, "reason": "LIST_PRESENT_BUT_NOT_EASTERN_HAN"})
            continue
        match = eastern[-1]
        declared = chinese_number(match.group(1))
        raw_units = [part.strip() for part in re.split(r"[、,，]", match.group(2)) if part.strip()]
        wiki_units = [u[:-1] if u.endswith("縣") and len(u) > 1 else u for u in raw_units]
        dropped = [u for u in wiki_units if not NAME_OK.match(u)]
        wiki_units = [u for u in wiki_units if NAME_OK.match(u)]
        wiki_fold = {fold(u): u for u in wiki_units}
        canon_fold = {fold(u): u for u in canon_units}
        only_wiki = sorted(wiki_fold[k] for k in wiki_fold.keys() - canon_fold.keys())
        only_canon = sorted(canon_fold[k] for k in canon_fold.keys() - wiki_fold.keys())

        # 공용 간화표의 범위는 「정본·타일·런타임 지도·나무위키 원장에 실제로 쓰인 글자만」이다.
        # 위키백과는 그 범위 밖이라, 표에 없는 글자 **한 자만** 다른 짝은 진짜 차이가 아닐 수 있다.
        # 길이가 같고 한 자만 다를 때로 좁힌다 — 그러지 않으면 완전히 다른 이름끼리 짝지어진다
        # (江夏郡의 襄 과 南新市 가 그렇게 묶였다).
        def differs_by_one(a: str, b: str) -> str | None:
            if len(a) != len(b):
                return None
            diff = [(x, y) for x, y in zip(a, b) if x != y]
            if len(diff) != 1:
                return None
            x, y = diff[0]
            outside = x not in table and x not in table.values()
            return f"{a} / {b}" if outside else None

        near = []
        for a in only_wiki:
            for b in only_canon:
                pair = differs_by_one(a, b)
                if pair:
                    near.append(pair)
        rows.append({
            "commandery": name,
            "droppedFragments": dropped,
            "possiblyTableScopeArtifact": sorted(near),
            "wikipediaDeclared": declared,
            "wikipediaListed": len(wiki_units),
            "canonUnits": len(canon_units),
            "agreed": sorted(canon_fold[k] for k in wiki_fold.keys() & canon_fold.keys()),
            "onlyInWikipedia": only_wiki,
            "onlyInCanon": only_canon,
        })

    totals = {
        "commanderies": len(groups),
        "withWikipediaList": len(rows),
        "withoutWikipediaList": len(no_list),
        "agreed": sum(len(r["agreed"]) for r in rows),
        "onlyInWikipedia": sum(len(r["onlyInWikipedia"]) for r in rows),
        "onlyInCanon": sum(len(r["onlyInCanon"]) for r in rows),
    }
    doc = {
        "schemaVersion": 1,
        "ledgerId": "wikipedia-commandery-crosscheck-v1",
        "note": (
            "위키백과 郡 문서의 東漢 속현 목록을 續漢書 郡國志 정본과 대조한 결과다. "
            "**위키백과는 정본이 아니라 두 번째 축이다** — 어긋난 곳이 곧 오류라는 뜻이 아니라 "
            "들여다볼 자리라는 뜻이다. 형근자(灈/灌·成/城)처럼 繁簡 표가 잡지 못하는 차이가 여기서 드러난다. "
            "목록이 없는 문서가 많다(雁門郡은 治所만 적는다) — 없으면 없다고 적고 채우지 않는다. "
            "공용 간화표의 범위는 정본·타일·런타임 지도·나무위키 원장에 쓰인 글자뿐이라 위키백과는 그 밖이다 — "
            "표에 없는 글자에서 갈린 것은 `possiblyTableScopeArtifact` 로 표시했고, 진짜 차이라는 뜻이 아니다."
        ),
        "source": {"api": API, "userAgent": AGENT},
        "totals": totals,
        "withoutList": no_list,
        "commanderies": rows,
    }
    args.out.write_text(json.dumps(doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {args.out.relative_to(ROOT)} {json.dumps(totals, ensure_ascii=False)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
