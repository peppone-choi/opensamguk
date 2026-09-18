# 장수 위치의 정본 — 省 위치 권위 (Tier-0 기반)

> 작성일: 2026-09-18
> 상태: **초안(제안).** 교차 비평·사용자 승인 전. 3단계 첫 기반, 판정문 [V59 일반화 판정](../research/2026-09-17-v59-generalization-verdict.md) §4.2·§4.3-1 의 「선행 리팩터 하나」.
> 상위: [장수·휘하 캠페인 재설계](./2026-09-17-general-and-retinue-campaign-redesign.md) §2.4·§4·§5.1·§6.2·§7·§17, [입력 registry 계약](./2026-09-17-input-registry-contract.md) §2(ruleProfile), ADR-LITE-050(공간 상태)·057.
> 범위: 「장수가 어디에 있는가」의 정본과 동기 방향만 정한다. 행군 경로·조우·점유는 뒤 묶음(판정문 §4.3-2 이후)이고, 여기서는 그것들이 기댈 불변식만 둔다. 수치 없음.

## 1. 현행 실측(판정문에서 코드로 확인된 것)

| 사실 | 근거 |
|---|---|
| 위치의 정본은 `general.city_id` 다. `general_spatial_position` 행은 **선택적**이고 城 이동을 **뒤따라** 고쳐진다 | `ENG/turn/BattlefieldCityMembership.kt:9-42` — 행이 없으면 건너뛰고, `relocated` 면 새 城의 省으로 옮긴다 |
| 위치 행은 **전장 진입 때만** 만들어진다. 시드·일반 이동은 행을 만들지 않는다 | 판정문 §2 R2, 쓰기 호출자 전수 `ENG/war/BattlefieldTurnHandler.kt:80-82`·`BattlefieldCityMembership.kt` |
| 城 → 省 대응은 이미 있다: 런타임 城마다 `spatialProvinceId`, 외부 거점(`external:v1:`)만 省이 없다 | `INFRA/seed/HistoricalBattlefieldCatalog.kt:20-48` `cityAnchors()`; 동봉 1133 리소스에서 省 없는 城은 0건(판정문 §5-7) |
| 저장·부팅·CAS 는 갖춰져 있다: `loadGeneralPositionSnapshot` ALWAYS_HOT, `applyGeneralPositionAssessment` CAS, `generalPositionWriteMany`, 위상 핀 검사 | 판정문 §1.2 표, `MIG/V50` |
| 전장 주둔 중 `general.city_id`·`TurnGeneral.cityId` 는 **귀환 城을 유지**한다. `cityId = 0` 은 논리 계층 **사본**에만 들어가고(`PerTurnOverlay.kt:74-75`), 「城에 없음」의 실제 키는 `isGeneralAtBattlefield`(= 위치 행의 `battlefield != null`)이며 그 키로 도시 행동을 막는 지점이 18곳이다(교차 비평 실측: `ProcessNationCommand` 4·`ReservedTurnHandler` 5·`GeneralReadRepository` 3·dispatcher 2·`BattleCommandContextBuilder`·`PerTurnOverlay`·`CommandPrecheckService` 등). 저장된 `city_id = 0` 을 견디는 코드는 없다 — `ProcessNationCommand.kt:477-478` 은 `error()` 다 | `ENG/turn/PerTurnOverlay.kt:74-75`, `ENG/war/BattlefieldTurnHandler.kt:43,61`, `ENG/turn/InMemoryTurnWorld.kt:227-233` |
| `.cityId` 를 읽는 곳이 main 에 215곳(engine 90·logic 86·game-api 39). 명시적 `cityId == 0` 처리는 10곳뿐 | 교차 비평 grep 재현 |
| 城 없는 省이 1,594 중 **461(29%)** — 「위치 省에 城이 없는」 상태는 드문 예외가 아니라 지도의 3분의 1이다 | `han-tiles.json` provinceRecords vs 1133 cities |
| 런타임에 장수를 만드는 경로가 7곳이다(`recordGeneralCreate` 호출 6 + `MakeGeneralHandler.kt:215`), 등장연도가 늦은 시나리오 장수도 시드가 아니라 event 행으로 들어온다 | `ScenarioImporter.kt:948,979`, 교차 비평 |

## 2. 결정(제안)

### 2.1 정본

