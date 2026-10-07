"""Red probes for the frozen input-state baseline and evidence promotions."""

import copy
import hashlib
import json
import shutil
import subprocess
import tempfile
import unittest
from datetime import datetime, timedelta
from pathlib import Path

from input_evidence_gate import (BASELINE, BASELINE_SHA256, CATALOG, ROOT, _proof,
                                 _ui_source_proof, check, validate, validate_ui_runtime,
                                 ui_candidate_identity, ui_source_pins, RuntimeProofError, check_ui_runtime,
                                 record_ui_start, validate_ui_start, check_ui_shards)


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
        self.assertEqual(35, sum(row["firstStepsExplanationNaReason"] == "INPUT_PLANNED"
                                 for row in self.catalog["inputs"]))
        newly_confirmed = {
            "court.offerReply", "court.officeNominate", "court.officeNominationReview",
            "court.officeNominationReply", "court.appointSubordinate", "court.dismissSubordinate",
        }
        for input_id in newly_confirmed:
            row = actual[input_id]
            self.assertEqual("N/A", row["firstStepsExplanationStepId"])
            self.assertEqual("INPUT_PLANNED", row["firstStepsExplanationNaReason"])
            self.assertEqual("PLANNED", row["deliveryState"])
        self.assertTrue(all(row["firstStepsExplanationStepId"] == "UNMAPPED"
                            for row in self.catalog["inputs"]
                            if row["inputId"] not in pinned and row["inputId"] not in newly_confirmed))
        self.assertTrue(all("tutorialObjectiveId" not in row and "tutorialNaReason" not in row
                            for row in self.catalog["inputs"]))
        self.assertEqual(45, len(validate(self.catalog, self.baseline, self.root)))

    def test_d21_catalog_records_80_explanations_without_stage_promotion(self):
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

    def test_new_row_needs_handler_evidence_but_can_attach_it_before_domain_evidence(self):
        extra = copy.deepcopy(self.row("action.enlist"))
        extra["inputId"] = "action.newEvidenceProbe"
        extra["deliveryState"] = "HANDLER_READY"
        extra["evidence"] = {}
        self.catalog["inputs"].append(extra)
        with self.assertRaisesRegex(ValueError, "declared state differs from evidence"):
            validate(self.catalog, self.baseline, self.root)
        self.write("logic/src/test/kotlin/HandlerTest.kt", "action.newEvidenceProbe handler test")
        extra["evidence"] = {"HANDLER_READY": [
            "handler-test:logic/src/test/kotlin/HandlerTest.kt#action.newEvidenceProbe"
        ]}
        self.assertEqual(45, len(validate(self.catalog, self.baseline, self.root)))

    def test_evidence_can_arrive_out_of_order_but_verified_needs_every_stage(self):
        row = self.row("action.enlist")
        self.write("data/help/topics.json", json.dumps({"topics": [
            {"id": "commands.action.enlist", "reviewState": "APPROVED"},
            {"id": "tutorial.enlist", "reviewState": "APPROVED"},
        ]}))
        self.write("web/game/e2e/first-steps.spec.ts", "tutorial.enlist action.enlist shortcut")
        self.write("app/game-engine/src/test/kotlin/CampaignTest.kt", "action.enlist campaign test")
        row["evidence"] = {
            "TUTORIAL_READY": [
                "tutorial-step:data/help/topics.json#tutorial.enlist",
                "tutorial-shortcut:web/game/e2e/first-steps.spec.ts#tutorial.enlist",
            ],
            "HELP_READY": ["help-topic:data/help/topics.json#commands.action.enlist"],
        }
        self.assertEqual(45, len(validate(self.catalog, self.baseline, self.root)))
        row["deliveryState"] = "TUTORIAL_READY"
        self.assertEqual(45, len(validate(self.catalog, self.baseline, self.root)))
        row["deliveryState"] = "AI_READY"
        with self.assertRaisesRegex(ValueError, "declared state differs from evidence"):
            validate(self.catalog, self.baseline, self.root)
        row["deliveryState"] = "VERIFIED"
        row["evidence"]["VERIFIED"] = [
            "campaign-test:app/game-engine/src/test/kotlin/CampaignTest.kt#action.enlist"
        ]
        with self.assertRaisesRegex(ValueError, "VERIFIED requires all evidence stages"):
            validate(self.catalog, self.baseline, self.root)

        self.write("logic/src/main/kotlin/Domain.kt", "action.enlist domain rule")
        self.write("logic/src/test/kotlin/HandlerTest.kt", "action.enlist handler test")
        self.write("web/game/e2e/enlist.spec.ts", UiInputSourceProofTest.enlist_delivered())
        self.write("app/game-engine/src/main/kotlin/Selector.kt", "action.enlist selector")
        self.write("app/game-engine/src/test/kotlin/SelectorTest.kt", "action.enlist selector test")
        self.write("logic/src/test/kotlin/ReplayTest.kt", "action.enlist replay test")
        row["evidence"].update({
            "DOMAIN_READY": ["domain-rule:logic/src/main/kotlin/Domain.kt#action.enlist"],
            "HANDLER_READY": ["handler-test:logic/src/test/kotlin/HandlerTest.kt#action.enlist"],
            "UI_READY": ["ui-e2e:web/game/e2e/enlist.spec.ts#action.enlist"],
            "AI_READY": [
                "ai-selector:app/game-engine/src/main/kotlin/Selector.kt#action.enlist",
                "ai-test:app/game-engine/src/test/kotlin/SelectorTest.kt#action.enlist",
            ],
            "REPLAY_READY": ["replay-test:logic/src/test/kotlin/ReplayTest.kt#action.enlist"],
        })
        self.assertFalse(any(item["inputId"] == "action.enlist" for item in
                             validate(self.catalog, self.baseline, self.root)))

    def test_contiguous_real_ui_e2e_reference_computes_one_promotion(self):
        self.write("web/game/e2e/enlist.spec.ts", UiInputSourceProofTest.enlist_delivered())
        row = self.row("action.enlist")
        row["evidence"] = {"UI_READY": ["ui-e2e:web/game/e2e/enlist.spec.ts#action.enlist"]}
        row["deliveryState"] = "UI_READY"
        self.assertEqual(45, len(validate(self.catalog, self.baseline, self.root)))
        row["evidence"]["UI_READY"] = ["ui-e2e:web/game/e2e/missing.spec.ts#action.enlist"]
        with self.assertRaisesRegex(ValueError, "evidence file missing"):
            validate(self.catalog, self.baseline, self.root)

    def test_help_topic_removal_turns_a_full_stage_chain_red(self):
        self.write("web/game/e2e/enlist.spec.ts", UiInputSourceProofTest.enlist_delivered())
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


