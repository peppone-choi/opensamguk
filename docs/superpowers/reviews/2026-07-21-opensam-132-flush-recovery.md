# Review: OPENSAM-132 FLUSH_RETRY / RELOAD recovery gate

- **Ticket:** OPENSAM-132 / GH #278 / PR #309 (+ evidence close PR)
- **Verdict:** cleared
- **Date:** 2026-07-21
- **Reviewer lane:** independent adversarial (post-land evidence close)

## Real path under review

- `FlushRecoveryGate` modes: READY | FLUSH_RETRY | RELOAD_REQUIRED
- `TurnRunService.flushWithGeneration` → `onFlushFailure` classifies:
  - `StaleWorldWriterException` → RELOAD_REQUIRED (no retained payload)
  - transient timeout/connection → FLUSH_RETRY (retains immutable payload)
- `runIntakeCommands` / `runTick` call `requireIntakeOrTickAllowed` — blocked when not READY
- `retryRetainedFlush` reuses retained payload for same-generation retry only in FLUSH_RETRY
- `TurnDaemonRunner` skips tick/intake while not READY; on FLUSH_RETRY invokes `retryRetainedFlush`
- `FlushRecoveryHealthIndicator` downs health while not READY; status exposes non-sensitive mode/reason

## GWT vs ship

| Acceptance | Evidence |
|------------|----------|
| Stop intake/tick during recovery | `TurnRunServiceFlushRecoveryTest` asserts IllegalStateException on real `runIntakeCommands`/`runTick` after flush failure |
| FLUSH_RETRY same-batch resume | `retryRetainedFlush resumes READY` + daemon loop calls `retryRetainedFlush` |
| RELOAD_REQUIRED no blind retry | test forbids `retryRetainedFlush` in RELOAD; daemon only sleeps (fail-closed) until process reload/markRecovered |
| Readiness fail while recovering | `FlushRecoveryHealthIndicator` Health.down with mode/worldId/generation/reason |

## Intentional build-only limits

- Full scoped **reload materialization** (WorldSnapshotLoader re-bootstrap after RELOAD) is not auto-invoked in-loop — fail-closed stop is the gate; process restart / later reload protocol completes RELOAD resume. Documented, not silent continue-on-dirty.
- DB kill/fault-injection IT matrix deferred; unit path proves classification + entry-point stop/resume contracts.

## Verdict

**cleared** — recovery stops real intake/tick; FLUSH_RETRY resume is wired and tested; RELOAD is fail-closed without same-payload retry.
