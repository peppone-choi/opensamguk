#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import argparse
import copy
import json
import re
import shutil
import zipfile
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path


NS = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
RUNTIME_SECTIONS = ("general", "general_ex", "general_neutral")
ARCHIVE_SECTIONS = ("generals", "generalsEx")
SOURCE_FIELDS = (
    "number", "name", "gender", "birth", "appearance", "death", "lifespan",
    "activityYears", "L", "S", "I", "politics", "charm", "total", "ideology",
)
SOURCE_STAT_FIELDS = ("L", "S", "I", "politics", "charm")
MATCHED_STAT_FIELDS = (
    ("leadership", "L", 5),
    ("strength", "S", 6),
    ("intelligence", "I", 7),
    ("politics", "politics", 14),
    ("charm", "charm", 15),
)
XLSX_HEADERS = {
    "무장번호": "number",
    "무장": "name",
    "성별": "gender",
    "생년": "birth",
    "등장년": "appearance",
    "몰년": "death",
    "수명": "lifespan",
    "활동년": "activityYears",
    "통솔": "L",
    "무력": "S",
    "지력": "I",
    "정치": "politics",
    "매력": "charm",
    "총합": "total",
    "주의": "ideology",
}
OVERRIDE_PATH = Path(__file__).with_name("rtk14_unmatched_overrides.json")
STAT_MIN = 1
STAT_MAX = 100
OVERRIDE_SCHEMA_VERSION = 2


def base_name(name):
    return re.sub(r"\d+$", "", str(name))


def runtime_identity(scenario_identity, section, index, name, leadership, strength, intelligence, birth, death):
    values = (scenario_identity, section, index, name, leadership, strength, intelligence, birth, death)
    if not isinstance(scenario_identity, str) or not scenario_identity:
        raise ValueError("runtime scenario identity must be a non-empty string")
    if not isinstance(section, str) or section not in RUNTIME_SECTIONS:
        raise ValueError("runtime identity section is invalid")
    if isinstance(index, bool) or not isinstance(index, int) or index < 0:
        raise ValueError("runtime identity index is invalid")
    if not isinstance(name, str) or not name:
        raise ValueError("runtime identity name is invalid")
    if any(isinstance(value, bool) or not isinstance(value, int) for value in values[4:]):
        raise ValueError("runtime identity stat fields must be integers")
    return "::".join(str(value) for value in values)


def _int(value):
    if isinstance(value, bool):
        return 0
    try:
        return int(value)
    except (TypeError, ValueError):
        return 0


def _strict_int(value, field, row_number):
    if isinstance(value, bool) or not isinstance(value, int):
        raise ValueError(f"source row {row_number} {field} must be an integer")
    return value


def _validate_source_rows(rows):
    if not isinstance(rows, list):
        raise ValueError("RTK source rows must be a list")
    if len(rows) != 1000:
        raise ValueError(f"RTK source must contain exactly 1000 rows, got {len(rows)}")

    normalized = []
    seen_numbers = set()
    expected_fields = set(SOURCE_FIELDS)
    for index, raw in enumerate(rows, start=1):
        if not isinstance(raw, dict):
            raise ValueError(f"source row {index} must be an object")
        actual_fields = set(raw)
        missing = [field for field in SOURCE_FIELDS if field not in raw]
        extra = sorted(actual_fields - expected_fields)
        if missing or extra:
            raise ValueError(f"source row {index} contract mismatch: missing={missing} extra={extra}")

        number = _strict_int(raw["number"], "number", index)
        if number in seen_numbers:
            raise ValueError(f"source row {index} has duplicate officer number {number}")
        if not 1 <= number <= 1000:
            raise ValueError(f"source row {index} number must be in 1..1000")
        seen_numbers.add(number)

        name = raw["name"]
        gender = raw["gender"]
        ideology = raw["ideology"]
        if not isinstance(name, str) or not name.strip():
            raise ValueError(f"source row {index} name must be a non-empty string")
        if gender not in ("남", "여"):
            raise ValueError(f"source row {index} gender must be 남 or 여")
        if not isinstance(ideology, str) or not ideology.strip():
            raise ValueError(f"source row {index} ideology must be a non-empty string")

        row = {"number": number, "name": name, "gender": gender}
        for field in ("birth", "appearance", "death", "lifespan", "activityYears", "total", *SOURCE_STAT_FIELDS):
            row[field] = _strict_int(raw[field], field, index)
        if not STAT_MIN <= row["L"] <= STAT_MAX:
            raise ValueError(f"source row {index} L must be in {STAT_MIN}..{STAT_MAX}")
        if not STAT_MIN <= row["S"] <= STAT_MAX:
            raise ValueError(f"source row {index} S must be in {STAT_MIN}..{STAT_MAX}")
        if not STAT_MIN <= row["I"] <= STAT_MAX:
            raise ValueError(f"source row {index} I must be in {STAT_MIN}..{STAT_MAX}")
        if not STAT_MIN <= row["politics"] <= STAT_MAX:
            raise ValueError(f"source row {index} politics must be in {STAT_MIN}..{STAT_MAX}")
        if not STAT_MIN <= row["charm"] <= STAT_MAX:
            raise ValueError(f"source row {index} charm must be in {STAT_MIN}..{STAT_MAX}")
        if not row["birth"] <= row["appearance"] <= row["death"]:
            raise ValueError(f"source row {index} has invalid birth/appearance/death ordering")
        if row["lifespan"] != row["death"] - row["birth"] + 1:
            raise ValueError(f"source row {index} lifespan must equal death - birth + 1")
        if row["activityYears"] != row["death"] - row["appearance"] + 1:
            raise ValueError(f"source row {index} activityYears must equal death - appearance + 1")
        if row["total"] != sum(row[field] for field in SOURCE_STAT_FIELDS):
            raise ValueError(f"source row {index} total must equal the five-stat sum")
        row["ideology"] = ideology
        normalized.append({field: row[field] for field in SOURCE_FIELDS})

    if seen_numbers != set(range(1, 1001)):
        raise ValueError("RTK source officer numbers must be exactly 1..1000")
    return sorted(normalized, key=lambda row: row["number"])


