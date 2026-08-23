#!/usr/bin/env python3
# SIZE_OK: One source grammar owns heading recovery, identity extraction, and count auditing.
# noqa: SIZE_OK — these stateful parsing invariants must be reviewed as one contract.
from __future__ import annotations

import hashlib
import html
import json
import re
from collections import Counter
from pathlib import Path
from typing import Final, NotRequired, TypedDict

EXPECTED_GROUP_COUNT: Final = 105
EXPECTED_UNIT_COUNT: Final = 1180
VOLUMES: Final = (109, 110, 111, 112, 113)
VOLUME_GROUP_COUNTS: Final = {109: 7, 110: 15, 111: 13, 112: 19, 113: 51}
CTEXT_VOLUME_SLUGS: Final = {
    109: "yi",
    110: "er",
    111: "san",
    112: "si",
    113: "wu",
}
CORPUS_SHA256: Final = {
    109: "d1119e73b55efcbe639a0ad8e13256fe65081dd91ad24f488ef11bb256228818",
    110: "54a16e588eb68d8e8df0f4c8d164aa781ae1b40658ae53993f08a768313e962d",
    111: "78baef4b029d8af0d2b7255c69c97d81d5c140ad2b9db9ed4abe11dd9ed66476",
    112: "47c577c0f9f8cbce40ed069b8521cf3efa3a46233ddf6560c25ff273ba8407a3",
    113: "55e8450051ffbca24be3b4dbc8661fb2bfd9ac97e4b161a5072d313dda021142",
}
CTEXT_SHA256: Final = {
    109: "78918515718b1979e7908b5696c7b84d0103328c4db785ea3e17096d4d17a315",
    110: "924be1df57882124e4f67a37ccdaea0b58585bd6b7653b6d61baf8d1b1ba8545",
    111: "4e8401d231cec62668d293f3a85cf11902f39942f47fe01e8982cd7c8579b329",
    112: "77585548c019b9944e2b94394be50b8535fc221ba467a90433f1142d55576688",
    113: "b0a86c7b530bd92924ae2b2d6dde9e38bb7bf9d26906248f9bf4434ef05e619b",
}
GUIZI_WITNESS_SHA256: Final = "8c73aa6dfa50593ddfc410d404bfe68e9df3eee8a01cfd97baad5dbd9a672ed4"

CANONICAL_GROUPS: Final = (  # noqa: SIM905 - keep the reviewed source order human-auditable
    "河南尹 河內郡 河東郡 弘農郡 京兆尹 左馮翊 右扶風 "
    "潁川郡 汝南郡 梁國 沛國 陳國 魯國 "
    "魏郡 鉅鹿郡 常山國 中山國 安平國 河閒國 清河國 趙國 勃海郡 "
    "陳留郡 東郡 東平國 任城國 泰山郡 濟北國 山陽郡 濟陰郡 "
    "東海郡 琅邪國 彭城國 廣陵郡 下邳國 "
    "濟南國 平原郡 樂安國 北海國 東萊郡 齊國 "
    "南陽郡 南郡 江夏郡 零陵郡 桂陽郡 武陵郡 長沙郡 "
    "九江郡 丹陽郡 廬江郡 會稽郡 吳郡 豫章郡 "
    "漢中郡 巴郡 廣漢郡 蜀郡 犍為郡 牂牁郡 越巂郡 益州郡 永昌郡 "
    "廣漢屬國 蜀郡屬國 犍為屬國 "
    "隴西郡 漢陽郡 武都郡 金城郡 安定郡 北地郡 武威郡 張掖郡 酒泉郡 敦煌郡 "
    "張掖屬國 張掖居延屬國 "
    "上黨郡 太原郡 上郡 西河郡 五原郡 雲中郡 定襄郡 鴈門郡 朔方郡 "
    "涿郡 廣陽郡 代郡 上谷郡 漁陽郡 右北平郡 遼西郡 遼東郡 玄菟郡 樂浪郡 遼東屬國 "
    "南海郡 蒼梧郡 鬱林郡 合浦郡 交趾郡 九真郡 日南郡"
).split()

