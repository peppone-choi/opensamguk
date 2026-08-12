# OPENSAM-149 active unique-auction rehydrate gate review

Date: 2026-08-12
Scope: `app/game-engine/`, `logic/`, and task-local `docs/` for the bounded active unique-auction resident/reload projection repair.
Reviewer: independent read-only `lazycodex-code-reviewer` agents `/root/implement_opensam149_rehydrate_gate/op149_independent_review` and `/root/implement_opensam149_rehydrate_gate/op149_exact_sha_review`.
Verdict: cleared

## Reviewed change set

- `app/game-engine/src/main/kotlin/opensamguk/engine/boot/WorldSnapshotLoader.kt`
- `app/game-engine/src/main/kotlin/opensamguk/engine/flush/DatabaseHooks.kt`
- `logic/src/main/kotlin/opensamguk/logic/memory/HotColdCatalog.kt`
- `app/game-engine/src/test/kotlin/opensamguk/engine/boot/RehydrateLosslessGateIT.kt`
- `docs/loops/opensam-149-rehydrate-gate/LEDGER.md`

The exact reviewed source commit is `83132d4a765bc2eae67ed6259e5ba1382aae98a2`, based on
`origin/main` `53f5d5ebc14e283d1f0dec1758ccb4bf2eaf3497`. Its source diff SHA-256 is
`02667bd49e9742f96e8a3ed0235add1e63e22df575be2d2607d76969cb355b43`.

## Independent findings

No BLOCKER, MAJOR, MINOR, or QUESTION finding remained.

- The loader keeps its `world_id` predicate, active-unique predicate, and `ng_auction.id ASC`
  order while retaining an ordered nullable `id -> target` companion projection. Its existing
  `activeUniqueAuctionItems` list remains the runtime-reader contract.
- The projection refresh is in the live `(world, recorder, dirty)` payload overload only. It
  consumes full existing `AuctionInfo.toArray()` recorder rows and adds no dirty channel, database
  call, `EntityManager`, or second daemon write path.
- Type, finalization, target, and multiple same-id upserts follow the executor's ordered upsert
  semantics. A failed flush cannot admit a divergent next turn: `TurnRunService` blocks work,
  retains the payload for retry, or requires reload before further work.
- `ALWAYS_HOT` now matches the boot-restored metadata projection consumed by the resident
  unique-item occupancy reader.
- The test is behavioral integration coverage, not implementation mirroring: it uses Flyway,
  PostgreSQL, `ChangeRecorder`, `DatabaseHooks`, `JdbcFlushExecutor`, and a fresh
  `WorldSnapshotLoader`; it also proves same-local-id rows remain world-isolated.
- The reviewed diff does not wire `RehydrateService`, change a migration or golden fixture, or
  modify `TurnRunService` / `ReservedTurnHandler` for the separately tracked same-due-tick case.

## Verification observed

- Focused Testcontainers command (run before the clean-base rebase; exact source code was then
  independently re-reviewed at the commit above):
  `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:test --tests 'opensamguk.engine.boot.RehydrateLosslessGateIT' --rerun-tasks`
  completed `BUILD SUCCESSFUL in 1m 25s`.
- Fresh JUnit XML: `tests=2`, `skipped=0`, `failures=0`, `errors=0`.
- `git diff --check` passed on the reviewed source diff.

## Explicit limits

This clearance covers the approved committed flush -> reload boundary for the unique-auction
projection. It is not a claim that the planned full `TurnRunService` N -> restart -> N+1 behavioral
gate has run. Same-due-tick intake/lifecycle visibility remains a separately owned follow-up and is
not silently expanded by this change.