def _normalize_override(label, value):
    if not isinstance(value, dict) or not isinstance(value.get("rationale"), str) or not value["rationale"].strip():
        raise ValueError(f"RTK14 reviewed override for {label!r} needs a rationale")
    for stat in ("politics", "charm"):
        score = value.get(stat)
        if isinstance(score, bool) or not isinstance(score, int) or not STAT_MIN <= score <= STAT_MAX:
            raise ValueError(f"RTK14 reviewed override for {label!r} needs {stat} as an integer in {STAT_MIN}..{STAT_MAX}")
    if value["politics"] == 50 and value["charm"] == 50:
        raise ValueError(f"RTK14 reviewed override for {label!r} cannot use the implicit 50/50 pair")
    return {
        "politics": value["politics"],
        "charm": value["charm"],
        "rationale": str(value["rationale"]),
    }


def read_override_config(path=OVERRIDE_PATH):
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    if not isinstance(data, dict) or set(data) != {"schemaVersion", "provenance", "overrides", "collisionOverrides"}:
        raise ValueError("RTK14 override config has an invalid root contract")
    if data["schemaVersion"] != OVERRIDE_SCHEMA_VERSION:
        raise ValueError(f"RTK14 override config must use schemaVersion {OVERRIDE_SCHEMA_VERSION}")
    if not isinstance(data["provenance"], str) or not data["provenance"].strip():
        raise ValueError("RTK14 override config needs provenance")
    overrides = data["overrides"]
    if not isinstance(overrides, dict):
        raise ValueError("RTK14 unmatched override config must contain overrides{}")
    normalized = {}
    for name, value in overrides.items():
        if not isinstance(name, str) or not name:
            raise ValueError("RTK14 unmatched override names must be non-empty strings")
        normalized[name] = _normalize_override(name, value)
    collisions = data["collisionOverrides"]
    if not isinstance(collisions, dict):
        raise ValueError("RTK14 override config must contain collisionOverrides{}")
    normalized_collisions = {}
    for identity, value in collisions.items():
        if not isinstance(identity, str) or len(identity.split("::")) != 9:
            raise ValueError("RTK14 collision override must use an exact runtime identity")
        normalized_collisions[identity] = _normalize_override(identity, value)
    return normalized, normalized_collisions


def read_unmatched_overrides(path=OVERRIDE_PATH):
    return read_override_config(path)[0]


def read_collision_overrides(path=OVERRIDE_PATH):
    return read_override_config(path)[1]


UNMATCHED_OVERRIDES, COLLISION_OVERRIDES = read_override_config()

ALIASES = {
    "김배삼결": "금환삼결",
    "구역거": "구력거",
    "금선": "김선",
    "답둔": "답돈",
    "동다나": "동도나",
    "번주": "번조",
    "보질": "보즐",
    "비위": "비의",
    "향랑": "상랑",
    "향총": "상총",
    "교수": "교유",
    "금의": "김의",
    "양혼": "양흔",
    "휴원진": "수원진",
    "주태유평": "주태",
    "정봉승연": "정봉",
    "하후령녀": "하후영녀",
    "보련사": "보연사",
    "포삼낭": "포삼랑",
    "마운": "마운록",
    "고담륭": "고당륭",
    "서진순욱": "순욱",
    "유대연주": "유대",
    "여영기": "여령기",
    "이이자": "이이",
    "유위대": "유대",
}


def _column_index(reference):
    match = re.match(r"([A-Z]+)", reference or "")
    if not match:
        raise ValueError(f"invalid XLSX cell reference {reference!r}")
    value = 0
    for letter in match.group(1):
        value = value * 26 + ord(letter) - ord("A") + 1
    return value


def _xlsx_cell_value(cell, shared_strings):
    if cell.get("t") == "inlineStr":
        return "".join(text.text or "" for text in cell.iter(NS + "t"))
    value = cell.find(NS + "v")
    if value is None:
        return None
    if cell.get("t") == "s":
        return shared_strings[int(value.text)]
    return value.text


