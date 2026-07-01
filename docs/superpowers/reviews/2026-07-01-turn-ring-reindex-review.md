# Turn ring reindex review

**Date:** 2026-07-01
**Scope:** `general_turn` and `nation_turn` ring pull/push reindexing
**Verdict: cleared**

## Root Cause

After promoting diagnostics to `s1`, `/admin/turn-daemon/status` reported `running=true`, `paused=false`, and `loopAlive=true`, but `successfulTicks=0`, `failedTicks=71`, and the latest tick error was:

`DuplicateKeyException ... UPDATE general_turn SET turn_idx = turn_idx - ? ... duplicate key value violates unique constraint "general_turn_general_id_turn_idx_key" ... Key (general_id, turn_idx)=(1002, 29) already exists.`

The repository attempted to model PHP/MySQL ordered ring updates with two PostgreSQL `UPDATE` statements. PostgreSQL does not provide row update ordering that avoids transient `UNIQUE (general_id, turn_idx)` or `UNIQUE (nation_id, officer_level, turn_idx)` collisions. A full 30-row general ring can collide when `turn_idx=30` is moved to `29` while the old `29` row still exists.

## Fix Review

- The fix moves all rows for the selected ring into a disjoint temporary index space before computing their final modulo index.
- Final indexing is based on the previous index modulo the ring length, so rows left at `turn_idx=30` by an older half-failed pull are recovered into the valid `0..29` ring.
- The same collision-avoidance strategy is applied to `general_turn` pull/push and `nation_turn` pull/push.
- The change stays inside the existing JDBC repository. No JPA write path or daemon dirty-source change is introduced.

## Evidence

- `JAVA_HOME=21 ./gradlew :infra:test --tests '*ReservedTurnRepositoryIT*' --tests '*NationTurnRingIT*' --rerun-tasks`
  - `ReservedTurnRepositoryIT`: 7 tests, 0 failures, 0 errors
  - `NationTurnRingIT`: 6 tests, 0 failures, 0 errors
- `JAVA_HOME=21 ./gradlew :app:game-engine:test --tests '*TurnRunServiceIT*' --tests '*RebirthAndRingTest*' --rerun-tasks`
  - `TurnRunServiceIT`: 1 test, 0 failures, 0 errors
  - `RebirthAndRingTest`: 10 tests, 0 failures, 0 errors

## Remaining Risk

Production still needs the merged image promoted to `s1` and then rechecked through the daemon diagnostics. The expected healthy signal is `successfulTicks > 0`, `consecutiveFailures = 0`, and public game time advancing past the previously stuck `188년 4월 중순`.
