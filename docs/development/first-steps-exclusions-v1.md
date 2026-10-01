# 첫걸음 설명 제외 입력 67건

C0가 확정한 D21 첫걸음 8단계 설명 대응표의 제외 근거다. 입력 조작을 설명하는 여덟 글의 범위만 판단하며, 서버 핸들러 부재·화면 미구현·도움말 검수 완료를 추론하지 않는다. 출처는 메타 `reports/opensamguk/tasks/2026-10-01-c0-d21-explanation-reasons.json`과 K7의 `2026-10-01-codex-K7-d21-explanation-delta.json`이다. `INPUT_PLANNED`는 제품 카탈로그의 플레이어 전달 단계 의미다.

<a id="first-steps-exclusion-action-scout"></a>
### 첩보 (`action.scout`)

- 확정 사유: `NOT_IN_FIRST_STEPS_EXPLANATION` — 첫걸음 8단계 밖 — 도움말 주제로 설명
- K7 행별 검토: 8단계의 전투 결과 읽기는 첩보 대상/정찰 제출을 설명하지 않는다.
- 판단: 첫걸음 8단계의 조작 설명 범위 밖이며, 별도 도움말 주제로 설명할 입력이다.
- 도움말 주제: `commands.action.scout`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-action-assault"></a>
### 강공 (`action.assault`)

- 확정 사유: `NOT_IN_FIRST_STEPS_EXPLANATION` — 첫걸음 8단계 밖 — 도움말 주제로 설명
- K7 행별 검토: 전투 단계는 결과/리플레이 열람이다. 강공 명령의 제출 방법은 없다.
- 판단: 첫걸음 8단계의 조작 설명 범위 밖이며, 별도 도움말 주제로 설명할 입력이다.
- 도움말 주제: `commands.action.assault`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-action-demandSurrender"></a>
### 항복 권고 (`action.demandSurrender`)

- 확정 사유: `NOT_IN_FIRST_STEPS_EXPLANATION` — 첫걸음 8단계 밖 — 도움말 주제로 설명
- K7 행별 검토: 결과 읽기와 항복 권고 제출은 다르다. 대상/제출 설명이 없다.
- 판단: 첫걸음 8단계의 조작 설명 범위 밖이며, 별도 도움말 주제로 설명할 입력이다.
- 도움말 주제: `commands.action.demandSurrender`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-action-siegeRoadFort"></a>
### 보루 포위 (`action.siegeRoadFort`)

- 확정 사유: `NOT_IN_FIRST_STEPS_EXPLANATION` — 첫걸음 8단계 밖 — 도움말 주제로 설명
- K7 행별 검토: 행군 단계는 출병/이동만 설명한다. 보루 포위 선택/제출 설명은 없다.
- 판단: 첫걸음 8단계의 조작 설명 범위 밖이며, 별도 도움말 주제로 설명할 입력이다.
- 도움말 주제: `commands.action.siegeRoadFort`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-court-dispatch"></a>
### 발령 (`court.dispatch`)

- 확정 사유: `NOT_IN_FIRST_STEPS_EXPLANATION` — 첫걸음 8단계 밖 — 도움말 주제로 설명
- K7 행별 검토: 발령 단계는 받은 발령에 응답한다. 주공의 발령 발신 설명은 없다.
- 판단: 첫걸음 8단계의 조작 설명 범위 밖이며, 별도 도움말 주제로 설명할 입력이다.
- 도움말 주제: `commands.court.dispatch`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-court-reward"></a>
### 상사 (`court.reward`)

- 확정 사유: `NOT_IN_FIRST_STEPS_EXPLANATION` — 첫걸음 8단계 밖 — 도움말 주제로 설명
- K7 행별 검토: 발령 화면 위치에 포상이라는 이름이 있으나 포상 대상/제출 설명은 없다.
- 판단: 첫걸음 8단계의 조작 설명 범위 밖이며, 별도 도움말 주제로 설명할 입력이다.
- 도움말 주제: `commands.court.reward`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-court-politicalConsent"></a>
### 정치 동의 (`court.politicalConsent`)

- 확정 사유: `NOT_IN_FIRST_STEPS_EXPLANATION` — 첫걸음 8단계 밖 — 도움말 주제로 설명
- K7 행별 검토: 발령 수락/거절은 발령 응답이다. 정치 동의 응답 설명은 없다.
- 판단: 첫걸음 8단계의 조작 설명 범위 밖이며, 별도 도움말 주제로 설명할 입력이다.
- 도움말 주제: `commands.court.politicalConsent`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-placement-assign"></a>
### 배치 (`placement.assign`)

- 확정 사유: `NOT_IN_FIRST_STEPS_EXPLANATION` — 첫걸음 8단계 밖 — 도움말 주제로 설명
- K7 행별 검토: 공사 단계 위치가 배치/방침/공사이나 인물 배치 방법을 설명하지 않는다.
- 판단: 첫걸음 8단계의 조작 설명 범위 밖이며, 별도 도움말 주제로 설명할 입력이다.
- 도움말 주제: `commands.placement.assign`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-policy-set"></a>
### 방침 설정 (`policy.set`)

- 확정 사유: `NOT_IN_FIRST_STEPS_EXPLANATION` — 첫걸음 8단계 밖 — 도움말 주제로 설명
- K7 행별 검토: 공사 단계 위치가 배치/방침/공사이나 방침 설정 방법을 설명하지 않는다.
- 판단: 첫걸음 8단계의 조작 설명 범위 밖이며, 별도 도움말 주제로 설명할 입력이다.
- 도움말 주제: `commands.policy.set`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-stratagem-rumor"></a>
### 유언비어 (`stratagem.rumor`)

