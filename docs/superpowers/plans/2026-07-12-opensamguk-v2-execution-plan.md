# 오픈삼국 v2 실행 계획

> 작성일: 2026-07-12
> 상태: reviewed-plan, current-round-review-cleared-2026-07-15
> 정본 제품 문서: `docs/superpowers/specs/2026-07-12-opensamguk-v2-product-spec.md`
> 근거 대조: `docs/superpowers/research/2026-07-12-v2-source-reconciliation.md`

## 운영 원칙

1. v1과 v2는 같은 저장소에서 관리하되 world/profile/schema 경계를 둔다.
2. 기존 v1 backend·web 패러티 gate가 모두 녹색인 상태에서만 v2 slice를 시작한다. 문서화된 실패도 시작 조건을 대체하지 않는다.
3. 한 phase는 한 가설과 한 수직 결과만 가진다.
4. 모든 자동 판단은 seed, 입력 feature, score, 결과를 기록한다.
5. 신규 write는 `ChangeRecorder → JdbcFlushExecutor` 경로로만 통과한다.
6. read-only → deterministic mutation → sandbox live 순서로 승격한다.

## 선행 기준선

| 축 | 현재 증거 | v2 영향 |
|---|---|---|
| v1 엔진 | s1 1분 profile에서 출병·틱·flush 진행 확인 | v2는 1분을 QA profile로만 사용 |
| 명령 intake | Redis immediate command와 command result 채널 존재 | result는 flush 이후 발행하는 계약으로 고정 |
| UI refresh | SSE `turnCompleted`가 `front-info` soft refresh | 이벤트별 query invalidation으로 세분화 |
| 지도 | 2D map preview/MapViewer, level별 detail/basic 규칙 | 공통 spatial read model을 먼저 잠그고 3D를 기본 표면, 2D를 정보 fallback으로 사용 |
| 패러티 | backend gate, PHP golden, v1 기준서 존재 | v2 신규 replay는 별도 golden/replay gate |
| 문서 | PRD/ROADMAP Rev 2와 기존 v2 계획이 서로 다른 cadence | 이번 제품 spec이 충돌을 해소 |

## Phase V2-0A — production 격리 선행 게이트

이 phase가 V2-G0보다 먼저다. 역사 catalog나 3D proof 코드를 추가하기 전에 같은 artifact와 배포 구성에서 v1 production이 v2를 전혀 로드하지 않는 경계를 고정한다.

### 작업

- v2 route namespace를 `/game/v2-lab/` 아래로 제한하고 `V2_ENABLED=true`와 `v2-sandbox` profile을 동시에 만족할 때만 route와 bean을 등록한다.
- v2 Flyway location과 catalog loader root를 v1 기본 location에서 분리한다. production compose와 s1 profile에는 v2 Flyway location, catalog path, feature flag를 넣지 않는다.
- v2 catalog artifact는 `content/v2/`의 read-only build input으로 시작하며 classpath 자동 scan이나 startup seed를 금지한다.
- production application context에서 v2 controller·bean·route·Flyway location·catalog scan이 모두 0개인지 architecture test로 고정한다.
- v1 schema dump, scenario seed hash, PHP golden, backend/web gate를 기준선 artifact로 저장한다.

### Exit

- production profile의 v2 route·bean·migration·catalog loader 수가 0이다.
- `V2_ENABLED`가 없거나 false이면 v2-lab route가 404이고 v1 route 결과가 기준선과 같다.
- v1 schema·seed·PHP golden diff 0, backend/web gate 녹색.
- 격리 계약에 대한 fresh review가 `cleared`다.

## Phase V2-G0 — 역사 지리 카탈로그·3D 공간 계약

이 phase는 V2-0A가 통과한 뒤에만 시작한다. runtime DB migration과 production world write를 만들지 않고, 출처가 붙은 catalog artifact, 검증기, synthetic fixture, `v2-sandbox`에서만 등록되는 3D proof route를 만든다. runtime 적재는 V2-0B에서 시작한다.

### 가설

행정단위, 물리 장소, 치소 배정, 지배 상태, 주변 정치·이동 네트워크를 먼저 분리하고 같은 spatial identity를 3D 전략·전술 표면이 공유하면, 후한 군현 전수 등장과 2,000개 거점 렌더링을 이후 기능이 재설계 없이 소비할 수 있다.

### Wave G0-A: 행정 기준선

