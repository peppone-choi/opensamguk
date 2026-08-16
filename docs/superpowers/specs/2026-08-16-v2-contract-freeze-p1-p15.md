# v2 계약 동결 — P-1~P-15 + 공통 게이트 GATE-a~f

- 티켓: `OPENSAM-73`(P-1~P-7) · `OPENSAM-74`(P-8~P-14) · `OPENSAM-75`(P-15 + GATE-a~f)
- 라벨: **계약 동결(contract)**. 이 문서는 계약을 고정만 하며 구현하지 않는다. 구현 정본은 각 phase 에픽이다
  (`docs/superpowers/plans/2026-07-17-v2-ticket-backlog/README.md:24-33` 중복 제거 규칙).
- 상세 정본: `docs/superpowers/plans/2026-07-17-v2-ticket-backlog/01-backbone-micro.md` §문서 1 P-* 섹션
  (배경 서술판 = `appendix-backbone-initial.md:27-134`), 제품 정본 = `docs/superpowers/specs/2026-07-12-opensamguk-v2-product-spec.md`.
- 원칙: 근거 없는 항목은 동결하지 않고 §OPEN QUESTION으로 남긴다. 근거는 전부 `path:line` 또는 문서 섹션으로 표기한다.

---

## 0. 선결 사항 — Jira 본문의 P-* 제목과 정본 번호가 어긋난다 (BLOCKER-급 표기 결함)

`OPENSAM-73`/`74` 본문의 P-* 제목 9개가 정본(backlog 01 + appendix + product-spec)의 P-* 번호와 다르다.
정본은 두 백로그 파일이 서로 일치하고 product-spec 섹션 순서와도 일치하므로 **정본 번호가 이긴다**.
이 문서는 정본 번호로 동결하며, Jira 제목은 아래 crosswalk로 대응만 시킨다. **Jira 본문 수정은 사람의 결정 사항이다.**

| Jira 본문 제목 | 정본 P-* | 정본 항목 | Jira 제목이 실제로 가리키는 곳 |
|---|---|---|---|
| P-1 세계 모델(군현·속령·지형) | P-1 | 이벤트 소비 계약 4종 | T1 그룹 B (`04-systems-micro.md:38`), 소유 = `OPENSAM-36` |
| P-2 시간·phase | P-2 | `CommandSubject` | product-spec §4 Cadence(= 정본 P-1의 출처) |
| P-3 부대·병종 | P-3 | `Operation` | T1 그룹 D Formation·실명 병종 (`04-systems-micro.md:67`) |
| P-4 Replay | **P-4** | `BattleReplay` Envelope/Body/Hash | 일치 ✅ |
| P-5 행정 | P-5 | `RetainerProposal` | T1 그룹 E 행정 변경 접기 (`04-systems-micro.md:75`), 소유 = `OPENSAM-36` |
| P-6 FeudalContract | **P-6** | `FeudalContract` | 일치 ✅ |
| P-7 정통성·천명 | P-7 | 개인턴·사령턴·전술 3계층 경계 | T2 그룹 D/F 황실 정본 (`04-systems-micro.md:174,186`) |
| P-8 전투 phase | P-8 | v1 커맨드 카탈로그 진화 규칙 | product-spec §6 BattleReplay `phases[]`(= 정본 P-4b) |
| P-9 건물 | **P-9** | `CityProject` + 첫 건물군 | 일치 ✅ |
| P-10 경제·물류 | P-10 | 임무형 지휘(4층 군령) | T1 그룹 C 자원·수송·병력 (`04-systems-micro.md:54`) |
| P-11 황실·관직 | P-11 | 화면 8종 구조 | T2 전체 (`04-systems-micro.md:140-`), Jira 본문도 "필드 상세=T2"라 자인 |
| P-12 지리 수치 | **P-12** | 역사 지리 표면 수치 | 일치 ✅ |
| P-13 전술 | **P-13** | 전술 엔진 기반 7종 | 일치 ✅ |
| P-14 콘텐츠 팩 | P-14 | 공통 계층 + Pack 인터페이스 7종 | exact-count 120/72/18/24/24/32는 C-track(`README.md:76`) 소유 |
| P-15 성공 기준 | **P-15** | §11 성공 기준 | 일치 ✅ |

**결과적으로 티켓 분할 경계(73 = P-1~7 / 74 = P-8~14 / 75 = P-15)는 정본 번호 기준으로도 그대로 성립한다.**
잘못된 것은 제목 문자열뿐이고 번호 구간은 맞으므로, 이 문서는 원래의 3분할대로 진행한다.

---

## OPENSAM-73 — P-1 ~ P-7

### P-1. 이벤트 소비 계약 4종 (§4 시간·갱신 계약) — 동결

근거: `docs/superpowers/specs/2026-07-12-opensamguk-v2-product-spec.md:76-83` · 분해 = `01-backbone-micro.md:11-16`

동결 내용:

- 프론트가 소비하는 이벤트는 **정확히 4종**이며 이 목록은 확장 시 계약 개정을 요구한다.
  - `commandResolved` — 요청한 명령의 결과와 영향을 받은 query (P-1a)
  - `turnCompleted` — 현재 장수·예약표·정세 요약 query (P-1b)
  - `battlePhaseChanged` — 해당 작전/replay query (P-1c)
  - `notificationCreated` — 알림 inbox query (P-1d)
- 전역 `window.location.reload()` 금지. route navigation이 필요할 때만 Next router 사용 (P-1e).
- 내부 tick(`simulation_tick: 200ms`)은 사용자 대상 "새로고침" 의미를 갖지 않는다 (`:70,76`).
- cadence 수치 자체(`general_command_cadence` production 3600s / QA 60s, 상순·중순·하순 표시, month boundary 1회)는
  `:69-74`로 동결한다. **단 v1 날짜 36순 적용은 ADR-LITE-024가 별도로 결정한 사항이며 여기서 재결정하지 않는다.**

