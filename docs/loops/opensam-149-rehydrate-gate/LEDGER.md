# OPENSAM-149 restart / rehydrate lossless gate

## Contract and boundaries

- The shared `.ai/task.md` now names OPENSAM-43, not this parallel OPENSAM-149 lane. On 2026-08-11 the root orchestrator explicitly
  authorized this isolated OPENSAM-149 lane under the user's parallel-issue request. This ledger is
  the task-local contract and evidence record; no shared `.ai/*` file is changed.
- Branch/worktree: `codex/opensam149-full-rehydrate` in
  `/private/tmp/opensam-op149-full-rehydrate`, created from the then-current `origin/main`
  `580bcac33c15fa7900061db14e0f1a3a0fccf67c` (which contains the earlier unique-auction repair).
  Rebase onto the current `origin/main` is a final pre-PR step after the lane-only commit.
- The daemon write remains exactly `ChangeRecorder -> DatabaseHooks.toFlushPayload ->
  JdbcFlushExecutor`. This task must not introduce an `EntityManager` write, a second dirty source,
  or a `RehydrateService` call. `RehydrateService` is explicitly superseded and remains unwired.
- Golden fixtures are read-only and out of scope.

## Scope / exact affected-file inventory

Owned now:

| Path | Responsibility | Initial state |
| --- | --- | --- |
| `docs/loops/opensam-149-rehydrate-gate/LEDGER.md` | contract, all-channel matrix, loops and evidence | update |
| `app/game-engine/src/test/kotlin/opensamguk/engine/boot/FullRehydrateTurnGateIT.kt` | real PostgreSQL N -> discard/reload -> N+1 equivalence gate | new |
| `app/game-engine/src/main/kotlin/opensamguk/engine/boot/WorldSnapshotLoader.kt` | reconstruct canonical clock metadata and retain typed clock precedence over cold `game_env` keys | update |

`RehydrateLosslessGateIT` and `RehydrateRoundTripIT` are inherited, passing focused coverage for
the earlier unique-auction projection and troop C/D/X repair. This lane does not weaken or edit them.

The remaining approved persistence-spine paths are potential only if a new RED proves they are needed:

| Path | Potential responsibility |
| --- | --- |
| `app/game-engine/src/main/kotlin/opensamguk/engine/turn/ChangeRecorder.kt` | keep live recorder projection coherent with a flushed channel |
| `app/game-engine/src/main/kotlin/opensamguk/engine/flush/DatabaseHooks.kt` | map/refresh payload-side rehydrate projection without another write path |
| `infra/src/main/kotlin/opensamguk/infra/persistence/JdbcFlushExecutor.kt` | JDBC persistence only |
| `logic/src/main/kotlin/opensamguk/logic/memory/HotColdCatalog.kt` | update the declared hot/cold contract after proof |

No other production file is authorized in this wave. In particular, no `RehydrateService`, daemon
configuration, golden, migration, or shared `.ai` edit is planned.

## Terms used by the matrix

- **H (HOT)**: a boot-resident domain projection whose next resolver turn can read it. Its proof
  target is a real JDBC flush -> discard -> boot reader round trip.
- **C (COLD)**: durable but intentionally query-on-demand through a world-scoped repository. It is
  not copied into `WorldSnapshot`; the gate asserts its DB rows and the existing/read-path owner.
- **N/A**: that lifecycle verb is not a meaningful operation for the channel.
- **Q (quarantine)**: the reader/projection is known but this lane has no executable lifecycle
  round trip. Q is never silently counted as gate evidence; its owner and follow-up are named in
  the authoritative closure matrix below.

`C` means `created/insert`, `D` means `dirty/update/upsert`, and `X` means
`deleted/invalidate/pull`. The cells under those three verb columns carry the channel class
(`H`, `C`, `Q`, or `N/A`), not an additional operation claim. `world` rows below are lifecycle
sets delegated to by `ChangeRecorder`; they remain part of its effective flush contract even though
they are not duplicate recorder lists.

## ChangeRecorder channel inventory

This inventory is the complete producer-to-reader map. It is not a claim that every row is exercised
by `FullRehydrateTurnGateIT`; the authoritative lifecycle proof status is the closure matrix below.

