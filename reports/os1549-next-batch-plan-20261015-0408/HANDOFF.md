# HANDOFF — os1549-next-batch-plan-20261015-0408

- project: opensamguk / stage: plan → implement
- repository: https://github.com/peppone-choi/opensamguk
- 기획 branch: `buzz/os1549-next-batch-plan-20261015-0408/opensamguk-plan` (기획 문서 전용, PR을 만들지 않음)
- 구현 기준 base: 원격 `main` `272248285dbfe6dd3c1fc15bce4cac8225053633`
- 이슈: GH1549 / OPENSAM-350 (상위 GH596 / OPENSAM-237). 모두 OPEN 유지.

## 다음 담당에게
- **opensamguk-implement**: `PLAN.md` §4 명세대로 B4 5명(조우 10399·이적 10019·공지 10909·손환 10245·학맹 10810)을 구현한다. 소유 파일은 정확히 2개다.
  - `tools/scenario/materialize_hwiha_scenarios.py`: `REVIEWED_RTK14_PROFILE_VARIANTS`에 5행 추가
  - `tools/scenario/tests/test_materialize_hwiha_scenarios.py`: ambiguous 집합을 12→7로 줄이고, 집계 단언을 갱신하고, 양성·음성 회귀를 추가
- 이어서 **opensamguk-review**가 독립 리뷰하고, 병합 담당이 기존 PR 정책대로 처리한다.
- 문서·프론트엔드·서버 Kotlin 역할은 이번 배치에 필요 없다.

## 침범 금지
- #1456: PR1619(CI 대기)
- PR1614: 검증 run aa8ce012
- PR1618: PR1614 의존
- 열린 PR 9개의 변경 파일 중 `tools/scenario`·초상·registry와 겹치는 파일은 0개다(`evidence/open-pr-overlap.txt`).

## 근거 (evidence/)
- `remote-main.txt`: 원격 main SHA
- `unresolved-inventory.{txt,json}`: 잔여 306행·56 신원·59 fingerprint·군주 정책 누락 5건
- `family-fingerprints.txt`: classic/alternate 동명 행 대조
- `simulation-single.txt`, `simulation-batch.txt`: 메모리 monkeypatch 시뮬레이션. 5명 → 292행/51, 고담륭은 중복 오류
- `rtk14-official-presence.txt`, `rtk14-absence-check.txt`: 공식 RTK14 1000명 목록(gamecity, 2026-10-15 04:12Z 조회)과 registry 대조
- `historical-sources.txt`: 三國志·三國演義 원문 조회
- `cdn-check.txt`: 대상 5 ID×3종 CDN 200·매직바이트 확인
- `*.py`: 위 결과를 재현하는 읽기 전용 스크립트. 저장소 루트를 인자로 받는다.

## Blocker
- B4 자체: 없음. 즉시 구현할 수 있다.
- 잔여 RTK14 미수록 35명(41 신원)의 초상: ADR-LITE-048 예외는 RTK14에만 적용된다. 다른 출처 초상을 쓰려면 **사용자 결정**이 필요하다(PLAN §5 대안 b/c). 결정이 없어도 가능한 대안 a는 'RTK14 미수록·기본 초상·잔여' 분류를 이슈에 기록하는 것이다.
- 고담륭 원천 중복·유대연주·군주 정책 5건: #596 레인.

## NOT_RUN
- 단위 시험(코드 변경 없음)
- GitHub CI
- DB cold reload, API 서빙, 실제 화면
- Jira OPENSAM-350 직접 조회
- 나무위키 웹 화상 조사(B 범위는 라이선스 결정이 먼저 필요해 수행하지 않음)
- 동명이인 7명 정밀 사서 판정
- 제품 QA
