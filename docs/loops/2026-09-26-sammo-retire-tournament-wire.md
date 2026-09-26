# 2026-09-26 #917 — 고아 토너먼트 wire 타입 제거

- 대상: 공통 wire의 토너먼트 명령 4개(`tournamentRefund`·`tournamentBettingPayout`·`tournamentReward`·`tournamentMatchResult`)와 결과 8개(Ok/Fail), 결과 디코더 분기 4줄, 이들만 쓰던 `MatchResult` enum. 디스패처가 처리하지 않는 고아였다(토너먼트 런타임은 #974·#975에서 은퇴). 선례: 토너먼트 즉시 명령 은퇴(`a288495bd`)도 호환 장치 없이 지웠다.
- 골든 fixture: `wire_commands_valid.json` 27→23, `wire_results.json` 44→36. `TurnDaemonCommandWireTest`의 합집합 기대값과 크기 잠금(23)을 맞췄다.
- 범위 밖: 개인 설정 `tnmt`(setMySetting 현역, 주석의 「tournament enroll」), `SeedSerializer.TournamentRngContext`와 RNG fixture(생성 도구 `tools/rng-dump`와 함께 따로).
- 검증(JDK 21): `:common:test` 268/268(결과 XML 이번 실행), `:infra`·`:app:game-engine`·`:app:game-api` 테스트 소스 컴파일 통과, naming lint 기준선 그대로.