| Channel / source | C | D | X | Class | Durable mapping | Rehydrate / runtime reader | Routing only |
| --- | ---: | ---: | ---: | --- | --- | --- | --- |
| `worldStateUpdate` (clock, phase, config, fence/version) | N/A | H | N/A | H | `world_state` | `loadWorldState`; `TurnRunService.nextRunTime` | closure row 1 |
| resident general/nation high-water allocators | N/A | H | N/A | H | `world_state.max_general_id`, `max_nation_id` and meta mirrors | `loadWorldState` -> `InMemoryTurnWorld.maxGeneralId/maxNationId` | closure row 2 |
| `world.createdGenerals`, `generalPatches`, `deletedGeneralIds` | H | H | H | H | `general`, `general_turn`, `rank_data`, owner/access cleanup | `loadGenerals`, `loadGeneralTurns`, `loadRankValues` | closure rows 3–5 |
| `cityPatches` | N/A | H | N/A | H | `city` | `loadCities` | closure row 6 |
| `world.createdNations`, `nationPatches`, `deletedNationIds` | H | H | H | H | live `nation`, `nation_turn`, diplomacy deletes | `loadNations` | closure rows 7–9 |
| `world.deletedNationSnapshots` + `nationArchiveSnapshots` | H | H | N/A | H | `ng_old_nations` UPSERT | `loadArchivedNationIds` -> `maxNationId`; `ChangeRecorder.nationSnapshots()` is an unused mirror | closure row 10 |
| `world.createdTroops`, `world.dirtyTroops`, `world.deletedTroops` | H | H | H | H | `troop` | `loadTroops` | closure row 11 |
| `world.createdDiplomacy`, `diplomacyUpdateDirty`, nation-cascade delete | H | H | H | H | `diplomacy` | `loadDiplomacy` | closure row 12 |
| `world.logs` (`DirtyState.logs`) | C | N/A | N/A | C | `log_entry` | ordered history/action reader, not `WorldSnapshot` | closure row 13 |
| `world.createdNationTurns`, `reservedNationTurnPulls` | C | N/A | C | C | `nation_turn` | reserved-turn repository (not snapshot) | closure row 14 |
| `reservedGeneralTurnPulls` | N/A | N/A | H | H | `general_turn` ring | `loadGeneralTurns`; runtime `ReservedTurnRepository` | closure row 15 |
| `generalTurnSlotWrites`, general-delete cleanup | H | H | H | H | `general_turn` ring | `loadGeneralTurns` | closure row 16 |
| `rankPatches` | N/A | H | H | H | `rank_data` | `loadRankValues` | closure row 17 |
| `accessLogUpserts`, `accessLogDeletes` | H | H | H | H | `general_access_log` | `loadAccessLogs` | closure row 18 |
| `generalOwnerDeletes` | N/A | N/A | C | C | `general_owner` | API/read-side owner repository | closure row 19 |
| `kvDirty` (`game_env`, `nation_env`) | H | H | H | H | `game_kv` / `nation_env` | `loadGameEnv`, `loadNationEnv` | closure row 20 |
| `kvDirty` other namespaces | C | C | C | C | `game_kv` | named repository/consumer | closure row 21 |
| `eventInserts`, `eventDeletes` | H | N/A | H | H | `event` | `EngineEventConfig.createEventStore` -> `EventStore`; bound at `DaemonLoopConfig` | closure row 22 |
| `selectPoolMutations` (mixed `REFRESH`/`PICK`/`UPDATE` lifecycle) | C | C | C | C | selection-pool tables | dedicated selection-pool repository | closure row 23 |
| `createdMessages`, `messageInvalidates` | C | C | N/A | C | `message` | `MessageRepository` on demand | closure row 24 |
| `diplomacyLetterInserts`, `diplomacyLetterUpdates` | Q | Q | N/A | Q | `diplomacy_letter` | letter repository on demand; allocator is not boot-seeded | closure row 25 |
| `auctionUpserts` | H | H | N/A | H | `ng_auction` | loader-derived `activeUniqueAuctionItems`, refreshed by `DatabaseHooks` | closure row 26 |
| `auctionBidInserts` | C | N/A | N/A | C | `ng_auction_bid` | `AuctionBidRepository` on demand | closure row 27 |
| `bettingInserts` (UPSERT) | C | C | N/A | C | `ng_betting` UPSERT | betting repository on demand | closure row 28 |
| `profileIconUpdates` | N/A | H | N/A | H | `general.picture`, `general.image_server` | `loadGenerals` meta fields | closure row 29 |
| `boardPostInserts`, `boardCommentInserts` | C | N/A | N/A | C | `board_post`, `board_comment` | board repositories | closure row 30 |
| `votePollInserts`, `votePollUpdates`, `voteInserts`, `voteCommentInserts` | C | C | N/A | C | `vote_poll`, `vote`, `vote_comment` | vote repositories | closure row 31 |
| `inheritanceKvWrites` | H | H | N/A | H | `game_kv` | boot/runtime inheritance point projection | closure row 32 |
| `inheritanceLogInserts`, `inheritanceResultInserts` | C | N/A | N/A | C | `inheritance_log`, `inheritance_result` INSERT | history/result reader | closure row 33 |
| `statisticInserts`, `yearbookInserts` | C | N/A | N/A | C | `statistic`, `yearbook_history` INSERT | history/report reader | closure row 34 |
| `gameWinnerUpdates` | N/A | C | N/A | C | `ng_games.winner_nation` UPDATE | no current production reader; `resolveActiveGame` does not select `winner_nation` | closure row 35 |
| `emperiorInserts`, `hallUpserts` | C | C | N/A | C | emperor INSERT / hall UPSERT | history/hall reader | closure row 36 |
| `oldGeneralSnapshots` | C | C | N/A | C | `ng_old_generals` UPSERT | archive reader | closure row 37 |
| `commandResults` (executor-derived after intake/reserved execution) | C | N/A | N/A | C | `command_result`, `command_outbox` | result poller / outbox relay, not `WorldSnapshot` | closure row 38 |
| `command_inbox` claim/status lifecycle | C | C | N/A | C | `command_inbox` | durable intake repository, not `WorldSnapshot` | closure row 39 |
| Redis command wake / `turnCompleted` publication | N/A | N/A | N/A | N/A | Redis-only ephemeral transport | rebuilt subscribers; never boot snapshot state | closure row 40 |
| `pendingVoteKeys`, `inheritancePointBase`, generation session | N/A | N/A | N/A | N/A | in-process dedupe/generation only | reconstructed per process/tick; no durable row representation | closure row 41 |
| message / auction ID allocators | N/A | N/A | N/A | N/A | `message`, `ng_auction` ids | `DaemonLoopConfig` seeds each from durable `findMaxId()` | closure row 42 |
| diplomacy-letter ID allocator | Q | N/A | Q | Q | `diplomacy_letter (world_id, id)` | `ChangeRecorder` default starts at 1; no daemon boot seed exists | closure row 43 |