이미 결정되어 재결정하지 않는 것: 202 인테이크는 성공이 아니며 FE는 `pollCommandResult(requestId)`로 `RESOLVED`까지
폴링한다(result-poll 규약, `CLAUDE.md` F4 / `OPENSAM-13`·`135`). 이 규약은 `commandResolved` 소비의 **하위 규약**이지
대체가 아니다.

Exit: 문서 미명시. 측정 Exit는 실행계획 V2-1(`01-backbone-micro.md:146`)이 소유한다.

### P-2. `CommandSubject` 계약 (§6) — 동결

근거: `product-spec.md:139-148` · 분해 = `01-backbone-micro.md:19` (단일 struct, 이미 최소)

```text
subjectType: GENERAL | RETAINER | BUGOK | SUBFACTION
subjectId
orderedByGeneralId
executionOwnerGeneralId
queueScope: PERSONAL | OPERATION | NATION
idempotencyKey            // 클라이언트 UUID
```

- `subjectType`은 **4값**이다. `FOLLOWER`는 존재하지 않는다 — ADR-LITE-017이 5값 → 4값으로 축소했고
  구 추종은 가신의 `origin=EXISTING` 속성으로 흡수됐다(`.ai/decisions.md:169`, `product-spec.md:152-166`).
  이는 이미 결정된 사항이므로 재결정하지 않는다.
- `BUGOK`은 사람이 아니라 병력 집단이므로 가신 병합 대상이 아니며 subjectType으로 유지된다(`product-spec.md:166`).

### P-3. `Operation` 계약 (§6) — 동결

근거: `product-spec.md:168-179` · 분해 = `01-backbone-micro.md:20`

```text
targetCityId
arrivalWindow
participants: generals, retainers, bugok, subfaction forces
roles: MAIN | SUPPORT | SCOUT | SUPPLY | RESERVE
route
rules: intercept, retreat, siege, supply
```

- **격리 조항(v1 불변)**: 기존 `che_출병`은 **v2 sandbox/world profile에서만** 단독 `Operation`으로 감싼다.
  v1 production의 예약 queue·판정·로그·result는 변경하지 않고 adapter 밖에서 끝난다(`:179`).
- 개인턴 출병이 Operation을 만들고, 사령턴은 Operation을 열지 않는다(`:120,126,135`). 사령턴은
  `전선 보급`·`원군 소집`·`예비대 배정`·`방어 태세`·`퇴각선`·`전쟁 목표` 정책만 붙인다.
- 배정 부대의 실제 합류는 `operation.reinforce`, 전장 투입 시점·위치는 `battle.formation.commitReserve`가 담당한다(`:135`).

Exit: 문서 미명시. 실제 활성화는 실행계획 V2-3(`01-backbone-micro.md:198`).

### P-4. `BattleReplay` 결정적 계약 (§6) — **부분 동결**

근거: `product-spec.md:181-196` · 분해 = `01-backbone-micro.md:21-23` · 구현 정본 = 4A-f/g(`README.md:31`)

동결하는 것:

```text
ReplayEnvelope                                                        // P-4a
  replayId, worldId, operationId, createdAt, persistedLogEntryIds[]

DeterministicReplayBody                                               // P-4b
  worldSnapshotHash, operationInputHash, seed
  contentVersion, balanceVersion, geographyVersion
  phases[]: APPROACH, SCOUT, INTERCEPT, FIELD, SIEGE, URBAN, AFTERMATH
  phaseInput, phaseDecision, rngDraws, orderedStateDiff, normalizedLogEntries

deterministicReplayHash = hash(canonicalSerialize(DeterministicReplayBody))   // P-4c
```

- 동등성 규칙: 같은 `world snapshot + operation input + seed + content/balance/geography version` ⇒ 같은 Body·hash·결과.
- **persistence metadata(`replayId`, `createdAt`, DB log id)는 동등성 비교에서 제외**하고 필요 시 normalized
  sequence key로 대응한다(`:196`). replay는 UI 장식이 아니라 검증 가능한 결과 계약이다.
- `phases[]` 7값은 `2026-06-29` release-plan D3의 7종(approach/scout/intercept/field/siege/urban/aftermath)이
  개정판이라는 README 판정과 일치한다(`README.md:22`).

**동결하지 않는 것 → OPEN QUESTION Q1** (아래 §OPEN QUESTION). ADR-LITE-025가 승인한 실시간 전투 정본
(`2026-07-30-v2-realtime-battle-session-command-replay-design.md`)은 `BattleTicket` + 승인 event log +
checkpoint hash 어휘를 쓰며(`:202,470,475,529`) `ReplayEnvelope`/`DeterministicReplayBody`/`phases[]`를
한 번도 언급하지 않는다. 두 replay 계약의 관계(포함/대체/병존)는 문서에 근거가 없다.

### P-5. `RetainerProposal` 계약 (§6) — 동결

근거: `product-spec.md:198-205` · 분해 = `01-backbone-micro.md:24`

```text
retainerId, subjectId, proposalType, targetId
score, confidence, evidence[], biasFactors[], expiresAt, status
```

- **런타임 LLM 금지.** 제안은 규칙 점수 + 템플릿으로 생성하고 입력 feature와 score를 저장한다(`:205`).
  이는 §8 비범위(`:242`)·§9 "AI 즉석 서술 금지"(`:404`)·백로그 비범위(`01-backbone-micro.md:303`)와 3중으로 일치한다.
- 전제 계약 `Retainer`(`product-spec.md:150-166`)는 ADR-LITE-017로 이미 확정 — `origin(EXISTING|RECRUITED)` /
  `hasOwnBugok`(EXISTING 기본 true, RECRUITED 기본 false) / `role(참모|호위|군수관|정찰|사신|NONE)` /
  `releasePolicy(MUTUAL|MASTER_ONLY)` / `upkeep`(RECRUITED만 월별 금·쌀). 커맨드 3종 통합
  (`가신서약`/`가신해제`/`가신임무`), 광역 3종(`동시침공`·`집결명령`·`광역이동`)은 대상만
  `hasOwnBugok=true` 가신으로 재정의. **재결정하지 않는다**(`.ai/decisions.md:169`).

