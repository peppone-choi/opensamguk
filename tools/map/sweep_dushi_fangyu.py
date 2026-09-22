#!/usr/bin/env python3
"""郡國志 정본 1,180縣을 讀史方輿紀要 전수 조회해 세 번째 축을 만든다.

위키백과 축은 105 郡 중 15 곳만 덮었다(문서 대부분에 東漢 속현 목록이 없다). 讀史方輿紀要 는
歷代 州域을 縣 단위로 훑는 지리지라 커버리지가 훨씬 넓고, shiliao-ext 색인에 121 권 3.8 MB 가
들어 있어 로컬에서 전수 조회할 수 있다.

**이 축은 「그 縣 이 사서에 나오는가」를 재는 것이지 좌표를 주지 않는다.** 결손 縣 이 여기서
걸리면 그 縣 이 실재한다는 두 번째 증거이고, 안 걸리면 그 縣 이름으로는 이 지리지에 없다는 뜻일
뿐이다 — 부재의 증명이 아니다.

조회 규칙 (skill 의 경고를 따른다)
  - 코퍼스는 繁體다. 정본은 卷마다 繁/簡 이 섞이므로(卷113 계열이 簡體) 기존 繁→簡 표를
    **거꾸로** 써서 簡體 이름을 繁體 후보로 되돌린 뒤 둘 다 조회한다.
  - **한자 대조는 글자 단위라 허위 일치가 진짜로 난다.** 한 글자 이름(安·項·慎)은 아무 데나
    걸린다. 그래서 「名+縣」 을 주 축(strong)으로 세고, 이름만 나온 것은 따로(weak) 센다.
    strong 0 · weak 다수는 판정이 아니라 「봐야 한다」는 표시다.

    python3 tools/map/sweep_dushi_fangyu.py --index <ext skill>/corpus/index.db
"""
import argparse
import json
import re
import sqlite3
import sys
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CANON = ROOT / "data/curated/han/administrative-units.json"
TABLE = ROOT / "data/curated/han/han-name-simplification-v1.json"
OUT = ROOT / "data/curated/han/dushi-fangyu-sweep-v1.json"
BOOK = "讀史方輿紀要"


def load_volumes(index: Path) -> list[tuple[str, str, str]]:
    connection = sqlite3.connect(index)
    rows = []
    for book, volume, title, body in connection.execute(
        "SELECT book, vol, title, body FROM vol WHERE book = ?", (BOOK,)
    ):
        try:
            text = zlib.decompress(body).decode("utf-8")
        except Exception:
            text = body.decode("utf-8") if isinstance(body, (bytes, bytearray)) else str(body)
        rows.append((volume, title, text))
    if not rows:
        raise SystemExit(f"{BOOK} 권을 하나도 읽지 못했다: {index}")
    return rows


def make_folder(table: dict[str, str]):
    """繁→簡 글자표로 한 축에 눕힌다.

    손으로 簡→繁 후보를 만들면 표 밖 이문을 놓친다 — 통제 표본 雒陽 이 「名+縣」 0 건으로 나왔다.
    讀史方輿紀要 는 洛陽 으로 적는데 후보에는 雒陽 만 있었다. 공용 표는 雒→洛 을 담고 있으므로
    **코퍼스와 이름을 같은 표로 눕히면** 그런 이문이 저절로 맞는다.
    """
    mapping = {ord(k): v for k, v in table.items()}

    def fold(text: str) -> str:
        return text.translate(mapping)

    return fold


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--index", required=True, type=Path)
    args = parser.parse_args(argv)

    table = json.loads(TABLE.read_text(encoding="utf-8"))["table"]
    fold = make_folder(table)

    volumes = [(v, t, fold(text)) for v, t, text in load_volumes(args.index)]
    blob = "\n".join(text for _, _, text in volumes)
    # 표를 지나면 縣 은 县 이 된다. 두 꼴 모두 찾는다 — 표가 縣 을 담지 않는 판본도 있다.
    suffixes = ("县", "縣")
    canon = json.loads(CANON.read_text(encoding="utf-8"))

    rows, strong_hits, weak_only, missing = [], 0, 0, 0
    for group in canon["groups"]:
        for unit in group["units"]:
            name = unit["sourceName"]
            folded = fold(name)
            names = [folded]
            strong = sum(blob.count(f"{folded}{suffix}") for suffix in suffixes)
            weak = blob.count(folded)
            where = None
            if strong:
                needle = next(f"{folded}{s}" for s in suffixes if f"{folded}{s}" in blob)
                for volume, title, text in volumes:
                    if needle in text:
                        at = text.find(needle)
                        where = {
                            "volume": volume,
                            "title": title,
                            "quote": re.sub(r"\s+", " ", text[max(0, at - 40):at + 40]).strip(),
                        }
                        break
                strong_hits += 1
            elif weak:
                weak_only += 1
            else:
                missing += 1
            rows.append({
                "commandery": group["canonicalGroup"],
                "ordinal": unit["ordinal"],
                "name": name,
                "searched": names,
                "countyFormHits": strong,
                "bareNameHits": weak,
                "firstCountyFormHit": where,
            })

    totals = {
        "counties": len(rows),
        "withCountyForm": strong_hits,
        "bareNameOnly": weak_only,
        "noHit": missing,
        "volumesSearched": len(volumes),
    }
    doc = {
        "schemaVersion": 1,
        "ledgerId": "dushi-fangyu-sweep-v1",
        "note": (
            f"郡國志 정본 1,180縣을 {BOOK} {len(volumes)} 권에 전수 조회한 결과다. "
            "**좌표를 주지 않는다** — 그 縣 이 이 지리지에 나오는가만 잰다. "
            "「名+縣」 으로 걸린 것(countyFormHits)이 강한 증거이고, 이름만 걸린 것(bareNameHits)은 "
            "한자 대조가 글자 단위라 허위 일치가 섞인다 — 한 글자 이름은 아무 데나 걸린다. "
            "0 건은 이 지리지에 그 이름으로 없다는 뜻이지 기록에 없다는 뜻이 아니다."
        ),
        "source": {"book": BOOK, "index": str(args.index), "volumes": len(volumes)},
        "totals": totals,
        "counties": rows,
    }
    OUT.write_text(json.dumps(doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {OUT.relative_to(ROOT)} {json.dumps(totals, ensure_ascii=False)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