### Authoritative lifecycle closure matrix

`P` means an executable proof named in the cell. `Q` means explicitly **not** proven by this
full-turn gate; it is owned by the persistence-spine follow-up, not treated as a green result. This
table deliberately separates each HOT/COLD lifecycle instead of implying C/D/X parity from a single
baseline reload. The new full-turn test does **not** exercise auction mutation; auction evidence is
only the inherited focused test named below.

| # | Channel × lifecycle | Class | Reader / seam | Executable evidence or Q owner |
| ---: | --- | --- | --- | --- |
| 1 | `worldStateUpdate` D (clock/fence) | H | `WorldSnapshotLoader.loadWorldState` | **P** `FullRehydrateTurnGateIT`: N, restart, and second reload assert clock/version equality. |
| 2 | `maxGeneralId` / `maxNationId` D | H | `loadWorldState` + `InMemoryTurnWorld` allocator bootstrap | **Q** Persistence-spine follow-up: loader reconstructs the high-water values from world state, live rows, and archived nation ids, but this full gate does not allocate a general or nation across restart. |
| 3 | general C | H | `loadGenerals` | **Q** Persistence-spine follow-up: no create-turn/restart action in this gate. |
| 4 | general D | H | `loadGenerals` | **P** full gate asserts executor `general` operation and resident-to-second-reload equality. |
| 5 | general X | H | `loadGenerals` | **Q** Persistence-spine follow-up: delete/reload turn absent. |
| 6 | city D | H | `loadCities` | **P** full gate's `che_농지개간` asserts `city` JDBC operation and second reload equality. |
| 7 | nation C | H | `loadNations` | **Q** Persistence-spine follow-up: create/reload turn absent. |
| 8 | nation D | H | `loadNations` | **P** full gate's `che_기술연구` asserts `nation` JDBC operation and second reload equality. |
| 9 | nation X | H | `loadNations` | **Q** Persistence-spine follow-up: live nation cascade delete/reload turn absent. |
| 10 | `ng_old_nations` archive C/D + archived-id high-water | H | `loadArchivedNationIds` -> `InMemoryTurnWorld.maxNationId` | **Q** Persistence-spine follow-up: `world.deletedNationSnapshots` and `nationArchiveSnapshots` flush through the archive UPSERT; `ChangeRecorder.nationSnapshots()` is only an unused mirror. No archive-write/restart/allocation coverage is claimed. |
| 11 | troop C/D/X | H | `loadTroops` | **P** inherited `RehydrateRoundTripIT` C/D/X round trip; full gate only proves baseline reload isolation. |
| 12 | diplomacy C/D/X | H | `loadDiplomacy` | **Q** Persistence-spine follow-up: baseline state is reloaded but not mutated by this turn. |
| 13 | `log_entry` insert | C | ordered history/action query | **P** full gate requires nonempty Korean UTF-8 hex rows and equality. |
| 14 | `nation_turn` C/X | C | `ReservedTurnRepository` | **Q** Persistence-spine follow-up: no nation command is scheduled here. |
| 15 | `general_turn` pull / next-slot queue | H | `loadGeneralTurns`, `ReservedTurnRepository` | **P** full gate asserts `general_turn_pull`, queued request id, ordered ring, and N+1 resolution. |
| 16 | `general_turn` C/D/X slot lifecycle | H | `loadGeneralTurns` | **Q** Persistence-spine follow-up: creation, rewrite, and general-delete paths are not inferred from pull proof. |
| 17 | `rank_data` D/X | H | `loadRankValues` | **Q** Persistence-spine follow-up: baseline reload comparison is not a rank mutation round trip. |
| 18 | `general_access_log` C/D/X | H | `loadAccessLogs` | **Q** Persistence-spine follow-up: baseline reload comparison is not an access mutation round trip. |
| 19 | `general_owner` X | C | API/read-side owner repository | **Q** Persistence-spine follow-up: no owner delete in this daemon gate. |
| 20 | hot `game_kv` / `nation_env` C/D/X | H | `loadGameEnv`, `loadNationEnv` | **Q** Persistence-spine follow-up: no KV-mutating reserved action is used. |
| 21 | other `game_kv` C/D/X | C | named repository | **Q** Persistence-spine follow-up: cold read contract needs its own repository test. |
| 22 | `event` insert/delete | H | `EngineEventConfig.createEventStore` -> `EventStore`; `DaemonLoopConfig` binds `recordEventMutation` | **Q** Persistence-spine/event follow-up: event boot is a separate EventStore seam, not `WorldSnapshotLoader`; no mutation/reboot dispatch test yet. |
| 23 | selection-pool mixed C/D/X | C | dedicated selection-pool repository | **Q** Persistence-spine follow-up: `REFRESH`/`PICK`/`UPDATE` spans delete+insert/update and needs an explicit repository lifecycle test. |
| 24 | message C/D | C | `MessageRepository` | **Q** Persistence-spine follow-up. |
| 25 | diplomacy-letter C/D + allocated ID | Q | `ChangeRecorder.recordDiplomacyLetterInsert` -> `diplomacy_letter (world_id,id)` | **Q** Persistence-spine follow-up: `DaemonLoopConfig` does not seed `diplomacyLetterIdAllocator`; a restart at existing id=1 can collide with the V32 composite PK. This full gate does not schedule a diplomacy letter. |
| 26 | unique-auction C/D | H | `loadActiveUniqueAuctionItems`; `DatabaseHooks` resident refresh | **P** inherited `RehydrateLosslessGateIT` create/finalize plus same-local-id reload isolation; not claimed by full gate. |
| 27 | auction-bid C | C | `AuctionBidRepository` | **Q** Persistence-spine follow-up. |
| 28 | betting C/D UPSERT | C | betting repository | **Q** Persistence-spine follow-up: `ng_betting` uses conflict-update amount accumulation. |
| 29 | profile-icon D | H | `loadGenerals` fields | **Q** Persistence-spine follow-up. |
| 30 | board post/comment C | C | board repositories | **Q** Persistence-spine follow-up. |
| 31 | vote C/D | C | vote repositories | **Q** Persistence-spine follow-up. |
| 32 | inheritance KV C/D | H | boot/runtime inheritance-point projection | **Q** Persistence-spine follow-up: must mutate and reconstruct the consuming daemon projection. |
| 33 | inheritance log/result C | C | history/result reader | **Q** Persistence-spine follow-up: INSERT-only. |
| 34 | statistic/yearbook C | C | history/report reader | **Q** Persistence-spine follow-up: INSERT-only. |
| 35 | game-winner D | C | no current production reader; `WorldSnapshotLoader.resolveActiveGame` excludes `winner_nation` | **Q** Persistence-spine follow-up: classify as HOT only when a concrete boot/runtime consumer is introduced and then round-trip it. |
| 36 | emperor C / hall C/D | C | history/hall reader | **Q** Persistence-spine follow-up. |
| 37 | old-general archive C/D | C | archive reader | **Q** Persistence-spine follow-up: `ng_old_generals` UPSERT, not a delete lifecycle. |
| 38 | `command_result` / `command_outbox` insert | C | result poller/outbox relay | **P** full gate asserts real executor operations, normalized payload equality, and exact request ids. |
| 39 | `command_inbox` C/D | C | durable intake repository | **Q** Persistence-spine follow-up: reserved execution proves its terminal result, not inbox replay. |
| 40 | Redis wake / `turnCompleted` | N/A | ephemeral transport | **N/A** reconstructed subscribers do not belong in a DB snapshot; durable result/outbox proof is row 38. |
| 41 | pending keys, inheritance-point base, generation session | N/A | per-process reconstruction | **N/A** no durable state representation. |
| 42 | message / auction ID allocators | N/A | `DaemonLoopConfig` | **N/A** each is explicitly seeded from its world-scoped durable max id before `ChangeRecorder` construction. |
| 43 | diplomacy-letter ID allocator | Q | `ChangeRecorder` default allocator | **Q** Persistence-spine follow-up: inject a world-scoped durable max-id seed and add restart collision coverage. It is a known restart-safety gap, not an N/A allocator. |

