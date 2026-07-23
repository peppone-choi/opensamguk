# OPENSAM-137 (ARCH-S5-T1 / GH #283) — Hot/cold catalog and deterministic phase-boundary prefetch: design

> Status: **DESIGN ONLY — no production code changed.**
> Date: 2026-07-23
> Ticket: Jira `OPENSAM-137`, GitHub `peppone-choi/opensamguk#283`, Draft ID `ARCH-S5-T1`, parent Story `ARCH-S5` / `OPENSAM-121` / `#267`.
> Source plan: `docs/superpowers/plans/2026-07-18-cqrs-memory-consistency-hardening-plan.md` §5 `ARCH-S5-T1`.
> Branch: `peppone-choi/arowana` (main-aligned).

## 0. Dependency status (checked 2026-07-23 via `gh issue view`)

| Dependency | Jira / GH | State | Effect on this ticket |
|---|---|---|---|
| `ARCH-S1-T3` capacity thresholds/admission policy | `OPENSAM-125` / `#271` | **OPEN** | Blocks *activation* only. |
| `ARCH-S4-T4` command crash/replay fault-matrix gate | `OPENSAM-136` / `#282` | **CLOSED** | Satisfied. |

The ticket's own dependency line is explicit: *"preliminary access-graph 조사는 앞서 병렬 수행할 수 있지만 activation은 두 의존성 뒤에만 한다"* — the catalog, contract, and architecture-test **design and construction** may proceed now (that is this document, plus the code Codex writes from §E), but **enabling the new tests as a merge-blocking gate and cutting production call sites over to the prefetch cache must wait for `OPENSAM-125`/`#271` to close.** §D restates this as a non-goal.

## A. Catalog categories

Three categories, matching the ticket's GWT wording verbatim (always-hot / phase-hot / query-only cold). The catalog is a single Kotlin object — proposed `logic/src/main/kotlin/opensamguk/logic/memory/HotColdCatalog.kt` — that is the one source of truth both the architecture tests and a human reviewer read. It does not itself hold data; it declares, per data kind, the category, the resident collection or loader symbol, and (for `PHASE_HOT`) the bound/order contract from §B.

### A.1 ALWAYS_HOT — resident in `InMemoryTurnWorld` for the whole process lifetime

Read and mutated every tick, inside RNG draw loops and entity iteration. Already correctly modeled as `LinkedHashMap`s in `InMemoryTurnWorld` — no change needed, the catalog just names them so future additions are checked against this list instead of ad hoc.

| Data kind | Resident symbol | Notes |
|---|---|---|
| generals | `InMemoryTurnWorld.generals` (`app/game-engine/src/main/kotlin/opensamguk/engine/turn/InMemoryTurnWorld.kt:44`) | keyed by id, insertion order = boot `ORDER BY id ASC` |
| cities | `InMemoryTurnWorld.cities` (`:45`) | same |
| nations | `InMemoryTurnWorld.nations` (`:46`) | same |
| troops | `InMemoryTurnWorld.troops` (`:47`) | boot-empty per `WorldSnapshotLoader` (no troop rows in seed) |
| diplomacy | `InMemoryTurnWorld.diplomacy` (`:48`), keyed by `(from,to)` | |
| general access log | `InMemoryTurnWorld.accessLogs` (`:49`) | small, 1 row/general |
| dirty/created/deleted id sets | `:51-70` | `ChangeRecorder`-adjacent bookkeeping, not DB-backed |
| battle-in-progress `WarUnit`/`WarUnitGeneral`/`WarUnitCity` | `logic/src/main/kotlin/opensamguk/logic/war/WarUnit.kt`, `ProcessWarNG.kt:35` params | staged once per battle by `BattleCommandContextBuilder.build()` (`app/game-engine/src/main/kotlin/opensamguk/engine/war/BattleCommandContextBuilder.kt:25-76`) from the four `InMemoryTurnWorld.list*()` accessors, then threaded by reference through the whole RNG draw loop — this is the existing, correct pattern §B generalizes |
| `GeneralAI`/`AutorunGeneralPolicy`/`AutorunNationPolicy` inputs | `logic/src/main/kotlin/opensamguk/logic/ai/GeneralAI.kt:134,251,336` | pure functions over `GeneralAiInput`/`NationAiInput` (`:408,438`) built entirely from `world.listGenerals()` etc. — confirmed zero repository imports in this file or `AiTurnAdapter.kt` (only a type-only import of a nested `ReservedTurnRepository.ReservedTurn` data class at `AiTurnAdapter.kt:8`, never a live call) |

