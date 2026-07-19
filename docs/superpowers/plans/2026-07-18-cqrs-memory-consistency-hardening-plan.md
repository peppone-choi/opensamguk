# CQRS 메모리·정합성 강화 티켓 발행 계획 및 결과

> 상태: **ISSUED — 승인 범위 발행 완료**
> 작성일: 2026-07-18
> 대상: Jira `OPENSAM` / GitHub `peppone-choi/opensamguk`
> 우선순위: 신규 27개 티켓 모두 `Highest` — 사용자의 명시적 P0 지시에 따른 예외
> 범위: 1 Epic + 6 Stories + 20 Sub-tasks, GitHub 1:1 미러 27개

## 1. 목표와 결론

쓰기와 읽기의 **논리적 책임은 분리**하되, 지금 바로 물리적인 쓰기 DB와 읽기 DB를 분리하지 않는다. 우선 하나의 PostgreSQL primary 안에서 command/query 코드 경로와 커넥션 풀을 분리하고, 다음을 먼저 보장한다.

1. 메모리에는 전체 데이터가 아니라 현재 턴 계산에 필요한 bounded hot set만 둔다.
2. 모든 상태와 키를 canonical `world_id`로 격리한다.
3. `202 Accepted` 전에 PostgreSQL command inbox에 명령을 영속화한다.
4. Redis는 명령의 원장이 아니라 wake-up/transport/cache로만 사용한다.
5. flush는 세대별 `prepare → commit/abort`, writer fencing, `world_version` CAS로 보호한다.
6. 데이터 정합성이 필요한 read는 primary에서 `minVersion` 장벽을 통과시킨다.
7. read replica 도입은 실제 부하·지연 측정 뒤 별도 승인하는 ADR로 제한한다.

이 계획은 현재 저장소에서 **실제 OOM 장애가 발생했다고 단정하지 않는다**. 전역 eager load, 무제한 history load, 2 GiB 컨테이너와 약 1.2 GiB JVM heap 상한을 근거로 한 용량 위험을 측정하고 제거하는 계획이다.

## 2. 현재 근거와 지켜야 할 불변식

- `InMemoryTurnWorld`는 프로세스당 전역 단일 bean이고 snapshot은 `world_state ORDER BY id LIMIT 1` 및 여러 live/history 테이블 전체를 읽는다.
- production game-engine은 2 GiB 제한, `MaxRAMPercentage=60`으로 설정되어 있다.
- immediate command는 Redis 경로가 중심이며 현재 stream cursor는 dispatch 전에 전진한다.
- dirty state와 로그는 flush 전에 destructive drain될 수 있다.
- `JdbcFlushExecutor`는 한 DB transaction을 쓰지만 `world_state` 갱신에 expected version/fencing 조건이 없다.
- command result의 Redis TTL은 5분이므로 장기 결과 원장으로 사용할 수 없다.
- game-api precheck는 마지막 flush의 DB 상태를 보며, game-engine의 최종 판정은 live memory를 본다.

계획 전체에서 다음 프로젝트 불변식을 유지한다.

- PHP grand truth의 RNG draw 순서, 반올림, 로그 byte, 부수효과 및 JDBC flush 순서를 바꾸지 않는다.
- daemon write는 계속 `ChangeRecorder → JdbcFlushExecutor` 단일 경로만 사용한다.
- JPA는 daemon write에 사용하지 않는다.
- read-side precheck는 advisory이고, command의 최종 허용/거절은 engine이 authoritative하다.
- multi-world registry, 런타임 eviction scheduler, 비동기 read projector는 이번 P0 범위에 넣지 않는다.
- 독립적인 이중 write authority는 만들지 않는다. 같은 PostgreSQL 안의 expand/backfill 호환 default/trigger는 제한된 migration 수단으로만 허용한다.

## 3. 중복 방지와 기존 티켓 경계

| 기존 티켓 | 기존 소유 범위 | 이 초안과의 관계 |
|---|---|---|
| `OPENSAM-148` / GitHub `#298` | canonical `world_id` contract and `WorldId` value type | `ARCH-S2-T0`의 foundation 단일 소유자다. `OPENSAM-43`과 `OPENSAM-126`을 block하며, build-only S2→S3→S4가 이 계약을 소비한다. |
| `OPENSAM-43` | broad V2-0B `world_id` work (G0 선행과 기존 11항목 범위 전체) | `OPENSAM-148`에 의해 block되지만 **open으로 유지**한다. foundation은 이 티켓의 범위를 축소하거나 Done 처리하지 않는다. |
| `OPENSAM-44` | v2 entity의 `ChangeRecorder → JdbcFlushExecutor` 영속화 | shared flush substrate를 병렬 수정하지 않는다. `ARCH-S2-T3`, `ARCH-S3-T1`, `ARCH-S3-T2`가 foundation 단일 소유자이며 handoff 뒤 OPENSAM-44가 소비한다. |
| `OPENSAM-45` | Accepted/Resolved/Rejected UI·SSE lifecycle와 query invalidation | `ARCH-S4-T3`의 durable result/outbox 계약을 선행 기반으로 소비한다. 프론트 lifecycle을 이 초안에서 중복 구현하지 않는다. |
| `OPENSAM-33` | 60초 cadence 운영 smoke와 관측 배선 | `ARCH-S6-T2` 검증에 재사용한다. |
| `OPENSAM-72` | 성능·동기화 gate | `ARCH-S1`, `ARCH-S5`, `ARCH-S6-T2`의 측정 결과를 연결한다. |

### Shared flush 단일 소유·handoff 규칙

- Foundation owner: 이 Epic의 `ARCH-S2-T3 → ARCH-S3-T1 → ARCH-S3-T2` 순차 작업자.
- 공유 계약: `world_id`가 포함된 delta key, generation token, immutable prepared batch, writer epoch, expected `world_version`, order-preserving JDBC flush 결과.
- `OPENSAM-44`는 위 세 작업이 완료되고 계약 테스트와 handoff 문서가 연결될 때까지 동일 클래스의 shared foundation 변경이 `blocked by` 상태다.
- handoff 뒤 `OPENSAM-44`는 공개된 계약을 소비하는 entity mapper/flush 확장만 소유한다.
- Jira 발행 시 `blocks/is blocked by` link와 양쪽 티켓 comment를 함께 추가한다. 이는 아래 승인 범위에 포함된다.

## 4. 공통 티켓 필드

- Jira project: `OPENSAM` (`오픈삼국`)
- Jira hierarchy: Epic → Story → Sub-task
- Jira priority: **Highest**
- GitHub repository: `peppone-choi/opensamguk`
- GitHub mirror: Jira 티켓당 1개, 제목 앞에 발행된 Jira key를 붙이고 본문에 Jira URL과 parent key를 기록
- GitHub milestone: `M6`가 존재하는지 발행 직전 재검증 후 연결
- 제안 labels: `jira-mirror`, `architecture`, `priority-highest`; 없는 label을 임의 생성하지 않고 승인자에게 차이를 보고
- Definition of Done: 수용 기준을 자동화된 테스트/측정 증거로 충족하고, PR에 `Closes <Jira key>` 및 GitHub issue link를 남기며, parity와 architecture gate를 통과해야 한다.

