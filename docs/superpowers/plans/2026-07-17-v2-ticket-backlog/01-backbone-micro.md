# v2 백본 문서 2종 — 최소 단위 재분해

재분해 원칙: 문서가 **이름 붙여 구분한 산출물**(계약/enum, 테이블, validator, fixture, 명령, 이벤트, gate, FE 배선)마다 티켓 1개. 문서가 세부를 더 안 준 지점만 "추가 분해 필요". 완료 기준은 대부분 **phase Exit 공유**(직전 반환의 Exit 그대로) — 개별 티켓 단위 Exit는 문서 미명시. 목적/구조/중복충돌/비범위 섹션은 **직전 반환과 동일(변경 없음)**, 하단에 비범위만 요약 재수록.

표기: `ID. 제목` — 1줄 요약. 각 phase 헤더에 출처·선행·공유 Exit를 한 번씩.

---

## 문서 1: product-spec — 최소 티켓

### §4 이벤트 소비 계약 (선행: 미명시 / Exit: 문서 미명시, 계획 V2-1 Exit로 완결)
- P-1a. `commandResolved` payload·소비 계약 정의
- P-1b. `turnCompleted` payload·소비 계약 정의
- P-1c. `battlePhaseChanged` payload·소비 계약 정의
- P-1d. `notificationCreated` payload·소비 계약 정의
- P-1e. 전역 `window.location.reload()` 제거 + route는 Next router만 사용 가드

### §6 도메인 계약 (선행: 미명시 / Exit: 문서 미명시)
- P-2. `CommandSubject` 계약(subjectType/subjectId/orderedBy/executionOwner/queueScope/idempotencyKey) 정의 — 단일 struct, 이미 최소
- P-3. `Operation` 계약(targetCityId/arrivalWindow/participants/roles/route/rules) 정의 — 단일 struct
- P-4a. `ReplayEnvelope` 필드 정의
- P-4b. `DeterministicReplayBody` 필드 정의(phases APPROACH~AFTERMATH enum 포함)
- P-4c. `deterministicReplayHash` canonicalSerialize 함수 정의
- P-5. `RetainerProposal` 계약 정의 — 단일 struct
- P-6. `FeudalContract` 계약 정의 — 단일 struct
- P-7a. 개인턴/사령턴/전술 3계층 정의표(대상·정본·엔진·저장·실패) 문서화
- P-7b. 전술 명령 식별자 계약(`battleId+formationId+sequence+issuedAtTick+expiresAtTick`) 정의
- P-7c. 계층별 registry·drain·adapter 분리 — **추가 분해 필요**(문서 세부 미제공)

### §9 커맨드 진화 규칙 (선행: 하위 롤아웃 문서 / Exit: 문서 미명시)
- P-8a. 카탈로그 레코드 필드(`commandId..deprecatedAt`) 정의
- P-8b. namespace enum(`personal/chief/operation/battle/campaign`) 정의
- P-8c. 진화 상태(보존/확장/분리/통합/폐기) 전이 규칙 정의
- P-8d. 커맨드별 재배치·병합·분리 매핑 — **추가 분해 필요**(`...command-catalog-and-rollout.md` 정본 필요)

### §9 건물·인프라 (선행: 미명시 / Exit: 문서 미명시)
- P-9a. `CityProject` 계약(projectId..status) 정의
- P-9b. `chief.build.plan` 명령 정의
- P-9c. `chief.build.assign` 명령 정의
- P-9d. `personal.cityProject.execute` 명령 정의
- P-9e. `personal.cityProject.pause` 명령 정의
- P-9f. `campaign.cityProject.created/progressed/paused/completed` 도메인 이벤트 정의
- P-9g. 첫 건물군 template/capability — 6종 각 1티켓(곡창·군량창 / 역참·군도 / 망루·봉화대 / 성벽·관문 / 병영·훈련장 / 시장·수운 시설). 단, template은 EraPack·C-track lifecycle 준수(중복 관리 필요)

