#!/usr/bin/env python3
"""main 브랜치 CI 적색·복구 알림 (#866).

`Main CI Alert` 워크플로가 CI 완료(workflow_run)마다 부른다. main push 로 돈 CI 가
빨갛게 끝나면 실패한 잡 이름을, 빨강 뒤 처음 초록이 되면 복구를 데몬 경보와 같은
Discord webhook(DAEMON_ALERT_WEBHOOK_URL)으로 보낸다.

- jvm 이 한도에 걸리면 conclusion 은 failure 가 아니라 cancelled 다. CI 에는
  concurrency 취소가 없으므로 main 의 cancelled 는 곧 한도 초과라 적색으로 센다.
- webhook 이 없으면 경고만 남기고 성공한다. 보내기 실패는 워크플로를 빨갛게 한다.
"""
from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.request

RED = frozenset({"failure", "timed_out", "cancelled", "startup_failure"})
COLOR_RED = 0xE74C3C
COLOR_GREEN = 0x2ECC71


def api(path: str) -> dict:
    base = os.environ.get("GITHUB_API_URL", "https://api.github.com")
    request = urllib.request.Request(
        f"{base}{path}",
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {os.environ['GITHUB_TOKEN']}",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        return json.load(response)


def failed_jobs(repo: str, run_id: int) -> list[str]:
    jobs = api(f"/repos/{repo}/actions/runs/{run_id}/jobs?per_page=100")["jobs"]
    return sorted(job["name"] for job in jobs if job.get("conclusion") in RED)


def previous_conclusion(repo: str, run: dict) -> str | None:
    """같은 워크플로의 main push 완료 실행 중 이 실행 바로 앞의 결론."""
    runs = api(
        f"/repos/{repo}/actions/workflows/{run['workflow_id']}/runs"
        "?branch=main&event=push&status=completed&per_page=20"
    )["workflow_runs"]
    earlier = [r for r in runs if r["id"] != run["id"] and r["created_at"] < run["created_at"]]
    if not earlier:
        return None
    return max(earlier, key=lambda r: r["created_at"])["conclusion"]


def decide(run: dict, jobs: list[str] | None, previous: str | None) -> dict | None:
    """보낼 Discord 메시지. 보낼 것이 없으면 None."""
    if run.get("head_branch") != "main" or run.get("event") != "push":
        return None
    sha = run["head_sha"][:8]
    subject = ((run.get("head_commit") or {}).get("message") or "").splitlines()[0][:200] or "(메시지 없음)"
    fields = [
        {"name": "commit", "value": sha, "inline": True},
        {"name": "run", "value": run["html_url"], "inline": False},
        {"name": "subject", "value": subject, "inline": False},
    ]
    if run.get("conclusion") in RED:
        names = ", ".join(jobs or []) or "(잡 목록 없음)"
        fields.insert(1, {"name": "failed", "value": names[:1000], "inline": False})
        title, color = f"[main CI 적색] {run['conclusion']} @ {sha}", COLOR_RED
    elif run.get("conclusion") == "success" and previous in RED:
        title, color = f"[main CI 복구] success @ {sha}", COLOR_GREEN
    else:
        return None
    return {
        "username": "opensamguk-main-ci",
        "embeds": [{"title": title, "color": color, "fields": fields}],
    }


def send(url: str, payload: dict, attempts: int = 3) -> None:
    body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode()
    for attempt in range(1, attempts + 1):
        request = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=10):
                return
        except (urllib.error.URLError, TimeoutError) as error:
            if attempt == attempts:
                raise RuntimeError(f"webhook dispatch failed after {attempts} attempts: {error}") from None
            time.sleep(2 * attempt)


def main() -> int:
    repo = os.environ["GITHUB_REPOSITORY"]
    run = api(f"/repos/{repo}/actions/runs/{int(os.environ['RUN_ID'])}")
    red = run.get("conclusion") in RED
    jobs = failed_jobs(repo, run["id"]) if red else None
    previous = None if red else previous_conclusion(repo, run)
    payload = decide(run, jobs, previous)
    if payload is None:
        print(f"main CI alert: nothing to send (conclusion={run.get('conclusion')} previous={previous})")
        return 0
    title = payload["embeds"][0]["title"]
    url = os.environ.get("DAEMON_ALERT_WEBHOOK_URL", "")
    if not url:
        print(f"::warning::alert webhook is not configured; NOT delivered: {title}")
        return 0
    try:
        send(url, payload)
    except RuntimeError as error:
        print(f"::error::{error}; NOT delivered: {title}")
        return 1
    print(f"main CI alert dispatched: {title}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