The remaining OPENSAM-149 deliverable is therefore the bounded real turn path in rows 1, 4, 6, 8,
13, 15, and 38, plus existing row 26/11 regression proof. Rows marked Q are inventory and follow-up
work, not evidence of a silent lossless claim. The published gap is a full-turn equivalence gate,
not a reason to wire the superseded `RehydrateService`.

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
same-tick case is a separately owned follow-up (`same-due-tick intake visibility`) unless the
orchestrator grants new ownership.

### Full lossless N -> discard/reload -> N+1 gate

`FullRehydrateTurnGateIT#restart_between_identical_reserved_turns_matches_the_uninterrupted_world`

1. Seed two independent worlds with equal local ids, fixed `hiddenSeed`, clock, hot entities,
   general-turn ring, Korean entity names and reserved commands. Seed a third same-local-id poison
   world with distinguishable Korean values.
2. Use the real `ReservedTurnRepository`, `TurnDaemonLifecycle`, `ReservedTurnHandler` (and its
   real `ChangeRecorder`), `TurnRunService`, `DatabaseHooks`, and `JdbcFlushExecutor` with a fresh
   PostgreSQL Testcontainer. The Redis stream is an intentionally empty transport seam and the
   realtime publisher is mocked: the matrix classifies Redis wake/SSE publication as N/A transport;
   durable `command_result` and `command_outbox` rows are asserted instead. `TurnRunService` is
   required rather than a direct lifecycle call: it constructs the post-tick payload, performs the
   one JDBC flush, then advances the resident clock only after that commit. Run N in both worlds once;
   assert one `world_state` executor operation per `runTick`.