- `TemporalAdministrativeUnit`, `AdministrativeChange`, `PhysicalPlace`, `PlaceBudgetClass`, `SeatAssignment`, `PlaceControl`, `ScenarioPlacement` 계약과 provenance·license 필드를 고정한다.
- 『후한서』 군국지의 순제기 기준인 군·국 105, 현·읍·도·후국 1,180을 read-only catalog로 전사한다.
- 주·군·현 레코드 수와 물리 장소 수를 분리하고, 군치·국치와 현치가 같은 장소를 공유하는 fixture를 만든다.
- `CREATE(10) → SPLIT(20) → MERGE(30) → RENAME(40) → REPARENT(50) → MOVE_SEAT(60) → RETIRE(70)` priority, dependency, ID 보존·lineage·불확실 날짜 branch 규칙을 적용해 189년 snapshot을 baseline fold로 생성한다.
- 위치가 확정되지 않은 치소는 후보 region과 deterministic `ScenarioPlacement`를 사용하고, 근거 없는 단일 좌표를 만들지 않는다.
- `SeatAssignment` 기간 중첩·중복과 동일 `placeIdentityKey`의 물리 장소 복제를 실패시키는 validator를 만든다.
- `CountyParticipationFixture`가 현급 1,180개 각각에서 조회·점령·주둔·징병·세입·보급 순수 command/read-model 전이를 실행한다. G0에서는 production DB write 없이 장소별 초기 snapshot을 복원하며 기능별 성공 수와 no-op·교차 오염을 집계한다.

### Wave G0-B: 주변 세계

- `PolityNetwork`, `PolityNode`, `PolityMembership`, `PolityRelation`, `PolityTransition`, `DiplomaticActorAssignment`, `TerritorialPresence`, `SeasonalRange`를 고정한다.
- 한반도의 한 군현과 부여·고구려·동옥저·읍루·예·삼한, 왜의 복수 국읍을 불확실성·시기와 함께 넣는다.
- 흉노·오환·선비·강·저·서역·산월·남중·교주 주변을 군현식 고정 도시로 바꾸지 않는다.
- 주변 정치 네트워크 240개와 주변 물리 장소 500개는 역사 실수량이 아닌 `CatalogBudget`으로 관리한다. 장소·정치 node·presence·seasonal range를 별도 집계하고 claim 없는 slot은 catalog record나 완료 수량으로 만들지 않는다.
- 모든 claim에 `attestationDate`와 `subjectPeriod`를 분리하고 189년 `ScenarioActivationManifest`를 만든다. 3세기 목록에만 근거한 국읍은 자동 활성화하지 않는다.
- polity graph와 presence/range의 orphan·기간·camp 참조·lineage·actor assignment를 검증하고 연맹 형성→계절 이동→분열→두 actor 승격을 두 번 재생해 hash diff 0을 단언한다.

### Wave G0-C: 3D 공간 증명

- Three.js 정사영 전략 scene에 도시 3개, route 2개, terrain patch 1개를 렌더한다.
- 동일한 `PhysicalPlace`·`RouteCorridor`·`projectionVersion`으로 picking, 작전 경로, 전장 anchor, replay camera를 왕복한다.
- 최종 거점 예산 2,000개를 상호 배타적 `PlaceBudgetClass`인 행정 정착지 1,200, 전략 비행정 거점 200, 주변 거점 500, 해상·원거리 교역 관문 100으로 분류하고 class별 count와 합계를 검증한다. 같은 catalog를 LOD Tier A/B/C 120/380/1,500으로 나눈 synthetic fixture에 사용해 instancing·clustering·streaming 성능을 시험한다. runtime render LOD `CLUSTER | SYMBOL | KIT | FULL_SCENE`은 독립 축으로 왕복한다. synthetic fixture는 역사 catalog로 세지 않는다.
- 같은 검사를 synthetic뿐 아니라 2,000개 실제 source catalog 전체에 적용한다. 시나리오 날짜 밖 항목은 연대기 모드로 조회하되 stable identity와 label/picking 검사를 생략하지 않는다.
- WebGL 불가 환경은 같은 read model의 정보 fallback을 사용하며 별도 이동·점령 규칙을 만들지 않는다.

### Exit

