from __future__ import annotations

_SIZE_OK_MARKER = "# noqa: SIZE_OK"

import hashlib
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path

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
            start_year=180 + (index % 46),
            sha256=f"{index + 1:064x}",
        )
        for index in range(31)
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
            "legacyIsSeat": False,
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
    candidate = {
        "schemaVersion": 1,
        "selectionId": "han-route-node-selection-candidates-v1",
        "candidatePolicy": {"reviewState": "PENDING", "automaticSelectionCount": 0},
        "summary": {
            "candidateCount": 1960,
            "legacyNodeCount": COUNT,
            "replacementPoolCount": 1180,
        },
        "candidates": candidates,
    }
    resources = scenario_resources()
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
            "seatRole": "NON_SEAT",
            "physicalPlaceRef": physical_id(index),
            "locationAdjudication": {"kind": "W0B_GLOBAL_UNIQUE_220"},
            "administrativeUnitId": admin_id(index),
            "effectiveFrom": 180,
            "effectiveTo": 225,
            "activeScenarioIds": [resource.scenario_id for resource in resources],
            "scenarioStates": [
                {"scenarioId": resource.scenario_id, "startYear": resource.start_year, "state": "ACTIVE"}
                for resource in resources
            ],
            "reviewState": "APPROVED",
            "selectionRationale": {
                "method": "HISTORICAL_REVIEW",
                "rationale": "source identity and place record agree",
                "evidenceRefs": [f"fixture:evidence:{index}"],
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
                "sourceClaimId": claim_id,
                "reviewState": "APPROVED",
                "claimRole": "LOCATION_ONLY",
                "subjectType": "ADMINISTRATIVE_PLACE",
                "subjectKey": admin_id(index),
                "canonicalName": f"縣{index}",
                "subjectPeriod": {"effectiveFromYear": 180, "effectiveToYear": 225},
                "locationResolution": {"kind": "POINT_REF", "physicalPlaceId": physical},
                "sourceRecords": [
                    {
                        "sourceBook": "後漢書",
                        "volume": 113,
                        "corpusPath": "data/corpus/hhs-113.txt",
                        "lineStart": 1001,
                        "lineEnd": 1001,
                        "snapshotSha256": "55e8450051ffbca24be3b4dbc8661fb2bfd9ac97e4b161a5072d313dda021142",
                        "verbatim": "〖龙编〗",
                    }
                ],
                "conflictDisposition": {"status": "NONE", "rationale": "fixture source record"},
            }
        )
        node = route_nodes[index - 1]
        node["physicalPlaceRef"] = physical
        node["locationClaimId"] = claim_id
        node["locationAdjudication"] = {"kind": "APPROVED_LOCATION_ONLY_CLAIM", "sourceClaimId": claim_id}
    selection = {
        "schemaVersion": 1,
        "selectionId": "han-route-node-selection-v1",
        "reviewState": "APPROVED",
        "runtimeScenarioActivationEnforcement": "NOT_CLAIMED_BY_W0_DATA_CONTRACT",
        "reviewPolicy": {"legacyAttributionCorrections": [], "forbiddenSelections": forbidden_selections()},
        "scenarioCatalog": {
            "resourceCount": 31,
            "resources": [
                {
                    "scenarioId": resource.scenario_id,
                    "resourceName": resource.resource_name,
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
        "summary": {"approvedCount": COUNT},
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
    return MODULE.ValidationDocuments(
        candidate=candidate,
        catalog=catalog,
        overlay=overlay,
        external_claims=claims,
        selection=selection,
        migration=migration,
        scenarios=resources,
        candidate_sha256=HASHES["candidate"],
        catalog_sha256=HASHES["catalog"],
        overlay_sha256=HASHES["overlay"],
        claims_sha256=HASHES["claims"],
        selection_sha256=HASHES["selection"],
        migration_sha256=HASHES["migration"],
        source_root=ROOT,
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
        self.assertEqual(31, report.scenario_count)
        self.assertEqual(HASHES["selection"], report.selection_sha256)
        self.assertEqual(HASHES["migration"], report.migration_sha256)

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
            "rationale": "textual source agreement",
            "evidenceRefs": ["fixture:adjudication:1"],
        }

        report = MODULE.validate_documents(self.documents)

        self.assertEqual(1, report.ambiguous_adjudication_count)

    def test_no_coordinate_hhs_binding_passes_with_approved_location_claim(self) -> None:
        report = MODULE.validate_documents(self.documents)

        self.assertEqual(8, report.location_claim_count)

    def test_attribution_conflict_passes_with_exhaustive_disposition(self) -> None:
        candidate = self.documents.candidate["candidates"][0]
        candidate["classification"] = "HHS_ATTRIBUTION_CONFLICT"
        candidate.pop("proposedAdministrativeUnitId")
        candidate["incompatibleAdministrativeUnitIds"] = [admin_id(1)]
        self.documents.selection["routeNodes"][0]["historicalConflictDisposition"] = {
            "selectedBindingRef": admin_id(1),
            "rejectedAdministrativeUnitIds": [],
            "rationale": "source attribution reviewed",
            "evidenceRefs": ["fixture:attribution:1"],
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

    def test_no_coordinate_binding_requires_an_external_location_claim(self) -> None:
        overlay = self.documents.overlay["administrativeUnits"][0]
        overlay["joinStatus"] = "NO_COORDINATE_CANDIDATE"
        overlay["candidateCount"] = 0
        overlay.pop("selectedCandidate")
        self.assert_invalid("locationClaimId")

    def test_tracked_artifacts_recursively_forbid_coordinate_fields(self) -> None:
        self.documents.selection["routeNodes"][0]["review"] = {"coordinate": [110, 35]}
        self.assert_invalid("coordinate")

    def test_external_claim_lifecycle_must_be_finite(self) -> None:
        claim = self.documents.external_claims["claims"][0]
        claim["subjectPeriod"]["effectiveFromYear"] = -9999
        self.assert_invalid("finite lifecycle")

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

    def test_liuqiu_has_zero_active_scenarios(self) -> None:
        self.documents.selection["routeNodes"][0]["displayName"] = "流求"
        self.assert_invalid("流求")

    def test_daifang_is_inactive_before_204(self) -> None:
        self.documents.selection["routeNodes"][0]["displayName"] = "帶方郡"
        self.assert_invalid("帶方郡")

    def test_scenario_catalog_must_cover_the_exact_31_resources(self) -> None:
        self.documents.selection["scenarioCatalog"]["resources"].pop()
        self.assert_invalid("scenario catalog")

    def test_scenario_catalog_hashes_must_match_actual_resources(self) -> None:
        self.documents.selection["scenarioCatalog"]["resources"][0]["sha256"] = "0" * 64
        self.assert_invalid("scenario catalog")

    def test_active_scenario_must_be_inside_node_lifecycle(self) -> None:
        self.documents.selection["routeNodes"][0]["effectiveFrom"] = 225
        self.assert_invalid("lifecycle")

    def test_active_scenario_ids_must_exactly_match_the_lifecycle(self) -> None:
        documents = real_documents()
        documents.selection["routeNodes"][0]["activeScenarioIds"].pop()

        with self.assertRaisesRegex(MODULE.SelectionContractError, "activeScenarioIds"):
            MODULE.validate_documents(documents)

    def test_scenario_states_must_cover_all_31_scenarios(self) -> None:
        documents = real_documents()
        documents.selection["routeNodes"][0]["scenarioStates"].pop()

        with self.assertRaisesRegex(MODULE.SelectionContractError, "scenarioStates"):
            MODULE.validate_documents(documents)

    def test_scenario_state_year_must_match_the_actual_resource(self) -> None:
        documents = real_documents()
        documents.selection["routeNodes"][0]["scenarioStates"][0]["startYear"] += 1

        with self.assertRaisesRegex(MODULE.SelectionContractError, "scenarioStates"):
            MODULE.validate_documents(documents)

    def test_scenario_state_value_must_match_the_lifecycle(self) -> None:
        documents = real_documents()
        state = documents.selection["routeNodes"][0]["scenarioStates"][0]
        state["state"] = "INACTIVE" if state["state"] == "ACTIVE" else "ACTIVE"

        with self.assertRaisesRegex(MODULE.SelectionContractError, "scenarioStates"):
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
        node["displayName"] = "교체현"
        node["replacementDisposition"] = {
            "rationale": "historical route replacement",
            "evidenceRefs": ["fixture:replacement:1"],
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

    def test_candidate_hash_reference_must_match(self) -> None:
        self.documents.selection["provenance"]["inputs"]["candidate"]["sha256"] = "0" * 64
        self.assert_invalid("candidate hash")

    def test_all_input_schema_versions_are_fail_closed(self) -> None:
        self.documents.selection["schemaVersion"] = 2
        self.assert_invalid("schemaVersion")

    def test_overlay_catalog_identity_must_match_catalog(self) -> None:
        self.documents.overlay["catalogId"] = "different-catalog"
        self.assert_invalid("catalogId")

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
        self.assert_invalid("oldCityId/fingerprint/routeNodeKey")

    def test_connection_graph_lifecycle_is_candidate_only(self) -> None:
        self.documents = replace(self.documents, connections=self._connections())
        self.documents.connections["lifecycle"] = "APPROVED"
        self.assert_invalid("CANDIDATE_ONLY")

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
        self.assertIn("approved=780", result.stdout)
        self.assertIn("scenarios=31", result.stdout)

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
            "rationale": "textual source agreement",
            "evidenceRefs": ["fixture:adjudication:1"],
        }

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
                    "startYear": resource.start_year,
                    "sha256": digest(payload),
                }
            )
        self.documents.selection["scenarioCatalog"]["resources"] = scenario_rows
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
            "--candidate", str(paths["candidate"]),
            "--catalog", str(paths["catalog"]),
            "--overlay", str(paths["overlay"]),
            "--external-claims", str(paths["claims"]),
            "--selection", str(selection),
            "--migration", str(migration),
            "--scenarios-dir", str(scenarios),
        ]


if __name__ == "__main__":
    unittest.main()
