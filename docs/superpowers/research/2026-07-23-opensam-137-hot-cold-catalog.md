# OPENSAM-137 ARCH-S5-T1 Hot/Cold Catalog

Date: 2026-07-23
Scope: build-only minimal slice for `ARCH-S5-T1`.
Design source: `docs/superpowers/plans/2026-07-23-opensam-137-hot-cold-prefetch-design.md` after it appeared in the worktree; initial fallback was `docs/superpowers/plans/2026-07-18-cqrs-memory-consistency-hardening-plan.md` (`ARCH-S5-T1`).
Code artifact: `logic/src/main/kotlin/opensamguk/logic/memory/HotColdCatalog.kt`
Guard: `app/game-engine/src/test/kotlin/opensamguk/engine/boot/HotColdWorldCatalogGuardTest.kt`

## CodeGraph Inventory

CodeGraph was used before source edits to inspect `WorldSnapshotLoader`, `InMemoryTurnWorld`, `TurnRunService`, `MonthlyPostUpdateHook`, `TurnDaemonCommandDispatcher`, and the existing architecture guards.

Observed boot snapshot flow:

| Loader method | Relation/cohort | Catalog class | Current order/bound |
|---|---|---|---|
| `loadWorldState` | `world_state` | always-hot | configured singleton row |
| `loadGameEnv` | `game_kv:game_env` | always-hot | `id ASC`, legacy keyset |
| `resolveActiveGame` | `ng_games` | query-only cold | configured `server_id` or singleton fallback |
| `loadServerCount` | `ng_games` | query-only cold | aggregate |
| `loadStatisticRows` | `statistic` | query-only cold | legacy full scan, `id ASC` |
| `loadNationHistory` | `log_entry` nation history | query-only cold | legacy full scan, `nation_id ASC, id DESC` |
| `loadGeneralHistory` | `log_entry` general history | query-only cold | legacy full scan, `general_id ASC, id DESC` |
| `loadGlobalLogs` | `log_entry` system history/action | query-only cold | legacy full scan, `id DESC` |
| `loadActiveUniqueAuctionItems` | `ng_auction` active unique items | query-only cold | active set, `id ASC` |
| `loadStoredUniqueItemCounts` | `game_kv` unique item counts | query-only cold | aggregate by namespace |
| `loadInheritancePoints` | `game_kv` inheritance rows | query-only cold | legacy full scan, `id ASC` |
| `loadNationEnv` | `nation_env` | always-hot | `id ASC`, legacy keyset |
| `loadNations` | `nation` | always-hot | `id ASC` |
| `loadCities` | `city` | always-hot | `id ASC` |
| `loadGenerals` | `general` | always-hot | `id ASC` |
| `loadRankValues` | `rank_data` | always-hot | `general_id ASC, id ASC` |
| `loadDiplomacy` | `diplomacy` | always-hot | `id ASC` |
| `loadAccessLogs` | `general_access_log` | always-hot | `general_id ASC` |
| `loadArchivedNationIds` | `ng_old_nations` | query-only cold | active server set, `nation ASC` |

Observed runtime read seams:

| Seam | Boundary |
|---|---|
| `ReservedTurnRepository` | reserved-turn phase exact slot read |
| `CommandInboxRepository` | command intake claim/pending lease batch |
| `CommandResultRepository` | command outbox relay pending batch + published marker |
| `AuctionRepository`, `AuctionBidRepository` | command/month boundary active set |
| `GameKvRepository`, `BettingRepository`, `InheritanceRepository` | command/tournament phase readers |
| `BoardPostRepository`, `VotePollRepository`, `DiplomacyLetterRepository`, `SelectPoolRepository`, `ContactReader` | command boundary readers |
| `MessageRepository` | boot allocator aggregate |

The guard is default-deny for runtime repository/read-seam calls: it infers typed
`Repository`/`Reader` receivers, aliases such as Elvis-return locals and `let`
aliases, repository-returning helper functions, and implicit calls inside
repository extension functions. It then compares method-agnostic calls on those
receivers with `HotColdCatalog.runtimeReadSeams`, so new methods such as
`loadOpenForPhase`, `findOpenForPhase`, `fetchOpenForPhase`, `lookupOpenForPhase`,
`getOpenForPhase`, `streamOpenForPhase`, or `existsOpenForPhase` fail until they
are cataloged. The only excluded method names are trivial Kotlin/Object helpers
and local adapter factory methods whose underlying repository reads are detected
separately.

The runtime source scope now includes `engine/turn` and `engine/redis` in addition
to the command/auction/tournament/world paths. `RehydrateService` remains a
cataloged `QUERY_ONLY_COLD` direct-SQL recovery boundary, not hot-loop logic; the
guard detects method-agnostic calls on typed JDBC receivers such as
`JdbcOperations.queryForList`, `NamedParameterJdbcOperations.queryForStream`,
`NamedParameterJdbcOperations.queryForRowSet`, and
`java.sql.Connection.prepareStatement`, and requires those files to appear in
`HotColdCatalog.runtimeDirectSqlBoundaries`.

Latest focused evidence:

- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:test --tests opensamguk.engine.boot.HotColdWorldCatalogGuardTest --no-daemon --no-configuration-cache --no-build-cache --console=plain -Dkotlin.compiler.execution.strategy=in-process`
- Tail: `BUILD SUCCESSFUL in 59s`; `17 actionable tasks: 5 executed, 12 up-to-date`.
- XML: `TEST-opensamguk.engine.boot.HotColdWorldCatalogGuardTest.xml` has `tests="9" skipped="0" failures="0" errors="0"` at `2026-07-23T13:35:12`.

## Residuals

This slice does not remove full-history boot scans. The catalog deliberately marks `statistic`, `log_entry` history/action, and inheritance rows as `LEGACY_FULL_SCAN_PENDING_S5_T2`; `ARCH-S5-T2` owns bounded retention and heap proof.

This slice does not activate a new phase-prefetch runtime. The guard makes future additions declare the relation, access boundary, ordering, and bound before code can pass tests.
