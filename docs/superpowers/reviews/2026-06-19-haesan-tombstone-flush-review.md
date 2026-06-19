# 2026-06-19 haesan tombstone flush review

Verdict: cleared for PR with targeted regression green; full local `:app:game-engine:test` is blocked by local Docker/Testcontainers availability and must be covered by CI.

## Scope

Fix the live/prod symptom where nations appear to vanish or stale nation rows accumulate after turns. The closed loop is specifically `che_해산` for wandering nations: registration and normal entry were already verified in the previous loop, while this loop fixes the nation deletion seam that was leaving dead `level=0` nation rows behind.

## Legacy Evidence

- `legacy/devsam-core/hwe/sammo/Command/General/che_해산.php:44-47`: command is lord-only and wandering-nation-only.
- `legacy/devsam-core/hwe/sammo/Command/General/che_해산.php:103-119`: command logs disband, calls `deleteNation($general, false)`, applies returned generals, and runs occupy-city event handlers.
- `legacy/devsam-core/hwe/func.php:1713-1805`: `deleteNation` is the canonical cascade.
- `legacy/devsam-core/hwe/func.php:1753-1778`: all generals are reverted to neutral/officerless state and action/history logs are emitted.
- `legacy/devsam-core/hwe/func.php:1780-1797`: owned cities become neutral, troops are deleted, `ng_old_nations` is inserted, `nation` and `nation_turn` are deleted, and diplomacy rows are deleted.

## Prod Baseline

Observed on live `s1` before the patch:

- `world_state`: `182|11|OPEN|scenario_1010`
- `nation`: 90 rows total, with `level=0` 88 rows, `level=3` 1 row, `level=7` 1 row.
- City ownership: neutral 70, nation 1 owns 14, nation 2 owns 10.
- Recent logs contain repeated `GENERAL|ACTION|...멸망했습니다.` rows for level-0 named nations such as `진표`.

This is not a display-only bug. The DB contained stale nation rows after the destroy/disband logs.

## Root Cause

`CheHaesan` already exposes the deleted nation id via `lastDeletedNationId`, but `ReservedTurnHandler` did not consume it. The handler resolved the action draft and then diffed general/city/log changes, so the world saw neutralized generals and logs but no nation tombstone.

A second flush-surface gap hid inside the existing tombstone helper: `ChangeRecorder.markNationDeleted` appended a `DeletedNationSnapshot` to the recorder, while `DatabaseHooks.toFlushPayload(world, recorder, dirty)` reads deleted nation snapshots from `DirtyState`. That made the three-argument flush builder capable of deleting the nation id but not archiving the `ng_old_nations` payload.

## Patch

- `ReservedTurnHandler` detects `CheHaesan` after resolve and calls `recorder.markNationDeleted(world, deletedNationId)` before applying the draft diff.
- `ChangeRecorder.markNationDeleted` records the same `DeletedNationSnapshot` into both the recorder and the world dirty state.
- `FoundingHandlerSeamTest` adds a handler-level regression for `che_해산`: the nation leaves the world, the flush payload includes `deletedNations=[7]`, the old-nation snapshot keeps `general_ids=[42]`, and the lord becomes neutral.

## Verification

Targeted command:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:test --tests opensamguk.engine.turn.FoundingHandlerSeamTest --tests opensamguk.engine.turn.KillTombstoneTest --tests opensamguk.engine.turn.TombstoneEmitterTest --rerun-tasks
```

XML results:

- `FoundingHandlerSeamTest`: 4 tests, 0 failures, 0 errors.
- `KillTombstoneTest`: 11 tests, 0 failures, 0 errors.
- `TombstoneEmitterTest`: 4 tests, 0 failures, 0 errors.

Additional checks:

- `git diff --check`: pass.
- `tools/agent-system/check.py --strict --base origin/main`: 0 errors, 0 warnings.
- Local full `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:test --rerun-tasks`: blocked by Docker daemon absence. The 6 failures were Testcontainers initialization errors (`Could not find a valid Docker environment`) in Docker-backed IT classes; the targeted tombstone XML files remained green.

## Remaining Risk

Existing live stale `level=0` nation rows are historical data. This patch prevents the `che_해산` handler path from creating new dead rows, but it does not mass-clean old prod rows. A reset/reseed or explicit cleanup loop can remove the already accumulated rows.