- 확정 사유: `INPUT_PLANNED` — 준비 중인 입력 — 첫걸음 설명과 화면 연결은 구현 뒤 확인
- K7 행별 검토: 현재 8단계 글은 유언비어 입력의 선택/대상/제출 조작을 명시하지 않는다. 전용 도움말 commands.stratagem.rumor는 초안이며 첫걸음 설명 연결은 미확인.
- 판단: 현재 카탈로그 전달 단계 PLANNED. 화면 전달과 첫걸음 조작 설명 연결은 제공 확인 뒤 다시 판단한다. 내부 처리 코드의 존재 여부를 이 상태만으로 단정하지 않는다.
- 도움말 주제: `commands.stratagem.rumor`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-stratagem-play"></a>
### 계책 사용 (`stratagem.play`)

- 확정 사유: `INPUT_PLANNED` — 준비 중인 입력 — 첫걸음 설명과 화면 연결은 구현 뒤 확인
- K7 행별 검토: 현재 8단계 글은 계책 사용 입력의 선택/대상/제출 조작을 명시하지 않는다. 전용 도움말 commands.stratagem.play는 초안이며 첫걸음 설명 연결은 미확인.
- 판단: 현재 카탈로그 전달 단계 PLANNED. 화면 전달과 첫걸음 조작 설명 연결은 제공 확인 뒤 다시 판단한다. 내부 처리 코드의 존재 여부를 이 상태만으로 단정하지 않는다.
- 도움말 주제: `commands.stratagem.play`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-action-farm"></a>
### 농지개간 (`action.farm`)

- 확정 사유: `NOT_IN_FIRST_STEPS_EXPLANATION` — 첫걸음 8단계 밖 — 도움말 주제로 설명
- K7 행별 검토: 현재 8단계 글은 농지개간 입력의 선택/대상/제출 조작을 명시하지 않는다. 전용 도움말 commands.action.farm는 초안이며 첫걸음 설명 연결은 미확인.
- 판단: 첫걸음 8단계의 조작 설명 범위 밖이며, 별도 도움말 주제로 설명할 입력이다.
- 도움말 주제: `commands.action.farm`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-action-commerce"></a>
### 상업투자 (`action.commerce`)

- 확정 사유: `NOT_IN_FIRST_STEPS_EXPLANATION` — 첫걸음 8단계 밖 — 도움말 주제로 설명
- K7 행별 검토: 현재 8단계 글은 상업투자 입력의 선택/대상/제출 조작을 명시하지 않는다. 전용 도움말 commands.action.commerce는 초안이며 첫걸음 설명 연결은 미확인.
- 판단: 첫걸음 8단계의 조작 설명 범위 밖이며, 별도 도움말 주제로 설명할 입력이다.
- 도움말 주제: `commands.action.commerce`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-action-fortify"></a>
### 수비강화 (`action.fortify`)

- 확정 사유: `NOT_IN_FIRST_STEPS_EXPLANATION` — 첫걸음 8단계 밖 — 도움말 주제로 설명
- K7 행별 검토: 현재 8단계 글은 수비강화 입력의 선택/대상/제출 조작을 명시하지 않는다. 전용 도움말 commands.action.fortify는 초안이며 첫걸음 설명 연결은 미확인.
- 판단: 첫걸음 8단계의 조작 설명 범위 밖이며, 별도 도움말 주제로 설명할 입력이다.
- 도움말 주제: `commands.action.fortify`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-action-repairWall"></a>
### 성벽보수 (`action.repairWall`)

- 확정 사유: `NOT_IN_FIRST_STEPS_EXPLANATION` — 첫걸음 8단계 밖 — 도움말 주제로 설명
- K7 행별 검토: 현재 8단계 글은 성벽보수 입력의 선택/대상/제출 조작을 명시하지 않는다. 전용 도움말 commands.action.repairWall는 초안이며 첫걸음 설명 연결은 미확인.
- 판단: 첫걸음 8단계의 조작 설명 범위 밖이며, 별도 도움말 주제로 설명할 입력이다.
- 도움말 주제: `commands.action.repairWall`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-action-security"></a>
### 치안강화 (`action.security`)

- 확정 사유: `NOT_IN_FIRST_STEPS_EXPLANATION` — 첫걸음 8단계 밖 — 도움말 주제로 설명
- K7 행별 검토: 현재 8단계 글은 치안강화 입력의 선택/대상/제출 조작을 명시하지 않는다. 전용 도움말 commands.action.security는 초안이며 첫걸음 설명 연결은 미확인.
- 판단: 첫걸음 8단계의 조작 설명 범위 밖이며, 별도 도움말 주제로 설명할 입력이다.
- 도움말 주제: `commands.action.security`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-action-settle"></a>
### 정착장려 (`action.settle`)

- 확정 사유: `NOT_IN_FIRST_STEPS_EXPLANATION` — 첫걸음 8단계 밖 — 도움말 주제로 설명
- K7 행별 검토: 현재 8단계 글은 정착장려 입력의 선택/대상/제출 조작을 명시하지 않는다. 전용 도움말 commands.action.settle는 초안이며 첫걸음 설명 연결은 미확인.
- 판단: 첫걸음 8단계의 조작 설명 범위 밖이며, 별도 도움말 주제로 설명할 입력이다.
- 도움말 주제: `commands.action.settle`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-action-selectResidents"></a>
### 주민선정 (`action.selectResidents`)

- 확정 사유: `NOT_IN_FIRST_STEPS_EXPLANATION` — 첫걸음 8단계 밖 — 도움말 주제로 설명
- K7 행별 검토: 현재 8단계 글은 주민선정 입력의 선택/대상/제출 조작을 명시하지 않는다. 전용 도움말 commands.action.selectResidents는 초안이며 첫걸음 설명 연결은 미확인.
- 판단: 첫걸음 8단계의 조작 설명 범위 밖이며, 별도 도움말 주제로 설명할 입력이다.
- 도움말 주제: `commands.action.selectResidents`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-action-conscript"></a>
### 징병 (`action.conscript`)

