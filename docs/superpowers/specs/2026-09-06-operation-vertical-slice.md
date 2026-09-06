# 작전(Operation) 수직 절편 (Phase 4X-B) 설계 — v2

- Date: 2026-09-06
- Status: REVISED v2 — 교차 비평 1차(`docs/superpowers/reviews/2026-09-06-operation-spec-critique.md`, fix-required F1~F4 · should-fix S1~S12 · 질문 Q1~Q6)를 전부 반영. 통합 재판정 대기(ADR-LITE-049 Phase 4X 규칙: spec → 비평 → 구현 → 게이트). 4X-A 스펙 **v3**(`2026-09-06-retinue-buqu-vertical-slice.md`)의 엔진 상태·flush 규약(메모리 세계 상태 + 행 단위 채널 + `removeGeneral` 즉시 가지치기 + 표마다 DELETE→CREATE→UPDATE)을 그대로 상속한다.
- Scope: 로드맵 「작전 목표와 교전」의 첫 수직 절편 — 국가가 **작전 목표를 선언**하고, 장수가 **참여**하며, 달마다 **실제 점유·통제·보급 연결**에서 진척을 읽고, **기한**에 정산한다. 08 아트보드 「작전 진행」 패널, 14 회의실 「작전」 탭, 작전실 우측 상단 배지를 실제 원천에 연결한다.
- 원칙(OPENSAM-56 코멘트 2026-08-27, 로드맵): 강제로 공격시키지 않는다. 처리 순서 「목표 선언 → WEGO 명령 봉인 → 이동·통제권·목표 진척 → 요격·조우 → 접촉했을 때만 전투 → 점령·보급·기한 정산」 중 이 절편은 **목표 선언 · 진척 · 기한 정산** 만 구현한다. 봉인은 4X-C, 요격·조우·전투 접촉은 기존 전투 경로 그대로(작전은 전투를 만들지 않는다).
- Tickets: OPENSAM-56(#? 작전 계약·결정 규칙·adapter·검증) 체크리스트 중 **3-a operations · 3-b operation_participants(→ operation_unit) · 3-e 역할 정의 · 3-k fixture 일부** 만 이 절편이 기여한다 — 3-c routes · 3-d events · 3-f 도착 window · 3-g 경로 · 3-h 요격 · 3-i 원군 지연 · 3-j che_출병 adapter · 3-l event diff 0 gate 는 밖. 따라서 **OPENSAM-56 은 닫지 않고 코멘트만** 남긴다.
- 밖: 경로·호송·도착 window(beyond-che W2/W3), 요격·원군 지연, 부곡의 이동(4X-A 는 부곡이 주인과 함께 있다), 봉인·리플레이(4X-C), NPC 작전 AI(결정성 — NPC 는 선언·참여하지 않는다).

## 0. 비평 반영표

| 항목 | 결정 |
|---|---|
| **F1 생산자 없는 3종** | 선언 가능 종류를 **`capture_city`·`relieve`·`cut_supply`** 로 한정한다(모든 지도 — 세 술어는 도시 필드만 읽는다). `secure_route`·`pass_through`·`blockade` 는 열·CHECK 만 두고 선언 게이트가 「아직 선언할 수 없는 작전 종류입니다.」 로 거부한다. 게이트의 축은 「스냅샷 != null」 이 아니라 **`OperationRules.DECLARABLE_KINDS`(생산자 존재)** 다. 강역·수역·장수 위치 상태의 유일한 쓰기 경로(`apply*Assessment`)는 main 호출자 0 — beyond-che W2/W3 생산자가 붙을 때 해제(§10). |
| **F2 선언자 수명** | `declared_by_general_id INTEGER NULL` + `ON DELETE SET NULL (declared_by_general_id)`. 메모리: `InMemoryTurnWorld.removeGeneral` 이 그 장수가 선언자인 작전의 `declaredByGeneralId = null` 로 바꾸고 dirty 로 표시(4X-A v3 N2 와 같은 자리). 읽기 API `declaredBy` 는 null 허용. |
| **F3 board FK vs flush 순서** | 작전 채널 위치를 **제약으로** 적는다: 「7단계 UPDATE 뒤 · **8d board INSERT 앞**」(부모 general/nation/bugok 은 그 앞에서 끝난다). 같은 틱 「선언 → 글 연결」 이 FK 를 만족. 단계 라벨은 executor 주석의 다음 자유 번호를 구현 시 따른다(글자 아님). |
| **F4 순 단위 기한 vs 월 정산** | 기한을 **월 단위**로 둔다: `deadline = 선언 순 + N개월` 을 **다음 상순(phase 1)** 으로 정규화. 상수는 `MIN/MAX_DEADLINE_MONTHS`. 정산 위치(L10) 불변. `GameDate` 비교는 `OperationRules.absoluteTurn(date) = year × 36 + (month − 1) × 3 + (phase − 1)`(`GameConst` 의 1년 36순) 로 한다. 표시는 `remainingMonths`(정산 기준, 0 이면 「이번 상순 정산」). relieve 는 기한 월 정산에서 관측(항상 상순). |
| S1 필드명 | `city.nationId`, `city.supplyState != 0`(`MonthlyPostUpdateHook` 선례), `general.cityId`. 예약 3종 술어(부록 A)는 `ProvinceControlState.nationId`, `WaterControlState.controllingNationId: Long?`·`blockadeState`, `StrategicNodeRef.LandProvince(id)`, nullable `stateFor` 로 고쳐 둔다. |
| S2 UpdateCitySupply | L5 **PRE_MONTH**(옛 날짜) — `supplyState` 는 L10 시점에 이미 갱신돼 있다(결론 유지). 규칙: `supplied` 는 **전월 기준 BFS** 이고 같은 달 L10 안의 소유 변화는 다음 달에 반영된다(한 달 지연을 명문화). 핀 테스트는 L5 → L10 순서. |
| S3 권한 원천 | 엔진 `SecretPermission.check(me)`(−1..4) 한 곳. game-api 는 `SecretPermissionReader` 로 같은 값을 읽어 `myPermission` 으로 내보내고 UI disabled 판정은 그 값만 쓴다(officerLevel 파생 금지). 문자열은 board 와 동일: 재야 「국가에 소속되어있지 않습니다.」, 권한 「권한이 부족합니다. 수뇌부가 아닙니다.」. |
| S4 로그 채널 | 국가 기록은 `LogEntryDraft(scope="nation", category="history", nationId)`(읽기 경로 `NationLogReadRepository` 가 HISTORY 만 본다). 개인 기록은 `scope="general", category="action", generalId`. |
| S5 mapCapabilities | 삭제. 대신 `rules.kinds:[{kind, label, declarable, reason?}]` — `declarable` 은 `DECLARABLE_KINDS` 에서, `reason` 은 예약 3종 거부 문자열. 지도 이름 판단 불필요(3종은 모든 지도). |
| S6 계획표 | 계획 `:53` 을 「OPENSAM-56 코멘트(3-a·3-b·3-e·3-k 일부), OPENSAM-228 결정 기록은 밖」 으로, `:352` 4X-C 문구를 「`battle_replay.operation_id` 기록만, 진척은 이정표 재계산이 도시 소유로 본다」 로 고친다(이 커밋). |
| S7 역할 라벨 | `roleLabel`: main=본대(주공) · flank=별동(조공) · scout=정찰 · convoy=호송(보급) · reserve=예비. 아트보드 08 의 본대·별동·호송이 1차 표기, Jira 3-e 명칭은 괄호. |
| S8 ADR 관계 | `operation.id` = ADR-LITE-032 `operationId`(4X-C `battle_plan.operation_id`·`battle_replay.operation_id` 가 이 키). 이정표 4개 ≠ ADR-037 `phases[]`(전투 안 단계 축). ADR-045 network-revision pin 은 예약 3종의 생산자와 함께 온다(§10). |
| S9 지어낸 수치 | `progressPct` → **`milestoneDisplayPct`**(파생임을 이름에). UI 1차 표기는 「이정표 k/4」, % 는 보조. 아트보드의 「통제권 호뢰관 도로 22%」 행은 **그리지 않는다**. `remainingMonths` 정의는 F4. |
| S10 id 미러 | 4X-A v3 N3 와 동일한 네 지점(`TurnRunService` 키 공급 · `JdbcFlushExecutor` meta 병합 두 곳 · `WorldSnapshotLoader` 허용 키 · `InMemoryTurnWorld` 시드). 행 0 세계 바이트 동일을 지키려 **값 > 0 일 때만 키를 쓴다**. |
| S11 국가를 바꾼 참여자 | 정산 시작에 `general.nationId != operation.nationId` 인 unit 을 제거(DELETE 기록)하고 술어·벌점에서 제외. 순수 함수 표에 한 행. |
| S12 빈칸 | §5 에 kind × 이정표 4 표. declared + unit 0 은 기한에 `failed`(사기 효과 0, 의도). 같은 틱 join→leave 는 `removeTroop` 규칙(DB 작업 0). 장수는 **진행 중 작전 하나에만** 참여(「이미 다른 작전에 참여 중입니다.」). |
| Q1 병합 순서 | 4X-B 는 4X-A(V55) 뒤에만 적용된다. 4X-A 가 늦으면 `operation_unit.bugok_id` 열·FK 를 V57 로 이월하고 `bugokId` 인자를 받지 않는다(폴백을 §9 에 명시). |
| Q2 4X-C | S6 의 인터페이스 한 줄. |
| Q3 08 라우트 | 08 「국가 운영」 = `/game/my-nation`(Phase 4B 가 「세력 정보」 hero·19 KV 로 재작성한 화면). 작전 패널은 그 아래. |
| Q4 han cut_supply | UNKNOWN 유지: han 지도의 `supplyState` 는 공간 그래프와 도시 그래프의 결합 판정이다 — 이 절편의 게이트는 che fixture 로만 실측하고 han 은 fixture 없음을 §8 에 적는다. |
| Q6 적색 프로브 범위 | `consumeDirtyState()`·recorder 패치·로그·**worldState 행(meta 키 포함)** 까지 deep-equal. S10 「값 > 0 일 때만」 이 이를 보장한다. |

## 1. 원칙

1. **진척은 읽기다**: 진척은 저장된 숫자를 올리는 게 아니라 달마다 세계 상태(도시 소유 `city.nation`, 참여 장수의 위치 `general.cityId`, 보급 플래그, `ProvinceControlSnapshot`, `WaterControlSnapshot`)에서 **다시 계산**한다. 저장하는 것은 이정표 4개의 도달 여부(불리언)와 상태뿐이다.
2. **생산자가 없는 것은 만들지 않는다**: 강역 통제·수역 통제·장수 위치는 플레이가 쓰는 경로가 아직 없다(`apply*Assessment` 의 main 호출자 0). 그래서 이 절편의 선언 가능 종류는 **도시 점령·구원·보급로 차단** 3종(`OperationRules.DECLARABLE_KINDS`, 술어는 도시 필드만)이고, 도로 확보·통과·봉쇄 3종은 예약(선언 거부 「아직 선언할 수 없는 작전 종류입니다.」, 부록 A 에 술어만 적어 둔다). 지어낸 통제권 % 는 없다.
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
| `declared_by_general_id INTEGER NULL` — `FOREIGN KEY (world_id, declared_by_general_id) REFERENCES general(world_id, id) ON DELETE SET NULL (declared_by_general_id)` | 선언자(장수 삭제 시 NULL — DB·메모리 양쪽, F2) |
| `declared_year/month/phase SMALLINT NOT NULL` | 선언 순 |
| `deadline_year/month/phase SMALLINT NOT NULL`, `CHECK (deadline_phase = 1)` | 기한 = 선언 순 + N개월을 **다음 상순으로 정규화**(F4; `OperationRules.deadlineFor(declaredAt, months)`) |
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
| MIN_DEADLINE_MONTHS / MAX_DEADLINE_MONTHS | 1 / 12 | 선언 시 기한(개월) 범위 — 정산은 월 경계뿐이므로 순 단위 기한은 두지 않는다(F4) |
| MAX_UNITS | 12 | 작전당 참여 상한 |
| FAIL_ATMOS_LOSS | 5 | 기한 실패 시 참여 장수 사기(`atmos`) −5(하한 0) — 로드맵 「멈추면 사기·기회를 잃는다」 |
| FAIL_RICE_LOSS_RATE_PCT | 0 | 실패 시 군량 비용 — **0 으로 둔다**(군량은 4X-A 부곡·호송(W3)이 다루므로 여기서 지어내지 않는다) |
| DECLARABLE_KINDS | {capture_city, relieve, cut_supply} | 생산자 있는 종류(F1). 상수가 아니라 **집합** — 예약 3종은 생산자 PR 이 여기에 더한다 |
| MILESTONE_DISPLAY_PCT | 25 | 표시 규칙(`milestoneDisplayPct = 도달 수 × 25`, 파생값 — S9) |

## 3. 엔진 상태 (4X-A §3 상속)

- `TurnWorldModel`: `data class Operation(id, nationId, kind, targetCityId?, targetProvinceId?, targetWaterZoneId?, title, fallbackText?, declaredByGeneralId?, declaredAt: GameDate, deadline: GameDate, status, milestones: OperationMilestones(departed, arrived, supplied, objective), closedReason?)`, `data class OperationUnit(id, operationId, generalId, bugokId?, role, joinedCityId, joinedAt: GameDate)`.
- `WorldSnapshot.operations/operationUnits` + `WorldSnapshotLoader` 적재. `InMemoryTurnWorld` map + dirty/created/deleted + `allocateOperationId/allocateOperationUnitId`. id 고수위 영속은 4X-A v3 N3 의 네 지점(`TurnRunService` 키 공급 · `JdbcFlushExecutor` meta 병합 두 곳 · `WorldSnapshotLoader` 허용 키 · 시드)을 같이 넓히되 **값 > 0 일 때만 키를 쓴다**(행 0 세계 `world_state` 바이트 동일 — S10·Q6).
- **툼스톤 전파(4X-A v3 규약 = 제거 시점에 즉시)**: `removeNation` 이 그 국가의 작전(+ unit)을, `removeGeneral` 이 그 장수의 unit 을 map·집합에서 제거하고 **그 장수가 선언자인 작전의 `declaredByGeneralId = null` 을 dirty 로 표시**한다(F2). `removeBugok`(4X-A) 은 그 부곡을 가리키는 unit 의 `bugokId = null` UPDATE 를 기록한다(DB `SET NULL (bugok_id)` 와 동일 결과) — 4X-A 코드 변경 1곳, 행 0 이면 영향 없음. 같은 틱 `operationJoin → operationLeave` 는 `removeTroop` 규칙(이번 틱 생성 행은 DELETE 기록 없음).
- `DirtyState`/`FlushPayload`: `createdOperations, updatedOperations, deletedOperationIds, createdOperationUnits, updatedOperationUnits, deletedOperationUnitIds`. `JdbcFlushExecutor` 위치는 **제약으로**: 「7단계 UPDATE 뒤 · 8d board_post INSERT **앞**」(F3 — 같은 틱 「선언 → `boardArticle(operationId)`」 가 FK 를 만족; general/nation/bugok 부모는 그 앞에서 끝난다). 순서는 4X-A v3 와 같이 표마다 DELETE → CREATE → UPDATE: `unitDeleteMany → operationDeleteMany → operationCreateMany → unitCreateMany → operationUpdate → unitUpdate`(unit DELETE 를 operation DELETE 앞에 두어 CASCADE 와 겹쳐도 무해). 모든 채널 `isNotEmpty()` 가드. board_post 의 `operation_id` 는 8d 의 INSERT 열에 추가. `operation.id` = ADR-LITE-032 `operationId`(4X-C 가 같은 키를 쓴다); 이정표 4개는 ADR-037 `phases[]` 와 다른 축이다(S8).

## 4. 명령(인테이크, 즉시 실행) — `TurnDaemonCommand` 4종 + `CommandWireMapper` + 디스패처 → `OperationHandler`

공통 게이트 순서(4X-A 와 동일): ① 장수 없음 → ② 접속 제한(`AccessLogThrottle`, 4종 모두) → ③ 입력 「올바르지 않은 입력입니다.」 → ④ 상태 게이트. 권한은 엔진 `SecretPermission.check(me)`(−1..4) 한 곳에서만 읽는다(S3).

| 코드 | 인자 | ③ 입력 | ④ 상태 게이트 순서 | 효과 |
|---|---|---|---|---|
| `operationDeclare` | kind, targetCityId, title, fallbackText?, deadlineMonths | kind ∉ 6종 / targetCityId 정수 / title trim 2~40 / fallbackText ≤ 200 / deadlineMonths ∉ [MIN, MAX] | 1 `check == -1` 「국가에 소속되어있지 않습니다.」 → 2 `check < 2` 「권한이 부족합니다. 수뇌부가 아닙니다.」 → 3 kind ∉ `DECLARABLE_KINDS` 「아직 선언할 수 없는 작전 종류입니다.」(F1) → 4 도시 없음 「목표를 찾을 수 없습니다.」 → 5 capture_city·cut_supply 인데 아군 도시 「이미 아군 도시입니다.」 / relieve 인데 아군 도시 아님 「아군 도시가 아닙니다.」 → 6 상한 「진행 중인 작전이 가득 찼습니다.」 | `createOperation(status=declared, milestones 전부 false, declaredAt=현재 순, deadline=deadlineFor(현재, N))`; 국가 기록 `LogEntryDraft(scope="nation", category="history", nationId, text="<Y>{title}</> 작전을 선언했습니다.")`; 결과 `id` |
| `operationJoin` | operationId, role, bugokId? | role ∉ 5종 / 정수 | 1 작전 없음·타국 「작전이 없습니다.」 → 2 status ∉ {declared, active} 「종료된 작전입니다.」 → 3 이 작전에 이미 참여 「이미 참여 중입니다.」 → 4 다른 진행 작전에 참여 중 「이미 다른 작전에 참여 중입니다.」(S12-d) → 5 상한 「작전 편성이 가득 찼습니다.」 → 6 bugokId 있으면 내 부곡 아님 「부곡이 없습니다.」 | `createOperationUnit(joinedCityId = me.cityId)`; declared → active(첫 참여) UPDATE; 결과 `id` |
| `operationLeave` | operationId | 정수 | 1 작전 없음 → 2 미참여 「참여하지 않은 작전입니다.」 | `removeOperationUnit`(이번 틱 생성이면 DB 작업 0); 참여 0 이 돼도 status 유지 |
| `operationClose` | operationId | 정수 | 1 작전 없음·타국 → 2 `check < 2` 「권한이 부족합니다. 수뇌부가 아닙니다.」 → 3 이미 종료 「종료된 작전입니다.」 | `status=closed, closedReason=command` UPDATE; 국가 기록 「<Y>{title}</> 작전을 종료했습니다.」 |

`boardArticle` 인자에 `operationId?` 추가(kind=operation 일 때만 허용, 내 국가의 작전이어야 함 — 아니면 「작전이 없습니다.」; 같은 틱에 선언한 작전도 허용 — F3 의 flush 순서가 보장). 결과 타입 `OperationActionResult(type, ok, generalId, reason?, id?)`(4X-A `RetainerActionResult` 와 같은 꼴; 직렬화기 왕복 테스트로 4 코드 핀).

## 5. 월 정산 (`OperationMonthlyService.settle(world, recorder, now: GameDate)`)

- **배치**: `MonthlyPostUpdateHook.run` 마지막 — 4X-A `retainerMonthly?.settle` **뒤**. 생성자 `operationMonthly: OperationMonthlyService? = null`(null = 미배선, 테스트 전용). `now` 는 L7 에서 올린 **새 날짜**(항상 상순).
- **행 0 즉시 반환**. 주인 국가를 그 시점에 `getNationById` 로 읽고 없으면 건너뛴다(방어선; 정상은 `removeNation` 가지치기).
- **참여자 정리(S11)**: 먼저 unit 마다 `general = getGeneralById(unit.generalId)`; 없거나 `general.nationId != operation.nationId` 이면 `removeOperationUnit`(DELETE 기록). 이후 술어·벌점은 남은 unit 만 본다.
- **`supplied` 의 원천**: `city.supplyState != 0` 은 L5 PRE_MONTH 의 `UpdateCitySupply`(전월 날짜 기준 도시 그래프 BFS)가 같은 틱 안에서 이미 갱신한 값이다(S2). 같은 달 L10 안의 소유 변화(해산·점령)는 다음 달 정산에서 보인다 — **한 달 지연이 규칙**이다.
- status ∈ {declared, active} 인 작전마다(id 오름차순) 이정표를 다시 계산하고 true 는 유지(단조). `declared` + unit 0 이면 계산을 건너뛴다(기한이 오면 `failed`, 사기 효과 0 — 의도, S12-b).

| kind | departed | arrived | supplied | objective |
|---|---|---|---|---|
| capture_city | unit 중 하나라도 `general.cityId != unit.joinedCityId` 또는 `== targetCityId` | unit 중 하나라도 `general.cityId == targetCityId` | unit 중 하나라도 `city(general.cityId).nationId == nationId && supplyState != 0` | `city(target).nationId == nationId` |
| relieve | 같음 | 같음 | 같음 | **기한 월 정산에서만** `city(target).nationId == nationId`(그 전엔 false) |
| cut_supply | 같음 | 같음 | 같음 | `city(target).nationId != nationId && city(target).supplyState == 0` |

- **전이**(같은 정산 안, 계산 뒤): `objective` → `achieved`(closedReason=achieved, 국가 기록 `history` 「<Y>{title}</> 작전 목표를 달성했습니다.」). 아니면 `absoluteTurn(now) >= absoluteTurn(deadline)` → `failed`(closedReason=deadline, 남은 unit 의 장수 전원 `atmos = max(0, atmos − FAIL_ATMOS_LOSS)` F1 경로, 국가 기록 「… 작전이 기한을 넘겨 실패했습니다.」, 개인 기록 `scope="general", category="action"` 「작전 실패로 사기가 떨어졌습니다.」). 값이 하나도 안 바뀌면 dirty 아님.
- 종료 상태(achieved/failed/closed)는 다시 보지 않는다. unit 행은 남긴다(기록).
- 순수 함수(`OperationRules`): `absoluteTurn(date)`, `deadlineFor(declaredAt, months)`, `remainingMonths(now, deadline)`, `milestones(kind, input)`, `transition(op, now)`. 정산 서비스는 결과를 메모리에 적용만 한다.

## 6. 읽기 API (game-api) — `GameApiSecurityConfig` `.authenticated()` 등록

- `GET /api/operations`(내 국가): 401 익명 · 재야 → `{operations: [], myPermission}` · 200 `{nationId, myPermission, operations:[{id, kind, kindLabel, title, fallbackText, target:{cityId, name}, status, declaredAt:{year,month,phase}, deadline:{year,month,phase}, remainingMonths, milestones:{departed,arrived,supplied,objective}, milestoneDisplayPct, units:[{id, generalId, name, role, roleLabel, crew, crewTypeName, bugokTroops, cityId, cityName, portrait}], declaredBy:{generalId,name}|null, boardPostIds:[…]}], rules:{maxActivePerNation, minDeadlineMonths, maxDeadlineMonths, maxUnits, failAtmosLoss, milestoneDisplayPct, provisional:true, kinds:[{kind, label, declarable, reason?}]}}`. `myPermission` 은 `SecretPermissionReader`(엔진 `SecretPermission` 과 같은 원천, S3). `roleLabel`: main=본대 · flank=별동 · scout=정찰 · convoy=호송 · reserve=예비(S7). `crewTypeName` 은 4X-A N6 가드.
- `GET /api/operations/{id}`: 같은 꼴 + `boardPosts:[{id,title,authorName,createdAt}]`(kind=operation & operation_id). 타국 작전 403.

## 7. UI

- **08 국가 운영 = `/game/my-nation`** 「작전 진행」 패널(Q3): 작전 카드 — 제목·상태 칩·「기한 {year}年 {month}月 상순 · 남은 N개월」·목표·대체 목표·**「이정표 k/4」**(1차) + `Gauge`(milestoneDisplayPct, 보조)·이정표 4개 체크(출발/도달/보급/목표)·참여 부대 행(`Portrait icon-40`, 이름, 역할 칩(본대·별동·정찰·호송·예비), 병력·병종, 현재 도시)·「참여」「이탈」「종료」(`myPermission < 2` 면 disabled + 「권한이 부족합니다. 수뇌부가 아닙니다.」). 아트보드의 「통제권 호뢰관 도로 22%」 행은 그리지 않는다(S9). 「작전 선언」 폼: 종류 6 개 중 `declarable=false` 3 개는 disabled + `reason`(「아직 선언할 수 없는 작전 종류입니다」)·목표 도시 select·제목·대체 목표·기한(개월, rules 범위)·「잠정」 칩. 「작전 흐름 …」 안내는 아트보드 문구 그대로(봉인·전투 단계는 「4X-C」 라벨).
- **14 회의실 「작전」 탭**: `kind=operation` 글에 작전 칩(제목·상태·이정표 k/4) + 글쓰기 폼에 「연결 작전」 select(내 국가 진행 작전). 표결 결정 기록은 밖(§10).
- **작전실 우측 상단 배지**: 진행 중 작전 수 + 가장 임박한 기한(「낙양 공략 · 2개월 남음」) → `/game/my-nation#operations`. 없으면 「작전 없음」 점선·사유 「수뇌부가 선언하면 나옵니다」.
- 명령은 모두 `CommandModal` pinnedCommand + extraArgs, 202 ≠ 성공.

## 8. 테스트·게이트

- logic `OperationRulesTest`: 이정표·전이 순수 함수 표(3 kind × 도달/미도달 · 기한 도달 · relieve 기한 규칙 · S11 타국 참여자 제거 · declared+unit 0 기한 실패), 게이트 순서 표(두 조건 동시 위반; 예약 3종 거부), `absoluteTurn`/`deadlineFor`/`remainingMonths` 표. 상수 단언 없음.
- engine `OperationIntakeTest`: 4 명령 채널 · 툼스톤(국가 멸망 → 작전+unit 제거 · 장수 삭제 → unit 제거 + 선언자 NULL dirty · 부곡 해산 → unit bugokId NULL) · 같은 틱 선언 → 참여 → 글 연결(id 즉시) · 같은 틱 join → leave 가 DB 작업 0.
- engine `OperationMonthlyNoopGateTest`(**적색 프로브**, 4X-A v3 N4 구성): 두 벌 world+recorder, `ScriptedRng`/`auctionRepo()` fixture; 행 0 → `consumeDirtyState()`·recorder 패치·로그·worldState 행 deep-equal, 작전 1행 → 상이. 훅 순서(부곡 → 작전) 핀.
- engine **관리자 개입 0 시뮬레이션**: che fixture(도시 2·국가 2·장수 3)에서 선언 → 참여 → `ReservedTurnHandler.handle("che_이동"/"che_출병")` 로 실제 이동·점령 → 다음 월 정산에서 achieved. 관리자 명령·SQL 0. 같은 fixture 로 기한 초과 → failed·atmos −5. han 지도 fixture 는 없다(Q4 UNKNOWN 명시).
- infra `OperationFlushIT`(PG16): V56 DDL · 채널 위치(8d 앞) · 같은 틱 「선언 + 연결 글」 성공(F3) · 선언자 DELETE 와 같은 틱 작전 UPDATE → `world_id` 보존·`declared_by_general_id` NULL·flush 성공(F2) · `SET NULL (bugok_id)`/`(operation_id)` · V32 인벤토리.
- game-api `OperationReadControllerTest`: 401 / 재야 빈 목록 / 200 / 타국 403 / `rules.kinds` 의 declarable·reason / `myPermission` / `rules.provisional`.
- vitest: my-nation 패널(k/4 1차·22% 행 없음·예약 종류 disabled 사유)·선언 폼·14 탭 칩·작전실 배지.

## 9. 마이그레이션·순서

V56 = 이 절편, **V55(4X-A) 뒤에만** 적용(`general_bugok` FK). 4X-A 가 늦으면 `operation_unit.bugok_id` 열·FK 를 V57 로 이월하고 `operationJoin.bugokId` 인자를 받지 않는다(Q1 폴백). 4X-C = 그 다음 번호.

## 10. UNKNOWN · 예약

- han 지도의 `cut_supply`: `supplyState` 가 공간 그래프와 도시 그래프의 결합 판정이라 che 와 다를 수 있다 — han fixture 가 없어 실측하지 않았다(Q4).
- 예약 3종(`secure_route`·`pass_through`·`blockade`)은 강역·수역·장수 위치 **생산자**(beyond-che W2/W3)가 붙을 때 `DECLARABLE_KINDS` 에 더한다. ADR-045 의 network-revision pin(`target_province_id` 에 topologyRevision/hash)도 그때 열을 더한다.
- 표결 결과 → 작전 메모(계획 문구): 열이 없어 이 절편 밖.
- 4X-C 인터페이스: `battle_plan.operation_id`·`battle_replay.operation_id` 에 **기록만** 한다. 작전 행은 쓰지 않으며 진척은 이정표 재계산이 전투 결과(도시 소유)로 본다.

## 부록 A. 예약 종류의 술어(생산자가 붙을 때 그대로 쓴다 — S1 필드명 정정)

| kind | arrived | objective |
|---|---|---|
| secure_route | `generalPositionSnapshot()?.statesByGeneralId[general.id]?.node` 가 `StrategicNodeRef.LandProvince(targetProvinceId)` | `provinceControlSnapshot()?.stateFor(targetProvinceId)?.nationId == nationId`(nullable → false) |
| pass_through | 위와 같음 | arrived 와 같음 |
| blockade | `waterControlSnapshot()?.stateFor(targetWaterZoneId)` 의 `controllingNationId == nationId.toLong()` | 위 + `blockadeState == BLOCKED` |
`departed` 는 목표 도시가 없으므로 「unit 중 하나라도 `general.cityId != unit.joinedCityId`」 만 쓴다.