## 5. 티켓 초안

## ARCH-E1 — [P0] CQRS runtime safety: bounded memory and durable consistency

- **유형:** Epic
- **우선순위:** Highest
- **사용자 가치:** 게임 데이터와 동시 명령이 늘어나도 서버가 메모리 고갈로 중단되지 않고, 사용자가 접수 확인을 받은 명령과 그 결과를 잃지 않으며, 정합성이 필요한 화면은 방금 반영된 상태를 읽는다.
- **수용 기준 (GWT):**
  - Given 동일한 hot entity 수와 cold/history row가 기준 대비 10배인 fixture, When game-engine을 정해진 측정 절차로 실행하면, Then after-GC retained heap 증가는 승인된 임계치 이내이고 boot에 full-history scan이 없다.
  - Given API가 command에 `202 Accepted`를 반환한 직후 API/engine/Redis 중 하나가 중단되는 상황, When 서비스가 재개되면, Then PostgreSQL 원장에서 command를 찾아 중복 적용 없이 durable terminal result에 도달한다.
  - Given world A와 B에 동일한 local general/nation/city ID가 존재할 때, When boot/read/precheck/intake/flush/delete를 수행하면, Then 모든 결과와 부수효과가 요청한 `world_id`에만 발생한다.
  - Given client가 `minVersion=V`인 authoritative read를 요청할 때, When query path가 응답하면, Then primary에서 `world_version >= V`인 결과를 주거나 명시적인 version-not-visible 응답을 반환하며 stale 데이터를 성공 응답으로 위장하지 않는다.
- **의존성:** `OPENSAM-148` canonical identity foundation이 scoped schema 시작의 hard predecessor이며 `OPENSAM-43`의 broad V2 범위 완료는 요구하지 않는다. `OPENSAM-148`은 `OPENSAM-43`과 `OPENSAM-126`을 block한다. `OPENSAM-44`, `OPENSAM-45`, `OPENSAM-33`, `OPENSAM-72`와 위 경계대로 link한다.
- **검증 방법:** 모든 하위 Story gate, two-world isolation IT, crash/replay fault matrix, heap/JFR 3회 측정, backend parity gate, architecture tests, staging canary 증거를 Epic에 링크한다.

### ARCH-S1 — [P0] Runtime baseline, consistency contract, and capacity guardrails

- **유형:** Story
- **우선순위:** Highest
- **사용자 가치:** 위험을 추측으로 관리하지 않고 재현 가능한 메모리·지연 수치와 명시적인 정합성 계약으로 운영 결정을 내릴 수 있다.
- **수용 기준 (GWT):** Given 동일 fixture와 JVM 설정, When baseline을 3회 실행하고 계약 검토를 완료하면, Then 결과 편차·heap 상한·정합성 등급·명령 terminal 의미·운영 임계치가 versioned artifact로 남고 이후 티켓이 이를 참조한다.
- **의존성:** 없음. `OPENSAM-72`와 relates-to link.
- **검증 방법:** 재현 스크립트, raw 결과, 분석 보고서, 승인된 ADR/계약 문서, metric/alert 단위 테스트를 Story에 첨부한다.

#### ARCH-S1-T1 — Reproducible heap, snapshot, and latency baseline

- **유형:** Sub-task
- **우선순위:** Highest
- **사용자 가치:** 실제 장애 여부와 별개로 현재 데이터 증가가 heap과 boot/tick latency에 미치는 영향을 수치로 판단할 수 있다.
- **수용 기준 (GWT):** Given production과 같은 2 GiB container 및 `MaxRAMPercentage=60`, current-size fixture와 hot cardinality는 같고 cold/history만 10배인 fixture가 있을 때, When 각 fixture를 최소 3회 boot하고 대표 tick을 실행하면, Then RSS, heap used/committed, after-GC retained heap, GC pause, loaded row 수, boot/tick p50·p95가 동일 포맷으로 저장되고 run-to-run 편차가 표시된다.
- **의존성:** 없음.
- **검증 방법:** 재현 명령, fixture hash, JVM/container 설정, JFR 또는 동등 heap evidence, 3회 raw output과 요약표를 CI artifact 또는 추적 문서에 보존한다.

#### ARCH-S1-T2 — Architecture contract for consistency and failure semantics

- **유형:** Sub-task
- **우선순위:** Highest
- **사용자 가치:** 개발자와 운영자가 어떤 read가 즉시 최신이어야 하고 어떤 read가 결과적 일관성을 허용하는지 같은 기준으로 판단한다.
- **수용 기준 (GWT):** Given command/query use case 목록, When 계약 리뷰가 완료되면, Then canonical 식별자는 `world_id` 하나로 고정되고, read는 `authoritative/RYW/eventual`로 분류되며, immediate command terminal은 `APPLIED/REJECTED`, reserved-turn은 `RESERVATION_ACCEPTED/REJECTED`와 이후 `EXECUTION_APPLIED/REJECTED`로 구분되고, 장애 지점별 source of truth와 복구 행동이 표로 정의된다.
- **의존성:** 없음. 기존 `OPENSAM-43`, `OPENSAM-45` 용어를 재사용한다.
- **검증 방법:** architecture decision record를 reviewer 2인이 검토하고, 각 API endpoint와 event에 consistency class 및 terminal state가 누락되지 않았는지 lint/checklist로 검증한다.

#### ARCH-S1-T3 — Capacity thresholds, admission policy, and alerts

- **유형:** Sub-task
- **우선순위:** Highest
- **사용자 가치:** heap이 위험 구간에 들어가기 전에 운영자가 대응하고, 너무 큰 world가 mutable runtime을 시작해 OOM으로 진행되는 것을 막는다.
- **수용 기준 (GWT):** Given `ARCH-S1-T1` baseline과 `ARCH-S1-T2` 계약, When 용량 정책을 적용하면, Then 기본값은 after-GC heap 65% warning, 80% critical(3회 연속), mandatory hot snapshot + 30% headroom이 max heap을 넘으면 boot fail-closed, fixed hot cardinality에서 10배 cold growth의 retained heap delta 5% 이하이며, 측정으로 다른 값이 승인되면 근거와 새 수치가 ADR에 기록된다.
- **수용 기준 (GWT):** Given gameplay turn이 진행 중일 때, When heap warning/critical이 발생하면, Then 자동 mid-turn eviction으로 parity를 깨지 않고 alert와 controlled pause/runbook을 사용한다.
- **수용 기준 (GWT):** Given production runtime, When Micrometer/Actuator telemetry를 수집하면, Then after-GC heap, hot/cold loaded count, boot/tick/flush latency, flush failure, CAS/fence loss, recovery state, inbox/outbox pending count와 oldest age, Redis PEL/reclaim, committed/read version lag, `minVersion` wait/timeout이 bounded-cardinality metric으로 노출되고 `world_id`/user/request id는 metric label로 사용하지 않는다.
- **의존성:** `ARCH-S1-T1`, `ARCH-S1-T2` 완료.
- **검증 방법:** threshold boundary 단위 테스트, oversized boot fixture, Micrometer registry/Actuator exposure test, metric label-cardinality 검사, alert smoke, pause/runbook drill을 실행하고 수치와 결과를 첨부한다.

