"""Strict loader for reviewed non-Han province display systems."""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_PATH = ROOT / "data/curated/han/external-province-seeds-v1.json"
_SEED_KEYS = {
    "id", "canonicalName", "nameCh", "administrativeSystem", "reviewState", "confidence"
}


@dataclass(frozen=True)
class ExternalProvinceDisplay:
    id: str
    canonical_name: str
    name_ch: str
    administrative_system: str
    review_state: str
    confidence: str


def load_external_province_displays(path: Path | str = DEFAULT_PATH) -> dict[str, ExternalProvinceDisplay]:
    document = json.loads(Path(path).read_text(encoding="utf-8"))
    if not isinstance(document, dict) or set(document) != {
        "schemaVersion", "effectiveYear", "sources", "seeds"
    }:
        raise ValueError("external province manifest requires exact root keys")
    if document["schemaVersion"] != 1 or document["effectiveYear"] != 220:
        raise ValueError("external province manifest version/year mismatch")
    if not isinstance(document["sources"], list) or not document["sources"]:
        raise ValueError("external province manifest sources are required")
    result = {}
    for index, row in enumerate(document["seeds"]):
        if not isinstance(row, dict) or set(row) != _SEED_KEYS:
            raise ValueError(f"external province seed {index} requires exact keys")
        if row["id"] in result:
            raise ValueError(f"duplicate external province seed ID: {row['id']}")
        if row["reviewState"] not in {"APPROVED", "PENDING"}:
            raise ValueError("external province reviewState is invalid")
        if row["confidence"] not in {"IDENTIFIED", "DISPUTED"}:
            raise ValueError("external province confidence is invalid")
        result[row["id"]] = ExternalProvinceDisplay(
            id=row["id"], canonical_name=row["canonicalName"], name_ch=row["nameCh"],
            administrative_system=row["administrativeSystem"],
            review_state=row["reviewState"], confidence=row["confidence"],
        )
    return result