- 140년 행정 레코드의 원전 수량, 명시된 numeric priority·dependency를 포함한 delta operation·lineage·before hash, 189년 snapshot 재생성 hash 검사가 통과한다.
- 140년 baseline의 현급 1,180개가 모두 상위 군·국, `SeatAssignment`, resolved point 또는 후보 region 안의 `ScenarioPlacement`를 갖고 실제 simulation에 참여한다. `EXCLUDED=0`, naked unknown=0, orphan=0이다. 다른 시나리오도 해당 날짜에 유효한 현급 단위의 제외를 허용하지 않는다.
- `CountyParticipationFixture`의 조회·점령·주둔·징병·세입·보급 여섯 카운터가 각각 `1,180/1,180`이고 no-op·다른 장소 상태 오염이 0이다.
- `SeatAssignment` 중복·기간 겹침 0, 근거 없는 동일 `placeIdentityKey` 복제 0이다. 군치·현치 co-location fixture는 한 `PhysicalPlace`와 두 role assignment로 통과한다.
- 한반도·왜 fixture의 `attestationDate`, `subjectPeriod`, `CANDIDATE | ACTIVE | EXCLUDED`가 검증되고 후대 국가·대방군·야마타이 위치를 시대착오나 단일 확정 좌표로 만들지 않는다.
- polity graph와 `TerritorialPresence`·`SeasonalRange`의 orphan·node/camp 기간 위반·lineage 불일치·중복 actor assignment가 0이고 transition replay hash diff 0이다.
- 3개 장소 spatial identity proof와 별도로 2,000개 synthetic fixture 및 2,000개 실제 source catalog의 모든 marker identity·picking·label overlap·runtime LOD 왕복 오류가 0이다. 비어 있지 않은 canvas, desktop 60 FPS와 지원 mobile 30 FPS 목표를 Playwright screenshot·canvas pixel·frame telemetry로 검증한다.
- 실제 source catalog의 `PlaceBudgetClass` count가 1,200/200/500/100이고 한 장소의 다중 class·무분류·비장소 객체 집계가 모두 0이다.
- v1 schema·seed·gate diff 0.

## Phase V2-0B — sandbox runtime 적재

### 목표

V2-0A에서 닫은 격리를 유지한 채 G0 artifact를 v2 sandbox runtime에만 적재한다.

### 작업

- v1 완료 기준의 남은 차단 항목을 `v1-completion` ledger에 갱신한다.
- v2 전용 profile/world 식별자와 feature flag의 0A 계약을 runtime integration test로 증명한다.
- `world_id`를 전제로 한 v2 migration naming과 rollback 규칙을 적용하고, v2 Flyway location이 sandbox에서만 실행되는지 검증한다.
- G0 catalog loader가 `ScenarioActivationManifest`의 `ACTIVE`만 적재하고 `CANDIDATE`, `EXCLUDED`, `BUDGET_ONLY`는 거부하게 한다.
- G0의 `CountyParticipationFixture`를 sandbox runtime adapter로 반복해 API 조회와 engine 상태 전이가 in-memory 결과와 같은지 비교한다.
- command result/turn event payload의 version과 영향 query 목록을 정의한다.
- s1에는 v2 schema/seed를 적용하지 않고, local/test sandbox에서만 실행한다.

### Exit

- v1 gate와 web gate가 녹색.
- v2 sandbox boot가 production s1과 독립.
- 140년 baseline→change fold→189 activation→sandbox load hash가 재시작 전후 동일.
- sandbox runtime에서도 조회·점령·주둔·징병·세입·보급이 각각 현급 `1,180/1,180`을 통과하고 G0 fixture와 결과 diff 0.
- event contract와 schema spike 리뷰가 `cleared`.

## Phase V2-1 — 이벤트 기반 명령 lifecycle + 조작 대상

### 목표

장수 생성과 일반 명령을 턴 완료나 전체 refresh에 묶지 않는다.

### 작업

- `commandAccepted`, `commandResolved`, `commandRejected` 이벤트를 정리한다.
- command result는 JDBC flush commit 뒤에만 발행한다.
- FE는 requestId 결과를 기다리고, 영향 query만 invalidate한다.
- 메인에 `현재 조작 대상` 패널과 subject type/id를 추가한다.
- 본인 장수 대상의 기존 예약턴은 동작 불변으로 유지한다.
- command catalog에 `personal.*`, `chief.*`, `operation.*`, `battle.*`, `campaign.*` layer와 legacy adapter/version/parityStatus를 추가한다.
- 상세 카탈로그의 재배치·추가·삭제·병합·분리 기준은 `docs/superpowers/specs/2026-07-12-v2-command-catalog-and-rollout.md`를 따른다.
- V2-1에서는 `che_출병`의 개인턴 의미와 adapter metadata만 registry에 등록하고 v2 domain 객체는 만들지 않는다. `Operation` 생성은 V2-3 foundation 뒤, 전술 세션 생성은 V2-4A foundation 뒤에 각각 활성화한다. 사령턴은 열린 전선 정책·보급·원군만 조정한다.
- 삭제·통합은 parser에서 먼저 하지 않는다. 보존·확장·분리·공통 precheck 통합·deprecated 순서로 이행한다.