### ARCH-S2 — [P0] End-to-end world_id isolation

- **유형:** Story
- **우선순위:** Highest
- **사용자 가치:** 여러 게임 world가 같은 DB/Redis를 사용해도 한 world의 조회·명령·삭제가 다른 world의 데이터를 오염시키지 않는다.
- **수용 기준 (GWT):** Given world A/B가 같은 local entity ID를 가질 때, When 두 별도 engine process/context로 핵심 lifecycle을 실행하면, Then schema, loader, API, Redis key, flush와 delete가 모두 `world_id`로 격리되고 unscoped live query/write가 gate에서 실패한다.
- **의존성:** `ARCH-S2-T0` (`OPENSAM-148`) 완료 및 canonical `world_id` contract artifact 공개. `ARCH-S1-T2`는 설계 참조이지만, local/live `OPENSAM-123` proof와 `OPENSAM-124` W3 durable binding은 build-only foundation의 blocker가 아니라 activation/cutover gate다.
- **검증 방법:** migration 검증, query/write scope architecture test, two-world Testcontainers integration suite.

#### ARCH-S2-T0 — Canonical world identity foundation

- **유형:** Sub-task
- **우선순위:** Highest
- **사용자 가치:** 모든 후속 scoped schema와 runtime 경로가 같은 world를 가리키도록, 모호하지 않은 식별자와 거절 규칙을 먼저 공유한다.
- **수용 기준 (GWT):** Given world identity가 필요한 Kotlin/SQL/wire 경계가 있을 때, When canonical contract와 `WorldId` type을 적용하면, Then PostgreSQL `world_state.id`의 positive SQL `INTEGER`만 canonical identity이고 Kotlin은 positive `Int`를 감싼 `@Serializable @JvmInline WorldId`, wire는 JSON integer scalar `worldId`를 사용한다.
- **수용 기준 (GWT):** Given `profile`, `server_id`, `ng_games.id`, 누락 world id, 또는 불일치한 world id가 있을 때, When 경계를 평가하면, Then 이들은 alias/default/fallback이 되지 않고 명시적으로 거절된다. 모든 local/request key는 `(world_id, local/request id)` composite contract를 따른다.
- **수용 기준 (GWT):** Given 아직 single-world expand 단계일 때, When 장래 backfill 규칙을 검토하면, Then 정확히 하나의 `world_state` row인 경우에만 그 `id`를 backfill source로 허용하고 0개·복수·orphan은 fail-closed한다.
- **의존성:** 없음. `OPENSAM-148`은 `OPENSAM-43`과 `OPENSAM-126`을 block한다. local/live `OPENSAM-123` proof와 `OPENSAM-124` W3 durable binding은 이 contract approval 또는 build-only foundation의 선행 조건이 아니라 activation/cutover gate다.
- **검증 방법:** focused `WorldId` construction/JSON scalar test, contract review, and later S2 schema/query implementations against this contract. second-world admission/cutover, full-table migration, and W3 activation are out of scope.

#### ARCH-S2-T1 — Scoped live schema expand, backfill, and constraints

- **유형:** Sub-task
- **우선순위:** Highest
- **사용자 가치:** 데이터베이스 자체가 cross-world 중복 ID와 잘못된 참조를 차단한다.
- **수용 기준 (GWT):** Given `OPENSAM-148`의 canonical `world_id` type/identity 계약, When schema inventory와 expand migration을 실행하면, Then mutable live뿐 아니라 append-only/cold/satellite인 log, history, rank, turn, KV, message, auction, archive-style world data와 향후 inbox/result/outbox를 포함한 **모든 world-owned table**이 scoped key/FK/unique/index 계약을 가지며 기존 단일-world backfill의 null/orphan/duplicate 검사가 0건이다.
- **수용 기준 (GWT):** Given schema inventory의 어떤 table이 `world_id`를 갖지 않을 때, When migration review를 수행하면, Then 해당 table은 world-independent global owner, 접근 경계, 근거가 기록된 allowlist에 있어야 하며 allowlist 밖 unscoped table은 gate를 통과하지 못한다.
- **수용 기준 (GWT):** Given 아직 두 번째 world가 admission되지 않은 expand 단계, When 구버전 호환 binary를 실행하면, Then 제한된 same-DB default/trigger로 단일 authority를 유지할 수 있고 별도 DB/Redis dual write는 발생하지 않는다.
- **의존성:** `ARCH-S2-T0` (`OPENSAM-148`) identity contract artifact. `OPENSAM-148`이 이 ticket/`OPENSAM-126`을 block한다; `OPENSAM-43`의 broad V2 scope closure와 `ARCH-S1-T2`의 W3 binding은 build-only schema foundation의 선행 조건이 아니다.
- **검증 방법:** fresh migration, production-shaped dump migration rehearsal, constraint-negative tests, 전체 table ownership inventory와 global allowlist diff. 누락 world-owned table이 0개임을 체크한다.

#### ARCH-S2-T2 — Scope loader, query/precheck, reservation, and Redis keys

- **유형:** Sub-task
- **우선순위:** Highest
- **사용자 가치:** 사용자의 조회와 명령 접수가 선택한 world의 데이터만 사용한다.
- **수용 기준 (GWT):** Given request/context에 `world_id=W`, When snapshot load, game-api JPA/read, precheck, `ReservedTurnRepository`, cold/history/rank/KV/message/auction query, Redis stream/result/cache 접근을 수행하면, Then 모든 world-owned SQL predicate와 Redis key/consumer identity가 W를 포함하고 다른 world의 동일 local ID를 반환하거나 덮어쓰지 않는다.
- **수용 기준 (GWT):** Given `world_id`가 누락되거나 권한과 불일치할 때, When API 또는 engine이 요청을 받으면, Then default world로 추측하지 않고 명시적으로 거절한다.
- **의존성:** `ARCH-S2-T1` expand schema 완료.
- **검증 방법:** repository slice tests, loader integration test, Redis key contract test, cross-world authorization/precheck negative cases.

#### ARCH-S2-T3 — Scope JdbcFlushExecutor create/update/delete paths

