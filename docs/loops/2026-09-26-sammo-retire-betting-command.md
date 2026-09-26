# 2026-09-26 #917 — 베팅 명령·조회 API 제거(B1)

## 범위

웹 소비자는 #985에서 지웠다. 베팅 입력·조회 API를 지운다. 이벤트 액션(국가 베팅 열기·닫기)·세계 문맥·recorder/flush·엔티티·저장소·표 삭제는 B2(엔진 쓰기 쪽)로 남긴다.

- 삭제: `BettingController`·`BettingDto`(game-api), `PlaceBetHandler`(engine), `BettingControllerTest`·`PlaceBetHandlerTest`.
- 명령 경로: `CommandWireMapper`의 `placeBet` 코드·매핑, 공통 wire `PlaceBet` 명령과 `PlaceBetOk/Fail` 결과·디코더 분기, 디스패처의 핸들러·분기·`bettingRepository` 매개변수, `TurnRunService`의 `bettingRepository` 전달.
- 상수: `GameConst.minGoldRequiredWhenBetting`과 `GET /api/const`의 같은 키.
- `HotColdCatalog` 디스패처 항목: `repo.findByTable` 3→2, `repo.sumAmountByBettingIdAndUserId` 제거, 관계에서 `betting` 제거(가드는 호출·횟수 정확 일치).
- 유지: `DaemonLoopConfig.turnRunService`의 `bettingRepository` 빈 매개변수(세계 이벤트 문맥이 아직 씀, `RehydrateWiringTest`가 이 선언을 본다), `BettingRepository`·`ng_betting`·국가 베팅 이벤트 액션(B2).

## 검증

- **로컬 Gradle 미실행.** 디스크 여유 1.24 GiB로 하한(1.5 GiB) 아래라 규칙대로 빌드를 멈췄다. 정적 대조만 했다.
  - 디스패처 저장소 호출: `findByTableAndNamespaceAndKey` 1·`findByTable` 2·`findPollState` 1·`findMessage` 1 = 카탈로그와 일치.
  - `placeBet`/`PlaceBet`/`minGoldRequiredWhenBetting` 본문·테스트 참조 0(주석 KDoc 정리).
  - naming lint 기준선 그대로.
- CI(jvm-core·game-engine, 건너뜀 금지 가드)가 컴파일·테스트를 판정한다.
