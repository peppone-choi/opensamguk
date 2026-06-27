# Turn Date 36-Per-Year Review

**Date:** 2026-06-27  
**Scope:** `logic/src`, `infra/src`, `app/game-engine/src`, `app/game-api/src`, `common/src`, `tools/wiki`, and v2 research docs.  
**Reviewer:** cross-agent-critique guard; a Codex native API-contract reviewer was requested but did not complete before shutdown, so this artifact records the deterministic evidence and remaining risks.

## Findings

No blocking findings after the cleanup pass.

## Contract Checks

1. **World-state phase is durable.** `world_state.current_phase` is added in `V1__baseline.sql` and `V21__world_state_current_phase.sql`, loaded through `WorldSnapshotLoader`, carried in `TurnWorldState`, and flushed by `JdbcFlushExecutor`.
2. **Engine and read APIs use the same source of truth.** `ServerClock.turnDate` returns `GameDate(year, month, phase)`, while `FrontInfoController`, `WorldStateReadRepository`, and `ReservedCommandsController` read `current_phase` from `world_state` instead of deriving it from local wall time.
3. **`common/src` wire compatibility is intentional.** `RealtimeEvent.TurnCompleted` adds nullable `turnPhase` and `turnPhaseText` fields, preserving old payload readers while allowing new clients to render 상순/중순/하순. `TurnRunServiceIT` decodes the Redis-published `RealtimeEvent` and asserts both fields, so the serialized contract is covered.
4. **Monthly replay gate no longer hides the gap.** `MonthTickReplayGateTest` now asserts 상순→중순→하순 and only expects the golden's next month on the third turn.
5. **External evidence was captured for v2 scope.** `tools/wiki/scrape_myosam_help.py` fetched 37 myosam help pages with 0 failures, `/Users/apple/Downloads/files/` was copied into local raw wiki storage, and samnet root was re-fetched on 2026-06-27 showing `218년 12월 상순`, 2D mode, yellow-turban entry, recent situation logs, and siege battle replays.

## Risks / Deferred Work

- Existing realtime consumers that hard-require an exact field set should ignore the two nullable additions; no such consumer was found in this backend pass, but browser QA should still watch the live SSE surface after deploy.
- v2 features from the user request (dynamic war replay, retainers, court bias, subfactions, fiefs, feudal contracts) are intentionally documented as design work, not implemented in this v1 phase fix branch.
- `docs/wiki/` remains git-ignored by repository policy. The reproducible scraper and research memo are tracked; raw wiki pages are local artifacts unless the project changes that policy.

## Verification

- `:logic:test --tests opensamguk.logic.golden.MonthTickReplayGateTest` passed after fixing the harness expectation.
- `:logic:compileKotlin :app:game-api:compileKotlin` passed after cleanup.
- `tools/wiki/scrape_myosam_help.py --out docs/wiki/raw/myosam-help --timeout 30 --delay 0.05` → `fetched=37 failures=0`.
- `tools/parity/gate.sh backend` previously passed on this branch with `BUILD SUCCESSFUL`, XML aggregate `437 suites, 3219 tests`; final run is repeated before merge.
- `git diff --check` passed.

## Verdict

**Verdict: cleared** — the branch closes the 삼모 순 calendar gap without weakening golden gates, and the wire/API expansion is additive and test-covered.
