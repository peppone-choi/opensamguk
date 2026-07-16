# v2 스펙 2문서 티켓 분해 (최소 단위 반영본)

## 재분해 규약 (읽는 사람 주의)
- 각 티켓 앞 태그: `[문서]` = 내용·완료기준이 스펙 문서에 직접 근거. `[아키]` = 산출물 분리(마이그레이션/row mapper·flush/read API/프론트 렌더/ChangeRecorder 채널)는 **스펙 문서가 아니라 저장소 CQRS 아키텍처(CLAUDE.md)에서 추론한 구조적 산출물**. 스펙은 필드 모양·불변식만 주고 SQL·mapper 세부는 안 주므로 이런 티켓 완료기준은 대부분 "문서 미명시"이고 컬럼은 짝이 되는 `모델 정의` 티켓 필드에서 파생.
- 문서1 시퀀싱: **G0 = in-memory contract(DB write 없음), V2-0B = sandbox 적재+runtime adapter**. `[아키]` 마이그레이션/mapper/flush는 원칙적으로 V2-0B 이후 산출물(→ (V2-0B) 표기), `모델 정의`·`validator`·`in-memory fixture`가 G0 산출물.
- 완료기준 없으면 "문서 미명시". 문서가 큰 덩어리로만 준 지점은 "추가 분해 필요".

---

# 문서 1 — v2 역사 기반 도시·군대·지형 (`2026-07-13-v2-historical-city-army-terrain-design.md`, 430줄)

## 목적/범위 요약
정사·후한 제도를 역사 기반 계층으로 삼고 『삼국지연의』 대표 콘텐츠를 출처 표시 overlay 계층으로 함께 허용하는 v2 콘텐츠 기반 설계. 공통 simulation kernel은 시대·병종 이름을 모르고 콘텐츠 데이터(이동·무장·대형·보급·사기 조합)가 부대를 정의하며 오픈 나폴레오닉·엠파이어도 같은 kernel 공유. 도시는 행정범위(TemporalAdministrativeUnit)·물리장소(PhysicalPlace)·치소(SeatAssignment)·지배(PlaceControl)·시설/재고(Facility/ResourceNode)·부대(Formation)로 분리. 지형은 140년 baseline에 AdministrativeChange를 접어 시나리오 snapshot을 결정적 생성, 전략/전술이 같은 world coordinate·projectionVersion 공유하는 Three.js 3D. **비범위: v1 PHP 패리티 병종·수치·로그 변경 안 함.**

## 웨이브/게이트 구조 (문서 그대로)
G0(140 baseline, in-memory, CountyParticipationFixture 포함) → V2-0B(sandbox 적재+runtime adapter 반복) → 4/3 기술 증명 슬라이스(4 formation+3 content entry, §10) → 역사 fixture→CLASSIC 연의 fixture→타 시대 conformance fixture(첫 역사 후보: 조조 허하 둔전·전방 수송 또는 합비 주둔·작피 둔전 / 첫 연의 후보: 등갑병·화공). 콘텐츠 시나리오 순서: 140 기준선+189 delta 먼저 → 184·194·200·208·220. ContentEntry 상태기계: BUDGET_ONLY(slot)→NAMED→CLAIMED→FIXTURE_GREEN→ACTIVE(건너뛰기 불가, 완료 수량은 ACTIVE만).

## 티켓 목록

### 그룹 A — 출처·확실성 계약 (§3)
- **T1-A01 `[문서]` EvidenceRef 모델 정의** — id/sourceType/sourceProximity/title/author/work/passage/url/license. 선행 없음. 완료: sourceProximity 7값만.
- **T1-A02 `[아키]` EvidenceRef 마이그레이션 (V2-0B)** — 선행 A01. 완료: 문서 미명시.
- **T1-A03 `[아키]` EvidenceRef row mapper+flush (V2-0B)** — 선행 A02. 완료: ChangeRecorder→JdbcFlushExecutor 단일 경로.
- **T1-A04 `[문서]` HistoricalClaim 모델 정의** — id/subject/predicate/object/attestationDate/subjectPeriodFrom·To/validFrom·To/geographyScope/evidenceClass/confidence/evidenceRefs[]/interpretationNote. 선행 A01. 완료: evidenceClass 5값만.
- **T1-A05 `[문서]` HistoricalClaim 시기분리 validator** — 선행 A04. 완료: 후대 사료 이름 등장을 189년에 역투영 금지.
- **T1-A06 `[문서]` 정사·연의 복수 claim 저장 규칙** — 선행 A04. 완료: 혼합 등급 신설 금지, HistoricalClaim 복수 생성.
- **T1-A07 `[아키]` HistoricalClaim 마이그레이션 (V2-0B)** — 선행 A04. 완료: 문서 미명시.
- **T1-A08 `[아키]` HistoricalClaim row mapper+flush (V2-0B)** — 선행 A07. 완료: 문서 미명시.
- **T1-A09 `[문서]` ContentEntry 모델 정의** — id/budgetSlotId/displayName/capabilities[]/constraints[]/claimIds[]/balanceVersion/lifecycle. 선행 A04. 완료: lifecycle NAMED|CLAIMED|FIXTURE_GREEN|ACTIVE.
- **T1-A10 `[문서]` ContentEntry lifecycle 상태기계** — 선행 A09. 완료: 건너뛰기 불가, 완료 수량 ACTIVE만.
- **T1-A11 `[문서]` CatalogBudget 모델 정의** — id/entryType/releaseTarget/regionEnvelope/rationale/lifecycle(BUDGET_ONLY)/revision. 완료: 문서 미명시.
- **T1-A12 `[문서]` CatalogBudgetSlot 모델 정의** — id/catalogBudgetId/familyId/ordinal/status/consumedByEntryId/consumedAt. 선행 A11. 완료: 문서 미명시.
- **T1-A13 `[문서]` slot 소비+entry 생성 트랜잭션 무결성** — 선행 A10,A12. 완료: budgetSlotId unique + slot consumedByEntryId 양방향 일치, 하나만 남으면 실패.
- **T1-A14 `[아키]` CatalogBudget/Slot 마이그레이션 (V2-0B)** — 선행 A11,A12. 완료: 문서 미명시.
- **T1-A15 `[문서]` WorldContentProfile enum+overlay 로직** — CHRONICLE/CLASSIC/LEGACY. 선행 A04. 완료: CLASSIC이 CHRONICLE 데이터 수정 금지, 활성 overlay 목록만 snapshot 기록.
- **T1-A16 `[문서]` 엄격 고증 validator** — 선행 A15. 완료: CHRONICLE에서 ROMANCE_ATTESTED/GAME_REFERENCE claim 또는 LEGACY overlay 활성 시 실패.

