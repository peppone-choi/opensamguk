# OPENSAM-213 — 후한 780성 작전 이동·다단계 전투 계약

- Date: 2026-08-20
- Status: **REVIEW-READY SPEC — implementation is not authorized by this document**
- Scope: `han` 월드의 작전 경로, 다중 턴 이동, 야전·공성·시가전 전이, 퇴각·합류, 작전 replay
- Non-scope: 제품 코드, DB schema, UI, 기존 `che` 거리 계산, 전용 battle-engine 구현, 세부 전투 수식

> **2026-08-22 정정:** 이 문서의 `780/1,778`과 고정 `ROAD=0.5`는 구현 불변식이 아니다.
> 현재 작업트리는 780 node/1,783 후보 edge이며 추가 5개는 도서·외부 연결이라 수단과 provenance
> 심사가 필요하다. 승인 대상은 숫자가 아니라 hash가 고정된 `RouteNetworkSnapshot`이며 각 corridor는
> mode, geometry, source claims, grade, condition, capacity, control, access, season, damage를 가진다.
> 이 정정과 충돌하는 고정 수치·자동 직선·정적 도로 전제는
> `2026-08-22-han-route-network-and-command-design.md`가 우선한다.

## 1. 판정 순위와 목적

이 문서는 OPENSAM-213 구현 티켓들이 소비할 작전층 계약이다. 충돌 시 다음 순서로 판정한다.

1. 승인된 ADR-LITE-041/042와 ADR-LITE-025/032/037
2. 승인된 전투 세션 스펙 `2026-07-30-v2-realtime-battle-session-command-replay-design.md`
3. 이 문서
4. 과거 94성·1,180현·PHP 패러티 전제의 계획과 연구 문서

목표는 `han`의 780개 성을 한 턴에 순간이동하는 목적지 목록이 아니라, 경로와 진행도가
저장되는 작전 공간으로 만드는 것이다. 같은 입력은 같은 경로, 같은 도착 순서, 같은 phase
전이와 같은 작전 replay를 만들어야 한다.

## 2. 고정 계약

### 2.1 지도와 소유권

- `han` 작전 그래프는 reviewed manifest의 **780개 city node와 승인 snapshot의 edge 집합**을 가진다.
- city ID가 node의 stable identity다. 이름은 중복될 수 있으므로 정본 key가 아니다.
- 이동 중에도 각 city의 소유권은 유지된다. 부대가 edge 위에 있다는 이유로 출발지·경유지·
  목적지의 `PlaceControl` 또는 현행 city owner를 선반영해 바꾸지 않는다.
- 점령은 시가전과 정산이 끝난 뒤 game-engine이 campaign delta를 적용할 때만 확정된다.
- `han` route engine은 `CityConstRegistry.of("han")` 계열의 별도 seam을 소비한다.
  `che`의 `CalcCityDistance`·BFS 순서·기대값은 수정하지 않는다.

### 2.2 비용 단위

경로 계산과 진행도는 부동소수점이 아니라 **half-cost unit** 정수를 사용한다.
문서의 비용 0.5가 저장값 1이다.

| 지형/시설 | 문서 비용 | `costHalfUnits` |
|---|---:|---:|
| `ROAD` | 0.5 | 1 |
| `PLAIN` | 1 | 2 |
| `HILL` | 1.5 | 3 |
| `BASIN` | 1.5 | 3 |
| `PLATEAU` | 2 | 4 |
| `DESERT` | 3 | 6 |
| `MOUNTAIN` | 4 | 8 |
| crossing surcharge | +1 | +2 |
| ford surcharge | +6 | +12 |
| `SEA` | 통행 불가 | 없음 |

- edge의 통행 지형은 versioned geography snapshot이 제공한다.
- `ROAD`는 그 edge의 base cost 0.5다. crossing 또는 ford 표식이 있으면 해당 surcharge를
  base cost에 더한다. 한 edge에 crossing과 ford가 동시에 붙는 입력은 validation failure다.
- `SEA`가 base terrain인 edge는 그래프에 표시할 수 있어도 Dijkstra 후보에는 넣지 않는다.
- 0 이하 비용, 알 수 없는 terrain, dangling endpoint, self-edge, 비대칭 edge, 중복 edge는
  route snapshot validation을 실패시킨다. 실패한 snapshot으로 작전을 시작하지 않는다.

현재 `han.json`은 780/1,783 후보 topology만 제공하고 edge별 terrain/road/crossing/ford annotation은
제공하지 않는다. `han-tiles.json`도 cell terrain은 있지만 이 edge annotation의 정본은 아니다.
따라서 구현은 offline build가 다음 immutable artifact를 만들기 전에는 시작할 수 없다.