def read_rtk14(xlsx_path):
    with zipfile.ZipFile(xlsx_path) as archive:
        shared_strings = []
        if "xl/sharedStrings.xml" in archive.namelist():
            shared_root = ET.fromstring(archive.read("xl/sharedStrings.xml"))
            shared_strings = ["".join(text.text or "" for text in item.iter(NS + "t")) for item in shared_root.findall(NS + "si")]
        worksheet = ET.fromstring(archive.read("xl/worksheets/sheet1.xml"))

    header_row_number = None
    columns = None
    rows = []
    for row_element in worksheet.findall(".//" + NS + "row"):
        values = {
            _column_index(cell.get("r")): _xlsx_cell_value(cell, shared_strings)
            for cell in row_element.findall(NS + "c")
        }
        labels = {str(value).strip(): column for column, value in values.items() if value is not None}
        if columns is None and set(XLSX_HEADERS).issubset(labels):
            columns = {field: labels[label] for label, field in XLSX_HEADERS.items()}
            header_row_number = _int(row_element.get("r"))
            continue
        if columns is None:
            continue
        if _int(row_element.get("r")) <= header_row_number:
            continue
        if not any(value is not None for value in values.values()):
            continue
        raw = {field: values.get(column) for field, column in columns.items()}
        if raw["number"] in (None, ""):
            raise ValueError(f"XLSX row {row_element.get('r')} has values but no officer number")
        converted = {}
        for field in SOURCE_FIELDS:
            value = raw[field]
            if field in ("name", "gender", "ideology"):
                converted[field] = value
            else:
                try:
                    converted[field] = int(value)
                except (TypeError, ValueError) as error:
                    raise ValueError(f"XLSX row {row_element.get('r')} {field} must be an integer") from error
        rows.append(converted)
    if columns is None:
        raise ValueError("XLSX sheet1 is missing the full RTK14 header row")
    return _source_rows_to_rtk(rows)


def _source_rows_to_rtk(rows):
    rtk = defaultdict(list)
    for row in _validate_source_rows(rows):
        rtk[row["name"]].append(dict(row))
    return rtk


def rtk_to_source_rows(rtk):
    rows = []
    for source_name, candidates in rtk.items():
        if not isinstance(candidates, list):
            raise ValueError(f"RTK source candidates for {source_name!r} must be a list")
        for candidate in candidates:
            if not isinstance(candidate, dict):
                raise ValueError(f"RTK source candidate for {source_name!r} must be an object")
            row = {field: candidate.get(field) for field in SOURCE_FIELDS}
            row["name"] = source_name
            rows.append(row)
    return _validate_source_rows(rows)


def source_rows(rtk):
    return rtk_to_source_rows(rtk)


def read_rtk14_source_json(path):
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    rows = data.get("rows", data) if isinstance(data, dict) else data
    if not isinstance(rows, list):
        raise ValueError("RTK source JSON must be a list or an object with rows[]")
    return _source_rows_to_rtk(rows)


def read_scenario(scenario_path):
    with open(scenario_path, encoding="utf-8") as f:
        return json.load(f)


def read_devsam(scenario):
    entries = []
    for section in RUNTIME_SECTIONS:
        values = scenario.get(section, [])
        if not isinstance(values, list):
            continue
        for index, arr in enumerate(values):
            if not isinstance(arr, list) or len(arr) < 8:
                continue
            entries.append({
                "key": (section, index),
                "section": section,
                "index": index,
                "arr": arr,
                "name": str(arr[1]) if len(arr) > 1 else "",
                "L": _int(arr[5]) if len(arr) > 5 else 0,
                "S": _int(arr[6]) if len(arr) > 6 else 0,
                "I": _int(arr[7]) if len(arr) > 7 else 0,
                "birth": _int(arr[9]) if len(arr) > 9 else 0,
                "death": _int(arr[10]) if len(arr) > 10 else 0,
            })
    return entries


def _alias_candidates(base, members, rtk):
    source_names = []
    for member in members or []:
        exact_name = str(member["name"])
        if exact_name in rtk and exact_name not in source_names:
            source_names.append(exact_name)
    if base not in source_names:
        source_names.append(base)
    alias = ALIASES.get(base)
    if alias is None and " " in base:
        primary_name = base.split(" ", 1)[0]
        if primary_name in rtk:
            alias = primary_name
    if alias is None and "(" in base:
        primary_name = base.split("(", 1)[0]
        if primary_name in rtk:
            alias = primary_name
    if alias and alias not in source_names:
        source_names.append(alias)

    by_number = {}
    for source_rank, source_name in enumerate(source_names):
        for candidate in rtk.get(source_name, []):
            number = candidate["number"]
            if number not in by_number:
                by_number[number] = (source_name, candidate, source_rank)
    candidates = [by_number[number] for number in sorted(by_number)]
    aliases = [source_name for source_name, _, source_rank in candidates if source_rank > 0]
    return candidates, aliases


def _empty_assignment_stats():
    return {
        "distinct": 0,
        "alias": 0,
        "collapsed": 0,
        "workbook_missing": 0,
        "override": 0,
        "missing": 0,
        "fallback": 0,
        "matched": 0,
        "direct": 0,
        "collision": 0,
        "collision_override": 0,
        "unreviewed": 0,
    }


