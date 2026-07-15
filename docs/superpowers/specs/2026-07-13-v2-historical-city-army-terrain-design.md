# 오픈삼국 v2 역사 기반 도시·군대·지형 설계

> 작성일: 2026-07-13
> 상태: user-direction-adopted, current-round-review-cleared-2026-07-15
> 범위: v2 도시 자원, 군수, 병력 편제, 병종, 지형, 정사·연의 콘텐츠 경계
> 비범위: v1 PHP 패리티 병종·수치·로그 변경

## 1. 결정

오픈삼국 v2의 기본 콘텐츠는 **정사와 후한 제도를 기반으로 하되, 『삼국지연의』의 대표 콘텐츠를 함께 허용하는 고전 삼국지 구성**으로 만든다.

- 정사·후한서·고고·학술 연구는 역사 기반 계층을 만든다.
- 등갑병처럼 연의에서 온 병종은 제거하지 않고 출처가 표시된 연의 계층으로 추가한다.
- 엄격 고증 월드는 연의 계층만 끌 수 있다.
- v1의 `GameUnitConst`와 `귀병`, `맹수병` 등은 PHP 패리티 데이터이므로 그대로 보존한다. 이를 v2 역사 데이터로 재분류하거나 덮어쓰지 않는다.
- 토탈 워: 삼국은 병종 역할·수행원·모집 조건·주둔군·전술 표현의 주요 게임 레퍼런스로 사용하되, 역사적 사실의 증거로 사용하지 않는다.

이 구조는 후속 시대마다 엔진을 새로 만드는 규칙셋 방식이 아니다. 공통 simulation kernel은 시대 이름과 병종 이름을 모르고, 콘텐츠 데이터가 이동·무장·대형·보급·사기 능력을 조합한다. 오픈 나폴레오닉과 오픈 엠파이어도 같은 kernel과 같은 명령 계약을 사용한다.

세부 카탈로그와 정치 제도는 다음 문서로 분리한다.

- [병종·건축물 콘텐츠 카탈로그](2026-07-13-v2-troop-building-content-catalog.md): 검증 core 72개를 포함한 formation 전통 120개, 시설 72개, 기반망 18개, 자원 유형 24개.
- [국가 정체성 전면 리워크](2026-07-13-v2-nation-identity-rework.md): 기존 제자백가·종교·도적 이름을 유지한 15개 전 항목 설계.
- [황실·관직·개혁·장비 설계](2026-07-13-v2-imperial-court-office-reform-equipment-design.md): 중앙/지방 관직, 추천·자칭·임명, 협천자령제후, 개혁 확산과 인장·옥새.

## 2. 채택한 접근과 기각한 접근

### 채택: 능력 조합 부대 + 출처 계층

`보병`, `기병`, `궁병`을 엔진 enum으로 고정하지 않는다. 하나의 부대는 모집 기반, 지휘 관계, 이동 수단, 무장, 방호, 훈련, 보급 요구를 조합해 만든다. 병종명은 이 조합에 붙는 콘텐츠 이름이다.

### 기각: v1 병종표를 v2 정본으로 확장

v1 병종표는 PHP 패리티에는 정확하지만 역사 모델은 아니다. `귀병`, 지역별 판타지 병종, 고정 가위바위보 계수까지 v2 기반으로 가져오면 고증 모드와 시대 확장이 모두 막힌다.

### 기각: 정사와 연의를 한 데이터 행에서 혼합

연의 콘텐츠를 배제할 이유는 없지만, 정사 근거와 게임 창작을 한 필드에서 섞으면 무엇이 사료인지 다시 분리할 수 없다. 연의 콘텐츠는 역사 기반 데이터를 파괴하지 않는 overlay로 둔다.

## 3. 출처와 확실성 계약

모든 역사·연의 콘텐츠 행은 다음 출처 등급을 가진다.

| 등급 | 의미 | 예시 | 사용 원칙 |
|---|---|---|---|
| `PRIMARY_ATTESTED` | 당대·근접 시기 사료에 직접 확인 | 후한 북군 오영, 조조의 둔전, 청주병 | 역사 기반 계층에서 사용 |
| `SCHOLARLY_RECONSTRUCTION` | 사료·고고를 학술적으로 복원 | 군현 이동망, 지역별 모집 능력 | 불확실성·적용 범위를 함께 표시 |
| `ROMANCE_ATTESTED` | 『삼국지연의』에 직접 등장 | 등갑군과 올돌골 | 기본 고전 구성에서 사용, 엄격 고증에서는 비활성 가능 |
| `GAME_REFERENCE` | 코에이·토탈 워 등 게임 설계 참고 | 수행원 슬롯, 역할 분류, 등갑의 화염 약점 수치화 | 역사 주장에 사용 금지 |
| `BALANCE_ONLY` | 플레이 가능성을 위한 수치 | 피해량, 재장전 시간, 정확한 화염 배율 | 사료 근거처럼 표시 금지 |

```text
EvidenceRef
  id, sourceType, sourceProximity, title, author, work, passage, url, license

HistoricalClaim
  id, subject, predicate, object
  attestationDate, subjectPeriodFrom, subjectPeriodTo
  validFrom, validTo, geographyScope
  evidenceClass, confidence, evidenceRefs[]
  interpretationNote

ContentEntry
  id, budgetSlotId, displayName, capabilities[], constraints[]
  claimIds[], balanceVersion
  lifecycle: NAMED | CLAIMED | FIXTURE_GREEN | ACTIVE
```

