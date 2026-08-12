# OPENSAM-149 restart / rehydrate lossless gate

## Contract and boundaries

- The shared `.ai/task.md` still names OPENSAM-35. On 2026-08-11 the root orchestrator explicitly
  authorized this isolated OPENSAM-149 lane under the user's parallel-issue request. This ledger is
  the task-local contract and evidence record; no shared `.ai/*` file is changed.
- Branch/worktree: `codex/opensam-149-rehydrate-gate`, cleanly rebased onto current `origin/main`
  `53f5d5ebc14e283d1f0dec1758ccb4bf2eaf3497` before its lane-only commit so the future PR does not
  include unrelated merged work.
- The daemon write remains exactly `ChangeRecorder -> DatabaseHooks.toFlushPayload ->
  JdbcFlushExecutor`. This task must not introduce an `EntityManager` write, a second dirty source,
  or a `RehydrateService` call. `RehydrateService` is explicitly superseded and remains unwired.
- Golden fixtures are read-only and out of scope.

## Scope / exact affected-file inventory

Owned now:

| Path | Responsibility | Initial state |
| --- | --- | --- |
| `docs/loops/opensam-149-rehydrate-gate/LEDGER.md` | contract, channel matrix, loops and evidence | new |
| `app/game-engine/src/test/kotlin/opensamguk/engine/boot/RehydrateLosslessGateIT.kt` | real JDBC flush -> PostgreSQL -> loader and N/restart/N+1 gate | new |

Only if a red test proves the production seam needs it (exclusive persistence-spine ownership):

| Path | Potential responsibility |
| --- | --- |
| `app/game-engine/src/main/kotlin/opensamguk/engine/turn/ChangeRecorder.kt` | keep live recorder projection coherent with a flushed channel |
| `app/game-engine/src/main/kotlin/opensamguk/engine/flush/DatabaseHooks.kt` | map/refresh payload-side rehydrate projection without another write path |
| `app/game-engine/src/main/kotlin/opensamguk/engine/boot/WorldSnapshotLoader.kt` | scoped boot reconstruction only |
| `infra/src/main/kotlin/opensamguk/infra/persistence/JdbcFlushExecutor.kt` | JDBC persistence only |
| `logic/src/main/kotlin/opensamguk/logic/memory/HotColdCatalog.kt` | update the declared hot/cold contract after proof |

No other production file is authorized in this wave. In particular, no `RehydrateService`, daemon
configuration, golden, migration, or shared `.ai` edit is planned.

## Terms used by the matrix

- **H (HOT)**: must be reconstructed by `WorldSnapshotLoader` because the resident world or the
  next resolver turn reads it. Each applicable H cell needs a real JDBC flush -> DB -> loader proof.
- **C (COLD)**: durable but intentionally query-on-demand through a world-scoped repository. It is
  not copied into `WorldSnapshot`; the gate asserts its DB rows and the existing/read-path owner.
- **N/A**: that lifecycle verb is not a meaningful operation for the channel.
- **Q (quarantine)**: a boot-derived projection is read by a resident turn but is not demonstrably
  updated along the uninterrupted path. It is a lossless-gate blocker, not a permitted exemption.

`C` means `created/insert`, `D` means `dirty/update/upsert`, and `X` means
`deleted/invalidate/pull`. `world` rows below are lifecycle sets delegated to by `ChangeRecorder`;
they remain part of its effective flush contract even though they are not duplicate recorder lists.

## ChangeRecorder x lifecycle x rehydrate matrix

