# 휘하 조정 대기 메타 오류 격리

작업 기준: `origin/main` 57c0e13. P0 턴 격리의 첫 독립 슬라이스로, 손상된 조정 대기 메타가 장수 턴을 중단하는 경로를 닫는다.

## 변경

- 발령 대기 메타 파싱 예외를 잡는다. 상사·기존 조정·계책 대기 메타도 기존의 조용한 무시 대신 실패로 확정한다.
- 손상된 대기 키를 제거하고 개인 기록에 `STATE_UNAVAILABLE`을 남긴다. 유효한 요청 ID와 소유자 ID를 읽을 수 있으면 폴링 가능한 실패 결과도 같은 flush에 싣는다.
- 조정 대기 입력은 실행 직전에도 같은 입력 원장의 배달 상태를 검사한다. 대기 중 `PLANNED`로 내려가거나 원장에서 빠진 입력은 효과를 실행하지 않고 사유가 있는 터미널 실패로 확정한다.
- 리뷰 지적을 반영해 계책 폴백 식별자 `stratagem.unknown`도 결과 종류를 `STRATAGEM`으로 분류한다. API 발령 사전검사의 기존 예외 처리 범위는 손상 큐 테스트로 확인한다.
- 다른 대기열과 개인 행동 처리는 계속한다.

## 검증

- 적색: 수정 전 손상된 발령 메타를 넣은 `HwihaLegacyCourtHandlerTest`가 `IllegalArgumentException`으로 실패했다.
- 초록: 같은 테스트 클래스 전체 통과. 네 대기열의 손상된 메타 제거·실패 결과를 확인했다.
- 실제 `TurnDaemonLifecycle.runTick`에서 손상된 발령 장수와 뒤따르는 장수의 `turnTime`이 모두 전진함을 확인했다.
- 발령을 예약한 뒤 원장 상태를 `PLANNED`로 내린 테스트에서 `NOT_DELIVERED` 결과와 대기 키 제거를 확인했다.
- `court.releaseCorps` 대기 입력의 원장을 `PLANNED`로 내렸을 때에도 비용·효과 없이 `NOT_DELIVERED`가 나옴을 확인했다. 계책 폴백의 결과 종류와 API의 손상 큐 사전검사·옵션·대기 조회도 검증했다.
- 리뷰 반영 후 `HwihaLegacyCourtHandlerTest` 10개와 `HwihaDispatchPrecheckServiceTest` 13개 통과(XML 실패·오류·건너뜀 0건).

## 남은 P0 범위

엔벨로프·월 경계 단계·임의의 핸들러 실행 예외에는 아직 안전한 롤백 경계가 없다. `InMemoryTurnWorld`와 `ChangeRecorder`가 함께 부분 변경될 수 있어 단순 catch 뒤 진행하면 잘못된 효과가 flush될 수 있다. 그 경계를 마련한 뒤 러너 경유 적색 프로브와 연속 실패 3회 격리를 추가한다. 이 PR을 전체 턴 격리 완료로 표시하지 않는다.
