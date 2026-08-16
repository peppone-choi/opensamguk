# V2 전투 정본 충돌 판정 — P-4 `ReplayEnvelope` · P-13 전술 7종 ↔ ADR-LITE-025 `BattleTicket`

- 날짜: 2026-08-16
- 대상: 계약 동결 문서(`2026-08-16-v2-contract-freeze-p1-p15.md`, 브랜치 `op-73-75-contract-freeze`) §OPEN QUESTION **Q1**(P-4) · **Q3**(P-13)
- 성격: **판정 초안(제안)**. 이 문서는 결정하지 않는다. ADR-LITE 신규 작성 · product-spec 수정 · Jira 수정은 전부 사람 승인 사항이다.
- base: `origin/main` = `d63f6fec`
- 조사 범위: product-spec · 07-28 2.5D 설계 · 07-30 세션 스펙 · 07-30 구현 계획 · v2 티켓 백로그 · `.ai/decisions.md` · git 커밋 이력 · Jira OPENSAM(에픽 21/24/25, BATTLE-F0~F13 = 156~169)

---

## 0. 결론 요약

레인 E는 Q1·Q3을 "같은 뿌리"로 묶었으나, **증거는 비대칭이다. 두 질문은 분리해서 판정해야 한다.**

| 질문 | 판정 | 신뢰도 | 한 줄 근거 |
|---|---|---|---|
| **Q3 (P-13 전술 7종)** | **(a) 포함** | **중상** | 07-30 스펙은 7종 이름을 안 쓰지만, **같은 커밋 계열의 후속 구현 계획이 7종 중 4종을 파일명 그대로 되살린다**(`BattleState.kt`·`BattleClock.kt`·`BattleReplayReducer.kt`·`BattleEventRepository.kt`). 나머지 3종은 폐기가 아니라 어댑터 에픽으로 이연 |
| **Q1 (P-4 Replay 계약)** | **(c) 병존이 유일한 무모순 해석이나, 그것을 명시한 문서가 없다 → 문서 등급으로는 (d)** | (c)가 의도라는 확신: **중** / 어느 문서도 명시하지 않았다는 확신: **상** | 필드 대조 결과 두 계약은 **어휘 차이가 아니라 스코프가 다르다**(P-4=`operationId` 키, 07-30=`battle_id` 키). 그런데 (c)를 선언한 문장이 어디에도 없다 |

**(b) 대체는 두 질문 모두에서 기각한다.** ADR-LITE-025는 supersede 목록을 명시적으로 열거하며 product-spec §6·§10을 그 목록에 넣지 않았고(`.ai/decisions.md:271`), 오히려 같은 동기화 커밋이 "제품 spec의 replay 계약을 개정하지 않는다"는 문장을 **의도적으로 보존**했다.

**가장 실행 가능한 단일 결함(판정과 무관하게 참):** `P-15d`(동일 입력 재실행 시 `DeterministicReplayBody` hash diff 0)는 **현재 소유 티켓이 없다.** 07-30 구현 계획은 product-spec을 **한 번도 참조하지 않으며**(grep 0건), BATTLE-F12가 만드는 G1 게이트는 checkpoint state hash 게이트로 P-15d와 다른 산출물이다.

---

## 1. 결정적 증거 (path:line)

### E1 — ADR-LITE-025는 supersede 목록을 열거했고 product-spec은 그 목록에 없다

`.ai/decisions.md:271` (Consequences):

> ADR-LITE-019/021의 "V2-4A/4B 오픈 후"와 "오픈 경로 20 단일값"은 이 결정으로 **해당 부분만** supersede된다. … **이전 2.5D 문서**의 game-engine scheduler·HTTP/SSE·8편제·오픈 후 rollout은 역사 초안으로 강등하고, Three.js 정사영 2.5D·formation 판정·에셋 계약은 유지한다.

supersede 대상이 (i) ADR-019/021의 **일정 분류**, (ii) **07-28 2.5D 문서**의 4개 절로 특정된다. product-spec §6(P-4)·§10(P-13)은 언급조차 없다. 07-30 스펙 본문 §3 "대체" 표(`:63-70`)도 6행 전부가 07-28 문서를 가리키며, product-spec을 가리키는 행은 0이다.

### E2 — 같은 동기화 커밋이 "product-spec replay 계약 불개정"을 문장 수술로 보존했다

`docs/superpowers/plans/2026-07-28-v2-2_5d-tactical-battle-and-sprite-design.md:70` (현재 본문):

