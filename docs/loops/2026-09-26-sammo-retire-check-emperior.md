# 2026-09-26 #917 — 삼모 전 城 통일 판정(Q14 checkEmperior) 은퇴

## 범위

- 월말 꼬리 Q14 `checkEmperior`를 뺐다. 삼모 규칙의 「모든 城 소유 → 천하통일 → 유산·명예의 전당·황제 기록·통일 이벤트」 경로다. 부(휘하) 통일 판정은 S4 F2가 따로 만든다(모든 州·郡 장악 + 칭제, 190년 漢 州·郡國만 — 2026-09-26 사용자 결정). 이 PR 이후 F2가 들어오기 전까지는 통일 종료가 없다.
- `logic/.../CheckEmperior.kt`와 전용 테스트 2개를 지웠다. `postUpdateMonthlyTail`의 `checkEmperior` 매개변수와 `MonthlyPostUpdateHook`의 전용 `WorldActionContext` 구성을 지웠다.
- `WorldActionContext`: `CheckEmperiorContext` 구현을 떼고 그 인터페이스에만 쓰던 멤버 13개와 그 멤버만 쓰던 보조 함수 20개(국가 연혁 기록, 통계 1행, 유니크 경매 환불, 유산 병합·지급, 명예의 전당, 황제 기록, 통일 이벤트, 침략자 제의 서신)를 지웠다. 다른 문맥(`InvaderEndingContext` 등)과 같은 이름 멤버(`isunited`·`totalCityCount`·`setIsunited`·`multiplyRefreshLimit`·연월·시드)는 그대로 두었다. `pushPreformattedGlobalHistoryLog`는 턴 시간 변경 로그가 쓰므로 private으로 남겼다.
- 생성자 매개변수(`auctionRepository`·`auctionBidRepository`·`archiveHistoryReader`·`statisticSnapshotReader`)는 호출처가 많아 이번엔 두었다. 경매·통계 슬라이스에서 정리한다.
- 결정론: Q14는 월 RNG를 쓰지 않았다. 뒤 순서(Q16 경매 등록 → 전선)는 그대로다.

## 검증 (JDK 21, 결과 XML 이번 실행 생성 확인)

- `:logic:test --tests PostUpdateMonthlyTailTest` 3/3(순서 기대값에서 Q14 제거)
- `:app:game-engine:test` — `WorldActionContextRngTest` 2/2, `MonthlyPostUpdateHookTailWiringTest` 9/9, `YuzhouCampaignInvarianceTest` 4/4(豫州 36순 결정론, 해시 불변), `WorldInvaderEndingContextTest` 7/7
- `naming_lint.py`: retired_reference 5861→5858
- 미실행: `PassChainInvarianceIT`(PostgreSQL, CI가 돈다), game-engine 전체 스위트(CI)