### 그룹 B — 도시 분리 4모델 (§4,§7)
- **T1-B01 `[문서]` TemporalAdministrativeUnit 모델** — names[]/level/parentUnitId/validFrom·To/sourceRefs[]/confidence. 완료: 문서 미명시.
- **T1-B02 `[아키]` 〃 마이그레이션 (V2-0B)** / **T1-B03 `[아키]` 〃 row mapper+flush (V2-0B)** / **T1-B04 `[아키]` 〃 read API** — 완료: 문서 미명시.
- **T1-B05 `[문서]` PhysicalPlace 모델** — names[]/type/developmentClass/placeIdentityKey/placeBudgetClass/locationResolution/coordinate/candidateRegions[]/uncertaintyRadius/distinctPlaceClaimIds[]/validFrom·To/sourceRefs[]/confidence. 완료: locationResolution RESOLVED_POINT|CANDIDATE_REGION.
- **T1-B06 `[문서]` PhysicalPlace placeIdentityKey 중복 validator** — 선행 B05. 완료: 동일기간 같은 placeIdentityKey 둘 이상은 distinctPlaceClaimIds[] 없이 실패, 군치·현치 동일장소는 한 PhysicalPlace 두 assignment.
- **T1-B07 `[문서]` PlaceBudgetClass 예산 검증** — 선행 B05. 완료: ADMINISTRATIVE_SETTLEMENT 1,200/STRATEGIC_NON_ADMINISTRATIVE 200/EXTERNAL_PLACE 500/MARITIME_REMOTE_GATE 100, 상호배타 class 하나, class별+합계 2,000 검증, presence/range 미포함.
- **T1-B08 `[문서]` CANDIDATE_REGION 불확실 위치 처리** — 선행 B05. 완료: 좌표 미상 시 상위 군·국 envelope 후보 region, 날조된 점 금지.
- **T1-B09 `[아키]` PhysicalPlace 마이그레이션/mapper·flush/read API (V2-0B)** — 선행 B05. 완료: 문서 미명시. 비고: 3산출물 재분해 필요.
- **T1-B10 `[문서]` 불확실 위치 UI 렌더(영역+복원배지)** — 선행 B08. 완료: 개연 경계·지형을 확정 사료처럼 정밀 표시 금지.
- **T1-B11 `[문서]` SeatAssignment 모델** — administrativeUnitId/physicalPlaceId/role/validFrom·To/sourceRefs[]/confidence. 선행 B01,B05. 완료: 치소 canonical owner는 SeatAssignment.
- **T1-B12 `[문서]` SeatAssignment 겹침 중복 validator** — 선행 B11. 완료: (administrativeUnitId,role)의 [validFrom,validTo) 겹치거나 완전동일 행 중복 시 실패.
- **T1-B13 `[문서]` role→배지 파생 로직** — 선행 B11. 완료: 현치·군치·수도 배지는 활성 SeatAssignment.role에서만 파생.
- **T1-B14 `[아키]` SeatAssignment 마이그레이션/mapper·flush (V2-0B)** — 선행 B11. 완료: 문서 미명시.
- **T1-B15 `[문서]` PlaceControl 모델** — physicalPlaceId/controllerActorId/exercisingOfficeIds[]/authorityScopes[]/validFrom·To/sourceRefs[]/confidence. 선행 B05. 완료: 문서 미명시.
- **T1-B16 `[아키]` PlaceControl 마이그레이션/mapper·flush/ChangeRecorder 채널 (V2-0B)** — 점령이 PlaceControl 변경. 선행 B15. 완료: 문서 미명시. 비고: 3산출물 재분해.

### 그룹 C — 자원·수송·병력 (§4)
- **T1-C01 `[문서]` Facility 모델** — 현창·군창·태창·무고·전방 군량고 공간·capacity·상태. 선행 B05. 완료: 시설과 재고 두 canonical owner 중복저장 금지.
- **T1-C02 `[문서]` ResourceNode 모델** — Facility/PhysicalPlace/Formation/IN_TRANSIT 귀속 재고·예약·소비 ledger. 선행 C01. 완료: 중앙 국고·태창은 수도 Facility 연결 특별 ResourceNode.
- **T1-C03 `[문서]` ResourceNode onHand 불변식 validator** — 선행 C02. 완료: onHand=available+reserved, 음수 불가.
- **T1-C04 `[문서]` ResourceSite 모델** — 농경지·목장·광산·염장. 선행 B05. 완료: 문서 미명시.
- **T1-C05 `[아키]` Facility/ResourceNode/ResourceSite 마이그레이션 (V2-0B)** — 완료: 문서 미명시. 비고: 엔티티별 3티켓 재분해.
- **T1-C06 `[아키]` ResourceNode row mapper+flush+ChangeRecorder 채널 (V2-0B)** — 징병·세입 변경. 완료: 문서 미명시.
- **T1-C07 `[문서]` Formation 모델(주둔군·부곡·야전군 휴대 병력·보급)** — 선행 B05. 완료: 병력은 두 formation·주둔군 동시 소속 불가.
- **T1-C08 `[아키]` Formation 마이그레이션/mapper·flush (V2-0B)** — 주둔이 formation assignment 변경. 완료: 문서 미명시.
- **T1-C09 `[문서]` IN_TRANSIT 수송 상태기계** — 선행 C02. 완료: 원거점·운송중·도착거점 중 정확히 한 상태.
- **T1-C10 `[문서]` 자원 명칭 매핑(FUNDS/GRAIN/동원인력/장비)** — 선행 C02. 완료: 첫 simulation은 GRAIN 열량·부피·부패·말 사료만 계산.
- **T1-C11 `[문서]` 병력 생애주기 파이프라인** — 선행 C07. 완료: 전투 손실은 "생성"이 아닌 회복 대기 인력 전후 정산.

