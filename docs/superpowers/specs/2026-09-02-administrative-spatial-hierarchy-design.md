# Administrative Spatial Hierarchy Design

## 결정

OpenSamguk의 지도·행정·게임플레이 계층을 다음 네 종류로 분리한다.

1. `SpatialProvince`: 이동, 인접성, 점령, 보급 판정의 최소 공간 단위다.
2. `County`: 현·후국 등 최하위 행정 단위다. 이름, 현치, 인구·내정 값을 가진다.
3. `Commandery`: 군·국·윤 등 기본 플레이와 명령 집계 단위다.
4. `ProvinceRegion`: 주 등 상위 표시·감찰 단위다. 공간 프로빈스를 직접 소유하지 않는다.

`SpatialProvince`와 `County`는 같은 개념이 아니다. 큰 현을 일정한 크기의 공간 프로빈스 여러 개로 나눌 수 있고, 작은 현도 최소 한 개의 공간 프로빈스를 가져야 한다. `Commandery` 전용 폴리곤을 중첩 생성하지 않는다. 군국의 기하는 소속 현의 공간 프로빈스 합집합이다.

## 정본 관계

```text
ProvinceRegion 1 ── N Commandery 1 ── N County 1 ── N SpatialProvince
                                      │
                                      └── 1 Settlement(seat)
```

- 한 현은 정확히 한 군국에 속한다.
- 모든 플레이 가능 공간 프로빈스는 정확히 한 현급 관할에 속한다. 군국 직할 또는 미귀속 프로빈스는 없다.
- 한 현은 한 개 이상의 공간 프로빈스를 가진다.
- 한 군국은 한 개 이상의 현을 가진다.
- 군국치는 소속 현 가운데 정확히 한 현의 현치다.
- 주치·황도·세력 수도·항구·나루·관문은 별도 도시가 아니라 하나의 물리적 취락에 붙는 역할이다.
- 검은 비플레이 영역은 공간 프로빈스, 현, 군국 어느 것도 만들지 않는다.
- 한대 행정 밖의 플레이 권역은 `LocalJurisdiction`을 현과 같은 공간 계층에 두되 `County`라는 한대 명칭을 강요하지 않는다.

## 정본 레코드

```text
SpatialProvinceRecord {
  id: string
  parentRegionId: string
  jurisdictionId: string
  administrativeSystem: string
  geometryBasis: string
  confidence: string
}

JurisdictionRecord {
  id: string
  displayName: string
  nameCh: string
  kind: COUNTY | MARQUISATE | EXTERNAL_SETTLEMENT
  parentRegionId: string
  seatPlaceId: string
  provinceIds: string[]
}

CommanderyRecord {
  id: string
  displayName: string
  nameCh: string
  kind: COMMANDERY | KINGDOM | METROPOLITAN
  upperRegionId: string | null
  seatJurisdictionId: string
  jurisdictionIds: string[]
}

SettlementRecord {
  id: string
  jurisdictionId: string
  col: number
  row: number
  roles: COUNTY_SEAT | COMMANDERY_SEAT | PROVINCIAL_SEAT |
         IMPERIAL_CAPITAL | FACTION_CAPITAL | PORT | FERRY | PASS | TRIBAL_SETTLEMENT
}
```

기존 `provinceRecords.kind=COUNTY|DIRECT_TERRITORY|SETTLEMENT`와 `cities.kind=COUNTY|COMMANDERY|KINGDOM|PROVINCE|EXTERNAL_PLACE`는 전환 입력으로만 취급한다. 새 렌더러·API·명령 코드는 이 서로 다른 종류를 한 배열의 동급 도시로 해석하지 않는다.

## 현재 데이터의 해석과 교정

2026-09-02 main의 `han-tiles.json`은 공간 프로빈스 1,524개를 가진다.

- 961개 `COUNTY`: 기록된 현치에 직접 연결된 핵심 공간 프로빈스
- 526개 `DIRECT_TERRITORY`: 군국에는 연결됐으나 현에는 연결되지 않은 공간 프로빈스
- 37개 `SETTLEMENT`: 외부권 취락에 연결된 공간 프로빈스

`cities` 1,138개는 물리적 취락 목록이 아니라 현치 961개, 군치 120개, 국도 17개, 주치 3개, 외부 취락 37개를 혼합한다. 같은 위치의 행정 역할을 별도 도시로 취급하지 않는다.

526개 `DIRECT_TERRITORY`는 유효한 최종 종류가 아니라 반드시 제거해야 하는 전환 부채다. 이 가운데 한대 군국 소속은 396개이며, 289개는 같은 군국에 기록된 현이 있고 107개는 장액속국·서하군·정양군·삭방군·요동군·현도군·낙랑군·요동속국·교지군·구진군·일남군 등 22개 군국에서 기록된 현이 현재 하나도 없다.

현이 존재하는 군국의 `DIRECT_TERRITORY`는 다음 결정 규칙으로 현에 귀속한다.

