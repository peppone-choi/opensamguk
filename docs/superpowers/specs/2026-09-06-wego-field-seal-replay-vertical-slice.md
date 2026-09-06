# WEGO 야전 봉인 · 결정론 해결 · 리플레이 수직 절편 (Phase 4X-C) 설계 — v1

- Date: 2026-09-06
- Status: DRAFT — 교차 비평 대기(ADR-LITE-049 Phase 4X 규칙: spec → 비평 → 구현 → 게이트). 4X-A(구현 완료)·4X-B(spec v3)의 엔진 규약(메모리 세계 상태 + 행 단위 채널 + `removeGeneral` 즉시 가지치기 + 표마다 DELETE→CREATE→UPDATE + 값 > 0 일 때만 meta 고수위)을 상속한다.
- Scope: 09 아트보드 「명령 봉인」 과 10 아트보드 「리플레이」 를 **기존 `che_출병` 야전 경로**에 붙인다 — 출병 전에 **계획**(태세·퇴각 조건)을 **봉인**하고, 예약 턴이 실행될 때 봉인된 계획이 있으면 phase 기계가 그 조건으로 멈추며, 그 전투의 페이즈별 상태와 정산을 **리플레이**로 남긴다. 같은 seed·입력이면 같은 리플레이(해시 게이트).
- 정합: 07-30 실시간 전투 세션 설계(`2026-07-30-v2-realtime-battle-session-command-replay-design.md`, battle-engine·200ms 틱·WebSocket)와 BATTLE-F0~F13(OPENSAM-156~169)은 **이 절편의 범위가 아니다**. 이 절편은 ADR-LITE-032 의 **작전층 리플레이 계약**(키 `operationId`) 쪽에 서고, 리플레이 키는 `battle_replay.id` + `operation_id?`(4X-B) 다. `phases[]`(ADR-037 작전 단계 축)와 이 절편의 「전투 페이즈」(processWarNG 의 phase 인덱스)는 **다른 축**이다 — 이름을 `battlePhases` 로 구분한다.
- Tickets: OPENSAM-57(#?) replay spine · OPENSAM-59 결정성 게이트 · OPENSAM-173 리플레이 렌더러 · OPENSAM-170 야전 adapter — **모두 닫지 않고 부분 기여 코멘트**(야전 1종, 기존 phase 기계 위, battle-engine 없음). 공성(171)·해전(172)·공통 검증(174) 밖.
- 밖: 실시간 세션·WebSocket·200ms 틱·battle-engine 프로세스, 여러 부대 협동(허저 호위·악진 별동 등 아군 다중 부대 봉인 — 이 절편은 **본인 부대 1개**), 이동·우회·방어 태세(야전 이동은 W2 경로), 추격(엔진에 추격 개념 없음), 공성·해전, 리플레이의 2.5D 렌더(10 아트보드는 페이즈 스크럽 + 로그 + 정산 텍스트로 구현).

## 1. 원칙

1. **계획이 없으면 바이트 동일**: 훅은 봉인된 계획이 있을 때만 결정을 바꾼다. 계획이 없는 `che_출병` 은 draw 순서·로그·flush 산출물이 오늘과 같다(§8 적색 프로브). 리플레이도 **계획이 봉인된 전투에만** 기록한다(모든 전투에 기록하면 계획 없는 경로의 FlushPayload 가 달라진다 — 명시된 트레이드오프; 전면 기록은 §10).
2. **draw 중립**: 계획 판정은 phase 사이에서 `attacker.getCrew()`·`getAtmos()` 같은 **상태 읽기**만으로 한다(RNG 소비 0). 판정이 참일 때만 기존 「공격자 퇴각」 분기(`attacker.tryWound()`/`def.tryWound()` 포함)를 **그대로** 탄다 — 새 분기 없음.
3. **지어낸 수치 없음**: 태세·조건의 임계값은 **플레이어 입력**이다(퇴각 손실 % · 사기 임계). 상수는 상한·하한(10~90, 0~100)뿐이고 `rules.provisional = true` 로 표시.
4. **엔진만 쓴다**: 계획은 인테이크 3종, 리플레이는 예약 턴 실행 중 recorder INSERT 채널. game-api 는 읽기만.

## 2. 도메인 (Flyway `V57__battle_plan_replay.sql`, 세계 범위 — V32 규약)

### battle_plan
| 열 | 뜻 |
|---|---|
| `world_id`, `id INTEGER NOT NULL` — PK, 엔진 할당(`maxBattlePlanId`, 4X-A 할당자 미러) | |
| `general_id` — `FOREIGN KEY (world_id, general_id) REFERENCES general(world_id, id) ON DELETE CASCADE` | 본인 부대(장수 1명) |
| `target_city_id` — `FOREIGN KEY (world_id, target_city_id) REFERENCES city(world_id, id)` | 출병 목표 도시 |
| `stance VARCHAR(16) NOT NULL CHECK (stance IN ('assault','probe'))` | 돌격(기본 = 기존 동작) / 탐색(첫 접촉 페이즈 뒤 퇴각 — 정의된 규칙, 상수 아님) |
| `retreat_loss_pct SMALLINT NULL CHECK (retreat_loss_pct BETWEEN 10 AND 90)` | 「병력 N% 손실 시 퇴각」 — 플레이어 입력 |
| `retreat_morale_below SMALLINT NULL CHECK (retreat_morale_below BETWEEN 0 AND 100)` | 「사기 N 미만이면 퇴각」 — 플레이어 입력(아트보드의 「방어로 전환」 은 공격자에게 없으므로 퇴각으로 정직하게 라벨링) |
| `sealed_at TIMESTAMPTZ NULL`, `sealed_year/month/phase SMALLINT NULL` | 봉인 시각·순. NULL = 초안(수정 가능) |
| `version INTEGER NOT NULL DEFAULT 1` | 저장마다 +1(봉인 뒤 수정 불가) |
| `created_at`, `updated_at` | |
| `UNIQUE (world_id, general_id, target_city_id)` · `INDEX (world_id, general_id)` | 장수 × 목표 도시 하나 |

### battle_replay (INSERT 전용 기록)
| 열 | 뜻 |
|---|---|
| `world_id`, `id INTEGER NOT NULL` — PK, 엔진 할당(`maxBattleReplayId`) | |
| `battle_plan_id INTEGER NULL` — `FOREIGN KEY (world_id, battle_plan_id) REFERENCES battle_plan(world_id, id) ON DELETE SET NULL (battle_plan_id)` | |
| `operation_id INTEGER NULL` | 4X-B 연결(ADR-032 `operationId`). 4X-B 가 먼저면 FK, 아니면 열만(§9) |
| `attacker_general_id INTEGER NULL` — `ON DELETE SET NULL (attacker_general_id)`, `attacker_name VARCHAR(50) NOT NULL`, `attacker_nation_id INTEGER NOT NULL` | 스냅샷(장수가 사라져도 기록 유지) |
| `defender_city_id INTEGER NOT NULL`, `defender_city_name VARCHAR(50) NOT NULL`, `defender_nation_id INTEGER NOT NULL` | |
| `year`, `month`, `phase SMALLINT NOT NULL` | 전투 순 |
| `war_seed CHAR(32) NOT NULL` | `WarSeed.build(...)` 의 hex — 기존 진격 로그가 이미 에코하는 값 |
| `input_hash CHAR(64) NOT NULL` | 정규화 입력(공격자 상태·병종·tech·수비자 후보·도시·계획·year/month)의 SHA-256 |
| `replay_hash CHAR(64) NOT NULL` | `battle_phases_json` + 정산의 SHA-256 — 결정성 게이트의 대상 |
| `schema_version SMALLINT NOT NULL DEFAULT 1` | 페이즈 JSON 스키마 |
| `battle_phases_json TEXT NOT NULL` | 페이즈 배열(§5) — jsonb 아님(append-only 기록, 조회 필터 없음) |
| `attacker_crew_before/after`, `attacker_dead`, `defender_dead`, `rice_used INTEGER NOT NULL` | 정산 |
| `result VARCHAR(16) NOT NULL CHECK (result IN ('retreat','repelled','defenders_down','conquered'))` | 공격자 퇴각 / 수비 성공(페이즈 소진) / 수비 전멸(점령 아님) / 점령 |
| `plan_stop VARCHAR(24) NULL CHECK (plan_stop IN ('probe','loss_pct','morale'))` | 계획 조건이 멈춘 경우 어느 조건이었나 |
| `created_at` | |
| `INDEX (world_id, attacker_nation_id, id)`, `INDEX (world_id, defender_nation_id, id)`, `INDEX (world_id, attacker_general_id)` | 감찰부 목록 |

등록: `TruncateContract` 두 표, `V32WorldScopeCompletionMigrationTest.postV32WorldTables` 두 표.

### 잠정 상수 (`opensamguk.logic.war.plan.BattlePlanRules`)
| 이름 | 값 | 쓰임 |
|---|---|---|
| RETREAT_LOSS_PCT_MIN / MAX | 10 / 90 | 입력 범위(플레이어가 값을 고른다) |
| MAX_PLANS_PER_GENERAL | 6 | 초안 상한(도시별 1개) |
| REPLAY_SCHEMA_VERSION | 1 | `battle_phases_json` 스키마 |
| STANCES | {assault, probe} | 돌격/탐색 |

## 3. 엔진 상태와 flush

- `TurnWorldModel`: `data class BattlePlan(id, generalId, targetCityId, stance, retreatLossPct: Int?, retreatMoraleBelow: Int?, sealedAt: Instant?, sealedDate: GameDate?, version)`. 리플레이는 세계 상태가 아니라 **recorder INSERT 채널**(`recordBattleReplayInsert(columns)`, board 미러) — 조회는 DB.
- `WorldSnapshot.battlePlans` + `WorldSnapshotLoader` 적재. `InMemoryTurnWorld` map + dirty/created/deleted + `allocateBattlePlanId`(값 > 0 일 때만 meta). `removeGeneral` 이 그 장수의 계획을 즉시 가지치기(4X-A N2).
- `DirtyState`/`FlushPayload`: `createdBattlePlans, updatedBattlePlans, deletedBattlePlanIds`, `battleReplayInserts`. `JdbcFlushExecutor` **8i**(4X-A 8g·4X-B 8h 뒤): `battlePlanDelete → battlePlanCreate → battlePlanUpdate → battleReplayInsertMany`(리플레이는 계획 뒤 — `battle_plan_id` FK). 모든 채널 `isNotEmpty()` 가드.
- 리플레이 id 는 recorder 선할당(`battleReplayIdAllocator`, `diplomacyLetterIdAllocator` 선례; DB-seed max+1) — 결과 로그에 「리플레이 #id」 를 박기 위해.

## 4. 명령(인테이크, 즉시 실행) — `TurnDaemonCommand` 3종 + `CommandWireMapper` + 디스패처 → `BattlePlanHandler`

공통 게이트: ① 장수 없음 → ② 접속 제한 → ③ 입력 「올바르지 않은 입력입니다.」 → ④ 상태.

| 코드 | 인자 | ③ 입력 | ④ 상태 게이트 순서 | 효과 |
|---|---|---|---|---|
| `battlePlanSave` | targetCityId, stance, retreatLossPct?, retreatMoraleBelow? | stance ∉ 2종 / pct ∉ [MIN,MAX] / morale ∉ [0,100] / 정수 | 1 도시 없음 「목표를 찾을 수 없습니다.」 → 2 아군 도시 「아군 도시입니다.」 → 3 같은 (장수, 도시) 계획이 **봉인됨** 「봉인된 계획입니다.」 → 4 새 계획인데 상한 「계획이 가득 찼습니다.」 | 없으면 `createBattlePlan(version 1)`, 있으면 `updateBattlePlan(version+1)`; 결과 `id` |
| `battlePlanSeal` | planId | 정수 | 1 내 계획 아님 「계획이 없습니다.」 → 2 이미 봉인 「봉인된 계획입니다.」 | `sealedAt = now, sealedDate = 현재 순` UPDATE; 개인 기록 「<Y>{도시}</> 출병 계획을 봉인했습니다.」 |
| `battlePlanDelete` | planId | 정수 | 1 내 계획 아님 → 2 봉인됨 「봉인된 계획입니다.」 | `removeBattlePlan` |

봉인 뒤 수정은 인테이크가 「봉인된 계획입니다.」 로 거부한다. game-api `POST /api/command/battlePlanSave` 는 인테이크 202 를 그대로 돌려주고(계약 불변), **읽기 API** `GET /api/my-battle-plans` 가 `sealed: true` 를 주어 UI 가 폼을 잠근다. HTTP 409 는 인테이크 계약 밖이므로 쓰지 않는다(계획 문구 「봉인 뒤 409」 는 「인테이크 거부 사유」 로 고친다 — §9).

결과 타입 `BattlePlanActionResult(type, ok, generalId, reason?, id?)`(4X-A 와 같은 꼴, sealed + 직렬화기 왕복 핀).

## 5. 해결 훅과 리플레이 기록 (예약 턴 `che_출병` 실행 시)

- **주입 경로**: 엔진 `BattleCommandContextBuilder` 가 `ProcessWarEnv` 에 `sealedPlans: Map<Int /*targetCityId*/, SealedBattlePlan>`(그 장수의 봉인된 계획, 실행 순 **이전**에 봉인된 것만 — 「봉인 마감 = 해결 직전 순」)과 `replaySink: ((BattleReplayDraft) -> Unit)?` 를 싣는다. `CheChulbyeong.resolve` 는 `defenderCityId` 를 고른 뒤 `env.sealedPlans[defenderCityId]` 를 찾아 훅을 감싼다: `hooks = ReplayRecordingHooks(PlannedWarBattleHooks(ProductionWarBattleHooks(...), plan), draft)`. 계획이 없으면 **감싸지 않는다**(기존 객체 그대로 — 바이트 동일의 구조적 증거).
- **`WarBattleHooks` 확장(기본값 = 오늘 동작)**: `fun plannedStop(attacker: WarUnitGeneral, phaseIndex: Int, contactMade: Boolean): PlanStop? = null`. `processWarNG` 는 phase 루프 **맨 위**(`while` 조건 통과 직후, `defender == null` 분기 앞)에서 `hooks.plannedStop(...)` 을 부르고 non-null 이면 기존 「공격자 퇴각」 블록과 **같은 코드**를 탄다(`onBattleResultLog`·`addLose/addWin`·`tryWound` 2회·`onRetreatLog` — 공용 함수로 추출해 두 자리에서 호출; 계획 없는 경로의 호출 순서는 바뀌지 않는다). null 이면 오늘과 같다.
- **`PlannedWarBattleHooks` 판정(draw 0)**: `probe` 는 접촉이 있었던 페이즈가 1개 지나면 `PlanStop.PROBE`; `retreatLossPct` 는 `attacker.getCrew() <= crewBefore × (100 − pct) / 100` 이면 `LOSS_PCT`; `retreatMoraleBelow` 는 `attacker.getAtmos() < value` 이면 `MORALE`. 여러 조건이 동시면 표기 순(probe → loss → morale). `crewBefore` 는 processWar 진입 시 공격자 crew.
- **`ReplayRecordingHooks`**: 위임 + 기록(draw 0). `onAdvanceLog` → 시작, `onContactLog` → 접촉 페이즈 표시, `onPhaseLog(attacker, def, deadA, deadD)` → `battlePhases += {index, defender: name/city, attackerCrewAfter, defenderCrewAfter, deadAttacker, deadDefender, contact}`, `onRetreatLog`/`onDefenderDownLog`/`onSupplyRout`/점령(`ProcessWarResult.conquerCity`) → `result`·`planStop`. `BattleReplayDraft` 는 `CheChulbyeong` 이 `processWar` 반환 뒤 정산(`attacker.getCrew()` 전후, `deadPerson` 합, `rice` 차)을 채워 `env.replaySink` 로 넘기고, 엔진(`ReservedTurnHandler` 의 출병 후처리)이 `recorder.recordBattleReplayInsert(columns)` 를 기록한다. `input_hash`·`replay_hash` 는 logic 순수 함수(`BattleReplayCodec.encode/hash`, 정렬된 키·정수만).
- **로그**: 기존 진격/대결/전투결과 로그 불변. 계획이 있을 때만 개인 기록 한 줄 추가 「리플레이 #{id} 가 기록되었습니다.」(`scope="general", category="action"`).
- **결정성 게이트**: 같은 `warSeed`·입력·계획으로 `processWar` 를 두 번 돌리면 `replay_hash` 가 같다(§8). 다른 계획(예: pct 10 vs 90)이면 `input_hash` 가 다르다.

`battle_phases_json` 스키마 v1: `{"v":1,"phases":[{"i":1,"def":"화웅","defKind":"general|city","contact":true,"deadA":1900,"deadD":600,"crewA":10100,"crewD":8400}],"stop":{"kind":"loss_pct"|"morale"|"probe"|null,"atPhase":3}}` — 정수만, 키 정렬 고정.

## 6. 읽기 API (game-api) — `GameApiSecurityConfig` `.authenticated()` 등록

- `GET /api/my-battle-plans`: 401 익명 · 200 `{generalId, plans:[{id, targetCityId, targetCityName, stance, stanceLabel, retreatLossPct, retreatMoraleBelow, sealed, sealedAt, version}], rules:{stances:[{value,label,description}], retreatLossPctMin, retreatLossPctMax, maxPlansPerGeneral, provisional:true}}`.
- `GET /api/battles/replays?scope=nation|mine`(감찰부 목록): 401 익명 · 200 `[{id, year, month, phase, attackerName, attackerNationId, defenderCityName, defenderNationId, result, resultLabel, attackerDead, defenderDead, hasPlan}]` — `nation` 은 내 국가가 공격자 **또는** 수비자인 리플레이, 재야는 `mine` 만.
- `GET /api/battles/replays/{id}`: 401 익명 · 403 (공격 국가·수비 국가 어느 쪽도 아님; 본인이면 200) · 200 위 + `battlePhases`, `settlement:{attackerCrewBefore, attackerCrewAfter, attackerDead, defenderDead, riceUsed, conquered}`, `plan:{stance, retreatLossPct, retreatMoraleBelow, planStop} | null`, `seed:{warSeed, inputHash, replayHash, schemaVersion}`, `operationId`.
- 예상 범위(09 「예상 (결정론 시뮬)」): 기존 `POST /api/simulate-battle` 을 **그대로** 쓴다(repeatCnt 로 Monte-Carlo, 결과는 killed/dead 의 min/avg/max). 아트보드의 「우세 41% · 대등 37% · 열세 22%」 는 그 API 가 per-repeat 결과를 주지 않으므로 **그리지 않는다**(min/avg/max 세 값으로 대체).

## 7. UI

- **09 명령 봉인 `/game/battle-plan?city={id}`**(군사 부서 아래 새 화면; 기존 라벨 변경 없음): 좌 「아군」 = 본인 부대 카드 1장(`Portrait card`, 병종·병력·훈련·사기·피로는 `/api/my-page` 값) — 다중 부대는 「이 절편은 본인 부대만」 점선 카드로 남긴다(숨기지 않음). 중앙 「적군 · 정찰 정보」 = 목표 도시(`/api/city/{id}` 기존)·수비 장수 목록(시야 규칙은 기존 API 그대로). 우 「명령」: 태세(돌격·탐색 활성 / 전진·방어·우회 는 disabled + 「이 절편에서는 지원하지 않습니다」), 조건 2개(손실 % 슬라이더 10~90 · 사기 임계 0~100 · 「합류 전 추격 금지」 는 disabled + 「엔진에 추격이 없습니다」), 「예상」 = simulate-battle 결과 min/avg/max, 「봉인」 버튼(→ `battlePlanSeal`, 확인 대화상자 「봉인 뒤에는 바꿀 수 없습니다」). 봉인되면 폼 전체 readonly + 「봉인됨 · {순}」 칩. 「봉인까지 01:48:20」 카운트다운 = 다음 내 턴 시각(`/api/my-page`의 turnTime) — 실측값.
- 작전실 명령 목록: `che_출병` 예약 슬롯에 「봉인」 링크(→ 위 화면, 대상 도시 = 예약 인자). 봉인된 계획이 있으면 `Slot` 상태 칩 「봉인됨」.
- **10 리플레이 `/game/battle-replay/{id}`**: 상단 對 카드(공격 장수 `hero`·수비 도시/장수), 페이즈 스크럽(‹ › n/총, 0.5×/1×/2× 는 자동 넘김 간격), 페이즈별 텍스트 로그(P1…Pn: 기존 `onPhaseLog` 수치), 조건 발동 표시, 정산(사상·군량·점령·`operationId` 있으면 4X-B 작전 링크), 「같은 seed·입력이면 같은 결과를 재생합니다」 + `replayHash` 앞 8자, 「감찰부 기록」 링크. 2.5D 렌더 없음(§10).
- **감찰부** `battle-center`: 「최근 전투」 표에 리플레이 열(있으면 링크 「리플레이」, 없으면 「기록 없음(계획 미봉인)」 점선 텍스트). **회의실 첨부 카드**는 밖(§10).

## 8. 테스트·게이트

- logic `BattlePlanRulesTest`: 게이트 순서 표(3 명령), 입력 범위, `plannedStop` 판정 표(probe/loss/morale/동시), `BattleReplayCodec` 결정성(같은 입력 → 같은 해시, 계획 차이 → input_hash 차이), 정수만·키 정렬.
- logic `ProcessWarPlanHookTest`(**적색 프로브**): 같은 fixture(공격자·수비자·도시·seed)로 `processWar` 를 (a) `hooks` 그대로, (b) `PlannedWarBattleHooks(plan=null)` 로 감싼 채 돌려 `ProcessWarResult`(crew/dead/phase/conquer)와 recording 로그 순서가 **deep-equal**(계획 없음 = 바이트 동일). 같은 테스트가 pct 10 계획으로 돌려 **달라짐**(더 이른 퇴각)을 단언. 두 번 돌려 `replay_hash` 동일.
- logic 골든 274 json 회귀(`processWarNG` 시그니처 기본값 유지 → 골든 무접촉).
- engine `BattlePlanIntakeTest`: 3 명령 채널·봉인 뒤 거부·툼스톤(장수 삭제)·같은 틱 저장→봉인. `ReservedTurnHandler` 출병 후처리: `replaySink` → `recordBattleReplayInsert` 열 + 개인 기록 1줄, 계획 없으면 채널 0.
- infra `BattlePlanReplayFlushIT`(PG16): V57 DDL · 8i 순서 · `SET NULL (battle_plan_id)`/`(attacker_general_id)` · 리플레이 INSERT.
- game-api `BattleReplayReadControllerTest`: 401 / 403 타국 / 200 공격국·수비국·본인 / 목록 scope.
- vitest: 09 폼(disabled 사유·봉인 후 readonly)·10 스크럽·감찰부 리플레이 열·작전실 봉인 링크.
- e2e 1건(계획 `web/game/e2e`): 저장 → 봉인 → (로컬 엔진에서 출병 턴 실행) → 리플레이 열기 — 로컬 스택에서 실행 순을 앞당길 수 없으면 **UNKNOWN** 으로 남기고 단위·IT 게이트로 대체(§10).

## 9. 마이그레이션·순서

V57 = 이 절편. 4X-B(V56)가 먼저면 `battle_replay.operation_id` 에 FK(`ON DELETE SET NULL (operation_id)`), 아니면 열만 두고 FK 는 4X-B 가 자기 번호에서 더한다. 계획 문서 4X-C 블록의 「`battle_plan`(battle_id·stance 돌격/전진/방어/우회·target·conditions[…]·sealed_at·version)」 을 이 스펙(장수×도시 키, stance 2종, 조건 2종)으로, 「봉인 뒤 409」 를 「인테이크 거부 사유」 로 고친다(이 커밋).

## 10. UNKNOWN · 밖

- 모든 전투 리플레이 기록(계획 없는 전투 포함)은 FlushPayload 바이트 동일 원칙과 충돌한다 — 원한다면 「전면 기록」 을 별도 ADR 로 결정(사용자).
- 다중 부대(호위·별동) 봉인·합류·추격은 엔진에 대응물이 없다(W2/W3·BATTLE-F10~11).
- 회의실·커뮤니티 리플레이 첨부 카드: `board_post.replay_id` 열이 없어 밖(4X-B 의 `operation_id` 선례로 다음 번호에서).
- e2e(봉인→해결→리플레이)는 로컬 엔진 턴 실행 시각에 묶여 있어 실행 가능성 UNKNOWN.
- 10 아트보드의 2.5D 전장 렌더는 밖(OPENSAM-173 의 HUD/렌더러).