### 그룹 D — Formation 템플릿·실명 병종 (§5)
- **T1-D01 `[문서]` FormationTemplate 5축 모델** — recruitmentSource/commandAttachment/mobilityProfile/weaponLoadout/protectionProfile/doctrineCapabilities[]/supplyProfile/availabilityConstraints[]/provenance. 선행 A09. 완료: kernel 상속계층·고정 상성표 금지.
- **T1-D02 `[아키]` FormationTemplate 마이그레이션/mapper·flush (V2-0B)** — 완료: 문서 미명시.
- **T1-D03 `[문서]` 청주병 ContentEntry(PRIMARY_ATTESTED)** / **D04 백마의종** / **D05 호표기** — 각 소유자·시기·지역 제약+evidence 필수. 선행 D01.
- **T1-D06 `[문서]` 등갑병 ContentEntry(ROMANCE_ATTESTED)** — 선행 D01. 완료: 불 피해 계수는 BALANCE_ONLY, flammability·습윤·화공노출·이동능력 조합으로 자체 replay fixture 수치화.
- **T1-D07 `[문서]` 연노병 ContentEntry(SCHOLARLY_RECONSTRUCTION)** — 선행 D01,A06. 완료: 반복노 실재·제갈량 발명 전승 복수 claim 분리, 대량 운용 별도 claim.
- **T1-D08 `[문서]` 귀병·맹수병 LEGACY 보존(GAME_REFERENCE)** — 선행 D01,A15. 완료: 역사·연의 기본 병종 오인 금지.

### 그룹 E — 행정 변경 접기 엔진 (§7)
- **T1-E01 `[문서]` AdministrativeChange 모델** — id/type(7값)/effectiveFrom·To/datePrecision/dependsOnChangeIds[]/subjectUnitIds[]/predecessor·successorUnitIds[]/beforeStateHash/statePatch/sourceRefs[]/confidence. 선행 B01.
- **T1-E02 `[문서]` 접기 적용순서·type priority** — 선행 E01. 완료: 순서 effectiveFrom→type priority→change id, priority CREATE10→SPLIT20→MERGE30→RENAME40→REPARENT50→MOVE_SEAT60→RETIRE70, RENAME/REPARENT/MOVE_SEAT ID 보존/CREATE 새 ID/RETIRE 닫기/SPLIT·MERGE successor ID+lineage.
- **T1-E03 `[문서]` dependsOnChangeIds 검증** — 선행 E02. 완료: priority 충돌·미존재 ID 참조 시 실패.
- **T1-E04 `[문서]` beforeStateHash·모순 patch 검증+결정성 게이트** — 선행 E02. 완료: hash 불일치·같은날짜 모순 patch 시 build 실패, 같은 manifest·baseline·change stream이면 tree·hash 항상 동일.
- **T1-E05 `[문서]` 140년 군국지 baseline 전사(데이터)** — 선행 B01. 완료: 189 손작성 금지, time-scoped record 1,600개+ 목표. 비고: 추가 분해 필요(주/군/현 배치).
- **T1-E06 `[문서]` ScenarioActivationManifest 모델** — scenarioId/scenarioDate/catalogVersion/reconstructionVersion/entries[](entityId/status CANDIDATE|ACTIVE|EXCLUDED/supportingClaimIds[]/decisionReason). 선행 E01.
- **T1-E07 `[문서]` manifest branch 선택 로직** — 선행 E06. 완료: 선택 불가 시 snapshot 생성 실패, 189 manifest는 대상시기 189 포함+독립근거 항목만 ACTIVE, 3세기 목록 등장은 CANDIDATE.
- **T1-E08 `[문서]` ScenarioPlacement 모델+playableAnchor 결정선택** — scenarioId/physicalPlaceId/reconstructionId/playableAnchor/admissibleRegion/placementMode/claimIds[]. 선행 B08. 완료: region 안 anchor를 deterministic reconstruction으로 선택해 조회·점령·주둔·징병·세입·보급 참여.

### 그룹 F — Polity graph (§7)
- **T1-F01~F08 `[문서]` polity 8모델 각각 정의** — PolityNetwork/PolityNode/PolityMembership/PolityRelation/PolityTransition/DiplomaticActorAssignment/TerritorialPresence/SeasonalRange 각 1티켓. 선행 B05. 완료: 개별 필드 §7.
- **T1-F09 `[아키]` polity 8모델 마이그레이션 (V2-0B)** — 완료: 문서 미명시. 비고: 모델별 8티켓 재분해.
- **T1-F10 `[아키]` TerritorialPresence/SeasonalRange row mapper·flush (V2-0B)** — 완료: 문서 미명시.
- **T1-F11 `[문서]` polity graph orphan/유효기간 validator** — 선행 F01~08. 완료: membership·relation·transition·actor·presence·range orphan·유효기간 이탈 거부, presence/range 기간은 참조 PolityNode 활성기간 안+camp PhysicalPlace 겹치는 유효기간.
- **T1-F12 `[문서]` SPLIT/MERGE lineage 활성/비활성 validator** — 선행 F11. 완료: predecessor 전이 직전 활성, successor 전이 전 비활성, lineage 재생성 hash 동일.
- **T1-F13 `[문서]` DiplomaticActorAssignment 단일활성 validator** — 선행 F06. 완료: 한 PolityNode는 같은 시기 하나의 활성 assignment만.
- **T1-F14 `[문서]` 연맹 재생 fixture(hash diff 0)** — 선행 F11,F12,F13. 완료: 연맹형성→계절이동→분열→두 actor 승격 두 번 재생, node·presence·range·relation·actor hash diff 0.
- **T1-F15 `[문서]` PolityNetwork 240 예산 검증** — 선행 F01,A11. 완료: 동북·한반도 96/왜 32/북방 32/강·저·서역 48/산월·형남·남중·교주 32, claim 없는 slot을 catalog record·완료 수량화 금지, 별도 카운터 집계.