3. Keep world A resident and run N+1. For world B, scope the N fixture to its call so no local turn
   object remains referenced; reconstruct a fresh world solely with `WorldSnapshotLoader`, recreate
   the handler/lifecycle/repositories, then run N+1 at the identical fixed instant.
4. Construct both worlds from the loaded `hiddenSeed`, `startYear`, and `startTime` metadata, rather
   than fixture literals. Compare canonical hot-state signatures (world state,
   general/city/nation/troop/diplomacy/access log/rank), RNG-consuming action result, and ordered
   nonempty `log_entry` values with the Korean text represented as
   `encode(convert_to(text, 'UTF8'), 'hex')`. Then discard **both** N+1 worlds, load them again, and
   compare each resident signature to its own second reload before comparing the two reloads.
   Compare the general-turn ring as a separate ordered repository query: the resident
   `TurnGeneral.initialTurns` is a boot snapshot and is intentionally not mutated when the runtime
   `ReservedTurnRepository` pulls a slot.
5. Assert world A/B never load the poison world's same local ids, and assert that poison world's
   durable clock/general/city/nation/troop signature is unchanged. This turns ordinary `world_id`
   predicates into an observed two-world isolation proof.

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
  project-recognized docs/parity mapping for this logic change. The exact-commit independent review
  cleared source commit `83132d4a765bc2eae67ed6259e5ba1382aae98a2` against current base
  `53f5d5ebc14e283d1f0dec1758ccb4bf2eaf3497`, with no BLOCKER, MAJOR, or MINOR finding. Its review
  artifact is `docs/superpowers/reviews/2026-08-12-opensam-149-rehydrate-gate-review.md`; rerun
  strict after that artifact is staged. Do not interpret the pre-review result as a passing strict gate.
