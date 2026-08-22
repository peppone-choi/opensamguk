#!/usr/bin/env python3
import argparse
import hashlib
import json
import math
import struct
import subprocess
import sys
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CATALOG = ROOT / "data/curated/han/administrative-units.json"
CHGIS_COUNTY_DBF = ROOT / "data/chgis-source/v6_time_cnty_pts_utf_wgs84.dbf"
OUT = ROOT / "data/map/administrative-place-overlay.json"
SOURCE_YEAR = 220
JOIN_STATUSES = (
    "RESOLVED_POINT",
    "AMBIGUOUS_POINT",
    "NO_COORDINATE_CANDIDATE",
    "SOURCE_PLACEHOLDER",
)
NAME_SUFFIXES = ("侯國", "侯国", "縣", "县", "道", "邑")
REQUIRED_DBF_FIELDS = {
    "NAME_CH",
    "NAME_FT",
    "X_COOR",
    "Y_COOR",
    "PRES_LOC",
    "BEG_YR",
    "END_YR",
    "SYS_ID",
}


def normalize_name(value: str) -> str:
    name = unicodedata.normalize("NFC", value.strip())
    for suffix in NAME_SUFFIXES:
        if name.endswith(suffix) and len(name) > len(suffix):
            return name[: -len(suffix)]
    return name


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def repo_root(start: Path) -> Path | None:
    resolved = start.resolve()
    for candidate in (resolved, *resolved.parents):
        if (candidate / ".git").exists():
            return candidate
    return None


def git_ignored(root: Path, path: Path) -> bool:
    result = subprocess.run(
        ["git", "-C", str(root), "check-ignore", "-q", str(path)],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        check=False,
    )
    return result.returncode == 0


def assert_untracked(path: Path, is_ignored=None) -> Path:
    resolved = Path(path).resolve()
    root = repo_root(Path(__file__).parent)
    if root is None or (resolved != root and root not in resolved.parents):
        return resolved
    ignored = (is_ignored or (lambda candidate: git_ignored(root, candidate)))(resolved)
    if not ignored:
        raise ValueError("tracked repo path cannot contain CHGIS coordinate output")
    return resolved


def source_label(path: Path) -> str:
    resolved = Path(path).resolve()
    try:
        return str(resolved.relative_to(ROOT))
    except ValueError:
        return f"external:{resolved.name}"


def read_dbf(path: Path, source_year: int = SOURCE_YEAR) -> tuple[list[dict], list[str]]:
    if source_year != SOURCE_YEAR:
        raise ValueError("source year must be exactly 220")
    raw = path.read_bytes()
    if len(raw) < 33:
        raise ValueError(f"invalid DBF header: {path}")
    record_count, header_length, record_length = struct.unpack("<IHH", raw[4:12])
    fields = []
    offset = 32
    while offset < header_length and raw[offset] != 0x0D:
        if offset + 32 > header_length:
            raise ValueError(f"invalid DBF field descriptor: {path}")
        name = raw[offset : offset + 11].split(b"\0", 1)[0].decode("ascii")
        fields.append((name, raw[offset + 16]))
        offset += 32
    if offset >= header_length or raw[offset] != 0x0D:
        raise ValueError(f"missing DBF field terminator: {path}")
    field_names = [name for name, _ in fields]
    missing = sorted(REQUIRED_DBF_FIELDS - set(field_names))
    if missing:
        raise ValueError(f"missing required DBF fields: {', '.join(missing)}")
    if header_length + record_count * record_length > len(raw):
        raise ValueError(f"truncated DBF records: {path}")
    if 1 + sum(size for _, size in fields) != record_length:
        raise ValueError(f"DBF record width does not match field widths: {path}")

    records = []
    for record_index in range(record_count):
        start = header_length + record_index * record_length
        record = raw[start : start + record_length]
        if record[:1] == b"*":
            continue
        if record[:1] != b" ":
            raise ValueError(f"invalid DBF record marker at record {record_index}")
        cursor = 1
        values = {}
        for field_name, size in fields:
            values[field_name] = record[cursor : cursor + size].decode("utf-8").strip()
            cursor += size
        try:
            values["BEG_YR"] = int(float(values["BEG_YR"]))
            values["END_YR"] = int(float(values["END_YR"]))
        except ValueError as error:
            raise ValueError(f"DBF record {record_index} has invalid year bounds") from error
        if not (values["BEG_YR"] <= source_year <= values["END_YR"]):
            continue
        try:
            values["X_COOR"] = float(values["X_COOR"])
            values["Y_COOR"] = float(values["Y_COOR"])
        except ValueError as error:
            raise ValueError(f"active DBF record {record_index} has invalid coordinates") from error
        if not math.isfinite(values["X_COOR"]) or not math.isfinite(values["Y_COOR"]):
            raise ValueError(f"active DBF record {record_index} has invalid coordinates")
        if not values["SYS_ID"]:
            raise ValueError(f"active DBF record {record_index} has no SYS_ID")
        if not values["NAME_CH"] and not values["NAME_FT"]:
            raise ValueError(f"active DBF record {record_index} has no Chinese name")
        values["recordIndex"] = record_index
        records.append(values)
    return records, field_names


