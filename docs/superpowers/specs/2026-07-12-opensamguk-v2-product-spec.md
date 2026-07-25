# 오픈삼국 v2 제품·시스템 정본

> 작성일: 2026-07-12
> 상태: reviewed-source-of-truth, current-round-review-cleared-2026-07-15
> 선행 자료: `docs/superpowers/research/2026-07-12-v2-source-reconciliation.md`, `docs/wiki/pages/game/opensamguk-v2-direction.md`, `docs/wiki/raw/opensamguk-v2-downloads/PRD.md`

## 1. 제품 정의

오픈삼국 v2는 삼모의 장수·세력·도시·예약턴 문법 위에, 플레이어의 명령이 부하·작전·보급·외교·전장을 통과하며 결과로 굴절되는 영구 멀티플레이어 전략 게임이다.

v2의 첫 제품 약속은 “기능이 많은 삼국지”가 아니라 다음 한 장면이다.

> 플레이어 장수가 출병을 결심해 자신의 작전을 열고, 참모와 군수관의 의견을 비교하고, 봉신 원군의 지연을 감수하며, 단계별 전쟁 replay와 관계 변화를 확인한다.

## 2. v1과 v2의 경계

### v1에서 보존

- PHP grand truth 기반 로직·RNG·반올림·로그 패러티.
- 현재 로그인/서버 선택/로비/인게임 경로.
- `InMemoryTurnWorld` source of truth와 `ChangeRecorder → JdbcFlushExecutor` 단일 write 경로.
- PostgreSQL read 모델, Redis Streams intake, SSE `turnCompleted`.
- 상순/중순/하순 표시와 예약턴 사용자 경험.

### v2에서 새로 정의

- 조작 대상: 본인 장수, 부곡, 가신, 도독부.
- 작전: 단독 출병을 포함하는 다중 참여 전쟁 단위.
- 전쟁 replay: 접근·정찰·요격·야전·공성·시가전·전후 처리 phase.
- 가신 제안과 편견: deterministic score, 근거, confidence, 관계 변화.
- 도독부·봉토·봉신 계약: 중앙 권위, 자율성, 조공, 원군 의무.
- 도시별 재정·곡물·주둔군·수송과 실시간 formation 전투.
- 이름을 유지한 국가 성향 15종의 정통성·통치제도·조직망·정책 조합.
- 황제·조정·상서·인장·조서와 중앙/지방 관직의 추천·자칭·임명·실권 분리.
- 전국 tech boolean이 아닌 관직·시범 군현·시설·예산을 통한 개혁 확산.

세부 정본은 다음 문서다.

- `docs/superpowers/specs/2026-07-13-v2-historical-city-army-terrain-design.md`
- `docs/superpowers/specs/2026-07-13-v2-troop-building-content-catalog.md`
- `docs/superpowers/specs/2026-07-13-v2-nation-identity-rework.md`
- `docs/superpowers/specs/2026-07-13-v2-imperial-court-office-reform-equipment-design.md`

v1의 `nation.level`, `officer_level`, 도시 수 기반 작위 상승은 `LEGACY` 패리티와 호환 adapter로 보존한다. v2에서 공·왕·황제·중앙관직·지방관직은 도시 수 경험치 단계가 아니다. 발령 주체, 조서·인장, 추천·수락, 실제 관할, 타 세력의 인정으로 성립한다.

v2 기능은 v1 명령의 결과를 바꾸는 방식으로 먼저 구현하지 않는다. 동일한 기능이 v1과 v2에 모두 존재해야 하면 world/profile/feature flag 경계로 분리한다.

## 3. 대상 사용자와 핵심 루프

### 사용자

- 복귀 삼모전 유저: 익숙한 예약턴과 로그를 빠르게 이해한다.
- 전략 게이머: 전쟁의 원인·과정·결과를 replay로 분석한다.
- 모바일 유저: 짧은 접속에서 제안 확인, 명령 승인, 큐 수정만 수행한다.

### 루프

| 주기 | 행동 | 화면 결과 |
|---|---|---|
| 즉시 | 명령/제안 승인·거부 | command result와 대상 query만 갱신 |
| 장수 턴 | 이동·내정·전투 명령 실행 | 예약표, 장수 상태, 로그 diff |
| 하루 | 전선·보급·가신·봉신 점검 | 작전 dashboard, 경고, 회의 |
| 시즌 | 봉토·외교·정통성·통일 목표 | 정세 timeline, 랭킹, replay archive |

## 4. 시간·갱신 계약

### Cadence

```text
simulation_tick: 200ms 목표, 메모리 평가 전용
general_command_cadence: world 설정, production 3600s / QA 60s
game_date: 상순·중순·하순 표시
monthly_effect: month boundary에서 한 번
```

내부 tick은 사용자에게 “새로고침”을 의미하지 않는다. 프론트는 다음 이벤트만 소비한다.

- `commandResolved`: 요청한 명령의 결과와 영향을 받은 query.
- `turnCompleted`: 현재 장수·예약표·정세 요약 query.
- `battlePhaseChanged`: 해당 작전/replay query.
- `notificationCreated`: 알림 inbox query.

전체 `window.location.reload()`는 사용하지 않는다. route navigation이 필요한 경우에만 Next router를 사용한다.

## 5. v2 MVP 수직 슬라이스

### 월드

- v2 sandbox world 1개.
- 도시 20개, 세력 3개, 장수 50명.
- 플레이어 장수 1명, 가신 2명(`origin=EXISTING` 1명, `origin=RECRUITED` 1명), 도독부 1개, 봉토 1개.
- production s1과 데이터·태그·마이그레이션을 분리한다.

### 장면