| Channel / source | C | D | X | Class | Durable mapping | Rehydrate / runtime reader | Gate cell |
| --- | ---: | ---: | ---: | --- | --- | --- | --- |
| `world.createdGenerals`, `generalPatches`, `deletedGeneralIds` | H | H | H | H | `general`, `general_turn`, `rank_data`, archive/owner/access cleanup | `loadGenerals`, `loadGeneralTurns`, `loadRankValues` | full N/restart/N+1 |
| `cityPatches` | N/A | H | N/A | H | `city` | `loadCities` | full gate |
| `world.createdNations`, `nationPatches`, `deletedNationIds` | H | H | H | H | `nation`, `nation_turn`, `ng_old_nations` | `loadNations`, archived-id projection | full gate |
| `world.createdTroops`, `world.dirtyTroops`, `world.deletedTroops` | H | H | H | H | `troop` | `loadTroops` | existing `RehydrateRoundTripIT`, full gate |
| `world.createdDiplomacy`, `diplomacyUpdateDirty`, nation-cascade delete | H | H | H | H | `diplomacy` | `loadDiplomacy` | full gate |
| `world.logs` (`DirtyState.logs`) | C | C | N/A | C | `log_entry` | ordered history/action reader, not `WorldSnapshot` | full gate: Korean UTF-8 hex equality |
| `world.createdNationTurns`, `reservedNationTurnPulls` | C | N/A | C | C | `nation_turn` | reserved-turn repository (not snapshot) | scoped DB/read assertion |
| `generalTurnSlotWrites`, `reservedGeneralTurnPulls` | H | H | H | H/C | `general_turn` ring | `loadGeneralTurns`; runtime `ReservedTurnRepository` | full gate |
| `rankPatches` | N/A | H | X via general delete | H | `rank_data` | `loadRankValues` | full gate |
| `accessLogUpserts`, `accessLogDeletes`, `generalOwnerDeletes` | H | H | H | H/C | `general_access_log`, `general_owner` | `loadAccessLogs`; owner is API/read-side C | full gate for access log; scoped DB for owner |
| `kvDirty` (`game_env`, `nation_env`) | H | H | H | H | `game_kv` / `nation_env` | `loadGameEnv`, `loadNationEnv` | payload/loader cell |
| `kvDirty` other namespaces | C | C | C | C | `game_kv` | named repository/consumer | scoped DB/read assertion |
| `eventInserts`, `eventDeletes` | C | N/A | C | Q | event store | no snapshot loader identified | quarantine until owner/read proof |
| `selectPoolMutations` | C | C | C | C | selection-pool tables | dedicated repository | scoped DB/read assertion |
| `createdMessages`, `messageInvalidates` | C | C | N/A | C | `message` | `MessageRepository` on demand | DB/read assertion |
| `diplomacyLetterInserts`, `diplomacyLetterUpdates` | C | C | N/A | C | `diplomacy_letter` | letter repository on demand | DB/read assertion |
| `auctionUpserts` | C | C | N/A | **Q** | `ng_auction` | `AuctionRepository`; loader derives `activeUniqueAuctionItems` | first focused RED / full gate |
| `auctionBidInserts` | C | N/A | N/A | C | `ng_auction_bid` | `AuctionBidRepository` on demand | DB/read assertion |
| `bettingInserts` | C | N/A | N/A | C | `ng_betting` | betting repository on demand | DB/read assertion |
| `profileIconUpdates` | N/A | H | N/A | H | `general.picture`, `general.image_server` | `loadGenerals` meta fields | payload/loader cell |
| `boardPostInserts`, `boardCommentInserts` | C | N/A | N/A | C | `board_post`, `board_comment` | board repositories | DB/read assertion |
| `votePollInserts`, `votePollUpdates`, `voteInserts`, `voteCommentInserts` | C | C | N/A | C | `vote_poll`, `vote`, `vote_comment` | vote repositories | DB/read assertion |
| `inheritanceKvWrites`, `inheritanceLogInserts`, `inheritanceResultInserts` | C | C | N/A | C/Q | `game_kv`, `inheritance_log`, `inheritance_result` | loader's active-owner projection plus recorder local base | quarantine only if an N+1 action reads stale world meta |
| `statisticInserts`, `yearbookInserts` | C | C | N/A | C | `statistic`, `yearbook_history` | history/report reader | DB/read assertion |
| `gameWinnerUpdates`, `emperiorInserts`, `hallUpserts` | C | C | N/A | C/Q | game/hall tables | active-game / hall reader | quarantine if resident world consults a stale projection |
| `oldGeneralSnapshots`, `nationSnapshots`, `nationArchiveSnapshots` | C | N/A | C | C/H-id | `ng_old_generals`, `ng_old_nations` | archive reader; loader only loads archived nation ids | scoped DB/read assertion |
| `pendingVoteKeys`, `inheritancePointBase`, ID allocators, generation session | N/A | N/A | N/A | N/A | in-process dedupe/allocator only | recreated from DB max/base or per-tick state | construction assertions, not snapshot rows |

### Matrix conclusions before implementation

1. The existing troop C/D/X cell is already genuinely round-tripped by
   `RehydrateRoundTripIT`; it is not the remaining OPENSAM-149 defect.
2. The published gap is a *full turn* equivalence gate, not a justification to wire the superseded
   `RehydrateService`.