### Exit

- 장수 생성 결과가 production-like sandbox에서 turn interval과 무관하게 관측된다.
- 700ms `front-info` 반복 조회가 제거된다.
- 전체 `window.location.reload()` 없이 대상 상태가 갱신된다.
- API→Redis→engine→flush→result→FE 테스트가 녹색.

## Phase V2-2 — 부곡 read foundation

### 목표

장수의 병력과 실행 주체를 분리할 최소 모델을 만든다.

### 작업

- `general_bugok`와 supply/location read model을 추가한다.
- 기존 장수 병력은 sandbox에서 기본 부곡 1개로 materialize한다.
- 기존 v1 전투 수치는 바꾸지 않고 adapter가 읽기만 병행한다.
- 장수/부곡 위치·병종·병력·훈련·사기·보급을 화면에 노출한다.

### Exit

- 기존 battle golden과 v1 gate diff 0.
- 장수와 부곡이 분리 가능한 fixture와 read API가 존재한다.

## Spike V2-B0 — 전술 상태 계약

**가설:** `GridTopology + COMMAND_STEP`과 `ContinuousTopology + REALTIME_FIXED_TICK`가 같은 `BattleState`, `OrderIntent`, `BattleEvent/BattleReplay` 직렬화 계약과 검증기를 사용할 수 있다.

- 작은 grid fixture와 연속 좌표 formation fixture를 각각 하나만 만든다.
- 이동·충돌·피해·사기·보급·승패는 서버가 판정하고, renderer 상태는 정본이 되지 않는다.
- **Exit:** 같은 seed와 의미상 같은 명령을 두 topology에 넣었을 때 공통 event schema와 replay verifier가 모두 통과한다. 완성 전투 콘텐츠는 요구하지 않는다.

## Spike V2-C0 — 시대 비의존 콘텐츠 계약

**가설:** simulation kernel이 시대·실명 문자열을 읽지 않고 capability 조합만으로 첫 전투와 보급을 계산할 수 있다.

- `FormationTemplate`, `Facility`, `InfrastructureNetwork`, `ResourceSite`, `HistoricalContentPack`과 `CatalogBudget`, `CatalogBudgetSlot`, `ContentEntry`의 최소 필드만 만든다.
- `CatalogBudgetSlot(BUDGET_ONLY)` 소비와 unique `ContentEntry.budgetSlotId` 생성은 한 transaction에서 처리한다. `slot.consumedByEntryId == entry.id`와 `entry.budgetSlotId == slot.id`의 양방향 참조가 일치해야 하며, validator는 중복 소비·dangling 참조·부분 생성·lifecycle 단계 건너뛰기를 거부한다.
- 징발 창병·노수대·경기병대·수송호위대 4개 formation과 곡창·전방 군량고·농경지 3개 정착지 항목, 도시 사이 route 1개만 fixture로 둔다.
- 이 spike의 `PureCapabilityFixture`는 V2-B0의 순수 in-memory simulation interface만 호출한다. `Operation`이나 campaign·전술·replay persistence에는 의존하지 않는다.
- **Exit:** 한 번의 이동·교전·재보급에서 인원·장비·군량의 보존과 provenance validation이 재현된다. 같은 slot의 이중 소비, slot만 소비된 부분 생성, slot-side dangling, entry-side dangling 네 상태가 각각 독립 validation 실패 fixture로 고정되고, 정상 fixture는 양방향 참조와 `NAMED -> CLAIMED -> FIXTURE_GREEN -> ACTIVE` 전이를 증명한다. 36개/24개 공개 roster는 이 spike의 범위가 아니다.

## Track V2-C1..C5 — 콘텐츠 카탈로그 승격

이 track은 별도 백로그가 아니라 주 실행 계획의 필수 출시선이다. V2-C0 계약을 소비하며 C5가 끝나기 전에는 V2-8 release gate를 통과할 수 없다.

### 작업·의존 순서

