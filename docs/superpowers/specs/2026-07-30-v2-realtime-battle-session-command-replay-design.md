# OpenSamguk v2 실시간 전투 세션·지휘권·명령·리플레이 설계

- Date: 2026-07-30
- Status: **APPROVED DESIGN / PENDING WRITTEN SPEC REVIEW**
- Product: OpenSamguk New Version (v2)
- Scope: 공통 전투 세션, 지휘권, 명령, 지휘망 지연, 내구성, 재접속, AI 대행, 결과 반영, 리플레이
- Required launch adapters: 야전, 공성, 수전
- Rendering direction: Three.js 정사영 2.5D + billboard pixel sprites

## 1. 사용자 가치

현재의 일괄 전투를 다음 선택이 실제 결과를 바꾸는 10–15분 전술 전투로 대체한다.

1. 지휘관이 서버가 허용한 주 목표와 부 목표를 선택한다.
2. 장수마다 하나의 편제를 맡고 직접 전술, 보조 직접, 목표 중심 AI 중 하나로 지휘한다.
3. 명령은 거리와 지휘망 상태에 따라 늦게 도착하며, 새 명령이 도착할 때까지 기존 명령이 계속된다.
4. 지형, 대형, 측후면, 사기, 피로, 보급, 시야, 증원, 퇴각선이 전투 결과에 영향을 준다.
5. 접속이 끊기거나 서버 액터가 재시작되어도 승인된 명령과 전투 결과가 유실·중복되지 않는다.
6. 전투 결과는 단 한 번 캠페인에 반영되고, 이후 전체 리플레이가 공개된다.

## 2. 이번 인터뷰에서 승인된 결정

1. 전술 전투는 V2 출시 후 콘텐츠가 아니라 **V2 출시 필수 기능**이다.
2. 기본 조작은 **실시간 + 제한 전술 정지**다.
3. 성능·동기화·재접속 게이트를 통과하지 못하면 **사전 전술 설정 + 자동전투**로 출시한다.
4. 한 진영에는 총지휘관 1명과 위임받은 장교들이 있다.
5. 총지휘관은 본대 편제 1개를 직접 지휘하면서 전체 목표, 위임, 회수, 상위 우선 명령 권한을 가진다.
6. 각 사람은 전투에서 편제 1개를 맡고, 편제 하나를 둘 이상의 사람이 동시에 조작하지 않는다.
7. 미배정 편제와 접속 이탈 편제는 AI가 지휘한다.
8. 총지휘관은 위임을 회수하고 상위 우선 명령을 내릴 수 있지만, 권한 변경과 명령 모두 지휘망 지연을 거친다.
9. 총지휘관만 제한된 토큰·시간으로 전술 정지를 실행하며, 장교는 정지를 요청할 수만 있다.
10. 야전·공성·수전을 모두 V2 출시 범위에 넣되, 공통 기반 뒤에 별도 어댑터 스펙으로 분리한다.
11. 출병 발령자, 수비 책임자, 관직, 장수 소유자, 지정 부관은 캠페인 권한에서 가져와 전투 시작 시 동결한다.
12. 주 목표와 부 목표는 총지휘관이 서버가 정의한 합법 패키지에서 고른다. 자유 형식 목표는 허용하지 않는다.
13. 전투 중에는 진영별 안개 정보를 제공하고, 전체 리플레이는 캠페인 결과가 최종 반영된 뒤 공개한다.
14. 접속 단절 시 기존 명령 유지 → AI 대행 → 재접속 회수 순으로 처리하며, 장기 지휘관 이탈은 지정 부관에게 승계한다.
15. 캠페인 시간은 계속 흐르며, 관련 장수·편제·도시만 잠근다. 캠페인 마감 전에 전투가 끝나지 않으면 AI가 종결한다.
16. 같은 교전 세력의 새 편제는 조건을 만족하면 증원으로 합류하고, 제3세력 또는 충돌하는 캠페인 명령은 결과 반영까지 보류한다.
17. 별도의 인간 참가자 상한은 두지 않는다. 단 출시 편제 상한과 편제당 1인 규칙이 실질 상한이다.
18. 편제 조작 모드는 직접 전술, 보조 직접+행동 정책, 목표 중심 AI의 세 종류이며 모드 변경도 지휘망 지연을 거친다.
19. 기본 전투 시간은 12분, 최대 15분이다. 최대 시간에는 섬멸이 아니라 목표 점수, 점유 거점, 전투 지속력으로 판정한다.
20. 출시 기준은 진영당 16편제, 총 32편제다. 진영당 32편제, 총 64편제는 비차단 stretch다.
21. 런타임은 기존 game-engine 내장이 아니라 **전용 battle-engine의 battle별 authoritative session actor**를 채택한다.

## 3. 기존 설계와의 관계

### 유지

`docs/superpowers/plans/2026-07-28-v2-2_5d-tactical-battle-and-sprite-design.md`에서 다음은 유지한다.

- 연속 정수/fixed-point 좌표와 200ms 고정 틱
- formation 단위 판정과 cosmetic soldier sprites
- Three.js 정사영 2.5D, billboard sprites, 8방향 표현
- 대형, 전면, 측후면, 사기, cohesion, 피로, 보급, 지휘망, 안개, 목표 기반 승리
- 브라우저 프레임과 네트워크 도착 시간이 전투 판정에 영향을 주지 않는 결정성
- v1 `ProcessWar`·PHP 골든·RNG·로그·예약 링을 변경하지 않는 격리
- 전투 종료 결과만 `ChangeRecorder -> JdbcFlushExecutor`로 캠페인에 반영하는 경계

### 대체

이번 승인으로 아래 기존 제안은 대체한다.

