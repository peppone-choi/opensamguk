# 오픈삼국 v2 역사 기반 도시·군대·지형 설계

> 작성일: 2026-07-13
> 상태: user-direction-adopted, review-cleared
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

- [병종·건축물 콘텐츠 카탈로그](2026-07-13-v2-troop-building-content-catalog.md): 72개 formation 전통, 시설 48개, 기반망 10개, 자원거점 12개.
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
  validFrom, validTo, geographyScope
  evidenceClass, confidence, evidenceRefs[]
  interpretationNote

ContentEntry
  id, displayName, capabilities[], constraints[]
  claimIds[], balanceVersion
```

`evidenceClass`는 위 다섯 값만 사용한다. 사료와의 시간적 거리는 `sourceProximity = CONTEMPORARY | OFFICIAL_HISTORY | EARLY_ANNOTATION | LATER_TRADITION | MODERN_STUDY | FICTION | GAME`으로 따로 기록한다. 한 콘텐츠에 정사·주석·연의·게임 참고가 함께 있으면 혼합 등급을 새로 만들지 않고 `HistoricalClaim`을 여러 개 생성해 각각의 class와 근거를 연결한다.

같은 기록을 정사와 연의가 다르게 전하면 둘 중 하나를 지워 합치지 않는다. 서로 다른 claim으로 저장하고 월드 콘텐츠 프로필이 어느 쪽을 활성화하는지 결정한다.

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
AdministrativeUnit: 주 · 군/국 · 현의 통치·세입·호구 범위
Place: 도성 · 군현 치소 · 관문 · 항구 · 나루 · 요새 · 촌락
Facility: 현창 · 군창 · 태창 · 무고 · 전방 군량고 같은 물리 시설과 저장 capacity
ResourceNode: Facility · Place · Formation · IN_TRANSIT에 귀속된 실제 재고·예약·소비 ledger
ResourceSite: 농경지 · 목장 · 광산 · 염장처럼 지도에 선재하는 생산 거점
Formation: 주둔군 · 부곡 · 야전군이 실제로 휴대하는 병력과 보급
```

UI에서는 이 묶음을 여전히 이해하기 쉬운 `도시`로 보여줄 수 있다. 정본 데이터에서는 행정 범위, 물리 시설, 실제 재고를 분리한다. `현창` 한 항목은 `Facility`가 공간·capacity·상태를, 연결된 `ResourceNode`가 곡물 수량·예약·소유를 맡는다. 창고 시설 자체와 그 안의 재고를 두 canonical owner로 중복 저장하지 않는다.

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
TemporalPlace
  names[], type, coordinate, uncertaintyRadius
  validFrom, validTo, sourceRefs[], confidence

RouteCorridor
  endpoints, LAND | RIVER | CANAL | PASS | FERRY | COASTAL
  capacity, grade, seasonality, control, damageState
  geometryConfidence, sourceRefs[]

TerrainRegion
  basin, plain, mountain, wetland, pasture, coast
  elevationProfile, drainage, vegetationInference
```

- 189년 후한 군현을 한 기준선으로 두고, 이후 신설·분할·폐지는 scenario event overlay로 적용한다.
- 군현 치소는 점과 오차 반경으로 저장한다. 근거가 부족한 고대 경계를 정밀 polygon으로 가장하지 않는다.
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
- 묘섭 원문: `docs/wiki/raw/myosam-help/help__start__basic__myostart.md`, `docs/wiki/raw/myosam-help/help__start__intermediate__intermediatebattle.md`, `docs/wiki/raw/myosam-help/help__start__intermediate__othercommands.md`, `docs/wiki/raw/myosam-help/help__start__basic__appointnation.md`.
- 게임 참고: [토탈 워: 삼국/부대](https://namu.wiki/w/%ED%86%A0%ED%83%88%20%EC%9B%8C:%20%EC%82%BC%EA%B5%AD/%EB%B6%80%EB%8C%80), [공식 매뉴얼](https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/779340/manuals/TW3K_PC_MAN_UK.pdf), [공식 Army Management](https://academy.totalwar.com/category/army-management/).

CHGIS 데이터는 버전과 서비스에 따라 비상업·재배포 제한이 확인되므로, 위치 검증 연구에는 사용할 수 있어도 제품 자산으로 직접 번들하기 전 별도 라이선스 검토가 필요하다. 모든 외부 데이터 행에 `sourceLicense`를 저장한다.

## 12. 자체 검토 결과

- `TBD`, 임시 수치, 근거 없는 확정 지형을 두지 않았다.
- 정사 기반과 연의 콘텐츠가 동시에 존재하되 서로의 출처를 덮지 않는다.
- v1 패리티와 v2 신규 모델의 write/read 경계를 분리했다.
- 도시 물류, 병력 생애주기, formation, 지형, 턴 명령이 하나의 수직 흐름으로 연결된다.
- 범용 엔진을 과도하게 선행하지 않고 세 시대 미니 fixture로 공통성만 먼저 증명한다.
