import argparse
import copy
import csv
import hashlib
import json
import os
import re
import sys
import tempfile
import xml.etree.ElementTree as element_tree
import zipfile
from pathlib import Path


FINGERPRINT_FIELDS = ("birth", "death", "leadership", "strength", "intelligence", "politics", "charm")
RANK_BY_STATUS = {"君主": 12, "太守": 4, "都督": 0, "一般": 0, "在野": 0}
XLSX_NAMESPACE = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
COLUMN_REFERENCE = re.compile(r"(?P<column>[A-Z]+)")
OVERRIDE_HEADERS = ("name_kanji", "name_reading", "page_key", "officer_number", "name_korean", "expected_mismatch_field")
APPROVED_SPECIAL_REMAPS = {
    "小沛": "패",
    "武威": "서량",
    "武関": "홍농",
    "涪水関": "자동",
    "潼関": "장안",
    "白水関": "자동",
    "綿竹関": "면죽",
    "虎牢関": "사수",
    "陽平関": "한중",
    "函谷関": "함곡",
    "剣閣": "자동",
    "壺関": "호관",
}
REVIEWED_NEAREST_REMAPS = {"襄平": "북평", "建安": "회계"}
REQUIRED_LOCATION_REMAPS = {**APPROVED_SPECIAL_REMAPS, **REVIEWED_NEAREST_REMAPS}


def _integer(value: object) -> int | None:
    try:
        return int(str(value).strip())
    except (TypeError, ValueError):
        return None


def _column_index(reference: str | None) -> int:
    match = COLUMN_REFERENCE.match(reference or "")
    if match is None:
        raise ValueError(f"missing XLSX cell reference: {reference}")
    index = 0
    for character in match.group("column"):
        index = index * 26 + ord(character) - ord("A") + 1
    return index - 1


def _shared_strings(archive: zipfile.ZipFile) -> list[str]:
    try:
        root = element_tree.fromstring(archive.read("xl/sharedStrings.xml"))
    except KeyError:
        return []
    return ["".join(text.text or "" for text in item.iter(XLSX_NAMESPACE + "t")) for item in root.findall(XLSX_NAMESPACE + "si")]


def _cell_value(cell: element_tree.Element, strings: list[str]) -> str | None:
    if cell.get("t") == "inlineStr":
        return "".join(text.text or "" for text in cell.iter(XLSX_NAMESPACE + "t"))
    value = cell.find(XLSX_NAMESPACE + "v")
    if value is None or value.text is None:
        return None
    if cell.get("t") == "s":
        index = _integer(value.text)
        if index is None or index >= len(strings):
            raise ValueError("XLSX shared-string index is invalid")
        return strings[index]
    return value.text


def _worksheet_rows(path: Path) -> list[dict[int, str | None]]:
    with zipfile.ZipFile(path) as archive:
        strings = _shared_strings(archive)
        root = element_tree.fromstring(archive.read("xl/worksheets/sheet1.xml"))
    rows: list[dict[int, str | None]] = []
    for row in root.findall(".//" + XLSX_NAMESPACE + "row"):
        cells: dict[int, str | None] = {}
        for cell in row.findall(XLSX_NAMESPACE + "c"):
            cells[_column_index(cell.get("r"))] = _cell_value(cell, strings)
        if cells:
            rows.append(cells)
    return rows