def validate_catalog(catalog: dict) -> None:
    groups = catalog.get("groups", [])
    units = [unit for group in groups for unit in group.get("units", [])]
    expected_groups = catalog.get("expectedGroupCount")
    expected_units = catalog.get("expectedUnitCount")
    detected_groups = catalog.get("detectedGroupCount")
    detected_units = catalog.get("detectedUnitCount")
    counts = (expected_groups, expected_units, detected_groups, detected_units)
    if any(type(value) is not int for value in counts) or not (
        expected_groups == detected_groups == len(groups)
        and expected_units == detected_units == len(units)
    ):
        raise ValueError("catalog count contract mismatch")

    group_identities = []
    administrative_unit_ids = []
    for group in groups:
        group_identity = (group.get("sourceVolume"), group.get("canonicalGroup"))
        volume, canonical_group = group_identity
        if type(volume) is not int or volume not in range(109, 114):
            raise ValueError("catalog group sourceVolume must be an int from 109 through 113")
        if not isinstance(canonical_group, str) or not canonical_group.strip():
            raise ValueError("catalog canonicalGroup must be a non-empty string")
        group_identities.append(group_identity)
        group_units = group.get("units", [])
        for expected_ordinal, unit in enumerate(group_units, start=1):
            if type(unit.get("ordinal")) is not int:
                raise ValueError("catalog unit ordinal must be an int")
            if unit["ordinal"] != expected_ordinal:
                raise ValueError("catalog unit ordinals must be contiguous in source order")
            if (unit.get("sourceVolume"), unit.get("canonicalGroup")) != group_identity:
                raise ValueError("catalog unit identity does not match its group")
            if unit.get("sourceNameStatus") not in {"SOURCE_LITERAL", "SOURCE_PLACEHOLDER"}:
                raise ValueError("catalog unit has unsupported sourceNameStatus")
            if not isinstance(unit.get("sourceName"), str) or not unit["sourceName"].strip():
                raise ValueError("catalog unit sourceName must be a non-empty string")
            citation = unit.get("sourceCitation")
            if not isinstance(citation, dict) or not citation.get("corpusPath") or type(citation.get("line")) is not int:
                raise ValueError("catalog unit sourceCitation requires corpusPath and integer line")
            correction = unit.get("nameCorrection")
            if correction is not None:
                correction_citation = correction.get("sourceCitation") if isinstance(correction, dict) else None
                if not (
                    isinstance(correction, dict)
                    and isinstance(correction.get("correctedName"), str)
                    and correction["correctedName"].strip()
                    and isinstance(correction.get("sourceQuote"), str)
                    and correction["sourceQuote"].strip()
                    and isinstance(correction_citation, dict)
                    and correction_citation.get("corpusPath")
                    and type(correction_citation.get("line")) is int
                ):
                    raise ValueError("catalog nameCorrection requires correctedName, sourceQuote, and sourceCitation")
            issue = unit.get("sourceNameIssue")
            if unit["sourceNameStatus"] == "SOURCE_PLACEHOLDER":
                if not (
                    isinstance(issue, dict)
                    and issue.get("resolutionStatus") == "UNRESOLVED_SOURCE_PLACEHOLDER"
                    and isinstance(issue.get("witnessText"), str)
                    and issue["witnessText"].strip()
                ):
                    raise ValueError("catalog SOURCE_PLACEHOLDER requires an unresolved sourceNameIssue")
            elif issue is not None:
                raise ValueError("catalog SOURCE_LITERAL cannot carry sourceNameIssue")
            administrative_unit_ids.append(
                f"hhs:{volume}:{canonical_group}:{unit['ordinal']:03d}"
            )
    if len(set(group_identities)) != len(group_identities):
        raise ValueError("duplicate catalog group identity")
    if len(set(administrative_unit_ids)) != len(administrative_unit_ids):
        raise ValueError("catalog administrativeUnitId contract contains duplicates")


def active_records(records: list[dict], source_year: int) -> list[dict]:
    return [
        record
        for record in records
        if int(record["BEG_YR"]) <= source_year <= int(record["END_YR"])
    ]