```text
HanRouteEdgeSnapshot
  geographyVersion
  geographyHash
  routePolicyRevision
  edgeSnapshotHash
  edges[] sorted by (minCityId, maxCityId):
    minCityId, maxCityId, baseTerrain, crossingKind: NONE | CROSSING | FORD
    baseCostHalfUnits, surchargeHalfUnits, totalCostHalfUnits, traversable
```

topology endpoint는 `han.json`에서만 가져온다. 지형·시설 annotation의 offline source와 생성기는
**UNKNOWN U6**이며, runtime에서 tile 색·좌표 거리·DB row order로 비용을 추론하지 않는다.

### 2.3 경로와 진행도

```text
OperationRoute
  geographyVersion
  geographyHash
  routePolicyRevision
  edgeSnapshotHash
  operationOriginCityId  // 작전 생성 시 동결; route revision이 바꾸지 않음
  attackTargetCityId     // 작전 생성 시 동결; route revision이 바꾸지 않음
  movementMode: ADVANCE | RETREAT
  routeStart: RouteLocation
  movementDestinationCityId
  futureNodeIds[]        // routeStart 뒤 도착할 city; destination 포함, start 중복 제외
  segmentCosts: RationalHalfUnits[] // futureNodeIds와 같은 size
  totalCost: RationalHalfUnits
  routeRevision

OperationProgress
  routeRevision
  segmentIndex           // 현재 위치 -> futureNodeIds[segmentIndex]
  segmentSpent: RationalHalfUnits
  progress: RationalHalfUnits // 이 routeRevision 안의 누적 진행 거리
  currentPhase
  advanceSequence

ArrivalCandidate
  campaignTurn
  locationId
  arrivalOffset: RationalHalfUnits // 이번 turn 시작점부터 이 location 도착까지 소비한 route cost
  turnAdvanceHalfUnits            // 이번 turn의 양의 정수 이동 budget
  generalId

RouteLocation
  AT_NODE(cityId)
  | ON_EDGE(minCityId, maxCityId, offsetFromMin: RationalHalfUnits)

RationalHalfUnits
  numerator              // 0 이상 정수
  denominator            // 양의 정수
```

`RationalHalfUnits`는 저장·비교 전 `gcd(|numerator|,denominator)=1`, 양의 denominator로
canonicalize한다. 같음과 순서는 overflow-safe integer cross-product로 판정하고 float 변환,
반올림, node snap을 금지한다. 일반 이동의 정수 budget은 denominator 1이고, mid-edge contact만
분수 좌표를 만들 수 있다.

`segmentCosts[i]`는 `i=0`이면 `routeStart -> futureNodeIds[0]`, 그 뒤에는
`futureNodeIds[i-1] -> futureNodeIds[i]` 비용이다. 따라서 `segmentCosts.size ==
futureNodeIds.size`가 항상 성립한다. `routeStart=AT_NODE(X)`인 일반 Dijkstra route는 full path의
첫 X를 빼고 나머지를 `futureNodeIds`로 저장한다. `routeStart=ON_EDGE`인 퇴각 route는 첫 도착할
직전 node를 `futureNodeIds[0]`으로, 정확한 partial-edge rational을 `segmentCosts[0]`으로 저장한다.

- `route`와 `progress`는 작전의 durable state다. 예약턴 하나가 끝났다고 지우지 않는다.
- 한 advance가 여러 edge 비용을 충족하면 node를 하나씩 순서대로 통과한다. 각 node 도착의
  encounter/합류/요격 판정을 마친 뒤에만 남은 budget으로 다음 edge를 진행한다.
- 이동 budget의 산출 공식과 어느 턴 cadence에서 지급하는지는 OPENSAM-213 구현 계획 전
  확정할 **UNKNOWN U1**이다. 이 문서는 이미 계산된 양의 정수 `advanceHalfUnits`만 소비한다.
- geography 또는 route policy가 바뀌어도 기존 route를 조용히 갈아끼우지 않는다. 명시적
  `RouteInvalidated`와 새 `routeRevision`이 있어야 다시 계산한다.

## 3. 780-node Dijkstra

### 3.1 입력과 출력

입력은 `(geographyHash, routePolicyRevision, edgeSnapshotHash, originCityId, targetCityId,
traversableEdgeSnapshot)`이고 출력은
`OperationRoute` 또는 typed denial이다. 알고리즘은 매 요청마다 780개 node 전체를 대상으로
동작하며 94성 전용 배열·상수·Floyd-Warshall cache를 소비하지 않는다.

