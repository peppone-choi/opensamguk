from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from tools.map.han_province_model import (
    load_administrative_history,
    load_strategic_sites,
    resolve_administrative_hierarchy,
    resolve_relations,
    validate_history,
)


def evidence(claim: str = "site-attested") -> dict[str, str]:
    return {
        "book": "三國志",
        "volume": "卷35",
        "section": "蜀書五 諸葛亮傳",
        "quote": "十二年春，亮悉大眾由斜谷出，以流馬運，據武功五丈原，與司馬宣王對於渭南。",
        "grade": "STANDARD_HISTORY",
        "claim": claim,
        "locationConfidence": "MEDIUM",
    }


def unit(
    stable_id: str,
    kind: str,
    name: str,
    **extra: object,
) -> dict[str, object]:
    return {"id": stable_id, "kind": kind, "name": name, "evidence": [evidence()], **extra}


def relation(
    child_id: str,
    parent_id: str,
    effective_from: int = 184,
    effective_to: int | None = None,
) -> dict[str, object]:
    return {
        "childId": child_id,
        "parentId": parent_id,
        "effectiveFrom": effective_from,
        "effectiveTo": effective_to,
    }


def history_document(
    *,
    units: list[dict[str, object]] | None = None,
    relations: list[dict[str, object]] | None = None,
    seats: list[dict[str, object]] | None = None,
    roles: list[dict[str, object]] | None = None,
) -> dict[str, object]:
    return {
        "schemaVersion": 1,
        "supportedYears": [184, 220],
        "units": units
        if units is not None
        else [
            unit("county:a", "COUNTY", "甲縣", historicalSubtype="縣"),
            unit("commandery:a", "COMMANDERY", "甲郡"),
        ],
        "relations": relations
        if relations is not None
        else [relation("county:a", "commandery:a")],
        "seats": seats
        if seats is not None
        else [
            {
                "unitId": "county:a",
                "settlementId": "settlement:a",
                "effectiveFrom": 184,
                "effectiveTo": None,
            }
        ],
        "roles": roles if roles is not None else [],
    }


