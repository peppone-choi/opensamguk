# CQRS 정합성·장애 의미 계약

> 계약 ID: `CQRS-CF-1.0.1-draft`
> Jira: `OPENSAM-124` (`ARCH-S1-T2`)
> 계획 정본: `docs/superpowers/plans/2026-07-18-cqrs-memory-consistency-hardening-plan.md`
> 상태: **DRAFT — GA-079 focused review cleared / activation-blocked.** ADR-LITE-014 selected the GA-079 in-memory lifecycle seam and its independent focused review is cleared, but the W3 durable world-scoped activation predecessor remains open. 이 문서는 이후 wave의 구현 계약이며, read replica 생성·schema cutover·운영 배포 승인 문서가 아니다.

## 1. 사용자 가치와 적용 범위

사용자가 `202 Accepted`를 받은 명령은 Redis, API, daemon 중 하나가 재시작되어도 잃지 않아야 한다. 또한 사용자가 방금 실행한 명령의 결과와 그 뒤 화면은 "성공처럼 보이는 오래된 값" 대신, 필요한 경우 commit된 버전까지 확인한 값을 받아야 한다. 이 계약은 그 판단을 endpoint별 취향이 아니라 하나의 일관된 규칙으로 고정한다.

### In scope

- canonical 식별자, state별 source of truth, command lifecycle, read consistency class를 정의한다.
- PostgreSQL inbox/outbox, Redis wake/cache, generation-safe flush, writer fence/CAS, recovery와 관측의 **행동 계약**을 정의한다.
- `OPENSAM-43`, `OPENSAM-44`, `OPENSAM-45`가 접하는 경계와 handoff 순서를 고정한다.
- 현 구현의 알려진 단일-world/Redis 중심 동작을 baseline evidence로 기록하되, 그것을 목표 동작으로 승격하지 않는다.

### Out of scope

- multi-world registry, runtime eviction scheduler, async read projector의 구현.
- physical PostgreSQL read replica의 생성·provisioning·connection configuration. 이는 `ARCH-S6-T3`의 별도 human-approved ADR와 후속 구현 티켓만 다룬다.
- `world_id`의 물리 type, request-id HTTP transport, writer-epoch 발급 방식 등 `OPENSAM-43`/후속 설계가 아직 정하지 않은 사실의 날조.
- PHP parity 의미(RNG draw 순서, `PhpRound`, 한글 로그 byte/order, JDBC flush 순서)의 변경, daemon JPA write, 독립적인 second write authority.

## 2. 해석 규칙과 불변식

이 문서에서 **MUST**는 후속 구현·테스트가 만족해야 하는 계약이고, **MUST NOT**은 금지다. **UNKNOWN**은 확인되지 않은 사실이다. UNKNOWN을 현재 singleton 관행, `profile`, `server_id`, `scenario_code`, `world_state.id`, Redis key로 추측하여 채우면 안 된다.

1. **canonical world identity는 `world_id` 하나뿐이다.** 모든 world-owned SQL predicate/key/FK/unique key, command/result/outbox identity, Redis key/consumer identity, authorization context에 같은 `world_id`가 명시되어야 한다.
2. local entity id는 world 안에서만 의미가 있다. 장수·국가·도시·예약·message 등의 식별은 필요한 경우 `(world_id, local_id)`이며, command idempotency key는 `(world_id, request_id)`다.
3. world가 누락되거나 caller 권한과 불일치하면 API와 engine은 fail closed 한다. default world 추론, `ORDER BY id LIMIT 1` 관행, profile-name 대체는 금지다.
4. **absolute one-daemon-write rule**을 보존한다. game-engine daemon의 모든 gameplay execution/work-consumption/terminal DB write는 `ChangeRecorder → immutable generation G → JdbcFlushExecutor`의 fenced JDBC transaction 한 경로뿐이다. 이는 live/history game rows, due `general_turn`/`nation_turn` consume·rotation, inbox terminal, durable result, and transactional-outbox creation을 포함한다. daemon은 JPA `EntityManager` write를 하지 않으며, `ReservedTurnRepository`, `JdbcTemplate`, `NamedParameterJdbcTemplate` 또는 다른 repository를 직접 호출해 game/execution/terminal/result/outbox-creation row를 write해서는 안 된다. §8.2–§8.3의 좁은 API reservation-admission/maintenance transaction은 **future schedule admission만**, §5.5의 claim-admission transaction은 **pending claim plus inbox admission만** 담당하는 별도 primary boundary다. 전자는 shared ring coordinator 아래 reservation slot과 admission inbox/result/outbox를 원자적으로 바꾸고, 후자는 pending scoped claim과 inbox를 원자적으로 만든다. 어느 쪽도 due consume/rotation, live gameplay, final ownership/NPC state, execution terminal, writer fence, 또는 `world_version`을 바꿀 수 없다. 그것들은 second gameplay writer가 아니다.
5. Direct JDBC coordination has exactly these row- and operation-bounded exceptions; no other daemon direct write is permitted.
   - A dedicated primary-backed `WorldWriteFence` protocol may conditionally acquire, renew, or release a per-world writer epoch and record generation recovery control state on named fence/generation-control metadata only. It cannot change gameplay, reservation-ring, inbox terminal, result, or outbox rows, and cannot bypass transaction-start fence validation or committed-generation evidence.
   - A dedicated `InboxClaimLeaseCoordinator` may conditionally set, renew, or release only the claimant/lease fields of an **existing non-terminal** `(world_id, request_id)` inbox row before processing. It cannot create an inbox row, change payload/fingerprint, write a terminal status/result/outbox, mutate game/ring state, or justify `XACK`; the `JdbcFlushExecutor` terminal transaction rechecks/owns the claim when it writes the terminal.
   - A dedicated `OutboxDeliveryLeaseCoordinator`, run by the post-commit relay rather than a game resolver, may conditionally update only lease/attempt/published-delivery metadata of an **existing** outbox event. It cannot create an outbox intent/event id, alter payload/correlation, change a terminal/result, or mutate game/ring state; stable outbox intent/event-id creation remains inside its authoritative transaction: `G` for daemon execution terminals and the reservation-admission primary transaction for reservation outcomes.
   Physical table/column names are **UNKNOWN**, but these named row sets and operation sets are exhaustive. They are not a general-purpose direct JDBC escape hatch.
6. PHP grand truth의 RNG/round/log/side-effect/insertion order는 fencing·inbox·read routing을 추가해도 변하지 않는다. fence 검증은 parity-sensitive game SQL을 재배열하거나 resolver에 inline DB write를 추가하는 근거가 될 수 없다.
7. Redis는 command 원장도 terminal result 원장도 아니다. Redis stream은 wake/delivery hint이고, Redis result/cache/SSE는 전달 가속 수단이다.

## 3. 현재 baseline evidence와 목표의 구분

다음은 현재 코드에서 관찰한 사실이다. 이 표는 현 상태의 안전성을 주장하지 않으며, 이후 계약이 왜 필요한지를 명확히 한다.

| 관찰된 baseline | 근거 | 이 계약의 목표 |
|---|---|---|
| Snapshot loader가 `world_state ORDER BY id ASC LIMIT 1` 및 다수 unscoped live/history query로 singleton snapshot을 만든다. | `app/game-engine/.../boot/WorldSnapshotLoader.kt` | `OPENSAM-43` 이후 모든 world-owned load/query를 `world_id`로 scope하고 bounded hot/cold policy를 적용한다. |
| Immediate intake는 `CommandReserveService`에서 Redis stream publish만으로 `202`를 만들 수 있다. | `app/game-api/.../reserve/CommandReserveService.kt` | PostgreSQL inbox commit이 먼저이며, Redis wake 실패는 accepted command를 무효화하지 않는다. |
| Engine stream reader는 local cursor를 전진시키는 `XREAD` 방식이며 consumer-group ACK boundary가 없다. | `app/game-engine/.../redis/RedisCommandStream.kt` | durable inbox claim + DB commit 뒤의 consumer-group `XACK`으로 교체한다. |
| Result polling은 Redis의 request-id key만 보며 publisher TTL은 5분이다. | `RealtimePublisher.kt`, `CommandController.kt` | Redis miss/expiry 뒤에도 primary의 durable result를 조회한다. |
| `JdbcFlushExecutor`는 JDBC 한 transaction을 쓰지만 current `world_state` update에는 writer epoch/world version CAS가 없다. | `infra/.../JdbcFlushExecutor.kt` | per-world fence와 `(world_id, writer_epoch, expected world_version)` CAS를 같은 transaction에 둔다. |
| `InMemoryTurnWorld.consumeDirtyState()`는 flush payload 생성 전에 destructive drain한다. | `InMemoryTurnWorld.kt`, `TurnRunService.kt` | generation `prepare`가 immutable batch를 보존하고 성공 commit 전에는 retry 재료를 버리지 않는다. |
| Due-turn ring pull은 daemon lifecycle이 direct `ReservedTurnRepository` JDBC update로 호출하고, `TurnRunService`의 later `JdbcFlushExecutor.flush(payload)`와 같은 transaction이 아니다. | `app/game-engine/.../DaemonLoopConfig.kt:389-395` → `TurnDaemonLifecycle.kt:186-190`; `infra/.../ReservedTurnRepository.kt:113-138,302-329`; `TurnRunService.kt:300-329` | **Known blocker/gap.** Due `general_turn`/`nation_turn` consume·rotation must become an immutable `G` delta and execute in the identical fenced `JdbcFlushExecutor` transaction with the execution state/result/outbox; direct daemon pull/write is forbidden by §2. |
| API reservation admission/edit and daemon due rotation currently have no shared world-scoped ring revision/affected-row boundary, so a concurrent prepared daemon pull can overwrite a newly accepted slot. | `CommandReserveService.kt:113-131`; `CommandQueueService.kt`; `DaemonLoopConfig.kt:389-395` → `TurnDaemonLifecycle.kt:186-190` | A shared `(world_id)` ring coordinator and monotonic `reservationRevision` serialize API admission/maintenance with daemon rotation; `world_version` alone is not that coordination protocol. |
| `GeneralPossessionService.claim` validates the live local `generalId` and persists `general_owner`; `PossessionController` then publishes `ClaimNpc` and returns `200 result=true`. | `app/game-api/.../owner/GeneralPossessionService.kt:58-92`; `app/game-api/.../controller/PossessionController.kt:54-63` | The API atomically commits a **pending** world-scoped owner claim plus `ClaimNpc` inbox admission before a post-commit wake; fenced `G` atomically finalizes NPC/token ownership or releases the pending claim with its terminal result. A `200` possession success before `G` terminalization is forbidden. |
| `AdminGeneralModerationService` does not directly persist gameplay: it calls `publishImmediate` and, for several actions, one or more `reserve` calls; the controller returns `202`. | `app/game-api/.../admin/AdminGeneralModerationService.kt:17-69`; `app/game-api/.../controller/AdminWriteController.kt:53-76` | Commit one durable parent envelope with ordered child intent before its post-commit wake. Exact moderation child expansion, partial-effect, and all-or-none semantics are W3/PHP UNKNOWN; this contract must not invent them from bulk behavior. |
| PHP general/nation bulk reservation applies each child in request order, retains the already-successful prefix on the first failed child, then stops without rollback. | Whole-payload `validateArgs`: `legacy/devsam-core/hwe/sammo/API/Command/ReserveBulkCommand.php:16-32` and `.../NationCommand/ReserveBulkCommand.php:16-32`; in-loop branches: both `:42-69`; no enclosing launch transaction in `legacy/devsam-core/src/sammo/APIHelper.php:84-227`; current Kotlin mirrors it in `CommandQueueService.kt:67-135`, `CommandController.kt:214-235,372-385` | Preserve intentional ordered **maximal prefix**: before evaluating child `i + 1`, accepted child `i` MUST atomically commit its reservation/ring change, child inbox/result/outbox, `reservationRevision` advance, and parent ordered-progress record. A crash therefore preserves each committed prefix child, and retry resumes at the first unresolved child. In-loop empty-turn/unavailable-action/non-array-arg failure returns the PHP scalar indexed reason; deeper `setGeneralCommand`/`setNationCommand` failure returns structured `briefList`/`errorIdx`/`reason`. Both preserve prefix and stop later children. **GA-079 focused review cleared:** the two-install PHP artifact [`ga079-nation-bulk-php.json`](../../loops/cqrs-runtime-safety-2026-07-18/evidence/ga079-nation-bulk-php.json) proves the post-ring below-floor `killturn` write and durable old-`killturn` failure/crash boundary. ADR-LITE-014 selects `PENDING -> RING_COMMITTED -> APPLIED|NOOP|FAILED_AFTER_RING` (or `REJECTED_BEFORE_RING`) with expected `stageVersion`; Stage B owns the recorder-backed general patch and later children cannot advance first. **Activation remains BLOCKED/W3:** no API general-row write, ring-only activation, or parity-complete claim is allowed. |
| Redis intake reads up to 100 envelopes, dispatches all available outcomes, then calls one flush before publishing per-request Redis replies. Therefore one flush can contain mixed immediate allow/deny outcomes. | `app/game-engine/.../RedisCommandStream.kt:55-71`; `TurnRunService.kt:225-226,313-332` | A generation is a 0..N ordered outcome batch with per-request attribution and one generation-level CAS rule; this contract does **not** assume one command per generation. |
| Current `ChangeRecorder` coalesces dirty rows by key with last-write-wins/merged patches before flush. | `app/game-engine/.../turn/ChangeRecorder.kt:69-72,97-112,399-409` | Per-request metadata must not claim a physical final-SQL-row subset. `ARCH-S3-T1` needs a pre-collapse logical capture layer only if exact provenance is required. |
| `SelectPoolHandler` returns `ok=true` for an already-valid user pool before any recorder mutation. | `app/game-engine/.../intake/SelectPoolHandler.kt:45-46` | `APPLIED` is semantically successful but may be `stateChanged=false`; CAS/version advancement follows aggregate delta, never terminal enum alone. |

## 4. State별 source of truth