3. `activeUniqueAuctionItems` is the first Q cell: the loader rebuilds it from `ng_auction`, but
   `ReservedTurnHandler.occupiedUniqueCounts` reads the resident `world.state.meta` projection.
   A unique-auction upsert can therefore make restart and uninterrupted N+1 disagree unless the
   live projection advances with the committed recorder payload. That occupancy map controls the
   weighted unique-item selection after RNG draws. The first test below turns the restart half of
   that inference into a reproducible red/green decision; the full gate exercises the behavioral
   consequence across the committed restart boundary.
4. `HotColdCatalog` currently calls both unique-item loader methods `QUERY_ONLY_COLD`, although
   `ReservedTurnHandler.occupiedUniqueCounts` consumes both projections from resident state. The
   active-auction side has a recorder producer; a source scan found no current `ut_` recorder
   producer for the stored-count side. Do not change the catalog classification until the focused
   integration test supplies the red/green evidence for the active-auction side.
5. A list-only resident projection cannot correctly apply an UPDATE for two active auctions with
   the same target. If RED confirms the gap, the smallest lossless seam is: loader retains an
   insertion-ordered `auctionId -> target` companion projection from its existing ordered query,
   `DatabaseHooks` applies the full `AuctionInfo.toArray()` upsert by id before building the payload,
   removes an id when its full row is no longer active/unique, canonically orders the resulting
   `LinkedHashMap` by the loader's `ng_auction.id ASC` contract, and derives the existing list from
   that map's values. `TurnWorldState.meta` is intentionally a read-only `Map`, so the payload
   builder must use the existing copy-on-write `InMemoryTurnWorld.setGameEnvValue` seam for these
   two derived keys; it must never cast/mutate the map in place. The list remains the only reader
   contract for `ReservedTurnHandler`; the id map is ephemeral reconstruction metadata, not a
   second write path. This is a design candidate only until the Testcontainers RED is observed.
6. `HotColdWorldCatalogGuardTest` inventories every private `load*` loader helper. A later fix must
   either alter the existing `loadActiveUniqueAuctionItems` helper and change its catalog entry to
   `ALWAYS_HOT`, or update the catalog in the same change; adding an uncataloged loader query is not
   permitted. The active-auction test needs no schema migration because V32 already makes
   `(world_id, id)` the `ng_auction` primary key.
7. A final `DatabaseHooks` source-to-payload inventory covered every direct recorder accessor and
   every `DirtyState` field. It surfaced `reservedNationTurnPulls` as the one initially omitted
   matrix name; it is now recorded with the cold `nation_turn` row. No unclassified recorder channel
   remains in this matrix.

## Executable test design

### Focused red: unique-auction projection

`RehydrateLosslessGateIT#flushed_unique_auction_keeps_the_resident_projection_equal_to_the_rehydrated_projection`

1. Flyway a real PostgreSQL Testcontainer and seed world `401`, a host general, and no auction.
2. Build the real `WorldSnapshotLoader` snapshot and the resident `InMemoryTurnWorld` from it.
3. Record one fixed-time Korean unique-auction `AuctionInfo` through `ChangeRecorder`; create the
   real payload with `DatabaseHooks.toFlushPayload`; flush with `JdbcFlushExecutor`.
4. Assert the durable `ng_auction` create row, then load a new snapshot from the same database.
5. Assert byte-for-value equality of `activeUniqueAuctionItems` between resident and rehydrated
   state; then record the same auction finalized (`finished=true`), flush it, and assert the same
   equality after the durable UPDATE. The unmodified code is expected to be RED at the create
   assertion: only the loader computes the list.

`RehydrateLosslessGateIT#same_local_auction_id_remains_isolated_through_flush_and_reload` uses two
worlds (`402` and `403`) with the same `general.id` and the same `ng_auction.id`, distinct Korean
targets, the real payload builder and executor, then both loaders. The focused-red world is `401`,
so JUnit method order cannot couple the cells. It asserts each boot projection excludes the other
world's target. This is an independently runnable scoped-writer/loader cell; it does not depend on
the first test's expected resident-projection RED.

The production fix, if RED confirms this, belongs in the approved persistence spine and must make
the resident projection follow the same id-keyed insertion/update/finalization semantics as the
durable auction rows; it must not add a direct database write or invoke `RehydrateService`.

### Explicit non-goal / follow-up boundary: same due-tick intake

