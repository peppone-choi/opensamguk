# 개인 턴 재현 해시 G3 검증

#893 / OPENSAM-292의 G3만 다룬다. 승인된
[개인 턴 결정론 계약](../superpowers/specs/2026-09-18-personal-turn-determinism-contract.md)
§2.3·G3에 따른 test-only 검증이다.
Q4는 이번 범위에서 시험 보고서에 해시를 계산·기록하는 것으로 한정한다.
제품 영속 스키마나 공개 해시 형식을 만들지 않으며 전체 이슈 완료를 뜻하지 않는다.

## 실행 경계

`G3PersonalTurnReplayFixture`는 독립된 합성 휘하 월드 893과 두 소유 장수(1·10),
고정된 육상 위치 p1, 200년의 36순 및 72개 명시적 예약 입력을 사용한다.
실제 `TurnDaemonLifecycle`·`ReservedTurnHandler`·개인 행동 handler를 호출한다.
단련과 요양을 번갈아 실행하고 실제 Applied 결과, 실제 기록 생성,
모든 입력의 정확히 한 번 조회·소비와 순서를 단언한다.

각 예약은 고정된 장수·소유자·requestId·args·turnTime·연월순을 가진다.
같은 시작 상태와 입력 기록으로 독립 월드를 두 번 구성한다.
장수 시각은 순 시작 +10초·+20초이며 호출 시각은 해당 시각 +1초다.
매 호출에는 정확히 한 장수만 마감되고 `runTick` 반환 후 상태를 읽는다.
예약 링 소비, 개인 턴 시각 +3600초, 처리 순 표식까지 포함한 완료 턴을 검증한다.
세계 달력은 입력 기록의 연월순을 그대로 적용한다. 월간/세계 경계 pipeline,
AI·이동·전투·확률 입력의 전수 재현이나 DB flush·API·Redis 검증은 아니다.
기존 G1 동률 시험과 G4 PostgreSQL 묶음 시험은 별도 기준선이다.

## 지문 범위와 사슬

`g3-personal-turn-v1` SHA-256 시험 형식이며 기존 `WorldStateBaseline`을 재사용하지 않는다.
공개 getter로 얻는 WorldSnapshot의 세계 상태·config·meta, 장수·도시·국가·부대·외교·
접근 기록·휘하·부곡·작전·참여 부대·전투 계획·공성·공간 투영과 합성 예약 큐를 담는다.
fixture에 없는 채널은 빈 값이며 정적 도시→省 바인딩도 포함한다.
장수/세계 metadata는 통째로 보존하므로 카드, 처리 표식, 식별자와 시각도 포함된다.
숨겨진 내부 allocator/dirty journal은 공개 상태 투영이 아니며 직접 해시하지 않는다.

JSON 객체 key와 entity 집합만 정렬한다. JSON 배열, 입력·로그 실행 순서는 유지한다.
null·숫자·문자열·boolean 타입과 key를 보존한다. Id/Time/Hash/Revision/Version
접미사나 값에 따른 광범위 제외 규칙은 **0개**다. 운송 시각 제거도 하지 않는다.
실제 `peekLogs()`를 사용하며 새 로그의 모든 필드·문장·metadata·연월순을 해시한다.
typed GameEvent buffer에는 non-draining reader가 없어 이번 범위에서 제외한다.
`consumeDirtyState()`나 reflection으로 그 버퍼를 읽지 않는다.

초기 link는 형식·고정 hiddenSeed·초기 상태 지문·전체 입력 기록 지문에 결합한다.
매 턴은 실제 전달 예약의 입력 지문, 실행 전/후 상태 지문, 새 로그와 Applied 결과 지문을 기록한다.
변화 지문은 실행 전/후 상태·로그·결과 지문에 결합하고, link는 직전 link·순서 키
`(turnTime,generalId)`·연월순·실제 입력 지문·변화 지문에 결합한다.
두 replay의 모든 턴 프레임과 link, 최종 상태 및 누적 로그 지문을 비교한다.
보고서는 `app/game-engine/build/reports/g3-personal-turn/*.json`이다.

## Mutation 검증

- `HIDDEN_STATE`: 턴 ordinal 8의 실제 field-action 결정 직전에 장수 1의 무력을 +1 한다.
  다음 턴에서 -1로 복원하므로 최종 상태와 로그는 대조 replay로 수렴하지만,
  중간 완료 턴과 이후 link가 달라져 동일 비교 assertion은 ordinal 8에서 실패해야 한다.
- `INPUT_TAMPER`: 원 입력 기록은 그대로 두고 같은 ordinal의 전달 예약 인자를
  strength에서 leadership 단련으로 바꾼다. 모든 request는 여전히 소비되지만
  실제 입력 지문과 상태가 달라져 동일 assertion은 ordinal 8에서 실패해야 한다.

영구 negative controls가 동일 assertion의 실패를 확인한다. 추가 실제 RED는 아래 환경변수로
정상 replay 시험 자체를 실행해 얻으며, 정상 suite PASS와 따로 보존한다.
mutation은 전용 fixture의 합성 상태/예약 callback에만 있고 제품 경로는 수정하지 않는다.

## 실행

JDK 21에서 다음을 실행한다.

```sh
./gradlew :app:game-engine:test --tests opensamguk.engine.invariance.G3PersonalTurnReplayTest
OPENSAMGUK_G3_REPLAY_PROBE=HIDDEN_STATE ./gradlew :app:game-engine:test \
  --tests '*G3PersonalTurnReplayTest.G3 same start*' --rerun
OPENSAMGUK_G3_REPLAY_PROBE=INPUT_TAMPER ./gradlew :app:game-engine:test \
  --tests '*G3PersonalTurnReplayTest.G3 same start*' --rerun
```

RED 실행의 nonzero exit·실패 XML과 최종 정상 suite XML을 각각 보존한다.
G4·G6, 제품/schema/공통 serializer/flush, 기존 공유 fixture·baseline은 수정 범위 밖이다.
