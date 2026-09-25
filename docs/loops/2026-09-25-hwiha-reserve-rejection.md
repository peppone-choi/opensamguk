# 휘하 예약 거절 사유 정합성

작업 기준: `origin/main` 57c0e13. R-FIX-A-3과 R-SPEC-8의 예약 API 첫 분류를 입력 원장 판정과 일치시킨다.

## 변경

- 예약 서비스가 휘하 입력을 처리하기 전에 `HwihaInputCatalog.rejectionFor`로 형식·규칙 프로필·등록 여부·배달 상태를 판정한다.
- 원장에 등록되고 핸들러가 있지만 예약 경로가 아닌 입력에는 `INVALID_INPUT_CHANNEL`을 돌려준다.
- 실제 예약 허용 목록 안에서 원장이 `PLANNED`인 8개 입력(`retire`·`persuadeCaptive`·`resign`·`rise`·`dissolve`·`independence`·`donate`·`tradeEquipment`)은 접수 전에 `NOT_DELIVERED`로 거절된다. 기존에는 접수 뒤 실행 핸들러를 찾지 못했다.
- 원장에 없는 `action.randomEnlist`·`action.targetEnlist`는 예약 허용 목록에서 빼고 `UNKNOWN_INPUT`으로 거절한다. V2 샌드박스의 두 옛 경로는 휘하 세계에서 `WRONG_RULE_PROFILE`로 분류한다.
- 입력 계약 문서에서 컨트롤러와 예약 서비스의 오래된 불일치 설명을 제거했다.

## 검증

- 적색: 수정 전 `CommandReserveServiceTest.hwiha reserve classifies ledger delivery before route availability` 실패. `stratagem.play`의 기대값 `NOT_DELIVERED`와 기존 `WRONG_RULE_PROFILE`이 달랐다.
- 리뷰 적색: 휘하 세계의 `v2CityTransport`가 기대 `WRONG_RULE_PROFILE` 대신 `MALFORMED_INPUT_ID`를 냈다.
- 초록: 리뷰 보강 뒤 `CommandReserveServiceTest` 10건·`HwihaReservableActionsTest` 2건·`CommandControllerSecurityTest` 26건 통과. 테스트 XML에서 실패·오류·건너뜀 0건 확인.
- `git diff --check` 통과.

## 범위

이 변경은 예약 API의 첫 거절 분류에 한정한다. PLANNED 입력의 핸들러나 화면은 활성화하지 않았다.
