#!/usr/bin/env python3
"""宋書 州郡志(卷035–038)로 결손 후보의 漢代 내력을 읽는 네 번째 축이다.

**왜 이 축인가.** 宋書 州郡志는 縣마다 제 내력을 한 줄로 적는데, 그 서술이 前漢·後漢·魏·吳·晉을
이름으로 지목한다 — 「丹楊令，漢舊縣」, 「溧陽令，漢舊縣。吳省爲屯田。晉武帝太康元年復立」,
「永興令，漢舊餘暨縣，吳更名」. 그래서 히트 수가 아니라 **그 한 줄이 220 년을 가른다**.

덤으로 이 志는 정본을 직접 언급한다 — 「續漢志無」, 「二漢無」, 「晉太康三年地志有」. 이건
郡國志 카탈로그에 대한 외부 대조라 결손 목록 자체의 검산이 된다.

**경계.** 宋書 州郡志는 宋(420–479)에 있던 縣만 표제로 싣는다. 그 전에 없어진 漢縣은 제 표제가
없고 남의 縣 연혁 안에 인용될 뿐이다 — 그래서 표제 부재는 폐지의 증명이 아니라 **이 축의 한계**다.

조회
  - 표제는 `:名令，` / `:名相，` / `:名長，` 꼴이다(單字 縣은 「縣」 이 붙기도 한다).
  - 표제가 없으면 본문 언급을 따로 모은다 — 「漢舊X縣，吳更名」 이 그 縣의 운명을 알려 준다.
  - 繁簡은 공용 표로 양쪽을 눕힌다. 표 범위 밖 이문은 놓친다.

    python3 tools/map/sweep_songshu_provenance.py --index <ext skill>/corpus/index.db
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
)
OUT = ROOT / "data/curated/han/songshu-provenance-v1.json"
VOLUMES = ("035", "036", "037", "038")
OFFICES = ("令", "相", "長")


def load_volumes(index: Path):
    connection = sqlite3.connect(index)
    rows = []
    for volume, title, body in connection.execute(
        "SELECT vol, title, body FROM vol WHERE book = '宋書'"
    ):
        if not any(v in volume for v in VOLUMES):
            continue
        rows.append((volume, title, zlib.decompress(body).decode("utf-8")))
    if len(rows) != len(VOLUMES):
        raise SystemExit(f"宋書 州郡志 {len(VOLUMES)} 권 중 {len(rows)} 권만 읽었다: {index}")
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
            for office in OFFICES:
                for head in (f":{name}{office}，", f":{name}縣{office}，"):
                    for match in re.finditer(re.escape(head), text):
                        end = text.find("\n", match.end())
                        entries.append({
                            "volume": volume, "title": title,
                            "entry": raw[match.start() + 1: end if end > 0 else match.end() + 200],
                        })
            for match in re.finditer(re.escape(name), text):
                window = text[max(0, match.start() - 40): match.end() + 60]
                if any(f":{name}{o}" in window for o in OFFICES):
                    continue
                if not re.search("(?:漢|汉|魏|吳|吴|晉|晋)", window):
                    continue
                mentions.append({
                    "volume": volume,
                    "quote": raw[max(0, match.start() - 40): match.end() + 60],
                })
        seen, kept = set(), []
        for item in mentions:
            if item["quote"][:28] in seen:
                continue
            seen.add(item["quote"][:28])
            kept.append(item)
            if len(kept) == 3:
                break
        rows.append({
            "commandery": candidate["commandery"],
            "name": candidate["name"],
            "priorVerdict": candidate["priorVerdict"],
            "songshu": "HEADWORD" if entries else ("MENTION_ONLY" if kept else "ABSENT"),
            "entries": entries[:3],
            "mentions": [] if entries else kept,
        })

    counts: dict[str, int] = {}
    for row in rows:
        counts[row["songshu"]] = counts.get(row["songshu"], 0) + 1
    doc = {
        "schemaVersion": 1,
        "ledgerId": "songshu-provenance-v1",
        "note": ("아직 심사되지 않은 결손 후보를 宋書 州郡志(卷035–038)에 대조한 결과다. 이 志는 縣마다 "
                 "前漢·後漢·魏·吳·晉을 이름으로 지목하는 연혁을 한 줄로 달기 때문에 그 줄이 220 년을 가른다. "
                 "HEADWORD 는 宋代까지 縣으로 남아 제 표제를 가진 것, MENTION_ONLY 는 남의 縣 연혁 안에서만 "
                 "언급되는 것, ABSENT 는 이 표기로 이 志에 없다는 뜻이다."),
        "method": {
            "headwordForms": "「:名令，」 「:名相，」 「:名長，」 및 單字 縣의 「:名縣令，」",
            "normalizer": "tools/map/audit_county_coverage.make_normalizer(group=True); 접은 길이가 원문과 1:1 임을 단언한다",
            "catalogCrosscheck": "이 志는 「續漢志無」·「二漢無」·「晉太康三年地志有」 로 정본을 직접 언급한다 — 결손 목록 자체의 검산이 된다",
            "limits": ("宋書 州郡志는 宋(420–479)에 있던 縣만 표제로 싣는다. 그 전에 없어진 漢縣은 표제가 없으므로 "
                       "ABSENT 는 폐지의 증명이 아니라 이 축의 한계다."),
        },
        "source": {"book": "宋書", "volumes": [v for v, _, _ in volumes]},
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
