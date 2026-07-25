# 오픈삼국 v2 백본 문서 2종 — 티켓화 분해

두 문서는 짝(pair)이다. 제품 스펙은 **무엇/왜**(계약·성공기준)를 정의하고, 실행 계획은 **어떻게/언제**(phase·Exit)를 정의한다. 완료 기준(Exit)은 거의 전부 실행 계획 쪽에만 있으므로, 티켓 완료 기준은 계획 문서에서 끌어왔다.

---

## 문서 1: `2026-07-12-opensamguk-v2-product-spec.md` (제품·시스템 정본)

### 목적/범위 요약
오픈삼국 v2의 제품·시스템 정본(source of truth)이다. 삼모 문법 위에 부하·작전·보급·외교·전장을 통과해 명령 결과가 굴절되는 영구 멀티플레이어 전략 게임을 정의한다. v1에서 보존할 것(PHP 패러티·InMemoryTurnWorld·단일 write 경로·예약턴 UX)과 v2에서 새로 정의할 것(작전, 전쟁 replay, 가신 제안, 도독부·봉신, 실시간 formation 전투, 국가 정체성, 황실·관직 등)의 경계를 긋는다. 핵심 도메인 계약(CommandSubject·Operation·BattleReplay·RetainerProposal·FeudalContract), 화면 구조, 콘텐츠 전략(우선순위 A/B/C), 장기 확장(Napoleonic·Empire), 제품·운영 성공 기준을 담는다. 세부 정본은 4개 하위 스펙 문서로 위임한다.

### 문서 구조 (섹션, 순서 보존)
1. 제품 정의
2. v1과 v2의 경계 (v1에서 보존 / v2에서 새로 정의)
3. 대상 사용자와 핵심 루프
4. 시간·갱신 계약 (Cadence)
5. v2 MVP 수직 슬라이스 (월드 / 장면)
6. 핵심 도메인 계약 (개인턴·사령턴·전술 명령의 경계, CommandSubject, Operation, BattleReplay, RetainerProposal, FeudalContract)
7. 화면 구조 (+ 전체 역사 지리 표면)
8. 비범위
9. 콘텐츠 전략 (우선순위 A·B·C, 전투 표현, 전장 감각, 토탈워식 캠페인, v1 커맨드 진화 규칙, 건물·인프라, 전술 전투 주체, 임무형 지휘, 넣지 않을 콘텐츠)
10. 장기 확장: Open Napoleonic·Open Empire (공통 계층, 전술 엔진 기반, 규칙셋 확장 순서)
11. 제품·운영 성공 기준

> 참고: 이 문서는 phase/wave 일정 구조가 없다. "구조"는 위 섹션 목차와 §9 콘텐츠 우선순위(A→B→C), §10 규칙셋 확장 순서(1~5)가 유일하다.

### 티켓 후보 (제품 스펙)

**P-1. 이벤트 소비 계약 4종을 프론트에 확정한다**
- 요약: 프론트가 소비하는 이벤트를 `commandResolved`·`turnCompleted`·`battlePhaseChanged`·`notificationCreated` 4종으로 고정하고, 전체 `window.location.reload()`를 금지하며 route navigation만 Next router로 처리한다.
- 출처: product-spec §4 시간·갱신 계약
- 선행 의존성: 문서 미명시 (실행계획 V2-1과 중복 — 아래 교차 참조)
- 완료 기준: 문서 미명시 (스펙엔 계약만; Exit는 계획 V2-1에)

**P-2. CommandSubject 계약을 정의한다**
- 요약: `subjectType(GENERAL|RETAINER|BUGOK|SUBFACTION)`, `subjectId`, `orderedByGeneralId`, `executionOwnerGeneralId`, `queueScope(PERSONAL|OPERATION|NATION)`, `idempotencyKey`를 갖는 명령 주체 계약을 확정한다.
- 출처: product-spec §6 핵심 도메인 계약 / CommandSubject
- 선행 의존성: 문서 미명시
- 완료 기준: 문서 미명시

**P-3. Operation 도메인 계약을 정의한다**
- 요약: `targetCityId`·`arrivalWindow`·`participants`·`roles(MAIN|SUPPORT|SCOUT|SUPPLY|RESERVE)`·`route`·`rules(intercept/retreat/siege/supply)` 계약을 확정한다. 기존 `che_출병`은 v2 sandbox/world profile에서만 단독 Operation으로 감싸며 v1 production 판정·로그·result는 불변이다.
- 출처: product-spec §6 / Operation
- 선행 의존성: 문서 미명시 (계획상 실제 활성화는 V2-3 이후)
- 완료 기준: 문서 미명시

**P-4. BattleReplay 결정적 계약(Envelope/Body/Hash)을 정의한다**
- 요약: `ReplayEnvelope`(id·world·operation·persistedLogEntryIds), `DeterministicReplayBody`(snapshot/input hash·seed·content/balance/geography version·phases[APPROACH~AFTERMATH]·rngDraws·orderedStateDiff·normalizedLogEntries), `deterministicReplayHash`를 정의한다. persistence metadata는 동등성 비교에서 제외한다.
- 출처: product-spec §6 / BattleReplay
- 선행 의존성: 문서 미명시
- 완료 기준: 같은 world snapshot+operation input+seed+version이면 같은 Body와 hash·결과 (§6 서술; 측정 gate는 §11·계획 V2-4A/4B)

**P-5. RetainerProposal 계약을 정의한다**
- 요약: `retainerId·subjectId·proposalType·targetId·score·confidence·evidence[]·biasFactors[]·expiresAt·status`를 저장하는 제안 계약. 런타임 LLM 미사용, 규칙 점수+템플릿으로 생성하고 입력 feature와 score를 저장한다.
- 출처: product-spec §6 / RetainerProposal
- 선행 의존성: 문서 미명시
- 완료 기준: 문서 미명시 (측정 Exit는 계획 V2-5)

**P-6. FeudalContract 계약을 정의한다**
- 요약: `lordSubfactionId·vassalSubfactionId·fiefIds·tributeRate·reinforcementObligation·diplomacyRight·autonomy·loyalty·breachConditions·expiresAt` 봉신 계약을 확정한다.
- 출처: product-spec §6 / FeudalContract
- 선행 의존성: 문서 미명시 (계획 V2-7에서 구현)
- 완료 기준: 문서 미명시

**P-7. 개인턴·사령턴·전술 명령 3계층 경계를 확정한다**
- 요약: 기존 개인턴·사령턴을 보존하고 실시간 전투에 전술 명령 계층을 추가한다. 대상·정본·예시·시간·엔진·저장·실패를 3계층으로 표로 분리하고, 전술 명령은 `battleId+formationId+sequence+issuedAtTick+expiresAtTick` 전용 명령으로 만들어 국가 예약턴을 소비하지 않게 한다.
- 출처: product-spec §6 / 개인턴·사령턴·전술 명령의 경계
- 선행 의존성: 문서 미명시
- 완료 기준: 문서 미명시
- 비고: 추가 분해 필요 (계층별 registry·drain·adapter로 쪼갤 여지)

