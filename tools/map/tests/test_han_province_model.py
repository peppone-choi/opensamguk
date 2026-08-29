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


ROOT = Path(__file__).resolve().parents[3]


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


def catalog_member_id(volume: int, group: str, ordinal: int) -> str:
    return json.dumps([volume, group, ordinal], ensure_ascii=False, separators=(",", ":"))


def catalog_evidence(quote: str = "甲郡\n甲縣\n乙縣") -> dict[str, str]:
    return {
        "book": "後漢書",
        "volume": "郡國一",
        "section": "甲郡 (n1-n3)",
        "quote": quote,
        "grade": "STANDARD_HISTORY",
        "claim": "group-membership-attested",
        "locationConfidence": "UNKNOWN",
    }


def catalog_group(*, child_evidence: bool = False) -> dict[str, object]:
    units: list[dict[str, object]] = [
        {
            "sourceVolume": 109,
            "canonicalGroup": "甲郡",
            "ordinal": 1,
            "sourceName": "雒阳",
            "unitType": "COUNTY",
        },
        {
            "sourceVolume": 109,
            "canonicalGroup": "甲郡",
            "ordinal": 2,
            "sourceName": "同名",
            "unitType": "COUNTY",
        },
    ]
    if child_evidence:
        for child in units:
            child["evidence"] = [evidence("administrative-unit-attested")]
    return {
        "sourceVolume": 109,
        "sourceGroupName": "甲郡",
        "canonicalGroup": "甲郡",
        "groupType": "COMMANDERY",
        "evidence": [catalog_evidence()],
        "memberCoverageIds": [catalog_member_id(109, "甲郡", 1), catalog_member_id(109, "甲郡", 2)],
        "units": units,
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

    def write_catalog_history(self, catalog: dict[str, object]) -> Path:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        (root / "catalog.json").write_text(
            json.dumps(catalog, ensure_ascii=False), encoding="utf-8"
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
        return history_path

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

    def test_weiguo_moves_from_east_commandery_to_wei_commandery_in_212(self) -> None:
        """Keeping the inherited HHS parent forever would leave 衞 in 東郡 at 220."""
        history = load_administrative_history(ROOT / "data/map/han-administrative-history.json")

        self.assertEqual(
            "commandery:hhs:111:24",
            resolve_relations(history.relations, 211)["county:hhs:111:24:14"],
        )
        self.assertEqual(
            "commandery:hhs:110:14",
            resolve_relations(history.relations, 212)["county:hhs:111:24:14"],
        )
        self.assertEqual(
            "commandery:hhs:110:14",
            resolve_relations(history.relations, 220)["county:hhs:111:24:14"],
        )

    def test_catalog_reference_preserves_source_order_as_stable_administrative_ids(self) -> None:
        """Each covered child inherits its group's reviewed evidence without display-name synthesis."""
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        catalog_path = root / "catalog.json"
        catalog_path.write_text(
            json.dumps(
                {
                    "catalogId": "fixture-hhs",
                    "groups": [catalog_group()],
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
        self.assertEqual(history.units[0].evidence, (history.units[1].evidence[0],))
        self.assertIs(history.units[0].evidence, history.units[1].evidence)
        self.assertIs(history.units[0].evidence, history.units[2].evidence)

    def test_catalog_reference_rejects_invalid_group_evidence_contract(self) -> None:
        """A group witness must be one compatible passage, never a label or permissive object."""
        cases = []
        missing = catalog_group(child_evidence=True)
        del missing["evidence"]
        cases.append(("missing", missing, "exactly one"))
        empty = catalog_group(child_evidence=True)
        empty["evidence"] = []
        cases.append(("empty", empty, "exactly one"))
        duplicate = catalog_group(child_evidence=True)
        duplicate["evidence"] = [catalog_evidence(), catalog_evidence()]
        cases.append(("duplicate", duplicate, "exactly one"))
        unsupported = catalog_group(child_evidence=True)
        unsupported["evidence"] = [{**catalog_evidence(), "runtimeId": "county:hhs:109:1:1"}]
        cases.append(("unsupported field", unsupported, "unsupported evidence field"))
        wrong_claim = catalog_group(child_evidence=True)
        wrong_claim["evidence"] = [{**catalog_evidence(), "claim": "site-attested"}]
        cases.append(("wrong claim", wrong_claim, "group-membership-attested"))
        mixed_script_label = catalog_group(child_evidence=True)
        mixed_script_label["evidence"] = [catalog_evidence("雒阳")]
        cases.append(("source-name quote", mixed_script_label, "reviewed group passage"))
        runtime_label = catalog_group(child_evidence=True)
        runtime_label["evidence"] = [catalog_evidence("commandery:hhs:109:1")]
        cases.append(("runtime-id quote", runtime_label, "reviewed group passage"))

        for case_name, group, expected_error in cases:
            with self.subTest(case_name=case_name):
                with self.assertRaisesRegex(ValueError, expected_error):
                    load_administrative_history(
                        self.write_catalog_history({"catalogId": "fixture-hhs", "groups": [group]})
                    )

    def test_catalog_reference_rejects_invalid_member_coverage(self) -> None:
        """Coverage must be complete, unique, canonical, and confined to its source group."""
        cases = []
        missing = catalog_group(child_evidence=True)
        del missing["memberCoverageIds"]
        cases.append(("missing", missing, "memberCoverageIds"))
        empty = catalog_group(child_evidence=True)
        empty["memberCoverageIds"] = []
        cases.append(("empty", empty, "missing coverage"))
        duplicate = catalog_group(child_evidence=True)
        duplicate["memberCoverageIds"][1] = duplicate["memberCoverageIds"][0]
        cases.append(("duplicate", duplicate, "duplicate coverage"))
        foreign = catalog_group(child_evidence=True)
        foreign["memberCoverageIds"][0] = catalog_member_id(109, "乙郡", 1)
        cases.append(("foreign", foreign, "foreign coverage"))
        malformed = catalog_group(child_evidence=True)
        malformed["memberCoverageIds"][0] = "[109,甲郡,1]"
        cases.append(("malformed", malformed, "invalid coverage"))
        noncanonical = catalog_group(child_evidence=True)
        noncanonical["memberCoverageIds"][0] = '[109, "甲郡", 1]'
        cases.append(("noncanonical", noncanonical, "non-canonical coverage"))

        for case_name, group, expected_error in cases:
            with self.subTest(case_name=case_name):
                with self.assertRaisesRegex(ValueError, expected_error):
                    load_administrative_history(
                        self.write_catalog_history({"catalogId": "fixture-hhs", "groups": [group]})
                    )

    def test_catalog_reference_rejects_unit_identity_mismatch(self) -> None:
        """Coverage cannot attest a child whose stored volume, group, or ordinal changes."""
        cases = []
        wrong_volume = catalog_group(child_evidence=True)
        wrong_volume["units"][0]["sourceVolume"] = 110
        cases.append(("volume", wrong_volume, "sourceVolume"))
        wrong_group = catalog_group(child_evidence=True)
        wrong_group["units"][0]["canonicalGroup"] = "乙郡"
        cases.append(("group", wrong_group, "canonicalGroup"))
        wrong_ordinal = catalog_group(child_evidence=True)
        wrong_ordinal["units"][0]["ordinal"] = 2
        cases.append(("ordinal", wrong_ordinal, "ordinal"))

        for case_name, group, expected_error in cases:
            with self.subTest(case_name=case_name):
                with self.assertRaisesRegex(ValueError, expected_error):
                    load_administrative_history(
                        self.write_catalog_history({"catalogId": "fixture-hhs", "groups": [group]})
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
                            "memberCoverageIds": [],
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