class UiInputSourceProofTest(unittest.TestCase):
    """Keep file tokens separate from actual input submission cases."""

    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.relative = "web/game/e2e/smoke/court.spec.ts"
        self.path = self.root / self.relative
        self.path.parent.mkdir(parents=True)
        parity = self.root / "web/game/e2e/support/parity.ts"
        parity.parent.mkdir(parents=True)
        shutil.copyfile(ROOT / "web/game/e2e/support/parity.ts", parity)
        shared = self.root / "web/shared/e2e/hitArea.ts"
        shared.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / "web/shared/e2e/hitArea.ts", shared)
        self.row = {"inputId": "court.reward"}
        self.reference = f"ui-e2e:{self.relative}#court.reward"

    def proof(self, source):
        self.path.write_text(source, encoding="utf-8")
        return _proof(self.row, "UI_READY", self.reference, self.root)

    @staticmethod
    def delivered(title="[court.reward] 상사 금액을 고르고 보낸다"):
        input_id = title.split("]", 1)[0].removeprefix("[")
        return f"""
import {{ test, expect }} from '@playwright/test';
test('{title}', {{ tag: ['@both'] }}, async ({{ page }}) => {{
  await page.goto('/game/court');
  const reward = page.locator('[data-input-id=\"{input_id}\"]');
  await expect(reward).toBeVisible();
  await reward.getByRole('textbox', {{ name: '상사 금액' }}).fill('100');
  const submit = reward.getByRole('button', {{ name: '상사 — 접수' }});
  const sent = page.waitForRequest((request) => request.method() === 'POST' &&
    new URL(request.url()).pathname === '/api/game/api/commands/court/reward');
  await submit.click();
  const request = await sent;
  expect(request.postDataJSON()).toEqual({{ retainerId: 31, money: 100 }});
}});
"""

    def test_court_reward_anchor_submit_post_and_body_are_one_case(self):
        self.assertEqual("ui-e2e", self.proof(self.delivered()))

    def test_gift_ui_contract_requires_recipient_resource_and_positive_amount(self):
        self.row = {"inputId": "action.gift"}
        self.reference = f"ui-e2e:{self.relative}#action.gift"
        source = (self.delivered("[action.gift] 증여를 보낸다")
                  .replace("/commands/court/reward", "/command/action.gift")
                  .replace("{ retainerId: 31, money: 100 }",
                           "{ targetGeneralId: 8, resource: 'MONEY', amount: 100 }"))
        self.assertEqual("ui-e2e", self.proof(source))
        for invalid in ("{ targetGeneralId: 8, resource: 'MONEY', amount: 0 }",
                        "{ targetGeneralId: 8, resource: 'INVALID', amount: 100 }",
                        "{ resource: 'MONEY', amount: 100 }"):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                self.proof(source.replace("{ targetGeneralId: 8, resource: 'MONEY', amount: 100 }", invalid))

    @staticmethod
    def enlist_delivered():
        return (UiInputSourceProofTest.delivered('[action.enlist] 출사 후보를 고르고 보낸다')
                .replace('/commands/court/reward', '/command/action.enlist')
                .replace('{ retainerId: 31, money: 100 }', "{ mode: 'GENERAL', targetId: 8 }"))

    def test_review_p1_selected_ui_submission_remains_supported(self):
        self.assertEqual('ui-e2e', self.proof(self.delivered()))
        self.assertEqual('ui-e2e', self.proof(self.object_case()))

    def test_review_p1_evaluate_fetch_after_unrelated_click_is_rejected(self):
        source = self.delivered().replace(
            "const submit = reward.getByRole('button', { name: '상사 — 접수' });",
            "const submit = page.getByRole('button', { name: '도움말' });")
        source = source.replace('  const request = await sent;', """
  await page.evaluate(() => fetch('/api/game/api/commands/court/reward', {
    method: 'POST', body: JSON.stringify({ retainerId: 31, money: 100 })
  }));
  const request = await sent;""")
        with self.assertRaisesRegex(ValueError, '프로그램 요청'):
            self.proof(source)

    def test_review_p1_selected_scope_is_required_for_sending(self):
        source = self.delivered().replace('data-input-id="court.reward"', 'data-input-id="other.input"')
        with self.assertRaisesRegex(ValueError, '선택 입력'):
            self.proof(source)

    def test_review_p1_api_requests_and_unreviewed_helpers_are_rejected(self):
        sources = {
            'request API': "await page.request.post('/api/game/api/commands/court/reward', {data:{retainerId:31,money:100}});",
            'helper': 'await sendReward(page);',
            'assertion helper': 'expect(sendReward(page)).toBeVisible();',
            'fill helper': "await reward.getByRole('textbox', {name: '상사 금액'}).fill(sendReward(page));",
            'navigation helper': 'await page.goto(sendReward(page));',
            'conditional helper': 'if (true) await sendReward(page);',
            'aliased helper': 'const send = sendReward; await send(page);',
            'script constructor': 'const socket = new WebSocket("ws://localhost");',
        }
        for label, injected in sources.items():
            source = self.delivered().replace('  const request = await sent;', injected + '\n  const request = await sent;')
            with self.subTest(label=label), self.assertRaises(ValueError):
                self.proof(source)

    def test_review_p1_hooks_imports_and_module_side_effects_are_rejected(self):
        injected = (
            "test.beforeEach(async ({page}) => { await page.evaluate(() => fetch('/api/game/api/commands/court/reward')); });",
            "test.use({ storageState: 'unreviewed.json' });",
            "import '../support/unreviewed';",
            "import {press} from '../fake/support/parity';",
            "const setup = installSubmission();",
            "installSubmission();",
        )
        for source in injected:
            with self.subTest(source=source), self.assertRaises(ValueError):
                self.proof(self.delivered() + '\n' + source)

    def test_whole_file_mentions_do_not_prove_submission(self):
        sources = {
            "제목만": "test('[court.reward] 상사', { tag: ['@both'] }, async ({page}) => {});",
            "주석만": "// court.reward\ntest('다른 시험', async () => {});",
            "대역만": "const table = { 'court.reward': { status: 'AVAILABLE' } };",
            "다른 시험": self.delivered('[other.input] 상사') +
                "test('[court.reward] 빈 시험', { tag: ['@both'] }, async () => {});",
            "잘못된 제목 ID": self.delivered('[court.rewardX] 상사') + "// court.reward",
            "POST 없음": self.delivered().replace("request.method() === 'POST'", "request.method() === 'GET'"),
            "본문 없음": self.delivered().replace("expect(request.postDataJSON()).toEqual({ retainerId: 31, money: 100 });", ""),
            "다른 경로": self.delivered().replace('/api/game/api/commands/court/reward', '/api/game/api/commands/court/appoint'),
            "부분 경로": self.delivered().replace("new URL(request.url()).pathname === '/api/game/api/commands/court/reward'", "request.url().includes('/commands/court/reward')"),
            "보내기 없음": self.delivered().replace('await submit.click();', ''),
            "다른 요청 본문": self.delivered().replace('expect(request.postDataJSON())', 'expect(otherRequest.postDataJSON())'),
            "다른 본문": self.delivered().replace('{ retainerId: 31, money: 100 }', '{ targetGeneralId: 31 }'),
            "skip": self.delivered().replace('test(', 'test.skip(', 1),
            "모바일 선택 없음": self.delivered().replace("{ tag: ['@both'] }, ", ''),
            "assertion shadowing": self.delivered().replace("  await page.goto", "  const expect = () => ({ toEqual() {} });\n  await page.goto"),
            "죽은 callback": self.delivered().replace("  await submit.click();", "  const dead = async () => { await submit.click(); };"),
            "조건부 종료": self.delivered().replace("  await submit.click();", "  if (true) return;\n  await submit.click();"),
            "문자열 속 시험": 'const text = ' + json.dumps(self.delivered()) + ';',
        }
        for reason, source in sources.items():
            with self.subTest(reason=reason), self.assertRaises(ValueError):
                self.proof(source)

    def test_actual_farm_mock_reference_is_not_an_input_delivery_proof(self):
        self.row = {"inputId": "action.farm"}
        self.reference = f"ui-e2e:{self.relative}#action.farm"
        source = (ROOT / "web/game/e2e/smoke/command-flow.spec.ts").read_text(encoding="utf-8")
        with self.assertRaises(ValueError):
            self.proof(source)

    def test_anchor_must_be_exact_selected_input_id(self):
        self.reference = f"ui-e2e:{self.relative}#court"
        with self.assertRaises(ValueError):
            self.proof(self.delivered())

    def test_templates_nested_callbacks_and_braces_do_not_clip_the_case(self):
        source = self.delivered().replace("  await page.goto", '''
  const text = `문자열 } { test('가짜', () => {})`;
  const unused = () => { const nested = () => ({ note: '}' }); };
  await page.goto''')
        self.assertEqual("ui-e2e", self.proof(source))

    def test_immutable_parameterized_case_binds_title_route_and_body(self):
        source = self.delivered()
        start = source.index("test('[court.reward]")
        body = source[start:]
        body = body.replace("'[court.reward] 상사 금액을 고르고 보낸다'", "`[${inputId}] 상사 금액을 고르고 보낸다`")
        body = body.replace("'/api/game/api/commands/court/reward'", 'path')
        body = body.replace('{ retainerId: 31, money: 100 }', 'expected')
        source = source[:start] + '''const cases = [
  ['court.reward', '/api/game/api/commands/court/reward', { retainerId: 31, money: 100 }]
] as const;
for (const [inputId, path, expected] of cases) {
''' + body + '\n}\n'
        self.assertEqual("ui-e2e", self.proof(source))
        self.assertEqual(1, len(_ui_source_proof('court.reward', self.relative, 'court.reward', self.root)['cases']))
        with self.assertRaises(ValueError):
            self.proof(source.replace("'/api/game/api/commands/court/reward'", "'/api/game/api/commands/court/appoint'"))

    def test_reviewed_parity_press_uses_the_selected_locator(self):
        source = self.delivered().replace("test, expect", "test, expect")
        source = "import { press, BOTH } from '../support/parity';\n" + source
        source = source.replace("['@both']", '[BOTH]').replace('await submit.click();', 'await press(submit, info);')
        source = source.replace('async ({ page })', 'async ({ page }, info)')
        self.assertEqual('ui-e2e', self.proof(source))
        parity = self.root / 'web/game/e2e/support/parity.ts'
        parity.write_text(parity.read_text().replace('page.mouse.click(x, y)', 'Promise.resolve()'))
        with self.assertRaises(ValueError):
            self.proof(source)

    def test_review_followup_bound_scope_and_reviewed_press_keep_literal_expect_messages(self):
        source = self.object_case().replace(
            'expect(request.postDataJSON()).toEqual(c.args);',
            "expect(request.postDataJSON(), '정본 본문').toEqual(c.args);")
        source = source.replace('    const sent = page.waitForRequest',
            "    await expect(submit, '선택 입력').toBeVisible();\n    const sent = page.waitForRequest")
        self.assertEqual('ui-e2e', self.proof(source))
        self.assertEqual('ui-e2e', self.proof(self.delivered()))

    def test_review_followup_expect_message_cannot_execute_a_module_helper(self):
        source = self.delivered().replace(
            'expect(request.postDataJSON())',
            'expect(request.postDataJSON(), dynamicMessage())')
        source += "\nfunction dynamicMessage() { globalThis.sideEffect = true; return '설명'; }\n"
        with self.assertRaises(ValueError):
            self.proof(source)

    def test_review_noncall_conditional_cannot_execute_a_module_helper(self):
        self.assertEqual('ui-e2e', self.proof(self.delivered()))
        source = self.delivered().replace(
            '  await submit.click();',
            '  await (true ? hiddenSideEffect() : Promise.resolve());\n  await submit.click();')
        source += "\nfunction hiddenSideEffect() { globalThis.sideEffect = true; return Promise.resolve(); }\n"
        with self.assertRaisesRegex(ValueError, '미검증 실행식'):
            self.proof(source)

    def test_review_noncall_void_cannot_execute_a_module_helper(self):
        self.assertEqual('ui-e2e', self.proof(self.delivered()))
        source = self.delivered().replace(
            '  await submit.click();',
            '  void hiddenSideEffect();\n  await submit.click();')
        source += "\nfunction hiddenSideEffect() { globalThis.sideEffect = true; }\n"
        with self.assertRaisesRegex(ValueError, '미검증 실행식'):
            self.proof(source)

    def test_review_followup_accessible_names_and_scope_escape_are_not_input_regions(self):
        original = 'const reward = page.locator(\'[data-input-id="court.reward"]\');'
        mutations = {
            '접근성 이름': "const reward = page.getByRole('region', {name: '[data-input-id=\"court.reward\"]'});",
            '부모 이동': original[:-1] + ".locator('xpath=..');",
            '형제 이동': original.replace('court.reward\"]', 'court.reward\"] + button'),
        }
        for label, replacement in mutations.items():
            with self.subTest(label=label), self.assertRaises(ValueError):
                source = self.delivered()
                self.assertIn(original, source)
                self.proof(source.replace(original, replacement))

    def test_review_function_assertion_declaration_preserves_import_identity(self):
        normal = self.delivered().replace(
            "  await page.goto", "  function unusedAudit() { return 'literal'; }\n  await page.goto")
        self.assertEqual('ui-e2e', self.proof(normal))
        source = self.delivered().replace(
            "  await page.goto", "  function expect(value) { return {toBeVisible() {}, toEqual(expected) {}}; }\n  await page.goto")
        with self.assertRaisesRegex(ValueError, 'binding shadowing'):
            self.proof(source)

    def test_review_function_import_aliases_preserve_assertion_and_press_identity(self):
        normal = self.delivered().replace('test, expect', 'test as scenario, expect as verify')
        normal = normal.replace("test('", "scenario('").replace('expect(', 'verify(')
        self.assertEqual('ui-e2e', self.proof(normal))
        source = normal.replace("  await page.goto", "  function verify(value) { return {toBeVisible() {}, toEqual(expected) {}}; }\n  await page.goto")
        with self.assertRaisesRegex(ValueError, 'binding shadowing'):
            self.proof(source)
        press = self.object_case().replace('press, BOTH', 'press as send, BOTH').replace('press(', 'send(')
        self.assertEqual('ui-e2e', self.proof(press))
        with self.assertRaisesRegex(ValueError, 'binding shadowing'):
            self.proof(press.replace('    await page.goto', '    function send() {}\n    await page.goto'))

    def test_review_callback_named_and_destructured_bindings_preserve_import_identity(self):
        self.assertEqual('ui-e2e', self.proof(self.delivered()))
        mutations = {
            'named callback': ('async ({ page }) =>', 'async function expect({ page })'),
            'fixture assertion': ('async ({ page })', 'async ({ page, expect })'),
            'fixture alias': ('async ({ page })', 'async ({ page, other: expect })'),
        }
        for label, (before, after) in mutations.items():
            with self.subTest(label=label), self.assertRaisesRegex(ValueError, 'binding shadowing'):
                self.proof(self.delivered().replace(before, after))

    def test_review_fixture_row_and_observed_value_declarations_keep_identity(self):
        self.assertEqual('ui-e2e', self.proof(self.object_case()))
        for identifier in ('page', 'info', 'c', 'submit', 'sent', 'request'):
            with self.subTest(identifier=identifier), self.assertRaisesRegex(ValueError, 'binding shadowing'):
                source = self.object_case().replace(
                    '    expect(request.postDataJSON())',
                    f'    function {identifier}() {{}}\n    expect(request.postDataJSON())')
                self.proof(source)
        with self.assertRaisesRegex(ValueError, 'binding shadowing'):
            self.proof(self.object_case().replace('async ({page}, info)', 'async ({page}, c)'))

    def test_review_global_url_identity_rejects_lexical_declarations_and_import_aliases(self):
        normal = self.delivered().replace('test, expect', 'test as scenario, expect as verify')
        normal = normal.replace("test('", "scenario('").replace('expect(', 'verify(')
        self.assertEqual('ui-e2e', self.proof(normal))
        self.assertEqual('ui-e2e', self.proof(normal.replace(
            '  await page.goto', '  function unusedAudit() {}\n  await page.goto')))
        mutants = {
            'module function': 'function URL() {}\n' + normal,
            'module alias': 'const URL = () => null;\n' + normal,
            'describe function': normal.replace("scenario('[court.reward]", "scenario.describe('범위', () => { function URL() {};\nscenario('[court.reward]") + '\n});',
            'callback function': normal.replace('  await page.goto', '  function URL() {}\n  await page.goto'),
            'import alias': normal.replace('test as scenario', 'test as URL, test as scenario'),
            'fixture alias': normal.replace('async ({ page })', 'async ({ page, other: URL })'),
            'named callback': normal.replace('async ({ page }) =>', 'async function URL({ page })'),
        }
        for label, source in mutants.items():
            with self.subTest(label=label), self.assertRaisesRegex(ValueError, 'binding shadowing'):
                self.proof(source)

    def test_review_global_url_identity_rejects_predicate_parameter_binding(self):
        self.assertEqual('ui-e2e', self.proof(self.delivered()))
        source = self.delivered().replace('(request) => request.method()', '(URL) => URL.method()')
        source = source.replace('new URL(request.url())', 'new URL(URL.url())')
        with self.assertRaisesRegex(ValueError, 'binding shadowing'):
            self.proof(source)
        for before, after in [('(request) =>', '(request = missing()) =>'),
                              ('(request) =>', '(request, extra) =>')]:
            with self.subTest(after=after), self.assertRaises(ValueError):
                self.proof(self.delivered().replace(before, after))

    @staticmethod
    def object_case():
        return '''
import {test, expect} from '@playwright/test';
import {press, BOTH} from '../support/parity';
const CASES = [{inputId:'court.reward', name:'상사', reads:{missing:null}, picks:['부장'],
  path:'/api/game/api/commands/court/reward', args:{retainerId:31,money:100}}] as const;
for (const c of CASES) {
  test(`[${c.inputId}] ${c.name}: 흐름에서 고르고 보낸다`, {tag:[BOTH]}, async ({page}, info) => {
    await page.goto(`/game?do=${c.inputId}`);
    const flow = page.getByTestId('command-flow');
    for (const pick of c.picks) await press(flow.getByRole('option',{name:pick}).first(), info);
    const submit = flow.locator(`[data-input-id="${c.inputId}"][data-input-status]`);
    const sent = page.waitForRequest((r) => r.method() === 'POST' && new URL(r.url()).pathname === c.path);
    await press(submit, info);
    const request = await sent;
    expect(request.postDataJSON()).toEqual(c.args);
  });
}
'''

    def test_fixed_object_row_binds_deep_link_derived_locator_and_same_request(self):
        self.assertEqual('ui-e2e', self.proof(self.object_case()))

    def test_object_row_mutations_dynamic_lookup_other_request_and_missing_submit_fail(self):
        source = self.object_case()
        mutants = {
            '다른 인자': source.replace('toEqual(c.args)', 'toEqual(c.reads)'),
            '다른 요청': source.replace('expect(request.postDataJSON())', 'expect(mockRequest.postDataJSON())'),
            '보내기 없음': source.replace('await press(submit, info);', ''),
            '배열 변이': source.replace('for (const c of CASES)', "CASES.push(CASES[0]);\nfor (const c of CASES)"),
            '함수 속 변이': source.replace('for (const c of CASES)', "function change() { CASES[0].args.money = 1; }\nfor (const c of CASES)"),
            '조건 속 변이': source.replace('await press(submit, info);', 'if (true) c.args.money = 1;\nawait press(submit, info);'),
            '인자 객체 전달': source.replace('await press(submit, info);', 'Object.assign(c.args, {money:1});\nawait press(submit, info);'),
            '별칭 인자 전달': source.replace('await press(submit, info);', 'const expected = c.args;\nmutate(expected);\nawait press(submit, info);'),
            '조건 속 별칭 변이': source.replace('await press(submit, info);', 'const expected = c.args;\nif (true) expected.money = 1;\nawait press(submit, info);'),
            '행 객체 전달': source.replace('await press(submit, info);', 'mutate(c);\nawait press(submit, info);'),
            '동적 lookup': source.replace('toEqual(c.args)', "toEqual(c['args'])"),
            '다른 행': source.replace('toEqual(c.args)', 'toEqual(CASES[1].args)'),
            '콜백 shadow': source.replace('const flow =', 'const c = {args:{retainerId:31,money:100}};\nconst flow ='),
            '대역 값': source.replace('toEqual(c.args)', 'toEqual(mock.args)'),
            '조건부 본문': source.replace('expect(request.postDataJSON()).toEqual(c.args);', 'if (true) expect(request.postDataJSON()).toEqual(c.args);'),
        }
        for name, mutant in mutants.items():
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.proof(mutant)


