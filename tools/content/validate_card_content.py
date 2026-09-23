#!/usr/bin/env python3
"""Validate current content inputs for the §6/§8/§9 card and county contracts.

This is read-only. It checks references and provenance, never a frozen row count.
"""
from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path

from build_hwiha_item_ledgers import split as split_items

ROOT = Path(__file__).resolve().parents[2]
RESOURCE_COLORS = {"money", "grain", "iron", "timber", "horses"}
COUNTY_INDICATORS = {
    "population": "households", "agriculture": "fields", "commerce": "market",
    "security": "publicSentiment", "trust": "publicSentiment",
    "defence": "defence", "wall": "defence",
}


def load(relative: str) -> dict:
    with (ROOT / relative).open(encoding="utf-8") as source:
        return json.load(source)


def duplicate_ids(rows: list[dict], key: str, label: str) -> list[str]:
    return [f"duplicate {label}: {item}" for item, count in
            sorted(Counter(row[key] for row in rows).items()) if count > 1]


def validate_domestic(ledger: dict) -> list[str]:
    errors: list[str] = []
    works = ledger["works"]["kinds"]
    errors += duplicate_ids(works, "code", "construction")
    for work in works:
        label = work["code"]
        if not work.get("name") or work.get("requiredProgress", 0) <= 0:
            errors.append(f"partial construction: {label}")
        costs = work.get("cost", {})
        if set(costs) != RESOURCE_COLORS or any(type(value) is not int or value < 0 for value in costs.values()):
            errors.append(f"invalid construction cost: {label}")
        for effect in work.get("completion", []):
            if effect.get("indicator") not in COUNTY_INDICATORS:
                errors.append(f"unknown county state: {label}/{effect.get('indicator')}")
    for policy in ledger["countyPolicies"]:
        for effect in policy["indicators"]:
            if effect["indicator"] not in COUNTY_INDICATORS:
                errors.append(f"unknown county state: {policy['code']}/{effect['indicator']}")
    for kind in ("ROAD", "POST_STATION"):
        if kind not in {row["code"] for row in works}:
            errors.append(f"missing construction network kind: {kind}")
    return errors


def validate_sites(sites: dict, extracts: dict, production: dict) -> list[str]:
    errors: list[str] = []
    entries = sites["entries"]
    source_rows = extracts["extracts"]
    errors += duplicate_ids(entries, "id", "site")
    errors += duplicate_ids(source_rows, "extractId", "evidence")
    by_extract = {row["extractId"]: row for row in source_rows}
    by_site = {row["id"]: row for row in entries}
    for site in entries:
        site_id = site["id"]
        evidence = site.get("evidence")
        source = by_extract.get(site_id)
        if site["matchStatus"] == "UNKNOWN_NO_SOURCE":
            if evidence is not None or source is not None or site.get("jurisdictionId"):
                errors.append(f"unknown site has invented evidence or county: {site_id}")
            continue
        if source is None or not isinstance(evidence, dict):
            errors.append(f"dangling claim/evidence: {site_id}")
            continue
        if any(not evidence.get(key) for key in ("book", "volume", "quote")):
            errors.append(f"incomplete book/volume citation: {site_id}")
        for target, origin in (("book", "book"), ("volume", "volume"), ("quote", "quote")):
            if evidence.get(target) != source.get(origin):
                errors.append(f"claim/evidence mismatch: {site_id}/{target}")
        if site["resource"] != source["resource"] or site["era"] != source["era"]:
            errors.append(f"claim/evidence kind mismatch: {site_id}")
        if site["level"] == "COUNTY" and site["matchStatus"].startswith("MATCHED") and not site.get("jurisdictionId") and site["matchStatus"] != "MATCHED_COMMANDERY":
            if site["matchStatus"] != "MATCHED_COMMANDERY_SUCCESSION":
                errors.append(f"matched county lacks county binding: {site_id}")
    for extract_id in by_extract.keys() - by_site.keys():
        errors.append(f"dangling evidence: {extract_id}")
    if production["source"]["catalogId"] != sites["catalogId"]:
        errors.append("production references the wrong site catalog")
    used: set[str] = set()
    for county in production["counties"]:
        for ref in county["sites"]:
            site_id = ref["id"]
            site = by_site.get(site_id)
            if site is None:
                errors.append(f"dangling production site: {site_id}")
            elif (site["resource"] != ref["resource"] or
                  (site["level"] == "COUNTY" and site.get("jurisdictionId") != county["jurisdictionId"]) or
                  (site["level"] == "COMMANDERY" and (ref.get("basis") != "COMMANDERY_SEAT" or ref.get("matchStatus") != site["matchStatus"]))):
                errors.append(f"production site/county mismatch: {site_id}")
            if site_id in used:
                errors.append(f"double consumed production site: {site_id}")
            used.add(site_id)
    return errors


def validate_item_ledgers(source: dict, treasures: dict, equipment: dict, excluded: dict) -> list[str]:
    """Every extracted row has one destination; no fixed catalogue row count is assumed."""
    errors: list[str] = []
    ledgers = ((treasures, "cards"), (equipment, "equipment"), (excluded, "excluded"))
    actual_rows = [row for ledger, key in ledgers for row in ledger.get(key, [])]
    source_rows = source.get("items", [])
    source_by_code = {row["code"]: row for row in source_rows}
    consumed = Counter(row.get("sourceCode") for row in actual_rows)
    for code, count in sorted(consumed.items(), key=lambda entry: str(entry[0])):
        if count > 1:
            errors.append(f"double consumed item source: {code}")
        if code not in source_by_code:
            errors.append(f"dangling item source: {code}")
    for code in sorted(source_by_code.keys() - consumed.keys()):
        errors.append(f"partial item split: {code} has no destination")
    try:
        expected = split_items(source)
    except (KeyError, TypeError, ValueError) as exc:
        return errors + [f"invalid extracted item source: {exc}"]
    for actual, intended, key in zip((treasures, equipment, excluded), expected,
                                     ("cards", "equipment", "excluded")):
        if {k: v for k, v in actual.items() if k != key} != {k: v for k, v in intended.items() if k != key}:
            errors.append(f"invalid item ledger header: {key}")
        intended_by_code = {row["sourceCode"]: row for row in intended[key]}
        for row in actual.get(key, []):
            code = row.get("sourceCode")
            if code not in intended_by_code:
                errors.append(f"wrong item destination: {key}/{code}")
            elif row != intended_by_code[code]:
                errors.append(f"item contract/source mismatch: {key}/{code}")
        if len(actual.get(key, [])) != len(intended[key]):
            errors.append(f"partial item ledger: {key}")
    return errors


def validate_current() -> list[str]:
    return validate_domestic(load("data/curated/han/hwiha-domestic-v1.json")) + validate_sites(
        load("data/curated/han/resource-sites-v1.json"),
        load("data/curated/han/resource-site-source-extracts-v1.json"),
        load("data/curated/han/hwiha-resource-production-v1.json"),
    ) + validate_item_ledgers(
        load("data/extracted/item/items.json"),
        load("data/curated/han/hwiha-treasure-cards-v1.json"),
        load("data/curated/han/hwiha-equipment-v1.json"),
        load("data/curated/han/hwiha-items-excluded-v1.json"),
    )


def main() -> int:
    argparse.ArgumentParser(description=__doc__).parse_args()
    errors = validate_current()
    for error in errors:
        print(error)
    if errors:
        return 1
    print("card/construction/site content references and provenance: valid")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