class HanProvinceModelTest(unittest.TestCase):
    def write_json(self, payload: object) -> Path:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = Path(directory.name) / "manifest.json"
        path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
        return path

    def test_effective_relations_switch_at_exclusive_end_year(self) -> None:
        """Removing the exclusive-end check would retain county:a in 220."""
        history = load_administrative_history(
            self.write_json(
                history_document(
                    units=[
                        unit("county:a", "COUNTY", "甲縣", historicalSubtype="縣"),
                        unit("county:b", "COUNTY", "乙縣", historicalSubtype="縣"),
                        unit("commandery:a", "COMMANDERY", "甲郡"),
                    ],
                    relations=[
                        relation("province:1", "county:a", 184, 220),
                        relation("province:1", "county:b", 220, None),
                        relation("county:a", "commandery:a"),
                        relation("county:b", "commandery:a"),
                    ],
                    seats=[
                        {
                            "unitId": "county:a",
                            "settlementId": "settlement:a",
                            "effectiveFrom": 184,
                            "effectiveTo": None,
                        },
                        {
                            "unitId": "county:b",
                            "settlementId": "settlement:b",
                            "effectiveFrom": 184,
                            "effectiveTo": None,
                        },
                    ],
                )
            )
        )

        self.assertEqual(resolve_relations(history.relations, 219)["province:1"], "county:a")
        self.assertEqual(resolve_relations(history.relations, 220)["province:1"], "county:b")

    def test_catalog_reference_preserves_source_order_as_stable_administrative_ids(self) -> None:
        """Replacing catalogue order with display-name keys would collapse repeated county names."""
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        catalog_path = root / "catalog.json"
        catalog_path.write_text(
            json.dumps(
                {
                    "catalogId": "fixture-hhs",
                    "groups": [
                        {
                            "sourceVolume": 109,
                            "sourceGroupName": "甲郡",
                            "canonicalGroup": "甲郡",
                            "groupType": "COMMANDERY",
                            "evidence": [evidence("administrative-unit-attested")],
                            "units": [
                                {
                                    "ordinal": 1,
                                    "sourceName": "同名",
                                    "unitType": "COUNTY",
                                    "evidence": [evidence("administrative-unit-attested")],
                                },
                                {
                                    "ordinal": 2,
                                    "sourceName": "同名",
                                    "unitType": "COUNTY",
                                    "evidence": [evidence("administrative-unit-attested")],
                                },
                            ],
                        }
                    ],
                },
                ensure_ascii=False,
            ),
            encoding="utf-8",
        )
        history_path = root / "history.json"
        history_path.write_text(
            json.dumps(
                {
                    "schemaVersion": 1,
                    "supportedYears": [184],
                    "catalogReference": "catalog.json",
                    "units": [],
                    "relations": [],
                    "seats": [],
                    "roles": [],
                },
                ensure_ascii=False,
            ),
            encoding="utf-8",
        )

        history = load_administrative_history(history_path)

        self.assertEqual(
            [unit.id for unit in history.units],
            ["commandery:hhs:109:1", "county:hhs:109:1:1", "county:hhs:109:1:2"],
        )
        self.assertEqual(
            resolve_relations(history.relations, 184)["county:hhs:109:1:2"],
            "commandery:hhs:109:1",
        )

    def test_catalog_reference_rejects_missing_explicit_evidence(self) -> None:
        """Deriving an evidence quote from sourceName would launder a catalog label as a witness."""
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        (root / "catalog.json").write_text(
            json.dumps(
                {
                    "catalogId": "fixture-hhs",
                    "groups": [
                        {
                            "sourceVolume": 109,
                            "sourceGroupName": "甲郡",
                            "canonicalGroup": "甲郡",
                            "groupType": "COMMANDERY",
                            "units": [],
                        }
                    ],
                },
                ensure_ascii=False,
            ),
            encoding="utf-8",
        )
        history_path = root / "history.json"
        history_path.write_text(
            json.dumps(
                {
                    "schemaVersion": 1,
                    "supportedYears": [184],
                    "catalogReference": "catalog.json",
                    "units": [],
                    "relations": [],
                    "seats": [],
                    "roles": [],
                },
                ensure_ascii=False,
            ),
            encoding="utf-8",
        )

        with self.assertRaisesRegex(ValueError, "evidence"):
            load_administrative_history(history_path)

    def test_overlapping_parents_are_rejected(self) -> None:
        """Allowing two active parents would make province ownership ambiguous."""
        history = load_administrative_history(
            self.write_json(
                history_document(
                    units=[
                        unit("county:a", "COUNTY", "甲縣", historicalSubtype="縣"),
                        unit("county:b", "COUNTY", "乙縣", historicalSubtype="縣"),
                        unit("commandery:a", "COMMANDERY", "甲郡"),
                    ],
                    relations=[
                        relation("province:1", "county:a", 184, None),
                        relation("province:1", "county:b", 220, None),
                        relation("county:a", "commandery:a"),
                        relation("county:b", "commandery:a"),
                    ],
                    seats=[
                        {
                            "unitId": "county:a",
                            "settlementId": "settlement:a",
                            "effectiveFrom": 184,
                            "effectiveTo": None,
                        },
                        {
                            "unitId": "county:b",
                            "settlementId": "settlement:b",
                            "effectiveFrom": 184,
                            "effectiveTo": None,
                        },
                    ],
                )
            )
        )

        with self.assertRaisesRegex(ValueError, "overlapping parents"):
            validate_history(history)

    def test_commandery_without_active_child_is_rejected(self) -> None:
        """Dropping child checks would admit a playable commandery with no territory."""
        history = load_administrative_history(
            self.write_json(
                history_document(relations=[])
            )
        )

        with self.assertRaisesRegex(ValueError, "has no active child"):
            resolve_administrative_hierarchy(history, 220)

    def test_active_county_without_parent_is_rejected(self) -> None:
        """Dropping orphan validation would silently remove a county from the snapshot."""
        history = load_administrative_history(
            self.write_json(
                history_document(
                    relations=[],
                    units=[unit("county:a", "COUNTY", "甲縣", historicalSubtype="縣")],
                )
            )
        )

        with self.assertRaisesRegex(ValueError, "has no active parent"):
            resolve_administrative_hierarchy(history, 220)

    def test_multiple_active_county_seats_are_rejected(self) -> None:
        """Accepting both seat records would make the county capital nondeterministic."""
        history = load_administrative_history(
            self.write_json(
                history_document(
                    seats=[
                        {
                            "unitId": "county:a",
                            "settlementId": "settlement:a",
                            "effectiveFrom": 184,
                            "effectiveTo": None,
                        },
                        {
                            "unitId": "county:a",
                            "settlementId": "settlement:b",
                            "effectiveFrom": 184,
                            "effectiveTo": None,
                        },
                    ]
                )
            )
        )

        with self.assertRaisesRegex(ValueError, "multiple active seats"):
            resolve_administrative_hierarchy(history, 220)

    def test_loader_rejects_invalid_unit_and_relation_contracts(self) -> None:
        """Removing enum, uniqueness, range, or reference checks accepts unusable history."""
        invalid_documents = [
            history_document(units=[unit("county:a", "NOT_A_KIND", "甲縣")]),
            history_document(
                units=[
                    unit("county:a", "COUNTY", "甲縣", historicalSubtype="縣"),
                    unit("county:a", "COMMANDERY", "甲郡"),
                ]
            ),
            history_document(relations=[relation("county:a", "commandery:a", 220, 220)]),
            history_document(relations=[relation("county:a", "commandery:missing")]),
            history_document(
                units=[
                    unit("county:a", "COUNTY", "甲縣", historicalSubtype="縣", evidence=[]),
                    unit("commandery:a", "COMMANDERY", "甲郡"),
                ]
            ),
            {**history_document(), "requireCountySeats": "false"},
        ]

        for document in invalid_documents:
            with self.subTest(document=document):
                with self.assertRaises(ValueError):
                    load_administrative_history(self.write_json(document))

    def test_direct_territory_requires_explicit_provisional_evidence_gap(self) -> None:
        """Removing the direct-territory guard permits invented historical counties."""
        history = load_administrative_history(
            self.write_json(
                history_document(
                    units=[
                        unit("direct:a", "DIRECT_TERRITORY", "甲郡 직할령"),
                        unit("commandery:a", "COMMANDERY", "甲郡"),
                    ],
                    relations=[relation("direct:a", "commandery:a")],
                    seats=[
                        {
                            "unitId": "direct:a",
                            "settlementId": "site:a",
                            "effectiveFrom": 184,
                            "effectiveTo": None,
                        }
                    ],
                )
            )
        )

        with self.assertRaisesRegex(ValueError, "DIRECT_TERRITORY"):
            validate_history(history)

    def test_direct_territory_requires_parent_name_and_absence_of_real_child(self) -> None:
        """A misnamed or redundant placeholder would hide a real county under an invented label."""
        wrong_name = load_administrative_history(
            self.write_json(
                history_document(
                    units=[
                        unit(
                            "direct:a",
                            "DIRECT_TERRITORY",
                            "다른 직할령",
                            provenanceStatus="PROVISIONAL",
                            evidenceGap="No sourced county is currently placed.",
                        ),
                        unit("commandery:a", "COMMANDERY", "甲郡"),
                    ],
                    relations=[relation("direct:a", "commandery:a")],
                    seats=[
                        {
                            "unitId": "direct:a",
                            "settlementId": "site:a",
                            "effectiveFrom": 184,
                            "effectiveTo": None,
                        }
                    ],
                )
            )
        )
        redundant_placeholder = load_administrative_history(
            self.write_json(
                history_document(
                    units=[
                        unit(
                            "direct:a",
                            "DIRECT_TERRITORY",
                            "甲郡 직할령",
                            provenanceStatus="PROVISIONAL",
                            evidenceGap="No sourced county is currently placed.",
                        ),
                        unit("county:a", "COUNTY", "甲縣", historicalSubtype="縣"),
                        unit("commandery:a", "COMMANDERY", "甲郡"),
                    ],
                    relations=[
                        relation("direct:a", "commandery:a"),
                        relation("county:a", "commandery:a"),
                    ],
                    seats=[
                        {
                            "unitId": "direct:a",
                            "settlementId": "site:a",
                            "effectiveFrom": 184,
                            "effectiveTo": None,
                        },
                        {
                            "unitId": "county:a",
                            "settlementId": "settlement:a",
                            "effectiveFrom": 184,
                            "effectiveTo": None,
                        },
                    ],
                )
            )
        )

        with self.assertRaisesRegex(ValueError, "name"):
            validate_history(wrong_name)
        with self.assertRaisesRegex(ValueError, "real child"):
            resolve_administrative_hierarchy(redundant_placeholder, 220)

    def test_strategic_evidence_requires_verbatim_quote_grade_and_known_site_kind(self) -> None:
        """Removing evidence validation would turn unsourced labels into historical sites."""
        invalid_sites = [
            {
                "schemaVersion": 1,
                "sites": [
                    {"id": "site:a", "name": "甲山", "kind": "MOUNTAIN_BATTLEFIELD", "evidence": [{"book": "三國志"}]}
                ],
            },
            {
                "schemaVersion": 1,
                "sites": [
                    {"id": "site:a", "name": "甲山", "kind": "UNKNOWN_KIND", "evidence": [evidence()]}
                ],
            },
            {
                "schemaVersion": 1,
                "sites": [
                    {
                        "id": "site:a",
                        "name": "甲山",
                        "kind": "MOUNTAIN_BATTLEFIELD",
                        "evidence": [{**evidence(), "locationConfidence": "CERTAIN"}],
                    }
                ],
            },
        ]

        for document in invalid_sites:
            with self.subTest(document=document):
                with self.assertRaises(ValueError):
                    load_strategic_sites(self.write_json(document))


if __name__ == "__main__":
    unittest.main()
