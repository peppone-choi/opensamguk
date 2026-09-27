#!/usr/bin/env python3
"""Validate the S6 unit and identity provenance ledgers before runtime wiring."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
UNIT_LEDGER = ROOT / "data/curated/han/named-unit-traditions.json"
IDENTITY_LEDGER = ROOT / "data/curated/han/identity-presets.json"
GRADES = {"PRIMARY", "ROMANCE", "SCHOLARLY", "GAME_TERM"}
COLORS = {"MONEY", "GRAIN", "IRON", "TIMBER", "HORSES"}
PRESETS = {
    "identity.virtue", "identity.dao", "identity.bandit", "identity.names",
    "identity.mohist", "identity.legalist", "identity.military", "identity.buddhist",
    "identity.wudoumi", "identity.confucian", "identity.yinyang",
    "identity.diplomatist", "identity.taiping", "identity.neutral", "identity.none",
}


def fail(message: str) -> None:
    raise ValueError(message)


def text(value: object, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        fail(f"{label} must be a nonempty string")
    return value


def decided(row: dict, label: str, numeric: bool = False) -> None:
    if not isinstance(row, dict):
        fail(f"{label} must be an object")
    if row.get("status") != "CONFIRMED" or row.get("decidedBy") != "구현 에이전트":
        fail(f"{label} requires CONFIRMED and decidedBy=구현 에이전트")
    text(row.get("decidedAt"), f"{label}.decidedAt")
    text(row.get("basis"), f"{label}.basis")
    if numeric and (type(row.get("value")) is not int or row["value"] <= 0):
        fail(f"{label}.value must be a positive integer")


def common_rows(data: dict, label: str) -> list[dict]:
    if not isinstance(data, dict) or data.get("schemaVersion") != 1:
        fail(f"{label} requires schemaVersion 1")
    rows = data.get("rows")
    if not isinstance(rows, list) or not rows:
        fail(f"{label}.rows must be nonempty")
    ids: set[str] = set()
    for index, row in enumerate(rows):
        if not isinstance(row, dict):
            fail(f"{label}.rows[{index}] must be an object")
        prefix = f"{label}.rows[{index}]"
        row_id = text(row.get("id"), f"{prefix}.id")
        if row_id in ids:
            fail(f"{label} duplicate id {row_id}")
        ids.add(row_id)
        grade = row.get("grade")
        if grade not in GRADES:
            fail(f"{row_id} has unknown grade {grade}")
        sources = row.get("sources")
        if not isinstance(sources, list):
            fail(f"{row_id}.sources must be a list")
        if grade != "GAME_TERM" and not sources:
            fail(f"{row_id} historical grade requires a source")
        if grade == "GAME_TERM":
            text(row.get("gameTermReason"), f"{row_id}.gameTermReason")
            if row.get("displayBadge") != "게임 용어":
                fail(f"{row_id} requires the 게임 용어 badge")
            if sources:
                fail(f"{row_id} GAME_TERM must keep historical context in a separate row")
        for src in sources:
            if not isinstance(src, dict) or src.get("grade") != grade:
                fail(f"{row_id} source grade must equal row grade")
            for field in ("book", "volume", "section", "quote"):
                text(src.get(field), f"{row_id}.sources.{field}")
        decided(row.get("designDecision"), f"{row_id}.designDecision")
    return rows


def validate_units(data: dict) -> int:
    rows = common_rows(data, "named-unit-traditions")
    for row in rows:
        row_id = row["id"]
        if not row_id.startswith("unit.") or row["grade"] == "GAME_TERM":
            fail(f"{row_id} must be a source-backed unit id")
        text(row.get("name"), f"{row_id}.name")
        historical_name = text(row.get("historicalName"), f"{row_id}.historicalName")
        if not any(historical_name in source["quote"] for source in row["sources"]):
            fail(f"{row_id} historical name is not in its quoted passage")
        if row.get("kind") not in {"NAMED", "REGIONAL"}:
            fail(f"{row_id} kind must be NAMED or REGIONAL")
        if row.get("availability") not in {"UNIQUE", "COMMON"}:
            fail(f"{row_id} availability is invalid")
        text(row.get("recruitmentSource"), f"{row_id}.recruitmentSource")
        text(row.get("attestedPeriod"), f"{row_id}.attestedPeriod")
        required = row.get("requires")
        if not isinstance(required, dict) or not {"generalName", "regionName"} <= set(required) or set(required) - {"generalName", "regionName", "mapParentRegionId"}:
            fail(f"{row_id}.requires needs generalName and regionName, with an optional mapParentRegionId")
        for key, value in required.items():
            if value is not None:
                text(value, f"{row_id}.requires.{key}")
        if "mapParentRegionId" in required and required["regionName"] is None:
            fail(f"{row_id}.requires.mapParentRegionId needs regionName")
        if row_id in {"unit.chijia-regional", "unit.liannu-regional"}:
            if required.get("regionName") != "涪陵郡" or required.get("mapParentRegionId") != "PARENT-0150":
                fail(f"{row_id} requires the MAP4 涪陵郡 parent region PARENT-0150")
        bonds = row.get("bondRecruitmentKinds")
        if not isinstance(bonds, list) or len(bonds) != len(set(bonds)) or set(bonds) - {"VOLUNTEER", "CAPTIVE"}:
            fail(f"{row_id} has invalid bond recruitment kinds")
        colors = row.get("costColors")
        if not isinstance(colors, list) or not colors or len(colors) != len(set(colors)) or set(colors) - COLORS:
            fail(f"{row_id} has invalid cost colors")
        decided(row.get("renownCost"), f"{row_id}.renownCost", numeric=True)
    return len(rows)


def validate_identities(data: dict) -> int:
    if data.get("defaultWhenSourcesConflict") != "ROMANCE":
        fail("identity-presets must retain the user-decided ROMANCE default")
    rows = common_rows(data, "identity-presets")
    if {row["id"] for row in rows} != PRESETS:
        fail("identity-presets must contain the 15 specified presets exactly once")
    for row in rows:
        text(row.get("name"), f"{row['id']}.name")
        if row["grade"] != "GAME_TERM":
            text(row.get("historicalClaimScope"), f"{row['id']}.historicalClaimScope")
    return len(rows)


def validate_paths(unit_path: Path = UNIT_LEDGER, identity_path: Path = IDENTITY_LEDGER) -> tuple[int, int]:
    units = json.loads(unit_path.read_text(encoding="utf-8"))
    identities = json.loads(identity_path.read_text(encoding="utf-8"))
    return validate_units(units), validate_identities(identities)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--units", type=Path, default=UNIT_LEDGER)
    parser.add_argument("--identities", type=Path, default=IDENTITY_LEDGER)
    args = parser.parse_args()
    unit_count, identity_count = validate_paths(args.units, args.identities)
    print(f"validated {unit_count} unit traditions and {identity_count} identity presets")


if __name__ == "__main__":
    main()