`InMemoryTurnWorld`는 healthy fenced daemon의 **uncommitted mutable interval**에서만 live authority다. 재시작·recovery·다른 process가 판단할 때는 PostgreSQL primary의 committed state가 기준이다. Redis, cache, SSE, local cursor는 아래 어느 행의 truth도 대체하지 않는다.

| 상태/사실 | Durable source of truth | Healthy daemon에서의 authority | 비권위적 사본과 금지 |
|---|---|---|---|
| `world_id`, lifecycle, committed `world_version`, active writer fence | scoped `world_state` row 또는 동등한 primary-owned world metadata | fenced daemon이 commit 전 후보 값을 보유할 수 있음 | `scenario_code`, `profile`, `server_id`, singleton numeric id는 `world_id` 대체 금지 |
| committed live gameplay rows, append-only gameplay effects | PostgreSQL primary의 scoped live/history tables | 현재 generation의 uncommitted mutation은 `InMemoryTurnWorld` + prepared delta | game-api JPA view, cache, replica, Redis는 engine final authority가 아님 |
| uncommitted dirty/created/deleted/log delta | durable truth 없음; 성공 commit 전에는 immutable prepared generation만 retry artifact | one fenced daemon의 prepared immutable generation | destructive drain 후 "메모리에 없으니 적용됨"으로 간주 금지 |
| immediate command admission과 idempotency | primary `command_inbox`의 unique `(world_id, request_id)` row | engine은 primary row를 claim/resolve할 뿐 새 truth를 만들지 않음 | Redis stream entry, request response, local queue는 admission proof가 아님 |
| reserved-turn admission/maintenance, due ring consumption, and rotation | primary reservation/ring rows + one shared `(world_id)` ring coordinator with monotonic `reservationRevision` + inbox admission outcome; due execution records its ring delta in `G` | API holds the coordinator only to atomically admit/maintain a future schedule; engine freezes its observed revision, final-validates due work, and commits exact pull/rotation in fenced `G` | Redis POKE is wake-only. `world_version` alone cannot coordinate API schedule writes with daemon rotation; daemon direct `ReservedTurnRepository` pull/write remains forbidden. |
| NPC possession ownership/claim admission | primary pending world-scoped logical `general_owner` claim keyed by `(world_id, general_id)` with user binding plus unique `(world_id, request_id)` `ClaimNpc` inbox; terminal status is durable | API may create only the pending claim and inbox atomically; engine `G` final-validates and atomically completes owner/token/NPC effects or releases/rejects the pending claim with its terminal | unscoped `general_id`/`user_id`, a `200` API response, and Redis publish are not possession completion authority. |
| immediate/reserved terminal result | primary durable result associated with `(world_id, request_id)` | engine writes it only in terminal transaction | Redis TTL result, SSE event, browser state는 cache/notification일 뿐 |
| outbox publish state and event dedupe key | primary transactional outbox row | relay reads/retries it | Redis Pub/Sub/SSE delivery success는 outbox source of truth가 아님 |
| pending work discovery | primary `command_inbox` non-terminal rows | engine polling/claim logic | Redis stream PEL/reclaim is an optimization, trim/loss cannot lose work |
| precheck and ordinary query observation | scoped PostgreSQL primary read at observation time | none; engine still final-validates mutable effects | a precheck allow is never a permission grant or durable result |

Logical names such as `command_inbox`, durable result, and transactional outbox are contract names. A later migration may choose different physical names only if it preserves their keys, atomicity, recovery queryability, and tests.

## 5. Command lifecycle and terminal meanings

### 5.1 Admission boundary

For an accepted mutation, the API MUST commit the unique inbox row before it returns `202 Accepted`.

- The inbox row contains at least the scoped identity, idempotency identity, payload/schema version, and enough request data for engine replay. Exact column names and payload encoding are **UNKNOWN** until the `OPENSAM-43` identity/wire contract is consumed.
- A retry with the same `(world_id, request_id)` returns the already-recorded admission/terminal outcome; it MUST NOT enqueue a second state effect. Same request id with a different immutable payload fingerprint MUST fail deterministically rather than overwrite the original request.
- Redis wake publication happens only after the primary admission transaction commits. Its failure may be observed and retried, but it MUST NOT turn the already-durable accepted command into an unaccepted/lost command.
- Syntax, authentication, and authorization failures that never reach durable admission are HTTP/API failures, not engine terminal outcomes, and MUST NOT be represented as `202`.

### 5.2 Precheck vs. engine authority

game-api precheck reads the last committed primary view and is **advisory**. It may save a request that is clearly unavailable, but it cannot promise execution:

- API `AVAILABLE`, `BLOCKED`, and similar precheck vocabulary are not `APPLIED`/`REJECTED` terminal results.
- The daemon re-evaluates execution constraints against the fenced live world at claim/turn time. Its allow/deny and parity reason string are final.
- Existing `PrecheckFullCrossCallSiteTest` equality requirements remain: shared rules/reason strings should agree when evaluated on equivalent input, while timing still permits a later engine rejection after a previously available precheck.

### 5.3 Immediate command terminal states

| State | Meaning | Required atomic durability |
|---|---|---|
| `APPLIED` | Engine final validation accepted the logical command (`outcomeApplied=true`). `stateChanged` separately records whether that command contributed a gameplay/system/ring delta; a valid no-op is therefore `APPLIED` with `stateChanged=false`. | Its terminal/result/outbox intent commits in `JdbcFlushExecutor`; its durable result carries that transaction's `committedWorldVersion`. |
| `REJECTED` | Engine final validation denied the logical command (`outcomeApplied=false`, `stateChanged=false`). The reported flag is not proof that no side effect escaped; the §7.1 checkpoint gate is. | Its inbox terminal, durable result, and outbox intent commit in the same generation transaction. Its durable result carries the generation transaction watermark: `V` for any non-mutating generation, or `V+1` when another aggregate mutation belongs to that transaction. |

`APPLIED`/`REJECTED` are the only immediate engine terminal values. A Redis response, handler return before flush, or API precheck is not terminal. Terminal acceptance and state mutation are intentionally separate: an `APPLIED` no-op does not advance a version merely because its terminal is `APPLIED`; a `REJECTED` never contributes its own game/system/ring/log delta. Every durable terminal result written by `G` carries `committedWorldVersion`, the generation transaction watermark: `V+1` only when the aggregate `G` contains a gameplay/system/ring delta, otherwise current `V` for a non-mutating `G`, regardless of its mix of `APPLIED` and `REJECTED` outcomes. `outcomeApplied=false` (or a legacy field named `effectApplied=false`) is reporting metadata, not sufficient no-effect proof. A `validation.observedWorldVersion=V` may additionally record the locked pre-generation validation view, but it MUST NOT replace `committedWorldVersion` or make a no-op/rejection appear to have caused `V+1`. If the locking fence/version observation cannot establish the active writer, the whole generation transaction rolls back and the world enters `RELOAD_REQUIRED` rather than recording a terminal.

### 5.4 Reserved-turn states are two separate axes

Reservation success is not execution success. A reserved command has a durable reservation outcome and, if reservation succeeds, a later independent execution outcome.

| Axis | Terminal values | Meaning |
|---|---|---|
| Reservation/admission | `RESERVATION_ACCEPTED`, `RESERVATION_REJECTED` | Accepted means the reservation/ring change, shared-ring revision advance, inbox admission, reservation result, and outbox event committed atomically. Rejected means a durable inbox/result/outbox admission terminal committed atomically and no reservation slot is created. |
| Later due-turn execution | `EXECUTION_APPLIED`, `EXECUTION_REJECTED` | The engine evaluated the reserved action at the actual turn against the fenced live state and committed or denied it. |

The UI, result API, outbox, and data model MUST retain both axes. It is incorrect to collapse `RESERVATION_ACCEPTED` into `EXECUTION_APPLIED`, or to claim `EXECUTION_REJECTED` merely because a reservation was not accepted. For a due command, the exact PHP-required ring consumption/rotation is itself a state change: both `EXECUTION_APPLIED` and `EXECUTION_REJECTED` must carry that immutable ring delta in the same fenced `JdbcFlushExecutor` transaction as execution state (if any), version CAS, execution result, and outbox.

**`CQRS-CF-U1` — scoped, nonblocking W3 UNKNOWN: accepted-slot replacement, cancellation, and expiry.** Their exact PHP-compatible business semantics, reason text, and whether a slot is consumed need W3/PHP evidence; implementation must not infer them from current direct ring writes. This is **not** an S1-T2 approval blocker because the architecture decision is fixed now: API admission/maintenance uses the bounded shared-revision primary transaction in §8.2, while a due consume/rotation uses the fenced engine `G` transaction in §8.3. Neither side may use an uncoordinated ring write, and the API boundary is not a second gameplay/execution writer. It cannot weaken the already-fixed semantics for a successfully due, non-replaced accepted slot.

### 5.5 NPC possession claim is world-scoped admission, not API completion

`general_owner` is world-owned because the current claim validates a live local `generalId`; its target logical identity is `(world_id, general_id)` with the claimed `user_id`, not a world-independent account record. The physical schema/status names remain W3 UNKNOWN, but the boundary is fixed:

1. The API acquires the scoped claim/eligibility serialization required for that general and, in **one primary transaction**, records a pending scoped `general_owner` claim plus the unique `(world_id, request_id)` `ClaimNpc` inbox admission. It must preserve the §5.1 idempotency/fingerprint rule. The pending row is neither completed possession nor an engine terminal.
2. Only after that transaction commits may it issue a Redis wake. The API returns `202 Accepted` admission, not `200 result=true` possession success; a Redis failure after commit does not invalidate the pending claim/inbox.
3. The fenced engine `G` revalidates NPC eligibility and, in its one terminal transaction, atomically completes the scoped claim, token consumption, NPC flip, durable result, and outbox on `APPLIED`. On `REJECTED`, it atomically marks the pending claim `REJECTED` or releases it, writes the durable terminal/result/outbox, and performs **no NPC flip**. This pending-claim disposition is terminal admission cleanup, not an action gameplay/system/ring/log delta: the rejected handler still passes the §7.1 world+`ChangeRecorder` zero-effect gate and has `stateChanged=false`.

The current sequence `general_owner` commit → best-effort `ClaimNpc` stream publish → `200` success is forbidden. It must atomically bind pending claim and inbox before wake, cannot report completion before `G`, and cannot let a stale/unscoped owner key decide a world claim.

## 6. Read consistency classes and routing

### 6.1 Classes

| Class | Promise | Initial route | Cache/replica rule |
|---|---|---|---|
| `AUTHORITATIVE` | The response is based on the current committed scoped primary state required by the use case. | PostgreSQL primary read pool. | Cache may not replace the required primary observation. Physical replica is not eligible in this contract. |
| `RYW` (read-your-writes) | A caller supplying `minVersion=V` must receive a state whose scoped `world_version >= V`, or an explicit not-visible result. | PostgreSQL primary read pool. | Redis/browser cache cannot satisfy the barrier. No physical replica serves RYW in W0–W5. |
| `EVENTUAL` | A boundedly stale value is acceptable to the use case and is labeled by its endpoint classification. | PostgreSQL primary initially; logical read pool remains separate from write pool. | Cache may serve it subject to scope/invalidation. A future ADR may allow a replica only for this class. |
| `NON_STATE` | The route/signal performs a pure computation, declaration-only notification, or operational observation and has no game-state command/result lifecycle. If it reads game state, its input snapshot class is stated explicitly. | In-process computation or declared notification; any state input follows its named read route. | It cannot be presented as an `APPLIED`/`REJECTED` result or use cache/SSE as game-state authority. |

`VERSION_NOT_VISIBLE` is the required semantic response for an RYW request whose bounded primary wait cannot observe `V`. It MUST include required/current version information and a retry hint; the exact HTTP status, wait budget, and response schema are **UNKNOWN** pending `ARCH-S5-T3`. Returning a stale `200` success is forbidden.

### 6.2 Primary/replica policy

1. Until a separate replica ADR reaches GO **and** a separately approved implementation ticket lands, both logical read and write pools connect to the same PostgreSQL primary. Splitting code paths/pools is allowed; creating a second authority is not.
2. `AUTHORITATIVE` and `RYW` requests route to the primary. GA-075 durable command-result lookup is `AUTHORITATIVE`: it returns the durable terminal record and has no current `minVersion` surface. A post-command **state** screen is different: this manifest designates GA-040 `GET /api/my-page` as `RYW` after an `APPLIED` result supplies `committedWorldVersion`; its future `minVersion` request must pass the primary barrier or return `VERSION_NOT_VISIBLE`.
3. An `EVENTUAL` endpoint may use a scoped cache after its classification explicitly permits it. Cache miss/failure falls back to primary and never to another world.
4. A physical replica remains ADR-only and, within this contract and `ARCH-S6-T3`, is **EVENTUAL only**. `AUTHORITATIVE` and `RYW` always read the primary; no `minVersion`-capable replica exception exists here. Any future exception requires a superseding approved plan, an explicit amendment to this contract, and separate human approval; it cannot be inferred from replica lag, a watermark, or this document.

### 6.3 Checked current endpoint and event inventory

This is the **review-time current-source manifest**, not a W4 TODO. It was derived from every indented Spring method mapping in `app/game-api`, plus every custom mapping in gateway-api/game-engine so operational routes are explicitly classified rather than silently excluded. The reproducible comparison in §6.3.5 must pass with exactly **86 game-api mappings**, **31 non-game control-plane mappings**, and the **14 currently enumerated event-source anchors**. The event portion is a checked current-path manifest, not yet a generic detector for future producer/consumer shapes.

The classifications are contract obligations; the route text after `current:` records what source does today. Therefore `AUTHORITATIVE` does not falsely claim that a current unscoped singleton read already complies with the `world_id` contract.

#### 6.3.1 Manifest legend