def _new_assignment_report():
    return {
        "aliases": [],
        "collapsed": [],
        "workbookMissing": [],
        "overrides": [],
        "missing": [],
        "collisions": [],
        "collisionOverrides": [],
        "unreviewedLegacyOnly": [],
        "existingSourceIdDuplicates": [],
    }


def _matched_stats(candidate):
    return {target: candidate[source] for target, source, _ in MATCHED_STAT_FIELDS}


def _distance(candidate, entry):
    return (
        abs(candidate["L"] - entry["L"])
        + abs(candidate["S"] - entry["S"])
        + abs(candidate["I"] - entry["I"])
    )


def _entry_source_id(entry, source_by_id):
    arr = entry["arr"]
    if len(arr) <= 17 or arr[17] is None:
        return None
    value = arr[17]
    if isinstance(value, bool) or not isinstance(value, int) or value not in source_by_id:
        raise ValueError(f"{entry['section']}[{entry['index']}] has invalid RTK14 officerNumber metadata {value!r}")
    return value


def _entry_runtime_identity(scenario_identity, entry):
    return runtime_identity(
        scenario_identity,
        entry["section"],
        entry["index"],
        entry["name"],
        entry["L"],
        entry["S"],
        entry["I"],
        entry["birth"],
        entry["death"],
    )


def _assign_pending(
    entries,
    rtk,
    used_numbers,
    stats,
    report,
    scenario_identity="external",
    collision_overrides=None,
    unmatched_overrides=None,
):
    collision_overrides = COLLISION_OVERRIDES if collision_overrides is None else collision_overrides
    unmatched_overrides = UNMATCHED_OVERRIDES if unmatched_overrides is None else unmatched_overrides
    candidates_by_key = {}
    pairs = []
    for entry_order, entry in enumerate(entries):
        base = base_name(entry["name"])
        candidates, _ = _alias_candidates(base, [entry], rtk)
        candidates_by_key[entry["key"]] = candidates
        for source_name, candidate, source_rank in candidates:
            pairs.append((
                _distance(candidate, entry),
                abs(candidate["birth"] - entry["birth"]),
                abs(candidate["death"] - entry["death"]),
                source_rank,
                entry["name"],
                entry["section"],
                entry["index"],
                candidate["number"],
                entry_order,
                source_name,
                candidate,
            ))

    choices = {}
    for _, _, _, source_rank, _, _, _, candidate_number, entry_order, source_name, candidate in sorted(pairs):
        entry = entries[entry_order]
        if entry["key"] in choices or candidate_number in used_numbers:
            continue
        used_numbers.add(candidate_number)
        choices[entry["key"]] = {"kind": "source", "source": candidate, "sourceName": source_name}
        stats["matched"] += 1
        stats["distinct"] += 1
        if source_rank > 0:
            stats["alias"] += 1
            report["aliases"].append(f"{entry['name']}->{source_name}")

    for entry in entries:
        if entry["key"] in choices:
            continue
        base = base_name(entry["name"])
        candidates = candidates_by_key[entry["key"]]
        identity = _entry_runtime_identity(scenario_identity, entry)
        detail = {"name": entry["name"], "baseName": base, "identity": identity}
        if candidates:
            stats["collision"] += 1
            report["collisions"].append({**detail, "reason": "all_candidate_officer_numbers_already_consumed"})
            override = collision_overrides.get(identity)
            if override is None:
                stats["unreviewed"] += 1
                report["unreviewedLegacyOnly"].append({**detail, "reason": "collision_override_missing"})
                choices[entry["key"]] = {"kind": "unreviewed", "identity": identity}
                continue
            stats["collision_override"] += 1
            report["collisionOverrides"].append({
                **detail,
                "politics": override["politics"],
                "charm": override["charm"],
                "rationale": override["rationale"],
            })
            choices[entry["key"]] = {
                "kind": "override",
                "override": override,
                "overrideKind": "collision",
                "identity": identity,
            }
            continue
        stats["workbook_missing"] += 1
        report["workbookMissing"].append(detail)
        override = unmatched_overrides.get(base)
        if override is None:
            stats["missing"] += 1
            report["missing"].append(detail)
            stats["unreviewed"] += 1
            report["unreviewedLegacyOnly"].append({**detail, "reason": "workbook_missing_override_missing"})
            choices[entry["key"]] = {"kind": "unreviewed", "identity": identity}
            continue
        stats["override"] += 1
        report["overrides"].append({
            **detail,
            "politics": override["politics"],
            "charm": override["charm"],
            "rationale": override["rationale"],
        })
        choices[entry["key"]] = {
            "kind": "override",
            "override": override,
            "overrideKind": "workbook_missing",
            "identity": identity,
        }
    return choices


def _scenario_start_year(scenario):
    value = scenario.get("startYear")
    if isinstance(value, bool) or not isinstance(value, int):
        raise ValueError("runtime scenario startYear must be an integer")
    return value


def _legacy_active_at_start(entry, start_year):
    arr = entry["arr"]
    if len(arr) > 24:
        existing = arr[24]
        if isinstance(existing, bool):
            return existing
        if existing is not None:
            raise ValueError(f"{entry['section']}[{entry['index']}] has invalid legacyActiveAtStart metadata")
    return entry["death"] > start_year and entry["birth"] + 14 <= start_year