> 이 2.5D는 … 따라서 **기존 제품 spec의 3D, `ContinuousTopology + REALTIME_FIXED_TICK`, spatial snapshot, replay 계약을 개정하지 않는다.** 전투의 V2 출시 필수 전환과 ADR-LITE-019/021 일정 개정은 2026-07-30 승인 스펙이 정본이다.

`git log -L 66,72`로 확인한 이 줄의 이력:

- `121baa21` (2026-07-29) 최초 작성 — 뒷절이 "다만 전투 자체는 현재 로드맵상 v2 오픈 후다…"
- `3f4d2f2a` (2026-07-30, `docs(v2): adopt realtime battle launch architecture`) — **같은 문장의 뒷절만 재작성**하고 앞절("replay 계약을 개정하지 않는다")은 그대로 둠

이것은 침묵이 아니라 **선택적 보존**이다. 동기화 담당자가 그 문장을 편집하려고 커서를 올려놓은 상태에서 replay 불개정 절만 남겼다. (b) 대체에 대한 가장 강한 반증이다.

### E3 — 07-30 구현 계획이 P-13 7종 중 4종을 파일명으로 되살린다

`docs/superpowers/plans/2026-07-30-v2-realtime-battle-foundation-implementation-plan.md`:

- `:80`, `:505` — `deterministic/BattleClock.kt` (= P-13c)
- `:82`, `:507` — `deterministic/BattleState.kt` (= P-13a), `:78` `codec/CanonicalBattleStateCodec.kt`, `:83` `deterministic/BattleStateHasher.kt`
- `:92`, `:516` — `replay/BattleReplayReducer.kt` (= P-13f 후반)
- `:143`, `:770` — `persistence/BattleEventRepository.kt` (= P-13f 전반), `:172` `BattleReplay100xTest.kt`

레인 E의 관찰("07-30 스펙은 7종을 한 번도 언급하지 않는다")은 **스펙 파일에 한해 참**이다. 그러나 정본 판단 단위는 스펙 단독이 아니라 스펙 + 그 스펙이 §23에서 예고한 후속 구현 계획이며(`spec:731`), 계획은 같은 저자·이틀 내(`fbfe095f`, 2026-07-30)에 나왔다.

---

## 2. 어휘 대응표 (필드 수준)

**작성 원칙**: 이름이 달라도 같은 역할이면 대응으로, 이름이 비슷해도 스코프·산출물이 다르면 불일치로 표기했다.

### 2.1 P-4a `ReplayEnvelope` ↔ 07-30 저장 모델

| P-4a 필드 (`product-spec.md:185`) | 07-30 대응물 | 근거 | 판정 |
|---|---|---|---|
| `replayId` | 대응 이름 없음. 최근접 = `(battle_id, result_revision)` | `spec:450` | △ 대체 가능, 명시 없음 |
| `worldId` | `world_id` | `spec:400` | ✅ 1:1 |
| **`operationId`** | **없음** — `operation`/`작전` 문자열이 07-30 스펙 전체에 **0회** | grep 검증 | ❌ **대응물 부재** |
| `createdAt` | `started_at` / `resolved_at` | `spec:409` | ✅ 등가 |
| `persistedLogEntryIds[]` | `(battle_id, event_seq)` · `latest_event_seq` | `spec:424`, `:406` | ✅ 등가(형태 다름: id 배열 → 단조 seq) |

### 2.2 P-4b `DeterministicReplayBody` ↔ 07-30 결정성 계약

| P-4b 필드 (`product-spec.md:188-191`) | 07-30 대응물 | 근거 | 판정 |
|---|---|---|---|
| `worldSnapshotHash` | **hash 아님** — `entity별 campaign revision` + `lockGeneration` + `lockSetRevision` | `spec:418` | ⚠️ **구조 치환**(해시 → 리비전 3종) |
| `operationInputHash` | BattleTicket `immutable payload와 payload hash` | `spec:414` | ✅ 등가(스코프만 operation→battle) |
| `seed` | `RNG algorithm/serializer revision과 seed` | `spec:420`, `:491` | ✅ 1:1(+revision 추가) |
| `contentVersion` | `catalog_revision` + catalog content hash | `spec:405`, `:419`, `:494` | ✅ 1:1(강화) |
| `balanceVersion` | `ruleset_revision` + ruleset content hash | 동상 | ✅ 1:1(강화) |
| `geographyVersion` | `terrain_revision` + terrain content hash | 동상 | ✅ 1:1(강화) |
| **`phases[]`: APPROACH, SCOUT, INTERCEPT, FIELD, SIEGE, URBAN, AFTERMATH** | **열거 없음.** "중요 phase/objective 전이"를 event로 저장, "phase transition" 시 추가 snapshot | `spec:435`, `:446` | ❌ **7값 미정의** |
| `phaseInput` / `phaseDecision` | phase 스코프 구조 없음. event별 `versioned payload` + `reason code and deterministic evidence` | `spec:427`, `:430` | ⚠️ 분해축 다름 |
| `rngDraws` | replay 구성요소 "RNG 근거" · snapshot `RNG state` | `spec:475`, `:442` | ✅ 등가 |
| `orderedStateDiff` | `compressed authoritative state` + `state_hash` (diff가 아니라 snapshot+event tail) | `spec:441`, `:444`, `:527` | ⚠️ 재현 방식 다름 |
| **`normalizedLogEntries`** | **없음** — 정규화 로그 엔트리 개념 부재 | grep 검증 | ❌ **대응물 부재** |

