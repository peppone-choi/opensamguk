# 개인 순 flush 불변성 G4 회귀

GH893 / OPENSAM-292, 승인 원장 `2026-09-18-personal-turn-determinism-contract.md`
§2.3·G4만 검증한다. G3 공개 상태 hash 저장과 G6 metrics, Q4/Q5는 이 변경의 범위가 아니다.
제품 schema/serializer/flush/handler와 삼모 골든을 변경하지 않는다.

## 실제 경계와 입력

`PersonalTurnFlushInvarianceIT`는 Docker PostgreSQL 16에 실제 Flyway migrations를 적용한다.
기존 `EnlistmentFixture`의 실제 1133 지도와 snapshot loader를 재사용하며 월드 893,
200년 1월 1순, 초기 세계 버전 0, 동일 초기 DB 행을 매 실행 새로 만든다.
장수 1(user 42, 순 시각 +10초)이 장수 10에게 돈 40을 증여하고,
장수 10(user 43, +20초)이 무력을 단련한다. 두 입력은 동일 province의 공유 장수 상태를
가로지른다. 장수 2는 다음 날로 미뤄 처리 대상에서 제외한다.
예약 slot 0, request ID, args, 실제 inbox owner binding을 고정한다. slot 1 요양은
처리 후 slot 0으로 당겨져야 하며 예약 revision은 실제 DB trigger로 갱신되어야 한다.

한 호출(+21초)과 두 호출(+11초, +21초)은 실제 `TurnRunService`→
`TurnDaemonLifecycle`→`ReservedTurnHandler`→`ChangeRecorder`→`DatabaseHooks`→
`JdbcFlushExecutor` SQL transaction을 사용한다. 실제 command result/outbox 저장과
publication marking도 수행한다. Redis 수송만 mock이며 API admission·실 Redis·UI 검증은 아니다.

매 최종 상태는 모든 migrated world_id 테이블과 world_state의 실제 SQL 행을 읽는다.
새 `WorldSnapshotLoader`의 독립 DB cold reload도 별도 digest로 비교한다.
행 집합은 canonical key 순서, JSON 객체는 key 정렬, JSON 배열 및 로그/이벤트 순서는 보존한다.
테스트 보고서는 `app/game-engine/build/reports/g4-flush-invariance/`에 저장된다.

## 테스트 전용 정규화

공개 hash가 아닌 SHA-256 테스트 oracle이다. 세계 버전은 실제 flush 수(1 대 2),
각 결과 committed version(1/1 대 1/2)을 DB와 cold loader에서 먼저 정확 단언한 뒤 제외한다.
result/outbox envelope의 committedWorldVersion와 sentAt만 transport projection에서 제외한다.
created_at/updated_at은 명시한 table/column whitelist에만 적용하며 game turn_time,
expiry와 gameplay metadata를 보존한다. outbox published_at의 wall clock 대신 실제 published
boolean을 보존하고 두 request가 각각 발행됐는지 단언한다.
예약 초기 UUID는 고정해 검증하고 final CAS revision의 실제 변경을 단언한 뒤 UUID 값만 제외한다.
로그와 game_event ID·내용·순서는 보존한다. 일괄 nested field 제거는 없다.

## 결함 검출

정상 시험은 동일 초기 hash, 실제 처리 순서/입력/owner binding, 저장 상태와 cold state의
동일 hash, 증여 후 960/1040, 단련 후 무력 71을 요구한다.

1. SQL 저장 누락 probe: updatedGenerals가 1개인 실제 flush에서 그 write만 제거한다.
   두 호출의 마지막 단련만 DB에서 사라져 hot 무력 71, cold 무력 70이 된다.
2. 승인 G4의 flush 경계 probe: 성공 flush 직후 아직 처리하지 않은 장수 10의 hot
   projection 무력을 cap 80으로 바꾼다. 같은 decorator가 양 스케줄에 적용된다.
   일괄 실행은 두 장수 처리 후 flush하므로 변화가 없고, 두 호출에서는 실제 다음
   PersonalHandler가 동일 단련 입력을 TRAINING_MAXED로 거절한다.

영구 negative controls는 이 두 실제 차이와 unchanged equivalence assertion의 실패를 요구한다.
추가 실제 RED 증거는 `OPENSAMGUK_G4_FLUSH_PROBE`를 각각 enum 이름으로 지정하고
정상 G4 시험만 실행한다. 원 assertion이 실패해야 하며 이 예상 RED를 정상 suite 실패나
정상 PASS에 섞어 집계하지 않는다. Docker 미가용 skip은 G4 검증 성공이 아니다.

## 실행

JDK 21에서 `:app:game-engine:test --tests opensamguk.engine.invariance.PersonalTurnFlushInvarianceIT`
를 실행한다. 결함 RED와 최종 정상 실행의 raw XML을 각 단계 별도 보존한다.