| 기존 제안 | 승인된 대체안 |
|---|---|
| 실시간 전투는 V2 오픈 후 | 야전·공성·수전 모두 V2 출시 필수 |
| game-engine의 단일 scheduler가 battle tick 실행 | 전용 battle-engine의 battle별 session actor |
| HTTP order intake + SSE부터 시작 | 단기 JoinTicket + battle-engine WebSocket |
| SSE 병목이 확인된 뒤 WebSocket 검토 | WebSocket이 실시간 명령·진영별 상태 스트림의 기본 |
| 첫 수직 슬라이스 약 8편제 | 출시 기준 진영당 16편제, 총 32편제 |
| 전투 권한·재접속·부관 승계 미정 | 본 문서의 권한 snapshot, formation seat, AI 대행, 승계 상태기계 |

따라서 ADR-LITE-019의 “V2-4A/4B 오픈 후” 일정 분류는 후속 ADR에서 본 결정을 반영해 개정해야 한다.

## 4. 범위와 비범위

### 범위

- battle-engine 서비스와 battle별 단일-writer session actor 계약
- 캠페인 잠금과 durable BattleTicket handoff
- BattleAuthoritySnapshot과 동적 지휘권
- 편제별 human/AI seat와 세 가지 조작 모드
- 명령 승인, 지휘망 전달 지연, 충돌, 우선순위, 만료, 거절
- 제한 전술 정지
- WebSocket join, 명령 ACK, 진영별 snapshot/delta
- append-only battle events, 주기 snapshot, state hash, replay
- 단절·재접속·AI 대행·부관 승계
- actor lease, epoch fence, 장애 복구, deadline auto resolve
- 증원과 충돌 캠페인 명령 보류
- 결과 outbox와 game-engine의 exactly-once 캠페인 반영
- 실시간 GO/자동전투 fallback 게이트

### 비범위

- 야전의 구체적인 이동·접촉·피해·목표 수식
- 성벽, 성문, 공성병기, 돌파 수식
- 선박, 바람, 수심, 화공, 승선 수식
- 렌더러 구현, sprite atlas loader, 전투 HUD 구현
- 병종별 수치 밸런스
- 64편제 stretch의 출시 차단
- v1 전투 경로의 변경

야전·공성·수전은 본 공통 계약을 소비하는 별도 스펙과 Epic으로 작성한다.

## 5. 아키텍처

```text
Campaign command
      |
      v
game-engine
  lock involved generals/formations/cities
  persist lockGeneration + lockSetRevision + CampaignBattleHandoff
  ChangeRecorder -> JdbcFlushExecutor
      |
      | committed handoff / wakeup hint
      v
battle-engine
  persist immutable BattleTicket
  BattleSessionActor(battleId)
  authoritative 200ms fixed tick
  authority/order/objective/pause/AI/reinforcement
      |
      +---- PostgreSQL battle_* append/snapshot/result outbox
      |
      +---- WebSocket faction projection ---- web/game battle client
      |
      v
BattleResolved(resultRevision, lockGeneration, lockSetRevision)
      |
      v
game-engine
  validate lock/version/fence/idempotency
  apply campaign deltas through ChangeRecorder -> JDBC flush
  acknowledge APPLIED and unlock
```

### 소유권 불변식

1. battle-engine은 `battle_*` 테이블만 수정한다.
2. battle-engine은 장수, 편제, 도시, 국가, 보급 등 캠페인 테이블을 직접 수정하지 않는다.
3. game-engine만 캠페인 결과를 `ChangeRecorder -> JdbcFlushExecutor`로 반영한다.
4. Redis는 wakeup과 배치 위치 탐색을 빠르게 할 수 있지만 정확성의 정본이 아니다.
5. 캠페인과 battle 저장소는 같은 v2 world DB 안에 둘 수 있지만 DML 소유권은 분리한다.
6. battle별 authoritative writer는 유효한 `sessionEpoch` lease를 가진 actor 하나뿐이다.
7. battle-engine 배포 하나는 v2의 한 `WorldId`와 한 world DB에만 바인딩한다. 여러 world를 한 프로세스에 섞지 않는다.

## 6. 세션 생성과 캠페인 잠금

game-engine은 출병/방어 precheck 뒤 다음을 같은 캠페인 flush 경계에서 확정한다.

1. 관련 장수, 편제, 도시, 수송/보급 항목에 `battleId` 잠금을 건다.
2. 잠금 대상 각각의 entity revision과 battle 전용 `lockGeneration`·`lockSetRevision`을 기록한다.
3. 출병 발령자, 수비 책임자, 관직, 장수 소유자, 지정 부관을 `BattleAuthoritySnapshot`으로 동결한다.
4. 합법적인 목표 패키지와 총지휘관의 선택을 기록한다.
5. `rulesetRevision`, `catalogRevision`, terrain revision, RNG seed를 고정한다.
6. 위 immutable payload를 campaign-owned `CampaignBattleHandoff` outbox로 기록한다.

battle-engine은 커밋된 handoff만 읽고, 자신이 소유한 `battle_ticket`과 `battle_session(READY)`을 같은 transaction에서 idempotently 생성한다. Redis wakeup이 유실되어도 DB의 미개시 handoff를 재조회한다. game-engine은 `battle_*` 테이블을 쓰지 않고 battle-engine은 campaign handoff를 읽기만 한다.

캠페인은 전투 동안 계속 진행한다. 결과 검증은 전역 world version이 아니라 `lockGeneration`, 최신 `lockSetRevision`, 잠긴 entity별 revision을 사용한다. 따라서 무관한 도시·장수·국가의 변경이 유효한 전투 결과를 막지 않는다.

### 6.1 잠금 폐쇄와 지연 캠페인 효과

초기 lock set은 전투 결과가 직접 변경할 수 있는 모든 캠페인 entity를 포함한다.

