# V59 일반화 판정 — 3단계(한 州 슬라이스) 착수 조건

> 작성일: 2026-09-17 · 기준 커밋: origin/main `11fcef63`
> 상태: **판정 문서(코드 변경 없음).** 포트폴리오 계획 `docs/superpowers/plans/2026-09-17-general-retinue-portfolio-plan.md:200` 의 3단계 착수 조건 넷 가운데 「V59 일반화 판정」이다(같은 계획 69행의 판정 항목).
> 물음: 재설계 spec(`docs/superpowers/specs/2026-09-17-general-and-retinue-campaign-redesign.md`, 아래 「spec」) §10·§13.1 이 S3 전에 판정하라고 남긴 것 — V59 전장(및 V49/V50·V55–V58)을 새 설계 1층에 **그대로 쓰는가 / 일반화하는가 / 버리는가.**
> 방법: 마이그레이션 SQL 과 main 소스만 읽었다. 테스트는 돌리지 않았다. 확인하지 못한 것은 §5 UNKNOWN 에 적었다. 이 문서의 어떤 숫자도 게이트 임계값이 아니다.

경로 약어: `MIG/` = `infra/src/main/resources/db/migration/`, `ENG/` = `app/game-engine/src/main/kotlin/opensamguk/engine/`, `API/` = `app/game-api/src/main/kotlin/opensamguk/gameapi/`, `LOGIC/` = `logic/src/main/kotlin/opensamguk/logic/`, `INFRA/` = `infra/src/main/kotlin/opensamguk/infra/`, `WIRE` = `common/src/main/kotlin/opensamguk/common/wire/TurnDaemonCommand.kt`, `FLUSH` = `INFRA/persistence/JdbcFlushExecutor.kt`, `REC` = `ENG/turn/ChangeRecorder.kt`, `HCC` = `LOGIC/memory/HotColdCatalog.kt`.

## 0. 한 줄 판정

**저장·동시성 뼈대는 그대로 쓰고, V59 의 「이름 붙은 전장」 의미층은 省 단위 조우의 선례로만 남기며 일반화의 출발점으로 삼지 않는다.** 일반화가 필요한 것은 V59 자체가 아니라 그 밑의 V50 `general_spatial_position` 을 「전장에 들어간 장수만 갖는 선택 행」에서 「모든 군단의 정본 위치」로 올리는 일이다. 버릴 표는 없다.

## 1. 마이그레이션별 표·제약과 코드 경로

### 1.1 표·열·제약

| 판 | 만든 것 | 키·핵심 제약 | 근거 |
|---|---|---|---|
| V49 | `water_zone_control` | PK `(world_id, water_zone_id)`. `controlling_nation_id`(NULL 허용, >0), `contesting_nation_ids` JSONB 배열, `blockade_state` ∈ OPEN/CONTESTED/BLOCKED, `topology_revision`·`topology_hash`(sha256) 핀, `revision`>0. 「Empty by design … Never infer/backfill」 | `MIG/V49__create_water_zone_control.sql:1-15` |
| V50 | `province_control` | PK `(world_id, province_id)`, `nation_id >= 0`, 위상 핀, `revision` | `MIG/V50__create_spatial_state.sql:2-10` |
| V50 | `general_spatial_position` | PK `(world_id, general_id)` — 장수당 한 행. `node_kind` ∈ LAND_PROVINCE/WATER_ZONE, `node_id`, 위상 핀, `revision`, `general(world_id,id)` FK CASCADE | `MIG/V50__create_spatial_state.sql:12-23` |
| V52 | 없음(번호 자리표시자) | `SELECT 1` | `MIG/V52__reserved_gap.sql:1-4` |
| V55 | `general_retainers` | PK `(world_id,id)`, `master_general_id` FK, `origin` ∈ EXISTING/RECRUITED, EXISTING ⇔ `general_id` NOT NULL, `relation` ∈ staff/lieutenant/guest, `role` 6종, `task` ∈ none/domestic/scout/train, `loyalty` 0–100, `(world_id, master_general_id, name)` UNIQUE | `MIG/V55__general_retainers_and_bugok.sql:6-34` |
| V55 | `general_bugok` | PK `(world_id,id)`, `master_general_id` FK, `troops>0`, `crew_type_id`, `training`·`morale`·`fatigue` 0–100, `provisions>=0`, `commander_retainer_id` FK SET NULL | 같은 파일 `:36-62` |
| V56 | `operation` | PK `(world_id,id)`, **`nation_id` NOT NULL FK CASCADE**, **`target_city_id` NOT NULL FK → city**, `kind` 6종, `status` 5종, 선언·기한 `year/month/phase`, **`CHECK (deadline_phase = 1)`**, 이정표 불리언 4개, `closed_reason` 5종 | `MIG/V56__operation.sql:5-39` |
| V56 | `operation_unit` | PK `(world_id,id)`, `general_id` FK, `bugok_id` FK SET NULL, `role` 5종, **`joined_city_id` NOT NULL**, `(world_id, operation_id, general_id)` UNIQUE | 같은 파일 `:41-61` |
| V56 | `board_post.operation_id`, `general_bugok.commander_bonus_applied` | DEFERRABLE FK / 불리언 | 같은 파일 `:63-71` |
| V57 | `battle_plan` | PK `(world_id,id)`, `general_id` FK, **`target_city_id` NOT NULL FK → city**, `stance` ∈ assault/probe, `retreat_loss_pct` 10–90, `retreat_morale_below` 0–100, 봉인·해결 `year/month/phase`, 부분 UNIQUE `(world_id, general_id, target_city_id) WHERE resolved_year IS NULL` | `MIG/V57__battle_plan_replay.sql:5-33` |
| V57 | `battle_replay`(INSERT 전용) | PK `(world_id,id)`, **`defender_city_id`·`defender_city_name` NOT NULL**, 공격자 1인(`attacker_general_id`), 국가 id 스냅샷, `war_seed`·`input_hash`·`replay_hash`, `battle_phases_json`, `result` ∈ retreat/repelled/defenders_down/conquered, `plan_stop` 3종 | 같은 파일 `:37-77` |
| V58 | 부분 UNIQUE 인덱스 | `general_retainers (world_id, general_id) WHERE general_id IS NOT NULL` — 기존 장수는 한 주인만 | `MIG/V58__existing_retainer_single_master.sql:1-4` |
| V59 | `general_spatial_position` 에 열 3개 | `battlefield_id`(`^[a-z][a-z0-9-]*$`), `battlefield_catalog_hash`(sha256), **`battlefield_return_city_id`(>0)** — 셋 다 NULL 이거나 셋 다 NOT NULL. 새 표 없음 | `MIG/V59__battlefield_presence.sql:1-13` |