def physical_place_id(record: dict) -> str:
    return f'chgis:v6:cnty:{record["SYS_ID"]}'


def validate_active_records(records: list[dict], source_year: int) -> None:
    active = active_records(records, source_year)
    record_indexes = [record["recordIndex"] for record in active]
    if len(set(record_indexes)) != len(record_indexes):
        raise ValueError("duplicate active CHGIS recordIndex")
    physical_place_ids = [physical_place_id(record) for record in active]
    if len(set(physical_place_ids)) != len(physical_place_ids):
        raise ValueError("duplicate active CHGIS physicalPlaceId")


def candidate_payload(record: dict, matched_fields: list[str], matched_names: list[str]) -> dict:
    return {
        "physicalPlaceId": physical_place_id(record),
        "chgisSysId": str(record["SYS_ID"]),
        "recordIndex": int(record["recordIndex"]),
        "nameCh": record["NAME_CH"],
        "nameFt": record["NAME_FT"],
        "matchedFields": sorted(matched_fields),
        "matchedNames": sorted(matched_names),
        "coordinate": [round(float(record["X_COOR"]), 6), round(float(record["Y_COOR"]), 6)],
        "begYr": int(record["BEG_YR"]),
        "endYr": int(record["END_YR"]),
        "presentLocation": record["PRES_LOC"],
    }


def build_name_index(records: list[dict], source_year: int) -> dict[str, list[tuple[dict, str]]]:
    index = defaultdict(list)
    for record in active_records(records, source_year):
        for field_name in ("NAME_CH", "NAME_FT"):
            normalized = normalize_name(str(record[field_name]))
            if normalized:
                index[normalized].append((record, field_name))
    return index


def match_names(unit: dict) -> list[str]:
    if unit.get("sourceNameStatus") == "SOURCE_PLACEHOLDER":
        return []
    names = [unicodedata.normalize("NFC", str(unit["sourceName"]).strip())]
    correction = unit.get("nameCorrection")
    if correction:
        names.append(unicodedata.normalize("NFC", str(correction["correctedName"]).strip()))
    return list(dict.fromkeys(name for name in names if name))


def candidates_for_unit(unit: dict, name_index: dict[str, list[tuple[dict, str]]]) -> list[dict]:
    by_record = {}
    for match_name in match_names(unit):
        for record, field_name in name_index.get(match_name, []):
            key = physical_place_id(record)
            state = by_record.setdefault(
                key,
                {"record": record, "matchedFields": set(), "matchedNames": set()},
            )
            state["matchedFields"].add(field_name)
            state["matchedNames"].add(match_name)
    candidates = [
        candidate_payload(
            state["record"],
            list(state["matchedFields"]),
            list(state["matchedNames"]),
        )
        for state in by_record.values()
    ]
    return sorted(candidates, key=lambda row: (row["chgisSysId"], row["recordIndex"]))


def administrative_unit_id(unit: dict) -> str:
    return f'hhs:{unit["sourceVolume"]}:{unit["canonicalGroup"]}:{unit["ordinal"]:03d}'


def unit_payload(unit: dict, candidates: list[dict], candidate_users: dict[str, list[str]]) -> dict:
    identity = {
        "sourceVolume": int(unit["sourceVolume"]),
        "canonicalGroup": unit["canonicalGroup"],
        "ordinal": int(unit["ordinal"]),
    }
    row = {
        "administrativeUnitId": administrative_unit_id(unit),
        "identity": identity,
        "sourceName": unit["sourceName"],
        "sourceNameStatus": unit["sourceNameStatus"],
        "unitType": unit["unitType"],
        "sourceCitation": unit["sourceCitation"],
        "matchNames": match_names(unit),
        "candidateCount": len(candidates),
    }
    if "nameCorrection" in unit:
        row["nameCorrection"] = unit["nameCorrection"]
    if "sourceNameIssue" in unit:
        row["sourceNameIssue"] = unit["sourceNameIssue"]

    annotated_candidates = []
    for candidate in candidates:
        competing_ids = [
            unit_id
            for unit_id in candidate_users[candidate["physicalPlaceId"]]
            if unit_id != row["administrativeUnitId"]
        ]
        annotated = dict(candidate)
        if competing_ids:
            annotated["competingAdministrativeUnitIds"] = competing_ids
        annotated_candidates.append(annotated)

    if unit.get("sourceNameStatus") == "SOURCE_PLACEHOLDER":
        row["joinStatus"] = "SOURCE_PLACEHOLDER"
    elif len(annotated_candidates) == 1 and not annotated_candidates[0].get("competingAdministrativeUnitIds"):
        row["joinStatus"] = "RESOLVED_POINT"
        row["selectedCandidate"] = annotated_candidates[0]
    elif annotated_candidates:
        row["joinStatus"] = "AMBIGUOUS_POINT"
        row["candidates"] = annotated_candidates
    else:
        row["joinStatus"] = "NO_COORDINATE_CANDIDATE"
    return row