**P-8. v1 커맨드 카탈로그 진화 상태 모델을 만든다**
- 요약: 모든 커맨드를 `commandId·legacyCode·layer·sourceRing·targetScope·adapter·version·parityStatus·deprecatedAt` 카탈로그로 관리하고, 보존·확장·분리·통합·폐기 5규칙과 `personal.*·chief.*·operation.*·battle.*·campaign.*` namespace를 적용한다.
- 출처: product-spec §9 / v1 커맨드 카탈로그의 진화 규칙
- 선행 의존성: 세부는 `2026-07-12-v2-command-catalog-and-rollout.md` 정본 참조
- 완료 기준: 문서 미명시 (계획 V2-1과 중복)
- 비고: 추가 분해 필요

**P-9. 건물·인프라 CityProject 계약과 첫 건물군을 정의한다**
- 요약: 건물을 즉시 버프가 아니라 국가계획→도시 프로젝트 중간상태로 두고 `projectId·cityId·templateId·sponsorNationId·assignedGeneralId·cost·upkeep·progress·priority·prerequisites·startedAt·completesAt·status`로 저장한다. 첫 건물군(곡창·역참·망루·성벽·병영·시장)의 capability와 발효 조건, `chief.build.*`/`personal.cityProject.*` 명령과 `campaign.cityProject.*` 도메인 이벤트를 정의한다.
- 출처: product-spec §9 / 건물·인프라의 추가 방식
- 선행 의존성: 문서 미명시 (건물 template은 EraPack에 배치)
- 완료 기준: 문서 미명시
- 비고: 추가 분해 필요

**P-10. 임무형 지휘(4층 군령) 명령 모델을 정의한다**
- 요약: 기본 명령을 어택땅이 아니라 임무→군령(위험도)→행동 규칙→보고·재지휘 4층으로 구성하고, 판정 근거(장수 성향·관계·정찰 신뢰도·지형·보급)를 숨은 랜덤 대신 노출한다. UI 기본 버튼은 `임무 선택→위험도/행동 규칙→부대 위임`.
- 출처: product-spec §9 / 삼국지식 임무형 지휘
- 선행 의존성: 문서 미명시
- 완료 기준: 문서 미명시
- 비고: 추가 분해 필요

**P-11. 화면 8종 구조를 설계한다**
- 요약: 메인·지도(3D 기본+정보 fallback)·명령 작업대·국정·작전·replay·가신·회의·모바일 화면 구조와 각 읽기 모델 연결을 설계한다. 권한 없는 장수도 구조와 비활성 사유를 볼 수 있어야 한다.
- 출처: product-spec §7 화면 구조
- 선행 의존성: 문서 미명시
- 완료 기준: 문서 미명시
- 비고: 추가 분해 필요 (화면별로 분리 가능)

**P-12. 전체 역사 지리 표면(현·읍·도·후국 전수 참여) 요구를 확정한다**
- 요약: 지도는 축약판이 아니라 『후한서』 군국지 순제기 기준선을 전사하고 189년 변경을 적용해 모든 치소를 조회·점령·주둔·징병·세입·보급에 참여시킨다. `PhysicalPlace` 2,000개(4개 `PlaceBudgetClass`), `PolityNetwork` 240(CatalogBudget), catalog LOD Tier A/B/C 120/380/1,500, runtime LOD 4종을 정의한다.
- 출처: product-spec §7 / 전체 역사 지리 표면
- 선행 의존성: 세부는 `2026-07-13-v2-historical-city-army-terrain-design.md` 정본
- 완료 기준: 문서 미명시 (측정 gate는 §11·계획 V2-G0/V2-8)
- 비고: 추가 분해 필요 (계획 V2-G0가 이걸 wave로 분해)

**P-13. 공통 전술 엔진 기반 7종을 정의한다**
- 요약: `BattleState·BattleTopology·BattleClock·OrderIntent·FormationModel·BattleEvent/BattleReplay·BattleServerAuthority`를 시대 비의존으로 정의해 첫 전투를 `ContinuousTopology+REALTIME_FIXED_TICK`로 표현할 수 있게 한다.
- 출처: product-spec §10 / 처음부터 만들어야 하는 전술 엔진 기반
- 선행 의존성: 문서 미명시 (계획 Spike V2-B0가 구현)
- 완료 기준: 문서 미명시
- 비고: 추가 분해 필요

**P-14. 공통 계층(CampaignWorld→Operation→BattleInstance)과 Pack 분리 계약을 정의한다**
- 요약: `CampaignWorld→Operation→BattleInstance` 3계층과 `EraPack·FactionPack·UnitTemplate·Doctrine·TerrainTemplate·BattleRuleset·Scenario`를 분리해 공통 엔진이 시대를 직접 가정하지 않게 한다.
- 출처: product-spec §10 / 공통 계층
- 선행 의존성: 문서 미명시
- 완료 기준: "새 시대 추가 시 기존 캠페인·명령·replay 엔진을 복사하지 않는가"가 공통 성공 기준 (§10)

**P-15. 제품·운영 성공 기준을 측정 gate로 고정한다**
- 요약: command acceptance p95<200ms, commandResolved 후 영향 query 2초 이내, replay 생성 p95<1초, 동일입력 hash diff 0, v1 gate 회귀 0, 3D proof scene 공통 spatial snapshot, 2,000개 catalog identity 유지, `CountyParticipationFixture` 1,180 전수, 데스크톱 60/모바일 30 FPS 등 §11의 13개 기준을 검증 가능한 gate로 만든다.
- 출처: product-spec §11 제품·운영 성공 기준
- 선행 의존성: 문서 미명시
- 완료 기준: §11에 열거된 각 수치가 곧 완료 기준 (실행계획 각 phase Exit로 분산)
- 비고: 추가 분해 필요 (기준별 개별 fixture로 쪼갤 것)

### 비범위 (product-spec §8, 그대로)
- v2 MVP에서 112개 커맨드 전체 구현.
- 실시간 병사 단위 조작, 병사 시점 자유 카메라, cinematic, 런타임 LLM, 결제·인앱, 네이티브 앱, 다국어.
- 1,180개 현급 거점을 플레이어가 같은 깊이로 반복 관리하는 UI (전수 simulation ≠ 전수 수동 관리).
- 기존 v1 production s1에 실험용 v2 schema/seed 직접 주입.
- 패러티 골든·게이트 약화 또는 v2 편의를 위한 PHP 동작 변경.

추가로 §9 "넣지 않을 콘텐츠": 반복 클릭형 일일 퀘스트·무작위 전리품·과금형 능력치·장식용 3D(핵심 루프 증명 전), AI 즉석 서술문 생성.

