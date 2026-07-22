# S4 Remaining-Gap Map — OPENSAM-133/134/135

Date: 2026-07-22
Branch: `peppone-choi/status-check-current-work` (active worktree; no branch switch)
Role: research only — **no production code edits**
Scope: close remaining S4 gaps only — **no deploy/cutover**

Sources of truth for “still open”:

- `.ai/current-state.md` (OPENSAM-133/134/135 sections; final bullet: reserved execution correlation + S4-T4 missing)
- `docs/superpowers/reviews/2026-07-22-opensam-135-durable-result-outbox-review.md` (Verdict: `fix-required`; Completion status: reserved execution + crash matrix)
- Contract: `docs/superpowers/specs/2026-07-18-cqrs-consistency-failure-contract.md` §5.4 / §8.2–8.3
- Plan: `docs/superpowers/plans/2026-07-18-cqrs-memory-consistency-hardening-plan.md` ARCH-S4-T1..T4

---

## 0. Executive summary

S4 foundation on this branch is **substantially built** for:

| Area | Ticket | Status on branch |
|------|--------|------------------|
| `command_inbox` authority before 202 | OPENSAM-133 / S4-T1 | Implemented (API producers) |
| Inbox claim/lease + DB poll fallback | OPENSAM-134 / S4-T2 | Implemented (engine claim path) |
| Redis consumer-group wake + post-commit ACK + PEL takeover | OPENSAM-134 / S4-T2 | Implemented |
| Durable `command_result` + `command_outbox` in flush TX | OPENSAM-135 / S4-T3 | Implemented (immediate intake + API lifecycle) |
| Outbox relay/retry | OPENSAM-135 / S4-T3 | Implemented (`CommandOutboxRelay`) |
| API-side `reservationAccepted` / `queueMutation` terminals | OPENSAM-134/135 correlation | Implemented |

**Exactly two remaining S4 gaps called out by current-state + review:**

1. **Reserved-turn EXECUTION is not correlated** as `EXECUTION_APPLIED` / `EXECUTION_REJECTED` to the original reservation `requestId` after due-turn evaluation.
2. **S4-T4 crash/replay matrix is missing** (before/after state commit, outbox publish, ACK; reserved reservation-vs-execution split).

Both are build-only; deploy/cutover remains out of scope.

---

## 1. What is already implemented (path:line evidence)

### 1.1 `command_inbox` authority (OPENSAM-133 / S4-T1)

**Schema**

- `infra/src/main/resources/db/migration/V34__command_inbox.sql:1-18` — table `(world_id, request_id)` PK; kinds `IMMEDIATE|RESERVED_TURN|QUEUE_MUTATION`; initial status `ACCEPTED` only.
- `infra/src/main/resources/db/migration/V35__command_result_outbox.sql:1-10` — status widened to `ACCEPTED|CLAIMED|APPLIED|REJECTED`; claim lease columns `claimed_at` / `claim_expires_at`.

**Repository**

- `infra/.../CommandInboxRepository.kt:50-106` — `insertAccepted` (`ON CONFLICT DO NOTHING` + fingerprint `Inserted|ExistingSame|Conflict`).
- Same file `:108-120` — `markRedisWakePublished`.
- Same file `:16-20` — `CommandKind` enum.

**API producers (DB before Redis wake)**

- `app/game-api/.../CommandReserveService.kt:150-198` (approx; reserve path): inbox `RESERVED_TURN` + ring `reserve` + terminal in one TX; `publishAfterCommit` only if inserted.
- Immediate path: `publishImmediate` inserts `IMMEDIATE` inbox then wake (same service).
- `app/game-api/.../CommandQueueService.kt:351-380` — `queueMutation` TX: ring mutate + inbox.
- Possession / admin moderation / bulk queue producers narrowed per `.ai/current-state.md` (OPENSAM-133 section).

### 1.2 Claim / lease (OPENSAM-134 / S4-T2)

- `CommandInboxRepository.kt:123-137` — `claimForExecution(worldId, requestIds, now, lease)` preserves wake order.
- `:139-164` — `claimPendingForExecution`: polls `ACCEPTED` or expired `CLAIMED`; **kinds restricted to `IMMEDIATE` and `RESERVED_TURN`** (excludes `QUEUE_MUTATION` — API-terminalized).
- `:182-220` — atomic `UPDATE ... RETURNING` sets `status='CLAIMED'`, lease timestamps; default lease 5 minutes (`:231`).
- `:166-180` — `terminalRequestIds` for already-terminal wake ACK.
- Engine wiring: `TurnRunService.kt:554-575` `claimExecutableEnvelopes` — DB claim from wake IDs, else ACK terminal wakes, else pending poll.
- Production bean: `DaemonLoopConfig.kt:114-115` (`commandInboxRepository`).