- 확정 사유: `NOT_IN_FIRST_STEPS_EXPLANATION` — 첫걸음 8단계 밖 — 도움말 주제로 설명
- K7 행별 검토: 현재 8단계 글은 징병 입력의 선택/대상/제출 조작을 명시하지 않는다. 전용 도움말 commands.action.conscript는 초안이며 첫걸음 설명 연결은 미확인.
- 판단: 첫걸음 8단계의 조작 설명 범위 밖이며, 별도 도움말 주제로 설명할 입력이다.
- 도움말 주제: `commands.action.conscript`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-action-raiseVolunteers"></a>
### 모병 (`action.raiseVolunteers`)

- 확정 사유: `NOT_IN_FIRST_STEPS_EXPLANATION` — 첫걸음 8단계 밖 — 도움말 주제로 설명
- K7 행별 검토: 현재 8단계 글은 모병 입력의 선택/대상/제출 조작을 명시하지 않는다. 전용 도움말 commands.action.raiseVolunteers는 초안이며 첫걸음 설명 연결은 미확인.
- 판단: 첫걸음 8단계의 조작 설명 범위 밖이며, 별도 도움말 주제로 설명할 입력이다.
- 도움말 주제: `commands.action.raiseVolunteers`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-action-train"></a>
### 훈련 (`action.train`)

- 확정 사유: `NOT_IN_FIRST_STEPS_EXPLANATION` — 첫걸음 8단계 밖 — 도움말 주제로 설명
- K7 행별 검토: 현재 8단계 글은 훈련 입력의 선택/대상/제출 조작을 명시하지 않는다. 전용 도움말 commands.action.train는 초안이며 첫걸음 설명 연결은 미확인.
- 판단: 첫걸음 8단계의 조작 설명 범위 밖이며, 별도 도움말 주제로 설명할 입력이다.
- 도움말 주제: `commands.action.train`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-action-boostMorale"></a>
### 사기진작 (`action.boostMorale`)

- 확정 사유: `NOT_IN_FIRST_STEPS_EXPLANATION` — 첫걸음 8단계 밖 — 도움말 주제로 설명
- K7 행별 검토: 현재 8단계 글은 사기진작 입력의 선택/대상/제출 조작을 명시하지 않는다. 전용 도움말 commands.action.boostMorale는 초안이며 첫걸음 설명 연결은 미확인.
- 판단: 첫걸음 8단계의 조작 설명 범위 밖이며, 별도 도움말 주제로 설명할 입력이다.
- 도움말 주제: `commands.action.boostMorale`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-action-demobilize"></a>
### 소집해제 (`action.demobilize`)

- 확정 사유: `NOT_IN_FIRST_STEPS_EXPLANATION` — 첫걸음 8단계 밖 — 도움말 주제로 설명
- K7 행별 검토: 현재 8단계 글은 소집해제 입력의 선택/대상/제출 조작을 명시하지 않는다. 전용 도움말 commands.action.demobilize는 초안이며 첫걸음 설명 연결은 미확인.
- 판단: 첫걸음 8단계의 조작 설명 범위 밖이며, 별도 도움말 주제로 설명할 입력이다.
- 도움말 주제: `commands.action.demobilize`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-action-travel"></a>
### 견문 (`action.travel`)

- 확정 사유: `NOT_IN_FIRST_STEPS_EXPLANATION` — 첫걸음 8단계 밖 — 도움말 주제로 설명
- K7 행별 검토: 현재 8단계 글은 견문 입력의 선택/대상/제출 조작을 명시하지 않는다. 전용 도움말 commands.action.travel는 초안이며 첫걸음 설명 연결은 미확인.
- 판단: 첫걸음 8단계의 조작 설명 범위 밖이며, 별도 도움말 주제로 설명할 입력이다.
- 도움말 주제: `commands.action.travel`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-action-selfTrain"></a>
### 단련 (`action.selfTrain`)

- 확정 사유: `NOT_IN_FIRST_STEPS_EXPLANATION` — 첫걸음 8단계 밖 — 도움말 주제로 설명
- K7 행별 검토: 현재 8단계 글은 단련 입력의 선택/대상/제출 조작을 명시하지 않는다. 전용 도움말 commands.action.selfTrain는 초안이며 첫걸음 설명 연결은 미확인.
- 판단: 첫걸음 8단계의 조작 설명 범위 밖이며, 별도 도움말 주제로 설명할 입력이다.
- 도움말 주제: `commands.action.selfTrain`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-action-recuperate"></a>
### 요양 (`action.recuperate`)

- 확정 사유: `NOT_IN_FIRST_STEPS_EXPLANATION` — 첫걸음 8단계 밖 — 도움말 주제로 설명
- K7 행별 검토: 현재 8단계 글은 요양 입력의 선택/대상/제출 조작을 명시하지 않는다. 전용 도움말 commands.action.recuperate는 초안이며 첫걸음 설명 연결은 미확인.
- 판단: 첫걸음 8단계의 조작 설명 범위 밖이며, 별도 도움말 주제로 설명할 입력이다.
- 도움말 주제: `commands.action.recuperate`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-action-gift"></a>
### 증여 (`action.gift`)

- 확정 사유: `NOT_IN_FIRST_STEPS_EXPLANATION` — 첫걸음 8단계 밖 — 도움말 주제로 설명
- K7 행별 검토: 현재 8단계 글은 증여 입력의 선택/대상/제출 조작을 명시하지 않는다. 전용 도움말 commands.action.gift는 초안이며 첫걸음 설명 연결은 미확인.
- 판단: 첫걸음 8단계의 조작 설명 범위 밖이며, 별도 도움말 주제로 설명할 입력이다.
- 도움말 주제: `commands.action.gift`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-action-donate"></a>
### 헌납 (`action.donate`)