### §9 임무형 지휘 (선행: 미명시 / Exit: 문서 미명시)
- P-10a. 임무(mission) 타입 목록 정의(적장 생포·보급선 차단·관문 점령·퇴로 확보·시각 방어)
- P-10b. 군령(위험도) 타입 정의(전군 진격·선봉만·결사·피해 최소·유인)
- P-10c. 행동 규칙(재량 범위) 타입 정의
- P-10d. 보고·재지휘(유지/수정/철회) 전이 정의
- P-10e. 판정 근거 노출 계약(성향·관계·정찰 신뢰도·지형·보급) 정의
- P-10f. 역할↔게임역할 매핑(군주·군사·주공·부장·전령) 정의
- P-10g. UI 3단(`임무→위험도/규칙→위임`) 설계

### §7 화면 (선행: 미명시 / Exit: 문서 미명시) — 화면당 1티켓
- P-11a 메인 / P-11b 지도(3D+정보 fallback) / P-11c 명령 작업대 / P-11d 국정 / P-11e 작전 / P-11f replay / P-11g 가신 / P-11h 회의 / P-11i 모바일

### §7 역사 지리 표면 (선행: 하위 지리 문서 / Exit: 문서 미명시)
- P-12. 스펙 수치 동결(PhysicalPlace 2,000·4 class, PolityNetwork 240, catalog LOD 120/380/1,500, runtime LOD 4종). **실 구현 최소 티켓은 계획 E-G0*/E-8* 사용**(중복 — 계획을 정본)

### §10 전술 엔진 기반 (선행: 미명시 / Exit: "새 시대 추가 시 엔진 미복사") — 컴포넌트당 1티켓
- P-13a `BattleState` / P-13b `BattleTopology` / P-13c `BattleClock` / P-13d `OrderIntent` / P-13e `FormationModel` / P-13f `BattleEvent`+`BattleReplay` / P-13g `BattleServerAuthority` 계약 정의. (Spike V2-B0가 구현 — 중복)

### §10 공통 계층·Pack (선행: 미명시 / Exit: §10)
- P-14a. `CampaignWorld→Operation→BattleInstance` 계층 경계 정의
- P-14b~h. Pack 인터페이스 7종 각 1티켓: `EraPack`/`FactionPack`/`UnitTemplate`/`Doctrine`/`TerrainTemplate`/`BattleRuleset`/`Scenario`

### §11 성공 기준 (선행: 미명시 / Exit: 각 수치 자체) — 기준당 gate 1티켓
- P-15a p95<200ms / P-15b commandResolved→query 2초 / P-15c replay p95<1초 / P-15d hash diff 0 / P-15e v1 gate 회귀 0 / P-15f 승인→replay→관계 재현 / P-15g 3D 공통 snapshot / P-15h 2,000 catalog identity / P-15i CountyParticipationFixture 1,180 (**V2-G0 오픈 후 gate; OP43 선행 아님**) / P-15j 정책 전파 우선순위 / P-15k 60·30 FPS / P-15l production v2 0개. (대부분 계획 phase Exit와 중복 — 계획을 정본)

---

## 문서 2: execution-plan — 최소 티켓

### Phase V2-0A (선행: G0보다 먼저 / 공유 Exit: production v2 0개·404·diff 0·gate 녹색·review cleared)
- 0A-a. v2 route `/game/v2-lab/` namespace 제한
- 0A-b. `V2_ENABLED`+`v2-sandbox` 동시 조건 route/bean 등록 게이트
- 0A-c. v2 Flyway location을 v1 기본에서 분리
- 0A-d. v2 catalog loader root `content/v2/` read-only(classpath scan·startup seed 금지)
- 0A-e. production compose/s1 profile에서 v2 Flyway·catalog·flag 제거
- 0A-f. production context v2 0개 architecture test
- 0A-g. 기준선 artifact 저장(v1 schema dump·seed hash·PHP golden·backend/web gate)