| Code | Meaning |
|---|---|
| `U-S` | `world_id` is **UNKNOWN**: the current route reads/writes the singleton game DB and has no canonical world binding. |
| `U-A` | `world_id` is **UNKNOWN**: authentication resolves a local user/general/nation, but no canonical world binding is present. |
| `U-P` | `world_id` is **UNKNOWN**: current Redis stream/pubsub/result key is scoped only by `opensamguk.profile`, not canonical `world_id`. |
| `P-read` | Current game-api repository/JPA/JDBC observation; target route is scoped PostgreSQL primary for `AUTHORITATIVE`/`RYW`. |
| `R-stream` | Current Redis stream or per-request Redis result/cache route; target command/result authority is PostgreSQL inbox/result/outbox. |
| `V0` | Source scan has no current `minVersion`, `worldVersion`, or `committedWorldVersion` surface. W4 may define the external version/status shape, but it may not defer classification. |
| `read-only` | No command terminal; it is an observation only. |
| `NON_STATE` | Pure read-computation, declaration-only signal, or operational observation. It is an explicit contract category, not an unclassified `Internal` escape hatch. |
| `Mutation` | A game admission/reservation/engine-mutation route. Its terminal/admission semantics are named in its row and §5/§8. |
| `N/A` | An explicitly inventoried gateway, operational health, or control-plane route outside game-state consistency; it is not a silent exclusion. |
| `advisory` | A precheck/availability response; it cannot grant engine execution authority. |
| `admission only` | Current `202`/HTTP response is not a durable engine terminal. The terminal namespace required by §5 applies after W3. |

Every live game-data endpoint/event entry must use exactly one of the four normal inventory classes: `AUTHORITATIVE`, `RYW`, `EVENTUAL`, or `Mutation`. `NON_STATE` is allowed only for the explicit pure/declaration-only allowlist GA-062 and EV-002 through EV-005 plus EV-008. `N/A` is allowed only for GA-082 and the explicitly inventoried NG/FW operational, gateway, and framework-health rows. This distinction keeps declaration-only types and health routes visible without falsely treating them as live game consistency paths.

#### 6.3.2 Game-api mappings (86)

Every row has exact method/path, source mapping anchor, current world source status, consistency class/route, terminal or advisory meaning, and version behavior.

<!-- CQRS-GAME-API-MANIFEST:BEGIN -->