- **HWIHA 월드**(입력 registry 계약 §2 의 `ruleProfile`): 장수의 위치 정본은 `general_spatial_position` 의 `(node_kind, node_id)` 다. **모든 장수가 행을 가진다**(없음 = 결함).
- `general.city_id` 는 **기준 城**이다 — V59 의 귀환 城과 같은 뜻으로, **절대 0 이 되지 않는다.** 위치 省에 城이 있으면 그 城, 없으면 **마지막으로 섰던 城**을 유지한다. 도시 소속·소득·AI 도시 뷰 등 215곳의 `cityId` 소비자는 그대로 기준 城을 읽는다.
- 「城에 없음」은 저장값이 아니라 **파생 술어** `atCity(g) := cityAnchors[g.cityId] == position(g).node` 다. 전장 주둔(`battlefield != null`)은 이 술어의 특수형이다. 도시 행동을 막는 18곳의 키를 `isGeneralAtBattlefield` 에서 `!isGeneralAtCity` 로 넓히는 것이 첫 묶음의 실제 작업이다(§3-4).
- **SAMMO 월드**: 아무것도 바뀌지 않는다. `general.city_id` 정본, 위치 행 선택적, 기존 전장 경로 그대로(재설계 §17, 판정문 §4.4). `isGeneralAtCity` 는 SAMMO 에서 항상 `!isGeneralAtBattlefield` 와 같다.

### 2.2 동기 방향

| 사건 | SAMMO(현행) | HWIHA(제안) |
|---|---|---|
| 월드 시드 | 위치 행 없음 | 장수마다 `city_id` 의 省으로 행 생성(`cityAnchors`). 省 없는 城에 선 장수는 시드 실패(현 리소스 0건) |
| 城 이동(기존 `che_이동` 류, 발령 부임 등) | `city_id` 갱신 → 행 있으면 뒤따라 고침 | **위치를 먼저 쓰고** `city_id` 를 투영. 城 이동 = 「목적 城의 省으로 이동」의 특수형 |
| 省 이동(행군, 뒤 묶음) | 없음 | 위치 쓰기. 도착 省에 城이 있으면 `city_id` 를 그 城으로, 없으면 기준 城 유지(`atCity = false`) |
| 장수 생성(시드·이벤트 등장·유저 생성·`MakeGeneralHandler`) | 위치 행 없음 | **생성 = 위치 행 생성.** `recordGeneralCreate` 한 곳에서 `city_id` 의 省으로 행을 만들고, `MakeGeneralHandler.kt:215` 의 별도 경로도 같은 함수를 부른다 |
| 행 삭제 | 전장 이탈·외부 거점·강제 해제 | **금지.** 사망·삭제 장수만 FK cascade 로 사라진다 |
| `city_id` 를 직접 쓰는 우회 경로(`InstantActionHandler.kt:75-76` 즉시 퇴각 등) | `city_id` 직접 갱신 | 위치 쓰기 진입점으로 바꾼다. 「관리자 강제 이동」 인테이크는 코드에서 찾지 못했다(UNKNOWN) |

### 2.3 불변식(HWIHA)

1. 살아 있는 장수 수 = 위치 행 수. 짝: **장수 생성 = 위치 행 생성**(§2.2). 부팅(`InMemoryTurnWorld.kt:65` 의 orphan 검사를 동등 검사로 강화)과 flush 직전 양쪽에서 검사한다.
2. `city_id` 는 항상 존재하는 城이다. 위치 省에 城이 있으면 `cityAnchors[city_id] == 위치 node`(`atCity`), 없으면 `city_id` 는 직전 `atCity` 였던 城이다.
3. 한 변경 단위(같은 `ChangeRecorder` 버퍼) 안에서 위치와 `city_id` 가 함께 바뀐다. 한쪽만 바꾸는 API 를 두지 않는다.
4. `battlefield_*` 열(V59)은 HWIHA 에서 **항상 NULL** 이다(판정문 §4.1 동결).

### 2.4 두지 않는 것

- 행군 중 상태(진행 중 경로·남은 비용)는 여기 넣지 않는다 — 판정문 §4.3-2 의 새 열/표. 이 문서는 「도착한 省」만 정의한다.
- `province_control` 의 의미(판정문 UNKNOWN-2)는 정하지 않는다.
- 위치가 縣 단위인지 省 단위인지: 재설계 §7 「省 = 군단 위치, 縣 = 내정」에 따라 **省**이다. 縣은 관할 조회로 파생한다.

## 3. 구현 형태(첫 묶음)

