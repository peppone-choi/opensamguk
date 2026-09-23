#!/usr/bin/env python3
"""결손 후보가 220 년 이후까지 살아 있었나를 晉書 地理志로 재는 네 번째 축이다.

**왜 새 축이 필요한가.** gap-snapshot-reconciliation-v1 은 TGAZ 존속구간으로 「220 년 전 폐지」
71 건을 판정했는데, 그 구간은 **이름만 맞춰 붙인 것**이라 두 가지로 무너진다.

  1. 同名異地 — 遼西郡 陽樂 에 붙은 구간 23~29 는 좌표가 산동(119.81,37.15)이다. 遼西 陽樂 은
     hvd_87636(119.04,39.77) 이고 晉書 遼西郡 統縣三 「陽樂　肥如　海陽」 으로 살아 있다.
  2. TGAZ 는 한 縣 의 생애를 여러 레코드로 쪼갠다 — 全椒 는 -202~48 · 67~125 · 136~212 ·
     280~310 이 같은 좌표에 있다. 임의의 하나를 집으면 폐지 연도가 엉뚱해진다.

그래서 **220 년 존속 판정은 晉書 地理志 속현 목록으로 한다.** 晉 太康 편제는 魏 를 이어받은
것이므로 여기 이름이 있으면 220 년에 있었다고 볼 근거가 되고, 지도(220 년 단면)에 없다면
그것은 진짜 결손이다.

조회 규칙
  - 晉書 地理志는 卷014(司州~梁州·益州) · 卷015 다. `'''郡名'''` 뒤 `:` 줄이 속현 목록이다.
  - **목록 표기가 권마다 다르다.** 88 郡 은 　 로 구분하고 67 郡 은 붙여 쓴다(「壯武黔陬平昌昌安」).
    구분된 목록에서 토큰으로 맞은 것만 strong 이고, 붙여 쓴 목록의 부분일치는 weak 다 —
    「鄱陽樂安」 이 陽樂 을 물어오는 종류의 허위 일치가 실제로 난다.
  - 繁簡은 공용 표로 양쪽을 같은 축에 눕힌다. **표의 범위는 기존 원장에 쓰인 글자 한정이므로
    晉書 쪽 이문은 표 밖일 수 있다** — 0 건을 부재로 읽지 말 것.

    python3 tools/map/sweep_jinshu_survival.py --index <skill>/corpus/index.db
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

RECON = ROOT / "data/curated/han/gap-snapshot-reconciliation-v1.json"
OUT = ROOT / "data/curated/han/jinshu-survival-sweep-v1.json"
VOLUMES = ("014", "015")


def load_blocks(index: Path) -> list[dict]:
    connection = sqlite3.connect(index)
    blocks: list[dict] = []
    for volume, body in connection.execute(
        "SELECT vol, body FROM vol WHERE book LIKE '%晉書%'"
    ):
        if not any(v in volume for v in VOLUMES):
            continue
        text = zlib.decompress(body).decode("utf-8")
        for match in re.finditer(r"'''([一-鿿]{2,6}(?:郡|國|尹))'''(.*?)(?=''')", text, re.S):
            segment = re.sub(r"\{\{[^{}]*\}\}", "", match.group(2))
            segment = re.sub(r"<ref>.*?</ref>", "", segment, flags=re.S)
            segment = re.sub(r"<[^>]+>", "", segment)
            listing = "".join(
                line.strip().lstrip(":")
                for line in segment.split("\n")
                if line.strip().startswith(":")
            )
            listing = re.sub(r"\s+", " ", listing.replace("　", " ")).strip()
            if listing:
                blocks.append({"volume": volume, "commandery": match.group(1), "counties": listing})
    if not blocks:
        raise SystemExit(f"晉書 地理志 블록을 하나도 읽지 못했다: {index}")
    return blocks


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--index", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    # 이름에는 縣·侯國 접미사 제거가 필요하지만, 목록 전체에 걸면 마지막 글자가 잘린다.
    # 글자 접기만 하는 group=True 를 블록 쪽에 쓴다 — 길이가 1:1 이라 문맥 인덱스도 맞는다.
    normalize = make_normalizer()
    fold = make_normalizer(group=True)
    blocks = load_blocks(args.index)
    for block in blocks:
        block["folded"] = fold(block["counties"])
        assert len(block["folded"]) == len(block["counties"]), block["commandery"]
        block["tokens"] = (
            {normalize(t) for t in block["counties"].split(" ") if t}
            if " " in block["counties"]
            else set()
        )

    recon = json.loads(RECON.read_text(encoding="utf-8"))
    rows = []
    for candidate in recon["counties"]:
        name = normalize(candidate["name"])
        strong = [
            {"volume": b["volume"], "commandery": b["commandery"]}
            for b in blocks
            if name in b["tokens"]
        ]
        weak = [
            {"volume": b["volume"], "commandery": b["commandery"],
             "context": b["counties"][max(0, b["folded"].find(name) - 6):b["folded"].find(name) + len(name) + 6]}
            for b in blocks
            if not b["tokens"] and name and name in b["folded"]
        ]
        rows.append({
            "commandery": candidate["commandery"],
            "name": candidate["name"],
            "priorVerdict": candidate["verdict"],
            "jinshu": "TOKEN" if strong else ("SUBSTRING_ONLY" if weak else "ABSENT"),
            "strongMatches": strong,
            "weakMatches": weak,
        })

    counts: dict[str, int] = {}
    for row in rows:
        key = f"{row['priorVerdict']}/{row['jinshu']}"
        counts[key] = counts.get(key, 0) + 1

    doc = {
        "schemaVersion": 1,
        "ledgerId": "jinshu-survival-sweep-v1",
        "note": (
            "결손 후보 282 건을 晉書 地理志(卷014·015) 속현 목록에 대조한 결과다. TGAZ 존속구간은 이름만 맞춘 것이라 "
            "同名異地와 레코드 분할로 무너진다 — 이 축이 그것을 대신한다. "
            "TOKEN 은 　 로 구분된 목록에서 토큰으로 맞은 것이고, SUBSTRING_ONLY 는 붙여 쓴 목록의 부분일치라 "
            "읽어야 하는 것이며(「鄱陽樂安」 이 陽樂 을 물어온다), ABSENT 는 이 표기로 晉書 地理志에 없다는 뜻일 뿐 폐지의 증명이 아니다."
        ),
        "method": {
            "blocksRead": len(blocks),
            "tokenizableBlocks": sum(1 for b in blocks if b["tokens"]),
            "runTogetherBlocks": sum(1 for b in blocks if not b["tokens"]),
            "normalizer": "tools/map/audit_county_coverage.make_normalizer (공용 繁簡·異體字 표)",
            "foldTableScope": "표의 범위는 기존 원장에 쓰인 글자 한정이다. 晉書 쪽 이문은 표 밖일 수 있으므로 ABSENT 를 부재로 읽지 말 것.",
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
    for key in sorted(counts):
        print(f"{counts[key]:4d}  {key}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