**Flush gate requires CLAIMED for engine terminalization**

- `JdbcFlushExecutor.kt:2110-2118` (approx) — inbox terminal update requires `status = 'CLAIMED'`.

### 1.3 Redis wake ACK / PEL (OPENSAM-134 / S4-T2)

- `RedisCommandStream.kt:28-35` — consumer group `game-engine`, per-world consumer name, 30s PEL idle default.
- `:97-105` — `readWakeEnvelopes`: `XREADGROUP >` → own pending `0` → stale foreign PEL claim.
- `:106-121` — `claimStaleWakeEnvelopes` via Spring Data `pending` + `claim`.
- `:123-126` — `acknowledgeWake(messageIds)`.
- `TurnRunService.kt:232-246` (`runIntakeCommands`): flush then `acknowledgeClaimedWakes` (post-commit ACK).
- `TurnRunService.kt:363-367` (`runTick`): same ACK-after-flush order.
- `TurnRunService.kt:577-582` — `acknowledgeClaimedWakes` only when message IDs present.
- Tests: `app/game-engine/.../RedisCommandStreamIT.kt` (group wake, ACK, PEL takeover); `TurnRunServiceFlushRecoveryTest`.

### 1.4 Durable result + outbox in flush TX (OPENSAM-135 / S4-T3)

- Schema: `V35__command_result_outbox.sql:12-40` — `command_result` PK `(world_id, request_id)`; `command_outbox` PK `event_id`; unpublished index.
- `FlushPayload.commandResults` — `JdbcFlushExecutor.kt:2205`.
- `JdbcFlushExecutor.kt:307-308` — `commandResultUpsertMany` inside the same `transactionTemplate` as state writes.
- `JdbcFlushExecutor.kt:2045-2124` — insert result + outbox (`ON CONFLICT DO NOTHING`) + terminalize inbox from `CLAIMED`.
- Intake path builds rows: `TurnRunService.kt:528-547` `toCommandResultRows` from dispatcher `(requestId, TurnDaemonCommandResult)`.
- API result fallback: `CommandController.kt:171-186` Redis miss/corrupt → `CommandResultRepository.findResultPayload`.

### 1.5 `CommandOutboxRelay` (OPENSAM-135)

- `app/game-engine/.../CommandOutboxRelay.kt:7-29` — `publishPending`: list unpublished → Redis SET exact payload → `markCommandOutboxPublished` only on success.
- `TurnRunService.kt:223-224, 251, 436` — relay before work and after successful flush/retry.
- `TurnRunService.kt:442-451` — production prefers relay; direct Redis publish only if relay null (narrow tests).
- Wiring: `DaemonLoopConfig.kt:169` (approx) `commandOutboxRelay` bean.
- Tests: `CommandOutboxRelayTest.kt`; pending/mark assertions in `CommandResultOutboxFlushIT`.

### 1.6 API-side `reservationAccepted` / `queueMutation` terminals

**Wire**

- `common/.../TurnDaemonCommandResult.kt:126-136` — `CommandLifecycleResult`.
- Same file `:559` — `COMMAND_LIFECYCLE_TYPES = setOf("reservationAccepted", "queueMutation")` only (no execution types yet).

**Factory + write**

- `app/game-api/.../CommandTerminalResultFactory.kt:12-47` — builds `CommandResultRow` with `eventId = "command-result:{world}:{requestId}:1"`, `committedWorldVersion = 0`.
- `CommandResultRepository.kt:72-154` — `insertTerminalResult`: result + outbox + inbox terminal in **caller** TX; `expectedInboxStatuses` default `ACCEPTED|CLAIMED`.
- `CommandReserveService.kt:179-193` — on successful reserve insert: `type = "reservationAccepted"`, `expectedInboxStatuses = setOf("ACCEPTED")` same TX as ring write.
- `CommandQueueService.kt:408-414` — `type = "queueMutation"` same TX as ring mutation.
- `claimPendingForExecution` excludes `QUEUE_MUTATION` (`CommandInboxRepository.kt:150`).
- Terminal reserved wakes ACKed without re-dispatch (`TurnRunService.kt:567-573`).
- Tests: `CommandReserveServiceTest` asserts `reservationAccepted`; `CommandQueueTest` asserts `queueMutation`; wire test `TurnDaemonCommandResultWireTest.kt:84-93`.