- 확정 사유: `INPUT_PLANNED` — 준비 중인 입력 — 첫걸음 설명과 화면 연결은 구현 뒤 확인
- K7 행별 검토: 현재 8단계 글은 헌납 입력의 선택/대상/제출 조작을 명시하지 않는다. 전용 도움말 commands.action.donate는 초안이며 첫걸음 설명 연결은 미확인.
- 판단: 현재 카탈로그 전달 단계 PLANNED. 화면 전달과 첫걸음 조작 설명 연결은 제공 확인 뒤 다시 판단한다. 내부 처리 코드의 존재 여부를 이 상태만으로 단정하지 않는다.
- 도움말 주제: `commands.action.donate`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-action-muster"></a>
### 집합 (`action.muster`)

- 확정 사유: `NOT_IN_FIRST_STEPS_EXPLANATION` — 첫걸음 8단계 밖 — 도움말 주제로 설명
- K7 행별 검토: 현재 8단계 글은 집합 입력의 선택/대상/제출 조작을 명시하지 않는다. 전용 도움말 commands.action.muster는 초안이며 첫걸음 설명 연결은 미확인.
- 판단: 첫걸음 8단계의 조작 설명 범위 밖이며, 별도 도움말 주제로 설명할 입력이다.
- 도움말 주제: `commands.action.muster`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-action-forcedMarch"></a>
### 강행 (`action.forcedMarch`)

- 확정 사유: `NOT_IN_FIRST_STEPS_EXPLANATION` — 첫걸음 8단계 밖 — 도움말 주제로 설명
- K7 행별 검토: 행군 단계에서 출병/이동만 설명한다. 강행 선택/대상/비용은 설명하지 않는다.
- 판단: 첫걸음 8단계의 조작 설명 범위 밖이며, 별도 도움말 주제로 설명할 입력이다.
- 도움말 주제: `commands.action.forcedMarch`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-action-return"></a>
### 귀환 (`action.return`)

- 확정 사유: `NOT_IN_FIRST_STEPS_EXPLANATION` — 첫걸음 8단계 밖 — 도움말 주제로 설명
- K7 행별 검토: 행군 단계에서 출병/이동만 설명한다. 귀환 선택/예약은 설명하지 않는다.
- 판단: 첫걸음 8단계의 조작 설명 범위 밖이며, 별도 도움말 주제로 설명할 입력이다.
- 도움말 주제: `commands.action.return`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-action-retire"></a>
### 은퇴 (`action.retire`)

- 확정 사유: `INPUT_PLANNED` — 준비 중인 입력 — 첫걸음 설명과 화면 연결은 구현 뒤 확인
- K7 행별 검토: 현재 8단계 글은 은퇴 입력의 선택/대상/제출 조작을 명시하지 않는다. 전용 도움말 commands.action.retire는 초안이며 첫걸음 설명 연결은 미확인.
- 판단: 현재 카탈로그 전달 단계 PLANNED. 화면 전달과 첫걸음 조작 설명 연결은 제공 확인 뒤 다시 판단한다. 내부 처리 코드의 존재 여부를 이 상태만으로 단정하지 않는다.
- 도움말 주제: `commands.action.retire`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-action-resign"></a>
### 하야 (`action.resign`)

- 확정 사유: `INPUT_PLANNED` — 준비 중인 입력 — 첫걸음 설명과 화면 연결은 구현 뒤 확인
- K7 행별 검토: 현재 8단계 글은 하야 입력의 선택/대상/제출 조작을 명시하지 않는다. 전용 도움말 commands.action.resign는 초안이며 첫걸음 설명 연결은 미확인.
- 판단: 현재 카탈로그 전달 단계 PLANNED. 화면 전달과 첫걸음 조작 설명 연결은 제공 확인 뒤 다시 판단한다. 내부 처리 코드의 존재 여부를 이 상태만으로 단정하지 않는다.
- 도움말 주제: `commands.action.resign`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-action-rise"></a>
### 거병 (`action.rise`)

- 확정 사유: `INPUT_PLANNED` — 준비 중인 입력 — 첫걸음 설명과 화면 연결은 구현 뒤 확인
- K7 행별 검토: 현재 8단계 글은 거병 입력의 선택/대상/제출 조작을 명시하지 않는다. 전용 도움말 commands.action.rise는 초안이며 첫걸음 설명 연결은 미확인.
- 판단: 현재 카탈로그 전달 단계 PLANNED. 화면 전달과 첫걸음 조작 설명 연결은 제공 확인 뒤 다시 판단한다. 내부 처리 코드의 존재 여부를 이 상태만으로 단정하지 않는다.
- 도움말 주제: `commands.action.rise`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-action-foundState"></a>
### 건국 (`action.foundState`)

- 확정 사유: `NOT_IN_FIRST_STEPS_EXPLANATION` — 첫걸음 8단계 밖 — 도움말 주제로 설명
- K7 행별 검토: 현재 8단계 글은 건국 입력의 선택/대상/제출 조작을 명시하지 않는다. 전용 도움말 commands.action.foundState는 초안이며 첫걸음 설명 연결은 미확인.
- 판단: 첫걸음 8단계의 조작 설명 범위 밖이며, 별도 도움말 주제로 설명할 입력이다.
- 도움말 주제: `commands.action.foundState`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-action-abdicate"></a>
### 선양 (`action.abdicate`)

