#!/usr/bin/env python3
"""Validate layer 2/3 content ledgers without interpreting historical claims."""

import argparse
import json
import sys
from pathlib import Path

GRADES = {"PRIMARY", "ROMANCE", "SCHOLARLY", "GAME_TERM"}
HISTORICAL = GRADES - {"GAME_TERM"}
SOURCE_FIELDS = ("book", "volume", "section", "quote")
NUMBER_FIELDS = ("status", "decidedBy", "decidedAt", "basis")


def _nonempty(value):
    return isinstance(value, str) and bool(value.strip())


def validate(document):
    """Return stable, human-readable errors for one parsed JSON ledger."""
    errors = []
    if not isinstance(document, dict) or document.get("schemaVersion") != 1:
        return ["schemaVersion: expected 1"]
    rows = document.get("rows")
    if not isinstance(rows, list):
        return ["rows: expected array"]
    ids = set()
    for index, row in enumerate(rows):
        path = f"rows[{index}]"
        if not isinstance(row, dict):
            errors.append(f"{path}: expected object")
            continue
        row_id = row.get("id")
        if not _nonempty(row_id):
            errors.append(f"{path}.id: required")
        elif row_id in ids:
            errors.append(f"{path}.id: duplicate {row_id}")
        else:
            ids.add(row_id)
        grade = row.get("grade")
        if not isinstance(grade, str) or grade not in GRADES:
            errors.append(f"{path}.grade: unknown {grade}")
        sources = row.get("sources")
        if not isinstance(sources, list):
            errors.append(f"{path}.sources: expected array")
            sources = []
        if grade in HISTORICAL and not sources:
            errors.append(f"{path}.sources: historical row needs a citation")
        if grade == "GAME_TERM":
            if not _nonempty(row.get("gameTermReason")):
                errors.append(f"{path}.gameTermReason: required")
            if row.get("displayBadge") != "게임 용어":
                errors.append(f"{path}.displayBadge: expected 게임 용어")
        for source_index, source in enumerate(sources):
            source_path = f"{path}.sources[{source_index}]"
            if not isinstance(source, dict):
                errors.append(f"{source_path}: expected object")
                continue
            source_grade = source.get("grade")
            if not isinstance(source_grade, str) or source_grade not in GRADES:
                errors.append(f"{source_path}.grade: unknown {source_grade}")
            elif grade in HISTORICAL and source_grade != grade:
                errors.append(f"{source_path}.grade: mixed historical grades")
            elif grade == "GAME_TERM" and source_grade != "GAME_TERM":
                errors.append(f"{source_path}.grade: historical claim needs a separate row")
            for field in SOURCE_FIELDS:
                if not _nonempty(source.get(field)):
                    errors.append(f"{source_path}.{field}: required")
        refs = row.get("refs", [])
        if not isinstance(refs, list):
            errors.append(f"{path}.refs: expected array")
        else:
            for ref in refs:
                if not _nonempty(ref):
                    errors.append(f"{path}.refs: expected nonempty id")
        _validate_numbers(row, path, errors)
    for index, row in enumerate(rows):
        if isinstance(row, dict) and isinstance(row.get("refs", []), list):
            for ref in row.get("refs", []):
                if isinstance(ref, str) and ref not in ids:
                    errors.append(f"rows[{index}].refs: dangling {ref}")
    return errors


def _validate_numbers(node, path, errors):
    if isinstance(node, dict):
        if "values" in node:
            values = node["values"]
            if not isinstance(values, dict):
                errors.append(f"{path}.values: expected object")
            else:
                for key, entry in values.items():
                    if not isinstance(entry, dict) or not isinstance(entry.get("value"), (int, float)) or isinstance(entry.get("value"), bool):
                        errors.append(f"{path}.values.{key}: expected numeric decision object")
        if "value" in node and isinstance(node["value"], (int, float)) and not isinstance(node["value"], bool):
            if node.get("status") != "CONFIRMED":
                errors.append(f"{path}.status: expected CONFIRMED")
            for field in NUMBER_FIELDS[1:]:
                if not _nonempty(node.get(field)):
                    errors.append(f"{path}.{field}: required for numeric value")
        for key, value in node.items():
            _validate_numbers(value, f"{path}.{key}", errors)
    elif isinstance(node, list):
        for index, value in enumerate(node):
            _validate_numbers(value, f"{path}[{index}]", errors)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("files", nargs="+", type=Path)
    args = parser.parse_args()
    failed = False
    for path in args.files:
        try:
            errors = validate(json.loads(path.read_text(encoding="utf-8")))
        except (OSError, ValueError) as exc:
            errors = [str(exc)]
        for error in errors:
            print(f"{path}: {error}", file=sys.stderr)
        failed |= bool(errors)
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