1. **C1/C2 기술 증명:** V2-C0 직후, V2-3 전에 곡창·전방 군량고·농경지 3개와 징발 창병·노수대·경기병대·수송호위대 4개를 `ACTIVE`까지 승격한다. 이동·교전·재보급, 자원 보존, claim/evidence를 검증한다.
2. **C3 첫 공개 roster:** V2-4B 수직 전투가 녹색인 뒤 formation 36개와 시설·기반망·자원 유형 합계 24개를 `ACTIVE`로 연다. 공개 sandbox는 이 gate 뒤에만 연다.
3. **C4 정체성·황실 확장:** V2-6과 V2-7에서 사용하는 태평도·도적·오두미도·학교·조정·인장·역참 콘텐츠를 같은 slot/lifecycle로 승격하고 해당 phase Exit의 선행 조건으로 둔다.
4. **C5 전체 카탈로그:** V2-8 release candidate 전에 formation 120, 시설 72, 기반망 18, 자원 유형 24, 정착지 kit 24, 지형·계절 profile 32를 각각 `ACTIVE`로 만든다. 각 entry는 capability, 시기·지역 제약, claim/evidence, AI·플레이어 공통 판정 fixture를 가져야 한다.
5. 각 wave는 family별 `CatalogBudgetSlot` 소비량, lifecycle 단계별 수량, 중복·dangling·부분 생성 failure fixture를 machine-readable manifest와 테스트 보고서로 남긴다. 근거가 부족하면 이름을 만들어 목표를 채우지 않고 해당 wave를 실패시킨다.

### Exit

- C5 manifest의 `ACTIVE` count가 formation 120, 시설 72, 기반망 18, 자원 유형 24, 정착지 kit 24, 지형·계절 profile 32와 각각 정확히 일치한다. 그 이전 lifecycle과 `BUDGET_ONLY`는 완료 수량에 포함되지 않는다.
- 모든 `ACTIVE` entry의 slot/entry 양방향 참조, unique `budgetSlotId`, claim/evidence, capability와 공통 판정 fixture 누락이 0이다.
- 이중 소비·부분 생성·slot-side dangling·entry-side dangling fixture가 모두 실패하고, 동일 manifest를 두 번 build한 content hash diff가 0이다.
- v1 content table, PHP golden, backend/web gate diff 0.

## Phase V2-3 — 작전·협공 foundation

### 목표

기존 단독 출병을 작전의 한 형태로 감싸고 다중 참여를 표현한다.

### 작업

- `operations`, `operation_participants`, `operation_routes`, `operation_events`를 추가한다.
- 주공·조공·정찰·보급·예비 역할을 정의한다.
- 도착 window, 경로, 요격, 원군 지연의 deterministic rule을 만든다.
- foundation이 준비된 뒤 기존 `che_출병` adapter를 v2 sandbox/world profile에서만 활성화해 단독 `Operation`을 생성한다. 전술 세션은 아직 만들지 않고 v1 production 결과도 건드리지 않는다.

### Exit

- 단독 출병·2부대 협공·원군 지연 3개 operation event fixture가 존재한다.
- 같은 seed/입력에서 operation event diff 0.

## Phase V2-4A — BattleSession·replay spine

### 목표

준비된 `Operation` 하나를 최소 `BattleSession`과 결정적 replay body로 변환한다.

### 작업

- `battle_sessions`, `battle_replays`, `battle_replay_phases`, `battle_participants`를 추가한다.
- 첫 fixture는 `approach → field → aftermath` 세 phase만 사용한다. `scout`, `intercept`, `siege`, `urban`은 이 spine 통과 뒤 추가한다.
- wall-clock id/timestamp를 제외한 phase input/decision/rng/state diff/log sequence로 deterministic replay body와 hash를 만든다.
- read-only replay timeline을 만든다.

### Exit

- 실제 operation 1건이 replay와 정세 log를 함께 만든다.
- replay gate가 seed·phase 순서·state diff와 canonical body hash를 검증한다.
- 전쟁 결과가 기존 v1 로그와 모순되지 않는다.

## Phase V2-4B — 실시간 formation 전투 수직 슬라이스

### 목표

V2-C0의 4개 formation과 3개 정착지 항목만 사용해 보급 호송/차단 전투 하나를 끝까지 실행한다.

### 작업

- 사각형 타일 UI 대신 작은 연속 좌표 전장에 formation footprint·전면 방향·지휘 반경·보급 경로만 표시한다.
- 징발 창병·노수대·경기병대·수송호위대를 배치하고 `MOVE`, `HOLD`, `SUPPORT`, `WITHDRAW`, `commitReserve`만 연다.
- 임무는 보급 마차 호송 또는 차단 하나, 행동 규칙은 교전 금지·보급 하한·퇴각 조건만 제공한다.
- production은 짧은 명령 창·queued order·AI 위임을 사용하고 pause/slow는 sandbox·관전·replay에만 둔다.
- 이동·사격·돌격·보급·퇴각·사기 붕괴 event를 V2-4A replay body에 기록하고 campaign 정산으로 돌려보낸다.

### Exit

