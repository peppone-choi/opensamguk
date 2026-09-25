# 휘하 예약 거절 사유 정합성

작업 기준: `origin/main` 57c0e13. R-FIX-A-3과 R-SPEC-8의 예약 API 첫 분류를 입력 원장 판정과 일치시킨다.

## 변경

- 예약 서비스가 휘하 입력을 처리하기 전에 `HwihaInputCatalog.rejectionFor`로 형식·규칙 프로필·등록 여부·배달 상태를 판정한다.
- 원장에 등록되고 핸들러가 있지만 예약 경로가 아닌 입력에는 `INVALID_INPUT_CHANNEL`을 돌려준다.
- 입력 계약 문서에서 컨트롤러와 예약 서비스의 오래된 불일치 설명을 제거했다.

## 검증

- 적색: 수정 전 `CommandReserveServiceTest.hwiha reserve classifies ledger delivery before route availability` 실패. `stratagem.play`의 기대값 `NOT_DELIVERED`와 기존 `WRONG_RULE_PROFILE`이 달랐다.
- 초록: `CommandReserveServiceTest`와 `CommandControllerSecurityTest` 통과. 테스트 XML에서 실패 0건 확인.
- `git diff --check` 통과.

## 범위

이 변경은 예약 API의 첫 거절 분류에 한정한다. PLANNED 입력의 핸들러나 화면은 활성화하지 않았다.
