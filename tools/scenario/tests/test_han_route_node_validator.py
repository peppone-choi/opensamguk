from __future__ import annotations

# SIZE_OK: One hermetic mutation matrix exercises the validator's single approval transaction.
# noqa: SIZE_OK — its shared 780-row fixture keeps every fail-closed mutation comparable.

import hashlib
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
MODULE_PATH = ROOT / "tools/scenario/validate_han_route_node_selection.py"
SPEC = importlib.util.spec_from_file_location("han_route_node_validator", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)

COUNT = 780
HASHES = {
    "candidate": "a" * 64,
    "catalog": "b" * 64,
    "overlay": "c" * 64,
    "claims": "d" * 64,
    "selection": "e" * 64,
    "migration": "f" * 64,
    "registry": MODULE.PINNED_ROUTE_KEY_REGISTRY_SHA256,
}
MUTABLE_REFERENCES = [
    "city.id",
    "general.city_id",
    "general.officer_city",
    "nation.capital_city_id",
    "v2_city_ledger.city_id",
    "general_turn.arg",
    "nation_turn.arg",
    "general.last_turn",
    "general.meta.officer_city",
    "command_inbox.payload",
]
IMMUTABLE_AUDIT_REFERENCES = ["command_result.result_payload", "command_outbox.payload", "history", "replay"]
DERIVED_RESEED_REFERENCES = [
    "scenario.nation.city_ids", "scenario.general.city_id", "HanCityConst", "HanGateIndex", "map.connections",
]


def real_documents() -> MODULE.ValidationDocuments:
    return MODULE.load_documents(
        MODULE.ValidationPaths(
            candidate=MODULE.DEFAULT_CANDIDATE,
            catalog=MODULE.DEFAULT_CATALOG,
            overlay=MODULE.DEFAULT_OVERLAY,
            external_claims=MODULE.DEFAULT_CLAIMS,
            selection=MODULE.DEFAULT_SELECTION,
            migration=MODULE.DEFAULT_MIGRATION,
            scenarios_dir=MODULE.DEFAULT_SCENARIOS,
            connections=None,
        )
    )


def forbidden_selections() -> MODULE.JsonObject:
    policy = json.loads(
        (ROOT / "data/curated/han/route-node-review-policy-v1.json").read_text(encoding="utf-8")
    )
    return policy["forbiddenSelections"]