---

## 문서 2: `2026-07-12-opensamguk-v2-execution-plan.md` (실행 계획)

### 목적/범위 요약
제품 정본 스펙을 phase 단위 실행 계획으로 번역한 문서다. 운영 원칙 6개(v1/v2 경계, v1 gate 녹색 전제, 1 phase=1 가설=1 수직결과, 판단 근거 기록, 단일 write 경로, read-only→deterministic→sandbox live 승격)와 선행 기준선을 세우고, V2-0A부터 V2-8까지의 phase/wave/spike/track과 각 Exit 기준을 정의한다. 격리(0A)→역사지리·3D 계약(G0)→sandbox 적재(0B)→명령 lifecycle(1)→부곡(2)→전술/콘텐츠 spike(B0/C0)→콘텐츠 승격(C1~C5)→작전(3)→전투 replay spine(4A)→실시간 전투(4B)→가신(5)→정체성 spike(I0)→회의(6)→관직 spike(O0)→황실·봉신(7)→3D·모바일 hardening(8) 순서다. 매 phase 공통 게이트와 자동 구현 금지(승인·보류) 항목으로 마감한다.

### 문서 구조 (phase/wave/spike/track, 순서 보존)
- 운영 원칙 (6개)
- 선행 기준선 (6축 표)
- **Phase V2-0A** — production 격리 선행 게이트
- **Phase V2-G0** — 역사 지리 카탈로그·3D 공간 계약
  - Wave G0-A: 행정 기준선
  - Wave G0-B: 주변 세계
  - Wave G0-C: 3D 공간 증명
- **Phase V2-0B** — sandbox runtime 적재
- **Phase V2-1** — 이벤트 기반 명령 lifecycle + 조작 대상
- **Phase V2-2** — 부곡 read foundation
- **Spike V2-B0** — 전술 상태 계약
- **Spike V2-C0** — 시대 비의존 콘텐츠 계약
- **Track V2-C1..C5** — 콘텐츠 카탈로그 승격
- **Phase V2-3** — 작전·협공 foundation
- **Phase V2-4A** — BattleSession·replay spine
- **Phase V2-4B** — 실시간 formation 전투 수직 슬라이스
- **Phase V2-5** — 가신 vertical slice
- **Spike V2-I0** — 국가 정체성 조합 계약
- **Phase V2-6** — 국가 회의와 편견
- **Spike V2-O0** — 관직·조정 권한 계약
- **Phase V2-7** — 황실·관직·도독부·봉신 계약
- **Phase V2-8** — 3D 지도·모바일·출시 hardening
- 콘텐츠 출시 순서 (1~9)
- 장기 규칙셋 확장선
- 매 phase 공통 게이트
- 승인·보류 결정

### 티켓 후보 (실행 계획)

**E-0A-1. production v2 route/profile 격리 게이트를 세운다**
- 요약: v2 route를 `/game/v2-lab/` 아래로 제한하고 `V2_ENABLED=true`+`v2-sandbox` profile 동시 만족 시에만 route·bean 등록. v2 Flyway location·catalog loader root를 v1 기본에서 분리하고 production compose/s1 profile에서 배제.
- 출처: execution-plan / Phase V2-0A / 작업
- 선행 의존성: 문서 미명시 (0A가 G0보다 먼저 — 명시)
- 완료 기준: production profile의 v2 route·bean·migration·catalog loader 수 0; `V2_ENABLED` 없거나 false면 v2-lab route 404이고 v1 결과 기준선 동일; v1 schema·seed·PHP golden diff 0, backend/web gate 녹색; 격리 계약 fresh review `cleared`.

**E-0A-2. v2 미로드를 architecture test로 고정하고 기준선 artifact를 저장한다**
- 요약: production application context에서 v2 controller·bean·route·Flyway location·catalog scan이 0개임을 architecture test로 고정하고, v1 schema dump·scenario seed hash·PHP golden·backend/web gate를 기준선 artifact로 저장한다. v2 catalog는 `content/v2/` read-only build input으로 시작(classpath 자동 scan·startup seed 금지).
- 출처: execution-plan / Phase V2-0A / 작업·Exit
- 선행 의존성: E-0A-1과 동일 phase
- 완료 기준: (E-0A-1 Exit 공유)

**E-G0A-1. 행정 기준선 계약과 140년 원전 전사를 만든다**
- 요약: `TemporalAdministrativeUnit·AdministrativeChange·PhysicalPlace·PlaceBudgetClass·SeatAssignment·PlaceControl·ScenarioPlacement` 계약+provenance/license 필드를 고정하고, 『후한서』 군국지 순제기 기준 군·국 105/현·읍·도·후국 1,180을 read-only catalog로 전사한다. 주·군·현 레코드 수와 물리 장소 수를 분리하고 군치·국치·현치 co-location fixture를 만든다.
- 출처: execution-plan / Phase V2-G0 / Wave G0-A
- 선행 의존성: V2-0A 통과 후에만 시작 (명시)
- 완료 기준: (V2-G0 Exit) 140년 행정 레코드 원전 수량·delta·lineage·before hash·189 재생성 hash 통과; SeatAssignment 중복·기간겹침 0, 근거없는 placeIdentityKey 복제 0; co-location fixture는 1 PhysicalPlace+2 role로 통과.

**E-G0A-2. 189년 snapshot fold 파이프라인을 만든다**
- 요약: `CREATE(10)→SPLIT(20)→MERGE(30)→RENAME(40)→REPARENT(50)→MOVE_SEAT(60)→RETIRE(70)` priority·dependency·ID 보존·lineage·불확실 날짜 branch 규칙을 적용해 189년 snapshot을 baseline fold로 생성한다. 위치 미확정 치소는 후보 region+deterministic `ScenarioPlacement`를 쓰고 근거 없는 단일 좌표를 만들지 않는다. SeatAssignment 중첩·placeIdentityKey 복제 validator를 만든다.
- 출처: execution-plan / Phase V2-G0 / Wave G0-A
- 선행 의존성: E-G0A-1
- 완료 기준: (V2-G0 Exit) 189 snapshot 재생성 hash 검사 통과; 140년 baseline 현급 1,180 전수가 상위 군·국·SeatAssignment·resolved point/후보 region ScenarioPlacement를 갖고 simulation 참여; `EXCLUDED=0`, naked unknown=0, orphan=0.

**E-G0A-3. CountyParticipationFixture(현급 1,180 전수 6기능)를 만든다**
- 요약: `CountyParticipationFixture`가 현급 1,180개 각각에서 조회·점령·주둔·징병·세입·보급 순수 command/read-model 전이를 실행한다(G0에서는 production DB write 없이 장소별 초기 snapshot 복원). 기능별 성공 수와 no-op·교차 오염을 집계한다.
- 출처: execution-plan / Phase V2-G0 / Wave G0-A
- 선행 의존성: E-G0A-1, E-G0A-2
- 완료 기준: (V2-G0 Exit) 6개 카운터가 각각 `1,180/1,180`이고 no-op·다른 장소 상태 오염 0.

