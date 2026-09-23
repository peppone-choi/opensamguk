#!/usr/bin/env python3
# SIZE_OK: 추출 문법·대조 규칙·검증이 한 원장 계약이다 — 갈라 놓으면 「지우지 않고 찍지 않는다」를 한눈에 못 본다.
"""철·목재·말(+소금 특산) 산지 원장을 굽는다 — GH #776 / OPENSAM-256, 스펙 §9.1–§9.3.

두 단계다.

1. **추출**(`--refresh-extracts`): 사료 원문에서 「有鐵/有铁/出铁」(後漢書 郡國志 卷109–113)과
   「鐵官·鹽官·馬官·木官」(漢書 地理志 上, 위키소스 顏師古註본 raw)을 뽑아
   `data/curated/han/resource-site-source-extracts-v1.json` 에 인용문째 적는다. 원문
   (`data/corpus/`, 위키소스 raw)은 저장소에 없다(gitignore) — 그래서 이 단계는 로컬에서만 돈다.
   後漢書 卷42의 90년 장례 목재 조달3郡도 별도 사건 근거로 추출한다. 지속 생산지로 결속하지 않는다.
2. **대조**(기본 동작): 커밋된 추출본을 `data/map/han-tiles.json` 의 jurisdictionRecords·
   commanderyRecords 에 붙여 `data/curated/han/resource-sites-v1.json` 을 만든다.
   `--check` 는 이 단계를 다시 돌려 커밋본과 바이트 단위로 비교한다 — 네트워크·원문 없이 돈다.

대조 규칙 — **지우지 않고, 찍지 않는다.**
- 後漢 縣은 두 축으로 붙인다: (가) 같은 郡 안의 이름 일치, (나) administrative-place-bindings 의
  CHGIS 결속. 동명 縣(平陽·漆·陽城)은 郡國志 절 제목의 郡으로 가른다.
- 前漢 縣은 郡 이름이 後漢과 같을 때만 붙인다. 이름만 같고 郡이 다르면 후보만 적고 id 는 null.
- 못 붙인 항목도 사유와 함께 남긴다.

두 번째 증인: administrative-units.json 의 ctext 繁體 인용문에서 郡별 「有鐵/出鐵」 개수를 세어
위키소스 코퍼스 추출과 郡 단위로 맞춘다(출처가 다른 축). 縣별 원문 신원과
縣명부터 철 주기까지도 대조한다. 명시한 세 이문은 판정하지 않으며, S1 원문 검토를 대체하지 않는다.

사용:
    python3 tools/map/build_resource_sites.py            # 원장 재생성
    python3 tools/map/build_resource_sites.py --check    # 커밋본이 최신인지
    python3 tools/map/build_resource_sites.py --refresh-extracts \
        --hhs-dir data/corpus --dlz-raw /path/to/dlz-upper.txt
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import unicodedata
from collections import Counter
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
TILES_PATH = ROOT / "data/map/han-tiles.json"
UNITS_PATH = ROOT / "data/curated/han/administrative-units.json"
BINDINGS_PATH = ROOT / "data/curated/han/administrative-place-bindings-v1.json"
SIMPLIFICATION_PATH = ROOT / "data/curated/han/han-name-simplification-v1.json"
EXTRACTS_PATH = ROOT / "data/curated/han/resource-site-source-extracts-v1.json"
LEDGER_PATH = ROOT / "data/curated/han/resource-sites-v1.json"

QUOTE_MAX = 40
IRON_MARK = re.compile(r"[有出][鐵铁]")
COLLATION_LINE = re.compile(r"頁.*行")
HHS_URL = "https://zh.wikisource.org/wiki/後漢書/卷{vol}"
DLZ_TITLE = "漢書顏師古註/地理志/地理"
DLZ_REVID = 2687363
DLZ_URL = f"https://zh.wikisource.org/w/index.php?title={DLZ_TITLE}&oldid={DLZ_REVID}"
DLZ_RAW_URL = f"https://zh.wikisource.org/w/index.php?action=raw&title={DLZ_TITLE}&oldid={DLZ_REVID}"

OFFICE_RESOURCE = {"鐵官": "IRON", "鹽官": "SALT", "馬官": "HORSE", "木官": "WOOD"}
OTHER_OFFICES = ("工官", "服官", "橘官", "銅官", "金官")

# 地理志 표기 → 비교용 글자. 원문 표기(sourceName)는 그대로 보존한다.
DLZ_VARIANTS = {"亰": "京", "爲": "為", "嶲": "巂", "睆": "皖", "揚": "陽"}

# han-name-simplification-v1 은 郡國志 繁體 卷(110–112)에 나온 글자만 담는다. 地理志 縣名에만 나오는
# 글자를 여기서 보탠다(비교 전용, 원문 표기는 보존). 빠진 글자는 縣을 못 붙일 뿐 잘못 붙이지는 않는다.
EXTRA_T2S = {"鄭": "郑", "黽": "黾", "嚴": "严", "絳": "绛", "晉": "晋", "臨": "临", "廣": "广", "長": "长",
             "計": "计", "慮": "虑", "鹽": "盐", "瀆": "渎", "壽": "寿", "當": "当", "連": "连", "東": "东",
             "歷": "历", "陽": "阳", "陰": "阴", "鄉": "乡", "縣": "县", "國": "国", "漢": "汉", "會": "会",
             "樂": "乐", "濟": "济", "潁": "颍", "廬": "庐", "萊": "莱", "齊": "齐", "華": "华", "龍": "龙",
             "雲": "云", "養": "养", "澤": "泽", "門": "门", "陳": "陈", "魯": "鲁", "鉅": "巨", "勃": "渤"}

# 郡國志가 郡 이름 자리에 縣을 적은 경우 등, 사람이 본 별칭. 근거를 같이 적는다.
REVIEWED_ALIASES = {
    ("LATER_HAN", "魯國", "魯國"): {
        "name": "魯",
        "reason": "郡國志 魯國 첫 縣이 「魯國」으로 적혀 있다(郡治 魯縣). 같은 郡 안에 鲁县 이 하나뿐이다.",
    },
    ("FORMER_HAN", "河內郡", "隆慮"): {
        "name": "林慮",
        "reason": "郡國志 河內郡 林慮 주기 「故隆虑，殇帝改」(後漢書 卷109) — 殤帝 휘를 피해 고친 같은 縣이다.",
    },
}

# 前漢 郡 → 後漢 郡 승계. 郡國志 郡 머리글이 직접 말하는 것만 둔다(추출 단계가 원문에서 needle 을 확인한다).
COMMANDERY_SUCCESSIONS = [
    {"formerHan": "千乘郡", "laterHan": "樂安國", "volumeNumber": 112, "needle": "爲千乘，",
     "quote": "樂安國，高帝西平昌置，爲千乘，永元七年更名"},
    {"formerHan": "會稽郡", "laterHan": "吳郡", "volumeNumber": 112, "needle": "呉郡順帝分會稽置",
     "quote": "呉郡順帝分會稽置"},
    {"formerHan": "蜀郡", "laterHan": "蜀郡屬國", "volumeNumber": 113, "needle": "蜀郡属国故属西部都尉",
     "quote": "蜀郡属国故属西部都尉，延光元年以为属国都尉，别领四城"},
]
TRANSFER_NOTE = re.compile(r"[故本][屬属]([^。，]{1,3})(?:[。，]|$)")

# 縣 주기가 아닌 구절을 거르는 무늬(地理志 自注). 後漢 縣名 사전에 있으면 이 무늬보다 사전이 이긴다.
NOTE_PREFIX = re.compile(r"^(有|故|莽|在|屬|本|後|過郡|行|侯國|都尉|戶|縣|禹貢|又)")
NOTE_BODY = re.compile(r"(曰|所|入|至|徙|起|置|更名|居此|之|在|封)")
NOTE_SUFFIX = re.compile(r"(山|水|澤|宮|祠|關|鄉|亭|聚|藪|浸|川|國|邑|里)$")

HORSE_PASSAGES = [
    {
        "extractId": "hhs:004:HORSE:liangzhou-parks",
        "resource": "HORSE", "era": "LATER_HAN", "level": "REGION",
        "sourceName": "涼州", "sourceCommandery": None,
        "book": "後漢書", "volume": "卷004 和帝紀", "volumeNumber": 4,
        "quote": "詔有司省減內外廄及涼州諸苑馬",
        "needle": "省減內外廄及涼州諸苑馬",
        "url": "https://zh.wikisource.org/wiki/後漢書/卷4",
        "claim": "涼州에 관영 마원(苑)이 여럿 있었다. 苑의 縣 위치는 이 구절에 없다.",
    },
] + [
    {
        "extractId": f"hhs:115:HORSE:six-commanderies:{name}",
        "resource": "HORSE", "era": "LATER_HAN", "level": "COMMANDERY",
        "sourceName": name, "sourceCommandery": canonical,
        "book": "後漢書", "volume": "卷115 百官志二 羽林郎", "volumeNumber": 115,
        "quote": "常選漢陽、隴西、安定、北地、上郡、西河凡六郡良家補",
        "needle": "漢陽、隴西、安定、北地、上郡、西河凡六郡良家",
        "url": "https://zh.wikisource.org/wiki/後漢書/卷115",
        "claim": "羽林郎을 뽑는 기마 인력 6郡. 말 산지의 간접 근거다(목장 기록이 아니다).",
    }
    for name, canonical in (("漢陽", "漢陽郡"), ("隴西", "隴西郡"), ("安定", "安定郡"),
                            ("北地", "北地郡"), ("上郡", "上郡"), ("西河", "西河郡"))
]

UNKNOWN_RECORDS = [
    {
        "id": "UNKNOWN:WOOD:LATER_HAN",
        "resource": "WOOD", "era": "LATER_HAN", "level": "REGION",
        "sourceName": None, "sourceCommandery": None, "jurisdictionId": None, "commanderyId": None,
        "matchStatus": "UNKNOWN_NO_SOURCE", "confidence": "UNKNOWN", "evidence": None,
        "missing": "後漢 縣 단위 벌채지·지속 생산량은 미확정이다. 卷42의 90년 장례 목재 조달3郡은 별도 사건 근거로 보존한다. "
                   "han-tiles terrainLegend 10종(SEA·PLAIN·MOUNTAIN·RIVER·LAKE·DESERT·PLATEAU·BASIN·HILL·"
                   "OUT_OF_SCOPE)에 숲이 없다. 前漢 木官 1곳(蜀郡 嚴道)은 시대가 다른 근거다. 현대 식생을 "
                   "2~3세기 경관으로 단정하지 않는다(스펙 §9.3).",
    },
    {
        "id": "UNKNOWN:HORSE:PASTURE_SITES",
        "resource": "HORSE", "era": "LATER_HAN", "level": "COUNTY",
        "sourceName": None, "sourceCommandery": None, "jurisdictionId": None, "commanderyId": None,
        "matchStatus": "UNKNOWN_NO_SOURCE", "confidence": "UNKNOWN", "evidence": None,
        "missing": "목장(苑·牧師苑)의 縣 단위 위치. 後漢書 색인에서 「牧師苑」·「馬苑」 0건. 涼州諸苑·六郡은 "
                   "州·郡 단위 근거뿐이다.",
    },
    {
        "id": "UNKNOWN:FORMER_HAN:DILIZHI_LOWER",
        "resource": "IRON", "era": "FORMER_HAN", "level": "REGION",
        "sourceName": None, "sourceCommandery": None, "jurisdictionId": None, "commanderyId": None,
        "matchStatus": "UNKNOWN_NO_SOURCE", "confidence": "UNKNOWN", "evidence": None,
        "missing": "漢書 地理志 下편(武都~長沙國)의 班固 自注. 위키소스 顏師古註본은 上편(京兆尹~巴郡)뿐이고, "
                   "ctext 는 자동 수집을 막으며, wayback 의 ctext 下편 캡처는 自注가 빠진 판이다. "
                   "下편 鐵官·鹽官·馬官(隴西·北地·遼東 등)은 이 원장에 없다 — 없음이 아니라 미수집이다.",
    },
]


# ── 공통 ────────────────────────────────────────────────────────────────────────────────────

def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def dump_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, indent=1, sort_keys=False) + "\n"


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def strip_templates(text: str) -> str:
    """`{{…}}` 을 겹침까지 통째로 뺀다(劉昭 註가 `{{*|…}}` 안에 있다)."""
    out: list[str] = []
    depth = 0
    i = 0
    while i < len(text):
        if text.startswith("{{", i):
            depth += 1
            i += 2
        elif text.startswith("}}", i) and depth:
            depth -= 1
            i += 2
        else:
            if depth == 0:
                out.append(text[i])
            i += 1
    return "".join(out)


def clip(text: str) -> str:
    return text if len(text) <= QUOTE_MAX else text[: QUOTE_MAX - 1] + "…"


class Normalizer:
    def __init__(self, table: dict[str, str]):
        self.table = table

    def chars(self, text: str) -> str:
        out = []
        for c in text:
            c = DLZ_VARIANTS.get(c, c)
            out.append(self.table.get(c, EXTRA_T2S.get(c, c)))
        return "".join(out)

    def tile_name(self, name_ch: str) -> str:
        name = self.chars(name_ch)
        for suffix in ("侯国", "属国", "公国", "县"):
            if name.endswith(suffix) and len(name) > len(suffix):
                return name[: -len(suffix)]
        return name

    def commandery_base(self, name: str) -> str:
        name = self.chars(name)
        return name[:-1] if len(name) >= 2 and name[-1] in "郡国尹" else name


def unit_id(volume: int, group: str, ordinal: int) -> str:
    return f"hhs:{volume}:{group}:{ordinal:03d}"


# ── 1단계: 추출 ─────────────────────────────────────────────────────────────────────────────

def extract_later_han_iron(hhs_dir: Path, units_doc: dict) -> tuple[list[dict], list[dict], list[dict]]:
    by_line: dict[tuple[int, int], dict] = {}
    snapshots: dict[int, str] = {}
    for group in units_doc["groups"]:
        snapshots[group["sourceVolume"]] = group["sourceCitation"]["snapshotSha256"]
        for unit in group["units"]:
            by_line[(unit["sourceVolume"], unit["sourceCitation"]["line"])] = unit
    extracts: list[dict] = []
    collation: list[dict] = []
    transfers: list[dict] = []
    for volume in sorted(snapshots):
        path = hhs_dir / f"hhs-{volume}.txt"
        raw = path.read_bytes()
        digest = sha256_bytes(raw)
        if digest != snapshots[volume]:
            raise SystemExit(f"{path}: sha256 {digest} 가 administrative-units.json 스냅샷과 다르다")
        for number, line in enumerate(raw.decode("utf-8").split("\n"), 1):
            text = strip_templates(line).replace("-{", "").replace("}-", "").strip("　 ")
            moved = TRANSFER_NOTE.search(text)
            if moved and (volume, number) in by_line and not COLLATION_LINE.search(text):
                unit = by_line[(volume, number)]
                transfers.append({
                    "administrativeUnitId": unit_id(volume, unit["canonicalGroup"], unit["ordinal"]),
                    "sourceName": unit["sourceName"], "laterHanCommandery": unit["canonicalGroup"],
                    "formerParent": moved.group(1), "quote": clip(text),
                    "book": "後漢書", "volume": f"卷{volume} 郡國志", "url": HHS_URL.format(vol=volume),
                })
            if not IRON_MARK.search(text):
                continue
            if COLLATION_LINE.search(text):
                collation.append({"volume": volume, "line": number, "text": clip(text)})
                continue
            unit = by_line.get((volume, number))
            if unit is None:
                raise SystemExit(f"hhs-{volume}.txt:{number}: 「{text[:20]}」 을 縣에 귀속하지 못했다")
            uid = unit_id(volume, unit["canonicalGroup"], unit["ordinal"])
            extracts.append({
                "extractId": f"{uid}:IRON",
                "resource": "IRON", "era": "LATER_HAN", "level": "COUNTY",
                "sourceName": unit["sourceName"], "sourceCommandery": unit["canonicalGroup"],
                "administrativeUnitId": uid, "marker": IRON_MARK.search(text).group(),
                "attribution": "UNIT_LINE",
                "book": "後漢書", "volume": f"卷{volume} 郡國志", "volumeNumber": volume,
                "quote": clip(text), "locator": {"corpusPath": f"data/corpus/hhs-{volume}.txt", "line": number},
                "url": HHS_URL.format(vol=volume), "snapshotSha256": digest,
            })
    for succession in COMMANDERY_SUCCESSIONS:
        raw_text = (hhs_dir / f"hhs-{succession['volumeNumber']}.txt").read_text(encoding="utf-8")
        if succession["needle"] not in raw_text:
            raise SystemExit(f"郡 승계 근거 「{succession['needle']}」 이 卷{succession['volumeNumber']} 에 없다")
    return extracts, collation, transfers


def clean_dlz_paragraph(paragraph: str) -> str:
    paragraph = re.sub(r"<ref[^>]*>.*?</ref>", "", paragraph)
    paragraph = re.sub(r"\{\{校\|([^|}]*)\|[^}]*\}\}", r"\1", paragraph)
    paragraph = paragraph.replace("{{yw|", "").replace("{{*|【班固註】", "").replace("}}", "")
    paragraph = re.sub(r"（[^）]*）", "", paragraph)                      # 校改: 지운 글자
    paragraph = re.sub(r"〔([^〕一二三四五六七八九十〇]+)〕", r"\1", paragraph)  # 校改: 넣은 글자
    return paragraph.strip("　 ")


def is_county_clause(clause: str, lexicon: set[str], norm: Normalizer) -> str | None:
    if not clause or len(clause) > 5 or "官" in clause:
        return None
    if norm.chars(clause) in lexicon:
        return "LEXICON"
    glyphs = re.sub(r"〈[^〉]*〉", "字", clause)  # 〈巾弦〉 = 없는 글자 한 자
    if len(glyphs) > 3:
        return None
    if NOTE_PREFIX.search(clause) or NOTE_BODY.search(clause) or NOTE_SUFFIX.search(clause):
        return None
    return "PATTERN"


def extract_former_han_offices(raw: bytes, lexicon: set[str], norm: Normalizer) -> tuple[list[dict], dict]:
    text = raw.decode("utf-8")
    body = text.split("<onlyinclude>")[1].split("</onlyinclude>")[0]
    digest = sha256_bytes(raw)
    extracts: list[dict] = []
    other: Counter[str] = Counter()
    commanderies: list[str] = []
    for paragraph in body.split("\n"):
        if not paragraph.strip() or paragraph.startswith("　　〔") or paragraph.startswith(":"):
            continue
        paragraph = clean_dlz_paragraph(paragraph)
        commandery = re.match(r"([^，]{2,4}?)，", paragraph).group(1)
        commanderies.append(commandery)
        for office in OTHER_OFFICES:
            other[office] += paragraph.count(office)
        split = re.search(r"縣[一二三四五六七八九十]+：", paragraph)
        head, tail = paragraph[: split.start()], paragraph[split.end():]
        seen: Counter[str] = Counter()

        def emit(office: str, level: str, name: str, attribution: str, sentence: str) -> None:
            resource = OFFICE_RESOURCE[office]
            seen[(name, resource)] += 1
            suffix = "" if seen[(name, resource)] == 1 else f":{seen[(name, resource)]}"
            quote = sentence if sentence.startswith(name) else f"{name}：{sentence}"
            extracts.append({
                "extractId": f"hs:028A:{commandery}:{name}:{resource}{suffix}",
                "resource": resource, "era": "FORMER_HAN", "level": level,
                "sourceName": name, "sourceCommandery": commandery,
                "marker": office, "attribution": attribution,
                "book": "漢書", "volume": "卷028上 地理志上(顏師古註본 班固 自注)", "volumeNumber": 28,
                "quote": clip(quote), "url": DLZ_URL, "snapshotSha256": digest,
            })

        # 郡 머리 自注 — 「有鐵官，在黽池」「陽翟有工官」「有家馬官」
        head_clauses = [c for c in re.split(r"[，。]|〔[一二三四五六七八九十〇]+〕", head) if c]
        for index, clause in enumerate(head_clauses):
            for office in OFFICE_RESOURCE:
                if office not in clause:
                    continue
                following = head_clauses[index + 1] if index + 1 < len(head_clauses) else ""
                locative = re.match(r"^在(.{1,3})$", following)
                if locative:
                    emit(office, "COUNTY", locative.group(1), "HEAD_LOCATIVE", f"{clause}，{following}")
                else:
                    emit(office, "COMMANDERY", commandery, "HEAD_NOTE", clause)

        # 縣 목록 — 구절을 거꾸로 걸어 올라가 가장 가까운 縣名 구절을 찾는다
        clauses = [c for c in re.split(r"[，。]|〔[一二三四五六七八九十〇]+〕", tail) if c]
        for index, clause in enumerate(clauses):
            for office in OFFICE_RESOURCE:
                if office not in clause:
                    continue
                locative = re.match(r"^在(.{1,3})有", clause)
                if locative:
                    emit(office, "COUNTY", locative.group(1), "CLAUSE_LOCATIVE", clause)
                    continue
                for back in range(index - 1, -1, -1):
                    how = is_county_clause(clauses[back], lexicon, norm)
                    if how:
                        emit(office, "COUNTY", clauses[back], how, clause)
                        break
                else:
                    raise SystemExit(f"地理志 {commandery}: 「{clause}」 을 縣에 귀속하지 못했다")
    meta = {"snapshotSha256": digest, "revisionId": DLZ_REVID, "url": DLZ_URL, "rawUrl": DLZ_RAW_URL,
            "coverage": f"地理志 上편만: {commanderies[0]}~{commanderies[-1]} {len(commanderies)}郡",
            "commanderies": commanderies,
            "otherOfficesNotLedgered": {k: other[k] for k in OTHER_OFFICES}}
    return extracts, meta


def verify_horse_passages(hhs_dir: Path) -> dict[int, str]:
    digests: dict[int, str] = {}
    for passage in HORSE_PASSAGES:
        volume = passage["volumeNumber"]
        raw = (hhs_dir / f"hhs-{volume:03d}.txt").read_bytes()
        if passage["needle"] not in raw.decode("utf-8"):
            raise SystemExit(f"hhs-{volume:03d}.txt 에 「{passage['needle']}」 이 없다")
        digests[volume] = sha256_bytes(raw)
    return digests


def extract_wood_procurement(hhs_dir: Path) -> list[dict]:
    """Preserve the 90 CE procurement event without inferring production sites."""
    raw = (hhs_dir / "hhs-042.txt").read_bytes()
    text = raw.decode("utf-8")
    quote = "發常山、鉅鹿、涿郡柏黃腸雜木"
    if text.count(quote) != 1:
        raise SystemExit("hhs-042.txt: unique wood procurement passage missing")
    line = text[:text.index(quote)].count("\n") + 1
    paragraph = text.splitlines()[line - 1]
    headings = re.findall(r"^==([^=]+)==$", text[:text.index(quote)], re.MULTILINE)
    if (not headings or headings[-1] != "中山簡王焉"
            or "永元二年|90年" not in paragraph or "三郡不能備" not in paragraph):
        raise SystemExit("hhs-042.txt: wood procurement event context missing")
    return [{
        "extractId": f"hhs:042:WOOD:procurement-90:{name}",
        "resource": "WOOD", "era": "LATER_HAN", "level": "COMMANDERY",
        "sourceName": name, "sourceCommandery": name,
        "book": "後漢書", "volume": "卷042 光武十王列傳 中山簡王焉", "volumeNumber": 42,
        "quote": quote, "url": HHS_URL.format(vol=42),
        "attribution": "EVENT_PROCUREMENT", "marker": None, "eventYear": 90,
        "snapshotSha256": sha256_bytes(raw),
        "locator": {"corpusPath": "data/corpus/hhs-042.txt", "line": line},
        "claim": "永元二年(90) 中山簡王焉 장례의 목재 징발·조달 근거. 三郡不能備라고 이어진다. "
                 "지속 생산·벌채 縣·생산량·현 지도 郡 귀속은 확정하지 않는다.",
    } for name in ("常山", "鉅鹿", "涿郡")]


def build_extracts(hhs_dir: Path, dlz_raw: Path) -> dict:
    norm = Normalizer(load_json(SIMPLIFICATION_PATH)["table"])
    units_doc = load_json(UNITS_PATH)
    lexicon = {norm.chars(u["sourceName"]) for g in units_doc["groups"] for u in g["units"]}
    iron, collation, transfers = extract_later_han_iron(hhs_dir, units_doc)
    offices, dlz_meta = extract_former_han_offices(dlz_raw.read_bytes(), lexicon, norm)
    horse_digests = verify_horse_passages(hhs_dir)
    horses = []
    for passage in HORSE_PASSAGES:
        record = {k: v for k, v in passage.items() if k not in ("needle",)}
        record["attribution"] = "CURATED_PASSAGE"
        record["marker"] = None
        record["snapshotSha256"] = horse_digests[passage["volumeNumber"]]
        horses.append(record)
    return {
        "schemaVersion": 1,
        "catalogId": "resource-site-source-extracts-v1",
        "generator": "tools/map/build_resource_sites.py --refresh-extracts",
        "note": "사료 원문에서 뽑은 산지 주기. 원문(data/corpus, 위키소스 raw)은 gitignore 라 저장소에 없다 — "
                "이 파일이 인용문을 품어 --check 가 네트워크 없이 돈다. 손으로 고치지 않는다.",
        "sources": {
            "laterHanJunguozhi": {"book": "後漢書 郡國志 卷109–113", "corpus": "data/corpus/hhs-109..113.txt",
                                  "noteHandling": "{{…}} 劉昭 註 제거, 「頁…行」 校勘記 줄 제외"},
            "formerHanDilizhi": dlz_meta,
        },
        "collationNotes": collation,
        "countyTransferNotes": transfers,
        "commanderySuccessions": [{k: v for k, v in c.items() if k != "needle"} | {
            "book": "後漢書", "volume": f"卷{c['volumeNumber']} 郡國志", "url": HHS_URL.format(vol=c["volumeNumber"])}
            for c in COMMANDERY_SUCCESSIONS],
        "extracts": iron + offices + horses + extract_wood_procurement(hhs_dir),
    }


# ── 2단계: 대조 ─────────────────────────────────────────────────────────────────────────────

def cross_witness_counts(units_doc: dict) -> dict[str, int]:
    """ctext 繁體 인용문(다른 출처)에서 郡별 有鐵/出鐵 개수."""
    counts: dict[str, int] = {}
    for group in units_doc["groups"]:
        quote = "".join(e["quote"] for e in group["evidence"]).replace("\n", "")
        n = len(IRON_MARK.findall(quote))
        if n:
            counts[group["canonicalGroup"]] = n
    return counts


# Exact textual differences in the committed witnesses, scoped to one source unit.
# These are comparison pairs, not an adjudication of which reading is correct.
COUNTY_WITNESS_VARIANTS = {
    "hhs:110:魯國:001": ("鲁国古奄国有大庭氏庫有铁", "鲁国奄国有大庭氏庫有铁"),
    "hhs:111:下邳國:001": ("下邳本属东海有葛嶧山本嶧阳山有铁", "下邳本属东海葛嶧山本嶧阳山有铁"),
    "hhs:112:廬江郡:008": ("皖有铁", "晥有铁"),
}


def validate_county_witnesses(extracts_doc: dict) -> list[str]:
    """Check source identity and county-to-iron clauses, not historical correctness.

    Only whitespace, punctuation and character forms are folded. The three exact
    variant pairs remain explicitly unresolved readings; no county/place binding
    is inferred here. Group totals alone cannot detect same-group reassignment.
    """
    norm = Normalizer(load_json(SIMPLIFICATION_PATH)["table"])

    def text(value: str) -> str:
        value = norm.chars(value).translate(str.maketrans("鐵殤韋", "铁殇韦"))
        return "".join(c for c in value if not c.isspace()
                       and not unicodedata.category(c).startswith("P"))

    units = {}
    for group in load_json(UNITS_PATH)["groups"]:
        witness = text("".join(e["quote"] for e in group["evidence"]))
        for unit in group["units"]:
            uid = f"hhs:{unit['sourceVolume']}:{unit['canonicalGroup']}:{unit['ordinal']:03d}"
            units[uid] = (unit, witness)
    errors = []
    consumed_markers = set()
    for extract in extracts_doc["extracts"]:
        if (extract["era"], extract["resource"], extract["level"]) != ("LATER_HAN", "IRON", "COUNTY"):
            continue
        uid = extract.get("administrativeUnitId")
        if uid not in units:
            errors.append(f"county identity: unknown administrativeUnitId {uid}")
            continue
        unit, witness = units[uid]
        citation = unit["sourceCitation"]
        expected = {
            "sourceName": unit["sourceName"], "sourceCommandery": unit["canonicalGroup"],
            "volumeNumber": unit["sourceVolume"], "extractId": uid + ":IRON",
            "snapshotSha256": citation["snapshotSha256"], "url": citation["sourceUrl"],
            "locator": {"corpusPath": citation["corpusPath"], "line": citation["line"]},
        }
        different = [key for key, value in expected.items() if extract.get(key) != value]
        if different:
            errors.append(f"county identity: {uid}: {', '.join(different)}")
        quote = text(extract["quote"])
        marker = IRON_MARK.search(quote)
        if not marker or not quote.startswith(text(unit["sourceName"])):
            errors.append(f"county witness: {uid}: missing county prefix or iron marker")
            continue
        if text(extract.get("marker") or "") != marker.group():
            errors.append(f"county witness: {uid}: marker differs from quoted iron marker")
        prefix = quote[:marker.end()]
        variant = COUNTY_WITNESS_VARIANTS.get(uid)
        comparison = variant[1] if variant and prefix == variant[0] else prefix
        if witness.count(comparison) != 1:
            errors.append(f"county witness: {uid}: county-to-iron clause must match once: {prefix}")
        else:
            position = (unit["canonicalGroup"], witness.index(comparison) + len(comparison))
            if position in consumed_markers:
                errors.append(f"county witness: {uid}: iron marker already attributed")
            consumed_markers.add(position)
    return errors


def build_ledger(extracts_doc: dict) -> dict:
    norm = Normalizer(load_json(SIMPLIFICATION_PATH)["table"])
    tiles = load_json(TILES_PATH)
    units_doc = load_json(UNITS_PATH)
    bindings = {b["administrativeUnitId"]: b for b in load_json(BINDINGS_PATH)["administrativeUnits"]}
    jurisdictions = {j["id"]: j for j in tiles["jurisdictionRecords"]}
    commanderies = {c["id"]: c for c in tiles["commanderyRecords"]}
    commandery_by_name: dict[str, str] = {}
    commandery_by_base: dict[str, list[str]] = {}
    for c in tiles["commanderyRecords"]:
        commandery_by_name[norm.chars(c["nameCh"])] = c["id"]
        commandery_by_base.setdefault(norm.commandery_base(c["nameCh"]), []).append(c["id"])
    by_name: dict[str, list[dict]] = {}
    for j in tiles["jurisdictionRecords"]:
        if j["kind"] == "COUNTY":
            by_name.setdefault(norm.tile_name(j["nameCh"]), []).append(j)

    # (縣名, 後漢 郡 id) → 前漢 때 속했던 郡(基名) — 郡國志 「故屬X」 주기
    attested_transfer: dict[tuple[str, str], dict] = {}
    for note in extracts_doc["countyTransferNotes"]:
        later = commandery_by_name.get(norm.chars(note["laterHanCommandery"]))
        if later:
            attested_transfer[(norm.chars(note["sourceName"]), later, norm.commandery_base(note["formerParent"]))] = note
    successions: dict[str, list[dict]] = {}
    for c in extracts_doc["commanderySuccessions"]:
        successions.setdefault(norm.commandery_base(c["formerHan"]), []).append(c)

    def evidence(x: dict) -> dict:
        ev = {"book": x["book"], "volume": x["volume"], "quote": x["quote"], "url": x["url"],
              "snapshotSha256": x["snapshotSha256"]}
        if "locator" in x:
            ev["locator"] = x["locator"]
        return ev

    entries: list[dict] = []
    for x in extracts_doc["extracts"]:
        entry: dict[str, Any] = {
            "id": x["extractId"], "resource": x["resource"], "era": x["era"], "level": x["level"],
            "sourceName": x["sourceName"], "sourceCommandery": x["sourceCommandery"],
            "sourceMarker": x["marker"], "countyAttribution": x["attribution"],
            "jurisdictionId": None, "commanderyId": None,
            "matchStatus": None, "matchReason": None, "confidence": None,
            "evidence": evidence(x),
        }
        if "claim" in x:
            entry["claim"] = x["claim"]
        if x["attribution"] == "EVENT_PROCUREMENT":
            entry.update(eventYear=x["eventYear"], matchStatus="UNREVIEWED_EVENT_PROCUREMENT",
                         confidence="LOW", matchReason="특정 사건의 조달 기록. 현재 지도 귀속·생산지 미검토")
            entries.append(entry)
            continue
        era, group = x["era"], x["sourceCommandery"]
        if x["level"] == "REGION":
            entry.update(matchStatus="REGION_ONLY", confidence="MEDIUM",
                         matchReason="州 단위 구절이다. 郡·縣 id 를 붙이지 않는다.")
            entries.append(entry)
            continue
        if era == "LATER_HAN":
            cid = commandery_by_name.get(norm.chars(group))
        else:
            ids = commandery_by_base.get(norm.commandery_base(group), [])
            cid = ids[0] if len(ids) == 1 else None
        entry["commanderyId"] = cid
        if x["level"] == "COMMANDERY":
            if cid:
                entry.update(matchStatus="MATCHED_COMMANDERY",
                             confidence="HIGH" if era == "LATER_HAN" else "MEDIUM",
                             matchReason="郡 이름 일치" + ("" if era == "LATER_HAN" else "(前漢 郡名 → 後漢 同名 郡, 경계 변동 미검증)"))
            elif len(heirs := successions.get(norm.commandery_base(group), [])) == 1 and \
                    (heir := commandery_by_name.get(norm.chars(heirs[0]["laterHan"]))):
                entry["commanderyId"] = heir
                entry["transferEvidence"] = {k: heirs[0][k] for k in ("book", "volume", "quote", "url")}
                entry.update(matchStatus="MATCHED_COMMANDERY_SUCCESSION", confidence="MEDIUM",
                             matchReason=f"郡國志가 郡 승계를 직접 적었다: 「{heirs[0]['quote']}」(경계 변동 미검증)")
            else:
                entry.update(matchStatus="UNMATCHED_NO_COMMANDERY", confidence="LOW",
                             matchReason=f"han-tiles commanderyRecords 에 「{group}」 에 해당하는 郡이 없다(前漢 郡 폐지·개명)")
            entries.append(entry)
            continue

        alias = REVIEWED_ALIASES.get((era, group, x["sourceName"]))
        name = norm.chars(alias["name"] if alias else x["sourceName"])
        candidates = by_name.get(name, [])
        in_commandery = [j for j in candidates if cid and j["commanderyId"] == cid]
        if len(candidates) > 1:
            entry["homonymCandidates"] = [
                {"jurisdictionId": j["id"], "commandery": commanderies[j["commanderyId"]]["nameCh"]}
                for j in candidates]
        bound = None
        if era == "LATER_HAN":
            binding = bindings.get(x["administrativeUnitId"], {})
            entry["placeBindingStatus"] = binding.get("joinStatus")
            place = (binding.get("selectedCandidate") or {}).get("physicalPlaceId", "")
            bound = jurisdictions.get(place.rsplit(":", 1)[-1]) if place else None

        if len(in_commandery) == 1:
            j = in_commandery[0]
            if bound and bound["id"] != j["id"]:
                entry.update(matchStatus="CONFLICT", confidence="LOW",
                             matchReason=f"이름 축은 {j['id']}, CHGIS 결속 축은 {bound['id']} — 사람이 판정해야 한다")
            else:
                entry["jurisdictionId"] = j["id"]
                if alias:
                    entry.update(matchStatus="MATCHED_REVIEWED_ALIAS", confidence="MEDIUM", matchReason=alias["reason"])
                elif era == "FORMER_HAN":
                    entry.update(matchStatus="MATCHED", confidence="MEDIUM",
                                 matchReason="前漢 郡名·縣名이 後漢 同名 郡 안의 縣과 일치(縣治 이동 미검증)")
                else:
                    axes = "이름+CHGIS 결속 두 축 일치" if bound else "같은 郡 안 이름 일치(CHGIS 결속 없음)"
                    if len(candidates) > 1:
                        axes += f"; 동명 縣 {len(candidates)}곳을 郡으로 갈랐다"
                    entry.update(matchStatus="MATCHED", confidence="HIGH" if bound else "MEDIUM", matchReason=axes)
        elif len(in_commandery) > 1:
            entry.update(matchStatus="AMBIGUOUS", confidence="LOW",
                         matchReason="같은 郡 안에 동명 縣이 둘 이상이다")
        elif bound:
            entry["jurisdictionId"] = bound["id"]
            entry.update(matchStatus="MATCHED_PARENT_DIFFERS", confidence="MEDIUM",
                         matchReason=f"CHGIS 결속으로 붙였다. 타일 원장은 이 縣을 "
                                     f"{commanderies[bound['commanderyId']]['nameCh']} 밑에 둔다(220년 단면의 郡 분할)")
        elif era == "FORMER_HAN" and (attested := [
                (j, why) for j in candidates
                if (why := attested_transfer.get((name, j["commanderyId"], norm.commandery_base(group)))
                    or next((c for c in successions.get(norm.commandery_base(group), [])
                             if commandery_by_name.get(norm.chars(c["laterHan"])) == j["commanderyId"]), None))]) \
                and len(attested) == 1:
            j, why = attested[0]
            entry["jurisdictionId"] = j["id"]
            entry["laterHanCommanderyId"] = j["commanderyId"]
            entry["transferEvidence"] = {k: why[k] for k in ("book", "volume", "quote", "url")}
            entry.update(matchStatus="MATCHED_TRANSFER_ATTESTED", confidence="MEDIUM",
                         matchReason=f"郡國志가 郡 이동을 직접 적었다: 「{why['quote']}」")
        elif candidates:
            entry["candidateJurisdictionIds"] = [j["id"] for j in candidates]
            entry.update(matchStatus="NAME_ONLY_PARENT_UNVERIFIED", confidence="LOW",
                         matchReason="이름이 같은 縣이 다른 郡에 있다. 같은 곳인지 검증 전이라 id 를 붙이지 않는다(僑置·동명이지)")
        else:
            reason = f"han-tiles jurisdictionRecords 에 「{x['sourceName']}」 縣이 없다"
            if entry.get("placeBindingStatus"):
                reason += f"; place-bindings joinStatus={entry['placeBindingStatus']}"
            entry.update(matchStatus="UNMATCHED_NO_JURISDICTION", confidence="LOW", matchReason=reason)
        entries.append(entry)

    first_mention: dict[tuple, str] = {}
    for entry in entries:
        if entry["level"] != "COUNTY":
            continue
        key = (entry["resource"], entry["era"], entry["sourceCommandery"], entry["sourceName"])
        if key in first_mention:
            entry["duplicateMentionOf"] = first_mention[key]
        else:
            first_mention[key] = entry["id"]

    entries.extend(json.loads(json.dumps(UNKNOWN_RECORDS)))

    # 두 번째 증인 — 郡별 개수
    witness = cross_witness_counts(units_doc)
    ours = Counter(e["sourceCommandery"] for e in entries
                   if e["resource"] == "IRON" and e["era"] == "LATER_HAN" and e["level"] == "COUNTY")
    disagreements = sorted(g for g in set(witness) | set(ours) if witness.get(g, 0) != ours.get(g, 0))

    def tally(keys: tuple[str, ...]) -> dict[str, int]:
        c = Counter("/".join(str(e[k]) for k in keys) for e in entries)
        return dict(sorted(c.items()))

    missing_states = {
        "UNKNOWN_NO_SOURCE": "DOCUMENTED_GAP",
        "UNREVIEWED_EVENT_PROCUREMENT": "EVENT_NOT_PERSISTENT_SITE",
        "UNMATCHED_NO_JURISDICTION": "UNBOUND_JURISDICTION",
    }
    missing_rows = [
        {"entryId": e["id"], "resource": e["resource"], "era": e["era"],
         "matchStatus": e["matchStatus"], "disposition": missing_states[e["matchStatus"]],
         "reason": e.get("missing") or e.get("matchReason")}
        for e in entries if e["matchStatus"] in missing_states
    ]

    return {
        "schemaVersion": 1,
        "catalogId": "resource-sites-v1",
        "generator": "tools/map/build_resource_sites.py",
        "issue": "GH #776 / OPENSAM-256",
        "note": "철·말·목재 산지와 소금 특산의 문헌 근거 원장. 게임 수치가 아니다 — 어느 縣·郡에 사료가 산지를 "
                "적었는지만 담는다. FORMER_HAN 은 기원후 2년 단면(漢書 地理志)이라 後漢 시나리오에 쓸 때 시기 표시를 "
                "지우지 않는다. 못 붙인 항목과 UNKNOWN 을 지우지 않는다.",
        "inputs": {
            "extracts": "data/curated/han/resource-site-source-extracts-v1.json",
            "tiles": "data/map/han-tiles.json",
            "administrativeUnits": "data/curated/han/administrative-units.json",
            "placeBindings": "data/curated/han/administrative-place-bindings-v1.json",
            "simplification": "data/curated/han/han-name-simplification-v1.json",
        },
        "sources": extracts_doc["sources"],
        "collationNotes": extracts_doc["collationNotes"],
        "crossWitness": {
            "axis": "administrative-units.json 의 ctext 繁體 인용문(위키소스 코퍼스와 다른 출처)에서 郡별 有鐵/出鐵 개수",
            "witnessTotal": sum(witness.values()), "ledgerTotal": sum(ours.values()),
            "disagreeingCommanderies": disagreements,
        },
        "counts": {
            "entries": len(entries),
            "byResourceEra": tally(("resource", "era")),
            "byResourceEraMatchStatus": tally(("resource", "era", "matchStatus")),
            "byLevel": tally(("level",)),
            "distinctSites": len([e for e in entries if "duplicateMentionOf" not in e
                                  and not e["matchStatus"].startswith("UNKNOWN")]),
            "missingRows": len(missing_rows),
        },
        "missingRows": missing_rows,
        "entries": entries,
    }


def validate_ledger(ledger: dict) -> list[str]:
    errors: list[str] = []
    tiles = load_json(TILES_PATH)
    jids = {j["id"] for j in tiles["jurisdictionRecords"]}
    cids = {c["id"] for c in tiles["commanderyRecords"]}
    seen: set[str] = set()
    for e in ledger["entries"]:
        if e["id"] in seen:
            errors.append(f"{e['id']}: id 중복")
        seen.add(e["id"])
        if e["resource"] not in ("IRON", "HORSE", "WOOD", "SALT"):
            errors.append(f"{e['id']}: resource {e['resource']}")
        if e["era"] not in ("LATER_HAN", "FORMER_HAN") or e["level"] not in ("COUNTY", "COMMANDERY", "REGION"):
            errors.append(f"{e['id']}: era/level")
        if e["jurisdictionId"] is not None and e["jurisdictionId"] not in jids:
            errors.append(f"{e['id']}: jurisdictionId {e['jurisdictionId']} 가 han-tiles 에 없다")
        if e["commanderyId"] is not None and e["commanderyId"] not in cids:
            errors.append(f"{e['id']}: commanderyId {e['commanderyId']} 가 han-tiles 에 없다")
        if not e["matchStatus"] or not e["confidence"]:
            errors.append(f"{e['id']}: matchStatus/confidence 없음")
        if e["jurisdictionId"] is None and e["commanderyId"] is None and not (e.get("matchReason") or e.get("missing")):
            errors.append(f"{e['id']}: 못 붙인 사유가 없다")
        if e["matchStatus"].startswith("UNKNOWN"):
            continue
        ev = e["evidence"]
        if not ev or not ev.get("book") or not ev.get("volume") or not ev.get("url"):
            errors.append(f"{e['id']}: evidence 결손")
        elif len(ev["quote"]) > QUOTE_MAX:
            errors.append(f"{e['id']}: quote {len(ev['quote'])}자 > {QUOTE_MAX}")
    if ledger["crossWitness"]["disagreeingCommanderies"]:
        errors.append(f"두 번째 증인 불일치: {ledger['crossWitness']['disagreeingCommanderies']}")
    required_missing_ids = {
        e["id"] for e in ledger["entries"]
        if e["matchStatus"] in {"UNKNOWN_NO_SOURCE", "UNREVIEWED_EVENT_PROCUREMENT", "UNMATCHED_NO_JURISDICTION"}
    }
    recorded_missing_ids = [row["entryId"] for row in ledger.get("missingRows", [])]
    if set(recorded_missing_ids) != required_missing_ids or len(recorded_missing_ids) != len(required_missing_ids):
        errors.append("산지 결손 목록이 살아 있는 항목 집합과 다르다")
    if ledger["counts"].get("missingRows") != len(required_missing_ids):
        errors.append("산지 결손 수가 결손 목록과 다르다")
    return errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--check", action="store_true", help="쓰지 않고 커밋본과 비교한다")
    parser.add_argument("--refresh-extracts", action="store_true", help="원문에서 추출본을 다시 만든다")
    parser.add_argument("--hhs-dir", type=Path, default=ROOT / "data/corpus")
    parser.add_argument("--dlz-raw", type=Path, help=f"위키소스 raw 저장본: {DLZ_RAW_URL}")
    parser.add_argument("--extracts", type=Path, default=EXTRACTS_PATH)
    parser.add_argument("--output", type=Path, default=LEDGER_PATH)
    args = parser.parse_args(argv)

    stale: list[str] = []
    if args.refresh_extracts:
        if not args.dlz_raw:
            parser.error("--refresh-extracts 에는 --dlz-raw 가 필요하다")
        text = dump_json(build_extracts(args.hhs_dir, args.dlz_raw))
        if args.check:
            if not args.extracts.exists() or args.extracts.read_text(encoding="utf-8") != text:
                stale.append(str(args.extracts))
        else:
            args.extracts.write_text(text, encoding="utf-8")

    extracts = load_json(args.extracts)
    errors = validate_county_witnesses(extracts)
    ledger = build_ledger(extracts)
    errors.extend(validate_ledger(ledger))
    if errors:
        print("\n".join(f"ERROR {e}" for e in errors), file=sys.stderr)
        return 1
    text = dump_json(ledger)
    if args.check:
        if not args.output.exists() or args.output.read_text(encoding="utf-8") != text:
            stale.append(str(args.output))
        if stale:
            print("STALE: " + ", ".join(stale) + " — tools/map/build_resource_sites.py 를 다시 돌려라", file=sys.stderr)
            return 1
    else:
        args.output.write_text(text, encoding="utf-8")
    c = ledger["counts"]
    print(f"resource-sites-v1: {c['entries']} entries {'(check OK)' if args.check else '(written)'}")
    for key, value in c["byResourceEraMatchStatus"].items():
        print(f"  {key}: {value}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
