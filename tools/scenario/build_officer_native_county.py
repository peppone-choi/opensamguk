#!/usr/bin/env python3
"""인물 본관 縣 원장 빌더 (OPENSAM-255 / GitHub #775).

두 단계다.

1. ``--extract --corpus-db <shiliao index.db>`` — 저장소 **밖** 사료 색인에서 열전 서두의
   「X字Y，郡縣人也」 꼴 문장을 뽑아 ``officer-native-place-extracts-v1.json`` 에 인용문째 적는다.
   사료 색인은 커밋돼 있지 않으므로 이 단계는 CI 에서 돌지 않는다. 산출물이 커밋된 증거다.
2. 기본 실행 / ``--check`` — **커밋된 입력만**(등록부·이름표·시나리오·글자표·추출 원장·han-tiles)
   으로 ``officer-native-county-v1.json`` 을 결정론으로 다시 세운다. ``--check`` 는 바이트 비교다.

추정하지 않는다. 혈연 추론·裴注·演義·수작업 판정은 이 빌더의 범위가 아니다 — 못 찾으면 MISSING 이다.
표준 라이브러리만 쓴다.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCENARIO_DIR = ROOT / "infra" / "src" / "main" / "resources" / "scenario"
REGISTRY_PATH = ROOT / "tools" / "scenario" / "officer-id-registry.tsv"
NAME_MAP_PATH = ROOT / "tools" / "scenario" / "officer-name-map.tsv"
CHAR_MAP_PATH = ROOT / "data" / "curated" / "han" / "shinjitai-to-traditional-v1.json"
SIMPLIFICATION_PATH = ROOT / "data" / "curated" / "han" / "han-name-simplification-v1.json"
TILES_PATH = ROOT / "data" / "map" / "han-tiles.json"
EXTRACTS_PATH = ROOT / "data" / "curated" / "han" / "officer-native-place-extracts-v1.json"
LEDGER_PATH = ROOT / "data" / "curated" / "han" / "officer-native-county-v1.json"

PRODUCT_SCENARIO_PATTERN = re.compile(r"^scenario_1[01]\d\d\.json$")
GENERAL_KEYS = ("general", "general_ex", "general_neutral")
# 책 우선순위 — 같은 본관을 여러 책이 말하면 앞 책의 인용문을 증거로 쓴다.
BOOKS = ("三國志", "後漢書", "晉書", "華陽國志")
QUOTE_LIMIT = 40

NAME_STOP = "\\s，。、：；「」『』（）《》〈〉=|\\[\\]{}"
PLACE_STOP = "，。、；：「」『』（）\\s"
# A/D: 「X字Y，郡縣人(也)」  B: 「X，郡縣人也」  E: 「姓X，諱Y(，字Z)，郡縣人」
HIT_PATTERN = re.compile(
    rf"(?:^|(?<=[\n　。 \t]))(?P<name>[^{NAME_STOP}]{{2,5}}?)(?:，?字(?P<zi>[^{NAME_STOP}]{{1,3}}))?"
    rf"(?:，本[字名][^{NAME_STOP}]{{1,3}})?，(?P<place>[^{PLACE_STOP}]{{1,8}}?)人(?P<tail>也|，|。)"
)
TABOO_PATTERN = re.compile(
    rf"姓(?P<surname>[^{NAME_STOP}]{{1,2}})，諱(?P<given>[^{NAME_STOP}]{{1,2}})(?:，字(?P<zi>[^{NAME_STOP}]{{1,3}}))?"
    rf"，(?P<place>[^{PLACE_STOP}]{{1,8}}?)人(?P<tail>也|，|。)"
)
# 철자만 다른 郡 이름. 시대가 달라 이름이 바뀐 郡(天水·譙國·義陽 …)은 여기 넣지 않는다 — 그건 사료 판정이다.
COMMANDERY_SPELLING_ALIASES = {"河間": "河閒國", "丹楊": "丹陽郡"}
# F: 「太祖武皇帝，沛國譙人也，姓曹，諱操」
TABOO_AFTER_PATTERN = re.compile(
    rf"，(?P<place>[^{PLACE_STOP}]{{1,8}}?)人(?P<tail>也)，姓(?P<surname>[^{NAME_STOP}]{{1,2}})，諱(?P<given>[^{NAME_STOP}]{{1,2}})(?=[，。])"
)
TILE_SUFFIXES = ("侯国", "侯國", "属国", "屬國", "公国", "公國", "县", "縣")


class LedgerError(Exception):
    pass


def _load_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def _read_tsv(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8", newline="") as handle:
        return list(csv.DictReader(handle, delimiter="\t"))


def _serialize(payload) -> str:
    return json.dumps(payload, ensure_ascii=False, indent=1) + "\n"


# ---------------------------------------------------------------- 글자표


class CharTables:
    def __init__(self, payload: dict):
        self.shinjitai = {row["from"]: row["to"] for row in payload["shinjitaiToTraditional"]}
        self.variant = {row["from"]: row["to"] for row in payload["matchVariantFold"]}
        self.place_additions = list(payload.get("placeSimplificationAdditions", []))
        for source, target in list(self.shinjitai.items()) + list(self.variant.items()):
            if len(source) != 1 or len(target) != 1 or source == target:
                raise LedgerError(f"글자표 항목이 1:1 글자 쌍이 아니다: {source!r}->{target!r}")
        overlap = set(self.shinjitai) & set(self.variant)
        if overlap:
            raise LedgerError(f"신자체 표와 이체 접기 표가 겹친다: {sorted(overlap)}")
        fold = dict(self.variant)
        fold.update(self.shinjitai)
        # 연쇄(예: a->b, b->c)를 끝까지 접는다.
        self._fold = {}
        for source in fold:
            target, seen = fold[source], {source}
            while target in fold and target not in seen:
                seen.add(target)
                target = fold[target]
            self._fold[ord(source)] = target
        self._traditional = {ord(k): v for k, v in self.shinjitai.items()}

    def to_traditional(self, text: str) -> str:
        return text.translate(self._traditional)

    def fold(self, text: str) -> str:
        """대조 전용. 글자 수를 바꾸지 않는다(오프셋 보존)."""
        return text.translate(self._fold)


# ---------------------------------------------------------------- 시나리오·등록부


def product_scenario_paths(scenario_dir: Path = SCENARIO_DIR) -> list[Path]:
    return sorted(p for p in scenario_dir.iterdir() if PRODUCT_SCENARIO_PATTERN.match(p.name))


def scenario_names(paths: list[Path]) -> dict[str, list[str]]:
    """장수 이름 -> 등장 시나리오 코드(정렬)."""
    seen: dict[str, set[str]] = {}
    for path in paths:
        payload = _load_json(path)
        code = path.stem.split("_", 1)[1]
        for key in GENERAL_KEYS:
            for row in payload.get(key, []):
                seen.setdefault(str(row[1]), set()).add(code)
    return {name: sorted(codes) for name, codes in seen.items()}


def link_officers(registry, name_map, names_by_scenario):
    """등록부 인물 중 제품 시나리오에 나오는 사람.

    시나리오 tuple 에는 등록부 id 가 없다 — 다리는 한글 이름뿐이다. 동명이인은 시나리오에서
    「이풍1·이풍2」처럼 숫자 접미가 붙는데 어느 쪽이 어느 등록부 인물인지는 여기서 정하지 않는다.
    본관은 한자 인물의 속성이므로, 접미를 뗀 이름이 같은 등록부 인물을 모두 행으로 싣고
    scenarioLink 로 구분한다.
    """
    korean_by_id = {row["id"]: row["name_korean"] for row in name_map}
    korean_count = Counter(korean_by_id.values())
    exact = set(names_by_scenario)
    suffixed: dict[str, list[str]] = {}
    for name in names_by_scenario:
        base = name.rstrip("0123456789")
        if base != name and base:
            suffixed.setdefault(base, []).append(name)
    linked = []
    used_names: set[str] = set()
    for row in registry:
        korean = korean_by_id.get(row["id"])
        if korean is None:
            continue
        names = ([korean] if korean in exact else []) + sorted(suffixed.get(korean, []))
        if not names:
            continue
        unique = korean_count[korean] == 1 and names == [korean]
        linked.append((row, korean, "EXACT" if unique else "HOMONYM", names))
        used_names.update(names)
    unlinked = sorted(exact - used_names)
    return linked, unlinked


# ---------------------------------------------------------------- 사료 정리·추출


def clean_wikitext(text: str) -> str:
    """裴注·李賢注({{*|…}})와 머리말 틀을 지우고 위키 링크·변환 표식을 벗긴다."""
    text = re.sub(r"-\{([^{}]*)\}-", r"\1", text)
    inner = re.compile(r"\{\{([^{}]*)\}\}")

    def _replace(match: re.Match) -> str:
        parts = match.group(1).split("|")
        if parts[0].strip() == "YL" and len(parts) >= 2:
            return parts[1]
        return ""

    previous = None
    while previous != text:
        previous = text
        text = inner.sub(_replace, text)
    text = re.sub(r"（([^（）])）", r"\1", text)  # 교감 보입 한 글자 「（右）扶風」
    text = re.sub(r"\[\[(?:[^\[\]|]*\|)?([^\[\]|]*)\]\]", r"\1", text)
    return text


def find_hits(cleaned: str, folded: str):
    """(folded name, zi, place(원문), form, quote(원문)) 를 돌려준다. folded 는 cleaned 와 길이가 같다."""
    if len(cleaned) != len(folded):
        raise LedgerError("접은 본문 길이가 달라졌다")
    for match in HIT_PATTERN.finditer(folded):
        start, end = match.start("name"), match.end("tail")
        zi = match.group("zi")
        form = ("A" if match.group("tail") == "也" else "D") if zi else "B"
        if form == "B" and match.group("tail") != "也":
            continue
        place_span = match.span("place")
        yield (match.group("name"), cleaned[match.start("zi"):match.end("zi")] if zi else None,
               cleaned[place_span[0]:place_span[1]], form, cleaned[start:end][:QUOTE_LIMIT])
    for match in TABOO_AFTER_PATTERN.finditer(folded):
        place_span = match.span("place")
        yield (match.group("surname") + match.group("given"), None, cleaned[place_span[0]:place_span[1]], "F",
               cleaned[match.start() + 1:match.end()][:QUOTE_LIMIT])
    for match in TABOO_PATTERN.finditer(folded):
        place_span = match.span("place")
        zi = match.group("zi")
        yield (match.group("surname") + match.group("given"),
               cleaned[match.start("zi"):match.end("zi")] if zi else None,
               cleaned[place_span[0]:place_span[1]], "E", cleaned[match.start():match.end("tail")][:QUOTE_LIMIT])


def extract(corpus_db: Path, tables: CharTables, wanted: dict[str, str]) -> dict:
    """wanted: folded name -> 정체 이름."""
    import sqlite3
    import zlib

    connection = sqlite3.connect(f"file:{corpus_db}?mode=ro", uri=True)
    hits = []
    volumes = 0
    for book in BOOKS:
        rows = connection.execute("select vol, title, body from vol where book = ? order by vol", (book,)).fetchall()
        for volume, title, body in rows:
            volumes += 1
            raw = zlib.decompress(body).decode("utf-8")
            cleaned = clean_wikitext(raw)
            folded = tables.fold(cleaned)
            digest = hashlib.sha256(raw.encode("utf-8")).hexdigest()
            for name, zi, place, form, quote in find_hits(cleaned, folded):
                if name not in wanted:
                    continue
                hits.append({
                    "nameKanjiTraditional": wanted[name], "courtesyName": zi, "placeText": place, "form": form,
                    "book": book, "volume": volume, "title": title, "quote": quote, "volumeSha256": digest,
                })
    hits.sort(key=lambda h: (h["nameKanjiTraditional"], BOOKS.index(h["book"]), h["volume"], h["quote"]))
    return {
        "schemaVersion": 1,
        "ledgerId": "officer-native-place-extracts-v1",
        "note": "사료 색인(shiliao corpus/index.db)에서 열전 서두 본관 문장을 뽑은 증거 원장. 註({{*|…}})는 뺐다. "
                "색인은 저장소 밖이라 이 파일이 커밋된 증거다 — 재추출은 build_officer_native_county.py --extract.",
        "books": list(BOOKS),
        "forms": {"A": "X字Y，郡縣人也", "B": "X，郡縣人也", "D": "X字Y，郡縣人[，。]", "E": "姓X，諱Y(，字Z)，郡縣人",
                  "F": "郡縣人也，姓X，諱Y"},
        "volumesScanned": volumes,
        "hits": hits,
    }


# ---------------------------------------------------------------- 郡·縣 대조


class Gazetteer:
    def __init__(self, tiles: dict, simplification: dict, tables: CharTables):
        self._tables = tables
        self._simplify = {ord(k): v for k, v in simplification["table"].items()}
        for row in simplification.get("reviewedVariantAdditions", []):
            self._simplify[ord(row["from"])] = row["to"]
        tile_names = {j["nameCh"] for j in tiles["jurisdictionRecords"]}
        for row in tables.place_additions:
            if row["from"] in simplification["table"]:
                raise LedgerError(f"繁→簡 추가 쌍이 기존 표와 겹친다: {row['from']}")
            if row["witness"] not in tile_names or row["to"] not in row["witness"]:
                raise LedgerError(f"繁→簡 추가 쌍의 witness 가 타일에 없다: {row}")
            self._simplify[ord(row["from"])] = row["to"]
        self.aliases: dict[str, list[dict]] = {}
        for commandery in tiles["commanderyRecords"]:
            spelled = {a for a, target in COMMANDERY_SPELLING_ALIASES.items() if target == commandery["nameCh"]}
            for alias in self._commandery_aliases(commandery["nameCh"]) | spelled:
                self.aliases.setdefault(self.norm(alias), []).append(commandery)
        self._county_index: dict[str, list[str]] = {}
        for record in tiles["jurisdictionRecords"]:
            if record.get("kind") != "COUNTY":
                continue
            self._county_index.setdefault(self.county_key(record["nameCh"]), []).append(record["id"])

    def norm(self, text: str) -> str:
        return self._tables.fold(text).translate(self._simplify)

    def county_key(self, name: str) -> str:
        name = self.norm(name)
        for suffix in TILE_SUFFIXES:
            if name.endswith(self.norm(suffix)) and len(name) > len(suffix):
                return name[: -len(suffix)]
        return name

    @staticmethod
    def _commandery_aliases(name: str) -> set[str]:
        aliases = {name}
        if name in ("左馮翊", "右扶風"):
            aliases.add(name[1:])
        if name[-1] in "郡國尹" and not name.endswith("屬國"):
            base = name[:-1]
            if name in ("左馮翊", "右扶風"):
                base = name
            if name[-1] in "郡國":
                aliases.update({base + "郡", base + "國"})
            if len(base) >= 2:
                aliases.add(base)
        return aliases

    def resolve(self, place_text: str) -> dict:
        if place_text.startswith("本") and len(place_text) > 2:  # 「本遼東襄平人也」
            place_text = place_text[1:]
        place = self.norm(place_text)
        best = None
        for alias, commanderies in self.aliases.items():
            if place.startswith(alias) and (best is None or len(alias) > len(best[0])):
                best = (alias, commanderies)
        if best is None:
            return {"matchStatus": "UNSPLIT", "nativeCommandery": None, "commanderyId": None,
                    "nativeCounty": None, "jurisdictionId": None}
        alias, commanderies = best
        if len({c["id"] for c in commanderies}) != 1:
            return {"matchStatus": "AMBIGUOUS", "nativeCommandery": place_text[: len(alias)], "commanderyId": None,
                    "nativeCounty": place_text[len(alias):] or None, "jurisdictionId": None}
        commandery = commanderies[0]
        county_text = place_text[len(alias):]
        base = {"nativeCommandery": commandery["nameCh"], "commanderyId": commandery["id"],
                "nativeCounty": county_text or None, "jurisdictionId": None}
        if not county_text:
            return {"matchStatus": "NO_COUNTY", **base}
        key = self.county_key(county_text)
        everywhere = self._county_index.get(key, [])
        inside = [i for i in everywhere if i in set(commandery["jurisdictionIds"])]
        if len(inside) == 1:
            return {"matchStatus": "MATCHED", **base, "jurisdictionId": inside[0]}
        if len(inside) > 1:
            return {"matchStatus": "AMBIGUOUS", **base, "candidateJurisdictionIds": sorted(inside)}
        result = {"matchStatus": "COUNTY_NOT_IN_COMMANDERY", **base}
        if everywhere:
            result["candidateJurisdictionIds"] = sorted(everywhere)
        return result


# ---------------------------------------------------------------- 원장


def validate_extracts(extracts: dict, tables: CharTables) -> None:
    if extracts.get("schemaVersion") != 1 or extracts.get("ledgerId") != "officer-native-place-extracts-v1":
        raise LedgerError("추출 원장 머리말이 다르다")
    for hit in extracts["hits"]:
        missing = {"nameKanjiTraditional", "placeText", "form", "book", "volume", "quote"} - set(hit)
        if missing:
            raise LedgerError(f"추출 행에 필드가 없다: {sorted(missing)}")
        if hit["book"] not in BOOKS:
            raise LedgerError(f"모르는 책: {hit['book']}")
        if len(hit["quote"]) > QUOTE_LIMIT:
            raise LedgerError(f"인용문이 {QUOTE_LIMIT}자를 넘는다: {hit['quote']}")
        if hit["placeText"] + "人" not in hit["quote"]:
            raise LedgerError(f"인용문에 본관 문구가 없다: {hit['quote']}")
        if hit["form"] not in ("E", "F") and not tables.fold(hit["quote"]).startswith(tables.fold(hit["nameKanjiTraditional"])):
            raise LedgerError(f"인용문이 인물 이름으로 시작하지 않는다: {hit['quote']}")


def build_ledger(registry, name_map, names_by_scenario, tables, extracts, gazetteer, scenario_codes) -> dict:
    validate_extracts(extracts, tables)
    linked, unlinked = link_officers(registry, name_map, names_by_scenario)
    hits_by_name: dict[str, list[dict]] = {}
    for hit in extracts["hits"]:
        hits_by_name.setdefault(tables.fold(hit["nameKanjiTraditional"]), []).append(hit)
    folded_count = Counter(tables.fold(tables.to_traditional(row["name_kanji"])) for row in registry)

    rows = []
    for row, korean, link, scenario_name_list in linked:
        traditional = tables.to_traditional(row["name_kanji"])
        folded = tables.fold(traditional)
        entry = {
            "stableId": row["id"], "nameKorean": korean, "nameKanjiRegistry": row["name_kanji"],
            "nameKanjiTraditional": traditional, "scenarioLink": link, "scenarioNames": scenario_name_list,
            "method": "MISSING", "missingReason": None, "nativeCommandery": None, "commanderyId": None,
            "nativeCounty": None, "jurisdictionId": None, "matchStatus": None, "evidence": None,
        }
        hits = hits_by_name.get(folded, [])
        resolved = [(hit, gazetteer.resolve(hit["placeText"])) for hit in hits]
        # 三國志 열전이 있으면 그것만으로 판정한다 — 시나리오 인물은 삼국 시대 사람이고, 다른 책의 같은 이름은
        # 동명이인일 수 있다(실측: 徐邈·賈逵·李通). 어긋난 타 책 문구는 otherBookDisagreement 로 남긴다.
        other_books = []
        if any(h["book"] == "三國志" for h, _ in resolved):
            other_books = [(h, r) for h, r in resolved if h["book"] != "三國志"]
            resolved = [(h, r) for h, r in resolved if h["book"] == "三國志"]
        # 같은 郡인데 한쪽만 縣까지 말하면(武陵 / 武陵漢壽) 모순이 아니다 — 더 자세한 쪽을 쓴다.
        specific = {r["commanderyId"] for _, r in resolved if r["commanderyId"] and r["nativeCounty"]}
        resolved = [(h, r) for h, r in resolved if r["nativeCounty"] or r["commanderyId"] not in specific]
        distinct = {(r["commanderyId"], gazetteer.county_key(r["nativeCounty"] or ""),
                     None if r["commanderyId"] else gazetteer.norm(h["placeText"])) for h, r in resolved}
        if folded_count[folded] > 1:
            entry["missingReason"] = "AMBIGUOUS_PERSON"
            entry["ambiguity"] = "등록부에 같은 한자 이름이 둘 이상이다"
        elif not hits:
            entry["missingReason"] = "NO_BIOGRAPHY_STATEMENT"
        elif len(distinct) > 1:
            entry["missingReason"] = "AMBIGUOUS_PERSON"
            entry["ambiguity"] = "같은 이름의 열전 서두가 서로 다른 본관을 말한다"
            entry["candidates"] = [{"placeText": h["placeText"], "book": h["book"], "volume": h["volume"], "quote": h["quote"]}
                                   for h, _ in resolved]
        elif all(h["book"] != "三國志" for h, _ in resolved):
            # 三國志 밖 책만 근거면 동명이인(後漢·晉 인물)일 수 있다 — 실측에서 張純·張超·孫登·郭奕 가 그랬다.
            # 이름만으로는 가를 수 없으므로 DIRECT 로 올리지 않고 사람 검토 대기열에 둔다.
            entry["missingReason"] = "HOMONYM_UNVERIFIED"
            entry["candidates"] = [{"placeText": h["placeText"], "book": h["book"], "volume": h["volume"], "quote": h["quote"]}
                                   for h, _ in resolved]
        else:
            hit, resolution = resolved[0]
            entry.update(resolution)
            entry["method"] = "DIRECT"
            entry["courtesyName"] = hit["courtesyName"]
            entry["evidence"] = {"book": hit["book"], "volume": hit["volume"], "quote": hit["quote"]}
            def _same(other):
                if other["commanderyId"] is None:  # 華陽國志 는 郡 절 안에서 「涪人」처럼 縣만 말한다
                    return resolution["nativeCounty"] is not None and gazetteer.county_key(other_text) == gazetteer.county_key(resolution["nativeCounty"])
                return other["commanderyId"] == resolution["commanderyId"] and (
                    not other["nativeCounty"] or not resolution["nativeCounty"]
                    or gazetteer.county_key(other["nativeCounty"]) == gazetteer.county_key(resolution["nativeCounty"]))
            agree, disagree = [], []
            for other_hit, other_resolution in resolved[1:] + other_books:
                other_text = other_hit["placeText"]
                (agree if _same(other_resolution) else disagree).append(other_hit)
            if agree:
                entry["corroboration"] = [{"book": h["book"], "volume": h["volume"]} for h in agree]
            if disagree:
                entry["otherBookDisagreement"] = [{"book": h["book"], "volume": h["volume"], "quote": h["quote"]} for h in disagree]
        rows.append(entry)
    rows.sort(key=lambda e: int(e["stableId"]))

    stats = {
        "officers": len(rows),
        "byMethod": dict(sorted(Counter(e["method"] for e in rows).items())),
        "byMissingReason": dict(sorted(Counter(e["missingReason"] for e in rows if e["method"] == "MISSING").items())),
        "byMatchStatus": dict(sorted(Counter(e["matchStatus"] for e in rows if e["method"] == "DIRECT").items())),
        "byScenarioLink": dict(sorted(Counter(e["scenarioLink"] for e in rows).items())),
        "unlinkedScenarioNames": len(unlinked),
    }
    return {
        "schemaVersion": 1,
        "ledgerId": "officer-native-county-v1",
        "issue": "OPENSAM-255 / GitHub #775",
        "note": "인물 본관 縣 원장 1차분. DIRECT = 열전 서두가 본관을 직접 말한다(인용문 첨부). "
                "MISSING = 아직 근거 없음 — 혈연·裴注·演義·수작업은 뒤 단계다. 추정으로 메우지 않는다.",
        "inputs": {
            "scenarios": scenario_codes,
            "registry": "tools/scenario/officer-id-registry.tsv",
            "nameMap": "tools/scenario/officer-name-map.tsv",
            "charMap": "data/curated/han/shinjitai-to-traditional-v1.json",
            "extracts": "data/curated/han/officer-native-place-extracts-v1.json",
            "tiles": "data/map/han-tiles.json",
        },
        "stats": stats,
        "officers": rows,
        "unlinkedScenarioNames": unlinked,
    }


def build_from_committed() -> dict:
    tables = CharTables(_load_json(CHAR_MAP_PATH))
    paths = product_scenario_paths()
    gazetteer = Gazetteer(_load_json(TILES_PATH), _load_json(SIMPLIFICATION_PATH), tables)
    return build_ledger(
        _read_tsv(REGISTRY_PATH), _read_tsv(NAME_MAP_PATH), scenario_names(paths), tables,
        _load_json(EXTRACTS_PATH), gazetteer, [p.stem.split("_", 1)[1] for p in paths],
    )


def print_stats(ledger: dict) -> None:
    stats = ledger["stats"]
    print(f"officers            {stats['officers']}")
    for title in ("byMethod", "byMissingReason", "byMatchStatus", "byScenarioLink"):
        print(title)
        for key, value in stats[title].items():
            print(f"  {key:<28}{value}")
    print(f"unlinkedScenarioNames {stats['unlinkedScenarioNames']}")


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--check", action="store_true", help="커밋된 원장이 재생성 결과와 다르면 exit 1")
    parser.add_argument("--extract", action="store_true", help="사료 색인에서 추출 원장을 다시 뽑는다(저장소 밖 입력)")
    parser.add_argument("--corpus-db", type=Path, help="shiliao corpus/index.db 경로")
    args = parser.parse_args(argv)
    try:
        if args.extract:
            if args.check or args.corpus_db is None:
                parser.error("--extract 는 --corpus-db 가 필요하고 --check 와 같이 못 쓴다")
            tables = CharTables(_load_json(CHAR_MAP_PATH))
            linked, _ = link_officers(_read_tsv(REGISTRY_PATH), _read_tsv(NAME_MAP_PATH), scenario_names(product_scenario_paths()))
            wanted = {}
            for row, *_ in linked:
                traditional = tables.to_traditional(row["name_kanji"])
                wanted[tables.fold(traditional)] = traditional
            payload = extract(args.corpus_db, tables, wanted)
            EXTRACTS_PATH.write_text(_serialize(payload), encoding="utf-8")
            print(f"extracts: {len(payload['hits'])} hits / {payload['volumesScanned']} volumes -> {EXTRACTS_PATH.relative_to(ROOT)}")
        ledger = build_from_committed()
    except LedgerError as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    serialized = _serialize(ledger)
    if args.check:
        current = LEDGER_PATH.read_text(encoding="utf-8") if LEDGER_PATH.exists() else ""
        if current != serialized:
            print(f"FAIL: {LEDGER_PATH.relative_to(ROOT)} 가 낡았다 — python3 tools/scenario/build_officer_native_county.py 로 다시 세워라", file=sys.stderr)
            return 1
        print_stats(ledger)
        print("OK: officer-native-county-v1 is up to date")
        return 0
    LEDGER_PATH.write_text(serialized, encoding="utf-8")
    print_stats(ledger)
    return 0


if __name__ == "__main__":
    sys.exit(main())