**E-G0B-1. 주변 세계(PolityNetwork) 계약과 240 예산을 만든다**
- 요약: `PolityNetwork·PolityNode·PolityMembership·PolityRelation·PolityTransition·DiplomaticActorAssignment·TerritorialPresence·SeasonalRange`를 고정하고, 한반도 군현+부여·고구려·동옥저·읍루·예·삼한·왜 국읍을 불확실성·시기와 함께 넣는다. 흉노·오환·선비·강·저·서역·산월·남중·교주 주변을 군현식 고정 도시로 바꾸지 않는다. 주변 정치 240·물리 장소 500은 `CatalogBudget`으로 관리(별도 집계, claim 없는 slot은 record/완료수량 금지).
- 출처: execution-plan / Phase V2-G0 / Wave G0-B
- 선행 의존성: V2-0A 통과 후 (phase 레벨)
- 완료 기준: (V2-G0 Exit) 한반도·왜 fixture의 attestationDate·subjectPeriod·`CANDIDATE|ACTIVE|EXCLUDED` 검증, 후대 국가·대방군·야마타이를 시대착오·단일확정좌표로 만들지 않음; polity graph·presence·range의 orphan·기간위반·lineage불일치·중복 actor assignment 0, transition replay hash diff 0.

**E-G0B-2. 189 ScenarioActivationManifest와 attestation 분리를 만든다**
- 요약: 모든 claim에 `attestationDate`와 `subjectPeriod`를 분리하고 189년 `ScenarioActivationManifest`를 만든다. 3세기 목록에만 근거한 국읍은 자동 활성화하지 않는다. 연맹 형성→계절 이동→분열→두 actor 승격을 두 번 재생해 hash diff 0을 단언한다.
- 출처: execution-plan / Phase V2-G0 / Wave G0-B
- 선행 의존성: E-G0B-1
- 완료 기준: (V2-G0 Exit) transition replay hash diff 0; 3세기 근거 국읍 자동활성화 없음.

**E-G0C-1. 3D 공간 증명 scene(도시3·route2·terrain1)을 만든다**
- 요약: Three.js 정사영 전략 scene에 도시 3·route 2·terrain patch 1을 렌더하고, 동일 `PhysicalPlace·RouteCorridor·projectionVersion`으로 picking·작전 경로·전장 anchor·replay camera를 왕복한다. WebGL 불가 환경은 같은 read model 정보 fallback(별도 이동·점령 규칙 없음).
- 출처: execution-plan / Phase V2-G0 / Wave G0-C
- 선행 의존성: V2-0A 통과 후
- 완료 기준: (V2-G0 Exit) 3개 장소 spatial identity proof; 비어있지 않은 canvas·desktop 60/mobile 30 FPS 목표를 Playwright screenshot·canvas pixel·frame telemetry로 검증.

**E-G0C-2. 2,000 거점 예산 분류·LOD·streaming을 synthetic+실제 catalog로 검증한다**
- 요약: 최종 2,000 거점을 상호배타 `PlaceBudgetClass`(행정정착 1,200·전략 비행정 200·주변 500·해상 교역관문 100)로 분류하고 class별 count·합계를 검증한다. catalog LOD Tier A/B/C 120/380/1,500 synthetic fixture로 instancing·clustering·streaming 성능을 시험하고 runtime LOD `CLUSTER|SYMBOL|KIT|FULL_SCENE`을 독립축으로 왕복한다. 같은 검사를 2,000개 실제 source catalog 전체에 적용(시나리오 날짜 밖 항목은 연대기 모드).
- 출처: execution-plan / Phase V2-G0 / Wave G0-C
- 선행 의존성: E-G0C-1
- 완료 기준: (V2-G0 Exit) synthetic 2,000·실제 2,000 catalog 전체 marker identity·picking·label overlap·runtime LOD 왕복 오류 0; 실제 catalog `PlaceBudgetClass` count 1,200/200/500/100, 다중 class·무분류·비장소 집계 0; v1 schema·seed·gate diff 0.

**E-0B-1. sandbox runtime에 G0 catalog(ACTIVE만)를 적재한다**
- 요약: 0A 격리를 유지한 채 G0 artifact를 v2 sandbox runtime에만 적재. `world_id` 전제 v2 migration naming·rollback 규칙 적용, v2 Flyway location이 sandbox에서만 실행됨을 검증. catalog loader가 `ScenarioActivationManifest`의 `ACTIVE`만 적재하고 `CANDIDATE·EXCLUDED·BUDGET_ONLY`는 거부. s1엔 v2 schema/seed 미적용(local/test sandbox만).
- 출처: execution-plan / Phase V2-0B / 작업
- 선행 의존성: G0 통과 (runtime 적재는 0B에서 시작 — 명시)
- 완료 기준: (V2-0B Exit) v1·web gate 녹색; sandbox boot가 s1과 독립; 140 baseline→fold→189 activation→sandbox load hash가 재시작 전후 동일; sandbox runtime 6기능 각각 현급 `1,180/1,180` 통과·G0 fixture와 결과 diff 0; event contract·schema spike 리뷰 `cleared`.

**E-0B-2. v2 event/turn payload version과 영향 query 목록을 정의한다**
- 요약: command result/turn event payload의 version과 영향 query 목록을 정의하고, v1 완료 기준의 남은 차단 항목을 `v1-completion` ledger에 갱신한다. G0의 `CountyParticipationFixture`를 sandbox runtime adapter로 반복해 API 조회·engine 전이가 in-memory 결과와 같은지 비교한다.
- 출처: execution-plan / Phase V2-0B / 작업
- 선행 의존성: E-0B-1과 동일 phase
- 완료 기준: (V2-0B Exit 공유)

**E-1-1. 이벤트 기반 명령 lifecycle(accepted/resolved/rejected)을 만든다**
- 요약: `commandAccepted·commandResolved·commandRejected` 이벤트를 정리하고, command result는 JDBC flush commit 뒤에만 발행한다. FE는 requestId 결과를 기다리고 영향 query만 invalidate한다. 본인 장수 대상 기존 예약턴은 동작 불변.
- 출처: execution-plan / Phase V2-1 / 작업
- 선행 의존성: 문서 미명시 (0B 이후 순서)
- 완료 기준: (V2-1 Exit) 장수 생성 결과가 sandbox에서 turn interval과 무관하게 관측; 700ms `front-info` 반복조회 제거; 전체 reload 없이 대상 상태 갱신; API→Redis→engine→flush→result→FE 테스트 녹색.