1. 플레이어가 참모 제안을 받는다.
2. 군수관이 보급 부족을 이유로 반대한다.
3. 플레이어가 출병을 결심하고 자신의 작전안을 확정한다. 이는 사령턴의 전쟁 승인 절차가 아니다.
4. 도독부 원군이 수락·지연·축소 중 하나로 응답한다.
5. 작전이 접근·정찰·요격·야전 또는 공성·시가전·전후 처리로 진행된다.
6. replay와 정세 로그가 생성된다.
7. 가신 신뢰, 봉신 충성, 도시 민심이 변한다.

## 6. 핵심 도메인 계약

### 개인턴·사령턴·전술 명령의 경계

기존 개인턴·사령턴을 보존하고, 실시간 전투에 세 번째 명령 계층을 추가한다. `che_` 같은 코드 접두어만으로 계층을 판단하지 않고, 명령 정의와 제출 경로가 개인턴인지 사령부인지 결정한다.

| 구분 | 개인턴 | 사령턴 | 전술 명령 |
|---|---|---|
| 대상 | 한 장수와 자신의 retinue | 국가·도시·외교·이미 열린 전선 | 열린 `BattleSession` 안의 부대·대형·지점 |
| 정본 | `general_turn` 링 | `nation_turn` 링의 국가·직책별 슬롯 | tactical order stream와 `BattleState` |
| 예시 | 출병, 이동, 징병, 훈련, 개인 참전·지원 | 보급 우선순위, 원군·예비대 배정 정책, 방어 정책, 전쟁 목표, 퇴각 정책 | 이동, 방향 전환, 선형/종대/방진, 사격, 돌격, 추격, 배정된 예비대의 전장 투입 |
| 시간 | production 3600초, QA/s1 60초 프로파일의 장수 예약턴 | 국가 수뇌부 직책별 예약턴 | 서버 fixed tick의 실시간 전투. 명령은 즉시 접수하고 짧은 만료 시각을 가진다 |
| 처리 엔진 | campaign/turn engine의 general-turn drain | campaign/turn engine의 nation-turn drain | tactical battle engine |
| 저장 | 장수·retinue·개인 상태를 기존 flush로 확정 | 국가·도시·외교·전선 정책을 기존 flush로 확정 | `BattleState`와 `BattleEvent`를 기록하고 종료 시 campaign 결과로 정산 |
| 실패 | 사유와 함께 거절 또는 다음 개인턴으로 재예약 | 권한·국가 조건·직책 슬롯 사유로 거절 또는 다음 사령턴으로 재예약 | 지연·무시·충돌·사기 붕괴·대형 이탈 등 전장 결과로 표현 |

개인턴의 출병 명령은 현재 v1 의미 그대로 침공을 시작한다. 사령턴은 출병을 열지 않고, 이미 열린 전선에 국가 차원의 정책과 지원을 붙인다.

```text
개인턴: `che_출병` 실행
  → 침공 의도·목표 도시·참가 부대·도착 시각 생성
  → Operation 생성·참가 부대 예약
사령턴: 열린 Operation에 보급·원군·방어·퇴각 정책 적용
개인턴: 다른 장수의 참전·정찰·보급 지원 합류
  → 교전 조건 충족 시 BattleSession 생성·참가 부대 잠금
  → 전술 초기 배치
  → tactical order stream에서 실시간 대형 지휘
  → BattleEvent/replay 생성
  → 승패·손실·포로·보급·도시/인물 변화로 Operation 정산
```

사령턴은 전쟁 개시 권한이 아니라 `전선 보급`, `원군 소집`, `예비대 배정`, `방어 태세`, `퇴각선`, `전쟁 목표`를 조정하는 국가 정책이다. 개인턴의 출병이 없으면 사령턴은 정책만 저장하고 전투를 만들지 않는다. 배정된 부대가 작전에 실제 합류하는 것은 `operation.reinforce`, 전장에 들어가는 시점과 위치를 정하는 것은 `battle.formation.commitReserve`가 담당한다. 전술 명령은 국가 전체의 예약턴을 소비하지 않고, `battleId + formationId + sequence + issuedAtTick + expiresAtTick`를 갖는 전용 명령으로 만든다. 전투가 끝날 때만 tactical result adapter가 전략 상태 변경안을 만들고, campaign engine이 기존 단일 flush 경로로 확정한다.

플레이어가 전투에서 이탈하면 마지막 유효 명령과 장수별 doctrine/retainer AI가 계속 실행한다. 전체 월드의 턴을 멈추는 전역 일시정지는 production에 두지 않고, sandbox·관전·replay에서만 허용한다.

### CommandSubject

```text
subjectType: GENERAL | RETAINER | BUGOK | SUBFACTION
subjectId: 대상 식별자
orderedByGeneralId: 명령을 승인한 장수
executionOwnerGeneralId: 실행 책임 장수
queueScope: PERSONAL | OPERATION | NATION
idempotencyKey: 클라이언트 UUID
```

### Retainer

부하는 두 트랙이 아니라 **가신 1트랙**이다. 기존 추종(Follower)은 가신의 `origin=EXISTING` 속성으로 흡수한다. 출신과 독립 병력 보유 여부는 별도 주체 타입이 아니라 가신의 속성이다.

```text
origin: EXISTING | RECRUITED
  EXISTING  = 기존 장수 풀(NPC/재야/유저)이 주장에게 서약
  RECRUITED = 자원·턴 소모로 신규 NPC 채용
hasOwnBugok: 독립 병력(부곡) 보유 여부. EXISTING 기본 true, RECRUITED 기본 false
role: 참모 | 호위 | 군수관 | 정찰 | 사신 | NONE
releasePolicy: MUTUAL | MASTER_ONLY
upkeep: RECRUITED만 월별 금·쌀 소모
```