- 확정 사유: `NOT_IN_FIRST_STEPS_EXPLANATION` — 첫걸음 8단계 밖 — 도움말 주제로 설명
- K7 행별 검토: 현재 8단계 글은 선양 입력의 선택/대상/제출 조작을 명시하지 않는다. 전용 도움말 commands.action.abdicate는 초안이며 첫걸음 설명 연결은 미확인.
- 판단: 첫걸음 8단계의 조작 설명 범위 밖이며, 별도 도움말 주제로 설명할 입력이다.
- 도움말 주제: `commands.action.abdicate`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-action-dissolve"></a>
### 세력 해산 (`action.dissolve`)

- 확정 사유: `INPUT_PLANNED` — 준비 중인 입력 — 첫걸음 설명과 화면 연결은 구현 뒤 확인
- K7 행별 검토: 현재 8단계 글은 세력 해산 입력의 선택/대상/제출 조작을 명시하지 않는다. 전용 도움말 commands.action.dissolve는 초안이며 첫걸음 설명 연결은 미확인.
- 판단: 현재 카탈로그 전달 단계 PLANNED. 화면 전달과 첫걸음 조작 설명 연결은 제공 확인 뒤 다시 판단한다. 내부 처리 코드의 존재 여부를 이 상태만으로 단정하지 않는다.
- 도움말 주제: `commands.action.dissolve`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-action-tour"></a>
### 순행 (`action.tour`)

- 확정 사유: `NOT_IN_FIRST_STEPS_EXPLANATION` — 첫걸음 8단계 밖 — 도움말 주제로 설명
- K7 행별 검토: 현재 8단계 글은 순행 입력의 선택/대상/제출 조작을 명시하지 않는다. 전용 도움말 commands.action.tour는 초안이며 첫걸음 설명 연결은 미확인.
- 판단: 첫걸음 8단계의 조작 설명 범위 밖이며, 별도 도움말 주제로 설명할 입력이다.
- 도움말 주제: `commands.action.tour`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-action-persuadeCaptive"></a>
### 포로 설득 (`action.persuadeCaptive`)

- 확정 사유: `INPUT_PLANNED` — 준비 중인 입력 — 첫걸음 설명과 화면 연결은 구현 뒤 확인
- K7 행별 검토: 등용 단계에 포로 설득은 준비 중이라는 안내만 있다. 실제 조작 설명으로 연결하지 않는다.
- 판단: 현재 카탈로그 전달 단계 PLANNED. 화면 전달과 첫걸음 조작 설명 연결은 제공 확인 뒤 다시 판단한다. 내부 처리 코드의 존재 여부를 이 상태만으로 단정하지 않는다.
- 도움말 주제: `commands.action.persuadeCaptive`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-action-oath"></a>
### 결의 (`action.oath`)

- 확정 사유: `NOT_IN_FIRST_STEPS_EXPLANATION` — 첫걸음 8단계 밖 — 도움말 주제로 설명
- K7 행별 검토: 현재 8단계 글은 결의 입력의 선택/대상/제출 조작을 명시하지 않는다. 전용 도움말 commands.action.oath는 초안이며 첫걸음 설명 연결은 미확인.
- 판단: 첫걸음 8단계의 조작 설명 범위 밖이며, 별도 도움말 주제로 설명할 입력이다.
- 도움말 주제: `commands.action.oath`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-action-independence"></a>
### 독립 (`action.independence`)

- 확정 사유: `INPUT_PLANNED` — 준비 중인 입력 — 첫걸음 설명과 화면 연결은 구현 뒤 확인
- K7 행별 검토: 현재 8단계 글은 독립 입력의 선택/대상/제출 조작을 명시하지 않는다. 전용 도움말 commands.action.independence는 초안이며 첫걸음 설명 연결은 미확인.
- 판단: 현재 카탈로그 전달 단계 PLANNED. 화면 전달과 첫걸음 조작 설명 연결은 제공 확인 뒤 다시 판단한다. 내부 처리 코드의 존재 여부를 이 상태만으로 단정하지 않는다.
- 도움말 주제: `commands.action.independence`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-action-convertProficiency"></a>
### 숙련전환 (`action.convertProficiency`)

- 확정 사유: `NOT_IN_FIRST_STEPS_EXPLANATION` — 첫걸음 8단계 밖 — 도움말 주제로 설명
- K7 행별 검토: 현재 8단계 글은 숙련전환 입력의 선택/대상/제출 조작을 명시하지 않는다. 전용 도움말 commands.action.convertProficiency는 초안이며 첫걸음 설명 연결은 미확인.
- 판단: 첫걸음 8단계의 조작 설명 범위 밖이며, 별도 도움말 주제로 설명할 입력이다.
- 도움말 주제: `commands.action.convertProficiency`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-action-tradeEquipment"></a>
### 장비매매 (`action.tradeEquipment`)

- 확정 사유: `INPUT_PLANNED` — 준비 중인 입력 — 첫걸음 설명과 화면 연결은 구현 뒤 확인
- K7 행별 검토: 현재 8단계 글은 장비매매 입력의 선택/대상/제출 조작을 명시하지 않는다. 전용 도움말 commands.action.tradeEquipment는 초안이며 첫걸음 설명 연결은 미확인.
- 판단: 현재 카탈로그 전달 단계 PLANNED. 화면 전달과 첫걸음 조작 설명 연결은 제공 확인 뒤 다시 판단한다. 내부 처리 코드의 존재 여부를 이 상태만으로 단정하지 않는다.
- 도움말 주제: `commands.action.tradeEquipment`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-action-tradeGrain"></a>
### 군량매매 (`action.tradeGrain`)

