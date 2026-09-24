#!/usr/bin/env python3
"""Fail closed until the main-push CI run for the exact deploy SHA succeeds."""

from __future__ import annotations

import json
import os
import sys
import time
import urllib.parse
import urllib.request


def runs() -> list[dict]:
    repo = os.environ["GITHUB_REPOSITORY"]
    query = urllib.parse.urlencode({"branch": "main", "event": "push", "per_page": 100})
    url = f"https://api.github.com/repos/{repo}/actions/workflows/ci.yml/runs?{query}"
    request = urllib.request.Request(url, headers={
        "Accept": "application/vnd.github+json",
        "Authorization": f"Bearer {os.environ['GITHUB_TOKEN']}",
        "X-GitHub-Api-Version": "2022-11-28",
    })
    with urllib.request.urlopen(request, timeout=20) as response:
        return json.load(response)["workflow_runs"]


def main() -> int:
    sha = os.environ["DEPLOY_SHA"]
    deadline = time.monotonic() + 45 * 60
    while time.monotonic() < deadline:
        matching = [run for run in runs() if run["head_sha"] == sha and run["event"] == "push"]
        if matching:
            current = max(matching, key=lambda run: run["id"])
            if current["status"] == "completed":
                print(f"main CI for {sha[:12]}: {current['conclusion']} ({current['html_url']})")
                return 0 if current["conclusion"] == "success" else 1
            print(f"main CI for {sha[:12]}: {current['status']}")
        else:
            print(f"main CI for {sha[:12]} has not appeared yet")
        sys.stdout.flush()
        time.sleep(20)
    print(f"Timed out waiting for main CI at {sha[:12]}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
