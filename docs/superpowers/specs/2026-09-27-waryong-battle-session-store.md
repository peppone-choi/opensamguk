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
4. actor는 checkpoint의 state hash와 ticket/event SHA를 검증한 뒤 snapshot 이후 입력을 재생한다. 손상된 tail은 전투 결과를 추측해 만들지 않고 격리해야 한다. 이 PR의 `recover`는 ticket/event SHA를 검사하며, versioned binary state codec과 격리 전이는 다음 통합 절편에서 연결한다.
5. `publishResult`는 `BATTLE_RESOLVED` event, result outbox, 세션 상태를 한 트랜잭션에 기록한다. 결과 적용과 전체 리플레이 공개는 캠페인 적용 확인 뒤에만 수행한다.

## 아직 연결할 경계

- 캠페인 handoff 및 잠금 생성: `AssignmentMarchTurn`/`EncounterResolver.resolvePending`, `SiegeService.assault`, `TravelTurn`·`TravelHandler`/`PersonalEncounter.settle`.
- 결과 적용·잠금 해제: `TurnRunService`의 `ChangeRecorder -> JdbcFlushExecutor` 단일 flush. `(battleId,resultRevision)` 적용 표식과 잠금 세대·개정 검사가 필요하다.
- 외부 참가: 계정 확인 뒤 짧은 signed JoinTicket, WebSocket, 진영별 투영과 입장/퇴장 전이. 현재 새 coordinator에는 HTTP/WS 엔드포인트가 없고 세션 actor는 아직 없다.
- PG 검증: `JdbcBattleSessionStoreIT`가 Flyway·lease/명령 중복·epoch fence·snapshot·result outbox를 검사한다. V67의 main 병합과 V68의 PG 또는 동등한 원격 CI 증거 전에는 V68을 ready/merge하지 않는다.