- Environment preflight on 2026-08-12: `docker info --format '{{.ServerVersion}} {{.OperatingSystem}}'`
  returned `29.3.1 Docker Desktop`, so Docker availability is not the expected reason for a skipped
  focused Testcontainers gate. The eventual XML must still prove `skipped=0`.
- The first full-turn GREEN on 2026-08-13 after isolation/result-payload strengthening is
  superseded, not a release gate: independent review correctly found that it did not compare either
  N+1 resident world to a second reload, so symmetric dropped city/general rows and empty logs could
  have passed. It remains historical baseline only.
- Strengthened full-turn RED on 2026-08-13:
  `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --no-daemon --max-workers=1 --console=plain
  :app:game-engine:test --tests 'opensamguk.engine.boot.FullRehydrateTurnGateIT' --rerun-tasks
  -Pkotlin.compiler.execution.strategy=in-process` completed with `BUILD FAILED in 17m 23s`.
  Fresh XML reports `tests=1`, `skipped=0`, `failures=1`, `errors=0` at `2026-08-13T03:50:50`.
  The second-reload assertion exposed a real reconstruction omission: the committed
  `current_year/current_month/current_phase` columns were present, but their canonical runtime
  metadata mirrors were only written by `InMemoryTurnWorld.setCurrentDate`, not rebuilt by
  `WorldSnapshotLoader`.
- Full-turn GREEN after the loader reconstruction repair on 2026-08-13:
  `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --no-daemon --max-workers=1 --console=plain
  :app:game-engine:test --tests 'opensamguk.engine.boot.FullRehydrateTurnGateIT'
  -Pkotlin.compiler.execution.strategy=in-process` completed `BUILD SUCCESSFUL in 2m 19s`.
  Fresh `app/game-engine/build/test-results/test/TEST-opensamguk.engine.boot.FullRehydrateTurnGateIT.xml`
  reports `tests=1`, `skipped=0`, `failures=0`, `errors=0` at `2026-08-13T03:55:36`; its system output
  records a new `postgres:16-alpine` container and five fresh `WorldSnapshotLoader` loads. The test
  observes continuous N+1 and discard/reload N+1 equality, each N+1 resident's second reload,
  durable clock/ring/result/outbox/nonempty-log channels, and unchanged poison-world rows. The
  production repair is limited to canonical world clock metadata reconstruction in
  `WorldSnapshotLoader`.
- Final focused confirmation after the all-clock snapshot-key precedence extension on 2026-08-13:
  `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --no-daemon --max-workers=1 --console=plain
  :app:game-engine:test --tests 'opensamguk.engine.boot.FullRehydrateTurnGateIT' --rerun-tasks
  -Pkotlin.compiler.execution.strategy=in-process` completed `BUILD SUCCESSFUL in 5m 8s`.
  Fresh XML reports `tests=1`, `skipped=0`, `failures=0`, `errors=0` at
  `2026-08-13T05:00:43` (file mtime `2026-08-13T14:00:51+0900`) and records five loader snapshots.
  The fixture seeds conflicting `game_env.currentYear=999`, `currentMonth=99`, and
  `currentPhase=3`; each fresh snapshot proves the typed durable clock remains authoritative over
  those cold environment values before constructing the RNG handler.