def _apply_source(arr, source, rtk14_added, legacy_active_at_start):
    while len(arr) <= 24:
        arr.append(None)
    arr[5] = source["L"]
    arr[6] = source["S"]
    arr[7] = source["I"]
    arr[9] = source["birth"]
    arr[10] = source["death"]
    arr[14] = source["politics"]
    arr[15] = source["charm"]
    arr[16] = source["appearance"]
    arr[17] = source["number"]
    arr[18] = source["gender"]
    arr[19] = source["lifespan"]
    arr[20] = source["activityYears"]
    arr[21] = source["total"]
    arr[22] = source["ideology"]
    arr[23] = bool(rtk14_added)
    arr[24] = False if rtk14_added else bool(legacy_active_at_start)


def _apply_override(arr, override, legacy_active_at_start):
    while len(arr) <= 24:
        arr.append(None)
    arr[14] = override["politics"]
    arr[15] = override["charm"]
    arr[24] = bool(legacy_active_at_start)


def _new_general(source, name):
    return [
        0,
        name,
        None,
        0,
        None,
        source["L"],
        source["S"],
        source["I"],
        0,
        source["birth"],
        source["death"],
        None,
        None,
        None,
        source["politics"],
        source["charm"],
        source["appearance"],
        source["number"],
        source["gender"],
        source["lifespan"],
        source["activityYears"],
        source["total"],
        source["ideology"],
        True,
        False,
    ]


def _tuple_int(arr, index, default=None):
    value = arr[index] if len(arr) > index else None
    return value if type(value) is int else default


def _active_at_start(arr, start_year):
    death = _tuple_int(arr, 10, 300)
    appearance = _tuple_int(arr, 16)
    if appearance is not None:
        return appearance <= start_year <= death
    birth = _tuple_int(arr, 9, 180)
    return death > start_year and birth + 14 <= start_year


def _materialized_active_general_contract(scenario):
    start_year = _scenario_start_year(scenario)
    general = scenario.get("general") if isinstance(scenario.get("general"), list) else []
    general_ex = scenario.get("general_ex") if isinstance(scenario.get("general_ex"), list) else []
    general_neutral = (
        scenario.get("general_neutral")
        if isinstance(scenario.get("general_neutral"), list)
        else []
    )

    def is_rtk14_added(arr):
        return isinstance(arr, list) and len(arr) > 23 and arr[23] is True

    def has_officer_number(arr):
        return isinstance(arr, list) and len(arr) > 17 and type(arr[17]) is int

    legacy_base = [arr for arr in general if isinstance(arr, list) and not is_rtk14_added(arr)]
    added_base = [arr for arr in general if isinstance(arr, list) and is_rtk14_added(arr)]
    legacy_neutral = [
        arr for arr in general_neutral if isinstance(arr, list) and not is_rtk14_added(arr)
    ]
    source_extended = [arr for arr in general_ex if has_officer_number(arr)]
    all_extended = [arr for arr in general_ex if isinstance(arr, list)]

    def active_count(rows):
        return sum(_active_at_start(arr, start_year) for arr in rows)

    common = legacy_base + legacy_neutral + added_base
    return {
        "base": active_count(common + source_extended),
        "extended": active_count(common + all_extended),
    }


def _runtime_names(scenario):
    names = set()
    base_names = set()
    counts = defaultdict(int)
    for section in RUNTIME_SECTIONS:
        entries = scenario.get(section, [])
        if not isinstance(entries, list):
            continue
        for arr in entries:
            if not isinstance(arr, list) or len(arr) < 2 or arr[1] is None:
                continue
            name = str(arr[1])
            names.add(name)
            base_names.add(base_name(name))
            counts[name] += 1
    duplicates = [{"name": name, "count": count} for name, count in sorted(counts.items()) if count > 1]
    return names, base_names, duplicates


def _allocate_added_name(source_name, names, base_names):
    base = base_name(source_name)
    if source_name not in names and base not in base_names:
        names.add(source_name)
        base_names.add(base)
        return source_name, None
    suffix = 1
    while f"{base}{suffix}" in names:
        suffix += 1
    assigned_name = f"{base}{suffix}"
    names.add(assigned_name)
    base_names.add(base)
    return assigned_name, {
        "sourceName": source_name,
        "assignedName": assigned_name,
        "reason": "existing_runtime_or_duplicate_base_name",
    }


