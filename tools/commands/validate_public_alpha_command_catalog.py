#!/usr/bin/env python3
"""Validate the reviewed public-alpha command catalog without runtime dependencies."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any


LAYERS = {
    "PERSONAL_RING",
    "CHIEF_RING",
    "PERSISTENT_PLAN",
    "BATTLE_ROUND",
    "SYSTEM_RESOLVER",
}
ACTOR_TYPES = {
    "GENERAL",
    "SUBORDINATE_PERSON",
    "BUGOK",
    "FORMATION",
    "OFFICE",
    "NATION",
    "SYSTEM",
    "ADMIN",
}
LIFECYCLE = [
    "DOMAIN_READY",
    "HANDLER_READY",
    "UI_READY",
    "AI_READY",
    "HELP_READY",
    "TUTORIAL_READY",
    "REPLAY_READY",
    "VERIFIED",
]
CONTRACT_STATUSES = {"FINAL", "PROVISIONAL"}
REQUIRED_PUBLIC_ALPHA_FAMILIES = {
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
REQUIRED_ROW_FIELDS = {
    "canonicalId",
    "aliases",
    "layer",
    "actorType",
    "authority",
    "argumentSchema",
    "resultSchema",
    "availabilityRules",
    "reservationRules",
    "executionRules",
    "eventTypes",
    "replayContract",
    "aiPolicy",
    "helpTopicId",
    "tutorialObjectiveId",
    "replacementId",
    "migrationPolicy",
    "deliveryState",
    "contractStatus",
    "ownerIssues",
    "provenance",
    "evidence",
}

REQUIRED_EVENTS_BY_LAYER = {
    "PERSONAL_RING": {"command.accepted", "command.rejected", "command.resolved"},
    "CHIEF_RING": {"command.accepted", "command.rejected", "command.resolved"},
    "PERSISTENT_PLAN": {
        "plan.created", "plan.progressed", "plan.interrupted", "plan.resolved",
        "recovery.resumed", "recovery.failed",
    },
    "BATTLE_ROUND": {"battle.round.sealed", "battle.round.resolved", "battle.replay.published"},
}
EVIDENCE_PREFIX_BY_STATE = {
    "DOMAIN_READY": "spec:", "HANDLER_READY": "test:", "UI_READY": "ui:",
    "AI_READY": "ai:", "HELP_READY": "help:", "TUTORIAL_READY": "tutorial:",
    "REPLAY_READY": "replay:", "VERIFIED": "campaign:",
}


def load_catalog(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as source:
        catalog = json.load(source)
    if not isinstance(catalog, dict):
        raise ValueError("catalog root must be an object")
    return catalog


def resolve_command(catalog: dict[str, Any], identifier: str) -> dict[str, Any] | None:
    """Return one exact canonical or alias match; unknown and ambiguous IDs fail closed."""
    matches = []
    for row in catalog.get("commands", []):
        if not isinstance(row, dict):
            continue
        if identifier == row.get("canonicalId") or identifier in row.get("aliases", []):
            matches.append(row)
    return matches[0] if len(matches) == 1 else None


def _replacement_cycle(start: str, replacements: dict[str, str]) -> list[str] | None:
    seen: list[str] = []
    current = start
    while current in replacements:
        if current in seen:
            offset = seen.index(current)
            return seen[offset:] + [current]
        seen.append(current)
        current = replacements[current]
    return None


def _evidence_reference_exists(entry: str, prefix: str, canonical_id: str, repo_root: Path) -> bool:
    if not entry.startswith(prefix):
        return False
    reference = entry.removeprefix(prefix)
    path_text, separator, marker = reference.partition("::")
    path_text = path_text.split("#", 1)[0]
    if not path_text:
        return False
    path = repo_root / path_text
    if not path.is_file():
        return False
    normalized = path_text.replace("\\", "/")
    rules = {
        "spec:": normalized.startswith(("docs/superpowers/specs/", "data/commands/")),
        "test:": "/test" in f"/{normalized}" or "/tests/" in f"/{normalized}",
        "ui:": normalized.startswith("web/") and ("test" in normalized or "e2e" in normalized),
        "ai:": "test" in normalized or "tests/" in normalized,
        "help:": normalized.startswith(("docs/", "web/")),
        "tutorial:": normalized.startswith("web/") and ("test" in normalized or "e2e" in normalized),
        "replay:": "test" in normalized or "tests/" in normalized,
        "campaign:": "test" in normalized or "e2e" in normalized,
    }
    if not rules.get(prefix, False):
        return False
    if not separator or canonical_id not in marker:
        return False
    return marker in path.read_text(encoding="utf-8")


def validate_catalog(catalog: dict[str, Any], repo_root: Path) -> list[str]:
    """Return all structural errors. This function is pure with respect to catalog and disk."""
    errors: list[str] = []
    if catalog.get("schemaVersion") != 1:
        errors.append("schemaVersion must be 1")
    rows = catalog.get("commands")
    if not isinstance(rows, list):
        return errors + ["commands must be an array"]

    canonical_counts: dict[str, int] = {}
    alias_owners: dict[str, set[str]] = {}
    replacements: dict[str, str] = {}

    for index, row in enumerate(rows):
        label = f"commands[{index}]"
        if not isinstance(row, dict):
            errors.append(f"{label} must be an object")
            continue
        missing = sorted(REQUIRED_ROW_FIELDS - row.keys())
        if missing:
            errors.append(f"{label} missing required fields: {', '.join(missing)}")

        canonical_id = row.get("canonicalId")
        if not isinstance(canonical_id, str) or not canonical_id.strip():
            errors.append(f"{label} canonicalId must be a non-empty string")
            canonical_id = label
        canonical_counts[canonical_id] = canonical_counts.get(canonical_id, 0) + 1

        aliases = row.get("aliases")
        if not isinstance(aliases, list) or any(not isinstance(alias, str) or not alias for alias in aliases):
            errors.append(f"{canonical_id} aliases must be non-empty strings")
            aliases = []
        for alias in aliases:
            alias_owners.setdefault(alias, set()).add(canonical_id)
            if alias == canonical_id:
                errors.append(f"{canonical_id} aliases must not repeat canonicalId")
        if len(aliases) != len(set(aliases)):
            errors.append(f"{canonical_id} aliases must not contain duplicates")

        if row.get("layer") not in LAYERS:
            errors.append(f"{canonical_id} invalid layer {row.get('layer')}")
        if row.get("actorType") not in ACTOR_TYPES:
            errors.append(f"{canonical_id} invalid actorType {row.get('actorType')}")
        authority = row.get("authority")
        if not isinstance(authority, dict) or not isinstance(authority.get("policy"), str) or not authority["policy"]:
            errors.append(f"{canonical_id} authority must contain a non-empty policy")
        for field in ("argumentSchema", "resultSchema"):
            schema = row.get(field)
            if not isinstance(schema, dict) or schema.get("type") != "object":
                errors.append(f"{canonical_id} {field} must be an object JSON schema")
            elif not isinstance(schema.get("required"), list) or not schema["required"]:
                errors.append(f"{canonical_id} {field} must declare required fields")
            elif not isinstance(schema.get("properties"), dict):
                errors.append(f"{canonical_id} {field} must declare properties")
            elif any(name not in schema["properties"] for name in schema["required"]):
                errors.append(f"{canonical_id} {field} required fields must exist in properties")
        for field in ("availabilityRules", "executionRules", "eventTypes"):
            values = row.get(field)
            if not isinstance(values, list) or not values or any(not isinstance(value, str) or not value for value in values):
                errors.append(f"{canonical_id} {field} must be a non-empty string array")
        event_types = set(row.get("eventTypes", [])) if isinstance(row.get("eventTypes"), list) else set()
        required_events = REQUIRED_EVENTS_BY_LAYER.get(row.get("layer"), set())
        if not required_events <= event_types:
            errors.append(
                f"{canonical_id} {row.get('layer')} missing required events: "
                f"{', '.join(sorted(required_events - event_types))}"
            )
        if row.get("layer") == "SYSTEM_RESOLVER":
            required_system_event = "admin.resolved" if row.get("actorType") == "ADMIN" else "resolver.resolved"
            if required_system_event not in event_types:
                errors.append(f"{canonical_id} SYSTEM_RESOLVER missing required event {required_system_event}")
        reservation = row.get("reservationRules")
        if not isinstance(reservation, dict) or "ring" not in reservation or "slots" not in reservation:
            errors.append(f"{canonical_id} reservationRules must declare ring and slots")
        for field in ("replayContract", "aiPolicy"):
            policy = row.get(field)
            if not isinstance(policy, dict) or not isinstance(policy.get("disposition"), str) or not policy["disposition"]:
                errors.append(f"{canonical_id} {field} must contain a disposition")
            elif policy["disposition"] == "N/A" and not policy.get("reason"):
                errors.append(f"{canonical_id} {field} N/A requires a reason")
            elif policy["disposition"] != "N/A" and not (policy.get("policy") or policy.get("reason")):
                errors.append(f"{canonical_id} {field} requires policy or reason")
        owner_issues = row.get("ownerIssues")
        if not isinstance(owner_issues, list) or not owner_issues or any(
            not isinstance(issue, str) or not (issue.startswith("OPENSAM-") or issue.startswith("GH-"))
            for issue in owner_issues
        ):
            errors.append(f"{canonical_id} ownerIssues must contain OPENSAM-* or GH-* identifiers")
        provenance = row.get("provenance")
        if not isinstance(provenance, list) or not provenance or any(
            not isinstance(item, dict) or not item.get("path") or not item.get("role") for item in provenance
        ):
            errors.append(f"{canonical_id} provenance must contain path and role objects")
        else:
            for item in provenance:
                path = item["path"]
                if not path.startswith(("http://", "https://")) and not (repo_root / path).exists():
                    errors.append(f"{canonical_id} provenance path does not exist: {path}")
        state = row.get("deliveryState")
        if state not in LIFECYCLE:
            errors.append(f"{canonical_id} invalid deliveryState {state}")
        else:
            evidence = row.get("evidence") if isinstance(row.get("evidence"), dict) else {}
            for required_state in LIFECYCLE[: LIFECYCLE.index(state) + 1]:
                entries = evidence.get(required_state)
                if not isinstance(entries, list) or not entries or any(
                    not isinstance(entry, str) or ":" not in entry for entry in entries
                ):
                    errors.append(f"{canonical_id} missing lifecycle evidence for {required_state}")
                else:
                    prefix = EVIDENCE_PREFIX_BY_STATE[required_state]
                    if not any(
                        _evidence_reference_exists(entry, prefix, canonical_id, repo_root) for entry in entries
                    ):
                        errors.append(
                            f"{canonical_id} {required_state} requires existing command-bound {prefix} evidence"
                        )
            if state == "VERIFIED":
                for evidence_state, prefix in EVIDENCE_PREFIX_BY_STATE.items():
                    entries = evidence.get(evidence_state, [])
                    if not any(
                        _evidence_reference_exists(entry, prefix, canonical_id, repo_root) for entry in entries
                    ):
                        errors.append(
                            f"{canonical_id} VERIFIED requires existing {prefix} evidence in {evidence_state}"
                        )
        if row.get("contractStatus") not in CONTRACT_STATUSES:
            errors.append(f"{canonical_id} invalid contractStatus {row.get('contractStatus')}")

        replacement = row.get("replacementId")
        if replacement is not None:
            if not isinstance(replacement, str) or not replacement:
                errors.append(f"{canonical_id} replacementId must be null or a non-empty string")
            else:
                replacements[canonical_id] = replacement
                migration = row.get("migrationPolicy")
                if not isinstance(migration, dict) or not {
                    "strategy", "savedQueuePolicy", "parserRemovalGate"
                } <= migration.keys() or any(not isinstance(value, str) or not value for value in migration.values()):
                    errors.append(
                        f"{canonical_id} replacement requires structured migrationPolicy with "
                        "strategy, savedQueuePolicy, and parserRemovalGate"
                    )
        elif row.get("migrationPolicy") is not None:
            errors.append(f"{canonical_id} migrationPolicy requires replacementId")

    for canonical_id, count in canonical_counts.items():
        if count > 1:
            errors.append(f"duplicate canonicalId {canonical_id}")
    canonical_ids = set(canonical_counts)
    for alias, owners in alias_owners.items():
        if len(owners) > 1:
            errors.append(f"alias {alias} resolves to multiple commands: {', '.join(sorted(owners))}")
        if alias in canonical_ids:
            errors.append(f"alias {alias} collides with canonicalId")
    for canonical_id, replacement in replacements.items():
        if replacement not in canonical_ids:
            errors.append(f"{canonical_id} has unknown replacementId {replacement}")
    reported_cycles: set[tuple[str, ...]] = set()
    for canonical_id in replacements:
        cycle = _replacement_cycle(canonical_id, replacements)
        if cycle:
            normalized = tuple(sorted(set(cycle)))
            if normalized not in reported_cycles:
                reported_cycles.add(normalized)
                errors.append(f"replacement cycle: {' -> '.join(cycle)}")
    return errors


def validate_gate(catalog: dict[str, Any], gate: str, repo_root: Path | None = None) -> list[str]:
    """Validate evidence-count gates independently from the structural validator."""
    rows = [row for row in catalog.get("commands", []) if isinstance(row, dict)]
    legacy_surfaces = [surface for row in rows for surface in row.get("legacySurfaces", [])]
    personal = sum(surface.get("ring") == "general_turn" for surface in legacy_surfaces if isinstance(surface, dict))
    chief = sum(surface.get("ring") == "nation_turn" for surface in legacy_surfaces if isinstance(surface, dict))
    errors: list[str] = []
    if gate in {"legacy", "stage-0"}:
        if personal != 46:
            errors.append(f"legacy gate requires 46 personal menu surfaces; found {personal}")
        if chief != 24:
            errors.append(f"legacy gate requires 24 chief menu surfaces; found {chief}")
        legacy_rows = [row for row in rows if row.get("legacySurfaces")]
        if len(legacy_rows) != 70:
            errors.append(f"legacy gate requires 70 one-to-one adapter rows; found {len(legacy_rows)}")
        if any(len(row.get("legacySurfaces", [])) != 1 for row in legacy_rows):
            errors.append("legacy gate requires exactly one menu surface per adapter row")
        actual_surfaces = [
            (surface.get("ring"), surface.get("category"), surface.get("legacyCode"))
            for surface in legacy_surfaces
            if isinstance(surface, dict)
        ]
        if len(actual_surfaces) != len(set(actual_surfaces)):
            errors.append("legacy gate forbids duplicate menu surfaces")
        if repo_root is not None:
            from tools.commands.build_legacy_command_catalog import read_legacy_surfaces

            expected_personal, expected_chief = read_legacy_surfaces(repo_root)
            expected_surfaces = {
                *(("general_turn", category, code) for category, code in expected_personal),
                *(("nation_turn", category, code) for category, code in expected_chief),
            }
            missing = sorted(expected_surfaces - set(actual_surfaces))
            extra = sorted(set(actual_surfaces) - expected_surfaces)
            if missing:
                errors.append(f"legacy gate missing Kotlin menu surfaces: {missing}")
            if extra:
                errors.append(f"legacy gate has non-menu surfaces: {extra}")
    if gate in {"planned", "stage-0"}:
        families = {family for row in rows for family in row.get("families", []) if isinstance(family, str)}
        required_families = set(catalog.get("requiredFamilies", []))
        if not required_families:
            errors.append("planned gate requires a non-empty requiredFamilies contract")
        if required_families != REQUIRED_PUBLIC_ALPHA_FAMILIES:
            errors.append("planned gate requiredFamilies differs from the independently reviewed product-family set")
        missing_families = sorted(required_families - families)
        if missing_families:
            errors.append(f"planned gate missing command families: {', '.join(missing_families)}")
        for family in sorted(required_families):
            owners = [
                row for row in rows
                if family in row.get("families", [])
                and row.get("contractStatus") == "FINAL"
                and isinstance(row.get("ownerIssues"), list)
                and row["ownerIssues"]
            ]
            if not owners:
                errors.append(f"planned family {family} has no final owned row")
    if gate == "stage-0":
        provisional = [row.get("canonicalId", "<unknown>") for row in rows if row.get("contractStatus") != "FINAL"]
        if provisional:
            errors.append(f"stage-0 gate forbids non-final rows: {', '.join(provisional)}")
        if not rows:
            errors.append("stage-0 gate requires at least one canonical command")
        all_events = {event for row in rows for event in row.get("eventTypes", [])}
        for required_event in ("notification.created", "recovery.resumed", "recovery.failed"):
            if required_event not in all_events:
                errors.append(f"stage-0 gate missing mandatory P-1 event ownership: {required_event}")
        for row in rows:
            canonical_id = row.get("canonicalId", "<unknown>")
            for field in ("ownerIssues", "provenance", "aiPolicy", "replayContract"):
                if not row.get(field):
                    errors.append(f"{canonical_id} stage-0 requires non-empty {field}")
            for field in ("helpTopicId", "tutorialObjectiveId"):
                value = row.get(field)
                valid_string = isinstance(value, str) and bool(value.strip())
                valid_na = (
                    isinstance(value, dict)
                    and value.get("disposition") == "N/A"
                    and isinstance(value.get("reason"), str)
                    and bool(value["reason"].strip())
                )
                if not (valid_string or valid_na):
                    errors.append(f"{canonical_id} stage-0 requires {field} or reason-bearing N/A")
            serialized = json.dumps(row, ensure_ascii=False).upper()
            for forbidden in ("TODO", "TBD", "AUTO_SUCCESS", "AUTO_REST"):
                if forbidden in serialized:
                    errors.append(f"{canonical_id} stage-0 forbids placeholder token {forbidden}")
    return errors


def _parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("catalog", type=Path)
    parser.add_argument("--gate", choices=("structural", "legacy", "planned", "stage-0"), default="stage-0")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = _parse_args(sys.argv[1:] if argv is None else argv)
    try:
        catalog = load_catalog(args.catalog)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"catalog load failed: {exc}", file=sys.stderr)
        return 2
    repo_root = Path(__file__).resolve().parents[2]
    if str(repo_root) not in sys.path:
        sys.path.insert(0, str(repo_root))
    errors = validate_catalog(catalog, repo_root)
    if args.gate != "structural":
        errors.extend(validate_gate(catalog, args.gate, repo_root))
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print(f"OK: {len(catalog['commands'])} canonical commands; gate={args.gate}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