- 동일한 world snapshot·operation input·seed에서 부대 배치, 이동 순서, 전투 결과, replay body hash가 동일하다.
- 보급선이 끊기거나 퇴각한 결과가 작전·도시·인물 상태에 반영된다.
- 4 formation/3 settlement-entry 이외 콘텐츠를 요구하지 않는다.

모바일 조작·2D fallback·30fps 성능 예산, 추가 임무, 공성·시가전 phase는 V2-8 hardening에서 연다.

## Phase V2-5 — 가신 vertical slice

### 목표

참모 가신 1명이 근거 있는 제안을 내고 승인·거부 결과가 관계에 반영된다.

### 작업

- `general_retainers`, `retainer_proposals`, `retainer_interactions`를 추가한다.
- 참모 1명, 제안 1종(공격 또는 보급)을 구현한다.
- score, confidence, evidence, bias factor를 저장한다.
- 승인 시 operation draft/command queue로 연결한다.

### Exit

- 같은 상태·seed에서 제안과 근거가 재현된다.
- 승인/거부/만료가 모두 명시적 상태가 된다.
- 런타임 외부 API·LLM 호출 0.

## Spike V2-I0 — 국가 정체성 조합 계약

**가설:** 이름을 바꾸거나 전역 수치 보너스를 주지 않고도 `FactionIdentityProfile + NetworkPresence + ReformAdoption`이 서로 다른 플레이를 만든다.

- 유가·태평도·도적 세 프리셋과 학교·방·산채 조직망만 만든다.
- **Exit:** 동일한 도시 상태에서 세 프리셋의 가용 명령·시설 요구·외교 선택·AI 우선순위가 달라지고, 전환 후에도 이전 조직망과 반대 세력이 보존된다.

## Phase V2-6 — 국가 회의와 편견

### 목표

제안들을 공개 논쟁으로 확장하고 플레이어 결정을 작전으로 연결한다.

### 작업

- `polity_councils`, `council_opinions`, `bias_profiles`를 추가한다. 과거 계획의 `court_councils` 명칭은 황제의 `ImperialCourt`와 충돌하므로 사용하지 않는다.
- 참모·군수관·사신 의견을 deterministic rule로 생성한다.
- 찬성/반대/보류·확신·근거·편향을 화면에 표시한다.
- 플레이어의 제안 채택·보류·수정·거부를 operation draft로 만든다. 실제 침공 개설은 개인 장수가 `che_출병`으로 자신의 작전을 확정한다.

### Exit

- 같은 seed에서 의견과 결정 diff 0.
- 편향 근거가 UI에 보인다.

### 국가 계획·건설 연결

사령턴은 외교와 같은 국가 계획 계층에서 건설 예산·우선순위·도시 프로젝트를 예약한다. 개인턴은 배정된 장수가 실제 착공·감독·인력/자원 투입을 실행한다. 첫 건설 fixture는 곡창·역참·망루·성벽·병영·시장 중 2개만 선택해 경제 효과와 전술 효과가 함께 보이게 한다.

### 국가 성향·조직망 slice

- 제자백가·종교·도적 이름은 그대로 유지하고, `정통성 청중 + 통치형태 + 전통 + 지역 조직망 + 정책`을 합성한다.
- 유가 학교·추천망, 태평도 `方`, 도적 산채·산길 세 가지를 같은 `FactionIdentityProfile`로 구현한다.
- 타국 도시의 은밀 조직망, 도시 없는 도적 연맹, 조직망 탄압·수용을 지원한다.
- 개혁은 전국 연구 완료가 아니라 담당관·시범 군현·시설·예산·현지 채택을 거친다.
- 상세 계약은 `docs/superpowers/specs/2026-07-13-v2-nation-identity-rework.md`와 `docs/superpowers/specs/2026-07-13-v2-troop-building-content-catalog.md`를 따른다.

이 slice의 추가 exit는 세 성향이 수치 보너스 없이 서로 다른 명령·시설·외교·AI 우선순위를 만들고, 이전 조직망을 삭제하지 않은 채 제도 전환을 replay하는 것이다.

## Spike V2-O0 — 관직·조정 권한 계약

**가설:** 관직 claim, 수락·부임한 tenure, 실제 operational assignment, 황제와 조정의 통제 상태를 분리해야 자칭과 제수가 동시에 존재해도 권한을 일관되게 판정할 수 있다.