def load_xlsx_rows(path: Path) -> list[dict]:
    rows = _worksheet_rows(path)
    if not rows:
        raise ValueError("XLSX has no rows")
    headers = {value: index for index, value in rows[0].items() if value is not None}
    labels = {
        "무장": "name_korean",
        "생년": "birth",
        "몰년": "death",
        "통솔": "leadership",
        "무력": "strength",
        "지력": "intelligence",
        "정치": "politics",
        "매력": "charm",
    }
    missing = [label for label in labels if label not in headers]
    if missing:
        raise ValueError(f"XLSX is missing columns: {', '.join(missing)}")
    output: list[dict] = []
    for row in rows[1:]:
        name = row.get(headers["무장"])
        if name is None or not name.strip():
            continue
        record = {"name_korean": name.strip()}
        if "무장번호" in headers:
            officer_number = row.get(headers["무장번호"])
            if officer_number is None or not officer_number.strip():
                raise ValueError(f"XLSX row for {name} has invalid 무장번호")
            record["officer_number"] = officer_number.strip()
        for label, field in labels.items():
            if field == "name_korean":
                continue
            value = _integer(row.get(headers[label]))
            if value is None:
                raise ValueError(f"XLSX row for {name} has invalid {label}")
            record[field] = value
        output.append(record)
    return sorted(output, key=lambda row: (row["name_korean"], _fingerprint(row), str(row.get("officer_number") or "")))


def _fingerprint(record: dict) -> tuple[object, ...]:
    return tuple(record.get(field) for field in FINGERPRINT_FIELDS)


