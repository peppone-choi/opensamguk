"""운영 감시 계약. 실제 Docker/웹훅/API에 접속하지 않는다."""
import fcntl
import io
import json
import os
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import external_health_run as external
import production_ops_monitor as monitor
from production_ops_contract import classify_maintenance, deployment_failure_streak


def deploy(index, conclusion, **overrides):
    return {"id": index, "created_at": f"2026-09-30T12:{index:02}:00Z", "head_branch": "main",
            "status": "completed", "event": "push", "conclusion": conclusion, **overrides}


class OpsContractTest(unittest.TestCase):
    def test_streak_stops_at_success_and_ignores_cancelled_and_other_branches(self):
        self.assertEqual(2, deployment_failure_streak([
            deploy(1, "failure"), deploy(2, "success"), deploy(3, "failure"),
            deploy(4, "cancelled"), deploy(5, "timed_out"),
            deploy(6, "failure", head_branch="feature"), deploy(7, "failure", event="pull_request"),
        ]))
        self.assertEqual(0, deployment_failure_streak([deploy(1, "failure"), deploy(2, "success")]))

    def test_deploy_adapter_alerts_after_second_failure_and_fails_closed_on_api_error(self):
        with patch.object(external, "github_api", return_value={"workflow_runs": [deploy(1, "failure")]}), \
                patch.dict(os.environ, {"GITHUB_REPOSITORY": "owner/repo"}), redirect_stdout(io.StringIO()):
            self.assertIsNone(external.deployment_result())
        with patch.object(external, "github_api", return_value={"workflow_runs": [deploy(1, "failure"), deploy(2, "failure")]}), \
                patch.dict(os.environ, {"GITHUB_REPOSITORY": "owner/repo"}), redirect_stdout(io.StringIO()):
            self.assertEqual("deploy_consecutive_failures", external.deployment_result())
        with patch.object(external, "github_api", side_effect=OSError("secret-sentinel")), \
                patch.dict(os.environ, {"GITHUB_REPOSITORY": "owner/repo"}):
            self.assertEqual("deployment_history_unavailable", external.deployment_result())

    def test_drained_is_orphaned_only_without_active_operation(self):
        value = {"capability": "maintenance-v1", "state": "drained"}
        self.assertIsNone(classify_maintenance(value, True))
        self.assertEqual("maintenance_orphaned", classify_maintenance(value, False))
        self.assertEqual("maintenance_unavailable", classify_maintenance({"state": "open"}, False))
        self.assertIsNone(classify_maintenance({**value, "state": "open"}, False))

    def test_scan_defers_while_operation_holds_lock_and_never_calls_docker(self):
        with tempfile.TemporaryDirectory() as temp:
            lock = Path(temp) / "production.lock"
            with lock.open("a") as owner, patch.object(monitor.subprocess, "run") as docker, redirect_stdout(io.StringIO()):
                fcntl.flock(owner, fcntl.LOCK_EX | fcntl.LOCK_NB)
                self.assertIsNone(monitor.maintenance_result(lock))
                docker.assert_not_called()

    def test_read_only_scan_limits_output_and_suppresses_secret_errors(self):
        with tempfile.TemporaryDirectory() as temp:
            lock = Path(temp) / "production.lock"
            for result, expected in [
                (SimpleNamespace(returncode=0, stdout='{"capability":"maintenance-v1","state":"drained"}'), "maintenance_orphaned"),
                (SimpleNamespace(returncode=0, stdout="secret-sentinel" * 30), "maintenance_unavailable"),
                (SimpleNamespace(returncode=1, stdout="secret-sentinel"), "maintenance_unavailable"),
            ]:
                output = io.StringIO()
                with patch.object(monitor.subprocess, "run", return_value=result) as docker, redirect_stdout(output):
                    self.assertEqual(expected, monitor.maintenance_result(lock))
                self.assertNotIn("secret-sentinel", output.getvalue())
                self.assertNotIn("/maintenance/leave", docker.call_args.kwargs["input"])

    def test_delivery_failure_retries_and_successful_incident_is_suppressed_then_recovers(self):
        with tempfile.TemporaryDirectory() as temp:
            previous = Path(temp) / "previous.json"
            current = Path(temp) / "current.json"
            env = {"PREVIOUS_STATE_FILE": str(previous), "CURRENT_STATE_FILE": str(current), "GITHUB_REPOSITORY": "owner/repo"}
            with patch.dict(os.environ, env, clear=True), redirect_stdout(io.StringIO()):
                for finding, delivered, calls, status in [
                    ("maintenance_orphaned", False, 1, 1), ("maintenance_orphaned", True, 1, 1),
                    ("maintenance_orphaned", True, 0, 1), (None, True, 1, 0),
                ]:
                    with patch.object(monitor, "maintenance_result", return_value=finding), \
                            patch.object(monitor.monitor, "deliver", return_value=delivered) as send:
                        self.assertEqual(status, monitor.execute(200))
                        self.assertEqual(calls, send.call_count)
                    previous.write_bytes(current.read_bytes())
            self.assertEqual([], json.loads(current.read_text())["codes"])


if __name__ == "__main__":
    unittest.main()