EXPECTED_SOURCE_GROUPS: Final = (  # noqa: SIM905 - keep the reviewed source order human-auditable
    "河南尹 河内郡 河东郡 弘农郡 京兆尹 左冯翊 右扶风 "
    "潁川郡 汝南郡 梁國 沛國 陳國 魯國 魏郡 鉅鹿郡 常山國 中山國 "
    "安平國 河間國 清河國 趙國 勃海郡 "
    "陳留郡 東郡 東平國 任城國 泰山郡 濟北國 山陽郡 濟陰郡 "
    "東海郡 琅邪國 彭城國 廣陵郡 下邳國 "
    "濟南 平原郡 樂安國 北海國 東萊郡 齊國 南陽郡 南郡 江夏郡 "
    "零陵郡 桂陽郡 武陵郡 長沙郡 "
    "九江郡 丹陽郡 廬江郡 會稽郡 呉郡 豫章郡 "
    "汉中郡 巴陵秦置 广汉郡 蜀郡 犍为郡 牂牁郡 越巂郡 益州郡 "
    "永昌郡 广汉属国 蜀郡 犍为属国 "
    "陇西郡 汉阳郡 武都郡 金城郡 安定郡 北地郡 武威郡 张掖郡 "
    "酒泉郡 敦煌郡 张掖属国 张掖居延属国 "
    "上党郡 太原郡 上郡 西河郡 五原郡 云中郡 定襄郡 雁门郡 朔方郡 "
    "涿郡 广阳郡 代郡 上谷郡 渔阳郡 右北平郡 辽西郡 辽东郡 玄菟郡 乐浪郡 辽东属国 "
    "南海郡 苍梧郡 郁林郡 合浦郡 交趾郡 九真郡 日南郡"
).split()

NUMERALS: Final = {
    "一": 1,
    "二": 2,
    "三": 3,
    "四": 4,
    "五": 5,
    "六": 6,
    "七": 7,
    "八": 8,
    "九": 9,
}


class SourceCitation(TypedDict):
    corpusPath: str
    line: int
    sourceUrl: str
    snapshotSha256: str


class TraditionalTextCitation(TypedDict):
    source: str
    url: str
    localWitness: str
    snapshotSha256: str


class TraditionalPassageCitation(TraditionalTextCitation):
    line: int
    endLine: NotRequired[int]


class SourceNameIssue(TypedDict):
    resolutionStatus: str
    witnessText: str
    traditionalTextCitation: TraditionalPassageCitation


class NameCorrection(TypedDict):
    correctedName: str
    reason: str
    sourceQuote: str
    sourceCitation: SourceCitation


class ParsedUnit(TypedDict):
    sourceName: str
    unitType: str
    sourceLine: int


class ParsedGroup(TypedDict):
    sourceVolume: int
    sourceGroupName: str
    declaredCities: int | None
    sourceLine: int
    units: list[ParsedUnit]


class CatalogUnit(TypedDict):
    sourceVolume: int
    canonicalGroup: str
    ordinal: int
    sourceName: str
    sourceNameStatus: str
    unitType: str
    sourceCitation: SourceCitation
    nameCorrection: NotRequired[NameCorrection]
    sourceNameIssue: NotRequired[SourceNameIssue]


class CatalogGroup(TypedDict):
    sourceVolume: int
    sourceGroupName: str
    canonicalGroup: str
    groupType: str
    declaredCities: int | None
    enumeratedUnits: int
    sourceCitation: SourceCitation
    traditionalTextCitation: TraditionalTextCitation
    units: list[CatalogUnit]


class CountMismatch(TypedDict):
    sourceVolume: int
    canonicalGroup: str
    declaredCities: int
    enumeratedUnits: int