1. **읽기 뷰**: `InMemoryTurnWorld.positionOf(generalId)`(HWIHA 에서 non-null)와 `isGeneralAtCity(generalId)`. 기존 `generalPositionSnapshot()`·`isGeneralAtBattlefield` 위에 얇게.
2. **쓰기 진입점 하나**: `recorder.moveGeneral(world, generalId, to: StrategicNodeRef)` — 위치 CAS 쓰기 + `city_id` 갱신(省에 城이 있을 때만)을 한 번에. `removeGeneralPosition` 이 이미 쓰는 「`diffGeneral` + `applyGeneralDirtyFree` 를 같은 버퍼에」 패턴을 따른다. HWIHA 의 모든 이동은 이것만 부른다.
3. **생성 = 행 생성**: `recordGeneralCreate` 안에서 HWIHA 면 `cityAnchors[city_id]` 로 행을 만든다. `MakeGeneralHandler` 의 별도 경로도 같은 함수를 탄다. 시드는 `ScenarioImporter.importAdmitted` 의 `insertGenerals` 직후에 전 장수 행 INSERT(핀은 `HanWorldArtifactsResolver.resolve(cityIds, emptyList()).projection.topology`).
4. **차단 키 넓히기**: 18곳의 `isGeneralAtBattlefield` 를 `!isGeneralAtCity` 로. SAMMO 에서는 두 값이 같으므로 바이트 불변.
5. **`applyPositionAwareGeneral`**: 호출자 14곳 중 이동인 곳은 `moveGeneral` 로, meta 갱신뿐인 4곳은 그대로 둔다(HWIHA 에서 통째로 끄면 그 갱신이 사라진다 — 교차 비평 S7). `InstantActionHandler` 의 우회 쓰기를 진입점으로 돌린다.
6. **ruleProfile 선행 의존**: 계약 §2 의 ruleProfile 은 아직 코드에 없다(0건). 이 묶음은 「시나리오 JSON 에 `ruleProfile` 필드 하나(없으면 SAMMO) → `ScenarioImporter` 가 `world_state.config` 에 기록 → 런타임이 읽음」을 **함께** 구현한다. 기존 시나리오 파일은 필드가 없어 SAMMO 다.
7. `cityAnchors` 는 클래스패스 리소스(`map/han-world-v3.json`)를 읽고 부팅은 변형 아카이브를 쓴다(교차 비평 S4) — 행 생성·`atCity` 는 부팅이 고른 변형의 `projection.bindingsByCityId` 를 써야 한다.
8. 스키마 변경 없음. V50 표·CAS·flush 그대로.

## 4. 게이트(적색 프로브 필수)

1. **SAMMO 바이트 불변**: 기존 골든·`SpatialStatePersistenceIT`·`BattlefieldTurnHandlerTest` 등 전부 그대로 통과. HWIHA 분기를 SAMMO 로 잘못 타면 빨개지는 테스트(예: SAMMO 시드에서 위치 행이 생기면 실패).
2. HWIHA 시드 뒤 「장수 수 = 위치 행 수」, 행 하나를 지우면 부팅이 실패한다.
3. `moveGeneral` 로 城 없는 省에 보내면 `city_id` 는 유지되고 `isGeneralAtCity == false`, 城 있는 省이면 `city_id` 가 그 城으로 바뀌고 `true`. `city_id` 를 직접 써서 위치와 어긋나게 하면 불변식 2 검사가 잡는다.
3b. 런타임 장수 생성 7경로 각각에서 행이 생긴다 — 한 경로의 훅을 빼면 flush 직전 검사가 빨개진다.
4. `city_id` 만 갱신하는 옛 경로가 HWIHA 에서 호출되면 실패(호출 자체를 막는 가드).
5. HotColdCatalog 등록 불변(새 표 없음) — `HotColdWorldCatalogGuardTest` 그대로.

## 5. 회귀 기준선 영향

스키마·SAMMO 경로 무변경 — 판정문 §4.4 와 같다. `city_id` 가 0 이 되는 일이 없으므로 215곳의 `cityId` 소비자는 값의 의미가 바뀌지 않는다. 바뀌는 것은 「그 城에 실제로 있는가」이고, 그것은 18곳의 차단 키를 넓히는 작업이 맡는다. 넓히지 못한 소비자는 「城에 없는 장수를 城에 있는 것처럼」 다룬다 — 하드 실패가 아니라 **조용한 오동작**이라 게이트 4 의 목록 대조가 필요하다.

## 6. 미결

- 18곳 밖에서 「城에 있음」을 전제하는 소비자(AI 도시 뷰 `AiWorldView.kt:371`, 보급 감쇠, 월 훅, 읽기 목록 등 교차 비평 S2 의 5계열)를 첫 슬라이스에서 어디까지 넓힐지 — 省 행군이 들어오기 전에는 `atCity` 가 거짓인 장수가 생기지 않으므로, 이 목록은 행군 묶음(판정문 §4.3-2)의 선행 조건으로 넘길 수 있다.
- `moveGeneral` 이 받을 위상 핀 불일치(리셋 뒤 토폴로지 변경) 처리 — 기존 CAS 거절을 그대로 쓸지.
- 재야(nation 0) 장수도 城·省을 가진다(시드 `ScenarioImporter.kt:496`, 유저 생성 `MakeGeneralHandler.kt:187`) — 현행 데이터와 맞는다. 방랑 주공(재설계 §2.1)이 城 없는 省에 서는 경우가 첫 `atCity = false` 사례가 될 것이다.