**E-1-2. 현재 조작 대상 패널과 command catalog layer를 추가한다**
- 요약: 메인에 `현재 조작 대상` 패널과 subject type/id를 추가한다. command catalog에 `personal.*·chief.*·operation.*·battle.*·campaign.*` layer와 legacy adapter/version/parityStatus를 추가한다. V2-1에서는 `che_출병`의 개인턴 의미·adapter metadata만 registry에 등록하고 v2 domain 객체는 만들지 않는다(Operation은 V2-3, 전술 세션은 V2-4A 이후). 삭제·통합은 parser에서 먼저 하지 않고 보존·확장·분리·공통 precheck 통합·deprecated 순서.
- 출처: execution-plan / Phase V2-1 / 작업 (+ `2026-07-12-v2-command-catalog-and-rollout.md`)
- 선행 의존성: `che_출병` Operation 생성은 V2-3 foundation 뒤, 전술 세션은 V2-4A foundation 뒤 (명시)
- 완료 기준: (V2-1 Exit 공유)

**E-2-1. 부곡 read foundation(general_bugok)을 만든다**
- 요약: `general_bugok`와 supply/location read model을 추가하고, 기존 장수 병력을 sandbox에서 기본 부곡 1개로 materialize한다. 기존 v1 전투 수치는 불변, adapter가 읽기만 병행. 장수/부곡 위치·병종·병력·훈련·사기·보급을 화면에 노출.
- 출처: execution-plan / Phase V2-2 / 작업
- 선행 의존성: 문서 미명시
- 완료 기준: (V2-2 Exit) 기존 battle golden·v1 gate diff 0; 장수와 부곡이 분리 가능한 fixture·read API 존재.

**E-B0-1. 전술 상태 계약 spike(2 topology 공통 직렬화)를 만든다**
- 요약: 작은 grid fixture와 연속 좌표 formation fixture를 각각 하나 만들어, `GridTopology+COMMAND_STEP`과 `ContinuousTopology+REALTIME_FIXED_TICK`가 같은 `BattleState·OrderIntent·BattleEvent/BattleReplay` 직렬화 계약·검증기를 쓸 수 있는지 증명한다. 이동·충돌·피해·사기·보급·승패는 서버 판정, renderer 상태는 정본 아님.
- 출처: execution-plan / Spike V2-B0
- 선행 의존성: 문서 미명시
- 완료 기준: (Spike Exit) 같은 seed·의미상 같은 명령을 두 topology에 넣었을 때 공통 event schema·replay verifier 모두 통과. 완성 전투 콘텐츠 불요구.

**E-C0-1. 시대 비의존 콘텐츠 계약 spike(PureCapabilityFixture)를 만든다**
- 요약: `FormationTemplate·Facility·InfrastructureNetwork·ResourceSite·HistoricalContentPack`과 `CatalogBudget·CatalogBudgetSlot·ContentEntry` 최소 필드를 만들고, slot(BUDGET_ONLY) 소비와 unique `ContentEntry.budgetSlotId` 생성을 한 transaction에서 양방향 참조 일치로 처리한다. formation 4(징발 창병·노수대·경기병대·수송호위대)·정착지 3(곡창·전방 군량고·농경지)·route 1만 fixture로 둔다. `PureCapabilityFixture`는 B0 순수 in-memory interface만 호출(Operation/persistence 미의존).
- 출처: execution-plan / Spike V2-C0
- 선행 의존성: V2-B0 순수 in-memory interface (명시)
- 완료 기준: (Spike Exit) 한 번의 이동·교전·재보급에서 인원·장비·군량 보존·provenance 재현; 이중소비·부분생성·slot-side/entry-side dangling 4상태가 각각 독립 validation 실패 fixture로 고정, 정상 fixture는 양방향 참조와 `NAMED→CLAIMED→FIXTURE_GREEN→ACTIVE` 전이 증명. 36/24 공개 roster는 범위 밖.

**E-C1C2. C1/C2 기술 증명(3 정착지·4 formation ACTIVE 승격)**
- 요약: V2-C0 직후·V2-3 전에 곡창·전방 군량고·농경지 3개와 징발 창병·노수대·경기병대·수송호위대 4개를 `ACTIVE`까지 승격하고 이동·교전·재보급·자원 보존·claim/evidence를 검증한다.
- 출처: execution-plan / Track V2-C1..C5 / 작업·의존 순서 1
- 선행 의존성: V2-C0 직후, V2-3 전 (명시)
- 완료 기준: 문서 미명시 (Track 종합 Exit는 C5에 기술 — 개별 wave는 manifest·failure fixture 요구)

**E-C3. C3 첫 공개 roster(formation 36·시설등 24 ACTIVE)**
- 요약: V2-4B 수직 전투 녹색 뒤 formation 36개와 시설·기반망·자원 유형 합계 24개를 `ACTIVE`로 연다. 공개 sandbox는 이 gate 뒤에만 연다.
- 출처: execution-plan / Track V2-C1..C5 / 작업·의존 순서 2
- 선행 의존성: V2-4B 수직 전투 녹색 (명시)
- 완료 기준: 문서 미명시 (개별)
- 비고: 추가 분해 필요

**E-C4. C4 정체성·황실 확장 콘텐츠 승격**
- 요약: V2-6·V2-7에서 쓰는 태평도·도적·오두미도·학교·조정·인장·역참 콘텐츠를 같은 slot/lifecycle로 승격하고 해당 phase Exit의 선행 조건으로 둔다.
- 출처: execution-plan / Track V2-C1..C5 / 작업·의존 순서 3
- 선행 의존성: V2-6·V2-7이 이 콘텐츠를 소비 (명시)
- 완료 기준: 문서 미명시 (개별)
- 비고: 추가 분해 필요

**E-C5. C5 전체 카탈로그 정확 수량 ACTIVE 승격**
- 요약: V2-8 release candidate 전에 formation 120·시설 72·기반망 18·자원 유형 24·정착지 kit 24·지형·계절 profile 32를 각각 `ACTIVE`로 만든다. 각 entry는 capability·시기/지역 제약·claim/evidence·AI·플레이어 공통 판정 fixture를 갖는다. wave별 slot 소비량·lifecycle 수량·failure fixture를 machine-readable manifest+테스트 보고서로 남긴다. 근거 부족 시 이름을 지어 목표를 채우지 않고 wave를 실패시킨다.
- 출처: execution-plan / Track V2-C1..C5 / 작업·의존 순서 4·5·Exit
- 선행 의존성: V2-8 release candidate 전 (명시); C5 완료 전 V2-8 release gate 통과 불가
- 완료 기준: (Track Exit) `ACTIVE` count가 120/72/18/24/24/32와 정확 일치(이전 lifecycle·`BUDGET_ONLY` 제외); 모든 ACTIVE entry의 slot/entry 양방향 참조·unique budgetSlotId·claim/evidence·capability·공통 판정 fixture 누락 0; 4종 dangling/이중소비/부분생성 fixture 모두 실패, 동일 manifest 두 번 build content hash diff 0; v1 content table·PHP golden·backend/web gate diff 0.
- 비고: 추가 분해 필요 (family별로 쪼갤 것)