### 2.3 P-4c 해시 ↔ 07-30 해시

| P-4c (`product-spec.md:193`) | 07-30 대응물 | 근거 | 판정 |
|---|---|---|---|
| `deterministicReplayHash = hash(canonicalSerialize(DeterministicReplayBody))` — **본문 전체에 대한 단일 종단 다이제스트** | **체인형**: snapshot별 `state_hash`, checkpoint별 hash, event별 `payload checksum`, canonical binary codec + SHA-256 | `spec:444`, `:475`, `:492`, `:431` | ⚠️ **검증 형태가 다름** |

두 방식 모두 결정성을 증명하지만 **산출물이 다르다.** P-15d는 "`DeterministicReplayBody`와 hash diff 0"(`product-spec:449`)이고, 07-30 G1은 "모든 checkpoint state hash 동일"(`spec:620`)이다. 체인에서 단일 다이제스트를 파생할 수는 있으나 **07-30은 그것을 정의하지 않는다.**

### 2.4 P-13 전술 7종 ↔ 07-30 스펙 + 구현 계획

| P-13 | 07-30 **스펙** | 07-30 **구현 계획** | 판정 |
|---|---|---|---|
| P-13a `BattleState` | 이름 없음(state/snapshot 개념 편재) | `deterministic/BattleState.kt` `:82`,`:507` · `BattleStateHasher` `:83` · `CanonicalBattleStateCodec` `:78` | ✅ **동명 생존** |
| P-13b `BattleTopology` | 없음 | 없음 | ❌ **부재** — §4 비범위 이동·접촉 수식(`spec:94`) + 어댑터 에픽(`plan:1638-1640`)으로 이연 |
| P-13c `BattleClock` | "200ms 고정 틱" · §12.1 clock domains(`:478-487`) | `deterministic/BattleClock.kt` `:80`,`:505` | ✅ **동명 생존** |
| P-13d `OrderIntent` | §8 명령 상태기계(`:253-319`) — 승인·전달지연·조작모드 | 동명 없음; `contract/BattleMessages.kt` `:74` | ⚠️ **개념 생존, 이름 소멸** |
| P-13e `FormationModel` | formation seat·편제 편재(§7 `:204-252`) | 동명 없음 | ⚠️ **개념 생존, 모델 자체는 어댑터로 이연** |
| P-13f `BattleEvent` + `BattleReplay` | `battle_event` 테이블 §11(`:422-435`) | `BattleEventRepository.kt` `:143`,`:770` · `BattleReplayReducer.kt` `:92`,`:516` · `BattleReplay100xTest` `:172` | ✅ **동명 생존** |
| P-13g `BattleServerAuthority` | §5 소유권 불변식(`:137-146`) + `BattleAuthoritySnapshot`(§7) | authority reducer `:583` | ⚠️ **개념 생존, `BattleAuthoritySnapshot`으로 개명** |

**P-13의 동결된 "첫 전투 조합" `ContinuousTopology + REALTIME_FIXED_TICK`**(`product-spec:430`)은 07-30 스펙 §3 유지 목록의 "연속 정수/fixed-point 좌표와 200ms 고정 틱"(`spec:51`)과 일치하고, 07-28 문서 `:70`이 이 계약의 불개정을 명시한다. → **동결값 유지 확인.**

### 2.5 축(axis) 불일치 — 이 판정의 핵심