### 그룹 G — 지형·지도·3D (§7)
- **T1-G01 `[문서]` RouteCorridor 모델** — endpoints/타입(LAND|RIVER|CANAL|PASS|FERRY|COASTAL)/capacity/grade/seasonality/control/damageState/geometryConfidence/sourceRefs[]. 선행 B05. 완료: 도로는 잔도·관문·나루·하천 corridor.
- **T1-G02 `[문서]` 강 수송로/장애물 속성 분리** — 선행 G01. 완료: 상하행·나루·선박·수군 숙련·홍수 위험 분리.
- **T1-G03 `[문서]` TerrainRegion 모델** — basin/plain/mountain/wetland/pasture/coast/elevationProfile/drainage/vegetationInference. 완료: 현대 DEM은 물리 기초로만.
- **T1-G04 `[아키]` RouteCorridor/TerrainRegion 마이그레이션 (V2-0B)** — 완료: 문서 미명시.
- **T1-G05 `[문서]` Catalog LOD Tier A/B/C 정의** — 120/380/1,500. 선행 B05. 완료: 카메라 거리·기기 성능 불변.
- **T1-G06 `[문서]` runtime render LOD 정의(CLUSTER|SYMBOL|KIT|FULL_SCENE)** — 선행 G05. 완료: catalog tier와 독립, 판정은 동일 server read model, Tier C·CLUSTER도 stable identity 선택·검색 가능.
- **T1-G07 `[문서]` BattlefieldSeed 모델** — operationId/engagementGeoAnchor/season/weather/contentVersion/seed. 완료: 문서 미명시.
- **T1-G08 `[문서]` TerrainPatch 모델+terrainReconstructionStatus** — elevation/slope/soilMoisture/vegetation/water/road/builtObstacle+OBSERVED|RECONSTRUCTED|PLAUSIBLE. 선행 G07. 완료: 사료 확인 고지·나루·성벽은 authored 고정, 나머지 PLAUSIBLE, replay 기록.
- **T1-G09 `[문서]` 전략/전술 공유 좌표·projectionVersion 계약** — 선행 G01,G08. 완료: 두 scene 같은 좌표·projectionVersion.
- **T1-G10 `[문서]` Three.js 정사영 지휘 카메라 렌더** — 선행 G09. 완료: 서버가 좌표·가시성·충돌·피해 계산, 클라이언트 presentation만, terrain LOD·streaming이 판정 불변. 비고: 추가 분해 필요.
- **T1-G11 `[문서]` WebGL 불가 정사영 fallback** — 선행 G10. 완료: 별도 simulation·다른 이동 규칙 금지.
- **T1-G12 `[문서]` uncertaintyRadius·PLAUSIBLE 시각 구분 렌더** — 선행 G10. 완료: 개연 경계·지형을 확정 사료처럼 정밀 표시 금지.

### 그룹 H — 관리 계층·UI (§7)
- **T1-H01 `[문서]` 군·국 기본정책+현 override 우선순위** — 선행 B01,B05. 완료: 우선순위 현 명시 override > 유효 위임 명령 > 군·국 기본정책 > world default, 상위 정책 변경이 현 override 삭제 안 함.
- **T1-H02 `[문서]` 태수 위임 명령+변경이력·철회·복구** — 선행 H01. 완료: 위임 변경 이력·철회·복구 제공.
- **T1-H03 `[문서]` 군현 검색·필터+다중선택 예외적용 UI** — 선행 H01. 완료: 문서 미명시.
- **T1-H04 `[문서]` 공급·치안·주둔 이상 알림 UI** — 선행 H01. 완료: 문서 미명시.

### 그룹 I — 게이트·fixture (§7,§10)
- **T1-I01 `[문서]` G0 140 baseline fixture 게이트** — in-memory, DB write 없음. 선행 E02,E05,B12. 완료: 현급 1,180 모두 ACTIVE, SeatAssignment+placement 보유, EXCLUDED=0.
- **T1-I02~I07 `[문서]` CountyParticipationFixture 여섯 계약별 전수검증(6티켓)** — read/점령(PlaceControl)/주둔(formation assignment)/징병(가용인력·병력 ledger)/세입(현지 ResourceNode)/보급(예약·route 연결) 각 1티켓. 선행 I01+대응 모델. 완료(각): 해당 항목 1,180/1,180, orphan·no-op·다른 장소 오염 0, 순수 command/read-model contract, 종료 후 snapshot 초기화.
- **T1-I08 `[문서]` V2-0B sandbox 적재+runtime adapter 반복** — 선행 I02~I07+V2-0B 마이그레이션군. 완료: 문서 미명시. 비고: 추가 분해 필요.
- **T1-I09~I16 `[문서]` 4/3 슬라이스 8단계(단계별 8티켓)** — §10 (1) 4 formation 생성 (2) 곡창·군량고 Facility+ResourceNode·농경지 ResourceSite+route1 (3) FUNDS·GRAIN·장비·동원인력·주둔군 표시 (4) 사령턴 비축하한·수송우선순위+개인턴 수송 (5) 개인 출병 예약+전투가 피로·사기·탄약·보급 소비 (6) 생존·부상·포로·탈영·잔여물자 정산 (7) deterministic replay body hash+자원 보존 동일 (8) 3D proof scene(도시3·route2·terrain patch1·formation4)+spatial identity 유지. 완료(각): 해당 단계, 실명부대·등갑병·화공·나폴레오닉·엠파이어 포병 제외.
- **T1-I17 `[문서]` 후속 gate 순서 스캐폴딩** — 역사→CLASSIC 연의→타 시대 conformance. 선행 I16. 완료: 문서 미명시(후보만). 비고: 추가 분해 필요.

### 그룹 J — 턴 명령 연결 (§8)
- **T1-J01 `[문서]` 개인턴 책임 배선** — 모집·훈련·이동·수송호위·둔전·출병·주둔군 인수인계. 선행 C11,C09,D01. 완료: 개인 출병은 available 자원 예약해 작전 생성.
- **T1-J02 `[문서]` 사령턴 정책 배선** — 세율·징발한도·비축하한·주둔·수송우선순위·전선보급·외교원조. 선행 C02. 완료: 사령턴은 승인 문이 아니라 정책 부착.
- **T1-J03 `[문서]` 작전 명령 배선** — 출발거점·참가formation·보급예약·수송경로·집결지·퇴각선. 선행 J01. 완료: 문서 미명시.
- **T1-J04 `[문서]` 전투 재보급 소비 규칙** — 선행 J03. 완료: 이미 전장에 존재하는 보급대·거점만 소비.

### 그룹 K — 라이선스 (§11)
- **T1-K01 `[문서]` sourceLicense 필드 추가** — 선행 A01. 완료: 모든 외부 데이터 행에 sourceLicense.
- **T1-K02 `[문서]` CHGIS 라이선스 검토(번들 전)** — 완료: 위치 검증 연구엔 사용 가능하나 제품 자산 직접 번들 전 별도 검토.