### A.2 PHASE_HOT — bounded, prefetched once at phase start, then read-only for the phase body

Not resident all the time, but *not* lazily queried mid-phase either — loaded once (keyset/limit-bounded, stable order) immediately before the phase that consumes it, held in a short-lived cache for that phase's duration, discarded (or refreshed) at the next phase boundary. This is the category this ticket adds; today the codebase has exactly one instance of the *shape* this should generalize, and it currently does it as a direct ad hoc repository call rather than through a named prefetch step:

| Data kind | Current call site | Why phase-hot (not always-hot, not cold) |
|---|---|---|
| open auctions (`finished = false`) | `MonthlyPostUpdateHook.registerAuction()` → `auctionRepository?.findByFinishedFalse()` (`app/game-engine/src/main/kotlin/opensamguk/engine/run/MonthlyPostUpdateHook.kt:375`) | Needed once per Month phase (Q15/neutral-auction reconciliation), naturally bounded by `finished=false`, but is a live repository call reached during phase execution rather than a named, catalog-declared prefetch — the concrete migration target for §E |

The ticket's second GWT ("architecture test rejects a new access not in the catalog") means this table is expected to **grow** as future work adds more phase-scoped reads (e.g. a bounded "generals due for autorun this tick" keyset, a bounded "active reserved-turn ring window"). This design does not invent new phase-hot entries beyond the one that already exists in disguise — it gives that one a name and a contract, and defines the mechanism the next one plugs into.

### A.3 QUERY_ONLY_COLD — never resident in the daemon; read on demand, outside the daemon's hot path

Two independent lines of evidence show this category already exists and already has the right *shape* on the read side (game-api), but the daemon boot path duplicates several of these into memory anyway:

**Already correctly cold** (game-api `read` package, never touched by `game-engine`):

| Data kind | Cold reader |
|---|---|
| yearbook history | `app/game-api/src/main/kotlin/opensamguk/gameapi/read/HistoryReadRepository.kt:15-53` (comment: *"yearbook_history READ — process-world scoped (OPENSAM-127 residual / GWT cold-history)"*) |
| general turn history | `app/game-api/src/main/kotlin/opensamguk/gameapi/read/GeneralTurnReadRepository.kt:28-81` |
| nation turn history | `app/game-api/src/main/kotlin/opensamguk/gameapi/read/NationTurnReadRepository.kt:23-68` |
| rank/log feed | `RankDataReadRepository.kt:20-58`, `LogFeedReadRepository.kt` (same directory) |

**Miscategorized today — loaded ALWAYS_HOT at boot despite being query-only in nature.** `WorldSnapshotLoader.buildSnapshot()` (`app/game-engine/src/main/kotlin/opensamguk/engine/boot/WorldSnapshotLoader.kt:51-143`) eagerly loads and stuffs into `TurnWorldState.meta` (i.e. keeps resident for the process lifetime) several full, unbounded, `LIMIT`-less scans that the game logic never reads inside a tick — they exist only for display/history endpoints that game-api already serves cold via the repositories above:

| Data kind | Boot loader (unbounded) | Existing cold equivalent |
|---|---|---|
| nation history text | `loadNationHistory()` (`:264-277`, `SELECT ... FROM log_entry WHERE scope='NATION' AND category='HISTORY'`, no LIMIT) | `HistoryReadRepository` / `NationTurnReadRepository` |
| general history text | `loadGeneralHistory()` (`:279-292`, same shape) | `GeneralTurnReadRepository` |
| global system logs | `loadGlobalLogs()` (`:294-308`) | `LogFeedReadRepository` |
| per-general rank values | `loadRankValues()` (`:492-498`, `SELECT ... FROM rank_data ORDER BY general_id, id`, no LIMIT) merged into every `TurnGeneral.meta` at `:444` | `RankDataReadRepository` |
| aggregate statistic rows | `loadStatisticRows()` (`:244-262`) | — (small, likely genuinely bounded; flagged for audit, not reclassification, see §D) |