def digest(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def route_key(index: int) -> str:
    return f"00000000-0000-4000-8000-{index:012x}"


def admin_id(index: int) -> str:
    return f"hhs:113:上郡:{index:03d}"


def physical_id(index: int) -> str:
    return f"chgis:v6:cnty:{index}"


def fingerprint(index: int) -> str:
    return "sha256:" + f"{index:064x}"


def scenario_resources() -> tuple[MODULE.ScenarioResource, ...]:
    return tuple(
        MODULE.ScenarioResource(
            scenario_id=str(index),
            resource_name=f"scenario_{index}.json",
            resource_path=f"infra/src/main/resources/scenario/scenario_{index}.json",
            start_year=180 + (index % 46),
            sha256=f"{index + 1:064x}",
        )
        for index in range(15)
    )


def valid_documents() -> MODULE.ValidationDocuments:
    units = [
        {
            "sourceVolume": 113,
            "canonicalGroup": "上郡",
            "ordinal": index,
            "sourceName": "龜茲" if index == 9 else f"縣{index}",
            "sourceNameStatus": "SOURCE_LITERAL",
            "unitType": "COUNTY",
        }
        for index in range(1, COUNT + 1)
    ]
    catalog = {
        "schemaVersion": 1,
        "catalogId": "fixture-catalog",
        "expectedUnitCount": COUNT,
        "detectedUnitCount": COUNT,
        "groups": [
            {
                "sourceVolume": 113,
                "sourceGroupName": "上郡",
                "canonicalGroup": "上郡",
                "units": units,
            }
        ],
    }
    overlay = {
        "schemaVersion": 1,
        "catalogId": "fixture-catalog",
        "sourceYear": 220,
        "administrativeUnits": [
            {
                "administrativeUnitId": admin_id(index),
                "identity": {"sourceVolume": 113, "canonicalGroup": "上郡", "ordinal": index},
                "joinStatus": "RESOLVED_POINT",
                "candidateCount": 1,
                "selectedCandidate": {
                    "physicalPlaceId": physical_id(index),
                    "recordIndex": index,
                    "coordinate": [110.0, 35.0],
                    "presentLocation": "external source only",
                },
            }
            for index in range(1, COUNT + 1)
        ],
    }
    for index in range(COUNT - 7, COUNT + 1):
        row = overlay["administrativeUnits"][index - 1]
        row["joinStatus"] = "NO_COORDINATE_CANDIDATE"
        row["candidateCount"] = 0
        row.pop("selectedCandidate")
    candidates = [
        {
            "origin": "CURRENT_780",
            "legacyCityId": index,
            "legacyNodeFingerprint": fingerprint(index),
            "legacyTileId": str(index),
            "legacyNameCh": "龜茲" if index == 9 else f"縣{index}",
            "legacyOwnerGroup": "上郡",
            "legacyIsSeat": index == 1,
            "reviewState": "PENDING",
            "physicalPlaceRef": physical_id(index),
            "classification": "HHS_RESOLVED",
            "proposedAdministrativeUnitId": admin_id(index),
        }
        for index in range(1, COUNT + 1)
    ]
    candidates.extend(
        {
            "origin": "HHS_REPLACEMENT_POOL",
            "candidateKey": f"fixture:replacement:{index}",
            "administrativeUnitId": admin_id(((index - 1) % COUNT) + 1),
            "physicalPlaceRefs": [],
            "reviewState": "PENDING",
        }
        for index in range(1, 1181)
    )
    for index in range(COUNT - 7, COUNT + 1):
        row = candidates[index - 1]
        row["classification"] = "HHS_UNMAPPED"
        row.pop("proposedAdministrativeUnitId")
        row.pop("physicalPlaceRef")
        row["externalPlaceRef"] = f"han-tiles:X{index}"
    resources = scenario_resources()
    candidate = {
        "schemaVersion": 1,
        "selectionId": "han-route-node-selection-candidates-v1",
        "candidatePolicy": {"reviewState": "PENDING", "automaticSelectionCount": 0},
        "summary": {
            "candidateCount": 1960,
            "legacyNodeCount": COUNT,
            "replacementPoolCount": 1180,
        },
        "scenarioCatalog": [
            {
                "code": resource.scenario_id,
                "resourcePath": resource.resource_path,
                "resourceSha256": resource.sha256,
                "startYear": resource.start_year,
            }
            for resource in resources
        ],
        "candidates": candidates,
    }
    route_nodes = [
        {
            "legacyCityId": index,
            "legacyNodeFingerprint": fingerprint(index),
            "numericCityId": index,
            "legacyDisposition": "RETAINED",
            "routeNodeKey": route_key(index),
            "nodeClass": "COUNTY_NODE",
            "displayName": "龜茲" if index == 9 else f"縣{index}",
            "canonicalName": "龜茲" if index == 9 else f"縣{index}",
            "parentName": "上郡",
            "parentRef": "hhs-group:113:上郡",
            "seatRole": "COMMANDERY_SEAT" if index == 1 else "NON_SEAT",
            "physicalPlaceRef": physical_id(index),
            "historicalBindingBasis": "HHS_ADMINISTRATIVE_UNIT",
            "locationAdjudication": {"kind": "W0B_GLOBAL_UNIQUE_220"},
            "administrativeUnitId": admin_id(index),
            "reviewState": "APPROVED",
            "selectionRationale": {
                "method": "APPROVED_REVIEW_BATCH",
                "reviewPolicyId": "han-w0c-route-node-review-policy-v1",
                "batchId": "w0b-overlay-unique-220",
                "rationale": MODULE.EXPECTED_SELECTION_RATIONALE,
                "evidenceRefs": [
                    "data/curated/han/route-node-review-policy-v1.json",
                    "w0b-overlay-unique-220",
                ],
            },
        }
        for index in range(1, COUNT + 1)
    ]
    claims = {"schemaVersion": 1, "claimSetId": "han-route-node-source-claims-v1", "claims": []}
    for index in range(COUNT - 7, COUNT + 1):
        claim_id = f"fixture-location-claim-{index}"
        physical = f"external:v1:X{index}"
        claims["claims"].append(
            {
                "aliases": [],
                "sourceClaimId": claim_id,
                "reviewState": "APPROVED",
                "claimRole": "LOCATION_ONLY",
                "subjectType": "ADMINISTRATIVE_PLACE",
                "subjectKey": admin_id(index),
                "canonicalName": f"縣{index}",
                "selectionReviewCoverage": "W0_ROUTE_NODE_PLACE_IDENTITY_ONLY",
                "reviewEvidenceRefs": list(MODULE.IDENTITY_REVIEW_EVIDENCE_REFS),
                "locationResolution": {
                    "kind": "POINT_REF",
                    "physicalPlaceId": physical,
                    "uncertaintyRadiusKm": 10,
                    "coordinateDatasetRef": {
                        "datasetPath": "data/authority.json",
                        "datasetSha256": "f6a255e76e305641ef3a8b7d925b481663809d6177dacb9e87801082d5de62a9",
                        "recordId": f"X{index}",
                        "wikidataId": f"Q{index}",
                    },
                },
                "sourceRecords": [
                    {
                        "sourceBook": "後漢書",
                        "volume": 113,
                        "corpusPath": "data/corpus/source.txt",
                        "lineStart": 1,
                        "lineEnd": 1,
                        "snapshotSha256": "f3f8678538aa2175719b6c699fb63495e9f91c87e611004eefbb760aa7708635",
                        "verbatim": "fixture source record",
                    }
                ],
                "conflictDisposition": {
                    "status": "NONE",
                    "rationaleCode": "PLACE_IDENTITY_ONLY",
                    "rationale": "fixture source record",
                    "competingRefs": [],
                },
            }
        )
        node = route_nodes[index - 1]
        node["physicalPlaceRef"] = physical
        node["locationClaimId"] = claim_id
        node["locationAdjudication"] = {"kind": "APPROVED_LOCATION_ONLY_CLAIM", "sourceClaimId": claim_id}
        node["selectionRationale"]["batchId"] = "w0c-hhs-external-location"
        node["selectionRationale"]["evidenceRefs"] = [
            MODULE.REVIEW_POLICY_PATH,
            "w0c-hhs-external-location",
        ]
    selection = {
        "schemaVersion": 1,
        "selectionId": "han-route-node-selection-v1",
        "reviewState": "APPROVED",
        "baselineYear": 220,
        "runtimeScenarioActivationEnforcement": "NOT_CLAIMED_BY_W0_DATA_CONTRACT",
        "reviewPolicy": {
            "legacyAttributionCorrections": [],
            "numericCityIdChangeAllowed": False,
            "forbiddenSelections": forbidden_selections(),
        },
        "scenarioCatalog": {
            "resourceCount": 15,
            "resources": [
                {
                    "scenarioId": resource.scenario_id,
                    "resourceName": resource.resource_name,
                    "resourcePath": resource.resource_path,
                    "startYear": resource.start_year,
                    "sha256": resource.sha256,
                }
                for resource in resources
            ],
        },
        "provenance": {
            "inputs": {
                "candidate": {"sha256": HASHES["candidate"]},
                "administrativeCatalog": {"sha256": HASHES["catalog"]},
                "administrativePlaceOverlay": {"sha256": HASHES["overlay"]},
                "externalClaims": {"sha256": HASHES["claims"]},
            }
        },
        "summary": {
            "approvedCount": COUNT,
            "historicalBindingCounts": {"HHS_ADMINISTRATIVE_UNIT": COUNT},
        },
        "routeNodes": route_nodes,
    }
    migration = {
        "schemaVersion": 1,
        "migrationId": "han-route-node-migration-v1",
        "mode": "NEW_WORLD_ONLY",
        "sourceSelectionId": "han-route-node-selection-v1",
        "sourceSelectionSha256": HASHES["selection"],
        "sourceCandidateSha256": HASHES["candidate"],
        "referenceInventory": {
            "mutable": list(MUTABLE_REFERENCES),
            "immutableAudit": list(IMMUTABLE_AUDIT_REFERENCES),
            "derivedReseed": list(DERIVED_RESEED_REFERENCES),
            "unknownPayloadPolicy": "REJECT_UNKNOWN",
            "inPlaceRewrite": False,
        },
        "rewriteSurfaces": json.loads(json.dumps(MODULE.EXPECTED_REWRITE_SURFACES)),
        "summary": {
            "rowCount": COUNT,
            "numericCityIdChangeCount": 0,
            "routeNodeReplacementCount": 0,
            "historicalBindingCorrectionCount": 8,
            "physicalPlaceCorrectionCount": 0,
            "displayNameChangeCount": 0,
            "parentChangeCount": 0,
            "seatRoleChangeCount": 0,
        },
        "rows": [
            {
                "oldCityId": index,
                "oldNodeFingerprint": fingerprint(index),
                "routeNodeKey": route_key(index),
                "newCityId": index,
                "disposition": "CORRECTED_BINDING_SAME_NODE" if index >= COUNT - 7 else "RETAINED_SAME_NODE",
            }
            for index in range(1, COUNT + 1)
        ],
    }
    route_key_registry = {
        "schemaVersion": 1,
        "registryId": "fixture-route-node-key-registry",
        "keyPolicy": {
            "format": "RFC_4122_UUID_V4_OPAQUE_LITERAL",
            "derivedFromNumericCityId": False,
            "derivedFromAdministrativeIdentity": False,
            "derivedFromPhysicalPlace": False,
            "derivedFromSourceClaim": False,
            "rebindingChangesKey": False,
            "note": "fixture opaque keys",
        },
        "keys": [
            {
                "initialAdministrativeUnitId": admin_id(index),
                "routeNodeKey": route_key(index),
                "issuanceReason": "W0C_REVIEWED_SELECTION",
            }
            for index in range(1, COUNT + 1)
        ],
    }
    return MODULE.ValidationDocuments(
        candidate=candidate,
        catalog=catalog,
        overlay=overlay,
        external_claims=claims,
        selection=selection,
        migration=migration,
        route_key_registry=route_key_registry,
        scenarios=resources,
        candidate_sha256=HASHES["candidate"],
        catalog_sha256=HASHES["catalog"],
        overlay_sha256=HASHES["overlay"],
        claims_sha256=HASHES["claims"],
        selection_sha256=HASHES["selection"],
        migration_sha256=HASHES["migration"],
        route_key_registry_sha256=HASHES["registry"],
        source_root=ROOT / "tools/scenario/tests/fixtures/repository",
        connections=None,
        connections_sha256=None,
    )


class HanRouteNodeValidatorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.documents = valid_documents()

    def assert_invalid(self, pattern: str) -> None:
        with self.assertRaisesRegex(MODULE.SelectionContractError, pattern):
            MODULE.validate_documents(self.documents)

    def test_green_780_fixture_satisfies_the_full_contract(self) -> None:
        report = MODULE.validate_documents(self.documents)

        self.assertEqual(COUNT, report.approved_count)
        self.assertEqual(15, report.scenario_count)
        self.assertEqual(HASHES["selection"], report.selection_sha256)
        self.assertEqual(HASHES["migration"], report.migration_sha256)

    def test_selection_and_migration_ids_are_canonical_after_coherent_rehash(self) -> None:
        mutations = (
            (
                "selectionId",
                lambda documents: (
                    documents.selection.__setitem__("selectionId", "forged-selection-v1"),
                    documents.migration.__setitem__("sourceSelectionId", "forged-selection-v1"),
                ),
            ),
            (
                "sourceSelectionId",
                lambda documents: documents.migration.__setitem__(
                    "sourceSelectionId", "forged-selection-v1"
                ),
            ),
            (
                "migrationId",
                lambda documents: documents.migration.__setitem__(
                    "migrationId", "forged-migration-v1"
                ),
            ),
        )
        for expected, mutate in mutations:
            with self.subTest(expected=expected):
                documents = valid_documents()
                mutate(documents)
                documents = replace(documents, selection_sha256="1" * 64)
                documents.migration["sourceSelectionSha256"] = "1" * 64
                with self.assertRaisesRegex(MODULE.SelectionContractError, expected):
                    MODULE.validate_documents(documents)

    def test_historical_binding_summary_is_recomputed_from_validated_nodes(self) -> None:
        self.documents.selection["summary"]["historicalBindingCounts"] = {
            "HHS_ADMINISTRATIVE_UNIT": 0
        }

        self.assert_invalid("historicalBindingCounts")

    def test_legacy_attribution_inventory_exact_matches_the_pinned_review_policy(self) -> None:
        for replacement in ([], [56]):
            with self.subTest(replacement=replacement):
                documents = real_documents()
                documents.selection["reviewPolicy"]["legacyAttributionCorrections"] = replacement
                with self.assertRaisesRegex(
                    MODULE.SelectionContractError, "legacyAttributionCorrections.*pinned review policy"
                ):
                    MODULE.validate_documents(documents)

    def test_pinned_same_node_corrections_reject_a_coherent_legacy_slot_swap(self) -> None:
        documents = real_documents()
        nodes = {row["legacyCityId"]: row for row in documents.selection["routeNodes"]}
        first, second = nodes[704], nodes[720]
        for field in ("numericCityId", "legacyCityId", "legacyNodeFingerprint"):
            first[field], second[field] = second[field], first[field]
        migration_rows = {
            row["oldCityId"]: row for row in documents.migration["rows"]
        }
        migration_rows[704]["routeNodeKey"], migration_rows[720]["routeNodeKey"] = (
            migration_rows[720]["routeNodeKey"],
            migration_rows[704]["routeNodeKey"],
        )
        documents.migration["summary"]["parentChangeCount"] = 115
        documents = replace(documents, selection_sha256="8" * 64)
        documents.migration["sourceSelectionSha256"] = "8" * 64

        with self.assertRaisesRegex(
            MODULE.SelectionContractError, "same-node correction content.*pinned review policy"
        ):
            MODULE.validate_documents(documents)

    def test_selection_baseline_year_is_exactly_220(self) -> None:
        self.documents.selection["baselineYear"] = 221
        self.assert_invalid("baselineYear must be 220")

    def test_hhs_binding_basis_must_match_the_derived_binding_kind(self) -> None:
        self.documents.selection["routeNodes"][0]["historicalBindingBasis"] = "NOT_HHS"
        self.assert_invalid("historicalBindingBasis")

    def test_coordinated_claim_and_witness_repin_cannot_replace_production_anchor(self) -> None:
        claim = self.documents.external_claims["claims"][0]
        record = claim["sourceRecords"][0]
        record["verbatim"] = "coordinated forged witness"
        forged_claim_hash = "9" * 64
        self.documents.selection["provenance"]["inputs"]["externalClaims"]["sha256"] = (
            forged_claim_hash
        )
        with tempfile.TemporaryDirectory() as raw_directory:
            source_root = Path(raw_directory)
            witness_path = (
                source_root / "data/curated/han/route-node-source-witness-v1.json"
            )
            witness_path.parent.mkdir(parents=True)
            witness_path.write_text(
                json.dumps(
                    {
                        "schemaVersion": 1,
                        "records": [{"sourceClaimId": claim["sourceClaimId"], **record}],
                    },
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )
            self.documents = replace(
                self.documents,
                claims_sha256=forged_claim_hash,
                catalog_sha256=MODULE.PINNED_ADMINISTRATIVE_CATALOG_SHA256,
                candidate_sha256=MODULE.PINNED_REVIEWED_CANDIDATE_SHA256,
                production_approval_mode=True,
                source_root=source_root,
            )
            self.assert_invalid("independent source-witness anchor")

    def test_ambiguous_point_passes_only_with_exhaustive_adjudication(self) -> None:
        overlay = self.documents.overlay["administrativeUnits"][0]
        overlay["joinStatus"] = "AMBIGUOUS_POINT"
        overlay["candidateCount"] = 2
        selected = overlay.pop("selectedCandidate")
        overlay["candidates"] = [selected, {"physicalPlaceId": "chgis:v6:cnty:alternate"}]
        candidate = self.documents.candidate["candidates"][0]
        candidate["classification"] = "HHS_AMBIGUOUS"
        candidate.pop("proposedAdministrativeUnitId")
        candidate["candidateAdministrativeUnitIds"] = [admin_id(1)]
        self.documents.selection["routeNodes"][0]["locationAdjudication"] = {
            "selectedPhysicalPlaceRef": physical_id(1),
            "rejectedPhysicalPlaceRefs": ["chgis:v6:cnty:alternate"],
            "rationaleCode": "GROUP_GEOGRAPHY_AND_TEMPORAL_RECORD_REVIEW",
            "rationale": "textual source agreement",
            "evidenceRefs": [
                "data/curated/han/administrative-place-bindings-v1.json",
                "docs/superpowers/reviews/2026-08-22-w0b-administrative-place-overlay-review.md",
            ],
        }
        self.documents.selection["routeNodes"][0]["selectionRationale"]["batchId"] = (
            "w0c-reviewed-ambiguity"
        )
        self.documents.selection["routeNodes"][0]["selectionRationale"]["evidenceRefs"] = [
            MODULE.REVIEW_POLICY_PATH,
            "w0c-reviewed-ambiguity",
        ]

        report = MODULE.validate_documents(self.documents)

        self.assertEqual(1, report.ambiguous_adjudication_count)

    def test_coordinated_ambiguous_winner_swap_cannot_repin_selection(self) -> None:
        documents = real_documents()
        node = next(row for row in documents.selection["routeNodes"] if row["legacyCityId"] == 19)
        original = node["physicalPlaceRef"]
        rejected = node["locationAdjudication"]["rejectedPhysicalPlaceRefs"][0]
        node["physicalPlaceRef"] = rejected
        node["locationAdjudication"]["selectedPhysicalPlaceRef"] = rejected
        node["locationAdjudication"]["rejectedPhysicalPlaceRefs"] = [original]
        node["physicalPlaceCorrection"] = {
            "fromPhysicalPlaceRef": original,
            "toPhysicalPlaceRef": rejected,
            "rationale": "coherently repinned alternate ambiguity winner",
            "evidenceRefs": ["coherent:repin"],
        }
        migration = next(
            row for row in documents.migration["rows"] if row["oldCityId"] == 19
        )
        migration["disposition"] = "CORRECTED_LOCATION_SAME_NODE"
        documents.migration["summary"]["physicalPlaceCorrectionCount"] += 1
        forged_selection_hash = "6" * 64
        documents.migration["sourceSelectionSha256"] = forged_selection_hash
        documents = replace(documents, selection_sha256=forged_selection_hash)

        with self.assertRaisesRegex(
            MODULE.SelectionContractError, "pinned location-adjudication ledger"
        ):
            MODULE.validate_documents(documents)

    def test_location_adjudication_ledger_must_name_loaded_overlay_digest(self) -> None:
        documents = real_documents()
        ledger = json.loads(
            (ROOT / MODULE.PROVENANCE_DEPENDENCIES["locationAdjudications"]).read_text(
                encoding="utf-8"
            )
        )
        ledger["inputOverlaySha256"] = "0" * 64
        with tempfile.TemporaryDirectory() as raw_directory:
            source_root = Path(raw_directory)
            ledger_path = source_root / MODULE.PROVENANCE_DEPENDENCIES["locationAdjudications"]
            ledger_path.parent.mkdir(parents=True)
            ledger_path.write_text(json.dumps(ledger, ensure_ascii=False), encoding="utf-8")
            documents = replace(documents, source_root=source_root)
            with self.assertRaisesRegex(
                MODULE.SelectionContractError, "inputOverlaySha256"
            ):
                MODULE._reviewed_location_adjudication_index(documents)

    def test_location_adjudication_ledger_boolean_schema_version_is_rejected(self) -> None:
        documents = real_documents()
        ledger = json.loads(
            (ROOT / MODULE.PROVENANCE_DEPENDENCIES["locationAdjudications"]).read_text(
                encoding="utf-8"
            )
        )
        ledger["schemaVersion"] = True
        with tempfile.TemporaryDirectory() as raw_directory:
            source_root = Path(raw_directory)
            ledger_path = source_root / MODULE.PROVENANCE_DEPENDENCIES["locationAdjudications"]
            ledger_path.parent.mkdir(parents=True)
            ledger_path.write_text(json.dumps(ledger, ensure_ascii=False), encoding="utf-8")
            documents = replace(documents, source_root=source_root)
            with self.assertRaisesRegex(
                MODULE.SelectionContractError,
                "location adjudications schemaVersion must be exact integer 1",
            ):
                MODULE._reviewed_location_adjudication_index(documents)

    def test_no_coordinate_hhs_binding_passes_with_approved_location_claim(self) -> None:
        report = MODULE.validate_documents(self.documents)

        self.assertEqual(8, report.location_claim_count)

    def test_attribution_conflict_passes_with_exhaustive_disposition(self) -> None:
        candidate = self.documents.candidate["candidates"][0]
        candidate["classification"] = "HHS_ATTRIBUTION_CONFLICT"
        candidate.pop("proposedAdministrativeUnitId")
        candidate["incompatibleAdministrativeUnitIds"] = [admin_id(1)]
        node = self.documents.selection["routeNodes"][0]
        conflict_row = {
            "legacyCityId": node["legacyCityId"],
            "administrativeUnitId": node["administrativeUnitId"],
            "selectedBindingRef": admin_id(1),
            "rejectedAdministrativeUnitIds": [],
        }
        conflict_digest = MODULE._decision_digest([conflict_row])
        replacement_digest = MODULE._decision_digest([])
        self.documents.selection["reviewPolicy"]["reviewDecisionAnchors"] = {
            "replacementDecisionSet": {
                "anchor": f"replacementDecisionSet:{replacement_digest}",
                "assignmentSha256": replacement_digest,
                "reviewState": "APPROVED",
                "rowCount": 0,
            },
            "historicalConflictDecisionSet": {
                "anchor": f"historicalConflictDecisionSet:{conflict_digest}",
                "assignmentSha256": conflict_digest,
                "reviewState": "APPROVED",
                "rowCount": 1,
            },
        }
        node["historicalConflictDisposition"] = {
            "selectedBindingRef": admin_id(1),
            "rejectedAdministrativeUnitIds": [],
            "rationale": "source attribution reviewed",
            "evidenceRefs": [
                MODULE.REVIEW_POLICY_PATH,
                f"historicalConflictDecisionSet:{conflict_digest}",
            ],
        }

        report = MODULE.validate_documents(self.documents)

        self.assertEqual(COUNT, report.approved_count)

    def test_external_binding_is_rejected_by_the_hhs_only_selection_contract(self) -> None:
        claim = self.documents.external_claims["claims"][0]
        claim["claimRole"] = "ROUTE_NODE"
        claim["subjectType"] = "AnchoredPlace"
        node = self.documents.selection["routeNodes"][0]
        node.pop("administrativeUnitId")
        node["sourceClaimId"] = claim["sourceClaimId"]

        self.assert_invalid("LOCATION_ONLY|external bindings")

    def test_physical_place_correction_is_structured_and_recomputed(self) -> None:
        old_ref = physical_id(1)
        new_ref = "chgis:v6:cnty:corrected"
        overlay = self.documents.overlay["administrativeUnits"][0]
        overlay["selectedCandidate"]["physicalPlaceId"] = new_ref
        node = self.documents.selection["routeNodes"][0]
        node["physicalPlaceRef"] = new_ref
        node["physicalPlaceCorrection"] = {
            "fromPhysicalPlaceRef": old_ref,
            "toPhysicalPlaceRef": new_ref,
            "rationale": "source record correction",
            "evidenceRefs": ["fixture:physical-correction:1"],
        }
        self.documents.migration["rows"][0]["disposition"] = "CORRECTED_LOCATION_SAME_NODE"
        self.documents.migration["summary"]["physicalPlaceCorrectionCount"] = 1

        report = MODULE.validate_documents(self.documents)

        self.assertEqual(1, report.physical_place_correction_count)

    def test_physical_place_correction_evidence_resolves_to_its_pinned_ledger_row(self) -> None:
        documents = real_documents()
        node = next(
            row for row in documents.selection["routeNodes"]
            if "physicalPlaceCorrection" in row
        )
        node["physicalPlaceCorrection"]["evidenceRefs"] = [
            "data/curated/han/does-not-exist.json",
            node["administrativeUnitId"],
        ]
        documents = replace(documents, selection_sha256="3" * 64)
        documents.migration["sourceSelectionSha256"] = "3" * 64

        with self.assertRaisesRegex(
            MODULE.SelectionContractError, "physicalPlaceCorrection evidenceRefs.*pinned"
        ):
            MODULE.validate_documents(documents)

    def test_retained_unchanged_node_requires_retained_migration_disposition(self) -> None:
        self.documents.migration["rows"][0]["disposition"] = "REPLACED_UNRELATED_NODE"
        self.assert_invalid("retained unchanged node must use RETAINED_SAME_NODE")

    def test_approved_count_must_be_exactly_780(self) -> None:
        self.documents.selection["routeNodes"].pop()
        self.assert_invalid("780")

    def test_every_route_node_must_be_approved(self) -> None:
        self.documents.selection["routeNodes"][0]["reviewState"] = "PENDING"
        self.assert_invalid("APPROVED")

    def test_numeric_city_ids_must_be_exact_1_through_780(self) -> None:
        self.documents.selection["routeNodes"][0]["numericCityId"] = 2
        self.assert_invalid("numericCityId")

    def test_legacy_city_ids_must_be_exact_1_through_780(self) -> None:
        self.documents.candidate["candidates"][0]["legacyCityId"] = 2
        self.assert_invalid("legacyCityId")

    def test_route_node_keys_must_be_literal_version_4_uuids(self) -> None:
        self.documents.selection["routeNodes"][0]["routeNodeKey"] = admin_id(1)
        self.assert_invalid("routeNodeKey")

    def test_route_node_keys_must_be_unique(self) -> None:
        self.documents.selection["routeNodes"][0]["routeNodeKey"] = route_key(2)
        self.assert_invalid("routeNodeKey")

    def test_physical_place_references_must_be_unique(self) -> None:
        self.documents.selection["routeNodes"][0]["physicalPlaceRef"] = physical_id(2)
        self.assert_invalid("physicalPlaceRef")

    def test_historical_binding_requires_exactly_one_reference(self) -> None:
        self.documents.selection["routeNodes"][0]["sourceClaimId"] = "claim:route:1"
        self.assert_invalid("exactly one")

    def test_historical_binding_cannot_be_missing(self) -> None:
        self.documents.selection["routeNodes"][0].pop("administrativeUnitId")
        self.assert_invalid("exactly one")

    def test_historical_binding_must_be_unique(self) -> None:
        self.documents.selection["routeNodes"][0]["administrativeUnitId"] = admin_id(2)
        self.assert_invalid("binding")

    def test_historical_binding_cannot_dangle(self) -> None:
        self.documents.selection["routeNodes"][0]["administrativeUnitId"] = "hhs:999:假郡:001"
        self.assert_invalid("unknown administrative")

    def test_catalog_source_name_must_be_non_empty(self) -> None:
        self.documents.catalog["groups"][0]["units"][0]["sourceName"] = ""
        self.assert_invalid("sourceName.*hhs:113:上郡:001")

    def test_catalog_source_name_status_must_be_known(self) -> None:
        self.documents.catalog["groups"][0]["units"][0]["sourceNameStatus"] = "UNKNOWN"
        self.assert_invalid("sourceNameStatus.*hhs:113:上郡:001")

    def test_catalog_unit_type_must_be_supported(self) -> None:
        self.documents.catalog["groups"][0]["units"][0]["unitType"] = "CITY"
        self.assert_invalid("unitType.*hhs:113:上郡:001")

    def test_hhs_binding_metadata_must_match_its_catalog_row(self) -> None:
        for field, value in {
            "nodeClass": "DAO_NODE",
            "displayName": "WRONG",
            "canonicalName": "WRONG",
            "parentName": "WRONG",
            "parentRef": "hhs-group:113:WRONG",
            "seatRole": "NON_SEAT",
        }.items():
            with self.subTest(field=field):
                self.documents = valid_documents()
                self.documents.selection["routeNodes"][0][field] = value
                self.assert_invalid(f"catalog metadata.*{field}")

    def test_source_placeholder_cannot_be_selected(self) -> None:
        self.documents.catalog["groups"][0]["units"][0]["sourceNameStatus"] = "SOURCE_PLACEHOLDER"
        self.assert_invalid("placeholder")

    def test_ambiguous_point_requires_location_adjudication(self) -> None:
        self._make_ambiguous()
        self.documents.selection["routeNodes"][0].pop("locationAdjudication")
        self.assert_invalid("locationAdjudication")

    def test_ambiguous_rejections_must_be_exhaustive(self) -> None:
        self._make_ambiguous()
        self.documents.selection["routeNodes"][0]["locationAdjudication"]["rejectedPhysicalPlaceRefs"] = []
        self.assert_invalid("exhaustive")

    def test_nearest_or_distance_rationale_is_forbidden(self) -> None:
        self.documents.selection["routeNodes"][0]["selectionRationale"]["method"] = "NEAREST_DISTANCE"
        self.assert_invalid("nearest|distance")

    def test_selection_rationale_evidence_must_anchor_to_the_pinned_review_batch(self) -> None:
        self.documents.selection["routeNodes"][0]["selectionRationale"]["evidenceRefs"] = [
            "forged:nonempty"
        ]
        self.assert_invalid("selectionRationale evidenceRefs")

    def test_selection_rationale_text_must_match_the_materializer_output(self) -> None:
        documents = real_documents()
        documents.selection["routeNodes"][0]["selectionRationale"]["rationale"] = (
            "fabricated but non-empty review explanation"
        )
        forged_selection_hash = digest(
            (json.dumps(documents.selection, ensure_ascii=False, indent=2) + "\n").encode()
        )
        documents.migration["sourceSelectionSha256"] = forged_selection_hash
        documents = replace(documents, selection_sha256=forged_selection_hash)

        with self.assertRaisesRegex(
            MODULE.SelectionContractError,
            "selectionRationale rationale must match the canonical materializer output",
        ):
            MODULE.validate_documents(documents)

    def test_selection_review_batch_must_match_the_validated_binding_route(self) -> None:
        documents = real_documents()
        node = next(
            row for row in documents.selection["routeNodes"]
            if row["selectionRationale"]["batchId"] == "w0b-overlay-unique-220"
        )
        node["selectionRationale"]["batchId"] = "w0c-reviewed-ambiguity"
        node["selectionRationale"]["evidenceRefs"] = [
            MODULE.REVIEW_POLICY_PATH,
            "w0c-reviewed-ambiguity",
        ]
        documents = replace(documents, selection_sha256="2" * 64)
        documents.migration["sourceSelectionSha256"] = "2" * 64

        with self.assertRaisesRegex(MODULE.SelectionContractError, "review batch.*binding route"):
            MODULE.validate_documents(documents)

    def test_no_coordinate_binding_requires_an_external_location_claim(self) -> None:
        overlay = self.documents.overlay["administrativeUnits"][0]
        overlay["joinStatus"] = "NO_COORDINATE_CANDIDATE"
        overlay["candidateCount"] = 0
        overlay.pop("selectedCandidate")
        self.assert_invalid("locationClaimId")

    def test_overlay_status_shapes_are_mutually_exclusive_and_closed(self) -> None:
        self.documents = valid_documents()
        no_coordinate = self.documents.overlay["administrativeUnits"][-1]
        no_coordinate["selectedCandidate"] = {"physicalPlaceId": "chgis:v6:cnty:forged"}
        self.assert_invalid("NO_COORDINATE_CANDIDATE overlay row fields must be exact")

        self.documents = valid_documents()
        resolved = self.documents.overlay["administrativeUnits"][0]
        resolved["candidates"] = [
            {"physicalPlaceId": physical_id(1), "runtimeWeight": 1}
        ]
        self.assert_invalid("overlay place candidate contains unknown fields")

    def test_tracked_artifacts_recursively_forbid_coordinate_fields(self) -> None:
        self.documents.selection["routeNodes"][0]["review"] = {"coordinate": [110, 35]}
        self.assert_invalid("unknown fields")

    def test_recursive_forbidden_field_guard_rejects_nested_coordinates(self) -> None:
        with self.assertRaisesRegex(
            MODULE.SelectionContractError, "forbidden tracked field coordinate"
        ):
            MODULE._forbid_fields(
                {"routeNodes": [{"review": {"coordinate": [110, 35]}}]}, "selection"
            )

    def test_external_claim_must_be_identity_only_w0_review_coverage(self) -> None:
        claim = self.documents.external_claims["claims"][0]
        claim["selectionReviewCoverage"] = "RUNTIME_LIFECYCLE"
        self.assert_invalid("identity-only W0 review coverage")

    def test_identity_only_claim_schema_rejects_lifecycle_bypasses(self) -> None:
        mutations = (
            ("subjectPeriod", {"startYear": 100, "endYear": 192}),
            ("lifecycle", {"activeUntilYear": 192}),
            ("lifecycleNote", "런타임에서 이 장소를 192년까지 활성 상태로 유지한다"),
        )
        for field, value in mutations:
            with self.subTest(field=field):
                self.documents = valid_documents()
                self.documents.external_claims["claims"][0][field] = value
                self.assert_invalid("LOCATION_ONLY external claim fields must be exact")

        self.documents = valid_documents()
        self.documents.external_claims["claims"][0]["conflictDisposition"]["rationale"] = (
            "이 장소는 시나리오에서 192년까지 유효하다"
        )
        self.assert_invalid("identity-only external claim rationale cannot assert lifecycle")

    def test_identity_only_claim_requires_structured_rationale_code(self) -> None:
        self.documents.external_claims["claims"][0]["conflictDisposition"]["rationaleCode"] = (
            "RUNTIME_LIFECYCLE"
        )
        self.assert_invalid("rationaleCode is invalid")

    def test_identity_only_claim_nested_schemas_reject_lifecycle_bypasses(self) -> None:
        self.documents.external_claims["claims"][0]["locationResolution"]["subjectPeriod"] = {
            "startYear": 100,
            "endYear": 192,
        }
        self.assert_invalid("locationResolution fields must be exact")

        self.documents = valid_documents()
        self.documents.external_claims["claims"][0]["sourceRecords"][0]["lifecycle"] = {
            "activeUntilYear": 192
        }
        self.assert_invalid("sourceRecord fields must be exact")

        self.documents = valid_documents()
        self.documents.external_claims["claims"][0]["reviewEvidenceRefs"].append(
            "runtime:active-until:192"
        )
        self.assert_invalid("reviewEvidenceRefs must exact-match")

    def test_external_claim_must_be_approved(self) -> None:
        claim = self.documents.external_claims["claims"][0]
        claim["reviewState"] = "PENDING"
        self.assert_invalid("APPROVED")

    def test_external_claim_requires_a_point_reference(self) -> None:
        claim = self.documents.external_claims["claims"][0]
        claim["locationResolution"]["physicalPlaceId"] = ""
        self.assert_invalid("physicalPlaceId")

    def test_external_claim_subject_type_must_be_known(self) -> None:
        claim = self.documents.external_claims["claims"][0]
        claim["subjectType"] = "InventedType"
        self.assert_invalid("subjectType")

    def test_polity_presence_cannot_bind_a_route_node(self) -> None:
        self._bind_external_type("PolityPresence")
        self.assert_invalid("PolityPresence")

    def test_remote_gate_cannot_bind_a_route_node(self) -> None:
        self._bind_external_type("RemoteGate")
        self.assert_invalid("RemoteGate")

    def test_alias_only_claim_cannot_bind_a_route_node(self) -> None:
        self._bind_external_type("ALIAS_ONLY")
        self.assert_invalid("ALIAS_ONLY")

    def test_scenario_catalog_must_cover_the_exact_15_resources(self) -> None:
        self.documents.selection["scenarioCatalog"]["resources"].pop()
        self.assert_invalid("scenario catalog")

    def test_scenario_catalog_hashes_must_match_actual_resources(self) -> None:
        self.documents.selection["scenarioCatalog"]["resources"][0]["sha256"] = "0" * 64
        self.assert_invalid("scenario catalog")

    def test_scenario_catalog_resource_path_must_be_the_exact_repository_sibling(self) -> None:
        self.documents.selection["scenarioCatalog"]["resources"][0]["resourcePath"] = (
            "../../scenario_0.json"
        )
        self.assert_invalid("scenario catalog")

        self.documents = real_documents()
        self.documents.candidate["scenarioCatalog"][0]["resourcePath"] = "../../scenario_1010.json"
        self.assert_invalid("candidate scenario catalog")

    def test_candidate_scenario_catalog_rejects_a_sixteenth_duplicate_row(self) -> None:
        self.documents = real_documents()
        self.documents.candidate["scenarioCatalog"].append(
            dict(self.documents.candidate["scenarioCatalog"][0])
        )
        self.assert_invalid("15 unique codes and resource paths")

    def test_candidate_scenario_catalog_is_required(self) -> None:
        self.documents.candidate.pop("scenarioCatalog")
        self.assert_invalid("scenarioCatalog")

    def test_w0_nodes_reject_runtime_lifecycle_fields(self) -> None:
        for field in ("effectiveFrom", "effectiveTo", "bindingLifecycle", "activeScenarioIds", "scenarioStates"):
            with self.subTest(field=field):
                documents = real_documents()
                documents.selection["routeNodes"][0][field] = 181
                with self.assertRaisesRegex(MODULE.SelectionContractError, "must not claim runtime lifecycle"):
                    MODULE.validate_documents(documents)

    def test_runtime_scenario_activation_enforcement_is_not_claimed(self) -> None:
        documents = real_documents()
        documents.selection["runtimeScenarioActivationEnforcement"] = "ENFORCED"

        with self.assertRaisesRegex(MODULE.SelectionContractError, "runtimeScenarioActivationEnforcement"):
            MODULE.validate_documents(documents)

    def test_node_class_must_be_one_of_the_four_route_node_classes(self) -> None:
        documents = real_documents()
        documents.selection["routeNodes"][0]["nodeClass"] = "POLITY_PRESENCE"

        with self.assertRaisesRegex(MODULE.SelectionContractError, "nodeClass"):
            MODULE.validate_documents(documents)

    def test_fake_guizi_commandery_string_is_forbidden(self) -> None:
        self.documents.selection["routeNodes"][0]["displayName"] = "龜茲屬國"
        self.assert_invalid("龜茲屬國")

    def test_correct_guizi_hhs_binding_must_appear_exactly_once(self) -> None:
        self.documents.selection["routeNodes"][8]["administrativeUnitId"] = admin_id(10)
        self.assert_invalid("hhs:113:上郡:009")

    def test_migration_mode_is_new_world_only(self) -> None:
        self.documents.migration["mode"] = "IN_PLACE"
        self.assert_invalid("NEW_WORLD_ONLY")

    def test_migration_has_exactly_780_rows(self) -> None:
        self.documents.migration["rows"].pop()
        self.assert_invalid("780")

    def test_migration_fingerprint_must_match_candidate(self) -> None:
        self.documents.migration["rows"][0]["oldNodeFingerprint"] = fingerprint(999)
        self.assert_invalid("fingerprint")

    def test_migration_counters_are_recomputed(self) -> None:
        self.documents.migration["summary"]["displayNameChangeCount"] = 1
        self.assert_invalid("displayNameChangeCount")

    def test_route_replacement_counter_comes_from_reviewed_row_disposition(self) -> None:
        node = self.documents.selection["routeNodes"][0]
        node["legacyDisposition"] = "REPLACED"
        self.documents.candidate["candidates"][0]["legacyNameCh"] = "구명"
        replacement_rows = [
            {key: node[key] for key in ("legacyCityId", "administrativeUnitId", "physicalPlaceRef", "routeNodeKey")}
        ]
        replacement_digest = MODULE._decision_digest(replacement_rows)
        conflict_digest = MODULE._decision_digest([])
        self.documents.selection["reviewPolicy"]["reviewDecisionAnchors"] = {
            "replacementDecisionSet": {
                "anchor": f"replacementDecisionSet:{replacement_digest}",
                "assignmentSha256": replacement_digest,
                "reviewState": "APPROVED",
                "rowCount": 1,
            },
            "historicalConflictDecisionSet": {
                "anchor": f"historicalConflictDecisionSet:{conflict_digest}",
                "assignmentSha256": conflict_digest,
                "reviewState": "APPROVED",
                "rowCount": 0,
            },
        }
        node["replacementDisposition"] = {
            "rationale": "historical route replacement",
            "evidenceRefs": [
                MODULE.REVIEW_POLICY_PATH,
                f"replacementDecisionSet:{replacement_digest}",
            ],
        }
        self.documents.migration["rows"][0]["disposition"] = "REPLACED_UNRELATED_NODE"
        self.assert_invalid("routeNodeReplacementCount")

    def test_retained_physical_change_requires_structured_correction(self) -> None:
        new_ref = "chgis:v6:cnty:corrected"
        self.documents.overlay["administrativeUnits"][0]["selectedCandidate"]["physicalPlaceId"] = new_ref
        self.documents.selection["routeNodes"][0]["physicalPlaceRef"] = new_ref
        self.assert_invalid("physicalPlaceCorrection")

    def test_legacy_attribution_policy_must_reference_an_actual_binding_change(self) -> None:
        self.documents.selection["reviewPolicy"]["legacyAttributionCorrections"] = [1]
        self.assert_invalid("legacyAttributionCorrections")

    def test_immutable_history_and_replay_inventory_is_exact(self) -> None:
        self.documents.migration["referenceInventory"]["immutableAudit"] = ["history"]
        self.assert_invalid("immutable")

    def test_scenario_resources_rewrite_must_be_reseed(self) -> None:
        documents = real_documents()
        documents.migration["rewriteSurfaces"]["scenarioResources"]["disposition"] = "REWRITE"

        with self.assertRaisesRegex(MODULE.SelectionContractError, "rewriteSurfaces"):
            MODULE.validate_documents(documents)

    def test_scenario_resources_unknown_bindings_must_block(self) -> None:
        documents = real_documents()
        documents.migration["rewriteSurfaces"]["scenarioResources"]["otherwise"] = "BEST_EFFORT"

        with self.assertRaisesRegex(MODULE.SelectionContractError, "rewriteSurfaces"):
            MODULE.validate_documents(documents)

    def test_immutable_audit_records_must_never_be_rewritten(self) -> None:
        documents = real_documents()
        documents.migration["rewriteSurfaces"]["immutableAudit"] = {"disposition": "REWRITE"}

        with self.assertRaisesRegex(MODULE.SelectionContractError, "rewriteSurfaces"):
            MODULE.validate_documents(documents)

    def test_rewrite_surfaces_reject_an_extra_untyped_surface(self) -> None:
        documents = real_documents()
        documents.migration["rewriteSurfaces"]["unknown"] = {"disposition": "REWRITE"}

        with self.assertRaisesRegex(MODULE.SelectionContractError, "rewriteSurfaces"):
            MODULE.validate_documents(documents)

    def test_rewrite_surfaces_reject_a_missing_surface(self) -> None:
        documents = real_documents()
        documents.migration["rewriteSurfaces"].pop("scenarioResources")

        with self.assertRaisesRegex(MODULE.SelectionContractError, "rewriteSurfaces"):
            MODULE.validate_documents(documents)

    def test_forbidden_canonical_name_is_enforced_from_embedded_policy(self) -> None:
        documents = real_documents()
        name = documents.selection["routeNodes"][0]["canonicalName"]
        documents.selection["reviewPolicy"]["forbiddenSelections"] = forbidden_selections()
        documents.selection["reviewPolicy"]["forbiddenSelections"]["canonicalNames"].append(name)

        with self.assertRaisesRegex(MODULE.SelectionContractError, "forbidden"):
            MODULE.validate_documents(documents)

    def test_forbidden_physical_x060_is_rejected(self) -> None:
        documents = real_documents()
        node = documents.selection["routeNodes"][0]
        legacy_id = node["legacyCityId"]
        unit_id = node["administrativeUnitId"]
        node["physicalPlaceRef"] = "external:v1:X060"
        candidate = next(
            row for row in documents.candidate["candidates"]
            if row.get("origin") == "CURRENT_780" and row.get("legacyCityId") == legacy_id
        )
        candidate["physicalPlaceRef"] = "external:v1:X060"
        overlay = next(
            row for row in documents.overlay["administrativeUnits"]
            if row["administrativeUnitId"] == unit_id
        )
        overlay["selectedCandidate"]["physicalPlaceId"] = "external:v1:X060"
        documents.selection["reviewPolicy"]["forbiddenSelections"] = forbidden_selections()

        with self.assertRaisesRegex(MODULE.SelectionContractError, "forbidden"):
            MODULE.validate_documents(documents)

    def test_hhs_ailao_administrative_unit_is_not_name_blacklisted(self) -> None:
        documents = real_documents()
        node = next(
            row for row in documents.selection["routeNodes"]
            if row["administrativeUnitId"] == "hhs:113:永昌郡:007"
        )

        self.assertEqual("哀牢", node["canonicalName"])
        MODULE.validate_documents(documents)

    def test_ninth_unused_location_claim_is_rejected(self) -> None:
        documents = real_documents()
        extra = json.loads(json.dumps(documents.external_claims["claims"][0], ensure_ascii=False))
        extra["sourceClaimId"] = "han-location-claim-v1-unused"
        extra["subjectKey"] = "hhs:113:永昌郡:007"
        extra["canonicalName"] = "哀牢"
        extra["locationResolution"]["physicalPlaceId"] = "external:v1:X999"
        documents.external_claims["claims"].append(extra)

        with self.assertRaisesRegex(MODULE.SelectionContractError, "exactly 8|unused"):
            MODULE.validate_documents(documents)

    def test_location_claim_source_snapshot_hash_is_verified(self) -> None:
        documents = real_documents()
        documents.external_claims["claims"][0]["sourceRecords"][0]["snapshotSha256"] = "0" * 64

        with self.assertRaisesRegex(MODULE.SelectionContractError, "source record"):
            MODULE.validate_documents(documents)

    def test_location_claim_source_line_range_is_verified(self) -> None:
        documents = real_documents()
        documents.external_claims["claims"][0]["sourceRecords"][0]["lineStart"] = 999999
        documents.external_claims["claims"][0]["sourceRecords"][0]["lineEnd"] = 999999

        with self.assertRaisesRegex(MODULE.SelectionContractError, "source record"):
            MODULE.validate_documents(documents)

    def test_location_claim_source_verbatim_is_verified(self) -> None:
        documents = real_documents()
        documents.external_claims["claims"][0]["sourceRecords"][0]["verbatim"] = "날조된 인용"

        with self.assertRaisesRegex(MODULE.SelectionContractError, "source record"):
            MODULE.validate_documents(documents)

    def test_unknown_payloads_are_rejected(self) -> None:
        self.documents.migration["referenceInventory"]["unknownPayloadPolicy"] = "BEST_EFFORT"
        self.assert_invalid("REJECT_UNKNOWN")

    def test_in_place_rewrite_is_rejected(self) -> None:
        self.documents.migration["referenceInventory"]["inPlaceRewrite"] = True
        self.assert_invalid("inPlaceRewrite")

    def test_selection_hash_reference_must_match(self) -> None:
        self.documents.migration["sourceSelectionSha256"] = "0" * 64
        self.assert_invalid("selection hash")

    def test_every_declared_provenance_hash_reference_must_match(self) -> None:
        self.documents.selection["provenance"]["inputs"]["candidate"]["sha256"] = "0" * 64
        self.assert_invalid("candidate hash")
        for name in (
            "legacyHanMap", "legacyTileMap", "locationAdjudications", "reviewPolicy",
            "routeNodeKeyRegistry",
        ):
            with self.subTest(name=name):
                self.documents = real_documents()
                self.documents.selection["provenance"]["inputs"][name]["sha256"] = "0" * 64
                self.assert_invalid(f"{name} hash reference does not match")
        self.documents = valid_documents()
        self.documents.selection["provenance"]["inputs"]["undeclaredInput"] = {"sha256": "0" * 64}
        self.assert_invalid("selection provenance inputs contains unknown fields")

    def test_all_input_schema_versions_are_fail_closed(self) -> None:
        self.documents.selection["schemaVersion"] = 2
        self.assert_invalid("schemaVersion")

    def test_boolean_schema_versions_are_rejected_for_every_core_artifact(self) -> None:
        for attribute in (
            "candidate", "catalog", "overlay", "external_claims", "selection",
            "migration", "route_key_registry",
        ):
            with self.subTest(attribute=attribute):
                documents = valid_documents()
                getattr(documents, attribute)["schemaVersion"] = True
                if attribute == "selection":
                    documents = replace(documents, selection_sha256="6" * 64)
                    documents.migration["sourceSelectionSha256"] = "6" * 64
                with self.assertRaisesRegex(
                    MODULE.SelectionContractError, "schemaVersion must be exact integer 1"
                ):
                    MODULE.validate_documents(documents)

    def test_boolean_candidate_connections_schema_version_is_rejected(self) -> None:
        connections = self._connections()
        connections["schemaVersion"] = True
        documents = replace(
            self.documents,
            connections=connections,
            connections_sha256="7" * 64,
        )

        with self.assertRaisesRegex(
            MODULE.SelectionContractError,
            "candidate connections schemaVersion must be exact integer 1",
        ):
            MODULE.validate_documents(documents)

    def test_overlay_catalog_identity_must_match_catalog(self) -> None:
        self.documents.overlay["catalogId"] = "different-catalog"
        self.assert_invalid("catalogId")

    def test_overlay_identity_must_exact_match_its_administrative_unit_id(self) -> None:
        self.documents.overlay["administrativeUnits"][0]["identity"]["ordinal"] = 2
        self.assert_invalid("overlay identity does not exact-match")

    def test_full_catalog_counts_and_citation_hashes_are_validated(self) -> None:
        self.documents = real_documents()
        self.documents.catalog["detectedGroupCount"] = 104
        self.assert_invalid("declared group counts")

        self.documents = real_documents()
        self.documents.catalog["groups"][0]["units"][0]["sourceCitation"][
            "snapshotSha256"
        ] = "not-a-hash"
        self.assert_invalid("snapshotSha256 is malformed")

    def test_catalog_mismatch_inventory_must_be_an_array(self) -> None:
        self.documents = real_documents()
        self.documents.catalog["declaredVsEnumeratedMismatches"] = {"not": "an array"}
        self.assert_invalid("declaredVsEnumeratedMismatches must be an array")

    def test_catalog_citations_require_complete_typed_variant_fields(self) -> None:
        mutations = (
            lambda catalog: catalog["groups"][0]["sourceCitation"].pop("corpusPath"),
            lambda catalog: catalog["groups"][0]["units"][0]["sourceCitation"].update(
                {"line": "18"}
            ),
            lambda catalog: catalog["groups"][0]["traditionalTextCitation"].pop(
                "localWitness"
            ),
            lambda catalog: next(
                unit
                for group in catalog["groups"]
                for unit in group["units"]
                if "sourceNameIssue" in unit
            )["sourceNameIssue"]["traditionalTextCitation"].update({"endLine": 0}),
        )
        for index, mutate in enumerate(mutations):
            with self.subTest(index=index):
                self.documents = real_documents()
                mutate(self.documents.catalog)
                self.assert_invalid("fields must be exact|must be an integer|endLine")

    def test_production_catalog_requires_exactly_1180_rows(self) -> None:
        self.documents = real_documents()
        group = self.documents.catalog["groups"][-1]
        removed = group["units"].pop()
        group["declaredCities"] -= 1
        group["enumeratedUnits"] -= 1
        self.documents.catalog["expectedUnitCount"] = 1179
        self.documents.catalog["detectedUnitCount"] = 1179
        self.documents.catalog["unitTypeCounts"][removed["unitType"]] -= 1
        self.assert_invalid("declared unit counts")

    def test_review_policy_inputs_exact_match_loaded_artifacts_and_paths(self) -> None:
        documents = real_documents()
        policy = json.loads(
            (ROOT / MODULE.PROVENANCE_DEPENDENCIES["reviewPolicy"]).read_text(encoding="utf-8")
        )
        mutations = (
            ("administrativeCatalogSha256", None),
            ("coordinateOverlaySha256", None),
            ("candidateManifest", "sha256"),
            ("locationClaims", "sha256"),
            ("locationAdjudications", "sha256"),
            ("routeNodeKeyRegistry", "sha256"),
            ("candidateManifest", "path"),
        )
        for name, child in mutations:
            with self.subTest(name=name, child=child):
                mutated = json.loads(json.dumps(policy))
                if child is None:
                    mutated["inputs"][name] = "0" * 64
                elif child == "path":
                    mutated["inputs"][name][child] = "../../escape.json"
                else:
                    mutated["inputs"][name][child] = "0" * 64
                with self.assertRaises(MODULE.SelectionContractError):
                    MODULE._validate_review_policy_inputs(documents, mutated)

    def test_production_catalog_bytes_have_an_independent_anchor(self) -> None:
        documents = real_documents()
        documents.catalog["groups"][0]["units"][0]["sourceName"] = "forged source name"
        forged_digest = "8" * 64
        documents.selection["provenance"]["inputs"]["administrativeCatalog"]["sha256"] = (
            forged_digest
        )
        documents = replace(documents, catalog_sha256=forged_digest)
        with self.assertRaisesRegex(
            MODULE.SelectionContractError, "independent administrative-catalog anchor"
        ):
            MODULE.validate_documents(documents)

    def test_production_candidate_fingerprints_have_an_independent_anchor(self) -> None:
        documents = real_documents()
        forged_fingerprint = "7" * 64
        documents.candidate["candidates"][0]["legacyNodeFingerprint"] = forged_fingerprint
        documents.selection["routeNodes"][0]["legacyNodeFingerprint"] = forged_fingerprint
        documents.migration["rows"][0]["oldNodeFingerprint"] = forged_fingerprint
        forged_digest = "9" * 64
        documents.selection["provenance"]["inputs"]["candidate"]["sha256"] = forged_digest
        documents.migration["sourceCandidateSha256"] = forged_digest
        documents = replace(documents, candidate_sha256=forged_digest)
        with self.assertRaisesRegex(
            MODULE.SelectionContractError, "independent reviewed-candidate anchor"
        ):
            MODULE.validate_documents(documents)

    def test_review_policy_requires_approved_expected_identity(self) -> None:
        documents = real_documents()
        policy = json.loads(
            (ROOT / MODULE.PROVENANCE_DEPENDENCIES["reviewPolicy"]).read_text(encoding="utf-8")
        )
        for field, value in (("status", "DRAFT"), ("policyId", "forged-policy")):
            with self.subTest(field=field):
                mutated = json.loads(json.dumps(policy))
                mutated[field] = value
                with self.assertRaisesRegex(
                    MODULE.SelectionContractError, "approved W0-C review policy"
                ):
                    MODULE._validate_review_policy_inputs(documents, mutated)

    def test_embedded_review_policy_identity_is_canonical_after_coherent_rehash(self) -> None:
        documents = real_documents()
        documents.selection["reviewPolicy"]["policyId"] = "forged-policy"
        documents = replace(documents, selection_sha256="4" * 64)
        documents.migration["sourceSelectionSha256"] = "4" * 64

        with self.assertRaisesRegex(
            MODULE.SelectionContractError, "embedded review policy identity"
        ):
            MODULE.validate_documents(documents)

    def test_selection_provenance_generator_is_canonical_after_coherent_rehash(self) -> None:
        documents = real_documents()
        documents.selection["provenance"]["generator"] = "tools/forged_generator.py"
        documents = replace(documents, selection_sha256="5" * 64)
        documents.migration["sourceSelectionSha256"] = "5" * 64

        with self.assertRaisesRegex(
            MODULE.SelectionContractError, "selection provenance generator"
        ):
            MODULE.validate_documents(documents)

    def test_candidate_provenance_exact_matches_loaded_and_pinned_inputs(self) -> None:
        mutations = (
            ("administrativeCatalog", "path", "data/curated/han/other.json"),
            ("administrativePlaceOverlay", "sha256", "0" * 64),
            ("legacyHanMap", "sha256", "1" * 64),
            ("legacyTileMap", "path", "data/map/other.json"),
        )
        for name, field, value in mutations:
            with self.subTest(name=name, field=field):
                documents = real_documents()
                documents.candidate["provenance"]["inputs"][name][field] = value
                with self.assertRaisesRegex(
                    MODULE.SelectionContractError, "candidate provenance"
                ):
                    MODULE.validate_documents(documents)

    def test_coordinated_review_policy_repin_cannot_replace_production_anchor(self) -> None:
        documents = real_documents()
        policy = json.loads(
            (ROOT / MODULE.PROVENANCE_DEPENDENCIES["reviewPolicy"]).read_text(encoding="utf-8")
        )
        policy["inputs"]["coordinateOverlaySha256"] = "0" * 64
        with tempfile.TemporaryDirectory() as raw_directory:
            source_root = Path(raw_directory)
            witness = source_root / "data/curated/han/route-node-source-witness-v1.json"
            witness.parent.mkdir(parents=True)
            witness.write_bytes(
                (ROOT / "data/curated/han/route-node-source-witness-v1.json").read_bytes()
            )
            policy_path = source_root / MODULE.PROVENANCE_DEPENDENCIES["reviewPolicy"]
            policy_path.write_text(json.dumps(policy, ensure_ascii=False), encoding="utf-8")
            documents = replace(documents, source_root=source_root)
            documents.selection["provenance"]["inputs"]["reviewPolicy"]["sha256"] = (
                hashlib.sha256(policy_path.read_bytes()).hexdigest()
            )
            with self.assertRaisesRegex(
                MODULE.SelectionContractError, "independent review-policy anchor"
            ):
                MODULE.validate_documents(documents)

    def test_production_validation_contract_bytes_are_independently_pinned(self) -> None:
        documents = real_documents()
        with tempfile.TemporaryDirectory() as raw_directory:
            contract = Path(raw_directory) / "contract.json"
            mutated = json.loads(MODULE.VALIDATION_CONTRACT_PATH.read_text(encoding="utf-8"))
            mutated["expectedSelectionCount"] = 779
            contract.write_text(json.dumps(mutated), encoding="utf-8")
            with patch.object(MODULE, "VALIDATION_CONTRACT_PATH", contract):
                with self.assertRaisesRegex(
                    MODULE.SelectionContractError, "independent validation-contract anchor"
                ):
                    MODULE.validate_documents(documents)

    def test_candidate_classification_must_be_known(self) -> None:
        self.documents.candidate["candidates"][0]["classification"] = "AUTO_APPROVED"
        self.assert_invalid("classification")

    def test_candidate_fingerprint_must_be_canonical_sha256(self) -> None:
        self.documents.candidate["candidates"][0]["legacyNodeFingerprint"] = "not-a-fingerprint"
        self.assert_invalid("fingerprint")

    def test_resolved_candidate_requires_its_proposed_hhs_identity(self) -> None:
        self.documents.candidate["candidates"][0].pop("proposedAdministrativeUnitId")
        self.assert_invalid("HHS_RESOLVED")

    def test_resolved_hhs_node_rejects_a_dangling_location_claim(self) -> None:
        self.documents.selection["routeNodes"][0]["locationClaimId"] = "claim:missing"
        self.assert_invalid("locationClaimId")

    def test_malformed_summary_fails_as_a_typed_contract_error(self) -> None:
        self.documents.selection["summary"] = "malformed"
        self.assert_invalid("summary")

    def test_malformed_mutable_inventory_fails_as_a_typed_contract_error(self) -> None:
        self.documents.migration["referenceInventory"]["mutable"][0] = {"unexpected": "object"}
        self.assert_invalid("mutable reference inventory")

    def test_attribution_conflict_requires_row_level_disposition(self) -> None:
        candidate = self.documents.candidate["candidates"][0]
        candidate["classification"] = "HHS_ATTRIBUTION_CONFLICT"
        candidate.pop("proposedAdministrativeUnitId")
        candidate["incompatibleAdministrativeUnitIds"] = [admin_id(1)]
        self.assert_invalid("historicalConflictDisposition")

    def test_migration_chain_cannot_cross_two_legacy_rows(self) -> None:
        first, second = self.documents.migration["rows"][:2]
        first["routeNodeKey"], second["routeNodeKey"] = second["routeNodeKey"], first["routeNodeKey"]
        first["newCityId"], second["newCityId"] = second["newCityId"], first["newCityId"]
        self.assert_invalid("newCityId.*oldCityId|oldCityId/fingerprint/routeNodeKey")

        self.documents = valid_documents()
        first, second = self.documents.migration["rows"][:2]
        first["newCityId"], second["newCityId"] = second["newCityId"], first["newCityId"]
        first_node, second_node = self.documents.selection["routeNodes"][:2]
        first_node["numericCityId"], second_node["numericCityId"] = (
            second_node["numericCityId"], first_node["numericCityId"]
        )
        self.documents.migration["summary"]["numericCityIdChangeCount"] = 2
        self.assert_invalid("numericCityIdChangeAllowed|newCityId.*oldCityId")

    def test_route_keys_cannot_be_coordinately_swapped_across_selection_and_migration(self) -> None:
        first_node, second_node = self.documents.selection["routeNodes"][:2]
        first_row, second_row = self.documents.migration["rows"][:2]
        first_node["routeNodeKey"], second_node["routeNodeKey"] = (
            second_node["routeNodeKey"], first_node["routeNodeKey"]
        )
        first_row["routeNodeKey"], second_row["routeNodeKey"] = (
            second_row["routeNodeKey"], first_row["routeNodeKey"]
        )
        self.assert_invalid("route-node key registry")

    def test_three_artifact_route_key_swap_cannot_repin_the_stable_id_anchor(self) -> None:
        first, second = self.documents.selection["routeNodes"][:2]
        first["routeNodeKey"], second["routeNodeKey"] = second["routeNodeKey"], first["routeNodeKey"]
        rows = self.documents.migration["rows"][:2]
        rows[0]["routeNodeKey"], rows[1]["routeNodeKey"] = rows[1]["routeNodeKey"], rows[0]["routeNodeKey"]
        registry = self.documents.route_key_registry["keys"][:2]
        registry[0]["routeNodeKey"], registry[1]["routeNodeKey"] = (
            registry[1]["routeNodeKey"], registry[0]["routeNodeKey"]
        )
        self.documents = replace(
            self.documents,
            catalog_sha256=MODULE.PINNED_ADMINISTRATIVE_CATALOG_SHA256,
            candidate_sha256=MODULE.PINNED_REVIEWED_CANDIDATE_SHA256,
            route_key_registry_sha256="0" * 64,
            production_approval_mode=True,
            source_root=ROOT,
        )

        self.assert_invalid("independent stable-ID anchor")

    def test_route_key_registry_document_contract_is_fail_closed(self) -> None:
        mutations = (
            (lambda registry: registry.__setitem__("schemaVersion", 2), "schemaVersion"),
            (lambda registry: registry["keys"].pop(), "exactly 780 rows"),
            (
                lambda registry: registry["keys"].__setitem__(
                    1, dict(registry["keys"][0])
                ),
                "identities and keys must be unique",
            ),
        )
        for mutate, pattern in mutations:
            with self.subTest(pattern=pattern):
                self.documents = valid_documents()
                mutate(self.documents.route_key_registry)
                self.assert_invalid(pattern)

    def test_route_key_registry_boolean_schema_version_is_rejected_at_index_boundary(self) -> None:
        registry = json.loads(json.dumps(self.documents.route_key_registry))
        registry["schemaVersion"] = True

        with self.assertRaisesRegex(
            MODULE.SelectionContractError,
            "route-node key registry schemaVersion must be exact integer 1",
        ):
            MODULE._route_key_registry_index(registry)

    def test_core_artifact_unknown_fields_fail_closed_at_each_nested_boundary(self) -> None:
        mutations = (
            lambda d: d.candidate.__setitem__("unknown", True),
            lambda d: d.candidate["candidatePolicy"].__setitem__("unknown", True),
            lambda d: d.candidate["summary"].__setitem__("unknown", True),
            lambda d: d.candidate["summary"].__setitem__("classificationCounts", {"unknown": 1}),
            lambda d: d.candidate["candidates"][0].__setitem__("unknown", True),
            lambda d: d.catalog.__setitem__("source", {"unknown": True}),
            lambda d: d.catalog["groups"][0].__setitem__("unknown", True),
            lambda d: d.catalog["groups"][0]["units"][0].__setitem__("unknown", True),
            lambda d: d.overlay["administrativeUnits"][0].__setitem__("unknown", True),
            lambda d: d.selection.__setitem__("unknown", True),
            lambda d: d.selection["summary"].__setitem__("unknown", True),
            lambda d: d.selection["routeNodes"][0].__setitem__("unknown", True),
            lambda d: d.selection["routeNodes"][0]["selectionRationale"].__setitem__("unknown", True),
            lambda d: d.selection["scenarioCatalog"]["resources"][0].__setitem__("unknown", True),
            lambda d: d.selection["provenance"].__setitem__("unknown", True),
            lambda d: d.migration.__setitem__("unknown", True),
            lambda d: d.migration["summary"].__setitem__("unknown", True),
            lambda d: d.migration["rows"][0].__setitem__("unknown", True),
            lambda d: d.route_key_registry.__setitem__("unknown", True),
            lambda d: d.route_key_registry["keys"][0].__setitem__("unknown", True),
        )
        for index, mutate in enumerate(mutations):
            with self.subTest(index=index):
                self.documents = valid_documents()
                mutate(self.documents)
                self.assert_invalid("unknown fields")

    def test_recursive_production_schema_inventory_rejects_every_unknown_boundary(self) -> None:
        def unknown(value: dict) -> None:
            value["unknown"] = True

        mutations = (
            ("candidate", lambda d: unknown(d.candidate)),
            ("candidatePolicy", lambda d: unknown(d.candidate["candidatePolicy"])),
            ("candidate row", lambda d: unknown(d.candidate["candidates"][0])),
            ("candidate correction", lambda d: unknown(next(row["correctionCandidate"] for row in d.candidate["candidates"] if "correctionCandidate" in row))),
            ("candidate provenance", lambda d: unknown(d.candidate["provenance"])),
            ("candidate provenance inputs", lambda d: unknown(d.candidate["provenance"]["inputs"])),
            ("candidate provenance input", lambda d: unknown(d.candidate["provenance"]["inputs"]["administrativeCatalog"])),
            ("candidate scenario", lambda d: unknown(d.candidate["scenarioCatalog"][0])),
            ("candidate summary", lambda d: unknown(d.candidate["summary"])),
            ("candidate classificationCounts", lambda d: unknown(d.candidate["summary"]["classificationCounts"])),
            ("candidate originCounts", lambda d: unknown(d.candidate["summary"]["originCounts"])),
            ("candidate reviewStateCounts", lambda d: unknown(d.candidate["summary"]["reviewStateCounts"])),
            ("catalog", lambda d: unknown(d.catalog)),
            ("catalog source", lambda d: unknown(d.catalog["source"])),
            ("catalog unitTypeCounts", lambda d: unknown(d.catalog["unitTypeCounts"])),
            ("catalog mismatch", lambda d: unknown(d.catalog["declaredVsEnumeratedMismatches"][0])),
            ("catalog group", lambda d: unknown(d.catalog["groups"][0])),
            ("catalog group citation", lambda d: unknown(d.catalog["groups"][0]["sourceCitation"])),
            ("catalog traditional citation", lambda d: unknown(d.catalog["groups"][0]["traditionalTextCitation"])),
            ("catalog unit", lambda d: unknown(d.catalog["groups"][0]["units"][0])),
            ("catalog unit citation", lambda d: unknown(d.catalog["groups"][0]["units"][0]["sourceCitation"])),
            ("catalog sourceNameIssue", lambda d: unknown(next(unit["sourceNameIssue"] for group in d.catalog["groups"] for unit in group["units"] if "sourceNameIssue" in unit))),
            ("catalog sourceNameIssue citation", lambda d: unknown(next(unit["sourceNameIssue"]["traditionalTextCitation"] for group in d.catalog["groups"] for unit in group["units"] if "sourceNameIssue" in unit))),
            ("catalog nameCorrection", lambda d: unknown(next(unit["nameCorrection"] for group in d.catalog["groups"] for unit in group["units"] if "nameCorrection" in unit))),
            ("catalog correction citation", lambda d: unknown(next(unit["nameCorrection"]["sourceCitation"] for group in d.catalog["groups"] for unit in group["units"] if "nameCorrection" in unit))),
            ("overlay", lambda d: unknown(d.overlay)),
            ("overlay row", lambda d: unknown(d.overlay["administrativeUnits"][0])),
            ("overlay identity", lambda d: unknown(d.overlay["administrativeUnits"][0]["identity"])),
            ("overlay selectedCandidate", lambda d: unknown(next(row["selectedCandidate"] for row in d.overlay["administrativeUnits"] if "selectedCandidate" in row))),
            ("overlay candidate", lambda d: unknown(next(row["candidates"][0] for row in d.overlay["administrativeUnits"] if row.get("candidates")))),
            ("claims", lambda d: unknown(d.external_claims)),
            ("claims policy", lambda d: unknown(d.external_claims["policy"])),
            ("claim row", lambda d: unknown(d.external_claims["claims"][0])),
            ("claim conflict", lambda d: unknown(d.external_claims["claims"][0]["conflictDisposition"])),
            ("claim resolution", lambda d: unknown(d.external_claims["claims"][0]["locationResolution"])),
            ("claim dataset", lambda d: unknown(d.external_claims["claims"][0]["locationResolution"]["coordinateDatasetRef"])),
            ("claim source record", lambda d: unknown(d.external_claims["claims"][0]["sourceRecords"][0])),
            ("selection", lambda d: unknown(d.selection)),
            ("selection provenance", lambda d: unknown(d.selection["provenance"])),
            ("selection provenance inputs", lambda d: unknown(d.selection["provenance"]["inputs"])),
            ("selection provenance input", lambda d: unknown(d.selection["provenance"]["inputs"]["candidate"])),
            ("selection reviewPolicy", lambda d: unknown(d.selection["reviewPolicy"])),
            ("selection forbidden", lambda d: unknown(d.selection["reviewPolicy"]["forbiddenSelections"])),
            ("selection anchors", lambda d: unknown(d.selection["reviewPolicy"]["reviewDecisionAnchors"])),
            ("selection anchor", lambda d: unknown(d.selection["reviewPolicy"]["reviewDecisionAnchors"]["replacementDecisionSet"])),
            ("route node", lambda d: unknown(d.selection["routeNodes"][0])),
            ("location adjudication", lambda d: unknown(d.selection["routeNodes"][0]["locationAdjudication"])),
            ("selection rationale", lambda d: unknown(d.selection["routeNodes"][0]["selectionRationale"])),
            ("replacement disposition", lambda d: unknown(next(row["replacementDisposition"] for row in d.selection["routeNodes"] if "replacementDisposition" in row))),
            ("conflict disposition", lambda d: unknown(next(row["historicalConflictDisposition"] for row in d.selection["routeNodes"] if "historicalConflictDisposition" in row))),
            ("physical correction", lambda d: unknown(next(row["physicalPlaceCorrection"] for row in d.selection["routeNodes"] if "physicalPlaceCorrection" in row))),
            ("scenario catalog", lambda d: unknown(d.selection["scenarioCatalog"])),
            ("scenario resource", lambda d: unknown(d.selection["scenarioCatalog"]["resources"][0])),
            ("selection summary", lambda d: unknown(d.selection["summary"])),
            ("historicalBindingCounts", lambda d: unknown(d.selection["summary"]["historicalBindingCounts"])),
            ("migration", lambda d: unknown(d.migration)),
            ("migration inventory", lambda d: unknown(d.migration["referenceInventory"])),
            ("migration rewriteSurfaces", lambda d: unknown(d.migration["rewriteSurfaces"])),
            ("migration derivedArtifacts", lambda d: unknown(d.migration["rewriteSurfaces"]["derivedArtifacts"])),
            ("migration immutableAudit", lambda d: unknown(d.migration["rewriteSurfaces"]["immutableAudit"])),
            ("migration scenarioResources", lambda d: unknown(d.migration["rewriteSurfaces"]["scenarioResources"])),
            ("migration row", lambda d: unknown(d.migration["rows"][0])),
            ("migration summary", lambda d: unknown(d.migration["summary"])),
            ("registry", lambda d: unknown(d.route_key_registry)),
            ("registry keyPolicy", lambda d: unknown(d.route_key_registry["keyPolicy"])),
            ("registry row", lambda d: unknown(d.route_key_registry["keys"][0])),
        )
        for label, mutate in mutations:
            with self.subTest(boundary=label):
                documents = real_documents()
                mutate(documents)
                with self.assertRaisesRegex(
                    MODULE.SelectionContractError,
                    r"unknown fields|fields must be exact",
                ):
                    MODULE.validate_documents(documents)

    def test_replacement_and_conflict_evidence_refs_resolve_to_reviewed_decisions(self) -> None:
        documents = real_documents()
        replacement = next(
            node for node in documents.selection["routeNodes"]
            if "replacementDisposition" in node
        )
        replacement["replacementDisposition"]["evidenceRefs"] = ["forged:nonempty"]
        with self.assertRaisesRegex(MODULE.SelectionContractError, "replacementDisposition evidenceRefs"):
            MODULE.validate_documents(documents)

        documents = real_documents()
        conflict = next(
            node for node in documents.selection["routeNodes"]
            if "historicalConflictDisposition" in node
        )
        conflict["historicalConflictDisposition"]["evidenceRefs"] = ["forged:nonempty"]
        with self.assertRaisesRegex(MODULE.SelectionContractError, "historicalConflictDisposition evidenceRefs"):
            MODULE.validate_documents(documents)

    def test_coordinated_repin_cannot_replace_the_production_decision_anchor(self) -> None:
        documents = real_documents()
        node = next(
            row for row in documents.selection["routeNodes"]
            if row.get("legacyDisposition") == "REPLACED"
        )
        node["physicalPlaceRef"] = "curated:forged-place"
        decisions = [
            {key: row[key] for key in ("legacyCityId", "administrativeUnitId", "physicalPlaceRef", "routeNodeKey")}
            for row in documents.selection["routeNodes"]
            if row.get("legacyDisposition") == "REPLACED"
        ]
        forged_digest = MODULE._decision_digest(decisions)
        anchor = documents.selection["reviewPolicy"]["reviewDecisionAnchors"]["replacementDecisionSet"]
        anchor["assignmentSha256"] = forged_digest
        anchor["anchor"] = f"replacementDecisionSet:{forged_digest}"
        for row in documents.selection["routeNodes"]:
            if "replacementDisposition" in row:
                row["replacementDisposition"]["evidenceRefs"] = [
                    MODULE.REVIEW_POLICY_PATH,
                    anchor["anchor"],
                ]

        with self.assertRaisesRegex(MODULE.SelectionContractError, "independent production anchor"):
            MODULE._validate_review_decision_anchors(
                documents.selection["routeNodes"],
                documents.selection["reviewPolicy"],
                production=True,
            )

    def test_adjudication_evidence_and_proximity_language_are_fail_closed(self) -> None:
        self._make_ambiguous()
        self.documents.selection["routeNodes"][0]["locationAdjudication"]["evidenceRefs"] = [
            "forged:review"
        ]
        self.assert_invalid("locationAdjudication evidenceRefs")

        self.documents = valid_documents()
        self.documents.selection["routeNodes"][0]["selectionRationale"]["rationale"] = (
            "selected by geographic proximity to a nearby place"
        )
        self.assert_invalid("proximity|nearby")

    def test_route_node_physical_place_reference_must_have_a_typed_scheme(self) -> None:
        self.documents.selection["routeNodes"][0]["physicalPlaceRef"] = "malformed-place"
        self.assert_invalid("valid point reference")

    def test_connection_graph_lifecycle_is_candidate_only(self) -> None:
        self.documents = replace(self.documents, connections=self._connections())
        self.documents.connections["lifecycle"] = "APPROVED"
        self.assert_invalid("CANDIDATE_ONLY")

    def test_connection_graph_rejects_unknown_top_level_and_edge_fields(self) -> None:
        for location in ("top", "edge"):
            with self.subTest(location=location):
                connections = self._connections()
                if location == "top":
                    connections["runtimeApproved"] = True
                else:
                    connections["connections"][0]["runtimeWeight"] = 1
                self.documents = replace(self.documents, connections=connections)
                self.assert_invalid("unknown fields")

    def test_connection_graph_rejects_self_edges(self) -> None:
        self.documents = replace(self.documents, connections=self._connections())
        self.documents.connections["connections"].append(
            {"fromRouteNodeKey": route_key(1), "toRouteNodeKey": route_key(1)}
        )
        self.assert_invalid("self")

    def test_connection_graph_rejects_dangling_edges(self) -> None:
        self.documents = replace(self.documents, connections=self._connections())
        self.documents.connections["connections"].append(
            {"fromRouteNodeKey": route_key(1), "toRouteNodeKey": route_key(999)}
        )
        self.assert_invalid("dangling")

    def test_connection_graph_requires_symmetric_edges(self) -> None:
        self.documents = replace(self.documents, connections=self._connections())
        self.documents.connections["connections"].pop()
        self.assert_invalid("asymmetric")

    def test_cli_validates_real_fixture_files(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            paths = self._write_fixture(Path(raw_directory))

            result = subprocess.run(
                [sys.executable, str(MODULE_PATH), *paths],
                cwd=ROOT,
                capture_output=True,
                text=True,
                check=False,
            )

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("synthetic/internal-consistency only", result.stdout)
        self.assertNotIn("approved", result.stdout)
        self.assertIn("consistentNodes=780", result.stdout)
        self.assertIn("scenarios=15", result.stdout)

    def test_cli_connections_input_is_hash_pinned_and_validated(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            directory = Path(raw_directory)
            paths = self._write_fixture(directory)
            connections = directory / "connections.json"
            connections.write_text(
                json.dumps(self._connections(), ensure_ascii=False), encoding="utf-8"
            )
            selection_path = Path(paths[paths.index("--selection") + 1])
            migration_path = Path(paths[paths.index("--migration") + 1])
            selection = json.loads(selection_path.read_text(encoding="utf-8"))
            selection["provenance"]["inputs"]["candidateConnections"] = {
                "sha256": digest(connections.read_bytes())
            }
            selection_path.write_text(json.dumps(selection, ensure_ascii=False), encoding="utf-8")
            migration = json.loads(migration_path.read_text(encoding="utf-8"))
            migration["sourceSelectionSha256"] = digest(selection_path.read_bytes())
            migration_path.write_text(json.dumps(migration, ensure_ascii=False), encoding="utf-8")

            result = subprocess.run(
                [sys.executable, str(MODULE_PATH), *paths, "--connections", str(connections)],
                cwd=ROOT,
                capture_output=True,
                text=True,
                check=False,
            )

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("consistentNodes=780", result.stdout)

    def test_default_cli_is_the_only_production_approval_mode(self) -> None:
        result = subprocess.run(
            [sys.executable, str(MODULE_PATH)], cwd=ROOT, capture_output=True, text=True, check=False,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("approved production manifest", result.stdout)
        self.assertIn("curated provenance snapshot validated", result.stdout)
        self.assertIn("live corpus refresh not claimed", result.stdout)
        with tempfile.TemporaryDirectory() as raw_directory:
            alias_root = Path(raw_directory)
            aliases = {}
            for field, target in {
                "candidate": MODULE.DEFAULT_CANDIDATE,
                "catalog": MODULE.DEFAULT_CATALOG,
                "overlay": MODULE.DEFAULT_OVERLAY,
                "external_claims": MODULE.DEFAULT_CLAIMS,
                "selection": MODULE.DEFAULT_SELECTION,
                "migration": MODULE.DEFAULT_MIGRATION,
                "scenarios_dir": MODULE.DEFAULT_SCENARIOS,
                "source_root": ROOT,
            }.items():
                alias = alias_root / field
                alias.symlink_to(target, target_is_directory=target.is_dir())
                aliases[field] = alias

            documents = MODULE.load_documents(MODULE.ValidationPaths(**aliases, connections=None))

        self.assertFalse(documents.production_approval_mode)

    def test_validation_contract_is_independently_hash_pinned(self) -> None:
        self.assertEqual(
            "83c11fc237a6f03f8699a97f56326a9a6dc65990c6460482b024e7a0ee3bef66",
            hashlib.sha256(MODULE.VALIDATION_CONTRACT_PATH.read_bytes()).hexdigest(),
        )

    def test_source_witness_ledger_is_independently_hash_pinned(self) -> None:
        witness = ROOT / "data/curated/han/route-node-source-witness-v1.json"
        self.assertEqual(
            MODULE.PINNED_SOURCE_WITNESS_SHA256,
            hashlib.sha256(witness.read_bytes()).hexdigest(),
        )

    def test_external_authority_symlink_cannot_escape_source_root_data(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            source_root = Path(raw_directory)
            (source_root / "data").mkdir()
            (source_root / "data/escape.json").symlink_to(ROOT / "data/curated/han/route-node-external-place-authority-v1.json")
            self.documents.external_claims["claims"][0]["locationResolution"]["coordinateDatasetRef"]["datasetPath"] = "data/escape.json"
            self.documents = replace(self.documents, source_root=source_root)
            self.assert_invalid("escapes source-root data/")
        with tempfile.TemporaryDirectory() as raw_directory:
            source_root = Path(raw_directory)
            (source_root / "data").symlink_to(ROOT / "data", target_is_directory=True)
            self.documents = valid_documents()
            self.documents = replace(self.documents, source_root=source_root)
            self.assert_invalid("source-root data/ escapes repository root")

    def test_custom_source_root_cannot_fallback_to_production_witness(self) -> None:
        record = self.documents.external_claims["claims"][0]["sourceRecords"][0]
        with tempfile.TemporaryDirectory() as raw_directory:
            with self.assertRaisesRegex(MODULE.SelectionContractError, "no source-root witness ledger"):
                MODULE._verify_source_record(record, "fixture-claim", Path(raw_directory))
        with tempfile.TemporaryDirectory() as raw_directory:
            source_root = Path(raw_directory)
            witness = source_root / "data/curated/han/route-node-source-witness-v1.json"
            witness.parent.mkdir(parents=True)
            witness.symlink_to(ROOT / "data/curated/han/route-node-source-witness-v1.json")
            with self.assertRaisesRegex(MODULE.SelectionContractError, "witness ledger escapes source-root data/"):
                MODULE._verify_source_record(record, "fixture-claim", source_root)

    def test_corpus_symlink_cannot_escape_to_another_data_subtree(self) -> None:
        record = dict(self.documents.external_claims["claims"][0]["sourceRecords"][0])
        with tempfile.TemporaryDirectory() as raw_directory:
            source_root = Path(raw_directory).resolve()
            corpus = source_root / "data/corpus"
            corpus.mkdir(parents=True)
            outside = source_root / "data/other-source.txt"
            outside.write_text("fixture source record\n", encoding="utf-8")
            (corpus / "source.txt").symlink_to(outside)
            with self.assertRaisesRegex(
                MODULE.SelectionContractError, "escapes source-root data/corpus"
            ):
                MODULE._verify_source_record(record, "fixture-claim", source_root)

    def _make_ambiguous(self) -> None:
        overlay = self.documents.overlay["administrativeUnits"][0]
        overlay["joinStatus"] = "AMBIGUOUS_POINT"
        overlay["candidateCount"] = 2
        selected = overlay.pop("selectedCandidate")
        overlay["candidates"] = [selected, {"physicalPlaceId": "chgis:v6:cnty:alternate"}]
        candidate = self.documents.candidate["candidates"][0]
        candidate["classification"] = "HHS_AMBIGUOUS"
        candidate.pop("proposedAdministrativeUnitId")
        candidate["candidateAdministrativeUnitIds"] = [admin_id(1)]
        self.documents.selection["routeNodes"][0]["locationAdjudication"] = {
            "selectedPhysicalPlaceRef": physical_id(1),
            "rejectedPhysicalPlaceRefs": ["chgis:v6:cnty:alternate"],
            "rationaleCode": "GROUP_GEOGRAPHY_AND_TEMPORAL_RECORD_REVIEW",
            "rationale": "textual source agreement",
            "evidenceRefs": [
                "data/curated/han/administrative-place-bindings-v1.json",
                "docs/superpowers/reviews/2026-08-22-w0b-administrative-place-overlay-review.md",
            ],
        }
        self.documents.selection["routeNodes"][0]["selectionRationale"]["batchId"] = (
            "w0c-reviewed-ambiguity"
        )
        self.documents.selection["routeNodes"][0]["selectionRationale"]["evidenceRefs"] = [
            MODULE.REVIEW_POLICY_PATH,
            "w0c-reviewed-ambiguity",
        ]

    def _bind_external_type(self, subject_type: str) -> None:
        claim = self.documents.external_claims["claims"][0]
        claim["subjectType"] = subject_type

    def _connections(self) -> MODULE.JsonObject:
        return {
            "schemaVersion": 1,
            "lifecycle": "CANDIDATE_ONLY",
            "connections": [
                {"fromRouteNodeKey": route_key(1), "toRouteNodeKey": route_key(2)},
                {"fromRouteNodeKey": route_key(2), "toRouteNodeKey": route_key(1)},
            ],
        }

    def _write_fixture(self, directory: Path) -> list[str]:
        source_root = directory / "repository"
        fixture_root = ROOT / "tools/scenario/tests/fixtures/repository"
        (source_root / "data/corpus").mkdir(parents=True)
        (source_root / "data/curated/han").mkdir(parents=True)
        (source_root / "data/authority.json").write_bytes(
            (fixture_root / "data/authority.json").read_bytes()
        )
        (source_root / "data/corpus/source.txt").write_bytes(
            (fixture_root / "data/corpus/source.txt").read_bytes()
        )
        registry_path = source_root / "data/curated/han/route-node-key-registry-v1.json"
        registry_path.write_text(
            json.dumps(self.documents.route_key_registry, ensure_ascii=False), encoding="utf-8"
        )
        self.documents.selection["provenance"]["inputs"]["routeNodeKeyRegistry"] = {
            "sha256": digest(registry_path.read_bytes())
        }
        scenarios = directory / "scenarios"
        scenarios.mkdir()
        scenario_rows = []
        for resource in self.documents.scenarios:
            path = scenarios / resource.resource_name
            payload = json.dumps(
                {"startYear": resource.start_year, "map": {"mapName": "han"}},
                separators=(",", ":"),
            ).encode()
            path.write_bytes(payload)
            scenario_rows.append(
                {
                    "scenarioId": resource.scenario_id,
                    "resourceName": resource.resource_name,
                    "resourcePath": resource.resource_path,
                    "startYear": resource.start_year,
                    "sha256": digest(payload),
                }
            )
        self.documents.selection["scenarioCatalog"]["resources"] = scenario_rows
        self.documents.candidate["scenarioCatalog"] = [
            {
                "code": row["scenarioId"],
                "resourcePath": row["resourcePath"],
                "resourceSha256": row["sha256"],
                "startYear": row["startYear"],
            }
            for row in scenario_rows
        ]
        files = {
            "candidate": self.documents.candidate,
            "catalog": self.documents.catalog,
            "overlay": self.documents.overlay,
            "claims": self.documents.external_claims,
        }
        paths = {}
        for name, document in files.items():
            path = directory / f"{name}.json"
            path.write_text(json.dumps(document, ensure_ascii=False), encoding="utf-8")
            paths[name] = path
        inputs = self.documents.selection["provenance"]["inputs"]
        inputs["candidate"]["sha256"] = digest(paths["candidate"].read_bytes())
        inputs["administrativeCatalog"]["sha256"] = digest(paths["catalog"].read_bytes())
        inputs["administrativePlaceOverlay"]["sha256"] = digest(paths["overlay"].read_bytes())
        inputs["externalClaims"]["sha256"] = digest(paths["claims"].read_bytes())
        selection = directory / "selection.json"
        selection.write_text(json.dumps(self.documents.selection, ensure_ascii=False), encoding="utf-8")
        migration = directory / "migration.json"
        self.documents.migration["sourceSelectionSha256"] = digest(selection.read_bytes())
        self.documents.migration["sourceCandidateSha256"] = digest(paths["candidate"].read_bytes())
        migration.write_text(json.dumps(self.documents.migration, ensure_ascii=False), encoding="utf-8")
        return [
            "--source-root", str(source_root),
            "--candidate", str(paths["candidate"]),
            "--catalog", str(paths["catalog"]),
            "--overlay", str(paths["overlay"]),
            "--external-claims", str(paths["claims"]),
            "--selection", str(selection),
            "--migration", str(migration),
            "--scenarios-dir", str(scenarios),
        ]


    def test_external_authority_path_is_consumed(self) -> None:
        self.documents.external_claims["claims"][0]["locationResolution"]["coordinateDatasetRef"]["datasetPath"] = "does-not-exist.json"
        self.assert_invalid("path must be repo-relative data/")

    def test_external_authority_hash_is_consumed(self) -> None:
        self.documents.external_claims["claims"][0]["locationResolution"]["coordinateDatasetRef"]["datasetSha256"] = "0" * 64
        self.assert_invalid("authority hash does not match")

    def test_external_authority_record_id_is_consumed(self) -> None:
        self.documents.external_claims["claims"][0]["locationResolution"]["coordinateDatasetRef"]["recordId"] = "DOES_NOT_EXIST"
        self.assert_invalid("recordId does not resolve uniquely")

    def test_external_authority_wikidata_identity_is_consumed(self) -> None:
        self.documents.external_claims["claims"][0]["locationResolution"]["coordinateDatasetRef"]["wikidataId"] = "Q999999999"
        self.assert_invalid("Wikidata identity does not match")

    def test_external_authority_subject_key_is_consumed(self) -> None:
        self.documents.external_claims["claims"][0]["subjectKey"] = "hhs:113:上郡:999"
        self.assert_invalid("subjectKey does not match")

    def test_external_authority_canonical_name_is_consumed(self) -> None:
        self.documents.external_claims["claims"][0]["canonicalName"] = "WRONG"
        self.assert_invalid("canonicalName does not match")


if __name__ == "__main__":
    unittest.main()