Exit: 문서 미명시. 측정 Exit는 실행계획 V2-5(`01-backbone-micro.md:227`) — "재현·명시 상태·LLM 0".

### P-6. `FeudalContract` 계약 (§6) — 동결

근거: `product-spec.md:207-213` · 분해 = `01-backbone-micro.md:25` · 구현 정본 = V2-7(`README.md`/`01-backbone-micro.md:260`)

```text
lordSubfactionId, vassalSubfactionId, fiefIds
tributeRate, reinforcementObligation, diplomacyRight, autonomy
loyalty, breachConditions, expiresAt
```

구현측(V2-7) 공유 Exit이 이 계약에 거는 관측 조건 — 계약 동결의 검증 대상으로 기록만 한다
(`01-backbone-micro.md:260`): 봉토수입 · 원군 · 위반 replay · sandbox fixture 녹색 · 실효지배 별도 ·
조서거부 비용 · `CourtProtectorate` 비자동.

### P-7. 개인턴·사령턴·전술 명령 3계층 경계 (§6) — **부분 동결**

근거: `product-spec.md:106-137` (3계층 표 `:110-118`) · 분해 = `01-backbone-micro.md:26-28`

P-7a 3계층 정의표 — 동결 (`product-spec.md:110-118`, 7행 × 3열):

| 축 | 개인턴 | 사령턴 | 전술 명령 |
|---|---|---|---|
| 대상 | 한 장수와 자신의 retinue | 국가·도시·외교·이미 열린 전선 | 열린 `BattleSession` 안의 부대·대형·지점 |
| 정본 | `general_turn` 링 | `nation_turn` 링의 국가·직책별 슬롯 | tactical order stream + `BattleState` |
| 시간 | production 3600s / QA·s1 60s 프로파일 장수 예약턴 | 국가 수뇌부 직책별 예약턴 | 서버 fixed tick 실시간, 즉시 접수 + 짧은 만료 |
| 엔진 | campaign/turn engine general-turn drain | campaign/turn engine nation-turn drain | tactical battle engine |
| 저장 | 장수·retinue·개인 상태를 기존 flush로 확정 | 국가·도시·외교·전선 정책을 기존 flush로 확정 | `BattleState`·`BattleEvent` 기록, 종료 시 campaign 결과로 정산 |
| 실패 | 사유 거절 또는 다음 개인턴 재예약 | 권한·국가 조건·직책 슬롯 사유 거절 또는 다음 사령턴 재예약 | 지연·무시·충돌·사기 붕괴·대형 이탈 등 전장 결과로 표현 |

- **계층 판별 규칙**: `che_` 같은 코드 접두어만으로 계층을 판단하지 않는다. 명령 정의와 제출 경로가 정한다(`:108`).
- **예약 링 분리**: 개인턴·사령턴과 전술 명령을 같은 예약 링에 섞지 않는다(`:316`).
- **flush 단일 경로**: 전투 종료 시에만 tactical result adapter가 전략 상태 변경안을 만들고 campaign engine이
  기존 단일 flush 경로로 확정한다(`:135`) — one-daemon-write rule과 정합(`CLAUDE.md`).
- **전역 일시정지 금지**: production에 두지 않고 sandbox·관전·replay에서만 허용한다(`:137`, `:283`, `:435`).
  플레이어 이탈 시 마지막 유효 명령 + 장수별 doctrine/retainer AI가 계속 실행한다(`:137`).

P-7b 전술 명령 식별자 — 동결: `battleId + formationId + sequence + issuedAtTick + expiresAtTick`.
전술 명령은 국가 전체의 예약턴을 소비하지 않는다(`:135`).

P-7c 계층별 registry·drain·adapter 분리 — **동결하지 않음**. `01-backbone-micro.md:28`이 "추가 분해 필요
(문서 세부 미제공)"로 명시했다. → OPEN QUESTION Q2.

---

## OPENSAM-74 — P-8 ~ P-14

### P-8. v1 커맨드 카탈로그 진화 규칙 (§9) — 부분 동결

근거: `product-spec.md:301-318` · 분해 = `01-backbone-micro.md:31-34`

P-8a 카탈로그 레코드 필드 — 동결 (`product-spec.md:305-308`):

```text
commandId, legacyCode, layer, sourceRing, targetScope,
adapter, version, parityStatus, deprecatedAt
```

P-8b namespace enum — 동결 (`:316`): `personal.*`(general_turn) / `chief.*`(nation_turn) /
`operation.*`(전선 생성·참여·지원) / `battle.*`(BattleSession 실시간) / `campaign.*`(전투 정산·도시·인사).
**개인턴·사령턴과 전술 명령을 같은 예약 링에 섞지 않는다**(P-7과 동일 조항).

P-8c 진화 상태 5규칙 — 동결 (`:310-314`):

| 상태 | 계약 |
|---|---|
| 보존 | 기존 개인턴·사령턴 커맨드의 코드·예약 위치·패리티 로그·v1 결과를 유지 |
| 확장 | 기존 payload에 **선택** 필드만 추가. 필드 부재 시 v1 기본값으로 동작 |
| 분리 | 외부 legacy code는 유지하고 내부에서 `operation.*`/`battle.*`/`campaign.*`으로 분할. `che_출병` → `operation.create` + 실시간 이동·대형·사격은 `battle.*` |
| 통합 | **중복된 precheck·권한·대상 해석만** 공통 모듈로 통합. 서로 다른 패리티 로그·부수효과를 가진 커맨드의 실행 의미는 합치지 않는다 |
| 폐기 | v1 production 즉시 삭제 금지. 새 UI에서 숨김 + `deprecated` 표시 → 사용량·대체 경로·replay 회귀 확인 → 마지막에 parser·adapter 제거 |

P-8d 커맨드별 재배치·병합·분리 매핑 — **동결하지 않음**. 정본은 `docs/superpowers/specs/2026-07-12-v2-command-catalog-and-rollout.md`
(`product-spec.md:318`, `README.md:19`, `01-backbone-micro.md:34` "추가 분해 필요"). 이 문서가 해당 매핑을
재선언하면 정본이 둘이 된다 → 참조만 하고 동결하지 않는다.

