# 후한 행정 지리·780성 수송망·커맨드 설계

- 작성일: 2026-08-22
- 상태: **제품 방향 승인 / 지리·밸런스 수치 미동결**
- 제품 결정: ADR-LITE-041·044·045
- 연동 정본: `2026-08-20-opensam-213-han-operation-movement-spec.md`, `2026-07-12-v2-command-catalog-and-rollout.md`

## 1. 서로 다른 두 지리 계층

후한 행정 지리와 플레이 공간의 개수를 하나로 합치지 않는다.

- **행정 카탈로그:** 《후한서》 권113 「군국지」의 순제기 기준은
  “凡郡、國百五，縣、邑、道、侯國千一百八十”이다. 1,180은 현만이 아니라 현·읍·도·후국을
  합친 현급 행정단위 수다. 시기별 명칭·소속·분합을 기록하는 역사 계층으로 보존한다.
- **현재 검출 상태:** 원문 구조 extractor는 105군국·1,180개를 전수 검출했다. 기존
  `data/map/junguozhi.json`은 그중 1,076개만 좌표 결합해 104개 join gap이 남는다. 상세 실측은
  `../research/2026-08-22-han-administrative-unit-detection-audit.md`를 따른다.
- **플레이 거점:** 제품 목표는 stable id 780개다. 다만 현재 선정은 결손 parser의 `zhi` 산술에
  의존하므로 개별 identity가 승인된 것은 아니다. reviewed selection manifest를 만들고 오류·중복 후보를
  교체하되 총 780개와 save identity migration을 명시적으로 관리한다.
- **군 치소:** 치소는 행정·집산·지휘 hub이지 나머지 플레이 거점을 대신하는 추상 node가 아니다.

따라서 1,180을 780으로 축소하거나 780을 1,180으로 자동 확장하지 않는다. 시나리오별
`AdministrativeUnit ↔ PhysicalPlace ↔ RouteNode` 매핑을 명시한다.

## 2. 수송망 결정

권장 구조는 **780 city node + 승인된 고정 geographic corridor + 변경 가능한 infrastructure state**다.

```text
RouteNode
  cityId, physicalPlaceId, administrativeUnitRefs[], hubRoles[]

GeographicCorridor
  corridorId, fromCityId, toCityId, modes[], geometryRef, provenanceRefs[]
  lifecycle: DRAFT | REVIEWED | ACTIVE | RETIRED

InfrastructureState
  corridorId, worldId, grade, condition, capacity, control, access, seasonalState
  infrastructureRevision, effectiveFromTurn
```

- 현재 작업트리 `han.json.connections`의 1,783개 무방향 edge는 **후보 topology snapshot**이다.
  과거 문서의 1,778과 5개 차이가 있으므로 count를 제품 불변식으로 동결하지 않는다. 검토 없이 실제
  도로·수로·해로의 위치나 등급으로 승격하지 않는다.
- 도로·관문·교량·나루·수로·해로는 geometry, provenance, revision을 가진 별도 데이터다.
- 모든 이동·출병·수송·보급은 같은 `RouteNetworkSnapshot`을 소비한다.
- 진행 중 이동·작전은 snapshot revision을 pin한다. 변경은 `RouteInvalidated`와 명시적 reroute로만 반영한다.
- 렌더러는 승인된 corridor와 live infrastructure를 표현한다. 도시 좌표를 자동 직선으로 잇지 않는다.

### 수용 조건

1. reviewed selection manifest의 플레이 node id 집합이 `1..780`과 정확히 일치하며 모든 node가
   원문 identity 또는 명시적 외부 source claim을 가진다.
2. 780개 모두 승인된 multimodal network에 참여한다. 섬·연안은 수로·해로·나루도 유효한 연결이다.
3. 같은 snapshot·입력·순서는 같은 경로, 도착 순서, replay hash를 만든다.
4. 통행권·계절·파손·용량으로 인한 실패는 typed denial이며 node 누락이나 순간이동으로 처리하지 않는다.
5. geometry 없는 후보 edge는 화면에 도로로 그리지 않는다.

## 3. 게임성

| 영역 | 수송망의 효과 |
| --- | --- |
| 이동 | 즉시 `cityId` 변경 대신 route·edge 진행도·피로·도착 예정이 durable state가 됨 |
| 보급 | 재고가 용량·통제·호송·계절을 통과해 도착하며 `IN_TRANSIT` 상태를 가짐 |
| 작전 | 집결지, 진격로, 우회로, 퇴각선, 원군 도착 시간이 계획 대상이 됨 |
| 전투 | 길목·관문·교량·나루·edge 접촉에서 야전, 목표 성 도착 뒤 공성·시가전 발생 |
| 내정 | 도로·교량·역참·항구의 건설·수리·파손이 비용·용량·우회에 반영됨 |
| 정보 | 정찰 수준에 따라 적 통제·혼잡·파손·예상 시간을 불완전하게 공개 |

