#!/usr/bin/env python3
"""배포 연속 실패·보유 작업 없는 maintenance의 순수 판정."""

FAILED_DEPLOY = frozenset({"failure", "timed_out", "startup_failure"})


def deployment_failure_streak(runs: list[dict]) -> int:
    completed = [run for run in runs if run.get("head_branch") == "main"
                 and run.get("status") == "completed" and run.get("event") == "push"
                 and run.get("conclusion") in FAILED_DEPLOY | {"success"}]
    completed.sort(key=lambda run: (run.get("created_at", ""), run.get("id", 0)), reverse=True)
    streak = 0
    for run in completed:
        if run["conclusion"] == "success":
            break
        streak += 1
    return streak


def classify_maintenance(value: dict | None, operation_active: bool) -> str | None:
    if operation_active:
        return None
    if not isinstance(value, dict) or value.get("capability") != "maintenance-v1":
        return "maintenance_unavailable"
    if value.get("state") == "drained":
        return "maintenance_orphaned"
    return None if value.get("state") == "open" else "maintenance_unavailable"