- 공격·방어·증원 장수와 편제
- 출발·목표·수비 도시
- 전투에 인출된 보급·수송 자원
- 포로·점령·퇴각 결과의 직접 대상

잠긴 entity를 대상으로 하는 다른 사용자 명령과 정기 시스템 효과는 즉시 변경하지 않는다. game-engine은 원래 campaign sequence와 precheck 근거를 가진 `DeferredCampaignEffect`로 기록한다. 전투 결과가 같은 flush에서 반영되고 잠금이 해제된 뒤, 지연 효과를 새 revision에서 순서대로 다시 precheck하여 적용·거절한다. 잠기지 않은 entity의 캠페인 처리는 계속된다.

증원은 새 entity를 같은 `lockGeneration`에 원자적으로 추가하고 `lockSetRevision`을 증가시킨다. battle-engine은 최신 lock-set revision을 확인한 증원만 admit한다.

### 6.2 durable session lifecycle

```text
READY
  -> STARTING
  -> RUNNING
  -> RESOLVING
  -> RESULT_PENDING
  -> APPLIED

RUNNING <-> PAUSED
RUNNING | PAUSED -> RECOVERING -> RUNNING | RESOLVING
READY | STARTING -> CANCELLED
RECOVERING -> QUARANTINED
RESULT_PENDING -> RESULT_BLOCKED
```

- `READY`: campaign handoff를 소비해 immutable BattleTicket과 battle session이 커밋됨
- `STARTING`: session epoch lease를 얻고 초기 state를 만드는 중
- `RUNNING`/`PAUSED`: authoritative simulation
- `RECOVERING`: 새 actor가 durable state를 재구성 중
- `RESOLVING`: 새 live 명령 admission이 닫힘
- `RESULT_PENDING`: durable result가 game-engine 적용을 기다림
- `APPLIED`: campaign apply와 unlock의 durable 승인을 확인함
- `QUARANTINED`: committed event 전부를 안전하게 재구성할 수 없음
- `RESULT_BLOCKED`: campaign lock/revision invariant가 맞지 않아 수동 복구 필요

CampaignBattleHandoff의 payload hash와 BattleTicket의 payload hash는 byte-identical해야 한다. BattleTicket, frozen authority, 초기 formation/seat, 선택 목표, 규칙·카탈로그·지형·RNG artifact pin은 immutable versioned payload로 저장한다. 동적 seat, authority, pause, disconnect, AI, reinforcement 전이는 event와 snapshot에 저장한다.

## 7. 권한과 formation seat

### 7.1 정적 참가 자격

```text
BattleAuthoritySnapshot
  battleId
  sideId
  participantId
  accountId
  generalId
  campaignOffice
  initialRole: COMMANDER | OFFICER
  designatedDeputyOrder[]
  authorityRevision
```

이 snapshot은 전투 참가 자격의 근거다. 전투가 시작된 뒤 캠페인의 관직 변화가 현재 전투 권한을 소급 변경하지 않는다.

### 7.2 동적 실효 지휘권

```text
FormationSeat
  formationId
  controller: HUMAN(participantId) | AI(proxyPolicy)
  delegatedBy
  authorityRevision
  controlMode
  effectiveFromTick
  status
```

- 사람 1명은 전투에서 편제 1개를 맡는다.
- 편제 1개에는 유효한 human controller가 최대 1명이다.
- 총지휘관도 본대 편제 1개만 직접 조작한다.
- 총지휘관의 전역 권한은 모든 편제를 직접 micro-control하는 권한이 아니다.
- 미배정 편제는 목표 중심 AI로 시작한다.
- 증원 편제도 위임되기 전에는 목표 중심 AI가 맡는다.

### 7.3 권한 변경

위임, 회수, 재배정, control mode 변경, 총지휘권 이양은 모두 명령이다. 명령 승인 즉시가 아니라 계산된 `deliverTick`에 실효 권한을 바꾼다.

- 회수가 먼저 도착하면 이전 revision으로 이동 중인 장교 명령은 도착 시 `STALE_AUTHORITY`로 거절된다.
- 장교 명령이 먼저 도착하면 상위 명령이 올 때까지 실행된다.
- 총지휘관의 상위 명령도 순간이동하지 않는다.
- 같은 틱에 도착한 충돌 명령의 우선순위는 `COMMANDER > OFFICER > AI_POLICY`다.
- 같은 계층의 동률은 서버 event sequence로 결정한다.

## 8. 명령 상태기계

```text
ISSUED
  -> ACCEPTED
  -> IN_TRANSIT
  -> DELIVERED
  -> ACTIVE
  -> SUPERSEDED | COMPLETED | CANCELLED

ISSUED | DELIVERED
  -> REJECTED(reasonCode)
```

### 8.1 승인

서버는 발행 시 다음을 검사한다.

- JoinTicket과 session epoch
- 참가자와 formation seat
- `expectedAuthorityRevision`
- 허용된 명령 종류와 payload 크기
- 진영 시야에서 합법적인 target인지
- pause/resolved/deadline 상태
- formation별 명령 rate limit
- `clientCommandId` idempotency

클라이언트는 위치, 피해, 명중, 도착 틱을 보내지 않는다. 전술 의도만 보낸다.

승인 순서는 다음으로 고정한다.

```text
validate
  -> current sessionEpoch fence 확인
  -> (battleId, issuerParticipantId, clientCommandId, intentHash) idempotency 확인
  -> ACCEPTED battle_event + command receipt를 같은 DB transaction에 append
  -> commit
  -> actor queue 반영
  -> ACCEPTED ACK
```

