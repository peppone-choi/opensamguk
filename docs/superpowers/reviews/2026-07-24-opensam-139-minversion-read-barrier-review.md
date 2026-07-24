# OPENSAM-139 MinVersion Read Barrier Review

Date: 2026-07-24
Scope: `.codex/` pre-existing personal config overlay baseline-separated; `.ai/`, `app/game-api/`, `app/game-engine/`, `common/`, and `docs/` OPENSAM-139 / ARCH-S5-T3 build-only minVersion read barrier slice.
Reviewer: `lazycodex-code-reviewer` (`019f929f-267b-7403-9dc5-368b92679195`) follow-up re-review.
Verdict: cleared

## Reviewed Scope

Reviewed the OPENSAM-139/#285 ARCH-S5-T3 working-tree diff for:

- `committedWorldVersion` wire propagation from daemon/API terminal result envelopes to `GET /api/command/result/{requestId}`.
- Realtime result publication using exact stored payload JSON through direct fallback and outbox relay.
- Primary-read `minVersion` barrier with a dedicated small Hikari pool, bounded acquisition, and 409 `VERSION_NOT_VISIBLE`.
- HandlerInterceptor classification with eventual endpoint denylist no-op behavior.
- One-daemon-write and PHP parity invariants.

## Findings

No critical, high, or medium fix-required findings remain.

Prior blockers were resolved:

- Pool saturation now has a bounded acquisition timeout and returns ordinary not-visible behavior instead of waiting on Hikari's 30 second default.
- Eventual denylisted endpoints now preserve existing behavior and do not call the barrier even when `minVersion` is present.
- The unused `WorldStateReadEntity.worldVersion` mapping was removed.
- The timing-based integration test sleep was replaced with latch coordination.

Remaining note: end-to-end payload-path coverage through `VerticalSliceE2EIT` was attempted and reverted because `game-engine:compileTestKotlin` repeatedly stalled in this environment. This is an evidence gap, not a release blocker; the changed production seams are covered by focused common, game-engine publisher/relay, and game-api barrier tests.

## Evidence

- `git diff --check main`: pass.
- Focused game-api XML inspected by reviewer:
  - `ReadConsistencyBarrierTest` 2 tests, 0 failures, 0 errors.
  - `ReadConsistencyClassifierTest` 3 tests, 0 failures, 0 errors.
  - `ReadConsistencyInterceptorTest` 5 tests, 0 failures, 0 errors.
  - `ReadConsistencyBarrierIT` 3 tests, 0 failures, 0 errors.
- Executor-observed focused gates:
  - `:common:test --tests opensamguk.common.wire.RealtimeEventWireTest`: `BUILD SUCCESSFUL in 1m 21s`.
  - `:app:game-api:test` focused barrier/result suite: `BUILD SUCCESSFUL in 4m 57s`.
  - `:app:game-engine:test` focused intake/relay suite: `BUILD SUCCESSFUL in 4m 56s`.

## Result

cleared
