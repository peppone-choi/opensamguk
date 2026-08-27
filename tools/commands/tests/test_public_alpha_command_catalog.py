import copy
import json
import subprocess
import sys
import unittest
from pathlib import Path

from tools.commands.build_legacy_command_catalog import build_catalog, read_legacy_surfaces
from tools.commands.validate_public_alpha_command_catalog import (
    REQUIRED_PUBLIC_ALPHA_FAMILIES,
    resolve_command,
    validate_catalog,
    validate_gate,
)


REPO_ROOT = Path(__file__).resolve().parents[3]


def valid_row(canonical_id: str = "personal.rest") -> dict:
    return {
        "canonicalId": canonical_id,
        "aliases": ["휴식"],
        "layer": "PERSONAL_RING",
        "actorType": "GENERAL",
        "authority": {"policy": "SUBJECT_OWNER"},
        "argumentSchema": {
            "type": "object",
            "required": ["idempotencyKey"],
            "properties": {"idempotencyKey": {"type": "string"}},
        },
        "resultSchema": {
            "type": "object",
            "required": ["requestId", "status"],
            "properties": {"requestId": {"type": "string"}, "status": {"type": "string"}},
        },
        "availabilityRules": ["actor.isActive"],
        "reservationRules": {"ring": "general_turn", "slots": 1},
        "executionRules": ["preserve-legacy-result"],
        "eventTypes": ["command.accepted", "command.rejected", "command.resolved"],
        "replayContract": {"disposition": "LEGACY_LOG", "reason": "legacy ring result"},
        "aiPolicy": {"disposition": "SUPPORTED", "policy": "rest-when-no-better-action"},
        "helpTopicId": "commands.personal.rest",
        "tutorialObjectiveId": "tutorial.personal.rest",
        "replacementId": None,
        "migrationPolicy": None,
        "deliveryState": "DOMAIN_READY",
        "contractStatus": "FINAL",
        "ownerIssues": ["OPENSAM-76", "GH-218"],
        "provenance": [{"path": "common/src/main/kotlin/opensamguk/common/constants/GameConst.kt", "role": "menu"}],
        "evidence": {
            "DOMAIN_READY": [
                "spec:data/commands/public-alpha-command-catalog.json::personal.rest"
            ]
        },
    }


def valid_catalog(*rows: dict) -> dict:
    return {
        "schemaVersion": 1,
        "catalogVersion": "2026-08-27",
        "commands": list(rows or [valid_row()]),
    }