커밋 전에 DB/actor 장애가 나면 ACCEPTED를 보내지 않는다. 클라이언트는 같은 ID로 재시도하고, 커밋 뒤 ACK 전 장애였다면 durable command receipt에서 최초 ACK를 재구성한다.

### 8.2 전달 지연

```text
deliverTick =
  acceptedTick
  + baseDelay
  + distanceBucket
  + commandNetworkPenalty
  + officerStatePenalty
  - signalBonus
```

모든 항은 정수이며 `delayPolicyRevision`과 함께 이벤트에 기록한다. 기존 명령은 새 명령이 `ACTIVE`가 될 때까지 계속 실행된다.

### 8.3 조작 모드

| 모드 | 사람 입력 | AI 책임 |
|---|---|---|
| DIRECT_TACTICS | 이동, 방향, 대형, 교전, 철수 등 세부 의도 | 경로·충돌·미세 대형 유지 |
| ASSISTED_DIRECT | 목표와 주요 전술 + 행동 정책 | 위협 반응, 간격, 추격 제한, 탄약/피로 정책 |
| INTENT_DRIVEN_AI | 목표, 우선순위, 제한 조건 | 세부 전술 전체 |

모드 전환도 전달 지연과 replay 기록을 가진다.

## 9. 목표와 전술 정지

### 목표

- 서버가 전장 종류와 캠페인 맥락에 따라 합법적인 목표 패키지를 제시한다.
- 총지휘관은 전투 시작 전 주 목표와 부 목표를 선택한다.
- 자유 형식 목표와 클라이언트 정의 scoring은 허용하지 않는다.
- 전투 중 전역 방향 수정은 지휘 명령으로 전달된다.
- 최대 시간에는 목표 점수, 점유 거점, 남은 전투 지속력으로 판정한다.

### 전술 정지

- 정지는 전장 통신이 아니라 meta-control이다.
- 총지휘관만 제한된 token/time budget을 소비해 정지·재개한다.
- 장교는 정지를 요청할 수 있지만 실행할 수 없다.
- 정지와 재개는 다음 안전 tick 경계에서 효력이 생긴다.
- 요청, 승인, 거절, 소비 시간은 replay event다.

## 10. WebSocket 프로토콜

### 10.1 접속

1. 브라우저가 game-api에 battle join을 요청한다.
2. game-api가 계정과 `BattleAuthoritySnapshot`을 확인한다.
3. game-api가 짧은 수명의 서명된 JoinTicket을 발급한다.
4. 브라우저가 `JoinTicket + lastSeenEventSeq`로 battle-engine WebSocket에 접속한다.
5. battle-engine이 진영별 snapshot과 이후 delta를 보낸다.

JoinTicket은 battleId, sessionEpoch, accountId, participantId, generalId, sideId, initial authority revision, expiry에 범위가 고정된다. 동적 formation seat는 서버 state에서 조회하며 토큰에 고정하지 않는다. 장기 gateway access token을 battle-engine에 넘기지 않는다.

### 10.2 명령

```text
BattleCommandEnvelope
  clientCommandId
  battleId
  scope: FORMATION | SIDE | SESSION_META
  issuerSeatFormationId?
  targetFormationId?
  targetSideId?
  expectedAuthorityRevision
  intentType
  intentPayload
```

인증된 issuer identity와 현재 seat는 JoinTicket과 서버 state에서 얻으며 클라이언트 필드를 신뢰하지 않는다.

- `FORMATION`: formation authority revision과 target formation seat를 검사한다.
- `SIDE`: commander authority revision을 검사하며 목표, 위임, 회수, override, 지휘권 이양에 사용한다.
- `SESSION_META`: commander authority와 pause budget을 검사하며 정지·재개에 사용한다. 장교의 pause request는 별도 허용 intent다.

서버 ACK:

```text
BattleCommandAck
  clientCommandId
  verdict: ACCEPTED | REJECTED
  serverTick
  acceptedTick?
  deliverTick?
  eventSeq
  reasonCode?
  recoverable
  currentAuthorityRevision
```

같은 ID와 같은 내용의 재전송은 최초 ACK를 반환한다. 같은 ID와 다른 내용은 `IDEMPOTENCY_CONFLICT`다.

### 10.3 정보 가시성

- DB에는 완전한 authoritative state와 event를 보존한다.
- WebSocket과 reconnect snapshot은 side-specific projection만 보낸다.
- 숨은 적의 entity ID, 정확 좌표, 완전 event payload는 오류 응답에도 포함하지 않는다.
- 전체 replay는 `BattleResultOutbox.APPLIED` 뒤에만 공개한다.

## 11. 저장 모델

### battle_session

- `battle_id`
- `world_id`
- 제6.2절의 `state`
- `session_epoch`
- `lease_owner`, `lease_until`
- `lock_generation`, `lock_set_revision`
- `ruleset_revision`, `catalog_revision`, `terrain_revision`
- `current_tick`, `latest_event_seq`, `latest_snapshot_seq`
- `session_elapsed_ms`, `time_anchor_db`
- `pause_budget_remaining_ms`
- `started_at`, `deadline_at`, `resolved_at`

### battle_ticket

- `battle_id`, `ticket_schema_version`
- immutable payload와 payload hash
- frozen `BattleAuthoritySnapshot`
- 초기 formation/seat와 side assignment
- 합법 목표 패키지와 선택된 주·부 목표
- entity별 campaign revision, `lockGeneration`, `lockSetRevision`
- ruleset/catalog/terrain artifact content hash
- RNG algorithm/serializer revision과 seed

### battle_event

- `(battle_id, event_seq)` primary identity
- `session_epoch`
- `accepted_tick`, `effective_tick`
- `event_type`, versioned payload
- actor/side/formation/authority revision
- visibility audience
- reason code and deterministic evidence
- payload checksum