### 1.7 Due reserved-turn EXECUTION path (exists, but uncorrelated)

This path **does** execute reserved actions and flush state; it does **not** write an execution lifecycle result:

| Step | Location |
|------|----------|
| Due general selection | `TurnDaemonLifecycle.kt:101-114` `dueGenerals` / `runTick` |
| Nation pass then general pass | `:136-166` `ProcessNationCommand` / `ReservedTurnHandler.handle` |
| Ring consume (always, even blocked) | `:189-190` `pullNationTurnOf` / `pullGeneralTurnOf` |
| Handler outcome shape | `ReservedTurnHandler.kt:172-196` `HandledTurn` — **no `requestId`** |
| Orchestration flush | `TurnRunService.kt:250-367` — `commandResults` built **only from intakeResults**, not from `handled` |
| Ring repository pull | `ReservedTurnRepository.kt:119+` `pullGeneralTurn`, `:327+` `pullNationTurn` |
| Ring schema | `V1__baseline.sql:110+` `general_turn` / `nation_turn` — **no `request_id` column** |

So execution effects are durable via ChangeRecorder → JDBC flush, but the original reservation `requestId` never receives an `EXECUTION_*` terminal.

---

## 2. Exact remaining gaps (current-state + review)

### Gap A — Reserved EXECUTION not correlated to reservation `requestId`

**Stated in**

- `.ai/current-state.md` final OPENSAM-134/135 section:
  > “Reserved turn execution after reservation remains a separate lifecycle and is not yet correlated to the original reservation request as `EXECUTION_APPLIED/REJECTED`.”
- Review `2026-07-22-opensam-135-...-review.md:20,62`: HIGH incomplete reserved lifecycle; completion still requires reserved execution correlation.

**Contract requirement**

- Spec §5.4 (`docs/superpowers/specs/2026-07-18-cqrs-consistency-failure-contract.md` around lines 110–119): two axes — reservation admission vs later due-turn execution (`EXECUTION_APPLIED` / `EXECUTION_REJECTED`). Must not collapse reservation into execution.
- Spec §8.2–8.3: reservation is API primary TX (no `world_version` advance); due execution is fenced engine `G` including ring consume/rotation + state effect (if any) + version CAS + execution durable result + outbox in **one** `JdbcFlushExecutor` transaction.
- Plan S4-T1 GWT: reservation success implies later separate execution lifecycle.

**Why current code fails the contract**

1. **Admission axis written immediately as inbox terminal**
   Reserve TX writes `reservationAccepted` and terminalizes inbox to `APPLIED` (`CommandReserveService` + `insertTerminalResult`). That is correct for **reservation** axis, but consumes the only `command_result` row slot.

2. **Schema allows only one result per request**
   `command_result` PRIMARY KEY `(world_id, request_id)` (`V35:12-24`).
   Upserts use `ON CONFLICT (world_id, request_id) DO NOTHING` (`JdbcFlushExecutor.kt:2095-2096`, `CommandResultRepository` insert).
   A second execution result under the **same** `requestId` is **silently dropped**.

3. **Wire vocabulary incomplete**
   `COMMAND_LIFECYCLE_TYPES` has only `reservationAccepted` / `queueMutation` — no `executionApplied` / `executionRejected` (or contract names `EXECUTION_APPLIED` / `EXECUTION_REJECTED`).

4. **No requestId on execution seam**
   `HandledTurn` has no requestId; `general_turn` / `nation_turn` have no request_id; `runTick` never maps handled turns → `FlushPayload.commandResults`.

5. **eventId sequence hint is unused**
   Factory uses `command-result:{world}:{requestId}:1` (`CommandTerminalResultFactory.kt:39`) — suggests multi-event intent, but result PK is still one row per requestId.

**Net:** Clients polling `/result/{requestId}` see durable `reservationAccepted` only. They never learn due-turn `EXECUTION_APPLIED`/`EXECUTION_REJECTED` for that reservation, even though the action may have run (or been denied/fallback to 휴식) at turn time.

### Gap B — S4-T4 crash/replay matrix missing

**Stated in**