class PublicAlphaCommandCatalogValidationTest(unittest.TestCase):
    def assert_error(self, catalog: dict, text: str) -> None:
        errors = validate_catalog(catalog, REPO_ROOT)
        self.assertTrue(any(text in error for error in errors), errors)

    def test_minimal_valid_catalog_has_no_structural_errors(self) -> None:
        self.assertEqual([], validate_catalog(valid_catalog(), REPO_ROOT))

    def test_missing_required_field_is_rejected(self) -> None:
        row = valid_row()
        del row["aiPolicy"]
        self.assert_error(valid_catalog(row), "missing required fields: aiPolicy")

    def test_duplicate_canonical_id_is_rejected(self) -> None:
        self.assert_error(valid_catalog(valid_row(), valid_row()), "duplicate canonicalId personal.rest")

    def test_alias_collision_is_rejected(self) -> None:
        second = valid_row("chief.rest")
        second["layer"] = "CHIEF_RING"
        self.assert_error(valid_catalog(valid_row(), second), "alias 휴식 resolves to multiple commands")

    def test_duplicate_alias_within_one_row_is_rejected(self) -> None:
        row = valid_row()
        row["aliases"] = ["rest", "rest"]
        self.assert_error(valid_catalog(row), "aliases must not contain duplicates")

    def test_unknown_replacement_is_rejected(self) -> None:
        row = valid_row()
        row["replacementId"] = "personal.missing"
        row["migrationPolicy"] = {
            "strategy": "rewrite saved aliases",
            "savedQueuePolicy": "rewrite on load",
            "parserRemovalGate": "usage reaches zero",
        }
        self.assert_error(valid_catalog(row), "unknown replacementId personal.missing")

    def test_replacement_cycle_is_rejected(self) -> None:
        first = valid_row("personal.old-a")
        first["aliases"] = ["old-a"]
        first["replacementId"] = "personal.old-b"
        first["migrationPolicy"] = {"strategy": "map a to b", "savedQueuePolicy": "rewrite", "parserRemovalGate": "zero"}
        second = valid_row("personal.old-b")
        second["aliases"] = ["old-b"]
        second["replacementId"] = "personal.old-a"
        second["migrationPolicy"] = {"strategy": "map b to a", "savedQueuePolicy": "rewrite", "parserRemovalGate": "zero"}
        self.assert_error(valid_catalog(first, second), "replacement cycle")

    def test_replacement_requires_migration_policy(self) -> None:
        old = valid_row("personal.old")
        old["aliases"] = ["old"]
        old["replacementId"] = "personal.new"
        new = valid_row("personal.new")
        new["aliases"] = ["new"]
        self.assert_error(valid_catalog(old, new), "replacement requires structured migrationPolicy")

    def test_replacement_rejects_truthy_non_structured_migration_policy(self) -> None:
        old = valid_row("personal.old")
        old["aliases"] = ["old"]
        old["replacementId"] = "personal.new"
        old["migrationPolicy"] = True
        new = valid_row("personal.new")
        new["aliases"] = ["new"]
        self.assert_error(valid_catalog(old, new), "requires structured migrationPolicy")

    def test_invalid_delivery_state_is_rejected(self) -> None:
        row = valid_row()
        row["deliveryState"] = "DONE"
        self.assert_error(valid_catalog(row), "invalid deliveryState DONE")

    def test_delivery_state_cannot_overclaim_missing_evidence(self) -> None:
        row = valid_row()
        row["deliveryState"] = "UI_READY"
        self.assert_error(valid_catalog(row), "missing lifecycle evidence for HANDLER_READY")

    def test_contract_fields_reject_arbitrary_truthy_strings(self) -> None:
        row = valid_row()
        row["authority"] = "anything"
        row["argumentSchema"] = "anything"
        row["ownerIssues"] = ["anything"]
        errors = validate_catalog(valid_catalog(row), REPO_ROOT)
        self.assertTrue(any("authority must contain" in error for error in errors), errors)
        self.assertTrue(any("argumentSchema must be an object JSON schema" in error for error in errors), errors)
        self.assertTrue(any("ownerIssues must contain" in error for error in errors), errors)

    def test_verified_requires_typed_evidence_for_every_surface(self) -> None:
        row = valid_row()
        row["deliveryState"] = "VERIFIED"
        row["evidence"] = {state: ["not-checked:value"] for state in (
            "DOMAIN_READY", "HANDLER_READY", "UI_READY", "AI_READY", "HELP_READY",
            "TUTORIAL_READY", "REPLAY_READY", "VERIFIED",
        )}
        errors = validate_catalog(valid_catalog(row), REPO_ROOT)
        self.assertTrue(any("VERIFIED requires existing test:" in error for error in errors), errors)
        self.assertTrue(any("VERIFIED requires existing campaign:" in error for error in errors), errors)

    def test_verified_rejects_existing_but_unrelated_artifacts(self) -> None:
        row = valid_row()
        row["deliveryState"] = "VERIFIED"
        row["evidence"] = {
            "DOMAIN_READY": ["spec:data/commands/public-alpha-command-catalog.json::chief.rest"],
            "HANDLER_READY": ["test:tools/commands/tests/test_public_alpha_command_catalog.py::unittest"],
            "UI_READY": ["ui:web/gateway/__tests__/board-contract.test.ts::describe"],
            "AI_READY": ["ai:tools/commands/tests/test_public_alpha_command_catalog.py::unittest"],
            "HELP_READY": ["help:docs/superpowers/specs/2026-08-27-public-alpha-command-contract-freeze.md::P-15"],
            "TUTORIAL_READY": ["tutorial:web/gateway/__tests__/board-contract.test.ts::describe"],
            "REPLAY_READY": ["replay:tools/commands/tests/test_public_alpha_command_catalog.py::unittest"],
            "VERIFIED": ["campaign:tools/commands/tests/test_public_alpha_command_catalog.py::unittest"],
        }
        errors = validate_catalog(valid_catalog(row), REPO_ROOT)
        self.assertTrue(any("command-bound" in error for error in errors), errors)

    def test_unknown_identifier_lookup_fails_closed(self) -> None:
        self.assertIsNone(resolve_command(valid_catalog(), "unknown.command"))

    def test_lookup_accepts_canonical_id_and_alias(self) -> None:
        catalog = valid_catalog()
        self.assertEqual("personal.rest", resolve_command(catalog, "personal.rest")["canonicalId"])
        self.assertEqual("personal.rest", resolve_command(catalog, "휴식")["canonicalId"])

    def test_validation_does_not_mutate_catalog(self) -> None:
        catalog = valid_catalog()
        before = copy.deepcopy(catalog)
        validate_catalog(catalog, REPO_ROOT)
        self.assertEqual(before, catalog)


class LegacyCatalogCoverageTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.catalog_path = REPO_ROOT / "data/commands/public-alpha-command-catalog.json"
        cls.catalog = json.loads(cls.catalog_path.read_text(encoding="utf-8"))

    def test_kotlin_public_menu_counts_are_frozen(self) -> None:
        personal, chief = read_legacy_surfaces(REPO_ROOT)
        self.assertEqual(46, len(personal))
        self.assertEqual(24, len(chief))

    def test_catalog_covers_each_kotlin_menu_surface_exactly_once(self) -> None:
        personal, chief = read_legacy_surfaces(REPO_ROOT)
        expected = {
            *(('general_turn', category, code) for category, code in personal),
            *(('nation_turn', category, code) for category, code in chief),
        }
        actual_list = [
            (surface["ring"], surface["category"], surface["legacyCode"])
            for row in self.catalog["commands"]
            for surface in row.get("legacySurfaces", [])
        ]
        self.assertEqual(len(actual_list), len(set(actual_list)), "legacy menu surface is duplicated")
        self.assertEqual(expected, set(actual_list))

    def test_opensam_76_keeps_one_adapter_row_per_surface(self) -> None:
        legacy_rows = [row for row in self.catalog["commands"] if row.get("legacySurfaces")]
        self.assertEqual(70, len(legacy_rows))
        self.assertTrue(all(len(row["legacySurfaces"]) == 1 for row in legacy_rows))

    def test_rest_requires_ring_context(self) -> None:
        self.assertIsNone(resolve_command(self.catalog, "휴식"))
        self.assertEqual("personal.rest", resolve_command(self.catalog, "general_turn:휴식")["canonicalId"])
        self.assertEqual("chief.rest", resolve_command(self.catalog, "nation_turn:휴식")["canonicalId"])

    def test_each_non_rest_menu_code_exists_in_command_registry(self) -> None:
        registry_source = (REPO_ROOT / "logic/src/main/kotlin/opensamguk/logic/actions/CommandRegistry.kt").read_text(
            encoding="utf-8"
        )
        registry_codes = set(__import__("re").findall(r'"((?:che|cr|event)_[^"]+)"\s*->', registry_source))
        menu_codes = {
            surface["legacyCode"]
            for row in self.catalog["commands"]
            for surface in row.get("legacySurfaces", [])
            if surface["legacyCode"] != "휴식"
        }
        self.assertEqual(set(), menu_codes - registry_codes)

    def test_provenance_paths_exist(self) -> None:
        missing = {
            evidence["path"]
            for row in self.catalog["commands"]
            for evidence in row["provenance"]
            if not (REPO_ROOT / evidence["path"]).exists()
        }
        self.assertEqual(set(), missing)


class PlannedCatalogCoverageTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.catalog = json.loads(
            (REPO_ROOT / "data/commands/public-alpha-command-catalog.json").read_text(encoding="utf-8")
        )

    def test_every_approved_family_has_a_final_owned_row(self) -> None:
        rows_by_family = {
            family: [row for row in self.catalog["commands"] if family in row.get("families", [])]
            for family in REQUIRED_PUBLIC_ALPHA_FAMILIES
        }
        missing = {family for family, rows in rows_by_family.items() if not rows}
        not_final = {
            family
            for family, rows in rows_by_family.items()
            if rows and not any(row["contractStatus"] == "FINAL" and row["ownerIssues"] for row in rows)
        }
        self.assertEqual(set(), missing)
        self.assertEqual(set(), not_final)

    def test_old_tactical_and_realtime_layers_are_absent(self) -> None:
        layers = {row["layer"] for row in self.catalog["commands"]}
        self.assertNotIn("TACTICAL", layers)
        self.assertNotIn("STRATEGIC", layers)
        self.assertTrue({"wego-land", "wego-siege", "wego-naval"} <= {
            family for row in self.catalog["commands"] for family in row.get("families", [])
        })

    def test_planned_rows_do_not_claim_implementation(self) -> None:
        planned = [row for row in self.catalog["commands"] if not row.get("legacySurfaces")]
        self.assertTrue(planned)
        self.assertEqual({"DOMAIN_READY"}, {row["deliveryState"] for row in planned})

    def test_real_catalog_passes_stage_zero_gate(self) -> None:
        self.assertEqual([], validate_gate(self.catalog, "stage-0", REPO_ROOT))

    def test_stage_zero_rejects_provisional_or_unowned_rows(self) -> None:
        catalog = copy.deepcopy(self.catalog)
        catalog["commands"][0]["contractStatus"] = "PROVISIONAL"
        catalog["commands"][1]["ownerIssues"] = []
        errors = validate_gate(catalog, "stage-0", REPO_ROOT)
        self.assertTrue(any("forbids non-final rows" in error for error in errors), errors)
        self.assertTrue(any("stage-0 requires non-empty ownerIssues" in error for error in errors), errors)

    def test_stage_zero_rejects_blank_tutorial_disposition(self) -> None:
        catalog = copy.deepcopy(self.catalog)
        catalog["commands"][0]["tutorialObjectiveId"] = ""
        errors = validate_gate(catalog, "stage-0", REPO_ROOT)
        self.assertTrue(any("requires tutorialObjectiveId" in error for error in errors), errors)

    def test_checked_in_catalog_matches_deterministic_builder(self) -> None:
        self.assertEqual(build_catalog(REPO_ROOT), self.catalog)

    def test_system_and_admin_commands_are_not_player_or_ai_selectable(self) -> None:
        internal_rows = [
            row for row in self.catalog["commands"] if row["actorType"] in {"SYSTEM", "ADMIN"}
        ]
        self.assertTrue(internal_rows)
        self.assertEqual({"N/A"}, {row["aiPolicy"]["disposition"] for row in internal_rows})
        admin_rows = [row for row in internal_rows if row["actorType"] == "ADMIN"]
        self.assertEqual({"AUDIT_LOG"}, {row["replayContract"]["disposition"] for row in admin_rows})

    def test_layer_specific_event_families_are_frozen(self) -> None:
        persistent = next(row for row in self.catalog["commands"] if row["layer"] == "PERSISTENT_PLAN")
        self.assertTrue(
            {"plan.created", "plan.progressed", "plan.interrupted", "plan.resolved"}
            <= set(persistent["eventTypes"])
        )
        self.assertTrue({"recovery.resumed", "recovery.failed"} <= set(persistent["eventTypes"]))
        battle = next(row for row in self.catalog["commands"] if row["layer"] == "BATTLE_ROUND")
        self.assertTrue(
            {"battle.round.sealed", "battle.round.resolved", "battle.replay.published"}
            <= set(battle["eventTypes"])
        )
        all_events = {event for row in self.catalog["commands"] for event in row["eventTypes"]}
        self.assertIn("notification.created", all_events)

    def test_operation_objective_and_wego_orders_have_nested_contracts(self) -> None:
        operation = next(row for row in self.catalog["commands"] if row["canonicalId"] == "operation.create")
        objective = operation["argumentSchema"]["properties"]["objective"]
        self.assertTrue(
            {"type", "deadline", "progressRules", "supplyRequirements", "interruptionRules"}
            <= set(objective["required"])
        )
        self.assertEqual(
            {frozenset({"targetProvinceIds"}), frozenset({"targetEdgeIds"})},
            {frozenset(option["required"]) for option in objective["anyOf"]},
        )
        operation_required = set(operation["argumentSchema"]["required"])
        self.assertTrue({"objective", "participants", "route", "rules"} <= operation_required)
        self.assertNotIn("targetProvinceIds", operation_required)
        self.assertNotIn("deadline", operation_required)
        for command_id in ("battle.land.orderBatch", "battle.siege.orderBatch", "battle.naval.orderBatch"):
            row = next(row for row in self.catalog["commands"] if row["canonicalId"] == command_id)
            self.assertTrue(
                {"eligibleFormationIds", "orders", "doctrineOnMissing"}
                <= set(row["argumentSchema"]["required"])
            )
            self.assertEqual(
                ["HOLD", "FORMATION_DOCTRINE"],
                row["argumentSchema"]["properties"]["doctrineOnMissing"]["enum"],
            )
            variants = row["argumentSchema"]["properties"]["orders"]["items"]["oneOf"]
            self.assertGreaterEqual(len(variants), 8)
            for order in variants:
                self.assertTrue({"orderId", "formationId", "orderType", "sequence", "payload"} <= set(order["required"]))
                self.assertTrue(order["properties"]["orderType"]["const"])
                self.assertTrue(order["properties"]["payload"]["required"])

    def test_documented_cli_runs_as_a_direct_script(self) -> None:
        result = subprocess.run(
            [
                sys.executable,
                "tools/commands/validate_public_alpha_command_catalog.py",
                "data/commands/public-alpha-command-catalog.json",
                "--gate",
                "stage-0",
            ],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("gate=stage-0", result.stdout)


if __name__ == "__main__":
    unittest.main()