승인 명령 receipt의 idempotency identity는 `(battle_id, issuer_participant_id, client_command_id)`이고 `intent_hash`를 함께 저장한다.

저장 대상은 승인 입력, 권한 전이, pause, reinforcement, disconnect/AI/deputy 전이, 중요 phase/objective 전이, result다. 매 tick의 모든 cosmetic 이동을 이벤트로 저장하지 않는다.

### battle_snapshot

- `(battle_id, snapshot_seq)`
- `tick`, `event_seq`, `session_epoch`
- compressed authoritative state
- RNG state
- schema/ruleset/catalog revisions
- `state_hash`

기본 주기는 5초다. pause, reinforcement, phase transition, resolution 시 추가 snapshot을 저장한다.

### battle_result_outbox

- `(battle_id, result_revision)`
- `lock_generation`, `lock_set_revision`, entity별 expected revision
- casualties, captures, conquest, supply, general status, reward deltas
- `PENDING | APPLIED | RESULT_BLOCKED`
- apply attempt and evidence

### battle_reinforcement_ticket

- `(battle_id, reinforcement_ticket_id)`
- idempotency key, side, formation snapshot
- lock generation/set revision과 entity별 revision
- arrival condition/tick, expiry
- `PENDING | ADMITTED | REJECTED | CANCELLED`

`battle_reinforcement_ticket`은 battle-engine 소유다. game-engine은 별도의 campaign-owned `CampaignReinforcementHandoff`를 기록하고 battle-engine은 이를 읽어 자신의 ticket을 idempotently 생성한다.

## 12. 결정성과 리플레이

- 서버 simulation tick은 200ms다.
- 좌표, 속도, 거리, 각도, 사기, 피로, cohesion은 정수 또는 명시적 fixed-point다.
- BattleTicket에 ruleset, catalog, terrain, RNG algorithm/state serializer, seed revision을 pin한다.
- 같은 tick의 formation, contact pair, command는 stable identity와 event sequence로 정렬한다.
- 클라이언트 frame rate와 animation frame은 결과에 영향을 주지 않는다.
- 네트워크 도착 시점은 명령이 어느 ingress cutoff에 들어가는지는 바꿀 수 있다. 그러나 서버가 canonical accepted tick/event sequence를 durable commit한 뒤의 재전송·지연은 결과를 바꾸지 않는다.
- snapshot 이후 승인 event를 재생하면 같은 state hash가 나와야 한다.
- replay는 초기 BattleTicket, 승인 명령/권한/접속/AI/목표 event, RNG 근거, checkpoint hash, result로 구성한다.
- fallback auto battle도 같은 event 계약을 소비한다.

### 12.1 clock domains

- `simulationTick`: `RUNNING`에서만 200ms 단위로 증가하며 pause 동안 멈춘다.
- `sessionLogicalTime`: pause budget, 30/90초 disconnect 승계, ingress cutoff에 사용한다. pause/recovery 중에도 증가하며 actor 메모리만으로 보존하지 않는다.
- `databaseLeaseTime`: session epoch lease와 JoinTicket expiry에 사용하는 DB 기준 시간이다.
- `campaignDeadlineAt`: game-engine이 ticket에 기록한 DB 기준 시각이며 pause/recovery 중에도 계속 흐른다.

명령은 서버가 수신한 시점에 아직 닫히지 않은 첫 ingress window에 배정하고, window 안에서는 durable event sequence로 정렬한다. campaign deadline이 오면 새 live 명령 admission을 닫고, 모든 committed event를 재구성한 뒤 headless resolve로 전환한다.

`battle_session`은 `sessionElapsedMs + timeAnchorDb`를 저장한다. actor는 epoch lease를 얻을 때 DB time으로 새 anchor를 커밋하고, 실행 중에는 process monotonic clock을 사용하되 모든 pause/disconnect/succession 전이에 절대 `deadlineAtDb`를 event와 snapshot에 저장한다. 새 actor는 admission을 열기 전에 DB time으로 downtime을 elapsed time에 합산하고 만료된 deadline을 `(deadlineAtDb, eventTypePriority, participantId)` 순서로 먼저 처리한다.

### 12.2 장기 replay pin

- RNG는 algorithm ID/revision과 state serialization revision을 함께 저장한다. 초기값은 기존 결정적 커널을 재사용하는 `lite-hash-drbg-v1`과 `seed-serializer-v1`이다.
- canonical battle state는 versioned deterministic binary codec으로 직렬화하고 SHA-256으로 hash한다.
- event/snapshot payload는 schema version을 가지며 과거 replay를 읽는 upcaster를 삭제하지 않는다.
- ruleset, catalog, terrain descriptor는 revision 문자열뿐 아니라 content hash로 고정하고 replay retention 동안 immutable artifact를 보존한다.
- 기존 event/snapshot을 새 schema로 파괴적으로 덮어쓰지 않는다.

## 13. 접속 단절과 승계

### 장교/일반 참가자

1. 단절 후 30초 동안 현재 명령을 유지한다.
2. 30초가 지나면 AI proxy가 현재 목표와 행동 정책을 이어받는다.
3. 재접속자는 새 JoinTicket으로 들어오며 다음 안전 tick에 본인 formation을 돌려받는다.

### 총지휘관

1. 단절 후 30초까지 기존 전역 명령과 본대 명령을 유지한다.
2. 이후 본대는 AI proxy가 맡는다.
3. 90초가 지나면 BattleAuthoritySnapshot의 지정 부관 순서에 따라 총지휘권을 승계한다.
4. 원 총지휘관이 돌아오면 본대 formation은 돌려받지만 총지휘권은 자동 왕복하지 않는다.
5. 총지휘권 복구는 현재 총지휘관의 명시적 이양 명령으로 처리한다.

