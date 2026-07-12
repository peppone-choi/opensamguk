# 오픈삼국 v2 실행 계획

> 작성일: 2026-07-12
> 상태: proposed-plan
> 정본 제품 문서: `docs/superpowers/specs/2026-07-12-opensamguk-v2-product-spec.md`
> 근거 대조: `docs/superpowers/research/2026-07-12-v2-source-reconciliation.md`

## 운영 원칙

1. v1과 v2는 같은 저장소에서 관리하되 world/profile/schema 경계를 둔다.
2. 기존 v1 패러티 gate가 녹색인 상태에서만 v2 slice를 시작한다.
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
| 지도 | 2D map preview/MapViewer, level별 detail/basic 규칙 | 2D marker contract를 먼저 잠금 |
| 패러티 | backend gate, PHP golden, v1 기준서 존재 | v2 신규 replay는 별도 golden/replay gate |
| 문서 | PRD/ROADMAP Rev 2와 기존 v2 계획이 서로 다른 cadence | 이번 제품 spec이 충돌을 해소 |

## Phase V2-0 — 기준선·경계 고정

### 목표

v1의 운영 가능 상태와 v2 sandbox의 경계를 실제로 분리한다.

### 작업

- v1 완료 기준의 남은 차단 항목을 `v1-completion` ledger에 갱신한다.
- v2 전용 profile/world 식별자와 feature flag 설계를 확정한다.
- `world_id`를 전제로 한 v2 migration naming과 rollback 규칙을 작성한다.
- command result/turn event payload의 version과 영향 query 목록을 정의한다.
- s1에는 v2 schema/seed를 적용하지 않고, local/test sandbox에서만 실행한다.

### Exit

- v1 gate와 web gate가 녹색.
- v2 sandbox boot가 production s1과 독립.
- event contract와 schema spike 리뷰가 `cleared`.

### 전술 엔진 기반을 V2-0에서 고정

v2의 전투 구현은 tile UI부터 시작하지 않는다. 먼저 `BattleState`, `BattleTopology`, `BattleClock`, `OrderIntent`, `FormationModel`, `BattleEvent/BattleReplay`, `BattleServerAuthority`의 공통 계약과 직렬화 형태를 만든다.

- `GridTopology + COMMAND_STEP`와 `ContinuousTopology + REALTIME_FIXED_TICK`를 같은 상태 계약으로 생성할 수 있어야 한다.
- 이동·충돌·피해·사기·보급·승패 계산은 서버 fixed tick에서 실행하고, 프론트는 명령 제출·상태 구독·렌더링만 담당한다.
- 첫 fixture는 작은 grid 전투와 연속 좌표 formation sandbox 두 개를 함께 만든다. 둘 다 동일한 명령·seed·replay 검증기를 사용한다.
- tile renderer나 React 상태가 전술 규칙의 정본이 되지 않도록 backend simulation과 replay JSON을 먼저 확정한다.

V2-0 exit에 `FormationBattle`의 완성 콘텐츠를 요구하지는 않지만, V2 이후에 별도 엔진을 다시 쓰지 않고 구현할 수 있는 상태 계약과 최소 연속 좌표 smoke를 요구한다.

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
- `che_출병`은 개인턴의 침공 의미를 보존하고, sandbox adapter가 Operation/BattleSession을 만든다. 사령턴은 열린 전선 정책·보급·원군만 조정한다.
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

## Phase V2-3 — 작전·협공 foundation

### 목표

기존 단독 출병을 작전의 한 형태로 감싸고 다중 참여를 표현한다.

### 작업

- `operations`, `operation_participants`, `operation_routes`, `operation_events`를 추가한다.
- 주공·조공·정찰·보급·예비 역할을 정의한다.
- 도착 window, 경로, 요격, 원군 지연의 deterministic rule을 만든다.
- 기존 `che_출병` adapter는 단독 operation을 생성한다.

### Exit

- 단독 출병·2부대 협공·원군 지연 3개 replay fixture가 존재한다.
- 같은 seed/입력에서 operation event diff 0.

## Phase V2-4 — 전쟁 replay spine

### 목표

전쟁을 결과 한 줄이 아니라 재생 가능한 phase timeline으로 만든다.

### 작업

- `battle_replays`, `battle_replay_phases`, `battle_participants`를 추가한다.
- `approach`, `scout`, `intercept`, `field`, `siege`, `urban`, `aftermath` phase를 고정한다.
- phase input/decision/rng/state diff/log reference를 저장한다.
- read-only replay timeline을 만든다.

### Exit

- 실제 operation 1건이 replay와 정세 log를 함께 만든다.
- replay gate가 seed·phase 순서·state diff를 검증한다.
- 전쟁 결과가 기존 v1 로그와 모순되지 않는다.

### 실시간 대형 부대·테이블탑 slice

V2-4의 첫 replay 화면은 사각형 타일 전투가 아니라 연속 좌표의 실시간 대형 부대 전투로 구현한다. 평지·숲·강·도로·성벽·보급 거점을 가진 작은 전장을 만들고, 부대 footprint·전면 방향·대형 질서·지휘 반경·보급선을 표시한다. 유저가 주공·추종 부대의 목표와 명령을 정하면 부대 AI가 세부 이동·교전·퇴각을 실행한다.

부대는 미니어처 워게임처럼 하나의 전술 말로 읽혀야 한다. 고지 점유·관문 방어·보급 마차 호송·퇴로 확보를 목표로 두고, 일시정지·저속·재지휘를 지원한다. 이동·정찰·사격·돌격·지원·보급·퇴각·사기 붕괴 이벤트는 타임라인과 함께 재생한다. 병사 단위 직접 조작과 3D 전장은 이 slice의 exit 이후로 미룬다.