`TurnRunService.runTick` can dispatch `AuctionOpenUnique` before it calls
`TurnDaemonLifecycle.runTick`, while the approved `DatabaseHooks` projection refresh occurs only
when that method subsequently builds the one flush payload. The normal runner generally drains such
intake through `runIntakeCommands` and commits it before a due tick, but a command arriving at the
due boundary can use the combined path. Fixing visibility *within that same tick* requires
`ReservedTurnHandler`/`TurnRunService` wiring (or a world-aware `ChangeRecorder` callback), which is
outside this task's explicitly allowed production inventory. OPENSAM-149 therefore gates the stated
committed N -> discard/reload -> N+1 lossless boundary only. Do not silently broaden this wave; the
same-tick case is a separately owned follow-up unless the orchestrator grants new ownership.

### Full lossless N -> restart -> N+1 gate

`RehydrateLosslessGateIT#restart_between_identical_reserved_turns_matches_the_uninterrupted_world`

1. Seed two independent worlds with equal local ids, fixed `hiddenSeed`, clock, hot entities,
   general-turn ring, Korean entity names and reserved commands. Seed a third same-local-id poison
   world with distinguishable Korean values.
2. Use real `ReservedTurnRepository`, `TurnDaemonLifecycle`, `ReservedTurnHandler`,
   `TurnRunService`, `RedisCommandStream`, `RealtimePublisher`, `DatabaseHooks` and
   `JdbcFlushExecutor` with the existing PostgreSQL + Redis Testcontainers harness. `TurnRunService`
   is required rather than a direct lifecycle call: it constructs the post-tick payload, performs the
   one JDBC flush, then advances the resident clock only after that commit. Run N in both worlds once;
   assert one `world_state` executor operation per `runTick`.
3. Keep world A resident and run N+1. For world B, discard all Java/Kotlin turn objects, reconstruct
   a fresh world solely with `WorldSnapshotLoader`, recreate the handler/lifecycle/repositories, then
   run N+1 at the identical fixed instant.
4. Compare canonical hot-state signatures (world state, general/city/nation/troop/diplomacy/access
   log/rank), RNG-consuming action result, and ordered `log_entry` values with the Korean text
   represented as `encode(convert_to(text, 'UTF8'), 'hex')`. Compare the general-turn ring as a
   separate ordered repository query: the resident `TurnGeneral.initialTurns` is a boot snapshot and
   is intentionally not mutated when the runtime `ReservedTurnRepository` pulls a slot.
5. Assert world A/B never load or flush the poison world's same local ids. This turns ordinary
   `world_id` predicates into an observed two-world isolation proof.

## Evidence / baseline

- Fablize has repeatedly emitted generic "tool failure" notices around successful read-only shell
  calls (including source reads whose commands returned exit 0). They are recorded as external
  harness telemetry only. No test outcome will be inferred from them; final claims require output
  tails and fresh JUnit XML.
- Static review confirms the turn clock has an existing durable pair: `DatabaseHooks` carries
  `last_turn_time`, `JdbcFlushExecutor` merges it as `meta.lastTurnTime`, and
  `WorldSnapshotLoader` prefers that value. It is therefore not a production-fix candidate unless a
  later runtime RED disproves the source reading.
- Static schema review confirms worlds `402` and `403` may share local `general.id` and
  `ng_auction.id`: V32 makes both identifiers composite with `world_id`. The focused scope cell
  therefore exercises the real isolation shape without a migration.
- Static producer review confirms every current `recordAuctionUpsert` call supplies the full
  `AuctionInfo.toArray()` row shape (including `type`, `finished`, and `target`), so an id-keyed
  payload-side projection can deterministically replace or remove an active unique auction without
  querying the database or creating another dirty source.
- Static state review confirms `InMemoryTurnWorld.setGameEnvValue` copies `TurnWorldState.meta`
  atomically into a new state object. It is the only already-owned call-site-safe way for the
  approved `DatabaseHooks` seam to refresh a derived resident projection without editing the
  world model or mutating a read-only map.
- One static source-discovery command initially used a stale `engine/run/TurnDaemonLifecycle.kt`
  path (the real path is `engine/turn/TurnDaemonLifecycle.kt`), producing an `rg` not-found exit and
  a generic Fablize warning. It was immediately isolated as a read-only command typo and rerun via
  `rg --files`; no source, database, container, or test state changed. This is documented baseline
  telemetry, not RED/GREEN evidence.
- A read-only whitespace-check wrapper also used Bash's `PIPESTATUS` syntax under zsh and emitted a
  zsh condition error. It did not run an edit, build, database, or container action; a zsh-compatible
  check is used for the follow-up. This is likewise command-wrapper telemetry, not test evidence.