| ID | Method / path | Source mapping | `world_id` status | Class / authority route | Terminal or advisory meaning | Version behavior |
|---|---|---|---|---|---|---|
| GA-001 | `GET /api/admin/game-settings` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/AdminReadController.kt:109` | `U-S` | `AUTHORITATIVE`; current `P-read` | read-only admin world setting view | `V0` |
| GA-002 | `GET /api/admin/general-moderation` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/AdminReadController.kt:145` | `U-S` | `AUTHORITATIVE`; current `P-read` | read-only admin moderation view | `V0` |
| GA-003 | `GET /api/admin/nation-stats` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/AdminReadController.kt:193` | `U-S` | `AUTHORITATIVE`; current `P-read` | read-only admin nation view | `V0` |
| GA-004 | `GET /api/admin/general-log` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/AdminReadController.kt:339` | `U-S` | `EVENTUAL`; current `P-read` | read-only historical/admin log | `V0` |
| GA-005 | `GET /api/admin/diplomacy-all` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/AdminReadController.kt:441` | `U-S` | `AUTHORITATIVE`; current `P-read` | read-only admin diplomacy view | `V0` |
| GA-006 | `POST /api/admin/server-status` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/AdminWriteController.kt:37` | `U-P` | `Mutation`; current `R-stream` to daemon | admission only; target engine final result | `V0` |
| GA-007 | `POST /api/admin/general-moderation` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/AdminWriteController.kt:53` | `U-S` | `Mutation`; current `AdminGeneralModerationService` calls `publishImmediate` and action-dependent `reserve` operations, not direct persistence; target durable parent admission → fenced engine `G` | Current `202` is only Redis/ring-path admission. Target commits one parent `(world_id, request_id)` envelope with immutable ordered child intent before its wake; engine writes correlated terminal result/outbox with the generation watermark. Exact child expansion, partial-effect, and all-or-none moderation semantics are W3/PHP UNKNOWN and MUST NOT be inferred from bulk behavior. | `V0`; no Redis-before-durable-parent admission; no invented moderation atomicity |
| GA-008 | `PATCH /api/admin/game-settings` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/AdminWriteController.kt:79` | `U-P` | `Mutation`; current `R-stream` to daemon | admission only; target engine final result | `V0` |
| GA-009 | `GET /api/auctions` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/AuctionController.kt:77` | `U-S` | `AUTHORITATIVE`; current `P-read` | read-only market observation | `V0` |
| GA-010 | `GET /api/auctions/unique` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/AuctionController.kt:112` | `U-S` | `AUTHORITATIVE`; current `P-read` | read-only market observation | `V0` |
| GA-011 | `GET /api/auctions/{id}/unique-detail` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/AuctionController.kt:150` | `U-S` | `AUTHORITATIVE`; current `P-read` | read-only market observation | `V0` |
| GA-012 | `GET /api/bettings/{bettingId}/bets` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/BettingController.kt:54` | `U-S` | `AUTHORITATIVE`; current `P-read` | read-only betting observation | `V0` |
| GA-013 | `GET /api/bettings/general/{generalId}` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/BettingController.kt:61` | `U-S` | `AUTHORITATIVE`; current `P-read` | read-only betting observation | `V0` |
| GA-014 | `GET /api/bettings` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/BettingController.kt:80` | `U-S` | `AUTHORITATIVE`; current `P-read` | read-only betting observation | `V0` |
| GA-015 | `GET /api/bettings/{bettingId}/detail` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/BettingController.kt:147` | `U-S` | `AUTHORITATIVE`; current `P-read` | read-only betting observation | `V0` |
| GA-016 | `GET /api/board` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/BoardController.kt:41` | `U-S` | `EVENTUAL`; current `P-read` | read-only public board | `V0` |
| GA-017 | `GET /api/nation/chief-reserved` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/ChiefCenterController.kt:65` | `U-A` | `AUTHORITATIVE`; current `P-read` | reservation view only; no execution terminal | `V0` |
| GA-018 | `GET /api/cities` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/CityListController.kt:36` | `U-S` | `AUTHORITATIVE`; current `P-read` | read-only live map list | `V0` |
| GA-019 | `GET /api/contacts` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/ContactController.kt:34` | `U-S` | `EVENTUAL`; current `P-read` | read-only contacts | `V0` |
| GA-020 | `GET /api/diplomacy/{nationId}` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/DiplomacyController.kt:43` | `U-S` | `AUTHORITATIVE`; current `P-read` | read-only live diplomacy | `V0` |
| GA-021 | `GET /api/diplomacy/letters` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/DiplomacyController.kt:67` | `U-S` | `AUTHORITATIVE`; current `P-read` | read-only current letters | `V0` |
| GA-022 | `GET /api/diplomacy/conflict` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/DiplomacyController.kt:165` | `U-S` | `AUTHORITATIVE`; current `P-read` | read-only live conflict map | `V0` |
| GA-023 | `POST /api/messages/{id}/accept` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/DiplomaticMessageController.kt:55` | `U-P` | `Mutation`; current reservation + `R-stream` poke | reservation admission only; later execution remains separate | `V0` |
| GA-024 | `POST /api/messages/{id}/decline` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/DiplomaticMessageController.kt:124` | `U-S` | `Mutation`; **target API admission → fenced engine G only**; current direct message invalidation is a gap | API response is admission only; engine terminal is `APPLIED`/`REJECTED` with durable result/outbox and generation `committedWorldVersion`; validation observation is optional metadata only. | `V0`; no direct API message/game write |
| GA-025 | `GET /api/front-info` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/FrontInfoController.kt:122` | `U-S` | `AUTHORITATIVE`; current `P-read` | read-only live front information | `V0` |
| GA-026 | `GET /api/nation/general-list` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/GeneralListController.kt:63` | `U-S` | `AUTHORITATIVE`; current `P-read` | read-only nation roster | `V0` |
| GA-027 | `GET /api/general-log` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/GeneralLogController.kt:46` | `U-S` | `EVENTUAL`; current `P-read` | read-only history/log | `V0` |
| GA-028 | `GET /api/generals` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/GeneralsController.kt:44` | `U-S` | `AUTHORITATIVE`; current `P-read` | read-only live general list | `V0` |
| GA-029 | `GET /api/const` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/GetConstController.kt:29` | `U-S` | `EVENTUAL`; current in-process/static read | read-only constants | `V0` |
| GA-030 | `GET /api/global-menu` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/GlobalMenuController.kt:26` | `U-S` | `EVENTUAL`; current read/config view | read-only menu | `V0` |
| GA-031 | `GET /api/history` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/HistoryController.kt:40` | `U-S` | `EVENTUAL`; current `P-read` | read-only history | `V0` |
| GA-032 | `GET /api/inherit-point` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/InheritPointController.kt:67` | `U-A` | `AUTHORITATIVE`; current `P-read` | read-only user state | `V0` |
| GA-033 | `POST /api/instant-action/{code}` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/InstantActionController.kt:75` | `U-P` | `Mutation`; current typed `R-stream` intake | admission only; target `APPLIED`/`REJECTED` | `V0` |
| GA-034 | `GET /api/mailbox/{mailbox}` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/MailboxController.kt:51` | `U-A` | `AUTHORITATIVE`; current `P-read` | read-only mailbox observation | `V0` |
| GA-035 | `GET /api/mailbox/{mailbox}/unread` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/MailboxController.kt:63` | `U-A` | `AUTHORITATIVE`; current `P-read` | read-only mailbox observation | `V0` |
| GA-036 | `GET /api/messages/{id}` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/MailboxController.kt:76` | `U-A` | `AUTHORITATIVE`; current `P-read` | read-only message observation | `V0` |
| GA-037 | `GET /api/mailbox/recent` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/MailboxController.kt:107` | `U-A` | `AUTHORITATIVE`; current `P-read` | read-only mailbox observation | `V0` |
| GA-038 | `GET /api/mailbox/old` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/MailboxController.kt:192` | `U-A` | `EVENTUAL`; current `P-read` | read-only old mailbox history | `V0` |
| GA-039 | `GET /api/map/preview` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/MapPreviewController.kt:57` | `U-S` | `EVENTUAL`; current `P-read` | read-only preview | `V0` |
| GA-040 | `GET /api/my-page` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/MyController.kt:52` | `U-A` | `RYW` target; current `P-read` | designated post-command state screen; no execution grant | `V0`; future `minVersion=committedWorldVersion` primary barrier |
| GA-041 | `GET /api/my-generals` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/MyController.kt:88` | `U-A` | `AUTHORITATIVE`; current `P-read` | read-only user live state | `V0` |
| GA-042 | `GET /api/my-cities` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/MyController.kt:152` | `U-A` | `AUTHORITATIVE`; current `P-read` | read-only user live state | `V0` |
| GA-043 | `GET /api/my-boss` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/MyController.kt:217` | `U-A` | `AUTHORITATIVE`; current `P-read` | read-only user/nation state | `V0` |
| GA-044 | `GET /api/my-nation-detail` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/MyController.kt:239` | `U-A` | `AUTHORITATIVE`; current `P-read` | read-only user/nation state | `V0` |
| GA-045 | `GET /api/nation/{id}/finance` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/NationFinanceController.kt:80` | `U-S` | `AUTHORITATIVE`; current `P-read` | read-only live nation finance | `V0` |
| GA-046 | `GET /api/nation/npc-policy` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/NpcPolicyController.kt:38` | `U-A` | `AUTHORITATIVE`; current `P-read` | read-only policy observation | `V0` |
| GA-047 | `POST /api/nation/npc-policy` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/NpcPolicyController.kt:80` | `U-P` | `Mutation`; current typed `R-stream` intake | advisory prechecks may block; `202` is admission only | `V0` |
| GA-048 | `GET /api/generals/claimable` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/PossessionController.kt:40` | `U-A` | `AUTHORITATIVE`; current account/game read seam | read-only claimable view | `V0` |
| GA-049 | `POST /api/general/claim` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/PossessionController.kt:46` | `U-A` | `Mutation`; current local `general_owner` commit → `publishImmediate(ClaimNpc)` → `200` is a gap. Target world-scoped pending owner-claim + inbox admission → fenced engine `G` completion | API atomically commits pending `(world_id, general_id, user_id)` claim plus `ClaimNpc` inbox, then wakes and returns `202` admission. `G` completes ownership/token/NPC effects on `APPLIED`, or explicitly releases/rejects the pending claim with terminal result/outbox on `REJECTED`. | `V0`; no world-independent owner key or pre-terminal possession success |
| GA-050 | `GET /api/rankings/best-generals` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/RankingController.kt:37` | `U-S` | `EVENTUAL`; current `P-read` | read-only ranking | `V0` |
| GA-051 | `GET /api/rankings/generals` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/RankingController.kt:41` | `U-S` | `EVENTUAL`; current `P-read` | read-only ranking | `V0` |
| GA-052 | `GET /api/rankings/kingdoms` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/RankingController.kt:45` | `U-S` | `EVENTUAL`; current `P-read` | read-only ranking | `V0` |
| GA-053 | `GET /api/rankings/kingdom-roster` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/RankingController.kt:53` | `U-S` | `EVENTUAL`; current `P-read` | read-only roster/ranking | `V0` |
| GA-054 | `GET /api/rankings/npcs` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/RankingController.kt:57` | `U-S` | `EVENTUAL`; current `P-read` | read-only ranking | `V0` |
| GA-055 | `GET /api/rankings/hall-of-fame` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/RankingController.kt:61` | `U-S` | `EVENTUAL`; current `P-read` | read-only history/ranking | `V0` |
| GA-056 | `GET /api/rankings/traffic` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/RankingController.kt:65` | `U-S` | `EVENTUAL`; current `P-read` | read-only activity ranking | `V0` |
| GA-057 | `GET /api/rankings/emperor` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/RankingController.kt:69` | `U-S` | `EVENTUAL`; current `P-read` | read-only historical ranking | `V0` |
| GA-058 | `GET /api/rankings/emperor/{id}` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/RankingController.kt:73` | `U-S` | `EVENTUAL`; current `P-read` | read-only historical ranking | `V0` |
| GA-059 | `GET /api/select-pool` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/SelectPoolController.kt:28` | `U-A` | `AUTHORITATIVE`; current `P-read` | read-only selection state | `V0` |
| GA-060 | `POST /api/select-pool/refresh` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/SelectPoolController.kt:46` | `U-P` | `Mutation`; current typed `R-stream` intake | `202` is admission only; target engine terminal | `V0` |
| GA-061 | `GET /api/server-basic-info` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/ServerBasicInfoController.kt:41` | `U-S` | `AUTHORITATIVE`; current `P-read` | read-only live server state | `V0` |
| GA-062 | `POST /api/simulate-battle` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/SimulatorController.kt:44` | `U-S` | `NON_STATE`; current `P-read` input + pure simulation | no state mutation or terminal; preview only; input observation is `AUTHORITATIVE` | `V0` |
| GA-063 | `GET /api/tournament` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/TournamentController.kt:63` | `U-S` | `AUTHORITATIVE`; current `P-read` | read-only current tournament state | `V0` |
| GA-064 | `POST /api/tournament/start` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/TournamentController.kt:99` | `U-P` | `Mutation`; current daemon command stream | `202` is admission only; target engine terminal | `V0` |
| GA-065 | `POST /api/tournament/reset` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/TournamentController.kt:121` | `U-P` | `Mutation`; current daemon command stream | `202` is admission only; target engine terminal | `V0` |
| GA-066 | `GET /api/troops` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/TroopController.kt:37` | `U-S` | `AUTHORITATIVE`; current `P-read` | read-only live troop state | `V0` |
| GA-067 | `GET /api/votes` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/VoteController.kt:40` | `U-S` | `EVENTUAL`; current `P-read` | read-only public vote listing | `V0` |
| GA-068 | `GET /api/votes/{id}` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/VoteController.kt:61` | `U-S` | `AUTHORITATIVE`; current `P-read` | read-only current vote detail | `V0` |
| GA-069 | `GET /api/world-log` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/WorldLogController.kt:23` | `U-S` | `EVENTUAL`; current `P-read` | read-only world history/log | `V0` |
| GA-070 | `GET /api/map` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/controller/WorldMapController.kt:50` | `U-S` | `AUTHORITATIVE`; current `P-read` | read-only live map | `V0` |
| GA-071 | `GET /sse/turn` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/sse/RealtimeRelayController.kt:29` | `U-P` | `EVENTUAL`; current Redis Pub/Sub → SSE | notification only; never a terminal/result authority | `V0` |
| GA-072 | `GET /api/commands/available` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/web/AvailableCommandsController.kt:75` | `U-A` | `AUTHORITATIVE`; current `P-read` | advisory availability/precheck; no execution grant | `V0` |
| GA-073 | `GET /api/city/{id}` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/web/CityDetailController.kt:164` | `U-S` | `AUTHORITATIVE`; current `P-read` | read-only live city state | `V0` |
| GA-074 | `POST /api/command/{code}` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/web/CommandController.kt:71` | `U-P` | `Mutation`; current reservation/typed `R-stream` intake | precheck is advisory; `202` is admission only; target immediate or reservation terminal axes | `V0` |
| GA-075 | `GET /api/command/result/{requestId}` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/web/CommandController.kt:162` | `U-P` | `AUTHORITATIVE` target; current Redis result-cache read | current `PENDING`/`RESOLVED` wrapper is not durable terminal; scoped DB result fallback is authority | `V0`; no `minVersion`, result returns terminal record |
| GA-076 | `POST /api/command/bulk` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/web/CommandController.kt:227` | `U-S` | `Mutation`; target ordered-prefix reservation admission under shared `(world_id)` ring revision; current direct ring write lacks inbox/revision coordination | Whole-payload schema validation precedes mutation, then children evaluate in order. Each accepted child atomically commits its reservation/ring change, child inbox/result/outbox, `reservationRevision` advance, and parent ordered progress before the next child is evaluated. First in-loop failure retains prefix/no later child: empty-turn/unavailable-action/non-array-arg uses scalar indexed reason; deeper `setGeneralCommand` denial uses durable structured `briefList`/`errorIdx`/reason. | `V0`; no all-or-none bulk, no semantic prevalidation of all children, no `world_version`-only coordination |
| GA-077 | `POST /api/command/push` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/web/CommandController.kt:238` | `U-S` | `Mutation`; target reservation admission/maintenance primary transaction under shared `(world_id)` ring revision; current direct ring write lacks inbox/revision coordination | Commit ring edit + inbox/result/outbox + revision advance before `202`; later due execution remains `EXECUTION_*` in `G`. | `V0`; no uncoordinated API ring write or `world_version`-only coordination |
| GA-078 | `POST /api/command/repeat` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/web/CommandController.kt:251` | `U-S` | `Mutation`; target reservation admission/maintenance primary transaction under shared `(world_id)` ring revision; current direct ring write lacks inbox/revision coordination | Commit ring edit + inbox/result/outbox + revision advance before `202`; later due execution remains `EXECUTION_*` in `G`. | `V0`; no uncoordinated API ring write or `world_version`-only coordination |
| GA-079 | `POST /api/command/nation/bulk` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/web/CommandController.kt:264` | `U-S` | `Mutation`; ring-prefix target under shared `(world_id)` ring revision; current direct ring write lacks inbox/revision coordination and Kotlin `CommandQueueService.setNationCommand` (`.../reserve/CommandQueueService.kt:236-263`) omits PHP's actor-general `killturn` refresh | Whole-payload schema validation precedes mutation, then children evaluate in order. Each accepted child atomically commits its reservation/ring change, child inbox/result/outbox, `reservationRevision` advance, and parent ordered progress before the next child is evaluated. First in-loop failure retains prefix/no later child: empty-turn/unavailable-action/non-array-arg uses scalar indexed reason; deeper `setNationCommand` denial uses durable structured `briefList`/`errorIdx`/reason. PHP capture [`ga079-nation-bulk-php.json`](../../loops/cqrs-runtime-safety-2026-07-18/evidence/ga079-nation-bulk-php.json) proves actor-general `killturn=max(env.killturn, current)` is a separate post-ring write for a below-floor user and that failure/`SIGKILL` at that boundary preserves the ring with old `killturn`; above-floor and `npc>=2` emit no general write. **Focused review cleared:** ADR-LITE-014 selects expected-`stageVersion` children `PENDING -> RING_COMMITTED -> APPLIED|NOOP|FAILED_AFTER_RING`, or `PENDING -> REJECTED_BEFORE_RING`; its focused engine seam has no durable/API activation. **Activation remains BLOCKED/W3:** no API general-row write or ring-only parity claim is allowed. | `V0`; no all-or-none bulk, no semantic prevalidation of all children, no `world_version`-only coordination; selected lifecycle is independently reviewed and remains activation-blocked on W3 |
| GA-080 | `POST /api/command/nation/push` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/web/CommandController.kt:277` | `U-S` | `Mutation`; target reservation admission/maintenance primary transaction under shared `(world_id)` ring revision; current direct ring write lacks inbox/revision coordination | Commit ring edit + inbox/result/outbox + revision advance before `202`; later due execution remains `EXECUTION_*` in `G`. | `V0`; no uncoordinated API ring write or `world_version`-only coordination |
| GA-081 | `POST /api/command/nation/repeat` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/web/CommandController.kt:294` | `U-S` | `Mutation`; target reservation admission/maintenance primary transaction under shared `(world_id)` ring revision; current direct ring write lacks inbox/revision coordination | Commit ring edit + inbox/result/outbox + revision advance before `202`; later due execution remains `EXECUTION_*` in `G`. | `V0`; no uncoordinated API ring write or `world_version`-only coordination |
| GA-082 | `GET /health` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/web/HealthCheckController.kt:30` | `N/A` | `N/A`; operational health only | no game terminal/advisory meaning | `N/A` |
| GA-083 | `GET /api/join` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/web/JoinController.kt:107` | `U-A` | `AUTHORITATIVE`; current `P-read` | read-only join eligibility | `V0` |
| GA-084 | `POST /api/join` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/web/JoinController.kt:146` | `U-P` | `Mutation`; current typed `R-stream` intake | `202` is admission only; target engine terminal | `V0` |
| GA-085 | `POST /api/internal/profile-icon-sync` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/web/ProfileIconSyncController.kt:43` | `U-P` | `Mutation`; internal typed `R-stream` intake | `202` is admission only; target engine terminal | `V0` |
| GA-086 | `GET /api/reserved-commands` | `src=app/game-api/src/main/kotlin/opensamguk/gameapi/web/ReservedCommandsController.kt:85` | `U-A` | `AUTHORITATIVE`; current `P-read` | reservation view only; no execution terminal | `V0` |

<!-- CQRS-GAME-API-MANIFEST:END -->

#### 6.3.3 Relevant event producer/consumer inventory (14 checked source anchors)

<!-- CQRS-EVENT-MANIFEST:BEGIN -->

| ID | Signal / producer → consumer | Source anchors | `world_id` status | Class / authority route | Terminal or advisory meaning | Version behavior |
|---|---|---|---|---|---|---|
| EV-001 | `TurnDaemonCommandEnvelope` Redis command stream: `CommandReserveService.publish` → `RedisCommandStream.readEnvelopes` | `evt-src=app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/CommandReserveService.kt:156`; `evt-src=app/game-engine/src/main/kotlin/opensamguk/engine/redis/RedisCommandStream.kt:67` | `U-P` | `Mutation`; current `R-stream` wake/transport | envelope is not admission or terminal authority; target inbox/cas/ACK contract applies | `V0` |
| EV-002 | declared `TurnDaemonEvent.status` | `evt-src=common/src/main/kotlin/opensamguk/common/wire/TurnDaemonEvent.kt:15` | `U-S` | `NON_STATE`; declaration only, no producer/consumer found | no current terminal meaning | `V0` |
| EV-003 | declared `TurnDaemonEvent.runStarted` | `evt-src=common/src/main/kotlin/opensamguk/common/wire/TurnDaemonEvent.kt:24` | `U-S` | `NON_STATE`; declaration only, no producer/consumer found | no current terminal meaning | `V0` |
| EV-004 | declared `TurnDaemonEvent.runCompleted` | `evt-src=common/src/main/kotlin/opensamguk/common/wire/TurnDaemonEvent.kt:33` | `U-S` | `NON_STATE`; declaration only, no producer/consumer found | no current terminal meaning | `V0` |
| EV-005 | declared `TurnDaemonEvent.runFailed` | `evt-src=common/src/main/kotlin/opensamguk/common/wire/TurnDaemonEvent.kt:42` | `U-S` | `NON_STATE`; declaration only, no producer/consumer found | no current terminal meaning | `V0` |
| EV-006 | declared `TurnDaemonEvent.commandResult`; engine publisher → result API consumer | `evt-src=common/src/main/kotlin/opensamguk/common/wire/TurnDaemonEvent.kt:51`; `evt-src=app/game-engine/src/main/kotlin/opensamguk/engine/redis/RealtimePublisher.kt:41`; `evt-src=app/game-api/src/main/kotlin/opensamguk/gameapi/web/CommandController.kt:162` | `U-P` | `AUTHORITATIVE` target; current Redis TTL cache | current wrapper is notification/cache; scoped durable result lookup, not event delivery, is authority | `V0` |
| EV-007 | declared `RealtimeEvent.turnCompleted`; engine publisher → Redis subscriber → SSE endpoint | `evt-src=common/src/main/kotlin/opensamguk/common/wire/RealtimeEvent.kt:22`; `evt-src=app/game-engine/src/main/kotlin/opensamguk/engine/redis/RealtimePublisher.kt:54`; `evt-src=app/game-api/src/main/kotlin/opensamguk/gameapi/sse/RealtimeSubscriber.kt:51`; `evt-src=app/game-api/src/main/kotlin/opensamguk/gameapi/sse/RealtimeRelayController.kt:29` | `U-P` | `EVENTUAL`; Redis Pub/Sub → SSE notification | no command terminal; clients must refresh a classified REST read | `V0` |
| EV-008 | declared `RealtimeEvent.messageCreated` | `evt-src=common/src/main/kotlin/opensamguk/common/wire/RealtimeEvent.kt:36` | `U-S` | `NON_STATE`; declaration only, no producer/consumer found | no current terminal meaning | `V0` |

<!-- CQRS-EVENT-MANIFEST:END -->

`messageCreated`, `status`, `runStarted`, `runCompleted`, and `runFailed` are included specifically because the source declares them; review must not mistake declaration-only types for live durable event paths. The command stream, command-result cache signal, and `turnCompleted` are the currently wired paths.

#### 6.3.4 Non-game/control-plane mappings (31) and framework health

These are inventory records, not unreviewed exclusions. Gateway authentication/account, deploy/admin control plane, and daemon pause/status are `N/A` to world consistency classes because they do not represent a game read/write result. They remain subject to their own security and operational controls. The game-api `/health` row is GA-082; Spring Boot's framework `/actuator/health` is included below even though it has no project mapping annotation.

<!-- CQRS-NON-GAME-MANIFEST:BEGIN -->

| ID | Method / path | Source mapping | `world_id` status | Class / authority route | Terminal or advisory meaning | Version behavior / N/A rationale |
|---|---|---|---|---|---|---|
| NG-001 | `GET /admin/turn-daemon/status` | `src=app/game-engine/src/main/kotlin/opensamguk/engine/status/StatusController.kt:65` | `N/A` | `N/A`; daemon operational control plane | no game command terminal | daemon status, not world read |
| NG-002 | `POST /admin/turn-daemon/pause` | `src=app/game-engine/src/main/kotlin/opensamguk/engine/status/StatusController.kt:100` | `N/A` | `N/A`; daemon operational control plane | control response, not command terminal | operational pause, not `minVersion` |
| NG-003 | `POST /admin/turn-daemon/resume` | `src=app/game-engine/src/main/kotlin/opensamguk/engine/status/StatusController.kt:107` | `N/A` | `N/A`; daemon operational control plane | control response, not command terminal | operational resume, not `minVersion` |
| NG-004 | `GET /admin/version` | `src=app/gateway-api/src/main/kotlin/opensamguk/gateway/controller/AdminController.kt:56` | `N/A` | `N/A`; gateway admin plane | no game terminal | deployment metadata |
| NG-005 | `GET /admin/deploy/status` | `src=app/gateway-api/src/main/kotlin/opensamguk/gateway/controller/AdminController.kt:71` | `N/A` | `N/A`; gateway admin plane | no game terminal | deployment status |
| NG-006 | `GET /admin/turn-daemon/status` | `src=app/gateway-api/src/main/kotlin/opensamguk/gateway/controller/AdminController.kt:79` | `N/A` | `N/A`; gateway proxy/control plane | no game terminal | operational proxy |
| NG-007 | `POST /admin/turn-daemon/pause` | `src=app/gateway-api/src/main/kotlin/opensamguk/gateway/controller/AdminController.kt:84` | `N/A` | `N/A`; gateway proxy/control plane | no game terminal | operational proxy |
| NG-008 | `POST /admin/turn-daemon/resume` | `src=app/gateway-api/src/main/kotlin/opensamguk/gateway/controller/AdminController.kt:89` | `N/A` | `N/A`; gateway proxy/control plane | no game terminal | operational proxy |
| NG-009 | `POST /admin/deploy` | `src=app/gateway-api/src/main/kotlin/opensamguk/gateway/controller/AdminController.kt:98` | `N/A` | `N/A`; deploy control plane | no game terminal | deployment operation |
| NG-010 | `POST /admin/servers` | `src=app/gateway-api/src/main/kotlin/opensamguk/gateway/controller/AdminController.kt:107` | `N/A` | `N/A`; server administration | no game terminal | server registry |
| NG-011 | `DELETE /admin/servers/{serverId}` | `src=app/gateway-api/src/main/kotlin/opensamguk/gateway/controller/AdminController.kt:111` | `N/A` | `N/A`; server administration | no game terminal | server registry |
| NG-012 | `POST /admin/servers/{serverId}/reset` | `src=app/gateway-api/src/main/kotlin/opensamguk/gateway/controller/AdminController.kt:115` | `N/A` | `N/A`; server administration | no game terminal | server reset operation |
| NG-013 | `GET /admin/scenarios` | `src=app/gateway-api/src/main/kotlin/opensamguk/gateway/controller/AdminController.kt:122` | `N/A` | `N/A`; scenario admin metadata | no game terminal | scenario catalog |
| NG-014 | `GET /admin/env/shared` | `src=app/gateway-api/src/main/kotlin/opensamguk/gateway/controller/AdminController.kt:126` | `N/A` | `N/A`; environment admin plane | no game terminal | configuration metadata |
| NG-015 | `PATCH /admin/env/shared` | `src=app/gateway-api/src/main/kotlin/opensamguk/gateway/controller/AdminController.kt:130` | `N/A` | `N/A`; environment admin plane | no game terminal | configuration operation |
| NG-016 | `GET /admin/env/servers/{serverId}` | `src=app/gateway-api/src/main/kotlin/opensamguk/gateway/controller/AdminController.kt:134` | `N/A` | `N/A`; environment admin plane | no game terminal | configuration metadata |
| NG-017 | `PATCH /admin/env/servers/{serverId}` | `src=app/gateway-api/src/main/kotlin/opensamguk/gateway/controller/AdminController.kt:138` | `N/A` | `N/A`; environment admin plane | no game terminal | configuration operation |
| NG-018 | `GET /admin/users` | `src=app/gateway-api/src/main/kotlin/opensamguk/gateway/controller/AdminController.kt:151` | `N/A` | `N/A`; account administration | no game terminal | account metadata |
| NG-019 | `POST /admin/system/{scope}` | `src=app/gateway-api/src/main/kotlin/opensamguk/gateway/controller/AdminController.kt:159` | `N/A` | `N/A`; system administration | no game terminal | operational system action |
| NG-020 | `POST /admin/users/scrub/{scope}` | `src=app/gateway-api/src/main/kotlin/opensamguk/gateway/controller/AdminController.kt:173` | `N/A` | `N/A`; account administration | no game terminal | account maintenance |
| NG-021 | `POST /admin/users/{id}/{action}` | `src=app/gateway-api/src/main/kotlin/opensamguk/gateway/controller/AdminController.kt:186` | `N/A` | `N/A`; account administration | no game terminal | account action |
| NG-022 | `POST /admin/ban-email` | `src=app/gateway-api/src/main/kotlin/opensamguk/gateway/controller/AdminController.kt:204` | `N/A` | `N/A`; account administration | no game terminal | account policy |
| NG-023 | `POST /auth/register` | `src=app/gateway-api/src/main/kotlin/opensamguk/gateway/controller/AuthController.kt:28` | `N/A` | `N/A`; gateway authentication | no game terminal | account creation |
| NG-024 | `POST /auth/login` | `src=app/gateway-api/src/main/kotlin/opensamguk/gateway/controller/AuthController.kt:34` | `N/A` | `N/A`; gateway authentication | no game terminal | token issuance |
| NG-025 | `POST /auth/refresh` | `src=app/gateway-api/src/main/kotlin/opensamguk/gateway/controller/AuthController.kt:40` | `N/A` | `N/A`; gateway authentication | no game terminal | token refresh |
| NG-026 | `GET /auth/me` | `src=app/gateway-api/src/main/kotlin/opensamguk/gateway/controller/AuthController.kt:46` | `N/A` | `N/A`; gateway account read | no game terminal | account identity |
| NG-027 | `POST /auth/account/password` | `src=app/gateway-api/src/main/kotlin/opensamguk/gateway/controller/AuthController.kt:52` | `N/A` | `N/A`; gateway account mutation | no game terminal | account credential change |
| NG-028 | `DELETE /auth/account` | `src=app/gateway-api/src/main/kotlin/opensamguk/gateway/controller/AuthController.kt:61` | `N/A` | `N/A`; gateway account mutation | no game terminal | account deletion |
| NG-029 | `POST /auth/account/profile-icon` (multipart) | `src=app/gateway-api/src/main/kotlin/opensamguk/gateway/controller/ProfileIconController.kt:28` | `N/A` | `N/A`; gateway account/profile plane | no game terminal | account profile upload |
| NG-030 | `POST /auth/account/profile-icon` (JSON) | `src=app/gateway-api/src/main/kotlin/opensamguk/gateway/controller/ProfileIconController.kt:46` | `N/A` | `N/A`; gateway account/profile plane | no game terminal | account profile mutation |
| NG-031 | `DELETE /auth/account/profile-icon` | `src=app/gateway-api/src/main/kotlin/opensamguk/gateway/controller/ProfileIconController.kt:52` | `N/A` | `N/A`; gateway account/profile plane | no game terminal | account profile mutation |
| FW-001 | `GET /actuator/health` | Spring Boot Actuator auto-configuration; no project `@*Mapping` source | `N/A` | `N/A`; framework health plane | no game terminal | intentionally outside source-anchor count |

<!-- CQRS-NON-GAME-MANIFEST:END -->

#### 6.3.5 Reproducible source-to-inventory comparison

Run from repository root with `zsh`. It intentionally compares **source line anchors**, so adding, deleting, or moving a mapped handler requires an explicit manifest review. It fails on omission/staleness for every mapped endpoint and for the currently enumerated event paths below; it does not claim to discover arbitrary future event producer/consumer shapes.

```zsh
set -euo pipefail
contract='docs/superpowers/specs/2026-07-18-cqrs-consistency-failure-contract.md'

