#!/usr/bin/env python3
"""Build reviewed legacy catalog rows from the Kotlin public menu source."""

from __future__ import annotations

import json
import re
import sys
from collections import OrderedDict
from pathlib import Path
from typing import Any


MENU_SOURCE = Path("common/src/main/kotlin/opensamguk/common/constants/GameConst.kt")
CONTRACT_SOURCE = "docs/superpowers/specs/2026-08-27-public-alpha-command-contract-freeze.md"
REGISTRY_SOURCE = "logic/src/main/kotlin/opensamguk/logic/actions/CommandRegistry.kt"

PERSONAL_GROUPS = {
    "che_출병": "personal.sortie",
    "che_징병": "personal.recruit",
    "che_모병": "personal.recruit",
    "che_훈련": "personal.retinue.prepare",
    "che_사기진작": "personal.retinue.prepare",
    "che_집합": "personal.formation.organize",
    "che_소집해제": "personal.formation.organize",
    "che_이동": "personal.retinue.relocate",
    "che_강행": "personal.retinue.relocate",
    "che_농지개간": "personal.cityAction",
    "che_상업투자": "personal.cityAction",
    "che_기술연구": "personal.cityAction",
    "che_수비강화": "personal.cityAction",
    "che_성벽보수": "personal.cityAction",
    "che_치안강화": "personal.cityAction",
    "che_정착장려": "personal.cityAction",
    "che_주민선정": "personal.cityAction",
}

CHIEF_GROUPS = {
    "che_물자원조": "chief.diplomacy.propose",
    "che_불가침제의": "chief.diplomacy.propose",
    "che_종전제의": "chief.diplomacy.propose",
    "che_불가침파기제의": "chief.diplomacy.propose",
    "che_선전포고": "chief.diplomacy.declare",
}