첫 명령 세트는 `임무·군령·행동 규칙·보고/재지휘` 네 층으로 만든다. `전군 진격`, `선봉 정찰`, `보급선 차단`, `관문 점령`, `퇴로 확보`를 프리셋으로 제공하되, 서버에는 목표·위험도·금지 조건·퇴각 조건·재지휘 시각을 별도 필드로 저장한다. 이것이 단순 자동전투와 임무형 지휘를 구분하는 최소 계약이다.

실시간 대형 부대 전투의 별도 exit는 다음과 같다.

- 동일한 world snapshot·operation input·seed에서 부대 배치, 이동 순서, 시야, 전투 결과, 로그가 동일하다.
- 보급선이 끊기거나 퇴각한 결과가 작전·도시·인물 상태에 반영된다.
- 모바일에서 부대 선택, 명령선, 부대 정보, 다음 명령 입력이 가능하고 2D fallback이 항상 동작한다.
- 부대 수와 fixed tick이 정해진 성능 예산 안에서 replay와 함께 30fps 이상으로 표시된다.

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

## Phase V2-6 — 어전회의와 편견

### 목표

제안들을 공개 논쟁으로 확장하고 플레이어 결정을 작전으로 연결한다.

### 작업

- `court_councils`, `council_opinions`, `bias_profiles`를 추가한다.
- 참모·군수관·사신 의견을 deterministic rule로 생성한다.
- 찬성/반대/보류·확신·근거·편향을 화면에 표시한다.
- 플레이어 승인·보류·수정안·거부를 operation draft로 만든다.

### Exit

- 같은 seed에서 의견과 결정 diff 0.
- 편향 근거가 UI에 보인다.

### 국가 계획·건설 연결

사령턴은 외교와 같은 국가 계획 계층에서 건설 예산·우선순위·도시 프로젝트를 예약한다. 개인턴은 배정된 장수가 실제 착공·감독·인력/자원 투입을 실행한다. 첫 건설 fixture는 곡창·역참·망루·성벽·병영·시장 중 2개만 선택해 경제 효과와 전술 효과가 함께 보이게 한다.

## Phase V2-7 — 도독부·봉토·봉신 계약

### 목표

국가 내부의 중앙 권위와 지방 자율성으로 원군 지연·조공·배반 위험을 만든다.

### 작업

- `subfactions`, `fiefs`, `feudal_contracts`, `subfaction_orders`를 추가한다.
- 도시 1개를 봉토로 부여한다.
- 조공, 원군 의무, 외교권, 자율성, 충성, 위반 조건을 계산한다.
- 중앙 원군 요청을 수락·지연·축소로 처리한다.

### Exit

- 봉토 수입·원군 응답·계약 위반이 replay/log에 남는다.
- v1 국가/외교 패러티와 분리된 sandbox fixture가 녹색이다.
- 봉신 도시의 건설권한·조공·원군 의무가 같은 `CityProject`와 `FeudalContract` 조건으로 검증된다.

## Phase V2-8 — 지도·모바일·출시 hardening

### 목표

검증된 2D 전쟁 상태를 모바일과 3D 선택 모드로 확장하고 출시한다.

### 작업

- 지도 데이터 계약과 detail/basic marker 규칙을 고정한다.
- 2D 지도 성능·접근성·모바일 조작을 먼저 통과시킨다.
- 3D Blue Marble/SRTM은 성능 예산을 만족할 때만 활성화한다.
- WebSocket/STOMP 또는 현재 SSE 확장 중 하나를 실제 부하 측정 후 선택한다.
- 알림·replay·작전·가신·봉신의 사용자 onboarding과 도움말을 작성한다.

### Exit

- 모바일 30fps 이상 또는 2D fallback 자동 전환.
- 이벤트 freshness 2초 이내.
- 두 세션에서 작전 상태·replay 완료가 동기화된다.
- production sandbox smoke와 v1 regression gate가 모두 녹색.

## 콘텐츠 출시 순서

기획 콘텐츠는 아래 순서로 수직 검증한다. 각 항목은 별도 메뉴를 만드는 목표가 아니라, 첫 작전에서 실제로 결과를 만드는 입력·판정·기록의 묶음이다.

1. `V2-1~V2-4`: 주공 1명과 부장 최대 2명의 retinue를 작전군으로 묶고, 개인턴 출병에서 구출·호송·보급 차단을 실시간 대형 부대 전투·replay로 연결한다.
2. `V2-5`: 가신 제안과 장수 관계망을 공격/보급 제안 하나씩만 제공해 승인·거부·만료의 차이를 만든다.
3. `V2-6~V2-7`: 도시 사건 4종(구휼·치안·이주·시장 회복)과 봉신 협상 3종(수락·지연·축소)을 작전 결과의 후속 선택으로 붙인다.
4. `V2-8`: 첩보·계절 사건·연대기를 추가하되, 기존 replay와 정세 로그가 먼저 재현되는지 확인한다.
5. 출시 후: 시나리오 변형·시즌 목표·관전/공유·유저 합의 사건을 하나씩 공개한다.

콘텐츠 추가 기준은 “새 화면이 있는가”가 아니라 “플레이어의 결정이 다음 작전·관계·도시 상태 중 두 곳 이상에 재현 가능한 변화를 남기는가”다.

## 장기 규칙셋 확장선

- v2는 `FormationRealtimeBattle`을 구현하고, `CampaignWorld → Operation → BattleInstance` 경계를 실제 데이터 계약으로 고정한다.
- 첫 sandbox부터 대형 부대·지휘 반경·사기·피로·보급·포병·기병을 작은 연속 좌표 전장에서 검증한다. 타일은 내부 공간 분할·미니맵·경로 탐색에만 선택적으로 사용한다.
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
