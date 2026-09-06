# 작전(Operation) 수직 절편 (Phase 4X-B) 설계 — v1

- Date: 2026-09-06
- Status: DRAFT — 교차 비평 대기(ADR-LITE-049 Phase 4X 규칙: spec → 비평 → 구현 → 게이트). 4X-A 스펙 v2(`2026-09-06-retinue-buqu-vertical-slice.md`)의 엔진 상태·flush 규약(메모리 세계 상태 + 행 단위 채널 + 툼스톤 + 8e 순서)을 그대로 상속한다.
- Scope: 로드맵 「작전 목표와 교전」의 첫 수직 절편 — 국가가 **작전 목표를 선언**하고, 장수가 **참여**하며, 달마다 **실제 점유·통제·보급 연결**에서 진척을 읽고, **기한**에 정산한다. 08 아트보드 「작전 진행」 패널, 14 회의실 「작전」 탭, 작전실 우측 상단 배지를 실제 원천에 연결한다.
- 원칙(OPENSAM-56 코멘트 2026-08-27, 로드맵): 강제로 공격시키지 않는다. 처리 순서 「목표 선언 → WEGO 명령 봉인 → 이동·통제권·목표 진척 → 요격·조우 → 접촉했을 때만 전투 → 점령·보급·기한 정산」 중 이 절편은 **목표 선언 · 진척 · 기한 정산** 만 구현한다. 봉인은 4X-C, 요격·조우·전투 접촉은 기존 전투 경로 그대로(작전은 전투를 만들지 않는다).
- Tickets: OPENSAM-56(#? 작전 계약·결정 규칙·adapter·검증) 체크리스트 중 **3-a operations · 3-b operation_participants(→ operation_unit) · 3-e 역할 정의 · 3-k fixture 일부** 만 이 절편이 기여한다 — 3-c routes · 3-d events · 3-f 도착 window · 3-g 경로 · 3-h 요격 · 3-i 원군 지연 · 3-j che_출병 adapter · 3-l event diff 0 gate 는 밖. 따라서 **OPENSAM-56 은 닫지 않고 코멘트만** 남긴다.
- 밖: 경로·호송·도착 window(beyond-che W2/W3), 요격·원군 지연, 부곡의 이동(4X-A 는 부곡이 주인과 함께 있다), 봉인·리플레이(4X-C), NPC 작전 AI(결정성 — NPC 는 선언·참여하지 않는다).

## 1. 원칙

1. **진척은 읽기다**: 진척은 저장된 숫자를 올리는 게 아니라 달마다 세계 상태(도시 소유 `city.nation`, 참여 장수의 위치 `general.cityId`, 보급 플래그, `ProvinceControlSnapshot`, `WaterControlSnapshot`)에서 **다시 계산**한다. 저장하는 것은 이정표 4개의 도달 여부(불리언)와 상태뿐이다.
2. **지도가 못 주는 것은 만들지 않는다**: 통제권·수역 데이터가 없는 지도(`che`: `provinceControlSnapshot() == null`)에서는 도로·보급로·통과·봉쇄 4종을 선언할 수 없다(거부 사유 「이 지도에는 통제권 데이터가 없습니다.」). 도시 점령·구원 2종만 선언 가능하다. 지어낸 통제권 % 는 없다.
3. **엔진만 쓴다 · 골든 불변**: 4X-A 규약과 동일. 작전 행이 없는 세계에서 월 정산은 산출물을 바꾸지 않는다(적색 프로브).
4. **수치는 잠정 상수**: `logic` `OperationRules` 한 곳. 응답 `rules.provisional = true`, UI 「잠정」 칩. 진척 % 는 「이정표 4개 중 도달 수 × 25」 라는 **표시 규칙**이지 모델이 아니다.

## 2. 도메인 (Flyway `V56__operation.sql`, 세계 범위 — V32 규약)

### operation
| 열 | 뜻 |
|---|---|
| `world_id`, `id INTEGER NOT NULL` — `PRIMARY KEY (world_id, id)` | 엔진 할당(`maxOperationId`, 4X-A 할당자 미러) |
| `nation_id INTEGER NOT NULL` — `FOREIGN KEY (world_id, nation_id) REFERENCES nation(world_id, id) ON DELETE CASCADE` | 선언 국가. 멸망 시 CASCADE + 메모리 툼스톤(`deletedNationIds`) |
| `kind VARCHAR(16) NOT NULL CHECK (kind IN ('capture_city','relieve','secure_route','cut_supply','pass_through','blockade'))` | 도시 점령 / 구원 / 도로 확보 / 보급로 차단 / 통과 / 봉쇄 |
| `target_city_id INTEGER NULL` — `FOREIGN KEY (world_id, target_city_id) REFERENCES city(world_id, id)` | capture_city · relieve · cut_supply 의 목표 도시 |
| `target_province_id VARCHAR(64) NULL` | secure_route · pass_through 의 목표 강역(`ProvinceControlSnapshot.knownProvinceIds`) |
| `target_water_zone_id VARCHAR(64) NULL` | blockade 의 수역(`WaterControlSnapshot.knownWaterZoneIds`) |
| `CHECK` | kind 별로 정확히 하나의 target 열만 NOT NULL |
| `title VARCHAR(40) NOT NULL` | 예 「낙양 공략」 |
| `fallback_text VARCHAR(200) NULL` | 대체 목표(08 아트보드 「대체 목표 · 호뢰관 도로 확보 및 보급로 차단」) — 텍스트만, 규칙 없음 |
| `declared_by_general_id INTEGER NOT NULL` — FK general CASCADE 아님(`ON DELETE SET NULL (declared_by_general_id)` 로 NULL 허용) | 선언자 |
| `declared_year/month/phase SMALLINT NOT NULL` | 선언 순 |
| `deadline_year/month/phase SMALLINT NOT NULL` | 기한 순 = 선언 순 + N순(`ServerClock.advance`) |
| `status VARCHAR(16) NOT NULL CHECK (status IN ('declared','active','achieved','failed','closed'))` | §5 전이 |
| `m_departed`, `m_arrived`, `m_supplied`, `m_objective BOOLEAN NOT NULL DEFAULT false` | 이정표 4개(§5). 한 번 true 가 되면 유지(단조) |
| `closed_reason VARCHAR(16) NULL CHECK (closed_reason IN ('achieved','deadline','command','nation_gone'))` | 종료 사유 |
| `created_at`, `updated_at TIMESTAMPTZ` | |
| `INDEX (world_id, nation_id)`, `INDEX (world_id, status)` | world 선행 |

### operation_unit
| 열 | 뜻 |
|---|---|
| `world_id`, `id` — PK, 엔진 할당(`maxOperationUnitId`) | |
| `operation_id` — `FOREIGN KEY (world_id, operation_id) REFERENCES operation(world_id, id) ON DELETE CASCADE` | |
| `general_id` — `FOREIGN KEY (world_id, general_id) REFERENCES general(world_id, id) ON DELETE CASCADE` | 참여 장수(본인 명령으로만) |
| `bugok_id INTEGER NULL` — `FOREIGN KEY (world_id, bugok_id) REFERENCES general_bugok(world_id, id) ON DELETE SET NULL (bugok_id)` | 편성에 부곡을 포함(병력 합산 표시용; 부곡은 이동하지 않는다) |
| `role VARCHAR(16) NOT NULL CHECK (role IN ('main','flank','scout','convoy','reserve'))` | OPENSAM-56 3-e 주공·조공·정찰·보급·예비 |
| `joined_city_id INTEGER NOT NULL` | 참여 시점 위치(이정표 「출발」 판정 기준) |
| `joined_year/month/phase SMALLINT NOT NULL` | |
| `UNIQUE (world_id, operation_id, general_id)` · `INDEX (world_id, general_id)` | 장수는 작전 하나에 한 번 |

### board_post
`ALTER TABLE board_post ADD COLUMN operation_id INTEGER NULL` + `FOREIGN KEY (world_id, operation_id) REFERENCES operation(world_id, id) ON DELETE SET NULL (operation_id)` + `INDEX (world_id, operation_id)`. `kind='operation'` 글은 `operation_id` 를 가질 수 있다(필수 아님 — V53 호환).

등록: `TruncateContract` 두 표, `V32WorldScopeCompletionMigrationTest.postV32WorldTables` 두 표(identity 아님 → `serialIdentityColumns` 미등록, world_state FK 무액션).

### 잠정 상수 (`opensamguk.logic.operation.OperationRules`)
| 이름 | 값 | 쓰임 |
|---|---|---|
| MAX_ACTIVE_PER_NATION | 3 | 국가당 진행(declared/active) 작전 상한 |
| MIN_DEADLINE_TURNS / MAX_DEADLINE_TURNS | 3 / 36 | 선언 시 기한(순) 범위 |
| MAX_UNITS | 12 | 작전당 참여 상한 |
| FAIL_ATMOS_LOSS | 5 | 기한 실패 시 참여 장수 사기(`atmos`) −5(하한 0) — 로드맵 「멈추면 사기·기회를 잃는다」 |
| FAIL_RICE_LOSS_RATE_PCT | 0 | 실패 시 군량 비용 — **0 으로 둔다**(군량은 4X-A 부곡·호송(W3)이 다루므로 여기서 지어내지 않는다) |
| PROGRESS_PER_MILESTONE_PCT | 25 | 표시 규칙(이정표 × 25) |

## 3. 엔진 상태 (4X-A §3 상속)

- `TurnWorldModel`: `data class Operation(id, nationId, kind, targetCityId?, targetProvinceId?, targetWaterZoneId?, title, fallbackText?, declaredByGeneralId?, declaredAt: GameDate, deadline: GameDate, status, milestones: OperationMilestones(departed, arrived, supplied, objective), closedReason?)`, `data class OperationUnit(id, operationId, generalId, bugokId?, role, joinedCityId, joinedAt: GameDate)`.
- `WorldSnapshot.operations/operationUnits` + `WorldSnapshotLoader` 적재. `InMemoryTurnWorld` map + dirty/created/deleted + `allocateOperationId/allocateOperationUnitId`(`maxGeneralId` 미러).
- **툼스톤 전파**: `deletedNationIds` 의 작전(+ 그 unit), `deletedGeneralIds` 의 unit — map·집합에서 제거(DB CASCADE). `deletedBugokIds`(4X-A) 의 unit 은 `bugokId = null` UPDATE 기록(DB `SET NULL (bugok_id)` 와 동일 결과). 4X-A 의 `removeBugok` 가 unit 을 모른다 → `consumeDirtyState` 에서 bugok 툼스톤을 unit 에 전파하는 규칙을 **여기서** 추가한다(4X-A 코드 변경 1곳, 4X-A 테스트 영향 없음: 행 0).
- `DirtyState`/`FlushPayload`: `createdOperations, updatedOperations, deletedOperationIds, createdOperationUnits, updatedOperationUnits, deletedOperationUnitIds`. `JdbcFlushExecutor` **8f**(8e 뒤): `operationCreateMany → unitCreateMany → operationUpdate → unitUpdate → unitDeleteMany → operationDeleteMany`. board_post 의 `operation_id` 는 8d 의 board INSERT 열에 추가.

## 4. 명령(인테이크, 즉시 실행) — `TurnDaemonCommand` 4종 + `CommandWireMapper` + 디스패처 → `OperationHandler`

공통 게이트 순서(4X-A 와 동일): ① 장수 없음 → ② 접속 제한(`AccessLogThrottle`, 4종 모두) → ③ 입력 「올바르지 않은 입력입니다.」 → ④ 상태 게이트.

| 코드 | 인자 | ③ 입력 | ④ 상태 게이트 순서 | 효과 |
|---|---|---|---|---|
| `operationDeclare` | kind, targetCityId?/targetProvinceId?/targetWaterZoneId?, title, fallbackText?, deadlineTurns | kind ∉ 6종 / kind 에 맞는 target 하나만 / title trim 2~40 / deadlineTurns ∉ [MIN, MAX] | 1 재야(nationId 0) 「국가에 소속되어 있지 않습니다.」 → 2 권한 `SecretPermission.check(me) < 2` 「수뇌부만 선언할 수 있습니다.」 → 3 지도 `kind ∈ {secure_route,cut_supply,pass_through,blockade}` 인데 `provinceControlSnapshot()==null`(blockade 는 `waterControlSnapshot()==null`) 「이 지도에는 통제권 데이터가 없습니다.」 → 4 target 존재(도시 id · 강역 id ∈ known · 수역 id ∈ known) 「목표를 찾을 수 없습니다.」 → 5 capture_city 인데 이미 아군 도시 「이미 아군 도시입니다.」 / relieve 인데 아군 도시 아님 「아군 도시가 아닙니다.」 → 6 상한 「진행 중인 작전이 가득 찼습니다.」 | `createOperation(status=declared, milestones 전부 false, declaredAt=현재 순, deadline=advance(현재, N))`; 국가 기록 `LogEntryDraft(scope="nation", category="action", nationId, text="<Y>{title}</> 작전을 선언했습니다.")`; 결과 `id` |
| `operationJoin` | operationId, role, bugokId? | role ∉ 5종 / 정수 | 1 작전 없음·타국 「작전이 없습니다.」 → 2 status ∉ {declared, active} 「종료된 작전입니다.」 → 3 이미 참여 「이미 참여 중입니다.」 → 4 상한 「작전 편성이 가득 찼습니다.」 → 5 bugokId 있으면 내 부곡 아님 「부곡이 없습니다.」 | `createOperationUnit(joinedCityId = me.cityId)`; declared → active(첫 참여) UPDATE; 결과 `id` |
| `operationLeave` | operationId | 정수 | 1 작전 없음 → 2 미참여 「참여하지 않은 작전입니다.」 | `removeOperationUnit`; 참여 0 이 돼도 status 유지(active) |
| `operationClose` | operationId | 정수 | 1 작전 없음·타국 → 2 권한 < 2 「수뇌부만 종료할 수 있습니다.」 → 3 이미 종료 | `status=closed, closedReason=command` UPDATE; 국가 기록 「… 작전을 종료했습니다.」 |

`boardArticle` 인자에 `operationId?` 추가(kind=operation 일 때만 허용, 내 국가 작전이어야 함 — 아니면 「작전이 없습니다.」). 결과 타입 `OperationActionResult(type, ok, generalId, reason?, id?)`(4X-A `RetainerActionResult` 와 같은 꼴, sealed 등록).

## 5. 월 정산 (`OperationMonthlyService.settle(world, recorder, now: GameDate)`)

- **배치**: `MonthlyPostUpdateHook.run` 마지막 — 4X-A `retainerMonthly?.settle` **뒤**(부곡 정산이 먼저, 그 다음 작전). 생성자 `operationMonthly: OperationMonthlyService? = null`(null = 미배선, 테스트 전용).
- **행 0 즉시 반환**.
- status ∈ {declared, active} 인 작전마다(id 오름차순) 이정표를 **다시 계산**하고 true 는 유지(단조):
  - `departed`: unit 중 하나라도 `general.cityId != unit.joinedCityId`, 또는 이미 목표 도시에 있음.
  - `arrived`: capture_city·relieve·cut_supply → unit 중 하나라도 `general.cityId == targetCityId`. secure_route·pass_through → `generalPositionSnapshot()?.statesByGeneralId[general.id]?.node` 가 목표 강역 노드(없으면 false — 데이터 없음을 진척으로 치지 않는다). blockade → `waterControlSnapshot().stateFor(zone).nationId == nationId`(도착=통제).
  - `supplied`: unit 중 하나라도 **아군 도시이면서 보급 연결된 도시**에 있음(`city.nation == nationId && city.supply`(LogicEntities 의 보급 플래그 — 구현 시 필드명 확인·핀)). 이 플래그는 같은 월 정산의 `UpdateCitySupply` 가 이미 갱신한 값이다(L9/L10 순서상 먼저 돈다 — 구현 시 `MonthlyPostUpdateHook` 안 순서를 테스트로 핀).
  - `objective`: capture_city → `city(target).nation == nationId`. relieve → 기한 순에 도달했을 때 `city(target).nation == nationId` 이면 true(기한 전에는 false). secure_route → `provinceControlSnapshot().stateFor(targetProvinceId)?.nationId == nationId`. cut_supply → 목표 도시가 그 소유 국가의 보급망에서 끊김(`!city(target).supply && city(target).nation != nationId`). pass_through → arrived 와 동일. blockade → arrived 와 동일(통제 = 달성).
  - `declared` 인데 unit 0 이면 이정표 계산을 건너뛴다(변화 없음).
- **전이**(같은 정산 안, 위 계산 뒤): `objective` → `achieved`(closedReason=achieved, 국가 기록 「<Y>{title}</> 작전 목표를 달성했습니다.」). 아니면 `now >= deadline`(연·월·순 비교) → `failed`(closedReason=deadline, 참여 장수 전원 `atmos = max(0, atmos − FAIL_ATMOS_LOSS)` F1 경로, 국가 기록 「… 작전이 기한을 넘겨 실패했습니다.」, 참여 장수 개인 기록 「작전 실패로 사기가 떨어졌습니다.」). 값이 하나도 안 바뀌면 dirty 아님.
- 종료 상태(achieved/failed/closed)는 다시 보지 않는다. unit 행은 남긴다(기록).

## 6. 읽기 API (game-api) — `GameApiSecurityConfig` `.authenticated()` 등록

- `GET /api/operations`(내 국가): 401 익명 · 재야 → `{operations: []}` · 200 `{nationId, operations:[{id, kind, kindLabel, title, fallbackText, target:{kind, id, name}, status, declaredAt:{year,month,phase}, deadline:{…}, remainingTurns, progressPct, milestones:{departed,arrived,supplied,objective}, units:[{id, generalId, name, role, roleLabel, crew, crewTypeName, bugokTroops, cityId, cityName, portrait}], declaredBy:{generalId,name}, boardPostIds:[…]}], rules:{maxActivePerNation, minDeadlineTurns, maxDeadlineTurns, maxUnits, failAtmosLoss, progressPerMilestonePct, provisional:true}, mapCapabilities:{provinceControl:boolean, waterControl:boolean}}`. `remainingTurns` 는 `ServerClock` 순 산술. `progressPct = 도달 이정표 수 × PROGRESS_PER_MILESTONE_PCT`.
- `GET /api/operations/{id}`: 같은 꼴 + `boardPosts:[{id,title,authorName,createdAt}]`(kind=operation & operation_id). 타국 작전 403(정보 누출 방지 — 작전은 국가 내부 정보다).
- `mapCapabilities` 는 엔진 상태 API(기존 `/api/server-basic-info` 류가 아니라 game-api 가 `province_control`/`water_zone_control` 표 존재 행 > 0 으로 판단 — 구현 시 실제 판단원을 핀; 없으면 UI 는 4종 선언 버튼을 사유 있는 disabled 로 그린다).

## 7. UI

- **08 국가 운영 「작전 진행」 패널**(`/game/nation` 또는 08 대응 화면): 작전 카드 — 제목·상태 칩·「기한 N순 · 남은 M순」·목표·대체 목표·진척 `Gauge`(progressPct, 라벨 「이정표 k/4」)·이정표 4개 체크(출발/도달/보급/목표)·참여 부대 행(`Portrait icon-40`, 이름, 역할 칩, 병력·병종, 현재 도시)·「참여」「이탈」「종료」(권한 없으면 disabled+사유). 「작전 선언」 폼: 종류(6종, 지도 미지원 4종은 disabled + 「이 지도에는 통제권 데이터가 없습니다」)·목표(도시 select / 강역·수역 select)·제목·대체 목표·기한(순, rules 범위)·잠정 칩. 「작전 흐름 목표 선언 → WEGO 봉인 → 이동·통제 → 접촉 시 전투 → 정산」 안내는 아트보드 문구 그대로(봉인·전투 단계는 「4X-C」 라벨).
- **14 회의실 「작전」 탭**: `kind=operation` 글에 작전 칩(제목·상태·진척) + 글쓰기 폼에 「연결 작전」 select(내 국가 진행 작전). 표결 글의 결정 기록은 이 절편 밖(계획 「표결 결과 → operation 메모」 는 UNKNOWN 으로 남긴다 — memo 열이 없다).
- **작전실 우측 상단 배지**: 진행 중 작전 수 + 가장 임박한 기한(「낙양 공략 · 4순 남음」) → `/game/nation#operations`. 없으면 표시하지 않는다(빈 자리 금지 원칙상 「작전 없음」 점선·사유 「수뇌부가 선언하면 나옵니다」).
- 명령은 모두 `CommandModal` pinnedCommand + extraArgs, 202 ≠ 성공.

## 8. 테스트·게이트

- logic `OperationRulesTest`: 이정표·전이 순수 함수 표(6 kind × 도달/미도달, 기한 도달, relieve 기한 규칙), 게이트 순서 표(두 조건 동시 위반), 상수 단언 없음.
- engine `OperationIntakeTest`: 4 명령 채널·툼스톤(국가 멸망·장수 삭제·부곡 해산 → unit bugokId NULL)·같은 틱 선언→참여(id 즉시).
- engine `OperationMonthlyNoopGateTest`(**적색 프로브**): 행 0 산출물 동일 / 작전 1행 상이. 4X-A 훅과의 **순서**(부곡 → 작전) 핀.
- engine **관리자 개입 0 시뮬레이션**(계획 4X-B 게이트): fixture 세계(che 지도, 도시 2·국가 2·장수 3)에서 선언 → 참여 → 장수가 목표 도시로 이동하는 기존 명령(출병/이동 예약 경로 그대로) → 점령 → 다음 월 정산에서 achieved. 관리자 명령·SQL 0. 같은 fixture 로 기한 초과 → failed·atmos −5 도 한 건.
- infra `OperationFlushIT`: V56 DDL·8f 순서·`SET NULL (bugok_id)`/`(operation_id)`/`(declared_by_general_id)` 의 world_id 보존·V32 인벤토리.
- game-api `OperationReadControllerTest`: 401 / 재야 빈 목록 / 200 / 타국 403 / `mapCapabilities` / `rules.provisional`.
- vitest: 08 패널·선언 폼(지도 미지원 disabled 사유)·14 탭 칩·작전실 배지.

## 9. 마이그레이션·순서

V56 = 이 절편(4X-A V55 뒤에만 적용 가능 — `general_bugok` FK). 4X-C = V57.

## 10. UNKNOWN

- `city.supply` 의 실제 필드명과 `UpdateCitySupply` 가 L9(MONTH 이벤트)인지 L10 인지 — 구현 시 확인해 §5 「supplied」 순서를 핀.
- `mapCapabilities` 의 판단원(game-api 가 표를 세는지, 엔진 상태 API 가 있는지).
- 표결 결과 → 작전 메모(계획 문구): 열이 없어 이 절편 밖. 필요하면 V57 이후.
- 작전과 4X-C 봉인의 연결(`battle_plan.operation_id`)은 4X-C 스펙에서.
