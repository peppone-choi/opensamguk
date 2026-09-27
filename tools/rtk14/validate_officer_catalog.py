"""Validate the checked-in, reference-only RTK14 officer name catalog."""

import json
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CATALOG = ROOT / "data/reference/rtk14/officer-catalog-v1.json"
EXPECTED_COUNTS = {"IDEOLOGY": 6, "TRAIT": 158, "POLICY": 33, "TACTIC": 135, "FORMATION": 15}
CREATION_IDS = {
    "IDEOLOGY": {"WANGDO", "PAEDO", "ADO", "HALGEO", "MYEONGRI", "YEGYO"},
    "TRAIT": {"DISCIPLINE", "WATER_COMBAT", "RENOWN", "DEBATER", "STRATEGIST", "SINGLE_RIDER"},
}
ALLOWED_FIELDS = {
    "id", "kind", "edition", "sourceNameKo", "sourceNameJa", "displayNameKo",
    "sourceRefs", "verification", "hwihaState", "selectableForCreation", "category", "note",
}


def validate() -> None:
    catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
    assert catalog["schemaVersion"] == 1
    assert catalog["status"] == "REFERENCE_ONLY"
    source_refs = catalog["sourceRefs"]
    assert all(key and value.startswith("https://") for key, value in source_refs.items())
    rows = catalog["entries"]
    assert Counter(row["kind"] for row in rows) == EXPECTED_COUNTS
    assert len({row["id"] for row in rows}) == len(rows)
    assert len({(row["kind"], row["sourceNameJa"]) for row in rows}) == len(rows)
    for row in rows:
        assert set(row) <= ALLOWED_FIELDS, row["id"]
        assert row["edition"] in {"BASE", "PK"}, row["id"]
        assert all(isinstance(row[key], str) and row[key].strip() for key in ("id", "kind", "sourceNameKo", "sourceNameJa", "displayNameKo", "verification", "hwihaState")), row["id"]
        assert len(set(row["sourceRefs"])) == len(row["sourceRefs"]) >= 2, row["id"]
        assert all(ref in source_refs for ref in row["sourceRefs"]), row["id"]
        assert row["verification"] in {"OFFICIAL", "TWO_SOURCE_NAME", "TWO_SOURCE_LOCALIZED", "OFFICIAL_AND_TWO_SOURCE_NAME", "OFFICIAL_AND_TWO_SOURCE_LOCALIZED"}, row["id"]
        assert row["hwihaState"] == ("CREATION_DISPLAY_ONLY" if row["selectableForCreation"] else "REFERENCE_ONLY"), row["id"]
    for kind, expected in CREATION_IDS.items():
        actual = {row["id"] for row in rows if row["kind"] == kind and row["selectableForCreation"]}
        assert actual == expected, (kind, actual)
    assert all(not row["selectableForCreation"] for row in rows if row["kind"] in {"POLICY", "TACTIC", "FORMATION"})


if __name__ == "__main__":
    validate()
    print(f"officer catalog valid: {sum(EXPECTED_COUNTS.values())} reference names")