PLANNED_COMMANDS = [
    ("plan.travel.start", "PERSISTENT_PLAN", "GENERAL", "SUBJECT_OWNER", ["travel"], ["OPENSAM-213"]),
    ("plan.travel.forceMarch", "PERSISTENT_PLAN", "GENERAL", "SUBJECT_OWNER", ["forced-march", "travel"], ["OPENSAM-213"]),
    ("plan.assignment.create", "PERSISTENT_PLAN", "GENERAL", "SUBJECT_OWNER", ["assignment"], ["OPENSAM-213"]),
    ("plan.convoy.create", "PERSISTENT_PLAN", "GENERAL", "SUBJECT_OWNER", ["convoy"], ["OPENSAM-214"]),
    ("plan.supply.establish", "PERSISTENT_PLAN", "GENERAL", "SUBJECT_OWNER", ["supply"], ["OPENSAM-214"]),
    ("plan.infrastructure.construct", "PERSISTENT_PLAN", "GENERAL", "SUBJECT_OWNER", ["infrastructure"], ["OPENSAM-215"]),
    ("plan.construction.execute", "PERSISTENT_PLAN", "GENERAL", "SUBJECT_OWNER", ["construction"], ["OPENSAM-215"]),
    ("operation.create", "PERSISTENT_PLAN", "GENERAL", "SUBJECT_OWNER", ["operation-create"], ["OPENSAM-200"]),
    ("operation.support", "PERSISTENT_PLAN", "GENERAL", "OPERATION_ROLE", ["operation-support"], ["OPENSAM-200"]),
    ("operation.reinforce", "PERSISTENT_PLAN", "FORMATION", "ASSIGNED_COMMANDER", ["operation-reinforcement"], ["OPENSAM-200"]),
    ("operation.intercept", "PERSISTENT_PLAN", "FORMATION", "OPERATION_ROLE", ["operation-intercept"], ["OPENSAM-200"]),
    ("operation.blockade", "PERSISTENT_PLAN", "FORMATION", "OPERATION_ROLE", ["operation-blockade"], ["OPENSAM-200"]),
    ("operation.escort", "PERSISTENT_PLAN", "FORMATION", "OPERATION_ROLE", ["operation-escort"], ["OPENSAM-200"]),
    ("operation.sabotage", "PERSISTENT_PLAN", "GENERAL", "OPERATION_ROLE", ["operation-sabotage"], ["OPENSAM-200"]),
    ("operation.retreat", "PERSISTENT_PLAN", "FORMATION", "OPERATION_ROLE", ["operation-retreat"], ["OPENSAM-200"]),
    ("operation.aftermath", "SYSTEM_RESOLVER", "SYSTEM", "SYSTEM_RESOLVER", ["operation-aftermath", "system-resolver"], ["OPENSAM-200"]),
    ("subordinate.recruit", "PERSONAL_RING", "GENERAL", "SUBJECT_OWNER", ["subordinate-person-recruitment"], ["OPENSAM-61"]),
    ("subordinate.oath", "PERSONAL_RING", "SUBORDINATE_PERSON", "SUBJECT_OWNER", ["subordinate-person-oath"], ["OPENSAM-61"]),
    ("subordinate.release", "PERSONAL_RING", "GENERAL", "SUBJECT_OWNER", ["subordinate-person-release"], ["OPENSAM-61"]),
    ("subordinate.assignRole", "PERSONAL_RING", "GENERAL", "SUBJECT_OWNER", ["subordinate-person-role"], ["OPENSAM-61"]),
    ("subordinate.assignMission", "PERSISTENT_PLAN", "SUBORDINATE_PERSON", "SUBJECT_OWNER", ["subordinate-person-mission"], ["OPENSAM-61"]),
    ("subordinate.delegate", "PERSISTENT_PLAN", "SUBORDINATE_PERSON", "SUBJECT_OWNER", ["subordinate-person-delegation"], ["OPENSAM-61"]),
    ("bugok.create", "PERSONAL_RING", "GENERAL", "SUBJECT_OWNER", ["bugok-create"], ["OPENSAM-48"]),
    ("bugok.configureFormation", "PERSONAL_RING", "BUGOK", "SUBJECT_OWNER", ["bugok-formation"], ["OPENSAM-48"]),
    ("bugok.replenish", "PERSONAL_RING", "BUGOK", "SUBJECT_OWNER", ["bugok-replenishment"], ["OPENSAM-48"]),
    ("bugok.train", "PERSISTENT_PLAN", "BUGOK", "SUBJECT_OWNER", ["bugok-training"], ["OPENSAM-48"]),
    ("bugok.split", "PERSONAL_RING", "BUGOK", "SUBJECT_OWNER", ["bugok-split"], ["OPENSAM-48"]),
    ("bugok.merge", "PERSONAL_RING", "BUGOK", "SUBJECT_OWNER", ["bugok-merge"], ["OPENSAM-48"]),
    ("bugok.assignCommander", "PERSONAL_RING", "BUGOK", "SUBJECT_OWNER", ["bugok-commander-assignment"], ["OPENSAM-48"]),
    ("bugok.dissolve", "PERSONAL_RING", "BUGOK", "SUBJECT_OWNER", ["bugok-dissolution"], ["OPENSAM-48"]),
    ("chief.council.convene", "CHIEF_RING", "NATION", "POLITY_ROLE", ["council"], ["OPENSAM-62"]),
    ("chief.policy.adopt", "CHIEF_RING", "NATION", "POLITY_ROLE", ["policy"], ["OPENSAM-62"]),
    ("chief.identity.adopt", "CHIEF_RING", "NATION", "POLITY_ROLE", ["identity", "national-identity"], ["OPENSAM-63"]),
    ("chief.court.petition", "CHIEF_RING", "NATION", "COURT_AUTHORITY", ["court"], ["OPENSAM-64"]),
    ("chief.office.nominate", "CHIEF_RING", "OFFICE", "OFFICE_CAPABILITY", ["office", "appointment"], ["OPENSAM-65"]),
    ("chief.edict.propose", "CHIEF_RING", "OFFICE", "COURT_AUTHORITY", ["edict"], ["OPENSAM-64"]),
    ("personal.seal.carry", "PERSISTENT_PLAN", "GENERAL", "COURT_AUTHORITY", ["seal"], ["OPENSAM-64"]),
    ("chief.reform.propose", "CHIEF_RING", "OFFICE", "OFFICE_CAPABILITY", ["reform"], ["OPENSAM-66"]),
    ("chief.governorship.assign", "CHIEF_RING", "OFFICE", "POLITY_ROLE", ["governorship"], ["OPENSAM-67"]),
    ("chief.fief.grant", "CHIEF_RING", "NATION", "POLITY_ROLE", ["fief"], ["OPENSAM-68"]),
    ("chief.vassal.offer", "CHIEF_RING", "NATION", "POLITY_ROLE", ["vassal"], ["OPENSAM-69"]),
    ("chief.tribute.set", "CHIEF_RING", "NATION", "POLITY_ROLE", ["tribute"], ["OPENSAM-69"]),
    ("chief.reinforcementObligation.call", "CHIEF_RING", "NATION", "POLITY_ROLE", ["reinforcement-obligation"], ["OPENSAM-69"]),
    ("battle.land.orderBatch", "BATTLE_ROUND", "FORMATION", "FORMATION_COMMANDER", ["wego-land"], ["OPENSAM-156"]),
    ("battle.siege.orderBatch", "BATTLE_ROUND", "FORMATION", "FORMATION_COMMANDER", ["wego-siege"], ["OPENSAM-157"]),
    ("battle.naval.orderBatch", "BATTLE_ROUND", "FORMATION", "FORMATION_COMMANDER", ["wego-naval"], ["OPENSAM-158"]),
    ("system.encounter.resolve", "SYSTEM_RESOLVER", "SYSTEM", "SYSTEM_RESOLVER", ["system-resolver"], ["OPENSAM-25"]),
    ("system.supply.resolve", "SYSTEM_RESOLVER", "SYSTEM", "SYSTEM_RESOLVER", ["system-resolver", "supply"], ["OPENSAM-25"]),
    ("system.occupation.resolve", "SYSTEM_RESOLVER", "SYSTEM", "SYSTEM_RESOLVER", ["system-resolver"], ["OPENSAM-25"]),
    ("system.notification.create", "SYSTEM_RESOLVER", "SYSTEM", "SYSTEM_RESOLVER", ["system-resolver"], ["OPENSAM-70"]),
    ("admin.world.setJoinPolicy", "SYSTEM_RESOLVER", "ADMIN", "ADMIN_AUDIT", ["admin-operation"], ["OPENSAM-29"]),
    ("admin.world.createSnapshot", "SYSTEM_RESOLVER", "ADMIN", "ADMIN_AUDIT", ["admin-operation"], ["OPENSAM-70"]),
    ("admin.world.requestReset", "SYSTEM_RESOLVER", "ADMIN", "ADMIN_AUDIT", ["admin-operation"], ["OPENSAM-71"]),
    ("admin.recovery.retryResolver", "SYSTEM_RESOLVER", "ADMIN", "ADMIN_AUDIT", ["admin-operation"], ["OPENSAM-72"]),
]