class UiRuntimeProofTest(unittest.TestCase):
    """합성 JSON으로 runtime validator를 시험한다. 브라우저 실행 증거는 아니다."""

    def setUp(self):
        self.root = Path('/source')
        self.head = 'a' * 40
        self.path = 'web/game/e2e/smoke/court.spec.ts'
        self.title = '[court.reward] 상사를 보낸다'
        self.sources = {self.path: 'b' * 64}
        self.proofs = [{'path': self.path, 'inputId': 'court.reward', 'sourceSha256': 'b' * 64,
                        'paritySha256': None, 'cases': [{'title': self.title}]}]
        self.phase = {'schema': 'web-e2e-phase-v1', 'app': 'game', 'phase': 'smoke',
                      'headSha': self.head, 'workflowSha': 'c' * 40, 'runId': '123', 'runAttempt': '1',
                      'recordState': 'FINISHED', 'testState': 'PLAYWRIGHT_FINISHED',
                      'playwrightInvoked': True, 'exitCode': 0, 'playwrightExitCode': 0,
                      'workflowStepOutcome': 'success', 'startedAt': '2026-10-02T00:00:00Z',
                      'finishedAt': '2026-10-02T00:01:00Z'}
        self.tests = [{'projectName': project, 'expectedStatus': 'passed', 'status': 'expected', 'annotations': [],
                       'results': [{'status': 'passed', 'errors': [], 'retry': 0, 'workerIndex': 0,
                                    'startTime': '2026-10-02T00:00:00Z', 'duration': 10}]}
                      for project in ('desktop', 'mobile')]
        self.report = {'config': {'rootDir': '/source/web/game/e2e'}, 'errors': [],
                       'stats': {'expected': 2, 'skipped': 0, 'unexpected': 0, 'flaky': 0},
                       'suites': [{'suites': [], 'specs': [{'file': 'smoke/court.spec.ts',
                                    'title': self.title, 'ok': True, 'tests': self.tests}]}]}

    def proof(self):
        return validate_ui_runtime(self.proofs, self.phase, self.report, self.head, self.root, self.sources)

    def test_same_candidate_and_both_expected_executions(self):
        result = self.proof()
        self.assertEqual('UI_CASES_PASSED', result['state'])
        self.assertEqual(2, result['uiCasesPassed'])
        self.assertEqual({'desktop', 'mobile'}, {row['project'] for row in result['cases']})

    def test_unfinished_or_other_head_phase_is_not_execution_proof(self):
        for key, value in [('headSha', 'd' * 40), ('recordState', 'RUNNING'), ('playwrightInvoked', False),
                           ('playwrightExitCode', 1), ('exitCode', False), ('workflowStepOutcome', 'skipped'),
                           ('finishedAt', None), ('runId', None)]:
            phase = copy.deepcopy(self.phase)
            phase[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_ui_runtime(self.proofs, phase, self.report, self.head, self.root, self.sources)

    def test_one_project_skip_or_failed_result_is_rejected(self):
        for mutation in ('one-project', 'skip', 'retry', 'expected-failure', 'missing-start', 'duplicate', 'other-title', 'other-file'):
            report = copy.deepcopy(self.report)
            spec = report['suites'][0]['specs'][0]
            test = spec['tests'][1]
            if mutation == 'one-project': spec['tests'].pop()
            elif mutation == 'skip': test['results'][0]['status'] = 'skipped'
            elif mutation == 'retry': test['results'][0]['retry'] = 1
            elif mutation == 'expected-failure': test['expectedStatus'] = 'failed'
            elif mutation == 'missing-start': test['results'][0].pop('startTime')
            elif mutation == 'duplicate': spec['tests'].append(copy.deepcopy(test))
            elif mutation == 'other-title': spec['title'] = '[court.rewardX] 다른 시험'
            elif mutation == 'other-file': spec['file'] = 'smoke/another.spec.ts'
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                validate_ui_runtime(self.proofs, self.phase, report, self.head, self.root, self.sources)

    def test_candidate_blob_and_helper_must_match_executed_source(self):
        self.sources[self.path] = 'e' * 64
        with self.assertRaises(ValueError): self.proof()
        self.sources[self.path] = 'b' * 64
        self.proofs[0]['paritySha256'] = 'f' * 64
        with self.assertRaises(ValueError): self.proof()

    def test_global_skip_or_flaky_report_does_not_hide_cases(self):
        for key in ('skipped', 'unexpected', 'flaky'):
            report = copy.deepcopy(self.report)
            report['stats'][key] = 1
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_ui_runtime(self.proofs, self.phase, report, self.head, self.root, self.sources)

    def test_no_selected_proofs_is_not_all_inputs_passed(self):
        result = validate_ui_runtime([], self.phase, self.report, self.head, self.root, {})
        self.assertEqual('NO_UI_PROOFS', result['state'])
        self.assertEqual(0, result['uiCasesPassed'])
        self.report['stats']['unexpected'] = 1
        with self.assertRaises(ValueError):
            validate_ui_runtime([], self.phase, self.report, self.head, self.root, {})

    def test_outside_smoke_static_proof_missing_from_actual_selector_is_rejected(self):
        self.proofs[0]['path'] = 'web/game/e2e/input-delivery.spec.ts'
        self.sources[self.proofs[0]['path']] = 'b' * 64
        with self.assertRaisesRegex(ValueError, 'missing desktop/mobile'):
            self.proof()

    def test_new_ui_proof_cannot_use_skipped_web_or_missing_selected_case(self):
        self.phase['workflowStepOutcome'] = 'skipped'
        with self.assertRaises(ValueError): self.proof()
        self.phase['workflowStepOutcome'] = 'success'
        self.report['suites'][0]['specs'] = []
        with self.assertRaisesRegex(ValueError, 'missing desktop/mobile'):
            self.proof()


class UiCandidateIdentityTest(unittest.TestCase):
    """Check commit/merge identity and working mutations in temporary Git repositories."""

    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.git('init', '-q', '-b', 'main')
        self.git('config', 'user.name', '검증')
        self.git('config', 'user.email', 'test@example.invalid')
        (self.root / 'spec.ts').write_text('same source\n')
        self.git('add', 'spec.ts')
        self.git('commit', '-qm', '기준')
        self.base = self.git('rev-parse', 'HEAD')
        self.git('checkout', '-qb', 'candidate')
        (self.root / 'case.txt').write_text('case\n')
        self.git('add', 'case.txt')
        self.git('commit', '-qm', '후보')
        self.candidate = self.git('rev-parse', 'HEAD')
        self.git('checkout', '-q', 'main')
        self.git('merge', '--no-ff', '-qm', '통합', 'candidate')
        self.checkout = self.git('rev-parse', 'HEAD')
        repo = {'full_name': 'owner/repo'}
        self.event = {'repository': repo, 'pull_request': {
            'head': {'sha': self.candidate, 'repo': repo}, 'base': {'sha': self.base, 'repo': repo}}}
        self.context = {'GITHUB_WORKFLOW': 'CI', 'GITHUB_WORKFLOW_REF': 'owner/repo/.github/workflows/ci.yml@refs/pull/1/merge',
                        'GITHUB_WORKFLOW_SHA': 'a' * 40, 'GITHUB_RUN_ID': '123', 'GITHUB_RUN_ATTEMPT': '1',
                        'GITHUB_EVENT_NAME': 'pull_request', 'GITHUB_REPOSITORY': 'owner/repo', 'GITHUB_SHA': self.checkout}

    def git(self, *arguments):
        completed = subprocess.run(['git', *arguments], cwd=self.root, check=True, capture_output=True, text=True)
        return completed.stdout.strip()

    def identity(self):
        return ui_candidate_identity(self.event, self.context, self.root)

    def test_actual_merge_and_candidate_are_recorded_separately(self):
        identity = self.identity()
        self.assertEqual(self.candidate, identity['candidateSha'])
        self.assertEqual(self.checkout, identity['actualCheckoutSha'])
        self.assertEqual([self.base, self.candidate], identity['checkoutParents'])
        pins = ui_source_pins({'spec.ts'}, identity, self.root)
        self.assertEqual(pins[0]['candidateBlobSha256'], pins[0]['checkoutBlobSha256'])
        self.assertEqual(pins[0]['checkoutBlobSha256'], pins[0]['workingSha256'])

    def advanced_merge(self):
        self.git('checkout', '-q', 'main')
        self.git('reset', '--hard', self.base)
        (self.root / 'main-advance.txt').write_text('actual main advance\n')
        self.git('add', 'main-advance.txt')
        self.git('commit', '-qm', '실제 main 전진')
        actual_base = self.git('rev-parse', 'HEAD')
        self.git('merge', '--no-ff', '-qm', '전진한 main과 같은 후보 통합', 'candidate')
        self.context['GITHUB_SHA'] = self.git('rev-parse', 'HEAD')
        return actual_base

    def test_actual_ci_advanced_merge_preserves_original_base_and_selected_bytes(self):
        actual_base = self.advanced_merge()
        identity = self.identity()
        self.assertEqual(self.base, identity['baseSha'])
        self.assertEqual([actual_base, self.candidate], identity['checkoutParents'])
        self.assertEqual(self.candidate, identity['candidateSha'])
        self.assertEqual(self.context['GITHUB_SHA'], identity['actualCheckoutSha'])
        pins = ui_source_pins({'spec.ts'}, identity, self.root)
        self.assertEqual(pins[0]['candidateBlobSha256'], pins[0]['workingSha256'])
        (self.root / 'spec.ts').write_text('dirty selected bytes\n')
        with self.assertRaisesRegex(RuntimeProofError, 'SOURCE_PIN_MISMATCH'):
            ui_source_pins({'spec.ts'}, identity, self.root)

    def test_actual_ci_advanced_merge_rejects_unrelated_and_reverse_base(self):
        actual_base = self.advanced_merge()
        self.git('checkout', '--orphan', 'unrelated-main')
        self.git('commit', '-qm', '계보가 다른 실제 커밋')
        unrelated = self.git('rev-parse', 'HEAD')
        tree = self.git('rev-parse', f'{self.context["GITHUB_SHA"]}^{{tree}}')
        completed = subprocess.run(['git', 'commit-tree', tree, '-p', unrelated, '-p', self.candidate],
                                   cwd=self.root, input='계보가 다른 통합\n', text=True,
                                   capture_output=True, check=True)
        bad_merge = completed.stdout.strip()
        self.git('checkout', '-q', '--detach', bad_merge)
        self.context['GITHUB_SHA'] = bad_merge
        with self.assertRaisesRegex(RuntimeProofError, 'MERGE_BASE_PARENT_MISMATCH'):
            self.identity()
        self.git('checkout', '-q', '--detach', self.checkout)
        self.context['GITHUB_SHA'] = self.checkout
        self.event['pull_request']['base']['sha'] = actual_base
        with self.assertRaisesRegex(RuntimeProofError, 'MERGE_BASE_PARENT_MISMATCH'):
            self.identity()

    def test_actual_ci_advanced_merge_still_requires_exact_candidate_parent(self):
        self.advanced_merge()
        self.event['pull_request']['head']['sha'] = self.base
        with self.assertRaisesRegex(RuntimeProofError, 'MERGE_BASE_PARENT_MISMATCH'):
            self.identity()

    def test_wrong_checkout_base_parent_or_missing_object_is_rejected(self):
        for mutation in ('checkout', 'base', 'parent', 'missing'):
            event, context = copy.deepcopy(self.event), self.context.copy()
            if mutation == 'checkout': context['GITHUB_SHA'] = self.candidate
            elif mutation == 'base': event['pull_request']['base']['sha'] = self.candidate
            elif mutation == 'parent': event['pull_request']['head']['sha'] = self.base
            elif mutation == 'missing': event['pull_request']['head']['sha'] = 'f' * 40
            with self.subTest(mutation=mutation), self.assertRaises(RuntimeProofError):
                ui_candidate_identity(event, context, self.root)

    def test_dirty_working_source_and_different_checkout_blob_fail(self):
        identity = self.identity()
        (self.root / 'spec.ts').write_text('modified working\n')
        with self.assertRaisesRegex(RuntimeProofError, 'SOURCE_PIN_MISMATCH'):
            ui_source_pins({'spec.ts'}, identity, self.root)
        self.git('add', 'spec.ts')
        self.git('commit', '-qm', '다른 실행 판')
        identity['actualCheckoutSha'] = self.git('rev-parse', 'HEAD')
        with self.assertRaisesRegex(RuntimeProofError, 'SOURCE_PIN_MISMATCH'):
            ui_source_pins({'spec.ts'}, identity, self.root)

    def test_full_receipt_keeps_no_proofs_separate_and_preserves_smoke_failure(self):
        paths = ('tools/ci/input_evidence_gate.py', 'tools/ci/ui_input_proof.mjs',
                 'tools/ci/package.json', 'tools/ci/package-lock.json', str(CATALOG), str(BASELINE),
                 'data/commands/input-evidence-debt-v1.json', 'data/help/first-steps-exclusions-v1.json',
                 'docs/development/first-steps-exclusions-v1.md')
        for relative in paths:
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / relative, target)
        self.git('add', *paths)
        self.git('commit', '-qm', '도구와 실제 기준선')
        base = self.git('rev-parse', 'HEAD')
        self.git('checkout', '-qb', 'ci-candidate')
        (self.root / 'case.txt').write_text('새 후보\n')
        self.git('add', 'case.txt')
        self.git('commit', '-qm', '검증 후보')
        candidate = self.git('rev-parse', 'HEAD')
        self.git('checkout', '-q', 'main')
        self.git('merge', '--no-ff', '-qm', '검증 통합', 'ci-candidate')
        context = self.context.copy()
        context['GITHUB_SHA'] = self.git('rev-parse', 'HEAD')
        event = copy.deepcopy(self.event)
        event['pull_request']['head']['sha'], event['pull_request']['base']['sha'] = candidate, base
        runtime = UiRuntimeProofTest()
        runtime.setUp()
        phase = runtime.phase
        phase.update(headSha=candidate, workflowSha=context['GITHUB_WORKFLOW_SHA'],
                     workflow='CI', event='pull_request', repository='owner/repo')
        report = runtime.report
        report['config']['rootDir'] = str(self.root / 'web/game/e2e')
        for name, document in [('event.json', event), ('phase.json', phase), ('results.json', report)]:
            (self.root / name).write_text(json.dumps(document))
        context['GITHUB_EVENT_PATH'] = str(self.root / 'event.json')
        start, code = record_ui_start(self.root / 'event.json', self.root, context)
        self.assertEqual(0, code, start)
        phase['uiInputStart'] = start
        generated = datetime.fromisoformat(start['generatedAt'])
        phase['startedAt'] = (generated + timedelta(seconds=1)).isoformat()
        phase['finishedAt'] = (generated + timedelta(seconds=2)).isoformat()
        report['stats']['startTime'] = phase['startedAt']
        (self.root / 'results.json').write_text(json.dumps(report))
        (self.root / 'phase.json').write_text(json.dumps(phase))
        receipt, code = check_ui_runtime(self.root / 'phase.json', self.root / 'results.json',
                                         self.root / 'event.json', self.root, context)
        self.assertEqual(0, code, receipt)
        self.assertEqual(1, receipt['schemaVersion'])
        self.assertEqual('NO_UI_PROOFS', receipt['status'])
        self.assertEqual(candidate, receipt['candidateSha'])
        self.assertEqual(context['GITHUB_SHA'], receipt['actualCheckoutSha'])
        self.assertEqual(6, len(receipt['sourcePins']))
        self.assertEqual([], receipt['proofs'])
        original_catalog = (self.root / CATALOG).read_bytes()
        dirty_catalog = json.loads(original_catalog)
        dirty_catalog['inputs'][0]['displayName'] = '선택 오염'
        (self.root / CATALOG).write_text(json.dumps(dirty_catalog))
        rejected, code = record_ui_start(self.root / 'event.json', self.root, context)
        self.assertEqual(1, code)
        self.assertEqual('FAILED', rejected['status'])
        self.assertIn('SOURCE_PIN_MISMATCH', rejected['reasons'])
        rejected, code = check_ui_runtime(self.root / 'phase.json', self.root / 'results.json',
                                          self.root / 'event.json', self.root, context)
        self.assertEqual(1, code)
        self.assertEqual('FAILED', rejected['status'])
        (self.root / CATALOG).write_bytes(original_catalog)
        phase['workflowStepOutcome'] = 'failure'
        (self.root / 'phase.json').write_text(json.dumps(phase))
        failed, code = check_ui_runtime(self.root / 'phase.json', self.root / 'results.json',
                                        self.root / 'event.json', self.root, context)
        self.assertEqual(1, code)
        self.assertEqual('FAILED', failed['status'])
        self.assertTrue(failed['reasons'])
        unavailable, code = check_ui_runtime(self.root / 'missing-phase.json', self.root / 'results.json',
                                             self.root / 'event.json', self.root, context)
        self.assertEqual(1, code)
        self.assertEqual('UNAVAILABLE', unavailable['status'])
        # 실제 catalog 모양의 새 UI 참조도 Git 후보/merge/working 및 두 프로젝트를 연결한다.
        self.git('checkout', '-qb', 'ci-ui-proof')
        catalog = json.loads((self.root / CATALOG).read_text())
        row = next(item for item in catalog['inputs'] if item['inputId'] == 'court.reward')
        row['deliveryState'] = 'UI_READY'
        row['evidence'] = {'UI_READY': ['ui-e2e:web/game/e2e/smoke/court.spec.ts#court.reward']}
        (self.root / CATALOG).write_text(json.dumps(catalog))
        spec = self.root / 'web/game/e2e/smoke/court.spec.ts'
        spec.parent.mkdir(parents=True)
        spec.write_text(UiInputSourceProofTest.delivered(runtime.title))
        self.git('add', str(CATALOG), 'web/game/e2e/smoke/court.spec.ts')
        self.git('commit', '-qm', '합성 UI 증거 후보')
        candidate = self.git('rev-parse', 'HEAD')
        self.git('checkout', '-q', 'main')
        base = self.git('rev-parse', 'HEAD')
        self.git('merge', '--no-ff', '-qm', '합성 UI 증거 통합', 'ci-ui-proof')
        context['GITHUB_SHA'] = self.git('rev-parse', 'HEAD')
        event['pull_request']['head']['sha'], event['pull_request']['base']['sha'] = candidate, base
        phase.update(headSha=candidate, workflowStepOutcome='success')
        (self.root / 'event.json').write_text(json.dumps(event))
        start, code = record_ui_start(self.root / 'event.json', self.root, context)
        self.assertEqual(0, code, start)
        phase['uiInputStart'] = start
        generated = datetime.fromisoformat(start['generatedAt'])
        phase['startedAt'] = (generated + timedelta(seconds=1)).isoformat()
        phase['finishedAt'] = (generated + timedelta(seconds=2)).isoformat()
        report['stats']['startTime'] = phase['startedAt']
        (self.root / 'results.json').write_text(json.dumps(report))
        (self.root / 'phase.json').write_text(json.dumps(phase))
        verified, code = check_ui_runtime(self.root / 'phase.json', self.root / 'results.json',
                                          self.root / 'event.json', self.root, context)
        self.assertEqual(0, code, verified)
        self.assertEqual('UI_RUNTIME_VERIFIED', verified['status'])
        self.assertEqual(2, len(verified['proofs']))
        report['suites'][0]['specs'] = []
        (self.root / 'results.json').write_text(json.dumps(report))
        missing, code = check_ui_runtime(self.root / 'phase.json', self.root / 'results.json',
                                         self.root / 'event.json', self.root, context)
        self.assertEqual(1, code)
        self.assertEqual('FAILED', missing['status'])