- On 2026-08-13, the first attempt to replace the test's realtime publisher used an obsolete source
  context and `apply_patch` rejected it before any edit. A later read-only `SeedBootstrap.kt` lookup
  used the wrong filename, and a later matrix search had an unmatched shell quote; each was rerun
  against the actual source with the corrected command. None changed database, container, build, or
  repository state. They are isolated tool telemetry, not RED/GREEN evidence.
- The local Fablize observer also emitted generic tool-failure notices around successful read-only
  commands on this host. The affected commands' own exit status and output were successful; no
  repository, database, or build operation failed. Treat those notices as local observer telemetry,
  not gate evidence.
- Broader backend parity was attempted after the focused green on 2026-08-13 with
  `JAVA_HOME=$(/usr/libexec/java_home -v 21) GRADLE_OPTS='-Dorg.gradle.workers.max=1'
  tools/parity/gate.sh backend`. `common` and `logic` completed before three unrelated `infra`
  Testcontainers initializers failed to launch containers: `SatelliteFlushGoldenIT`,
  `V28YearbookServerIdMigrationTest`, and `V30ProfileIconMigrationIT`. The live worker then
  remained in container-retry cleanup, so the command was stopped cleanly with exit `130`; no
  loader or full-rehydrate assertion failed. This is host Docker/Testcontainers-blocked validation,
  not GREEN evidence. Re-run the full backend gate on a stable container host before treating it as
  release coverage.
- Testcontainers must run with `skipped=0` for this task's focused/full integration claims. Docker
  unavailability is a documented blocker, not a passing gate.
- Required terminal checks after rebase: focused engine XML, architecture tests, strict/diff checks,
  and an exact-SHA independent review. The broader backend parity rerun remains required on a stable
  Docker host; its current host-blocked result is intentionally not substituted for a pass.
- The branch was rebased without conflict onto exact base
  `f4ee9135ad6cbce1c6cfb28f7113d7742f478282`. The post-rebase focused JDK 21 rerun completed
  `BUILD SUCCESSFUL in 38m 33s` with 17 tasks executed. Fresh
  `FullRehydrateTurnGateIT` XML (mtime `2026-08-13T16:49:49+0900`) reports `tests=1`,
  `skipped=0`, `failures=0`, and `errors=0`.
- The serialized post-rebase architecture rerun completed `BUILD SUCCESSFUL in 10m 23s` with 20
  tasks executed. Fresh `DaemonNoEntityManagerTest` and `InfraNoEntityManagerTest` XML each report
  `tests=1`, `skipped=0`, `failures=0`, and `errors=0` (mtimes
  `2026-08-13T16:59:37+0900` and `2026-08-13T17:00:40+0900`). All OPENSAM-149 Gradle/Kotlin
  processes then exited before the shared JVM slot was released.
- `git diff --check origin/main...HEAD` is clean. The first strict check correctly remained RED only
  for the missing PR-visible independent-review artifact/docs mapping; it reported no product-code
  finding. Strict is rerun after that review artifact is added, rather than treating this pre-review
  result as GREEN.
- The first exact-source review correctly blocked because the subsequent architecture task had
  replaced the game-engine test-results directory, leaving no focused XML to inspect. After the
  shared JVM slot was reacquired, the exact source SHA was rerun again with the same JDK 21 focused
  command. It completed `BUILD SUCCESSFUL in 19m 47s` with 17 tasks executed; the retained XML at
  `app/game-engine/build/test-results/test/TEST-opensamguk.engine.boot.FullRehydrateTurnGateIT.xml`
  has mtime `2026-08-13T18:10:10+0900` and reports `tests=1`, `skipped=0`, `failures=0`, and
  `errors=0`. This retained post-commit artifact, rather than the earlier overwritten result, is the
  evidence submitted for exact-source re-review.