### P-9. `CityProject` 건물·인프라 계약 (§9) — 동결

근거: `product-spec.md:320-345` · 분해 = `01-backbone-micro.md:36-43` · 구현 정본 = 6-j·C-track(`README.md:30`)

P-9a 프로젝트 계약 — 동결 (`:332`):

```text
projectId, cityId, templateId, sponsorNationId, assignedGeneralId,
cost, upkeep, progress, priority, prerequisites, startedAt, completesAt, status
```

- **핵심 계약**: 건물은 즉시 수치 버프가 아니라 **국가 계획과 도시 현장 프로젝트의 중간 상태**다(`:322`).
  같은 도시에 여러 건물을 즉시 쌓지 않고 슬롯·인력·자원·보급 상태로 경쟁시킨다(`:332`).
- 흐름(`:324-330`): 사령턴이 국가 계획·예산·우선순위·건설권한 결정 → `CityProject` 생성·도시/자원 잠금 →
  개인턴 담당 장수가 착공·감독·인력/자원 투입·중단 → 월간/프로젝트 tick에서 진행도·사고·완공 판정 →
  경제·보급·방어·정찰·전술 전장 효과 반영.

P-9b~e 명령 4종 — 동결 (`:345`): `chief.build.plan` · `chief.build.assign` (사령턴 예약) /
`personal.cityProject.execute` · `personal.cityProject.pause` (개인턴 예약).

P-9f 도메인 이벤트 — 동결 (`:345`): `campaign.cityProject.created/progressed/paused/completed`.
**제출 명령이 아니라 campaign engine이 확정한 domain event다.** 건설 완료는 전술 화면에서 직접 발생하지 않는다.

P-9g 첫 건물군 6종 template/capability — 동결 (`:336-343`). 각 행의 "실제 효력이 생기는 조건"이 계약의 일부다:

| 건물/시설 | capability | 효력 조건 |
|---|---|---|
| 곡창·군량창 | 곡물 보관·예약·배급·재보급 거점 | 실제 재고, 관리 인력, 부패·화재 상태, 연결된 수송로 |
| 역참·군도 | 전령 교대, 숙영, 노선별 수송 capacity | 역마·인부·노면 정비·통행권·중간 거점이 유지된 구간만 |
| 망루·봉화대 | 관측 보고와 봉수 전달 | 관측 인력·가시선·날씨·연결된 봉수망 |
| 성벽·관문 | 물리 장애물, 수비 위치, 통행 통제 | 수비대·성문 인력·보수 자재 부재 시 파손·침투·우회에 취약 |
| 병영·훈련장 | 모집 queue, 교련 배정, 장비 지급·재편성 | 교관·장비·급료·군량·모집원 |
| 시장·수운 시설 | 거래 계약, 집산, 선박·창고·하역 capacity | 상인 관계·현물 재고·운송 수단·치안·계절 수위 |

- **template 배치 계약**: 건물 template과 capability는 `EraPack`에 둔다(`:345`) → P-14와 결합.
  따라서 template 실체는 C-track ContentEntry lifecycle을 따르며 이 문서가 개수를 선언하지 않는다
  (`01-backbone-micro.md:43` "중복 관리 필요").

### P-10. 임무형 지휘 4층 군령 (§9) — 동결

근거: `product-spec.md:363-384` · 분해 = `01-backbone-micro.md:46-52`

4층 구조 — 동결 (`:365-370`):

1. **임무** — 무엇을 달성할지. 예: 적장 생포, 보급선 차단, 관문 점령, 아군 퇴로 확보, 특정 시각까지 방어 (P-10a)
2. **군령(위험도)** — 어디까지 위험을 감수할지. 예: 전군 진격, 선봉만 진격, 결사 항전, 피해 최소화, 적 유인 (P-10b)
3. **행동 규칙** — 부대 AI 재량 범위. 예: 적 본대와 교전 금지, 보급 40% 미만 퇴각, 성벽 돌파 전까지 예비대 유지 (P-10c)
4. **보고·재지휘** — 정찰·전령·참모 보고 수신 시 명령을 **유지·수정·철회**. 보고 지연과 정보 오판은 replay에 기록 (P-10d)

- **P-10e 판정 근거 노출 계약(핵심)**: 결과를 숨은 랜덤으로 만들지 않고 **장수 성향·관계·정찰 신뢰도·지형·보급**을
  판정 근거로 보여준다(`:372`). §9 "넣지 않을 콘텐츠"의 AI 즉석 서술 금지(`:404`)와 정합.
- P-10f 역할 매핑 — 동결 (`:376-382`): 군주·도독 → 작전 목표·병력 투입·외교적 금기·최종 퇴각선 /
  군사·참모 → 작전안·지형/보급 분석·적 의도 추정·대안 / 주공 장수 → 현장 임무 수행·진형·공격축·예비대·재량 판정 /
  부장·가신 장수 → 맡은 목표와 행동 규칙 안에서 독립 실행 / 전령·정찰대 → 정보·명령 전달 + 시간 지연·오보 가능성.
- P-10g UI 3단 — 동결 (`:384`): 기본 버튼은 `공격` 하나가 아니라 `임무 선택 → 위험도/행동 규칙 → 부대 위임`.
  `전군 진격`은 이 UI의 가장 공격적인 **프리셋**으로 제공한다.

### P-11. 화면 8종(+모바일) 구조 (§7) — 동결

근거: `product-spec.md:215-225` · 분해 = `01-backbone-micro.md:55`

