# OPENSAM-9 city defence-train read parity golden set

## Scope and authority

- Task-local authority: the root orchestrator confirmed that its explicit user-authorized
  OPENSAM-9 delegation is the bounded contract for this isolated worktree. The global
  `.ai/task.md` remains an unrelated OPENSAM-43 marker and is intentionally untouched.
- Owned implementation files: `CityDetailController.kt` and `CityDetailControllerTest.kt`.
- This is a read-only game-api slice. No daemon, flush, schema, legacy, or golden fixture
  change is in scope.

## Oracle facts

- PHP reads `defence_train` with a default of `80` in
  `legacy/devsam-core/hwe/j_set_my_setting.php:18`; values above `90` normalize to `999`
  at lines 28-32.
- PHP city detail selects `defence_train` at
  `legacy/devsam-core/hwe/b_currentCity.php:214`, exposes it only for an own general at
  lines 303-323, and aggregates only own armed generals at lines 392-438.
- The aggregation order is: skip no-nation/viewer-no-nation; split foreign rows; add own
  total; skip `crew == 0`; then evaluate `min(train, atmos) >= defenceTrain`.

## Deterministic acceptance checks

1. An own general with `meta["defence_train"] = 90` is counted at the exact
   `min(train, atmos) == 90` boundary and not below it.
2. A missing metadata key defaults to `80`; a `999` threshold is excluded by normal
   comparison; a zero-crew own row is not counted.
3. A foreign row neither contributes to `crewDef`/`genDef` nor has its metadata exposed.
4. The controller continues to use `GeneralReadRepository`'s process-world-scoped
   `findByCityIdOrderByTurnTimeAsc` seam, rather than any raw/worldless query.
5. Focused and module game-api tests have fresh XML with zero failures and errors.

## Non-applicable parity dimensions

This read aggregation performs no RNG draw, rounding, Korean log emission, mutation, or
insertion-order-sensitive output. The review must verify that the change stays read-only.
