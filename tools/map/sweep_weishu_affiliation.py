#!/usr/bin/env python3
"""魏書 地形志로 북변 결손 후보의 後漢·晉 소속을 읽는 다섯 번째 축이다.

**왜 이 축인가.** 宋書 州郡志는 남조라 북변을 애초에 다루지 않아 132 건 중 12 건만 걸렸다
(songshu-provenance-adjudications-v1). 魏書 地形志는 그 대칭짝 — 北魏의 志라 북변을 덮고,
縣마다 前漢·後漢·晉 소속을 **명시적으로** 적는다.

    繁陽{{*|二漢屬，晉屬頓丘。真君六年併頓丘…}}
    列人{{*|前漢屬廣平，後漢屬，晉屬廣平。}}
    好畤{{*|郡治。前漢屬，後漢、晉罷，後復。}}
    靈武{{*|前漢屬北地，後漢罷，晉復…}}

그래서 한 줄이 220 년을 가른다 — 「後漢屬」·「二漢屬」·「晉屬」 은 존속, 「後漢罷」·「後漢、晉罷」 는
그 전 폐지다. 「二漢」 은 前漢·後漢 둘을 뜻하는 이 志의 상투어다.

**경계.** 코퍼스에 地形志 **上(卷106A)·下(卷106B)만 있고 中(第六)이 없다.** 中에 실린 州의 縣은
이 축에서 구조적으로 0 건이 된다 — 부재로 읽으면 안 된다.

    python3 tools/map/sweep_weishu_affiliation.py --index <ext skill>/corpus/index.db
"""
import argparse
import json
import re
import sqlite3
import sys
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))
from audit_county_coverage import make_normalizer  # noqa: E402

SWEEP = ROOT / "data/curated/han/jinshu-survival-sweep-v1.json"
ADJUDICATED = (
    ("survived-county-adjudications-v1", "adjudications"),
    ("abolished-verdict-corrections-v1", "corrections"),
    ("jinshu-token-adjudications-v1", "adjudications"),
    ("dushi-disposition-adjudications-v1", "adjudications"),
    ("songshu-provenance-adjudications-v1", "adjudications"),
    ("naming-artifact-false-absences-v1", "falseAbsences"),
)
OUT = ROOT / "data/curated/han/weishu-affiliation-v1.json"
# 소속·처분을 말하는 상투어. 「二漢」 은 前漢·後漢 둘을 뜻한다.
AFFIL = re.compile(r"(?:二漢|前漢|後漢|晉)[^。]{0,16}?(?:屬|罷|置|復|併|分)")


def load_volumes(index: Path):
    connection = sqlite3.connect(index)
    rows = [
        (volume, title, zlib.decompress(body).decode("utf-8"))
        for volume, title, body in connection.execute(
            "SELECT vol, title, body FROM vol WHERE book = '魏書' AND vol LIKE '%106%'"
        )
    ]
    if not rows:
        raise SystemExit(f"魏書 地形志를 읽지 못했다: {index}")
    return rows


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--index", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    fold = make_normalizer(group=True)
    volumes = load_volumes(args.index)
    folded = [(v, t, fold(text), text) for v, t, text in volumes]
    for _, _, f, raw in folded:
        assert len(f) == len(raw)

    adjudicated = set()
    for name, key in ADJUDICATED:
        for row in json.loads((ROOT / f"data/curated/han/{name}.json").read_text(encoding="utf-8"))[key]:
            adjudicated.add((fold(row["commandery"]), fold(row.get("canonName") or row["name"])))

    rows = []
    for candidate in json.loads(SWEEP.read_text(encoding="utf-8"))["counties"]:
        key = (fold(candidate["commandery"]), fold(candidate["name"]))
        if key in adjudicated:
            continue
        name = fold(candidate["name"])
        entries, mentions = [], []
        for volume, title, text, raw in folded:
            # 표제는 줄머리 '::' 또는 표제 구분자 '　　' 뒤에 오고 곧바로 註가 붙는다
            for head in (f"::{name}{{{{*|", f"　　{name}{{{{*|", f"::{name}\n", f"　　{name}\n"):
                for match in re.finditer(re.escape(head), text):
                    window = raw[match.start(): match.start() + 260]
                    entries.append({"volume": volume, "title": title, "entry": window})
            for match in re.finditer(re.escape(name), text):
                window = text[max(0, match.start() - 30): match.end() + 90]
                if not AFFIL.search(window):
                    continue
                mentions.append({"volume": volume,
                                 "quote": raw[max(0, match.start() - 30): match.end() + 90]})
        def dedupe(items, field, limit):
            seen, kept = set(), []
            for item in items:
                fingerprint = item[field][:34]
                if fingerprint in seen:
                    continue
                seen.add(fingerprint)
                kept.append(item)
                if len(kept) == limit:
                    break
            return kept
        entries = dedupe(entries, "entry", 3)
        rows.append({
            "commandery": candidate["commandery"],
            "name": candidate["name"],
            "priorVerdict": candidate["priorVerdict"],
            "weishu": "HEADWORD" if entries else ("MENTION_ONLY" if mentions else "ABSENT"),
            "entries": entries,
            "mentions": [] if entries else dedupe(mentions, "quote", 3),
        })

    counts: dict[str, int] = {}
    for row in rows:
        counts[row["weishu"]] = counts.get(row["weishu"], 0) + 1
    doc = {
        "schemaVersion": 1,
        "ledgerId": "weishu-affiliation-v1",
        "note": ("아직 심사되지 않은 결손 후보를 魏書 地形志에 대조한 결과다. 이 志는 縣마다 前漢·後漢·晉 소속을 "
                 "상투어로 명시한다 — 「二漢屬」·「後漢屬X」·「晉屬Y」 는 존속, 「後漢罷」·「後漢、晉罷」 는 그 전 폐지다. "
                 "HEADWORD 는 北魏까지 縣으로 남아 제 표제와 註를 가진 것이다."),
        "method": {
            "headwordForms": "줄머리 '::名{{*|' 또는 표제 구분자 '　　名{{*|' (註 없는 표제는 줄바꿈으로 끝난다)",
            "affiliationIdiom": "「二漢」 은 前漢·後漢 둘을 뜻하는 이 志의 상투어다",
            "normalizer": "tools/map/audit_county_coverage.make_normalizer(group=True); 접은 길이가 원문과 1:1 임을 단언한다",
            "corpusGap": ("코퍼스에 地形志 上(卷106A)·下(卷106B)만 있고 **中(第六)이 없다**. "
                          "中에 실린 州의 縣은 이 축에서 구조적으로 0 건이 되므로 ABSENT 를 부재로 읽으면 안 된다."),
        },
        "source": {"book": "魏書", "volumes": [v for v, _, _ in volumes]},
        "totals": counts,
        "counties": rows,
    }
    payload = json.dumps(doc, ensure_ascii=False, indent=2) + "\n"
    if args.check:
        current = OUT.read_text(encoding="utf-8") if OUT.exists() else ""
        if current != payload:
            print(f"STALE {OUT}")
            return 1
        print(f"OK {OUT}")
        return 0
    OUT.write_text(payload, encoding="utf-8")
    print(counts)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