- 커맨드는 `가신서약`(구 `추종서약`+`가신채용`), `가신해제`(구 `추종해제`+`가신해고`), `가신임무`(구 `가신임무부여`) 3종으로 통합한다. `releasePolicy=MUTUAL`은 양방향 해제, `MASTER_ONLY`는 주장 일방 해제다.
- 광역 명령 `동시침공`·`집결명령`·`광역이동`은 그대로 유지하고, 대상만 `hasOwnBugok=true`인 가신으로 정의한다.
- 부곡은 사람이 아니라 병력 집단이므로 이 통합의 대상이 아니다. `BUGOK` subjectType은 그대로 둔다.

### Operation

```text
targetCityId
arrivalWindow
participants: generals, retainers, bugok, subfaction forces
roles: MAIN | SUPPORT | SCOUT | SUPPLY | RESERVE
route
rules: intercept, retreat, siege, supply
```

기존 `che_출병`은 **v2 sandbox/world profile에서만** 단독 `Operation`으로 감싼다. v1 production의 예약 queue·판정·로그·result는 변경하지 않고 adapter 밖에서 끝난다.

### BattleReplay

```text
ReplayEnvelope
  replayId, worldId, operationId, createdAt, persistedLogEntryIds[]

DeterministicReplayBody
  worldSnapshotHash, operationInputHash, seed
  contentVersion, balanceVersion, geographyVersion
  phases[]: APPROACH, SCOUT, INTERCEPT, FIELD, SIEGE, URBAN, AFTERMATH
  phaseInput, phaseDecision, rngDraws, orderedStateDiff, normalizedLogEntries

deterministicReplayHash = hash(canonicalSerialize(DeterministicReplayBody))
```

같은 `world snapshot + operation input + seed + content/balance/geography version`은 같은 `DeterministicReplayBody`와 hash, 같은 결과를 만들어야 한다. `replayId`, `createdAt`, DB log id 같은 persistence metadata는 동등성 비교에서 제외하고 필요하면 normalized sequence key로 대응한다. replay는 UI 장식이 아니라 검증 가능한 결과 계약이다.

### RetainerProposal

```text
retainerId, subjectId, proposalType, targetId
score, confidence, evidence[], biasFactors[], expiresAt, status
```

런타임 LLM은 사용하지 않는다. 제안은 규칙 점수와 템플릿으로 만들고, 입력 feature와 score를 저장한다.

### FeudalContract

```text
lordSubfactionId, vassalSubfactionId, fiefIds
tributeRate, reinforcementObligation, diplomacyRight, autonomy
loyalty, breachConditions, expiresAt
```

## 7. 화면 구조

- 메인: 현재 조작 대상, 다음 명령, 작전 경보, 가신 제안, 최근 정세.
- 지도: 3D 기본. 도시·관문·나루·route·formation을 같은 scene/selection 계약으로 표시한다. 정사영 지휘 카메라와 WebGL 불가 환경의 정보 fallback을 제공한다.
- 명령 작업대: 장수별 예약 큐, 슬롯 선택, drag reorder, 일괄등록, 프리셋을 제공한다. 소속·관직·부대 역할·현재 위치로 계산한 서버 capability가 명령과 목적지 후보를 결정한다.
- 국정: 조직도, 관직 권한, 국가 회의, 등용 후보, 부대 편제를 읽기 모델로 연결한다. 권한이 없는 장수도 구조와 비활성 사유를 볼 수 있어야 한다.
- 작전: 참여 대상·경로·도착 window·보급·원군 상태.
- replay: phase timeline, 전투 로그, 상태 diff, RNG/근거는 관리자·디버그 권한에서만 노출.
- 가신: 카드, 관계, 현재 임무, 제안함, 상호작용 기록.
- 회의: 인물별 입장·확신·근거·편향을 표로 비교.
- 모바일: 바텀시트와 큐 timeline. 데스크톱 전용 정보 밀도를 모바일에 그대로 축소하지 않는다.

### 전체 역사 지리 표면

지도는 수십 개 대표 도시만 보여주는 축약판이 아니다. 『후한서』 군국지의 순제기 행정 기준선을 전사하고 189년까지의 변경을 적용해 모든 현·읍·도·후국 치소를 조회·점령·주둔·징병·세입·보급 상태에 참여시킨다. 군치·국치가 현치와 같은 도시에 있으면 물리 장소는 하나만 만들고 행정 역할을 겹쳐 연결한다.

- 정식 `PhysicalPlace` 목표는 2,000개다: 한 행정 정착지 1,200, 전략 비행정 거점 200, 주변 정착지·시기별 camp·항구·오아시스 500, 해상·원거리 교역 관문 100. 각 장소는 이 네 `PlaceBudgetClass` 중 정확히 하나에 속하고 합계와 클래스별 수량을 별도로 검증한다. 계절 이동권과 영역 geometry는 장소 수에 포함하지 않는다.
- 주변 `PolityNetwork` 240개는 역사 실수량이 아니라 별도 `CatalogBudget`의 제품 수용 예산이다. 한반도·왜, 북방 초원, 강·저·서역, 산월·형남·남중·교주 주변을 포함하되 claim 없는 slot이나 동시 활성 독립국 수로 해석하지 않는다. 장소·정치 node·영역 presence·계절 range는 각자 별도 집계한다.
- catalog LOD는 제작 상세 예산 Tier A 120, Tier B 380, Tier C 1,500이다. runtime render LOD `CLUSTER | SYMBOL | KIT | FULL_SCENE`은 카메라 거리·밀도·기기 성능에 따라 독립적으로 변한다. 모든 조합이 같은 server read model과 simulation 상태를 사용한다.
- 기본 정책과 반복 명령은 군·국 단위로 내리고, 현 단위는 상세 보기·예외 명령·태수 위임으로 제공한다. 군현 검색·필터, 다중 선택 예외, 이상 알림, 위임 이력·철회·복구를 같은 관리 표면에 둔다.
- 비정이 논쟁적인 치소·삼한 국읍·왜 국읍·유목 이동권은 오차 반경과 복수 reconstruction을 유지하며 정밀한 단일 좌표·경계를 역사 사실처럼 표시하지 않는다.