- `OfficeDefinition`, `OfficeNomination`, `OfficeClaim`, `OfficeTenure`, `OperationalAssignment`, `ImperialCourt`, `CourtProtectorate`, `CourtSettlement`의 최소 상태만 만든다.
- 황제 임명자와 자칭자가 같은 지방관직을 주장하는 fixture 하나, `봉대·보정·협제` 전환 fixture 하나만 둔다.
- **Exit:** 관직명만으로 행동 권한이 생기지 않고, claim origin·수락·부임·치소·속관·예산·인장·실무 위임을 바꿀 때 capability resolver 결과와 replay가 함께 바뀐다.

## Phase V2-7 — 황실·관직·도독부·봉신 계약

### 목표

한 조정의 법적 권위, 군벌의 실제 역할, 국가 내부의 중앙 권위와 지방 자율성을 함께 모델링해 관직 분쟁·원군 지연·조공·배반 위험을 만든다.

### 작업

- `subfactions`, `fiefs`, `feudal_contracts`, `subfaction_orders`를 추가한다.
- 도시 1개를 봉토로 부여한다.
- 조공, 원군 의무, 외교권, 자율성, 충성, 위반 조건을 계산한다.
- 중앙 원군 요청을 수락·지연·축소로 처리한다.
- 중앙관직·지방관직·장군 commission·작위·소속 군벌 역할을 별도 포트폴리오로 저장한다.
- 추천→심의→임명/변경/보류/기각→수락→부임과 자칭→추인의 관직 lifecycle을 구현한다.
- 황제의 신변, 조정 소재지, 상서 기구, 경비, 군량, 인장·절, 역참을 별도 상태로 만든다.
- 황제를 확보한 세력은 `봉대`, `보정`, `협제` 중 조정 정착 방침을 공표하고 실제 행동에 따라 상태가 이동한다.
- 조서의 제안·재가·기초·봉인·전달·수신·집행 workflow와 수신 세력의 거부를 구현한다.
- 상세 계약은 `docs/superpowers/specs/2026-07-13-v2-imperial-court-office-reform-equipment-design.md`를 따른다.

### Exit

- 봉토 수입·원군 응답·계약 위반이 replay/log에 남는다.
- v1 국가/외교 패러티와 분리된 sandbox fixture가 녹색이다.
- 봉신 도시의 건설권한·조공·원군 의무가 같은 `CityProject`와 `FeudalContract` 조건으로 검증된다.
- 황제 임명 관직과 자칭 관직이 동시에 존재하고, 치소·속관·세입·군대에 따라 실효 지배가 별도로 계산된다.
- 조서를 거부한 군벌이 영토를 잃지 않되 조정 불복의 정통성·외교 비용을 받는다.
- 황제 소재 도시 점령만으로 `CourtProtectorate`가 자동 이전되지 않는다.

## Phase V2-8 — 3D 지도·모바일·출시 hardening

### 목표

V2-G0에서 synthetic 2,000개로 검증한 3D 기본 표면을 실제 활성 역사 catalog와 전쟁 상태로 확장하고, 같은 read model의 정보 fallback과 모바일 조작을 hardening해 출시한다.

### 작업

- `PhysicalPlace` 2,000개와 catalog LOD Tier A/B/C 120/380/1,500, runtime render LOD `CLUSTER | SYMBOL | KIT | FULL_SCENE`의 picking·label·streaming·상태 갱신 규칙을 고정한다.
- V2-C5의 `ACTIVE` formation 120, 시설 72, 기반망 18, 자원 유형 24, 정착지 kit 24, 지형·계절 profile 32 manifest를 실제 지도와 모집·건설·보급 표면에 연결한다.
- 군현 검색·필터, 다중 선택 예외, 이상 알림, 위임 변경 이력·철회·복구를 실제 catalog에서 검증한다.
- V2-4A의 최소 phase 뒤에 `scout`, `intercept`, `siege`, `urban`과 추가 임무 preset을 하나씩 연다.
- 3D 정사영 지도에서 데스크톱·모바일 조작과 접근 가능한 selection/readout을 통과시킨다.
- SRTM 등 현대 지형은 고도·분지·경사의 물리 기반으로만 사용하고, 역사 하도·도로·정착지는 provenance가 있는 reconstruction overlay로 분리한다.
- WebGL 불가·저성능 환경은 같은 상태를 읽는 정보 fallback으로 자동 전환한다. 3D와 다른 simulation을 두지 않는다.
- WebSocket/STOMP 또는 현재 SSE 확장 중 하나를 실제 부하 측정 후 선택한다.
- 알림·replay·작전·가신·봉신의 사용자 onboarding과 도움말을 작성한다.

### Exit