REQUIRED_FAMILIES = {
    "legacy-personal", "legacy-chief", "identity", "appointment", "founding", "domestic",
    "military", "finance", "personnel", "diplomacy", "travel", "forced-march", "assignment",
    "convoy", "supply", "infrastructure", "construction", "operation-create", "operation-support",
    "operation-reinforcement", "operation-intercept", "operation-blockade", "operation-escort",
    "operation-sabotage", "operation-retreat", "operation-aftermath", "subordinate-person-recruitment",
    "subordinate-person-oath", "subordinate-person-release", "subordinate-person-role",
    "subordinate-person-mission", "subordinate-person-delegation", "bugok-create", "bugok-formation",
    "bugok-replenishment", "bugok-training", "bugok-split", "bugok-merge", "bugok-commander-assignment",
    "bugok-dissolution", "council", "policy", "national-identity", "court", "office", "edict",
    "seal", "reform", "governorship", "fief", "vassal", "tribute", "reinforcement-obligation",
    "wego-land", "wego-siege", "wego-naval", "system-resolver", "admin-operation",
}

PLANNED_ARGUMENTS = {
    "plan.travel.start": ["actorId", "destinationProvinceId", "route"],
    "plan.travel.forceMarch": ["actorId", "destinationProvinceId", "route", "riskPolicy"],
    "plan.assignment.create": ["subjectId", "assigneeId", "destinationId", "role"],
    "plan.convoy.create": ["cargo", "originProvinceId", "destinationProvinceId", "route", "escortFormationIds"],
    "plan.supply.establish": ["operationId", "sourceProvinceId", "route", "capacity"],
    "plan.infrastructure.construct": ["templateId", "provinceIds", "routeEdgeIds"],
    "plan.construction.execute": ["projectId", "assignedActorId", "resourceCommitment"],
    "operation.create": ["objective", "participants", "route", "rules"],
    "operation.support": ["operationId", "supportType", "amount"],
    "operation.reinforce": ["operationId", "formationId", "arrivalWindow"],
    "operation.intercept": ["operationId", "targetOperationId", "interceptProvinceIds"],
    "operation.blockade": ["operationId", "targetEdgeIds", "deadline"],
    "operation.escort": ["operationId", "subjectPlanId", "formationIds"],
    "operation.sabotage": ["operationId", "targetId", "method"],
    "operation.retreat": ["operationId", "formationId", "retreatRoute", "trigger"],
    "operation.aftermath": ["battleId", "operationId", "replayHash"],
    "subordinate.recruit": ["candidateId", "terms"],
    "subordinate.oath": ["subordinatePersonId", "oathTerms"],
    "subordinate.release": ["subordinatePersonId", "releasePolicy"],
    "subordinate.assignRole": ["subordinatePersonId", "role"],
    "subordinate.assignMission": ["subordinatePersonId", "objective", "deadline"],
    "subordinate.delegate": ["subordinatePersonId", "commandScope", "duration"],
    "bugok.create": ["name", "manpower", "composition"],
    "bugok.configureFormation": ["bugokId", "formationTemplateId", "composition"],
    "bugok.replenish": ["bugokId", "manpower", "resourceCommitment"],
    "bugok.train": ["bugokId", "trainingPolicy", "duration"],
    "bugok.split": ["bugokId", "manpower", "newBugokName"],
    "bugok.merge": ["bugokId", "sourceBugokId"],
    "bugok.assignCommander": ["bugokId", "subordinatePersonId"],
    "bugok.dissolve": ["bugokId", "settlementPolicy"],
    "chief.council.convene": ["agenda", "participantIds", "deadline"],
    "chief.policy.adopt": ["policyId", "policyOptions"],
    "chief.identity.adopt": ["identityId", "policyOptions"],
    "chief.court.petition": ["petitionType", "targetId", "terms"],
    "chief.office.nominate": ["officeId", "candidateId", "jurisdictionId"],
    "chief.edict.propose": ["edictType", "targetIds", "terms"],
    "personal.seal.carry": ["sealId", "destinationId", "route", "escortFormationIds"],
    "chief.reform.propose": ["reformId", "jurisdictionIds", "budget"],
    "chief.governorship.assign": ["jurisdictionId", "candidateId", "authorityTerms"],
    "chief.fief.grant": ["vassalId", "fiefIds", "grantTerms"],
    "chief.vassal.offer": ["targetPolityId", "vassalTerms"],
    "chief.tribute.set": ["vassalId", "tributeTerms"],
    "chief.reinforcementObligation.call": ["vassalId", "operationId", "obligationTerms"],
    "battle.land.orderBatch": ["battleId", "roundId", "eligibleFormationIds", "orders", "doctrineOnMissing"],
    "battle.siege.orderBatch": ["battleId", "roundId", "eligibleFormationIds", "orders", "doctrineOnMissing"],
    "battle.naval.orderBatch": ["battleId", "roundId", "eligibleFormationIds", "orders", "doctrineOnMissing"],
    "system.encounter.resolve": ["worldSnapshotHash", "orderedInputHash", "seed"],
    "system.supply.resolve": ["worldSnapshotHash", "orderedInputHash", "seed"],
    "system.occupation.resolve": ["worldSnapshotHash", "orderedInputHash", "seed"],
    "system.notification.create": ["recipientIds", "topicId", "payload"],
    "admin.world.setJoinPolicy": ["joinEnabled", "announcement"],
    "admin.world.createSnapshot": ["reason"],
    "admin.world.requestReset": ["snapshotId", "reason", "announcement"],
    "admin.recovery.retryResolver": ["resolverId", "failureId", "reason"],
}