source_mappings() {
  rg --with-filename -n --glob '*.kt' '^[[:space:]]+@(?:Get|Post|Put|Patch|Delete|Request)Mapping' "$@" |
    sed -E 's#^(.+):([0-9]+):.*#src=\1:\2#' |
    LC_ALL=C sort -u
}

game_manifest() {
  awk '/^<!-- CQRS-GAME-API-MANIFEST:BEGIN -->$/{inside=1; next} /^<!-- CQRS-GAME-API-MANIFEST:END -->$/{exit} inside' "$contract" |
    rg -o 'src=app/game-api/src/main/kotlin/[^`|[:space:]]+:[0-9]+' |
    LC_ALL=C sort -u
}

non_game_manifest() {
  awk '/^<!-- CQRS-NON-GAME-MANIFEST:BEGIN -->$/{inside=1; next} /^<!-- CQRS-NON-GAME-MANIFEST:END -->$/{exit} inside' "$contract" |
    rg -o 'src=app/(gateway-api|game-engine)/src/main/kotlin/[^`|[:space:]]+:[0-9]+' |
    LC_ALL=C sort -u
}

event_sources() {
  {
    rg --with-filename -n '@SerialName\("(status|runStarted|runCompleted|runFailed|commandResult|turnCompleted|messageCreated)"\)' \
      common/src/main/kotlin/opensamguk/common/wire/TurnDaemonEvent.kt \
      common/src/main/kotlin/opensamguk/common/wire/RealtimeEvent.kt
    rg --with-filename -n 'opsForStream<Any, Any>\(\)\.add' app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/CommandReserveService.kt
    rg --with-filename -n 'fun readEnvelopes' app/game-engine/src/main/kotlin/opensamguk/engine/redis/RedisCommandStream.kt
    rg --with-filename -n 'fun publish(CommandResult|TurnCompleted)' app/game-engine/src/main/kotlin/opensamguk/engine/redis/RealtimePublisher.kt
    rg --with-filename -n '@GetMapping\("/result/\{requestId\}"\)' app/game-api/src/main/kotlin/opensamguk/gameapi/web/CommandController.kt
    rg --with-filename -n 'MessageListener \{ message, _ -> relay\.fanOut' app/game-api/src/main/kotlin/opensamguk/gameapi/sse/RealtimeSubscriber.kt
    rg --with-filename -n '@GetMapping\("/turn"\)' app/game-api/src/main/kotlin/opensamguk/gameapi/sse/RealtimeRelayController.kt
  } |
    sed -E 's#^(.+):([0-9]+):.*#evt-src=\1:\2#' |
    LC_ALL=C sort -u
}

event_manifest() {
  awk '/^<!-- CQRS-EVENT-MANIFEST:BEGIN -->$/{inside=1; next} /^<!-- CQRS-EVENT-MANIFEST:END -->$/{exit} inside' "$contract" |
    rg -o 'evt-src=(app|common)/[^`;|[:space:]]+:[0-9]+' |
    LC_ALL=C sort -u
}

diff -u <(source_mappings app/game-api/src/main/kotlin) <(game_manifest)
diff -u <(source_mappings app/gateway-api/src/main/kotlin app/game-engine/src/main/kotlin) <(non_game_manifest)
diff -u <(event_sources) <(event_manifest)