def _verify_roster(
    scenario,
    source_by_id,
    added_rows,
    added_general_neutral,
    expected_active_by_key,
    reviewed_legacy_by_key,
):
    entries = read_devsam(scenario)
    source_numbers = []
    legacy_only = []
    unreviewed_legacy_only = []
    for entry in entries:
        source_number = _entry_source_id(entry, source_by_id)
        if source_number is None:
            detail = {"section": entry["section"], "index": entry["index"], "name": entry["name"]}
            reviewed = reviewed_legacy_by_key.get(entry["key"])
            if reviewed is None:
                unreviewed_legacy_only.append(detail)
            else:
                if entry["arr"][14] != reviewed["politics"] or entry["arr"][15] != reviewed["charm"]:
                    raise ValueError(f"{entry['section']}[{entry['index']}] reviewed override values were not applied")
                detail.update(reviewed)
            legacy_only.append(detail)
        else:
            if len(entry["arr"]) <= 24 or type(entry["arr"][24]) is not bool:
                raise ValueError(f"{entry['section']}[{entry['index']}] is missing legacyActiveAtStart metadata")
            added = len(entry["arr"]) > 23 and entry["arr"][23] is True
            expected_active = False if added else expected_active_by_key.get(entry["key"])
            if expected_active is None or entry["arr"][24] is not expected_active:
                raise ValueError(f"{entry['section']}[{entry['index']}] has inconsistent legacyActiveAtStart metadata")
            source_numbers.append(source_number)
    if unreviewed_legacy_only:
        labels = ", ".join(f"{item['section']}[{item['index']}]={item['name']}" for item in unreviewed_legacy_only)
        raise ValueError(f"runtime legacy-only rows have no explicit reviewed override: {labels}")
    duplicates = sorted(number for number in set(source_numbers) if source_numbers.count(number) > 1)
    if duplicates:
        raise ValueError(f"runtime scenario has duplicate RTK14 officerNumber metadata: {duplicates}")
    expected = set(source_by_id)
    represented = set(source_numbers)
    missing = sorted(expected - represented)
    unexpected = sorted(represented - expected)
    if missing or unexpected or len(source_numbers) != len(expected):
        raise ValueError(f"runtime scenario does not represent each RTK14 officer exactly once: missing={missing} unexpected={unexpected}")
    final_roster_rows = len(entries)
    if final_roster_rows != len(expected) + len(legacy_only):
        raise ValueError("runtime final roster count does not equal source rows plus legacy-only rows")
    if added_general_neutral != 0:
        raise ValueError("RTK14 workbook-only rows must not be added to general_neutral")
    return {
        "representedSourceRows": len(represented),
        "missingSourceIds": missing,
        "unexpectedSourceIds": unexpected,
        "existingSourceIdDuplicates": duplicates,
        "legacyOnlyRows": len(legacy_only),
        "legacyOnlyDetails": legacy_only,
        "reviewedLegacyOnlyRows": len(legacy_only),
        "unreviewedLegacyOnlyRows": unreviewed_legacy_only,
        "finalRosterRows": final_roster_rows,
        "addedRows": added_rows,
        "addedGeneralRows": added_rows,
        "addedGeneralNeutral": added_general_neutral,
    }


def enrich_scenario(scenario, rtk, scenario_identity="in_memory", collision_overrides=None, unmatched_overrides=None):
    enriched = copy.deepcopy(scenario)
    sources = source_rows(rtk)
    source_by_id = {row["number"]: row for row in sources}
    entries = read_devsam(enriched)
    start_year = _scenario_start_year(enriched)
    stats = _empty_assignment_stats()
    report = _new_assignment_report()
    choices = {}
    used_numbers = set()

    pending = []
    for entry in entries:
        source_number = _entry_source_id(entry, source_by_id)
        if source_number is None:
            pending.append(entry)
            continue
        if source_number in used_numbers:
            report["existingSourceIdDuplicates"].append({
                "sourceNumber": source_number,
                "section": entry["section"],
                "index": entry["index"],
                "name": entry["name"],
            })
            raise ValueError(f"runtime scenario has duplicate RTK14 officerNumber metadata {source_number}")
        used_numbers.add(source_number)
        choices[entry["key"]] = {
            "kind": "source",
            "source": source_by_id[source_number],
            "rtk14Added": len(entry["arr"]) > 23 and entry["arr"][23] is True,
        }
        stats["matched"] += 1
        stats["direct"] += 1

    choices.update(_assign_pending(
        pending,
        rtk,
        used_numbers,
        stats,
        report,
        scenario_identity=scenario_identity,
        collision_overrides=collision_overrides,
        unmatched_overrides=unmatched_overrides,
    ))
    unreviewed = [entry for entry in entries if choices[entry["key"]]["kind"] == "unreviewed"]
    if unreviewed:
        identities = ", ".join(choices[entry["key"]]["identity"] for entry in unreviewed)
        raise ValueError(f"runtime legacy-only rows have no explicit reviewed override: {identities}")

    expected_active_by_key = {}
    reviewed_legacy_by_key = {}
    for entry in entries:
        choice = choices[entry["key"]]
        legacy_active = _legacy_active_at_start(entry, start_year)
        if choice["kind"] == "source":
            expected_active_by_key[entry["key"]] = False if choice.get("rtk14Added", False) else legacy_active
            _apply_source(
                entry["arr"],
                choice["source"],
                choice.get("rtk14Added", False),
                legacy_active,
            )
        elif choice["kind"] == "override":
            _apply_override(entry["arr"], choice["override"], legacy_active)
            reviewed_legacy_by_key[entry["key"]] = {
                "identity": choice["identity"],
                "overrideKind": choice["overrideKind"],
                "reviewedOverride": True,
                "politics": choice["override"]["politics"],
                "charm": choice["override"]["charm"],
                "rationale": choice["override"]["rationale"],
            }

    existing_names, existing_base_names, runtime_name_duplicates = _runtime_names(enriched)
    general = enriched.get("general")
    if not isinstance(general, list):
        general = []
        enriched["general"] = general
    name_collisions = []
    added_rows = 0
    for source in sources:
        if source["number"] in used_numbers:
            continue
        assigned_name, collision = _allocate_added_name(source["name"], existing_names, existing_base_names)
        if collision is not None:
            collision["sourceNumber"] = source["number"]
            name_collisions.append(collision)
        general.append(_new_general(source, assigned_name))
        used_numbers.add(source["number"])
        added_rows += 1

    audit = _verify_roster(
        enriched,
        source_by_id,
        added_rows,
        0,
        expected_active_by_key,
        reviewed_legacy_by_key,
    )
    audit.update(report)
    audit["counts"] = stats
    audit["matchedExistingRows"] = stats["matched"]
    audit["runtimeNameDuplicates"] = runtime_name_duplicates
    audit["nameCollisions"] = name_collisions
    active_general_contract = None
    if "seedContract" in enriched:
        active_general_contract = _materialized_active_general_contract(enriched)
        enriched["seedContract"] = {"activeGenerals": active_general_contract}
    audit["activeGeneralContract"] = active_general_contract
    return enriched, audit


