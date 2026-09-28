#!/usr/bin/env python3
"""GitHub 호스트 러너의 공개 감시·상호 heartbeat 실행기 (#1005)."""

from __future__ import annotations

import json
import os
import re
import socket
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

from external_health_contract import (
    MAX_BODY_BYTES, MISSED_SCHEDULE_SECONDS, classify_http, classify_peer_runs, parse_time, transition,
)

URLS = {
    "origin": "https://sam.peppone.dev/health",
    "gateway": "https://sam.peppone.dev/api/health",
    "game_api": "https://sam.peppone.dev/api/server-basic-info/pep",
}
WORKFLOWS = {"probe": "external-health-monitor.yml", "watchdog": "external-health-watchdog.yml"}
MESSAGES = {
    "delivery_test": "전송 시험: 실제 서비스 장애가 아닙니다",
    "game_api_down": "게임 API 연결 실패(502)",
    "turn_stalled": "공개 턴 시각 정지",
    "peer_not_started": "상대 감시기 일정 실행 없음",
    "peer_cancelled": "상대 감시기 실행 취소",
    "peer_failed": "상대 감시기 실행 실패",
    "peer_schedule_missing": "상대 감시기 일정 누락·지연",
    "peer_invalid_run": "상대 감시기 실행 상태 이상",
    "peer_api_unavailable": "감시 실행 이력 조회 실패",
    "state_unavailable": "이전 감시 상태를 읽지 못함",
}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return None


def repository() -> str:
    value = os.environ["GITHUB_REPOSITORY"]
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", value):
        raise ValueError("invalid repository")
    return value


def github_api(path: str) -> dict:
    base = os.environ.get("GITHUB_API_URL", "https://api.github.com")
    request = urllib.request.Request(
        f"{base}{path}",
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {os.environ['GITHUB_TOKEN']}",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    with urllib.request.urlopen(request, timeout=15) as response:
        value = json.load(response)
    if not isinstance(value, dict):
        raise ValueError("invalid GitHub API response")
    return value


def previous_artifact_run(mode: str, current_run_id: int) -> int | None:
    name = f"external-health-state-{mode}"
    artifacts = github_api(f"/repos/{repository()}/actions/artifacts?name={name}&per_page=100").get("artifacts")
    if not isinstance(artifacts, list):
        raise ValueError("artifact inventory missing")
    candidates = [item for item in artifacts if item.get("name") == name and not item.get("expired")
                  and isinstance(item.get("workflow_run"), dict)
                  and item["workflow_run"].get("head_branch") == "main"
                  and item["workflow_run"].get("id") != current_run_id]
    if not candidates:
        return None
    return max(candidates, key=lambda item: item.get("created_at", ""))["workflow_run"]["id"]


def find_state(mode: str, current_run_id: int) -> int:
    lookup_error = False
    try:
        previous_run = previous_artifact_run(mode, current_run_id)
    except (KeyError, ValueError, urllib.error.URLError, TimeoutError):
        print("::warning::previous monitor artifact lookup failed")
        previous_run = None
        lookup_error = True
    output = os.environ.get("GITHUB_OUTPUT")
    if output:
        with open(output, "a", encoding="utf-8") as stream:
            stream.write(f"run_id={previous_run or ''}\n")
            stream.write(f"lookup_error={str(lookup_error).lower()}\n")
    print("previous monitor state:", "found" if previous_run else "none")
    return 0


def public_get(url: str) -> tuple[int | None, bytes | None, str | None]:
    opener = urllib.request.build_opener(NoRedirect())
    request = urllib.request.Request(url, headers={"Accept": "application/json", "User-Agent": "opensamguk-external-health/1"})
    try:
        with opener.open(request, timeout=8) as response:
            return response.status, response.read(MAX_BODY_BYTES + 1), None
    except urllib.error.HTTPError as error:
        return error.code, error.read(MAX_BODY_BYTES + 1), None
    except (TimeoutError, socket.timeout):
        return None, None, "timeout"
    except urllib.error.URLError as error:
        return None, None, "timeout" if isinstance(error.reason, (TimeoutError, socket.timeout)) else "network"
    except (OSError, ValueError):
        return None, None, "network"


def peer_result(mode: str, now: datetime, run_id: int) -> str | None:
    peer_mode = "watchdog" if mode == "probe" else "probe"
    peer = WORKFLOWS[peer_mode]
    try:
        runs = github_api(f"/repos/{repository()}/actions/workflows/{peer}/runs?event=schedule&per_page=20")["workflow_runs"]
        if not isinstance(runs, list):
            raise ValueError("workflow run list missing")
        finding = classify_peer_runs(runs, now, run_id)
        if finding == "peer_failed":
            recent_failed = [item for item in runs if item.get("event") == "schedule"
                             and item.get("conclusion") in {"failure", "timed_out", "startup_failure"}
                             and (at := parse_time(item.get("created_at"))) is not None
                             and 0 <= (now - at).total_seconds() <= MISSED_SCHEDULE_SECONDS]
            for failed in recent_failed:
                artifacts = github_api(f"/repos/{repository()}/actions/runs/{failed['id']}/artifacts?per_page=100")["artifacts"]
                if not isinstance(artifacts, list):
                    raise ValueError("workflow artifacts missing")
                # Red public-health checks are expected. Their state artifact proves that the
                # classifier ran and its own notification owns the incident.
                if not any(item.get("name") == f"external-health-state-{peer_mode}" for item in artifacts):
                    return "peer_failed"
            return None
        return finding
    except (KeyError, ValueError, urllib.error.URLError, TimeoutError):
        return "peer_api_unavailable"


def read_previous(path: Path) -> tuple[list[str], bool, bool]:
    if not path.exists():
        return [], False, False
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
        codes = value["codes"]
        delivered = value["delivered"]
        if not isinstance(codes, list) or not all(isinstance(code, str) for code in codes) or type(delivered) is not bool:
            raise ValueError("invalid state")
        return codes, delivered, False
    except (OSError, ValueError, KeyError, TypeError):
        return [], False, True


def write_state(path: Path, codes: list[str], delivered: bool) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({"schemaVersion": 1, "codes": sorted(set(codes)), "delivered": delivered},
                               separators=(",", ":")) + "\n", encoding="utf-8")


