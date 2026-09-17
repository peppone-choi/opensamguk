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
| 전장 주둔 중에는 논리 계층에 `cityId = 0` 으로 가려 준다 — 「城에 없음」을 표현하는 선례 | `ENG/turn/PerTurnOverlay.kt:75` `physicalLogicGeneral` |
| `.cityId` 를 읽는 곳이 엔진·logic main 에 176곳이다(단순 grep, 정확한 소비자 수는 아님) | `grep -rn "\.cityId" app/game-engine/src/main logic/src/main` |

## 2. 결정(제안)

### 2.1 정본

- **HWIHA 월드**(입력 registry 계약 §2 의 `ruleProfile`): 장수의 위치 정본은 `general_spatial_position` 의 `(node_kind, node_id)` 다. **모든 장수가 행을 가진다**(없음 = 결함, 부팅 실패).
- `general.city_id` 는 **투영**이다: 위치 省에 城이 있으면 그 城, 없으면 `0`(「城에 없음」 — `PerTurnOverlay.kt:75` 선례를 정본으로 승격). 투영은 위치 쓰기와 같은 변경 단위에서 갱신되며 따로 쓰이지 않는다.
- **SAMMO 월드**: 아무것도 바뀌지 않는다. `general.city_id` 정본, 위치 행 선택적, 기존 전장 경로 그대로(재설계 §17, 판정문 §4.4).

### 2.2 동기 방향

| 사건 | SAMMO(현행) | HWIHA(제안) |
|---|---|---|
| 월드 시드 | 위치 행 없음 | 장수마다 `city_id` 의 省으로 행 생성(`cityAnchors`). 省 없는 城에 선 장수는 시드 실패(현 리소스 0건) |
| 城 이동(기존 `che_이동` 류, 발령 부임 등) | `city_id` 갱신 → 행 있으면 뒤따라 고침 | **위치를 먼저 쓰고** `city_id` 를 투영. 城 이동 = 「목적 城의 省으로 이동」의 특수형 |
| 省 이동(행군, 뒤 묶음) | 없음 | 위치 쓰기. 도착 省에 城이 없으면 `city_id = 0` |
| 행 삭제 | 전장 이탈·외부 거점·강제 해제 | **금지.** 사망·삭제 장수만 FK cascade 로 사라진다 |
| 관리자 도시 강제 이동 | `city_id` 직접 갱신 | 같은 인테이크가 위치 쓰기로 바뀐다 |

### 2.3 불변식(HWIHA)

1. 살아 있는 장수 수 = 위치 행 수(부팅 시 검사, 어긋나면 부팅 실패 — `WorldSnapshot` 의 기존 id 대조 자리).
2. `city_id != 0` 이면 `cityAnchors[city_id] == 위치 node`. `city_id == 0` 이면 위치 省에 城이 없다.
3. 한 변경 단위(같은 `ChangeRecorder` 버퍼) 안에서 위치와 `city_id` 가 함께 바뀐다. 한쪽만 바꾸는 API 를 두지 않는다.
4. `battlefield_*` 열(V59)은 HWIHA 에서 **항상 NULL** 이다(판정문 §4.1 동결).

### 2.4 두지 않는 것

- 행군 중 상태(진행 중 경로·남은 비용)는 여기 넣지 않는다 — 판정문 §4.3-2 의 새 열/표. 이 문서는 「도착한 省」만 정의한다.
- `province_control` 의 의미(판정문 UNKNOWN-2)는 정하지 않는다.
- 위치가 縣 단위인지 省 단위인지: 재설계 §7 「省 = 군단 위치, 縣 = 내정」에 따라 **省**이다. 縣은 관할 조회로 파생한다.

## 3. 구현 형태(첫 묶음)

1. **읽기 뷰**: `InMemoryTurnWorld.positionOf(generalId): StrategicNodeRef`(HWIHA 에서 non-null). 기존 `generalPositionSnapshot()` 위에 얇게.
2. **쓰기 진입점 하나**: `recorder.moveGeneral(world, generalId, to: StrategicNodeRef)` — CAS 로 위치 쓰기 + `city_id` 투영을 한 번에. HWIHA 의 모든 이동은 이것만 부른다. SAMMO 는 부르지 않는다.
3. **시드**: `ScenarioImporter`/부트스트랩에서 `ruleProfile == HWIHA` 일 때 전 장수 행 INSERT(같은 위상 핀). 시나리오 JSON 은 안 바뀐다.
4. **투영 적용점**: `BattlefieldCityMembership.applyPositionAwareGeneral` 는 SAMMO 전용으로 남기고, HWIHA 에서는 호출되지 않게 규칙 프로필로 가른다(지우지 않는다 — 재설계 §17).
5. 스키마 변경 없음. V50 표·CAS·flush 그대로.

## 4. 게이트(적색 프로브 필수)

1. **SAMMO 바이트 불변**: 기존 골든·`SpatialStatePersistenceIT`·`BattlefieldTurnHandlerTest` 등 전부 그대로 통과. HWIHA 분기를 SAMMO 로 잘못 타면 빨개지는 테스트(예: SAMMO 시드에서 위치 행이 생기면 실패).
2. HWIHA 시드 뒤 「장수 수 = 위치 행 수」, 행 하나를 지우면 부팅이 실패한다.
3. `moveGeneral` 로 城 없는 省에 보내면 `city_id == 0`, 城 있는 省이면 그 城 — 투영을 손으로 어긋나게 쓰면 불변식 2 검사가 잡는다.
4. `city_id` 만 갱신하는 옛 경로가 HWIHA 에서 호출되면 실패(호출 자체를 막는 가드).
5. HotColdCatalog 등록 불변(새 표 없음) — `HotColdWorldCatalogGuardTest` 그대로.

## 5. 회귀 기준선 영향

없다(스키마·SAMMO 경로 무변경). 판정문 §4.4 와 같다. 176곳의 `.cityId` 소비자는 투영값을 읽으므로 그대로 동작한다 — 단 「`cityId == 0` 인 장수」를 HWIHA 에서 처음으로 마주치는 소비자들이 어떻게 반응하는지가 첫 슬라이스의 실제 위험이다(§6).

## 6. 미결

- `cityId == 0` 소비자 176곳의 전수 심사 — 전장 주둔 선례(`PerTurnOverlay`, 판정문 §1.2 끝의 차단 지점 목록)가 이미 다루는 곳과 아닌 곳을 가른다. 이 심사가 첫 구현 PR 의 절반이다.
- 시드 시점의 `ruleProfile` 읽기 경로(입력 registry 계약 §2 의 제안과 같은 길).
- `moveGeneral` 이 받을 위상 핀 불일치(리셋 뒤 토폴로지 변경) 처리 — 기존 CAS 거절을 그대로 쓸지.
- 사람 장수의 「재야」 위치(재설계 §2.1): 소속 없는 장수도 省에 선다는 전제. 확인 필요.