우선순위 queue key는 다음 tuple의 오름차순이다.

```text
(totalCostHalfUnits, hopCount, fullPathCityIds lexicographic)
```

neighbor는 city ID 오름차순으로 열거한다. 동일 node에 같은 비용의 후보가 오면 hop 수가
작은 경로, 그마저 같으면 전체 city ID 경로가 사전식으로 작은 후보만 남긴다. RNG, map
insertion order, thread scheduling, DB row order를 tie-break에 사용하지 않는다.

### 3.2 Given-When-Then

#### R1 — 780개 node 전수

> **Given** valid `han` geography가 reviewed city ID 780개와 hash가 승인된 edge snapshot을 제공하고
> **When** route snapshot을 검증하면
> **Then** node 780개가 정확히 한 번 등록되고 승인 edge가 양방향 동일 비용이며,
> 누락·추가·비대칭·dangling edge가 하나라도 있으면 fail closed한다.

#### R2 — 가중 최단 경로

> **Given** 직접 산악 edge 비용이 4이고 평지 두 edge 합이 2이며
> **When** Dijkstra를 실행하면
> **Then** hop 수가 더 많아도 총 비용 2인 평지 경로를 선택한다.

#### R3 — 결정적 동률

> **Given** 총 비용과 hop 수가 같은 두 경로가 있고
> **When** 같은 snapshot에서 실행 순서와 입력 edge 순서를 바꾸어 100회 계산하면
> **Then** `fullPathCityIds`가 사전식으로 작은 같은 경로와 같은 route hash가 100회 나온다.

#### R4 — 해로와 도달 불가

> **Given** 목적지로 가는 후보가 `SEA` edge뿐이거나 연결 성분 밖에 있고
> **When** 경로를 요청하면
> **Then** `ROUTE_UNREACHABLE`을 반환하고 Operation, 참가 부대, 자원 예약을 만들거나
> 변경하지 않는다.

#### R5 — `che` 격리

> **Given** 동일 commit의 `che` distance fixtures와 새 `han` route fixtures가 있고
> **When** `han` Dijkstra를 추가한 뒤 기존 `CalcCityDistance` gate를 실행하면
> **Then** `che` 결과와 방문 순서는 byte-for-byte 기존 baseline이고, `han`만 새 seam을 쓴다.

## 4. 다중 턴 이동과 도착 순서

#### M1 — edge 안 진행도 보존

> **Given** 다음 edge 비용이 8 half-units이고 progress가 3이며
> **When** 이번 advance budget 2를 적용하면
> **Then** 같은 segment의 progress가 5가 되고 city 소유권·작전 phase·다음 edge는 바뀌지 않는다.

#### M2 — 여러 node 통과

> **Given** 남은 비용이 각각 2, 3, 4인 세 segment와 budget 6이 있고
> **When** advance하면
> **Then** 첫째·둘째 node arrival event를 순서대로 확정한 후 셋째 segment progress 1에서
> 멈추며, 각 arrival 사이의 encounter 판정을 생략하지 않는다.

#### M3 — 같은 턴·같은 위치의 도착과 합류 순서

> **Given** 여러 아군 장수가 같은 campaign turn과 같은 city/node에 도착하고
> **When** arrival batch를 resolve하면
> **Then** `arrivalOffset / turnAdvanceHalfUnits`가 작은 장수, 동률이면 `generalId`
> 오름차순으로 처리하고,
> RNG를 소비하지 않는다. 같은 operation/side의 합류 가능 대상은 이 순서로 합류한다.

`progress`는 현재 route revision 안의 durable 누적 거리라 state/replay에 사용한다.
서로 다른 길의 lifetime 누적 거리를 arrival 우선순위로 비교하지 않는다. 같은 turn의
`arrivalOffset`은 turn 시작 시 남아 있던 현재 edge 비용과 그 뒤 완주한 edge 비용을 도착 node까지
합해 만든다. 도착 시각은 정확한 유리수 `arrivalOffset / turnAdvanceHalfUnits`다. 두 후보는
canonical numerator와 `denominator * budget`을 overflow-safe cross-product로 비교한다. 값이 작은
쪽이 먼저며, 정확히 같을 때만 `generalId` 오름차순이다. budget이 달라도 raw offset만 비교하지
않는다.
따라서 더 긴 과거 route를 걸었다는 이유로 먼저 도착하지 않는다.
route revision이 바뀌면 새 route-local progress는 0에서 시작하고 predecessor revision의 마지막
progress는 replay history에 보존한다.