**E-3-1. 작전·협공 foundation(operations 테이블군)을 만든다**
- 요약: `operations·operation_participants·operation_routes·operation_events`를 추가하고 주공·조공·정찰·보급·예비 역할을 정의한다. 도착 window·경로·요격·원군 지연의 deterministic rule을 만든다. foundation 준비 후 `che_출병` adapter를 v2 sandbox/world profile에서만 활성화해 단독 Operation 생성(전술 세션 미생성, v1 production 불변).
- 출처: execution-plan / Phase V2-3 / 작업
- 선행 의존성: V2-1에서 등록된 `che_출병` adapter metadata (V2-3 foundation 뒤 Operation 활성화 — 명시)
- 완료 기준: (V2-3 Exit) 단독 출병·2부대 협공·원군 지연 3개 operation event fixture 존재; 같은 seed/입력에서 operation event diff 0.

**E-4A-1. BattleSession·replay spine(3 phase)을 만든다**
- 요약: `battle_sessions·battle_replays·battle_replay_phases·battle_participants`를 추가하고 첫 fixture는 `approach→field→aftermath` 3 phase만 사용(scout·intercept·siege·urban은 이후). wall-clock id/timestamp 제외 phase input/decision/rng/state diff/log sequence로 deterministic replay body·hash를 만들고 read-only replay timeline을 만든다.
- 출처: execution-plan / Phase V2-4A / 작업
- 선행 의존성: 준비된 Operation 1건 (V2-3 산출물); 전술 세션 생성은 V2-4A foundation 뒤 (V2-1에서 명시)
- 완료 기준: (V2-4A Exit) 실제 operation 1건이 replay·정세 log를 함께 생성; replay gate가 seed·phase 순서·state diff·canonical body hash 검증; 전쟁 결과가 기존 v1 로그와 모순 없음.

**E-4B-1. 실시간 formation 전투 수직 슬라이스(보급 호송/차단)를 만든다**
- 요약: 사각형 타일 UI 대신 작은 연속 좌표 전장에 formation footprint·전면 방향·지휘 반경·보급 경로만 표시. 징발 창병·노수대·경기병대·수송호위대를 배치하고 `MOVE·HOLD·SUPPORT·WITHDRAW·commitReserve`만 연다. 임무는 보급 마차 호송/차단 하나, 행동 규칙은 교전금지·보급하한·퇴각조건만. production은 짧은 명령 창·queued order·AI 위임, pause/slow는 sandbox·관전·replay만. 이동·사격·돌격·보급·퇴각·사기붕괴 event를 V2-4A replay body에 기록하고 campaign 정산으로 반환.
- 출처: execution-plan / Phase V2-4B / 작업
- 선행 의존성: V2-C0의 4 formation·3 정착지, V2-4A replay body (명시)
- 완료 기준: (V2-4B Exit) 동일 world snapshot·operation input·seed에서 배치·이동순서·전투결과·replay body hash 동일; 보급선 절단·퇴각 결과가 작전·도시·인물 상태 반영; 4 formation/3 settlement 이외 콘텐츠 불요구. (모바일·2D fallback·30fps·추가 임무·공성/시가전 phase는 V2-8로 이월)

**E-5-1. 가신 vertical slice(참모 1명·제안 1종)를 만든다**
- 요약: `general_retainers·retainer_proposals·retainer_interactions`를 추가하고 참모 1명·제안 1종(공격 또는 보급)을 구현한다. score·confidence·evidence·bias factor를 저장하고 승인 시 operation draft/command queue로 연결한다.
- 출처: execution-plan / Phase V2-5 / 작업
- 선행 의존성: 문서 미명시 (승인 연결 대상 operation draft는 V2-3 산출물)
- 완료 기준: (V2-5 Exit) 같은 상태·seed에서 제안·근거 재현; 승인/거부/만료가 모두 명시적 상태; 런타임 외부 API·LLM 호출 0.

**E-I0-1. 국가 정체성 조합 계약 spike(3 프리셋)를 만든다**
- 요약: 유가·태평도·도적 세 프리셋과 학교·방·산채 조직망만 만들어 `FactionIdentityProfile+NetworkPresence+ReformAdoption`이 이름·전역 수치 보너스 없이 다른 플레이를 만드는지 증명한다.
- 출처: execution-plan / Spike V2-I0
- 선행 의존성: 문서 미명시
- 완료 기준: (Spike Exit) 동일 도시 상태에서 세 프리셋의 가용 명령·시설 요구·외교 선택·AI 우선순위가 달라지고, 전환 후에도 이전 조직망·반대 세력이 보존.

**E-6-1. 국가 회의와 편견(polity_councils)을 만든다**
- 요약: `polity_councils·council_opinions·bias_profiles`를 추가한다(과거 `court_councils` 명칭은 `ImperialCourt`와 충돌해 미사용). 참모·군수관·사신 의견을 deterministic rule로 생성하고 찬성/반대/보류·확신·근거·편향을 화면에 표시. 플레이어의 채택·보류·수정·거부를 operation draft로 만들되 실제 침공 개설은 개인 장수 `che_출병`.
- 출처: execution-plan / Phase V2-6 / 작업
- 선행 의존성: C4 콘텐츠(태평도 등)가 선행 조건 (Track 명시); operation draft는 V2-3 산출물
- 완료 기준: (V2-6 Exit) 같은 seed에서 의견·결정 diff 0; 편향 근거가 UI에 보임.

**E-6-2. 국가 계획·건설 연결(첫 건설 fixture 2종)을 만든다**
- 요약: 사령턴이 외교와 같은 국가 계획 계층에서 건설 예산·우선순위·도시 프로젝트를 예약하고 개인턴이 실제 착공·감독·인력/자원 투입을 실행한다. 첫 건설 fixture는 곡창·역참·망루·성벽·병영·시장 중 2개만 선택해 경제·전술 효과가 함께 보이게 한다.
- 출처: execution-plan / Phase V2-6 / 국가 계획·건설 연결
- 선행 의존성: 문서 미명시
- 완료 기준: 문서 미명시

**E-6-3. 국가 성향·조직망 slice(3 성향 합성)를 만든다**
- 요약: 제자백가·종교·도적 이름을 유지하고 `정통성 청중+통치형태+전통+지역 조직망+정책`을 합성한다. 유가 학교·추천망, 태평도 方, 도적 산채·산길을 같은 `FactionIdentityProfile`로 구현하고, 타국 도시 은밀 조직망·도시 없는 도적 연맹·조직망 탄압/수용을 지원한다. 개혁은 전국 연구 완료가 아니라 담당관·시범 군현·시설·예산·현지 채택을 거친다.
- 출처: execution-plan / Phase V2-6 / 국가 성향·조직망 slice (+ `2026-07-13-v2-nation-identity-rework.md`, `2026-07-13-v2-troop-building-content-catalog.md`)
- 선행 의존성: V2-I0 spike 결과 소비 (계약 상 연속)
- 완료 기준: (추가 exit) 세 성향이 수치 보너스 없이 서로 다른 명령·시설·외교·AI 우선순위를 만들고, 이전 조직망을 삭제하지 않은 채 제도 전환을 replay.

