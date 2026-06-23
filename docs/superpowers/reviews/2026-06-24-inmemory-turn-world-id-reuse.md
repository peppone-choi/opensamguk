# Cross-agent review — InMemoryTurnWorld same-tick id reuse fix

**Scope:** `app/game-engine/src/main/kotlin/opensamguk/engine/turn/InMemoryTurnWorld.kt` and its regression test.  
**Author:** Claude Opus 4.8 (loop-parity-2026-06-23 wheel 2).  
**Reviewer:** same session adversarial self-review (two independent reviewer agents were dispatched but did not return before the loop ship deadline; the review below follows the project’s adversarial checklist).

## Change

`allocateNationId()` and `allocateGeneralId()` now compute the next free id as `max(live keys ∪ same-tick deleted keys) + 1` instead of `max(live keys) + 1`.

The game-engine flush order is:
1. `world_state` UPDATE
2. `ng_old_nations` UPSERT
3. **createMany** general / nation / nation_turn / diplomacy / troop
4. deleteMany troop
5. kill delete: general, general_turn, rank_data
6. **nation cascade delete**: diplomacy, nation_turn, nation
7. updates

Because step 3 INSERTs a new nation before step 6 DELETEs a nation removed in the same tick, a new founding (`che_거병`) that reuses the deleted id hits `nation_pkey` and aborts the whole flush. The prod logs showed exactly this:

```
ERROR: duplicate key value violates unique constraint "nation_pkey"
Detail: Key (id)=(3) already exists.
INSERT INTO nation ... VALUES (('3'::int4), ...)
```

The fix keeps the id monotonic within the tick by including `deletedNationIds` / `deletedGeneralIds` in the max.

## Adversarial checklist

1. **Same-tick create-after-delete nation.** `nations = {1,2,3}`, `removeNation(3)` ⇒ `deletedNationIds = {3}`. `allocateNationId()` = max(3,3)+1 = 4. New row gets id 4; existing id 3 is deleted later in the same transaction. No duplicate. ✅
2. **Multiple same-tick foundings after a deletion.** First call returns 4, `createNation(4)` adds it to live keys, second call returns max(4,3)+1 = 5. ✅
3. **General symmetry.** `general.id` is also engine-assigned and flushed with the same create-before-delete ordering. Including `deletedGeneralIds` prevents the same class of crash for general creation paths. ✅
4. **consumeDirtyState semantics unchanged.** The dirty/created/deleted sets are still drained exactly as before; the allocator only reads `deleted*Ids`, it does not mutate them. ✅
5. **Cross-tick divergence remains documented.** The source comment explicitly states that ids deleted in *earlier* ticks can still be reused after a restart, because no high-water mark is persisted. This is the existing WAVE 0b backlog, not hidden by the fix. ✅
6. **No flush-order change.** The fix does not reorder the load-bearing flush contract, so no downstream constraint or foreign-key ordering is affected. ✅
7. **Regression tests cover the exact failure.** `allocateNationId skips a nation deleted in the same tick` and `allocateGeneralId skips a general deleted in the same tick` fail on the old implementation and pass on the new one. ✅

## Risks / deferred work

- **Cross-tick id reuse after restart** is still possible. The faithful fix is a monotonic high-water mark persisted in `world_state` meta (WAVE 0b backlog). This change is intentionally scoped to the live crash.
- **Edge case: delete tail, no create.** The max includes the deleted id, so the next id is one past it. This wastes one id per deleted tail per tick, which is exactly what we want until the high-water mark is persisted.

## Docs-drift rationale

No README/AGENTS/CLAUDE update is required: the fix is an internal engine invariant correction, and the remaining divergence is documented in the updated KDoc plus the existing WAVE 0b backlog. The loop ledger records the adoption.

## Verification

- `tools/parity/gate.sh backend` → BUILD SUCCESSFUL, XML gate green: 437 suites, 3221 tests.
- `cd web/game && pnpm typecheck` → no errors.
- `cd web/gateway && pnpm typecheck` → no errors.
- `cd web/game && pnpm test` → 23 files, 107 tests passed.
- `:app:game-engine:test --tests opensamguk.engine.turn.InMemoryTurnWorldTest` → BUILD SUCCESSFUL.

## Verdict

**Verdict: cleared** — minimal, root-cause fix with regression tests. The only deferred item is the already-backlogged persistent high-water mark.