- **유형:** Sub-task
- **우선순위:** Highest
- **사용자 가치:** engine의 일괄 저장과 삭제가 다른 world의 동일 ID row를 변경하지 않는다.
- **수용 기준 (GWT):** Given prepared delta가 `world_id=W`를 가진 상태, When `JdbcFlushExecutor`가 create/update/delete/tombstone을 실행하면, Then 모든 key와 predicate가 W를 포함하고 affected row 검증이 적용되며 unscoped live SQL은 architecture test에서 실패한다.
- **수용 기준 (GWT):** Given shared flush 계약을 변경할 때, When 구현 PR이 시작되면, Then 이 티켓이 foundation 단일 owner이고 `OPENSAM-44`는 handoff 전 동일 shared class를 병렬 변경하지 않는다.
- **의존성:** `ARCH-S2-T1`, `ARCH-S2-T2`의 scoped key contract. Jira에서 `blocks OPENSAM-44` link를 설정한다.
- **검증 방법:** Testcontainers CRUD/tombstone suite, SQL capture assertion, unscoped query static/architecture test, OPENSAM-44 handoff checklist.

#### ARCH-S2-T4 — Two-world identical-local-ID isolation gate

- **유형:** Sub-task
- **우선순위:** Highest
- **사용자 가치:** 실제 운용 형태에서 cross-world 오염이 없음을 회귀 테스트로 보장한다.
- **수용 기준 (GWT):** Given world A/B에 동일한 general/nation/city/command local ID와 서로 다른 log/history/rank/turn/KV/message/auction/satellite 값이 있고 각 world에 별도 engine process/context가 있을 때, When boot, hot/cold read, precheck, immediate/reserved intake, flush, append, tombstone delete, result lookup을 교차 실행하면, Then 각 관측값과 DB/Redis 부수효과가 지정 world에만 존재한다.
- **의존성:** `ARCH-S2-T2`, `ARCH-S2-T3` 완료.
- **검증 방법:** PostgreSQL+Redis Testcontainers end-to-end test와 world별 row/key dump 비교. 교차 오염 0건을 assert한다.

### ARCH-S3 — [P0] Generation-safe flush with writer fencing and CAS

- **유형:** Story
- **우선순위:** Highest
- **사용자 가치:** DB 장애, 중복 engine, 재시작 중에도 메모리 delta를 잃거나 두 번 반영하지 않고 턴 상태가 한 방향으로 전진한다.
- **수용 기준 (GWT):** Given flush 전후 임의 장애와 stale writer, When retry/reload가 수행되면, Then prepared generation은 commit 또는 abort로만 종료되고, stale writer의 transaction은 전체 rollback되며, 해결 전 새 intake/tick은 진행하지 않는다.
- **의존성:** `ARCH-S2-T4` isolation gate 완료.
- **검증 방법:** generation property tests, concurrent writer/CAS IT, DB fault injection, parity flush-order assertion.

#### ARCH-S3-T1 — Immutable delta generation prepare/commit/abort contract

- **유형:** Sub-task
- **우선순위:** Highest
- **사용자 가치:** flush 실패가 dirty state와 로그를 메모리에서 먼저 지워 명령 결과를 잃는 상황을 방지한다.
- **수용 기준 (GWT):** Given generation N의 dirty/created/deleted/log delta, When `prepare(N)`이 시작되면, Then N은 immutable하게 freeze되고 `commit(N)` 또는 `abort(N)`으로 종료될 때까지 모든 새 intake/tick/mutation을 차단하며, 실패한 `abort(N)`은 동일 batch를 재시도 가능하게 보존하고 성공한 `commit(N)`만 정확히 N을 제거한다.
- **수용 기준 (GWT):** Given 동일 generation을 중복 commit/abort할 때, When API가 호출되면, Then idempotent한 동일 결과 또는 명시적 illegal-transition 오류가 발생하고 다른 generation은 손상되지 않는다.
- **의존성:** `ARCH-S2-T3`, `ARCH-S2-T4` 완료. `ARCH-S2-T3` foundation owner가 연속 소유한다.
- **검증 방법:** state-machine/property tests, failure-before/after-prepare tests, prepared 구간 intake/tick/mutation 차단 concurrency test, log/order byte comparison, destructive drain 금지 architecture test.

#### ARCH-S3-T2 — Transaction-start fencing and order-preserving world_version CAS

- **유형:** Sub-task
- **우선순위:** Highest
- **사용자 가치:** 두 daemon이 같은 world를 쓰거나 오래된 daemon이 재등장해도 최신 상태를 덮어쓰지 않는다.
- **수용 기준 (GWT):** Given writer epoch E와 expected `world_version=V`, When flush transaction이 시작되면, Then transaction 시작부에서 per-world lock/fence를 검증하고 기존 parity-sensitive JDBC operation order를 그대로 실행하며, 원래 canonical `world_state` 단계에서 `(world_id, writer_epoch, version=V)` CAS가 정확히 1 row를 갱신해야 commit한다.
- **수용 기준 (GWT):** Given stale epoch 또는 version mismatch로 CAS affected-row가 0일 때, When transaction을 종료하면, Then 앞서 실행된 모든 SQL도 함께 rollback되고 generation은 commit되지 않으며 engine은 recovery state로 이동한다.
- **의존성:** `ARCH-S3-T1` 완료. Jira에서 `blocks OPENSAM-44`를 유지하고 이 티켓 완료 시 shared-contract handoff artifact를 첨부한다.
- **검증 방법:** 두 writer concurrency IT, stale epoch negative test, CAS=0 rollback DB snapshot, SQL operation sequence golden/spy test, `DaemonNoEntityManagerTest`.

#### ARCH-S3-T3 — FLUSH_RETRY/RELOAD safety gate and recovery

- **유형:** Sub-task
- **우선순위:** Highest
- **사용자 가치:** 저장 성공 여부가 불명확한 동안 새 명령이나 턴이 진행되어 상태가 더 발산하는 것을 막는다.
- **수용 기준 (GWT):** Given flush failure, timeout, connection loss 또는 ambiguous commit, When engine이 이를 감지하면, Then 상태는 `FLUSH_RETRY` 또는 `RELOAD_REQUIRED`가 되고 새 intake/tick을 받지 않으며, DB의 generation/version/result를 확인해 retry 또는 scoped reload 중 하나로만 복구한다.
- **수용 기준 (GWT):** Given 복구가 완료되지 않았을 때, When health/readiness를 조회하면, Then readiness는 fail하고 원인 world/generation을 비민감 metric/log로 노출한다.
- **의존성:** `ARCH-S3-T2` 완료, `ARCH-S1-T3` alert/runbook contract 완료.
- **검증 방법:** DB kill/restart, commit-response loss, duplicate retry, reload fault tests; intake/tick count가 recovery 동안 증가하지 않음을 assert한다.

### ARCH-S4 — [P0] Authoritative command inbox/outbox with Redis as wake transport