- `.ai/current-state.md`: “S4-T4 crash/replay matrix remains missing.”
- Review HIGH finding line 20; completion status line 62.
- Plan ARCH-S4-T4 GWT (plan ~lines 270–280): crash points
  `before inbox commit`, `after inbox before wake`, `before/after state commit`, `before/after outbox publish`, `before/after ACK`, Redis/DB solo faults; reserved reservation held while execution terminal separate.

**What exists today (partial, not the matrix)**

- Unit/IT coverage for claim lease, outbox relay mark-after-SET, consumer ACK, PEL takeover, reserve/queue terminal TX, flush recovery gate (`TurnRunServiceFlushRecoveryTest`, `CommandResultOutboxFlushIT`, `RedisCommandStreamIT`, reserve/queue tests).
- **No** parameterized fault-injection suite covering the full ordered crash points for both immediate and reserved flows with cardinality assertions (inbox/result/outbox, at-most-once state effect).

---

## 3. File/symbol map — where reserved EXECUTION terminal should be written

### 3.1 Decision: **game-engine flush path**, not game-api

| Concern | Owner | Why |
|---------|-------|-----|
| Reservation admission (`RESERVATION_ACCEPTED` / reject) | **game-api** primary TX | Already: ring + inbox + reservation result/outbox; no due evaluation; no `world_version` CAS |
| Due execution (`EXECUTION_APPLIED` / `EXECUTION_REJECTED`) | **game-engine** fenced `G` | Only engine has live world + `ReservedTurnHandler` + ring pull + ChangeRecorder + `JdbcFlushExecutor` CAS |

Writing execution terminals from game-api would reintroduce a second writer and violate one-daemon-write / fenced-G rules.

### 3.2 Recommended write seam (symbols)

```
TurnDaemonLifecycle.runTick
  → ReservedTurnHandler.handle / ProcessNationCommand.process
  → pullGeneralTurnOf / pullNationTurnOf          # mandatory ring delta even on EXECUTION_REJECTED
  → (return HandledTurn list, still no requestId today)

TurnRunService.runTick
  → buildFlushPayload()                            # state deltas from recorder
  → attach EXECUTION CommandResultRow(s)           # NEW: correlate requestIds
  → flushWithGeneration(payload)                   # JdbcFlushExecutor same TX
  → commandOutboxRelay.publishPending()            # post-commit relay (existing)
```

**Concrete insertion points**

| Symbol | Path | Role for Gap A |
|--------|------|----------------|
| `TurnRunService.runTick` | `app/game-engine/.../TurnRunService.kt:250-367` | After handling, build execution `commandResults` **in addition to** intake results; pass into `FlushPayload` |
| `TurnRunService.flushWithGeneration` | same file `:398-416` | Unchanged contract: prepare → JDBC → commit/abort |
| `JdbcFlushExecutor.flush` / `commandResultUpsertMany` | `infra/.../JdbcFlushExecutor.kt:56+`, `:2045-2124` | Persist execution result+outbox; **must change** if dual-axis needs multi-row result or non-CLAIMED inbox transition |
| `FlushPayload.commandResults` / `CommandResultRow` | `JdbcFlushExecutor.kt:2205-2218` | May need lifecycle axis / sequence fields |
| `ReservedTurnHandler.HandledTurn` | `engine/turn/ReservedTurnHandler.kt:172-196` | Carry `requestId?`, action outcome (`fellBack`/`denyReason` → REJECTED vs APPLIED) |
| `TurnDaemonLifecycle.runTick` | `engine/turn/TurnDaemonLifecycle.kt:111-190` | Thread requestId from ring/reservation ledger into handle outcome |
| `ReservedTurnRepository.reserve` / ring rows | `infra/.../ReservedTurnRepository.kt` + `general_turn` | **Correlation store** candidate: persist `request_id` on slot write |
| Wire | `common/.../TurnDaemonCommandResult.kt` | Add execution lifecycle types; extend `COMMAND_LIFECYCLE_TYPES` / factory |
| Result API | `game-api/.../CommandController.kt:171+` | Surface dual-axis (list/history or composite) — consumers currently assume one RESOLVED payload |

### 3.3 Schema tension to resolve before coding

Dual-axis on one `requestId` **collides** with:

- `command_result` PK `(world_id, request_id)`
- inbox single terminal status (`APPLIED` after reservation already)
- `commandResultUpsertMany` requiring inbox `CLAIMED` for engine terminalization (reserved admission already left inbox `APPLIED`, never `CLAIMED` for due execution)

