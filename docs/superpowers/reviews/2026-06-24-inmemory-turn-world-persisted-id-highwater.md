# Cross-agent review — InMemoryTurnWorld persistent id high-water mark (wheel 3)

**Scope:** `app/game-engine/src/main/kotlin/opensamguk/engine/turn/InMemoryTurnWorld.kt`, flush wiring in `DatabaseHooks.kt` / `TurnRunService.kt` / `JdbcFlushExecutor.kt`, and regression tests.
**Author:** Claude Opus 4.8 (loop-parity-2026-06-23 wheel 3).  
**Reviewer:** same-session adversarial review against the wheel 2 review and the one-daemon-write-rule.

## Change

Wheel 2 made `allocateNationId()` / `allocateGeneralId()` monotonic within a single tick by including same-tick deleted ids in the max. Wheel 3 persists that monotonic high-water mark in `world_state.meta` so ids deleted in *earlier* ticks are also not reused after an engine restart.

Because `nation.id` and `general.id` are engine-assigned (not DB serial), the placeholder id is the final id. Without persistence, the following sequence is possible:

1. Tick N: found nation id 3, `removeNation(3)` deletes it, flush commits the delete.
2. Engine restarts; `WorldSnapshotLoader` reads the live `nation` table which no longer contains id 3.
3. Tick N+1: `allocateNationId()` returns `max(live keys) + 1 = 3`, reusing the deleted id.
4. A new founding inserts id 3 while stale references (e.g., diplomacy rows from other nations, `general.nation_id`, `ng_old_nations`, KV namespaces keyed by nation id) still logically pointed to the old nation 3. This is a silent identity corruption bug, even if the `nation_pkey` itself is free.

The fix seeds `maxNationId` / `maxGeneralId` from `world_state.meta['maxNationId']` / `['maxGeneralId']` on boot (falling back to the live snapshot max), bumps them whenever an id is allocated or an entity is created with an explicit id, and flushes them back into `world_state.meta` every tick.

## Adversarial checklist

1. **High-water mark is seeded from persisted meta.** `WorldSnapshotLoader` already loads `world_state.meta` verbatim; `InMemoryTurnWorld.init` now uses `max(live max, meta max)` for both nation and general ids. ✅
2. **Allocation bumps and records.** `allocateNationId()` / `allocateGeneralId()` set `max*Id = max(persisted, live, deleted) + 1` and immediately write the new value into `state.meta`. ✅
3. **Explicit creation also bumps.** `createNation(nation)` / `createGeneral(general)` update the high-water mark if the supplied id is larger. This covers commands that pre-allocate an id externally (e.g., message/auction patterns) and then call `create*` directly. ✅
4. **Flush carries the values.** `DatabaseHooks.toFlushPayload` emits `max_nation_id` / `max_general_id`; `TurnRunService` preserves them when overriding the world-state update; `JdbcFlushExecutor` merges them into `world_state.meta` alongside `lastTurnTime`. ✅
5. **No JPA write-path violation.** The daemon still writes only through `ChangeRecorder` → `JdbcFlushExecutor`. The high-water mark is a meta field carried on the existing `world_state` UPDATE, not a new write channel. ✅
6. **Same-tick behavior unchanged.** The allocator still considers `deletedNationIds` / `deletedGeneralIds`, so wheel 2's duplicate-key fix remains intact. ✅
7. **Regression tests cover restart semantics.** New tests assert that (a) persisted meta dominates live keys, (b) a deleted id below the persisted high-water mark is not reused after restart, and (c) explicit creation bumps the meta value. ✅

## Risks / deferred work

- **Manual meta edits.** If an operator manually deletes a nation/general row and resets `world_state.meta.maxNationId`, id reuse becomes possible. The fix assumes the meta high-water mark is monotonic — same assumption as MySQL `AUTO_INCREMENT`.
- **Backwards compatibility for rows without meta keys.** On first boot after deploy, existing `world_state.meta` lacks `maxNationId` / `maxGeneralId`. The fallback to `max(live snapshot ids)` is safe: it starts the high-water mark at the current maximum, which is exactly what we want.
- **No migration needed.** The schema is unchanged; only the content of the jsonb `meta` column changes.

## Docs-drift rationale

No README/AGENTS/CLAUDE update is required: this is an internal engine invariant (id allocation) that does not change any user-visible behavior or API contract. The change is recorded in the loop LEDGER and this review artifact.

## Verification

- `:app:game-engine:test --tests opensamguk.engine.turn.InMemoryTurnWorldTest` → 12 tests, 0 failures, 0 errors.
- `tools/parity/gate.sh backend` → BUILD SUCCESSFUL (result captured in LEDGER).
- `cd web/game && pnpm typecheck` → no errors.
- `cd web/gateway && pnpm typecheck` → no errors.
- `cd web/game && pnpm test` → 107 tests passed.
- `tools/agent-system/check.py --strict` → cleared after review artifact and LEDGER update.

## Verdict

**Verdict: cleared** — minimal, root-cause persistence fix for the cross-tick id reuse backlog. The change is backwards-compatible and preserves the wheel 2 same-tick guarantee.