def _scenario_schema(scenario):
    if any(section in scenario for section in RUNTIME_SECTIONS):
        return "legacy_runtime_tuples"
    if any(section in scenario for section in ARCHIVE_SECTIONS):
        return "normalized_archive"
    return "no_applicable_tuples"


def _inactive_detail(schema, status):
    return {
        "schema": schema,
        "status": status,
        "aliases": [],
        "collapsed": [],
        "workbookMissing": [],
        "overrides": [],
        "missing": [],
        "collisions": [],
        "collisionOverrides": [],
        "unreviewedLegacyOnly": [],
        "existingSourceIdDuplicates": [],
        "representedSourceRows": 0,
        "missingSourceIds": [],
        "unexpectedSourceIds": [],
        "legacyOnlyRows": 0,
        "legacyOnlyDetails": [],
        "reviewedLegacyOnlyRows": 0,
        "unreviewedLegacyOnlyRows": [],
        "finalRosterRows": 0,
        "addedRows": 0,
        "addedGeneralRows": 0,
        "addedGeneralNeutral": 0,
        "runtimeNameDuplicates": [],
        "nameCollisions": [],
        "activeGeneralContract": None,
        "counts": _empty_assignment_stats(),
    }


def build_one(path, out_dir, rtk, scenario_root=None, dry_run=False):
    relative_path = Path(path).relative_to(scenario_root) if scenario_root else Path(Path(path).name)
    scenario = read_scenario(path)
    schema = _scenario_schema(scenario)
    if schema == "normalized_archive":
        return None, 0, 0, _empty_assignment_stats(), _inactive_detail(schema, "excluded_non_runtime_schema")

    out_path = Path(out_dir) / relative_path
    devsam = read_devsam(scenario)
    if not devsam:
        if not dry_run:
            out_path.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, out_path)
        return str(out_path), 0, 0, _empty_assignment_stats(), _inactive_detail(schema, "untouched_no_applicable_tuples")

    enriched, detail = enrich_scenario(scenario, rtk, scenario_identity=relative_path.as_posix())
    detail["schema"] = schema
    detail["status"] = "dry_run_would_update" if dry_run else "updated"
    if not dry_run:
        out_path.parent.mkdir(parents=True, exist_ok=True)
        with open(out_path, "w", encoding="utf-8") as f:
            json.dump(enriched, f, ensure_ascii=False, separators=(",", ":"))
    counts = detail["counts"]
    assigned_count = counts["matched"] + counts["override"] + counts["collision_override"]
    return str(out_path), len(devsam), assigned_count, counts, detail


