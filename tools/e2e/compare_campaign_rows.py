#!/usr/bin/env python3
"""Compare normalized campaign rows without mistaking map shape for battle results."""

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET


SHA256 = re.compile(r"[0-9a-f]{64}\Z")
GIT_SHA = re.compile(r"[0-9a-f]{40}\Z")
CITY_FIELDS = (
    "kind", "id", "nationId", "level", "state", "population", "populationMax", "dead",
    "agriculture", "agricultureMax", "commerce", "commerceMax", "security", "securityMax",
    "supplyState", "frontState", "defence", "defenceMax", "wall", "wallMax", "trade",
    "region", "term", "officerSet", "conflict", "meta",
)
SIEGE_FIELDS = (
    "kind", "countyId", "status", "besiegerGeneralId", "besiegerOwnerGeneralId",
    "besiegerNationId", "defenderNationId", "startedYear", "startedMonth", "startedPhase",
    "settledYear", "settledMonth", "settledPhase", "turns", "morale", "garrison",
    "endReason", "timeline",
)
DYNAMIC_KINDS = ("siege", "bugok", "general", "nation", "position", "calendar")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def canonical_fields(line: str) -> list:
    """Read the baseline's length-prefixed canonical values, including nested metadata strings."""
    def parse(at: int):
        require(at < len(line), "truncated canonical row")
        symbol = line[at]
        if symbol == "[":
            values = []
            at += 1
            while True:
                require(at < len(line), "unterminated canonical list")
                if line[at] == "]":
                    return values, at + 1
                value, at = parse(at)
                values.append(value)
                require(at < len(line) and line[at] in ",]", "invalid canonical separator")
                if line[at] == "]":
                    return values, at + 1
                at += 1
        if symbol == "S":
            end = line.find(":", at + 1)
            require(end > at + 1 and line[at + 1:end].isdigit(), "invalid canonical string length")
            length = int(line[at + 1:end])
            start = end + 1
            units = 0
            at = start
            while units < length:
                require(at < len(line), "truncated canonical string")
                units += 2 if ord(line[at]) > 0xFFFF else 1
                at += 1
            require(units == length, "canonical string splits a surrogate pair")
            return line[start:at], at
        if symbol == "D":
            end = at + 1
            while end < len(line) and line[end] not in ",]":
                end += 1
            token = line[at:end]
            require(re.fullmatch(r"D-?\d+(?:\.\d+)?", token) is not None,
                    "invalid canonical number")
            return token, end
        if symbol in "NTF":
            return symbol, at + 1
        raise ValueError("invalid canonical value")

    result, end = parse(0)
    require(isinstance(result, list) and end == len(line) and result
            and isinstance(result[0], str), "invalid canonical row")
    return result


def read_rows(path: Path) -> dict:
    source = path.read_bytes()
    if path.suffix == ".xml":
        root = ET.fromstring(source)
        outputs = [node.text or "" for node in root.findall(".//system-out")]
        rows = [line.split("behavior-row ", 1)[1] for output in outputs
                for line in output.splitlines() if "behavior-row " in line]
        tests = {key: int(root.get(key, "0")) for key in ("tests", "failures", "errors", "skipped")}
        require(tests["tests"] > 0 and tests["errors"] == 0 and tests["skipped"] == 0,
                "JUnit row source was not executed cleanly")
    else:
        require(source.endswith(b"\n"), "row file must end with a newline")
        rows = source.decode("utf-8").splitlines()
        tests = None
    require(bool(rows), "normalized row source is empty")
    parsed = [canonical_fields(row) for row in rows]
    return {
        "rows": rows,
        "parsed": parsed,
        "source_sha256": hashlib.sha256(source).hexdigest(),
        "rows_sha256": hashlib.sha256(("\n".join(rows) + "\n").encode("utf-8")).hexdigest(),
        "tests": tests,
    }


def keyed_rows(parsed: list[list], kind: str, fields: tuple[str, ...]) -> dict[int, list]:
    rows = {}
    for row in parsed:
        if row[0] != kind:
            continue
        require(len(row) == len(fields), f"{kind} normalized projection changed shape")
        token = row[1]
        require(isinstance(token, str) and re.fullmatch(r"D\d+", token) is not None,
                f"{kind} row missing numeric identity")
        identity = int(token[1:])
        require(identity not in rows, f"duplicate {kind} identity")
        rows[identity] = row
    return rows


def keyed_diff(before: dict[int, list], after: dict[int, list], fields: tuple[str, ...]) -> dict:
    common = before.keys() & after.keys()
    changed = sorted(key for key in common if before[key] != after[key])
    field_changes = Counter(field for key in changed for index, field in enumerate(fields)
                            if before[key][index] != after[key][index])
    return {
        "baseline_only_ids": sorted(before.keys() - after.keys()),
        "candidate_only_ids": sorted(after.keys() - before.keys()),
        "common_changed_ids": changed,
        "field_change_counts": dict(sorted(field_changes.items())),
    }


