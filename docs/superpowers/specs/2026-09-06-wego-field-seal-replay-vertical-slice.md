# 야전 출병 계획 봉인 · 결정론 해결 · 리플레이 수직 절편 (Phase 4X-C) 설계 — v4

- Date: 2026-09-06
- Status: REVISED v4 — 1차(F1~F7·S1~S16)·2차(N1~N4·R1~R14)·3차(v3 재판정: fix-required P1~P2 · should-fix M1~M7, 같은 판정 파일 「V3 통합 재판정」)를 전부 반영. 통합 재판정 대기
- Scope: 09 아트보드 「명령 봉인」 과 10 아트보드 「리플레이」 를 **기존 `che_출병` 야전 경로**에 붙인다 — 출병 전에 **계획**(태세·퇴각 조건)을 **봉인**하고, 예약 턴이 실행될 때 봉인된 계획이 있으면 phase 기계가 그 조건으로 멈추며, 그 전투의 페이즈별 상태와 정산을 **리플레이**로 남긴다. 같은 seed·입력이면 같은 리플레이(해시 게이트).
- 정합: 07-30 실시간 전투 세션 설계(`2026-07-30-v2-realtime-battle-session-command-replay-design.md`, battle-engine·200ms 틱·WebSocket)와 BATTLE-F0~F13(OPENSAM-156~169)은 **이 절편의 범위가 아니다**. 이 절편은 ADR-LITE-032 의 **작전층 리플레이 계약**(키 `operationId`) 쪽에 서고, 리플레이 키는 `battle_replay.id` + `operation_id?`(4X-B) 다. `phases[]`(ADR-037 작전 단계 축)와 이 절편의 「전투 페이즈」(processWarNG 의 phase 인덱스)는 **다른 축**이다 — 이름을 `battlePhases` 로 구분한다.
- Tickets: OPENSAM-57(#?) replay spine · OPENSAM-59 결정성 게이트 · OPENSAM-173 리플레이 렌더러 · OPENSAM-170 야전 adapter — **모두 닫지 않고 부분 기여 코멘트**(야전 1종, 기존 phase 기계 위, battle-engine 없음). 공성(171)·해전(172)·공통 검증(174) 밖.
- 밖: 실시간 세션·WebSocket·200ms 틱·battle-engine 프로세스, 여러 부대 협동(허저 호위·악진 별동 등 아군 다중 부대 봉인 — 이 절편은 **본인 부대 1개**), 이동·우회·방어 태세(야전 이동은 W2 경로), 추격(엔진에 추격 개념 없음), 공성·해전, 리플레이의 2.5D 렌더(10 아트보드는 페이즈 스크럽 + 로그 + 정산 텍스트로 구현).

## 0. 비평 반영표

| 항목 | 결정 |
|---|---|
| **F1 plannedStop 자리** | 루프 맨 위가 아니라 **`addPhase()` 뒤 · 두 `continueWar()` 뒤**(접촉한 `def` 가 바인딩된 자리, draw 없음 — M1). 계획 정지가 참이면 **기존 `attackerCont.canContinue == false` 분기와 같은 함수**를 탄다(공용 함수로 추출; 자연 퇴각이 우선). 미접촉 수비자에게 `addWin`/`tryWound` 를 주는 경로는 없다. 판정 순서의 정본은 §0 N1·M1 행과 §5 (1)~(3). |
| **F2 주입 경로** | `ProcessWarEnv` 가 아니라 **`BattleCommandContext`**(엔진 `BattleCommandContextBuilder` 가 만든다)에 `sealedPlans: Map<Int, SealedBattlePlan>`(키 = `finalTargetCityId`)과 `executingDate` 를 싣고, `CheChulbyeong` 은 `lastBattleResult` 처럼 **`lastReplayDraft`** 를 노출한다. 엔진 `ReservedTurnHandler` 가 출병 후처리에서 그것을 읽어 `recorder.recordBattleReplayInsert` 한다. |
| **F3 같은 턴 「봉인 출병 → 사망」** | `ChangeRecorder.markGeneralDeleted` 가 pending `battleReplayInserts` 의 `attacker_general_id`·`battle_plan_id` 를 NULL 로 바꾼다(4X-B P2 와 같은 자리의 프룬). 이름 스냅샷 열이 기록을 지킨다. 적색 프로브: 인테이크·IT. |
| **F4 id 할당자** | `diplomacyLetterIdAllocator` 는 시드 없는 카운터라 선례가 아니다 → **`messageIdAllocator`/`auctionIdAllocator` 방식**(`DaemonLoopConfig` 에서 `findMaxId()` DB-seed). `battle_plan.id` 는 세계 상태 고수위(`maxBattlePlanId`, 4X-A 방식), `battle_replay.id` 는 recorder 선할당(DB-seed). |
| **F5 봉인 마감** | 「해결 직전 순」 = **`sealedDate <= executingDate`**(같은 순 봉인도 적용 — 인테이크가 드레인보다 앞이라 같은 틱 봉인이 그 턴에 반영된다). UI 카운트다운 「다음 내 턴 시각」 과 일치. |
| **F6 계획 키** | 계획은 **예약 출병의 `finalTargetCityId`** 로 찾는다(우회 도중 `rng.choice` 로 고른 경유 도시가 목표와 달라도 적용). 조건은 「목표 도시로의 출병」 에 걸린 것이므로 경유 전투에도 같은 계획을 적용한다 — 문서화. |
| **F7 봉인 뒤 수명** | 봉인 계획은 **소비된다**: 출병 해결 시 `resolvedAt`(순) UPDATE + `battle_replay` 에 계획 스냅샷 열(stance·pct·morale) 기록. 소비된 계획은 (장수, 도시) 키를 놓아 다시 저장할 수 있다(UNIQUE 는 `resolved_at IS NULL` 부분 인덱스). 미소비 봉인 계획의 삭제·수정은 여전히 거부(봉인 의미). |
| **N1 정지 순서** | 계획 정지는 **수비자가 서 있을 때만**: `addPhase()` 뒤 `val natural = attacker.continueWar()`; `!natural.canContinue` → 자연 퇴각(오늘 그대로). 아니면 `val defCont = def.continueWar()`(draw 없음, 오늘 `:168` 을 앞으로 옮겨 한 번만 부르고 재사용)와 `val stop = hooks.plannedStop(attacker, def, phase)`(**한 번만** 평가, M1)를 본다. **`fell = !defCont.canContinue && (def !is WarUnitCity || def.isSiege())`**(오늘 `siegeWin` 술어 — 비공성 성의 「한 대 맞고 재정비」 는 무너짐이 아니다, M1). `!fell && stop != null` 일 때만 공용 퇴각 함수(비용 = `addLose`·`tryWound`, S4). `!defCont.canContinue` 면 오늘의 분기를 그대로 타고(점령·재정비·`onDefenderDownLog` 유지), 그 분기 끝의 `phase >= maxPhase` break 를 `|| stop != null` 로 넓혀 다음 수비자로 넘어가지 않고 멈춘다(추가 draw 없음). `result` 는 §5 의 **단일 규칙**(P1)으로만 정한다. |
| **N2 이름** | logic `General`/`City` 에 `name` 이 없다. 리플레이 초안은 **id 만** 싣고(`attackerId`, 페이즈별 `defId`+`defKind`, `cityId`), 엔진 `ReservedTurnHandler` 출병 후처리가 `world.getGeneralById/getCityById` 로 이름을 채워 INSERT 열과 `battle_phases_json` 을 만든다(해시는 그 최종 바이트). |
| **N3 계획 문서** | `docs/superpowers/plans/…§4X-C` 블록을 이 스펙으로 **이 커밋에서** 실제로 고친다(diff 로 확인). |
| **N4 `operation_id` FK 구멍** | `battle_replay.operation_id` FK 는 `operation` 이 국가 CASCADE 라 같은 틱 국가 소멸에서 터진다 → `ChangeRecorder.markNationDeleted` 가 pending `battleReplayInserts.operation_id` 도 NULL 로(4X-B 의 `boardPostInserts` 프룬과 같은 자리). `attacker_nation_id`·`defender_nation_id`·`defender_city_id` 는 **FK 없는 스냅샷**임을 명시. |
| R1~R14 | 아래 본문에 반영: 「기존 진격 로그 에코」 문구·`maxPlansPerGeneral` 삭제(R) / `input_hash` 에 아이템·부상·숙련·explevel·`ProcessWarEnv` 값 포함 / 수비 병력은 `def.getHP()` / `GameDate` 비교는 `OperationRules.absoluteTurn` 재사용 / `battlePlanDelete` 는 초안만 / 로더는 `resolved_year IS NULL` 만 적재 / 부분 UNIQUE 인덱스 DDL 명시 / S10 은 「목록 첫 수비자」(클라이언트가 `extractBattleOrder` 를 재현하지 않는다) / AI 요격으로 상대가 바뀐 전투는 계획 미적용·리플레이 없음 / `operation_id` 채움 규칙 / 훅 감싸는 자리 `CheChulbyeong.defaultProcessWar` / `findMaxId` world-scoped. |
| S1 로그 | 프로덕션 `ProductionWarBattleHooks` 는 `on*Log` 를 구현하지 않는다 — 「기존 진격 로그 에코」 문구 삭제. `war_seed` 는 리플레이 행에만 남는다. |
| S2 카탈로그 | `HotColdCatalog.snapshotAccesses` 에 `loadBattlePlans` 등록(4X-A `76d48c8f` 선례). |
| S3 계획 드리프트 | 계획 §4X-C 의 `battle_plan.operation_id`·태세 4종·조건 3종·409 를 이 스펙으로 고친다(§9). |
| S4 퇴각 비용 | 계획 퇴각도 자연 퇴각과 같은 분기라 `deathnum+1`·`tryWound` draw 가 든다 — UI 도움말에 「퇴각은 부상 판정을 받는다」 명시. |
| S5 input_hash | 내용 열거(§5)·정규화: Double 은 `toBits()`, 맵은 키 정렬, 리스트 순서 유지. 「정수만」 삭제. |
| S6 war_seed | 순이 없는 값이라 전투 키가 아니다 — `battle_replay.id` 가 키, `war_seed` 는 재현 입력. |
| S7 TEXT | `battle_phases_json TEXT` 의 이유 = **저장 바이트 그대로 해시**(jsonb 는 정규화·키 재정렬로 바이트가 바뀐다). 조회 필터 없음. |
| S8 단계 라벨 | 4X-B 8h 가 커밋됐으므로 **8i**(R11). |
| S9 sealed_at | 핸들러 `nowProvider`(4X-A 선례). |
| S10 simulate-battle | 예상 = `POST /api/simulate-battle` 에 `defenderGeneralId` 가 필요하다 — 09 화면은 목표 도시 상세 API 가 주는 수비 장수 목록의 **첫 항목**(클라이언트가 `extractBattleOrder` 를 재현하지 않는다, R8)을 넣고 「목록 첫 수비자 1인 기준」 이라고 적는다. |
| S11 슬롯 링크 | `slot.action == "che_출병"` 이고 `typeof slot.arg.destCityID === 'number'` 일 때만 「봉인」 링크(R14). |
| S12 이름 | 「WEGO 봉인」 → 「출병 계획 봉인(공격자)」. 양측 동시 해결은 로드맵·07-30 의 미래 범위. |
| S13 상수 | `MAX_PLANS_PER_GENERAL` 삭제 — 상한은 자연 상한(도시 수)뿐. |
| S14 적색 프로브 | (b) 는 `PlannedWarBattleHooks(plan=null)` 이 아니라 **`WarBattleHooks.NOOP` 그대로 vs 새 기본 메서드가 붙은 인터페이스** 로 훅 호출 순서를 기록하는 `ProcessWarNGOrderTest` 확장(`onBattleResultLog` 포함)이 핀. |
| S15 이름·사망자 | ~~`WarUnitGeneral.getGeneral().name`·`WarUnitCity.state.city.name`~~ — logic 엔티티에 `name` 이 없어 **N2 로 대체**(초안은 id, 엔진 후처리가 이름). 사망자는 `onPhaseLog` 의 `deadAttacker/deadDefender` 누적으로 센다(`deadPerson` private). |
| S16 흔적 | 계획이 있을 때 개인 기록 1줄 + 리플레이가 유일한 흔적임을 §7 에 적는다(전투 로그는 프로덕션에 없다 — S1). |

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
| `sealed_at TIMESTAMPTZ NULL`, `sealed_year/month/phase SMALLINT NULL` | 봉인 시각(`nowProvider`)·순. NULL = 초안(수정 가능) |
| `resolved_year/month/phase SMALLINT NULL` | 출병 해결로 **소비된** 순(F7). NULL = 미소비 |
| `version INTEGER NOT NULL DEFAULT 1` | 저장마다 +1(봉인 뒤 수정 불가) |
| `created_at`, `updated_at` | |
| `CREATE UNIQUE INDEX battle_plan_open_uk ON battle_plan (world_id, general_id, target_city_id) WHERE resolved_year IS NULL` · `INDEX (world_id, general_id)` | 미소비 계획은 장수 × 목표 도시 하나(F7). 로더는 `resolved_year IS NULL` 행만 적재(소비된 행은 기록으로만 남고 세계 상태에 없다) |

### battle_replay (INSERT 전용 기록)
| 열 | 뜻 |
|---|---|
| `world_id`, `id INTEGER NOT NULL` — PK, 엔진 할당(`maxBattleReplayId`) | |
| `battle_plan_id INTEGER NULL` — `FOREIGN KEY (world_id, battle_plan_id) REFERENCES battle_plan(world_id, id) ON DELETE SET NULL (battle_plan_id)` | 계획 스냅샷 열 `plan_stance`·`plan_retreat_loss_pct`·`plan_retreat_morale_below` 도 함께(F7) |
| `operation_id INTEGER NULL` — `FOREIGN KEY (world_id, operation_id) REFERENCES operation(world_id, id) ON DELETE SET NULL (operation_id)` | 4X-B 연결(ADR-032 `operationId`). 채움 규칙(R10): 해결 후처리 시점에 공격자가 참여 중인 `operation_unit` 의 작전 중 `target_city_id == finalTargetCityId`·status ∈ {declared, active} 인 첫 작전(id 오름차순), 없으면 NULL. 같은 틱 국가 소멸은 `markNationDeleted` 가 pending INSERT 의 값을 NULL 로(N4) |
| `attacker_general_id INTEGER NULL` — `ON DELETE SET NULL (attacker_general_id)`, `attacker_name VARCHAR(50) NOT NULL`, `attacker_nation_id INTEGER NOT NULL`(FK 없음) | 스냅샷(장수가 사라져도 기록 유지). 같은 턴 사망은 `markGeneralDeleted` 가 pending INSERT 의 id 를 NULL 로(F3) |
| `defender_city_id INTEGER NOT NULL`, `defender_city_name VARCHAR(50) NOT NULL`, `defender_nation_id INTEGER NOT NULL` | FK 없는 스냅샷(N4) — 도시가 사라지는 경로는 없지만 국가는 사라진다 |
| `year`, `month`, `phase SMALLINT NOT NULL` | 전투 순 |
| `war_seed CHAR(32) NOT NULL` | `WarSeed.build(...)` 의 hex — 재현 입력(전투 키 아님, S6) |
| `input_hash CHAR(64) NOT NULL` | 정규화 입력(공격자 상태·병종·tech·수비자 후보·도시·계획·year/month)의 SHA-256 |
| `replay_hash CHAR(64) NOT NULL` | `battle_phases_json` + 정산의 SHA-256 — 결정성 게이트의 대상 |
| `schema_version SMALLINT NOT NULL DEFAULT 1` | 페이즈 JSON 스키마 |
| `battle_phases_json TEXT NOT NULL` | 페이즈 배열(§5) — **저장 바이트 그대로 해시**하려고 TEXT(jsonb 는 정규화로 바이트가 바뀐다, S7). 조회 필터 없음 |
| `attacker_crew_before/after`, `attacker_dead`, `defender_dead`, `rice_used INTEGER NOT NULL` | 정산 |
| `result VARCHAR(16) NOT NULL CHECK (result IN ('retreat','repelled','defenders_down','conquered'))` | §5 단일 규칙(P1): 점령 / 공격자 퇴각(자연·계획) / 마지막 페이즈에 마지막 상대가 무너졌으나 미점령 / 페이즈 소진(마지막 상대 생존 — 가장 흔한 결말). 라벨 「점령」·「퇴각」·「수비 격파 · 미점령」·「수비 성공」 |
| `plan_stop VARCHAR(24) NULL CHECK (plan_stop IN ('probe','loss_pct','morale'))` | 계획 조건이 멈춘 경우 어느 조건이었나 |
| `created_at` | |
| `INDEX (world_id, attacker_nation_id, id)`, `INDEX (world_id, defender_nation_id, id)`, `INDEX (world_id, attacker_general_id)` | 감찰부 목록 |

등록: `TruncateContract` 두 표, `V32WorldScopeCompletionMigrationTest.postV32WorldTables` 두 표.

### 잠정 상수 (`opensamguk.logic.war.plan.BattlePlanRules`)
| 이름 | 값 | 쓰임 |
|---|---|---|
| RETREAT_LOSS_PCT_MIN / MAX | 10 / 90 | 입력 범위(플레이어가 값을 고른다) |
| REPLAY_SCHEMA_VERSION | 1 | `battle_phases_json` 스키마 |
| STANCES | {assault, probe} | 돌격/탐색 |

상한 상수는 없다(S13) — 계획 수의 자연 상한은 도시 수.

## 3. 엔진 상태와 flush

- `TurnWorldModel`: `data class BattlePlan(id, generalId, targetCityId, stance, retreatLossPct: Int?, retreatMoraleBelow: Int?, sealedAt: Instant?, sealedDate: GameDate?, resolvedDate: GameDate?, version)`. 리플레이는 세계 상태가 아니라 **recorder INSERT 채널**(`recordBattleReplayInsert(columns)`, board 미러) — 조회는 DB.
- `WorldSnapshot.battlePlans` + `WorldSnapshotLoader.loadBattlePlans`(HotColdCatalog 등록, S2). `InMemoryTurnWorld` map + dirty/created/deleted + `allocateBattlePlanId`(값 > 0 일 때만 meta; 4X-A 5곳 미러). `removeGeneral` 이 그 장수의 계획을 즉시 가지치기(4X-A N2). `ChangeRecorder.markGeneralDeleted` 는 pending `battleReplayInserts` 의 `attacker_general_id`·`battle_plan_id` 를, `markNationDeleted` 는 `operation_id` 를 NULL 로(F3·N4). `sealedDate <= executingDate` 같은 순 비교는 4X-B `OperationRules.absoluteTurn` 을 재사용한다.
- `DirtyState`/`FlushPayload`: `createdBattlePlans, updatedBattlePlans, deletedBattlePlanIds`, `battleReplayInserts`. `JdbcFlushExecutor` **8i**(4X-A 8g·4X-B 8h 뒤): `battlePlanDelete → battlePlanCreate → battlePlanUpdate → battleReplayInsertMany`(리플레이는 계획 뒤 — `battle_plan_id` FK). 모든 채널 `isNotEmpty()` 가드.
- 리플레이 id 는 recorder 선할당 `battleReplayIdAllocator` — **`messageIdAllocator`/`auctionIdAllocator` 방식**으로 `DaemonLoopConfig` 가 world-scoped `findMaxId(worldId)` DB-seed 를 주입한다(F4; `diplomacyLetterIdAllocator` 는 시드 없는 카운터라 선례가 아니다). 결과 로그에 「리플레이 #id」 를 박기 위해.

## 4. 명령(인테이크, 즉시 실행) — `TurnDaemonCommand` 3종 + `CommandWireMapper` + 디스패처 → `BattlePlanHandler`

공통 게이트: ① 장수 없음 → ② 접속 제한 → ③ 입력 「올바르지 않은 입력입니다.」 → ④ 상태.

| 코드 | 인자 | ③ 입력 | ④ 상태 게이트 순서 | 효과 |
|---|---|---|---|---|
| `battlePlanSave` | targetCityId, stance, retreatLossPct?, retreatMoraleBelow? | stance ∉ 2종 / pct ∉ [MIN,MAX] / morale ∉ [0,100] / 정수 | 1 도시 없음 「목표를 찾을 수 없습니다.」 → 2 아군 도시 「아군 도시입니다.」 → 3 같은 (장수, 도시) 의 **미소비** 계획이 봉인됨 「봉인된 계획입니다.」 | 미소비 계획이 없으면 `createBattlePlan(version 1)`, 초안이면 `updateBattlePlan(version+1)`(소비된 계획은 기록으로 남고 새 행을 만든다, F7); 결과 `id` |
| `battlePlanSeal` | planId | 정수 | 1 내 미소비 계획 아님 「계획이 없습니다.」 → 2 이미 봉인 「봉인된 계획입니다.」 | `sealedAt = nowProvider(), sealedDate = 현재 순` UPDATE(S9); 개인 기록 「<Y>{도시}</> 출병 계획을 봉인했습니다.」 |
| `battlePlanDelete` | planId | 정수 | 1 내 미소비 계획 아님 「계획이 없습니다.」 → 2 봉인됨 「봉인된 계획입니다.」 | `removeBattlePlan`(초안만 삭제된다) |

봉인 뒤 수정은 인테이크가 「봉인된 계획입니다.」 로 거부한다. game-api `POST /api/command/battlePlanSave` 는 인테이크 202 를 그대로 돌려주고(계약 불변), **읽기 API** `GET /api/my-battle-plans` 가 `sealed: true` 를 주어 UI 가 폼을 잠근다. HTTP 409 는 인테이크 계약 밖이므로 쓰지 않는다(계획 문구 「봉인 뒤 409」 는 「인테이크 거부 사유」 로 고친다 — §9).

결과 타입 `BattlePlanActionResult(type, ok, generalId, reason?, id?)`(4X-A 와 같은 꼴, sealed + 직렬화기 왕복 핀).

## 5. 해결 훅과 리플레이 기록 (예약 턴 `che_출병` 실행 시)

- **주입 경로(F2·F5·F6)**: 엔진 `BattleCommandContextBuilder.build(..., autorunMode: Boolean = false)` 가 **`BattleCommandContext`** 에 `sealedPlans: Map<Int /*finalTargetCityId*/, SealedBattlePlan>`(그 장수의 봉인·미소비 계획 중 `sealedDate <= executingDate` 인 것; **`autorunMode` 가 참이면 `emptyMap()`** — `ReservedTurnHandler` 가 AI 인터포즈로 `actionCode` 를 바꾼 턴의 `autorunMode` 를 넘긴다, M5)과 `executingDate` 를 싣는다. `CheChulbyeong.defaultProcessWar`(훅을 만드는 자리)가 예약의 `finalTargetCityId` 로 계획을 찾아(경유 전투에도 같은 계획, F6) 훅을 감싼다 — AI 가 명령을 바꾼 턴(`autorunMode == true`, `actionCode` 상이)에는 계획을 적용하지 않고 리플레이도 남기지 않는다(같은 `che_출병` 이면 인간 인자가 유지되므로 적용된다 — M5): `hooks = ReplayRecordingHooks(PlannedWarBattleHooks(ProductionWarBattleHooks(...), plan), draft)`. 계획이 없으면 **감싸지 않는다**(기존 객체 그대로 — 바이트 동일의 구조적 증거). `processWarFn` 을 주입하는 테스트는 이 조립을 우회한다(R12). `lastReplayDraft` 는 `resolve` 진입 시 초기화 목록(`lastBattleResult`·`lastWarSeed` 옆)에 넣는다. 해결 뒤 `lastReplayDraft` 를 노출하고 엔진 `ReservedTurnHandler` 출병 후처리가 `recorder.recordBattleReplayInsert(columns)` + 계획 `resolvedDate` UPDATE 를 기록한다.
- **`WarBattleHooks` 확장(기본값 = 오늘 동작)**: `fun plannedStop(attacker: WarUnitGeneral, def: WarUnit, phaseIndex: Int): PlanStop? = null`. `processWarNG` 는 `addPhase()` 뒤에서 (1) `val natural = attacker.continueWar()` — `!natural.canContinue` 면 오늘의 자연 퇴각 분기 그대로(`retreatAttacker(attacker, def, noRice = natural.noRice)` 로 추출: `onBattleResultLog` 2회·`addLose/addWin`·`tryWound` 2회·`onRetreatLog`·`break`); (2) `val defCont = def.continueWar()`(오늘 `:168` 호출을 앞으로 옮겨 한 번만)와 `val stop = hooks.plannedStop(attacker, def, phase)`(**한 번만** 평가, M1)를 읽고 `fell = !defCont.canContinue && (def !is WarUnitCity || def.isSiege())` 를 계산한다 — **`!fell && stop != null`** 일 때만 같은 `retreatAttacker(noRice = false)`(N1·M1: 수비자가 서 있거나 비공성 성이 재정비하는 페이즈 = 퇴각 비용을 치른다); (3) `!defCont.canContinue` 면 오늘의 수비자 분기를 그대로 타고(`conquerCity`·재정비 `setOppose(null)`·`onDefenderDownLog` 유지), 그 분기 끝의 `phase >= maxPhase` break 를 `|| stop != null` 로 넓혀 다음 수비자로 넘어가지 않는다(추가 draw 0 — 건너뛰는 것은 `finishDefenderBattle`(사후에 한 번 불린다)·`getNextDefender`(rng 없음)뿐). 계획 없는 경로는 `stop == null` 이라 호출·draw 순서가 바뀌지 않는다(`ProcessWarNGOrderTest` 로 핀, S14). 계획 퇴각도 `deathnum+1`·`tryWound` draw 가 든다(S4).
- **`PlannedWarBattleHooks` 판정(draw 0)**: `probe` 는 접촉이 있었던 페이즈가 1개 지나면 `PlanStop.PROBE`; `retreatLossPct` 는 `attacker.getCrew() <= crewBefore × (100 − pct) / 100` 이면 `LOSS_PCT`; `retreatMoraleBelow` 는 `attacker.getAtmos() < value` 이면 `MORALE`. 여러 조건이 동시면 표기 순(probe → loss → morale). `crewBefore` 는 processWar 진입 시 공격자 crew.
- **`ReplayRecordingHooks`**: 위임 + 기록(draw 0). `onAdvanceLog` → 시작, `onContactLog` → 접촉 페이즈 표시, `onPhaseLog(attacker, def, deadA, deadD)` → `battlePhases += {i, defId: 수비 장수 id 또는 도시 id, defKind, contact, deadA, deadD, crewA: attacker.getCrew(), hpD: def.getHP()}` — logic `General`/`City` 에 `name` 이 없으므로(N2) 초안은 id 만 싣고, 엔진 후처리가 `world.getGeneralById/getCityById` 로 이름을 채운 뒤 직렬화한다 — `BattleReplayCodec.encode(draft, names: (kind, id) -> String)` 처럼 이름은 **입력**이고 폴백은 `"G$id"`/`"C$id"`(`attacker_name`·`defender_city_name` NOT NULL, JSON `def` 문자열), 결정성 테스트는 고정 이름 맵을 주입한다(M6)(`getName()` 은 토큰이라 안 쓴다, S15), `onRetreatLog`/`onDefenderDownLog`/`onSupplyRout`/점령(`ProcessWarResult.conquerCity`) → `result`·`planStop`. 사망자 합은 `onPhaseLog` 의 `deadA/deadD` 누적(`deadPerson` 은 private). `BattleReplayDraft` 는 `CheChulbyeong` 이 `processWar` 반환 뒤 정산(`attacker.getCrew()` 전후, 누적 사망, `rice` 차)을 id 기준으로 채워 `lastReplayDraft` 로 노출하고, 엔진 후처리가 이름·`operation_id`(§2 채움 규칙)를 채운다. `input_hash`·`replay_hash` 는 logic 순수 함수 `BattleReplayCodec.encode/hash`.
- **로그**: 프로덕션 `ProductionWarBattleHooks` 는 `on*Log` 를 구현하지 않으므로 전투 로그는 오늘도 없다(S1) — 이 절편도 만들지 않는다. 계획이 있을 때만 개인 기록 한 줄 「리플레이 #{id} 가 기록되었습니다.」(`scope="general", category="action"`) — 이것과 리플레이 행이 플레이어가 받는 유일한 전투 흔적이다(S16).
- **`input_hash` 내용(S5·R)**: `{schema:1, warSeed, attacker:{id, crew, crewTypeId, tech, train, atmos, rice, injury, explevel, dex(병종별 숙련), items(정렬), stats(leadership/strength/intel/politics/charm), skills 정렬}, defenders:[같은 필드](출병 순), city:{id, level, def, wall, nationId, supply}, env:{ProcessWarEnv 의 값 전부}, plan:{stance, pct, morale}, year, month, startYear}` 를 키 정렬·리스트 순서 유지·Double 은 `toBits()` 로 정규화한 뒤 SHA-256. **부분 지문**이다(R3) — 결정성 **게이트**는 `input_hash` 가 아니라 「같은 메모리 입력을 두 번 실행한 `replay_hash` 동일성」 이다.
- **`result` 단일 규칙(P1, 훅 이벤트 기준 — §0 표·§2 CHECK·`resultLabel` 전부 이 한 곳)**: `ReplayRecordingHooks` 가 `retreat`(`onRetreatLog` 발화)·`lastDefenderDown`(마지막 `onPhaseLog` 의 상대에게 `onDefenderDownLog` 가 발화) 두 플래그를 들고, 정산은 ① `ProcessWarResult.conquerCity` → `conquered`; ② 아니고 `retreat` → `retreat`(자연·계획 모두; 「A 격파 뒤 B 와 싸우다 퇴각」 도 여기); ③ 아니고 `lastDefenderDown` → `defenders_down`(마지막 페이즈에 마지막 상대가 무너졌으나 점령 아님 — 페이즈 소진 break 또는 계획 정지 break); ④ 아니면 `repelled`(양쪽 생존·페이즈 소진 — 가장 흔한 결말; 비공성 성 재정비도 여기). `BattlePlanRulesTest` 표 4행 + 「A 격파 뒤 B 생존·소진 = repelled」 1행.
- **결정성 게이트**: 같은 `warSeed`·입력·계획으로 `processWar` 를 두 번 돌리면 `replay_hash` 가 같다(§8; `RandUtil`/`LiteHashDrbg` 는 인스턴스 상태뿐이라 재실행 가능). 다른 계획(예: pct 10 vs 90)이면 `input_hash` 가 다르다. `war_seed` 에는 순이 없으므로 전투 키는 `battle_replay.id` 다(S6).

`battle_phases_json` 스키마 v1: `{"v":1,"phases":[{"i":1,"defId":123,"def":"화웅","defKind":"general|city","contact":true,"deadA":1900,"deadD":600,"crewA":10100,"hpD":8400}],"stop":{"kind":"loss_pct"|"morale"|"probe"|null,"atPhase":3}}` — 키 정렬 고정, 저장 바이트가 곧 해시 입력(S7).

## 6. 읽기 API (game-api) — `GameApiSecurityConfig` `.authenticated()` 등록

- `GET /api/my-battle-plans`: 401 익명 · 200 `{generalId, plans:[{id, targetCityId, targetCityName, stance, stanceLabel, retreatLossPct, retreatMoraleBelow, sealed, sealedAt, resolved:false, version}], rules:{stances:[{value,label,description}], retreatLossPctMin, retreatLossPctMax, provisional:true}}`(소비된 계획은 목록에 없다).
- `GET /api/battles/replays?scope=nation|mine`(감찰부 목록): 401 익명 · 200 `[{id, year, month, phase, attackerName, attackerNationId, defenderCityName, defenderNationId, result, resultLabel, attackerDead, defenderDead, hasPlan}]` — `nation` 은 내 국가가 공격자 **또는** 수비자인 리플레이, 재야는 `mine` 만.
- `GET /api/battles/replays/{id}`: 401 익명 · 403 (공격 국가·수비 국가 어느 쪽도 아님; 본인이면 200) · 200 위 + `battlePhases`, `settlement:{attackerCrewBefore, attackerCrewAfter, attackerDead, defenderDead, riceUsed, conquered}`, `plan:{stance, retreatLossPct, retreatMoraleBelow, planStop} | null`, `seed:{warSeed, inputHash, replayHash, schemaVersion}`, `operationId`.
- 예상 범위(09 「예상 (결정론 시뮬)」): 기존 `POST /api/simulate-battle` 을 **그대로** 쓴다 — `defenderGeneralId` 가 필요하므로 09 화면은 목표 도시 상세 API 가 주는 수비 장수 목록의 **첫 항목**(클라이언트가 `extractBattleOrder` 를 재현하지 않는다; 없으면 「수비 장수 없음 — 성 방어만」 으로 disabled)을 넣고 「목록 첫 수비자 1인 기준 예상」 이라고 적는다(S10·R). 결과는 killed/dead 의 min/avg/max. 아트보드의 「우세 41% · 대등 37% · 열세 22%」 는 그 API 가 per-repeat 결과를 주지 않으므로 **그리지 않는다**.

## 7. UI

- **09 명령 봉인 `/game/battle-plan?city={id}`**(군사 부서 아래 새 화면; 기존 라벨 변경 없음): 좌 「아군」 = 본인 부대 카드 1장(`Portrait card`, 병종·병력·훈련·사기·피로는 `/api/my-page` 값) — 다중 부대는 「이 절편은 본인 부대만」 점선 카드로 남긴다(숨기지 않음). 중앙 「적군 · 정찰 정보」 = 목표 도시(`/api/city/{id}` 기존)·수비 장수 목록(시야 규칙은 기존 API 그대로). 우 「명령」: 태세(돌격·탐색 활성 / 전진·방어·우회 는 disabled + 「이 절편에서는 지원하지 않습니다」), 조건 2개(손실 % 슬라이더 10~90 · 사기 임계 0~100 · 「합류 전 추격 금지」 는 disabled + 「엔진에 추격이 없습니다」), 「예상」 = simulate-battle 결과 min/avg/max, 「봉인」 버튼(→ `battlePlanSeal`, 확인 대화상자 「봉인 뒤에는 바꿀 수 없습니다」). 봉인되면 폼 전체 readonly + 「봉인됨 · {순}」 칩. 「봉인까지 01:48:20」 카운트다운 = 다음 내 턴 시각(`/api/my-page`의 turnTime) — 실측값.
- 작전실 명령 목록: `slot.action == "che_출병"` 이고 `typeof slot.arg.destCityID === 'number'`(`arg: Record<string, unknown>`, R14)인 예약 슬롯에만 「봉인」 링크(→ 위 화면 `?city={destCityID}`, S11). 계획은 **예약한 목표 도시로의 출병**에만 걸린다 — AI 가 명령을 바꾼 턴(`autorunMode`)은 미적용(M5 배선)이므로 autorun 대상(`npc >= 2` 또는 자율행동 창) 장수의 슬롯에는 「봉인됨」 칩을 점선 + 「AI 가 명령을 바꾼 턴에는 적용되지 않습니다」 로(R9). 봉인된 미소비 계획이 있으면 `Slot` 상태 칩 「봉인됨」. 「봉인까지」 카운트다운 = 다음 내 턴 시각(같은 순 봉인도 적용된다, F5).
- **10 리플레이 `/game/battle-replay/{id}`**: 상단 對 카드(공격 장수 `hero`·수비 도시/장수), 페이즈 스크럽(‹ › n/총, 0.5×/1×/2× 는 자동 넘김 간격), 페이즈별 텍스트 로그(P1…Pn: 기존 `onPhaseLog` 수치), 조건 발동 표시, 정산(사상·군량·점령·`operationId` 있으면 4X-B 작전 링크), 「같은 seed·입력이면 같은 결과를 재생합니다」 + `replayHash` 앞 8자, 「감찰부 기록」 링크. 2.5D 렌더 없음(§10).
- **감찰부** `battle-center`: 「최근 전투」 표에 리플레이 열(있으면 링크 「리플레이」, 없으면 「기록 없음(계획 미봉인)」 점선 텍스트). **회의실 첨부 카드**는 밖(§10).

## 8. 테스트·게이트

- logic `BattlePlanRulesTest`: 게이트 순서 표(3 명령), 입력 범위, `plannedStop` 판정 표(probe/loss/morale/동시), `BattleReplayCodec` 결정성(같은 입력 → 같은 해시, 계획 차이 → input_hash 차이), 정수만·키 정렬.
- logic `ProcessWarPlanHookTest`(**적색 프로브**): 같은 fixture 로 `processWar` 를 기존 `WarBattleHooks.NOOP` 으로 돌린 결과와 리팩터 뒤(공용 `retreatAttacker` 추출 + 새 기본 메서드) 결과가 `ProcessWarResult`·훅 호출 순서 기록(`ProcessWarNGOrderTest` 확장, `onBattleResultLog` 포함)에서 **deep-equal**(계획 없음 = 바이트 동일, S14). 같은 테스트가 pct 10 계획으로 돌려 **달라짐**(더 이른 퇴각)을 단언. 두 번 돌려 `replay_hash` 동일.
- logic 골든 274 json 회귀(`processWarNG` 시그니처 기본값 유지 → 골든 무접촉).
- engine `BattlePlanIntakeTest`: 3 명령 채널·봉인 뒤 거부·소비 뒤 재저장 허용(F7)·툼스톤(장수 삭제 → 계획 프룬, pending 리플레이 id NULL — F3)·같은 틱 저장→봉인. `ReservedTurnHandler` 출병 후처리: `lastReplayDraft` → `recordBattleReplayInsert` 열 + `resolvedDate` UPDATE + 개인 기록 1줄, 계획 없으면 채널 0. 같은 순 봉인이 그 턴에 적용됨(F5). **N4 적색 행**: 「봉인 출병(작전 연결) → 같은 틱 `markNationDeleted`(공격국)」 → pending `battleReplayInserts.operation_id` 가 null.
- infra `BattlePlanReplayFlushIT`(PG16): V57 DDL(부분 UNIQUE 인덱스 포함) · 채널 순서 · `SET NULL (battle_plan_id)`/`(attacker_general_id)` · 리플레이 INSERT · 「봉인 출병 → 같은 틱 사망」 payload 가 NULL 로 COMMIT 성공(F3 적색면). **N4 적색 행**: 「작전 연결 리플레이 INSERT + 같은 flush 의 nation cascade DELETE(6단계)」 → `operation_id` NULL 로 COMMIT 성공. **M1 행**(logic): 비공성 성이 `def` 인 페이즈의 계획 정지 = `addLose`·`tryWound` 발화 + `retreat`.
- game-api `BattleReplayReadControllerTest`: 401 / 403 타국 / 200 공격국·수비국·본인 / 목록 scope.
- vitest: 09 폼(disabled 사유·봉인 후 readonly)·10 스크럽·감찰부 리플레이 열·작전실 봉인 링크.
- e2e 1건(계획 `web/game/e2e`): 저장 → 봉인 → (로컬 엔진에서 출병 턴 실행) → 리플레이 열기 — 로컬 스택에서 실행 순을 앞당길 수 없으면 **UNKNOWN** 으로 남기고 단위·IT 게이트로 대체(§10).

## 9. 마이그레이션·순서

V57 = 이 절편(4X-B V56 뒤 — 이미 커밋됨; `battle_replay.operation_id` FK `ON DELETE SET NULL (operation_id)`). executor 단계는 **8i**(8h 가 커밋됨). 계획 문서 4X-C 블록은 v3 커밋에서 이 스펙으로 실제로 고쳤다(N3): 이름에서 WEGO 삭제·07-30 링크는 「범위 밖 참조」·봉인 마감 `<=`·`battle_plan.operation_id` 없음(리플레이 쪽에만)·명령 3종 이름·409 아님·stance 2종·조건 2종·소비된 계획.

## 10. UNKNOWN · 밖

- 모든 전투 리플레이 기록(계획 없는 전투 포함)은 FlushPayload 바이트 동일 원칙과 충돌한다 — 원한다면 「전면 기록」 을 별도 ADR 로 결정(사용자).
- 다중 부대(호위·별동) 봉인·합류·추격은 엔진에 대응물이 없다(W2/W3·BATTLE-F10~11).
- 회의실·커뮤니티 리플레이 첨부 카드: `board_post.replay_id` 열이 없어 밖(4X-B 의 `operation_id` 선례로 다음 번호에서).
- e2e(봉인→해결→리플레이)는 로컬 엔진 턴 실행 시각에 묶여 있어 실행 가능성 UNKNOWN.
- 10 아트보드의 2.5D 전장 렌더는 밖(OPENSAM-173 의 HUD/렌더러).