def description(code: str) -> str:
    if code in MESSAGES:
        return MESSAGES[code]
    endpoint = next((name for name in URLS if code.startswith(name + "_")), "")
    reason = code[len(endpoint) + 1:] if endpoint else ""
    label = {"origin": "공개 원점", "gateway": "게이트웨이", "game_api": "게임 API"}.get(endpoint, "공개 검사")
    suffix = {
        "timeout": "시간 초과", "unreachable": "무응답", "cloudflare_52x": "Cloudflare 52x",
        "http_error": "HTTP 비정상", "invalid_response": "응답 형식 이상",
    }.get(reason, "상태 이상")
    return f"{label}: {suffix}"


def payload(kind: str, codes: list[str], mode: str, now: datetime, run_id: int, exercise: bool = False) -> dict:
    title = "[운영 공개 감시 장애]" if kind == "incident" else "[운영 공개 감시 복구]"
    if exercise:
        title = "[전송 시험] " + title
    lines = [description(code) for code in codes] if codes else ["공개 검사와 상대 감시기 실행이 정상입니다."]
    return {"username": "opensamguk-external-health", "embeds": [{
        "title": title, "color": 0xE74C3C if kind == "incident" else 0x2ECC71,
        "description": "\n".join(lines)[:3000],
        "fields": [
            {"name": "감시기", "value": mode, "inline": True},
            {"name": "실행 시각 UTC", "value": now.isoformat(), "inline": True},
            {"name": "실행", "value": f"https://github.com/{repository()}/actions/runs/{run_id}", "inline": False},
        ],
    }]}


def deliver(body: dict) -> bool:
    url = os.environ.get("DAEMON_ALERT_WEBHOOK_URL", "")
    if not url:
        print("::warning::existing alert webhook is not configured; alert NOT delivered")
        return False
    data = json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode()
    for attempt in range(3):
        try:
            request = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(request, timeout=10):
                return True
        except Exception:
            # Exception 문자열에 webhook URL이 섞일 수 있으므로 절대 출력하지 않는다.
            if attempt < 2:
                time.sleep(2 * (attempt + 1))
    print("::error::alert dispatch failed; alert NOT delivered")
    return False


def execute(mode: str, current_run_id: int) -> int:
    now = datetime.now(timezone.utc)
    previous_path = Path(os.environ.get("PREVIOUS_STATE_FILE", "previous-state/monitor-state.json"))
    state_path = Path(os.environ.get("CURRENT_STATE_FILE", "current-state/monitor-state.json"))
    previous, previously_delivered, invalid_previous = read_previous(previous_path)
    findings: list[str] = []
    if invalid_previous:
        findings.append("state_unavailable")
    if os.environ.get("PREVIOUS_STATE_EXPECTED") == "true" and not previous_path.exists():
        findings.append("state_unavailable")
    if os.environ.get("STATE_LOOKUP_ERROR") == "true":
        findings.append("state_unavailable")
    if mode == "probe":
        for endpoint, url in URLS.items():
            status, response, transport = public_get(url)
            code = classify_http(endpoint, status, response, transport, now)
            if code:
                findings.append(code)
    peer = peer_result(mode, now, current_run_id)
    if peer:
        findings.append(peer)
    findings = sorted(set(findings))
    kind = transition(previous, findings, previously_delivered)
    delivered = previously_delivered if kind is None else deliver(payload(kind, findings, mode, now, current_run_id))
    # 복구 전송 실패 시 이전 사고를 보존해 다음 실행에서 재시도한다.
    stored = previous if kind == "recovered" and not delivered else findings
    write_state(state_path, stored, delivered)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    line = f"{mode}: {', '.join(findings) if findings else 'healthy'}; notification={kind or 'suppressed'}; delivered={delivered}\n"
    print(line, end="")
    if summary:
        with open(summary, "a", encoding="utf-8") as stream:
            stream.write(line)
    exercise_failed = False
    if os.environ.get("EXERCISE_ALERT_RECOVERY") == "true" and mode == "probe":
        if findings or previous:
            print("delivery exercise skipped because monitor state is not healthy")
        else:
            alert_sent = deliver(payload("incident", ["delivery_test"], mode, now, current_run_id, exercise=True))
            recovery_sent = deliver(payload("recovered", [], mode, now, current_run_id, exercise=True)) if alert_sent else False
            exercise_failed = not (alert_sent and recovery_sent)
            print("delivery exercise:", "sent" if not exercise_failed else "failed")
    return 1 if findings or (kind is not None and not delivered) or exercise_failed else 0


def main() -> int:
    if len(sys.argv) != 3 or sys.argv[1] not in {"find-state", "execute"} or sys.argv[2] not in WORKFLOWS:
        print("usage: external_health_run.py {find-state|execute} {probe|watchdog}", file=sys.stderr)
        return 2
    mode = sys.argv[2]
    run_id = int(os.environ["GITHUB_RUN_ID"])
    return find_state(mode, run_id) if sys.argv[1] == "find-state" else execute(mode, run_id)


if __name__ == "__main__":
    sys.exit(main())