### Phase V2-G0 / Wave G0-A 행정 기준선 (v2 오픈 후 작업; OPENSAM-43 V2-0B의 선행 아님 / 공유 Exit: 원전 수량·189 hash·SeatAssignment 0중복·co-location 통과·counter 1,180)
- G0A-a~g. 계약 7종 각 1티켓: `TemporalAdministrativeUnit`/`AdministrativeChange`/`PhysicalPlace`(+provenance·license)/`PlaceBudgetClass`/`SeatAssignment`/`PlaceControl`/`ScenarioPlacement`
- G0A-h. 군·국 105 read-only catalog 전사
- G0A-i. 현·읍·도·후국 1,180 read-only catalog 전사
- G0A-j. 주·군·현 레코드 수 vs 물리 장소 수 분리 집계
- G0A-k. 군치·국치·현치 co-location fixture(1 PhysicalPlace+2 role)
- G0A-l. change fold 우선순위 엔진 `CREATE(10)→…→RETIRE(70)`
- G0A-m. ID 보존·lineage·불확실 날짜 branch 규칙
- G0A-n. 189 snapshot baseline fold 생성 + 재생성 hash 검사
- G0A-o. 위치 미확정 치소 후보 region + deterministic `ScenarioPlacement`
- G0A-p. SeatAssignment 중첩/중복 validator
- G0A-q. `placeIdentityKey` 복제 validator
- G0A-r~w. `CountyParticipationFixture` counter 6종 각 1티켓: 조회/점령/주둔/징병/세입/보급

### Wave G0-B 주변 세계 (선행: V2-0A 통과 / 공유 Exit: 한반도·왜 attestation·orphan 0·transition hash diff 0)
- G0B-a~h. 계약 8종 각 1티켓: `PolityNetwork`/`PolityNode`/`PolityMembership`/`PolityRelation`/`PolityTransition`/`DiplomaticActorAssignment`/`TerritorialPresence`/`SeasonalRange`
- G0B-i. 한반도 군현 데이터 입력(불확실성·시기)
- G0B-j. 부여·고구려·동옥저·읍루·예 국읍 입력
- G0B-k. 삼한 국읍 입력
- G0B-l. 왜 복수 국읍 입력
- G0B-m. 흉노·오환·선비·강·저·서역·산월·남중·교주 주변 비-군현 표현(군현식 고정 금지 가드)
- G0B-n. 주변 정치 240 `CatalogBudget` 집계
- G0B-o. 주변 물리 장소 500 `CatalogBudget` 집계
- G0B-p. `attestationDate`/`subjectPeriod` 분리
- G0B-q. 189 `ScenarioActivationManifest` 생성(3세기-only 자동활성화 금지)
- G0B-r. polity graph orphan/기간/camp/lineage/actor validator
- G0B-s. 연맹형성→계절이동→분열→2 actor 승격 2회 재생 hash diff 0

### Wave G0-C 3D 공간 증명 (선행: V2-0A 통과 / 공유 Exit: identity·picking·label·LOD 오류 0·class count 1,200/200/500/100·60·30 FPS)
- G0C-a. Three.js 정사영 scene 도시3·route2·terrain1 렌더
- G0C-b. `PhysicalPlace/RouteCorridor/projectionVersion` 공유 picking 왕복
- G0C-c. 작전 경로 왕복
- G0C-d. 전장 anchor 왕복
- G0C-e. replay camera 왕복
- G0C-f. WebGL 불가 정보 fallback(동일 read model)
- G0C-g. 2,000 거점 4-class 분류·count·합계 검증
- G0C-h. catalog LOD Tier A/B/C synthetic fixture(instancing·clustering·streaming)
- G0C-i. runtime LOD 4종 독립축 왕복
- G0C-j. 실제 2,000 source catalog 전체 동일 검사(연대기 모드 포함)
- G0C-k. 60/30 FPS Playwright screenshot·canvas pixel·frame telemetry 검증

### Phase V2-0B (선행: V2-0A 격리 게이트; G0는 오픈 후 / 공유 Exit: gate 녹색·v1 production v2 미적용·pinned `cities_1010` source SHA/94/24·반복 typed snapshot diff 0·review cleared)

> **2026-08-09 승인 정정 (ADR-LITE-030).** 이 phase의 구 G0 통과·counter 1,180·gameplay
> `CountyParticipationFixture` 선행은 supersede되었다. `scenario/cities_1010.json`은 기존
> tracked source payload이고, `content/v2/cities_1010.json`은 이를 가리키는 metadata일 뿐
> 도시 행 복사본이 아니다. V2-G0/1,180/fixture 자체는 오픈 후로 보존한다.