class CatalogSource(TypedDict):
    book: str
    section: str
    volumes: list[int]
    structureWitness: str
    traditionalTextWitness: str
    unitNamePolicy: str
    fetchContract: str
    rights: str


class Catalog(TypedDict):
    schemaVersion: int
    catalogId: str
    source: CatalogSource
    expectedGroupCount: int
    expectedUnitCount: int
    detectedGroupCount: int
    detectedUnitCount: int
    unitTypeCounts: dict[str, int]
    declaredVsEnumeratedMismatches: list[CountMismatch]
    groups: list[CatalogGroup]


class CatalogContractError(RuntimeError):
    pass


def chinese_int(value: str) -> int:
    total = 0
    current = 0
    for char in value:
        if char in NUMERALS:
            current = NUMERALS[char]
        elif char == "十":
            total += (current or 1) * 10
            current = 0
        elif char == "百":
            total += (current or 1) * 100
            current = 0
        else:
            raise CatalogContractError(f"unsupported Chinese numeral: {value}")
    return total + current


def clean_markup(value: str) -> str:
    cleaned = value
    while "-{" in cleaned:
        unwrapped = re.sub(r"-\{(.*?)}-", r"\1", cleaned)
        if unwrapped == cleaned:
            break
        cleaned = unwrapped
    cleaned = re.sub(r"\{\{.*", "", cleaned)
    return cleaned.replace("'''", "").strip(" \t\r\n，。")


def unit_type(name: str, remainder: str) -> str:
    normalized = remainder.lstrip("，。 ")
    if normalized.startswith(("侯國", "侯国")):
        return "MARQUISATE"
    if normalized.startswith("邑"):
        return "TOWN"
    if name.endswith("道"):
        return "DAO"
    return "COUNTY"


def group_type(canonical_group: str) -> str:
    """1급 행정단위(郡國) 종류 판별.

    續漢書 郡國志 卷113: 「凡郡、國百五」 — 郡과 國은 같은 1급 레벨의 서로 다른 종류.
    屬國은 卷118 百官志 「屬國，分郡離遠縣置之，如郡差小，置本郡名」 — 郡에서 갈라져
    나온 郡급 단위이므로 COMMANDERY.

    河南尹/京兆尹/左馮翊/右扶風(三輔)도 마찬가지로 COMMANDERY다. 卷117 百官志
    「司隸所部郡七。河南尹一人，主京都 … 其京兆尹、左馮翊、右扶風三人 … 謂之三輔。
    中興都雒陽，更以河南郡爲尹 … 其餘弘農、河內、河東三郡」 — 司隸가 관할하는 郡이
    일곱이라 세고, 河南郡을 尹으로 고친 것뿐이며(단위가 郡에서 다른 종류로 바뀐 게
    아니라 장관 관직 명칭만 바뀜), "나머지 세 郡"이라 부르는 비교 대상이 곧 앞의
    넷이다. 尹/翊/風은 郡의 장관 관직명이지 郡과 구분되는 1급 단위 종류가 아니다.
    """
    if canonical_group.endswith(("屬國", "属国")):
        return "COMMANDERY"
    if canonical_group.endswith(("尹", "翊", "風", "风")):
        return "COMMANDERY"
    if canonical_group.endswith(("國", "国")):
        return "KINGDOM"
    if canonical_group.endswith("郡"):
        return "COMMANDERY"
    raise CatalogContractError(f"unrecognized group type for canonical group: {canonical_group}")


def extract_marked_row(line: str) -> tuple[str, str] | None:
    match = re.match(r"^　　〖([^〗]+)〗(.*)$", line)
    if match is None:
        return None
    raw_name = match.group(1).split("{{", 1)[0]
    return clean_markup(raw_name), match.group(2)