| ID | 화면 | 동결된 요구 |
|---|---|---|
| P-11a | 메인 | 현재 조작 대상, 다음 명령, 작전 경보, 가신 제안, 최근 정세 |
| P-11b | 지도 | **3D 기본**. 도시·관문·나루·route·formation을 같은 scene/selection 계약으로. 정사영 지휘 카메라 + WebGL 불가 환경의 정보 fallback |
| P-11c | 명령 작업대 | 장수별 예약 큐, 슬롯 선택, drag reorder, 일괄등록, 프리셋. **서버 capability**(소속·관직·부대 역할·현재 위치)가 명령·목적지 후보를 결정 |
| P-11d | 국정 | 조직도, 관직 권한, 국가 회의, 등용 후보, 부대 편제를 읽기 모델로 연결. **권한 없는 장수도 구조와 비활성 사유를 볼 수 있어야 한다** |
| P-11e | 작전 | 참여 대상·경로·도착 window·보급·원군 상태 |
| P-11f | replay | phase timeline, 전투 로그, 상태 diff. **RNG/근거는 관리자·디버그 권한에서만 노출** |
| P-11g | 가신 | 카드, 관계, 현재 임무, 제안함, 상호작용 기록 |
| P-11h | 회의 | 인물별 입장·확신·근거·편향을 표로 비교 |
| P-11i | 모바일 | 바텀시트 + 큐 timeline. **데스크톱 전용 정보 밀도를 그대로 축소하지 않는다** |

- 표시 규칙은 ADR-LITE-022가 이미 결정 — "이 주체가 지금 할 수 있는 것만" 보여주고 불가능한 것은 숨기거나
  **사유와 함께** 비활성한다. **판정 정본은 서버이며 프론트가 조건을 복제 구현하면 안 된다(이중 진실 금지)**
  (`.ai/decisions.md:231,235`). 범위 소유는 `OPENSAM-113`. **재결정하지 않는다.**
- P-11d의 "권한 없는 장수도 비활성 사유를 본다"는 ADR-LITE-022의 "사유 표기 비활성이 기본, 숨김은 신분상
  애초에 무관한 것에만"과 정합한다.

### P-12. 전체 역사 지리 표면 수치 (§7) — 동결(수치만), 구현 정본은 계획

근거: `product-spec.md:227-237` · 분해 = `01-backbone-micro.md:58` · **구현 정본 = 계획 E-G0*/E-8*** (`README.md:28`)

동결하는 수치(스펙 수치 동결이 이 티켓의 전부다):

- `PhysicalPlace` **2,000** = 한 행정 정착지 **1,200** + 전략 비행정 거점 **200** + 주변 정착지·시기별 camp·항구·오아시스 **500**
  + 해상·원거리 교역 관문 **100**. 각 장소는 이 네 `PlaceBudgetClass` 중 **정확히 하나**에 속하고, 합계와
  클래스별 수량을 **별도로** 검증한다. 계절 이동권·영역 geometry는 장소 수에 포함하지 않는다 (`:231`).
- `PolityNetwork` **240** — 역사 실수량이 아니라 별도 `CatalogBudget`의 **제품 수용 예산**이다.
  claim 없는 slot이나 동시 활성 독립국 수로 **해석하지 않는다**. 장소·정치 node·영역 presence·계절 range는
  각자 별도 집계 (`:232`).
- catalog LOD 제작 상세 예산 Tier A **120** / Tier B **380** / Tier C **1,500** (`:233`).
- runtime render LOD **4종** `CLUSTER | SYMBOL | KIT | FULL_SCENE` — 카메라 거리·밀도·기기 성능에 따라
  **catalog LOD와 독립적으로** 변하고, 모든 조합이 같은 server read model·simulation 상태를 사용한다 (`:233`).
- 현급 거점 **1,180**(§11 `CountyParticipationFixture` 기준, `:454`) — 참여 기능 6종(조회·점령·주둔·징병·세입·보급).
- 관리 계층 계약(`:234`): 기본 정책·반복 명령은 **군·국 단위**, 현 단위는 상세 보기·예외 명령·태수 위임.
  군현 검색·필터, 다중 선택 예외, 이상 알림, 위임 이력·철회·복구를 같은 관리 표면에 둔다.
- 불확실성 계약(`:235`): 비정이 논쟁적인 치소·삼한 국읍·왜 국읍·유목 이동권은 오차 반경과 복수 reconstruction을
  유지하며 **정밀한 단일 좌표·경계를 역사 사실처럼 표시하지 않는다**.
- 필드 상세 정본은 `docs/superpowers/specs/2026-07-13-v2-historical-city-army-terrain-design.md`(`:237`).

**적용 시점 — 이미 결정됨, 재결정하지 않는다.** ADR-LITE-019가 GOLDENSET 4번(전 치소 지도 참여)과 8번
(120/380/1,500 LOD)의 적용 시점을 "v2 오픈 시점"에서 **"오픈 후 G0 착수 시점"**으로 옮겼다
(`.ai/decisions.md:197`). ADR-LITE-030은 `OPENSAM-43`(V2-0B)에서 G0·1,180 선행을 해제했다(`.ai/decisions.md:324`).
수치 자체는 폐기되지 않았고 **v2 오픈 판정 기준에서만 제외**된다.

### P-13. 공통 전술 엔진 기반 7종 (§10) — **부분 동결**

근거: `product-spec.md:420-430` · 분해 = `01-backbone-micro.md:61` · 구현 정본 = Spike V2-B0(`README.md:29`)

| ID | 컴포넌트 | 계약 |
|---|---|---|
| P-13a | `BattleState` | 부대·대형·위치·속도·사기·보급·시야·지휘 연결·현재 명령을 **하나의 서버 상태**로 표현 |
| P-13b | `BattleTopology` | 사각형/육각형 grid와 연속 좌표 지형을 같은 위치·이동·충돌 계약으로. **tile renderer가 핵심 상태를 독점하지 않는다** |
| P-13c | `BattleClock` | 명령 단계 실행과 fixed-tick 실시간 실행을 모두 지원. QA는 짧은 profile, 생산 전투 속도는 world 설정 |
| P-13d | `OrderIntent` | 공격 지점·방어선·호송·정찰·보급·퇴각 임무 + 위험도·금지 조건·트리거 저장. **타일 클릭/드래그는 입력 방식의 하나일 뿐** |
| P-13e | `FormationModel` | 부대 stack, 선형, 종대·방진, 포병 배치를 공통 명령 대상으로. **삼국지 부곡도 같은 부대 인터페이스로 감싼다** |
| P-13f | `BattleEvent` + `BattleReplay` | 고정 tick·명령·seed·state diff 기록. **타일 재생과 실시간 재생이 같은 포맷** |
| P-13g | `BattleServerAuthority` | 클라이언트는 명령 제출 + 상태 구독. 이동·충돌·피해·사기·승패는 서버 계산. **브라우저 프레임률·입력 순서가 결과를 바꾸지 않는다** |