`evidenceClass`는 위 다섯 값만 사용한다. 사료와의 시간적 거리는 `sourceProximity = CONTEMPORARY | OFFICIAL_HISTORY | EARLY_ANNOTATION | LATER_TRADITION | MODERN_STUDY | FICTION | GAME`으로 따로 기록한다. 한 콘텐츠에 정사·주석·연의·게임 참고가 함께 있으면 혼합 등급을 새로 만들지 않고 `HistoricalClaim`을 여러 개 생성해 각각의 class와 근거를 연결한다.

같은 기록을 정사와 연의가 다르게 전하면 둘 중 하나를 지워 합치지 않는다. 서로 다른 claim으로 저장하고 월드 콘텐츠 프로필이 어느 쪽을 활성화하는지 결정한다.

`attestationDate`는 기록이 작성된 시점이고 `subjectPeriodFrom/To`는 그 기록이 주장하는 대상 시기다. `validFrom/To`는 여러 claim과 activation 결정을 정규화한 시나리오 유효 구간이며 원문 시기 필드를 덮어쓰지 않는다. 후대 사료에 이름이 등장했다는 이유만으로 189년 월드에 역투영하지 않는다. 수량 예산은 `BUDGET_ONLY` 상태를 가진 별도 `CatalogBudgetSlot`이고 `ContentEntry`는 그 slot이 `NAMED`로 승격할 때 처음 생성된다. `ContentEntry.budgetSlotId`는 unique이며 slot의 `consumedByEntryId`와 양방향 일치해야 한다. slot 소비와 entry 생성은 한 transaction이고 둘 중 하나만 남으면 validation이 실패한다. 이후 `CLAIMED → FIXTURE_GREEN → ACTIVE`를 건너뛸 수 없으며 완료 수량에는 `ACTIVE`만 포함한다.

### 월드 콘텐츠 프로필

| 프로필 | 포함 | 용도 |
|---|---|---|
| `CHRONICLE` | 정사·후한서·학술 복원 | 엄격 고증 플레이와 검증 |
| `CLASSIC` | `CHRONICLE` + 연의 대표 콘텐츠 | v2 기본값 |
| `LEGACY` | v1 PHP 패리티 병종과 규칙 | v1 월드·회귀 검증 전용 |

`CHRONICLE`, `CLASSIC`, `LEGACY`는 `WorldContentProfile` 값이며 `evidenceClass`가 아니다. `CLASSIC`은 `CHRONICLE` 데이터를 수정하지 않는다. 활성 overlay 목록만 simulation snapshot에 기록한다.

## 4. 도시는 군현 치소와 물류 거점이다

묘섭의 도시별 금·병량·주둔병 모델은 채택하되, 모든 것을 하나의 `city` 숫자 묶음으로 만들지는 않는다.

후한의 군현 행정과 창고 기록, 수도 태창·무고, 전방 둔전과 군량 수송을 반영해 다음을 분리한다.

```text
TemporalAdministrativeUnit: 주 · 군/국 · 현/읍/도/후국의 시기별 통치·세입·호구 범위
PhysicalPlace: 도성 · 치소 · 관문 · 항구 · 나루 · 요새 · 촌락의 실제 위치
SeatAssignment: 행정단위가 어느 시기에 어떤 PhysicalPlace를 치소로 사용했는지
PlaceControl: 어느 세력·관직이 실제 점유·세입·군무 권한을 행사하는지
Facility: 현창 · 군창 · 태창 · 무고 · 전방 군량고 같은 물리 시설과 저장 capacity
ResourceNode: Facility · PhysicalPlace · Formation · IN_TRANSIT에 귀속된 실제 재고·예약·소비 ledger
ResourceSite: 농경지 · 목장 · 광산 · 염장처럼 지도에 선재하는 생산 거점
Formation: 주둔군 · 부곡 · 야전군이 실제로 휴대하는 병력과 보급
```

군치·국치는 대개 그 아래 현치와 같은 물리 도시에 놓인다. 따라서 군·현마다 별도 도시를 복제하지 않고 여러 `SeatAssignment`가 하나의 `PhysicalPlace`를 가리키게 한다. UI에서는 이 묶음을 여전히 이해하기 쉬운 `도시`로 보여줄 수 있다. 정본 데이터에서는 행정 범위, 물리 시설, 실제 재고를 분리한다. `현창` 한 항목은 `Facility`가 공간·capacity·상태를, 연결된 `ResourceNode`가 곡물 수량·예약·소유를 맡는다. 창고 시설 자체와 그 안의 재고를 두 canonical owner로 중복 저장하지 않는다.

치소 관계의 유일한 canonical owner는 `SeatAssignment`다. 정착지 규모, 관직이 사용하는 건물, 현재 지배 세력은 각각 `PhysicalPlace.developmentClass`, `OfficeFacilityAssignment`, `PlaceControl`로 저장하되 어느 것도 현치·군치·수도 여부를 다시 선언하지 않는다. UI의 `현치`, `군치`, `수도` 배지는 활성 `SeatAssignment.role`에서만 파생한다.

### 자원 소유 원칙

- 국가는 명령권·세율·징발·예산·배분 정책을 가진다. 지도 밖에서 순간 이동하는 단일 국고는 갖지 않는다.
- 중앙 국고와 태창은 수도의 `Facility`에 연결된 특별한 `ResourceNode`다.
- 현과 군에서 거둔 재화·곡물은 현지 창고에 먼저 들어오고, 상계·수송·징발을 거쳐 수도나 전선으로 이동한다.
- 수송품은 출발 때 원도시에서 빠지고 `IN_TRANSIT`가 된다. 도착·약탈·소실 중 하나로만 끝난다.
- 전술 전투는 도시에 직접 자원을 요청하지 않는다. 작전이 예약한 보급대·전방 창고·휴대 보급만 사용한다.