def extract_plain_row(line: str, metadata_line: str) -> tuple[str, str] | None:
    if not line.startswith("　　"):
        return None
    stripped = line.strip()
    if stripped == metadata_line or stripped.startswith(("右", "臣昭案", "又案", "{{")):
        return None
    name_part, separator, remainder = stripped.partition("，")
    if not separator:
        name_part, separator, remainder = stripped.partition("。")
    name = clean_markup(name_part)
    if not name or len(name) > 8:
        return None
    return name, remainder


def expected_count(metadata: str) -> int | None:
    match = re.search(r"([一二三四五六七八九十百]+)城", metadata)
    return chinese_int(match.group(1)) if match else None


def _line_number(text: str, offset: int) -> int:
    return text.count("\n", 0, offset) + 1


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _require_snapshot(path: Path, expected_sha256: str) -> None:
    if not path.exists():
        raise CatalogContractError(f"missing source snapshot: {path}")
    actual_sha256 = _sha256(path)
    if actual_sha256 != expected_sha256:
        raise CatalogContractError(
            f"source snapshot drift: {path} sha256={actual_sha256} expected={expected_sha256}"
        )


def _ctext_segments(path: Path) -> list[str]:
    raw = path.read_text(encoding="utf-8", errors="replace")
    segment_pattern = re.compile(
        r'<td class="ctext">\s*(?:<div id="comm\d+"></div>)?(.*?)</td>', re.DOTALL
    )
    segments: list[str] = []
    for match in segment_pattern.finditer(raw):
        text = html.unescape(re.sub(r"<[^>]+>", "", match.group(1))).strip()
        if text:
            segments.append(text)
    return segments


def _traditional_group_citations(ctext_dir: Path) -> dict[str, TraditionalTextCitation]:
    citations: dict[str, TraditionalTextCitation] = {}
    canonical_offset = 0
    for volume in VOLUMES:
        slug = CTEXT_VOLUME_SLUGS[volume]
        path = ctext_dir / f"{slug}.html"
        _require_snapshot(path, CTEXT_SHA256[volume])
        segments = _ctext_segments(path)
        witnessed: list[str] = []
        for text in segments:
            if text in CANONICAL_GROUPS:
                witnessed.append(text)
                continue
            tail = text.rsplit("。", 1)[-1]
            if tail in CANONICAL_GROUPS:
                witnessed.append(tail)
        expected_count_for_volume = VOLUME_GROUP_COUNTS[volume]
        expected = CANONICAL_GROUPS[
            canonical_offset : canonical_offset + expected_count_for_volume
        ]
        if witnessed != expected:
            divergence = next(
                (
                    f"{index}: {actual} != {wanted}"
                    for index, (actual, wanted) in enumerate(zip(witnessed, expected), start=1)
                    if actual != wanted
                ),
                "length only",
            )
            raise CatalogContractError(
                f"ctext canonical group sequence mismatch in volume {volume}: "
                f"{len(witnessed)}/{expected_count_for_volume} ({divergence})"
            )
        if volume == 113:
            try:
                shang_index = segments.index("上郡")
                xihe_index = segments.index("西河郡", shang_index + 1)
            except ValueError as error:
                raise CatalogContractError(
                    "ctext volume 113 is missing the 上郡/西河郡 segment boundary"
                ) from error
            if "龜茲屬國" not in segments[shang_index + 1 : xihe_index]:
                raise CatalogContractError("ctext 上郡 block is missing 龜茲屬國")
        url = f"https://ctext.org/hou-han-shu/jun-guo-{slug}/zh"
        local_witness = f"data/chgis-source/junguozhi/{slug}.html"
        for canonical_group in witnessed:
            citations[canonical_group] = {
                "source": "Chinese Text Project",
                "url": url,
                "localWitness": local_witness,
                "snapshotSha256": CTEXT_SHA256[volume],
            }
        canonical_offset += expected_count_for_volume
    return citations


