# HANDOFF — 초기 휘하 상한 명세 정합 (문서 → 리뷰)

- taskId: `opensamguk-retinue-capacity-docs-20261013-0718`
- project: opensamguk / stage: docs
- repository: https://github.com/peppone-choi/opensamguk
- branch: `buzz/opensamguk-retinue-capacity-docs-20261013-0718/opensamguk-docs`
- cwd: `/srv/openclaw-dev/company/agents/opensamguk-docs/worktrees/opensamguk-retinue-capacity-docs-20261013-0718`
- base: `ce89a2d8263eeec4733e72ad804fe7d741c9cc2f` (#596 기획 문서 commit) / origin main `a8511f6ebc64bbd8b1cf0f489f591f3ff40ef745` (작업 시 `git fetch` 재확인, 변화 없음)
- commit: 이 문서를 담은 커밋 (SHA는 총괄 인계 메시지에 기록)
- 다음 담당: opensamguk-review

## 변경

`docs/superpowers/specs/2026-09-17-general-and-retinue-campaign-redesign.md` §2.8 한 곳.

1. 삭제: "역사 인물 시드의 능력치 출처 검증은 미구현이다." — 현재 코드와 맞지 않음.
2. 추가: 시나리오 시작 상한 = 군주 직속 휘하 코스트 합 + 30, 선언 불일치·월단평 상한 초과 거절, 비군주 시드 상한 30, 비군주 휘하 주인 상한 검사, 3190 실측(공손찬 101 / 동탁 307 / 첫 정산 이탈 0).
3. 추가: 능력치 출처 결속(합성 QA 또는 RTK14 `rtk14-workbook:190.1` 고정 revision)과 "게임 데이터 출처이며 사료 대조가 아님" 명시. 남은 역사 시나리오 이관은 GH #596 / OPENSAM-237 미결로 유지.

정책 변경·이탈 해방 규칙 창작 없음. 제품 코드·테스트·seed·CI 변경 없음.

## 근거 (직접 확인)

- `infra/.../seed/ScenarioPersonPolicies.kt`: `startingRulerCapacities`(cost + `RenownRules.INITIAL_CAPACITY`, ceiling 검사), `validate`(비군주 = 30), `validateOrdinaryStartingRetinues`, `requireApprovedSource`(synthetic-qa 또는 RTK14 source+revision+officerId 10001..11000), 초상 ID 일치.
- `logic/.../renown/RenownRules.kt`: `INITIAL_CAPACITY = 30`.
- `infra/.../seed/ScenarioJson.kt` 192–200행: 파싱 시 군주 상한을 계산값으로 설정. `ScenarioImporter.kt` 672–680행: 선언값 ≠ 계산값이면 거절.
- `scenario_3190.json`: personPolicies 1000건 전부 RTK14 동일 source/revision, retainers 228.
- `Scenario3190FirstAssessmentRoundTripIT.kt` 145–146행: 공손찬 101, 동탁 307 단언; 228행 불일치 거절 메시지 (PR #1602, 테스트 재실행 안 함).
- PR #1515 MERGED(2026-10-08, `3ba793ef`): 남은 manual53·동명이인12·재야 위치·역사15 packaged 조건은 #596 OPEN. `gh issue view 596` = OPEN.

## 미검증

- 문서가 인용한 IT 결과는 #1602 CI 기록에 기대며 이번에 재실행하지 않았다.
- RTK14 능력치의 사료 정합성은 미확인으로 남긴다.
- 제품 QA: NOT_RUN.

## 검증

- `git diff --stat`: 소유 2파일만 변경. Markdown 수동 확인.
- push/PR/병합/배포 없음 (지시 범위).

## blocker

없음.