def _menu_block(source: str, name: str, next_marker: str) -> str:
    start = source.index(f"val {name}:")
    end = source.index(next_marker, start)
    return source[start:end]


def parse_menu(source: str, name: str, next_marker: str) -> list[tuple[str, str]]:
    block = _menu_block(source, name, next_marker)
    result: list[tuple[str, str]] = []
    category_pattern = re.compile(r'"([^"]+)"\s+to\s+listOf\((.*?)\n\s*\)', re.DOTALL)
    for category, commands_block in category_pattern.findall(block):
        for code in re.findall(r'"([^"]+)"', commands_block):
            result.append((category, code))
    return result


def read_legacy_surfaces(repo_root: Path) -> tuple[list[tuple[str, str]], list[tuple[str, str]]]:
    source = (repo_root / MENU_SOURCE).read_text(encoding="utf-8")
    personal = parse_menu(source, "availableGeneralCommand", "val availableChiefCommand")
    chief = parse_menu(source, "availableChiefCommand", "const val retirementYear")
    return personal, chief


def _canonical_id(layer_prefix: str, code: str, groups: dict[str, str]) -> str:
    if code == "휴식":
        return f"{layer_prefix}.rest"
    normalized_intent = groups.get(code)
    if normalized_intent:
        return f"{normalized_intent}.legacy.{code.removeprefix('che_')}"
    return f"{layer_prefix}.legacy.{code}"