- PR review remediation on 2026-08-13 invalidated the historical `a9a167...` review binding because
  that commit is unreachable. The immutable replacement source commit is
  `85c79bee6b9d93961997b794ba4a63188081c5e0`; its exact `app/game-engine` tree is
  `4ee085c4e8ed57df8d0dd3acb80cdc415a528e15` and its boot-test directory tree is
  `2b5264313c2c328fb38341ba3a9be659b785230b`. The formerly 577-pure-LOC gate was split into six
  concept files (92/51/28/172/165/126 pure LOC) without changing the one-test/four-tick scenario.
  Fresh JDK 21 focused verification completed `BUILD SUCCESSFUL in 3m 9s`; XML is
  `tests=1`, `skipped=0`, `failures=0`, `errors=0`, SHA-256
  `a08f6924a28f26f3bc088183cfc4053770fc5db8e2ffcb4b5eff0682b4417946`. The serialized
  one-daemon-write run completed `BUILD SUCCESSFUL in 16m 59s`; daemon and infra XML are each
  `1/0/0/0`, with SHA-256
  `179347937470b01234a15f960e54010b7348e4c75adb40470a681ddc944a4fe0` and
  `ddc84313bda84968449a3661863408539cd1a4619f4245b3df2901061c73b5c6`. Gradle overwrites
  same-module focused XML, so the focused and daemon XML were copied to isolated `/tmp` evidence
  immediately after their runs; the tracked review records their counters and digests. A corrected
  zsh Git object query also replaced an initial read-only `${source}:path` expansion typo; the typo
  changed no repository or test state and is not gate evidence.
- A final combined JDK 21 engine invocation retained both `FullRehydrateTurnGateIT` and
  `DaemonNoEntityManagerTest` XML in the same module result directory and completed
  `BUILD SUCCESSFUL in 1m 19s`. Both are `1/0/0/0`; their final simultaneously inspectable
  SHA-256 values are `a9f137c1472f6545251cd3049b913edda24ed860c8237dc243e5bd3ebaba6e5d`
  and `b8abc27c64bea97ba92057a88ca8e974a3940011b6331558cf074d84798d70d5`.

## Loop log

| Round | Baseline | Hypothesis | Measurement | Decision |
| --- | --- | --- | --- | --- |
| 1 | Loader derives unique auction targets only at boot; resident unique-lottery code reads the projection at N+1. | A recorder flush leaves resident and restarted eligibility state divergent. | Fresh Testcontainers XML: 2 tests, skipped=0, failures=1; unique create rehydrates `[che_명마_07_백마]` while resident remains `[]`; isolation passed. | RED confirmed; apply only loader + payload projection + catalog repair. |
| 2 | The repaired projection must be built only through the live ChangeRecorder-aware flush seam. | An id-keyed resident projection applied from full auction upserts makes uninterrupted and rehydrated state equal after create and finalization. | Fresh Testcontainers XML: 2 tests, skipped=0, failures=0, errors=0; terminal `BUILD SUCCESSFUL`. | Adopt bounded loader + `DatabaseHooks` + hot/cold catalog repair; request independent review and broader gates. |
| 3 | The inherited repair had focused projection coverage but no full `TurnRunService` N -> discard/reload -> N+1 proof. | The current single daemon-write spine preserves the clock, ring, hot state, Korean logs, terminal result/outbox rows, and world scope across a real restart boundary. | Initial full XML was green but independent review rejected its symmetric-loss blind spot. | Reject the initial gate; add second-reload and nonempty-flush assertions. |
| 4 | A full resident-to-second-reload comparison should expose omitted HOT reconstruction fields. | Rebuild the canonical `currentYear/currentMonth/currentPhase/lastTurnTime` metadata mirrors from durable world-state columns. | Fresh Testcontainers RED: 1 test, skipped=0, failures=1 at second reload; then fresh GREEN: 1 test, skipped=0, failures=0, errors=0 after the loader repair. | Adopt the bounded loader fix and strengthened full gate; no same-due-tick expansion. |
| 5 | The typed durable clock must outrank same-named `game_env` keys when the snapshot is assembled. | Pin the clock metadata mirrors with the existing source-of-truth snapshot-key pass, then seed distinguishable conflicting cold values. | Fresh `--rerun-tasks` Testcontainers XML: 1 test, skipped=0, failures=0, errors=0; each of five loader snapshots preserves typed year/month/phase over `999`/`99`/`3`. | Keep the precedence regression in the full gate. |