## 데이터/스키마 신규 (문서1)
엔티티: EvidenceRef, HistoricalClaim, ContentEntry, CatalogBudget, CatalogBudgetSlot, TemporalAdministrativeUnit, PhysicalPlace, SeatAssignment, PlaceControl, Facility, ResourceNode, ResourceSite, Formation, FormationTemplate, AdministrativeChange, ScenarioActivationManifest, ScenarioPlacement, PolityNetwork/Node/Membership/Relation/Transition, DiplomaticActorAssignment, TerritorialPresence, SeasonalRange, RouteCorridor, TerrainRegion, BattlefieldSeed, TerrainPatch. Enum: evidenceClass(5), sourceProximity(7), WorldContentProfile(3), PlaceBudgetClass(4), locationResolution(2), AdministrativeChange.type(7), catalog LOD Tier(A/B/C), runtime render LOD(4), terrainReconstructionStatus(3). replay 버전 필드: geographyVersion/terrainTileVersion/projectionVersion/assetManifestVersion. 불변식: onHand=available+reserved 음수불가, budgetSlotId unique 양방향 일치. 아키: game-engine write는 ChangeRecorder→JdbcFlushExecutor 단일 경로만.

## 하지 않을 것 (문서1)
비범위: v1 PHP 패리티 병종·수치·로그 변경. v1 GameUnitConst·귀병·맹수병을 v2 역사 데이터로 재분류/덮어쓰기 금지, 토탈워를 역사 사실 증거로 사용 금지, 보병/기병/궁병 엔진 enum 고정 금지, v1 병종표 v2 정본 확장 금지, 정사·연의 한 데이터 행 혼합 금지, 혼합 evidenceClass 신설 금지, 후대 사료 이름을 189년 역투영 금지, 단일 국고(지도 밖 순간이동) 금지, 병사를 소모성 재화 한 칸 취급 금지, 오행 색·장수 유형 병종 하드락 금지, 장수당 6부대·10등급·정확 피해수치 정본화 금지, 토탈워 창작 병종을 사료 병종 표기 금지, 연구 한 번 상위 병종 양산 금지, 정사/연의 차이 전역 boolean 축소 금지, 189 완성본 수작업 우회 생성 금지, 좌표 미상 시 날조된 점 금지, 240개 항상 독립 AI 국가 실행 금지, 군·국 105와 현급 1,180 물리 도시 합산 금지, 백제국·사로국 후대 통일왕국 선반영 금지, 폐지 진번·임둔·이동 현도군 한 시점 중첩 금지, 야마타이 단일 정답 좌표 정본 금지, 이동성 집단 고정 도시망 전환 금지, G0 부하상한 채우려 행 생성 금지, 개연 생성 지형을 확정 사료처럼 정밀 표시 금지.

---

# 문서 2 — v2 황실·관직·개혁·장비 (`2026-07-13-v2-imperial-court-office-reform-equipment-design.md`, 665줄)

## 목적/범위 요약
황실을 `황제 점령=보너스` 단일 상태가 아니라 황제 신변·조정 소재지·관료·상서 문서기구·인장/절/조서·궁정경비·재정/군량·역참/사자망을 별도 실체로 분리. 관직도 officer_level 하나가 아니라 OfficeDefinition/Nomination/Claim/Tenure/OperationalAssignment/NobleTitle 6항목으로 분리, 모든 관직 행동은 OfficeDefinition 필드만으로 허용되지 않고 OfficeCapabilityResolver 통과. 협천자령제후는 강제 명령 버튼이 아니라 봉대/보정/협제 세 방침(CourtSettlement)+7단계 조서 파이프라인, 폐위·시해·선양은 네 번째 방침 아닌 별도 위기 chain. 개혁은 전국 boolean 아니라 군현별 제도 채택·확산(ReformDefinition/Proposal/Adoption), 장비/인장/옥새는 능력치 장신구 아닌 실제 소유자·위치·소유권 이력 문서·권한 도구. **비범위: v1 officer_level·국가 작위·아이템 효과와 PHP 패리티 변경.**

## 웨이브 구조 (문서 그대로)
G0/G1 번호 체계 없음. 명시 로드맵: 첫 수직 슬라이스(§16, 196년 피난 조정→허 정착, 10단계+13 수용조건), 명령 카탈로그(§14, 개인턴10/사령턴11/전략7) — 단 canonical id·payload·authority policy·rollout 순서 정본은 **별도 문서** `2026-07-12-v2-command-catalog-and-rollout.md`, 이 문서는 lifecycle·resolver 의미만 소관. 상태기계: 추천 작성→제출→심의중(원안/낮은관직·대행/보류/기각/경쟁), 임명조서→수락·사양→인장·부임명령→치소도착·속관인수→재임(정상/명목/분쟁/정지/파면·사망·사직·정권교체). CourtSettlement stance 이동: 협제→보정→봉대(황제 독자 재가·경비·재정 회복), 봉대→보정→협제(접근차단·상서숙청·인장강탈).

## 티켓 목록

### 그룹 A — 관직 6모델+resolver (§1,§3)
- **T2-A01~A06 `[문서]` 관직 6모델 각각 정의** — OfficeDefinition/OfficeNomination/OfficeClaim/OfficeTenure/OperationalAssignment/NobleTitle 각 1티켓(§3 스키마). 완료: §1의 6개 상태 모두 표현(명목 예주자사·자칭 주목·추천 기각·중앙+세력 겸직·사후 추인·지휘력/식읍 없는 장군호).
- **T2-A07 `[문서]` OfficeClaim.origin 8종+시작 신뢰도** — IMPERIAL_GRANT/COURT_CONFIRMED/POLITY_APPOINTMENT/SELF_STYLED/ACTING_XING/CONCURRENT_LING/CAMPAIGN_COMMISSION/POSTHUMOUS. 선행 A03. 완료: 이름 유사로 같은 임시직 보너스 합치기 금지.
- **T2-A08 `[문서]` 行/領/假節/持節 credential·capability 분리** — 선행 A07. 완료: 합치기 금지.
- **T2-A09 `[문서]` OfficeCapabilityResolver 구현** — 입력 claim origin+recognition/accepted·active tenure/assumed seat+staff+budget/seal·tally·document/OperationalAssignment/place·formation jurisdiction → ALLOW|DENY. 선행 A01,A07. 완료: handler·AI는 dutyCapabilityRequirements 단독 불가 resolver 결과만, 정식 임명이라도 부임·속관·예산·관인 없으면 명목 직함만.
- **T2-A10 `[아키]` 관직 6모델 마이그레이션(영속화 시점 미명시)** — 완료: 문서 미명시. 비고: 모델별 6티켓 재분해, 문서2가 영속화 시점 안 줌.
- **T2-A11 `[아키]` OfficeTenure/OfficeClaim row mapper·flush·ChangeRecorder 채널** — 완료: 문서 미명시.