test "$(source_mappings app/game-api/src/main/kotlin | wc -l | tr -d ' ')" = 86
test "$(non_game_manifest | wc -l | tr -d ' ')" = 31
test "$(event_sources | wc -l | tr -d ' ')" = 14
```

The source scan currently finds no `minVersion`, `worldVersion`, `committedWorldVersion`, `world_id`, or `worldId` reference in the game-api/engine/common/infra implementation paths inspected for this contract. That is a current-baseline fact. Only the external `minVersion` HTTP status/wait/response shape remains an `ARCH-S5-T3` W4 UNKNOWN; the endpoint class and route obligation are fixed above now.

## 7. Generation-safe flush, fencing, and recovery

### 7.1 Generation state machine

For one world, a flush generation `G` is an immutable ordered batch of **zero through N request outcomes** plus any tick/system effect. Each ordered request outcome freezes `(world_id, request_id, terminal namespace/value, outcomeApplied, stateChanged, terminal reason, validation version, durable-result intent, stable transactional-outbox intent/event id)`. `outcomeApplied` means the logical command was accepted (`APPLIED`); `stateChanged` separately says that outcome contributed a gameplay/system/ring delta, so a valid `APPLIED` no-op is `outcomeApplied=true, stateChanged=false`. This is logical outcome metadata, not a physical subset of final SQL rows: current `ChangeRecorder` key coalescing/last-write-wins may merge multiple logical effects into one aggregate row patch. `G` separately freezes that aggregate dirty/created/deleted/log delta and ordered due ring consume/rotation deltas. It preserves existing PHP-sensitive JDBC operation order.

If a later feature requires exact per-request logical effect provenance, `ARCH-S3-T1` MUST add a pre-collapse ordered logical capture layer before `ChangeRecorder` coalesces rows. It MUST NOT reconstruct provenance from final SQL row patches, split the flush into per-request transactions, or change PHP-sensitive order. Until then, `outcomeApplied`/`stateChanged`/reason/validation metadata is the only per-request effect contract.

For every resolver, final validation MUST complete **before** it mutates the in-memory world, records dirty/created/deleted/log entries, or attaches a ring delta. Each handler MUST take a per-handler pre/post checkpoint over its mutable world contribution and its `ChangeRecorder` dirty/created/deleted/log contribution. For an immediate `REJECTED`, the post-checkpoint MUST equal the pre-checkpoint and its ring contribution MUST be zero. A boolean such as `effectApplied=false` or `outcomeApplied=false` is not proof. `PREPARING` MUST verify these checkpoint claims before it freezes `G`; any violation rejects the generation from `COMMITTING`, enters the abort/reload path, and exposes no terminal/result/outbox effect. This is a logical zero-effect gate, not a demand for per-request final-SQL provenance. A due `EXECUTION_REJECTED` remains distinct: its rejected **action** has a zero-effect checkpoint, while its separately frozen, mandatory due consume/rotation delta is a scheduling effect of the generation.

These outcome and event identities are frozen at `PREPARING`: a known-rollback retry of the same `G` reuses the byte-identical batch and every result/outbox identity, and cannot regenerate, omit, or duplicate an effect. A due `general_turn` or `nation_turn` pull is not a side write: its exact ordered delta belongs to `G` and is executed by `JdbcFlushExecutor` in the same primary transaction as the corresponding execution result/outbox. `G` is not one-command-per-generation: the current `COUNT 100` intake may supply mixed `APPLIED`/`REJECTED` outcomes to the same one-flush generation.

| Transition | Required behavior |
|---|---|
| `READY → PREPARING(G)` | Capture a stable `(world_id, writer_epoch, expectedWorldVersion)`. When `G` may contain a due ring delta, acquire the same shared ring-coordinator lock as §8.2 **or** one serializable primary snapshot that binds ring rows and `reservationRevision` together; derive/freeze the ordered ring delta from that exact `(ring state, observedReservationRevision)` pair. A revision-only late read is forbidden. Then freeze the ordered 0..N outcome metadata, every result/outbox identity, aggregate system/ring delta, and logical validation evidence. Do not destructively clear source delta. |
| `PREPARING → PREPARED(G)` | Finish immutable serialization and validate that every daemon DB effect, including due ring rotation and per-request terminal metadata, is represented in `G`; enforce the immediate-`REJECTED` zero-effect checkpoint gate above. A direct repository write is an illegal transition. |
| `PREPARING`, `PREPARED`, `COMMITTING`, and `ABORTING` | From entry to `PREPARING(G)` until a confirmed commit or completed abort termination, block all new engine intake, tick, and daemon mutation for that world. API routes that would create a new **immediate/execution** command `202` fail closed during the block. The bounded §8.2 reservation admission/maintenance transaction may proceed only through the shared revision lock/CAS; it cannot enter the daemon mutable world and a resulting revision change forces a stale ring-bearing `G` to reload/reprepare. Read-only observation remains allowed. |
| `PREPARED → COMMITTING(G)` | Start one fenced `JdbcFlushExecutor` primary transaction, verify the per-world fence at transaction start, preserve the existing ordered flush, and commit every frozen per-request terminal/result/outbox intent. If `G` has a ring delta, acquire the same shared ring-coordinator lock, require `observedReservationRevision`, execute the due rotation in its reserved-turn flush slot, and advance that revision in the same transaction. The CAS branch is determined only by whether aggregate `G` has a gameplay/system/ring delta: mutating `G` performs exactly one canonical state CAS; any non-mutating `G` (including `APPLIED` no-ops) uses the locking observation branch. |
| `COMMITTING → COMMITTED(G)` | Only a confirmed transaction commit creates the durable committed-generation identity. Only now may source delta be retired and post-commit delivery proceed. |
| `PREPARING`/`PREPARED`/`COMMITTING → ABORTING(G, attempt=A)` | On a known pre-commit transaction failure, transaction cancellation, or known rollback, begin an **attempt abort** without releasing the world block or clearing immutable data. No partial state/ring/result/outbox effect may become visible. |
| `ABORTING(G, A) → ABORTED_RETRYABLE(G, A) → FLUSH_RETRY(G)` | After primary evidence proves attempt `A` did not commit, record/observe that attempt abort and preserve the exact immutable batch and frozen terminal/outbox identities. `ABORTED_RETRYABLE` is terminal for attempt `A`, **not** terminal for generation `G`; `FLUSH_RETRY` remains blocked and is not `READY`. |
| `FLUSH_RETRY(G) → PREPARED(G, attempt=A+1) → COMMITTING(G, A+1)` | This is the only legal same-generation retry. Before re-entry, primary evidence must show `G` is not committed, the committed scoped `world_version` still equals frozen `expectedWorldVersion`, the daemon still holds the frozen/current writer fence, and, for a ring-bearing `G`, `reservationRevision` still equals `observedReservationRevision`; otherwise transition to `RELOAD_REQUIRED`. The retry reuses the byte-identical immutable batch, inbox-terminal intent, durable-result intent, and stable outbox event id; it does not re-run resolver/RNG work, regenerate an identity, or admit/tick new work. `COMMITTING(G, A+1)` re-validates the fence and required ring revision at transaction start. |
| `ABORTING`/`COMMITTING → RELOAD_REQUIRED` | On ambiguous commit, stale fence/CAS loss, process restart, or any condition where local memory cannot prove DB outcome, keep the block, make readiness fail, inspect scoped durable generation/version/inbox/result/outbox evidence, and reload before resuming. |
| `COMMITTED(G) → READY` | Complete only post-commit bookkeeping after the durable commit identity is verified; then release the world for a new generation. An aborted attempt reaches `READY` only through the retry/reload path that proves a fresh authoritative baseline. |

An implementation must make committed-generation identity and per-attempt abort identity discoverable from primary-backed control metadata so recovery can distinguish `G committed`, known `G attempt A aborted`, and `G outcome unknown`. The physical field/table and exact non-terminal state names are **UNKNOWN**, but a local boolean or Redis marker is insufficient. `commit(G)` is idempotent only when durable evidence already says that exact `(world_id, G)` committed, in which case it returns the recorded outcome and emits no second SQL/outbox effect; otherwise a second commit outside `COMMITTING(G, A)` is an illegal transition. `abortAttempt(G, A)` is idempotent only when primary evidence already says that exact attempt rolled back/no-commit, in which case it returns the same attempt-abort outcome; it leaves `G` retryable only through the explicit `FLUSH_RETRY → PREPARED(G, A+1)` transition. It may not erase, compensate, or re-open a committed `G`. Concurrent commit/attempt-abort requests serialize through the fenced generation identity, and an unresolved race is `RELOAD_REQUIRED`.

### 7.2 Writer epoch and world-version CAS

- `writer_epoch` is a durable per-world fence token. A daemon may write only while it holds the current token. Its issuance/lease/lock acquisition protocol is **UNKNOWN** and belongs to the `ARCH-S3-T2` design, but it must prevent an old daemon from becoming valid again after a newer epoch exists.
- `world_version` is monotonic per world. It is a **generation** value, not a per-request counter: if the aggregate `G` contains any gameplay, system, or due-ring delta, that entire generation advances it exactly once according to the scoped world-state contract. No cache, stream id, or global version substitutes for it. A due ring consume/rotation is a state delta, so a generation containing it advances even when the associated terminal is `EXECUTION_REJECTED`. A valid `APPLIED` no-op does not by itself advance it.
- **0..N outcome metadata and result versions:** every durable terminal result written by `G` carries its generation transaction watermark `committedWorldVersion`. For any mutating `G`, that is the one CAS value `V+1`, including a terminal whose own `stateChanged=false`; `V+1` reports atomic transaction visibility, not state causation by that outcome. For any non-mutating `G`, including an all-no-op `APPLIED` batch or a mixed `APPLIED`/`REJECTED` batch, `committedWorldVersion=V` because no CAS advances the world. `validation.observedWorldVersion=V` may record the locked pre-generation validation view, but does not replace the watermark. A due `EXECUTION_APPLIED` or `EXECUTION_REJECTED` has required logical ring metadata; its `committedWorldVersion` means the aggregate generation committed ring progression, not that `EXECUTION_REJECTED` applied its logical action. Reservation-admission results occur outside `G` and do not borrow a `G` watermark.
- **Non-mutating versus mutating generation:** the CAS decision MUST NOT inspect terminal enum/count. A non-mutating `G` has no aggregate gameplay/system/ring delta, regardless of whether it contains `APPLIED`, `REJECTED`, or both; it writes terminals/results/outbox without a `world_state UPDATE` or CAS and holds the locking observation through commit. Any aggregate gameplay/system/ring delta makes `G` mutating and uses the one generation-level CAS. An immediate `REJECTED` has `stateChanged=false` and must pass the §7.1 zero-effect gate even inside a mutating batch.
- At transaction start, the implementation validates the world fence. For a state-changing generation, at the existing canonical `world_state` flush step it performs an affected-row-checked CAS equivalent to:

  ```sql
  UPDATE world_state
     SET world_version = :next_version, ...
   WHERE world_id = :world_id
     AND writer_epoch = :writer_epoch
     AND world_version = :expected_world_version;
  ```

  Exactly one row MUST be updated. `0` rows means stale epoch or version mismatch: the whole transaction rolls back, `G` does not commit, and the engine enters `RELOAD_REQUIRED`. No earlier SQL in that transaction may survive. For a non-mutating `G`, the required observation is **not** a plain `SELECT`: it MUST use the same row-lock/serialization protocol as writer-epoch acquisition, for example an affected-row-checked scoped `SELECT world_version FROM world_state WHERE world_id = :world_id AND writer_epoch = :writer_epoch FOR UPDATE`. It MUST produce exactly one locked row and hold that lock through inbox/result/outbox terminal commit. Zero rows, lock/fence acquisition conflict, or inability to retain the lock rolls back and enters `RELOAD_REQUIRED`. This excludes a stale writer from terminalizing any no-delta outcome without advancing the version.
- For a ring-bearing `G`, that same transaction MUST also hold the shared `(world_id)` ring coordinator lock and conditionally advance `reservationRevision` from the frozen `observedReservationRevision` while it applies the ordered due consume/rotation delta. An equivalent affected-row predicate is `WHERE world_id = :world_id AND reservation_revision = :observed_reservation_revision`; exactly one row must advance to the next monotonic revision. A zero-row/revision mismatch proves no commit, rolls the whole transaction back, and requires reload/reprepare before retry. It MUST NOT overwrite a reservation accepted by the API after `G` prepared. `world_version` CAS is necessary for gameplay fencing but is not a substitute for this shared ring coordination predicate.
- Fence checks, state CAS, and ring revision checks are structural safety operations, not a license to reorder parity-sensitive game SQL. The world-state CAS occupies the canonical world-state stage in the existing ordered JDBC flush; due ring rotation and its revision advance occupy the established reserved-turn flush slot in that same transaction.

### 7.3 Exact Redis ACK boundary

The target Redis consumer uses a consumer group and a durable inbox claim. For stream delivery `D`, the only legal order is:

1. read `D` as a wake hint and locate/claim the scoped inbox row; a pre-processing claim may use only the bounded non-terminal lease update in §2.5 and is neither admission, terminalization, nor ACK authority;
2. final-validate and prepare/commit one `JdbcFlushExecutor` primary transaction for the ordered 0..N outcomes: it writes every frozen inbox terminal/result/outbox intent; it performs exactly one CAS if any aggregate gameplay/system/ring delta exists, or the locking non-mutating observation if none exists; an immediate `REJECTED` passes the zero-effect checkpoint even when another outcome causes the single CAS; due execution includes its exact ring rotation and shared-revision attribution;
3. receive confirmed primary DB commit;
4. only then `XACK D`.

Outbox publish, SSE fan-out, Redis result-cache set, and HTTP polling are **after** the DB commit boundary and do not delay terminal durability. If the engine crashes after step 3 but before step 4, reclaim observes the durable terminal result and ACKs without applying the state effect again. Current local cursor advance is not an acceptable ACK substitute.

## 8. Durable inbox/outbox and idempotency rules

### 8.1 Immediate command transaction

For each immediate command outcome in a 0..N `JdbcFlushExecutor` generation, the attribution is one of these mutually exclusive forms:

| Terminal | Atomic contents | Forbidden contents |
|---|---|---|
| `APPLIED` | `outcomeApplied=true` plus explicit `stateChanged=true|false`; inbox terminal; durable result with the generation `committedWorldVersion`; transactional outbox event with stable event id | a second terminal/state effect/outbox for the same `(world_id, request_id)` or treating a no-op as a state change |
| `REJECTED` | `outcomeApplied=false`, `stateChanged=false`, successful §7.1 pre/post zero-effect checkpoint; inbox terminal; durable result with generation `committedWorldVersion` (`V` non-mutating, `V+1` only when another aggregate mutation exists) and optional `validation.observedWorldVersion=V`; transactional outbox event with stable event id | a per-request physical SQL-row subset, game/system/ring/log delta from the rejected handler, or a per-request CAS/version advance |

The generation commits exactly one CAS only if its aggregate contains a gameplay/system/ring delta. A non-mutating `G`—whether it contains only `REJECTED`, valid `APPLIED` no-ops, or both—performs no CAS and instead holds the §7.2 locking observation through terminal commit. On rollback, none of any outcome's effects is visible. A terminal is at most once per `(world_id, request_id)`; an `APPLIED` state effect is at most once only when `stateChanged=true`, even when API retries, Redis duplicates, PEL reclaim, or engine restart occur. An outbox event may be delivered more than once; consumers deduplicate by its stable event id, while the outbox row remains the source of truth.

### 8.2 Reserved command transaction boundaries

Reservation admission/maintenance is a separate, bounded primary API transaction. It is the only API path allowed to change a **future** reservation/ring schedule; it is not due execution and never changes `world_version`.

| Admission terminal | Atomic primary contents | HTTP meaning after commit | Must not happen |
|---|---|---|---|
| `RESERVATION_ACCEPTED` | same locked `(world_id)` ring coordinator at expected `reservationRevision`; unique inbox admission row; complete reservation/ring change; durable reservation result; transactional outbox event with stable event id; affected-row-checked monotonic revision advance | Return `202 Accepted` with request id only after this transaction commits; Redis wake follows commit. | It must not claim `EXECUTION_APPLIED`, publish a wake before durability, or update the ring without the shared revision predicate. |
| `RESERVATION_REJECTED` | unique inbox terminal row; durable reservation-rejection result; transactional outbox event with stable event id; no reservation/ring change and no revision advance | Return a **non-`202` terminal reservation-rejection response** only after this transaction commits. It represents durable `RESERVATION_REJECTED`, not an advisory precheck; the same `(world_id, request_id)` retry resolves to the recorded terminal outcome. Exact numeric HTTP status, body, and request-id transport are W3 UNKNOWNs. | No reservation/ring slot, no Redis wake, no execution terminal, and no game-state/world-version mutation. |

The API transaction obtains a single primary-backed shared ring-coordinator lock for `world_id`, reads its monotonic `reservationRevision=R`, validates its expected `R`, and uses an affected-row check equivalent to `WHERE world_id = :world_id AND reservation_revision = :R` while it writes the reservation mutation and advances the revision. `0` affected rows or a stale expected revision rolls back the attempted durability scope; it reloads and revalidates before a later attempt and MUST NOT overwrite a newer accepted reservation. A revision conflict is a known no-commit coordination failure, not permission to manufacture a stale reservation terminal. Physical coordinator table/column names are W3 UNKNOWN, but this lock/expected-revision/affected-row behavior is fixed. The table above applies to one independently admitted reservation child; §8.2.2 defines the PHP-required bulk aggregate exception.

#### 8.2.1 GA-007 moderation parent admission

GA-007 currently expands one request through a mix of `publishImmediate` and action-dependent `reserve` calls. Its target admission boundary is one durable parent `(world_id, request_id)` envelope containing immutable ordered child intent before its post-commit wake. This prevents Redis-before-durable-parent loss. It does **not** assert that moderation child effects, child reservations, or later terminals are all-or-none: those exact PHP/owner semantics are W3 UNKNOWN and require evidence before implementation. A parent correlation/idempotency record is not a license to copy the bulk all-or-none or prefix rule.

#### 8.2.2 PHP bulk reservation: ordered maximal prefix

GA-076 and GA-079 are a parity exception to generic composite intuition. First run the PHP-equivalent whole-payload `validateArgs` transport/schema gate (required action/turn list, nonempty action, integer turn-list shape) before any mutation; this MUST NOT perform speculative business/semantic validation of every child. Then evaluate children strictly in their original order. On any in-loop failure at index `k`, preserve the successful prefix `< k` and record no later child `> k`. The response/summary shape is branch-specific: empty turn list, unavailable action, or non-array `arg` returns the PHP scalar indexed reason; only a deeper `setGeneralCommand`/`setNationCommand` failure records the structured partial `result=false, briefList, errorIdx=k, reason`. Both shapes must be durably correlated with the same prefix before the parity-compatible response is returned.

Each accepted child MUST have its own primary durability boundary. Before evaluating child `i + 1`, that boundary atomically commits child `i`'s reservation/ring change, child inbox/result/outbox, `reservationRevision` advance, and parent ordered child-progress record. No primary transaction may span two accepted children. A DB or infrastructure failure inside child `i` rolls back only that uncommitted child; every previously committed prefix child remains durable. At an in-loop failure at `k`, record the branch-specific durable scalar or structured parent outcome/progress without a rejected-child ring mutation, then stop without evaluating `> k`. The parent correlation/idempotency aggregate makes a same-parent retry resume at the first unresolved child without duplicate/reorder/skip and never evaluate a later child before that child resolves. The parent is **not** one `RESERVATION_REJECTED`, because its prefix has legitimately changed the ring. All-or-none bulk admission, rollback of earlier accepted children on a later failure, and semantic prevalidation of all children are forbidden.

GA-079 has a nation-only parity blocker outside that ring boundary. After each successful PHP `_setNationCommand`, the actor general when `npc < 2` receives and persists `killturn=max(env.killturn, current)` (`legacy/devsam-core/hwe/func_command.php:483-488`; `legacy/devsam-core/hwe/sammo/LazyVarUpdater.php:55-65`). This is a live general-meta mutation, and current Kotlin `CommandQueueService.setNationCommand` (`.../reserve/CommandQueueService.kt:236-263`) omits it. The two independent PHP/MariaDB installs captured in [`ga079-nation-bulk-php.json`](../../loops/cqrs-runtime-safety-2026-07-18/evidence/ga079-nation-bulk-php.json) now prove the missing order: for a user below the floor, `UPDATE nation_turn` succeeds before `UPDATE general` (`killturn` `93 -> 100`); an actor-scoped `BEFORE UPDATE general` failure and an acknowledged post-ring `SIGKILL` both leave the ring durable with `killturn=93`. The above-floor user and `npc>=2` branches emit no `general` update. ADR-LITE-014 now selects the daemon-owned model `PENDING -> RING_COMMITTED -> APPLIED|NOOP|FAILED_AFTER_RING`, or `PENDING -> REJECTED_BEFORE_RING`, using expected `stageVersion`; Stage B owns the recorder-backed general patch and an unresolved child blocks later children. The focused seam and architecture guard are **independently reviewed / cleared**, but are not durable activation. **Activation remains BLOCKED/W3:** `OPENSAM-43` must first define canonical `world_id`, then W3 must bind the same stages/version checks to durable CAS and the fenced flush path. No API general-row write, ring-only implementation, or parity-complete claim is allowed. The GA-076 rule is not permission to silently omit the nation effect.

Syntax/authentication/authorization failures that never enter reservation admission remain ordinary non-accepted HTTP failures under §5.1 and create none of these rows. A future due-turn execution for an accepted reservation records `EXECUTION_APPLIED`/`EXECUTION_REJECTED` in an immutable `G`; the same fenced `JdbcFlushExecutor` transaction includes the exact due ring consume/rotation, execution state effect if any, version CAS, execution durable result, and execution outbox event. The current direct daemon `ReservedTurnRepository.pull*` path is therefore a blocker, not an allowed implementation of this rule.

### 8.3 Shared reservation-ring coordination with due `G`

For every `G` that contains a due reservation consume/rotation delta, `PREPARING` acquires the same shared coordinator lock as §8.2 **or** a serializable primary snapshot that atomically reads the relevant ring rows with `reservationRevision=R`; it derives and freezes the due delta from that exact pair. Reading a revision after an independently loaded ring, or freezing only `R` without the ring state it protects, is forbidden. At `COMMITTING`, the fenced `JdbcFlushExecutor` transaction acquires the **same** `(world_id)` coordinator lock used by §8.2, verifies the affected-row predicate at `R`, applies the ordered due ring delta, and advances it monotonically to the next revision in that transaction. It also performs the ordinary `world_version` CAS when the aggregate `G` is mutating. These are complementary predicates: `world_version` protects world execution; `reservationRevision` prevents the API and daemon from overwriting each other's ring changes.

If the daemon sees a revision mismatch, the transaction rolls back with known no commit, preserves the accepted API reservation, and enters `RELOAD_REQUIRED` to reload/reprepare from the new ring state. It MUST NOT replay the stale prepared rotation, overwrite the accepted slot, or use a same-`G` retry unless §7.1 proves the frozen revision is still current. A non-ring `G` does not acquire or advance the ring coordinator merely because it writes a terminal.

### 8.4 Redis rules

- Redis down, stream trim, duplicate delivery, PEL loss, cache eviction, and result TTL expiry cannot decide whether a command was accepted or applied.
- The engine polls primary non-terminal inbox rows and reclaims pending wakes. Redis only reduces latency.
- A durable result retention/archival policy must preserve lookup long enough for the product contract; exact retention duration is **UNKNOWN**. The current five-minute Redis TTL cannot serve as that policy.

## 9. Failure and recovery matrix

| Failure point | What is durable / authoritative | Required recovery | Forbidden outcome |
|---|---|---|---|
| API fails before inbox commit | No accepted command exists, **except** a bulk parent may already have an earlier durable child prefix as defined below. | Client may retry the same request id; no engine effect is expected for the failed standalone scope. | Returning `202`, waking Redis, or treating an in-memory request as accepted. |
| Primary DB admission transaction rolls back/fails | No accepted inbox/reservation transaction exists. | Return non-accepted failure; retry is safe. | `202` before primary commit. |
| API/process or response path fails after inbox commit, before/after Redis wake | Inbox (and reservation if applicable) is durable. | Same request id resolves to recorded admission/terminal outcome; poller/outbox retry wakes engine. | Duplicate inbox insert or accepted-but-lost command. |
| Redis is unavailable after inbox commit | Primary inbox remains authority. | Return/retain accepted outcome, record wake failure, durable polling finds it later. | Rolling back a committed inbox only because Redis wake failed. |
| Engine crashes before claim or before generation prepare | Inbox remains non-terminal; reservation remains durable if accepted. | New fenced engine polls/reclaims, reloads scoped world, then final-validates. | Treating stream cursor position as completion. |
| Engine crashes after prepare but before DB commit | No new committed world state exists; local prepared memory may be lost. | Reload primary baseline and replay from durable inbox/reservation. | Assuming prepared/in-memory effects committed. |
| DB failure with known rollback/no commit during flush | Immutable `G` remains retry material; DB remains last committed version. | Enter `FLUSH_RETRY`, block world work, and retry the same `G` only when §7.1 also proves its frozen ring revision is unchanged; otherwise reload/reprepare. Do not ACK wake. | Clearing delta, accepting/ticking new mutable work, issuing terminal result, or replaying a stale ring rotation. |
| Connection loss/timeout where commit outcome is ambiguous | Local process cannot know whether `G` committed. | Enter `RELOAD_REQUIRED`; inspect scoped generation/version/inbox/result/outbox evidence, reload, then either observe committed result or safely reprocess non-committed work. | Blind retry or blind rollback assumption. |
| Stale writer epoch or CAS affected rows = 0 | Transaction rolls back; current writer/version is elsewhere. | `RELOAD_REQUIRED`, readiness false, reacquire/validate a current fence only through the later protocol. | Continuing as writer or partially retaining SQL effects. |
| API reservation admission/maintenance sees stale `reservationRevision` or zero affected row | The attempted durability scope committed no new inbox/ring/result/outbox change; any prior accepted schedule or already durable bulk prefix remains authoritative. | Roll back, reload/revalidate the scoped ring, then make a later admission decision/retry. | `202` for the failed scope, an unrecorded child result, or overwriting a newer accepted reservation. |
| Prepared due `G` sees shared-ring revision mismatch | The daemon `G` did not commit; an intervening accepted API schedule change is durable. | Roll back, keep readiness false, `RELOAD_REQUIRED`, reload/reprepare from the new ring revision. | Replaying the stale rotation, same-`G` retry without revision proof, or ACKing the wake. |
| Possession API/process crashes after pending `general_owner` + `ClaimNpc` inbox commit but before wake/response | The same-request scoped pending claim and inbox are durable; no final NPC/token ownership completion exists until `G`. | Same request id resumes/polls the recorded admission; durable polling wakes engine after a post-commit Redis/response failure. | A second pending claim, a lost accepted claim, or pre-terminal `200` possession success. |
| `ClaimNpc` is terminally `REJECTED` after pending admission | Pending claim/inbox are authoritative until the one `G` terminal transaction explicitly records rejection or releases the claim; no NPC flip is durable. | Persist the terminal disposition/result/outbox atomically and allow a later eligible claim under its defined idempotency rules. | Leaving a permanently blocking pending claim, NPC flip/token completion, or treating cleanup as an action state change. |
| GA-007 moderation parent admission fails before its durable envelope commits | No parent admission exists. | Roll back the parent envelope and return non-accepted failure; retry the same parent request id safely. | Redis wake/`202` without the durable parent envelope. Exact child expansion semantics remain W3 UNKNOWN. |
| PHP bulk reaches first in-loop failure at child `k` | Accepted prefix `< k` and its ring/outcomes are authoritative; no child `> k` exists. Empty-turn/unavailable-action/non-array-arg uses a durable scalar indexed failure; deeper `set*Command` denial uses durable structured `briefList`/`errorIdx=k`/reason. | Return/retry the same branch-specific aggregate idempotently without rolling back prefix; infrastructure rollback of an uncommitted attempt may retry from durable baseline. | All-or-none rollback, validation/application of a later child, or reporting either scalar/structured failure without its durable prefix/correlation. |
| API/process/response crashes between bulk children | Durable prefix children `< k` and their ordered parent progress are authoritative; `k` is either the first unresolved child or has its already-committed branch-specific terminal outcome. | Same parent request id resumes only at the first unresolved child, or returns the recorded terminal; it must not duplicate, reorder, skip, or evaluate `> k` before `k` resolves. | Treating generic pre-inbox failure as permission to erase/duplicate a durable prefix, or returning a conflicting bulk response. |
| A handler reports immediate `REJECTED` but its §7.1 checkpoint changed | No terminal/result/outbox or aggregate flush may commit from that invalid generation. | Abort/reload and surface a zero-effect-gate diagnostic; fix/replay only from durable baseline. | Persisting a `REJECTED` whose handler changed world, recorder, log, or ring state. |
| Engine crashes after DB commit but before `XACK` | Terminal/result/outbox/CAS are durable; stream delivery may be pending. | Reclaim checks durable terminal and ACKs without mutation. | Applying state a second time. |
| Redis duplicate delivery, trim, or PEL reclaim | Inbox/result state remains primary authority. | Claim/poll by `(world_id, request_id)` and terminal idempotency; ACK only after durable confirmation. | Dropping accepted work because no stream record remains. |
| Outbox publisher/SSE/cache fails after DB commit | Outbox/result remain durable. | Retry outbox delivery; result API falls back to DB. | Reversing committed state or reporting result as absent solely due to Redis/SSE. |
| Client retries an already accepted request | Unique inbox identity identifies the original. | Return the original admission/terminal result. | A second reservation, state effect, or outbox identity. |

During `FLUSH_RETRY` and `RELOAD_REQUIRED`, readiness MUST be false and new engine intake/tick/mutation MUST remain blocked for that world. The bounded §8.2 API reservation-admission/maintenance transaction may still proceed only under the shared revision lock/CAS; it cannot revive, mutate, or make ready the blocked `G`, and any changed revision requires that `G` reload/reprepare. Recovery completion requires a scoped primary check of writer fence, committed generation/version, relevant inbox/result state, ring revision where applicable, and a fresh in-memory load; a liveness-only restart is not sufficient.

## 10. Observability contract

The following signals are required for `ARCH-S1-T3`/later waves. `world_id`, user id, and request id MUST NOT be Micrometer metric labels because of cardinality. They may appear in bounded, access-controlled structured logs/traces and in readiness diagnostics where appropriate.

| Signal | Required interpretation |
|---|---|
| world readiness/recovery state | Count and current state of `READY`, `FLUSH_RETRY`, `RELOAD_REQUIRED`; readiness must expose blocked worlds without leaking payloads. |
| flush generation | prepare/commit/abort/retry counts, duration, oldest prepared age, and ambiguous-commit count. |
| fence/CAS | writer-fence validation failures, CAS zero-row failures, stale-writer rejections, and reloads caused by them. |
| reservation ring coordination | API and daemon `reservationRevision` lock/CAS conflicts, reload/reprepare count, coordinator lock duration, and accepted-slot overwrite violations (must remain zero). |
| terminal zero-effect gate | immediate `REJECTED` pre/post checkpoint failures, `PREPARING` aborts caused by them, and valid `APPLIED stateChanged=false` no-op counts. |
| inbox | accepted/non-terminal/terminal counts, oldest pending age, idempotency duplicate count, claim/reclaim/replay count. |
| outbox/result | unpublished outbox count/oldest age/retry/dedupe count; durable-result fallback count after Redis miss/expiry. |
| Redis transport | wake publish failures, consumer PEL/reclaim count, duplicate delivery count, and a post-commit ACK invariant violation counter (must remain zero). |
| read consistency | current committed/read version lag, `minVersion` wait/timeout/`VERSION_NOT_VISIBLE` count, primary-routing assertion failures. |
| capacity coupling | after-GC heap, hot/cold loaded counts, boot/tick/flush latency, as specified by `ARCH-S1-T3`; thresholds are not invented in this contract. |

## 11. Dependencies and handoff boundaries

| Dependency | This contract consumes / requires | Boundary it enforces |
|---|---|---|
| `OPENSAM-43` | canonical `world_id` type, payload/version identity contract | `CQRS-CF-1.0.1-draft` uses only symbolic `world_id`; W1 schema/API/Redis implementation is blocked until this is Done. |
| `OPENSAM-44` | v2 entity `ChangeRecorder → JdbcFlushExecutor` persistence extension | It must not co-modify the shared flush foundation while `ARCH-S2-T3 → ARCH-S3-T1 → ARCH-S3-T2` owns scope, generation, fence, and CAS. It consumes the documented handoff afterward. |
| `OPENSAM-45` | UI/SSE lifecycle and query invalidation | It consumes durable reservation/execution/result/outbox vocabulary. It must not create a second result authority or collapse reservation acceptance into execution success. |

The source-level implementation handoff for shared flush changes is sequential, not parallel: scoped flush (`S2-T3`) → immutable generation (`S3-T1`) → fence/CAS (`S3-T2`) → recovery (`S3-T3`).

## 12. GWT acceptance criteria for OPENSAM-124

1. **Identity** — Given two worlds with the same local entity/request id, when a reader reviews this contract, then it names only `world_id` as canonical and requires `(world_id, local/request id)` scoping with no fallback default.
2. **Terminal semantics** — Given immediate and reserved command use cases, when lifecycle terms are chosen, then immediate terminals are exactly `APPLIED`/`REJECTED`, and reservation admission is separately `RESERVATION_ACCEPTED`/`RESERVATION_REJECTED` from later `EXECUTION_APPLIED`/`EXECUTION_REJECTED`.
3. **Read semantics** — Given every **currently checked** endpoint/event inventory entry, when classified, then every live game-data/event entry has exactly one of `AUTHORITATIVE`, `RYW`, `EVENTUAL`, or `Mutation`, with primary/cache/replica routing, `world_id` source, and `minVersion` behavior where required. `NON_STATE` and `N/A` appear only in the explicit declaration-only/pure-computation and operational/gateway allowlists in §6.3.1.
4. **Durability** — Given an API response of `202`, when Redis/API/engine fail at any listed fault point, then the matrix identifies PostgreSQL inbox/result/outbox or committed world state as authority, requires idempotent recovery, and never treats Redis as the record of acceptance.
5. **Flush safety** — Given a flush failure, stale writer, or ambiguous commit, when the engine transitions state, then `PREPARING`/`PREPARED`/`COMMITTING`/`ABORTING` blocking through a confirmed commit or abort termination, idempotent commit/abort behavior, writer epoch/world version CAS, immutable due-ring/result/outbox intent, `FLUSH_RETRY`, `RELOAD_REQUIRED`, and readiness behavior are explicit.
6. **Replica restraint** — Given a proposed replica, when it has not passed its separate ADR and human-approved implementation ticket, then no physical replica is introduced; even if introduced under `ARCH-S6-T3`, it is `EVENTUAL` only and `AUTHORITATIVE`/`RYW` always remain on primary. Any exception requires a superseding plan, contract amendment, and human approval.
7. **Ring concurrency** — Given an API reservation admission/edit/cancel races a prepared daemon due rotation, when either changes the shared ring, then the same scoped `reservationRevision` lock/affected-row protocol admits exactly one ordering; a mismatch rolls back/reloads/reprepares and never overwrites an accepted slot. `world_version` alone is not accepted as that coordination proof.
8. **Outcome mutation semantics** — Given a valid no-op `APPLIED` or an immediate `REJECTED`, when `G` is prepared, then terminal acceptance is distinct from `stateChanged`; a no-delta mixed `G` holds the locking observation and reports `committedWorldVersion=V`, while every immediate rejection passes the per-handler zero-effect gate before commit.
9. **Admission seams** — Given NPC possession or GA-007 moderation, when an API admission succeeds, then it has its durable scoped inbox/parent boundary before its wake, cannot return gameplay success early, and leaves engine `G` as the only owner of final gameplay/ownership effects. GA-007 child expansion semantics remain explicitly UNKNOWN rather than being generalized from bulk.
10. **Bulk parity** — Given general bulk reservation, or the ring-prefix portion of nation bulk reservation, when a first in-loop child failure occurs, then its already accepted ordered prefix remains durable and idempotently observable, no later child is evaluated, and no all-or-none rollback or whole-list semantic prevalidation occurs. Empty-turn/unavailable-action/non-array-arg preserves PHP's scalar indexed response; deeper `set*Command` denial preserves structured `briefList`/`errorIdx`/reason. GA-079's PHP user-general `killturn` effect is captured, including its post-ring failure/crash boundary. ADR-LITE-014 selected the expected-`stageVersion`, one-daemon-write lifecycle and its focused review is cleared; production activation remains blocked until `OPENSAM-43` and W3 provide canonical world scope, durable CAS, and fenced flush binding.

## 13. Verification checklist

### Contract review now

- [ ] Two independent reviewers check every `MUST` against the draft CQRS hardening plan and mark no unresolved contradiction.
- [ ] `OPENSAM-43/44/45` owners verify the dependency table and no shared ownership expansion is implied.
- [ ] Reviewer confirms each UNKNOWN is genuinely unverified, has an owner/wave, and is not filled by a current singleton assumption.
- [ ] Reviewer confirms the source-of-truth table, terminal table, read classes, ACK sequence, generation state machine, and fault matrix all agree.
- [ ] Reviewer runs the exact §6.3.5 `zsh` manifest gate: all three diffs are empty and its current source counts are game-api `86`, non-game control-plane `31`, event anchors `14`.
- [ ] `git diff --check` passes and referenced repository paths exist.

### Implementation gates required by later waves

- [ ] Endpoint inventory regression test keeps §6.3.5 green for all mapped game endpoints, and current-event regression keeps its 14 enumerated anchors green. This is not a claim of generic future-event discovery.
- [ ] `ARCH-S4`/W3 implements a generalized producer/consumer event lint before activation: a new live event path outside a declared allowlist must fail review until it has a class, terminal/advisory meaning, `world_id` source, and version behavior.
- [ ] Two-world identical-local-id integration test across read, precheck, intake, result, flush, delete, Redis key, and cache paths.
- [ ] `202`-before-inbox-commit negative test, duplicate request-id test, Redis-down admission test, durable result after Redis TTL/miss test.
- [ ] NPC possession integration test proves API atomically writes the pending scoped `general_owner` claim with its inbox before wake, a crash after that commit resumes by same request id, `APPLIED` atomically completes owner/token/NPC effects, and `REJECTED` explicitly releases/rejects the pending claim without NPC flip or gameplay delta.
- [ ] GA-007 moderation test proves the durable parent envelope commits before its wake/`202`; it does not assert unproven child all-or-none or partial-effect behavior.
- [ ] General bulk parity and nation bulk ring-prefix tests prove whole-payload `validateArgs` before mutation followed by ordered child evaluation; every accepted child has an individual atomic primary boundary that commits its reservation/ring change, child inbox/result/outbox, `reservationRevision` advance, and parent ordered progress before the next child is evaluated. Every first in-loop failure preserves the durable maximal prefix, evaluates no later child, and never rolls back earlier accepted children. Empty-turn/unavailable-action/non-array-arg must retain the scalar indexed response, while deeper `set*Command` denial retains exact structured `briefList`/`errorIdx`/reason.
- [x] GA-079 PHP capture is recorded in `docs/loops/cqrs-runtime-safety-2026-07-18/evidence/ga079-nation-bulk-php.json`: two fresh installs were byte-identical and prove post-ring `general.killturn` ordering plus the durable ring / old-`killturn` failure and crash boundary.
- [x] GA-079's selected one-daemon-write lifecycle, focused tests, and architecture guard received independent review with no remaining critical or major finding.
- [ ] `OPENSAM-43`/W3 bind GA-079 stages and expected-version checks to canonical world scope, durable CAS, and fenced flush before activation; an API general-row write and ring-only activation remain forbidden.
- [ ] Bulk crash-between-children tests prove a same-parent retry preserves the durable prefix, resumes at the first unresolved child without duplicate/reorder/skip, and never evaluates a later child first; include a crash after child 0 commits and before child 1 evaluation.
- [ ] Immediate and reserved lifecycle integration tests prove the four terminal namespaces are not conflated.
- [ ] Generation property/fault tests prove no destructive clear before confirmed commit, no intake/tick during recovery, and same-batch retry after known rollback only when the frozen ring revision remains current.
- [ ] A concurrent API reservation admission/edit/cancel and prepared daemon due-rotation test proves one shared `reservationRevision` ordering: zero-row conflict rolls back/reloads/reprepares and the newly accepted slot is never overwritten.
- [ ] `SelectPoolHandler`-style valid existing-selection no-op test proves `APPLIED, stateChanged=false`, no CAS, and `committedWorldVersion=V`; mixed no-op/rejection batches use the same non-mutating locking branch.
- [ ] S4 fault/architecture tests prove every immediate `REJECTED` final-validates before mutation, its handler pre/post world+`ChangeRecorder`/log/ring checkpoint is zero, and a `PREPARING` violation aborts rather than commits a terminal. These tests require no per-request final-SQL provenance.
- [ ] Concurrent writer, stale epoch, CAS-zero rollback, ambiguous-commit reload, and post-commit/pre-ACK reclaim tests pass.
- [ ] Primary routing and `minVersion` timeout tests prove no stale success; replica tabletop proves `EVENTUAL`-only routing and that `AUTHORITATIVE`/`RYW` remain primary.
- [ ] `DaemonNoEntityManagerTest`, `InfraNoEntityManagerTest`, precheck cross-call-site agreement, and applicable parity gate remain green. No test/golden is weakened to satisfy this contract.

## 14. Explicit unresolved facts

| UNKNOWN | Owner / earliest wave | Required resolution |
|---|---|---|
| Physical representation, request propagation, and authorization binding of `world_id` | `OPENSAM-43`, W1 | Publish canonical schema/wire contract; do not infer it from current `world_state.id`. |
| HTTP status, bounded wait, retry hint shape for `VERSION_NOT_VISIBLE` | `ARCH-S5-T3`, W4 | Specify and integration-test response contract. |
| Inbox/result/outbox, pending scoped `general_owner` status/claim serialization, and shared ring-coordinator physical schemas; retention, payload fingerprint, and non-terminal claim/lease names | `ARCH-S4-T1/T3`, W3 | Implement equivalent durable semantics and migration tests. The physical names remain open, but pending-claim + inbox atomicity, terminal release/rejection, `(world_id)` coordinator lock, monotonic `reservationRevision`, ring+revision atomic PREPARING snapshot, expected-revision check, and affected-row behavior do not. |
| Exact HTTP status/body and request-id transport for durable `RESERVATION_REJECTED` | `ARCH-S4`, W3 | Define a non-`202` terminal response without changing the already-fixed durability, idempotency, or no-ring semantics. |
| Writer-epoch acquisition/lease and committed-generation storage mechanism | `ARCH-S3-T2`, W2 | Choose a primary-backed protocol that proves stale-writer exclusion and ambiguous-commit recovery. |
| `CQRS-CF-U1`: reservation replacement/cancel/expiry business semantics | `ARCH-S4`, W3 | Nonblocking scoped UNKNOWN: obtain PHP evidence for exact reason/consume behavior without changing the fixed durable-admission → fenced-G writer boundary or collapsing admission/execution axes. |
| GA-007 moderation child expansion, ordering, partial-effect, and all-or-none semantics | `ARCH-S4`, W3 | Capture the PHP behavior and add focused tests before implementing child semantics. The already-fixed durable parent-before-wake boundary remains required, but bulk semantics must not be inferred. |
| GA-079 nation reservation actor-general `killturn=max(env.killturn, current)` durable activation | `ARCH-S4`, W3 | PHP evidence is captured at `docs/loops/cqrs-runtime-safety-2026-07-18/evidence/ga079-nation-bulk-php.json`. ADR-LITE-014 selected the in-memory expected-`stageVersion` lifecycle and its focused test seam/architecture guard are independently reviewed and cleared. Bind the selected stages to world-scoped durable CAS and fenced flush before activation. **Blocks production activation and contract approval:** no API general-row write and no ring-only activation. |
| Logical primary read-pool naming/configuration | `ARCH-S5-T3`, W4 | Separate code/pool boundary while still using primary initially. |
| Replica topology, cost threshold, and lag instrumentation | `ARCH-S6-T3`, W5 | Decide in signed ADR; this contract still permits `EVENTUAL` only. Any exception requires a superseding plan, contract amendment, and human approval. |

## 15. Evidence references

- Draft sequence and ticket predicates: `docs/superpowers/plans/2026-07-18-cqrs-memory-consistency-hardening-plan.md`.
- GA-079 PHP/MariaDB two-install capture and exact matrix output: `docs/loops/cqrs-runtime-safety-2026-07-18/evidence/ga079-nation-bulk-php.json`; focused-review-cleared seam and activation boundary: `docs/loops/cqrs-runtime-safety-2026-07-18/OPENSAM-124.md`.
- Current engine write/read baseline: `app/game-engine/src/main/kotlin/opensamguk/engine/{boot,run,turn,redis}/` and `infra/src/main/kotlin/opensamguk/infra/persistence/JdbcFlushExecutor.kt`.
- Current API intake/precheck/result baseline: `app/game-api/src/main/kotlin/opensamguk/gameapi/{reserve,web,precheck}/`.
- Load-bearing invariants: `CLAUDE.md` §Architecture and §Parity discipline; architecture evidence: `docs/agent/architecture.md`.

This contract deliberately does not assert that the baseline already satisfies the target. Its purpose is to give W1–W5 a single fail-closed definition of acceptance, execution, freshness, and recovery while preserving the existing one-daemon-write and PHP-parity invariants.