- **유형:** Story
- **우선순위:** Highest
- **사용자 가치:** 사용자가 접수 확인을 받은 명령이 Redis 장애나 process crash 때문에 사라지지 않고, 재처리돼도 게임 상태가 중복 변경되지 않는다.
- **수용 기준 (GWT):** Given API가 `202`를 반환한 명령, When Redis가 유실되거나 API/engine이 어느 지점에서든 중단되면, Then PostgreSQL inbox에서 명령을 복구하고 state/result/outbox transaction commit 뒤에만 ACK하며 durable terminal result를 조회할 수 있다.
- **의존성:** `ARCH-S3-T3` recovery gate와 `ARCH-S2-T4` isolation gate 완료.
- **검증 방법:** command lifecycle IT, Redis-down polling, consumer pending recovery, transactional outbox tests, exhaustive crash matrix.

#### ARCH-S4-T1 — PostgreSQL command_inbox authority before 202

- **유형:** Sub-task
- **우선순위:** Highest
- **사용자 가치:** 접수됐다고 안내된 명령은 durable 원장에 반드시 존재한다.
- **수용 기준 (GWT):** Given 유효한 immediate 또는 reserved-turn request, When API가 응답하면, Then `(world_id, request_id)` unique command inbox row와 payload/schema version이 PostgreSQL에 commit된 뒤에만 `202`를 반환하고 Redis wake 발행 실패는 접수 자체를 되돌리지 않는다.
- **수용 기준 (GWT):** Given reserved-turn request, When reservation DB transaction이 성공하면, Then `RESERVATION_ACCEPTED`는 ring reservation과 inbox가 함께 durable할 때만 성립하고 이후 turn 실행은 별도 `EXECUTION_APPLIED/REJECTED` lifecycle을 가진다.
- **수용 기준 (GWT):** Given immediate request, When engine이 처리하면, Then terminal은 state delta와 durable result가 commit된 `APPLIED` 또는 아무 state effect가 없는 `REJECTED`이다.
- **의존성:** `ARCH-S3-T3`, `ARCH-S2-T4` 완료. 기존 `OPENSAM-45` lifecycle vocabulary와 호환한다.
- **검증 방법:** 202-before-commit 방지 IT, duplicate request idempotency, Redis unavailable API test, reserved ring+inbox atomicity test.

#### ARCH-S4-T2 — Redis consumer-group wake and durable polling fallback

- **유형:** Sub-task
- **우선순위:** Highest
- **사용자 가치:** Redis stream 메시지가 중복되거나 사라져도 command 원장을 기준으로 안전하게 처리한다.
- **수용 기준 (GWT):** Given consumer group과 PostgreSQL inbox, When Redis wake가 정상일 때, Then engine은 inbox row를 claim하고 처리하며 state delta, CAS version, inbox terminal, durable result, outbox가 포함된 DB transaction commit을 확인한 뒤에만 stream entry를 ACK한다.
- **수용 기준 (GWT):** Given Redis down/trim, consumer crash, pending entry 또는 ambiguous DB commit, When engine이 복구하면, Then durable inbox polling과 pending reclaim으로 미완료 command를 찾고 DB terminal 상태를 확인한 뒤 retry/ACK하며 command effect를 중복 적용하지 않는다.
- **의존성:** `ARCH-S4-T1`, `ARCH-S4-T3`, `ARCH-S3-T3` 완료. consumer group 코드는 먼저 준비할 수 있으나 **ACK/reclaim activation은 세 의존성의 검증 증거가 연결된 후에만** 허용한다.
- **검증 방법:** Redis stop/trim/restart, consumer kill, PEL reclaim, duplicate wake, ambiguous commit integration tests; ACK timestamp가 DB commit 이후임을 assert한다.

#### ARCH-S4-T3 — Transactional outbox and durable result fallback

- **유형:** Sub-task
- **우선순위:** Highest
- **사용자 가치:** SSE/cache 전달이 실패하거나 5분 TTL이 지나도 명령의 최종 결과를 다시 확인할 수 있다.
- **수용 기준 (GWT):** Given engine이 command를 적용 또는 거절할 때, When flush transaction이 commit되면, Then command terminal status, `committedWorldVersion`, durable result, outbox event가 해당 command state effect와 같은 transaction에 기록되고 rollback 시 모두 보이지 않는다.
- **수용 기준 (GWT):** Given Redis result TTL 5분이 만료되거나 Redis가 비어 있을 때, When client가 request id로 결과를 조회하면, Then API는 `(world_id, request_id)` durable DB result로 fallback하며 다른 world의 결과를 반환하지 않는다.
- **수용 기준 (GWT):** Given outbox publish가 재시도될 때, When 동일 event가 다시 전달되면, Then event id로 dedupe 가능하고 DB row의 publish 상태가 source of truth다.
- **의존성:** `ARCH-S4-T1`, `ARCH-S3-T2`, `ARCH-S3-T3` 완료. Jira에서 이 티켓이 `blocks OPENSAM-45`임을 link하고, OPENSAM-45는 SSE/UI lifecycle만 소비한다.
- **검증 방법:** transaction rollback/commit IT, Redis TTL expiry test, outbox duplicate publish test, request/world isolation test.

#### ARCH-S4-T4 — Command crash/replay and fault-matrix gate

- **유형:** Sub-task
- **우선순위:** Highest
- **사용자 가치:** 실제 장애 순서에서도 “accepted but lost”와 이중 실행이 없음을 배포 전에 증명한다.
- **수용 기준 (GWT):** Given crash points `before inbox commit`, `after inbox before wake`, `before/after state commit`, `before/after outbox publish`, `before/after ACK`와 Redis/DB 단독 장애, When immediate와 reserved command를 각각 재생하면, Then accepted command는 durable terminal에 도달하고 state effect는 최대 한 번이며 unaccepted command는 effect가 없다.
- **수용 기준 (GWT):** Given reserved command가 예약됐지만 실행 turn 전 crash가 발생할 때, When 복구하면, Then durable reservation은 유지되고 execution terminal은 실제 turn 적용 여부와 분리되어 기록된다.
- **의존성:** `ARCH-S4-T1`, `ARCH-S4-T2`, `ARCH-S4-T3` 완료.
- **검증 방법:** parameterized fault-injection suite, DB/Redis snapshot comparison, command/result/outbox cardinality assertions, parity gate.

### ARCH-S5 — [P0] Bounded hot/cold world state and versioned primary reads

- **유형:** Story
- **우선순위:** Highest
- **사용자 가치:** 오래된 기록이 늘어도 engine heap은 현재 게임 계산량에 비례하고, 방금 명령을 수행한 사용자는 필요할 때 최신 상태를 확실히 읽는다.
- **수용 기준 (GWT):** Given hot entity 수는 고정이고 cold/history만 10배인 world, When boot와 대표 turn을 실행하면, Then retained heap 증가가 승인 임계치 이내이고 parity-sensitive loop 안에 lazy DB query가 없으며 authoritative read는 primary version barrier를 따른다.
- **의존성:** `ARCH-S1-T3`, `ARCH-S4-T4` 완료.
- **검증 방법:** heap comparison, SQL query-count/order assertions, PHP parity fixtures, minVersion integration tests.