def _new_row(canonical_id: str, layer: str, actor_type: str, ring: str) -> dict[str, Any]:
    topic_suffix = canonical_id.replace("_", "-")
    return {
        "canonicalId": canonical_id,
        "aliases": [],
        "layer": layer,
        "actorType": actor_type,
        "authority": {"policy": "SUBJECT_OWNER" if layer == "PERSONAL_RING" else "LEGACY_OFFICER_LEVEL"},
        "argumentSchema": {
            "type": "object",
            "required": ["legacyCode", "args"],
            "properties": {"legacyCode": {"type": "string"}, "args": {"type": "object"}},
            "additionalProperties": False,
        },
        "resultSchema": {
            "type": "object",
            "required": ["requestId", "turnIdx", "actorId", "commandCode", "status"],
            "properties": {
                "requestId": {"type": "string"}, "turnIdx": {"type": "integer"},
                "actorId": {"type": "string"}, "commandCode": {"type": "string"},
                "status": {"type": "string"},
            },
            "additionalProperties": True,
        },
        "availabilityRules": ["preserve-legacy-precheck"],
        "reservationRules": {"ring": ring, "slots": 1, "preservePosition": True},
        "executionRules": [
            "preserve-legacy-cost",
            "preserve-legacy-rng-order",
            "preserve-legacy-log-order",
            "preserve-legacy-result",
        ],
        "eventTypes": ["command.accepted", "command.rejected", "command.resolved"],
        "replayContract": {"disposition": "LEGACY_LOG", "reason": "legacy ring result and log remain authoritative"},
        "aiPolicy": {"disposition": "PLANNED", "policy": "same availability contract as player"},
        "helpTopicId": f"commands.{topic_suffix}",
        "tutorialObjectiveId": f"tutorial.{topic_suffix}",
        "replacementId": None,
        "migrationPolicy": None,
        "deliveryState": "DOMAIN_READY",
        "contractStatus": "FINAL",
        "ownerIssues": ["OPENSAM-76", "GH-218"],
        "provenance": [
            {"path": str(MENU_SOURCE), "role": "public-menu"},
            {"path": REGISTRY_SOURCE, "role": "legacy-handler-registry"},
            {"path": CONTRACT_SOURCE, "role": "contract"},
        ],
        "evidence": {
            "DOMAIN_READY": [f"spec:data/commands/public-alpha-command-catalog.json::{canonical_id}"]
        },
        "families": ["legacy-personal" if layer == "PERSONAL_RING" else "legacy-chief"],
        "legacySurfaces": [],
    }


def _terms_schema() -> dict[str, Any]:
    return {
        "type": "object",
        "required": ["version", "clauses"],
        "properties": {
            "version": {"type": "integer", "minimum": 1},
            "clauses": {
                "type": "array", "minItems": 1,
                "items": {
                    "type": "object", "required": ["type", "value"],
                    "properties": {"type": {"type": "string", "minLength": 1}, "value": {}},
                    "additionalProperties": False,
                },
            },
        },
        "additionalProperties": False,
    }


def _resources_schema() -> dict[str, Any]:
    return {
        "type": "object",
        "required": ["resources"],
        "properties": {
            "resources": {
                "type": "array", "minItems": 1,
                "items": {
                    "type": "object", "required": ["resourceType", "amount"],
                    "properties": {
                        "resourceType": {"type": "string", "minLength": 1},
                        "amount": {"type": "number", "minimum": 0},
                    },
                    "additionalProperties": False,
                },
            },
        },
        "additionalProperties": False,
    }


def _objective_schema() -> dict[str, Any]:
    return {
        "type": "object",
        "required": ["type", "deadline", "progressRules", "supplyRequirements", "interruptionRules"],
        "properties": {
            "type": {"enum": ["OCCUPATION", "PASSAGE", "ROUTE_CONTROL", "SUPPLY_INTERDICTION", "BLOCKADE", "RELIEF"]},
            "targetProvinceIds": {"type": "array", "items": {"type": "string"}, "minItems": 1},
            "targetEdgeIds": {"type": "array", "items": {"type": "string"}, "minItems": 1},
            "deadline": {"type": "string", "minLength": 1},
            "progressRules": {"type": "array", "items": {"type": "string"}, "minItems": 1},
            "supplyRequirements": _resources_schema(),
            "interruptionRules": {"type": "array", "items": {"type": "string"}, "minItems": 1},
        },
        "anyOf": [{"required": ["targetProvinceIds"]}, {"required": ["targetEdgeIds"]}],
        "additionalProperties": False,
    }