- 첫 전투 조합 — 동결: `ContinuousTopology + REALTIME_FIXED_TICK`. grid는 내부 분할·미니맵·경로 탐색에만
  선택적으로 사용한다(`:430`). 사각형 타일은 플레이어 전장의 기본 표현으로 채택하지 않는다(`:274`).
- 확장 순서 1~5 — 동결(`:434-438`): ①공통 전술 기반 → ②`FormationRealtimeBattle`(v2 첫 노출,
  pause/slow는 sandbox·관전·replay 전용) → ③`TabletopScenarioLayer` → ④`NapoleonicRuleset` → ⑤`EmpireRuleset`.
- Exit(§10 공통 성공 기준): "새 시대 추가 시 기존 캠페인·명령·replay 엔진을 복사하지 않는가"(`:442`).

**동결하지 않는 것 → OPEN QUESTION Q3.** ADR-LITE-025가 승인한 전용 `battle-engine` 정본
(`2026-07-30-...-design.md`)은 위 7종 이름을 **한 번도 사용하지 않고** battle session actor / `BattleTicket` /
frozen authority / 편제(진영당 16, 총 32) 어휘를 쓴다. 7종이 그 안에서 어떤 이름으로 살아남는지 문서에 근거가 없다.

### P-14. 공통 계층 + Pack 분리 계약 (§10) — 동결

근거: `product-spec.md:406-418` · 분해 = `01-backbone-micro.md:64-65`

P-14a 3계층 경계 — 동결 (`:412-416`):

```text
CampaignWorld            도시·국가·경제·외교·인물·시즌
  └─ Operation           목표·경로·보급·참여 부대·명령 권한
       └─ BattleInstance 지형·부대·사기·시야·전투 규칙셋·replay
```

P-14b~h Pack 인터페이스 **7종** — 동결 (`:418`): `EraPack` · `FactionPack` · `UnitTemplate` · `Doctrine` ·
`TerrainTemplate` · `BattleRuleset` · `Scenario`.

- **불변식**: 공통 엔진은 어느 시대인지 **직접 가정하지 않는다**. 시대별 콘텐츠(삼국지 장수·부곡·가신 /
  나폴레옹 연대·포병·기병 / 제국 생산·해군·식민지)는 전부 pack과 ruleset이 제공한다(`:418`).
- Exit: §10 공통 성공 기준(`:442`) — 새 시대 추가 시 엔진 미복사.
- **Jira 74 본문의 "exact-count 120/72/18/24/24/32 + ContentEntry lifecycle
  (BUDGET_ONLY→NAMED→CLAIMED→FIXTURE_GREEN→ACTIVE)"은 정본 P-14가 아니다.** 그 계약의 소유는
  C-track(`README.md:76`, `01-backbone-micro.md:190`)이며 여기서 재선언하면 정본이 둘이 된다.
  ADR-LITE-019에 따라 C-track은 **오픈 후**다(`.ai/decisions.md:190`). 참조만 하고 동결하지 않는다.

---

## OPENSAM-75 — P-15 성공 기준 + 공통 게이트 GATE-a~f

### P-15. 제품·운영 성공 기준 (§11) — 동결(수치), 측정 소유는 각 phase Exit

근거: `product-spec.md:444-457` · 분해 = `01-backbone-micro.md:67-68` · **구현 정본 = 각 phase Exit**(`README.md:32`)

**계약 동결의 의미**: 아래 12개 수치는 이 문서로 고정된다. 그러나 각 수치를 *언제 어느 게이트가 측정하는가*는
실행계획 phase Exit이 소유하며(`01-backbone-micro.md:68` "대부분 계획 phase Exit와 중복 — 계획을 정본"),
이 문서는 게이트를 만들지 않는다.

| ID | 기준 (동결) | 근거 | 측정 소유 |
|---|---|---|---|
| P-15a | command acceptance **p95 < 200ms** | `:446` | V2-1 |
| P-15b | `commandResolved` 후 영향 query가 **2초 이내** 갱신 | `:447` | V2-1 / V2-8 freshness |
| P-15c | replay 생성 **p95 < 1초** (정산 자체는 비동기 가능) | `:448` | V2-4A |
| P-15d | 동일 입력·버전·seed 재실행 시 `DeterministicReplayBody`와 **hash diff 0**. **envelope id/timestamp는 비교 제외** | `:449` | V2-4A/4B |
| P-15e | **v1 backend gate + web typecheck/build 회귀 0** | `:450` | 전 phase 공통 = GATE-e |
| P-15f | v2 sandbox에서 **승인부터 replay·관계 변화까지 한 번에 재현** | `:451` | V2-5 / V2-6 |
| P-15g | 3D proof scene에서 도시 picking·작전 경로·전장 진입·formation 명령·replay camera가 **같은 spatial snapshot 사용** | `:452` | V2-G0-C (`OPENSAM-41`) |
| P-15h | 2,000 synthetic 전체 지도 **및** 2,000 실제 source catalog 전체에서 catalog Tier A/B/C **120/380/1,500**과 runtime `CLUSTER\|SYMBOL\|KIT\|FULL_SCENE` 전환이 동일한 `PhysicalPlace` identity와 picking·점령·보급 상태를 유지하고, **streaming 전후 simulation diff 0** | `:453` | V2-G0-C / V2-8 |
| P-15i | `CountyParticipationFixture`가 현급 **1,180개 각각**에서 조회·점령·주둔·징병·세입·보급의 read-model과 순수 상태 전이를 실행해 **기능별 누락 0** 증명 | `:454` | V2-G0 (**오픈 후 gate**) |
| P-15j | 군·국 정책 1회가 소속 현에 전파되고 우선순위 **`현 override > 유효한 위임 > 군·국 정책 > world default`** 준수. 검색·필터·다중 예외, 이상 알림, 위임 감사·철회·복구를 command/read-model/browser fixture로 검증 | `:455` | V2-G0-A (`OPENSAM-36`) |
| P-15k | 데스크톱 1080p **60 FPS**, 지원 모바일 **30 FPS**를 Playwright screenshot·canvas pixel·frame telemetry로 검증 | `:456` | V2-G0-C / V2-8 |
| P-15l | production profile에서 **v2 route·bean·Flyway location·catalog loader가 0개**이고, production에서 v1 world와 v2 sandbox world를 명확히 구분 | `:457` | V2-0A (`OPENSAM-35`, 격리 게이트) |