단절, grace 만료, AI takeover, reconnect reclaim, deputy succession은 모두 durable event다. actor 장애가 단절 timer를 초기화하지 않는다.

각 단절 event에는 `aiTakeoverDeadlineAtDb`를, 총지휘관 단절에는 `deputySuccessionDeadlineAtDb`를 추가 기록한다. 재접속과 승계는 해당 deadline의 취소·소비 event를 남긴다. recovery 중 deadline이 지났다면 새 epoch actor가 live command admission보다 먼저 AI takeover/부관 승계를 적용한다.

## 14. 액터 장애 복구

### session epoch fence

- active actor는 `sessionEpoch` lease를 보유한다.
- 새 actor가 lease를 인계할 때 epoch를 CAS로 증가시킨다.
- 이전 epoch의 tick, snapshot, event, result write는 거절된다.

### 복구 단계

1. **R1 빠른 복구:** 최신 유효 snapshot + 이후 event tail을 재생한다.
2. state hash가 맞지 않으면 직전 snapshot으로 후퇴한다.
3. **R2 전체 재생:** BattleTicket부터 모든 승인 event를 재생하고 checkpoint hash를 검사한다.
4. 캠페인 마감 전에 live actor 재개가 어렵더라도 모든 committed ACCEPTED event를 재구성하고 hash를 확인할 수 있으면 **R3 headless resolve**로 전환한다.
5. R3는 검증된 최신 committed state에서 AI가 전투를 끝내고 `DEADLINE_FALLBACK` 결과를 만든다.
6. committed event tail이 손상되었거나 재생할 수 없으면 R3로 건너뛰지 않는다. session을 `QUARANTINED`로 두고 캠페인 결과를 만들지 않는다.

중간 state나 추정 delta를 캠페인에 반영하지 않는다.

## 15. 결과 반영과 exactly-once

1. battle-engine이 `BattleResolved`와 `battle_result_outbox(PENDING)`을 같은 battle 저장 경계에 기록한다.
2. game-engine이 battleId/resultRevision idempotency, `lockGeneration`, 최신 `lockSetRevision`, 잠긴 entity별 revision을 검사한다. 무관한 world version 증가는 검사 대상이 아니다.
3. 검사가 통과하면 모든 캠페인 delta, campaign-owned `(battleId, resultRevision)` 적용 표식, 잠금 해제, durable `BattleResultApplied` outbox를 `ChangeRecorder -> JdbcFlushExecutor`의 같은 단일 flush에 반영한다.
4. 지연된 캠페인 효과는 커밋된 새 entity revision에서 순서대로 재검사한다.
5. 커밋 뒤 relay는 durable `BattleResultApplied`를 retry-only로 발행한다.
6. battle-engine은 그 승인을 소비한 뒤 자신이 소유한 result outbox를 `APPLIED`로 전이한다.
7. 같은 resultRevision 재전송은 campaign-owned 적용 표식에서 기존 결과를 반환한다.
8. 일시 DB/transport 장애는 같은 revision으로 재시도한다.
9. lock generation/set revision/entity revision 불일치는 자동 보정하지 않는다. game-engine은 campaign-owned durable `BattleResultBlocked` outbox를 `ChangeRecorder -> JdbcFlushExecutor`로 기록하고 잠금을 유지한다.
10. battle-engine은 `BattleResultBlocked`를 소비한 뒤 자신이 소유한 result outbox와 session을 `RESULT_BLOCKED`로 전이한다.
11. battle-engine이 durable `BattleResultApplied` 승인을 확인한 뒤에만 전체 replay를 공개한다.

game-engine은 battle-owned result outbox를 갱신하거나 claim하지 않는다. process-world의 campaign apply marker unique identity와 lock generation/set revision이 동시 소비를 fence한다.

## 16. 증원과 충돌 명령

- 동일 교전 세력의 편제는 game-engine의 `CampaignReinforcementHandoff` 승인을 받아 기존 session에 합류할 수 있다.
- handoff는 formation snapshot, side, arrival condition/tick, lock generation/set revision, authority eligibility를 가진다.
- 증원은 도착 시 목표 중심 AI로 시작하며 총지휘관이 위임할 수 있다.
- 제3세력 개입, 동일 장수/도시/편제를 대상으로 하는 충돌 명령은 현재 result가 `APPLIED`될 때까지 캠페인 큐에 보류한다.
- 결과 반영 뒤 보류 명령은 새 campaign version에서 다시 precheck한다.

진영당 16편제 상한은 초기 편제와 이미 admit되었거나 도착 예정인 증원을 모두 포함한다. 전용 reserve slot은 두지 않는다.

1. game-engine이 증원 entity를 잠그고 같은 `lockGeneration`의 `lockSetRevision`을 원자적으로 증가시킨 뒤 campaign-owned handoff를 기록한다.
2. battle-engine은 handoff를 읽어 battle-owned reinforcement ticket을 idempotently 만들고, `RUNNING|PAUSED` 상태와 최신 lock-set revision을 CAS로 확인하여 side admitted count가 16 미만일 때만 admit한다.
3. 같은 idempotency key의 재전송은 기존 결과를 반환한다.
4. 상한 초과는 `FORMATION_CAP_REACHED`, `RESOLVING` 이후 도착은 `BATTLE_ADMISSION_CLOSED`로 거절한다.
5. battle-engine은 거절·만료를 battle-owned durable ack로 기록한다. game-engine은 이를 읽어 해당 증원 entity를 lock set에서 제거하고 `lockSetRevision`을 다시 증가시키며, 잠금 해제와 campaign-owned `CampaignReinforcementReleased`를 같은 flush에 기록한다.
6. battle-engine은 release ack의 새 lock-set revision을 확인한 뒤 자신의 ticket을 terminal로 만든다.