- An earlier isolated focused Gradle invocation reached active Kotlin test compilation but produced
  no test-result XML before the root orchestrator reserved the shared JVM build slot for OP9. Its
  wrapper and daemon were terminated gracefully on that coordination request. This is neither RED
  nor GREEN.
- Actual focused RED on 2026-08-12, after a later explicit JVM-slot release:
  `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:test --tests
  'opensamguk.engine.boot.RehydrateLosslessGateIT' --rerun-tasks`. Fresh XML
  `TEST-opensamguk.engine.boot.RehydrateLosslessGateIT.xml` reports `tests=2`, `skipped=0`,
  `failures=1`, `errors=0`. The focus cell failed at test line 152: rehydrated
  `[che_명마_07_백마]` versus resident `[]`; its independent same-local-id isolation cell passed.
  This is the required evidence that the persistent row exists and loader is correct while the
  uninterrupted resident projection is stale.
- A first combined source patch was rejected because its `DatabaseHooks` context anchor did not
  match; it made no source change. A later comment check rejected a new nonessential loader comment,
  which was removed. Both are resolved edit-tool telemetry, not test evidence.
- One static-check wrapper used zsh's special `path` and read-only `status` identifiers, so it
  stopped after the initial tracked-file whitespace check. It made no edit; the follow-up uses
  explicit non-reserved names and is the authoritative static-check result.
- The first post-repair focused rerun stopped at Kotlin compilation, before executing tests, because
  the new `auctionUpserts` declaration was placed in the deprecated three-argument payload overload.
  The live `ChangeRecorder`-aware overload is the only one with `world` and `recorder`; the
  declaration and refresh call were moved there, and a line-scoped source check confirms exactly
  one declaration/call pair in that overload. This is a fixed compile-surface defect, not a test
  outcome; the next fresh focused XML remains required.
- Focused GREEN on 2026-08-12 after the placement repair: the same `--rerun-tasks` command completed
  `BUILD SUCCESSFUL in 1m 25s`. Fresh
  `TEST-opensamguk.engine.boot.RehydrateLosslessGateIT.xml` reports `tests=2`, `skipped=0`,
  `failures=0`, `errors=0`; both the real `ChangeRecorder -> DatabaseHooks -> JdbcFlushExecutor ->
  PostgreSQL -> WorldSnapshotLoader` create/finalize projection cell and the two-world same-local-id
  isolation cell passed. `git diff --check` was clean immediately afterward.
- Pre-review strict check on 2026-08-12 was intentionally RED only for missing evidence:
  `tools/agent-system/check.py --strict --base origin/main --format json` reported no code defect but
  required a `docs/superpowers/reviews/*.md` cross-agent critique artifact, which is also the
  project-recognized docs/parity mapping for this logic change. The first independent review cleared
  the source, but a second exact-commit review and strict rerun remain required after the clean-base
  commit. Do not interpret this pre-review result as a passing strict gate.
- Environment preflight on 2026-08-12: `docker info --format '{{.ServerVersion}} {{.OperatingSystem}}'`
  returned `29.3.1 Docker Desktop`, so Docker availability is not the expected reason for a skipped
  focused Testcontainers gate. The eventual XML must still prove `skipped=0`.
- Testcontainers must run with `skipped=0` for this task's focused/full integration claims. Docker
  unavailability is a documented blocker, not a passing gate.
- Required final checks: focused engine XML, full `:app:game-engine:test`, architecture tests, and
  the backend parity gate; an independent review is a parent/orchestrator handoff requirement.

## Loop log

| Round | Baseline | Hypothesis | Measurement | Decision |
| --- | --- | --- | --- | --- |
| 1 | Loader derives unique auction targets only at boot; resident unique-lottery code reads the projection at N+1. | A recorder flush leaves resident and restarted eligibility state divergent. | Fresh Testcontainers XML: 2 tests, skipped=0, failures=1; unique create rehydrates `[che_명마_07_백마]` while resident remains `[]`; isolation passed. | RED confirmed; apply only loader + payload projection + catalog repair. |
| 2 | The repaired projection must be built only through the live ChangeRecorder-aware flush seam. | An id-keyed resident projection applied from full auction upserts makes uninterrupted and rehydrated state equal after create and finalization. | Fresh Testcontainers XML: 2 tests, skipped=0, failures=0, errors=0; terminal `BUILD SUCCESSFUL`. | Adopt bounded loader + `DatabaseHooks` + hot/cold catalog repair; request independent review and broader gates. |
