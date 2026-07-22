# S4 Remaining Gaps — Design: Reserved-Execution Correlation, Crash Matrix, Verdict Criteria

Date: 2026-07-22
Author: backend-design worker (task_710f11ef68eb)
Status: build-only design — no deploy/cutover implied
Inputs: `docs/superpowers/research/2026-07-22-s4-remaining-gaps.md`, `docs/superpowers/reviews/2026-07-22-opensam-135-durable-result-outbox-review.md`, `docs/superpowers/specs/2026-07-18-cqrs-consistency-failure-contract.md`, `docs/superpowers/plans/2026-07-18-cqrs-memory-consistency-hardening-plan.md`, `.ai/current-state.md`, `.ai/decisions.md`, direct source reads of the current working tree.

## 0. A note on timing

**The working tree changed under this investigation.** At the start of this task the two HIGH review findings (reserved-execution correlation missing; crash matrix missing) were fully open. Partway through, a concurrent change landed on this same branch (`peppone-choi/status-check-current-work`, uncommitted) that implements the *core mechanism* for the correlation gap: `general_turn`/`nation_turn` gained a `request_id` column, `command_result` gained a `result_seq` column (PK now `(world_id, request_id, result_seq)`), `CommandResultRow` gained `resultSeq`/`terminalizeInbox`, `ReservedTurnHandler.HandledTurn` gained `requestId`/`reservedActionCode`, and `TurnRunService.runTick` now merges `handled.toExecutionCommandResultRows(...)` into the same flush payload as `intakeResults.toCommandResultRows(...)`.

This document therefore does two things instead of one:

- **§A** records the *contract* for reserved-execution correlation (so it has a written spec independent of whoever implements it) against the **current** shape of the code, cites exact file:line evidence, and identifies **one remaining structural gap** that survives the concurrent change — the ring-pull transaction boundary (§A.4). This is the one piece of item A not yet closed by the in-flight work.
- **§B** and **§C** are fully original — no concurrent work touches the crash matrix or the Verdict criteria.

All line numbers below are current as of this read; re-verify before implementing, since the tree is actively moving.

---

## A. Reserved execution lifecycle correlation

### A.1 The two-axis contract (recap)

Per `docs/superpowers/specs/2026-07-18-cqrs-consistency-failure-contract.md` §5.4: a reserved turn has two independent terminal events under the *same* `requestId` —

1. **Admission** (`RESERVATION_ACCEPTED`/`RESERVATION_REJECTED`) — written synchronously by game-api at `POST` time, no `world_version` advance.
2. **Execution** (`EXECUTION_APPLIED`/`EXECUTION_REJECTED`) — written later by the daemon when the reserved turn's `general_turn`/`nation_turn` ring slot comes due, evaluated against live state, **in the same fenced transaction as the state effect**.

Both must be durably retrievable under the original `requestId` — neither may overwrite the other.

### A.2 Current implementation shape (as of this read)

**Identity.** The schema now supports multiple terminal rows per `requestId` via a sequence column, rather than a synthetic second id or an axis enum:

- `infra/src/main/resources/db/migration/V35__command_result_outbox.sql:12-16` — `general_turn`/`nation_turn` each gained `request_id text` (nullable).
- `V35__command_result_outbox.sql:18-36` — `command_result` PK is now `(world_id, request_id, result_seq)`, `result_seq > 0`.
- `infra/src/main/kotlin/opensamguk/infra/persistence/JdbcFlushExecutor.kt:2218-2227` — `CommandResultRow` carries `resultSeq: Int = 1` and `terminalizeInbox: Boolean = true`.

**Identity threading (ring → engine).**

