# Turn daemon diagnostics review

**Date:** 2026-07-01
**Scope:** `app/game-engine` turn daemon status diagnostics
**Verdict: cleared**

## Why this exists

Production `s1` was confirmed deployed to image tag `8ba9758933c15a31e772dac60da022a7063b6a74`, with `game-api` and `game-engine` both reachable and not version-skewed. Public `server-basic-info` still reported `188년 4월 중순` across multiple samples longer than `turnTerm=60`, while the admin turn-daemon status reported `running=true`, `paused=false`, and `loopAlive=true`.

The previous status payload could only distinguish paused/idle/running. It could not distinguish a healthy ticking daemon from a live loop repeatedly failing before flush, nor could it show the in-memory clock that decides the next due tick.

## Review

- The change is observability-only: it does not add a new write path and does not weaken the one-daemon-write rule.
- `TurnRunService.clockSnapshot()` reads the existing in-memory `TurnWorldState` and `nextRunTime()`; it does not mutate world state or consume dirty state.
- `TurnDaemonRunner.diagnostics()` records tick success/failure counters and the last failure message from the existing exception handler. Failed ticks already remained retryable because `JdbcFlushExecutor` owns one-transaction flush semantics.
- The status endpoint remains admin-gated through gateway-api in production. The additional fields expose operational clock and exception class/message to admins only.

## Evidence

- Public `s1` samples stayed at `188년 4월 중순` for more than two turn terms.
- Admin status before this patch: `state=running`, `running=true`, `paused=false`, `loopAlive=true`.
- Admin version before this patch: gateway, `s1` game-api, and `s1` game-engine all reported image tag `8ba9758933c15a31e772dac60da022a7063b6a74`.
- Targeted test gate after this patch:
  - `TurnDaemonRunnerTest`: 7 tests, 0 failures, 0 errors
  - `StatusControllerTest`: 4 tests, 0 failures, 0 errors
  - `GameEngineApplicationTests`: 1 test, 0 failures, 0 errors

## Remaining risk

This patch does not by itself fix the production tick freeze. It makes the next production status read show whether the daemon is failing each tick, waiting on a future `nextRunTime`, or advancing in memory without the expected public DB projection.