**E-O0-1. 관직·조정 권한 계약 spike를 만든다**
- 요약: `OfficeDefinition·OfficeNomination·OfficeClaim·OfficeTenure·OperationalAssignment·ImperialCourt·CourtProtectorate·CourtSettlement` 최소 상태만 만들고, 황제 임명자 vs 자칭자가 같은 지방관직을 주장하는 fixture 1개, `봉대·보정·협제` 전환 fixture 1개만 둔다.
- 출처: execution-plan / Spike V2-O0
- 선행 의존성: 문서 미명시
- 완료 기준: (Spike Exit) 관직명만으로 행동 권한이 생기지 않고, claim origin·수락·부임·치소·속관·예산·인장·실무 위임을 바꿀 때 capability resolver 결과와 replay가 함께 바뀜.

**E-7-1. 황실·관직·도독부·봉신 계약(subfactions 등)을 만든다**
- 요약: `subfactions·fiefs·feudal_contracts·subfaction_orders`를 추가하고 도시 1개를 봉토로 부여. 조공·원군 의무·외교권·자율성·충성·위반 조건을 계산하고 중앙 원군 요청을 수락·지연·축소로 처리한다.
- 출처: execution-plan / Phase V2-7 / 작업
- 선행 의존성: V2-O0 spike, C4 콘텐츠(조정·인장·역참) (명시)
- 완료 기준: (V2-7 Exit) 봉토 수입·원군 응답·계약 위반이 replay/log에 남음; v1 국가/외교 패러티와 분리된 sandbox fixture 녹색; 봉신 도시의 건설권한·조공·원군 의무가 같은 `CityProject`·`FeudalContract` 조건으로 검증.

**E-7-2. 관직 lifecycle과 황실 운영(봉대·보정·협제)을 만든다**
- 요약: 중앙관직·지방관직·장군 commission·작위·소속 군벌 역할을 별도 포트폴리오로 저장하고 추천→심의→임명/변경/보류/기각→수락→부임 및 자칭→추인 lifecycle을 구현. 황제 신변·조정 소재지·상서 기구·경비·군량·인장/절·역참을 별도 상태로 만들고, 황제 확보 세력이 봉대·보정·협제 방침을 공표하며 행동에 따라 상태 이동. 조서의 제안·재가·기초·봉인·전달·수신·집행 workflow와 수신 세력 거부를 구현.
- 출처: execution-plan / Phase V2-7 / 작업 (+ `2026-07-13-v2-imperial-court-office-reform-equipment-design.md`)
- 선행 의존성: V2-O0 spike
- 완료 기준: (V2-7 Exit) 황제 임명 관직·자칭 관직이 동시 존재하고 치소·속관·세입·군대에 따라 실효 지배가 별도 계산; 조서 거부 군벌이 영토를 잃지 않되 정통성·외교 비용을 받음; 황제 소재 도시 점령만으로 `CourtProtectorate` 자동 이전 안 됨.
- 비고: 추가 분해 필요 (관직 lifecycle / 황실 상태 / 조서 workflow로 분리 가능)

**E-8-1. 3D 실제 catalog·LOD·군현 관리 표면을 hardening한다**
- 요약: `PhysicalPlace` 2,000개와 catalog LOD Tier A/B/C 120/380/1,500·runtime LOD 4종의 picking·label·streaming·상태 갱신 규칙을 고정하고, C5의 ACTIVE manifest(formation 120 등)를 실제 지도·모집·건설·보급 표면에 연결. 군현 검색·필터·다중 선택 예외·이상 알림·위임 이력/철회/복구를 실제 catalog에서 검증.
- 출처: execution-plan / Phase V2-8 / 작업
- 선행 의존성: V2-G0(synthetic 2,000), V2-C5(ACTIVE manifest) (명시)
- 완료 기준: (V2-8 Exit) synthetic 2,000·실제 2,000 catalog 전체 blank canvas·label overlap·picking identity 오류 0; C5 exact-count·no-placeholder·slot/entry 무결성 gate 녹색, `BUDGET_ONLY`·중간 lifecycle 미노출.
- 비고: 추가 분해 필요

**E-8-2. 추가 전투 phase·모바일·전송 방식·onboarding을 연다**
- 요약: V2-4A 최소 phase 뒤 `scout·intercept·siege·urban`과 추가 임무 preset을 하나씩 연다. 3D 정사영 지도의 데스크톱·모바일 조작과 접근 가능한 selection/readout, WebGL 불가·저성능 정보 fallback 자동 전환(3D와 다른 simulation 금지)을 통과시킨다. SRTM 등 현대 지형은 물리 기반, 역사 하도·도로·정착지는 provenance reconstruction overlay로 분리. WebSocket/STOMP vs SSE 확장을 실제 부하 측정 후 선택하고 알림·replay·작전·가신·봉신 onboarding·도움말을 작성.
- 출처: execution-plan / Phase V2-8 / 작업
- 선행 의존성: V2-4A 최소 phase (명시)
- 완료 기준: (V2-8 Exit) 데스크톱 60/모바일 30 FPS 또는 fallback 자동 전환; 이벤트 freshness 2초 이내; 두 세션에서 작전 상태·replay 완료 동기화; production sandbox smoke·v1 regression gate 모두 녹색.
- 비고: 추가 분해 필요

**E-GATE. 매 phase 공통 게이트를 CI/체크리스트로 고정한다**
- 요약: PHP 경계 있는 v1 변경은 `opensamguk-php-oracle` source/line+golden evidence 기록, UI 변경은 `webapp-testing`, 가설/baseline/결정적 grader/채택·원복 기준을 `docs/loops/v2-*`에 기록, `tools/agent-system/check.py --strict --base origin/main --format json` 통과, v1 backend/web gate 통과, implementation 외부 fresh reviewer가 `cleared|fix-required|quarantined-with-proof` 판정을 낸다.
- 출처: execution-plan / 매 phase 공통 게이트
- 선행 의존성: 모든 phase에 공통 적용
- 완료 기준: 위 6개 항목이 각 phase 통과 조건

### 승인·보류 결정 (execution-plan, 자동 구현 금지 — 그대로)
- 기본 production cadence를 60분 외 값으로 변경.
- v2 world를 s1에 직접 생성하거나 기존 s1 데이터를 변환.
- 3D 지도 asset/license/인프라 비용 확정.
- v1 gate 또는 golden 기대값 완화.

---