class UiStartRecordTest(unittest.TestCase):
    def setUp(self):
        self.identity = {'producer': {'runId': '123', 'runAttempt': '1', 'event': 'pull_request'},
                         'candidateSha': 'a' * 40, 'actualCheckoutSha': 'b' * 40,
                         'baseSha': 'c' * 40, 'checkoutParents': ['c' * 40, 'a' * 40]}
        self.pins = [{'path': 'data/commands/input-catalog.json', 'candidateBlobSha256': 'd' * 64,
                      'checkoutBlobSha256': 'd' * 64, 'workingSha256': 'd' * 64}]
        self.proofs = [{'inputId': 'court.reward', 'cases': [{'title': '[court.reward] 상사'}]}]
        self.start = dict(self.identity, schemaVersion=1, kind='ui-input-start', sourcePins=self.pins,
                          selectedProofs=self.proofs, generatedAt='2026-10-02T00:00:00Z',
                          status='STATIC_PROOF_VALID', reasons=[])

    def test_same_start_identity_source_and_selected_cases_are_required(self):
        validate_ui_start(self.start, self.identity, self.pins, self.proofs)
        for field, replacement in [('candidateSha', 'e' * 40), ('actualCheckoutSha', 'e' * 40),
                                    ('baseSha', 'e' * 40), ('checkoutParents', []),
                                    ('sourcePins', []), ('selectedProofs', []), ('status', 'FAILED'), ('generatedAt', ''),
                                    ('producer', {'runId': '124', 'runAttempt': '1', 'event': 'pull_request'})]:
            changed = copy.deepcopy(self.start)
            changed[field] = replacement
            with self.subTest(field=field), self.assertRaises(RuntimeProofError):
                validate_ui_start(changed, self.identity, self.pins, self.proofs)

    def test_missing_start_and_other_attempt_or_event_fail(self):
        with self.assertRaisesRegex(RuntimeProofError, 'UI_START_RECORD_MISSING'):
            validate_ui_start(None, self.identity, self.pins, self.proofs)
        for field, value in [('runAttempt', '2'), ('event', 'push')]:
            changed = copy.deepcopy(self.start)
            changed['producer'][field] = value
            with self.subTest(field=field), self.assertRaises(RuntimeProofError):
                validate_ui_start(changed, self.identity, self.pins, self.proofs)


