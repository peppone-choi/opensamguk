#!/usr/bin/env python3
"""VM 밖 공개 감시의 순수 판정 계약. 응답 본문은 결과에 포함하지 않는다."""

from __future__ import annotations

import json
from datetime import datetime, timezone
from typing import Any

ENDPOINTS = ("origin", "gateway", "game_api")
SCHEDULE_SECONDS = 300
MISSED_SCHEDULE_SECONDS = SCHEDULE_SECONDS * 3
MAX_BODY_BYTES = 65536


def parse_time(value: Any) -> datetime | None:
    if not isinstance(value, str):
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    return parsed.astimezone(timezone.utc) if parsed.tzinfo else None


def classify_http(
    endpoint: str,
    status: int | None,
    body: bytes | None,
    transport: str | None,
    now: datetime,
) -> str | None:
    """한 공개 GET의 상태 코드. None은 정상이다."""
    if endpoint not in ENDPOINTS:
        raise ValueError("unknown endpoint")
    if transport == "timeout":
        return f"{endpoint}_timeout"
    if transport is not None:
        return f"{endpoint}_unreachable"
    if status is None:
        return f"{endpoint}_unreachable"
    if endpoint == "game_api" and status == 502:
        return "game_api_down"
    if 520 <= status <= 529:
        return f"{endpoint}_cloudflare_52x"
    if status != 200:
        return f"{endpoint}_http_error"
    if body is None or len(body) > MAX_BODY_BYTES:
        return f"{endpoint}_invalid_response"
    try:
        value = json.loads(body)
    except (UnicodeDecodeError, json.JSONDecodeError):
        return f"{endpoint}_invalid_response"
    if not isinstance(value, dict):
        return f"{endpoint}_invalid_response"
    if endpoint == "origin":
        if value.get("status") != "up" or value.get("nginx") != "ok":
            return "origin_invalid_response"
        return None
    if endpoint == "gateway":
        if value.get("status") != "UP" or value.get("app") != "web-gateway":
            return "gateway_invalid_response"
        return None
    game = value.get("game")
    if not isinstance(game, dict) or game.get("status") not in {"CLOSED", "PRE_OPEN", "OPEN"}:
        return "game_api_invalid_response"
    if any(type(game.get(key)) is not int for key in ("year", "month", "turnPhase", "turnTerm")):
        return "game_api_invalid_response"
    if not (game["year"] > 0 and 1 <= game["month"] <= 12 and 1 <= game["turnPhase"] <= 3
            and game["turnTerm"] >= 0):
        return "game_api_invalid_response"
    # 이 필드는 현행 공개 배포에는 없다. 존재할 때만 정지 판정을 한다.
    turn_time = game.get("lastTurnTime")
    if turn_time is None or game["status"] != "OPEN" or game["turnTerm"] == 0:
        return None
    last_turn = parse_time(turn_time)
    if last_turn is None:
        return "game_api_invalid_response"
    age = (now - last_turn).total_seconds()
    if age < -SCHEDULE_SECONDS:
        return "game_api_invalid_response"
    if age > 3 * game["turnTerm"] * 60:
        return "turn_stalled"
    return None


def classify_peer_runs(runs: list[dict[str, Any]], now: datetime, current_run_id: int) -> str | None:
    """상대 GitHub 호스트 감시기의 최근 일정 실행을 판정한다."""
    scheduled = [run for run in runs if run.get("event") == "schedule" and run.get("id") != current_run_id]
    scheduled = [(run, parse_time(run.get("created_at"))) for run in scheduled]
    scheduled = [(run, created) for run, created in scheduled if created is not None]
    if not scheduled:
        return "peer_not_started"
    run, created = max(scheduled, key=lambda item: item[1])
    age = (now - created).total_seconds()
    if run.get("conclusion") == "cancelled":
        return "peer_cancelled"
    if run.get("conclusion") in {"failure", "timed_out", "startup_failure"}:
        return "peer_failed"
    if age > MISSED_SCHEDULE_SECONDS:
        return "peer_schedule_missing"
    if run.get("status") == "completed" and run.get("conclusion") != "success":
        return "peer_invalid_run"
    if run.get("status") not in {"queued", "in_progress", "completed"}:
        return "peer_invalid_run"
    return None


def transition(previous: list[str], current: list[str], previously_delivered: bool) -> str | None:
    """동일 사고를 억제하고 정상 복구를 한 번 알린다."""
    before = sorted(set(previous))
    after = sorted(set(current))
    if after:
        return "incident" if after != before or not previously_delivered else None
    return "recovered" if before else None