행정단위·물리 장소·치소·주변 네트워크·LOD의 상세 계약과 출처는 `docs/superpowers/specs/2026-07-13-v2-historical-city-army-terrain-design.md`를 따른다.

## 8. 비범위

- v2 MVP에서 112개 커맨드 전체 구현.
- 실시간 병사 단위 조작, 병사 시점 자유 카메라, cinematic, 런타임 LLM, 결제·인앱, 네이티브 앱, 다국어.
- 1,180개 현급 거점을 플레이어가 같은 깊이로 반복 관리하는 UI. 전수 simulation과 전수 수동 관리는 같은 요구가 아니다.
- 기존 v1 production s1에 실험용 v2 schema/seed를 직접 주입.
- 패러티 골든·게이트를 약화하거나 v2 편의를 위해 PHP 동작을 변경.

## 9. 콘텐츠 전략

v2 콘텐츠는 도움말에 이미 등장하는 `턴 입력 → 국가 활동 → 지도 → 외교 → 전쟁 → 유저 교류`를 확장한다. 별도 미니게임을 늘리는 대신, 같은 명령이 인물 관계·도시 상태·작전 replay·정세 기록으로 이어지게 한다.

### 우선순위 A: 첫 출시 수직 콘텐츠

| 콘텐츠 | 플레이어가 하는 일 | 결과가 남는 곳 |
|---|---|---|
| 작전 목표 | 점령 외에 구출·호송·보급선 차단·요격·퇴각을 선택한다 | operation, replay, 전후 처리 |
| 전쟁 전야 회의 | 참모·군수관·사신의 찬성/반대와 근거를 비교하고 승인·수정·보류한다 | council, proposal, 관계 변화 |
| 원군·봉신 협상 | 원군의 수락·지연·축소를 감수하고 계약을 지키거나 위반한다 | contract, 충성, 외교 log |
| 도시 사건 | 세금·구휼·치안·이주·시장 회복 중 하나를 선택한다 | 도시 민심, 생산, 다음 작전 |
| 전쟁 기록관 | 종료된 작전에서 분기·실수·공훈을 되짚고 다음 결정을 세운다 | replay archive, 연대기 |

이 다섯 가지는 하나의 장면으로 연결된다. `명령 입력 → 회의 → 작전 목표 → 원군 응답 → 전투 phase → 도시/인물 변화 → replay`가 v2의 첫 완결 콘텐츠다.

### 전투 표현: 실시간 대형 부대 전술 전장

v2의 첫 전투는 3D 실시간 전장으로 만든다. 전략 지도와 전투 현장은 화면·LOD는 분리하지만 동일한 world coordinate, route, terrain version, selection 계약을 공유한다. 플레이어는 병사 하나가 아니라 장수·부곡·원군으로 구성된 대형 부대를 정사영 지휘 카메라에서 직접 지휘한다.

- 부대는 선형·종대·방진과 같은 대형을 전환한다. 선형은 화력과 전면 교전에, 종대는 행군과 도로 이동에, 방진은 기병 대응과 방어에 강하지만 기동성이 떨어진다.
- 부대 상태는 병력·사기·피로·경험·탄약/군량·지휘 연결·대형 질서를 가진다. 병력 수가 많아도 사기가 무너지면 대형이 붕괴하고 전투에서 이탈한다.
- 장수·기수·군악/신호 담당은 단순 장식이 아니라 명령 전달·사기 회복·대형 유지의 핵심 부속으로 둔다. 삼국지에서는 북·기·전령·부장 체계로 표현한다.
- 플레이어는 부대 선택, 이동 지점, 공격축, 사격·돌격·추격·퇴각, 대형 전환, 예비대 투입을 실시간으로 결정한다. 세부 병사 이동과 충돌 정렬은 전술 AI가 처리한다.
- 평지·숲·산·강·도로·성벽·관문·보급 거점은 이동·시야·사격·방어·피로·보급에 영향을 준다. 전략 지도는 출병과 보급선을, 전술 지도는 실제 지형과 대형을 담당한다.
- 전투는 서버 fixed tick으로 계산하고, 클라이언트는 명령·상태 구독·카메라·애니메이션을 담당한다. 모든 명령·tick·seed·state diff는 replay로 남긴다.

v2의 첫 수직 루프는 `개인턴 출병 → 실시간 대형 부대 전투 → 사기 붕괴/퇴각 또는 돌파 → replay → 도시·인물 변화`다. 사각형 타일은 플레이어 전장의 기본 표현으로 채택하지 않으며, 필요할 때 내부 공간 분할·미니맵·경로 탐색 자료구조로만 사용한다.

### 전장 감각: 실시간 테이블탑 미니어처 워게임

전술 화면은 작은 병사 아이콘을 흩뿌리는 RTS가 아니라, 지휘관이 미니어처 부대를 배치하고 전장을 읽는 느낌을 목표로 한다.