def compare_rows(baseline: Path, candidate: Path) -> dict:
    old, new = read_rows(baseline), read_rows(candidate)
    source_test_failures = sum(source["tests"]["failures"] for source in (old, new)
                               if source["tests"] is not None)
    old_counts = Counter(row[0] for row in old["parsed"])
    new_counts = Counter(row[0] for row in new["parsed"])
    old_multiset, new_multiset = Counter(old["rows"]), Counter(new["rows"])
    city = keyed_diff(keyed_rows(old["parsed"], "city", CITY_FIELDS),
                      keyed_rows(new["parsed"], "city", CITY_FIELDS), CITY_FIELDS)
    siege = keyed_diff(keyed_rows(old["parsed"], "siege", SIEGE_FIELDS),
                       keyed_rows(new["parsed"], "siege", SIEGE_FIELDS), SIEGE_FIELDS)
    type_counts = {}
    for kind in sorted(old_counts.keys() | new_counts.keys()):
        left = Counter(row for row, parsed in zip(old["rows"], old["parsed"]) if parsed[0] == kind)
        right = Counter(row for row, parsed in zip(new["rows"], new["parsed"]) if parsed[0] == kind)
        type_counts[kind] = {
            "baseline": old_counts[kind], "candidate": new_counts[kind],
            "baseline_only_rows": sum((left - right).values()),
            "candidate_only_rows": sum((right - left).values()),
        }
    return {
        "schemaVersion": "campaign-row-diff-v1",
        "status": "NO_ROW_DIFF" if old_multiset == new_multiset and source_test_failures == 0
                  else "REVIEW_REQUIRED",
        "source_test_failures": source_test_failures,
        "baseline": {"source_sha256": old["source_sha256"], "rows_sha256": old["rows_sha256"],
                     "row_count": len(old["rows"]), "junit": old["tests"]},
        "candidate": {"source_sha256": new["source_sha256"], "rows_sha256": new["rows_sha256"],
                      "row_count": len(new["rows"]), "junit": new["tests"]},
        "identical_rows": sum((old_multiset & new_multiset).values()),
        "baseline_only_rows": sum((old_multiset - new_multiset).values()),
        "candidate_only_rows": sum((new_multiset - old_multiset).values()),
        "row_types": type_counts,
        "structural_map": {"city_ids": {key: city[key] for key in
                         ("baseline_only_ids", "candidate_only_ids")}},
        "shared_city_values": {"common_changed_ids": city["common_changed_ids"],
                               "field_change_counts": city["field_change_counts"]},
        "campaign_outcomes": {
            "siege": siege,
            "row_types": {kind: type_counts.get(kind, {"baseline": 0, "candidate": 0,
                "baseline_only_rows": 0, "candidate_only_rows": 0}) for kind in DYNAMIC_KINDS},
        },
        "judgement": "Row differences or failed JUnit sources require cause review; map ID changes alone cannot approve W1 or W4.",
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path)
    parser.add_argument("candidate", type=Path)
    parser.add_argument("--baseline-git", required=True)
    parser.add_argument("--candidate-git", required=True)
    parser.add_argument("--baseline-map-sha", required=True)
    parser.add_argument("--candidate-map-sha", required=True)
    parser.add_argument("--scenario-sha", required=True)
    parser.add_argument("--baseline-artifact-id", type=int)
    parser.add_argument("--candidate-artifact-id", type=int)
    parser.add_argument("--require-same-git", action="store_true",
                        help="reject different engine commits for a controlled map-only comparison")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    for name in ("baseline_git", "candidate_git", "baseline_map_sha", "candidate_map_sha", "scenario_sha"):
        value = getattr(args, name)
        pattern = GIT_SHA if name.endswith("git") else SHA256
        require(pattern.fullmatch(value) is not None, f"invalid {name} pin")
    if args.require_same_git:
        require(args.baseline_git == args.candidate_git,
                "controlled map comparison requires the same engine Git SHA")
    summary = compare_rows(args.baseline, args.candidate)
    summary["pins"] = {name: getattr(args, name) for name in
                       ("baseline_git", "candidate_git", "baseline_map_sha", "candidate_map_sha", "scenario_sha")}
    for name in ("baseline_artifact_id", "candidate_artifact_id"):
        value = getattr(args, name)
        if value is not None:
            require(value > 0, f"invalid {name}")
            summary["pins"][name] = value
    body = (json.dumps(summary, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")
    if args.output.exists():
        require(args.output.read_bytes() == body, "existing row-diff evidence differs")
    else:
        args.output.write_bytes(body)
    print(f"Row diff: {summary['baseline']['row_count']} → {summary['candidate']['row_count']}; "
          f"{summary['status']}")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, ET.ParseError) as exc:
        raise SystemExit(f"campaign row evidence incomplete: {exc}")