This is the single most important finding for the catalog: **the daemon already has a working cold read path for exactly this data** (game-api), and the boot-time eager copy is a second, unbounded, duplicated source of the same rows sitting in daemon heap. Reclassifying these four fields `QUERY_ONLY_COLD` and *deleting* the boot-eager copy is the natural feed into sibling ticket `ARCH-S5-T2` (`OPENSAM-138` / `#284`, "Remove full-history boot scans and prove bounded retention") — this ticket's job is to record the classification and prove nothing in the RNG-draw/entity-iteration path actually reads `state.meta["nationHistory"]` etc. today (grep confirms: `MonthlyPostUpdateHook`, `GeneralAI`, `ProcessWarNG`, `AiTurnAdapter` never reference these meta keys), so removal in S5-T2 is safe. **This ticket does not remove them** (see §D non-goals).

## B. Phase-boundary prefetch contract

Applies to every `PHASE_HOT` catalog entry.

1. **Trigger point.** Prefetch runs exactly once, synchronously, immediately before the phase body it feeds, at the existing phase-boundary call sites — not inside `logic` (which is intentionally pure/IO-free: `MonthlyPipeline.runMonth()` at `logic/src/main/kotlin/opensamguk/logic/tick/MonthlyPipeline.kt:95-125` invokes only injected `fun interface` hooks, zero IO). The engine-side call sites are:
   - Monthly phase: `TurnRunService.kt:288` (`pipeline.runMonth(...)`) — prefetch runs immediately before this call, in the same daemon-thread turn as the rest of the tick (no separate scheduler, no async).
   - Battle phase: already correctly shaped — `BattleCommandContextBuilder.build()` (`app/game-engine/src/main/kotlin/opensamguk/engine/war/BattleCommandContextBuilder.kt:25`) is itself the prefetch step, staging everything `processWarNG`'s RNG loop needs from `InMemoryTurnWorld` (all `ALWAYS_HOT`, so this is a memory-to-memory copy, not a DB read — no new work needed here beyond citing it in the catalog as the reference pattern).
