#!/usr/bin/env python3
"""워크플로 어댑터 계약. 실제 공개 URL·웹훅에는 접속하지 않는다."""

import io
import http.client
import json
import os
import sys
import tempfile
import unittest
import urllib.error
from contextlib import redirect_stdout
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import external_health_run as runner  # noqa: E402


def responses(broken=False):
    last_turn = datetime.now(timezone.utc) - timedelta(minutes=5)
    game = {"game": {"status": "OPEN", "year": 190, "month": 1, "turnPhase": 1, "turnTerm": 10,
                     "lastTurnAt": last_turn.isoformat(),
                     "lastTickExecutedAt": last_turn.isoformat(),
                     "serverTime": datetime.now(timezone.utc).isoformat(),
                     "nextTurnAt": (last_turn + timedelta(minutes=10)).isoformat(),
                     "turnLoop": {"state": "RUNNING", "staleSeconds": 300}}, "me": None}
    return {
        runner.URLS["origin"]: (200, b'{"status":"up","nginx":"ok"}', None),
        runner.URLS["gateway"]: (200, b'{"status":"UP","app":"web-gateway"}', None),
        runner.URLS["game_api"]: (502, b"", None) if broken else
        (200, json.dumps(game).encode(), None),
    }


class ExecuteTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="external-health-test-")
        self.addCleanup(self.tmp.cleanup)
        root = Path(self.tmp.name)
        self.previous = root / "previous" / "monitor-state.json"
        self.current = root / "current" / "monitor-state.json"
        self.env = {
            "GITHUB_REPOSITORY": "owner/repo",
            "GITHUB_RUN_ID": "200",
            "GITHUB_TOKEN": "secret-sentinel",
            "DAEMON_ALERT_WEBHOOK_URL": "https://webhook.invalid/secret-sentinel",
            "PREVIOUS_STATE_FILE": str(self.previous),
            "CURRENT_STATE_FILE": str(self.current),
        }

    def execute(self, broken, delivered=True):
        reply = responses(broken)
        output = io.StringIO()
        with patch.dict(os.environ, self.env, clear=True), \
                patch.object(runner, "public_get", side_effect=lambda url: reply[url]), \
                patch.object(runner, "peer_result", return_value=None), \
                patch.object(runner, "deployment_result", return_value=None), \
                patch.object(runner, "operations_heartbeat_result", return_value=None), \
                patch.object(runner, "deliver", return_value=delivered) as send, \
                redirect_stdout(output):
            status = runner.execute("probe", 200)
        self.assertNotIn("secret-sentinel", output.getvalue())
        self.assertNotIn("secret-sentinel", self.current.read_text())
        return status, send

    def promote_state(self):
        self.previous.parent.mkdir(parents=True, exist_ok=True)
        self.previous.write_bytes(self.current.read_bytes())

    def test_incident_duplicate_suppression_and_recovery(self):
        status, send = self.execute(broken=True)
        self.assertEqual(1, status)
        send.assert_called_once()
        self.assertEqual(["game_api_down"], json.loads(self.current.read_text())["codes"])
        self.promote_state()
        status, send = self.execute(broken=True)
        self.assertEqual(1, status)
        send.assert_not_called()
        self.promote_state()
        status, send = self.execute(broken=False)
        self.assertEqual(0, status)
        send.assert_called_once()
        self.assertEqual([], json.loads(self.current.read_text())["codes"])

    def test_failed_delivery_is_retried(self):
        status, send = self.execute(broken=True, delivered=False)
        self.assertEqual(1, status)
        send.assert_called_once()
        self.promote_state()
        status, send = self.execute(broken=True, delivered=True)
        self.assertEqual(1, status)
        send.assert_called_once()

    def test_corrupt_previous_artifact_is_an_incident_not_a_recovery(self):
        self.previous.parent.mkdir(parents=True, exist_ok=True)
        self.previous.write_text("not-json")
        status, send = self.execute(broken=False)
        self.assertEqual(1, status)
        send.assert_called_once()
        self.assertEqual(["state_unavailable"], json.loads(self.current.read_text())["codes"])

    def test_recovery_delivery_failure_retains_prior_incident(self):
        self.execute(broken=True)
        self.promote_state()
        status, _ = self.execute(broken=False, delivered=False)
        self.assertEqual(1, status)
        self.assertEqual(["game_api_down"], json.loads(self.current.read_text())["codes"])

    def test_single_dispatch_exercises_alert_and_recovery_without_service_incident(self):
        reply = responses(False)
        env = {**self.env, "EXERCISE_ALERT_RECOVERY": "true"}
        with patch.dict(os.environ, env, clear=True), \
                patch.object(runner, "public_get", side_effect=lambda url: reply[url]), \
                patch.object(runner, "peer_result", return_value=None), \
                patch.object(runner, "deployment_result", return_value=None), \
                patch.object(runner, "operations_heartbeat_result", return_value=None), \
                patch.object(runner, "deliver", return_value=True) as send, \
                redirect_stdout(io.StringIO()):
            self.assertEqual(0, runner.execute("probe", 200))
        self.assertEqual(2, send.call_count)
        self.assertIn("[전송 시험]", send.call_args_list[0].args[0]["embeds"][0]["title"])
        self.assertEqual([], json.loads(self.current.read_text())["codes"])


