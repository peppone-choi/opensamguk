# GH1549 / OPENSAM-350 누락 NPC 초상 — 잔여 inventory·후속 최소 보완 기획 인계

- taskId: opensamguk-gh1549-portrait-plan-20261013-0831 / stage: plan / 담당: opensamguk-plan
- 조사 기준: `origin/main` = `8b0f9b3ac0a0c2c93dfc1adb321fc41ef294fdf9` (2026-10-13 fetch). 준비 base `36fa4808f`는 오래된 기준으로 사용하지 않음.
- GH1549: OPEN (원격 확인). 이 문서는 기획이며 제품 완료·초상 서빙 완료를 뜻하지 않는다.

## 1. 이미 끝난 범위 (중복 구현 금지)
- PR1553(5명), PR1555 → main `ef0fef9`(CI 37750335826 SUCCESS, 이슈 댓글 2026-10-09): 단경/진복/휴고 3명 원42행 결속. 현재 main `tools/scenario/materialize_hwiha_scenarios.py:386-416` `REVIEWED_RTK14_PROFILE_VARIANTS`에 반영 확인.
- RTK14 registry: `infra/src/main/resources/scenario/scenario_3190.json` general 1000행(각 행 `NNNNN.png` picture). 재수집 대상 아님.

## 2. 열린 PR 대조 (겹침 없음)
| PR | 변경 파일 범위 | 초상/시나리오 materializer와 겹침 |
|---|---|---|
| 1603 | court 발령 증거 부채(`data/commands/*`, `DispatchInputBoundaryTest.kt`, `tools/ci/test_input_evidence_gate.py`, docs/plans 2건) | 없음 |
| 1600 | `logic/.../record/*` EV-D1 | 없음 |
| 1599 | Retire 정리(engine/infra persistence) | 없음 |
용량 문서·1603 파일은 후속 소유에서 제외한다.

## 3. 실측 잔여 inventory (main 8b0f9b3, 읽기 전용 projection 실행)
`prepared_materialization()` 메모리 실행 결과(파일 쓰기 없음):
- 역사 15 시나리오(ACTIVE_CODES 1010…1120), 미결 행 318, 고유 신원 57 (이슈 댓글의 "미결 57"과 일치).
- **미검토 군주 정책 누락**: 1010 `유굉`, 1021 `유대연주`·`진온`·`유총`, 1031 `유총` → `materialized()`가 ValueError로 막는 직접 원인 중 하나.
- 57 신원 분류(scenario_3190 general 이름 대조):
  - (A) 동명 RTK14 행 존재·생몰 불일치 → **동명이인/추정차 검토 후보 13**: 공지(178-197 vs 178-242, 10909), 두씨(172-236 vs 172-221, 10462), 손환(166-228 vs 손환 197-228 10245 / 손환1 195-234 10236), 유순(172-255 vs 184-239), 유씨(164-215 vs 164-205, 10087), 유주(172-233 vs 189-233), 이적(162-273 vs 162-226, 10019), 장의(153-215 vs 167-208), 장초(168-196 vs 160-195), 조우(199-278 vs 199-260), 조절(190-260 vs 196-260), 학맹(156-300 vs 156-197, 10810). 이름 일치만으로 결속 금지.
  - (B) RTK14 1000 내 동명 없음 → 44: 강경, 건석, 고담륭, 관로(2변형), 교국로, 교현, 길평, 낙준, 남두, 독발수기능, 변장, 북궁백옥, 사마준, 사마휘(2변형), 사의관, 송건, 아들노숙, 악신, 예형(2변형), 우길, 원사, 원혁, 유굉, 유대연주, 유변, 유약, 유위대, 유총, 유협(2변형·동명이인 가능), 이리, 이이자, 장량(153-185; 153-184 변형은 이미 10321 결속), 장양2, 주한, 지양군, 진온, 허소(2변형), 화타(2변형), 황승언. 일부는 표기차(예: 사마휘↔司馬徽, 화타↔華佗, 허소↔許劭, 건석↔蹇碩)로 RTK14 다른 한글표기 행이 있을 수 있으나 **미검증**.
- 전체17 = 역사15 + `scenario_3190`(registry 자체) + `scenario_990002`. 990002 NPC 초상 결속 상태는 **NOT_RUN(미조사)**.

## 4. 후속 최소 보완안 (구현 1 PR, 한 기능)
목표: (A)13 + (B) 중 RTK14 표기차로 확증 가능한 신원만 기존 초상 재사용 결속, 미결 수를 실측으로 감소. 신규 화상 수집·합성 없음.

**정확 파일 소유 (2파일, 다른 레인과 비중첩)**
1. `tools/scenario/materialize_hwiha_scenarios.py` — `REVIEWED_RTK14_PROFILE_VARIANTS` / `REVIEWED_RTK14_ALIASES` 행 추가만. 판정 로직 변경 금지.
2. `tools/scenario/tests/test_materialize_hwiha_scenarios.py` — 신규 행 양성 + 동명이인 음성 회귀.
(`--write` 결과 packaged 시나리오 변경은 15개 미결이 0이 되기 전엔 `materialized()`가 막으므로 이번 PR에서 발생하지 않음. 발생 시 범위 초과로 중단·보고.)

**인수 조건**
- 신원별 근거표(한글/漢字/생몰 출처/원천 tuple 3능력/대상 officerId·picture)를 PR 본문에 첨부. 漢字·인물 일치는 `historical-sources` 원문 인용 또는 나무위키 등 명시 URL 근거. 근거 없으면 미결 유지.
- 결속은 (이름, 생몰, 3능력) exact fingerprint + archive 명부 한정. (A) 동명이인 판정 시 결속하지 않고 음성 회귀 추가.
- projection 실측: 중복0 유지, 미결 57 → 실제 감소치(예측치 금지), 총 946 불변.
- 군주 정책 누락 5건은 이 PR 범위 밖(정책·역사계층 기획 레인 소관)으로 표기만, 임의 정책 생성 금지.
- CI: 관련 job 초록 + 작성과 분리된 독립 리뷰 후 병합.
- DB cold reload·API 서빙·동일인 UI 확인은 승인 QA 전까지 OPEN 유지.

**의존성**: 없음(PR1603/1600/1599와 독립). 다른 두 기획(역사계층/registry)과 경계: 군주 정책·registry 확장은 그 레인.

## 5. 검증 근거
- 명령: `python3 -I -c "import tools.scenario.materialize_hwiha_scenarios as m; m.prepared_materialization()"` (repo root) → codes 15 / unres rows 318 / unique 57 / missing rulers 위 5건.
- 동명 대조: `scenario_3190.json` general 1000행 이름·생몰·3능력 직접 조회.

## 6. 미검증 (NOT_RUN)
- 초상 실제 파일 존재/서빙(파일은 git 미추적, 서빙 위치 미확인), DB reload, UI.
- 990002 시나리오 결속, (B)의 표기차 RTK14 매칭, 웹 화상 출처 조사.
- 단위 테스트 실행(기획 단계, 코드 변경 없음).

## 7. 인계
- 다음 고정 담당: 총괄 → 오픈삼국 구현 담당(opensamguk-implement), 위 2파일 소유.
- blocker: 없음(구현 착수 가능). 군주 정책 5건은 별도 레인 결정 필요.