#### M3a — 서로 다른 budget의 도착

> **Given** A가 cost 2를 budget 2로, B가 cost 3을 budget 6으로 같은 node까지 이동하고
> **When** arrival fraction을 비교하면
> **Then** `3/6 < 2/2`인 B를 먼저 처리하고 raw offset 2 < 3을 우선순위로 쓰지 않는다.

### 4.1 edge 위 접촉

edge identity는 항상 `(minCityId,maxCityId)`이고 위치는 min endpoint에서부터의 canonical
`RationalHalfUnits` 거리다. route 방향이 min→max면 `offsetFromMin=segmentSpent`, 반대면
`offsetFromMin=edgeCost-segmentSpent`다. node로 snap하거나 방향별 edge를 둘로 만들지 않는다.

각 참가자의 이번 turn 이동은 edge별 `[startOffset,endOffset]`와 그 구간의 정확한 시작/끝
turn fraction으로 펼친다. 적대 참가자의 같은 canonical edge 구간이 겹치면 선형 이동 방정식의
첫 교차 fraction을 정수 분자/분모로 계산한다. 후보는 다음 total order로 resolve한다.

```text
(contactTurnFraction, minCityId, maxCityId, contactOffsetFromMin, lowerGeneralId, higherGeneralId)
```

fraction과 contact offset 비교는 정수 cross-product를 쓰며 RNG·float를 쓰지 않는다. 같은 edge를
같은 방향으로 달리는 아군의 따라잡기와 적군 접촉도 같은 scheduler를 쓰고, 한 후보가 FIELD를
열어 lock되면 그 뒤 겹치는 후보는 새 state에서 재검사한다.

같은 방향·같은 속도·같은 시작 offset으로 궤적이 완전히 겹치면 무한 교점 중 **겹침 시작
fraction** 하나를 canonical contact로 고른다. 시작 offset이 다르고 상대 속도가 같으면 contact가
아니다. ROAD cost 1의 양 끝에서 동시에 들어오는 두 부대처럼 접점이 `1/2` half-unit인 경우도
`RationalHalfUnits(1,2)`로 그대로 저장한다.

각 참가자의 정확한 contact progress, Operation phase, operation-global `encounterOrdinal`, 관련
campaign entity lock, immutable `CampaignBattleHandoff`를 **하나의**
`ChangeRecorder -> JdbcFlushExecutor` campaign flush에서 commit한다. 일부만 먼저 commit하지 않는다.
Redis wakeup은 이 commit 뒤의 hint다. crash 뒤에는 이 동일 flush가 0회 또는 1회 관측되므로
progress만 contact에 남고 battle이 없는 상태가 생기지 않는다. 퇴각은 battle 전 위치가 아니라
이 committed contact에서 시작한다. `turnAdvanceHalfUnits <= 0`인 입력은 arrival/contact
scheduler에 들어오기 전에 validation failure다.

#### M3b — 반대 방향 edge 교차

> **Given** 두 적대 장수가 같은 canonical edge를 반대 방향으로 지나며 turn fraction 안에서
> 위치가 교차하고
> **When** edge encounter scheduler가 실행되면
> **Then** 정확한 첫 contact fraction/location에 FIELD encounter 하나를 만들고, 입력 처리
> 순서를 바꿔도 같은 pair와 `encounterOrdinal`을 고른다.

#### M4 — 재시작과 replay

> **Given** route와 progress가 edge 중간에 durable commit됐고
> **When** game-engine이 재시작해 같은 advance command를 idempotency key로 재수신하면
> **Then** 마지막 committed progress에서 정확히 한 번 전진하며 중복 arrival·합류·전투를
> 만들지 않는다.

## 5. 작전 phase 상태기계

정본 phase 순서는 다음과 같다.

```text
APPROACH -> SCOUT -> INTERCEPT -> FIELD -> SIEGE -> URBAN -> AFTERMATH
```

모든 작전이 모든 phase에서 battle을 만드는 것은 아니다. phase는 작전의 순차 단계이고
야전·공성·수전은 별도 battle type이다(ADR-LITE-037). 한 phase는 0..N개의 battle을 만들 수
있고, 수전은 phase가 아니다.

첫 FIELD 승리 뒤의 이동과 이후 route encounter도 같은 FIELD phase instance 안에서 계속한다.
`FIELD -> APPROACH`로 역전이하지 않으며, 추가 야전은 같은 phase의 증가하는
`encounterOrdinal`로 기록한다. 따라서 phase ordinal은 감소하지 않는다.