### 그룹 B — 추천·임명·자칭 상태기계 (§4,§5)
- **T2-B01 `[문서]` 추천 상태기계(작성→제출→심의중→5분기)** — 선행 A02. 완료: 자동 성공률 판정 하나로 끝내지 않음.
- **T2-B02 `[문서]` 추천 심의 조건 평가** — 공석·정원·제도/추천자 표문권·조정영향력/후보 명성·경력·가문·충성·위치/파벌 이해/영토 지배자 관계/경쟁 claim/조정 강제 능력. 선행 B01. 완료: 문서 미명시(조건 목록만).
- **T2-B03 `[문서]` 조정 응답 분기(대행만 승인/다른 관직/관할 불승인/관직·작위 분리)** — 선행 B01. 완료: 기각뿐 아니라 4분기 표현.
- **T2-B04 `[문서]` 임명조서 상태기계(수락·사양→인장·부임명령→치소도착·속관인수→재임 5분기)** — 선행 A04. 완료: 사양·수락 후 미부임·협상 수단 표현.
- **T2-B05 `[문서]` 자칭(SELF_STYLED) 효과** — 자기 세력 OperationalAssignment·내부서열, SELF_STYLED claim, 부하·명사·타세력 인정/유보/부정, de facto 권한. 선행 A05,A07. 완료: 자칭은 금지된 실패 명령 아님.
- **T2-B06 `[문서]` 자칭→추인(COURT_CONFIRMED) 이력 보존** — 선행 B05. 완료: 추인 시 claim 이력 보존+origin에 COURT_CONFIRMED 추가.
- **T2-B07 `[문서]` 세 포트폴리오 겸직 표현(조정/지방/소속정권 역할)** — 선행 A01. 완료: 중앙·지방·장군호·후작·군벌 내부 역할 겸직 가능.
- **T2-B08 `[문서]` 관직 위치 요구+대리·정보비대칭 비용** — 수도출석·순행·치소상주·전선지휘. 선행 B07,A09. 완료: 미수행 관직은 명예·서열·정통성 claim만, 상충 관할 동시 행사는 명시적 겸임 조서·속관·통신망 필요.
- **T2-B09 `[문서]` v1 che_발령→OperationalAssignment adapter** — 선행 A05. 완료: 조정 관직 안 만듦, 조정 관직은 추천·조서·수락·부임 workflow 필수, 발령 주체 정권 claim 기록.
- **T2-B10 `[문서]` actualJurisdiction 실효 지배 판정** — 치소점유+속관복종+장부인수+창고·역참·인장통제+주둔군+지역명사협력+상급지원. 선행 A04,A09. 완료: 관직명만으로 지도 색 변경 금지.

### 그룹 C — 관직 계층 카탈로그 (§6)
- **T2-C01~C09 `[문서]` 관직 9계층 content pack 정의(계층별 9티켓)** — 황제·내조/재상·삼공/구경/중앙무관/주/군·국/현·후국/막부·속관/작위 각 1티켓. 선행 A01. 완료: 같은 이름도 후한·조위·촉한·손오 권한 상이 가능 → universal enum 고정 효과 금지, 근거는 『후한서』 백관지·개별 열전. 비고: 계층 내 관직 데이터 입력 추가 분해 필요.
- **T2-C10 `[문서]` 상설/임시(commission) 구분** — 선행 A01. 완료: 상설은 기관·속관·예산 필요, 장군호는 설치·해제 commission.

### 그룹 D — 황실 정본 모델 (§7)
- **T2-D01~D06 `[문서]` 황실 6모델 각각 정의** — ImperialHouse/Emperor/ImperialCourt/ImperialRegalia/CourtProtectorate/CourtSettlement 각 1티켓(§7 스키마). 완료: 황제·조정·수도·옥새는 하나의 객체 아님, 피난+조정 분산+옥새 불명 표현.
- **T2-D07 `[아키]` 황실 6모델 마이그레이션/mapper·flush** — 완료: 문서 미명시. 비고: 모델별 재분해.
- **T2-D08 `[문서]` CourtSettlement 세 방침 정의** — 봉대(HONOR_AND_RESTORE)/보정(COREGENT_PROTECTORATE)/협제(COERCIVE_CONTROL)+proclaimedTerms/actualPracticeEvidence. 선행 D06. 완료: 고정 세력 특성 아니라 공개 헌정 약속.
- **T2-D09 `[문서]` stance 이동 로직** — 선행 D08. 완료: 협제→보정→봉대(독자 재가·경비·재정 회복), 봉대→보정→협제(접근차단·상서숙청·인장강탈), 실제 행동이 약속과 어긋나면 이동.

### 그룹 E — 협천자 조서 파이프라인 (§8,§9)
- **T2-E01~E07 `[문서]` 7단계 조서 파이프라인(단계별 7티켓)** — (1) 표문·조서안 제안 (2) 황제·파벌 동의·수정·거부·밀지 (3) 상서 기초·등록 (4) 인장·절·부절 부착 (5) 사자 역참·군사호위 전달 (6) 수신 세력 진위·강압·이익·권위 판단 (7) 수락·부분수락·지연·거부·공개비난 후 집행. 선행 D01~D06. 완료(각): 해당 단계 동작.
- **T2-E08 `[문서]` EdictCredibility 산식** — 실제 재가+상서 기록+인장 진위+관료 증언+의례·연호 연속+사자 신뢰+수신 이익 − 공개강압·위조·반복불복종·조정붕괴. 선행 E07. 완료: 수치 하나로 모든 세력 동일 효과 금지, LegitimacyAudience·수신 이해관계가 별도 평가.
- **T2-E09 `[문서]` 조서 발행/거부 결과 처리** — 선행 E08. 완료: 발행돼도 수신 군벌 거부+영토 유지 가능, 거부엔 조정 불복 외교·정통성 비용.
- **T2-E10 `[문서]` 보호자 얻는 것/부담하는 것 배선** — 청원 우선접근·조서초안·정통성·외교명분·정보이점 vs 식량·급료·수도복구·요구감시·굴욕·자율성 딜레마·탈출/쿠데타 위험. 선행 D08. 완료: 문서 미명시(효과 목록만).