| P-4 `phases[]` (7값) | 07-30 어댑터 (3종, `spec:691-710`) |
|---|---|
| APPROACH / SCOUT / INTERCEPT | 대응 없음 (작전 접근 단계) |
| **FIELD** | **야전** ✅ |
| **SIEGE** | **공성** ✅ |
| URBAN | 대응 없음 |
| AFTERMATH | 대응 없음 (결과 정산 = §15) |
| **대응 없음** | **수전(naval)** ❌ |

두 목록은 같은 축이 아니다. `phases[]`는 **한 작전 안의 순차 단계**이고, 07-30 어댑터는 **전투의 종류**다. 교집합(FIELD/SIEGE)은 실재하지만, ADR-LITE-025가 출시 필수로 넣은 **수전은 `phases[]`에 자리가 없다**(`.ai/decisions.md:268`). 이는 어느 판정을 택하든 참인 구체적 결함이다.

### 2.6 product-spec 자신이 이미 두 계층을 중첩시켜 놓았다

- `product-spec.md:122-133` — `che_출병` → **Operation 생성** → 사령턴 정책 → "교전 조건 충족 시 **`BattleSession` 생성**" → tactical order stream → "**`BattleEvent`/replay 생성**" → "**Operation 정산**"
- `product-spec.md:413-415` (P-14a) — `CampaignWorld` ⊃ `Operation` ⊃ `BattleInstance`("지형·부대·사기·시야·전투 규칙셋·**replay**")
- `product-spec.md:112-117` (P-7a 전술 명령 열) — 정본 = "tactical order stream와 `BattleState`", 저장 = "`BattleState`와 `BattleEvent`를 기록하고 **종료 시 campaign 결과로 정산**"

07-30 스펙이 구현하는 것은 정확히 이 P-7a 전술 명령 열이다(§5 아키텍처 `:106-135`의 game-engine ↔ battle-engine ↔ 결과 반영 흐름 = "종료 시 campaign 결과로 정산"). 그리고 P-4의 envelope는 그 **한 층 위**인 `operationId`로 키를 잡는다.

---

## 3. 후보별 찬반

### (a) 포함 — 세션 문서가 P-4/P-13의 구현 상세이고 어휘만 다르다

**찬성**
1. 계획이 P-13 7종 중 4종을 **파일명 그대로** 되살림 (`plan:80,82,92,143`). "한 번도 언급 안 함"은 스펙 파일에 한정된 관찰이다.
2. 07-28 `:70`이 "제품 spec의 … replay 계약을 개정하지 않는다"를 07-30 동기화 후에도 유지 (E2).
3. `contentVersion`/`balanceVersion`/`geographyVersion` ↔ `catalog`/`ruleset`/`terrain` revision+content hash가 사실상 1:1이며 07-30이 오히려 강화(`spec:405`,`:419`,`:494`).
4. `seed`, `operationInputHash`가 BattleTicket에 그대로 대응(`spec:414`,`:420`).
5. ADR-025가 P-4/P-13을 supersede 목록에 넣지 않음(`.ai/decisions.md:271`).

**반대**
1. `operationId` 대응물 **0건**(grep). 어휘 차이라면 envelope의 주 키가 대응되어야 한다.
2. `normalizedLogEntries` 대응물 **0건**.
3. `phases[]` 7값이 **어디에도 열거되지 않음**; 07-30의 분해축은 야전/공성/수전이며 **수전은 `phases[]`에 없다**.
4. `worldSnapshotHash` → 리비전 3종은 개명이 아니라 **구조 치환**.
5. P-4c 단일 다이제스트 ↔ 07-30 checkpoint 해시 체인은 **다른 산출물**이며, P-15d가 전자를 문자 그대로 게이트한다(`product-spec:449`).
6. P-13 7종 중 3종(Topology/OrderIntent/FormationModel)은 계획에도 동명이 없다.

**소결**: **P-13에는 성립, P-4에는 불성립.** "어휘만 다르다"가 P-4에서는 사실이 아니다.

### (b) 대체 — ADR-LITE-025가 P-4/P-13을 폐기했고 product-spec이 낡았다

**찬성**
1. `README.md:77`(커밋 `3f4d2f2a`에서 추가) — "기존 `V2-4A`(57)·`V2-4B`(58~60)는 신규 전투 프로그램으로 **대체·재분해**한다." V2-4A는 P-4의 구현 정본이다(`README.md:32`).
2. BATTLE-F0~F13(OPENSAM-156~169) 티켓 본문 어디에도 `ReplayEnvelope`/`DeterministicReplayBody`/`phases[]`/7종이 없다. 모든 티켓의 출처는 07-30 스펙+계획 링크뿐(Jira 검증).
3. 07-30 구현 계획은 product-spec을 **한 번도 참조하지 않는다**(`product-spec|2026-07-12|P-15|P-4` grep 0건).
4. 07-30 스펙(2026-07-30)이 product-spec 최종 수정(`0cbcf446`, 2026-07-25)보다 나중이다.

