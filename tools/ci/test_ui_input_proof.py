"""D144 mock servers remain UI evidence only when the UI sends the observed POST."""
import json
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = """
import { test, expect } from '@playwright/test';
test('[court.reward] 상사 보내기', { tag: ['@both'] }, async ({ page }) => {
  await page.route('**/api/game/**', async (route) => {
    await route.fulfill({ status: 202, json: { accepted: true } });
  });
  await page.goto('/game/court');
  const reward = page.locator('[data-input-id="court.reward"]');
  const sent = page.waitForRequest((request) => request.method() === 'POST' &&
    new URL(request.url()).pathname === '/api/game/api/commands/court/reward');
  await reward.getByRole('button', { name: '상사 — 접수' }).click();
  const request = await sent;
  expect(request.postDataJSON()).toEqual({ retainerId: 31, money: 100 });
});
"""


class MockUiProofTest(unittest.TestCase):
    def proof(self, source):
        payload = {"source": source, "path": "web/game/e2e/smoke/court.spec.ts",
                   "inputId": "court.reward", "contract": {
                       "paths": ["/api/game/api/commands/court/reward"],
                       "body": {"retainerId": "positive-int", "money": "bounded-positive:1000000000"}}}
        return subprocess.run(["node", str(ROOT / "tools/ci/ui_input_proof.mjs")],
                              input=json.dumps(payload), text=True, capture_output=True)

    def test_page_route_fulfill_keeps_the_observed_ui_request(self):
        result = self.proof(SOURCE)
        self.assertEqual(0, result.returncode, result.stderr)
        proof = json.loads(result.stdout)
        self.assertEqual(["desktop", "mobile"], proof["cases"][0]["projects"])
        self.assertFalse(proof["uiRuntimeExecuted"])

    def test_route_callback_does_not_replace_a_ui_send(self):
        result = self.proof(SOURCE.replace("  await reward.getByRole('button', { name: '상사 — 접수' }).click();", ""))
        self.assertNotEqual(0, result.returncode)

    def test_mock_response_does_not_replace_request_body_assertion(self):
        result = self.proof(SOURCE.replace("expect(request.postDataJSON()).toEqual", "expect({retainerId: 31, money: 100}).toEqual"))
        self.assertNotEqual(0, result.returncode)

    def test_route_registration_must_be_awaited(self):
        result = self.proof(SOURCE.replace("await page.route", "page.route"))
        self.assertNotEqual(0, result.returncode)

    def test_direct_script_request_remains_rejected(self):
        result = self.proof(SOURCE.replace("await page.goto('/game/court');", "await page.evaluate(() => fetch('/api/game/api/commands/court/reward'));"))
        self.assertNotEqual(0, result.returncode)

    def test_mock_handler_cannot_replace_selected_input_scope(self):
        result = self.proof(SOURCE.replace('data-input-id="court.reward"', 'data-input-id="court.appoint"'))
        self.assertNotEqual(0, result.returncode)


if __name__ == "__main__":
    unittest.main()
