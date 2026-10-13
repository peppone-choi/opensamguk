# GH1543 H1 구현 인계

- taskId: opensamguk-gh1543-implement-20261013-0840
- project/stage: opensamguk / implement
- repository: https://github.com/peppone-choi/opensamguk
- branch: buzz/opensamguk-gh1543-implement-20261013-0840/opensamguk-implement
- worktree: /srv/openclaw-dev/company/agents/opensamguk-implement/worktrees/opensamguk-gh1543-implement-20261013-0840
- 코드 commit: cfe5979c9ca9cdad4acb088cb7f2d57a351c2d6c
- PR: https://github.com/peppone-choi/opensamguk/pull/1605
- 다음 담당: opensamguk-review

## 실제 산출물

1. ScenarioRetainerEvidence.kt: 3분류·원문 출처·기준연도 타입과 엄격 검증. 중복/미선언 링크/비정수·미래연도/빈 원문/예상 외 필드 거절.
2. ScenarioJson.kt: 선택적 근거 파싱·Scenario 메타데이터 필드만. 관계/예산/런타임 데이터는 변경하지 않음.
3. ScenarioPersonPolicies.kt: inventory/claim hunk만. 모든 링크의 명시 근거가 충족되어야 HISTORICAL_SOURCE. 혼합/누락 UNKNOWN. 합성 QA 승격 금지. 완전 GAME_ONLY RTK14는 GAME_DECLARATION 유지.
4. ScenarioRetainerEvidenceTest.kt/ScenarioHierarchyInventoryTest.kt: 엄격 검증, 혼합/누락, root와 parsed 근거 위조 대조, 기존17건, 3190 메타데이터 제외 전체 모델 동일성·seed contract 단언.

## 실제 관측 및 검증 경계

- 착수 origin/main: 8b0f9b3ac0a0c2c93dfc1adb321fc41ef294fdf9; GH1543 OPEN; 열린 PR1603/1600/1599. 소유 파일 중복 없음.
- git diff --check PASS. 승인된 5개 코드·시험 파일 외 H2/H3 데이터·런타임 JSON·용량 문서 변경 없음.
- PR 생성으로 GitHub-hosted CI 실행 시작: https://github.com/peppone-choi/opensamguk/actions/runs/38371274227 . 이 문서 작성 시 IN_PROGRESS이며 PASS라고 보고하지 않음. 문서 후속 head의 jvm-core/game-engine 결과를 별도 직접 확인해야 함.
- 기존 DB 저장/reload·첫 정산 회귀는 app/game-engine의 Scenario3190FirstAssessmentRoundTripIT/EnlistmentPersistenceIT를 수정하지 않고 기존 CI에서 재사용.
- 스키마 시험의 인용은 명백한 fixture 문자열. 실제3190 사료 검증/228링크 원장 작성은 H2이며 이 구현의 성공으로 간주하지 않음.
- NOT_RUN: 로컬 JVM 빌드·무거운 시험(정책), 독립 리뷰, 실제 USER QA/턴/UI, 운영 배포/GCP/초기화/병합.

## blocker 및 후속

- H1 코드·PR 생성 blocker 없음. 필수 CI 성공 확인과 독립 리뷰 전 병합 가능 판정 금지.
- H2 실제 사료 원장 및 H3 역사15 런타임 반영(#596)은 이번 범위 밖. GH1543 DoD 잔여와 이슈 OPEN 유지.