V51·V53·V54 는 gateway 공지·게시판이라 이 판정 범위 밖이다(파일명으로만 확인).

### 1.2 읽고 쓰는 경로

| 대상 | HotColdCatalog | 메모리·ChangeRecorder 채널 | JdbcFlush step | 인테이크·와이어 | 읽기 API |
|---|---|---|---|---|---|
| `water_zone_control` | `loadWaterControlSnapshot` ALWAYS_HOT (`HCC:33-40`), 핀 검사 `loadHistoricalMapPins` (`HCC:9-16`) | `applyWaterControlAssessment` (`REC:740-761`), 버퍼 `waterControlWrites` (`REC:199`) | 14번 블록 뒤 `waterControlWriteMany` (`FLUSH:395-397`, `:503-536`) | **없음** — main 소스에 `applyWaterControlAssessment` 호출자가 없다(§3 A7) | `API/read/WaterControlReadRepository.kt:24` |
| `province_control` | `loadProvinceControlSnapshot` (`HCC:17-24`) | `applyProvinceControlAssessment` (`REC:763-782`), 버퍼 `provinceControlWrites` (`REC:201`) | `provinceControlWriteMany` (`FLUSH:389-391`, `:417-447`), CAS 실패는 `StaleProvinceControlException` → RELOAD_REQUIRED (`ENG/flush/FlushRecoveryGate.kt:128`) | **없음** — main 소스에 호출자가 없다(§3 A7) | `API/read/SpatialStateReadRepository.kt:38` |
| `general_spatial_position`(+V59 열) | `loadGeneralPositionSnapshot` (`HCC:25-32`), 부팅 로드·전장 검증 `ENG/boot/WorldSnapshotLoader.kt:242-252` | `applyGeneralPositionAssessment`·`removeGeneralPosition` (`REC:784-836`), 버퍼 `generalPositionWrites` (`REC:202`), 월드 뷰 `InMemoryTurnWorld.generalPositionSnapshot()`·`isGeneralAtBattlefield` (`ENG/turn/InMemoryTurnWorld.kt:227-233`) | `generalPositionWriteMany` (`FLUSH:392-394`, `:450-499`), 한 payload 안 위상 핀 혼합 금지 (`FLUSH:404-415`), 행 코덱 `INFRA/persistence/GeneralPositionRowCodec.kt:10-49` | 와이어 타입 없음. **예약 턴 명령 `che_전장이동`** (`LOGIC/actions/military/CheJeonjangIdong.kt:9-18`, 등록 `LOGIC/actions/CommandRegistry.kt:150`, 예약 허용 `API/reserve/CommandQueueService.kt:444`) → `ENG/turn/ReservedTurnHandler.kt:283-291` → `ENG/war/BattlefieldTurnHandler.kt` | `GET /api/battlefields` (`API/controller/BattlefieldController.kt:22-41`), `API/read/BattlefieldReadRepository.kt`, `API/read/SpatialStateReadRepository.kt:43` |
| `general_retainers`·`general_bugok` | `loadRetainers`·`loadBugoks` (`HCC:124-139`) | 월드 create/update/remove → `DirtyState` `retainers/createdRetainers/deletedRetainers`, `bugoks/…` (`ENG/turn/DirtyState.kt:205-210`) | **8g** (`FLUSH:264-273`) | `RetainerPledge`·`RetainerRelease`·`RetainerTask`·`BugokForm`·`BugokDisband`·`BugokAssignCommander` (`WIRE:145-210`) → `ENG/run/TurnDaemonCommandDispatcher.kt:424-429` → `ENG/intake/RetainerHandler.kt`; 월 정산 `ENG/retainer/RetainerMonthlyService.kt` (`ENG/run/MonthlyPostUpdateHook.kt:236`) | `API/controller/RetinueController.kt`, `API/read/RetainerReadRepository.kt` |
| `operation`·`operation_unit` | `loadOperations`·`loadOperationUnits` (`HCC:140-155`) | `DirtyState` `operations/…`, `operationUnits/…` (`ENG/turn/DirtyState.kt:212-217`) | **8h** (`FLUSH:275-283`) | `OperationDeclare`·`Join`·`Leave`·`Close` (`WIRE:214-258`) → dispatcher `:430-433` → `ENG/intake/OperationHandler.kt`; 월 정산 `ENG/operation/OperationMonthlyService.kt` (`MonthlyPostUpdateHook.kt:238`) | `API/controller/OperationController.kt`, `API/read/OperationReadRepository.kt` |
| `battle_plan` | `loadBattlePlans` (`HCC:156-163`) | `DirtyState` `battlePlans/…` (`DirtyState.kt:219-221`) | **8i** (`FLUSH:285-292`) | `BattlePlanSave`·`Seal`·`Delete` (`WIRE:262-292`) → dispatcher `:434-` → `ENG/intake/BattlePlanHandler.kt`; 소비는 출병 해결 안 (`LOGIC/actions/war/CheChulbyeong.kt:289-291`, `ENG/war/BattleCommandContextBuilder.kt:83-85`) | `API/controller/BattlePlanController.kt` |
| `battle_replay` | 부팅 스냅샷에 없음(INSERT 전용). id 시드 `INFRA/read/BattleReplayRepository.kt:19` | `recordBattleReplayInsert` (`REC:870`), 버퍼 `battleReplayInserts` (`REC:217`) | **8i** `battleReplayInsertMany` (`FLUSH:293`, `:1965-1981`) | 입력 없음 — `ReservedTurnHandler.drainBattleReplay` 가 출병 뒤 기록 (`ENG/turn/ReservedTurnHandler.kt:714-760`) | `/api/battles/replays` (`API/security/GameApiSecurityConfig.kt:50`) |