def build_all(scenario_dir, out_dir, rtk, dry_run=False):
    root = Path(scenario_dir)
    source_count = len(source_rows(rtk))
    totals = {
        "files": 0,
        "updatedFiles": 0,
        "untouchedFiles": 0,
        "excludedFiles": 0,
        "entries": 0,
        "assignedEntries": 0,
        "distinct": 0,
        "alias": 0,
        "collapsed": 0,
        "workbookMissing": 0,
        "override": 0,
        "missing": 0,
        "fallback": 0,
        "matched": 0,
        "direct": 0,
        "collision": 0,
        "collisionOverride": 0,
        "unreviewedLegacyOnly": 0,
        "representedSourceRows": 0,
        "legacyOnlyRows": 0,
        "reviewedLegacyOnlyRows": 0,
        "finalRosterRows": 0,
        "addedRows": 0,
        "addedGeneralRows": 0,
        "addedGeneralNeutral": 0,
    }
    files = []
    workbook_missing_names = set()
    unresolved_missing_names = set()
    for path in sorted(root.rglob("scenario_*.json")):
        out_path, entry_count, assigned_count, stats, detail = build_one(
            path, out_dir, rtk, scenario_root=root, dry_run=dry_run
        )
        status = detail["status"]
        totals["files"] += 1
        totals["entries"] += entry_count
        totals["assignedEntries"] += assigned_count
        if status in ("updated", "dry_run_would_update"):
            totals["updatedFiles"] += 1
            if detail["representedSourceRows"] != source_count or detail["missingSourceIds"]:
                raise ValueError(f"{path} did not represent every RTK14 source row")
            if detail["addedGeneralNeutral"] != 0:
                raise ValueError(f"{path} added RTK14 rows to general_neutral")
            if detail["finalRosterRows"] != source_count + detail["legacyOnlyRows"]:
                raise ValueError(f"{path} final roster count is inconsistent")
            if detail["unreviewedLegacyOnlyRows"]:
                raise ValueError(f"{path} has legacy-only rows without reviewed overrides")
            if detail["reviewedLegacyOnlyRows"] != detail["legacyOnlyRows"]:
                raise ValueError(f"{path} did not review every legacy-only row")
        elif status == "excluded_non_runtime_schema":
            totals["excludedFiles"] += 1
        else:
            totals["untouchedFiles"] += 1
        for key, value in stats.items():
            total_key = {
                "workbook_missing": "workbookMissing",
                "collision_override": "collisionOverride",
                "unreviewed": "unreviewedLegacyOnly",
            }.get(key, key)
            totals[total_key] += value
        for key in (
            "representedSourceRows",
            "legacyOnlyRows",
            "reviewedLegacyOnlyRows",
            "finalRosterRows",
            "addedRows",
            "addedGeneralRows",
            "addedGeneralNeutral",
        ):
            totals[key] += detail[key]
        workbook_missing_names.update(entry["baseName"] for entry in detail["workbookMissing"])
        unresolved_missing_names.update(entry["baseName"] for entry in detail["missing"])
        files.append({
            "file": path.relative_to(root).as_posix(),
            "output": str(Path(out_path).relative_to(out_dir)) if out_path else None,
            "schema": detail["schema"],
            "status": status,
            "entries": entry_count,
            "assignedEntries": assigned_count,
            "counts": stats,
            "aliases": detail["aliases"],
            "collapsed": detail["collapsed"],
            "workbookMissing": detail["workbookMissing"],
            "overrides": detail["overrides"],
            "missing": detail["missing"],
            "collisions": detail["collisions"],
            "collisionOverrides": detail["collisionOverrides"],
            "unreviewedLegacyOnlyRows": detail["unreviewedLegacyOnlyRows"],
            "existingSourceIdDuplicates": detail["existingSourceIdDuplicates"],
            "representedSourceRows": detail["representedSourceRows"],
            "missingSourceIds": detail["missingSourceIds"],
            "unexpectedSourceIds": detail["unexpectedSourceIds"],
            "legacyOnlyRows": detail["legacyOnlyRows"],
            "legacyOnlyDetails": detail["legacyOnlyDetails"],
            "reviewedLegacyOnlyRows": detail["reviewedLegacyOnlyRows"],
            "finalRosterRows": detail["finalRosterRows"],
            "addedRows": detail["addedRows"],
            "addedGeneralRows": detail["addedGeneralRows"],
            "addedGeneralNeutral": detail["addedGeneralNeutral"],
            "runtimeNameDuplicates": detail["runtimeNameDuplicates"],
            "nameCollisions": detail["nameCollisions"],
            "activeGeneralContract": detail["activeGeneralContract"],
        })
    return {
        "schemaVersion": 3,
        "scope": "legacy_runtime_tuples_with_full_rtk14_roster",
        "dryRun": dry_run,
        "sourceRows": source_count,
        "scenarioDirectory": str(root),
        "outputDirectory": str(out_dir),
        "totals": totals,
        "workbookMissingNames": sorted(workbook_missing_names),
        "unresolvedMissingNames": sorted(unresolved_missing_names),
        "files": files,
    }


def write_report(report, path):
    output = Path(path)
    output.parent.mkdir(parents=True, exist_ok=True)
    with open(output, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)


def main():
    ap = argparse.ArgumentParser()
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("--xlsx")
    src.add_argument("--rtk-source-json")
    ap.add_argument("--dump-rtk-source-json")
    ap.add_argument("--scenario-dir", default="infra/src/main/resources/scenario")
    ap.add_argument("--out-dir", default="infra/src/main/resources/rtk14-scenarios.local")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--report-json")
    a = ap.parse_args()

    rtk = read_rtk14(a.xlsx) if a.xlsx else read_rtk14_source_json(a.rtk_source_json)
    if a.dump_rtk_source_json:
        out = Path(a.dump_rtk_source_json)
        out.parent.mkdir(parents=True, exist_ok=True)
        with open(out, "w", encoding="utf-8") as f:
            json.dump({"rows": rtk_to_source_rows(rtk)}, f, ensure_ascii=False, separators=(",", ":"))
        print(f"wrote RTK source JSON rows={len(source_rows(rtk))} -> {out}")
        return

    report = build_all(a.scenario_dir, a.out_dir, rtk, dry_run=a.dry_run)
    if a.report_json:
        write_report(report, a.report_json)
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