- 0B-a. canonical v1 non-operational completion ledger **참조** (새 v1 완료 주장 복사 금지)
- 0B-b. v2 profile/world 식별자 runtime integration test
- 0B-c. feature flag 0A 계약 runtime integration test
- 0B-d. `world_id` 전제 v2 migration naming/rollback 규칙
- 0B-e. v2 Flyway sandbox-only 실행 검증 test
- 0B-f. catalog loader ACTIVE-only 적재·CANDIDATE·EXCLUDED·BUDGET_ONLY 거부
- 0B-g. metadata가 가리키는 tracked `scenario/cities_1010.json` typed adapter 반복 적재 +
  SHA-256 `6759a68255cae1a6b9c05cbbaf5736ed8fc9fcb50c6623be44d7e3dfe0b4d393`·94 total·24 owned·in-memory diff 0 비교
- 0B-h. command result payload version 정의
- 0B-i. turn event payload version 정의
- 0B-j. 실제 world/profile/catalog/wire/Flyway query·read·write impact seam inventory (완료 주장 금지)
- 0B-k. v1 production v2 bean/probe table/content 미적용 guard (local/test sandbox 전용)

### Phase V2-1 (선행: `che_출병` Operation은 V2-3 뒤·전술은 V2-4A 뒤 / 공유 Exit: interval 무관 관측·700ms 제거·reload 없이 갱신·e2e 녹색)
- 1-a. `commandAccepted` 이벤트 정의/발행
- 1-b. `commandResolved` 이벤트(flush commit 뒤만) 정의/발행
- 1-c. `commandRejected` 이벤트 정의/발행
- 1-d. command result가 flush commit 뒤 발행되도록 순서 보장
- 1-e. FE requestId 결과 대기 + 영향 query invalidate
- 1-f. 700ms `front-info` 반복 조회 제거
- 1-g. 전역 reload 제거
- 1-h. 메인 `현재 조작 대상` 패널(subject type/id)
- 1-i. command catalog layer 필드 추가
- 1-j. legacy adapter/version/parityStatus 필드 추가
- 1-k. `che_출병` 개인턴 metadata registry 등록(v2 domain 미생성)
- 1-l. API→Redis→engine→flush→result→FE e2e 테스트

### Phase V2-2 (선행: 미명시 / 공유 Exit: battle golden·v1 gate diff 0·분리 fixture+read API)
- 2-a. `general_bugok` 스키마/마이그레이션
- 2-b. supply/location read model
- 2-c. 기존 장수 병력 → 기본 부곡 1개 materialize(sandbox)
- 2-d. read-only adapter(v1 전투 수치 불변)
- 2-e. 장수/부곡 위치·병종·병력·훈련·사기·보급 화면 노출
- 2-f. 장수·부곡 분리 fixture + read API
- 2-g. battle golden·v1 gate diff 0 회귀 확인

### Spike V2-B0 (선행: 미명시 / 공유 Exit: 두 topology 공통 schema·verifier 통과)
- B0-a. grid fixture 1개
- B0-b. 연속 좌표 formation fixture 1개
- B0-c. `BattleState` 공통 직렬화 계약
- B0-d. `OrderIntent` 공통 직렬화 계약
- B0-e. `BattleEvent`/`BattleReplay` 공통 직렬화 계약
- B0-f. 서버 판정(이동·충돌·피해·사기·보급·승패), renderer 비정본
- B0-g. 공통 event schema + replay verifier 두 topology 통과 gate

### Spike V2-C0 (선행: V2-B0 in-memory interface / 공유 Exit: 보존·provenance 재현·4 dangling 실패·정상 전이 증명)
- C0-a~e. 계약 5종 최소 필드 각 1티켓: `FormationTemplate`/`Facility`/`InfrastructureNetwork`/`ResourceSite`/`HistoricalContentPack`
- C0-f~h. `CatalogBudget`/`CatalogBudgetSlot`/`ContentEntry` 최소 필드 각 1티켓
- C0-i. slot 소비+entry 생성 단일 transaction 양방향 참조
- C0-j. formation 4/정착지 3/route 1 fixture
- C0-k. 이중 소비 실패 validation fixture
- C0-l. 부분 생성(slot만 소비) 실패 fixture
- C0-m. slot-side dangling 실패 fixture
- C0-n. entry-side dangling 실패 fixture
- C0-o. 정상 fixture: 양방향 참조 + `NAMED→CLAIMED→FIXTURE_GREEN→ACTIVE` 전이
- C0-p. `PureCapabilityFixture`(이동·교전·재보급 보존, B0 in-memory only)