#### ARCH-S5-T1 — Hot/cold catalog and deterministic phase-boundary prefetch

- **유형:** Sub-task
- **우선순위:** Highest
- **사용자 가치:** 턴 계산에 꼭 필요한 데이터만 안정적으로 메모리에 두면서도 RNG와 실행 순서를 보존한다.
- **수용 기준 (GWT):** Given game logic의 entity/history access graph, When hot/cold catalog를 적용하면, Then always-hot, phase-hot, query-only cold 데이터가 명시되고 phase-hot 데이터는 bounded keyset/limit로 phase 시작 전에 stable insertion/history order로 prefetch되며 RNG draw 또는 entity iteration 내부에서 lazy SQL이 0회다.
- **수용 기준 (GWT):** Given catalog에 없는 신규 접근이 추가될 때, When architecture test를 실행하면, Then 무제한 snapshot load 또는 loop 내부 repository 호출이 실패한다.
- **의존성:** `ARCH-S1-T3`, `ARCH-S4-T4` 완료. preliminary access-graph 조사는 앞서 병렬 수행할 수 있지만 activation은 두 의존성 뒤에만 한다.
- **검증 방법:** CodeGraph/call-site inventory artifact, query-count test, deterministic ordering test, RNG/log golden comparison.

#### ARCH-S5-T2 — Remove full-history boot scans and prove bounded retention

- **유형:** Sub-task
- **우선순위:** Highest
- **사용자 가치:** history와 로그가 장기간 축적되어도 boot memory와 heap이 데이터 전체 크기에 비례해 증가하지 않는다.
- **수용 기준 (GWT):** Given 동일한 hot cardinality와 1배/10배 cold-history fixture, When boot 및 대표 tick 후 full GC 기준 retained heap을 비교하면, Then 기본 delta는 5% 이하이고 `ARCH-S1-T3`에서 승인된 다른 수치가 있으면 그 값을 사용하며, boot SQL에는 unbounded full-history scan이 없다.
- **수용 기준 (GWT):** Given cold data 조회가 필요할 때, When query/phase prefetch가 실행되면, Then keyset pagination, bounded projection 또는 aggregate를 사용하고 stable ordering과 PHP 결과 byte parity를 유지한다.
- **의존성:** `ARCH-S5-T1` 완료.
- **검증 방법:** 3회 heap/JFR comparison, SQL plan/query capture, full-scan regression test, logic/common parity gate.

#### ARCH-S5-T3 — committedWorldVersion and minVersion primary-read barrier

- **유형:** Sub-task
- **우선순위:** Highest
- **사용자 가치:** 사용자가 자신의 명령 직후 조회할 때 오래된 상태를 성공 응답으로 받지 않는다.
- **수용 기준 (GWT):** Given command result의 `committedWorldVersion=V`와 read request의 `minVersion=V`, When authoritative/RYW endpoint가 응답하면, Then write primary의 scoped `world_state`가 `version >= V`임을 확인한 결과만 성공으로 반환하고 bounded wait 안에 보이지 않으면 current/required version과 retry hint가 포함된 `VERSION_NOT_VISIBLE` 응답을 준다.
- **수용 기준 (GWT):** Given 일반 ranking/history처럼 eventual로 분류된 endpoint, When `minVersion`이 없으면, Then eventual path를 사용할 수 있지만 precheck 결과는 advisory로 표시되고 engine final validation을 대체하지 않는다.
- **수용 기준 (GWT):** Given 현재 물리 read replica가 없을 때, When read/write 코드를 배치하면, Then logical repository/connection-pool 경계는 분리하되 둘 다 PostgreSQL primary를 사용하며 async projector를 새 source of truth로 만들지 않는다.
- **의존성:** `ARCH-S4-T3`, `ARCH-S3-T2`, `ARCH-S2-T2` 완료.
- **검증 방법:** concurrent write/read IT, version timeout/response contract tests, endpoint consistency classification coverage, primary routing assertion.

### ARCH-S6 — [P0] Safe rollout, rollback fence, canary, and replica ADR

- **유형:** Story
- **우선순위:** Highest
- **사용자 가치:** 데이터 손실 없이 단계적으로 변경을 도입하고, 되돌릴 수 없는 시점을 운영자가 명확히 인지하며, replica 비용은 필요할 때만 지불한다.
- **수용 기준 (GWT):** Given expand/backfill/cutover 배포, When staging/canary gate를 통과하면, Then 두 번째 world admission 전후 rollback 규칙이 강제되고, memory/fault/parity gate가 통과하며, replica는 별도 GO 승인 전 생성되지 않는다.
- **의존성:** `ARCH-S2`~`ARCH-S5` 모든 Story 완료.
- **검증 방법:** migration rehearsal, rollback drill, canary checklist, OPENSAM-33/72 evidence, signed replica ADR.

#### ARCH-S6-T1 — Expand/backfill/cutover and rollback point-of-no-return

- **유형:** Sub-task
- **우선순위:** Highest
- **사용자 가치:** schema 전환 중에도 기존 단일 world 서비스를 유지하고 잘못된 구버전 binary가 multi-world 데이터를 오염시키는 것을 막는다.
- **수용 기준 (GWT):** Given expand/backfill 단계이고 두 번째 world가 아직 admission되지 않았을 때, When 새 버전에 문제가 생기면, Then 호환 범위 안에서 old scoped-compatible 또는 기존 단일-world binary로 rollback할 수 있고 검증 query가 데이터 완전성을 확인한다.
- **수용 기준 (GWT):** Given composite scoping이 active이고 두 번째 world가 최초 admission된 뒤, When unscoped binary가 boot하려 하면, Then persistent cutover marker/schema compatibility check가 boot를 거절하며 이후 대응은 forward-fix 또는 scoped-compatible binary만 허용한다.
- **수용 기준 (GWT):** Given migration 기간, When write path를 관찰하면, Then 독립 DB/Redis authority로 dual write하지 않고 동일 PostgreSQL transaction의 compatibility default/trigger만 명시된 기간에 사용된다.
- **의존성:** `ARCH-S2-T4`, `ARCH-S3-T3`, `ARCH-S4-T4`, `ARCH-S5-T2`, `ARCH-S5-T3` 완료.
- **검증 방법:** fresh/upgrade migration, backfill reconciliation, pre/post-no-return rollback drill, unscoped binary boot-negative test.

#### ARCH-S6-T2 — Shadow/canary memory, fault, and parity gates