### 그룹 F — 황제 행위능력·황실 상태 (§9,§10)
- **T2-F01 `[문서]` 황제 행위능력(imperial.*)** — 공개재가/거부/지연/조건부/사면/밀지/탈출/후계지명/양위협상. 선행 D02. 완료: 유저 황제는 개인턴에서 imperial.* 제출, NPC는 Court AI 동일 명령·제약, 새 턴 링 신설 금지.
- **T2-F02 `[문서]` 궁정 관료·황실 가족 독자 파벌·정보망·경호 충성** — 선행 D03. 완료: 문서 미명시.
- **T2-F03 `[문서]` 조서 위조를 별도 계략 명령으로 분리** — 선행 E01. 완료: 보호국 사령턴은 청원·추천·경호·이동 제안만, 직접 위조 금지, 위조는 발각·문서대조·사자증언·인장진위 위험.
- **T2-F04 `[문서]` 황실 상태 6종** — ITINERANT/PROTECTED/DOMINATED/DIVIDED/RESTORED/ABOLISHED_OR_TRANSITIONED. 선행 D03. 완료: 각 상태 위험·기회 반영.
- **T2-F05~F08 `[문서]` 세 방침 이후 후속 사건 4종(사건별 4티켓)** — 구출·천도(호송 operation)/폐위·시해(succession crisis)/선양·찬탈(dynasty transition)/한 조정 거부·별도 천명(legitimacy break) 각 1티켓. 선행 D08,E08. 완료: 네 번째 방침이 아니라 별도 court.succession.resolve 위기·종결 경로.

### 그룹 G — 인장·옥새·장비 (§11)
- **T2-G01 `[문서]` 물품 6분류 정의** — PERSONAL_EQUIPMENT/MOUNT/KNOWLEDGE_OBJECT/OFFICE_INSTRUMENT/STATE_REGALIA/HUMAN_FOLLOWER. 완료: 사람 추종자를 장비 inventory 이동 불가.
- **T2-G02 `[문서]` 소유권 이력(포획·증여·상속·교역·몰수·분실)** — 선행 G01. 완료: 고유 물품은 복제 안 되고 실제 소유자·위치 보유.
- **T2-G03 `[문서]` ImperialRegalia authenticityClaims 경쟁** — 진위·전승·발견경위·증언. 선행 D04. 완료: 옥새는 소유 즉시 황제 아님, 옥새만 있으면 정통성 한 근거일 뿐.
- **T2-G04 `[문서]` 관인·부절 유효 관직·관할·기간 기록** — 선행 A09,G01. 완료: 어느 관직·관할·기간 유효한지 기록.
- **T2-G05 `[문서]` 조서 세트 조건(인장+문서고+상서+사자망)** — 선행 E01. 완료: 네 요소 함께 있어야 조서 파이프라인 정상 작동(세트 효과 대신).
- **T2-G06 `[아키]` 물품/regalia 마이그레이션·mapper·flush** — 선행 G01. 완료: 문서 미명시.

### 그룹 H — 개혁 (§12,§13)
- **T2-H01~H03 `[문서]` 개혁 3모델 각각 정의** — ReformDefinition/ReformProposal/ReformAdoption 각 1티켓(§12 스키마). 선행 A01. 완료: 문서 미명시(필드 §12).
- **T2-H04 `[문서]` 개혁 수명주기** — 문제인식→상주→심의→시범군현→시설·속관·예산→현지시행→보고→확대/수정/철회/지역변형. 선행 H01. 완료: 전국 boolean 아님, 시범 군현 먼저 전국 즉시 적용 금지.
- **T2-H05 `[문서]` 개혁 지역 채택 요구(사자·관인·속관·지역협력)** — 선행 H04. 완료: 허 조정 공포해도 익주·강동·산지 실제 채택엔 위 요소 필요.
- **T2-H06~H13 `[문서]` 개혁 8분야 카탈로그(분야별 8티켓)** — 호구·추천·관직·문서 / 율령·재판·감찰·사면 / 조세·화폐·염철·시장·도량형 / 농업·수리·둔전·창고·구휼 / 역참·도로·교량·나루·수송 / 징발·모집·군호·주둔·군량·장비 / 학교·의례·율력·종교 / 외교·책봉·속국·인질·통행·자치 각 1티켓. 선행 H01. 완료: 문서 미명시. 비고: 분야 내 개혁 항목 추가 분해 필요.
- **T2-H14 `[문서]` 국가 성향별 개혁 지지기반 연결** — 유가(학교·추천)/법가(호구·율령)/태평도·오두미도·도적(자기 조직망 기구). 선행 H01. 완료: 국가 성향 이름 유지하되 개혁 하드락 금지.
- **T2-H15 `[문서]` 세력 고유성 구성 통합** — 국가성향+지도자 관계·야망+조정·관직·작위 claim+지역 조직망·자원+시행 개혁+역사·연의 overlay. 선행 A01,H01. 완료: 지도자 ID에만 붙이기 금지, 도적이 관직 받아도 즉시 한식 국가 안 됨.

### 그룹 I — 명령 카탈로그 lifecycle/resolver (§14)
※ payload·canonical id 정본은 별도 문서 `2026-07-12-v2-command-catalog-and-rollout.md`. 이 문서는 lifecycle·resolver 의미만.
- **T2-I01~I10 `[문서]` 개인턴 명령 10개(명령당 1티켓)** — personal.office.petition/accept/assume/exercise/selfStyle, personal.court.audience/assentEdict/carryEdict/escort, personal.reform.pilot. 선행 A09+대응 상태기계. 완료(각): §14 설명+resolver 통과. 비고: payload는 rollout 문서 의존.
- **T2-I11~I21 `[문서]` 사령턴 명령 11개(명령당 1티켓)** — chief.office.nominate/assignOperationalRole/challengeClaim, chief.court.petition/protect/relocate/proposeEdict/dispatchEdict, chief.reform.propose/expand/repeal. 완료(각): §14 설명. 비고: 상동.
- **T2-I22~I28 `[문서]` 전략 domain resolve 명령 7개(명령당 1티켓)** — court.edict.resolve/dispatch/respond, office.claim.resolve, office.jurisdiction.resolve, court.succession.resolve, reform.adoption.resolve. 완료(각): §14 설명, 실시간 전술은 유효 OperationalAssignment·campaign commission만 확인.

### 그룹 J — UI (§15)
- **T2-J01 `[문서]` 인물 관직 카드 렌더** — 조정관직/지방관직/소속국역할/작위/추천이력/경쟁claim 분리. 선행 A01,B10. 완료: 법적 관할 vs 실효 관할, 식읍 claim vs 실제 수납 분리.
- **T2-J02 `[문서]` 황실 화면 렌더** — 황제·후계·종실 신변, 조정 기능 상태, 충성 비율, 옥새·관인·문서고 소재·진위, 조서별 상태, 추천 타임라인, 청중별 반응. 선행 D01~D06,E08. 완료: `황실 호의 62` 한 줄 축소 금지.
- **T2-J03 `[아키]` 관직·황실 read API 컨트롤러** — 선행 A11,D07. 완료: 문서 미명시. 비고: 카드/황실 2티켓 분리 가능.