def _parse_heading_volumes(corpus_dir: Path) -> list[ParsedGroup]:
    groups: list[ParsedGroup] = []
    for volume in range(109, 113):
        path = corpus_dir / f"hhs-{volume:03d}.txt"
        text = path.read_text(encoding="utf-8")
        headings = list(re.finditer(r"^===([^=\n]+)===$", text, re.MULTILINE))
        for index, heading in enumerate(headings):
            group_name = clean_markup(heading.group(1))
            if group_name == "校勘記":
                continue
            end = headings[index + 1].start() if index + 1 < len(headings) else len(text)
            block = text[heading.end() : end]
            block_start_line = _line_number(text, heading.end())
            lines = [(offset, line) for offset, line in enumerate(block.splitlines()) if line.strip()]
            if not lines:
                continue
            _, raw_metadata = lines[0]
            metadata = clean_markup(raw_metadata)
            units: list[ParsedUnit] = []
            for source_offset, line in lines[1:]:
                extracted = extract_marked_row(line) or extract_plain_row(line, metadata)
                if extracted is None:
                    continue
                name, remainder = extracted
                units.append(
                    {
                        "sourceName": name,
                        "unitType": unit_type(name, remainder),
                        "sourceLine": block_start_line + source_offset,
                    }
                )
            groups.append(
                {
                    "sourceVolume": volume,
                    "sourceGroupName": group_name,
                    "declaredCities": expected_count(raw_metadata),
                    "sourceLine": _line_number(text, heading.start()),
                    "units": units,
                }
            )
    return groups


def _parse_volume_113(corpus_dir: Path) -> list[ParsedGroup]:
    path = corpus_dir / "hhs-113.txt"
    text = path.read_text(encoding="utf-8")
    headings = list(re.finditer(r"^　　◎　([^\n]+)$", text, re.MULTILINE))
    groups: list[ParsedGroup] = []
    for index, heading in enumerate(headings):
        raw_metadata = heading.group(1)
        metadata = clean_markup(raw_metadata)
        group_match = re.match(r"^([一-鿿]{1,10}?(?:屬國|属国|郡|國|国|尹))", metadata)
        end = headings[index + 1].start() if index + 1 < len(headings) else len(text)
        block = text[heading.end() : end]
        block_start_line = _line_number(text, heading.end())
        units: list[ParsedUnit] = []
        for source_offset, line in enumerate(block.splitlines()):
            extracted = extract_marked_row(line)
            if extracted is None:
                continue
            name, remainder = extracted
            units.append(
                {
                    "sourceName": name,
                    "unitType": unit_type(name, remainder),
                    "sourceLine": block_start_line + source_offset,
                }
            )
        groups.append(
            {
                "sourceVolume": 113,
                "sourceGroupName": group_match.group(1) if group_match else metadata.split("。", 1)[0],
                "declaredCities": expected_count(raw_metadata),
                "sourceLine": _line_number(text, heading.start()),
                "units": units,
            }
        )
    return groups


def parse_groups(corpus_dir: Path) -> list[ParsedGroup]:
    missing = [
        corpus_dir / f"hhs-{volume:03d}.txt"
        for volume in VOLUMES
        if not (corpus_dir / f"hhs-{volume:03d}.txt").exists()
    ]
    if missing:
        joined = ", ".join(str(path) for path in missing)
        raise CatalogContractError(f"missing corpus files: {joined}")
    for volume in VOLUMES:
        _require_snapshot(corpus_dir / f"hhs-{volume:03d}.txt", CORPUS_SHA256[volume])
    return _parse_heading_volumes(corpus_dir) + _parse_volume_113(corpus_dir)