- **유형:** Sub-task
- **우선순위:** Highest
- **사용자 가치:** 아키텍처 변경이 턴 결과나 운영 안정성을 훼손하지 않았음을 제한된 범위에서 먼저 확인한다.
- **수용 기준 (GWT):** Given production-shaped staging과 canary world, When 대표 command/tick replay 및 fault suite를 실행하면, Then PHP/Kotlin RNG·로그·상태 parity, command durability, two-world isolation, heap thresholds, p95 tick/flush latency가 모두 승인 기준을 통과하고 실패 시 cutover가 중단된다.
- **수용 기준 (GWT):** Given 60초 cadence smoke, When `OPENSAM-33` 절차를 수행하면, Then missed/duplicate tick과 unresolved command가 0건이고 측정 결과가 `OPENSAM-72`에 연결된다.
- **의존성:** `ARCH-S6-T1` staging 완료, `OPENSAM-33` smoke 절차, `OPENSAM-72` measurement contract.
- **검증 방법:** shadow diff report, canary dashboard snapshot, fault matrix 결과, backend parity gate XML, rollback decision log.

#### ARCH-S6-T3 — Read-replica GO/NO-GO ADR only

- **유형:** Sub-task
- **우선순위:** Highest
- **사용자 가치:** read replica의 비용과 일관성 복잡도를 실제 병목이 있을 때만 도입하고, 중요한 조회가 replica lag 때문에 stale해지는 것을 막는다.
- **수용 기준 (GWT):** Given canary의 read/write QPS, primary CPU/IO/headroom, query p95, 측정된 replica-lag 가정과 `minVersion` 계약, When ADR을 검토하면, Then 기본 NO-GO를 포함한 정량 기준으로 GO/NO-GO를 결정하고 근거 없는 replica provisioning은 하지 않는다.
- **수용 기준 (GWT):** Given ADR이 GO일 때, When 후속 작업을 준비하면, Then 별도의 human-approved implementation ticket을 새로 만들 뿐 이 티켓에서 인프라를 생성하지 않으며, replica는 eventual read만 담당하고 watermark가 required version 이상이 아니면 authoritative/minVersion read를 primary로 fallback한다.
- **의존성:** `ARCH-S6-T2`, `ARCH-S5-T3`, `ARCH-S1-T1` 완료.
- **검증 방법:** 서명된 ADR, cost/capacity 표, endpoint routing matrix, lag/watermark tabletop test. 인프라 변경 0건을 확인한다.

## 6. 실행 순서와 완료 predicate

| Wave | 작업 | 다음 Wave로 넘어가는 정확한 조건 |
|---|---|---|
| W0 (evidence) | `S1-T1`, `S1-T2` 병렬 → `S1-T3` | local/live `OPENSAM-123` proof와 `OPENSAM-124` W3 durable binding은 activation/cutover evidence로 유지한다. 이 증거의 보류는 아래 build-only foundation을 막지 않는다. |
| B0 (build-only) | `S2-T0` / `OPENSAM-148` canonical identity | positive `WorldId`, SQL/wire contract, alias/default rejection, single-world fail-closed backfill rule. `OPENSAM-43`은 broad V2 scope를 보존한 채 open으로 남는다. |
| B1 (build-only) | `S2-T1` → `S2-T2`/`S2-T3` → `S2-T4` | canonical identity를 소비한 scoped schema/read/write/key와 two-world 동일-ID gate green. `OPENSAM-148`이 `OPENSAM-126`을 block한다. |
| B2 (build-only) | `S3-T1` → `S3-T2` → shared-contract handoff → `S3-T3` | generation state machine, fence/CAS rollback, recovery 동안 intake/tick 정지 증거; OPENSAM-44 handoff link |
| B3 (build-only) | `S4-T1` → `S4-T3` → `S4-T2` ACK/reclaim implementation (inactive) → `S4-T4` | DB-before-202, atomic result/outbox, post-commit ACK, 전체 crash matrix green; durable W3 activation은 activation/cutover gate에서만 허용 |
| Activation/cutover | local/live `OPENSAM-123` proof + `OPENSAM-124` W3 durable binding + B0→B3 evidence | 위 두 W0 proof가 승인되기 전에는 second-world admission, durable W3 activation, production cutover를 실행하지 않는다. |
| W4 | `S5-T1`과 `S5-T3`의 구현은 durability gate 뒤 분리 가능 → `S5-T2` | bounded phase prefetch, full-history scan 0, 10배 cold heap delta 기준 통과, primary version barrier green |
| W5 | `S6-T1` → `S6-T2` → `S6-T3` | rollback fence/canary/parity 통과, signed replica GO/NO-GO ADR; GO라도 별도 승인 전 provisioning 0 |

### 병렬화 제한

- `ChangeRecorder`, `JdbcFlushExecutor`, shared delta/flush contract는 `ARCH-S2-T3 → S3-T1 → S3-T2` 단일 writer 순서를 지킨다.
- Build-only foundation 순서는 `ARCH-S2-T0` (identity) → `ARCH-S2` → `ARCH-S3` → `ARCH-S4`다. `OPENSAM-123` local/live proof와 `OPENSAM-124` W3 binding은 이 순서의 approval/implementation blocker가 아니라 activation/cutover gate다.
- `OPENSAM-44`는 handoff 전 shared foundation을 수정하지 않는다.
- Redis consumer-group 코드는 사전 준비할 수 있지만 ACK/reclaim을 활성화하는 배포는 `S3-T3`, `S4-T1`, `S4-T3` 완료 뒤다.
- hot/cold access-graph 조사는 일찍 할 수 있지만 runtime activation은 `S1-T3`와 `S4-T4` 뒤다.

## 7. Jira/GitHub 발행 매핑

승인 뒤 다음 순서로 발행한다.

### Foundation-unblock addendum (2026-07-19)