**반대**
1. **ADR-025가 supersede 목록을 명시적으로 열거했고 product-spec §6·§10을 넣지 않았다**(`.ai/decisions.md:271`). 열거형 supersede에서의 누락은 침묵이 아니라 비-supersede의 증거다.
2. **동기화 커밋 `3f4d2f2a`가 "replay 계약 불개정" 문장을 살려 뒀다**(E2). 같은 커밋이 한쪽에서 폐기하고 다른 쪽에서 불개정을 확인할 수는 없다.
3. **`3f4d2f2a`는 product-spec을 건드리지 않았다.** 변경 파일 5개 = `.ai/decisions.md`, `.ai/task.md`, `SESSION_HANDOFF.md`, 백로그 `README.md`, 07-28 2.5D 문서. 07-30 스펙 §24 정본 갱신 목록(`:750-757`)에도 product-spec은 없다.
4. **Jira에서 아무도 닫지 않았다.** OPENSAM-24 `[V2-4A] replay spine — deterministic replay body + hash` = 상태 **할 일**, 자식 0. OPENSAM-21 `[Spike B0+C0] 전술 공통 계약` = 상태 **할 일**, 본문에 "`BattleState`·`OrderIntent`·`BattleEvent/Replay` 직렬화 계약"이 그대로 살아 있음.
5. `README.md:77`은 "대체·**재분해**"라는 **티켓 층위** 서술이다. `README.md:28`이 정한 라벨 분리 규칙("스펙 티켓 = 계약 동결 / 계획 티켓 = 구현")상 구현 티켓 교체가 동결된 계약을 자동 폐기하지 않는다.
6. 계획이 P-13 4종을 부활시킨다(E3).

**소결**: **기각.** 반대 근거 1·2·3이 각각 독립적으로 (b)를 무너뜨린다.

### (c) 병존 — 서로 다른 계층 (P-4 = 작전 스코프 사후 리플레이, BattleTicket = 전투 인스턴스 스코프 실시간 세션)

**찬성**
1. **스코프 키가 다르다**: P-4 = `operationId`(`product-spec:185`), 07-30 = `battle_id`(`spec:399`). 07-30에 Operation 어휘 0건은 "다른 계층이라 안 쓴 것"으로 정합하게 설명된다.
2. **product-spec이 이미 중첩을 문서화했다**: P-14a `CampaignWorld ⊃ Operation ⊃ BattleInstance`(`:413-415`), 흐름도 `che_출병 → Operation → BattleSession → BattleEvent/replay → Operation 정산`(`:122-133`).
3. **P-7a가 07-30의 담당 셀을 지정한다**: 전술 명령 열의 정본·저장·정산 서술(`:112-117`)이 07-30 §5 아키텍처(`:106-135`)·§15 결과 반영(`:536-551`)과 그대로 겹친다.
4. 07-30 §4 비범위(`:92-102`)가 캠페인 층 소유권을 주장하지 않고 "야전·공성·수전은 본 공통 계약을 소비하는 **별도 스펙과 Epic**"으로 이연한다(`:102`).
5. 축 불일치(§2.5)가 (a)로는 설명 안 되고 (c)로는 설명된다: `phases[]`는 작전 서사, 어댑터는 전투 종류.
6. Jira 배치가 (c)와 정합: BATTLE-F0~F13 전부 부모 = **OPENSAM-25(V2-4B "실시간 formation 전투 + 전술 엔진 runtime")**이고, **OPENSAM-24(V2-4A replay spine)는 건드리지 않은 채 남아 있다.** 실시간 세션 층만 재분해되고 작전 replay 층은 그대로다.

**반대**
1. **(c)를 명시한 문장이 어느 문서에도 없다.** 이것은 재구성이지 인용이 아니다.
2. 두 결정성 게이트(P-15d `DeterministicReplayBody` hash diff 0 / 07-30 G1 checkpoint hash 100회)를 **둘 다** 만들 주체가 없다. BATTLE-F12는 후자만 만든다.
3. `README.md:77`이 V2-4A를 "대체"라고 쓴 것은 (c)와 어긋난다 — (c)라면 V2-4A는 살아 있어야 한다.
4. product-spec 자체가 replay를 한 번만 언급("`BattleEvent`/replay 생성", `:131`)해 작전 replay와 전투 replay가 **둘**인지 **하나**인지 내부적으로 모호하다.