**Design options (research recommendation only):**

1. **Preferred for contract fidelity:** multi-row lifecycle results
   - PK → `(world_id, request_id, lifecycle_axis)` or `(world_id, request_id, result_seq)`
   - Axes: `RESERVATION` | `EXECUTION` (and maybe `QUEUE`)
   - Outbox `event_id` already unique; use `:1` reservation / `:2` execution
   - Inbox status semantics: either keep admission terminal separate from execution ledger, or add execution status column / child table so inbox is not forced to double-terminalize
2. **Weaker:** separate synthetic execution `requestId` (breaks “correlate to original reservation requestId”).
3. **Forbidden by contract:** overwrite reservation result with execution result.

### 3.4 Correlation strategy (requestId → due turn)

Ring currently has **no** `request_id` (`V1` `general_turn` / `nation_turn`). Slot `turn_idx` at reservation time is **not** stable identity through pulls (ring rotates).

Safe correlation requires one of:

- **A.** Persist `request_id` on `general_turn`/`nation_turn` at `CommandReserveService.reserve` / nation reserve (cleared or rewritten on overwrite/push/repeat).
- **B.** Side table `reservation_slot(world_id, general_id|nation+level, turn_idx, request_id, fingerprint)` maintained in the same API TX as ring write.
- **C.** At due time, match front-of-ring action+args fingerprint to latest non-superseded reservation row (fragile with AI rewrite / push / repeat — **not recommended**).

Recommendation: **A or B in same TX as reservation admission**; thread into `HandledTurn`; emit EXECUTION row only when a binding exists (AI-replaced autorun may need explicit “execution of reserved vs chosen” policy — mark UNKNOWN until PHP/W3 evidence if ambiguous).

---

## 4. Recommended implement order + risks

### Order (S4 remainder only; no deploy)

| Step | Work | Why this order | Primary modules |
|------|------|----------------|-----------------|
| **R0** | Freeze dual-axis data model (axis column / multi-result PK + inbox semantics + result API shape) | Unblocks all code; wrong model → rewrite | migration, `CommandResultRow`, wire, review artifact |
| **R1** | Wire types: `executionApplied`/`executionRejected` (or exact contract enum names) + serializer tests | Cheap; enables payload encoding | `common` |
| **R2** | Persist reservation `request_id` on ring or ledger in API reserve/queue overwrite paths | Correlation before engine can emit | `game-api` reserve/queue + `ReservedTurnRepository` + migration |
| **R3** | Engine due path: HandledTurn(+nation) → EXECUTION `CommandResultRow` on **tick** flush only | Core Gap A | `TurnDaemonLifecycle`, `TurnRunService`, `JdbcFlushExecutor` rules for non-CLAIMED execution axis |
| **R4** | Outbox relay already generic — verify EXECUTION events publish; result API dual-axis read | Completes client observability | `CommandOutboxRelay`, `CommandController` |
| **R5** | **S4-T4 crash/replay matrix** (immediate + reserved): before/after inbox, wake, state commit, outbox publish, ACK; assert at-most-once state + dual terminals for reserved | Gap B; only meaningful after R0–R4 exist | ITs under `infra`/`game-engine`/`game-api` |
| **R6** | Independent review re-run → clear `fix-required` verdict | Unblocks agent-system strict check | review doc |

**Do not** start with only more unit tests on the current single-result schema — they would cement the collapse of axes.

### Risks

| Risk | Severity | Notes |
|------|----------|-------|
| **Single-result PK vs dual axis** | HIGH | Silent `DO NOTHING` drops EXECUTION if naively inserted under same requestId |
| **Inbox already APPLIED after reservation** | HIGH | Engine `commandResultUpsertMany` expects CLAIMED → fails or needs separate execution ledger path |
| **Ring rotation loses turn_idx identity** | HIGH | Must bind requestId at reservation write time, not at due-time slot index guess |
| **AI autorun / push / repeat overwrite** | MEDIUM | Supersede rules for which requestId owns front-of-ring; W3 UNKNOWN for cancellation/replacement (`CQRS-CF-U1`) must not invent PHP semantics |
| **Mixed tick generations** | MEDIUM | One tick flushes many generals; each EXECUTION row shares generation `committedWorldVersion`; intake results may coexist in same `G` |
| **EXECUTION_REJECTED still rotates ring** | MEDIUM | Contract: ring consume is mandatory state delta even when action denied; must still write EXECUTION_REJECTED with ring delta in same TX |
| **Result API one-payload assumption** | MEDIUM | FE/poll currently one RESOLVED body; dual-axis needs explicit shape (array or `lifecycle` field) without breaking immediate commands |
| **S4-T4 cost / flaky Docker** | MEDIUM | Full matrix is expensive; parameterize crash points; skip cleanly if Docker unavailable (project convention) |
| **Accidental deploy/cutover** | LOW (process) | Current-state repeatedly: build-only; Redis/DB activation already partial in code — do not claim CQRS complete or ship production cutover |
| **Parity / golden** | LOW for this gap | Execution lifecycle is CQRS observability; do not change battle/RNG paths |