OPENSAM-213의 핵심 흐름은 다음과 같다.

```text
movement
  -> route 위 적 접촉: FIELD
  -> 승리 시 이동 재개
  -> target city 도착: SIEGE
  -> 성벽 붕괴/성문 돌파 뒤 지배 미확정: URBAN
  -> 시가전/항복/철수 정산: AFTERMATH
```

SCOUT와 INTERCEPT의 세부 판정 정책은 이 티켓이 새로 정의하지 않는다. route 위 적 접촉이
확정되면 최소한 FIELD로 들어간다는 경계만 고정한다.

### 5.1 야전

#### P1 — 경로 위 야전 생성

> **Given** 전진 중인 공격대와 같은 route node/edge encounter window에 적대 부대가 있고
> **When** deterministic arrival ordering 뒤 교전 조건이 성립하면
> **Then** Operation은 FIELD phase와 stable `encounterOrdinal`을 기록하고,
> contact progress·phase·ordinal·lock·`CampaignBattleHandoff`를 같은 campaign flush에 commit한
> 뒤에만 battle-engine이 field `battle_ticket`을 만든다.

#### P2 — 야전 승자와 패자

> **Given** field battle result가 exactly-once로 campaign에 적용되고
> **When** 공격 측이 이기면
> **Then** 생존 참가자는 같은 route revision에서 전진을 재개할 수 있다.
> **When** 공격 측이 지면
> **Then** 패자는 origin 방향으로 퇴각하고 target city에는 도달하지 못한다.

야전 패배를 같은 advance budget의 남은 값으로 상쇄하거나, 패자를 목적지에 먼저 도착시킨 뒤
되돌리는 구현은 금지한다.

### 5.2 공성

#### P3 — 목적지 도착

> **Given** 공격 측이 route의 target city에 도착했고 적대 소유권과 유효한 성벽 방어가 있고
> **When** arrival를 resolve하면
> **Then** city owner는 유지되고 Operation은 SIEGE phase로 전이하며 siege handoff를 만든다.

목적지와 phase terminal path는 다음처럼 전부 닫는다.

| 현재 조건/결과 | 다음 상태 |
|---|---|
| target이 도착 전에 same-side가 됨 | battle 없이 `AFTERMATH(ARRIVED_FRIENDLY)` |
| hostile target, 유효 wall 없음 | `URBAN`으로 직행 |
| hostile target, 유효 wall 있음 | `SIEGE` |
| SIEGE 전 defender 항복 | `AFTERMATH(CAPITULATED)` + delayed conquest apply |
| SIEGE breach/wall collapse | `URBAN` |
| SIEGE attacker 승리, defender 잔존 0 | `AFTERMATH(CAPITULATED)` + delayed conquest apply |
| SIEGE attacker 패배/철수 | `RETREAT`; conquest 없음 |
| URBAN attacker 승리/defender 항복 | `AFTERMATH(CONQUERED)` + delayed conquest apply |
| URBAN attacker 패배/철수 | `RETREAT`; conquest 없음 |
| RETREAT destination 도착 | `AFTERMATH(RETREATED)` |
| route/retreat destination 불가 | typed blocked terminal; 순간이동·점령 없음 |

#### P4 — 기존 전투 kernel 경계

> **Given** SIEGE phase가 전투를 실행하고
> **When** 전투 규칙 adapter가 combat을 resolve하면
> **Then** 현행 `processWar_NG` 의미는 **공성 phase의 내부 kernel**로 소비되고, OPENSAM-213은
> 그 kernel의 수식·RNG·로그·side-effect 순서를 재작성하지 않는다.

ADR-LITE-042로 PHP 동일성은 새 설계 제약이 아니지만, 기존 테스트는 frozen-baseline이다.
변경 이유와 별도 승인 없이 공성 kernel 기대값을 수정하지 않는다.

### 5.3 시가전

#### P5 — 성벽 붕괴 뒤 시가전

> **Given** siege result가 성벽 붕괴 또는 성문 돌파를 확정했고 city control은 아직 적대이며
> **When** campaign result adapter가 그 결과를 적용하면
> **Then** 같은 Operation은 URBAN phase와 다음 `encounterOrdinal`을 기록하고 street battle을
> 생성할 수 있으며, city owner는 아직 바뀌지 않는다.

#### P6 — 점령 확정

> **Given** URBAN phase의 필요한 battle/항복 판정이 terminal이고
> **When** 최종 result를 game-engine이 적용하면
> **Then** casualty·capture·supply·conquest delta와 Operation AFTERMATH를 같은 campaign flush
> 경계에서 exactly-once 확정하고 그 뒤에만 새 city owner가 관측된다.

