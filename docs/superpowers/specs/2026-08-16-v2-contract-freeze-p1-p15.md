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