**소결**: **모든 문서와 모순되지 않는 유일한 해석이지만, 어느 문서도 이것을 말하지 않는다.**

### (d) 판정 불가

**찬성**: Q1에 한해 — (c)를 지지하는 것은 전부 정황·구조 정합성이고, (c)를 **선언한 근거 문장이 0건**이다. 프로젝트 규칙상 "미검증 = UNKNOWN이지 추측이 아니다"(`CLAUDE.md` Hard rules).

**반대**: Q3에는 부적용 — E3(계획의 동명 부활)이 직접 증거이고 07-28 `:70`이 뒷받침한다. (d)로 미루면 확보된 증거를 버리는 것이 된다.

---

## 4. 최종 판정

### Q3 (P-13 전술 7종) — **(a) 포함**, 신뢰도 **중상**

07-30 세션 스펙 + 그 후속 구현 계획은 P-13 전술 엔진 기반의 **런타임·세션 상세화**다. P-13은 계약으로 살아 있고 동결값(`ContinuousTopology + REALTIME_FIXED_TICK`, 확장 순서 1~5)도 유효하다.

7종의 생존 형태:
- **동명 생존 4종**: `BattleState`, `BattleClock`, `BattleEvent`, `BattleReplay` → BATTLE-F2/F3/F5가 구현
- **개명 생존 2종**: `OrderIntent` → 07-30 §8 명령 상태기계 / `BattleServerAuthority` → `BattleAuthoritySnapshot` + §5 소유권 불변식
- **이연 1종**: `BattleTopology` → 야전/공성/수전 어댑터 스펙 (**현재 소유 티켓 없음 — 결함**)

잔여 리스크: P-13b의 불변식 "사각형/육각형 grid와 연속 좌표 지형을 **같은** 위치·이동·충돌 계약으로"(`product-spec:423`)와 P-13e "삼국지 부곡도 **같은** 부대 인터페이스로"(`:426`)를 07-30 계열 문서 어디도 재진술하지 않는다. 어댑터 3종이 각자 좌표·부대 모델을 만들면 이 불변식이 조용히 깨진다.

### Q1 (P-4 Replay 계약) — **(c)가 유일한 무모순 해석이나 문서 근거는 부재 → 형식 등급 (d)**

- (b) 대체: **기각**(§3 반대 1·2·3)
- (a) 포함: **기각**(`operationId`·`normalizedLogEntries` 부재, `phases[]` 미열거, 해시 형태 상이)
- (c) 병존: **유일한 무모순 해석**, 그러나 **선언 문장 0건** → 사람이 비준(ratify)해야 성립

즉 **"(c)로 확정하자"가 아니라 "(c)로 확정할지를 사람이 결정해야 한다"**가 이 문서의 결론이다. 비준 전까지 BATTLE 트랙은 P-4를 소비하지도 폐기하지도 않은 상태로 진행 중이며, 그 상태 자체가 P-15d 미소유를 만들고 있다.

**판정을 (c)로 확정 가능하게 만드는 것**(레인 E 원칙 "무엇이 있으면 판정 가능한가"):
1. 사람이 "P-4는 작전 스코프, 07-30은 전투 인스턴스 스코프"를 승인 → ADR-LITE 1건이면 종결
2. 또는 사람이 "P-4를 폐기하고 07-30 계약으로 단일화" 승인 → product-spec §6 BattleReplay 절 + P-15c/d 개정
3. 어느 쪽이든 **수전(naval)의 `phases[]` 자리**를 함께 결정해야 함

---

## 5. 무엇을 고쳐야 하는가 (전부 제안)