street battle의 공식 battle type 이름과 세부 adapter 수식은 BATTLE-F2 및 후속 adapter가
소유하는 **UNKNOWN U2**다. URBAN phase 자체와 점령 지연 불변식은 그 이름과 무관하게 고정한다.

## 6. 퇴각과 작전 합류

### 6.1 퇴각

퇴각은 새 operation phase가 아니라 FIELD/SIEGE/URBAN의 phase decision과 route 상태 전이다.
정상 전진 route와 혼동하지 않도록 각 route revision/hash에 `movementMode=ADVANCE|RETREAT`,
`routeStart`, `movementDestinationCityId`를 기록한다.

#### T1 — 야전 패배 퇴각

> **Given** 야전 패자에게 original `originCityId`와 현재 위치가 있고
> **When** retreat를 확정하면
> **Then** 현 geography snapshot에서 current location부터 origin 방향으로 Dijkstra를 다시
> 계산하고 새 route revision을 기록하며, 이 작전은 target arrival와 SIEGE로 전이할 수 없다.

`current location`이 `ON_EDGE`면 먼저 기존 route의 직전 node로 **같은 edge를 역주행**한다.
첫 retreat segment 비용은 현재 canonical rational `segmentSpent`이고 progress를 버리거나 node로
snap하지 않는다. 직전 node 도착 뒤 그 node→origin Dijkstra tail을 잇는다. 직전 node가 origin이면
tail은 비어 있다. 이 composite retreat route는 `routeStart=contact`, `futureNodeIds=[직전 node,
tail의 후속 nodes...]`, `movementDestinationCityId=operationOriginCityId`인 새 route revision/hash로
durable 저장한다. `attackTargetCityId`는 감사용으로만 보존하며 `movementMode=RETREAT`인 revision은
그 ID에 도착해도 SIEGE 판정을 실행할 수 없다.

퇴각 경로의 통행 허용 node 집합과 origin이 점령됐을 때의 대체 목적지는 **UNKNOWN U3**이다.
정책이 목적지를 제공하지 못하거나 도달 불가하면 임의의 인접 city로 순간이동하지 않고
typed blocked result를 남긴다.

#### T2 — 퇴각 중 접촉

> **Given** 퇴각대가 route node를 통과하고 적대 부대와 encounter가 성립하며
> **When** arrival batch를 resolve하면
> **Then** 전진과 같은 deterministic ordering으로 `FIELD_BATTLE` type의 새 battle을 만들 수
> 있고, 과거 field result를 재사용하거나 target city 도착으로 처리하지 않는다.

퇴각 contact는 operation phase를 FIELD로 되돌리지 않는다. FIELD 패배에서 시작한 퇴각이면 FIELD,
SIEGE 패배/철수에서 시작했으면 SIEGE, URBAN 패배/철수에서 시작했으면 URBAN phase를 유지한 채
그 phase 안에 `FIELD_BATTLE` type을 연다. `encounterOrdinal`은 phase별로 reset하지 않는
operation-global 단조 증가값이다. 따라서 `(phaseOrdinal,encounterOrdinal)`은 유일하고 phase
ordinal은 절대 감소하지 않는다. 퇴각 목적지 도착 뒤에만 AFTERMATH(RETREATED)로 전이한다.

### 6.2 같은 위치 합류

#### J1 — 아군 합류

> **Given** 같은 turn/location에 도착한 compatible same-side 참가자들이 있고 어느 쪽도
> active battle/result-pending lock에 있지 않으며
> **When** M3 순서로 resolve하면
> **Then** 후착 참가자를 primary Operation의 participant로 exactly-once 추가하고 원래
> provenance, role, reserved supply를 보존한다.

같은 Operation에 예약된 참가자의 도착은 participant join일 뿐 Operation merge가 아니다.
서로 다른 두 Operation을 merge할 때는 secondary의 **모든** live participant, entity lock,
resource/supply reservation, deferred effect owner, replay predecessor를 primary로 한 campaign
flush에서 옮긴다. 일부만 옮길 수 있으면 전체 merge를 거절하고 두 Operation을 그대로 둔다.
전량 transfer와 `MergedInto(primaryOperationId)` terminal event가 같은 flush에 성공한 뒤에만
secondary를 terminal로 만든다. 삭제하지 않으며 모든 replay가 predecessor를 추적한다.

#### J2 — primary 선택