def _orders_schema(canonical_id: str) -> dict[str, Any]:
    order_types = {
        "battle.land.orderBatch": {
            "MOVE": ["targetProvinceIds", "route"],
            "HOLD": ["targetProvinceId", "stance"],
            "FIRE": ["targetFormationId", "ammunitionPolicy"],
            "CHARGE": ["targetFormationId", "approachProvinceIds"],
            "SUPPORT": ["targetFormationId", "supportType"],
            "WITHDRAW": ["retreatRoute"],
            "RALLY": ["targetProvinceId"],
            "RESUPPLY": ["supplySourceId", "route"],
        },
        "battle.siege.orderBatch": {
            "APPROACH": ["targetWallSegmentId", "route"],
            "BOMBARD": ["targetWallSegmentId", "ammunitionPolicy"],
            "BREACH": ["targetWallSegmentId", "breachMethod"],
            "ASSAULT": ["targetWallSegmentId", "assaultAxis"],
            "DEFEND_GATE": ["gateId", "reserveFormationIds"],
            "REPAIR": ["wallSegmentId", "resourceCommitment"],
            "SORTIE": ["gateId", "targetFormationId"],
            "WITHDRAW": ["retreatRoute"],
        },
        "battle.naval.orderBatch": {
            "SAIL": ["destinationWaterProvinceId", "route"],
            "FORMATION": ["formationType", "spacing"],
            "FIRE": ["targetFormationId", "ammunitionPolicy"],
            "BOARD": ["targetFormationId", "boardingSide"],
            "BLOCKADE": ["targetEdgeIds", "duration"],
            "ESCORT": ["subjectPlanId", "route"],
            "DISENGAGE": ["retreatRoute"],
            "RESUPPLY": ["supplySourceId", "route"],
        },
    }[canonical_id]
    return {
        "type": "array", "minItems": 1,
        "items": {
            "oneOf": [
                {
                    "type": "object",
                    "required": ["orderId", "formationId", "orderType", "sequence", "payload"],
                    "properties": {
                        "orderId": {"type": "string", "minLength": 1},
                        "formationId": {"type": "string", "minLength": 1},
                        "orderType": {"const": order_type},
                        "sequence": {"type": "integer", "minimum": 0},
                        "payload": {
                            "type": "object", "required": payload_fields,
                            "properties": {
                                field: _field_schema(field, canonical_id) for field in payload_fields
                            },
                            "additionalProperties": False,
                        },
                    },
                    "additionalProperties": False,
                }
                for order_type, payload_fields in order_types.items()
            ]
        },
    }


def _field_schema(field: str, canonical_id: str) -> dict[str, Any]:
    if field in {"amount", "capacity", "manpower", "budget"}:
        return {"type": "number", "minimum": 0}
    if field == "joinEnabled":
        return {"type": "boolean"}
    if field == "doctrineOnMissing":
        return {"enum": ["HOLD", "FORMATION_DOCTRINE"]}
    if field == "orders":
        return _orders_schema(canonical_id)
    if field.endswith("Ids") or field in {"route", "retreatRoute"}:
        return {"type": "array", "items": {"type": "string"}, "minItems": 1}
    if field == "objective":
        return _objective_schema()
    if field == "payload":
        return {
            "type": "object", "required": ["severity", "messageKey", "data", "dedupeKey"],
            "properties": {
                "severity": {"enum": ["INFO", "WARNING", "CRITICAL"]},
                "messageKey": {"type": "string", "minLength": 1},
                "data": {"type": "object"},
                "dedupeKey": {"type": "string", "minLength": 1},
            },
            "additionalProperties": False,
        }
    if field in {"cargo", "resourceCommitment"}:
        return _resources_schema()
    if field == "composition":
        return {
            "type": "object", "required": ["units"],
            "properties": {
                "units": {
                    "type": "array", "minItems": 1,
                    "items": {
                        "type": "object", "required": ["templateId", "manpower"],
                        "properties": {
                            "templateId": {"type": "string", "minLength": 1},
                            "manpower": {"type": "integer", "minimum": 1},
                        },
                        "additionalProperties": False,
                    },
                },
            },
            "additionalProperties": False,
        }
    if field in {"terms", "oathTerms", "authorityTerms", "grantTerms", "vassalTerms", "tributeTerms", "obligationTerms"}:
        return _terms_schema()
    if field in {"releasePolicy", "trainingPolicy", "settlementPolicy", "policyOptions"}:
        return {
            "type": "object", "required": ["policyId", "parameters"],
            "properties": {
                "policyId": {"type": "string", "minLength": 1},
                "parameters": {"type": "object"},
            },
            "additionalProperties": False,
        }
    if field == "agenda":
        return {
            "type": "object", "required": ["topicIds", "decisionRule"],
            "properties": {
                "topicIds": {"type": "array", "items": {"type": "string"}, "minItems": 1},
                "decisionRule": {"type": "string", "minLength": 1},
            },
            "additionalProperties": False,
        }
    if field == "participants":
        return {
            "type": "array", "minItems": 1,
            "items": {
                "type": "object", "required": ["participantId", "role"],
                "properties": {
                    "participantId": {"type": "string", "minLength": 1},
                    "role": {"enum": ["MAIN", "SUPPORT", "SCOUT", "SUPPLY", "RESERVE"]},
                },
                "additionalProperties": False,
            },
        }
    if field == "rules":
        return {
            "type": "object", "required": ["intercept", "retreat", "siege", "supply"],
            "properties": {
                "intercept": {"enum": ["ALLOW", "AVOID", "REQUIRE"]},
                "retreat": {"enum": ["HOLD", "ORDERED", "AUTOMATIC_ON_TRIGGER"]},
                "siege": {"enum": ["DISALLOW", "ALLOW", "REQUIRE"]},
                "supply": {"enum": ["CONNECTED_REQUIRED", "FORAGING_ALLOWED", "CARRY_ONLY"]},
            },
            "additionalProperties": False,
        }
    return {"type": "string", "minLength": 1}