### Track V2-C1..C5 (공유 Exit: C5 exact-count 120/72/18/24/24/32·양방향 참조·dangling 실패·hash diff 0·v1 gate diff 0)
- **C1 (선행: V2-C0 직후·V2-3 전)**: C1-a 곡창 / C1-b 전방 군량고 / C1-c 농경지 ACTIVE 승격
- **C2 (동)**: C2-a 징발 창병 / C2-b 노수대 / C2-c 경기병대 / C2-d 수송호위대 ACTIVE 승격
- C1C2-e. 이동·교전·재보급·자원 보존·claim/evidence 검증
- **C3 (선행: V2-4B 녹색)**: C3-a formation 36 ACTIVE — **추가 분해 필요**(개별 이름 미제공) / C3-b 시설·기반망·자원 24 ACTIVE — **추가 분해 필요** / C3-c 공개 sandbox gate 오픈
- **C4 (선행: V2-6·V2-7 소비)** — 콘텐츠군당 1티켓: C4-a 태평도 / C4-b 도적 / C4-c 오두미도 / C4-d 학교 / C4-e 조정 / C4-f 인장 / C4-g 역참 승격
- **C5 (선행: V2-8 RC 전)**: C5-a formation 120 / C5-b 시설 72 / C5-c 기반망 18 / C5-d 자원 24 / C5-e 정착지 kit 24 / C5-f 지형·계절 profile 32 — 각 family별 manifest 1티켓이되 **개별 entry는 추가 분해 필요**(이름 미제공) / C5-g exact-count+slot/entry 무결성+dangling gate / C5-h 동일 manifest 2회 build hash diff 0

### Phase V2-3 (선행: V2-1 `che_출병` metadata / 공유 Exit: 3 event fixture·event diff 0)
- 3-a `operations` / 3-b `operation_participants` / 3-c `operation_routes` / 3-d `operation_events` 스키마 각 1티켓
- 3-e. 역할 정의(주공·조공·정찰·보급·예비)
- 3-f 도착 window / 3-g 경로 / 3-h 요격 / 3-i 원군 지연 deterministic rule 각 1티켓
- 3-j. `che_출병` adapter → 단독 Operation 생성 활성화(sandbox/world profile only)
- 3-k. operation event fixture 3종(단독/2부대 협공/원군 지연)
- 3-l. operation event diff 0 gate

### Phase V2-4A (선행: V2-3 Operation 1건 / 공유 Exit: replay+정세 log·replay gate·v1 로그 비모순)
- 4A-a `battle_sessions` / 4A-b `battle_replays` / 4A-c `battle_replay_phases` / 4A-d `battle_participants` 스키마 각 1티켓
- 4A-e. approach/field/aftermath 3 phase fixture
- 4A-f. phase input/decision/rng/state diff/log sequence → deterministic replay body
- 4A-g. replay body hash(wall-clock id/timestamp 제외)
- 4A-h. read-only replay timeline
- 4A-i. replay gate(seed·phase 순서·state diff·canonical hash)
- 4A-j. v1 로그 비모순 확인

### Phase V2-4B (선행: V2-C0 4 formation·3 정착지 + V2-4A replay body / 공유 Exit: 동일 입력 배치·순서·결과·hash 동일·정산 반영·4/3 이외 불요구)
- 4B-a. 연속 좌표 전장 렌더(footprint·전면 방향·지휘 반경·보급 경로)
- 4B-b. 4 formation 배치
- 4B-c `MOVE` / 4B-d `HOLD` / 4B-e `SUPPORT` / 4B-f `WITHDRAW` / 4B-g `commitReserve` 명령 각 1티켓
- 4B-h. 임무 1종(보급 호송/차단)
- 4B-i. 행동 규칙(교전 금지·보급 하한·퇴각 조건)
- 4B-j. production 짧은 명령 창·queued order·AI 위임
- 4B-k. pause/slow는 sandbox·관전·replay만 가드
- 4B-l. 전투 event → V2-4A replay body 기록
- 4B-m. campaign 정산 반환(작전·도시·인물 반영)
- 4B-n. 동일 입력 결정성 gate

