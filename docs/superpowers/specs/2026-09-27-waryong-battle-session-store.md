# 와룡전 전투 세션 저장 경계

- Date: 2026-09-27
- Status: draft implementation contract; requires #999 V67 before V68 ready/merge
- Parent: [실시간 전술 전투 계약](2026-09-27-waryong-realtime-tactical-battle.md), ADR-LITE-025

## 소유권과 자료 흐름

캠페인 엔진은 이미 커밋한 불변 handoff와 lock generation·잠금 대상 revision을 제공한다. `BattleSessionCoordinator.open`은 handoff 원문 SHA-256, 참가자·전장·규칙·카탈로그·지형 핀을 검증한다. 전투 저장소는 `battle_*` 테이블만 새 트랜잭션으로 쓰고, 캠페인 `general`·`city`·`world_state` 등은 직접 변경하지 않는다. 세션 결과는 `battle_result_outbox(PENDING)`에서 대기하며, 캠페인 엔진만 `ChangeRecorder -> JdbcFlushExecutor`로 결과를 적용한다. `markApplied`는 그 durable 적용 확인 뒤에만 호출한다.

## V68 테이블

| 표 | 고유 키 | 내용 |
|---|---|---|
| `battle_ticket` | `(world_id,battle_id)` | immutable handoff 원문, SHA·seed·잠금 세대/개정·참가/전투 기한 |
| `battle_participant` | `(world_id,battle_id,participant_id)` | 동결된 계정·장수·진영·초기 권한 revision |
| `battle_session` | `(world_id,battle_id)` | READY→JOINING→RUNNING→RESULT_PENDING→APPLIED 상태, epoch lease, 현재 틱/이벤트 seq |
| `battle_event` | `(world_id,battle_id,event_seq)` | 승인 명령, 사람/AI 전이, 시작/종결의 append-only 입력 로그 |
| `battle_command_receipt` | `(world_id,battle_id,participant_id,client_command_id)` | 승인·거절의 최초 ACK 재현과 서로 다른 내용의 ID 충돌 판정 |
| `battle_snapshot` | `(world_id,battle_id,snapshot_seq)` | 압축된 권위 상태·state hash·처리 완료 event seq |
| `battle_result_outbox` | `(world_id,battle_id,result_revision)` | 결과 원문·replay hash·잠금 개정, PENDING/APPLIED/RESULT_BLOCKED |

JSON은 `TEXT` 원문과 SHA-256으로 저장한다. `jsonb` 정규화가 원래 byte hash를 바꾸지 않도록 하는 조치다. 모든 PK/FK 및 조회 인덱스는 `world_id`가 선행한다. `battle_*`가 일반 캠페인 행을 참조하는 FK를 두지 않아, 봉인 뒤 장수·도시의 생명주기가 리플레이 원문을 지우지 않는다.

## 명령·복구 불변식

1. `claimEpoch`는 DB 시각으로 만료된 lease만 인계하고 epoch를 올린다. `renewLease`, 체크포인트, 결과 쓰기는 epoch·owner·DB lease가 맞을 때만 허용한다.
2. JOINING은 handoff의 DB 참가 기한까지 유지한다. `startRun`은 기한 뒤 RUNNING과 `SESSION_STARTED` 이벤트를 한 트랜잭션에서 확정한다. 입장·이탈·AI 전이는 `appendTransition`으로 event seq와 함께 durable하게 남긴다.
3. 명령 승인에서 `(battleId,participantId,clientCommandId)`의 기존 receipt를 먼저 확인한다. 같은 hash는 최초 ACK를 재현하고 다른 hash는 `IdempotencyConflict`다. 새 명령은 session row lock 아래 권한·epoch·상태·deadline·tick을 확인하고 event와 receipt를 한 트랜잭션에 기록한다.
   액터는 매 100ms 틱 뒤 `advanceTick(expectedTick, expectedEventSeq)`로 session row를 정확히 1틱 CAS 전진시킨다. 입력 조회 뒤 새 event가 커밋되면 `latest_event_seq`가 달라 CAS가 실패하므로 새 꼬리를 읽고 같은 틱을 다시 계산한다. 50틱 간격 checkpoint는 이미 durable해진 `current_tick`과 같은 틱만 기록한다. 재시작 때 마지막 checkpoint부터 durable `current_tick`까지 event seq 순서로 다시 계산한다.
4. actor는 checkpoint의 state hash와 ticket/event SHA를 검증한 뒤 snapshot 이후 입력을 재생한다. 손상된 tail은 전투 결과를 추측해 만들지 않고 격리해야 한다. 이 PR의 `recover`는 ticket/event SHA를 검사하며, versioned binary state codec과 격리 전이는 다음 통합 절편에서 연결한다.
5. `publishResult`는 `BATTLE_RESOLVED` event, result outbox, 세션 상태를 한 트랜잭션에 기록한다. 결과 적용과 전체 리플레이 공개는 캠페인 적용 확인 뒤에만 수행한다.

## 캠페인과 전투 사이의 장애·재시도 계약

### 1. 티켓 생성

