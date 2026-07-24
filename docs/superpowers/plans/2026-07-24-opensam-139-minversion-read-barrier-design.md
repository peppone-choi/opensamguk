# OPENSAM-139 (ARCH-S5-T3 / GH #285) — committedWorldVersion + minVersion primary-read barrier: design

> Status: **DESIGN — not implemented. Minimal production code only where cited; everything else is a plan for Codex.**
> Date: 2026-07-24
> Ticket: Jira `OPENSAM-139`, GitHub `peppone-choi/opensamguk#285`, Draft ID `ARCH-S5-T3`, parent Story `ARCH-S5` / `OPENSAM-121` / `#267`.
> Dependencies (per GH #285 body): `ARCH-S4-T3` (`OPENSAM-135`/`#281`), `ARCH-S3-T2` (`OPENSAM-131`/`#277`), `ARCH-S2-T2` (`OPENSAM-127`/`#273`) — all three confirmed **merged on `main`** (`73fb13cb fix(OPENSAM-135)`, `world_version`/`writer_epoch` CAS live since V33, `WorldStateReadRepository`/scoped read repos live since V31/V32). No external gate blocks starting this ticket, unlike `S5-T1`'s `ARCH-S1-T3` wait.
> Confirms: `docs/superpowers/research/2026-07-24-next-after-138.md` (research worker, this session) independently arrived at the same target as the task's default.
> Research inputs: `docs/superpowers/plans/2026-07-18-cqrs-memory-consistency-hardening-plan.md` (§ARCH-S5-T3 GWT, source of truth for GH #285), live source of `JdbcFlushExecutor`/`TurnRunService`/`RealtimePublisher`/`CommandController`/`WorldStateReadRepository`/`WorldScopedReadRepositoryArchitectureTest`, sibling designs `docs/superpowers/plans/2026-07-23-opensam-138-bounded-boot-design.md` and `2026-07-23-opensam-137-hot-cold-prefetch-design.md` (format + non-goal conventions this doc follows).

## 0. What already exists vs. what this ticket actually closes

Tracing the write side end to end (`JdbcFlushExecutor.flush` → `worldStateUpdate` → `commandResultUpsertMany`, all one `transactionTemplate.execute` block, lines 56–339 of `infra/.../JdbcFlushExecutor.kt`) shows **`ARCH-S4-T3`'s transactional-outbox GWT is already true**: `worldStateUpdate` (step 1, line 96) does the `world_version` CAS and throws `StaleWorldWriterException` on a miss, which rolls back the *entire* transaction — `commandResultUpsertMany` (line 336, same transaction) never runs on a stale-CAS abort. `TurnRunService` already computes `committedWorldVersion = preState.worldVersion + 1` (lines 243, 365) and threads it into every `CommandResultRow`, which is durably persisted to `command_result.committed_world_version` (`V35__command_result_outbox.sql`).

**So `committedWorldVersion` is already correct and durable server-side.** What is missing, confirmed by reading every hop from write-commit to client response:

1. **The client never sees it.** `TurnDaemonEventEnvelope` (`common/wire/WireEnvelope.kt`) carries only `{requestId, sentAt, event}` — no version field. `CommandController.commandResult()` (`GET /api/command/result/{requestId}`) decodes that envelope and never surfaces `committedWorldVersion` in its JSON response, even though the DB column has held the right value since `ARCH-S4-T3` shipped.
2. **There is no `minVersion` read barrier anywhere.** No controller, filter, or interceptor in `game-api` reads a `minVersion` param. `WorldStateReadEntity` (`gameapi/read/WorldStateReadRepository.kt`) doesn't even map the `world_version` column.
3. **No logical read/write connection-pool separation inside `game-api`.** `application.yml` declares one `spring.datasource` (default Hikari pool, unsized → Spring Boot default 10) shared by every JPA read repository. A blocking bounded-wait poll loop sharing that pool risks starving ordinary request handling under concurrent RYW load.

This design closes exactly these three gaps. It does **not** touch RNG, rounding, Korean logs, or flush ordering — the CAS/outbox mechanics from `S3-T2`/`S4-T3` are reused unchanged.

## A. Wire/API contracts

### A.1 `committedWorldVersion` on the wire (write→read plumbing fix)

Add one optional field to the envelope, not to every `TurnDaemonCommandResult` subtype (avoids touching ~40 sealed classes in `TurnDaemonCommandResult.kt`):

```kotlin
// common/src/main/kotlin/opensamguk/common/wire/WireEnvelope.kt
@Serializable
data class TurnDaemonEventEnvelope(
    val requestId: String? = null,
    val sentAt: String,
    val event: TurnDaemonEvent,
    val committedWorldVersion: Long? = null,   // NEW — null = not yet committed (202-accept placeholder) or absent (pre-migration cached payload)
)
```

Backward compatible: kotlinx defaults the field to `null` on decode for any envelope JSON persisted/cached before this change (old Redis-cached `command_result:*` keys, old `command_result.result_payload` rows) — no `payload_schema_version` bump needed (that column tracks `TurnDaemonCommandResult` shape, not the envelope).

Three envelope-construction call sites get the value threaded in (`app/game-engine/.../run/TurnRunService.kt`):

- `toCommandResultRows(committedWorldVersion)` (line ~540) — already receives the value as a parameter; just add it to the `TurnDaemonEventEnvelope(...)` call.
- `toExecutionCommandResultRows(committedWorldVersion)` (line ~573) — same.
- `CommandTerminalResultFactory.acceptedRow` (`app/game-api/.../reserve/CommandTerminalResultFactory.kt`) keeps `committedWorldVersion = 0` — this is the pre-commit `202 Accepted` placeholder envelope. **Wire contract rule: `committedWorldVersion == 0` (or `null`) means "not yet committed"; clients must never pass it as `minVersion`.** Document this in the field's KDoc.

`CommandResultRepository.findResultPayload` needs **no SQL change** — `result_payload` is the same `envelopeJson` string built by `toCommandResultRows`/`toExecutionCommandResultRows`, so once the envelope carries the field, the DB fallback path carries it automatically.

`RealtimePublisher.publishCommandResult(requestId, result, sentAtIso)` currently **rebuilds its own envelope** from `(requestId, result, sentAtIso)`, silently dropping `committedWorldVersion` — this is the one real bug in the existing plumbing, not just a missing field. Fix: delete `publishCommandResult` (its only caller is `TurnRunService.publishCommandResults` line 458) and instead call the already-existing `realtimePublisher.publishCommandResultPayload(row.requestId, row.envelopeJson)` — the same primitive `CommandOutboxRelay` already uses (`redis/CommandOutboxRelay.kt:21`). This makes the direct-publish and outbox-relay-publish paths share one JSON string instead of two independently-built envelopes, which is also a latent parity risk removed for free (they could theoretically drift on `sentAt` formatting today).

`CommandController.commandResult()` surfaces the field:

```kotlin
body["committedWorldVersion"] = envelope.committedWorldVersion   // null while PENDING/ACCEPTED-only
```

### A.2 `minVersion` primary-read barrier contract

New query param `minVersion: Long?` accepted by authoritative/RYW `GET` endpoints (see §A.3 for which). Behavior:

| Condition | Response |
|---|---|
| `minVersion` absent | Existing behavior, unchanged — eventual read, no wait. |
| `minVersion` present, `world_state.world_version >= minVersion` observed on primary within the bounded wait | Normal `200 OK` response, controller logic proceeds as if `minVersion` were absent. |
| `minVersion` present, bound exceeded without visibility | `409 Conflict`, body: |

```json
{
  "status": "VERSION_NOT_VISIBLE",
  "worldId": 1,
  "requiredVersion": 4821,
  "currentVersion": 4819,
  "retryAfterMs": 150
}
```

**Why `409` and not the codebase's usual "always-200 polling" convention** (`GET /api/command/result/{requestId}` returns `200 {status:"PENDING"}`): that endpoint IS the polling channel — client-driven retry is its entire contract. `minVersion` decorates *generic* read endpoints (general/nation/city detail) whose normal 200 response shape is unrelated to lifecycle polling; a distinct non-2xx status lets a shared FE HTTP client interceptor detect and retry `VERSION_NOT_VISIBLE` uniformly across every decorated endpoint without each page's success-path parser having to special-case a `status` field it doesn't otherwise have. `retryAfterMs` gives the client a same-magnitude backoff hint as the server's own poll interval (§C.2) rather than a guess.

### A.3 Endpoint classification (authoritative/RYW vs. eventual)

Per GWT #2, classification must be a checkable, static property — not just "whatever the client happens to pass." Design: the barrier is a single reusable Spring `HandlerInterceptor` (`MinVersionReadBarrierInterceptor`), registered in a `WebMvcConfigurer` on `/api/**`, that:

1. No-ops immediately if the request has no `minVersion` param (existing eventual behavior, zero cost).
2. **Explicitly refuses to honor `minVersion` on a static eventual-endpoint denylist** — `RankingController`, `HistoryController`, `WorldLogReadRepository`-backed feed endpoints, `AdminReadController` — even if a client passes it, matching GWT #2's "precheck/read stays advisory, does not replace engine final validation" for those paths. This is the one static classification list the ticket's AC actually requires; everything else is opt-in-by-request-param, which needs no per-endpoint maintenance as new authoritative endpoints are added.
3. Otherwise runs the bounded-wait barrier (§C.2) and short-circuits with `409` on timeout.

This mirrors the existing static-list-plus-architecture-test pattern already used for world-scoping (`WorldScopedReadRepositoryArchitectureTest`'s literal `repositories` list) — same shape, new list, same enforcement style (§D.3).

## B. Touch points

| File | Change | Module |
|---|---|---|
| `common/src/main/kotlin/opensamguk/common/wire/WireEnvelope.kt` | Add `committedWorldVersion: Long? = null` to `TurnDaemonEventEnvelope`. | `common` |
| `app/game-engine/.../run/TurnRunService.kt` | Pass `committedWorldVersion` into both envelope constructions; change `publishCommandResults()` to call `publishCommandResultPayload(row.requestId, row.envelopeJson)`. | `game-engine` |
| `app/game-engine/.../redis/RealtimePublisher.kt` | Delete `publishCommandResult(requestId, result, sentAtIso)` (dead after the above); keep `publishCommandResultPayload`. | `game-engine` |
| `app/game-api/.../web/CommandController.kt` | Surface `committedWorldVersion` in the `RESOLVED` response body of `commandResult()`. | `game-api` |
| `app/game-api/.../read/WorldStateReadRepository.kt` | Add `worldVersion: Long` mapping to `WorldStateReadEntity` (`@Column(name = "world_version")`) — used by the barrier's fallback/diagnostic path only, **not** the hot poll loop (§C.2 uses raw JDBC, not this JPA entity, to avoid session/L1-cache staleness in a tight loop). | `game-api` |
| `app/game-api/.../read/WorldVersionBarrierRepository.kt` **(new)** | Raw `NamedParameterJdbcTemplate`-based `SELECT world_version FROM world_state WHERE id = :id`, bound to the new dedicated pool (§C.1). Same non-JPA, non-`Repository`-inherited-CRUD convention as `CommandResultRepository`/`WorldStateReadRawRepository`. | `game-api` |
| `app/game-api/.../read/WorldVersionReadBarrier.kt` **(new)** | The bounded-wait poll service: `awaitVisible(worldId, minVersion): BarrierResult`. | `game-api` |
| `app/game-api/.../web/MinVersionReadBarrierInterceptor.kt` **(new)** | `HandlerInterceptor` per §A.3; registered in a `WebMvcConfigurer`. | `game-api` |
| `app/game-api/.../config/ReadBarrierDataSourceConfig.kt` **(new)** | Second small Hikari pool, same JDBC URL as the primary `spring.datasource`, dedicated to the barrier's poll queries (§C.1). | `game-api` |
| `app/game-api/src/main/resources/application.yml` | Add `opensamguk.read-barrier.{max-wait-ms, poll-interval-ms, pool.max-size}` config block. | `game-api` |

No `infra`, no new Flyway migration (`world_version`/`writer_epoch` columns already exist since `V33`), no `logic` changes, no daemon write-path changes beyond the envelope field plumbing in `game-engine` (which is Redis/DB-result publishing, not a `ChangeRecorder`/`JdbcFlushExecutor` write — the one-daemon-write-rule is untouched).

## C. Transaction/ordering

### C.1 Why a second connection pool, still against the same primary

`game-api`'s single default Hikari pool (unsized → Spring Boot default `maximum-pool-size: 10`) serves every JPA read repository for ordinary request traffic. A bounded-wait poll loop (§C.2) held open for up to several hundred ms per RYW request, under concurrent load right after a busy turn tick (exactly when many clients are simultaneously polling for their own command's visibility), can exhaust that pool and stall unrelated eventual reads. The GH AC's third GWT explicitly asks for a **logical** repository/connection-pool separation while staying on the primary (no physical replica) — so: a second `HikariDataSource` bean, same `GAME_DATABASE_URL`, small fixed size (`opensamguk.read-barrier.pool.max-size`, default 4), used **only** by `WorldVersionBarrierRepository`. This bounds the barrier's worst-case connection footprint independent of normal request volume, without introducing a replica, a projector, or any new source of truth — exactly the GWT's "primary routing" requirement (§D.4 asserts this by config, not by mocking a second host).

### C.2 Poll loop and why checking one integer proves whole-transaction visibility

`WorldVersionReadBarrier.awaitVisible(worldId, minVersion)`:

```
deadline = now + maxWaitMs   (default 400ms, opensamguk.read-barrier.max-wait-ms)
loop:
  current = WorldVersionBarrierRepository.currentVersion(worldId)   // fresh SELECT, no cache
  if current >= minVersion: return Visible(current)
  if now >= deadline: return NotVisible(current, minVersion)
  sleep(pollIntervalMs)   // default 25ms, opensamguk.read-barrier.poll-interval-ms
```

Each iteration is a fresh `SELECT` on a connection from the dedicated pool — not a reused JPA `EntityManager`/persistence-context read, which could return a stale first-level-cache entity. Because `game-api`'s `application.yml` already sets `spring.jpa.open-in-view: false` (confirmed), no request-scoped transaction spans the whole HTTP request by default; the interceptor's `preHandle` barrier check and the controller's own subsequent JPA read are separate transactions/connections. So: once the barrier observes `world_version >= minVersion` via a plain read-committed `SELECT` on the primary, **every other row changed in the same `JdbcFlushExecutor.flush()` transaction is also visible** to any subsequent read on that same primary — Postgres commits atomically, and `world_version` is bumped in the exact same transaction (`worldStateUpdate`, step 1) as every other write in that flush. Checking the single counter is therefore a correct, sufficient proxy for "the whole committed delta is now readable," not a heuristic — this is the same CAS/transaction machinery `S3-T2` already built, reused for a read-side purpose it wasn't originally exposed for.

### C.3 One-daemon-write-rule — unaffected

Every change in this ticket is either (a) a value threaded through an already-existing envelope/DB write in `game-engine` (still exclusively via `ChangeRecorder`/`JdbcFlushExecutor`; the envelope/Redis publish steps touched here are the *existing* Redis wake/result-transport layer, not a new write path) or (b) purely additive read-side code in `game-api` (JPA/read-only, per the architecture's existing split). No `EntityManager` write is introduced in `game-engine`; `DaemonNoEntityManagerTest` needs no change.

## D. Test plan (GWT → test mapping, reusing existing harnesses)

| GH #285 GWT | Test | Harness reused |
|---|---|---|
| "committedWorldVersion=V / minVersion=V ... version>=V인 결과만 성공 반환" | `WorldVersionReadBarrierTest` (unit): fake `WorldVersionBarrierRepository` returning an increasing sequence — assert `Visible` only once `current >= minVersion`, assert it never returns `Visible` early. | New, pure unit (no Testcontainers needed for the loop logic itself). |
| "bounded wait 안에 안 보이면 current/required + retry hint 포함 VERSION_NOT_VISIBLE" | `MinVersionReadBarrierInterceptorIT` (Testcontainers PostgreSQL, mirroring `CommandControllerIT`'s harness): hold `world_state.world_version` fixed below `minVersion`, hit a decorated endpoint with `?minVersion=<future>`, assert `409` + exact JSON shape + `retryAfterMs` present. | `CommandControllerIT` Testcontainers setup pattern. |
| Concurrent write/read: version becomes visible mid-wait | `WorldVersionBarrierConcurrencyIT`: start the barrier poll against a fixture version, then run a real `JdbcFlushExecutor.flush()` CAS bump on a background thread mid-poll (reusing `WorldVersionCasIT`'s flush-triggering setup), assert the barrier returns `Visible` promptly after the concurrent commit rather than waiting out the full bound. | `WorldVersionCasIT` (`infra/src/test/kotlin/.../WorldVersionCasIT.kt`) CAS-triggering setup. |
| "eventual endpoint, minVersion 없으면 eventual path 사용 가능, precheck는 advisory" | `MinVersionReadBarrierInterceptorTest`: assert the interceptor no-ops (no barrier call) when `minVersion` is absent, and no-ops even when present for the denylisted controllers (§A.3). | New, `MockMvc`-level. |
| "endpoint consistency classification coverage" | `EndpointConsistencyClassificationTest`: static-source-scan test, same style as `WorldScopedReadRepositoryArchitectureTest` — assert the denylist matches an explicit annotated/registered set (fails if a new controller is added to the denylist without an explicit reason comment, and fails if the denylist and the interceptor's registered path patterns drift). | `WorldScopedReadRepositoryArchitectureTest` pattern (source-text regex, not reflection). |
| "primary routing assertion" (no replica introduced) | `ReadBarrierDataSourceConfigTest`: assert the barrier `DataSource`'s JDBC URL equals the primary `spring.datasource.url` property (same host, not a second configured host) — config-equality assertion, not a live-DB test. | New, pure unit (`@SpringBootTest` property binding only). |
| `committedWorldVersion` wire round-trip (§A.1) | Extend `CommandControllerIT`'s existing `GET /api/command/result/{requestId}` coverage: assert `committedWorldVersion` is present and correct once `RESOLVED`, and is `0`/absent while `PENDING`/`ACCEPTED`. Extend `CommandResultOutboxFlushIT` to assert the persisted `result_payload` JSON contains the field. | `CommandControllerIT`, `CommandResultOutboxFlushIT` (both already exist, already exercise this exact round-trip minus the new field). |
| Redis-direct-publish vs. outbox-relay-publish parity (the `RealtimePublisher.publishCommandResult` deletion, §A.1) | `RealtimePublisherTest`/existing engine unit coverage: assert both the direct path (`publishCommandResults` when `commandOutboxRelay` is null) and the relay path (`CommandOutboxRelay.publishPending()`) publish byte-identical JSON for the same `CommandResultRow`. | Existing `RealtimePublisher`/`CommandOutboxRelay` unit tests, extended. |

Parity gate: this ticket touches zero RNG/rounding/log-string code; `tools/parity/gate.sh backend` re-run is a regression check only (nothing in scope should move a golden), not a primary verification tool here.

## E. Acceptance criteria for build-only close + residual activation items

### Build-only close (this ticket, mergeable independently — no ticket-specific activation gate, per the GH #285 dependency line)

- [ ] `TurnDaemonEventEnvelope.committedWorldVersion` field added; all three construction call sites populate it; `0`/`null`-means-"not yet committed" documented in KDoc.
- [ ] `RealtimePublisher.publishCommandResult` deleted; `publishCommandResults()` routes through `publishCommandResultPayload` (dedupes direct/outbox-relay envelope construction).
- [ ] `CommandController.commandResult()` surfaces `committedWorldVersion` in the `RESOLVED` response.
- [ ] `WorldVersionBarrierRepository` + `WorldVersionReadBarrier` implemented, unit-tested (§D row 1).
- [ ] Dedicated Hikari pool wired, config-bound, asserted same-URL-as-primary (§D row 6).
- [ ] `MinVersionReadBarrierInterceptor` registered on `/api/**`, denylist enforced, tested (§D rows 2, 5, 6).
- [ ] All §D tests green; no golden/log/rounding/RNG file touched (grep diff for `logic/src/test/resources/golden/` — must be empty).

### Residual activation items (explicitly out of scope for this ticket's close, matching the S6 rollout story's own gating)

- **Which controllers actually opt into `minVersion`.** This ticket ships the barrier mechanism and the interceptor plumbing; it does **not** retrofit every `general`/`nation`/`city` detail page's frontend fetch call to pass `minVersion` after a command result. That is a `web/game` frontend wiring task (naturally follow-on, likely under the existing F4 action-page track in `CLAUDE.md`), tracked separately — landing it here would silently expand this ticket's diff far past "the barrier exists and is correct" into "every RYW-sensitive page now uses it," which is a frontend product decision (which pages need RYW at all) this design doc does not make.
- **Tuning `max-wait-ms`/`poll-interval-ms` against real production tick cadence.** Defaults (400ms bound, 25ms poll) are a reasonable starting point given the observed turn-tick interval, not a measured/approved number — same caveat pattern as `S5-T2`'s heap-delta threshold ("use the `S1-T3`-approved number if one exists"). No `S1-T3`-style numeric ADR exists for this specific knob; flag for the same operator-runbook process if production data suggests retuning.
- **`ARCH-S6-T3`'s read-replica ADR** (`docs/superpowers/plans/2026-07-18-cqrs-memory-consistency-hardening-plan.md` §ARCH-S6-T3) explicitly depends on this ticket (`ARCH-S5-T3`) being done and reuses its `minVersion` contract as the replica-fallback trigger ("watermark가 required version 이상이 아니면 primary로 fallback"). Not started here — this ticket only proves the primary-only contract; the replica ADR is a separate, later, human-approved GO/NO-GO.

## F. TDD implement order for Codex

Ordered so each step compiles with a green test before the next starts, one commit per task (`superpowers:subagent-driven-development`). File ownership: every step below touches at most 1–2 files, no two steps widen the same file concurrently — safe to run as a single sequential worker, not a parallel wave (this ticket's footprint doesn't justify `/parity-wave`).

1. **Red → green: envelope field.** Add `committedWorldVersion: Long? = null` to `TurnDaemonEventEnvelope` (`common`). Green: existing envelope serialization tests still pass (field is optional/defaulted); add one round-trip test asserting `null` decodes from pre-existing envelope JSON fixtures without the field.
2. **Thread the value through `TurnRunService`.** Populate `committedWorldVersion` in both `toCommandResultRows`/`toExecutionCommandResultRows`. Green: extend `CommandResultOutboxFlushIT` to assert the persisted `result_payload` JSON now contains the field with the correct value.
3. **Collapse `RealtimePublisher.publishCommandResult` into `publishCommandResultPayload`.** Delete the dead method; update `publishCommandResults()`'s one call site. Green: `RealtimePublisherTest`/`CommandOutboxRelay` tests (§D last row) prove direct and relay paths now emit identical JSON.
4. **Surface it in `CommandController`.** Add `committedWorldVersion` to the `RESOLVED` body. Green: extend `CommandControllerIT`'s result-polling test.
5. **`WorldVersionBarrierRepository` + dedicated pool.** New raw-JDBC repository + `ReadBarrierDataSourceConfig` (Hikari bean, config-bound). Green: `ReadBarrierDataSourceConfigTest` (§D row 6) + a `WorldVersionBarrierRepositoryIT` (Testcontainers, reads a seeded `world_version`).
6. **`WorldVersionReadBarrier` poll loop.** Pure logic over the repository interface (fakeable). Green: `WorldVersionReadBarrierTest` (§D row 1), including the timeout-returns-`NotVisible` case.
7. **`MinVersionReadBarrierInterceptor` + registration + denylist.** Green: `MinVersionReadBarrierInterceptorTest` (no-op cases) + `MinVersionReadBarrierInterceptorIT` (409 case, §D row 2) + `EndpointConsistencyClassificationTest` (§D row 5).
8. **Concurrency proof.** `WorldVersionBarrierConcurrencyIT` (§D row 3) — the one test that proves the whole mechanism under a real concurrent CAS-bumping flush, not mocks.
9. **Full module test run + independent review.** `:app:game-api:test`, `:app:game-engine:test`, `:common:test`, `:infra:test`; confirm zero golden/log diffs; get the repo's standard independent adversarial review before merge (no production deploy without explicit human go-ahead, per `CLAUDE.md`).
