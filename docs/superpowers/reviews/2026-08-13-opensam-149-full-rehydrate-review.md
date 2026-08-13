# OPENSAM-149 full rehydrate turn-gate review

Date: 2026-08-13
Reviewer: independent read-only `lazycodex-code-reviewer`
Scope: `app/game-engine/` bounded `N -> discard/reload -> N+1` restart equivalence and its focused integration gate
Exact source SHA: `a9a167881c86c8d2458baec985027c2d1134ef10`
Exact base SHA: `f4ee9135ad6cbce1c6cfb28f7113d7742f478282`
Verdict: cleared

## Scope reviewed

- `app/game-engine/src/main/kotlin/opensamguk/engine/boot/WorldSnapshotLoader.kt`
- `app/game-engine/src/test/kotlin/opensamguk/engine/boot/FullRehydrateTurnGateIT.kt`
- `docs/loops/opensam-149-rehydrate-gate/LEDGER.md`

The review covers the bounded `N -> discard/reload -> N+1` restart-equivalence gate. It does not
infer all-channel rehydration coverage.

## Findings and resolution

The first review blocked because the later architecture run had replaced the game-engine test-results
directory, so no focused XML remained for direct inspection. The exact source SHA was rerun after the
shared JVM slot was reacquired. The reviewer directly inspected the retained post-commit XML and
cleared the blocker.

No source defect or remaining fix-required finding was identified. The integration test's size is a
nonblocking maintainability watch: its behavior coverage is real and non-tautological, using
Testcontainers PostgreSQL, Flyway, the live turn runner, JDBC flush, and a fresh snapshot loader.

## Evidence inspected

- Focused JDK 21 `--rerun-tasks` command: `BUILD SUCCESSFUL in 19m 47s`, 17 tasks executed.
- Retained `FullRehydrateTurnGateIT` XML mtime: `2026-08-13T18:10:10+0900`.
- Focused XML: `tests=1`, `skipped=0`, `failures=0`, `errors=0`; Testcontainers connected, Flyway
  migrated, and five `WorldSnapshotLoader` loads were recorded.
- Earlier serialized architecture XML: `DaemonNoEntityManagerTest` and
  `InfraNoEntityManagerTest` each `tests=1`, `skipped=0`, `failures=0`, `errors=0`.
- Exact source diff check: clean.

## Explicit quarantine

- EventStore event insert/delete is not covered.
- Resident general/nation allocator continuation is not covered.
- Diplomacy-letter allocator continuation is not covered.
- Same-due-tick intake visibility remains a separate follow-up.
- No all-channel or all-allocator completion claim is made.

The broader backend parity gate remains host-blocked by unrelated Testcontainers startup failures and
is not represented as green.

## Decision

APPROVED for the bounded full-rehydrate turn-gate scope above. No merge or deployment authorization
is implied.