표기 정정: `appendix-backbone-initial.md:121`은 §11을 "13개 기준"으로 적었으나 `product-spec.md:446-457`의
실제 항목은 **12개**이며 `01-backbone-micro.md:68`의 a~l 열거(12개)와 일치한다. 12가 맞다.

**적용 시점 — 이미 결정됨, 재결정하지 않는다.**

- P-15i는 **`OPENSAM-43`(V2-0B)의 선행이 아니다.** ADR-LITE-030이 OP43을 고정된 기존 도시 입력으로 열고
  G0·1,180 선행을 해제했다(`.ai/decisions.md:324`, `01-backbone-micro.md:68` 괄호 주석과 동일).
- P-15h·P-15i·P-15k는 ADR-LITE-019에 의해 **v2 오픈 판정 기준에서 제외**되고 오픈 후 G0 착수 시점에 적용된다
  (`.ai/decisions.md:197`). 항목 자체는 폐기되지 않았다.
- P-15l은 `OPENSAM-35`의 격리 게이트로 이미 선설치됐다(브랜치 `op-35-v2-0a`, 커밋 `18b8bd95`/`db91d56d`).
  ADR-LITE-029가 "OPENSAM-35는 격리 probe로 닫고 실제 v2 leaf는 OPENSAM-150에서 증명한다"로 확정
  (`.ai/decisions.md:312`).

### GATE-a ~ GATE-f. 매 phase 공통 게이트 — 동결

근거: `01-backbone-micro.md:294-295` · `README.md:34` · `CLAUDE.md` §Skills(mandatory legacy-gap chain / 외부 리뷰어)

**전 phase Exit에 공통 적용된다.** 항목당 1체크이며, 하나라도 미충족이면 phase는 닫히지 않는다.

| ID | 게이트 | 요구 증거 | 정합 근거 |
|---|---|---|---|
| GATE-a | PHP oracle 증거 | PHP/hwe **source path + line** + 해당 golden | `01-backbone-micro.md:295`; `CLAUDE.md` "opensamguk-php-oracle 먼저" |
| GATE-b | webapp-testing UI 재현 | 브라우저로 재현한 UI 증거 | 동 `:295`; `CLAUDE.md` mandatory legacy-gap chain |
| GATE-c | loop-engineering 증거 | `docs/loops/v2-*`에 **가설 · baseline · grader · 채택 · 원복** 기록 | 동 `:295`; `docs/superpowers/LOOP_ENGINEERING.md` |
| GATE-d | provider-agnostic 가드 | `tools/agent-system/check.py --strict --base origin/main --format json` | 동 `:295`; `CLAUDE.md` §provider-agnostic guard |
| GATE-e | v1 패러티 게이트 green | v1 backend gate + web typecheck/build **회귀 0** (= P-15e와 동일 대상) | 동 `:295`; `product-spec.md:450` |
| GATE-f | 외부 fresh 리뷰어 | 크로스-에이전트 판정 `cleared` \| `fix-required` \| `quarantined-with-proof`. **미해결 `fix-required`는 머지·배포를 막는다** | 동 `:295`; `CLAUDE.md` §cross-agent critique |

부가 규칙 — 이미 결정되어 재결정하지 않는 것:

- **증거 부재는 통과가 아니다.** 체인의 링크가 불가하면 `채점대기`/`blocked`로 기록하고 조용히 ship/merge 하지 않는다
  (`CLAUDE.md` mandatory legacy-gap chain). 미검증은 UNKNOWN이지 추측이 아니다(`CLAUDE.md` Hard rules).
- GATE-f의 PR 운용은 ADR-LITE-026이 이미 확정 — 리뷰 에이전트를 **3회 멘션**하고 수정·재검증 후에만 머지
  (`.ai/decisions.md:276`).
- **골든·게이트 약화 금지**가 모든 게이트의 상위 규칙이다(`product-spec.md:245`, `01-backbone-micro.md:303`,
  `CLAUDE.md` 패러티 discipline 5).

### 전 티켓 공통 비범위 (동결, 변경 없음)

근거: `product-spec.md:239-245`·`:401-404` · `01-backbone-micro.md:303` · `README.md:78`

- v2 MVP에서 112개 커맨드 전체 구현 / 실시간 병사 단위 조작·병사 시점 자유 카메라·cinematic·**런타임 LLM**·
  결제·인앱·네이티브 앱·다국어 / 1,180개 현급을 같은 깊이로 반복 관리하는 UI(전수 simulation ≠ 전수 수동 관리) /
  v1 production s1에 v2 schema·seed 직접 주입 / 패러티 골든·게이트 약화 또는 v2 편의를 위한 PHP 동작 변경.
- §9 넣지 않을 콘텐츠: 반복 클릭형 일일 퀘스트 · 무작위 전리품 · 과금형 능력치 · (핵심 루프 증명 전) 장식용 3D ·
  **AI 즉석 서술 생성**.
- 자동 구현 금지(승인·보류): cadence 60분 외 값 · s1 v2 world 생성 · 3D asset · license · 인프라 비용 ·
  v1 gate/golden 완화(`01-backbone-micro.md:303`).
