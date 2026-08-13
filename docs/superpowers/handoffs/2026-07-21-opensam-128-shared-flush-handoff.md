# Handoff: OPENSAM-128/130/131/132 → v2 just-in-time persistence owners

- **Date:** 2026-07-21
- **Foundation sequence:** ARCH-S2-T3 / OPENSAM-128 (world-scoped C/U/D) → ARCH-S3-T1 /
  OPENSAM-130 (immutable generations) → ARCH-S3-T2 / OPENSAM-131 (writer fence and
  order-preserving `world_version` CAS) → ARCH-S3-T3 / OPENSAM-132 (fail-closed recovery)
- **Original consumer (superseded 2026-08-13):** OPENSAM-44 broad v2 persistence batch
- **Active consumers:** OPENSAM-150 for the first product leaf, then each just-in-time product owner
  named by `2026-08-13-opensam-44-contract-crosswalk.md`

## Complete contract ready for consumers

### OPENSAM-128 — world-scoped JDBC flush

- All V32 world-owned flush methods take `worldId: WorldId` and bind/predicate `world_id`.
- Mixed `game_kv` preserves global `inheritance` (`world_id IS NULL`) vs world-owned families.
- Architecture tests: `JdbcFlushExecutorWorldScopeTest` (method list + unscoped DML scan).
- Global exception: `inheritance_log` remains user-global (not V32 world-owned).

### OPENSAM-130 — immutable delta generation

- `DeltaGenerationSession` freezes one prepared `ChangeRecorder` batch until `commit` or `abort`;
  only successful commit clears that generation, while abort retains retry material.
- Intake/tick/mutation cannot mutate a prepared generation. Duplicate/illegal transitions are
  idempotent or fail explicitly rather than damaging another generation.
- Evidence: `DeltaGenerationSessionTest`; independent review
  `docs/superpowers/reviews/2026-07-21-opensam-130-generation.md` (`cleared`).

### OPENSAM-131 — writer fence, ordered CAS, and atomic rollback

- A fenced payload carries `expectedWorldVersion` and `writerEpoch`. The existing canonical
  `world_state` step applies `(world_id, writer_epoch, world_version)` CAS and increments version
  exactly once; the step is not moved after parity-sensitive JDBC operations.
- A zero-row/stale CAS throws `StaleWorldWriterException`, rolling back every earlier statement in
  the same transaction. Local version advances only after the transaction commits.
- Evidence: `WorldVersionCasIT` drives the real executor for matching CAS/version advance and stale
  CAS with no `world_state` advance. The canonical step position and transaction-wide rollback
  requirement are source-inspected contract evidence recorded by independent review
  `docs/superpowers/reviews/2026-07-21-opensam-131-world-version-cas.md` (`cleared`); this handoff does
  not claim that `WorldVersionCasIT` itself asserts operation traces or an earlier entity write.

### OPENSAM-132 — recovery and readiness

- Transient timeout/connection failure enters `FLUSH_RETRY` and retains the exact immutable payload
  for same-generation retry. Stale writer/CAS failure enters `RELOAD_REQUIRED` without blind retry.
- Both modes block intake/tick and report readiness DOWN. Successful retry restores the committed
  in-memory clock; `RELOAD_REQUIRED` remains fail-closed until scoped reload/process restart.
- Evidence: `TurnRunServiceFlushRecoveryTest`, `FlushRecoveryHealthIndicator` assertions, and
  independent review `docs/superpowers/reviews/2026-07-21-opensam-132-flush-recovery.md` (`cleared`).

## Rules for OPENSAM-150 and later just-in-time consumers
- Do not co-widen `JdbcFlushExecutor` / `ChangeRecorder` channels without sequential handoff.
- New entity flush steps must accept process `WorldId` and never emit unscoped live SQL for world-owned tables.
- Append new rows to the prepared generation; never drain/clear around `DeltaGenerationSession`.
- Preserve the existing JDBC operation order and canonical `world_state` CAS position. New steps
  participate in the same fenced transaction and must roll back on stale epoch/version.
- Do not catch or downgrade `StaleWorldWriterException`, `FLUSH_RETRY`, or `RELOAD_REQUIRED`.
  Consumer tests must prove retry uses the identical payload, stale CAS leaves no partial entity
  writes, local version/clock advances only after commit, and readiness/intake remain blocked during recovery.