def _name_correction(
    corpus_dir: Path, source_name: str, canonical_group: str, ordinal: int
) -> NameCorrection | None:
    if canonical_group == "上郡" and ordinal == 9 and source_name == "龟兹属国":
        witness = corpus_dir / "hhs-065.txt"
        _require_snapshot(witness, GUIZI_WITNESS_SHA256)
        line = witness.read_text(encoding="utf-8").splitlines()[48]
        if "龜茲音丘慈，縣名，屬上郡" not in line:
            raise CatalogContractError("後漢書 卷65 line 49 no longer proves 龜茲 is an 上郡 county")
        return {
            "correctedName": "龜茲",
            "reason": "後漢書 卷65 states that 龜茲 is a county belonging to 上郡",
            "sourceQuote": "龜茲音丘慈，縣名，屬上郡。",
            "sourceCitation": {
                "corpusPath": "data/corpus/hhs-065.txt",
                "line": 49,
                "sourceUrl": "https://zh.wikisource.org/wiki/後漢書/卷65",
                "snapshotSha256": GUIZI_WITNESS_SHA256,
            },
        }
    return None


def _source_name_issue(
    source_name: str, canonical_group: str, ordinal: int
) -> SourceNameIssue | None:
    issue_witnesses = {
        ("北地郡", 5, "参[�]"): ("參讀", 1492, None),
        ("武威郡", 7, "朴[B459]"): ("樸峦", 1532, 1536),
        ("交趾郡", 10, "朱[B42B]"): ("朱觏", 2860, None),
    }
    witness = issue_witnesses.get((canonical_group, ordinal, source_name))
    if witness is None:
        return None
    witness_text, line, end_line = witness
    citation: TraditionalPassageCitation = {
        "source": "Chinese Text Project",
        "url": "https://ctext.org/hou-han-shu/jun-guo-wu/zh",
        "localWitness": "data/chgis-source/junguozhi/wu.html",
        "snapshotSha256": CTEXT_SHA256[113],
        "line": line,
    }
    if end_line is not None:
        citation["endLine"] = end_line
    return {
        "resolutionStatus": "UNRESOLVED_SOURCE_PLACEHOLDER",
        "witnessText": witness_text,
        "traditionalTextCitation": citation,
    }


def _validate_source_shape(groups: list[ParsedGroup]) -> None:
    if len(groups) != EXPECTED_GROUP_COUNT:
        raise CatalogContractError(
            f"group count mismatch: {len(groups)}/{EXPECTED_GROUP_COUNT}"
        )
    actual_by_volume = Counter(group["sourceVolume"] for group in groups)
    if dict(actual_by_volume) != VOLUME_GROUP_COUNTS:
        raise CatalogContractError(
            f"volume group distribution mismatch: {dict(actual_by_volume)}"
        )
    actual_names = [group["sourceGroupName"] for group in groups]
    if actual_names != EXPECTED_SOURCE_GROUPS:
        first_difference = next(
            index
            for index, (actual, expected) in enumerate(
                zip(actual_names, EXPECTED_SOURCE_GROUPS, strict=True), start=1
            )
            if actual != expected
        )
        raise CatalogContractError(
            "source group sequence mismatch at "
            f"ordinal {first_difference}: "
            f"{actual_names[first_difference - 1]} != {EXPECTED_SOURCE_GROUPS[first_difference - 1]}"
        )
    unit_count = sum(len(group["units"]) for group in groups)
    if unit_count != EXPECTED_UNIT_COUNT:
        raise CatalogContractError(f"unit count mismatch: {unit_count}/{EXPECTED_UNIT_COUNT}")