def _planned_result_schema(layer: str, actor_type: str) -> dict[str, Any]:
    required = ["requestId", "status"]
    if layer == "PERSISTENT_PLAN":
        required.append("planId")
    elif layer == "BATTLE_ROUND":
        required.extend(["battleId", "roundId", "replayHash"])
    elif layer == "SYSTEM_RESOLVER" and actor_type == "ADMIN":
        required.append("auditId")
    elif layer == "SYSTEM_RESOLVER":
        required.extend(["resolverId", "replayHash"])
    properties = {field: {"type": "string", "minLength": 1} for field in required}
    return {"type": "object", "required": required, "properties": properties, "additionalProperties": True}


def _planned_row(
    canonical_id: str,
    layer: str,
    actor_type: str,
    authority: str,
    families: list[str],
    owner_issues: list[str],
) -> dict[str, Any]:
    ring = {"PERSONAL_RING": "general_turn", "CHIEF_RING": "nation_turn"}.get(layer)
    argument_fields = ["idempotencyKey", *PLANNED_ARGUMENTS[canonical_id]]
    if actor_type == "ADMIN":
        replay_contract = {"disposition": "AUDIT_LOG", "reason": "operator-only mutation requires durable audit evidence"}
        ai_policy = {"disposition": "N/A", "reason": "administrator commands are never selectable by player or AI"}
        availability_rules = ["authenticated-admin", "audit-reason-present", "world-state-allows-operation"]
        execution_rules = ["validate-admin-authority", "write-audit-intent", "single-campaign-flush", "write-audit-result"]
    elif actor_type == "SYSTEM":
        replay_contract = {"disposition": "DETERMINISTIC_REPLAY", "reason": "ordered resolver input and state diff are hashable"}
        ai_policy = {"disposition": "N/A", "reason": "system resolvers are derived transitions and are not selectable commands"}
        availability_rules = ["system-invocation-only", "ordered-input-complete", "snapshot-version-matches"]
        execution_rules = ["validate-versioned-input", "resolve-deterministically", "single-campaign-flush", "publish-replay-hash"]
    elif layer == "BATTLE_ROUND":
        replay_contract = {"disposition": "DETERMINISTIC_REPLAY", "reason": "sealed WEGO orders and ordered state diff are hashable"}
        ai_policy = {"disposition": "REQUIRED", "policy": "AI submits the same sealed order schema as a player"}
        availability_rules = ["battle-round-open", "formation-authority-valid", "order-deadline-open"]
        execution_rules = [
            "validate-round", "validate-orders-target-eligible-formations",
            "apply-doctrineOnMissing-to-every-eligible-formation-without-an-order",
            "seal-orders", "resolve-deterministically", "publish-replay-hash",
        ]
    elif layer == "PERSISTENT_PLAN":
        replay_contract = {"disposition": "DETERMINISTIC_REPLAY", "reason": "plan transitions and recovery checkpoints are ordered"}
        ai_policy = {"disposition": "REQUIRED", "policy": "AI submits the same plan schema and availability contract as a player"}
        availability_rules = ["actor-authority-valid", "route-and-resource-preconditions-pass", "no-conflicting-plan-lock"]
        execution_rules = ["validate-idempotency", "create-durable-plan", "progress-by-system-resolver", "recover-after-restart"]
    else:
        replay_contract = {"disposition": "COMMAND_EVENT", "reason": "reserved-ring result is recorded in ordered campaign events"}
        ai_policy = {"disposition": "REQUIRED", "policy": "AI reserves the same ring command and availability contract as a player"}
        availability_rules = ["actor-authority-valid", "ring-slot-available", "command-preconditions-pass"]
        execution_rules = ["validate-idempotency", "reserve-ring-slot", "execute-when-due", "single-campaign-flush"]
    return {
        "canonicalId": canonical_id,
        "aliases": [],
        "layer": layer,
        "actorType": actor_type,
        "authority": {"policy": authority},
        "argumentSchema": {
            "type": "object", "required": argument_fields,
            "properties": {field: _field_schema(field, canonical_id) for field in argument_fields},
            "additionalProperties": False,
        },
        "resultSchema": _planned_result_schema(layer, actor_type),
        "availabilityRules": availability_rules,
        "reservationRules": {"ring": ring, "slots": 1 if ring else 0},
        "executionRules": execution_rules,
        "eventTypes": (
            [
                "plan.created", "plan.progressed", "plan.interrupted", "plan.resolved",
                "recovery.resumed", "recovery.failed",
            ]
            if layer == "PERSISTENT_PLAN" else
            ["battle.round.sealed", "battle.round.resolved", "battle.replay.published"]
            if layer == "BATTLE_ROUND" else
            [
                "admin.accepted", "admin.rejected", "admin.resolved",
                *( ["recovery.resumed", "recovery.failed"] if canonical_id == "admin.recovery.retryResolver" else [] ),
            ]
            if actor_type == "ADMIN" else
            [
                "resolver.accepted", "resolver.rejected", "resolver.resolved",
                *( ["notification.created"] if canonical_id == "system.notification.create" else [] ),
            ]
            if actor_type == "SYSTEM" else
            ["command.accepted", "command.rejected", "command.resolved"]
        ),
        "replayContract": replay_contract,
        "aiPolicy": ai_policy,
        "helpTopicId": f"commands.{canonical_id}",
        "tutorialObjectiveId": f"tutorial.{canonical_id}",
        "replacementId": None,
        "migrationPolicy": None,
        "deliveryState": "DOMAIN_READY",
        "contractStatus": "FINAL",
        "ownerIssues": owner_issues,
        "provenance": [
            {"path": "docs/superpowers/specs/2026-08-27-public-alpha-rebaseline-design.md", "role": "product-family"},
            {"path": CONTRACT_SOURCE, "role": "contract"},
        ],
        "evidence": {
            "DOMAIN_READY": [f"spec:data/commands/public-alpha-command-catalog.json::{canonical_id}"]
        },
        "families": families,
        "legacySurfaces": [],
    }


