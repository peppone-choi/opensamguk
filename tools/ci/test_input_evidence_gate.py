"""Red probes for the frozen input-state baseline and evidence promotions."""

import copy
import json
import shutil
import tempfile
import unittest
from pathlib import Path

from input_evidence_gate import BASELINE, BASELINE_SHA256, CATALOG, ROOT, _proof, check, validate


class InputEvidenceGateTest(unittest.TestCase):
    def setUp(self):
        self.catalog = json.loads((ROOT / CATALOG).read_text())
        self.baseline = json.loads((ROOT / BASELINE).read_text())
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        for relative in ("data/help/first-steps-exclusions-v1.json",
                         "docs/development/first-steps-exclusions-v1.md"):
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / relative, target)

    def write(self, name, content):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return path

    def row(self, input_id):
        return next(row for row in self.catalog["inputs"] if row["inputId"] == input_id)

    def test_checked_in_catalog_is_pinned_and_reports_45_legacy_debts(self):
        self.assertEqual(64, len(BASELINE_SHA256))
        debt = check()
        self.assertEqual(45, len(debt))
        self.assertEqual({"HANDLER_READY", "UI_READY"}, {row["frozenState"] for row in debt})

    def assert_mapped_catalog(self):
        self.assertEqual(5, self.catalog["schemaVersion"])
        pinned = {row["inputId"]: row["deliveryState"] for row in self.baseline["entries"]}
        actual = {row["inputId"]: row for row in self.catalog["inputs"]}
        self.assertEqual(74, len(pinned))
        self.assertEqual(len(self.catalog["inputs"]), len(actual))
        self.assertLessEqual(pinned.keys(), actual.keys())
        linked = {"action.enlist": "tutorial.enlist", "action.deploy": "tutorial.march",
                  "court.dispatchReply": "tutorial.dispatch", "work.start": "tutorial.work",
                  "action.search": "tutorial.employ", "action.employ": "tutorial.employ",
                  "action.move": "tutorial.march"}
        self.assertEqual(linked, {row["inputId"]: row["firstStepsExplanationStepId"]
                                  for row in self.catalog["inputs"]
                                  if row["firstStepsExplanationStepId"] not in ("N/A", "UNMAPPED")})
        self.assertEqual(38, sum(row["firstStepsExplanationNaReason"] == "NOT_IN_FIRST_STEPS_EXPLANATION"
                                 for row in self.catalog["inputs"]))
        self.assertEqual(29, sum(row["firstStepsExplanationNaReason"] == "INPUT_PLANNED"
                                 for row in self.catalog["inputs"]))
        self.assertTrue(all(row["firstStepsExplanationStepId"] == "UNMAPPED"
                            for row in self.catalog["inputs"] if row["inputId"] not in pinned))
        self.assertTrue(all("tutorialObjectiveId" not in row and "tutorialNaReason" not in row
                            for row in self.catalog["inputs"]))
        self.assertEqual(45, len(validate(self.catalog, self.baseline, self.root)))

    def test_d21_catalog_records_74_explanations_without_stage_promotion(self):
        self.assert_mapped_catalog()

    def test_additional_planned_input_keeps_pinned_rows_and_cannot_claim_unproven_stage(self):
        for number in range(5):
            extra = copy.deepcopy(self.row("action.enlist"))
            extra["inputId"] = f"action.newUnmappedProbe{number}"
            extra["deliveryState"] = "PLANNED"
            extra["evidence"] = {}
            extra["firstStepsExplanationStepId"] = "UNMAPPED"
            extra["firstStepsExplanationNaReason"] = None
            self.catalog["inputs"].append(extra)
        self.assert_mapped_catalog()
        extra["firstStepsExplanationStepId"] = "tutorial.enlist"
        with self.assertRaises(AssertionError):
            self.assert_mapped_catalog()
        extra["firstStepsExplanationStepId"] = "UNMAPPED"
        extra["deliveryState"] = "HANDLER_READY"
        with self.assertRaisesRegex(ValueError, "declared state differs from evidence"):
            validate(self.catalog, self.baseline, self.root)
        extra["deliveryState"] = "PLANNED"
        self.catalog["inputs"] = [row for row in self.catalog["inputs"] if row["inputId"] != "action.enlist"]
        with self.assertRaisesRegex(ValueError, "pinned input removed"):
            validate(self.catalog, self.baseline, self.root)

    def test_retired_progress_fields_and_na_without_reason_fail_red(self):
        row = self.row("action.enlist")
        row["tutorialObjectiveId"] = "tutorial.enlist"
        row["tutorialNaReason"] = None
        with self.assertRaisesRegex(ValueError, "retired progress fields"):
            validate(self.catalog, self.baseline, self.root)
        row.pop("tutorialObjectiveId")
        row.pop("tutorialNaReason")
        row["firstStepsExplanationStepId"] = "N/A"
        with self.assertRaisesRegex(ValueError, "must match N/A"):
            validate(self.catalog, self.baseline, self.root)

    def test_tutorial_stage_requires_approved_article_and_shortcut(self):
        row = self.row("action.enlist")
        row["firstStepsExplanationStepId"] = "tutorial.enlist"
        article = self.write("data/help/topics.json", json.dumps({"topics": [
            {"id": "tutorial.enlist", "reviewState": "APPROVED"}
        ]}))
        shortcut = self.write("web/game/e2e/first-steps.spec.ts",
                              "tutorial.enlist action.enlist opens the enlist screen")
        article_ref = "tutorial-step:data/help/topics.json#tutorial.enlist"
        shortcut_ref = "tutorial-shortcut:web/game/e2e/first-steps.spec.ts#tutorial.enlist"
        self.assertEqual("tutorial-step", _proof(row, "TUTORIAL_READY", article_ref, self.root))
        self.assertEqual("tutorial-shortcut", _proof(row, "TUTORIAL_READY", shortcut_ref, self.root))
        row["evidence"] = {"TUTORIAL_READY": [article_ref]}
        with self.assertRaisesRegex(ValueError, "explanation and shortcut evidence required"):
            validate(self.catalog, self.baseline, self.root)
        article.write_text(json.dumps({"topics": [{"id": "tutorial.enlist", "reviewState": "DRAFT"}]}))
        with self.assertRaisesRegex(ValueError, "prose is not approved"):
            _proof(row, "TUTORIAL_READY", article_ref, self.root)
        shortcut.unlink()

    def test_unmapped_cannot_use_na_or_reach_verified(self):
        row = self.row("action.farm")
        row["firstStepsExplanationStepId"] = "UNMAPPED"
        row["firstStepsExplanationNaReason"] = None
        row["evidence"] = {"TUTORIAL_READY": ["tutorial-na:NOT_IN_FIRST_STEPS_EXPLANATION"]}
        row["deliveryState"] = "VERIFIED"
        with self.assertRaisesRegex(ValueError, "wrong first-steps N/A evidence"):
            validate(self.catalog, self.baseline, self.root)

    def test_na_needs_confirmed_exclusion_with_source(self):
        row = self.row("action.farm")
        row["firstStepsExplanationStepId"] = "N/A"
        row["firstStepsExplanationNaReason"] = "NOT_IN_FIRST_STEPS_EXPLANATION"
        reference = "tutorial-na:NOT_IN_FIRST_STEPS_EXPLANATION"
        ledger = self.write("data/help/first-steps-exclusions-v1.json",
                            json.dumps({"schemaVersion": 1, "entries": []}))
        with self.assertRaisesRegex(ValueError, "N/A is not confirmed"):
            _proof(row, "TUTORIAL_READY", reference, self.root)
        self.write("docs/development/first-steps-map.md",
                   '<a id="first-steps-exclusion"></a>\n### 농지개간 (`action.farm`)')
        ledger.write_text(json.dumps({"schemaVersion": 1, "entries": [{
            "inputId": "action.farm", "status": "CONFIRMED",
            "reason": "NOT_IN_FIRST_STEPS_EXPLANATION",
            "source": "docs/development/first-steps-map.md#first-steps-exclusion",
        }]}))
        self.assertEqual("tutorial-na", _proof(row, "TUTORIAL_READY", reference, self.root))

    def test_low_stage_na_requires_confirmed_exclusion_without_tutorial_evidence(self):
        row = self.row("action.enlist")
        row["firstStepsExplanationStepId"] = "N/A"
        row["firstStepsExplanationNaReason"] = "NOT_IN_FIRST_STEPS_EXPLANATION"
        self.assertEqual("HANDLER_READY", row["deliveryState"])
        self.assertEqual({}, row["evidence"])
        (self.root / "data/help/first-steps-exclusions-v1.json").unlink()
        existing = json.loads((ROOT / "data/help/first-steps-exclusions-v1.json").read_text())["entries"]
        with self.assertRaisesRegex(ValueError, "exclusion ledger missing"):
            validate(self.catalog, self.baseline, self.root)
        ledger = self.write("data/help/first-steps-exclusions-v1.json",
                            json.dumps({"schemaVersion": 1, "entries": []}))
        with self.assertRaisesRegex(ValueError, "N/A is not confirmed"):
            validate(self.catalog, self.baseline, self.root)
        entry = {
            "inputId": "action.enlist", "status": "CONFIRMED",
            "reason": row["firstStepsExplanationNaReason"],
            "source": "docs/development/first-steps-map.md#first-steps-exclusion",
        }
        source = self.write("docs/development/first-steps-map.md",
                            '<a id="first-steps-exclusion"></a>\n### 출사 (`action.enlist`)')
        for overrides, error in [
            ({"inputId": "action.farm"}, "N/A is not confirmed"),
            ({"status": "DRAFT"}, "N/A is not confirmed"),
            ({"reason": "UNCONFIRMED_EXCLUSION"}, "N/A is not confirmed"),
            ({"source": None}, "N/A needs source"),
            ({"source": "docs/development/../first-steps-map.md#first-steps-exclusion"},
             "unsafe first-steps N/A source"),
            ({"source": "docs/development/missing.md#first-steps-exclusion"},
             "source missing input and anchor"),
        ]:
            with self.subTest(overrides=overrides):
                ledger.write_text(json.dumps({"schemaVersion": 1, "entries": [entry | overrides]}))
                with self.assertRaisesRegex(ValueError, error):
                    validate(self.catalog, self.baseline, self.root)
        ledger.write_text(json.dumps({"schemaVersion": 1, "entries": [entry, entry]}))
        with self.assertRaisesRegex(ValueError, "N/A is not confirmed"):
            validate(self.catalog, self.baseline, self.root)
        ledger.write_text(json.dumps({"schemaVersion": 1, "entries": existing + [entry]}))
        for content in ['<a id="first-steps-exclusion"></a>', '### 출사 (`action.enlist`)']:
            with self.subTest(content=content):
                source.write_text(content)
                with self.assertRaisesRegex(ValueError, "source missing input and anchor"):
                    validate(self.catalog, self.baseline, self.root)
        source.write_text('<a id="first-steps-exclusion"></a>\n### 출사 (`action.enlist`)')
        self.assertEqual(45, len(validate(self.catalog, self.baseline, self.root)))

    def test_exclusion_source_must_name_input_inside_its_own_section(self):
        source = self.root / "docs/development/first-steps-exclusions-v1.md"
        content = source.read_text()
        marker = '<a id="first-steps-exclusion-action-scout"></a>'
        self.assertIn(marker, content)
        source.write_text(content.replace('### 첩보 (`action.scout`)', '### 첩보 (`another.input`)'))
        with self.assertRaisesRegex(ValueError, "source missing input and anchor"):
            validate(self.catalog, self.baseline, self.root)

    def test_planned_exclusion_cannot_outlive_its_delivery_state(self):
        row = self.row("stratagem.rumor")
        self.assertEqual("INPUT_PLANNED", row["firstStepsExplanationNaReason"])
        self.assertEqual("PLANNED", row["deliveryState"])
        self.write("logic/src/main/kotlin/opensamguk/logic/input/DomainProbe.kt",
                   "stratagem.rumor domain rule")
        row["deliveryState"] = "DOMAIN_READY"
        row["evidence"] = {"DOMAIN_READY": [
            "domain-rule:logic/src/main/kotlin/opensamguk/logic/input/DomainProbe.kt#stratagem.rumor"
        ]}
        with self.assertRaisesRegex(ValueError, "INPUT_PLANNED requires PLANNED"):
            validate(self.catalog, self.baseline, self.root)
        with self.assertRaisesRegex(ValueError, "INPUT_PLANNED requires PLANNED"):
            _proof(row, "TUTORIAL_READY", "tutorial-na:INPUT_PLANNED", self.root)

    def test_existing_row_cannot_claim_a_higher_state_without_evidence(self):
        self.row("action.enlist")["deliveryState"] = "UI_READY"
        with self.assertRaisesRegex(ValueError, "declared state differs from evidence"):
            validate(self.catalog, self.baseline, self.root)

    def test_new_row_cannot_claim_a_handler_without_domain_and_handler_evidence(self):
        extra = copy.deepcopy(self.row("action.enlist"))
        extra["inputId"] = "action.newEvidenceProbe"
        extra["deliveryState"] = "HANDLER_READY"
        extra["evidence"] = {}
        self.catalog["inputs"].append(extra)
        with self.assertRaisesRegex(ValueError, "declared state differs from evidence"):
            validate(self.catalog, self.baseline, self.root)

    def test_contiguous_real_ui_e2e_reference_computes_one_promotion(self):
        self.write("web/game/e2e/enlist.spec.ts", "test('action.enlist submits', async () => {})\n")
        row = self.row("action.enlist")
        row["evidence"] = {"UI_READY": ["ui-e2e:web/game/e2e/enlist.spec.ts#action.enlist"]}
        row["deliveryState"] = "UI_READY"
        self.assertEqual(45, len(validate(self.catalog, self.baseline, self.root)))
        row["evidence"]["UI_READY"] = ["ui-e2e:web/game/e2e/missing.spec.ts#action.enlist"]
        with self.assertRaisesRegex(ValueError, "evidence file missing"):
            validate(self.catalog, self.baseline, self.root)

    def test_help_topic_removal_turns_a_full_stage_chain_red(self):
        self.write("web/game/e2e/enlist.spec.ts", "test('action.enlist submits', async () => {})\n")
        self.write("app/game-engine/src/main/kotlin/Selector.kt", 'val id = "action.enlist" // selector\n')
        self.write("app/game-engine/src/test/kotlin/SelectorTest.kt", 'test("action.enlist selects") {}\n')
        help_path = self.write("data/help/topics.json", json.dumps({"topics": [
            {"id": "commands.action.enlist", "reviewState": "APPROVED"}
        ]}))
        row = self.row("action.enlist")
        row["deliveryState"] = "HELP_READY"
        row["evidence"] = {
            "UI_READY": ["ui-e2e:web/game/e2e/enlist.spec.ts#action.enlist"],
            "AI_READY": [
                "ai-selector:app/game-engine/src/main/kotlin/Selector.kt#action.enlist",
                "ai-test:app/game-engine/src/test/kotlin/SelectorTest.kt#action.enlist",
            ],
            "HELP_READY": ["help-topic:data/help/topics.json#commands.action.enlist"],
        }
        self.assertEqual(45, len(validate(self.catalog, self.baseline, self.root)))
        help_path.write_text(json.dumps({"topics": []}), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "help prose is not approved"):
            validate(self.catalog, self.baseline, self.root)

    def test_baseline_rewrite_fails_even_if_state_counts_stay_the_same(self):
        baseline = (ROOT / BASELINE).read_text()
        self.write(str(BASELINE), baseline.replace('"action.abdicate"', '"action.another"'))
        with self.assertRaisesRegex(ValueError, "v3 baseline hash changed"):
            check(self.root)


if __name__ == "__main__":
    unittest.main()