### Phase V2-5 (선행: operation draft는 V2-3 산출물 / 공유 Exit: 재현·명시 상태·LLM 0)
- 5-a `general_retainers` / 5-b `retainer_proposals` / 5-c `retainer_interactions` 스키마 각 1티켓
- 5-d. 참모 1명 구현
- 5-e. 제안 1종(공격 또는 보급) 규칙
- 5-f. score/confidence/evidence/bias factor 저장
- 5-g. 승인 → operation draft/command queue 연결
- 5-h. 승인/거부/만료 명시 상태 전이
- 5-i. 같은 상태·seed 재현 + LLM 0 gate

### Spike V2-I0 (선행: 미명시 / 공유 Exit: 3 프리셋 명령·시설·외교·AI 차이·전환 후 보존)
- I0-a `FactionIdentityProfile` / I0-b `NetworkPresence` / I0-c `ReformAdoption` 계약 각 1티켓
- I0-d 유가+학교 / I0-e 태평도+方 / I0-f 도적+산채 프리셋 각 1티켓
- I0-g. 차이 + 전환 후 보존 gate

### Phase V2-6 (선행: C4 콘텐츠·operation draft V2-3 / 공유 Exit: 의견·결정 diff 0·편향 UI)
- 6-a `polity_councils` / 6-b `council_opinions` / 6-c `bias_profiles` 스키마 각 1티켓
- 6-d 참모 / 6-e 군수관 / 6-f 사신 의견 deterministic rule 각 1티켓
- 6-g. 찬성/반대/보류·확신·근거·편향 화면 표시
- 6-h. 채택/보류/수정/거부 → operation draft
- 6-i. 의견·결정 diff 0 gate
- 6-j. 건설 fixture: 6종 중 2개 선택(경제+전술 효과)
- 6-k. `FactionIdentityProfile` 합성(정통성 청중+통치형태+전통+조직망+정책)
- 6-l 유가 학교·추천망 / 6-m 태평도 方 / 6-n 도적 산채·산길 구현 각 1티켓
- 6-o. 타국 은밀 조직망 / 도시 없는 도적 연맹 / 탄압·수용
- 6-p. 개혁 확산(담당관·시범 군현·시설·예산·현지 채택)
- 6-q. 3 성향 차이 + 조직망 미삭제 제도전환 replay gate

### Spike V2-O0 (선행: 미명시 / 공유 Exit: 관직명만으로 권한 없음·resolver+replay 동시 변화)
- O0-a~h. 계약 8종 각 1티켓: `OfficeDefinition`/`OfficeNomination`/`OfficeClaim`/`OfficeTenure`/`OperationalAssignment`/`ImperialCourt`/`CourtProtectorate`/`CourtSettlement`
- O0-i. 황제 임명자 vs 자칭자 동일 지방관직 fixture
- O0-j. 봉대·보정·협제 전환 fixture
- O0-k. capability resolver + replay 동시 변화 gate

### Phase V2-7 (선행: V2-O0 spike·C4 콘텐츠 / 공유 Exit: 봉토수입·원군·위반 replay·sandbox fixture 녹색·실효지배 별도·조서거부 비용·CourtProtectorate 비자동)
- 7-a `subfactions` / 7-b `fiefs` / 7-c `feudal_contracts` / 7-d `subfaction_orders` 스키마 각 1티켓
- 7-e. 도시 1개 봉토 부여
- 7-f 조공 / 7-g 원군 의무 / 7-h 외교권 / 7-i 자율성 / 7-j 충성 / 7-k 위반 조건 계산 각 1티켓
- 7-l. 중앙 원군 요청 수락/지연/축소 처리
- 7-m 중앙관직 / 7-n 지방관직 / 7-o 장군 commission·작위·군벌 역할 포트폴리오 각 1티켓
- 7-p. 관직 lifecycle 추천→심의→임명/변경/보류/기각→수락→부임
- 7-q. 자칭→추인 lifecycle
- 7-r. 황제 신변·조정 소재지 상태
- 7-s. 상서 기구·경비·군량 상태
- 7-t. 인장·절·역참 상태
- 7-u. 봉대·보정·협제 방침 공표 + 상태 이동
- 7-v. 조서 workflow(제안·재가·기초·봉인·전달·수신·집행)
- 7-w. 수신 세력 조서 거부 처리
- 7-x. 실효 지배 별도 계산(치소·속관·세입·군대)
- 7-y. CourtProtectorate 비자동 이전 가드
- 7-z. sandbox fixture + CityProject/FeudalContract 조건 검증 gate

