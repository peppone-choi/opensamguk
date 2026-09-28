#!/usr/bin/env python3
"""공개 감시 분류 fixture. 네트워크와 비밀값을 사용하지 않는다."""

import json
import sys
import unittest
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from external_health_contract import classify_http, classify_peer_runs, transition  # noqa: E402

NOW = datetime(2026, 9, 28, 12, 0, tzinfo=timezone.utc)


def body(value):
    return json.dumps(value).encode()


class PublicResponseTest(unittest.TestCase):
    def test_200_contracts(self):
        self.assertIsNone(classify_http("origin", 200, body({"status": "up", "nginx": "ok"}), None, NOW))
        self.assertIsNone(classify_http("gateway", 200, body({"status": "UP", "app": "web-gateway"}), None, NOW))
        game = {"game": {"status": "OPEN", "year": 190, "month": 1, "turnPhase": 1, "turnTerm": 10}, "me": None}
        self.assertIsNone(classify_http("game_api", 200, body(game), None, NOW))

    def test_game_api_502_has_own_reason(self):
        self.assertEqual("game_api_down", classify_http("game_api", 502, b"", None, NOW))

    def test_cloudflare_522_is_not_a_generic_http_error(self):
        self.assertEqual("origin_cloudflare_52x", classify_http("origin", 522, b"", None, NOW))

    def test_timeout_and_unreachable_are_distinct(self):
        self.assertEqual("origin_timeout", classify_http("origin", None, None, "timeout", NOW))
        self.assertEqual("origin_unreachable", classify_http("origin", None, None, "network", NOW))

    def test_invalid_200_is_an_incident(self):
        self.assertEqual("gateway_invalid_response", classify_http("gateway", 200, b"<html>ok</html>", None, NOW))

    def test_optional_public_turn_time_detects_stall(self):
        game = {"game": {"status": "OPEN", "year": 190, "month": 1, "turnPhase": 1, "turnTerm": 10,
                         "lastTurnTime": "2026-09-28T11:29:59Z"}}
        self.assertEqual("turn_stalled", classify_http("game_api", 200, body(game), None, NOW))
        game["game"]["lastTurnTime"] = "2026-09-28T11:40:00Z"
        self.assertIsNone(classify_http("game_api", 200, body(game), None, NOW))


class HeartbeatTest(unittest.TestCase):
    def test_cancelled_fixture(self):
        runs = [{"id": 99, "event": "schedule", "created_at": "2026-09-28T11:55:00Z",
                 "status": "completed", "conclusion": "cancelled"}]
        self.assertEqual("peer_cancelled", classify_peer_runs(runs, NOW, 100))

    def test_missing_or_delayed_schedule_is_not_success(self):
        runs = [{"id": 99, "event": "schedule", "created_at": "2026-09-28T11:40:00Z",
                 "status": "completed", "conclusion": "success"}]
        self.assertEqual("peer_schedule_missing", classify_peer_runs(runs, NOW, 100))
        self.assertEqual("peer_not_started", classify_peer_runs([], NOW, 100))

    def test_recent_success_is_healthy(self):
        runs = [{"id": 99, "event": "schedule", "created_at": "2026-09-28T11:55:00Z",
                 "status": "completed", "conclusion": "success"}]
        self.assertIsNone(classify_peer_runs(runs, NOW, 100))


class TransitionTest(unittest.TestCase):
    def test_duplicate_suppression_and_recovery(self):
        self.assertEqual("incident", transition([], ["game_api_down"], True))
        self.assertIsNone(transition(["game_api_down"], ["game_api_down"], True))
        self.assertEqual("incident", transition(["game_api_down"], ["game_api_down"], False))
        self.assertEqual("recovered", transition(["game_api_down"], [], True))
        self.assertIsNone(transition([], [], True))


if __name__ == "__main__":
    unittest.main()