- 확정 사유: `NOT_IN_FIRST_STEPS_EXPLANATION` — 첫걸음 8단계 밖 — 도움말 주제로 설명
- K7 행별 검토: 현재 8단계 글은 군량매매 입력의 선택/대상/제출 조작을 명시하지 않는다. 전용 도움말 commands.action.tradeGrain는 초안이며 첫걸음 설명 연결은 미확인.
- 판단: 첫걸음 8단계의 조작 설명 범위 밖이며, 별도 도움말 주제로 설명할 입력이다.
- 도움말 주제: `commands.action.tradeGrain`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-action-transport"></a>
### 물자조달 (`action.transport`)

- 확정 사유: `NOT_IN_FIRST_STEPS_EXPLANATION` — 첫걸음 8단계 밖 — 도움말 주제로 설명
- K7 행별 검토: 현재 8단계 글은 물자조달 입력의 선택/대상/제출 조작을 명시하지 않는다. 전용 도움말 commands.action.transport는 초안이며 첫걸음 설명 연결은 미확인.
- 판단: 첫걸음 8단계의 조작 설명 범위 밖이며, 별도 도움말 주제로 설명할 입력이다.
- 도움말 주제: `commands.action.transport`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-court-releaseCorps"></a>
### 군단 해제 (`court.releaseCorps`)

- 확정 사유: `NOT_IN_FIRST_STEPS_EXPLANATION` — 첫걸음 8단계 밖 — 도움말 주제로 설명
- K7 행별 검토: 전투 결과 읽기는 군단 해산/해제 입력을 설명하지 않는다.
- 판단: 첫걸음 8단계의 조작 설명 범위 밖이며, 별도 도움말 주제로 설명할 입력이다.
- 도움말 주제: `commands.court.releaseCorps`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-court-diplomacy"></a>
### 외교 (`court.diplomacy`)

- 확정 사유: `INPUT_PLANNED` — 준비 중인 입력 — 첫걸음 설명과 화면 연결은 구현 뒤 확인
- K7 행별 검토: 출사/발령 설명에 외교 입력 대상/제출은 없다.
- 판단: 현재 카탈로그 전달 단계 PLANNED. 화면 전달과 첫걸음 조작 설명 연결은 제공 확인 뒤 다시 판단한다. 내부 처리 코드의 존재 여부를 이 상태만으로 단정하지 않는다.
- 도움말 주제: `commands.court.diplomacy`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-court-abandonCounty"></a>
### 현 포기 (`court.abandonCounty`)

- 확정 사유: `NOT_IN_FIRST_STEPS_EXPLANATION` — 첫걸음 8단계 밖 — 도움말 주제로 설명
- K7 행별 검토: 현재 8단계 글은 현 포기 입력의 선택/대상/제출 조작을 명시하지 않는다. 전용 도움말 commands.court.abandonCounty는 초안이며 첫걸음 설명 연결은 미확인.
- 판단: 첫걸음 8단계의 조작 설명 범위 밖이며, 별도 도움말 주제로 설명할 입력이다.
- 도움말 주제: `commands.court.abandonCounty`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-court-institution"></a>
### 제도 결정 (`court.institution`)

- 확정 사유: `INPUT_PLANNED` — 준비 중인 입력 — 첫걸음 설명과 화면 연결은 구현 뒤 확인
- K7 행별 검토: 현재 8단계 글은 제도 결정 입력의 선택/대상/제출 조작을 명시하지 않는다. 전용 도움말 commands.court.institution는 초안이며 첫걸음 설명 연결은 미확인.
- 판단: 현재 카탈로그 전달 단계 PLANNED. 화면 전달과 첫걸음 조작 설명 연결은 제공 확인 뒤 다시 판단한다. 내부 처리 코드의 존재 여부를 이 상태만으로 단정하지 않는다.
- 도움말 주제: `commands.court.institution`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-court-moveCapital"></a>
### 천도 (`court.moveCapital`)

- 확정 사유: `NOT_IN_FIRST_STEPS_EXPLANATION` — 첫걸음 8단계 밖 — 도움말 주제로 설명
- K7 행별 검토: 현재 8단계 글은 천도 입력의 선택/대상/제출 조작을 명시하지 않는다. 전용 도움말 commands.court.moveCapital는 초안이며 첫걸음 설명 연결은 미확인.
- 판단: 첫걸음 8단계의 조작 설명 범위 밖이며, 별도 도움말 주제로 설명할 입력이다.
- 도움말 주제: `commands.court.moveCapital`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-court-confiscate"></a>
### 몰수 (`court.confiscate`)

- 확정 사유: `INPUT_PLANNED` — 준비 중인 입력 — 첫걸음 설명과 화면 연결은 구현 뒤 확인
- K7 행별 검토: 현재 8단계 글은 몰수 입력의 선택/대상/제출 조작을 명시하지 않는다. 전용 도움말 commands.court.confiscate는 초안이며 첫걸음 설명 연결은 미확인.
- 판단: 현재 카탈로그 전달 단계 PLANNED. 화면 전달과 첫걸음 조작 설명 연결은 제공 확인 뒤 다시 판단한다. 내부 처리 코드의 존재 여부를 이 상태만으로 단정하지 않는다.
- 도움말 주제: `commands.court.confiscate`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-court-nonAggression"></a>
### 불가침 (`court.nonAggression`)

- 확정 사유: `INPUT_PLANNED` — 준비 중인 입력 — 첫걸음 설명과 화면 연결은 구현 뒤 확인
- K7 행별 검토: 출사/발령 설명에 불가침 협정 입력/응답은 없다.
- 판단: 현재 카탈로그 전달 단계 PLANNED. 화면 전달과 첫걸음 조작 설명 연결은 제공 확인 뒤 다시 판단한다. 내부 처리 코드의 존재 여부를 이 상태만으로 단정하지 않는다.
- 도움말 주제: `commands.court.nonAggression`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-court-declareWar"></a>
### 선전포고 (`court.declareWar`)

