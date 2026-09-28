"""Red probes for the frozen input-state baseline and evidence promotions."""

import copy
import json
import tempfile
import unittest
from pathlib import Path

from input_evidence_gate import BASELINE, BASELINE_SHA256, CATALOG, ROOT, check, validate


class InputEvidenceGateTest(unittest.TestCase):
    def setUp(self):
        self.catalog = json.loads((ROOT / CATALOG).read_text())
        self.baseline = json.loads((ROOT / BASELINE).read_text())
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)

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