- 데스크톱 60 FPS, 지원 모바일 30 FPS 목표 또는 정보 fallback 자동 전환.
- 2,000개 synthetic fixture와 2,000개 실제 source catalog 전체에서 blank canvas·label overlap·picking identity 오류 0.
- V2-C5 exact-count, no-placeholder, slot/entry 무결성 gate가 녹색이고 `BUDGET_ONLY` 또는 중간 lifecycle entry가 출시 표면에 노출되지 않는다.
- 이벤트 freshness 2초 이내.
- 두 세션에서 작전 상태·replay 완료가 동기화된다.
- production sandbox smoke와 v1 regression gate가 모두 녹색.

## 콘텐츠 출시 순서

기획 콘텐츠는 아래 순서로 수직 검증한다. 각 항목은 별도 메뉴를 만드는 목표가 아니라, 첫 작전에서 실제로 결과를 만드는 입력·판정·기록의 묶음이다.

1. `V2-0A`: production profile에서 v2 route·bean·migration·catalog loader가 0임을 먼저 증명한다.
2. `V2-G0`: 140년 행정 기준선→189년 delta, 익주 완전 군현 slice, 유주·요동·한반도·왜 경계·해상 slice 순서로 지리 계약과 3D catalog/runtime LOD를 검증한다.
3. `V2-0B`: 검증된 catalog의 `ACTIVE` 항목만 sandbox runtime에 적재한다.
4. `V2-1~V2-4B`: 주공 1명과 부장 최대 2명의 retinue를 작전군으로 묶고, 개인턴 출병에서 보급 호송/차단 임무 하나를 실시간 formation 전투·replay로 연결한다.
5. `V2-5`: 가신 제안과 장수 관계망을 공격/보급 제안 하나씩만 제공해 승인·거부·만료의 차이를 만든다.
6. `V2-6`: 도시 사건 4종과 유가·태평도·도적 조직망을 붙이고, 시설·개혁·AI 우선순위가 달라지는지 검증한다.
7. `V2-7`: 관직 추천·자칭·추인, 봉대·보정·협제 황실 운영, 봉신 협상 3종(수락·지연·축소)을 연결한다.
8. `V2-8`: 첩보·계절 사건·연대기를 추가하되, 기존 replay와 정세 로그가 먼저 재현되는지 확인한다.
9. 출시 후: 시나리오 변형·시즌 목표·관전/공유·유저 합의 사건을 하나씩 공개한다.

콘텐츠 추가 기준은 “새 화면이 있는가”가 아니라 “플레이어의 결정이 다음 작전·관계·도시 상태 중 두 곳 이상에 재현 가능한 변화를 남기는가”다.

## 장기 규칙셋 확장선

- v2는 `FormationRealtimeBattle`을 구현하고, `CampaignWorld → Operation → BattleInstance` 경계를 실제 데이터 계약으로 고정한다.
- 첫 4/3 sandbox는 창병·노수·경기병·수송호위와 보급만 검증한다. 포병과 타 시대 formation은 이 gate를 통과한 뒤 conformance fixture로 추가한다. 타일은 내부 공간 분할·미니맵·경로 탐색에만 선택적으로 사용한다.
- 나폴레오닉은 선열·종대·방진·포병·기병·장교 명령 지연을 `EraPack`과 `BattleRuleset`으로 추가한다.
- 엠파이어는 산업·무역·해군·식민지·국제 외교를 campaign/operation 계층에 추가하고, 전투 규칙을 무리하게 확장하지 않는다.
- 어느 확장도 v1 production world나 v2 오픈삼국 sandbox에 새 시대 데이터를 직접 주입하지 않는다.

## 매 phase 공통 게이트

- PHP 경계가 있는 v1 변경은 `opensamguk-php-oracle` source/line과 golden evidence를 기록한다.
- UI 변경은 `webapp-testing`으로 실제 브라우저 또는 공개 API surface를 확인한다.
- 가설 1개, baseline, 결정적 grader, 채택/원복 기준을 `docs/loops/v2-*`에 기록한다.
- implementation 외부의 fresh reviewer가 `cleared`, `fix-required`, `quarantined-with-proof` 중 하나를 낸다.
- `tools/agent-system/check.py --strict --base origin/main --format json`을 통과한다.
- v1 backend/web gate가 통과한다.

## 승인·보류 결정

다음은 이 문서만으로 자동 구현하지 않는다.

- 기본 production cadence를 60분 외 값으로 변경.
- v2 world를 s1에 직접 생성하거나 기존 s1 데이터를 변환.
- 3D 지도 asset/license/인프라 비용 확정.
- v1 gate 또는 golden 기대값 완화.