- one-daemon-write rule(`ChangeRecorder → JdbcFlushExecutor`) 유지 · CHRONICLE에서 연의·게임 콘텐츠
  역사화 금지(`README.md:78`).

---

## 병행 레인과의 정합 검토 (충돌 없음)

동시 진행 중인 구현 레인의 티켓 본문을 대조했다. **이 문서가 동결한 계약과 모순되는 항목은 없다.**

| 레인 | 티켓 | 접점 | 판정 |
|---|---|---|---|
| B | `OPENSAM-36` G0-A① 행정 계약 7종 + 도시 분리 4모델 | Jira가 말한 "P-5 행정"은 정본 P-5(RetainerProposal)가 아니라 **T1 그룹 B/E**다. 실제 접점은 **P-12**(`PhysicalPlace` 2,000·4-class = 체크리스트 `T1-B07`)와 **P-15j**(군·국 정책 전파) | **정합.** 2,000·4-class 수치가 P-12 동결값과 일치. P-5는 접점이 아니므로 이 문서의 P-5 동결이 레인 B를 제약하지 않는다 |
| C | `OPENSAM-37` 출처·확실성 계약 (EvidenceRef/HistoricalClaim/WorldContentProfile) | product-spec P-* 어디에도 대응 항목이 **없다**(T1 그룹 A·K 전용) | **정합.** 겹치는 계약 표면 없음. 단 P-12의 "정밀한 단일 좌표를 역사 사실처럼 표시 금지"(`product-spec.md:235`)가 레인 C의 `T1-A05` 시기분리 validator와 **같은 방향**이라 상호 보강 |
| D | `OPENSAM-41` G0-C 3D 공간 증명 | **P-12**(catalog LOD 120/380/1,500 · runtime LOD 4종 · 2,000 4-class) + **P-15g/h/k** | **정합.** 체크리스트 `G0C-g/h/i/k`가 P-12·P-15h·P-15k 동결값과 1:1 일치. `G0C-b~e`(picking·경로·anchor·camera 왕복)가 P-15g의 "같은 spatial snapshot"을 그대로 측정 |

주의로 남기는 것(충돌 아님): 레인 B·D는 **오픈 후** 분류인 `V2-G0`에 속한다(ADR-LITE-019, `.ai/decisions.md:190`).
이 문서는 그 순서를 바꾸지 않으며, P-12·P-15h/i/k의 적용 시점도 ADR-019/030이 정한 대로 둔다.

---

## OPEN QUESTION (동결하지 않음 — 근거 없음 / 결정 필요)

이 목록은 착수 시점까지 열려 있으며, **임의로 채우지 않는다.** 각 항목은 결정 주체를 명시한다.

- **Q1 (P-4)** — product-spec §6의 `ReplayEnvelope`/`DeterministicReplayBody`/`phases[APPROACH..AFTERMATH]`와
  ADR-LITE-025가 승인한 `2026-07-30-v2-realtime-battle-session-command-replay-design.md`의
  `BattleTicket` + 승인 event log + checkpoint hash 계약이 **같은 replay인지 다른 층인지 문서에 없다.**
  후자는 전자의 어휘를 한 번도 쓰지 않는다(`:202,470,475,529`). ADR-LITE-025는 "V2-4A/4B 오픈 후"만
  supersede했고 replay 계약 자체의 대체 여부는 언급하지 않았다(`.ai/decisions.md:271`).
  결정 주체: 전투 프로그램 에픽(키 발행 대기, `README.md:70`) + 사람 승인.
- **Q2 (P-7c)** — 3계층 registry·drain·adapter 분리 설계. `01-backbone-micro.md:28`이 "문서 세부 미제공"으로
  자인. 결정 주체: 실행계획 V2-1/V2-3 구현 티켓.
- **Q3 (P-13)** — 전술 엔진 기반 7종(`BattleState`/`BattleTopology`/`BattleClock`/`OrderIntent`/`FormationModel`/
  `BattleEvent`+`BattleReplay`/`BattleServerAuthority`)이 ADR-LITE-025의 전용 `battle-engine` 설계 안에서
  어떤 이름·경계로 살아남는지 문서에 없다. 07-30 스펙은 7종을 한 번도 언급하지 않는다.
  결정 주체: Spike V2-B0 ↔ 전투 프로그램 에픽 통합 판정 + 사람 승인. (Q1과 같은 뿌리)
- **Q4 (P-8d)** — 커맨드별 재배치·병합·분리 매핑. 정본은 `2026-07-12-v2-command-catalog-and-rollout.md`이며
  이 문서가 재선언하면 정본이 둘이 된다. 결정 주체: 커맨드 카탈로그 에픽(C0~C5).
- **Q5 (P-9g)** — 첫 건물군 6종의 template 실체는 `EraPack` 소속이라 C-track ContentEntry lifecycle을 따르는데
  (`product-spec.md:345`, `01-backbone-micro.md:43` "중복 관리 필요"), C-track exact-count
  120/72/18/24/24/32 중 어느 버킷에 6종이 들어가는지 문서에 없다. 결정 주체: C-track(오픈 후).
- **Q6 (P-15 / GATE-a)** — P-1~P-15는 전부 **v2 신규 계약**이라 PHP 오라클이 존재하지 않는데, GATE-a는
  "PHP oracle source/line + golden"을 매 phase Exit에 요구한다(`01-backbone-micro.md:295`). v2-only 산출물에서
  GATE-a를 어떻게 충족(또는 면제)하는지 문서에 규정이 없다. `CLAUDE.md`는 "링크 불가 시 `채점대기`/`blocked`"만
  말하고 면제 절차는 주지 않는다. 결정 주체: 사람 승인(게이트 정의 변경이므로).
- **Q7 (P-15 개수 표기)** — `appendix-backbone-initial.md:121`의 "13개 기준"과 실제 12개의 불일치.
  이 문서는 12로 동결했으나 appendix 원문 정정 여부는 문서 소유자의 결정이다.