class ArtifactTest(unittest.TestCase):
    def test_finds_latest_other_run(self):
        payload = {"artifacts": [
            {"name": "external-health-state-probe", "expired": False, "created_at": "2026-09-28T11:00:00Z",
             "workflow_run": {"id": 100, "head_branch": "main"}},
            {"name": "external-health-state-probe", "expired": False, "created_at": "2026-09-28T11:05:00Z",
             "workflow_run": {"id": 200, "head_branch": "main"}},
            {"name": "external-health-state-probe", "expired": False, "created_at": "2026-09-28T11:10:00Z",
             "workflow_run": {"id": 201, "head_branch": "main"}},
        ]}
        with patch.dict(os.environ, {"GITHUB_REPOSITORY": "owner/repo"}), \
                patch.object(runner, "github_api", return_value=payload):
            self.assertEqual(201, runner.previous_artifact_run("probe", 200))

    def test_failed_peer_with_state_artifact_is_not_second_alert(self):
        runs = {"workflow_runs": [{"id": 99, "event": "schedule", "created_at": "2026-09-28T11:55:00Z",
                                   "status": "completed", "conclusion": "failure"}]}
        artifacts = {"artifacts": [{"name": "external-health-state-probe"}]}
        with patch.dict(os.environ, {"GITHUB_REPOSITORY": "owner/repo"}), \
                patch.object(runner, "github_api", side_effect=[runs, artifacts]):
            from datetime import datetime, timezone
            self.assertIsNone(runner.peer_result("watchdog", datetime(2026, 9, 28, 12, tzinfo=timezone.utc), 100))

    def test_failed_peer_without_artifact_remains_visible_after_new_success(self):
        runs = {"workflow_runs": [
            {"id": 101, "event": "schedule", "created_at": "2026-09-28T11:58:00Z",
             "status": "completed", "conclusion": "success"},
            {"id": 99, "event": "schedule", "created_at": "2026-09-28T11:55:00Z",
             "status": "completed", "conclusion": "failure"},
        ]}
        with patch.dict(os.environ, {"GITHUB_REPOSITORY": "owner/repo"}), \
                patch.object(runner, "github_api", side_effect=[runs, {"artifacts": []}]):
            from datetime import datetime, timezone
            self.assertEqual("peer_failed", runner.peer_result("watchdog", datetime(2026, 9, 28, 12, tzinfo=timezone.utc), 100))

    def test_remote_disconnect_is_an_api_incident(self):
        from datetime import datetime, timezone
        with patch.dict(os.environ, {"GITHUB_REPOSITORY": "owner/repo"}), \
                patch.object(runner, "github_api", side_effect=http.client.RemoteDisconnected("connection dropped")):
            self.assertEqual("peer_api_unavailable",
                             runner.peer_result("probe", datetime(2026, 9, 28, 12, tzinfo=timezone.utc), 100))

    def test_incomplete_artifact_lookup_sets_error_output(self):
        with tempfile.TemporaryDirectory(prefix="external-health-output-") as temp:
            output = Path(temp) / "github-output"
            with patch.dict(os.environ, {"GITHUB_REPOSITORY": "owner/repo", "GITHUB_OUTPUT": str(output)}), \
                    patch.object(runner, "github_api", side_effect=http.client.IncompleteRead(b"partial")), \
                    redirect_stdout(io.StringIO()):
                self.assertEqual(0, runner.find_state("probe", 100))
            self.assertIn("lookup_error=true", output.read_text())


class SecretSafetyTest(unittest.TestCase):
    def test_http_failure_prints_only_status_code(self):
        output = io.StringIO()
        secret_url = "https://webhook.invalid/secret-sentinel"
        with patch.dict(os.environ, {"DAEMON_ALERT_WEBHOOK_URL": secret_url}), \
                patch.object(runner.urllib.request, "urlopen",
                             side_effect=urllib.error.HTTPError(secret_url, 404, "secret-sentinel", {}, None)) as send, \
                redirect_stdout(output):
            self.assertFalse(runner.deliver({"content": "test"}))
        self.assertEqual(1, send.call_count)
        self.assertIn("HTTP 404", output.getvalue())
        self.assertNotIn("secret-sentinel", output.getvalue())

    def test_webhook_failure_does_not_print_exception_or_url(self):
        output = io.StringIO()
        with patch.dict(os.environ, {"DAEMON_ALERT_WEBHOOK_URL": "https://webhook.invalid/secret-sentinel"}), \
                patch.object(runner.urllib.request, "urlopen",
                             side_effect=urllib.error.URLError("secret-sentinel")), \
                patch.object(runner.time, "sleep"), redirect_stdout(output):
            self.assertFalse(runner.deliver({"content": "test"}))
        self.assertNotIn("secret-sentinel", output.getvalue())
        self.assertIn("NOT delivered", output.getvalue())


if __name__ == "__main__":
    unittest.main()