## 문서 간 중복/충돌 지점

**중복(같은 작업을 양쪽이 기술 — 계획을 정본으로):**
1. **이벤트 소비 계약** — spec §4(4종 이벤트+reload 금지) ↔ plan V2-1(3종 lifecycle 이벤트+700ms 조회 제거). 스펙은 프론트 소비 이벤트 4종(`commandResolved·turnCompleted·battlePhaseChanged·notificationCreated`), 계획은 명령 lifecycle 이벤트 3종(`commandAccepted·commandResolved·commandRejected`). 이름 집합이 달라 보이나 층위가 다름(소비 이벤트 vs lifecycle 이벤트) — 충돌 아님, 병합 시 구분 유지.
2. **커맨드 카탈로그 진화** — spec §9(진화 규칙·상태 필드) ↔ plan V2-1(layer·adapter·parityStatus 등록). 둘 다 `2026-07-12-v2-command-catalog-and-rollout.md`를 정본 위임.
3. **Operation/che_출병 감싸기** — spec §6(sandbox/world profile에서만 단독 Operation) ↔ plan V2-1·V2-3(metadata만 V2-1, Operation 생성 V2-3). 계획이 활성화 시점을 못박음 — 스펙보다 구체.
4. **BattleReplay 결정성** — spec §6(Body/hash 계약) ↔ plan V2-4A/4B(replay gate·hash diff 0). 스펙=계약, 계획=검증 gate. 정합.
5. **역사 지리 2,000 거점·LOD** — spec §7 ↔ plan V2-G0/V2-8. 수치(1,200/200/500/100, Tier 120/380/1,500) 동일.
6. **성공 기준** — spec §11 ↔ plan 각 phase Exit. 대부분 동일 수치가 phase Exit로 분산. p95<200ms, hash diff 0, 60/30 FPS, 1,180/1,180 등이 양쪽에 반복.

**명시된 명칭 충돌 해소(계획이 처리):**
- plan V2-6가 "과거 계획의 `court_councils` 명칭은 황제의 `ImperialCourt`와 충돌하므로 사용하지 않는다"고 명시 → `polity_councils` 채택. 이건 문서가 스스로 해소한 과거 계획과의 충돌.

**드러난 긴장(충돌은 아니나 주의):**
- spec §4는 사용자 소비 이벤트에 `notificationCreated`를 포함하나, plan V2-1 작업에는 명시적으로 없음(V2-8 onboarding에 알림 등장). 알림 이벤트의 최초 도입 phase가 계획에 못박혀 있지 않음 — 티켓화 시 phase 귀속 확인 필요.
- spec §7은 화면 8종(가신·회의 등)을 한 번에 열거하나 계획은 이를 V2-5(가신)·V2-6(회의)로 분산. 화면 스펙은 phase보다 앞서 정의되어 있으니 UI 티켓은 phase 귀속을 계획 기준으로 잡아야 함.

**실질적 상호 모순은 발견되지 않음.** 두 문서 모두 `current-round-review-cleared-2026-07-15` 상태이며, 계획 "선행 기준선" 표가 "이번 제품 spec이 (PRD/ROADMAP과 기존 v2 계획의) 충돌을 해소"한다고 명시한다.

---

## 문서에 명시된 "하지 않을 것"(non-goals) 통합

**product-spec §8 비범위**
- v2 MVP에서 112개 커맨드 전체 구현.
- 실시간 병사 단위 조작, 병사 시점 자유 카메라, cinematic, 런타임 LLM, 결제·인앱, 네이티브 앱, 다국어.
- 1,180개 현급 거점을 플레이어가 같은 깊이로 반복 관리하는 UI.
- 기존 v1 production s1에 실험용 v2 schema/seed 직접 주입.
- 패러티 골든·게이트 약화 또는 v2 편의를 위한 PHP 동작 변경.

**product-spec §9 넣지 않을 콘텐츠**
- 반복 클릭형 일일 퀘스트·무작위 전리품·과금형 능력치·장식용 3D(핵심 루프 증명 전).
- AI 즉석 서술문 생성(모든 사건·대사·판정은 feature·seed·규칙·템플릿으로 재현).

**product-spec 곳곳의 전역 금지**
- 전체 `window.location.reload()` 사용(§4).
- 사각형 타일을 플레이어 전장 기본 표현으로 채택(§9, 내부 분할·미니맵·경로탐색에만).
- 클라이언트가 전투 소유(§10, 서버 fixed tick 권위).

**execution-plan 승인·보류 결정(자동 구현 금지)**
- production cadence 60분 외 변경 / v2 world를 s1에 직접 생성·변환 / 3D asset·license·인프라 비용 확정 / v1 gate·golden 완화.

**execution-plan 각 phase의 부정 제약(대표)**
- V2-0A: production에 v2 route·bean·migration·catalog loader 0.
- V2-G0: 근거 없는 단일 좌표 금지, claim 없는 slot을 record/완료수량으로 금지, synthetic fixture를 역사 catalog로 세지 않음.
- V2-0B: s1에 v2 schema/seed 미적용, ACTIVE 아닌 항목 적재 거부.
- V2-3: 전술 세션 미생성·v1 production 불변.
- V2-4B: pause/slow는 sandbox·관전·replay만, 4/3 이외 콘텐츠 불요구.
- V2-5: 런타임 외부 API·LLM 호출 0.
- V2-C5: 근거 부족 시 이름 지어 목표 채우지 않고 wave 실패.
- 장기 확장선: 어느 확장도 v1 production·v2 오픈삼국 sandbox에 새 시대 데이터 직접 주입 금지.

---

## 티켓화 시 유의점 (분해 판단)
- **완료 기준 부재 항목**: 제품 스펙의 P-2·P-3·P-5·P-6·P-7·P-8·P-9·P-10·P-11 등 도메인 계약/화면 티켓은 스펙 자체에 측정 Exit이 없다(계획의 대응 phase Exit로 완결됨). 티켓화 시 계획 phase Exit을 acceptance로 링크할 것.
- **"추가 분해 필요" 표시 티켓**: P-7·P-8·P-9·P-10·P-11·P-12·P-13·P-15(스펙), E-C3·E-C4·E-C5·E-7-2·E-8-1·E-8-2(계획)는 문서가 큰 단위로만 기술 → 1~3일 단위로 추가 분해 필요.
- **하위 스펙 4종 위임**: 지리(`...historical-city-army-terrain-design`), 병종·건물(`...troop-building-content-catalog`), 정체성(`...nation-identity-rework`), 황실·관직(`...imperial-court-office-reform-equipment-design`), 커맨드 롤아웃(`...command-catalog-and-rollout`)은 두 문서가 정본 위임한다. 세부 티켓 수량은 이 하위 문서들을 읽어야 확정 가능(이번 분해 범위 밖).

두 문서 모두 읽기 전용으로만 다뤘고, 파일 수정은 없다.