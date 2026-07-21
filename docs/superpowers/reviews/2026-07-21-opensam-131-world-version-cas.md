# Review: OPENSAM-131 world_version CAS + writer_epoch fence

- **Ticket:** OPENSAM-131 / GH #277 / PR #308
- **Verdict:** cleared
- **Date:** 2026-07-21
- **Reviewer lane:** independent adversarial (post-land evidence close)

## Real path under review

- Flyway `V33__world_version_writer_fence.sql` adds `world_state.world_version` + `writer_epoch`
- `JdbcFlushExecutor.worldStateUpdate` optional CAS: when `expected_world_version` + `writer_epoch` present, UPDATE requires match and bumps version; 0 rows → `StaleWorldWriterException` (transaction rolls back)
- Engine: `WorldSnapshotLoader` loads fence; `TurnRunService.applyWriterFence` stamps CAS keys on intake/tick flush; advances local version only after commit

## GWT vs ship

| Acceptance | Evidence |
|------------|----------|
| Matching fence advances version by 1 | `WorldVersionCasIT.matching CAS advances world_version by one` |
| Stale fence does not advance version / rolls back side effects | `WorldVersionCasIT.stale CAS throws and does not advance version` |
| Order-preserving (canonical world_state step) | CAS lives in existing step-1 `worldStateUpdate`, not a reordered post-commit write |
| No second daemon write truth | Still ChangeRecorder → JdbcFlushExecutor only |

## Residual / intentional limits

- Writer-epoch *acquisition/bump at boot* is deferred (later protocol); loaded epoch is used as fence predicate.
- CAS remains optional when keys absent (legacy/test payloads without fence keys still flush).

## Verdict

**cleared** — no GWT self-narrow; stale CAS fail-closed; IT drives real executor path.