### Phase V2-8 (선행: V2-G0 synthetic 2,000·V2-C5 manifest·V2-4A 최소 phase / 공유 Exit: 오류 0·60·30 FPS·freshness 2초·2세션 동기화·smoke·regression 녹색)
- 8-a. `PhysicalPlace` 2,000 picking/label/streaming/상태 갱신 규칙
- 8-b. catalog LOD Tier A/B/C 규칙 고정
- 8-c. runtime LOD 4종 규칙 고정
- 8-d. C5 ACTIVE manifest → 실제 지도·모집·건설·보급 표면 연결
- 8-e 군현 검색·필터 / 8-f 다중 선택 예외 / 8-g 이상 알림 / 8-h 위임 이력·철회·복구 각 1티켓
- 8-i `scout` / 8-j `intercept` / 8-k `siege` / 8-l `urban` phase 각 1티켓
- 8-m. 추가 임무 preset(1개씩) — **추가 분해 필요**(preset 목록 미제공)
- 8-n 3D 데스크톱 조작 / 8-o 3D 모바일 조작+접근성 selection·readout 각 1티켓
- 8-p. SRTM 현대 지형(물리 기반)
- 8-q. 역사 하도·도로·정착지 reconstruction overlay 분리
- 8-r. WebGL 불가/저성능 fallback 자동 전환
- 8-s. WebSocket/STOMP vs SSE 부하 측정 후 선택 — **승인·보류 성격**(3D asset/전송 방식은 문서상 자동 확정 금지 대상 아님이나 부하측정 선행)
- 8-t. 알림·replay·작전·가신·봉신 onboarding·도움말
- 8-u 60·30 FPS gate / 8-v freshness 2초 gate / 8-w 2세션 동기화 gate

### 매 phase 공통 게이트 (전 phase 공통) — 항목당 1체크
- GATE-a PHP oracle source/line+golden / GATE-b webapp-testing / GATE-c `docs/loops/v2-*` 가설·baseline·grader·채택·원복 / GATE-d `tools/agent-system/check.py --strict --base origin/main --format json` / GATE-e v1 backend/web gate / GATE-f 외부 fresh reviewer(`cleared|fix-required|quarantined-with-proof`)

---

## 문서 간 중복/충돌 — 직전 반환과 동일(변경 없음). 재분해로 새로 드러난 중복만 추가:
- **P-12 ↔ E-G0*/E-8***, **P-13 ↔ B0-***, **P-4 ↔ 4A-f/g**, **P-9 ↔ 6-j·C-track**, **P-15 ↔ 각 phase Exit** — 스펙 계약 티켓과 계획 구현 티켓이 같은 산출물을 가리킨다. 티켓화 시 스펙 티켓은 "계약 동결", 계획 티켓은 "구현"으로 라벨을 분리해 중복 착수를 막을 것.

## 비범위(그대로) — 직전 반환과 동일. 요약:
- MVP 112 커맨드 전체 / 병사 단위 조작·자유 카메라·cinematic·런타임 LLM·결제·네이티브·다국어 / 1,180 현급 동일 깊이 반복 관리 UI / s1에 v2 schema·seed 직접 주입 / 패러티 gate·PHP 약화. §9: 일일 퀘스트·무작위 전리품·과금 능력치·장식 3D·AI 즉석 서술. 계획 승인·보류: cadence 60분 외·s1 v2 world 생성·3D asset·license·인프라 비용·v1 gate/golden 완화는 자동 구현 금지.

2026-08-09에 OPENSAM-43의 stale prerequisite만 ADR-LITE-030에 맞춰 정합화했다. 그 밖의
최소 티켓 분해는 역사 기록으로 유지한다.