def build_legacy_rows(repo_root: Path) -> list[dict[str, Any]]:
    personal, chief = read_legacy_surfaces(repo_root)
    rows: OrderedDict[str, dict[str, Any]] = OrderedDict()
    definitions = [
        (personal, "personal", PERSONAL_GROUPS, "PERSONAL_RING", "GENERAL", "general_turn"),
        (chief, "chief", CHIEF_GROUPS, "CHIEF_RING", "NATION", "nation_turn"),
    ]
    for surfaces, prefix, groups, layer, actor_type, ring in definitions:
        for category, code in surfaces:
            canonical_id = _canonical_id(prefix, code, groups)
            row = rows.setdefault(canonical_id, _new_row(canonical_id, layer, actor_type, ring))
            row["normalizedIntentId"] = groups.get(code)
            alias = f"{ring}:{code}" if code == "휴식" else code
            if alias not in row["aliases"]:
                row["aliases"].append(alias)
            row["legacySurfaces"].append(
                {
                    "ring": ring,
                    "category": category,
                    "legacyCode": code,
                    "adapterPolicy": "PRESERVE",
                    "parityStatus": "LOCKED",
                }
            )
    if any(len(row["legacySurfaces"]) != 1 for row in rows.values()):
        raise ValueError("OPENSAM-76 requires one canonical adapter row per legacy menu surface")
    return list(rows.values())


def _add_legacy_family_tags(rows: list[dict[str, Any]]) -> None:
    tag_by_category = {
        ("general_turn", "내정"): ["domestic"],
        ("general_turn", "군사"): ["military"],
        ("general_turn", "인사"): ["personnel"],
        ("general_turn", "국가"): ["finance", "founding"],
        ("nation_turn", "인사"): ["appointment", "personnel"],
        ("nation_turn", "외교"): ["diplomacy"],
        ("nation_turn", "특수"): ["policy"],
        ("nation_turn", "전략"): ["military", "policy"],
        ("nation_turn", "기타"): ["identity", "national-identity"],
    }
    for row in rows:
        for surface in row["legacySurfaces"]:
            for family in tag_by_category.get((surface["ring"], surface["category"]), []):
                if family not in row["families"]:
                    row["families"].append(family)


def build_catalog(repo_root: Path) -> dict[str, Any]:
    legacy_rows = build_legacy_rows(repo_root)
    _add_legacy_family_tags(legacy_rows)
    planned_rows = [_planned_row(*definition) for definition in PLANNED_COMMANDS]
    return {
        "schemaVersion": 1,
        "catalogVersion": "2026-08-27",
        "productAuthority": "docs/superpowers/specs/2026-08-27-public-alpha-rebaseline-design.md",
        "contractAuthority": CONTRACT_SOURCE,
        "requiredFamilies": sorted(REQUIRED_FAMILIES),
        "commands": legacy_rows + planned_rows,
    }


def main() -> int:
    repo_root = Path(__file__).resolve().parents[2]
    json.dump(build_catalog(repo_root), sys.stdout, ensure_ascii=False, indent=2)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