### 16.1 admission-close handshake

result 생성 전에 증원 admission을 다음 순서로 닫는다.

1. battle-engine이 durable `BattleAdmissionCloseRequested(observedLockSetRevision)`를 기록한다.
2. game-engine이 해당 battle에 새 reinforcement handoff 생성을 막는다.
3. 이미 생성된 handoff를 모두 ADMITTED 또는 RELEASED terminal로 drain하고, rejection cleanup까지 반영한다.
4. game-engine이 최종 revision을 가진 campaign-owned `CampaignBattleAdmissionClosed(finalLockSetRevision)`를 같은 flush 경계에 기록한다.
5. battle-engine이 이 ack를 소비하고 모든 local reinforcement ticket과 revision이 일치하는지 확인한 뒤에만 `RESOLVING`으로 전이한다.

resolution과 새 증원 lock-set 확장은 이 handshake로 동시에 성공할 수 없다.

## 17. 시간과 규모

- 기본 목표 시간: 12분
- hard deadline: 15분
- hard deadline 판정: 목표 점수 + 점유 거점 + 전투 지속력
- 출시 규모: 진영당 16편제, 총 32편제
- human socket: 편제당 최대 1명
- stretch: 진영당 32편제, 총 64편제
- 64편제는 출시 차단이 아니며 동일 게이트 재통과 뒤에만 활성화한다.

## 18. 출시 성능 게이트

기준 시험은 15분, 32편제, 32 WebSocket, 전투 밀집 구간을 포함한다.

| 항목 | 조건 | 통과선 |
|---|---|---|
| Tick | 200ms fixed tick | p95 ≤ 100ms, p99 ≤ 180ms, 연속 2 tick deadline 초과 0 |
| 명령 ACK | RTT 80ms, packet loss 1%, 재전송 | p95 ≤ 250ms, 승인 명령 유실·중복 적용 0 |
| 재접속 | 진영당 2명 동시 단절, snapshot+delta | p95 ≤ 3초, state hash 불일치 0 |
| actor 승계 | 승인·snapshot·result 직전 강제 종료 | 복구 ≤ 10초, 중복 result/부분 campaign apply 0 |
| 메모리 | battle-engine 2GiB 제한, 60분 반복 전투 | OOM 0, 안정화 뒤 RSS 증가율 ≤ 1%/시간 |
| 브라우저 렌더 | 1080p, 32편제, cosmetic sprite 약 1,000개, 전술 overlay 활성 | p95 frame ≤ 16.7ms, simulation state 누락 0 |

명령 ACK 시간은 게임 규칙의 command-network delay를 포함하지 않는다.

## 19. 검증 게이트

### G0 계약과 아키텍처

- battle-engine의 campaign table DML 0
- game-engine만 campaign result를 ChangeRecorder/JDBC로 반영
- immutable BattleTicket, session lifecycle, battle schema와 message versioning
- entity lock closure와 lock generation/set revision
- session epoch fence와 result idempotency
- accepted event commit-before-ACK

### G1 결정적 replay

- 같은 BattleTicket과 event log를 프로세스 재시작 전후 100회 실행
- 모든 checkpoint state hash 동일
- snapshot을 하나씩 제거해 이전 snapshot/전체 replay fallback 검사

### G2 권한 행렬

- commander/officer/AI
- delegate/revoke/reassign
- commander override
- stale authority revision
- 세 control mode와 delayed switch
- pause request/approve/deny/exhaustion

### G3 장애 행렬

- WebSocket 중복·누락·순서 뒤바뀜
- 명령 append 전/후 actor crash
- snapshot write 전/후 crash
- deputy succession 직전/후 crash
- result outbox 전/후 crash
- game-engine apply 전/후 retry

### G4 정보 보안과 다중 브라우저

- 위조/만료/다른 battle JoinTicket 거절
- 상대 진영 snapshot 요청 거절
- hidden entity ID/좌표/payload 누출 0
- 결과 APPLIED 전 full replay 접근 거절
- commander, officer, reconnecting user의 실제 WebSocket/브라우저 흐름
- 1080p 32편제/1,000 sprite renderer frame gate

### G5 32편제 부하

- 제18절 수치 전부 통과
- event loss와 duplicate apply 0
- 15분 hard deadline result와 campaign apply 완료

### G6 전장 어댑터 수용 흐름

야전·공성·수전 각각 실시간 모드와 headless fallback 모드에서 다음 Given-When-Then을 통과해야 한다.

> Given 합법적인 BattleTicket과 양 진영 참가자가 있고
> When 목표 선택, 편제 위임, 교전, 단절/재접속, 제한시간 판정이 실행되면
> Then 캠페인 결과가 정확히 한 번 반영되고, 진영별 전투 정보와 최종 전체 replay가 올바른 시점에 제공된다.

## 20. 실시간 GO와 fallback

### 실시간 GO

공통 G0–G5와 야전·공성·수전 G6가 기준 장비에서 모두 통과하면 실시간+전술정지 모드로 출시한다.

### 자동전투 fallback

야전·공성·수전의 headless 기능·결과·replay G6 중 하나라도 실패하면 fallback으로도 V2를 출시하지 않는다. 세 어댑터의 headless G6가 모두 통과한 상태에서 실시간 성능, 동기화, 재접속 또는 브라우저 렌더 게이트가 출시 시점까지 실패할 때만 다음으로 축소한다.

- 전투 전 목표 패키지, 배치, control mode, 행동 정책을 설정한다.
- headless battle-engine AI가 같은 BattleTicket과 명령 event 계약으로 전투를 실행한다.
- 실시간 중간 접속과 직접 전술 명령은 비활성화한다.
- 결과 outbox, 캠페인 exactly-once apply, replay 계약은 유지한다.

