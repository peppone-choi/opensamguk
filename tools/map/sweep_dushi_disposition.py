#!/usr/bin/env python3
"""讀史方輿紀要의 「名+縣」 대목에서 **시대별 처분**을 뽑는다.

dushi-fangyu-sweep-v1 은 「그 縣이 이 지리지에 나오는가」만 셌다(名+縣 926 / 이름만 136 / 0건 118).
그런데 讀史方輿紀要는 縣마다 歷代 연혁을 적기 때문에, 대목을 읽으면 **220 년 전후의 처분**이
그대로 나온다 — 「晉省衙縣入粟邑」(晉이 합쳤으니 220 년엔 있었다), 「後漢爲絳邑縣」(정본 絳은
절단이다), 「後漢仍爲霸陵縣」.

그래서 이 도구는 히트 수가 아니라 **처분 문구**를 뽑는다. 판정은 하지 않는다 — 문구를 사람이
읽고 판정하도록 근거를 모아 주는 것이 전부다.

주의
  - 대조는 글자 단위다. 「名+縣」 으로 좁혀도 `白水縣`·`故白水縣` 처럼 앞에 글자가 붙거나
    다른 縣 설명 안에 인용될 수 있으므로 문구를 반드시 읽어야 한다.
  - 처분 표지가 없는 히트가 대다수다(수계·교통 서술). 표지가 든 대목만 남긴다.
  - 0 건은 이 지리지에 그 표기로 없다는 뜻이다. 부재의 증명이 아니다.

    python3 tools/map/sweep_dushi_disposition.py --index <skill>/corpus/index.db
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
)
OUT = ROOT / "data/curated/han/dushi-disposition-v1.json"
BOOK = "讀史方輿紀要"

# 시대 표지 + 처분 동사. 「晉省」 처럼 붙어 나오기도 하고 「晉…省」 으로 떨어지기도 한다.
ERA = "(?:後漢|后汉|東漢|东汉|漢末|汉末|曹魏|魏|蜀|吳|吴|晉|晋|三國|三国)"
ACT = "(?:置|省|廢|废|復置|复置|改|徙|屬|属|仍爲|仍为|爲|为|分置|併|并)"
MARK = re.compile(ERA + r"[^。]{0,12}?" + ACT)


def load_volumes(index: Path):
    connection = sqlite3.connect(index)
    rows = []
    for volume, title, body in connection.execute(
        "SELECT vol, title, body FROM vol WHERE book = ?", (BOOK,)
    ):
        rows.append((volume, title, zlib.decompress(body).decode("utf-8")))
    if not rows:
        raise SystemExit(f"{BOOK} 권을 하나도 읽지 못했다: {index}")
    return rows


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--index", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    fold = make_normalizer(group=True)
    volumes = [(v, t, fold(text)) for v, t, text in load_volumes(args.index)]
    raw = {v: text for v, _, text in load_volumes(args.index)}

    adjudicated = set()
    for name, key in ADJUDICATED:
        for row in json.loads((ROOT / f"data/curated/han/{name}.json").read_text(encoding="utf-8"))[key]:
            adjudicated.add((fold(row["commandery"]), fold(row.get("canonName") or row["name"])))

    sweep = json.loads(SWEEP.read_text(encoding="utf-8"))
    rows = []
    for candidate in sweep["counties"]:
        key = (fold(candidate["commandery"]), fold(candidate["name"]))
        if key in adjudicated:
            continue
        name = fold(candidate["name"])
        needle = name + "縣" if not name.endswith("縣") else name
        passages = []
        for volume, title, folded in volumes:
            for match in re.finditer(re.escape(needle), folded):
                window = folded[max(0, match.start() - 90): match.end() + 130]
                if not MARK.search(window):
                    continue
                offset = max(0, match.start() - 90)
                passages.append({
                    "volume": volume,
                    "title": title,
                    "quote": raw[volume][offset: match.end() + 130],
                })
        # 같은 문구가 권마다 반복되므로 앞쪽 4 개만 남긴다
        seen, kept = set(), []
        for item in passages:
            fingerprint = item["quote"][:40]
            if fingerprint in seen:
                continue
            seen.add(fingerprint)
            kept.append(item)
            if len(kept) == 4:
                break
        rows.append({
            "commandery": candidate["commandery"],
            "name": candidate["name"],
            "priorVerdict": candidate["priorVerdict"],
            "jinshu": candidate["jinshu"],
            "searched": needle,
            "dispositionPassages": kept,
        })

    counts = {"withDisposition": sum(1 for r in rows if r["dispositionPassages"]),
              "withoutDisposition": sum(1 for r in rows if not r["dispositionPassages"]),
              "candidates": len(rows)}
    doc = {
        "schemaVersion": 1,
        "ledgerId": "dushi-disposition-v1",
        "note": ("아직 심사되지 않은 결손 후보에 대해 讀史方輿紀要 121 권에서 「名+縣」 대목 중 "
                 "시대 표지(後漢·漢末·魏·蜀·吳·晉)와 처분 동사(置·省·廢·改·屬)가 함께 든 것만 뽑았다. "
                 "**판정이 아니라 판정 근거다** — 문구를 읽어야 한다. 대조는 글자 단위이므로 앞뒤에 다른 글자가 "
                 "붙은 인용이 섞이고, 처분 표지가 없는 히트(수계·교통 서술)는 버렸다."),
        "source": {"book": BOOK, "volumes": len(volumes)},
        "method": {
            "needle": "정규화한 縣 이름 + 縣",
            "normalizer": "tools/map/audit_county_coverage.make_normalizer(group=True) — 코퍼스와 이름을 같은 축에 눕힌다",
            "dedupe": "같은 문구가 권마다 반복되므로 앞 40 자 지문으로 중복을 걷고 4 개까지만 남긴다",
            "limits": "0 건은 이 지리지에 그 표기로 없다는 뜻이다. 부재의 증명이 아니다.",
        },
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