| # | 산출물 | 제안 내용 | 선행 조건 |
|---|---|---|---|
| **R1** | **ADR-LITE 신규 (최우선)** | P-4와 07-30 계약의 관계를 (c) 병존 2계층으로 비준하거나 단일화를 결정한다. P-13은 (a) 포함으로 기록하고 7종의 생존 형태(동명4/개명2/이연1)를 명시한다. **가장 값싼 단일 조치** — 이것 하나로 Q1·Q3이 동시에 닫힌다 | 사람 승인 |
| **R2** | `product-spec.md:190` `phases[]` | 수전(naval) 처리를 결정한다. ①`NAVAL` 추가 ②수전을 phase가 아닌 battle type으로 선언 ③`phases[]`를 작전 층 전용으로 한정. ADR-025가 수전을 출시 필수로 만든 이상 현행 7값은 불완전하다 | R1 |
| **R3** | 동결 문서 §OPEN QUESTION | Q1을 "(c) 잠정 / 비준 대기"로, Q3을 "(a) 판정 — 근거 `plan:80,82,92,143`"으로 갱신. Q3은 **닫을 수 있다** | R1(Q1만) |
| **R4** | Jira OPENSAM-24 (V2-4A) | `README.md:77`은 "대체"라 했는데 상태는 할 일·자식 0. (c) 비준 시 "작전 스코프 replay envelope"로 재스코프, 단일화 시 종료 | R1 |
| **R5** | Jira OPENSAM-21 (Spike B0) | 7종 계약 동결 티켓의 존치 여부. (a) 판정상 BATTLE-F2/F3이 4종을 이미 구현하므로 B0는 잔여 3종(특히 `BattleTopology`) 계약으로 축소 가능 | R1 |
| **R6** | **P-15d 소유자 공백** | `DeterministicReplayBody` hash diff 0을 측정할 티켓이 없다. (c) 비준 시 V2-4A로, 단일화 시 P-15d를 checkpoint-hash 기준으로 개정 후 BATTLE-F12로 | R1 |
| **R7** | `BattleTopology` 소유자 공백 | 7종 중 유일하게 어느 티켓에도 없다. 어댑터 에픽(`plan:1638-1640`) 발행 시 P-13b 불변식을 공유 SPI로 못박아야 함 | 어댑터 에픽 발행 |
| **R8** | 07-30 구현 계획 traceability | `Spec-to-Task Traceability`(`plan:1579-1604`)가 product-spec을 0회 참조. R1 결정 후 P-4/P-13/P-15 행 추가 제안 | R1 |

**수정하지 말아야 할 것**: 07-28 2.5D 문서 `:70`. 이 줄이 (b) 기각의 핵심 증거이므로 R1 결정 전 변경 금지.

---

## 6. BATTLE-F0~F13 트랙 영향

**구조 (Jira 검증)**: BATTLE-F0~F13 = OPENSAM-156~169, **전원 부모 = OPENSAM-25 `[V2-4B] 실시간 formation 전투 + 전술 엔진 runtime`**. 신규 전투 에픽은 발행되지 않았다 — 07-30 계획 Task 0~13이 기존 V2-4B 에픽 아래로 재분해됐다. 라벨 `foundation-first`, `v2-battle-foundation`. 전원 상태 **할 일**.

| 티켓 | Task | 소비 계약 | P-4/P-13 접점 |
|---|---|---|---|
| **F0** (156) | 0 | 선행 증거 게이트(OPENSAM-149/35/43~48/56) | 없음 — 안전 |
| **F1** (157) | 1 | 모듈 경계·process-world | 없음 — 안전 |
| **F2** (158) | 2 | `BattleTicketV1`·message·adapter SPI·artifact registry | ⚠️ **P-4b 버전 3종의 실제 정착지**. `contentVersion`/`balanceVersion`/`geographyVersion` ↔ catalog/ruleset/terrain content hash. R1이 늦으면 이름이 굳는다 |
| **F3** (159) | 3 | 결정적 커널·`BattleState` codec·SHA-256·replay reducer | ⚠️ **P-13a/c/f 동명 구현 지점** + **P-4c 해시 형태가 여기서 확정**. (c) 병존이면 작전층 다이제스트 파생 훅이 필요 |
| **F4** (160) | 4 | `battle_*` 스키마·DML 소유권 | 낮음 |
| **F5** (161) | 5 | ticket/event/snapshot/result 저장소 | ⚠️ P-4a `persistedLogEntryIds[]` ↔ `(battle_id, event_seq)` |
| **F6~F7** (162~163) | 6~7 | 캠페인 lock·handoff·exactly-once 결과 | ⚠️ **(c) 병존 시 작전층 replay 조립 지점**. 여기가 Operation ↔ Battle 경계 |
| **F8~F11** (164~167) | 8~11 | actor·ingress·WebSocket·AI/부관/증원 | 없음 — 안전 |
| **F12** (168) | 12 | **G1 결정적 replay 게이트**(checkpoint hash 100회) | 🔴 **P-15d와 다른 게이트를 만든다.** 현행대로면 P-15d는 미측정으로 남는다 |
| **F13** (169) | 13 | 로컬 스모크·공통 기반 릴리스 게이트 | ⚠️ 공통 기반 "완료" 선언 지점 — P-4 미비준이 여기서 굳는다 |