- 확정 사유: `INPUT_PLANNED` — 준비 중인 입력 — 첫걸음 설명과 화면 연결은 구현 뒤 확인
- K7 행별 검토: 전투 단계는 결과/리플레이 읽기이다. 선전포고 제출 설명은 없다.
- 판단: 현재 카탈로그 전달 단계 PLANNED. 화면 전달과 첫걸음 조작 설명 연결은 제공 확인 뒤 다시 판단한다. 내부 처리 코드의 존재 여부를 이 상태만으로 단정하지 않는다.
- 도움말 주제: `commands.court.declareWar`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-court-offerPeace"></a>
### 강화 제안 (`court.offerPeace`)

- 확정 사유: `INPUT_PLANNED` — 준비 중인 입력 — 첫걸음 설명과 화면 연결은 구현 뒤 확인
- K7 행별 검토: 전투 결과 열람과 종전 제안 입력은 다르다. 제출 설명은 없다.
- 판단: 현재 카탈로그 전달 단계 PLANNED. 화면 전달과 첫걸음 조작 설명 연결은 제공 확인 뒤 다시 판단한다. 내부 처리 코드의 존재 여부를 이 상태만으로 단정하지 않는다.
- 도움말 주제: `commands.court.offerPeace`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-court-breakNonAggression"></a>
### 불가침 파기 (`court.breakNonAggression`)

- 확정 사유: `INPUT_PLANNED` — 준비 중인 입력 — 첫걸음 설명과 화면 연결은 구현 뒤 확인
- K7 행별 검토: 전투 결과 열람과 불가침 파기 입력은 다르다. 제출 설명은 없다.
- 판단: 현재 카탈로그 전달 단계 PLANNED. 화면 전달과 첫걸음 조작 설명 연결은 제공 확인 뒤 다시 판단한다. 내부 처리 코드의 존재 여부를 이 상태만으로 단정하지 않는다.
- 도움말 주제: `commands.court.breakNonAggression`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-work-reduce"></a>
### 공사 감축 (`work.reduce`)

- 확정 사유: `INPUT_PLANNED` — 준비 중인 입력 — 첫걸음 설명과 화면 연결은 구현 뒤 확인
- K7 행별 검토: 공사 단계는 시작 접수만 설명한다. 감축/중단 입력 설명은 없다.
- 판단: 현재 카탈로그 전달 단계 PLANNED. 화면 전달과 첫걸음 조작 설명 연결은 제공 확인 뒤 다시 판단한다. 내부 처리 코드의 존재 여부를 이 상태만으로 단정하지 않는다.
- 도움말 주제: `commands.work.reduce`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-stratagem-steal"></a>
### 절도 (`stratagem.steal`)

- 확정 사유: `INPUT_PLANNED` — 준비 중인 입력 — 첫걸음 설명과 화면 연결은 구현 뒤 확인
- K7 행별 검토: 현재 8단계 글은 절도 입력의 선택/대상/제출 조작을 명시하지 않는다. 전용 도움말 commands.stratagem.steal는 초안이며 첫걸음 설명 연결은 미확인.
- 판단: 현재 카탈로그 전달 단계 PLANNED. 화면 전달과 첫걸음 조작 설명 연결은 제공 확인 뒤 다시 판단한다. 내부 처리 코드의 존재 여부를 이 상태만으로 단정하지 않는다.
- 도움말 주제: `commands.stratagem.steal`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-stratagem-sabotage"></a>
### 파괴공작 (`stratagem.sabotage`)

- 확정 사유: `INPUT_PLANNED` — 준비 중인 입력 — 첫걸음 설명과 화면 연결은 구현 뒤 확인
- K7 행별 검토: 현재 8단계 글은 파괴공작 입력의 선택/대상/제출 조작을 명시하지 않는다. 전용 도움말 commands.stratagem.sabotage는 초안이며 첫걸음 설명 연결은 미확인.
- 판단: 현재 카탈로그 전달 단계 PLANNED. 화면 전달과 첫걸음 조작 설명 연결은 제공 확인 뒤 다시 판단한다. 내부 처리 코드의 존재 여부를 이 상태만으로 단정하지 않는다.
- 도움말 주제: `commands.stratagem.sabotage`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-stratagem-fire"></a>
### 방화 (`stratagem.fire`)

- 확정 사유: `INPUT_PLANNED` — 준비 중인 입력 — 첫걸음 설명과 화면 연결은 구현 뒤 확인
- K7 행별 검토: 현재 8단계 글은 방화 입력의 선택/대상/제출 조작을 명시하지 않는다. 전용 도움말 commands.stratagem.fire는 초안이며 첫걸음 설명 연결은 미확인.
- 판단: 현재 카탈로그 전달 단계 PLANNED. 화면 전달과 첫걸음 조작 설명 연결은 제공 확인 뒤 다시 판단한다. 내부 처리 코드의 존재 여부를 이 상태만으로 단정하지 않는다.
- 도움말 주제: `commands.stratagem.fire`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-stratagem-lastStand"></a>
### 배수진 (`stratagem.lastStand`)

- 확정 사유: `INPUT_PLANNED` — 준비 중인 입력 — 첫걸음 설명과 화면 연결은 구현 뒤 확인
- K7 행별 검토: 현재 8단계 글은 배수진 입력의 선택/대상/제출 조작을 명시하지 않는다. 전용 도움말 commands.stratagem.lastStand는 초안이며 첫걸음 설명 연결은 미확인.
- 판단: 현재 카탈로그 전달 단계 PLANNED. 화면 전달과 첫걸음 조작 설명 연결은 제공 확인 뒤 다시 판단한다. 내부 처리 코드의 존재 여부를 이 상태만으로 단정하지 않는다.
- 도움말 주제: `commands.stratagem.lastStand`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-stratagem-mobilizePeople"></a>
### 민심 동원 (`stratagem.mobilizePeople`)