### 자원 명칭도 고증에 맞춘다

| v1 표현 | v2 역사 기반 표현 | 이유 |
|---|---|---|
| 금 | `FUNDS` 재정·전(錢) | 금괴만을 뜻하지 않으며 동전·포·현물 결제가 혼재 |
| 쌀 | `GRAIN` 곡물·군량 | 북부의 조·기장·밀과 남부의 쌀을 모두 포괄 |
| 병사 | 인구·동원 가능 인력·주둔군·야전군 상태 | 사람을 소모성 재화 한 칸으로 취급하지 않음 |
| 철·나무 | 장비 재고와 생산 역량 | 모든 건설을 범용 원자재 숫자로 환원하지 않음 |

`GRAIN`에는 지역별 작물 조성을 metadata로 남길 수 있지만, 첫 simulation에서는 열량·부피·부패·말 사료 요구만 계산한다.

### 병력 생애주기

```text
민호 인구
  → 동원 가능 인력
  → 징발병 / 모집병 / 항졸 / 부곡 / 군호 / 이민족 원군
  → 훈련 중
  → 도시 주둔군 또는 장수 수행 부대
  → 작전 참가 야전군
  → 전사 / 부상 / 포로 / 탈영 / 원소속 복귀
```

묘섭의 전투 손실 일부가 도시병사로 돌아오는 아이디어는 `사라진 병사가 생성된다`가 아니라, 경상자·이탈자·해산병이 원소속 거점의 회복 대기 인력으로 돌아오는 전후 정산으로 바꾼다.

## 5. 부대와 병종 모델

### 부대는 다섯 축의 조합이다

1. **모집 기반**: 징발, 자원 모집, 유민·항졸 재편, 사가 부곡, 군호, 둔전병, 이민족·동맹 보조군.
2. **지휘 소속**: 수도 숙위, 군현 주둔군, 장수 수행원, 야전 파견대, 수군 선단.
3. **이동 수단**: 도보, 기마, 수레·공성 장비, 선박.
4. **무장·방호**: 도·검, 창·극, 활·노, 방패, 갑옷 재질과 중량, 공성 도구.
5. **상태**: 인원, 경험, 훈련, 대형 숙련, 사기, 결속, 피로, 탄약, 군량.

```text
FormationTemplate
  recruitmentSource
  commandAttachment
  mobilityProfile
  weaponLoadout
  protectionProfile
  doctrineCapabilities[]
  supplyProfile
  availabilityConstraints[]
  provenance
```

`근접 보병`, `충격 기병`, `원거리 보병` 같은 말은 UI 검색과 AI 역할 태그로는 유용하다. simulation kernel의 상속 계층이나 고정 상성표가 되어서는 안 된다.

### 실명 병종은 범용 연구 해금이 아니다

| 콘텐츠 | 출처 계층 | 모델링 |
|---|---|---|
| 청주병 | `PRIMARY_ATTESTED` | 조조가 항복한 황건 병력을 선발·재편한 formation tradition |
| 백마의종 | `PRIMARY_ATTESTED` | 공손찬과 기마 궁사·백마 수행 집단에 시간·소유자 제약 |
| 호표기 | `PRIMARY_ATTESTED` | 조씨 핵심 지휘망의 제한된 정예 기병, 전국 공용 모집 금지 |
| 등갑병 | `ROMANCE_ATTESTED` | 남중 연의 콘텐츠. 등나무 갑옷, 숲·습지 적응, 화염 취약 capability 조합 |
| 연노병 | `SCHOLARLY_RECONSTRUCTION` | 반복노의 실재와 제갈량 발명 전승을 여러 claim으로 분리하고, 대량 전장 운용은 별도 claim |
| 귀병·맹수병 | `GAME_REFERENCE` | v1 `LEGACY` profile에는 보존하되 역사·연의 기본 병종으로 오인시키지 않음 |

등갑병의 `불 피해 2배` 같은 정확한 계수는 토탈 워의 밸런스 표현이지 역사 수치가 아니다. 오픈삼국은 `flammability`, 습윤 상태, 화공 노출, 숲·습지 이동 능력을 조합하고 자체 replay fixture로 수치를 정한다.

## 6. 토탈 워: 삼국에서 참고할 것