- 부대마다 명확한 base/footprint, 전면 방향, 대형 폭·깊이, 색상·깃발·장수 표식을 보여준다.
- 이동은 자유로운 연속 좌표지만 부대 간격과 대형 질서를 유지한다. 겹침·측면·후방·지휘거리·보급선이 결과에 영향을 준다.
- 전투 목표는 적 섬멸 하나가 아니라 고지 점유, 관문 방어, 보급 마차 호송, 퇴로 확보, 적 지휘부 압박처럼 테이블탑 시나리오 목표로 정의한다.
- production 실시간 시뮬레이션에는 짧은 명령 창·예약 명령·전황 보고·AI 위임을 제공한다. 일시정지·저속은 sandbox·관전·replay 전용이며, 플레이어는 클릭 속도가 아니라 명령의 우선순위와 부대 배치로 승부한다.
- 피해는 병사 한 명을 직접 조작하는 대신 부대 strength, cohesion, morale, fatigue, ammunition/supply의 변화로 읽는다. 중요한 붕괴·돌격·지휘관 손실은 replay event로 강조한다.
- UI는 부대 카드, 명령선, 지휘 반경, 사격 범위, 보급 경로, 목표 지점을 명확히 표시하고, 화면을 가리는 화려한 이펙트는 줄인다.

이 감각은 코삭 2의 대형·사기·피로·지형 중심 전투와 테이블탑 워게임의 배치·목표·지휘 명료성을 결합한다. 삼국지에서는 부대 깃발·북·전령·군사·장수 관계를 이 표현에 연결한다.

### 토탈워: 삼국식 캠페인 연결

토탈워: 삼국에서 참고할 핵심은 영웅 연출보다 장수와 부대의 결합 방식이다. 군대는 한 명의 장수에 매달린 병력 묶음이 아니라, 여러 장수의 retinue가 모인 지휘 집단으로 모델링한다.

- 하나의 작전군은 주공 장수 1명과 부장 장수 최대 2명의 지휘 집단으로 구성한다. 각 장수는 자신의 부곡·가신·특수병을 가진 retinue를 지휘한다.
- 장수 역할은 전투 능력만이 아니라 모집 가능한 병종, 대형 유지, 보급, 정찰, 공성, 사기 회복에 영향을 준다.
- 장수 관계·신뢰·만족도·충성은 캠페인 상태이면서 전투의 명령 지연·협동 보너스·독단 행동·이탈 위험으로 연결된다.
- 같은 장수가 전장 지휘관, 도시 태수, 조정 관료, 첩보 담당으로 이동할 수 있어야 한다. 직책 변경과 인사 갈등도 작전 결과의 일부가 된다.
- 개인 결투나 초인적 장수 능력은 기본 전투의 필수 규칙으로 두지 않는다. 필요하면 시나리오·인물 성향·명예 규칙으로 선택적으로 활성화한다.

오픈삼국의 차별점은 retinue를 단순 병력 슬롯으로 끝내지 않는 것이다. 장수의 부곡·가신·봉신 관계가 `누가 어떤 명령을 얼마나 잘 수행하는가`를 만들고, 전투 결과가 다시 인사·외교·도시 민심으로 돌아오게 한다.

### v1 커맨드 카탈로그의 진화 규칙

v2는 기존 커맨드를 제거해 새 전투에 맞추지 않는다. 모든 커맨드는 다음 상태를 갖는 카탈로그에서 관리한다.

```text
commandId, legacyCode, layer, sourceRing, targetScope,
adapter, version, parityStatus, deprecatedAt
```

- **보존**: 기존 개인턴·사령턴 커맨드의 코드, 예약 위치, 패리티 로그와 v1 결과를 유지한다.
- **확장**: 기존 payload에 선택 필드를 추가하되, 필드가 없으면 v1 기본값으로 동작한다.
- **분리**: 하나의 v1 커맨드가 여러 책임을 갖는 경우, 외부 legacy code는 유지하고 내부에서 `operation.*`, `battle.*`, `campaign.*`으로 나눈다. `che_출병`은 `operation.create`를 만들고, 실시간 부대 이동·대형·사격은 `battle.*`로 분리한다.
- **통합**: 중복된 precheck·권한·대상 해석만 공통 모듈로 통합한다. 서로 다른 패리티 로그와 부수효과를 가진 커맨드의 실행 의미는 합치지 않는다.
- **폐기**: v1 production에서 바로 삭제하지 않는다. 새 UI에서 숨기고 `deprecated`로 표시한 뒤 사용량·대체 경로·replay 회귀를 확인하고, 마지막으로 parser와 adapter를 제거한다.

새 커맨드의 기본 namespace는 `personal.*`(general_turn), `chief.*`(nation_turn), `operation.*`(전선 생성·참여·지원), `battle.*`(BattleSession 실시간 명령), `campaign.*`(전투 정산·도시·인사)로 둔다. 개인턴·사령턴과 전술 명령을 같은 예약 링에 섞지 않는다.

세부 legacy 목록·병합/분리 후보·추가/삭제 기준·단계별 이행은 `docs/superpowers/specs/2026-07-12-v2-command-catalog-and-rollout.md`를 정본으로 참조한다.

### 건물·인프라의 추가 방식

건물은 즉시 수치 버프가 아니라 국가 계획과 도시 현장 프로젝트의 중간 상태로 둔다.

```text
사령턴: 국가 계획·예산·우선순위·건설권한 결정
  → CityProject 생성·도시/자원 잠금
개인턴: 담당 장수가 착공·감독·인력/자원 투입·중단을 실행
  → 월간/프로젝트 tick에서 진행도·사고·완공 판정
  → 경제·보급·방어·정찰·전술 전장 효과 반영
```