2. **Bounded keyset/limit.** Every `PHASE_HOT` SQL query carries an explicit `LIMIT` and/or a keyset predicate (`WHERE id > :cursor ORDER BY id ASC LIMIT :n`, or a narrow predicate that is bounded by construction such as `finished = false` on an auction table with a small live-row count). No `PHASE_HOT` loader may be a full unqualified table scan. A prefetch loader that needs more than one page repeats the keyset query with an updated cursor — it never falls back to loading everything "to be safe."
3. **Stable order.** Loader SQL always carries an explicit `ORDER BY` on a monotonic key (id, or an insertion-order surrogate), and the loader materializes into a `LinkedHashMap`/`List` that preserves that order — never re-sorted downstream (repo rule 6: PHP sorts are stable, never add a non-stable secondary comparator). The resulting cache is read-only for the phase; nothing mutates its iteration order mid-phase.
4. **Zero lazy SQL inside the phase body.** Once prefetch completes, the phase body (RNG draw loop, entity iteration, battle loop) reads only `InMemoryTurnWorld` accessors and the just-populated phase-hot cache — never a repository, `JdbcTemplate`, or `EntityManager` call. This is the literal wording of the ticket's first GWT and is what §C's static test enforces.
5. **Cache lifetime.** A `PHASE_HOT` cache is scoped to the phase call (a plain local value threaded through the phase's hook functions, e.g. a parameter on `MonthlyPostUpdateHook.run(...)`/`registerAuction(...)`) — not a class field, not cross-phase state, so there is no staleness question between phases and no extra invalidation logic to get wrong.
6. **RNG/log parity untouched.** Prefetch is a pure read with no side effects and draws no RNG — moving a call from "ad hoc mid-phase" to "prefetched immediately before the phase" does not change draw order, log order, or flush order, so the existing golden gates keep proving parity unchanged. This must be verified per migrated call site (§E) with the existing parity gate, not asserted here.

## C. Architecture tests

The repo has no ArchUnit dependency; every existing "architecture test" is a plain JVM test that does a class-file constant-pool scan or a source-text scan against a single named-object contract (`DaemonWriteGuard` + `DaemonNoEntityManagerTest`/`InfraNoEntityManagerTest`; `AiProductionWiringGuardTest` for source-text assertions; `WorldScopedReadRepositoryArchitectureTest` for regex-based repository-shape checks). This design adds two tests in the same style plus one dynamic IT, and one new catalog object they both read from — mirroring `DaemonWriteGuard`'s "single source of truth read by the guard test" shape exactly.

**New catalog/guard object** — `logic/src/main/kotlin/opensamguk/logic/memory/HotColdCatalog.kt`:
- `alwaysHot: List<CatalogEntry>`, `phaseHot: List<PhaseHotEntry>`, `queryOnlyCold: List<CatalogEntry>` (§A tables, machine-readable).
- `pureExecutionPackages: List<String>` = `["opensamguk/logic/war", "opensamguk/logic/ai", "opensamguk/logic/tick"]` plus the engine loop-body classes that must stay lazy-SQL-free even though they live in the (JPA-carrying) `:app:game-engine` module: `opensamguk/engine/run/MonthlyPostUpdateHook`, `opensamguk/engine/turn/AiTurnAdapter`, `opensamguk/engine/war/BattleCommandContextBuilder`.
- `forbiddenInternalNames: List<String>` = the JDBC/JPA/repository type set: `jakarta/persistence/EntityManager`, `jakarta/persistence/EntityManagerFactory`, `org/springframework/jdbc/core/JdbcTemplate`, `org/springframework/jdbc/core/namedparam/NamedParameterJdbcTemplate`, `org/springframework/data/jpa/repository/JpaRepository`, `org/springframework/data/repository/CrudRepository`, plus every `*Repository` interface named in `phaseHot`/`queryOnlyCold` entries (so a *new* repository dependency shows up even before it's added to `forbiddenInternalNames` by name — the constant-pool scan matches on the interface's own internal name, read directly from the catalog's declared repository types, not hand-duplicated in the test).
- `phaseHotPrefetchAllowlist: List<String>` = the *only* classes permitted to reference the above types at all (the prefetch step itself, e.g. a new `PhaseHotPrefetchStep` engine class) — everything else in `pureExecutionPackages` must be clean, full stop; the existing `registerAuction()` direct call is the one pre-existing entry that either gets added here as a documented exception or (preferably, see §E) migrated to go through the prefetch step so the allowlist can start empty.

**Test 1 — `PureExecutionNoLazySqlTest`** (new, sibling of `DaemonNoEntityManagerTest`, same class-file constant-pool scan technique):
- Compiles the same way `DaemonNoEntityManagerTest` does (`build/classes/kotlin/main` under `logic` and `app/game-engine`).
- For every class under `HotColdCatalog.pureExecutionPackages` **not** in `phaseHotPrefetchAllowlist`, assert its constant pool contains none of `HotColdCatalog.forbiddenInternalNames`.
- This directly enforces GWT #1's "RNG draw 또는 entity iteration 내부에서 lazy SQL이 0회": the packages named are precisely the RNG-draw loop (`logic.war`), the AI selection loop (`logic.ai`, `engine.turn.AiTurnAdapter`), the monthly entity-iteration phase (`engine.run.MonthlyPostUpdateHook`), and the battle prefetch/staging class.
- This also directly enforces GWT #2 ("catalog에 없는 신규 접근이 추가될 때 architecture test가 실패") for the *loop-internal repository call* half: any new repository/JDBC dependency added to a pure-execution class fails immediately, by construction, without needing per-PR maintenance of a banned-symbol list — the banned set is derived from the catalog's own declared repository types.

**Test 2 — `PhaseHotQueryIsBoundedTest`** (new, source-text scan in the `AiProductionWiringGuardTest` style — reads `.kt` source, not compiled bytecode, because it inspects SQL string literals):
- For every `PhaseHotEntry` in `HotColdCatalog.phaseHot`, locate its declared loader source file + method (the catalog entry carries the file path and method name) and assert the method body's SQL text contains `LIMIT` or a keyset comparison (`> :cursor`, `> ?` paired with an `ORDER BY`) — i.e. lints the registered query text itself.
- Also asserts the method body contains an explicit `ORDER BY` clause (stable-order requirement, §B.3).
- This enforces GWT #2's *unbounded snapshot load* half: a new `PhaseHotEntry` registered with a full-scan query fails this test at the point it's added to the catalog, before it ever reaches production.

**Test 3 — `PhaseBoundaryQueryCountIT`** (new, Testcontainers integration test — dynamic complement to Tests 1–2, matches the ticket's own 검증 방법 "query-count test"):
- Wraps the test `DataSource` in a counting proxy (a thin `DataSource` decorator incrementing an `AtomicInteger` per `Connection.prepareStatement`/`createStatement`).
- Boots the daemon against two fixtures with identical hot cardinality but phase-hot-relevant row counts differing by 10× (reusing the `ARCH-S1-T1`/`ARCH-S5-T2` fixture convention: same hot generals/nations/cities, 10× the cold/phase-scoped table rows — e.g. 10× open auctions).
- Runs one representative Month phase tick against each fixture and asserts the query count delta between fixtures is bounded (a fixed small number of *pages*, not proportional to row count) — this is what a purely static scan cannot catch: a syntactically loop-free `.map { repo.find(it) }` N+1 pattern, or a keyset loop that never terminates its paging.
- This is the dynamic proof for GWT #1's "bounded keyset/limit" and complements Test 2's static SQL-text check with an end-to-end row-count-independent assertion.

All three tests read their inputs from `HotColdCatalog` only — never hand-maintain a duplicate banned-symbol or entry list — so the catalog stays the single source of truth exactly as `DaemonWriteGuard` already models for the write path.

## D. Acceptance criteria mapping and non-goals

### Mapping to GH #283 GWT

| GWT (from #283) | Design element |
|---|---|
| "always-hot, phase-hot, query-only cold 데이터가 명시" | §A three tables, backed by `HotColdCatalog` (§C) |
| "phase-hot 데이터는 bounded keyset/limit로 phase 시작 전에 stable insertion/history order로 prefetch" | §B contract (points 1–3), enforced statically by Test 2 |
| "RNG draw 또는 entity iteration 내부에서 lazy SQL이 0회" | §B point 4, enforced statically by Test 1, enforced dynamically by Test 3 |
| "catalog에 없는 신규 접근이 추가될 때 architecture test가 실패 (무제한 snapshot load 또는 loop 내부 repository 호출)" | Test 1 (loop-internal call — default-deny via constant-pool scan) + Test 2 (unbounded load — SQL-text LIMIT/keyset lint) |

### Non-goals (explicitly out of scope for this ticket)

- **Activation/cutover.** Per §0, enabling these tests as a merge-blocking gate in CI and cutting the one identified production call site (`registerAuction`'s `auctionRepository?.findByFinishedFalse()`) over to go through the new prefetch step is implementation work gated on `ARCH-S1-T3`/`#271` closing. This design and its minimal slice (§E) may be *built and tested on a branch*; it is not to be merged as an active gate until that dependency closes, per the plan doc's Wave table (`ARCH-S1-T3`, `ARCH-S4-T4` → W4).
- **`ARCH-S5-T2` (`OPENSAM-138`/`#284`) full work.** Actually deleting `WorldSnapshotLoader`'s unbounded `loadNationHistory`/`loadGeneralHistory`/`loadGlobalLogs`/`loadRankValues` boot scans and re-pointing any daemon consumer at game-api's existing cold repositories is that ticket's scope. This design only records the `QUERY_ONLY_COLD` reclassification (§A.3) and the grep evidence that nothing in the RNG-draw/entity-iteration path reads those `state.meta` keys today, so S5-T2 has a ready-made worklist.
- **`ARCH-S5-T3` (`OPENSAM-139`/`#285`) `committedWorldVersion`/`minVersion` primary-read barrier.** Unrelated read-consistency concern for API endpoints, not memory residency.
- **Auditing `statisticRows`/`activeUniqueAuctionItems`/`storedUniqueItemCounts`/`inheritancePoints` boundedness.** Flagged in §A.3 as "audit, not reclassify" — left as `ALWAYS_HOT` here since no evidence was found that they grow unboundedly with world age, but confirming that is not this ticket's job.
- **Any change to RNG draw order, rounding, or Korean log strings.** Prefetch is read-only and pre-phase; if a migration in §E ever appears to require a behavior change to hit these bullets, that is a bug in the migration, not a sanctioned outcome.

## E. Minimal implement slice order for Codex

Ordered so each step compiles and has a green test before the next starts (TDD red→green, one commit per task per `superpowers:subagent-driven-development`). All of this can be done now (parallel to `ARCH-S1-T3`); only the final "activate" step waits on that dependency per §0/§D.

1. **`HotColdCatalog.kt`** (new file, `logic/src/main/kotlin/opensamguk/logic/memory/HotColdCatalog.kt`). Data-only: the three category lists from §A, `pureExecutionPackages`, `forbiddenInternalNames`, `phaseHotPrefetchAllowlist` (start this non-empty, containing `MonthlyPostUpdateHook`'s `registerAuction`, so Test 1 is green on day one — tighten to empty in step 4). Test first: a small unit test asserting the catalog's own lists are non-empty and internally consistent (every `phaseHot` entry's declared file exists) — cheap, catches typos before the heavier tests depend on it.
2. **`PureExecutionNoLazySqlTest.kt`** (new, `logic/src/test/kotlin/opensamguk/logic/memory/`, mirrors `DaemonNoEntityManagerTest`'s class-file scan). Red against the current allowlist-inclusive catalog only if some other pure-execution class unexpectedly references a forbidden type (it shouldn't, per the Explore evidence in §A.1/A.2) — expected green immediately, proving the test harness itself works before step 4 removes the allowlist entry.
3. **`PhaseHotQueryIsBoundedTest.kt`** (new, `logic/src/test/kotlin/opensamguk/logic/memory/`, source-text scan per `AiProductionWiringGuardTest` style). Register `registerAuction`'s query (`finished = false`, already bounded by predicate — not a `LIMIT`, so the test's bound-check needs to accept "narrow equality/boolean predicate on an indexed column" as well as `LIMIT`/keyset, matching what actually exists) as the one `phaseHot` entry from step 1; green once the loader text matches.
4. **Migrate `registerAuction()`** (`app/game-engine/src/main/kotlin/opensamguk/engine/run/MonthlyPostUpdateHook.kt:374-393`): introduce a small `PhaseHotCache` parameter (a plain data holder, e.g. `data class MonthPhaseHotCache(val openAuctions: List<Auction>)`) populated once by a new `PhaseHotPrefetchStep` object called from `TurnRunService.kt` immediately before `pipeline.runMonth(...)` (§B point 1), and change `registerAuction()` to read `cache.openAuctions` instead of calling `auctionRepository?.findByFinishedFalse()` directly. Shrink `HotColdCatalog.phaseHotPrefetchAllowlist` to empty. `PureExecutionNoLazySqlTest` should now pass with an empty allowlist — this is the point the design's central claim ("zero lazy SQL inside the phase body") becomes literally true for existing code, not just new code. Verify with the existing golden/parity gate (`tools/parity/gate.sh backend`) that Month-phase RNG/log output is byte-identical before/after (per §B point 6) — this is the one step in the slice that touches behavior, so it needs the parity gate, not just the new unit tests.
5. **`PhaseBoundaryQueryCountIT.kt`** (new, `app/game-engine/src/test/kotlin/opensamguk/engine/memory/`, Testcontainers IT, counting `DataSource` proxy per §C Test 3). Build the two fixtures (baseline vs 10× open-auction rows), assert bounded query count across both. This is the slowest test to write (needs the fixture + Testcontainers wiring) — sequenced last since steps 1–4 already prove the static half of the contract; this closes the dynamic half.
6. **Do not enable any of steps 2/3/5 as a required CI gate yet** — land them as passing-but-not-yet-blocking (or simply do not wire a CI job reference) until `ARCH-S1-T3`/`#271` closes per §0/§D. Record the gate-activation follow-up as a checklist item on this ticket or a linked comment, not as new code.

Each step above touches at most one or two files, matching this repo's "foundation-first, one logical commit per task" convention — step 1 is the foundation every later step (and any future `PHASE_HOT` addition) only consumes.
