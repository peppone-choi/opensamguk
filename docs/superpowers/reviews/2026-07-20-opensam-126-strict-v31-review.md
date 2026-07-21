# OPENSAM-126 strict V31+V32 independent review

Date: 2026-07-20 (final follow-up: 2026-07-21)
Reviewer: independent Codex review agents (`op126_final_clearance`,
`op126_message_final_review`)
Scope: full OPENSAM-126 diff from `e536f6f30565eb5852e79466ef60f1209b61227f` across `.ai/`, `.codex/` (user-owned concurrent configuration diff observed and baseline-separated; not implementation-owned), `app/`, `docs/`, `infra/`, `logic/` (including `logic/src`), and `web/`.

## Outcome

The original diplomatic-message writer blocker and the follow-up PHP failure
reason blocker are resolved. No blocker-scope code finding remains for the
OPENSAM-126 strict world-scope stack.

This review clearance is not a production-cutover approval. The final fresh
backend verification is green, but the production-shaped sanitized V31 dump
rehearsal remains `채점대기`, and OPENSAM-126 must not be marked Done until that
separate acceptance evidence exists.

## Resolved diplomatic-message blocker

### Authenticated typed intake, with no game-api write

- `DiplomaticMessageController` no longer reads or saves `MessageEntity`.
  Accept and decline require an authenticated principal, verify the requested
  general through the process-world-scoped `GeneralResolver`, publish the
  existing typed immediate daemon commands, and return `202` with `requestId`.
- The reviewed controller and engine path contains no `EntityManager`, JPA
  `save`, or local-ID-only message mutation. The daemon remains the sole writer.

### World-scoped read and write boundary

- `ContactReader.findMessage(WorldId, Int)` requires the canonical world and
  queries `WHERE world_id = :world_id AND id = :id`.
- The dispatcher supplies the same `world.worldId`. The flush payload carries
  that world, and `JdbcFlushExecutor` invalidates with both `world_id` and `id`
  while requiring exactly one affected row.
- `DiplomaticMessageWorldScopeIT` inserts the same local message ID in two
  worlds, reads and declines only W1, performs one real flush, and proves that
  W2 remains byte/state-identical.

### PHP validation and exact failure reasons

The final implementation was rechecked against the PHP grand truth:

- `DiplomaticMessage.php:23-55` invalidates only a truthy `used` marker or a
  strictly earlier `validUntil`; the legacy `invalid` marker alone is ignored.
- `DiplomaticMessage.php:57-73` orders common validation as invalid/expiry,
  national mailbox, then `checkSecretPermission(..., false) >= 4`.
- `che_불가침수락.php:34-89` performs strict integer/self/start-year argument
  validation. Its stale test is `reqMonth <= currentMonth` at `:116-125`.
- `DiplomaticMessage.php:75-141` executes the three accept commands with
  `NoRNG`, requires FULL constraints, and returns `getFailString()` on denial.
  `BaseCommand.php:377-381,466-472` defines the exact result as
  `{reason} {command name} 실패.`.

`ProcessNationCommand.processInstant` now admits only the three diplomatic
accept commands and preserves the PHP order:

1. strict action-specific `argTest`, including the configured `startYear`;
2. the no-aggression stale replacement constraint;
3. ordered FULL constraints, including missing nation, missing general, and
   proposer-nation membership at their PHP positions;
4. exact command-name failure suffix;
5. zero-draw `NoRng` dispatch on success.

This closes the previously observed suffix-less constraint reason, generic
malformed-argument reason, incorrect `currentYear` fallback for `startYear`,
and early proposer checks that changed FULL constraint ordering. The focused
tests include all three command names, malformed arguments, stale prior months,
missing/mismatched proposers, and the ambassador-versus-`BeChief` ordering.

### One recorder, one flush, replay guard, result after commit

- `DaemonLoopConfig` constructs one `ProcessNationCommand` with the same
  `ChangeRecorder` used by the dispatcher and `TurnRunService`.
- Accept effects and message invalidation enter that recorder and therefore one
  `FlushPayload`; a denial records neither effect nor invalidation. Decline
  records only the invalidation.
- A pending invalidation in the same recorder rejects a replay before a second
  effect can run.
- `TurnRunService` publishes command results only after the synchronous
  transactional `JdbcFlushExecutor.flush(payload)` returns, and clears the
  recorder only after that flush.
- Instant execution does not reseed, update `turn_last`, or call the normal
  turn-completion path.

### UI observes the committed result

- Both message surfaces poll `/api/command/result/{requestId}` through the
  shared typed helper.