V59 의 부수 경로(전장 주둔이 다른 코드에 미치는 곳): 도시 명령 차단 `ReservedTurnHandler.kt:292-298`, 사령턴 차단·연쇄 이동 제외 `ENG/turn/ProcessNationCommand.kt:112,200,542,576`, 수비 목록 제외 `ENG/war/BattleCommandContextBuilder.kt:68`, 제약 평가 때 `cityId=0` 로 가림 `ENG/turn/PerTurnOverlay.kt:75`, v2 샌드박스 명령 차단 `TurnDaemonCommandDispatcher.kt:475,483`, 사전검사 `API/precheck/CommandPrecheckService.kt:116-117`, 도시 장수 목록에서 숨김 `API/read/GeneralReadRepository.kt:263-264,277-278,328-329`, 도시 이동 때 위치 정합 `ENG/turn/BattlefieldCityMembership.kt:9-42`.

## 2. 새 설계 1층 요구와의 대조

| # | 1층 요구(spec) | 기존 구현의 상태 | 판정 | 근거·무엇을 어떻게 |
|---|---|---|---|---|
| R1 | 省 = 군단 위치·이동·군사 점유(§7 첫 항), 「V49·V50 보존 — 군단 위치·省 점유 저장 기반」(§13.1) | `general_spatial_position` 은 省/수역 노드 키, 낙관적 revision CAS, 위상 핀, 부팅 검증까지 갖췄다 | **재사용(표·코덱·레코더·flush)** | `MIG/V50:12-23`, `LOGIC/world/GeneralPositionState.kt:130-162`, `REC:784-819`, `FLUSH:450-499` |
| R2 | 모든 군단이 省 위에 서 있다(§5.1 4단계 「경로를 이동량만큼 진행」, §6.2 「위치 省」) | 위치 행을 **만드는 곳은 전장 진입뿐**이다. 시나리오 시드도, 일반 이동도 행을 만들지 않는다. 도시 이동은 **이미 행이 있을 때만** 따라 고친다 | **일반화 필요** | 쓰기 호출자 전수: `ENG/war/BattlefieldTurnHandler.kt:80-82`, `ENG/turn/BattlefieldCityMembership.kt:22,27,29`(행이 없으면 17행에서 건너뜀). 표 참조 전수(§1.2)에 시드 경로 없음. → S3 월드 개시 때 전 장수 위치 행을 시드하고, 위치의 정본을 `general.city_id` 에서 이 표로 옮기는 결정이 선행돼야 한다 |
| R3 | 縣 단위 행군: 거리×지형 계수, 강·도로·관문(§7), 여러 순에 걸친 부임 이동(§2.4, §4 배치) | 省 그래프와 경로 탐색은 있다(`StrategicPathResolver.resolve`·`reachableNodes`). 그러나 육지 간선 비용은 전부 `movementCost = 1` 이고, 엔진에서 이 경로 탐색을 **행군**에 쓰는 호출자는 없다. 호출자는 보급 도달성, game-api 읽기·v2 사전검사, 그리고 **엔진의 v2 도시 간 수송**(`V2CityTransportHandler` → `resolveImmediateCityTransportRoute` → `projection.resolve`) 넷이다 — 마지막 것이 省 행군이 재사용할 가장 가까운 선례다(교차 비평 정정: 처음엔 `StrategicPathResolver` 이름으로만 grep 해 래퍼의 호출자를 놓쳤다). 「진행 중인 경로」를 담을 열이 없다 | **일반화 필요(새 코드 + 새 열/표)** | `LOGIC/world/HanStrategicRouteProjection.kt:100-110`(비용 1), `:44-59`, 호출자 `LOGIC/world/StrategicSupplyNetwork.kt:59`, `API/read/StrategicTopologyReadSource.kt`, `API/v2/V2CommandPrecheckService.kt:39`, `ENG/v2/V2CityTransportHandler.kt:81`, `LOGIC/v2/command/V2CityTransportRoutes.kt:13-20`. 전장 이동 규칙 자체가 「Same-node deployment only. Cross-province … need separately reviewed movement」라고 적는다 (`LOGIC/world/BattlefieldMovementRules.kt:26`) |
| R4 | 조우: 적 군단이 있는 省 진입·설치 계책 省·요격 범위(§5.1 4단계, §5.2 기본값) | 조우 판정 단위가 省이 아니라 **카탈로그 전장 id** 다. 같은 省에 있어도 같은 `siteId` 에 주둔한 장수만 상대가 된다. 카탈로그는 장판·관도 2곳 | **폐기(조우 트리거로서)** — 선례로만 보존 | `BattlefieldTurnHandler.kt:47-50`(occupants = 같은 siteId), `infra/src/main/resources/map/han-world-v3-battlefields.json`(sites 2건: changban·guandu), `INFRA/seed/HistoricalBattlefieldCatalog.kt:49-59` |
| R5 | 야전 전투 해결: 결정론, 城 보정 없음(§5.1 5단계) | `processFieldWar` 는 장수 대 장수, 城 미구성·도시 보너스 없음·정산 없음, 시드는 (hiddenSeed, world, 연, 월, **순**, 장수, site, 카탈로그 해시, revision) | **재사용(전투 커널)** + 시드 입력만 교체 | `LOGIC/war/ProcessFieldWar.kt:28-60`, 시드 `BattlefieldTurnHandler.kt:73-74` — `site.id`·`catalog.contentHash` 자리를 省 id·위상 해시로 바꾸면 된다 |
| R6 | 조우 전투에 지형·결속·사기·보급 보정(§5.1 5단계, §7) | 야전 커널에 지형·보급 입력이 없다(`cityLevel = 0`, 입력은 장수·병종·기술·파이프라인뿐) | **일반화 필요(새 입력)** | `ProcessFieldWar.kt:11-16,43-46`. spec §0 표도 「전투·명령 코드에서 terrain 참조 0건」이라 적는다 |
| R7 | 공격 봉인 계획 + 방어 대응·진형을 함께 공개(§5.1 5단계), 「야전 조우·방어 대응·진형은 새 스키마」(§5.1 거리 절) | `battle_plan` 은 공격자·`target_city_id NOT NULL`·stance 2종·퇴각 2조건. 전장 핸들러는 계획을 **전혀 읽지 않는다** | **재사용(城 강공 한정) + 신규** | `MIG/V57:9,26-27`, `LOGIC/war/plan/BattlePlanRules.kt:16-18,84-91`; `BattlefieldTurnHandler.kt` 에 `battlePlan` 참조 없음. spec 판단과 일치 — 야전 계획은 새 표로 |
| R8 | 리플레이(§5.1 8단계), 「V57 리플레이 형식 재사용」 | `battle_replay` 는 `defender_city_id`·`defender_city_name` NOT NULL, 공격자 1인. **야전 전투는 리플레이를 쓰지 않고** 시드의 SHA-256 지문을 로그 문자열에만 남긴다 | **일반화 필요** | `MIG/V57:45-46`, `BattlefieldTurnHandler.kt:98,104`. 야전 기록을 넣으려면 `defender_city_*` 를 NULL 허용으로 풀고 省 id·방어측 장수 목록 열(또는 JSON)을 더해야 한다. 계획 #166·#199 가 이 일이다(포트폴리오 계획 72·77행) |
| R9 | 진 쪽은 **온 쪽으로** 물러난다(§5.1 5단계) | 패한 수비는 `battlefield_return_city_id` 로, 즉 **진입했던 城** 으로 귀환한다. 城 하나가 전장마다 고정(`ingressCityId`)이고 진입·이탈 모두 그 城에 있어야 한다 | **폐기(귀환 城 모델)** | `MIG/V59:5,12`, `LOGIC/world/GeneralPositionState.kt:6-16`(「the city's identity is only a return origin」), `BattlefieldMovementRules.kt:49-51,67-68`. 省 행군에서는 「직전 省」이 물러날 곳이며 城이 아니다 |
| R10 | 공성: 방어군 없는 적 城 앞이면 강공 또는 포위 유지, 포위 누적은 순 경계(§5.1 6단계, §5.2 2단계) | 강공은 삼모 출병 경로(`CheChulbyeong`)에 있고 V57 이 거기 훅으로 붙는다. **포위 상태를 담는 표·열은 V49–V59 에 없다** | **재사용(강공) + 신규(포위)** | `LOGIC/actions/war/CheChulbyeong.kt:289-291`; §1.1 표 전체에 siege 열 없음 |
| R11 | 작전: 여러 장수 군단의 합류(§10 「V56 작전으로 합류」, §13.1 「보존 → 세력 작전」) | 목표 = 城(`target_city_id NOT NULL`), 소유 = 국가, 진척 = **월 정산**에서 `general.cityId` 와 城 인접으로 재계산, 기한은 상순 고정, 선언 권한은 수뇌부 | **일반화 필요** | `MIG/V56:8,10,35`, `LOGIC/operation/OperationRules.kt:152-185`(UnitView 가 cityId), `ENG/operation/OperationMonthlyService.kt:42-46`(`CalcCityDistance.nearCity`), `ENG/intake/OperationHandler.kt:56-59`. 省 위치로 도착·출발을 판정하도록 입력을 바꿔야 하고, 예약 3종(`secure_route`·`pass_through`·`blockade`)은 코드가 스스로 「강역·수역·장수 위치 생산자가 붙을 때」라 적어 뒀다(`OperationRules.kt:10,34`) |
| R12 | 縣 점령 R1·省 점유(§7, §5.2 「적 군단이 선 省은 보급 통과 불가」) | `province_control` 은 채널만 있고 **생산자가 없다.** 현행 R1 은 城 소유를 관할 省에 투영하는 계산이며 이 표를 쓰지 않는다. 보급망은 `waterControlSnapshot` 만 받는다 | **재사용(표) + 신규(생산자·보급 차단 입력)** | 호출자 0: `applyProvinceControlAssessment` grep(main) → 정의뿐. `ENG/config/DaemonLoopConfig.kt:270`(보급망 인자에 province/position 없음), `ENG/world/HanSpatialSupplyProvider.kt:59,83`, ADR-LITE-052 R1(`.ai/decisions.md:894-898`) |
| R13 | 휘하: NPC 인물은 거느린 장수의 배치·방침을 따름, 주공 따라 소속 이동(§2.3·§2.4), 부대 카드는 지휘 인물이 붙어 움직임(§6.3) | 가신에 위치·자리 열이 없다. 부곡은 `logic/war` 어디에서도 읽히지 않는다 — 전투에 나가지 않는다. NPC 는 휘하를 못 둔다 | **재사용(표·유일 카드 원칙) + 일반화 필요(의미)** | `MIG/V55:6-33`(위치 열 없음), `bugok` 참조 파일 목록에 `LOGIC/war/**` 없음(§1.2 조사), `ENG/intake/RetainerHandler.kt:42`(`npcState >= 2` 거부), V58 은 spec §2.6 과 같은 원칙 |
| R14 | 장수 턴·순 경계(§3, §5) | 전장 시드와 작전·계획 시각은 이미 `year/month/phase` 를 쓴다. 그러나 작전·가신 정산은 월 훅에서만 돈다 | **재사용(시각 표현) + 일반화 필요(정산 주기)** | `BattlefieldTurnHandler.kt:73-74`, `MIG/V56:14-19`, `MIG/V57:14-19`, `ENG/run/MonthlyPostUpdateHook.kt:236-238` |
| R15 | NPC 도 같은 규칙으로 행군·조우(§14, §15.2 S3 「NPC 주공 여럿」) | NPC 는 계획을 봉인하지 못하고(인테이크 거부) 자동 턴에서는 봉인 계획을 아예 무시한다. `LOGIC/ai` 에 전장·위치 참조가 없다 | **일반화 필요** | `ENG/intake/BattlePlanHandler.kt:36`, `BattleCommandContextBuilder.kt:83`(`autorunMode` → emptyMap), `LOGIC/ai/**` 에서 전장·위치·작전·가신·부곡·계획 참조 grep 0건 |
| R16 | 해전·수역(§10, S4) | `water_zone_control`·WATER_ZONE 노드·`BattlefieldRole.NAVAL` 자리는 있다. 수역 전장 진입은 코드가 막아 둔다 | **재사용(보류)** — S4 판정 대상, 이 판정 밖 | `LOGIC/world/BattlefieldCatalog.kt:5,20-23`, `BattlefieldMovementRules.kt:26` |

## 3. 가정이 박힌 곳(코드로 확인한 것만)

| # | 가정 | 어디에 | 새 설계와의 충돌 |
|---|---|---|---|
| A1 | **城 단위 키.** 목표·합류 지점·계획 목표·리플레이 방어자가 모두 `city.id` 다 | `MIG/V56:10,31,48`, `MIG/V57:9,26,32,45-46`, `MIG/V59:5`; `OperationRules.kt:152-165`; `WIRE:218,265`(`targetCityId`) | §7 「省 = 군단 위치, 縣 = 내정」. 城 없는 省에서의 야전·차단 작전을 표현할 수 없다 |
| A2 | **공격 측 城 강공만.** stance 2종, 방어측 입력 없음, 결과 4종이 전부 城 공방 어휘 | `MIG/V57:27,72`, `BattlePlanRules.kt:16-35`(예약 stance 3종은 「엔진 대응물이 없어 disabled」 `:24-27`) | §5.1 5단계 방어 대응·진형 |
| A3 | **삼모 명령 코드 의존.** V57 은 `CheChulbyeong`(출병) 안에서만 발동하고, V59 는 `che_전장이동` 예약 명령 하나로만 들어가며 `GameConst.availableGeneralCommand` 에 덧붙여 허용된다. 전장 주둔 중 허용 행동은 `che_전장이동`(리터럴)과 휴식 폴백 키(`fallback.key` 비교) 둘로 고정 | `CheChulbyeong.kt:289-291`, `ReservedTurnHandler.kt:283,292-293`, `API/reserve/CommandQueueService.kt:444`, `API/precheck/CommandPrecheckService.kt:116` | spec §1 「삼모 명령은 동결 회귀 기준선으로만」. 새 입력(배치·방침)이 이동을 일으키므로 진입점을 새로 만들어야 한다 |
| A4 | **국가 단위 소유.** 작전은 `nation_id NOT NULL`, 省·수역 점유도 `nation_id`. 선언·종료 권한은 `SecretPermission`(수뇌부) | `MIG/V56:8,30`, `MIG/V50:7`, `MIG/V49:10-11`, `OperationHandler.kt:56,127`, `OperationRules.kt:144-149` | §2.2–§2.4 주공·봉신·장수 위계, §4 조정 결정. 재야(nation 0)·방랑 주공의 작전을 담지 못한다. 단 spec 은 V56 을 「세력 작전」으로 두므로(§13.1) **국가 키 자체는 1층에서 유지 가능**하고, 주공 단위 소유는 2층 봉신 계약 때의 문제다 |
| A5 | **적대 판정이 국가 외교.** 전장 수비 판정 = 국가가 다르고 (무소속이거나 `diplomacy.state == 0`) | `BattlefieldTurnHandler.kt:45-46,57-58` | §5.1 2단계 「이번 턴의 편」 — 국가 기준은 1층에서 유지 가능. 무소속(재야) 장수는 **진입자가 국가 소속이면 언제나 수비로 잡힌다**(진입자도 무소속이면 `nationId` 가 같아 수비에서 빠진다)는 점은 재야 플레이(§2.1)와 부딪친다 |
| A6 | **월 단위 정산.** 작전 이정표·기한, 가신·부곡 유지비가 월 훅에서만 돈다. `deadline_phase = 1` 은 DB CHECK | `MIG/V56:35`, `OperationRules.kt:76-83`, `MonthlyPostUpdateHook.kt:236-238`, `RetainerRules.kt:27`(`PROVISION_PER_TROOP_MONTH`) | §5.2 순 경계 보급·포위, §6.3 「유지 = 순마다 곡」 |
| A7 | **점유 채널에 생산자가 없다.** `province_control`·`water_zone_control` 은 로드·검증·flush·읽기 API 는 있으나 값을 쓰는 게임 규칙이 main 에 없다 | `applyProvinceControlAssessment`·`applyWaterControlAssessment` 호출자 grep(main 전체) 0건 — 정의는 `REC:740,763` | 가정이라기보다 **빈자리**다. 의미(군사 점유 vs R1 소유)를 S3 에서 새로 정해도 깨질 기존 데이터가 없다 |
| A8 | **위치 행은 선택적이고 城에 종속.** 행이 없으면 「위치 모름」이 정상이고, 城 이동이 위치를 끌고 간다(역방향). 외부 거점 城은 省이 없어 행을 지운다 | `BattlefieldCityMembership.kt:16-34`, `HistoricalBattlefieldCatalog.kt:25-30` | §6.2 「위치 省」이 정본이어야 한다. 방향을 뒤집어야 한다(R2) |
| A9 | **전장 = 검수된 고정 목록 + 고정 진입 城.** 진입은 같은 省에 앉은 지정 城에서만, 교차 省 진입은 금지 | `BattlefieldCatalog.kt:8-24`, `BattlefieldMovementRules.kt:26,49-52`, `HistoricalBattlefieldCatalog.kt:55` | §5.1 4단계 조우는 임의 省에서 난다 |
| A10 | **NPC 제외.** NPC 는 휘하·계획을 못 두고 자동 턴은 봉인 계획을 무시한다 | `RetainerHandler.kt:42`, `BattlePlanHandler.kt:36`, `BattleCommandContextBuilder.kt:83` | §2.2 NPC 주공, §14 |
| A11 | **부곡은 전투에 안 나간다.** `general_bugok` 을 읽는 전투 코드가 없다 | `bugok` 참조 파일: `RetainerRules`·`OperationRules`·핸들러·로더·flush 뿐, `LOGIC/war/**` 없음 | §6.3·§10 「군단 = 지휘 인물 카드 + 부대 카드」 |
| A12 | **단일 위상 핀.** 세 공간 채널의 모든 행이 한 위상 해시를 공유해야 하고 섞이면 flush 가 거부, CAS 불일치는 재로드 | `FLUSH:404-415`, `GeneralPositionRowCodec.kt:60-62`, `FlushRecoveryGate.kt:128` | 충돌은 아니다. 다만 위치 행이 전 장수로 늘면 **지도 판 교체 = 전 위치 행 재핀**이 된다. pep 전환은 월드 초기화를 전제하므로(§15.2) S3 에서는 문제가 되지 않는다 |
| A13 | **공격자 1인 대 수비 다수.** 야전 커널 입력이 `attacker` 하나 + `defenders` 목록, 리플레이도 `attacker_general_id` 하나 | `ProcessFieldWar.kt:33-37`, `MIG/V57:42` | §10 「여러 장수의 군단이 합류」 — 개인 턴 구조(§3)에서는 턴 주인 1인이 공격자이므로 1층에서는 유지 가능 |

찾았으나 **없었던** 것: 전장·위치 코드에 월 단위 틱 가정은 없다(시드에 `currentPhase` 포함, `BattlefieldTurnHandler.kt:74`). 월 가정은 A6 의 작전·가신 정산에만 있다.

## 4. 판정 요약과 3단계 첫 구현 묶음에 주는 함의

### 4.1 판정

| 대상 | 판정 | 한 줄 이유 |
|---|---|---|
| V49 `water_zone_control` | 그대로 둔다(S4 까지 보류) | 생산자 없음, 해전은 S4 |
| V50 `province_control` | 재사용 — **의미를 S3 에서 확정** | 생산자가 없어 자유롭다(A7). 「군사 점유」로 쓸지 R1 소유의 저장본으로 쓸지는 아직 정해지지 않았다 |
| V50 `general_spatial_position` | **재사용 + 일반화** | 전 장수 시드, 정본 방향 뒤집기(A8), 행군 진행 상태는 새 열/표 |
| V55 가신·부곡 | 재사용 + 의미 일반화 | 표는 맞다. 위치·자리·전투 참여·NPC 허용이 빠져 있다(A10·A11) |
| V56 작전 | **일반화** | 城 키·월 정산·`general.cityId` 이정표(A1·A6). 국가 키는 1층에서 유지 |
| V57 `battle_plan` | 재사용(城 강공 한정) | spec §5.1 과 같은 결론. 야전·방어 대응은 새 표 |
| V57 `battle_replay` | **일반화** | `defender_city_*` NOT NULL 을 풀고 省·방어측 열 추가(R8) |
| V58 | 그대로 | 유일 카드 원칙과 동일 |
| V59 열 3개 + 전장 카탈로그 + `che_전장이동` | **일반화하지 않는다 — 장판·관도 선례로 동결.** 새 조우는 이 위에 짓지 않는다 | 조우 단위가 siteId(A9), 귀환이 城(R9), 진입점이 삼모 명령(A3). 세 가지 모두 새 설계와 방향이 반대다 |
| V59 가 끌어온 **재사용 자산** | 재사용 | `processFieldWar` 커널, `applyGeneralPositionAssessment` CAS 경로, 「주둔 중 도시 행동 차단」 배선 지점 목록(§1.2 끝) — 새 「행군 중」 상태가 막아야 할 곳의 지도다 |

버리는 표·열은 없다. V59 열은 NULL 로 남겨 두면 새 경로에 간섭하지 않는다(`MIG/V59:6-8` CHECK 가 전부 NULL 을 허용).

### 4.2 선행 리팩터가 필요한가 — 필요하다, 하나

**위치의 정본을 정하는 일**이 다른 모든 1층 전쟁 작업 앞에 온다. 지금은 `general.city_id` 가 정본이고 공간 위치가 그것을 따라간다(A8). 행군·조우·省 보급 차단·작전 이정표(R2·R3·R4·R11·R12)가 전부 「장수가 어느 省에 있는가」를 읽으므로, 이 방향이 정해지기 전에 나머지를 지으면 두 위치가 어긋난다. 이것은 Tier-0 기반 작업이며(저장소 규칙 「Foundation-first」) 병렬 레인으로 쪼갤 수 없다.

### 4.3 제안 순서(S3 첫 묶음)

1. **위치 정본화.** 월드 개시 때 전 장수 위치 행 시드, 城 ↔ 省 동기 방향 확정, 省 없는 城의 처리 결정(코드 분기는 `HistoricalBattlefieldCatalog.kt:25-30` 에 있으나 동봉 1133 리소스에서는 해당 城 0건 — §5-7). 기존 CAS·flush·부팅 검증은 그대로 쓴다.
2. **省 행군.** `StrategicPathResolver` 재사용 + 간선 비용에 거리·지형(지금은 1 고정) + 진행 중 경로 저장. 템포 값은 `data/curated/han/march-tempo-targets-v1.json`(승인 기준선)과 S2 시뮬레이션에서 온다 — 이 문서는 값을 정하지 않는다.
3. **省 조우 + 야전 기록.** `processFieldWar` 를 省 진입 트리거에 연결, 시드 입력을 省·위상 해시로, 패자는 직전 省으로. `battle_replay` 일반화(R8)를 같은 묶음에 넣어야 야전이 처음부터 리플레이를 남긴다.
4. **작전 일반화.** 이정표 입력을 省 위치로, 정산을 순 경계로. 1–3 이 있어야 의미가 있다.
5. **省 점유·보급 차단.** `province_control` 생산자와 `StrategicSupplyNetwork` 입력 추가(R12).
6. 휘하의 전투 참여·NPC 허용(R13·R15), 포위 상태 신규 스키마(R10), 야전 계획·방어 대응 신규 스키마(R7)는 3 뒤 어느 순서로든 붙일 수 있다.

새 입력의 진입점은 `che_*` 예약 명령이 아니라 새 경로여야 한다(A3). 다만 spec §17 이 「pep 전환 전까지 기존 명령 입력 경로는 … 살아 있어야 한다」고 하므로 `che_전장이동`·출병 훅은 **지우지 않고** 월드 규칙으로 갈라 둔다.

### 4.4 회귀 기준선에 주는 영향

이 판정대로라면 V59·V57 의 기존 테스트(`BattlefieldTurnHandlerTest`, `BattlefieldMovementRulesTest`, `ProcessFieldWarTest`, `ReservedTurnBattlePlanTest`, `SpatialStatePersistenceIT` 등)는 손대지 않는다. `battle_replay` 열 제약을 푸는 마이그레이션만 `BattlePlanReplayFlushIT` 에 닿는다 — 기존 행은 전부 城 방어 기록이라 NULL 허용으로 바꿔도 값이 변하지 않는다(추론이며 실행 확인은 §5).

## 5. UNKNOWN

1. **라이브 pep DB 에 이 표들의 행이 있는지.** 위치·작전·계획·리플레이 행 수를 조회하지 않았다. pep 은 S3 뒤 초기화 예정이라(spec §15.2) 판정에는 영향이 없다고 보았으나 확인한 것은 아니다.
2. **`province_control` 의 의도된 의미.** 표 주석은 「Campaign-owned spatial state」, 코드 주석은 「Missing rows mean unknown, not neutral control」(`LOGIC/world/ProvinceControlState.kt:5`)뿐이다. 군사 점유인지 R1 소유 저장본인지 정한 spec 을 찾지 못했다 — `2026-09-06-province-front-and-county-capture-design.md` 는 이번에 읽지 않았다.
3. **테스트 현황.** 관련 테스트 파일의 존재만 확인했고 돌리지 않았다. §4.4 의 「닿지 않는다」는 호출 관계에서 추론한 것이다.
4. **web/game 프런트의 의존.** `/api/battlefields`·작전·계획 화면이 城 키에 얼마나 묶였는지 보지 않았다(백엔드만 조사).
5. **NPC AI 가 이 계층을 쓰지 않는 이유가 의도인지.** `LOGIC/ai/**` 에서 `Battlefield|generalPosition|operation|retainer|bugok|battlePlan`(대소문자 무시) 은 0건이다 — 사실은 확인했다. 결정성 때문에 일부러 뺀 것인지(핸들러 주석은 「결정성」을 든다, `RetainerRules.kt:65`, `BattlePlanRules.kt:45`) 단순 미구현인지는 구분하지 못했다.
6. **`StrategicEdgeStateSnapshot`(간선 상태·용량)의 생산자.** 확인된 생산자는 하나다 — `LOGIC/v2/command/V2CityTransportRoutes.kt:16-20` 이 토폴로지 리비전·해시와 **빈 간선 상태 맵**으로 직접 만든다. 즉 차단·용량을 실제로 채우는 생산자는 아직 없다. 다른 호출자(보급망·game-api)가 무엇을 넘기는지는 추적하지 않았다.
7. **외부 거점 城(省 없는 城)의 런타임 규모.** 동봉 리소스 `infra/src/main/resources/map/han-world-v3.json` 은 城 1,133개 전부가 `spatialProvinceId` 를 가진다(이번에 세어 0건). 다만 엔진은 부팅 때 고른 `hanWorldVariant` 아카이브를 쓰므로(`WorldSnapshotLoader.kt:204-209`), 옛 판 월드에서도 0건인지는 확인하지 않았다. S3 은 최신 판 새 월드라 예외가 없을 것으로 보이나 추론이다.
8. **V51·V53·V54.** 파일명만 보고 범위 밖으로 두었다. 내용은 읽지 않았다.
9. **이슈 번호.** 포트폴리오 계획 69행은 이 판정을 #790 / OPENSAM-270 으로 적는다. PR 제목의 OPENSAM-231 과의 관계는 확인하지 못했다.