- `app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/CommandReserveService.kt:171-179` — at admission, `reservedTurns.reserve(..., requestId = requestId)` stamps the ring slot with the admission's `requestId`, inside the **same** `transactions.executeWithoutResult { }` block as the `command_inbox` insert and the `reservationAccepted` terminal write (lines 128-195). This is correctly atomic on the API side — one Postgres transaction covers inbox + ring + result + outbox for admission.
- `infra/src/main/kotlin/opensamguk/infra/persistence/ReservedTurnRepository.kt:41-79` — `ReservedTurn.requestId` is read back by `readReserved`/`readReservedNationTurn` (lines 85-106, 285-315).
- `ReservedTurnRepository.kt` pull/push/repeat methods (`pullGeneralTurn` lines 119-146, `pushGeneralTurn` 159-186, `repeatGeneralTurn` 198-240, and the nation-ring mirrors 327-451) all **null out `request_id`** on any slot that rotates out or gets overwritten — the ring's `request_id` is single-use by construction: a slot carries an identity only until it fires or is displaced by a new reservation.
- `app/game-engine/src/main/kotlin/opensamguk/engine/turn/ReservedTurnHandler.kt:172-196` — `HandledTurn` now carries `requestId: String?` and `reservedActionCode: String?` (both default null, so the P1-P4 call sites that don't thread a ring stay source-compatible).
- `app/game-engine/src/main/kotlin/opensamguk/engine/turn/TurnDaemonLifecycle.kt:154-168` — inside `runTick`'s per-general loop, after `handler.handle(...)` returns, the lifecycle does `handled.add(result.copy(requestId = reserved.requestId, reservedActionCode = reserved.actionCode))`. Note: `ReservedTurnHandler.handle` itself does **not** read or propagate `requestId` — the lifecycle stamps it on afterward via `.copy()`. This is a deliberate seam (the handler stays a pure command-resolution function; only the lifecycle, which owns the ring read, knows the identity), and it is correct **as long as** `handled.add` runs before anything can throw between the ring read (`reservedActionOf(g.id)`, line 155) and this stamping (line 163-168) — it does, they are adjacent statements with no intervening I/O.

**Execution-result production and merge into the fenced flush.**

- `app/game-engine/src/main/kotlin/opensamguk/engine/run/TurnRunService.kt:554-586` — `List<HandledTurn>.toExecutionCommandResultRows(committedWorldVersion)`: filters to `handled.requestId != null` (a HandledTurn with no ring identity — e.g. a P1-P4 test call site, or a general with no reserved-turn admission behind it at all — produces no result row, which is correct: not every due general's turn originated from a durable admission), builds `CommandLifecycleResult(type = "executionApplied"|"executionRejected", ok = !fellBack, commandKind = RESERVED_TURN, actionCode, generalId, turnIdx = 0, reason = denyReason)`, and emits a `CommandResultRow(requestId, resultSeq = 2, eventId = "command-result:{world}:{requestId}:2", terminalizeInbox = false, ...)`.
  - `resultSeq = 2` is a **hardcoded** literal, matching the admission row's implicit `resultSeq = 1` default. This is correct for the current two-axis contract (exactly one admission row, exactly one execution row per `requestId`) but is not self-documenting — see A.5 for a naming/constant recommendation.
  - `turnIdx = 0` is also hardcoded here; the ring's actual `turn_idx` at fire time is not threaded through `HandledTurn`. Since `general_turn`/`nation_turn` are 30/12-slot **rotating** rings and the same `requestId` fires exactly once (identity is nulled on pull, A.2 above), this doesn't create a correlation ambiguity, but it does mean the `EXECUTION_APPLIED` result payload cannot report which absolute due-turn it was without a lookup. Low priority — flag for a follow-up if a client ever needs it.
  - `terminalizeInbox = false` is the mechanism that avoids the schema tension the earlier research doc worried about (a due-turn execution finding the `command_inbox` row already terminal `APPLIED` from admission, and failing the `status = 'CLAIMED'` guard). By skipping the inbox transition entirely for `resultSeq = 2` rows, `commandResultUpsertMany` (`JdbcFlushExecutor.kt:2113-2133`) only applies the `UPDATE command_inbox ... WHERE status = 'CLAIMED'` batch to rows where `terminalizeInbox = true`, so execution rows never touch `command_inbox` at all. This is a clean solution — no synthetic inbox row is needed.
- `TurnRunService.kt:269-336` — the month-boundary interleave now accumulates `handledDuringBoundaries` across every `drain` call inside `MonthBoundaryDriver` (line 274: `drain = { upto -> handledDuringBoundaries += lifecycle.runTick(upto) }`) instead of discarding it. This closes the specific bug this investigation found earlier in the session (the production pipeline-wired path previously hardcoded `handled = emptyList()`), so `handled` now reaches the flush regardless of which of the two `runTick` code paths (pipeline-wired vs. fallback) is active.
- `TurnRunService.kt:365-369` — `commandResults = intakeResults.toCommandResultRows(committedWorldVersion) + handled.toExecutionCommandResultRows(committedWorldVersion)`, then `flushWithGeneration(payload)` at line 370. **Both axes' terminal rows ride the identical `FlushPayload.commandResults` list into the identical `flushExecutor.flush(payload)` call** — i.e., the execution result, the general/city/log state deltas, and the world_version CAS all commit or abort together in `flushWithGeneration` (`TurnRunService.kt:398-416`: `generationSession.prepare()` → `flushExecutor.flush(payload)` → `commit`/`abort`). This satisfies the task's "transaction boundary" question for the *result-writing* half of execution: **the EXECUTION_APPLIED/REJECTED row and the state effect it describes are transactionally inseparable.**

### A.3 What this design confirms is now correct

- Reservation and execution never collide at the same `(world_id, request_id)` — they occupy `result_seq` 1 and 2 respectively (schema-enforced by the composite PK, `ON CONFLICT (world_id, request_id, result_seq) DO NOTHING`).
- Double-flush of the same generation (FLUSH_RETRY retrying a retained payload) is idempotent for the result/outbox rows: the same `(requestId, resultSeq)` pair re-inserts as a no-op via `ON CONFLICT DO NOTHING`, and `terminalizeInbox = false` means there is no `command_inbox` row to double-transition either. A retried execution result row is therefore safe.
- The execution result and the state mutation it reports on commit atomically together, because both are entries in the one `FlushPayload` consumed by one `flushExecutor.flush(...)` call.

### A.4 The one remaining gap: ring-pull is not inside the fenced transaction

This is the load-bearing finding of this design pass, and it predates and is independent of the concurrent correlation work — it is **not** fixed by anything in A.2.

`docs/superpowers/specs/2026-07-18-cqrs-consistency-failure-contract.md` §3's baseline-evidence table already flagged this shape of problem in an earlier version of these files ("due ring pull/rotation is NOT currently in the same transaction as `JdbcFlushExecutor.flush(payload)`"). It is still true against the current line numbers:

- `app/game-engine/src/main/kotlin/opensamguk/engine/turn/TurnDaemonLifecycle.kt:191-195` — `pullNationTurnOf(g.nationId, g.officerLevel)` / `pullGeneralTurnOf(g.id)` run **inside** the per-general `runTick` loop, immediately after `handler.handle(...)` / `applyKillturnDecrement(...)`, and **before** the caller (`TurnRunService.runTick`) ever reaches `flushWithGeneration(payload)`.
- `app/game-engine/src/main/kotlin/opensamguk/engine/config/DaemonLoopConfig.kt:414-419` (production wiring) — these callbacks are bound directly to `reservedTurnRepository.pullNationTurn(...)` / `reservedTurnRepository.pullGeneralTurn(...)`, i.e. **live `NamedParameterJdbcTemplate` calls that commit immediately**, not `ChangeRecorder`-tracked deltas. `ReservedTurnRepository` is plain JDBC (`ReservedTurnRepository.kt:32-34`), so it passes `DaemonNoEntityManagerTest` (which only forbids JPA `EntityManager`/Spring-Data references, confirmed by reading `app/game-engine/src/test/kotlin/opensamguk/engine/flush/DaemonNoEntityManagerTest.kt:8-22`) — but the "single fenced transaction" contract is a stronger property than "no JPA," and that stronger property is what's actually violated here. No existing test catches it.

**Concretely, `pullGeneralTurn`'s own SQL (`ReservedTurnRepository.kt:132-146`) nulls the ring slot's `request_id` and resets `action_code`/`arg`/`brief` to `휴식`/`{}`/`휴식` in a statement that commits on its own, seconds (or a full generation) before the state effect + the `executionApplied`/`executionRejected` row for that same `requestId` reach `flushWithGeneration`.**

**The failure window this opens:** if the daemon crashes (process kill, OOM, uncaught exception in a *later* due general's `handler.handle()` within the same tick, etc.) after `pullGeneralTurnOf` has committed for general G but before `flushWithGeneration` commits the tick's `FlushPayload` —

- The ring slot for G is already rotated and `request_id` already NULL in the durable ring. On restart, that slot cannot fire again, so the reserved command is **never re-attempted**.
- G's in-memory state mutation from `handler.handle()` (stat/city/log deltas recorded on `ChangeRecorder`) was never flushed, so it is **lost** — the command silently did not happen.
- Because `request_id` is already NULL on the ring, and no `command_result`/`command_outbox` row was ever written (that write was also inside the lost flush), **there is no durable trace that this reservation ever reached execution.** A client polling `GET /api/command/result/{requestId}` sees the admission row (`reservationAccepted`, `resultSeq=1`) forever and nothing else — indistinguishable from "still queued," when the true state is "silently dropped."

This is exactly the failure mode item A's prompt asks about ("flush fail after state apply, outbox not published") — except the more dangerous variant, since the *identity-consuming* step (ring pull) is what's non-atomic with the *effect-recording* step (flush), not the outbox-publish step (which is already correctly async/retryable via `CommandOutboxRelay`, per the review's remediation notes).

**Design fix — fold ring-pull into the ChangeRecorder delta:**

1. Add a `ReservedTurnPullDelta` concept to `ChangeRecorder` (mirroring the existing pattern for e.g. `recordAccessLogUpsert`/`recordRankIncrease`): `recorder.recordGeneralTurnPull(worldId, generalId, turnCnt = 1)` / `recorder.recordNationTurnPull(worldId, nationId, officerLevel, turnCnt = 1)`. These append a small value object to a new `ChangeRecorder` list rather than calling the repository.
2. `TurnDaemonLifecycle.kt:191-195`'s `pullNationTurnOf`/`pullGeneralTurnOf` callback *type* stays `(params) -> Unit` (no signature break), but `DaemonLoopConfig.kt:414-419`'s production wiring changes from calling `reservedTurnRepository.pullGeneralTurn(...)` directly to calling `handler.recorder.recordGeneralTurnPull(...)` (the recorder is already threaded to this call site — `ReservedTurnHandler.recorder`, `ReservedTurnHandler.kt:168`).
3. Add `generalTurnPulls: List<GeneralTurnPullRow>` / `nationTurnPulls: List<NationTurnPullRow>` to `FlushPayload` (`JdbcFlushExecutor.kt:2140-2206`), and a `generalTurnPullMany`/`nationTurnPullMany` step in `JdbcFlushExecutor.flush(...)` that runs the **same SQL** `ReservedTurnRepository.pullGeneralTurn`/`pullNationTurn` already issue (`ReservedTurnRepository.kt:119-146`, `327-355`) — the SQL itself is correct and needs no change, only its execution site moves from "called eagerly by the lifecycle" to "queued as a delta, executed by the flush executor inside the fenced transaction."
4. `DatabaseHooks.toFlushPayload` (the `ChangeRecorder → FlushPayload` mapper `TurnRunService.kt:530` delegates to) gains the two new list mappings, same pattern as every other channel already there.
5. `ReservedTurnRepository.pullGeneralTurn`/`pullNationTurn` (the public methods) stay as-is for any caller that still wants an eager call (e.g. an admin/ops tool) — this is additive, not a signature break to the repository.

This closes the crash window precisely: after the fix, "ring rotated" and "state effect + execution result committed" become one atomic unit again, matching the one-daemon-write-rule's spirit (`CLAUDE.md` "The ONE daemon-write rule") even though `ReservedTurnRepository` was never a JPA `EntityManager` and so never tripped the existing architecture test.

**Scope note:** this fix is itself build-only and additive (new `ChangeRecorder` methods + new `FlushPayload` fields + one new flush step); it does not touch golden-tested game logic, RNG draw order, or log strings, so it carries no parity risk. It is the single highest-value remaining piece of item A.

### A.5 Minor follow-ups (non-blocking)

- Replace the `resultSeq = 1` (implicit default) / `resultSeq = 2` (hardcoded, `TurnRunService.kt:576`) magic numbers with named constants, e.g. `CommandResultRow.ADMISSION_SEQ = 1`, `CommandResultRow.EXECUTION_SEQ = 2`, in `common/wire` or `infra/persistence` — purely readability, no behavior change.
- `toExecutionCommandResultRows`'s `turnIdx = 0` (`TurnRunService.kt:565`) — either thread the real ring slot through `HandledTurn`, or rename the field/document that it's unused for this result type, so a future reader doesn't assume it's meaningful.
- `CommandLifecycleResult.commandKind` for an execution row is `CommandInboxRepository.CommandKind.RESERVED_TURN.name` (`TurnRunService.kt:562`) — same enum value as the admission row. Correct (it is the same underlying command kind across both axes), just noting it so a consumer doesn't mistake it for an axis discriminator; the axis is `type` (`executionApplied`/`executionRejected` vs. `reservationAccepted`), not `commandKind`.

---

## B. S4-T4 crash/replay matrix

### B.1 Harness strategy

No new test framework. Four existing harnesses already cover the four boundaries that matter; each gets targeted new `@Test`s using its established fault-injection idiom:

| Harness | Existing idiom (reuse as-is) | New fault points to add |
|---|---|---|
| `infra/src/test/kotlin/opensamguk/infra/persistence/CommandResultOutboxFlushIT.kt` | Real Postgres (Testcontainers) + real `JdbcFlushExecutor`; asserts row-level DB state after `executor.flush(...)` succeeds or throws (`assertFailsWith<Exception>`), including that a schema-invalid payload rolls back state+result+outbox+inbox together (lines 119-160). | Two-row (`resultSeq` 1 and 2) flush in one payload; `terminalizeInbox=false` row leaves `command_inbox` untouched; a `resultSeq=2` insert when `resultSeq=1` doesn't exist yet (execution racing ahead of admission — should still succeed, they're independent rows); duplicate flush of the same `(requestId, resultSeq)` is a no-op (`ON CONFLICT DO NOTHING` — assert row count stays 1, not that it throws). |
| `app/game-engine/src/test/kotlin/opensamguk/engine/run/TurnRunServiceFlushRecoveryTest.kt` | Fake `JdbcFlushExecutor` subclass overriding `flush(payload)` to throw on a chosen call (`QueryTimeoutException` → FLUSH_RETRY, `StaleWorldWriterException` → RELOAD_REQUIRED); asserts `recoverySnapshot()`/`recoveryGate()` state and that Redis wake ACK only fires post-commit (lines 50-161). | A `handled` list with a non-null `requestId` reaching `flushWithGeneration` and throwing — assert the execution result never lands in a captured `FlushPayload` on the failed attempt, then lands once on `retryRetainedFlush()` success (mirroring the existing "claimed Redis wake is acked only after successful intake flush" pattern but for the ring/execution-result path instead of the wake-ack path). Once §A.4's fix lands: a fault injected **between** `recordGeneralTurnPull` and `flushWithGeneration` should be inexpressible (it's the same in-memory call graph now) — that's the regression test proving the fix, i.e. this test class becomes the natural home for "ring pull is exactly-once with the flush" once the recorder-based delta exists. |
| `app/game-engine/src/test/kotlin/opensamguk/engine/redis/RedisCommandStreamIT.kt` | Real Redis (Testcontainers) consumer-group semantics: only-new cursor, explicit-ack-required, cross-consumer PEL takeover (lines 47-100). | Wake message claimed but the corresponding DB claim (`CommandInboxRepository.claimForExecution`) returns empty (simulates "wake fired, inbox row already terminal by the time we look" — assert the wake is acked-and-dropped via the existing `terminalRequestIds` fallback path, `TurnRunService.kt` `claimExecutableEnvelopes`, rather than retried forever). |
| `app/game-engine/src/test/kotlin/opensamguk/engine/e2e/VerticalSliceE2EIT.kt` | Full precheck→daemon→flush→SSE→golden-DB-byte-compare, single golden test method today (line 201). | A **second** `@Test` (not a modification of the golden-comparison test — that one must stay byte-pinned): reserve a turn via the real API path, advance the daemon past the due turn, kill/restart the in-process daemon fixture *before* the tick's flush commits (or inject the §A.4 crash window directly once the fix exists), restart, and assert: (a) no duplicate execution of the reserved command, (b) exactly one terminal row pair (`resultSeq` 1 and 2) for the `requestId`, or explicitly documented "silently dropped, pre-fix" behavior if run against the *current* (pre-§A.4-fix) code — see B.2 row 6. |

### B.2 Fault-injection matrix

Columns: fault point → expected `command_inbox` state → expected `command_result` state → expected `command_outbox`/relay state → expected Redis wake/ACK state → which harness proves it.

| # | Fault point | `command_inbox` | `command_result` | `command_outbox` | Redis wake/ACK | Harness / test |
|---|---|---|---|---|---|---|
| 1 | Crash before `command_inbox` INSERT (admission, IMMEDIATE/RESERVED_TURN) | no row | no row | no row | never sent | `CommandReserveServiceTest`/`IT` (existing) — API-side, transaction never opened |
| 2 | Crash after inbox `ACCEPTED` commit, before Redis wake publish (IMMEDIATE) | `ACCEPTED` | no row | no row | not sent; **DB poll fallback** (`claimPendingForExecution`) picks it up on next `runIntakeCommands`/`runTick` | `RedisCommandStreamIT` + `CommandResultOutboxFlushIT.claimPendingForExecution reclaims expired leases only` (existing) |
| 3 | Wake delivered, daemon claims (`ACCEPTED`→`CLAIMED`), crashes before `flushWithGeneration` commits | `CLAIMED` (lease not yet expired) | no row | no row | wake unacked (PEL) | `TurnRunServiceFlushRecoveryTest` "claimed Redis wake is not acked when intake flush fails" (existing) + new: assert `claim_expires_at` eventually lets `claimPendingForExecution` or cross-consumer PEL takeover reclaim it (`RedisCommandStreamIT` "current consumer claims stale wake from another consumer PEL", existing) |
| 4 | `flushWithGeneration` commits (state + `command_result` resultSeq=1 or execution resultSeq=2 + `command_outbox`), crash before `commandOutboxRelay.publishPending()` runs | terminal (`APPLIED`/`REJECTED`) if `terminalizeInbox=true`; unchanged if `terminalizeInbox=false` (execution row) | row present, `published_at IS NULL` | row present, `published_at IS NULL` | ack not yet sent (or sent — see #5) | `CommandResultOutboxFlushIT` "command terminal result and outbox commit with the state effect" (existing) + new: assert relay resumes on next `publishPending()` call without re-flushing |
| 5 | Flush commits, ack sent, crash before `commandOutboxRelay.publishPending()` marks `published_at` | terminal | row present | row present, `published_at IS NULL` on restart | ack already sent (safe — ack only ever fires post-commit, `TurnRunService.kt` `acknowledgeClaimedWakes` at line 371, strictly after `flushWithGeneration` at line 370) | `CommandOutboxRelayTest` (existing) — relay is idempotent: re-publish of the same stored payload, `published_at` set only on Redis SET success |
| 6 | **[NEW — the §A.4 gap]** `pullGeneralTurnOf`/`pullNationTurnOf` commits (ring `request_id` nulled, slot rotated), crash before `flushWithGeneration` commits the same tick's state + execution result | ring: rotated, `request_id` NULL (already durable); `command_inbox` for the *admission* row: unchanged (still `APPLIED` from admission) | **no execution (`resultSeq=2`) row — permanently absent** (pre-fix); state mutation lost | no execution outbox row | n/a (RESERVED_TURN never rode the wake stream) | **Pre-fix: document as a known gap via `VerticalSliceE2EIT`'s new crash-restart test (B.1) — expect FAIL / explicit backlog entry, not a fabricated pass.** Post-§A.4-fix: ring pull becomes a `ChangeRecorder` delta in the same `FlushPayload`, so this fault point collapses into #4 (all-or-nothing with the state effect) — re-run the same test and expect PASS. |
| 7 | Stale CAS (`world_version` mismatch) during flush → `RELOAD_REQUIRED` | unchanged from pre-flush | unchanged | unchanged | unacked | `TurnRunServiceFlushRecoveryTest` "stale CAS flush enters RELOAD_REQUIRED and blocks intake and tick" (existing) |
| 8 | Transient DB error during flush → `FLUSH_RETRY`, then successful `retryRetainedFlush()` | terminal after retry succeeds | row present after retry (same payload replayed — `ON CONFLICT DO NOTHING` makes a partial-then-retried write idempotent) | row present after retry | ack fires only after retry's `flushWithGeneration` succeeds | `TurnRunServiceFlushRecoveryTest` "transient flush enters FLUSH_RETRY then retryRetainedFlush resumes READY" (existing) |
| 9 | Two consumers race the same wake (cross-instance failover) | one wins the `CLAIMED` row (`UPDATE ... WHERE status = 'ACCEPTED'` is atomic per row); the other's `claimForExecution` returns it empty | single row (whoever flushes first; the other's later flush attempt would double-process in memory but `ON CONFLICT DO NOTHING` + the `CLAIMED`-only inbox transition guard prevents a double terminal write) | single row | first claimant's consumer acks; PEL takeover claims if the first claimant dies mid-processing | `RedisCommandStreamIT` "current consumer claims stale wake from another consumer PEL" (existing) — extend assertion to also check `command_inbox` claimed-once via `CommandInboxRepository.claimForExecution`'s atomic `UPDATE...RETURNING` (`CommandInboxRepository.kt`, referenced in prior investigation) |
| 10 | Double-execute: same `requestId` claimed twice due to a retried `claimExecutableEnvelopes` call after a transient error in `commandDispatcher.dispatchEnvelopes` (not the flush) | first claim → `CLAIMED`; second claim attempt for the same row after the first already advanced to terminal → excluded (`claimForExecution`/`claimPendingForExecution` only select `ACCEPTED` or lease-expired `CLAIMED`) | single row per `resultSeq` | single row | ack sent once, on the successful attempt | `CommandResultOutboxFlushIT` "claimForExecution returns DB payloads in wake order and marks a finite lease" (existing) — extend with a second `claimForExecution` call after a terminal transition, assert empty result |

**No silent caps:** rows #1-5, #7-10 are already provable with the *current* code — item C should not accept a Verdict flip until they are actually written (they are specified here, not yet coded, per this task's build-only/design-preferred scope). Row #6 is explicitly a **known-gap** row: the matrix must show it FAILING (or explicitly `채점대기`/quarantined per `CLAUDE.md`'s mandatory legacy-gap chain convention) against pre-§A.4-fix code, and the acceptance criterion in §C is that this row passes only after §A.4 ships — do not weaken row #6's assertion to make it pass early.

---

## C. Acceptance criteria for flipping the review Verdict

Target artifact: `docs/superpowers/reviews/2026-07-22-opensam-135-durable-result-outbox-review.md` (currently `Verdict: fix-required`, two HIGH findings).

### C.1 Finding-by-finding criteria

**HIGH — "Reserved/queue lifecycle correlation is incomplete."**

Flip to resolved when ALL of:

1. §A.2's mechanism (ring `request_id`, `resultSeq`-keyed `command_result`, `terminalizeInbox`, `handled.toExecutionCommandResultRows` merged into the same `FlushPayload.commandResults`) is present in a **committed** state on the branch (not just working-tree-uncommitted) — verify via `git log` showing the commit(s), not `git status`.
2. §A.4's ring-pull transaction-boundary fix is implemented: `pullGeneralTurnOf`/`pullNationTurnOf` in production wiring (`DaemonLoopConfig.kt`) route through `ChangeRecorder` + `FlushPayload`, not a direct eager `ReservedTurnRepository` call. Evidence: a diff showing `recordGeneralTurnPull`/`recordNationTurnPull` on `ChangeRecorder`, the two new `FlushPayload` list fields, and the corresponding `JdbcFlushExecutor` flush step, plus `DaemonLoopConfig.kt`'s callback wiring updated to call the recorder instead of the repository directly.
3. Matrix row #6 (§B.2) passes: a `VerticalSliceE2EIT` (or equivalent) crash-restart test proves a reserved command's due-turn execution and its ring-slot consumption are atomic — no silently-dropped reserved commands, no duplicate execution on restart.
4. `CommandResultLookupTest`/equivalent proves a client polling `GET /api/command/result/{requestId}` after a reserved turn both admits and executes observes **both** terminal events (not just the admission), e.g. by returning the highest `result_seq` row, or a list, per the actual read-API contract (whichever the API layer settles on — this doc does not prescribe the read-side shape, only that both rows must be reachable).
5. Focused test bundle green: `:common:test --tests *TurnDaemonCommandResultWireTest*`, `:infra:test --tests *CommandResultOutboxFlushIT*`, `:app:game-api:test --tests *CommandReserveService*`, `:app:game-engine:test --tests *TurnRunServiceFlushRecoveryTest* --tests *VerticalSliceE2EIT*` — all `BUILD SUCCESSFUL`, output tail + XML checked per `CLAUDE.md`'s verification rule (not exit code alone).

**HIGH — "Crash/replay matrix is missing."**

Flip to resolved when ALL of:

1. Every row of §B.2's matrix (#1-10) has a corresponding `@Test` in its named harness, committed.
2. Row #6 specifically demonstrates the before/after contrast described above (fails pre-§A.4-fix in the same PR history or an explicitly linked prior run, passes post-fix) — this is the crash-matrix's own proof that it isn't a rubber-stamp.
3. No row is skipped, weakened, or marked `.skip`/`.only` — per `CLAUDE.md`'s no-fake-completion guard. A row that genuinely cannot be tested without Docker (Testcontainers-dependent rows) may report Docker-unavailable-skip, which is distinct from a disabled test — `Assumptions.assumeTrue(...)` pattern already used in `CommandResultOutboxFlushIT.kt:32-35`.
4. `python3 tools/agent-system/check.py --strict --base origin/main` passes with no `fix-required` findings attributable to this area.

### C.2 If residual findings remain instead of a clean flip

Per this task's framing ("or clear residual fix-required items with proof"), an acceptable alternative to a full flip is: keep `Verdict: fix-required`, but replace each HIGH finding's text with a **pointer to a tracked follow-up** (a Jira/GitHub issue id, matching the `docs/agent/` ticket-mapping convention already used for ARCH-S4-T4 → OPENSAM-136/#282) plus the evidence in §A.4/§B.2 proving the *remaining* scope is narrow and understood (not "still investigating," but "here is exactly what's left and why it's deferred"). This is the honest path if §A.4's fix is judged out-of-scope for the current PR and deserves its own review cycle — do not stretch this design doc's evidence to claim a flip that hasn't actually landed.

### C.3 What NOT to accept as evidence

- A green `VerticalSliceE2EIT` golden-comparison run alone — it does not exercise any crash path (single golden replay, no fault injection), so it cannot support either HIGH finding's resolution by itself.
- "The mechanism looks right in code review" without the row-#6 before/after test — the whole point of the ring-pull gap is that it is invisible under happy-path testing (every existing test flushes successfully; the bug only manifests when a crash lands in the specific window between `pullGeneralTurnOf` and `flushWithGeneration`).
- Re-running `CommandResultOutboxFlushIT`'s existing two tests unchanged — they predate `resultSeq`/`terminalizeInbox` conceptually similar cases but do not yet cover the two-row-per-`requestId` scenario (§B.1 table, column 3) until the new cases in this doc are added.

---

## Appendix — file/line index (current as of this read; verify before implementing)

- `infra/src/main/resources/db/migration/V35__command_result_outbox.sql` — schema (ring `request_id`, `command_result.result_seq` PK).
- `app/game-api/.../reserve/CommandReserveService.kt:110-199` — admission write (inbox + ring + `reservationAccepted` result, one transaction).
- `infra/.../persistence/ReservedTurnRepository.kt` — ring reserve/read/pull/push/repeat, all plain JDBC, `request_id` threading.
- `app/game-engine/.../turn/ReservedTurnHandler.kt:172-196` — `HandledTurn` shape.
- `app/game-engine/.../turn/TurnDaemonLifecycle.kt:111-204` — `runTick` per-general loop; line 163-168 requestId stamping; line 191-195 the ring-pull call site (§A.4 gap).
- `app/game-engine/.../turn/TurnDaemonLifecycle.kt:250-278` — `MonthBoundaryDriver`.
- `app/game-engine/.../run/TurnRunService.kt:251-388` — `runTick`; line 274 `handledDuringBoundaries` accumulation; line 365-370 merged `commandResults` into `flushWithGeneration`.
- `app/game-engine/.../run/TurnRunService.kt:398-416` — `flushWithGeneration` (the fenced transaction boundary).
- `app/game-engine/.../run/TurnRunService.kt:554-586` — `toExecutionCommandResultRows`.
- `app/game-engine/.../config/DaemonLoopConfig.kt:370-422` — production wiring of `TurnDaemonLifecycle` callbacks, including line 414-419 (the ring-pull direct-call site to change per §A.4).
- `infra/.../persistence/JdbcFlushExecutor.kt:2045-2136` — `commandResultUpsertMany`, `terminalizeInbox`-gated inbox transition.
- `infra/.../persistence/JdbcFlushExecutor.kt:2140-2227` — `FlushPayload`, `CommandResultRow`.
- `infra/.../persistence/CommandResultRepository.kt` — API-side terminal-result writer (admission path), `insertTerminalResult`.
- `app/game-engine/src/test/.../flush/DaemonNoEntityManagerTest.kt` — confirms the existing architecture test's scope (JPA-only, does not catch the ring-pull gap).
- Test harnesses referenced in §B: `CommandResultOutboxFlushIT.kt`, `TurnRunServiceFlushRecoveryTest.kt`, `RedisCommandStreamIT.kt`, `VerticalSliceE2EIT.kt`, `CommandOutboxRelayTest.kt`.