def _fingerprint_sha256(record: dict) -> str:
    encoded = json.dumps(_fingerprint(record), ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _stable_key(record: dict) -> str:
    identity = {
        "name_kanji": record.get("name_kanji"),
        "name_reading": record.get("name_reading") or "",
        "page_key": record.get("page_key"),
    }
    if not all(isinstance(value, str) and value for value in (identity["name_kanji"], identity["page_key"])):
        raise ValueError("raw officer requires name_kanji and page_key")
    return hashlib.sha256(json.dumps(identity, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")).hexdigest()


def _raw_sort_key(record: dict) -> tuple[str, str, str]:
    return (str(record.get("name_kanji") or ""), str(record.get("name_reading") or ""), str(record.get("page_key") or ""))


def _blank_report() -> dict:
    return {
        "unresolved": [],
        "ambiguous": [],
        "collisions": [],
        "semantic_downgrades": [],
        "exact_join_count": 0,
        "override_join_count": 0,
    }


def _override_key(record: dict) -> tuple[str, str, str]:
    name_kanji = record.get("name_kanji")
    name_reading = record.get("name_reading")
    page_key = record.get("page_key")
    if not all(isinstance(value, str) and value for value in (name_kanji, name_reading, page_key)):
        raise ValueError("override identity requires name_kanji, name_reading, and page_key")
    return name_kanji, name_reading, page_key


def _candidate_id(index: int, candidate: dict) -> str:
    officer_number = candidate.get("officer_number")
    return f"officer:{officer_number}" if officer_number is not None else f"index:{index}"


def _validate_override(key: tuple[str, str, str], override: dict) -> None:
    if not isinstance(override, dict):
        raise ValueError(f"override for {key} must be an object")
    values = [override.get(field) for field in ("officer_number", "name_korean", "expected_mismatch_field")]
    if not all(isinstance(value, str) and value for value in values):
        raise ValueError(f"override for {key} is incomplete")
    if override["expected_mismatch_field"] not in FINGERPRINT_FIELDS:
        raise ValueError(f"override for {key} has an invalid mismatch field")


def join_korean_names(raw: list[dict], xlsx_rows: list[dict], overrides: dict[tuple[str, str, str], dict] | None = None) -> tuple[list[dict], dict]:
    if len(raw) != len(xlsx_rows):
        raise ValueError("raw and XLSX row counts must match for an exact bijection")
    override_map = overrides or {}
    candidates: dict[tuple[object, ...], list[tuple[str, dict]]] = {}
    xlsx_by_number: dict[str, tuple[str, dict]] = {}
    all_candidate_ids: set[str] = set()
    for index, row in enumerate(xlsx_rows):
        candidate_id = _candidate_id(index, row)
        if candidate_id in all_candidate_ids:
            raise ValueError("XLSX candidate identities must be unique")
        all_candidate_ids.add(candidate_id)
        candidates.setdefault(_fingerprint(row), []).append((candidate_id, row))
        officer_number = row.get("officer_number")
        if officer_number is not None:
            officer_number = str(officer_number)
            if officer_number in xlsx_by_number:
                raise ValueError("XLSX officer numbers must be unique")
            xlsx_by_number[officer_number] = (candidate_id, row)
    report = _blank_report()
    joined: list[dict] = []
    deferred: list[dict] = []
    used_candidates: set[str] = set()
    for record in sorted(raw, key=_raw_sort_key):
        matches = candidates.get(_fingerprint(record), [])
        descriptor = {
            "name_kanji": record.get("name_kanji"),
            "page_key": record.get("page_key"),
            "fingerprint_sha256": _fingerprint_sha256(record),
        }
        if not matches:
            deferred.append(record)
            continue
        if len(matches) != 1:
            report["ambiguous"].append({
                **descriptor,
                "candidate_count": len(matches),
                "candidates": sorted(str(candidate.get("name_korean") or "") for _, candidate in matches),
            })
            continue
        candidate_id, candidate = matches[0]
        if candidate_id in used_candidates:
            report["collisions"].append({"kind": "xlsx_candidate_reused", "candidate": candidate_id})
            continue
        enriched = copy.deepcopy(record)
        enriched["name_korean"] = candidate["name_korean"]
        joined.append(enriched)
        used_candidates.add(candidate_id)
        report["exact_join_count"] += 1
    applied_overrides: set[tuple[str, str, str]] = set()
    for record in deferred:
        descriptor = {
            "name_kanji": record.get("name_kanji"),
            "page_key": record.get("page_key"),
            "fingerprint_sha256": _fingerprint_sha256(record),
        }
        key = _override_key(record)
        override = override_map.get(key)
        if override is None:
            report["unresolved"].append(descriptor)
            continue
        _validate_override(key, override)
        candidate_entry = xlsx_by_number.get(override["officer_number"])
        if candidate_entry is None:
            raise ValueError(f"override for {key} references a missing XLSX officer number")
        candidate_id, candidate = candidate_entry
        if candidate_id in used_candidates:
            raise ValueError(f"override for {key} references an already used XLSX candidate")
        if candidate.get("name_korean") != override["name_korean"]:
            raise ValueError(f"override for {key} has a Korean-name drift")
        mismatch_fields = [
            field
            for field, raw_value, candidate_value in zip(FINGERPRINT_FIELDS, _fingerprint(record), _fingerprint(candidate))
            if raw_value != candidate_value
        ]
        if mismatch_fields != [override["expected_mismatch_field"]]:
            raise ValueError(f"override for {key} no longer has exactly the reviewed six-of-seven fingerprint match")
        enriched = copy.deepcopy(record)
        enriched["name_korean"] = candidate["name_korean"]
        joined.append(enriched)
        used_candidates.add(candidate_id)
        applied_overrides.add(key)
        report["override_join_count"] += 1
    unused_overrides = sorted(set(override_map) - applied_overrides)
    if unused_overrides:
        raise ValueError("override table contains entries that were not unresolved exact-join records")
    if len(override_map) == 22 and (len(raw) != 1000 or report["exact_join_count"] != 978):
        raise ValueError("reviewed override table may run only for 1000 records after exactly 978 exact joins")
    if not report["unresolved"] and not report["ambiguous"] and not report["collisions"] and used_candidates != all_candidate_ids:
        raise ValueError("raw and XLSX candidate identities must form an exact bijection")
    return joined, report


def _registry_index(existing_registry: list[dict]) -> tuple[dict[str, dict], dict[int, dict]]:
    by_key: dict[str, dict] = {}
    by_id: dict[int, dict] = {}
    for entry in existing_registry:
        stable_key = entry.get("stable_key")
        fingerprint_sha256 = entry.get("fingerprint_sha256")
        officer_id = _integer(entry.get("id"))
        if not isinstance(stable_key, str) or not stable_key or not isinstance(fingerprint_sha256, str) or officer_id is None:
            raise ValueError("registry entry is incomplete")
        if stable_key in by_key or officer_id in by_id:
            raise ValueError("registry contains a duplicate stable key or id")
        by_key[stable_key] = entry
        by_id[officer_id] = entry
    ids = sorted(by_id)
    if ids and ids != list(range(10001, 10001 + len(ids))):
        raise ValueError("registry ids must be contiguous from 10001")
    return by_key, by_id


def _semantic_downgrades(record: dict, officer_id: int) -> list[dict]:
    output: list[dict] = []
    for scenario in record.get("scenarios", []):
        status = scenario.get("status")
        office = scenario.get("office")
        common = {
            "id": officer_id,
            "year_month": scenario.get("year_month"),
            "status": status,
            "v1_rank": scenario.get("v1_rank"),
        }
        if status == "太守":
            output.append({**common, "kind": "taesu_officer_city_unrepresentable"})
        if status == "都督":
            output.append({**common, "kind": "dudok_rank_downgrade"})
        if office is not None and status != "君主":
            output.append({**common, "kind": "non_ruler_office_unrepresentable", "office": office})
    return output


def _with_rank(record: dict, officer_id: int) -> tuple[dict, list[dict]]:
    refined = copy.deepcopy(record)
    scenarios = []
    for scenario in refined.get("scenarios", []):
        scenario["v1_rank"] = RANK_BY_STATUS.get(scenario.get("status"))
        scenarios.append(scenario)
    refined["scenarios"] = sorted(
        scenarios,
        key=lambda scenario: (
            str(scenario.get("year_month") or ""),
            str(scenario.get("status") or ""),
            str(scenario.get("location") or ""),
            str(scenario.get("faction") or ""),
            str(scenario.get("office") or ""),
        ),
    )
    refined["id"] = officer_id
    return refined, _semantic_downgrades(refined, officer_id)


def refine(raw: list[dict], xlsx_rows: list[dict], existing_registry: list[dict], overrides: dict[tuple[str, str, str], dict] | None = None) -> tuple[list[dict], list[dict], dict]:
    joined, report = join_korean_names(raw, xlsx_rows, overrides)
    if report["unresolved"] or report["ambiguous"]:
        return [], [], report
    keyed: dict[str, dict] = {}
    for record in joined:
        stable_key = _stable_key(record)
        if stable_key in keyed:
            report["collisions"].append({"stable_key": stable_key})
        else:
            keyed[stable_key] = record
    if report["collisions"]:
        return [], [], report
    existing_by_key, existing_by_id = _registry_index(existing_registry)
    raw_keys = set(keyed)
    missing_registry_keys = sorted(set(existing_by_key) - raw_keys)
    if missing_registry_keys:
        raise ValueError("registry identity is missing from raw input")
    assigned: dict[str, int] = {}
    for stable_key, record in keyed.items():
        entry = existing_by_key.get(stable_key)
        if entry is None:
            continue
        if entry["fingerprint_sha256"] != _fingerprint_sha256(record):
            raise ValueError("registry fingerprint drift detected")
        officer_id = _integer(entry["id"])
        if officer_id is None:
            raise ValueError("registry id is invalid")
        assigned[stable_key] = officer_id
    next_id = 10001 + len(existing_by_id)
    for stable_key, record in sorted(keyed.items(), key=lambda item: _raw_sort_key(item[1])):
        if stable_key not in assigned:
            assigned[stable_key] = next_id
            next_id += 1
    if len(assigned) > 1000 or max(assigned.values(), default=10000) > 11000:
        raise ValueError("officer ids exceed the reserved 10001..11000 band")
    refined: list[dict] = []
    registry: list[dict] = []
    semantic_downgrades: list[dict] = []
    for stable_key, record in sorted(keyed.items(), key=lambda item: assigned[item[0]]):
        officer_id = assigned[stable_key]
        refined_record, downgrades = _with_rank(record, officer_id)
        refined.append(refined_record)
        semantic_downgrades.extend(downgrades)
        registry.append({
            "id": officer_id,
            "stable_key": stable_key,
            "fingerprint_sha256": _fingerprint_sha256(record),
            "name_kanji": record["name_kanji"],
            "name_reading": record.get("name_reading") or "",
        })
    report["semantic_downgrades"] = sorted(
        semantic_downgrades,
        key=lambda entry: json.dumps(entry, ensure_ascii=False, sort_keys=True, separators=(",", ":")),
    )
    return refined, registry, report


def _reject_duplicate_pairs(pairs: list[tuple[str, object]]) -> dict[str, object]:
    output: dict[str, object] = {}
    for key, value in pairs:
        if key in output:
            raise ValueError(f"duplicate mapping key: {key}")
        output[key] = value
    return output


def load_mapping(path: Path) -> dict[str, str]:
    loaded = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_reject_duplicate_pairs)
    if not isinstance(loaded, dict) or not all(isinstance(key, str) and isinstance(value, str) for key, value in loaded.items()):
        raise ValueError(f"mapping must be a string-to-string object: {path}")
    return loaded


def load_name_join_overrides(path: Path) -> dict[tuple[str, str, str], dict]:
    with path.open(encoding="utf-8", newline="") as source:
        reader = csv.DictReader(source, delimiter="\t")
        if tuple(reader.fieldnames or ()) != OVERRIDE_HEADERS:
            raise ValueError("name join override table has an unexpected header")
        overrides: dict[tuple[str, str, str], dict] = {}
        officer_numbers: set[str] = set()
        for row in reader:
            key = tuple((row.get(field) or "").strip() for field in OVERRIDE_HEADERS[:3])
            values = {field: (row.get(field) or "").strip() for field in OVERRIDE_HEADERS[3:]}
            if not all(key) or not all(values.values()):
                raise ValueError("name join override table contains a blank required value")
            if key in overrides:
                raise ValueError("name join override table contains a duplicate identity key")
            if values["officer_number"] in officer_numbers:
                raise ValueError("name join override table contains a duplicate XLSX officer number")
            if values["expected_mismatch_field"] not in FINGERPRINT_FIELDS:
                raise ValueError("name join override table has an invalid mismatch field")
            overrides[key] = values
            officer_numbers.add(values["officer_number"])
    return overrides


def validate_tracked_name_join_overrides() -> dict[tuple[str, str, str], dict]:
    overrides = load_name_join_overrides(Path(__file__).resolve().parent / "name-join-overrides.tsv")
    if len(overrides) != 22:
        raise ValueError("name join override table must contain exactly 22 reviewed rows")
    return overrides


def validate_location_maps(city_map: dict[str, str], remap: dict[str, str], che_names: set[str]) -> None:
    overlap = sorted(set(city_map) & set(remap))
    if overlap:
        raise ValueError(f"direct and remap keys overlap: {', '.join(overlap)}")
    unknown_targets = sorted({target for target in [*city_map.values(), *remap.values()] if target not in che_names})
    if unknown_targets:
        raise ValueError(f"mapping targets missing from che catalog: {', '.join(unknown_targets)}")


def validate_location_remaps(remap: dict[str, str]) -> None:
    if remap != REQUIRED_LOCATION_REMAPS:
        raise ValueError("location-remap.yaml must exactly match the 12 approved and 2 reviewed remaps")


def validate_tracked_location_maps() -> tuple[dict[str, str], dict[str, str]]:
    scenario_directory = Path(__file__).resolve().parent
    repository_root = scenario_directory.parents[1]
    city_map = load_mapping(scenario_directory / "city_map.json")
    remap = load_mapping(scenario_directory / "location-remap.yaml")
    che = json.loads((repository_root / "infra/src/main/resources/map/che.json").read_text(encoding="utf-8"))
    che_names = {city["name"] for city in che["cities"]}
    validate_location_maps(city_map, remap, che_names)
    validate_location_remaps(remap)
    if len(city_map) != 42:
        raise ValueError("city_map.json must contain the 42 direct city mappings")
    return city_map, remap


def _atomic_write(path: Path, content: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as temporary:
            temporary.write(content)
            temporary.flush()
            os.fsync(temporary.fileno())
        os.replace(temporary_name, path)
    except BaseException:
        if os.path.exists(temporary_name):
            os.unlink(temporary_name)
        raise


def _write_json(path: Path, value: object) -> None:
    _atomic_write(path, (json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8"))


def load_registry(path: Path) -> list[dict]:
    if not path.exists():
        return []
    with path.open(encoding="utf-8", newline="") as source:
        return list(csv.DictReader(source, delimiter="\t"))


def write_registry(path: Path, registry: list[dict]) -> None:
    headers = ["id", "stable_key", "fingerprint_sha256", "name_kanji", "name_reading"]
    lines = ["\t".join(headers)]
    for entry in sorted(registry, key=lambda item: int(item["id"])):
        lines.append("\t".join(str(entry[field]) for field in headers))
    _atomic_write(path, ("\n".join(lines) + "\n").encode("utf-8"))


def write_name_map(path: Path, refined: list[dict]) -> None:
    lines = ["id\tname_korean"]
    for record in sorted(refined, key=lambda item: item["id"]):
        lines.append(f"{record['id']}\t{record['name_korean']}")
    _atomic_write(path, ("\n".join(lines) + "\n").encode("utf-8"))


def _location_failures(refined: list[dict], city_map: dict[str, str], remap: dict[str, str]) -> list[dict]:
    unresolved: set[str] = set()
    for record in refined:
        for scenario in record.get("scenarios", []):
            location = scenario.get("location")
            if location is not None and location not in city_map and location not in remap:
                unresolved.add(location)
    return [{"kind": "location", "location": location} for location in sorted(unresolved)]


def _parse_arguments(arguments: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    scenario_directory = Path(__file__).resolve().parent
    parser.add_argument("--raw", type=Path, required=True)
    parser.add_argument("--xlsx", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--registry", type=Path, default=scenario_directory / "officer-id-registry.tsv")
    parser.add_argument("--name-map", type=Path, default=scenario_directory / "officer-name-map.tsv")
    parser.add_argument("--name-join-overrides", type=Path, default=scenario_directory / "name-join-overrides.tsv")
    return parser.parse_args(arguments)


def main(arguments: list[str] | None = None) -> int:
    options = _parse_arguments(arguments if arguments is not None else sys.argv[1:])
    try:
        city_map, remap = validate_tracked_location_maps()
        overrides = load_name_join_overrides(options.name_join_overrides)
        if len(overrides) != 22:
            raise ValueError("name join override table must contain exactly 22 reviewed rows")
        raw = json.loads(options.raw.read_text(encoding="utf-8"))
        if not isinstance(raw, list):
            raise ValueError("raw officer input must be a list")
        refined, registry, report = refine(raw, load_xlsx_rows(options.xlsx), load_registry(options.registry), overrides)
        report["unresolved"].extend(_location_failures(refined, city_map, remap))
        if report["unresolved"] or report["ambiguous"] or report["collisions"]:
            _write_json(options.report, report)
            print("refinement failed closed; inspect report", file=sys.stderr)
            return 1
    except (OSError, ValueError, json.JSONDecodeError, zipfile.BadZipFile) as error:
        _write_json(options.report, {"unresolved": [], "ambiguous": [], "collisions": [str(error)], "semantic_downgrades": []})
        print(str(error), file=sys.stderr)
        return 1
    _write_json(options.out, refined)
    _write_json(options.report, report)
    write_registry(options.registry, registry)
    write_name_map(options.name_map, refined)
    print(f"refined {len(refined)} officers")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
