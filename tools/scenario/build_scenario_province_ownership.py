#!/usr/bin/env python3
"""Build the canonical complete scenario-to-province ownership artifact."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys
from typing import Any, Mapping


DEFAULT_ROOT = Path(__file__).resolve().parents[2]
if str(DEFAULT_ROOT) not in sys.path:
    sys.path.insert(0, str(DEFAULT_ROOT))

from tools.scenario.province_ownership_contract import parse_ownership_document
from tools.scenario.province_ownership_materializer import (
    ProvinceAssignment,
    ProvinceCatalogEntry,
    materialize_all,
)


OUTPUT_RELATIVE = Path("data/map/han-scenario-province-ownership-v1.json")
CLAIMS_RELATIVE = Path("data/curated/han/scenario-province-claims-v1.json")
MAP_RELATIVE = Path("data/map/han-tiles.json")
SCENARIO_RELATIVE = Path("infra/src/main/resources/scenario")


def canonical_bytes(document: Mapping[str, Any]) -> bytes:
    return (json.dumps(document, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def _load(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _assignment_row(row: ProvinceAssignment) -> dict[str, Any]:
    return {
        "scenarioCode": row.scenario_code,
        "provinceId": row.province_id,
        "ownerNationId": row.owner_nation_id,
        "ownerNationKey": row.owner_nation_key,
        "controllerCityId": row.controller_city_id,
        "winningClaimId": row.winning_claim_id,
        "claimTrace": list(row.claim_trace),
        "basisType": row.basis_type,
        "evidenceIds": list(row.evidence_ids),
        "confidence": row.confidence,
        "rationale": row.rationale,
    }


def runtime_scenario_catalog(document: Mapping[str, Any]) -> dict[str, Any]:
    """Resolve nation IDs from the scenario files that are actually seeded."""
    return {
        "nations": [
            {"id": index, "name": row[0], "color": row[1]}
            for index, row in enumerate(document["nation"], start=1)
        ]
    }


def generate_document(root: Path = DEFAULT_ROOT) -> dict[str, Any]:
    claims_path = root / CLAIMS_RELATIVE
    map_path = root / MAP_RELATIVE
    raw = _load(claims_path)
    map_doc = _load(map_path)
    scenario_documents = {
        code: _load(root / SCENARIO_RELATIVE / f"scenario_{code}.json")
        for code in raw["activeScenarioCodes"]
    }
    parsed = parse_ownership_document(
        raw,
        {
            "provinceIds": [row["id"] for row in map_doc["provinceRecords"]],
            "parentRegionIds": [row["id"] for row in map_doc["parentRegions"]],
        },
        {code: runtime_scenario_catalog(document) for code, document in scenario_documents.items()},
    )
    catalog = tuple(
        ProvinceCatalogEntry(row["id"], row["parentRegionId"])
        for row in map_doc["provinceRecords"]
    )
    materialized = materialize_all(parsed, catalog)
    scenario_hash_input = canonical_bytes({
        str(code): _sha256(
            (root / SCENARIO_RELATIVE / f"scenario_{code}.json").read_bytes()
        )
        for code in sorted(scenario_documents)
    })
    return {
        "schemaVersion": 1,
        "mapId": parsed.map_id,
        "unitSet": parsed.unit_set,
        "generatorPolicyVersion": 1,
        "sources": {
            "claimsSha256": _sha256(claims_path.read_bytes()),
            "mapSha256": _sha256(map_path.read_bytes()),
            "scenarioCatalogSha256": _sha256(scenario_hash_input),
        },
        "scenarios": [
            {
                "scenarioCode": code,
                "effectiveYear": parsed.scenarios[code].effective_year,
                "placementBasis": parsed.scenarios[code].placement_basis,
                "assignments": [_assignment_row(row) for row in materialized[code]],
            }
            for code in sorted(materialized)
        ],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    output_path = DEFAULT_ROOT / OUTPUT_RELATIVE
    generated = canonical_bytes(generate_document())
    if args.check:
        if not output_path.exists() or output_path.read_bytes() != generated:
            print(f"drift: {OUTPUT_RELATIVE}")
            return 1
        print(f"clean: {OUTPUT_RELATIVE}")
        return 0

    output_path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path = output_path.with_suffix(output_path.suffix + ".tmp")
    temporary_path.write_bytes(generated)
    temporary_path.replace(output_path)
    print(f"wrote {OUTPUT_RELATIVE}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