- `ARCH-S2-T0` maps to Jira [`OPENSAM-148`](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-148) and GitHub [#298](https://github.com/peppone-choi/opensamguk/issues/298).
- `OPENSAM-148` blocks `OPENSAM-43` and `OPENSAM-126`; `OPENSAM-43` remains open with its broad V2-0B scope intact.
- This mapping records a build-only foundation. It does not admit a second world, run a migration, activate W3 durable inbox/outbox behavior, or authorize production cutover.

1. Jira Epic `ARCH-E1`을 생성하고 발행된 key를 기록한다.
2. Jira Story 6개를 Epic parent로 생성한다.
3. Jira Sub-task 20개를 해당 Story parent로 생성한다.
4. 모든 신규 Jira 티켓 priority를 `Highest`로 확인한다.
5. 기존 `OPENSAM-43/44/45/33/72`와 위 dependency/relates/block link를 생성하고, OPENSAM-44/45에는 소유 경계 comment를 추가한다.
6. GitHub에 Jira 27개를 1:1 mirror issue로 생성한다. 제목은 `[<Jira key>] <summary>`, 본문은 Jira URL, parent, 사용자 가치, GWT 수용 기준, 의존성, 검증 방법을 포함한다.
7. 존재가 재확인된 `M6` milestone과 사용 가능한 제안 label을 연결한다.
8. 생성 결과를 Jira key ↔ GitHub issue URL 표로 이 문서에 backfill한다.

예상 외부 생성 수:

- Jira: 27개 (`1 Epic + 6 Stories + 20 Sub-tasks`)
- GitHub: 27개 mirror issues
- 추가 외부 변경: 기존 Jira 5개에 issue link, 그중 OPENSAM-44/45에 경계 comment
- 구현, commit, push, PR, deploy, production data 변경: **0개**

## 8. 승인 기록

2026-07-18 사용자가 이 문서의 `27 Jira + 27 GitHub` 발행안에 대해 “승인”이라고 답했다. 다음 승인 범위만 실행했다.

- Jira issue 27개 생성 및 parent/`Highest` 설정
- GitHub mirror issue 27개 생성
- 기존 Jira `OPENSAM-43/44/45/33/72`와 dependency/relates link 생성
- `OPENSAM-44/45` 소유 경계 comment 추가
- 발행된 key/URL을 이 문서에 backfill

승인은 구현, commit, push, PR, merge, deploy, read replica 생성 또는 production data migration을 포함하지 않았으며 실행하지 않았다.

2026-07-19 사용자는 foundation-unblock amendment를 승인했다. 따라서 `OPENSAM-148/#298` contract approval과 build-only `identity → S2 → S3 → S4`는 local/live `OPENSAM-123` proof 또는 `OPENSAM-124` W3 binding을 기다리지 않는다. 두 증거는 activation/cutover gate로만 유지한다.

## 9. 검토 기록

- 저장소와 Jira/GitHub 기존 backlog를 read-only로 대조했다.
- 독립 architecture review에서 지적된 `world_id` 전 구간 scoping, DB-before-202, generation-safe flush, order-preserving CAS, ACK hard dependency, OPENSAM-44 handoff, point-of-no-return, replica ADR-only 조건을 반영했다.
- 모든 신규 티켓을 `Highest`로 두는 데 대한 reviewer의 범위 우려는 기록하되, 사용자의 명시적 지시가 우선하므로 이 초안에서는 27개 모두 `Highest`로 유지한다.
- 실제 production OOM은 확인되지 않았으며 문서 전체에서 capacity risk로만 표현한다.

## 10. 발행 결과

- Jira 범위: `OPENSAM-116`~`OPENSAM-142`
- GitHub 범위: `#262`~`#288`
- GitHub label: `jira-mirror`
- GitHub milestone: `5` — 기존 M6 영역 mirror `OPENSAM-43/#185`와 동일 milestone 재사용
- Jira read-back: Epic 1, Story 6, Sub-task 20, `Highest` 누락 0, parent 누락 0
- GitHub read-back: open 27, label 누락 0, milestone 누락 0, Jira-key 제목 누락 0

| Draft ID | Jira | GitHub |
|---|---|---|
| `ARCH-E1` | [OPENSAM-116](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-116) | [#262](https://github.com/peppone-choi/opensamguk/issues/262) |
| `ARCH-S1` | [OPENSAM-117](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-117) | [#263](https://github.com/peppone-choi/opensamguk/issues/263) |
| `ARCH-S1-T1` | [OPENSAM-123](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-123) | [#269](https://github.com/peppone-choi/opensamguk/issues/269) |
| `ARCH-S1-T2` | [OPENSAM-124](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-124) | [#270](https://github.com/peppone-choi/opensamguk/issues/270) |
| `ARCH-S1-T3` | [OPENSAM-125](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-125) | [#271](https://github.com/peppone-choi/opensamguk/issues/271) |
| `ARCH-S2` | [OPENSAM-118](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-118) | [#264](https://github.com/peppone-choi/opensamguk/issues/264) |
| `ARCH-S2-T0` | [OPENSAM-148](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-148) | [#298](https://github.com/peppone-choi/opensamguk/issues/298) |
| `ARCH-S2-T1` | [OPENSAM-126](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-126) | [#272](https://github.com/peppone-choi/opensamguk/issues/272) |
| `ARCH-S2-T2` | [OPENSAM-127](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-127) | [#273](https://github.com/peppone-choi/opensamguk/issues/273) |
| `ARCH-S2-T3` | [OPENSAM-128](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-128) | [#274](https://github.com/peppone-choi/opensamguk/issues/274) |
| `ARCH-S2-T4` | [OPENSAM-129](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-129) | [#275](https://github.com/peppone-choi/opensamguk/issues/275) |
| `ARCH-S3` | [OPENSAM-119](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-119) | [#265](https://github.com/peppone-choi/opensamguk/issues/265) |
| `ARCH-S3-T1` | [OPENSAM-130](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-130) | [#276](https://github.com/peppone-choi/opensamguk/issues/276) |
| `ARCH-S3-T2` | [OPENSAM-131](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-131) | [#277](https://github.com/peppone-choi/opensamguk/issues/277) |
| `ARCH-S3-T3` | [OPENSAM-132](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-132) | [#278](https://github.com/peppone-choi/opensamguk/issues/278) |
| `ARCH-S4` | [OPENSAM-120](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-120) | [#266](https://github.com/peppone-choi/opensamguk/issues/266) |
| `ARCH-S4-T1` | [OPENSAM-133](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-133) | [#279](https://github.com/peppone-choi/opensamguk/issues/279) |
| `ARCH-S4-T2` | [OPENSAM-134](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-134) | [#280](https://github.com/peppone-choi/opensamguk/issues/280) |
| `ARCH-S4-T3` | [OPENSAM-135](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-135) | [#281](https://github.com/peppone-choi/opensamguk/issues/281) |
| `ARCH-S4-T4` | [OPENSAM-136](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-136) | [#282](https://github.com/peppone-choi/opensamguk/issues/282) |
| `ARCH-S5` | [OPENSAM-121](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-121) | [#267](https://github.com/peppone-choi/opensamguk/issues/267) |
| `ARCH-S5-T1` | [OPENSAM-137](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-137) | [#283](https://github.com/peppone-choi/opensamguk/issues/283) |
| `ARCH-S5-T2` | [OPENSAM-138](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-138) | [#284](https://github.com/peppone-choi/opensamguk/issues/284) |
| `ARCH-S5-T3` | [OPENSAM-139](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-139) | [#285](https://github.com/peppone-choi/opensamguk/issues/285) |
| `ARCH-S6` | [OPENSAM-122](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-122) | [#268](https://github.com/peppone-choi/opensamguk/issues/268) |
| `ARCH-S6-T1` | [OPENSAM-140](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-140) | [#286](https://github.com/peppone-choi/opensamguk/issues/286) |
| `ARCH-S6-T2` | [OPENSAM-141](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-141) | [#287](https://github.com/peppone-choi/opensamguk/issues/287) |
| `ARCH-S6-T3` | [OPENSAM-142](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-142) | [#288](https://github.com/peppone-choi/opensamguk/issues/288) |