def build_overlay(catalog: dict, records: list[dict], source_year: int = SOURCE_YEAR) -> dict:
    if source_year != SOURCE_YEAR:
        raise ValueError("source year must be exactly 220")
    validate_catalog(catalog)
    validate_active_records(records, source_year)
    name_index = build_name_index(records, source_year)
    matched_units = []
    candidate_users = defaultdict(list)
    for group in catalog["groups"]:
        for unit in group["units"]:
            candidates = candidates_for_unit(unit, name_index)
            matched_units.append((unit, candidates))
            for candidate in candidates:
                candidate_users[candidate["physicalPlaceId"]].append(administrative_unit_id(unit))
    rows = [unit_payload(unit, candidates, candidate_users) for unit, candidates in matched_units]
    status_counts = Counter(row["joinStatus"] for row in rows)
    unsafe_selections = sum(
        row["joinStatus"] == "AMBIGUOUS_POINT" and "selectedCandidate" in row
        for row in rows
    )
    shared_candidate_conflicts = sum(len(users) > 1 for users in candidate_users.values())
    return {
        "schemaVersion": 1,
        "catalogId": catalog["catalogId"],
        "sourceYear": source_year,
        "matchingPolicy": {
            "unitNames": ["sourceName", "independently cited nameCorrection.correctedName"],
            "chgisFields": ["NAME_CH", "NAME_FT"],
            "temporalPredicate": "BEG_YR <= sourceYear <= END_YR",
            "namePredicate": "NFC exact match after one administrative suffix is removed",
            "ambiguityPolicy": (
                "a unit has multiple candidate records or one physical place is used by multiple "
                "administrative units; no coordinate is selected"
            ),
            "placeholderPolicy": "SOURCE_PLACEHOLDER is never auto-joined",
        },
        "summary": {
            "administrativeUnitCount": len(rows),
            "joinStatusCounts": {status: status_counts[status] for status in JOIN_STATUSES},
            "silentNearestNeighborSelections": unsafe_selections,
            "sharedCandidateConflictCount": shared_candidate_conflicts,
        },
        "administrativeUnits": rows,
    }


def build_document(catalog_path: Path, dbf_path: Path) -> dict:
    catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    if catalog.get("expectedGroupCount") != 105 or catalog.get("expectedUnitCount") != 1180:
        raise ValueError("W0 catalog must declare exactly 105 groups and 1,180 units")
    records, field_names = read_dbf(dbf_path, SOURCE_YEAR)
    document = build_overlay(catalog, records, SOURCE_YEAR)
    active_count = len(records)
    document["provenance"] = {
        "catalogPath": source_label(catalog_path),
        "catalogSha256": sha256(catalog_path),
        "chgisDbfPath": source_label(dbf_path),
        "chgisDbfSha256": sha256(dbf_path),
        "chgisDbfFields": field_names,
        "activeRecordCount": active_count,
        "generator": "tools/map/build_administrative_place_overlay.py",
        "redistribution": "generated coordinate overlay remains gitignored under ADR-LITE-039",
    }
    return document


def serialized(document: dict) -> str:
    return json.dumps(document, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--catalog", type=Path, default=CATALOG)
    parser.add_argument("--chgis-dbf", type=Path, default=CHGIS_COUNTY_DBF)
    parser.add_argument("--out", type=Path, default=OUT)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    try:
        out = assert_untracked(args.out)
        dbf_path = assert_untracked(args.chgis_dbf)
        document = build_document(args.catalog.resolve(), dbf_path)
        blob = serialized(document)
        if args.check:
            if not out.exists() or out.read_text(encoding="utf-8") != blob:
                print(f"drift: {out}", file=sys.stderr)
                return 1
            print("administrative place overlay: no drift")
            return 0
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(blob, encoding="utf-8")
        counts = document["summary"]["joinStatusCounts"]
        print(
            f"{out}: units={document['summary']['administrativeUnitCount']} "
            + " ".join(f"{status}={counts[status]}" for status in JOIN_STATUSES)
        )
        return 0
    except (OSError, UnicodeError, ValueError, KeyError, TypeError, json.JSONDecodeError) as error:
        print(f"administrative place overlay failed: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
