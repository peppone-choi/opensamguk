#!/usr/bin/env python3
"""Check the reviewed 郡國志 × 漢書 地理志 iron comparison against the live site ledger."""

import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SITES = ROOT / "data/curated/han/resource-sites-v1.json"
COMPARISON = ROOT / "data/curated/han/iron-two-book-comparison-v1.json"
KNOWN_STATES = {
    "TEXTUAL_COUNTERPART",
    "FORMER_HAN_ANNOTATION_NOT_COLLECTED",
    "NO_COUNTERPART_IN_COLLECTED_EXTRACTS",
}


def check(sites: dict, comparison: dict, sites_sha256: str) -> dict:
    if comparison["inputs"]["resourceLedger"]["sha256"] != sites_sha256:
        raise ValueError("resource ledger SHA-256 is stale")
    by_id = {entry["id"]: entry for entry in sites["entries"]}
    if len(by_id) != len(sites["entries"]):
        raise ValueError("duplicate resource site ID")
    iron = [entry for entry in sites["entries"] if entry["resource"] == "IRON"]
    later = {entry["id"] for entry in iron if entry["era"] == "LATER_HAN"}
    former = {entry["id"] for entry in iron if entry["era"] == "FORMER_HAN"
              and not entry["matchStatus"].startswith("UNKNOWN")}
    comparison_later = set()
    comparison_former = set()
    statuses = Counter()

    def check_witness(witness: dict, era: str) -> str:
        entry_id = witness["entryId"]
        source = by_id.get(entry_id)
        if source is None or source["resource"] != "IRON" or source["era"] != era:
            raise ValueError(f"iron witness ID is absent from source ledger: {entry_id}")
        if any(witness[key] != source[key] for key in
               ("sourceCommandery", "sourceName", "level", "evidence")):
            raise ValueError(f"iron witness content drifted: {entry_id}")
        return entry_id

    for row in comparison["rows"]:
        later_id = check_witness(row["laterHan"], "LATER_HAN")
        if later_id in comparison_later:
            raise ValueError(f"duplicate later Han iron row: {later_id}")
        comparison_later.add(later_id)
        if row["status"] not in KNOWN_STATES:
            raise ValueError("unknown iron comparison state")
        statuses[row["status"]] += 1
        if row.get("formerHan") is not None:
            former_id = check_witness(row["formerHan"], "FORMER_HAN")
            if former_id in comparison_former:
                raise ValueError(f"duplicate former Han iron witness: {former_id}")
            comparison_former.add(former_id)
            if row["status"] != "TEXTUAL_COUNTERPART":
                raise ValueError("former Han witness requires textual counterpart state")
    for row in comparison["formerHanRowsWithoutLaterHanCounterpart"]:
        former_id = check_witness(row["formerHan"], "FORMER_HAN")
        if former_id in comparison_former:
            raise ValueError(f"duplicate former Han iron witness: {former_id}")
        comparison_former.add(former_id)
    if comparison_later != later or comparison_former != former:
        raise ValueError("iron comparison has missing or extra source IDs")
    expected_counts = {
        "laterHanRows": len(later),
        "formerHanExtractRows": len(former),
        "statuses": dict(sorted(statuses.items())),
        "formerHanRowsWithoutLaterHanCounterpart": len(comparison["formerHanRowsWithoutLaterHanCounterpart"]),
    }
    if comparison["counts"] != expected_counts:
        raise ValueError("iron comparison counts drifted")
    return expected_counts


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", required=True)
    parser.parse_args()
    raw = SITES.read_bytes()
    counts = check(
        json.loads(raw),
        json.loads(COMPARISON.read_bytes()),
        hashlib.sha256(raw).hexdigest(),
    )
    print(f"OK {COMPARISON.relative_to(ROOT)} {counts}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