def build_catalog(corpus_dir: Path, ctext_dir: Path | None = None) -> Catalog:
    parsed_groups = parse_groups(corpus_dir)
    _validate_source_shape(parsed_groups)
    resolved_ctext_dir = ctext_dir or corpus_dir.parent / "chgis-source" / "junguozhi"
    traditional_citations = _traditional_group_citations(resolved_ctext_dir)
    catalog_groups: list[CatalogGroup] = []
    mismatches: list[CountMismatch] = []
    type_counts: Counter[str] = Counter()

    for canonical_group, parsed_group in zip(CANONICAL_GROUPS, parsed_groups, strict=True):
        volume = parsed_group["sourceVolume"]
        corpus_path = f"data/corpus/hhs-{volume:03d}.txt"
        source_url = f"https://zh.wikisource.org/wiki/後漢書/卷{volume}"
        catalog_units: list[CatalogUnit] = []
        for ordinal, parsed_unit in enumerate(parsed_group["units"], start=1):
            unit_kind = parsed_unit["unitType"]
            type_counts[unit_kind] += 1
            catalog_unit: CatalogUnit = {
                "sourceVolume": volume,
                "canonicalGroup": canonical_group,
                "ordinal": ordinal,
                "sourceName": parsed_unit["sourceName"],
                "sourceNameStatus": "SOURCE_LITERAL",
                "unitType": unit_kind,
                "sourceCitation": {
                    "corpusPath": corpus_path,
                    "line": parsed_unit["sourceLine"],
                    "sourceUrl": source_url,
                    "snapshotSha256": CORPUS_SHA256[volume],
                },
            }
            correction = _name_correction(
                corpus_dir, parsed_unit["sourceName"], canonical_group, ordinal
            )
            if correction is not None:
                catalog_unit["nameCorrection"] = correction
            source_name_issue = _source_name_issue(
                parsed_unit["sourceName"], canonical_group, ordinal
            )
            if source_name_issue is not None:
                catalog_unit["sourceNameStatus"] = "SOURCE_PLACEHOLDER"
                catalog_unit["sourceNameIssue"] = source_name_issue
            catalog_units.append(catalog_unit)
        enumerated = len(catalog_units)
        declared = parsed_group["declaredCities"]
        if declared is not None and declared != enumerated:
            mismatches.append(
                {
                    "sourceVolume": volume,
                    "canonicalGroup": canonical_group,
                    "declaredCities": declared,
                    "enumeratedUnits": enumerated,
                }
            )
        catalog_groups.append(
            {
                "sourceVolume": volume,
                "sourceGroupName": parsed_group["sourceGroupName"],
                "canonicalGroup": canonical_group,
                "groupType": group_type(canonical_group),
                "declaredCities": declared,
                "enumeratedUnits": enumerated,
                "sourceCitation": {
                    "corpusPath": corpus_path,
                    "line": parsed_group["sourceLine"],
                    "sourceUrl": source_url,
                    "snapshotSha256": CORPUS_SHA256[volume],
                },
                "traditionalTextCitation": traditional_citations[canonical_group],
                "units": catalog_units,
            }
        )

    return {
        "schemaVersion": 1,
        "catalogId": "hhs-junguozhi-administrative-units-v1",
        "source": {
            "book": "後漢書",
            "section": "郡國志",
            "volumes": list(VOLUMES),
            "structureWitness": "Wikisource corpus snapshots pin block boundaries and unit order",
            "traditionalTextWitness": (
                "Chinese Text Project snapshots pin all 105 traditional group headings "
                "and the 上郡 龜茲屬國 placement"
            ),
            "unitNamePolicy": (
                "sourceName preserves the corpus reading; only independently cited "
                "corrections use nameCorrection; damaged source glyphs use sourceNameIssue"
            ),
            "fetchContract": (
                "python3 tools/corpus/fetch_sources.py --jobs 4; ctext snapshots must "
                "match each traditionalTextCitation hash"
            ),
            "rights": "public domain source text; OpenSamguk extraction",
        },
        "expectedGroupCount": EXPECTED_GROUP_COUNT,
        "expectedUnitCount": EXPECTED_UNIT_COUNT,
        "detectedGroupCount": len(catalog_groups),
        "detectedUnitCount": sum(group["enumeratedUnits"] for group in catalog_groups),
        "unitTypeCounts": dict(sorted(type_counts.items())),
        "declaredVsEnumeratedMismatches": mismatches,
        "groups": catalog_groups,
    }


def render_catalog(catalog: Catalog) -> str:
    return json.dumps(catalog, ensure_ascii=False, indent=2) + "\n"
