#!/usr/bin/env python3
"""Audit source identity, or emit provisional county-note comparisons.

--notes --corpus-dir /path/to/corpus --out PATH preserves every county source
line and compares its full body with committed CText quotations. This mode does
not fetch/reverify CText HTML, resolve variants, or grant the S1 release gate.
The default mode remains the original hash-pinned, two-source catalog audit.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
import re
import unicodedata
from pathlib import Path

import junguozhi_contract as contract
from junguozhi_contract import (
    EXPECTED_GROUP_COUNT,
    EXPECTED_UNIT_COUNT,
    CatalogContractError,
    build_catalog,
    render_catalog,
)

ROOT = Path(__file__).resolve().parents[2]
CORPUS = ROOT / "data" / "corpus"
CATALOG = ROOT / "data/curated/han/administrative-units.json"
CHAR_TABLE = ROOT / "data/curated/han/han-name-simplification-v1.json"


def align_note_bodies(rows: list[dict], witness: str, table: dict[str, str]) -> tuple[list[dict], list[dict]]:
    """Find review candidates, never infer county boundaries from name substrings."""
    def fold(value: str) -> str:
        return "".join(table.get(c, c) for c in value
                       if not c.isspace() and not unicodedata.category(c).startswith("P"))

    text = fold(witness)
    result = []
    anchors = []
    for source in rows:
        row = dict(source, review="UNREVIEWED", candidates=[])
        body = source.get("body")
        if source.get("sourceNameStatus") == "SOURCE_PLACEHOLDER":
            status = "SOURCE_PLACEHOLDER"
        elif body is None:
            status = "UNSUPPORTED_MARKUP"
        elif fold(body) == fold(source["sourceName"]):
            status = "NAME_ONLY_REQUIRES_BOUNDARY_REVIEW"
        elif not fold(body).startswith(fold(source["sourceName"])):
            status = "SOURCE_NAME_PREFIX_DIFFERS"
        else:
            needle = fold(body)
            positions = [m.start() for m in re.finditer(f"(?={re.escape(needle)})", text)]
            row["candidates"] = [{"start": p, "end": p + len(needle)} for p in positions]
            status = ("NO_EXACT_CLAUSE" if not positions else
                      "UNIQUE_CLAUSE_CANDIDATE" if len(positions) == 1 else
                      "MULTIPLE_CLAUSE_OCCURRENCES")
            if len(positions) == 1:
                anchors.append((len(result), positions[0], positions[0] + len(needle)))
        row["comparison"] = status
        result.append(row)
    rejected = set()
    for i, (row_index, start, end) in enumerate(anchors):
        for next_index, next_start, _ in anchors[i + 1:]:
            if end > next_start:
                rejected.update((row_index, next_index))
    for index in rejected:
        result[index]["comparison"] = "NON_MONOTONIC_OR_OVERLAPPING"
    cursor = 0
    unmatched = []
    for index, start, end in anchors:
        if index in rejected:
            continue
        if start > cursor:
            unmatched.append({"start": cursor, "end": start, "text": text[cursor:start]})
        cursor = end
    if cursor < len(text):
        unmatched.append({"start": cursor, "end": len(text), "text": text[cursor:]})
    return result, unmatched


def collate_notes(corpus: Path, catalog_path: Path = CATALOG) -> dict:
    """Compare pinned source lines with the committed CText witness, not live HTML."""
    catalog_bytes = catalog_path.read_bytes()
    catalog = json.loads(catalog_bytes)
    parsed = contract.parse_groups(corpus)
    if len(parsed) != len(catalog["groups"]) or len(parsed) != EXPECTED_GROUP_COUNT:
        raise CatalogContractError("note collation group count differs from source")
    table_bytes = CHAR_TABLE.read_bytes()
    table = json.loads(table_bytes)["table"]
    source_bytes = {v: (corpus / f"hhs-{v:03d}.txt").read_bytes() for v in contract.VOLUMES}
    if any(hashlib.sha256(data).hexdigest() != contract.CORPUS_SHA256[v]
           for v, data in source_bytes.items()):
        raise CatalogContractError("note collation corpus hash changed after source parsing")
    lines = {v: data.decode("utf-8").splitlines() for v, data in source_bytes.items()}
    groups = []
    counts = Counter()
    for group_index, (original, group) in enumerate(zip(parsed, catalog["groups"])):
        volume = group["sourceVolume"]
        if (original["sourceVolume"] != volume or original["sourceGroupName"] != group["sourceGroupName"]
                or group["canonicalGroup"] != contract.CANONICAL_GROUPS[group_index]
                or len(original["units"]) != len(group["units"])):
            raise CatalogContractError("note collation group identity differs from source")
        rows = []
        for ordinal, (source, unit) in enumerate(zip(original["units"], group["units"]), 1):
            citation = unit["sourceCitation"]
            expected_status = ("SOURCE_PLACEHOLDER" if contract._source_name_issue(
                source["sourceName"], group["canonicalGroup"], ordinal) else "SOURCE_LITERAL")
            if (source["sourceName"] != unit["sourceName"] or ordinal != unit["ordinal"]
                    or unit["sourceNameStatus"] != expected_status
                    or source["sourceLine"] != citation["line"]
                    or citation["corpusPath"] != f"data/corpus/hhs-{volume:03d}.txt"
                    or citation["sourceUrl"] != f"https://zh.wikisource.org/wiki/後漢書/卷{volume}"
                    or citation["snapshotSha256"] != contract.CORPUS_SHA256[volume]
                    or source["unitType"] != unit["unitType"]
                    or unit["canonicalGroup"] != group["canonicalGroup"]
                    or unit["sourceVolume"] != volume):
                raise CatalogContractError("note collation county identity differs from source")
            raw = lines[volume][citation["line"] - 1]
            row = {"memberId": contract.stable_member_id(volume, group["canonicalGroup"], ordinal),
                   "ordinal": ordinal, "sourceName": unit["sourceName"],
                   "sourceNameStatus": unit["sourceNameStatus"], "sourceCitation": citation,
                   "raw": raw, "body": None}
            try:
                row["body"] = contract.county_note_body(raw)
            except CatalogContractError as error:
                row["markupIssue"] = str(error)
            rows.append(row)
        witness = "\n".join(item["quote"] for item in group["evidence"])
        compared, unmatched = align_note_bodies(rows, witness, table)
        counts.update(row["comparison"] for row in compared)
        groups.append({"sourceVolume": volume, "canonicalGroup": group["canonicalGroup"],
                       "traditionalTextCitation": group["traditionalTextCitation"],
                       "committedWitnessQuote": witness, "rows": compared,
                       "unassignedWitnessSpans": unmatched})
    if sum(counts.values()) != EXPECTED_UNIT_COUNT:
        raise CatalogContractError("note collation county count differs from 1180")
    if catalog_path.read_bytes() != catalog_bytes or CHAR_TABLE.read_bytes() != table_bytes:
        raise CatalogContractError("note collation inputs changed during calculation")
    for volume, data in source_bytes.items():
        if (corpus / f"hhs-{volume:03d}.txt").read_bytes() != data:
            raise CatalogContractError("note collation corpus changed during calculation")
    return {"schemaVersion": 1, "catalogId": "junguozhi-county-note-collation-v1",
            "status": "MACHINE_COLLATION_REQUIRES_REVIEW", "s1GatePassed": False,
            "scope": "All 1180 pinned Wikisource county rows versus committed CText group quotations; notes excluded",
            "limitations": ["CText source HTML has not been reverified by this mode",
                            "unique clause candidates do not prove county boundaries or historical identity",
                            "name-only rows, variants, placeholders and ambiguous boundaries require review",
                            "unassigned spans also contain group headings, population and province-level text"],
            "normalization": "Committed character table, whitespace and punctuation only; originals retained. Offsets refer to normalized witness text.",
            "catalogSha256": hashlib.sha256(catalog_bytes).hexdigest(),
            "characterTableSha256": hashlib.sha256(table_bytes).hexdigest(),
            "sourceSha256": {str(v): hashlib.sha256(data).hexdigest() for v, data in source_bytes.items()},
            "counts": dict(sorted(counts.items())), "groups": groups}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus-dir", type=Path, default=CORPUS)
    parser.add_argument("--notes", action="store_true", help="emit provisional county-note collation; never grants S1 approval")
    parser.add_argument("--catalog", type=Path, default=CATALOG, help="committed catalog used by --notes")
    output = parser.add_mutually_exclusive_group()
    output.add_argument("--out", type=Path, help="write canonical JSON to this path")
    output.add_argument("--check", type=Path, help="fail if this canonical JSON has drifted")
    args = parser.parse_args()

    try:
        catalog = collate_notes(args.corpus_dir, args.catalog) if args.notes else build_catalog(args.corpus_dir)
    except CatalogContractError as error:
        raise SystemExit(f"catalog audit failed: {error}") from error

    rendered = render_catalog(catalog)
    if args.out is not None:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(rendered, encoding="utf-8")
    if args.check is not None:
        if not args.check.exists():
            raise SystemExit(f"catalog drift: missing {args.check}")
        if args.check.read_text(encoding="utf-8") != rendered:
            raise SystemExit(f"catalog drift: regenerate {args.check}")

    if args.notes:
        print(f"REVIEW_REQUIRED units={sum(catalog['counts'].values())} counts={catalog['counts']}")
        return

    print(
        f"PASS groups={catalog['detectedGroupCount']}/{EXPECTED_GROUP_COUNT} "
        f"units={catalog['detectedUnitCount']}/{EXPECTED_UNIT_COUNT} "
        f"types={catalog['unitTypeCounts']}"
    )
    for mismatch in catalog["declaredVsEnumeratedMismatches"]:
        print(
            "SOURCE_MISMATCH "
            f"volume={mismatch['sourceVolume']} group={mismatch['canonicalGroup']} "
            f"declared={mismatch['declaredCities']} "
            f"enumerated={mismatch['enumeratedUnits']}"
        )


if __name__ == "__main__":
    main()
