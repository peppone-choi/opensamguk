#!/usr/bin/env python3
"""Split the extracted registry into HWIHA treasure, equipment, and excluded ledgers.

Legacy availability 2 is classified as limited, but its card instance count stays
unresolved until #788 receives a product decision. No runtime issuance occurs here.
"""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = Path("data/extracted/item/items.json")
OUTPUT = Path("data/curated/han")
SLOTS = {"horse": "HORSE", "weapon": "WEAPON", "book": "BOOK", "item": "ITEM"}


def legacy_fields(row: dict) -> dict:
    # Keep the entire extracted row, including statNick and optional hook metadata.
    # The normalized card/equipment fields above are the new contract; this is audit data.
    return dict(row)


def split(source: dict) -> tuple[dict, dict, dict]:
    rows = source["items"]
    if len(rows) != source["count"] or len({r["code"] for r in rows}) != len(rows):
        raise ValueError("extracted item count or code identity is inconsistent")
    treasures: list[dict] = []
    equipment: list[dict] = []
    excluded: list[dict] = []
    for index, row in enumerate(rows):
        code = row["code"]
        if not row["inRegistry"]:
            excluded.append({"sourceRowIndex": index, "sourceCode": code,
                             "name": row["name"], "kind": row["kind"],
                             "reason": "OUTSIDE_REGISTRY"})
            continue
        slot = SLOTS[row["registrySlot"]]
        availability = row["availability"]
        if availability == 0:
            equipment.append({"id": f"equipment:{code}", "sourceRowIndex": index,
                              "sourceCode": code, "name": row["name"], "slot": slot,
                              "supply": "UNLIMITED", "legacy": legacy_fields(row)})
        elif availability in (1, 2):
            treasures.append({
                "header": {"id": f"treasure:{code}", "name": row["name"],
                           "kind": "TREASURE", "availability": "UNIQUE",
                           "provenance": [{"kind": "GAME_TERM"}], "renownCost": 0,
                           "costColors": [], "tags": [f"legacy-kind:{row['kind']}", f"slot:{slot.lower()}"]},
                "sourceRowIndex": index, "sourceCode": code, "slot": slot,
                "legacyAvailability": availability,
                "issuedCopies": 1 if availability == 1 else None,
                "legacy": legacy_fields(row),
            })
        else:
            raise ValueError(f"unsupported registered availability: {code}={availability}")
    common = {"schemaVersion": 1, "source": str(SOURCE)}
    return (
        {**common, "catalogId": "hwiha-treasure-cards-v1",
         "availabilityTwoCopyPolicy": "PENDING", "cards": treasures},
        {**common, "catalogId": "hwiha-equipment-v1", "equipment": equipment},
        {**common, "catalogId": "hwiha-items-excluded-v1", "excluded": excluded},
    )


def main() -> None:
    source = json.loads((ROOT / SOURCE).read_text(encoding="utf-8"))
    for name, data in zip(("hwiha-treasure-cards-v1.json", "hwiha-equipment-v1.json",
                           "hwiha-items-excluded-v1.json"), split(source)):
        (ROOT / OUTPUT / name).write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
