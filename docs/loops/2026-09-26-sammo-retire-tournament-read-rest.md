# 2026-09-26 #917 — 토너먼트 조회 잔여 제거

## 범위

토너먼트 월말·즉시 명령은 #974·#975에서 은퇴했고, 웹 소비자는 #985에서 지웠다. 남은 조회 API와 화면용 필드를 지운다(화면 전용 읽기 API는 Claude 소유, ADR-LITE-049 2026-09-26 개정).

- 삭제: `TournamentController`(`GET /api/tournament`), `F4Dto`의 토너먼트 DTO 9개, `F4StateText.tournamentTypeText`·`RANKING_TYPES`, `logic/tournament/TournamentDomain.kt`, `TournamentReadContractTest`.
- FrontInfo(`IdentityDto` 전역 블록): `tournamentTermMinutes`·`tournamentState`·`tournamentType`·`isTournamentActive`·`isTournamentApplicationOpen`·`isBettingActive`·`nationBetting` 제거. 모두 `game_env.tournament`에서 나오던 값이고 웹 소비자 0.
- `GlobalMenuController`: 「천통국 베팅」 항목 제거(웹은 이 API를 쓰지 않는다).
- 범위 밖: 개인 설정 `tnmt`(setMySetting 현역), 장수 `tournament` 컬럼, 게이트웨이 리셋 `tournamentTrig`, 예약 서버 ID 목록의 `tournament` 이름, 공통 wire의 고아 토너먼트 타입(골든 wire fixture와 함께 별도 정리).

## 검증 (JDK 21, 결과 XML 이번 실행 생성)

- `:app:game-api:test` 전체 755건, 건너뜀·실패·오류 0.
- `naming_lint.py` 기준선 그대로.