프로젝트 계약은 `projectId, cityId, templateId, sponsorNationId, assignedGeneralId, cost, upkeep, progress, priority, prerequisites, startedAt, completesAt, status`로 저장한다. 같은 도시에 여러 건물을 즉시 쌓지 않고 슬롯·인력·자원·보급 상태를 통해 경쟁하게 한다.

v2 첫 건물군은 다음처럼 전쟁과 국가 운영을 동시에 바꾼다.

| 건물/시설 | 제공 capability | 실제 효력이 생기는 조건 |
|---|---|---|
| 곡창·군량창 | 곡물 보관·예약·배급·재보급 거점 | 실제 재고, 관리 인력, 부패·화재 상태, 연결된 수송로가 필요 |
| 역참·군도 | 전령 교대, 숙영, 노선별 수송 capacity | 역마·인부·노면 정비·통행권·중간 거점이 유지된 구간만 이용 가능 |
| 망루·봉화대 | 관측 보고와 봉수 전달 | 관측 인력·가시선·날씨·연결된 봉수망이 있어야 경보가 도착 |
| 성벽·관문 | 물리 장애물, 수비 위치, 통행 통제 | 수비대·성문 인력·보수 자재가 없으면 파손·침투·우회에 취약 |
| 병영·훈련장 | 모집 queue, 교련 배정, 장비 지급·재편성 | 교관·장비·급료·군량과 모집원이 있어야 formation을 준비 |
| 시장·수운 시설 | 거래 계약, 집산, 선박·창고·하역 capacity | 상인 관계·현물 재고·운송 수단·치안·계절 수위에 따라 실제 흐름 결정 |

사령턴은 `chief.build.plan`, `chief.build.assign`으로 국가 계획·예산·건설권한·담당자를 예약하고, 개인턴은 `personal.cityProject.execute`, `personal.cityProject.pause`로 현장 실행·감독·중단을 예약한다. `campaign.cityProject.created/progressed/paused/completed`는 제출 명령이 아니라 campaign engine이 확정한 domain event다. 건설 완료는 전술 화면에서 직접 발생하지 않는다. 건물 template과 capability는 `EraPack`에 두어 오픈삼국의 성곽·곡창·역참과 나폴레오닉/엠파이어의 도로·창고·항만을 같은 프로젝트 계약으로 확장한다.

### 전술 전투의 플레이 주체

전투의 결정권은 실제 유저에게 두고, 반복 조작은 장수 AI와 부대 AI에 위임한다.

| 주체 | 맡는 일 | 맡기지 않는 일 |
|---|---|---|
| 플레이어 | 목표·진형·집결지·보급 우선순위·공격/퇴각 시점·원군 투입을 결정 | 병사 한 명 단위 이동과 매 타격 직접 조작 |
| 주공 장수 플레이어 | 본인 부대의 핵심 전술 명령과 본인 작전 phase 확정 | 다른 유저의 부대를 강제 이동 |
| 참모·가신 | 정찰 결과, 위험 경고, 대안 경로와 보급안 제안 | 플레이어 승인 없이 중요한 작전 목표 변경 |
| 부대 AI | 이동 경로, 교전 간격, 적 추적, 자동 퇴각 등 세부 실행 | 상위 작전의 승리 목표를 임의로 변경 |
| 적 세력 AI | 방어선, 요격, 증원, 거짓 정보, 퇴각 판단 | 플레이어에게 보이지 않는 정보를 근거 없이 사용 |

멀티플레이 지휘권은 `작전 지휘관 → 역할 지휘관 → 부대 실행자`로 나눈다. 작전 지휘관은 목표와 phase를 정하고, 주공·조공·정찰·보급·예비 담당자는 자신의 부대에 제한된 명령을 내린다. 같은 부대에는 한 시점에 하나의 유효한 명령만 존재하며, 충돌한 명령은 권한·도착 시각·보급 상태를 근거로 거부 사유를 남긴다.

v2 MVP에서는 한 명의 플레이어가 주공 부대와 독립 병력을 가진 가신 부대를 직접 지휘하고 나머지 가신·원군을 위임하는 방식으로 시작한다. 이후 협동 작전에서 역할 지휘관을 여러 유저에게 열되, 관전자와 자동 위임 모드도 함께 제공한다. 이렇게 해야 전투가 유저의 판단을 요구하면서도 접속 시간이 긴 유저만 유리한 실시간 조작 게임으로 변하지 않는다.

### 삼국지식 임무형 지휘

플레이어의 기본 명령은 현대식 `어택땅`이 아니라 다음 네 층으로 구성된 군령이다.

1. **임무**: 무엇을 달성할지 정한다. 예: 적장 생포, 보급선 차단, 관문 점령, 아군 퇴로 확보, 특정 시각까지 방어.
2. **군령**: 어디까지 위험을 감수할지 정한다. 예: 전군 진격, 선봉만 진격, 결사 항전, 피해 최소화, 적을 유인.
3. **행동 규칙**: 부대 AI가 재량을 행사할 범위를 정한다. 예: 적 본대와 교전 금지, 보급 40% 미만이면 퇴각, 성벽 돌파 전까지 예비대 유지.
4. **보고·재지휘**: 정찰·전령·참모 보고가 들어왔을 때 명령을 유지·수정·철회한다. 보고 지연과 정보 오판은 replay에 기록한다.

이 모델은 삼국지의 인물성을 살린다. 같은 `관문을 점령하라`도 통솔이 높은 장수는 현장에서 우회·유인·퇴각을 잘 판단하고, 성급하거나 군량이 부족한 장수는 무리한 돌격을 선택할 수 있다. 단, 결과를 숨은 랜덤으로 만들지 않고 장수 성향·관계·정찰 신뢰도·지형·보급을 판정 근거로 보여준다.