- 확정 사유: `INPUT_PLANNED` — 준비 중인 입력 — 첫걸음 설명과 화면 연결은 구현 뒤 확인
- K7 행별 검토: 현재 8단계 글은 민심 동원 입력의 선택/대상/제출 조작을 명시하지 않는다. 전용 도움말 commands.stratagem.mobilizePeople는 초안이며 첫걸음 설명 연결은 미확인.
- 판단: 현재 카탈로그 전달 단계 PLANNED. 화면 전달과 첫걸음 조작 설명 연결은 제공 확인 뒤 다시 판단한다. 내부 처리 코드의 존재 여부를 이 상태만으로 단정하지 않는다.
- 도움말 주제: `commands.stratagem.mobilizePeople`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-stratagem-flood"></a>
### 수공 (`stratagem.flood`)

- 확정 사유: `INPUT_PLANNED` — 준비 중인 입력 — 첫걸음 설명과 화면 연결은 구현 뒤 확인
- K7 행별 검토: 현재 8단계 글은 수공 입력의 선택/대상/제출 조작을 명시하지 않는다. 전용 도움말 commands.stratagem.flood는 초안이며 첫걸음 설명 연결은 미확인.
- 판단: 현재 카탈로그 전달 단계 PLANNED. 화면 전달과 첫걸음 조작 설명 연결은 제공 확인 뒤 다시 판단한다. 내부 처리 코드의 존재 여부를 이 상태만으로 단정하지 않는다.
- 도움말 주제: `commands.stratagem.flood`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-stratagem-falseReport"></a>
### 거짓 보고 (`stratagem.falseReport`)

- 확정 사유: `INPUT_PLANNED` — 준비 중인 입력 — 첫걸음 설명과 화면 연결은 구현 뒤 확인
- K7 행별 검토: 현재 8단계 글은 거짓 보고 입력의 선택/대상/제출 조작을 명시하지 않는다. 전용 도움말 commands.stratagem.falseReport는 초안이며 첫걸음 설명 연결은 미확인.
- 판단: 현재 카탈로그 전달 단계 PLANNED. 화면 전달과 첫걸음 조작 설명 연결은 제공 확인 뒤 다시 판단한다. 내부 처리 코드의 존재 여부를 이 상태만으로 단정하지 않는다.
- 도움말 주제: `commands.stratagem.falseReport`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-stratagem-raiseMilitia"></a>
### 의병 봉기 (`stratagem.raiseMilitia`)

- 확정 사유: `INPUT_PLANNED` — 준비 중인 입력 — 첫걸음 설명과 화면 연결은 구현 뒤 확인
- K7 행별 검토: 현재 8단계 글은 의병 봉기 입력의 선택/대상/제출 조작을 명시하지 않는다. 전용 도움말 commands.stratagem.raiseMilitia는 초안이며 첫걸음 설명 연결은 미확인.
- 판단: 현재 카탈로그 전달 단계 PLANNED. 화면 전달과 첫걸음 조작 설명 연결은 제공 확인 뒤 다시 판단한다. 내부 처리 코드의 존재 여부를 이 상태만으로 단정하지 않는다.
- 도움말 주제: `commands.stratagem.raiseMilitia`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-stratagem-provokeRivalry"></a>
### 이간 (`stratagem.provokeRivalry`)

- 확정 사유: `INPUT_PLANNED` — 준비 중인 입력 — 첫걸음 설명과 화면 연결은 구현 뒤 확인
- K7 행별 검토: 현재 8단계 글은 이간 입력의 선택/대상/제출 조작을 명시하지 않는다. 전용 도움말 commands.stratagem.provokeRivalry는 초안이며 첫걸음 설명 연결은 미확인.
- 판단: 현재 카탈로그 전달 단계 PLANNED. 화면 전달과 첫걸음 조작 설명 연결은 제공 확인 뒤 다시 판단한다. 내부 처리 코드의 존재 여부를 이 상태만으로 단정하지 않는다.
- 도움말 주제: `commands.stratagem.provokeRivalry`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-stratagem-raid"></a>
### 기습 (`stratagem.raid`)

- 확정 사유: `INPUT_PLANNED` — 준비 중인 입력 — 첫걸음 설명과 화면 연결은 구현 뒤 확인
- K7 행별 검토: 현재 8단계 글은 기습 입력의 선택/대상/제출 조작을 명시하지 않는다. 전용 도움말 commands.stratagem.raid는 초안이며 첫걸음 설명 연결은 미확인.
- 판단: 현재 카탈로그 전달 단계 PLANNED. 화면 전달과 첫걸음 조작 설명 연결은 제공 확인 뒤 다시 판단한다. 내부 처리 코드의 존재 여부를 이 상태만으로 단정하지 않는다.
- 도움말 주제: `commands.stratagem.raid`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.

<a id="first-steps-exclusion-stratagem-reciprocity"></a>
### 보답 (`stratagem.reciprocity`)

- 확정 사유: `INPUT_PLANNED` — 준비 중인 입력 — 첫걸음 설명과 화면 연결은 구현 뒤 확인
- K7 행별 검토: 현재 8단계 글은 보답 입력의 선택/대상/제출 조작을 명시하지 않는다. 전용 도움말 commands.stratagem.reciprocity는 초안이며 첫걸음 설명 연결은 미확인.
- 판단: 현재 카탈로그 전달 단계 PLANNED. 화면 전달과 첫걸음 조작 설명 연결은 제공 확인 뒤 다시 판단한다. 내부 처리 코드의 존재 여부를 이 상태만으로 단정하지 않는다.
- 도움말 주제: `commands.stratagem.reciprocity`. 본 행은 도움말 글 승인이나 입력 배달 단계 승격을 뜻하지 않는다.
