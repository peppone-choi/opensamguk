#!/usr/bin/env python3
"""운영 VM에서 maintenance를 읽고 기존 웹훅으로 경보한다. 변경/해제 경로는 없다."""

from __future__ import annotations

import fcntl
import json
import os
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

import external_health_run as monitor
from external_health_contract import transition
from production_ops_contract import classify_maintenance

MAINTENANCE_DEFERRED = "maintenance_scan_deferred"

READ_MAINTENANCE = """
import json, os, sys, urllib.request
try:
    token = os.environ["DEPLOYER_TOKEN"]
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None
    request = urllib.request.Request("http://localhost:9000/maintenance",
                                     headers={"Authorization": "Bearer " + token})
    with urllib.request.build_opener(NoRedirect).open(request, timeout=10) as response:
        body = response.read(4097)
    if len(body) > 4096:
        raise ValueError()
    value = json.loads(body)
    if value.get("capability") != "maintenance-v1" or value.get("state") not in {"open", "drained"}:
        raise ValueError()
    print(json.dumps({"capability": "maintenance-v1", "state": value["state"]}))
except Exception:
    raise SystemExit(1)
"""


def maintenance_result(lock_path: Path = Path("/tmp/opensamguk-production.lock")) -> str | None:
    try:
        with lock_path.open("a") as lock:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                print("maintenance scan deferred: production operation owns the lock")
                return MAINTENANCE_DEFERRED
            # 잠금을 읽기 동안 보유하므로 새 배포가 동시에 maintenance를 잡을 수 없다.
            result = subprocess.run(["docker", "exec", "-i", "opensamguk-deployer", "python3", "-"],
                                    input=READ_MAINTENANCE, text=True, capture_output=True, timeout=20)
            if result.returncode != 0 or len(result.stdout) > 256:
                return "maintenance_unavailable"
            return classify_maintenance(json.loads(result.stdout), operation_active=False)
    except (OSError, ValueError, subprocess.SubprocessError):
        return "maintenance_unavailable"


def execute(run_id: int) -> int:
    previous_path = Path(os.environ.get("PREVIOUS_STATE_FILE", "previous-state/monitor-state.json"))
    current_path = Path(os.environ.get("CURRENT_STATE_FILE", "current-state/monitor-state.json"))
    previous, previous_delivered, invalid = monitor.read_previous(previous_path)
    findings = []
    if invalid or os.environ.get("STATE_LOOKUP_ERROR") == "true" or (
            os.environ.get("PREVIOUS_STATE_EXPECTED") == "true" and not previous_path.exists()):
        findings.append("state_unavailable")
    finding = maintenance_result()
    deferred = finding == MAINTENANCE_DEFERRED
    state_findings = bool(findings)
    if deferred:
        # No GET occurred. Preserve both existing incident and delivery state;
        # unrelated state-read errors may still produce their own incident.
        findings.extend(previous)
    elif finding:
        findings.append(finding)
    findings = sorted(set(findings))
    kind = None if deferred and not state_findings else transition(previous, findings, previous_delivered)
    delivered = previous_delivered if kind is None else monitor.deliver(
        monitor.payload(kind, findings, "ops", datetime.now(timezone.utc), run_id))
    stored = previous if kind == "recovered" and not delivered else findings
    monitor.write_state(current_path, stored, delivered)
    observation = MAINTENANCE_DEFERRED if deferred else "observed"
    line = f"ops: {', '.join(findings) if findings else observation if deferred else 'healthy'}; scan={observation}; notification={kind or 'suppressed'}; delivered={delivered}\n"
    print(line, end="")
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as stream:
            stream.write(line)
    return int(bool(findings) or kind is not None and not delivered)


def main() -> int:
    if len(sys.argv) != 2 or sys.argv[1] not in {"find-state", "execute"}:
        return 2
    run_id = int(os.environ["GITHUB_RUN_ID"])
    return monitor.find_state("ops", run_id) if sys.argv[1] == "find-state" else execute(run_id)


if __name__ == "__main__":
    sys.exit(main())