### Out of scope (explicit)

- Production deploy, feature-flag cutover, W3 activation ops
- Possession `ClaimNpc` end-to-end completion polish beyond existing admission narrowing
- Softening golden/parity tests
- Collapsing reservationAccepted into execution success in UI

---

## 5. Gap checklist (acceptance mapping)

| ID | Requirement | Status | Evidence |
|----|-------------|--------|----------|
| S4-T1 inbox before 202 | Implemented | V34 + `CommandReserveService` / queue / immediate insertAccepted |
| S4-T1 reserved dual lifecycle | **Partial** | Admission yes; **execution correlation no** |
| S4-T2 claim/lease + poll | Implemented | `CommandInboxRepository` + `TurnRunService.claimExecutableEnvelopes` |
| S4-T2 post-commit ACK + PEL | Implemented | `RedisCommandStream` + ACK after `flushWithGeneration` |
| S4-T3 result/outbox same TX | Implemented (immediate + API lifecycle) | `JdbcFlushExecutor.commandResultUpsertMany` |
| S4-T3 outbox relay | Implemented | `CommandOutboxRelay` |
| S4-T3 reserved EXECUTION terminal | **Missing** | Gap A |
| S4-T4 crash matrix | **Missing** | Gap B |
| Independent review | `fix-required` until A+B evidenced | review doc |

---

## 6. Suggested next worker (implementation) brief

1. Design ADR-lite for dual-axis result storage (do not ship EXECUTION under current PK without migration).
2. Implement R0–R4 on this branch (or stacked PR) with focused tests: reserve → due tick → two durable lifecycle events for same requestId; ring rotated once; deny/fallback → EXECUTION_REJECTED still with ring delta.
3. Add S4-T4 parameterized IT matrix; do not mark OPENSAM-133/134/135 complete until review verdict clears.
4. No production code was changed by this research worker.

---

## 7. Key path index (quick jump)

```
infra/src/main/resources/db/migration/V34__command_inbox.sql
infra/src/main/resources/db/migration/V35__command_result_outbox.sql
infra/src/main/kotlin/opensamguk/infra/persistence/CommandInboxRepository.kt
infra/src/main/kotlin/opensamguk/infra/persistence/CommandResultRepository.kt
infra/src/main/kotlin/opensamguk/infra/persistence/JdbcFlushExecutor.kt
infra/src/main/kotlin/opensamguk/infra/persistence/ReservedTurnRepository.kt
app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/CommandReserveService.kt
app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/CommandQueueService.kt
app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/CommandTerminalResultFactory.kt
app/game-api/src/main/kotlin/opensamguk/gameapi/web/CommandController.kt
app/game-engine/src/main/kotlin/opensamguk/engine/run/TurnRunService.kt
app/game-engine/src/main/kotlin/opensamguk/engine/redis/RedisCommandStream.kt
app/game-engine/src/main/kotlin/opensamguk/engine/redis/CommandOutboxRelay.kt
app/game-engine/src/main/kotlin/opensamguk/engine/turn/TurnDaemonLifecycle.kt
app/game-engine/src/main/kotlin/opensamguk/engine/turn/ReservedTurnHandler.kt
app/game-engine/src/main/kotlin/opensamguk/engine/config/DaemonLoopConfig.kt
common/src/main/kotlin/opensamguk/common/wire/TurnDaemonCommandResult.kt
docs/superpowers/specs/2026-07-18-cqrs-consistency-failure-contract.md
docs/superpowers/plans/2026-07-18-cqrs-memory-consistency-hardening-plan.md
docs/superpowers/reviews/2026-07-22-opensam-135-durable-result-outbox-review.md
.ai/current-state.md
```