역할은 다음처럼 나눈다.

| 역사적 역할 | 게임 역할 |
|---|---|
| 군주·도독 | 작전 목표, 병력 투입, 외교적 금기, 최종 퇴각선 결정 |
| 군사·참모 | 작전안, 지형·보급 분석, 적 의도 추정, 대안 제시 |
| 주공 장수 | 현장 임무 수행, 진형·공격축·예비대 운용, 재량 판정 |
| 부장·가신 장수 | 맡은 목표와 행동 규칙 안에서 독립 실행 |
| 전령·정찰대 | 정보와 명령을 전달하며 시간 지연·오보 가능성을 만든다 |

따라서 UI의 기본 버튼은 `공격` 하나가 아니라 `임무 선택 → 위험도/행동 규칙 → 부대 위임`이어야 한다. `전군 진격`은 이 UI의 가장 공격적인 프리셋으로 제공하고, 플레이어가 원하면 특정 phase에서만 직접 재지휘한다.

### 우선순위 B: 재방문을 만드는 확장 콘텐츠

- 장수 관계망: 사제·동료·라이벌·원한·구명 기록이 명령 신뢰와 협상 태도에 영향을 주고, **능력치에도 보정을 준다**(유비-관우-장비 의형제형 사전 관계 포함). 2026-07-25 개정 — 원문은 "능력치 버프가 **아니라** 명령 신뢰와 협상 태도에"였으나 사용자 결정("관계는 능력치 버프에도 영향을 줘야지")으로 뒤집혔다. 보정은 PHP 골든 오라클이 없는 **v2 전용 divergence**이므로 v2 world profile 한정 tail-append stat 모듈로만 주입하고 v1 source 목록·RNG draw·로그는 불변이다. 같은 도시 동석 파트너당 ±2, 선언 상한 ±6, `GetStatValue` 교차증강 때문에 실효 상한은 통솔 ±6 / 무력·지력 ±8이며 파이프라인 직후 재클램프(`GetStatValue.kt:65`)로 255를 넘지 않는다. 설계 정본: `docs/loops/v2-planning-2026-07-12/round3-proposal-city-guanxi.md` §4.6~4.7 (독립 채점 10/10 `cleared`). 착수 시점은 **오픈 후**(P0~P6 7티켓) — ADR-LITE-021.
- 첩보와 역정보: 정찰·밀정·거짓 보고·대응 첩보를 confidence와 근거로 표시한다. 플레이어가 틀린 정보도 사후에 왜 틀렸는지 replay에서 확인한다.
- 인재 등용과 인물 사건: 등용 제안, 망명, 포상, 좌천, 후계자 추천을 작전과 도시 상태에 연결한다.
- 계절·정세 사건: 흉년, 전염병, 수해, 상업 호황, 명분 분쟁을 월간 경계에 발생시키고 국가의 선택을 기록한다.
- 플레이어 연대기: 통일보다 과정의 기록, 첫 출병·최장 방어·배신·구출·명장전 같은 사건을 개인·국가·월드 기록으로 남긴다.

### 우선순위 C: 출시 후 콘텐츠

- 시나리오 변형: 같은 지도에서 군량 부족, 후계 분쟁, 외교 봉쇄 등 시작 조건만 바꾼다.
- 시즌 목표: 점령 수가 아니라 생존·동맹 유지·민심·인재 보존처럼 서로 다른 승리 조건을 제공한다.
- 관전·공유: replay를 요약 카드와 전쟁 보고서로 공유하되, 숨겨진 정보와 관리자용 RNG 세부값은 분리한다.
- 커뮤니티 사건: 유저 간 합의로 만든 휴전·공동 방어·포로 교환을 계약과 로그로 남긴다.

### 넣지 않을 콘텐츠

- 반복 클릭형 일일 퀘스트, 무작위 전리품, 과금형 능력치, 장식만을 위한 3D 콘텐츠는 v2 핵심 루프를 증명하기 전에는 넣지 않는다.
- AI가 서술문을 즉석 생성하는 방식은 사용하지 않는다. 모든 사건·대사·판정은 입력 feature, seed, 규칙, 템플릿으로 재현한다.

## 10. 장기 확장: Open Napoleonic·Open Empire

장기적으로는 삼국지 콘텐츠를 복제하는 것이 아니라, 처음부터 같은 서버 권위·replay·작전 명령 기반 위에 대형 부대 실시간 전투·시대별 규칙을 얹는다. v2에서 실시간 대형 전투를 먼저 노출하는 것은 기반을 삼국지 전용으로 만든다는 뜻이 아니라, 공통 기반의 첫 검증 콘텐츠를 정하는 것이다.

### 공통 계층

```text
CampaignWorld      도시·국가·경제·외교·인물·시즌
  └─ Operation     목표·경로·보급·참여 부대·명령 권한
       └─ BattleInstance  지형·부대·사기·시야·전투 규칙셋·replay
```

공통 모델은 `EraPack`, `FactionPack`, `UnitTemplate`, `Doctrine`, `TerrainTemplate`, `BattleRuleset`, `Scenario`로 분리한다. 삼국지의 장수·부곡·가신, 나폴레옹 시대의 연대·포병·기병, 제국 시대의 생산·해군·식민지 시스템은 각각 pack과 ruleset이 제공하며, 공통 엔진은 어느 시대인지 직접 가정하지 않는다.

### 처음부터 만들어야 하는 전술 엔진 기반

