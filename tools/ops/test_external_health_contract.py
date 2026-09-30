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
    def game(self, **overrides):
        value = {"status": "OPEN", "year": 190, "month": 1, "turnPhase": 1, "turnTerm": 10,
                 "lastTurnAt": "2026-09-28T11:50:00Z", "nextTurnAt": "2026-09-28T12:00:00Z",
                 "lastTickExecutedAt": "2026-09-28T11:50:00Z", "serverTime": NOW.isoformat(),
                 "turnLoop": {"state": "RUNNING", "staleSeconds": 600}}
        value.update(overrides)
        return body({"game": value, "me": None})

    def test_200_contracts(self):
        self.assertIsNone(classify_http("origin", 200, body({"status": "up", "nginx": "ok"}), None, NOW))
        self.assertIsNone(classify_http("gateway", 200, body({"status": "UP", "app": "web-gateway"}), None, NOW))
        self.assertIsNone(classify_http("game_api", 200, self.game(), None, NOW))

    def test_game_api_502_has_own_reason(self):
        self.assertEqual("game_api_down", classify_http("game_api", 502, b"", None, NOW))

    def test_cloudflare_522_is_not_a_generic_http_error(self):
        self.assertEqual("origin_cloudflare_52x", classify_http("origin", 522, b"", None, NOW))

    def test_timeout_and_unreachable_are_distinct(self):
        self.assertEqual("origin_timeout", classify_http("origin", None, None, "timeout", NOW))
        self.assertEqual("origin_unreachable", classify_http("origin", None, None, "network", NOW))

    def test_invalid_200_is_an_incident(self):
        self.assertEqual("gateway_invalid_response", classify_http("gateway", 200, b"<html>ok</html>", None, NOW))

    def test_successful_tick_wall_time_detects_stall(self):
        self.assertEqual("turn_stalled", classify_http("game_api", 200,
                         self.game(lastTickExecutedAt="2026-09-28T11:29:59Z"), None, NOW))
        self.assertIsNone(classify_http("game_api", 200, self.game(), None, NOW))

    def test_missing_clock_cannot_be_healthy(self):
        self.assertEqual("game_api_invalid_response", classify_http("game_api", 200,
                         self.game(lastTickExecutedAt=None), None, NOW))

    def test_nullable_contract_fields_must_be_present_even_when_paused(self):
        for missing in ("lastTurnAt", "nextTurnAt", "lastTickExecutedAt", "serverTime"):
            value = json.loads(self.game(status="PRE_OPEN", turnLoop={"state": "PAUSED", "staleSeconds": None}))
            del value["game"][missing]
            self.assertEqual("game_api_invalid_response", classify_http("game_api", 200, body(value), None, NOW))
        self.assertEqual("game_api_invalid_response", classify_http("game_api", 200,
                         self.game(status="PRE_OPEN", turnLoop={"state": "PAUSED"}), None, NOW))

    def test_25_hour_stop_cannot_be_healthy_even_with_long_cadence(self):
        self.assertEqual("turn_stalled", classify_http("game_api", 200,
                         self.game(turnTerm=600, lastTickExecutedAt="2026-09-27T10:59:59Z",
                                   nextTurnAt="2026-09-27T20:59:59Z", turnLoop={"state": "RUNNING", "staleSeconds": 90001}),
                         None, NOW))

    def test_intentional_pause_is_not_a_turn_stall(self):
        self.assertIsNone(classify_http("game_api", 200,
                          self.game(status="PRE_OPEN", lastTurnAt="2026-09-26T00:00:00Z",
                                    nextTurnAt="2026-09-26T00:10:00Z",
                                    turnLoop={"state": "PAUSED", "staleSeconds": 216000}), None, NOW))

    def test_20_hour_catch_up_is_healthy_while_wall_ticks_advance(self):
        for multiplier in (2, 4):
            value = self.game(lastTurnAt="2026-09-27T16:00:00Z", nextTurnAt="2026-09-27T16:10:00Z",
                              catchUp={"active": True, "multiplier": multiplier},
                              turnLoop={"state": "CATCHING_UP", "staleSeconds": 600})
            self.assertIsNone(classify_http("game_api", 200, value, None, NOW))
            stopped = self.game(lastTurnAt="2026-09-27T16:00:00Z",
                                lastTickExecutedAt="2026-09-28T11:29:59Z",
                                catchUp={"active": True, "multiplier": multiplier},
                                turnLoop={"state": "CATCHING_UP", "staleSeconds": 1801})
            self.assertEqual("turn_stalled", classify_http("game_api", 200, stopped, None, NOW))

    def test_first_turn_waiting_requires_a_future_schedule(self):
        self.assertIsNone(classify_http("game_api", 200,
                          self.game(lastTickExecutedAt=None, nextTurnAt="2026-09-28T13:00:00Z",
                                    turnLoop={"state": "WAITING", "staleSeconds": None}), None, NOW))
        self.assertEqual("game_api_invalid_response", classify_http("game_api", 200,
                         self.game(lastTickExecutedAt=None, turnLoop={"state": "WAITING", "staleSeconds": None}),
                         None, NOW))


class HeartbeatTest(unittest.TestCase):
    def test_cancelled_fixture(self):
        runs = [{"id": 99, "event": "schedule", "created_at": "2026-09-28T11:55:00Z",
                 "status": "completed", "conclusion": "cancelled"}]
        self.assertEqual("peer_cancelled", classify_peer_runs(runs, NOW, 100))
        runs.append({"id": 101, "event": "schedule", "created_at": "2026-09-28T11:58:00Z",
                     "status": "queued", "conclusion": None})
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