캠페인 flush는 잠금 대상의 `lockGeneration`·entity revision, 불변 handoff outbox, 원인 사건별 stable `(worldId,battleId)`를 **한 트랜잭션**에서 확정한다. 전투 레인은 이 커밋 뒤 handoff만 읽어 `open`을 호출한다. `create`는 ticket·session·participant를 별도 **한 battle 트랜잭션**에 넣으며 `(worldId,battleId)` 중복은 동일 원문 SHA와 핀/참가자면 기존 세션을 반환하고 다른 내용이면 충돌로 멈춘다. 따라서 캠페인 커밋 뒤 battle 생성 전에 죽어도 handoff scanner가 같은 ID로 재시도한다. battle 생성 뒤 ACK 전에 죽어도 동일 ID 재시도로 기존 세션을 찾는다. actor는 DB lease를 새 epoch로 인계하며 과거 epoch 쓰기는 거절된다. 캠페인에 없는 고아 ticket은 추측하여 해제하거나 결과를 적용하지 않고 운영 격리 대상으로 남긴다. 현재 PR은 **커밋된 handoff를 입력으로 받는 battle 측**만 구현하며 캠페인 outbox 생성·scanner는 후속 통합 절편이다.

### 2. 결과 적용

`publishResult`는 `(worldId,battleId,resultRevision)` 고유 outbox와 `BATTLE_RESOLVED` event를 같은 battle 트랜잭션에 기록한다. game-engine은 PENDING을 다시 읽을 수 있어야 하고, lock generation·최신 lock set revision·잠긴 entity별 revision을 검사한다. 캠페인 `(worldId,battleId,resultRevision)` 적용 표식, 손실·점령 등 deltas, 잠금 해제, 적용 ACK outbox는 `ChangeRecorder -> JdbcFlushExecutor` **한 flush**에서 확정한다. 같은 결과가 재전송되면 표식에서 기존 ACK를 반환한다. battle `markApplied`가 실패하거나 프로세스가 죽어도 캠페인 ACK를 재전송해 같은 결과 outbox를 APPLIED로 전이한다. revision/잠금 불일치는 캠페인 `BattleResultBlocked` ACK와 잠금 유지 후 battle `markBlocked`로 기록하고 자동 적용하지 않는다. 현재 PR은 battle outbox와 APPLIED/BLOCKED 전이만 구현하며 캠페인 적용 표식·flush hook은 미연결이다.

### 3. 월드 턴과 잠금

원래 전투 진입점은 원인 사건의 티켓·잠금·알림만 확정하고 전투 종료를 기다리지 않는다. 잠금 없는 장수·도시·군단은 계속 턴을 진행한다. 교전 객체의 이동·개인 턴 입력과 같은 대상의 정기 효과는 캠페인 precheck에서 잠금 사유로 거절하거나 원래 순서를 가진 지연 효과로 기록한다. 전투 세션은 캠페인 테이블을 쓰지 않는다. 이 precheck/턴 러너 변경은 현재 PR 범위 밖이며 통합 레인이 기존 심볼 소유를 인계한 뒤 연결한다.

### 4. 참가·AI·재접속 순서

세션은 DB `join_deadline_at`까지 JOINING으로 남고, actor는 입장·배치 변경을 고유 transition ID로 append한다. 60초 뒤 `startRun`은 RUNNING과 `SESSION_STARTED` event를 한 트랜잭션에 확정한다. 미참가 측은 AI로 시작하고, 사람이 중도 입장하거나 이탈할 때 `HUMAN_JOIN`·`HUMAN_LEFT`·`AI_TAKEOVER`를 session epoch·event seq·효력 틱과 함께 append한다. 같은 입력 로그에서는 seq 순서로 재생하고 같은 효력 틱은 seq로 동률을 푼다. 재접속은 새 짧은 JoinTicket의 계정·진영·epoch를 검증한 뒤 마지막 수신 seq 이후의 해당 진영 투영을 준다. 현재 PR은 JOINING 기한, `appendTransition`, event seq 저장까지 제공한다. JoinTicket 서명, WebSocket, 진영별 투영, actor의 10Hz 스케줄러와 사람/AI 인계 호출은 후속 절편이다.

## 아직 연결할 경계

- 캠페인 handoff 및 잠금 생성: `AssignmentMarchTurn`/`EncounterResolver.resolvePending`, `SiegeService.assault`, `TravelTurn`·`TravelHandler`/`PersonalEncounter.settle`.
- 결과 적용·잠금 해제: `TurnRunService`의 `ChangeRecorder -> JdbcFlushExecutor` 단일 flush. `(battleId,resultRevision)` 적용 표식과 잠금 세대·개정 검사가 필요하다.
- 외부 참가: 계정 확인 뒤 짧은 signed JoinTicket, WebSocket, 진영별 투영과 입장/퇴장 전이. 현재 새 coordinator에는 HTTP/WS 엔드포인트가 없고 세션 actor는 아직 없다.
- PG 검증: `JdbcBattleSessionStoreIT`가 Flyway·lease/명령 중복·epoch fence·snapshot·result outbox를 검사한다. V67의 main 병합과 V68의 PG 또는 동등한 원격 CI 증거 전에는 V68을 ready/merge하지 않는다.