> **Given** 둘 이상의 compatible Operation이 같은 batch에서 합류하고
> **When** primary를 고르면
> **Then** 가장 이른 durable operation creation sequence, 동률이면 operationId의 canonical
> byte order로 한 개를 고르며 network arrival order나 RNG를 쓰지 않는다.

서로 다른 목표·퇴각 정책·외교 제약을 compatible로 볼지와 보급 합산 상한은 **UNKNOWN U4**다.
명시적 compatibility verdict가 없으면 merge하지 않는다. active battle에 증원이 들어가는 경우는
Operation merge가 아니라 기존 battle spec §16의 reinforcement handoff를 사용한다.

## 7. battle-engine 및 replay 경계

| 작전/game-engine 소유 | battle-engine 소유 |
|---|---|
| `operationId`, route, progress, phase, encounter ordinal | `battleId`, 200ms tick, formation 위치·충돌 |
| city ownership, campaign participant/resource lock | `battle_*` ticket/event/snapshot/result outbox |
| Dijkstra와 campaign arrival/merge ordering | BattleTopology와 전장 내부 pathfinding |
| phase 전이와 `CampaignBattleHandoff` | `battle_ticket` 생성, 한 battle의 authority/order/reconnect/replay |
| result apply와 city conquest | result proposal; campaign table DML 금지 |

- route Dijkstra를 `BattleTopology`에 넣지 않는다. 둘은 서로 다른 공간·시간 계층이다.
- battle-engine은 Operation route/progress/phase 또는 city owner를 직접 수정하지 않는다.
- game-engine만 battle result를 검증하고 `ChangeRecorder -> JdbcFlushExecutor`로 적용한다.
- phase 안의 battle은 `(phaseOrdinal, operation-global encounterOrdinal)`로 식별하고, raw `battleId` 생성 순서는
  Operation replay의 결정성 tie-break가 아니다.
- 여러 battle result가 transport에서 역순 도착해도 앞선 encounter가 terminal/apply되기 전
  후속 result를 phase 전이에 반영하지 않는다.

### 7.1 Operation replay

`DeterministicReplayBody`에는 최소한 다음을 canonical serialize한다.

```text
worldSnapshotHash, operationInputHash, seed
contentVersion, balanceVersion, geographyVersion, geographyHash
routePolicyRevision, edgeSnapshotHash, movementMode, routeStart, movement destination,
future node/cost list, route revisions
ordered progress/arrival/merge/retreat events
phases[] and phase decisions
normalized battle references:
  phaseOrdinal, encounterOrdinal, battleTicketHash, resultRevision, battleReplayHash
ordered campaign state diff, normalized log entries
```

`createdAt`, DB row ID, raw transport arrival time는 hash 입력이 아니다. 같은 입력의 동시 result는
`phaseOrdinal -> encounterOrdinal -> resultRevision` 순서로 fold한다. battle replay의 내부 event는
각 `battleId,eventSeq` 계약을 유지하되 Operation replay에는 normalized reference와 terminal hash를
넣어 두 계층을 중복 저장하지 않는다.

#### D1 — operation replay 결정성

> **Given** 같은 world/operation/geography/version/seed와 같은 승인 event 집합이 있고
> **When** edge 입력 순서, thread scheduling, battle result delivery 순서를 바꿔 100회 replay하면
> **Then** route, arrival order, phase sequence, normalized battle reference order, campaign diff와
> `deterministicReplayHash`가 모두 같다.

#### D2 — 장애 경계

> **Given** field, siege 또는 street result를 campaign에 적용하기 직전/직후 process가 죽고
> **When** durable event와 idempotency marker로 복구하면
> **Then** progress·phase·owner 변경은 0회 또는 정확히 1회만 관측되고 부분 점령이나 중복
> 합류는 없다.

## 8. 수용 fixture

구현 계획은 최소 다음 fixture를 이름으로 가져야 한다.

1. 780 node/승인 edge snapshot validation과 full-graph Dijkstra 실행
2. road/plain/hill/basin/plateau/desert/mountain 및 crossing/ford 비용 표
3. SEA-only와 disconnected destination의 `ROUTE_UNREACHABLE`
4. 동일 비용·동일 hop의 lexicographic tie
5. 세 턴 이상 지속되는 edge progress와 재시작
6. 한 budget으로 여러 node를 지나도 encounter를 생략하지 않는 fixture
7. 서로 다른 budget의 rational arrival fraction, general ID final tie, RNG 0 draw 합류
8. ROAD cost 1의 `1/2` rational contact와 coincident trajectory overlap-start 규칙
9. ROAD `1/2` contact → prior node 퇴각을 rational arrival와 다른 부대 arrival에 비교하고
   composite retreat route의 `futureNodeIds.size == segmentCosts.size`, destination=origin,
   attack target SIEGE 금지를 확인한 뒤 replay hash를 재실행해 diff 0
