#!/usr/bin/env python3
"""main CI 적색·복구 알림의 판정 경계. 합성 실행 기록만 쓴다."""
import io
import os
import sys
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import main_ci_alert as alert  # noqa: E402


def run(**overrides):
    base = {
        "id": 200,
        "workflow_id": 7,
        "head_branch": "main",
        "event": "push",
        "conclusion": "failure",
        "head_sha": "677e8940aaaabbbbcccc",
        "html_url": "https://github.com/o/r/actions/runs/200",
        "created_at": "2026-09-23T19:56:00Z",
        "head_commit": {"message": "Merge pull request #888\n\nbody"},
    }
    base.update(overrides)
    return base


class DecideTest(unittest.TestCase):
    def test_red_main_push_names_every_failed_job(self):
        payload = alert.decide(run(), ["contracts", "jvm"], None)
        embed = payload["embeds"][0]
        self.assertEqual("[main CI 적색] failure @ 677e8940", embed["title"])
        self.assertEqual(alert.COLOR_RED, embed["color"])
        fields = {f["name"]: f["value"] for f in embed["fields"]}
        self.assertEqual("contracts, jvm", fields["failed"])
        self.assertEqual("Merge pull request #888", fields["subject"])

    def test_timeout_cancel_counts_as_red(self):
        # jvm 한도 초과는 cancelled 로 끝난다 — 이걸 놓치면 가장 흔한 적색을 못 본다.
        payload = alert.decide(run(conclusion="cancelled"), ["jvm"], None)
        self.assertIsNotNone(payload)
        self.assertIn("적색", payload["embeds"][0]["title"])

    def test_first_green_after_red_is_a_recovery(self):
        payload = alert.decide(run(conclusion="success"), None, "failure")
        self.assertEqual("[main CI 복구] success @ 677e8940", payload["embeds"][0]["title"])
        self.assertEqual(alert.COLOR_GREEN, payload["embeds"][0]["color"])

    def test_green_after_green_is_silent(self):
        self.assertIsNone(alert.decide(run(conclusion="success"), None, "success"))
        self.assertIsNone(alert.decide(run(conclusion="success"), None, None))

    def test_only_main_push_is_watched(self):
        self.assertIsNone(alert.decide(run(head_branch="work/x"), ["jvm"], None))
        self.assertIsNone(alert.decide(run(event="pull_request"), ["jvm"], None))


class PreviousConclusionTest(unittest.TestCase):
    def test_picks_the_latest_run_before_this_one(self):
        runs = {"workflow_runs": [
            {"id": 200, "created_at": "2026-09-23T19:56:00Z", "conclusion": "success"},
            {"id": 199, "created_at": "2026-09-23T19:45:00Z", "conclusion": "failure"},
            {"id": 150, "created_at": "2026-09-23T08:00:00Z", "conclusion": "success"},
            {"id": 201, "created_at": "2026-09-23T20:10:00Z", "conclusion": "success"},
        ]}
        with patch.object(alert, "api", return_value=runs):
            self.assertEqual("failure", alert.previous_conclusion("o/r", run(conclusion="success")))


class MainTest(unittest.TestCase):
    def test_missing_webhook_warns_and_does_not_fail(self):
        env = {"GITHUB_REPOSITORY": "o/r", "RUN_ID": "200", "GITHUB_TOKEN": "x"}
        with patch.dict(os.environ, env, clear=True), \
                patch.object(alert, "api", return_value=run()), \
                patch.object(alert, "failed_jobs", return_value=["jvm"]), \
                patch.object(alert, "send") as send:
            out = io.StringIO()
            with redirect_stdout(out):
                self.assertEqual(0, alert.main())
        send.assert_not_called()
        self.assertIn("::warning::", out.getvalue())

    def test_dispatch_failure_turns_the_workflow_red(self):
        env = {"GITHUB_REPOSITORY": "o/r", "RUN_ID": "200", "GITHUB_TOKEN": "x",
               "DAEMON_ALERT_WEBHOOK_URL": "https://example.invalid/hook"}
        with patch.dict(os.environ, env, clear=True), \
                patch.object(alert, "api", return_value=run()), \
                patch.object(alert, "failed_jobs", return_value=["jvm"]), \
                patch.object(alert, "send", side_effect=RuntimeError("down")):
            with redirect_stdout(io.StringIO()):
                self.assertEqual(1, alert.main())


if __name__ == "__main__":
    unittest.main()
