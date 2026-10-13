# GH1543 잔여 — 역사17 명시 상하관계 출처·초기 계층 시드 후속 기획 인계

- taskId: `opensamguk-gh1543-history-plan-20261013-0831` / project: opensamguk / stage: plan
- repository: peppone-choi/opensamguk, branch `buzz/opensamguk-gh1543-history-plan-20261013-0831/opensamguk-plan`
- 조사 기준: origin/main `8b0f9b3ac0a0c2c93dfc1adb321fc41ef294fdf9` (2026-10-13 fetch, 배정 base `36fa4808`보다 최신)
- 범위 밖: UI/조직도 표시(UI1547 MERGED 2026-10-09), 초상 편집, registry 기획, 용량 문서(retinue-capacity-docs), PR1603
- 이 문서는 기획이다. 제품 구현·QA·병합은 모두 **NOT_RUN**.

## 1. 현황 (직접 확인)

| 항목 | 상태 | 근거 |
|---|---|---|
| GH1543 / OPENSAM-347 | OPEN, DoD 5·6·8 미체크 | `gh issue view 1543` |
| backend 다층·예산·순환 방어 | 병합됨 (PR1552, `f97dc0d2`) | 이슈 본문 |
| 조직도 UI | PR1547 MERGED 2026-10-09 | `gh pr view 1547` |
| 17 시나리오 인벤토리 | 있음: 아카이브15(1010…1120) `UNKNOWN`·links 0, 3190 `GAME_DECLARATION`(retainers 228, rtk14-workbook), 990002 합성 | `infra/src/test/.../ScenarioHierarchyInventoryTest.kt`, `ScenarioPersonPolicies.hierarchyInventory` |
| 역사 출처 주장 | 차단(fail-closed): `requireHierarchyClaim`이 `HISTORICAL_SOURCE`를 무조건 거절, "per-link evidence 스키마 별도 리뷰 전까지" | `ScenarioPersonPolicies.kt:66-70` |
| 아카이브15 런타임 패키징 | 미완료 → GH#596/OPENSAM-237 OPEN | `infra/src/main/resources/scenario`에는 3190·990002만 존재 |

결론: 남은 acceptance는 "링크별 사료 근거 스키마 + 검증기 + 시나리오별 잠정 분류 원장"이다. 다층 저장/reload·조직도·예산 로직은 이미 병합되어 재구현하지 않는다.

## 2. 열린 PR과의 중복 대조

- PR1603(`DispatchInputBoundaryTest`, `data/commands/*`, `tools/ci/test_input_evidence_gate.py`, handoff 문서 2): 겹치는 파일 없음.
- PR1600(`logic/.../record/*`): 겹침 없음.
- PR1599(Retire/Flush/ChangeRecorder/JdbcFlushExecutor): 겹침 없음.
- 용량 문서 작업(`docs/superpowers/specs/2026-09-17-general-and-retinue-campaign-redesign.md` §2.8): 후속 작업에서 이 파일을 편집하지 않는다.

## 3. 남은 acceptance (후속 구현 대상만)

A1. **링크별 근거 스키마**: 시나리오 JSON의 선택 필드 `retainerEvidence: [{general, master, class, sources:[{book, chapter, quote}], asOfYear}]`.
  `class` ∈ `HISTORICAL_EXPLICIT`(사료 원문에 상하 명시) / `PROVISIONAL`(정황·연의만, 잠정) / `GAME_ONLY`(게임 선언만).