- They show success and reload only for `RESOLVED` + success, expose the exact
  daemon denial reason, and treat a still-pending timeout as neutral accepted
  work rather than a false success.

## Cleared schema portions

- V32 classifies the 42 physical tables as 33 strict world-owned tables, mixed
  `game_kv`, `world_state`, and seven exact global tables.
- Strict rows have explicit positive-world ownership without compatibility
  defaults, functions, or triggers. Historical migrations are unchanged; only
  V32 is added.
- Primary keys, required composite child foreign keys, world-leading uniques
  and indexes, same-local-ID coexistence, cross-world rejection, zero/multiple
  world fail-closed behavior, and transactional rollback are covered by the
  V32 catalog and migration tests.
- The two `game_kv` partial unique indexes and both runtime `ON CONFLICT`
  predicates match literally for global inheritance and world-scoped rows.
- `JdbcFlushExecutor`, `ScenarioImporter`, rehydrate bootstrap KV, and
  game-api `general_owner` / `select_npc_token` writes pass explicit
  `WorldId`. The original unscoped message writer has now joined that boundary.
- Changed historical and integration fixtures add required world/parent data
  and scope assertions; removed controller assertions were transferred to
  authenticated controller, authoritative daemon, and real-DB tests rather
  than weakened.

## Verification evidence

Final fresh `$os-verify` backend rerun with JDK 21:

- Gradle result: `BUILD SUCCESSFUL in 16m 13s`.
- `logic`: 270 suites / 3,110 tests.
- `infra`: 46 suites / 172 tests.
- `game-engine`: 89 suites / 599 tests / 1 existing skip.
- `game-api`: 57 suites / 397 tests.
- Backend total: 462 suites / 4,278 tests, failures 0, errors 0, skips 1.
- `web/game`: typecheck and 39 files / 192 tests green.

Final reason/world-scope follow-up, rerun with JDK 21, one worker, and
`--rerun-tasks`:

- Gradle result: `BUILD SUCCESSFUL in 6m 46s`.
- `ProcessNationCommandInstantTest`: 6/6.
- `DiplomaticMessageHandlerTest`: 11/11.
- `IntakeCommandConsumeDispatchTest`: 5/5.
- `DiplomaticMessageWorldScopeIT`: 1/1.
- Total: 23/23, failures 0, errors 0, skips 0.
- `git diff --check`: clean.

Additional observed focused evidence:

- `DiplomaticMessageControllerTest`: 6/6.
- `ContactReaderIT`: 1/1.
- `CommandControllerIT`: 2/2 after a fresh-container retry.
- `logic/src/main/kotlin/opensamguk/logic/actions/nation/InstantNationCommandRegistry.kt`
  is a KDoc-only change mapped to the PHP oracle
  `legacy/devsam-core/hwe/sammo/DiplomaticMessage.php:75-141` and to
  `ProcessNationCommandInstantTest`, `DiplomaticMessageHandlerTest`, and
  `IntakeCommandConsumeDispatchTest`.

Earlier canonical parity/backend gate attempts encountered long-lived
Docker/Testcontainers PostgreSQL startup/readiness and connection failures
before product assertions (`DiplomaticMessageWorldScopeIT`,
`RebirthAndRingTest`, `ScenarioBlankUnificationIT`, and `ScenarioBootIT`). The
later fresh focused run made the changed world-scope IT green, and the final
fresh backend rerun completed successfully. The earlier failures remain
recorded as environment history rather than being silently discarded or
misreported as product-code findings.

Two non-product validation baselines remain separated from this implementation:
the Agent OS test still raises its known `KeyError: max_threads`, and the strict
checker still observes the user-owned `.codex/` model pin. Neither is an
implementation-owned OPENSAM-126 change.

The repeated Fablize wrapper warning is isolated in `.ai/current-state.md` as
an observer-layer baseline: it also appeared on commands that returned exit 0
with valid output and green XML. This review uses the underlying command
result, preserved Gradle completion, and JUnit XML rather than that wrapper
signal.

## Deferred gates and scope boundary

- No approved production-shaped sanitized V31 dump is available. Migration
  row-count/checksum/orphan comparison and lock-duration rehearsal remain
  `채점대기`.
- Remaining loader/read/precheck/intake query scoping and second-world admission
  remain fenced under OPENSAM-127. W3 fenced flush/CAS and durable inbox/outbox
  activation remain separate gates.
- This review clears the OPENSAM-126 world-scoped message writer and its
  immediate accept/decline contract. It does not certify pre-existing broader
  `DiplomaticMessage` content parity still tracked elsewhere, including
  acceptance-side destination stamping, follow-up messages, and unavailable
  legacy log sinks.

Verdict: cleared