- `BattleState`: 부대·대형·위치·속도·사기·보급·시야·지휘 연결·현재 명령을 하나의 서버 상태로 표현한다.
- `BattleTopology`: 사각형/육각형 grid와 연속 좌표 지형을 같은 위치·이동·충돌 계약으로 다룬다. tile renderer가 핵심 상태를 독점하지 않는다.
- `BattleClock`: 명령 단계 실행과 fixed-tick 실시간 실행을 모두 지원한다. QA는 짧은 profile을 쓰고, 생산 전투 속도는 world 설정으로 결정한다.
- `OrderIntent`: 공격 지점, 방어선, 호송, 정찰, 보급, 퇴각 같은 임무와 위험도·금지 조건·트리거를 저장한다. 타일 클릭이나 실시간 드래그는 이 명령의 한 입력 방식일 뿐이다.
- `FormationModel`: 부대 stack, 선형 대형, 종대·방진, 포병 배치와 같은 대형을 공통 명령 대상으로 둔다. 삼국지 부곡도 같은 부대 인터페이스로 감싼다.
- `BattleEvent`와 `BattleReplay`: 고정 tick·명령·seed·state diff를 기록해 타일 재생과 실시간 전투 재생이 같은 포맷을 사용한다.
- `BattleServerAuthority`: 클라이언트는 명령을 제출하고 상태를 구독한다. 이동·충돌·피해·사기·승패는 서버가 계산하며, 브라우저 프레임률이나 입력 순서가 결과를 바꾸지 않는다.

이 기반을 먼저 만들면 v2의 첫 전투는 `ContinuousTopology + REALTIME_FIXED_TICK` 조합으로 표현하고, grid는 내부 분할·미니맵·경로 탐색에 선택적으로 사용한다. 이후 다른 전투 모드가 필요해도 별도 게임으로 다시 만드는 것이 아니라, 같은 `BattleState`, `OrderIntent`, `FormationModel`, `BattleEvent`를 다른 시간·공간 표현으로 실행한다.

### 전투 규칙셋 확장 순서

1. 공통 전술 기반: `BattleState`, topology, fixed clock, order intent, formation, authority, event/replay를 먼저 고정한다.
2. `FormationRealtimeBattle`: 연속 좌표, 대형 footprint, 선형·종대·방진, 실시간 fixed tick, 명령 창·queued order·AI 위임, 사기·피로·지휘 반경·보급. 오픈삼국 v2의 첫 노출 방식이며 pause/slow는 sandbox·관전·replay에서만 허용한다.
3. `TabletopScenarioLayer`: 고지·관문·호송·퇴로 확보 같은 시나리오 목표, 전장 배치·전면 방향·지휘선·replay를 미니어처 워게임처럼 읽게 하는 표현 계층.
4. `NapoleonicRuleset`: 선열·종대·방진, 포병 사격, 기병 돌격, 장교 지휘, 명령 전달 지연을 `UnitTemplate`과 전투 규칙으로 추가한다.
5. `EmpireRuleset`: 전투 외에 산업·무역·해군·식민지·국제 외교를 CampaignWorld와 Operation에 추가한다. 전술전투 자체를 비대하게 만들지 않는다.

코삭스식 실시간 전투는 가능하지만, 클라이언트가 전투를 소유하면 replay와 공정성이 무너진다. 서버가 고정 tick으로 상태를 계산하고, 클라이언트는 명령과 3D 카메라·애니메이션만 담당해야 한다. 처음에는 제한된 3D terrain patch·소수 대형·짧은 전투 인스턴스로 성능을 증명한 뒤, 지형 범위·대형 수·실시간 입력을 늘린다.

확장 프로젝트의 공통 성공 기준은 “새 시대를 추가할 때 기존 캠페인·명령·replay 엔진을 복사하지 않는가”다. 시대별 콘텐츠가 공통 계약을 지키면 오픈삼국·오픈 나폴레오닉·오픈 엠파이어를 같은 플랫폼의 독립 시나리오로 운영할 수 있다.

## 11. 제품·운영 성공 기준

- command acceptance p95 < 200ms.
- commandResolved 후 영향 query가 2초 이내 갱신.
- replay 생성 p95 < 1초(정산 자체는 비동기 가능).
- 동일 입력·버전·seed 재실행 시 `DeterministicReplayBody`와 hash diff 0. envelope id/timestamp는 비교 제외.
- v1 backend gate와 web typecheck/build 회귀 0.
- v2 sandbox에서 승인부터 replay·관계 변화까지 한 번에 재현.
- 3D proof scene에서 도시 picking, 작전 경로, 전장 진입, formation 명령, replay camera가 같은 spatial snapshot을 사용.
- 2,000개 synthetic 전체 지도와 2,000개 실제 source catalog 전체에서 catalog Tier A/B/C 120/380/1,500 및 runtime `CLUSTER | SYMBOL | KIT | FULL_SCENE` 전환이 동일한 `PhysicalPlace` identity와 picking·점령·보급 상태를 유지하고, streaming 전후 simulation diff가 0.
- `CountyParticipationFixture`가 현급 1,180개 각각에서 조회, 점령, 주둔, 징병, 세입, 보급의 read-model과 순수 상태 전이를 실행해 기능별 누락 0을 증명한다.
- 군·국 정책 한 번이 소속 현에 전파되고, 우선순위 `현 override > 유효한 위임 > 군·국 정책 > world default`가 지켜진다. 검색·필터·다중 예외, 이상 알림, 위임 감사·철회·복구를 command/read-model/browser fixture로 검증한다.
- 데스크톱 1080p 60 FPS, 지원 모바일 30 FPS 목표를 Playwright screenshot·canvas pixel·frame telemetry로 검증.
- production profile에서는 v2 route, bean, Flyway location, catalog loader가 0개이고, production에서는 v1 world와 v2 sandbox world를 명확히 구분.