A2. **검증기**: `HISTORICAL_SOURCE` 주장은 모든 retainer 링크에 `HISTORICAL_EXPLICIT` 근거(원문 인용 비어있지 않음, `asOfYear` ≤ 시나리오 시작 연도)가 있을 때만 허용. 하나라도 빠지면 `UNKNOWN`/혼합으로 강등. 근거 없는 링크·retainers에 없는 근거 항목·중복은 거절. 관직·능력치에서 상사를 도출하는 경로는 만들지 않는다.
A3. **3190 잠정 분류 원장**: 228 링크 각각을 위 3분류로 표시한 원장. 사료 확인 안 된 링크는 `GAME_ONLY`/`PROVISIONAL` 그대로 두고 인벤토리 결과는 `GAME_DECLARATION` 유지(승격 금지).
A4. **아카이브15 시드 설계안(데이터 원장만)**: 시점별 명시 상사/휘하 후보를 `historical-sources` 근거로만 기재. #596 패키징 전에는 런타임 seed에 넣지 않고 인벤토리 `UNKNOWN`·links 0 단언을 유지.
A5. **회귀**: 명시 관계→DB 저장/reload→3단계 조직도 동일성은 기존 `Scenario3190FirstAssessmentRoundTripIT`/`EnlistmentPersistenceIT` 경로를 재사용, 근거 필드가 저장 관계·예산·첫 정산을 바꾸지 않음을 단언.

## 4. 후속 작업 분할·exact 파일 소유

**작업 H1 (구현, Codex)** — 스키마·검증기
- 수정: `infra/src/main/kotlin/opensamguk/infra/seed/ScenarioPersonPolicies.kt` (`requireHierarchyClaim`·`hierarchyInventory`만), `infra/src/main/kotlin/opensamguk/infra/seed/ScenarioJson.kt` (retainerEvidence 파싱 hunk만)
- 신규: `infra/src/main/kotlin/opensamguk/infra/seed/ScenarioRetainerEvidence.kt`, `infra/src/test/kotlin/opensamguk/infra/seed/ScenarioRetainerEvidenceTest.kt`
- 수정(테스트): `infra/src/test/kotlin/opensamguk/infra/seed/ScenarioHierarchyInventoryTest.kt` (기존 17건 단언 유지, 근거 케이스 추가)
- 검증: GitHub Actions jvm-core/engine 필수 job SUCCESS. 로컬 무거운 빌드 금지.

**작업 H2 (데이터, H1 병합 후)** — 3190 원장
- 신규: `data/scenario-hierarchy/scenario_3190.evidence.json` (228 링크 분류). 출처 인용은 `historical-sources` 스킬 원문만.
- 3190 런타임 JSON에 넣는 것은 H1 스키마 통과·리뷰 후 별도 hunk(`infra/src/main/resources/scenario/scenario_3190.json`의 `retainerEvidence`만). retainers/personPolicies 값은 변경 금지.
- 검증: 기존 IT(공손찬 101/동탁 307 상한, 첫 정산 이탈 0) 불변, 인벤토리 `GAME_DECLARATION` 유지.

**작업 H3 (기획/데이터, #596 의존)** — 아카이브15 시점별 설계안
- 신규: `data/scenario-hierarchy/scenario_<code>.evidence.json` × 15. 런타임 반영은 #596 패키징 완료 후.

의존성: H1 → H2 → (H3 런타임 반영은 #596). H1·H3 원장 작성은 병렬 가능.

## 5. 불변식

- 사료에 없는 상사를 합성하지 않는다. 출처 불명은 잠정 분류로만 둔다.
- 기존 명망 수용량 거절·개별 예산·초기 명부·첫 정산 값을 바꾸지 않는다.
- 접수 202나 fixture 결과를 실제 저장/턴 완료로 쓰지 않는다. DoD 8 실제 USER QA는 별도 승인 대상.
- 운영 배포·GCP·초기화 없음.

## 6. 미검증·blocker

- 미검증: 3190 228 링크의 사료 정합성(이번에 대조 안 함), 아카이브15 실제 역사 관계 자료, 모든 테스트 재실행(NOT_RUN), 실제 USER QA(NOT_RUN).
- blocker: 아카이브15 런타임 반영은 GH#596 OPEN에 막힘. H1 스키마 형태는 총괄/사용자 확인 권장(필드명·3분류).
- 다음 고정 담당: opensamguk 구현 담당(Codex) — H1. 이후 독립 리뷰.