### 그룹 K — 첫 수직 슬라이스 (§16)
- **T2-K01~K10 `[문서]` 196 허 정착 fixture 10단계(단계별 10티켓)** — (1) 황제·백관·상서 문서망·궁정경비·인장/절·군량 수송대 별도 객체 (2) 두 군벌 영접 제안(경로·공급·자율성 조건) (3) 황제 AI 보호조건 평가·이동선택 (4) 보호자가 장수를 지방관 추천 (5) 조정 원안·대행·기각 근거 선택 (6) 미부임 시 명목 관직 (7) 다른 군벌 자칭+치소 장악 (8) 조서 도착·양측 수락·거부·추인 협상 (9) 밀지·탈출 (10) replay가 조서·인장·추천·관직·실효지배 전체 이력 재현. 선행 그룹 A~G 다수. 완료(각): 해당 단계+§16 수용조건 매핑.
- **T2-K11 `[문서]` 첫 수직 슬라이스 13개 수용조건 게이트** — 선행 K01~K10. 완료: §16 13개 조건 전부(도시 점령만으로 CourtProtectorate 자동이전 금지 / 황제·조정·상서·옥새·경비·군량 소재 상이 / 조서 발행돼도 거부+영토 유지 / 거부에 외교·정통성 비용 / 추천 5분기 표현 / 자칭·황제임명 동시+세력별 인정 / 중앙·지방·소속국·작위·formation 독립 표시 / 치소·속관·예산·인장 없이 capability 불가 / 모든 관직 행동 resolver 통과 / 사람 추종자 inventory 이동 불가 / 개혁 시범 군현 먼저).

## 데이터/스키마 신규 (문서2)
엔티티: OfficeDefinition, OfficeNomination, OfficeClaim, OfficeTenure, OperationalAssignment, NobleTitle, ImperialHouse, Emperor, ImperialCourt, ImperialRegalia, CourtProtectorate, CourtSettlement, ReformDefinition, ReformProposal, ReformAdoption. Enum: OfficeClaim.origin(8), CourtSettlement stance(3), 황실 상태(6), 물품 분류(6). 파생: EdictCredibility. 기존 매핑: v1 che_발령→OperationalAssignment adapter. **v1 officer_level은 비범위(변경 안 함), v2 관직 6항목이 병존 신규 계층.** 명령 정본 경계: canonical id·payload·rollout 순서는 별도 문서 `2026-07-12-v2-command-catalog-and-rollout.md`(§14).

## 하지 않을 것 (문서2)
비범위: v1 officer_level·국가 작위·아이템 효과와 PHP 패리티 변경. 토탈워/개혁: 대기 후 전국 즉시 적용 추상 기술트리·오행 색 선행노드·수입+15%만 남는 개혁·건물 보유만으로 전국 제도 연구 복제 금지. 토탈워/장비: 사람 추종자를 늙지 않는 장비 슬롯 취급·반란군 무작위 장신구 생성·세트 착용만으로 전국 비축·수입 변경·옥새·관인·절을 능력치 장신구 취급 금지. 토탈워/세력: 건물 위신으로 자동 승급·황제 도시 점령 세력이 즉시 천자 소유·천자 옹립을 정기 이벤트 전역 보너스 축소·황건·도적이라는 이유로 외교 문법 임의 차단 금지. 관직명 유사로 임시직 보너스 합치기 금지, 추천을 자동 성공률 판정 하나로 끝내기 금지, 관직명만으로 지도 색 변경 금지, 협천자를 강제 명령 버튼/황제 소유 boolean/전역 보너스 금지, 폐위·시해·선양을 네 번째 동급 방침 금지, 조서 신뢰도를 수치 하나로 동일 적용 금지, 황제를 PUPPET=true 아이템 취급 금지, 새 턴 링 신설 금지, 보호국 사령턴 황제 명령 직접 위조 금지, 옥새를 소유 즉시 황제 되는 아이템 금지, 사람 추종자를 장비 inventory 이동 금지, 개혁을 즉시 전국 적용 기술트리 취급 금지, 국가 성향으로 개혁 하드락 금지, 세력 고유성을 지도자 ID에만 붙이기 금지, universal enum 고정 관직 효과 금지, 제자백가·기존 국가 성향 이름 변경 금지.

---

## 공통 주의 & 커버리지 요약
- 모든 v2 신규 write는 기존 ChangeRecorder→JdbcFlushExecutor 단일 경로만(문서1 §9, one-daemon-write-rule). v2는 v1 PHP 골든 게이트를 건드리지 않는 additive 작업.
- 문서2 §14 명령 payload·rollout 정본은 본 분해 대상 밖 문서 `2026-07-12-v2-command-catalog-and-rollout.md` → T2-I 그룹 착수 전 확인 필요.
- 문서1 §1 참조 `2026-07-13-v2-troop-building-content-catalog.md`(formation 전통 120·시설 72·기반망 18·자원 24)와 `2026-07-13-v2-nation-identity-rework.md`(국가 정체성 15항목)도 본 분해 대상 밖 → T1-D(실명 병종)·T1-G(시설)·T2-H14(국가 성향)의 실제 콘텐츠 데이터 입력이 그 문서에 의존.
- 커버리지: 문서1 약 11그룹 원자 티켓 ~95개("추가 분해 필요" 9곳), 문서2 약 11그룹 원자 티켓 ~90개("추가 분해 필요" 6곳). `[아키]` 티켓은 스펙 문서가 아니라 저장소 CQRS 아키텍처 추론 산출물이며 대부분 완료기준 "문서 미명시" — 착수 전 실제 영속화 시점(문서1 V2-0B, 문서2 미명시)을 팀에서 확정 필요.

읽기 전용 작업으로 파일은 수정하지 않았음. 대상 파일 절대경로: `/Users/apple/Desktop/개인프로젝트/opensamguk/docs/superpowers/specs/2026-07-13-v2-historical-city-army-terrain-design.md`, `/Users/apple/Desktop/개인프로젝트/opensamguk/docs/superpowers/specs/2026-07-13-v2-imperial-court-office-reform-equipment-design.md`.