**차단 판정**: **F0·F1은 지금 착수해도 안전하다.** 두 티켓은 P-4/P-13 어휘를 전혀 소비하지 않는다(선행 증거 게이트 + 모듈 스켈레톤).

**R1(ADR) 없이 진행하면 위험한 최초 지점 = F2(OPENSAM-158)** — 여기서 `BattleTicketV1`과 버전/아티팩트 레지스트리 이름이 동결되고, 그 뒤 F3에서 해시 형태가 굳는다. 즉 **F1 완료 시점까지가 R1 결정의 마감선**이다.

---

## 7. 사람 승인이 필요한 결정 지점

| ID | 결정 | 선택지 | 마감 |
|---|---|---|---|
| **H1** | P-4와 07-30 replay 계약의 관계 확정 | ①(c) 2계층 병존 비준 ②P-4 폐기·07-30 단일화 ③현행 유지(미결) | **F2 착수 전** |
| **H2** | 수전(naval)의 `phases[]` 처리 | ①`NAVAL` 추가 ②battle type으로 분리 ③`phases[]`를 작전층 전용으로 한정 | H1과 동시 |
| **H3** | P-15d 측정 소유자 | ①V2-4A 부활 ②P-15d를 checkpoint-hash로 개정 후 F12 귀속 ③신규 티켓 | H1 종속 |
| **H4** | OPENSAM-24(V2-4A) 처분 | ①재스코프 ②종료 ③현행 유지 | H1 종속 |
| **H5** | OPENSAM-21(Spike B0) 처분 | ①잔여 3종으로 축소 ②종료 ③현행 유지 | H1 종속 |
| **H6** | product-spec 수정 권한 | product-spec은 "제품 사양 정본"(`README.md:19`). §6·§10 수정은 정본 개정 | H1 종속 |
| **H7** | `BattleTopology` 소유 | 어댑터 에픽 발행 시 공유 SPI로 선치 vs 어댑터별 자율 | 어댑터 에픽 발행 전 |
| **H8** | 동결 문서 Q3 종결 | 이 문서의 (a) 판정으로 Q3을 닫을지 | 즉시 가능 |

---

## 8. 조사의 한계 (확인하지 못한 것)

- **GitHub 이슈 미조회.** Jira만 확인했다. `README.md:74`가 언급한 `TICKETS-issued.md` 코드↔Jira↔GitHub 대조표는 이 조사에서 열지 않았다. GitHub 측에 P-4 관련 이슈가 별도로 있을 가능성은 **미확인**.
- **어댑터 3종 스펙 부재 확인.** 야전/공성/수전 스펙은 `plan:1638-1640`이 "후속 에픽"으로 예고만 했고 실제 파일은 `docs/superpowers/specs/`에 없다(디렉터리 목록 확인). 어댑터 스펙이 생기면 `BattleTopology`/`FormationModel` 대응이 바뀔 수 있다.
- **`04-systems-micro.md` 전투 섹션 미정독.** 레인 E가 인용한 범위(`:38,54,67,75,140-,174,186`)는 행정·지리·황실 계약이고 전투 항목은 확인되지 않았다. P-4/P-13에 대한 추가 분해가 그 파일에 있다면 이 판정의 세부가 보강될 수 있다.
- **(c)의 "선언 문장 0건"은 grep 기반 확인**이다. 검색어(`operation`/`작전`/`ReplayEnvelope`/`DeterministicReplayBody`/`phases`/7종)를 벗어난 우회 표현이 있을 가능성은 배제하지 못한다.

---

## 9. 부록 — 사실 정정 2건 (레인 E 동결 문서 대상)

1. **"07-30 스펙은 7종을 한 번도 언급하지 않는다"**(동결 문서 §P-13 / Q3) — **스펙 파일에 한해 참, 07-30 계열 전체로는 거짓.** 같은 커밋 계열의 구현 계획이 `BattleState`·`BattleClock`·`BattleEvent`·`BattleReplay`를 파일명으로 사용한다(`plan:80,82,92,143,505,507,516,770`).
2. **Q1·Q3이 "같은 뿌리"**(동결 문서 Q3 말미) — **근거 구조가 다르다.** Q3은 직접 증거(동명 부활)로 판정 가능하고, Q1은 스코프 불일치 때문에 사람 비준이 필요하다. 묶어 두면 Q3이 불필요하게 Q1의 대기에 걸린다.