빠른 간선은 집중과 보급을 돕지만 적의 침공축도 된다. 관문과 교량은 용량 병목이자 방어 거점이다.
따라서 최단·안전·대용량 경로가 항상 같지 않도록 설계한다.

## 4. 커맨드 변경 판정

v1의 `che_*` 코드, 예약 링, 로그와 결과는 보존한다. v2 world의 adapter는 즉시 상태 변경 대신
durable intent를 만든다.

| 기존/현재 명령 | 판정 | v2 의미 |
| --- | --- | --- |
| `che_이동`, `che_강행`, `che_귀환` / `personal.retinue.relocate` | 변경 | route intent + `ON_EDGE` 진행도. 강행은 `marchMode=FORCED` |
| `che_출병` / `personal.sortie`, `operation.create` | 변경 | pinned network revision, route/waypoint, 집결·휴대 보급·목표를 가진 작전 |
| `che_집합`, `che_소집해제` | 유지·제약 | 편성만 변경하며 다른 성의 부대는 도착 전 합류 금지 |
| `che_물자원조`, `che_물자조달`, `che_증여` | 변경 | 출발 재고 예약과 수송 intent; 원격 목적지 재고 즉시 증가 금지 |
| `operation.support` | 구체화 | source, cargo, convoy, route, escort, arrival window를 참조 |
| `chief.operation.*`, `operation.join/changeObjective/setRetreat/reinforce/cancel` | 유지·확장 | corridor access·capacity·supply route를 입력으로 사용 |
| `che_성벽보수` | 유지 | 도시 성벽 전용. 도로 수리와 합치지 않음 |
| `che_파괴`, `che_화계`, `che_급습`, `che_첩보` | facade 유지 | 자동으로 도로 mutation으로 재해석하지 않음 |

### 추가·일반화할 canonical 표면

- `personal.travel.plan|cancel`: 개인·부곡의 다턴 이동.
- `logistics.convoy.create|reroute|cancel`: 운송 중 재고, 호위, 용량, 도착·차단·약탈.
- `operation.route.revise`: invalidation 뒤 expected revision을 확인한 명시적 우회.
- `chief.build.plan/assign`, `personal.cityProject.execute/pause` 권한 계층은 유지하되 project site를
  `CITY | ROUTE_SEGMENT | CROSSING`으로 일반화한다. 별도 aggregate가 필요하면 `InfrastructureProject`를
  사용하며 도로 종류마다 command를 늘리지 않는다.
- 요격·보급선 차단·파괴·호송은 우선 `operation`의 typed objective
  `INTERCEPT | BLOCK_ROUTE | SABOTAGE | ESCORT`로 표현한다. 지속 파손, 일시 봉쇄, 이동체 접촉의
  결과 event는 서로 합치지 않는다.

### 불변식

- precheck와 reserved-turn 평가는 route revision, 권한, 재고 예약, 통행 가능 여부와 reason까지 합의한다.
- intake 성공은 이동·운송 성공이 아니다. daemon terminal result 전 UI 성공 처리 금지.
- 원군 배정 → 실제 행군 → 전장 예비대 투입은 각각 별도 권한·시간축을 유지한다.
- 모든 mutation은 `ChangeRecorder` delta와 JDBC batch flush를 사용한다.

## 5. 실행 순서

1. **R0 행정 검출·공간 매핑:** 구조 검출된 1,180개 identity를 ctext·CHGIS와 결합해 104개 join gap을
   해소하고, 유형을 보존한 뒤 reviewed 780 RouteNode manifest와 시나리오별 mapping/provenance를 만든다.
2. **R1 corridor 저작 계약:** 780 node, 후보 edge, geometry, lifecycle, validator와 전수 연결 정책.
3. **R2 이동 수직 절편:** route snapshot, `personal.travel.*`, progress, 재시작, terminal result.
4. **R3 물류·기반망:** convoy, capacity, infrastructure project, damage/repair, invalidation/reroute.
5. **R4 작전·전투:** 출병·원군·퇴각, edge encounter, 공성 전이, replay.
6. **R5 지도 표현:** 승인된 geometry와 live state의 LOD, 명령 preview와 실제 실행 경로 일치.

작은 그래프는 알고리즘 fixture로만 사용한다. 제품 완료는 780 node 전수와 대표 육로·관문·도하·수로·해로
시나리오를 통과해야 한다.

## 6. 미결 결정

- 후보 topology 밖 신규 corridor 저작 허용 범위와 snapshot count/hash 승인 절차
- 비포장 corridor의 통행 가능 여부와 전역 시작 연결 정책
- grade·capacity·upkeep·damage, 건설 비용·공기·주체, 통행권·통행료
- 36순 이동 budget, convoy 속도·소비·호위·약탈, 동일 edge 용량 경합
- 중간 파손·점령 시 pin 유지와 즉시 invalidation의 경계
- 관문·교량·나루 전투의 별도 tactical seed/type
- 새 투영, 공간 해상도, corridor의 물리 표현과 LOD

샘플 수치로 이 항목을 동결하지 않는다. 780성 long-sim과 역사·지리 데이터 감사를 거쳐 별도 결정한다.
