#!/usr/bin/env python3
"""Validate the three S6-8a starting templates against the S6-9a source ledger."""

from __future__ import annotations

import json
from pathlib import Path

from tools.content.validate_unit_identity_sources import validate_identities


ROOT = Path(__file__).resolve().parents[2]
TEMPLATES = ROOT / "data/curated/han/identity-core-templates.json"
SOURCES = ROOT / "data/curated/han/identity-presets.json"
FIRST_IDS = {"identity.confucian", "identity.taiping", "identity.bandit"}
STAGES = {"MOVEMENT", "CONFEDERATION", "TERRITORIAL_REGIME", "BUREAUCRATIC_STATE", "DYNASTIC_CLAIM"}
LIST_FIELDS = (
    "governanceSeeds", "traditionSeeds", "commandCapabilities",
    "facilityCapabilities", "recruitmentCapabilities", "diplomacyCapabilities",
)
PLACE_KINDS = {"CITY", "ROUTE", "HIDEOUT"}


def validate(templates: dict, sources: dict) -> int:
    validate_identities(sources)
    if templates.get("schemaVersion") != 1 or not isinstance(templates.get("rows"), list):
        raise ValueError("identity core requires schemaVersion 1 and rows")
    source_ids = {row["id"] for row in sources["rows"]}
    rows = templates["rows"]
    ids = [row.get("id") for row in rows]
    if len(rows) != len(FIRST_IDS) or set(ids) != FIRST_IDS or len(set(ids)) != len(ids):
        raise ValueError("identity core requires the three first preset IDs exactly once")
    for row in rows:
        row_id = row["id"]
        if row_id not in source_ids:
            raise ValueError(f"{row_id} is absent from the evidence ledger")
        if row.get("initialStage") not in STAGES:
            raise ValueError(f"{row_id} has an invalid initialStage")
        for field in LIST_FIELDS:
            values = row.get(field)
            if not isinstance(values, list) or not values or any(not isinstance(value, str) or not value.strip() for value in values) or len(values) != len(set(values)):
                raise ValueError(f"{row_id}.{field} needs unique nonempty identifiers")
        network_seeds = row.get("startingNetworks")
        if not isinstance(network_seeds, list) or not network_seeds:
            raise ValueError(f"{row_id}.startingNetworks needs a nonempty list")
        network_keys = []
        for seed in network_seeds:
            if not isinstance(seed, dict) or set(seed) != {"kind", "placeKind"} or not isinstance(seed["kind"], str) or not seed["kind"].strip() or not isinstance(seed["placeKind"], str) or seed["placeKind"] not in PLACE_KINDS:
                raise ValueError(f"{row_id}.startingNetworks has an invalid place binding")
            network_keys.append((seed["kind"], seed["placeKind"]))
        if len(network_keys) != len(set(network_keys)):
            raise ValueError(f"{row_id}.startingNetworks has a duplicate place binding")
        decision = row.get("designDecision")
        if not isinstance(decision, dict) or decision.get("status") != "CONFIRMED" or decision.get("decidedBy") != "구현 에이전트":
            raise ValueError(f"{row_id}.designDecision requires CONFIRMED agent decision")
        if not decision.get("decidedAt") or not decision.get("basis"):
            raise ValueError(f"{row_id}.designDecision needs date and basis")
    return len(rows)


if __name__ == "__main__":
    count = validate(json.loads(TEMPLATES.read_text(encoding="utf-8")), json.loads(SOURCES.read_text(encoding="utf-8")))
    print(f"validated {count} first identity core templates")