fallback은 실시간 실패를 통과로 간주하는 것이 아니라 별도 출시 모드다.

### prebattle setup intake

1. game-api가 adapter가 선언한 합법 목표·배치·행동 정책 패키지를 제공한다.
2. 총지휘관은 idempotent setup request로 하나를 선택한다.
3. 기본 응답 시간은 60초다. timeout 또는 총지휘관 부재 시 adapter가 버전 관리하는 `SAFE_DEFAULT` 패키지를 선택하고 미배정 편제를 목표 중심 AI로 둔다.
4. 선택 또는 default가 campaign lock과 immutable BattleTicket에 포함된 뒤에만 session을 `READY`로 만든다.
5. 실시간 모드와 fallback 모드는 같은 setup payload를 소비한다.

## 21. 전장 어댑터 경계

### 야전

- 지형 이동 비용, 숲/도로/얕은 물, 경사
- 대형, 전면, 측후면, 돌격, 사격, 퇴각
- 목표 지점, 호송/차단, 퇴로

### 공성

- 성벽, 성문, 사다리/충차/투석 등 공성 장치
- 돌파구, 성내 목표, 수비 구역
- 공성 보급과 시간 압박

### 수전

- 선박 formation, 수심, 바람/흐름
- 원거리 사격, 화공, 충돌/승선
- 항로, 퇴로, 상륙/보급 목표

세 어댑터는 공통 session, authority, command, persistence, reconnect, replay, result 계약을 바꾸지 않는다.

## 22. 시각 자산과 지형·이펙트

현재 추적된 v2 battle asset release는 다음 후보 계약을 제공한다.

- unit source/runtime: 105개
- terrain sprites: 32개
- effect families: 16개, 총 94 frames

정본 경로는 `assets/battle/v2/`다. 본 공통 기반은 asset ID와 catalog revision을 pin하지만 개별 전장 배치 규칙은 어댑터 스펙이 소유한다.

이 수량은 각 매니페스트의 현재 선언과 일치하지만 상태는 각각 `RAW_IDENTITY_MASTER`, `STATIC_RUNTIME_SPRITE_CANDIDATE`, `STATIC_TERRAIN_CORE_CANDIDATE`, `RUNTIME_EFFECT_CANDIDATE`다. 프로덕션 전투 채택이나 simulation authority를 뜻하지 않는다.

- 야전 스펙은 평지, 도로, 숲, 얕은 물, 나루, 경사, 관문 조합을 정의한다.
- 공성 스펙은 성벽, 성문, 파괴 상태, 공성 장치, 화재/붕괴 효과를 추가 정의한다.
- 수전 스펙은 수면, 연안, 수심 band, 선박 wake, 화재/연기 효과를 추가 정의한다.
- cosmetic sprite와 effect frame은 simulation truth가 아니다.
- 전장 선택과 피격 판정은 sprite alpha가 아니라 formation footprint와 authoritative state를 사용한다.

## 23. 구현 순서의 제약

이 문서는 구현 계획이 아니다. 후속 `superpowers:writing-plans`에서 다음 foundation-first 순서를 작업 단위로 분해한다.

1. battle identity, schema, message, deterministic clock/RNG/state hash
2. campaign lock + durable BattleTicket + result outbox/apply
3. session actor + epoch lease/failover
4. authority snapshot + formation seat + order state machine
5. WebSocket join/ACK/faction projection/reconnect
6. AI proxy/deputy/pause/reinforcement/deadline resolver
7. headless replay and G0–G5 harness
8. 야전 adapter spec/implementation
9. 공성 adapter spec/implementation
10. 수전 adapter spec/implementation
11. 2.5D renderer/HUD와 추적 자산 연동
12. G6 실제 사용자 흐름과 실시간 GO/fallback 판정

공유 schema/message/actor 확장점은 foundation owner가 먼저 만들고, 세 adapter는 소비만 한다.

## 24. 승인 후 필요한 정본 갱신

작성된 스펙 검토 직후, `superpowers:writing-plans`를 시작하거나 Jira/GitHub 티켓을 만들기 전에 다음 정본 동기화를 먼저 수행한다.

1. `.ai/task.md`: v2 전투 설계·계획·티켓화 계약
2. `.ai/decisions.md`: 전용 battle-engine과 V2 출시 필수 전투를 ADR-LITE로 기록
3. 기존 `docs/superpowers/plans/2026-07-28-v2-2_5d-tactical-battle-and-sprite-design.md`: game-engine scheduler, HTTP/SSE, 오픈 후 rollout 절을 본 스펙으로 명시적 supersede
4. `docs/superpowers/SESSION_HANDOFF.md`: 사용자 지시와 인터뷰 결정 기록
5. ADR-LITE-019/021과 v2 backlog: 실시간 전투의 오픈 후 분류 개정

이 동기화가 커밋되기 전에는 구현 계획과 외부 티켓을 만들지 않는다. 그 뒤 Jira OPENSAM 목표/Epic과 GitHub issues는 승인된 구현 계획을 기준으로 생성·상호 링크한다.

## 25. 승인 상태

시각 설계 보드에서 다음 섹션이 순서대로 승인되었다.

1. 전용 battle-engine 공통 세션 아키텍처
2. 권한·명령·formation seat 상태 모델
3. WebSocket·battle_* 저장·재접속 데이터 흐름
4. 오류·복구·보안 경계
5. 출시 범위·성능·테스트 게이트

본 문서는 그 승인 내용을 정본 Markdown으로 옮긴 것이다. 다음 단계는 사용자가 이 문서가 승인 내용과 일치하는지 검토하는 것이며, 그 전에는 구현 계획 작성이나 외부 Jira/GitHub 티켓 생성을 시작하지 않는다.