class UiShardProofTest(unittest.TestCase):
    # Temporary Git/report fixtures exercise the verifier, never product UI evidence.
    git = UiCandidateIdentityTest.git

    def setUp(self):
        UiCandidateIdentityTest.setUp(self)
        paths = ('tools/ci/input_evidence_gate.py', 'tools/ci/ui_input_proof.mjs',
                 'tools/ci/package.json', 'tools/ci/package-lock.json', str(CATALOG), str(BASELINE),
                 'data/commands/input-evidence-debt-v1.json', 'data/help/first-steps-exclusions-v1.json',
                 'docs/development/first-steps-exclusions-v1.md')
        for relative in paths:
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / relative, target)
        catalog = json.loads((self.root / CATALOG).read_text())
        row = next(item for item in catalog['inputs'] if item['inputId'] == 'court.reward')
        row['deliveryState'] = 'UI_READY'
        row['evidence'] = {'UI_READY': ['ui-e2e:web/game/e2e/smoke/court.spec.ts#court.reward']}
        (self.root / CATALOG).write_text(json.dumps(catalog))
        self.title = '[court.reward] own request'
        self.spec_path = 'web/game/e2e/smoke/court.spec.ts'
        spec = self.root / self.spec_path
        spec.parent.mkdir(parents=True)
        spec.write_text(UiInputSourceProofTest.delivered(self.title))
        self.git('add', *paths, self.spec_path)
        self.git('commit', '-qm', '합성 shard 소비 검증 원천')
        self.git('checkout', '-qb', 'shard-candidate')
        (self.root / 'case.txt').write_text('후보 신원\n')
        self.git('add', 'case.txt')
        self.git('commit', '-qm', '합성 후보')
        candidate = self.git('rev-parse', 'HEAD')
        self.git('checkout', '-q', 'main')
        base = self.git('rev-parse', 'HEAD')
        self.git('merge', '--no-ff', '-qm', '합성 통합', 'shard-candidate')
        self.context['GITHUB_SHA'] = self.git('rev-parse', 'HEAD')
        self.event['pull_request']['head']['sha'] = candidate
        self.event['pull_request']['base']['sha'] = base
        self.event_path = self.root / 'event.json'
        self.event_path.write_text(json.dumps(self.event))
        self.context['GITHUB_EVENT_PATH'] = str(self.event_path)
        self.start, code = record_ui_start(self.event_path, self.root, self.context)
        self.assertEqual(0, code, self.start)
        begun = datetime.fromisoformat(self.start['generatedAt']) + timedelta(seconds=1)
        self.common = {'app': 'game', 'runId': '123', 'runAttempt': '1', 'headSha': candidate,
                       'workflowSha': 'a' * 40, 'shardCount': 4}
        self.raw = self.root / 'originals'
        self.aggregate = self.root / 'aggregate'
        self.originals = {}
        specs = []
        for title, file, line in ((self.title, 'smoke/court.spec.ts', 10), ('other case', 'smoke/other.spec.ts', 20)):
            specs.append({'file': file, 'line': line, 'column': 1, 'title': title, 'ok': True, 'id': 'list-id',
                          'tests': [{'projectName': project, 'expectedStatus': 'passed', 'status': 'expected',
                                     'annotations': [], 'results': []} for project in ('desktop', 'mobile')]})
        for phase_name in ('smoke', 'topdown-screens'):
            inventory = {'suites': [{'title': 'same describe', 'specs': specs, 'suites': []}], 'errors': []}
            if phase_name == 'topdown-screens':
                inventory = {'suites': [], 'errors': []}
            for index in range(1, 5):
                directory = self.raw / f'{phase_name}-{index}'
                phase = dict(self.common, schema='web-e2e-phase-v1', phase=phase_name, shardIndex=index,
                    workflow='CI', event='pull_request', repository='owner/repo', uiInputStart=self.start,
                    recordState='FINISHED', workflowStepOutcome='success', exitCode=0,
                    startedAt=begun.isoformat(), finishedAt=(begun + timedelta(seconds=1)).isoformat(),
                    testState='PLAYWRIGHT_FINISHED', playwrightInvoked=True, playwrightExitCode=0)
                report = None
                if phase_name == 'smoke':
                    spec = copy.deepcopy(specs[(index - 1) // 2])
                    test = spec['tests'][(index - 1) % 2]
                    test['results'] = [{'status': 'passed', 'retry': 0, 'errors': [], 'workerIndex': 0,
                                        'startTime': begun.isoformat(), 'duration': 5}]
                    spec['tests'] = [test]
                    spec['id'] = f'original-shard-id-{index}'
                    report = {'config': {'rootDir': str(self.root / 'web/game/e2e')},
                              'stats': {'expected': 1, 'skipped': 0, 'unexpected': 0, 'flaky': 0,
                                        'startTime': begun.isoformat()},
                              'errors': [], 'suites': [{'title': 'same describe', 'specs': [spec], 'suites': []}]}
                else:
                    phase.update(testState='NO_TOPDOWN_SPECS', playwrightInvoked=False, playwrightExitCode=None)
                self.originals[phase_name, index] = {'directory': directory, 'phase': phase, 'inventory': copy.deepcopy(inventory), 'report': report}
        self.write_originals_and_aggregate()

    def write(self, path, value):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value, ensure_ascii=False) + '\n')

    def write_originals_and_aggregate(self):
        summary = dict(self.common, schema='web-shards-v1', phases={})
        for phase_name in ('smoke', 'topdown-screens'):
            originals, merged = [], []
            for index in range(1, 5):
                item = self.originals[phase_name, index]
                directory = item['directory']
                self.write(directory / 'phase.json', item['phase'])
                self.write(directory / 'ui-input-start.json', self.start)
                self.write(directory / 'expected.json', item['inventory'])
                if item['report'] is not None:
                    self.write(directory / 'results.json', item['report'])
                    for suite in item['report']['suites']:
                        merged.extend(copy.deepcopy(suite['specs']))
                originals.append({'shardIndex': index, 'phase': copy.deepcopy(item['phase']),
                                  'phaseSha256': hashlib.sha256((directory / 'phase.json').read_bytes()).hexdigest()})
            count = sum(len(spec['tests']) for spec in merged)
            self.write(self.aggregate / phase_name / 'phase.json',
                       dict(self.common, schema='web-e2e-shard-aggregate-v1', phase=phase_name,
                            testCount=count, shardReceipts=originals))
            self.write(self.aggregate / phase_name / 'results.json',
                       {'suites': [{'title': 'same describe', 'specs': merged, 'suites': []}] if merged else [], 'errors': []})
            summary['phases'][phase_name] = {'testCount': count}
        self.write(self.aggregate / 'summary.json', summary)

    def verify(self):
        return check_ui_shards(self.raw, self.aggregate, self.event_path, self.root, self.context)

    def test_review_p2_actual_empty_topdown_shell_inventory_reaches_consumer(self):
        from test_ci_workflow import WebE2eArtifactContractTest
        producer = WebE2eArtifactContractTest()
        self.addCleanup(producer.doCleanups)
        producer.setUp()
        result = producer.shell(producer.topdown, 'game')
        self.assertEqual(0, result.returncode, result.stdout)
        raw = (producer.phase_dir(producer.topdown, 'game') / 'expected.json').read_bytes()
        self.assertEqual(b'{"suites":[]}\n', raw)
        inventory = json.loads(raw)
        # Only the actual empty inventory crosses fixtures. The fake shell start
        # is never used as identity or browser evidence by the real consumer.
        for index in range(1, 5):
            self.originals['topdown-screens', index]['inventory'] = copy.deepcopy(inventory)
        self.write_originals_and_aggregate()
        receipt, code = self.verify()
        self.assertEqual(0, code, receipt)
        self.assertEqual('UI_RUNTIME_VERIFIED', receipt['status'])
        self.assertEqual(2, len(receipt['proofs']))

    def test_review_p2_empty_inventory_does_not_bypass_phase_or_error_gates(self):
        original = copy.deepcopy(self.originals)
        mutants = (
            ({'suites': [], 'errors': None}, {}),
            ({'suites': [], 'errors': [{'message': 'collection failed'}]}, {}),
            ({'suites': [], 'unexpected': 1}, {}),
            ({'suites': []}, {'testState': 'PLAYWRIGHT_FINISHED', 'playwrightInvoked': True, 'playwrightExitCode': 0}),
            ({'suites': []}, {'exitCode': 1}),
        )
        for inventory, phase_delta in mutants:
            self.originals = copy.deepcopy(original)
            item = self.originals['topdown-screens', 1]
            item['inventory'] = inventory
            item['phase'].update(phase_delta)
            self.write_originals_and_aggregate()
            receipt, code = self.verify()
            with self.subTest(inventory=inventory, phase=phase_delta):
                self.assertNotEqual(0, code, receipt)

    def test_review_p2_nonempty_inventory_requires_explicit_errors(self):
        self.originals['smoke', 1]['inventory'].pop('errors')
        self.write_originals_and_aggregate()
        receipt, code = self.verify()
        self.assertNotEqual(0, code, receipt)
        self.assertIn('SHARD_REPORT_ERRORS', str(receipt))

    def test_originals_and_selected_desktop_mobile_union(self):
        receipt, code = self.verify()
        self.assertEqual(0, code, receipt)
        self.assertEqual('UI_RUNTIME_VERIFIED', receipt['status'])
        self.assertEqual(8, len(receipt['originalArtifacts']))
        self.assertEqual(2, len(receipt['proofs']))
        self.assertEqual({'desktop', 'mobile'}, {p['project'] for p in receipt['proofs']})
        self.assertIn(str(BASELINE), {p['path'] for p in receipt['sourcePins']})
        self.assertFalse(any(key in receipt for key in ('startedAt', 'finishedAt')))

    def test_no_selected_proofs_never_claims_input_delivery(self):
        (self.root / CATALOG).write_bytes((ROOT / CATALOG).read_bytes())
        self.git('add', str(CATALOG))
        self.git('commit', '-qm', '선택 증거 없는 합성 push')
        head = self.git('rev-parse', 'HEAD')
        self.context.update(GITHUB_EVENT_NAME='push', GITHUB_SHA=head,
                            GITHUB_WORKFLOW_REF='owner/repo/.github/workflows/ci.yml@refs/heads/main')
        self.event = {'repository': {'full_name': 'owner/repo'}, 'after': head}
        self.write(self.event_path, self.event)
        self.start, code = record_ui_start(self.event_path, self.root, self.context)
        self.assertEqual(0, code, self.start)
        begun = datetime.fromisoformat(self.start['generatedAt']) + timedelta(seconds=1)
        self.common['headSha'] = head
        for item in self.originals.values():
            item['phase'].update(headSha=head, event='push', uiInputStart=self.start,
                startedAt=begun.isoformat(), finishedAt=(begun + timedelta(seconds=1)).isoformat())
            if item['report'] is not None:
                item['report']['stats']['startTime'] = begun.isoformat()
        self.write_originals_and_aggregate()
        receipt, code = self.verify()
        self.assertEqual(0, code, receipt)
        self.assertEqual('NO_UI_PROOFS', receipt['status'])
        self.assertEqual([], receipt['proofs'])

    def test_aggregate_without_originals_is_unavailable(self):
        shutil.rmtree(self.originals['smoke', 1]['directory'])
        receipt, code = self.verify()
        self.assertEqual(1, code)
        self.assertNotEqual('UI_RUNTIME_VERIFIED', receipt['status'])
        self.assertEqual([], receipt['proofs'])

    def test_foreign_identity_and_missing_initial_receipt_fail(self):
        path = self.originals['smoke', 1]['directory'] / 'phase.json'
        before = path.read_bytes()
        for key, value in (('runAttempt', '2'), ('runId', '456'), ('headSha', 'b' * 40),
                           ('workflow', 'other'), ('repository', 'another/repo'), ('event', 'push'), ('shardIndex', True)):
            with self.subTest(key=key):
                phase = json.loads(before)
                phase[key] = value
                self.write(path, phase)
                self.assertEqual(1, self.verify()[1])
                path.write_bytes(before)
        (path.parent / 'ui-input-start.json').unlink()
        self.assertEqual(1, self.verify()[1])

    def test_forged_aggregate_hash_or_original_phase_is_rejected(self):
        path = self.aggregate / 'smoke/phase.json'
        before = path.read_bytes()
        for key, value in (('phaseSha256', '0' * 64), ('phase', {})):
            with self.subTest(key=key):
                aggregate = json.loads(before)
                aggregate['shardReceipts'][0][key] = value
                self.write(path, aggregate)
                self.assertEqual(1, self.verify()[1])
                path.write_bytes(before)

    def test_skip_retry_missing_project_and_wrong_result_are_rejected(self):
        item = self.originals['smoke', 2]
        original = copy.deepcopy(item['report'])
        for mutation in ('skip', 'retry', 'missing', 'foreign result'):
            with self.subTest(mutation=mutation):
                item['report'] = copy.deepcopy(original)
                report = item['report']
                test = report['suites'][0]['specs'][0]['tests'][0]
                if mutation == 'skip':
                    report['stats']['skipped'] = 1
                    test['status'] = 'skipped'
                elif mutation == 'retry':
                    test['results'][0]['retry'] = 1
                elif mutation == 'missing':
                    report['stats']['expected'] = 0
                    report['suites'] = []
                else:
                    test['projectName'] = 'not-mobile'
                self.write_originals_and_aggregate()
                self.assertEqual(1, self.verify()[1])
        item['report'] = original

    def test_duplicate_shard_inventory_and_union_fail(self):
        for mutation in ('duplicate shard', 'inventory differs', 'duplicate test', 'changed aggregate result'):
            with self.subTest(mutation=mutation):
                originals = copy.deepcopy(self.originals)
                if mutation == 'duplicate shard':
                    self.originals['smoke', 2]['phase']['shardIndex'] = 1
                elif mutation == 'inventory differs':
                    self.originals['smoke', 2]['inventory']['suites'][0]['specs'].pop()
                elif mutation == 'duplicate test':
                    self.originals['smoke', 2]['report'] = copy.deepcopy(self.originals['smoke', 1]['report'])
                self.write_originals_and_aggregate()
                if mutation == 'changed aggregate result':
                    path = self.aggregate / 'smoke/results.json'
                    combined = json.loads(path.read_text())
                    combined['suites'][0]['specs'][0]['tests'][0]['results'][0]['duration'] += 1
                    self.write(path, combined)
                self.assertEqual(1, self.verify()[1])
                self.originals = originals

    def test_late_start_is_rejected(self):
        self.start['generatedAt'] = (datetime.fromisoformat(self.start['generatedAt']) + timedelta(days=1)).isoformat()
        for item in self.originals.values():
            item['phase']['uiInputStart'] = self.start
        self.write_originals_and_aggregate()
        self.assertEqual(1, self.verify()[1])

    def test_duplicate_json_keys_and_symlinked_originals_fail(self):
        path = self.originals['smoke', 1]['directory'] / 'phase.json'
        raw = path.read_bytes()
        path.write_bytes(b'{"app":"game",' + raw[1:])
        self.assertEqual(1, self.verify()[1])
        path.write_bytes(raw)
        target = path.with_name('phase-original.json')
        path.rename(target)
        path.symlink_to(target)
        self.assertEqual(1, self.verify()[1])


if __name__ == "__main__":
    unittest.main()