10. 반대 방향·같은 방향 canonical edge contact total order
11. contact progress/phase/ordinal/lock/handoff flush 전후 crash atomicity
12. route-cross FIELD 패배 → rational mid-edge progress 보존 역주행 → origin 방향 퇴각
13. friendly/no-wall/surrender/breach/패배/철수/retreat-complete phase terminal matrix
14. SIEGE·URBAN retreat contact가 phase를 낮추지 않고 FIELD_BATTLE type을 여는 fixture
15. movement → FIELD → movement → SIEGE → wall collapse → URBAN → conquest
16. full secondary Operation의 participant/lock/reservation atomic merge와 전부 거절
17. active battle 증원은 merge가 아니라 reinforcement handoff를 쓰는 fixture
18. battle result 역순 delivery에도 encounter 순서가 보존되는 fixture
19. 같은 입력 100회 Operation replay hash diff 0
20. 기존 `che` `CalcCityDistance` focused tests와 baseline diff 0
21. battle-engine campaign table DML 0 및 game-engine single flush architecture gate

## 9. UNKNOWN register와 review stop conditions

| ID | 미결 사항 | 구현 전 stop condition |
|---|---|---|
| U1 | turn별 `advanceHalfUnits` 산식·cadence | 수치와 version owner 승인 전 movement resolver 구현 금지 |
| U2 | street battle의 공식 battle type/adapter와 세부 수식 | BATTLE-F2/adapter 계약 전 battle enum 하드코딩 금지 |
| U3 | 퇴각 허용 node와 origin 상실 fallback | 정책 승인 전 임의 nearest-friendly fallback 금지 |
| U4 | Operation merge compatibility와 보급 상한 | explicit verdict 없이 자동 merge 금지 |
| U5 | 현재 graph의 고립 node 523/550/759/770/780이 의도인지 데이터 결함인지 | `ROUTE_UNREACHABLE`은 유지하되 연결선을 날조하지 않음 |
| U6 | edge terrain/road/crossing/ford offline source와 snapshot generator | artifact와 hash가 생기기 전 route 구현 금지 |

이 UNKNOWN들은 이 문서의 결정성·소유권·점령 지연·phase 순서 불변식을 약화하지 않는다.
독립 리뷰는 비용의 additive 해석, tie-break total order, 전투 결과 역순, 퇴각 후 target 도달 금지,
`processWar_NG` 비변경, Operation/Battle replay 이중 진실 여부를 우선 공격해야 한다.

Review checkpoint RC1: Jira 값에는 비용의 대수식이 없으므로, 구현 계획은 `ROAD`를 절대 base
cost로 두고 crossing/ford를 하나만 additive 적용하는 이 문서 §2.2 해석을 승인받아야 한다.
Review checkpoint RC2: 서로 다른 route의 같은-turn arrival 비교는 lifetime progress나 raw
offset이 아니라 §4의 exact fraction 식을 써야 하며, 속도/budget 정책(U1)이 그 식을 바꾸는
경우 구현 전에 versioned 식을 다시 승인받아야 한다.

## 10. 근거 추적

- `.ai/decisions.md` ADR-LITE-041: 175郡·780城, 인접+치소 연결의 `han` 세계 규격.
- `.ai/decisions.md` ADR-LITE-042: PHP 동일성 해제, 결정적 replay와 one-daemon-write 유지.
- `infra/src/main/resources/map/han.json`: 현재 작업트리 780 city, 1,783 symmetric undirected candidate edge 실측.
- `tools/scenario/build_han_world.py`: 인접·치소 연결 생성 규칙과 sorted connection 출력.
- `logic/.../CalcCityDistance.kt`: 기존 `che` BFS 및 insertion-order baseline; 이 문서의 변경 대상 아님.
- `2026-07-12-opensamguk-v2-product-spec.md` §Operation/§BattleReplay: route, progress 계층,
  7개 operation phase, operation replay hash.
- ADR-LITE-037: phase 축과 battle type 축 분리, 한 phase 안 0..N battle.
- `2026-07-30-v2-realtime-battle-session-command-replay-design.md` §§5–16: BattleTicket,
  authority, stable ordering, replay, result exactly-once, reinforcement 경계.
- OPENSAM-213 current issue contract relayed by the team lead on 2026-08-20: terrain costs,
  multi-turn route/progress, arrival ordering, field/siege/street flow, retreat/join, `che` isolation.