1. 같은 `parentRegionId`의 기록된 현만 후보로 둔다.
2. 공간적으로 접한 후보가 있으면 공유 경계 길이가 가장 긴 현을 선택한다.
3. 동률이면 현치까지의 격자 최단거리가 짧은 현, 이어서 안정 ID 오름차순으로 결정한다.
4. 접한 후보가 없으면 같은 군국 안에서 격자 최단거리가 가장 짧은 현을 선택하고 `INFERRED_WITHIN_PARENT` 근거를 기록한다.
5. 기록된 현이 현재 없는 22개 군국은 사료·CHGIS·기존 정본 입력에서 실제 현과 현치를 복구한 뒤 귀속한다.
6. 실제 현을 증명할 수 없는 내부 조각은 공유 경계가 가장 긴 인접 실제 현의 공간 프로빈스에 흡수한다. 새 가짜 현이나 군국 직할지를 만들지 않는다. 군국 경계를 넘는 흡수는 출처와 변경 전후 기하를 별도 adjudication에 기록한다.
7. 비플레이 전환은 플레이 영역 외곽과 연결된 경계를 안쪽으로 다듬는 경우에만 허용한다. 플레이 가능 육지에 완전히 둘러싸인 검은 비플레이 구멍은 금지한다.
8. 산·사막·밀림은 플레이 경계 안에서는 지형이며 비플레이 공백이 아니다.
9. 군국 대표 세력이나 인접 세력색으로 소유권을 보간하지 않는다.

## 소유권과 통제

- 시나리오 소유권의 정본 키는 `SpatialProvinceRecord.id`다.
- 현 통제는 소속 공간 프로빈스의 직접 소유권과 현치 점유를 함께 사용해 계산한다.
- 군국 통제는 소속 현 통제의 집계이며, 아래 공간 프로빈스에 색을 역전파하지 않는다.
- 군국 통제와 현/공간 프로빈스 직접 소유권이 다르면 둘 다 보존하고 툴팁·명령 preview·AI·replay에 표시한다.
- 여포의 연주 기습처럼 일부 현만 남는 상황은 공간 프로빈스 직접 소유권으로 표현한다.

## 명령과 이동

- 내정·징세·건설 큐·인사·군수 집계의 기본 대상은 군국이다.
- 명령 UI는 군국을 먼저 보여 주고 예외 조정이 필요한 현만 펼친다.
- 부대 위치와 이동 경로는 공간 프로빈스에 기록한다.
- 육상 이동은 공간 프로빈스 인접 그래프를 사용하고 ROAD 그래프를 만들지 않는다.
- 목적지는 군국 또는 현으로 받을 수 있지만, 실행기는 결정적인 공간 프로빈스 경로로 변환한다.
- 점령·봉쇄·도하·항구·나루·관문은 공간 프로빈스 또는 간선 상태다.

## 지도 표시

- 기본 LOD는 군국 경계, 군국명, 군국치 하나를 표시한다.
- 확대 LOD는 현 경계, 현명, 현치 하나를 표시한다.
- 군국치는 같은 현치 아이콘에 역할 배지를 더한다. 아이콘·깃발·이름을 중복 렌더링하지 않는다.
- 취락 규모(`소·중·대·특·경`)와 행정 역할(`현치·군국치·수도`)은 서로 다른 시각 채널을 쓴다.
- 현 경계와 군국 경계는 독립 레이어/토글이며 항상 동시에 강제 표시하지 않는다.

## 계약

- 모든 플레이 가능 공간 프로빈스는 비어 있지 않은 `jurisdictionId`를 가져야 한다.
- `DIRECT_TERRITORY`와 군국 직할 프로빈스는 정본 산출물에서 0개여야 한다.
- 비플레이 육지의 모든 연결 성분은 지도 외곽 비플레이 영역과 연결되어야 한다. 내륙의 검은 고립 성분은 0개여야 한다.
- 모든 현은 최소 한 공간 프로빈스와 정확히 한 현치를 가진다.
- 모든 군국은 최소 한 현과 정확히 한 군국치를 가진다.
- 하나의 `seatPlaceId`는 하나의 물리적 마커만 만든다.
- 지도 아이콘, 이름, 깃발의 anchor와 hitbox는 해당 공간 프로빈스 안에 있어야 한다.
- 시나리오 정치색은 직접 공간 프로빈스 소유권만 칠한다.
- 전환 결과는 생성기, 시드 JSON, JVM importer, API DTO, 웹 렌더러에서 같은 ID 관계를 검증한다.

## 단계적 전환

1. 계층 계약과 감사 산출물을 추가한다.
2. 526개 공간 프로빈스를 현에 결정적으로 귀속하고 `jurisdictionRecords`를 물질화한다.
3. `cities`의 중복 행정치소를 `SettlementRecord.roles`로 접는다.
4. API와 렌더러를 새 계층으로 전환하고 군국/현 LOD를 분리한다.
5. 명령·AI·replay의 군국 기본 집계와 공간 프로빈스 이동을 전환한다.
6. 모든 소비자가 전환된 뒤 레거시 혼합 배열 의존을 제거한다.