[토탈 워: 삼국/부대](https://namu.wiki/w/%ED%86%A0%ED%83%88%20%EC%9B%8C:%20%EC%82%BC%EA%B5%AD/%EB%B6%80%EB%8C%80)와 [공식 매뉴얼](https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/779340/manuals/TW3K_PC_MAN_UK.pdf), [Total War Academy](https://academy.totalwar.com/category/army-management/)에서 다음 구조를 적극 참고한다.

- 장수 한 명과 그 수행 부대가 지속되는 retinue 구조.
- 정착지·숙영지에서 모집하고, 보급과 지역 충원율에 따라 점진적으로 인원이 차는 방식.
- 인물, 세력, 등급, 제도, 지역에 따라 모집 풀이 달라지는 방식.
- 도시 건물과 등급에 따라 별도로 구성되는 주둔군.
- 근접·창·원거리·기마·공성 역할을 명확히 보여주는 UI 분류.
- 갑옷 재질과 중량, 방패, 탄약, 사거리, 피로, 사기, 돌격, 측후면, 지형을 별도 속성으로 두는 방식.
- 원·혼합 방진·쐐기·마름모 등 대형이 병종명과 분리되는 방식.
- 세력·인물 고유 부대를 적은 수와 조건부 접근으로 차별화하는 방식.

그대로 복제하지 않을 항목도 명확하다.

- 오행 색과 장수 유형으로 병종을 하드락하지 않는다.
- 장수당 6부대, 10등급, 정확한 피해·방어 수치를 정본으로 가져오지 않는다.
- 토탈 워의 창작 병종을 사료 병종으로 표기하지 않는다.
- 모든 세력이 같은 상위 병종을 연구 한 번으로 양산하게 만들지 않는다.
- 정사/연의 인물 모드 차이를 부대 수치만 바꾸는 전역 boolean으로 축소하지 않는다.

## 7. 지형과 지도 모델

### 전략 지도

```text
TemporalAdministrativeUnit
  names[], level, parentUnitId, validFrom, validTo
  sourceRefs[], confidence

AdministrativeChange
  id, type: CREATE | RETIRE | RENAME | REPARENT | SPLIT | MERGE | MOVE_SEAT
  effectiveFrom, effectiveTo, datePrecision
  dependsOnChangeIds[]
  subjectUnitIds[], predecessorUnitIds[], successorUnitIds[]
  beforeStateHash, statePatch, sourceRefs[], confidence

PhysicalPlace
  names[], type, developmentClass, placeIdentityKey, placeBudgetClass
  locationResolution: RESOLVED_POINT | CANDIDATE_REGION
  coordinate, candidateRegions[], uncertaintyRadius
  distinctPlaceClaimIds[], validFrom, validTo, sourceRefs[], confidence

SeatAssignment
  administrativeUnitId, physicalPlaceId, role
  validFrom, validTo, sourceRefs[], confidence

PlaceControl
  physicalPlaceId, controllerActorId, exercisingOfficeIds[], authorityScopes[]
  validFrom, validTo, sourceRefs[], confidence

ScenarioPlacement
  scenarioId, physicalPlaceId, reconstructionId
  playableAnchor, admissibleRegion, placementMode, claimIds[]

PolityNetwork
  names[], organizationType, actorPromotionPolicy
  validFrom, validTo, sourceRefs[], confidence

PolityNode
  networkId, names[], nodeType, validFrom, validTo, sourceRefs[]

PolityMembership
  networkId, nodeId, role, validFrom, validTo, sourceRefs[]

PolityRelation
  fromNodeId, toNodeId, relationType
  validFrom, validTo, sourceRefs[], confidence

PolityTransition
  type: FORM | DISSOLVE | SPLIT | MERGE | PROMOTE_ACTOR | DEMOTE_ACTOR
  predecessorNodeIds[], successorNodeIds[], effectiveFrom, effectiveTo
  sourceRefs[], confidence

DiplomaticActorAssignment
  polityNodeId, actorId, validFrom, validTo, promotionReason

TerritorialPresence
  polityNodeId, placeIds[], regionGeometry, uncertaintyRadius
  controlType, seasonality, validFrom, validTo, sourceRefs[]

SeasonalRange
  polityNodeId, season, campPlaceIds[], corridorGeometry
  pastureCapacity, routeRisk, validFrom, validTo, sourceRefs[], confidence

ScenarioActivationManifest
  scenarioId, scenarioDate, catalogVersion, reconstructionVersion
  entries[]:
    entityId, status: CANDIDATE | ACTIVE | EXCLUDED
    supportingClaimIds[], decisionReason

RouteCorridor
  endpoints, LAND | RIVER | CANAL | PASS | FERRY | COASTAL
  capacity, grade, seasonality, control, damageState
  geometryConfidence, sourceRefs[]

TerrainRegion
  basin, plain, mountain, wetland, pasture, coast
  elevationProfile, drainage, vegetationInference

CatalogBudget
  id, entryType, releaseTarget, regionEnvelope
  rationale, lifecycle: BUDGET_ONLY, revision

CatalogBudgetSlot
  id, catalogBudgetId, familyId, ordinal
  status: BUDGET_ONLY | CONSUMED
  consumedByEntryId, consumedAt
```

`AdministrativeChange`를 140년의 불변 baseline에 접어 각 시나리오 snapshot을 생성한다. `RENAME`, `REPARENT`, `MOVE_SEAT`는 행정단위 ID를 보존한다. `CREATE`는 새 ID를 만들고 `RETIRE`는 기존 ID를 닫는다. `SPLIT`과 `MERGE`는 새 successor ID를 만들며 predecessor/successor lineage를 반드시 기록한다. 적용 순서는 `effectiveFrom → type priority → change id`로 고정한다. type priority는 `CREATE(10) → SPLIT(20) → MERGE(30) → RENAME(40) → REPARENT(50) → MOVE_SEAT(60) → RETIRE(70)`이며 같은 type에서는 change id 오름차순이다. 명시적 선후관계는 `dependsOnChangeIds[]`로만 표현하고 priority와 충돌하거나 아직 존재하지 않는 ID를 참조하면 실패한다. 각 단계의 `beforeStateHash`가 다르거나 같은 날짜에 서로 모순되는 patch가 있으면 build를 실패시킨다. 189년 완성본을 별도 수작업 파일로 우회 생성하는 경로는 두지 않는다.

날짜가 범위로만 알려진 change가 scenario date를 가로지르면 자동으로 임의 날짜를 택하지 않는다. `ScenarioActivationManifest`가 근거와 reconstruction을 명시해 한 branch를 선택하지 못하면 해당 snapshot 생성이 실패한다. 같은 manifest, baseline, change stream에서 생성한 행정 tree와 hash는 항상 같아야 한다.

정확한 치소 좌표를 모르는 경우에도 `PhysicalPlace`를 날조된 점으로 만들지 않는다. 직접 위치 근거가 없으면 최소한 원전의 상위 군·국 행정 envelope를 후보 region으로 사용하고 오차 범위 전체를 `locationResolution=CANDIDATE_REGION`으로 저장한다. 활성 시나리오는 그 region 안의 `ScenarioPlacement.playableAnchor`를 deterministic reconstruction으로 선택해 조회·점령·주둔·징병·세입·보급에 참여시킨다. UI는 점이 아니라 불확실 영역과 복원 배지를 함께 보여준다. 140년 baseline fixture는 현급 1,180개 모두 `ACTIVE`, `SeatAssignment`와 placement 보유, `EXCLUDED=0`이어야 G0를 통과한다. 이후 시나리오도 그 날짜에 유효한 모든 현급 단위를 활성화하며, 폐지 전후 단위는 연대기 조회에는 남지만 유효기간 밖 simulation actor로 중첩하지 않는다.

`SeatAssignment` validator는 `(administrativeUnitId, role)`의 `[validFrom, validTo)`가 겹치거나 완전히 같은 행이 중복되면 실패한다. 여러 행정 role이 같은 장소를 쓸 수는 있지만, 동일 기간의 `placeIdentityKey`가 같은 `PhysicalPlace`를 둘 이상 만들면 명시적 `distinctPlaceClaimIds[]` 없이는 실패한다. 군치·현치가 같은 장소라는 claim은 두 장소가 아니라 한 `PhysicalPlace`를 가리키는 두 assignment로만 표현한다.

polity graph validator는 membership·relation·transition·actor assignment·`TerritorialPresence`·`SeasonalRange`의 orphan 참조와 유효기간 이탈을 거부한다. presence와 range의 기간은 참조 `PolityNode`의 활성 기간 안에 있어야 하고, camp `PhysicalPlace`와 겹치는 유효기간을 가져야 한다. `SPLIT`/`MERGE` predecessor는 전이 직전 활성, successor는 전이 전 비활성이어야 하고 lineage를 재생성한 hash가 같아야 한다. 한 `PolityNode`는 같은 시기에 하나의 활성 `DiplomaticActorAssignment`만 가질 수 있다. 연맹 형성→계절 이동→분열→두 actor 승격 fixture를 두 번 재생해 node, presence, range, relation, actor hash diff 0을 검증한다.

### 행정 기준선과 수량 예산

『후한서』 군국지의 순제기 기준은 군·국 105개와 현·읍·도·후국 1,180개다. 이 140년 기준선을 먼저 전사한 뒤 141~189년의 신설·폐지·개명·상위 변경·분할·통폐합·치소 이동을 `AdministrativeChange`로 적용해 189년 scenario snapshot을 만든다. `189년 후한 군현`을 출처 없는 독립 정본으로 손으로 작성하지 않는다.

- 주·자사부 계층과 군·국·현급 단위, 시대별 변경을 합친 `TemporalAdministrativeUnit` 목표는 **1,600개 이상**이다. 이는 동시 활성 도시 수가 아니라 time-scoped record 예산이다.
- 정식 지도 `PhysicalPlace` 목표는 **2,000개**다: `ADMINISTRATIVE_SETTLEMENT` 1,200, `STRATEGIC_NON_ADMINISTRATIVE` 200, `EXTERNAL_PLACE` 500, `MARITIME_REMOTE_GATE` 100. 이는 역사 실수량 주장이 아니라 제품 수용 예산이다. 각 장소는 상호 배타적인 `PlaceBudgetClass` 하나만 가지며 class별 count와 합계 2,000을 모두 검증한다. `SeasonalRange`와 `TerritorialPresence`는 장소 수에 포함하지 않는다.
- 주변 `PolityNetwork`의 제품 수용 예산은 **240개**다: 동북·한반도 96, 왜 32, 흉노·오환·선비 등 북방 32, 강·저·서역 48, 산월·형남·남중·교주 주변 32. 이는 역사적으로 확인된 실수량이 아니라 `CatalogBudget(lifecycle=BUDGET_ONLY)`의 검색·검증·부하 예산이며, claim 없는 slot을 catalog record나 완료 수량으로 만들지 않는다.
- 별도 카운터로 `PhysicalPlace`, `PolityNetwork`, `PolityNode`, `TerritorialPresence`, `SeasonalRange`를 각각 집계한다. G0 synthetic 부하 상한은 presence 480, seasonal range 120이지만 실제 콘텐츠 목표는 근거를 통과한 수량이며 이 상한을 채우기 위해 행을 만들지 않는다.
- 시나리오에서는 소국을 연맹·책봉·종속 관계로 묶고, 분열·독립·외교 접촉이 발생할 때 별도 외교 actor로 승격한다. 240개를 항상 독립 AI 국가로 실행하지 않는다.

군·국 105와 현급 1,180을 물리 도시 수로 더하지 않는다. 군치·국치가 현치와 같은 장소를 쓰면 한 `PhysicalPlace`에 여러 치소 역할을 연결한다. 주·자사부도 행정 overlay이며, 고정 주도(州都)가 사료로 확인되지 않으면 별도 수도를 만들지 않는다.

### 주변 세계와 불확실성

- 한반도에는 낙랑 등 당시 한 군현과 부여·고구려·동옥저·읍루·예·마한·진한·변진의 국읍·읍락·교역망을 함께 둔다. 백제국·사로국을 후대 통일 왕국으로 선반영하지 않는다.
- 대방군은 건안 연간의 신설 event 이후에만 활성화한다. 폐지된 진번·임둔과 이동한 현도군도 한 시점에 중첩 활성화하지 않는다.
- 『삼국지』 동이전에 열거된 마한 50여 국, 진한 12국, 변진 12국과 교류권의 왜 30국은 record 후보로 보존한다. 비정된 위치는 점 하나로 확정하지 않고 오차 반경·후보 region·복수 `HistoricalClaim`을 둔다.
- 동이전처럼 후대에 편찬된 기록은 `attestationDate`와 `subjectPeriodFrom/To`를 분리한다. 189년 activation manifest는 대상 시기가 189년을 포함하고 독립 근거가 있는 항목만 `ACTIVE`로 올린다. 단순히 3세기 목록에 등장한 국읍은 `CANDIDATE`로 남기며, 목록 전체를 189년에 자동 투영하지 않는다.
- 야마타이와 항로 비정은 단일 정답 좌표를 제품 정본으로 채택하지 않는다. scenario가 선택한 reconstruction과 근거를 replay snapshot에 기록한다.
- 흉노·오환·선비·강·저 등 이동성이 큰 집단은 군현식 고정 도시망으로 바꾸지 않는다. 계절 camp, 목초지, 이동 corridor, 교역·책봉·인질·군사 동맹으로 상태를 표현한다.
- 연맹·부·국·읍락은 `PolityNode`로, 소속은 `PolityMembership`으로, 책봉·종속·동맹·적대는 `PolityRelation`으로 시계열 기록한다. `PolityNetwork` 자체를 단일 국가로 간주하지 않으며, `DiplomaticActorAssignment`가 활성화된 node만 독립 외교 actor가 된다.

### 전수 등장과 관리 계층

모든 현·읍·도·후국 치소는 지도에서 조회·점령·주둔·징병·세입·보급·route 통제에 참여한다. 다만 플레이어가 1,180개 관리 화면을 반복 조작하지 않도록 기본 정책은 군·국 단위로 내리고, 현 단위는 상세 보기·예외 명령·태수 위임으로 연다. 정책 우선순위는 `현 명시 override > 유효한 위임 명령 > 군·국 기본 정책 > world default`로 고정하고, 상위 정책 변경은 현 override를 삭제하지 않는다. UI는 군현 검색·필터, 다중 선택 예외 적용, 공급·치안·주둔 이상 알림, 위임 변경 이력과 철회·복구를 제공한다.

`CountyParticipationFixture`는 140년 baseline의 현급 1,180개를 하나씩 대상으로 같은 초기 snapshot에서 여섯 계약을 실행한다. read query는 stable place/unit identity를 반환하고, 점령은 `PlaceControl`, 주둔은 formation assignment, 징병은 가용 인력과 병력 ledger, 세입은 현지 `ResourceNode`, 보급은 예약·route 연결 상태를 각각 변화시켜야 한다. 각 전이는 production handler가 소비할 순수 command/read-model contract를 사용하고 종료 후 snapshot을 초기화한다. 장소별·기능별 성공 수를 따로 집계해 여섯 항목 모두 `1,180/1,180`, orphan·no-op·다른 장소 오염 0이어야 전수 참여로 인정한다. G0에서는 production DB write 없이 in-memory contract로 실행하고 V2-0B가 sandbox 적재 뒤 같은 fixture를 runtime adapter로 반복한다.

- **Catalog detail Tier A 120개**: 군·국 치소, 수도, 핵심 관문과 주변 주요 중심지. authored 3D layout.
- **Catalog detail Tier B 380개**: 중요 현치·항구·관문·외부 중심지. 지역 kit 기반 3D.
- **Catalog detail Tier C 1,500개**: 일반 현치·읍락·캠프·교역 관문. procedural kit 기반 3D.

120/380/1,500은 제작 상세도와 검수 예산을 나타내는 **catalog LOD tier**이며 카메라 거리와 기기 성능에 따라 변하지 않는다. 별도의 runtime render LOD는 `CLUSTER | SYMBOL | KIT | FULL_SCENE`이고 화면 밀도·거리·성능에 따라 모든 catalog tier가 어느 상태로든 이동할 수 있다. 두 축 모두 표시 비용만 바꾸며 세입·이동·시야·보급·점령 판정은 동일한 server read model을 사용한다. Tier C나 `CLUSTER` 상태도 simulation에서 사라지지 않고 stable identity로 선택·검색할 수 있다.

- 140년 기준선과 189년 delta를 먼저 만들고, 이후 184·194·200·208·220 등 scenario가 같은 time-scoped catalog를 소비한다.
- 군현 치소는 확정 점 또는 후보 region과 오차 반경으로 저장한다. 근거가 부족한 고대 경계를 정밀 polygon으로 가장하지 않는다.
- 도로는 직선 도시 연결이 아니라 관중·한중·촉의 잔도, 관문, 나루, 하천과 같은 corridor다.
- 황하·회수·장강은 장애물이면서 수송로다. 강의 상하행, 나루, 선박, 수군 숙련, 홍수 위험을 분리한다.
- 현대 DEM은 산맥·경사·분지를 만드는 물리 기초로만 사용한다. 현대 하도·숲·마을을 2~3세기 지형이라고 주장하지 않는다.

### 실시간 전술 전장

전술 전장은 연속 좌표를 유지한다. 작전의 교전 지점, 주변 `RouteCorridor`, 계절, 기상, 고도·배수 profile을 입력으로 받아 plausible battlefield를 생성한다.

```text
BattlefieldSeed
  operationId, engagementGeoAnchor, season, weather, contentVersion, seed

TerrainPatch
  elevation, slope, soilMoisture, vegetation, water, road, builtObstacle
  terrainReconstructionStatus: OBSERVED | RECONSTRUCTED | PLAUSIBLE
```

`terrainReconstructionStatus`는 전장 geometry가 관측 자료·복원·개연 생성 중 어디에서 왔는지를 나타내며 `evidenceClass`가 아니다. 사료에 특정 고지·나루·성벽이 확인되면 authored feature로 고정한다. 나머지 숲 밀도·길 폭·마을 배치는 `PLAUSIBLE`로 표시한다.

### 3D 표현 계약

- 전략 지도와 전술 전장은 같은 world coordinate와 `projectionVersion`을 사용한다. 전략 scene은 도시·관문·나루·route·작전 경로를, 전술 scene은 terrain patch·formation·시설·장애물을 렌더한다.
- 렌더링은 Three.js 기반 3D를 기본으로 한다. 정사영 지휘 카메라가 기본이며, 회전·틸트·줌은 명령 정확도를 해치지 않는 범위로 제한한다.
- catalog LOD Tier A/B/C는 120/380/1,500의 안정된 제작 예산을 사용한다. runtime render LOD `CLUSTER | SYMBOL | KIT | FULL_SCENE`은 독립적으로 선택하며 clustering·instancing·asset streaming이 marker picking과 label layout을 흔들지 않게 한다.
- terrain LOD와 asset streaming은 simulation 판정을 바꾸지 않는다. 서버가 좌표·가시성·충돌·피해를 계산하고 클라이언트는 presentation만 담당한다.
- `uncertaintyRadius`와 `PLAUSIBLE` geometry는 시각적으로 구분한다. 개연 생성 경계나 지형을 확정 사료처럼 정밀하게 표시하지 않는다.
- WebGL 불가 환경에서는 같은 read model의 정사영 정보 fallback을 제공하지만 별도 simulation이나 다른 이동 규칙을 만들지 않는다.

## 8. 개인턴·사령턴·전술 명령과의 연결

| 계층 | 역사 기반 책임 |
|---|---|
| 개인턴 | 장수의 모집·훈련·이동·수송 호위·둔전 감독·출병·주둔군 인수인계 |
| 사령턴 | 세율·징발 한도·군현별 비축 하한·주둔 정책·수송 우선순위·전선 보급·외교 원조 |
| 작전 명령 | 출발 거점, 참가 formation, 보급 예약, 수송 경로, 집결지, 퇴각선 |
| 실시간 전술 | formation 이동·방향·대형·사격·돌격·재집결·보급대 보호·퇴각 |

개인 `출병`은 도시 또는 전방 거점의 `available` 자원을 예약해 작전을 만든다. 사령턴은 그 작전을 승인하는 문이 아니라 국가 차원의 비축·원군·수송 정책을 붙인다. 전투 중 `재보급`은 이미 전장에 존재하는 보급대나 거점을 소비한다.

## 9. 데이터 무결성과 replay 불변식

- v1 PHP golden, seed, 로그, 명령 결과는 변하지 않는다.
- 역사 기반 simulation snapshot은 content pack, overlay, balance, geography version과 seed를 기록한다.
- 자원은 `onHand = available + reserved`를 만족하고 음수가 될 수 없다.
- 수송은 원거점·운송중·도착거점 중 정확히 한 상태에만 존재한다.
- 병력은 두 formation이나 도시 주둔군에 동시에 속할 수 없다.
- 엄격 고증 프로필에서 `ROMANCE_ATTESTED`, `GAME_REFERENCE` claim 또는 `WorldContentProfile.LEGACY` overlay가 활성화되면 validation이 실패한다.
- 실명 병종은 소유자·시기·지역 제약과 evidence를 가져야 한다.
- 토탈 워나 코에이에서 가져온 수치는 `BALANCE_ONLY`로 표시한다.
- 전술 전장은 `OBSERVED`, `RECONSTRUCTED`, `PLAUSIBLE` 공간 재구성 상태를 `terrainReconstructionStatus`로 replay에 남긴다.
- replay는 `geographyVersion`, `terrainTileVersion`, `projectionVersion`, `assetManifestVersion`을 기록하고, 관전 camera keyframe은 판정 body와 분리한다.
- game-engine write는 기존 `ChangeRecorder → JdbcFlushExecutor` 단일 경로만 사용한다.

## 10. 4/3 기술 증명 슬라이스

DB 전체를 먼저 바꾸지 않는다. 이 gate는 다음 4개 formation과 3개 정착지 content entry만 사용한다.

1. `징발 창병`, `노수대`, `경기병대`, `수송호위대` formation을 만든다.
2. `후방 곡창 Facility+ResourceNode`, `전방 군량고 Facility+ResourceNode`, `농경지 ResourceSite`를 만들고 route 1개로 잇는다.
3. `FUNDS`, `GRAIN`, 장비, 동원 인력과 주둔군을 표시한다.
4. 사령턴이 비축 하한·수송 우선순위를 정하고 개인턴 장수가 군량·장비·주둔병을 수송한다.
5. 개인 출병이 수행 부대와 휴대 군량을 예약하고, 전투가 피로·사기·탄약·보급을 소비한다.
6. 생존자·부상자·포로·탈영자와 남은 물자를 원소속 또는 점령 거점에 정산한다.
7. 같은 snapshot·명령·seed·content version에서 deterministic replay body hash와 자원 보존 결과가 동일한지 검증한다.
8. 도시 3개·route 2개·terrain patch 1개·formation 4개를 3D proof scene에 렌더하고, 전략 route에서 전술 anchor와 replay까지 spatial identity가 유지되는지 검증한다.

실명 역사 부대, 등갑병·화공, 나폴레오닉 전열보병, 엠파이어 포병은 이 gate에 들어오지 않는다. 4/3 proof가 통과한 뒤 `역사 fixture → CLASSIC 연의 fixture → 타 시대 conformance fixture` 순서로 별도 gate를 연다. 첫 역사 fixture 후보는 조조의 허하 둔전·전방 수송 또는 합비 주둔·작피 둔전이고, 첫 연의 fixture 후보는 등갑병과 화공 상호작용이다.

## 11. 자료와 라이선스 원칙

- [『후한서』 군국지5](https://ctext.org/hou-han-shu/jun-guo-wu/zh): 순제기 군·국 105, 현·읍·도·후국 1,180의 수량 기준선.
- [『삼국지』 위서30 동이전](https://zh.wikisource.org/zh-hant/%E4%B8%89%E5%9C%8B%E5%BF%97/%E9%AD%8F%E6%9B%B8/%E6%9D%B1%E5%A4%B7%E5%82%B3): 부여·고구려·동옥저·읍루·예·삼한·왜의 국읍과 관계망.
- [『후한서』 권116](https://zh.wikisource.org/zh-hans/%E5%BE%8C%E6%BC%A2%E6%9B%B8/%E5%8D%B7116): 대사농·태창과 중앙 수납.
- [『후한서』 권117](https://zh.wikisource.org/zh-hans/%E5%BE%8C%E6%BC%A2%E6%9B%B8/%E5%8D%B7117): 무고·성문·북군.
- [『후한서』 권118](https://zh.wikisource.org/zh-hans/%E5%BE%8C%E6%BC%A2%E6%9B%B8/%E5%8D%B7118): 군현 행정·현창·농도위.
- [『삼국지』 무제기](https://ctext.org/text.pl?if=gb&node=601875&remap=gb): 허하 둔전과 각지의 곡물 축적.
- [『삼국지』 위서16](https://ctext.org/sanguozhi/16): 임준의 둔전·관도 군량 수송.
- [『삼국지연의』](https://ctext.org/sanguo-yanyi/zh): 연의 콘텐츠의 문학 정본.
- [Rafe de Crespigny, Northern Frontier](https://openresearch-repository.anu.edu.au/items/1a59ea73-106d-4c58-bd3f-56d475c0e513): 후한 북방 군사·지리 연구.
- [Rafe de Crespigny, Generals of the South](https://openresearch-repository.anu.edu.au/items/3f9f4ff5-730e-4153-a5ce-e2b8a6dd6afc): 강동·손오 연구.
- [CHGIS](https://chgis.fas.harvard.edu/pages/intro/): 시계열 지명·행정구역 연구 기반.
- [NASA SRTM](https://science.nasa.gov/mission/srtm/): 현대 고도 자료의 물리 기반.
- 지명 탐색 인덱스: [삼국지/지명](https://namu.wiki/w/%EC%82%BC%EA%B5%AD%EC%A7%80/%EC%A7%80%EB%AA%85?from=%ED%9B%84%ED%95%9C%2013%EC%A3%BC). 행정 시점·좌표의 정본으로 직접 사용하지 않는다.
- 게임 지리 문법 참고: [토탈 워: 삼국/지역](https://namu.wiki/w/%ED%86%A0%ED%83%88%20%EC%9B%8C:%20%EC%82%BC%EA%B5%AD/%EC%A7%80%EC%97%AD). 주·보조 정착지, 자원, 비옥도, 관문 분류만 참고하고 역사 증거로 사용하지 않는다.
- 묘섭 원문: `docs/wiki/raw/myosam-help/help__start__basic__myostart.md`, `docs/wiki/raw/myosam-help/help__start__intermediate__intermediatebattle.md`, `docs/wiki/raw/myosam-help/help__start__intermediate__othercommands.md`, `docs/wiki/raw/myosam-help/help__start__basic__appointnation.md`.
- 게임 참고: [토탈 워: 삼국/부대](https://namu.wiki/w/%ED%86%A0%ED%83%88%20%EC%9B%8C:%20%EC%82%BC%EA%B5%AD/%EB%B6%80%EB%8C%80), [공식 매뉴얼](https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/779340/manuals/TW3K_PC_MAN_UK.pdf), [공식 Army Management](https://academy.totalwar.com/category/army-management/).

CHGIS 데이터는 버전과 서비스에 따라 비상업·재배포 제한이 확인되므로, 위치 검증 연구에는 사용할 수 있어도 제품 자산으로 직접 번들하기 전 별도 라이선스 검토가 필요하다. 모든 외부 데이터 행에 `sourceLicense`를 저장한다.

## 12. 자체 검토 결과

- `TBD`, 임시 수치, 근거 없는 확정 지형을 두지 않았다.
- 정사 기반과 연의 콘텐츠가 동시에 존재하되 서로의 출처를 덮지 않는다.
- v1 패리티와 v2 신규 모델의 write/read 경계를 분리했다.
- 도시 물류, 병력 생애주기, formation, 지형, 턴 명령이 하나의 수직 흐름으로 연결된다.
- 군현 전체와 주변 세계를 동일한 도시 타입으로 평면화하지 않고, 행정·장소·치소·정치 네트워크·계절 이동권을 분리했다.
- 2,000개 거점 전체가 simulation에 참여하되 120/380/1,500 catalog LOD와 독립 runtime render LOD, 군 단위 기본 관리로 조작·렌더링 비용을 제한했다.
- 범용 엔진을 과도하게 선행하지 않고 세 시대 미니 fixture로 공통성만 먼저 증명한다.
