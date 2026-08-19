# OPENSAM-204 스코프 — 기존 시나리오를 후한 군현 세계로 갈아끼울 때 무엇이 걸리나

- 일자: 2026-08-19
- 성격: 조사 전용(코드 미수정). 조사 주체 = `scope-204` 서브에이전트, 검증 = 본 세션에서 재실행한 grep/파일 카운트.
- 요청: "현재 있는 시나리오의 맵과 병종을 다 새걸로 맞추고, 군현의 소속도 이에 맞춰."

## 0. 검증한 것 / 못 한 것

본 세션에서 직접 재실행해 확인한 수치(서브에이전트 보고를 그대로 믿지 않는다):

| 주장 | 재확인 결과 |
|---|---|
| main 코드의 `CityConst.` 참조 101건 | ✅ 101 (`common logic infra app` / `src/main`) |
| `ScenarioJson` 도시 인덱스 G_CITY=4, N_CITIES=8 | ✅ `ScenarioJson.kt:33,65` |
| P5 골든 `world-1010.json` 도시 94개 | ✅ 94 |
| `UnitSetTable.defaultCrewTypeId` / `castleCrewTypeId` 존재, main 호출자 0 | ⚠️ 함수 존재(`UnitSetTable.kt:77,80`)는 맞으나 **defaultCrewTypeId 는 main 호출자 1건**(`AutorunNationPolicy.kt:136`). castleCrewTypeId 만 0건. |
| `han-tiles.json` 郡 165 | ❌ **168** (`juns` 길이 = `_meta.counts.seats` = 168) |

미검증(UNKNOWN으로 남긴다): 각 파일:라인의 참조 성격 분류 전수, 골든 277파일 안의 도시 문자열 집계, `CityConstTest.bidirectionalConnectivity()` 를 `adjacency.commandery` 가 만족하는지.

## 1. main 코드가 che 94성을 컴파일타임으로 가정하는 지점

싱글턴 `CityConst()` 를 직접 잡아 쓰는 곳(변형 미주입)이 문제다.

- **인접(path) 기반 이동/BFS — 12곳**: `ai/bfs/AiDistance.kt:83,122,210` · `ai/families/GenWarMoveFamily.kt:473,768` · `NationDeployFamily.kt:267` · `GenFoundFamily.kt:525` · `war/SearchDistanceListToDest.kt:32,57` · `world/CalcCityDistance.kt:63,101,140` · `app/game-engine/.../AiTurnAdapter.kt:1204,1239`
- **도시 id 존재 검증(없으면 명령 거부) — 11개 명령**: `CheBaekseongDongwon` `CheChotohwa` `CheSumol` `CrInguIdong` `CheTalchwi` `CheHeobo` `CheCheobo` `CheNpcNeungdong` `CheSeondong` `CheHwagye` `ChePagoe`
- **전 도시 열거 — 1곳**: `ProcessNationCommand.kt:255` (`CityConst.all().keys`)

→ **24곳이 하드 결선**. 이 파일들은 P4/P5 골든이 덮는다.

변형 주입이 이미 된 곳(郡 세계로 자동 따라옴): `UpdateCitySupply.kt:82` · `SetNationFront.kt:72` · `ConquerCity.kt:429`.

부수적으로 걸리는 면:
- **로그 바이트 패러티** — 도시 id→한글 이름을 로그에 박는 곳 약 22곳. `CheJiphap.kt:53` 만 하드 실패, 나머지는 `?: ""` 라 **로그가 조용히 깨진다**.
- **레벨 8종**(수/진/관/이/소/중/대/특) 매칭 — 특히 `level == 4`(이민족)는 침략자 게이트 조건(`WorldActionContext.kt`).
- **지역 8종**(하북/중원/서북/서촉/남중/초/오월/동이) 문자열 매칭 — 郡 세계의 13州와 대응 규칙 **미정**.

## 2. 시나리오 ↔ 도시 매핑 주체

**`CityConst` 와 직접 연결이 없다.** `infra/src/main` 에서 `CityConst` 는 주석에만 등장한다.

- `nation[i][8]` = 도시 **한글 이름 배열**, 첫 원소가 수도
- `general[i][4]` = 도시 **이름 또는 숫자 id 문자열**(정수 필드 아님)
- 매핑 주체 = **`ScenarioImporter`**, 사전은 생성자로 받은 `cities: List<ScenarioCity>` — 출처는 `map/<mapName>.json`

**가장 큰 함정: 도시명 미스가 대부분 조용히 흡수된다.**

| 경로 | 미스일 때 |
|---|---|
| `nation.cities` | 조용히 무시 → 공백지 |
| 수도 | 조용히 스킵, `capital_city_id` 기본값 잔류 |
| 배치 풀 | 조용히 드랍, 비면 전 도시 폴백 |
| 장수 도시(RNG 소비 행) | **무작위 도시 배정** |
| 장수 도시(RNG 미소비 행) | 유일하게 `error(...)` |

즉 **잘못 갈아끼운 세계가 테스트 그린으로 통과할 수 있다.** (이미 커밋된 오염 사례: `scenario_1021.json` "하비.광릉", `scenario_1031.json` 빈 문자열·"소패", `scenario_1041.json` 장수 7명 "소패".)

그리고 **인접 그래프는 오직 코틀린 `CityConst` 에만 있다** — `map/che.json` 의 `connections` 는 `loadMapCities` 가 버리고, DB `city` 테이블에도 path 컬럼이 없다.

## 3. `cities_1010` 해시 게이트

- 메타 `content/v2/cities_1010.json`(sha256 + cityCount 94 + scenarioOwnedCityCount 24) ↔ 데이터 `scenario/cities_1010.json`
- 검증: `V2ContentCatalog.kt:29-60` (구조) → `V2CityCatalogAdapter.kt:19-35` (sha256 재계산·개수·중복)
- 핀: `V2CityCatalogAdapterTest.kt:18-22`, `V2ContentCatalogBeanTest.kt:53`
- **郡 세계를 새 content id 로 등록하면 이 게이트를 안 건드린다.** (`V2CityCatalogAdapter.kt:38` 이 `cities_1010` 고정인 점은 손봐야 함)

## 4. 병종

- `data/unitset/units.json` → `common/build.gradle.kts` 가 processResources 때 복사 → `UnitCatalog.kt:63` 이 classpath 에서 읽음. sets.che 34(frozen) / sets.han 127.
- **che 행은 런타임에 안 읽힌다** — `UnitCatalog.kt:80` 이 `GameUnitConst.all()` 로 덮는다. JSON che 는 사본, 코틀린이 진실(최신성은 `--check` + `CheUnitSetExportTest` 가 지킴).
- **시나리오에 장수/도시 단위 병종 필드는 0건.** 있는 건 `map.unitSet` 문자열 4건(`scenario_900/910/911/912`)인데 **넷 다 units.json sets 에 없다** → `isSupported()` false → 병종 목록 빈 배열. 나머지 27개는 미기재 → "che" 기본값.
- 시드 기본 병종 `ScenarioImporter.kt:487` = `GameUnitConst.DEFAULT_CREWTYPE`(1100) **che 고정, unitSet 무시**. 성벽 `WarUnitCity.kt:44` = 1000 **하드코딩**.

**"병종을 새 걸로 맞춘다" = 4점:** ①유령 unitSet 4건 정정 ②시드 기본값을 `UnitSetTable.defaultCrewTypeId(unitSet)` 로 ③성벽을 `castleCrewTypeId(unitSet)` 로 ④郡 시나리오는 `map.unitSet="han"`.

## 5. 판단 — 덮어쓰기 vs 새 코드

### 골든이 도시 id/인접에 의존하는가: **예, 강하게**

P2 fixtures 55파일 전부 cityId 포함 · P3 `month-0*` state.city 94개 · P5 `world-1010.json` cities 94 · P4 conquercity · `AiSelectionGateIT.kt:49`("174 generals, 94 cities"). 게다가 **P5 AI 골든은 인접 순회 순서(name-order)에 draw-for-draw 로 묶여 있다** — 인접 그래프가 바뀌면 draw 순서가 바뀌고 전 스트림이 desync 한다.

### (a) `scenario_1010` 덮어쓰기 — 권고하지 않음

결정적 이유 하나: **골든을 다시 뜰 방법이 없다.** 골든은 PHP `devsam/core` 에서만 캡처 가능하고 PHP 는 94성 che 세계다. 郡 세계에는 캡처할 오라클이 존재하지 않는다 → CLAUDE.md 규칙 5(fabricate 금지) 정면 충돌. 여기에 카운트 단정 12개 파일·특정 도시 단정 다수 수정이 얹히고, §2의 조용한 실패 때문에 **틀린 세계가 그린으로 통과**할 수 있으며, 후퇴 경로가 없다.

### (b) 새 맵 변형 `han` + 새 시나리오 코드 — 권고

깨지는 것 원칙적으로 **없다**. che 시나리오·`CityConst`·골든이 그대로 살아 전부 그린. 선례도 있다 — miniche(78성)가 같은 방식으로 공존한다.

할 일:
1. `infra/src/main/resources/map/han.json` — `che.json` 스키마 그대로. 원자재는 이미 있다(`data/map/han-tiles.json` 郡 **168**, `data/map/junguozhi.json` 郡 106/縣 1076+호구).
2. `CityConstRegistry.variants` 에 `"han"` 추가 — 레벨 8종·지역 8종 안에 매핑(13州→8지역 규칙 **미정**).
3. **§1의 하드 결선 24곳을 변형 주입으로 교체** — 실작업의 무게중심. P4/P5 골든이 덮으므로 **che 일 때 바이트 동일**이어야 하는 시그니처 확장 리팩터다.
4. 새 `scenario_2xxx.json` — nation/general 도시명을 郡 이름으로, `map.unitSet="han"`.
5. §4의 병종 4점.
6. v2 트랙: 새 content id 등록(`V2CityCatalogAdapter.kt:38` 고정 해제).

## 5.5. 사용자 결정 (2026-08-19)

- **성 이름 = 治所 縣 이름.** 遼東郡이 아니라 양평, 河南尹이 아니라 낙양. `CityConst` RawCity 의 name 은 縣 이름으로 간다.
- **郡 = HOI4 의 State 개념.** 성이 다스리는 영역이지 성의 이름이 아니다. 지도에서는 성 이름 아래 배경 라벨로 깔린다.
  → `regionMap` 8지역과 별개 층이 하나 더 생기는 셈이라, §1의 지역 8종 매칭 규칙과 어떻게 겹칠지 별도 결정이 필요하다(UNKNOWN).
- **도로·해로·수로는 폐기 후 사용자가 직접 세운다.** 현재 `terrain-grid.json` 의 간선/수로/나루터는 사료 검증 전이며 렌더에서 제거했다(데이터는 유지). `build_terrain_grid.py` 의 도로 생성부는 재작성 대상.

## 5.6. 후속 조사 A/B (2026-08-19, scope-204 + 본 세션 검증)

### A. 郡 인접이 `CityConstTest` 불변식을 만족하는가

`CityConstTest.bidirectionalConnectivity()`(common/src/test/.../CityConstTest.kt:72-84)가 요구하는 건
"대칭"이 아니라 **양쪽에 명시적으로 적혀 있을 것**이다 — `path` 는 각 도시가 이웃을 직접 나열하는 구조라
A→B 만 적고 B→A 를 빼면 실패한다. 자기루프 금지·고립 금지는 단정하지 않는다.

`adjacency.commandery` 실측(413 엔트리): 무향 간선을 **한 번만** 저장하는 규약이라 역방향이 전부 없다.
결함이 아니라 저장 형식이며, **변환기가 각 쌍을 양방향으로 펼치면 통과**한다. 자기루프 0건.
고립 노드 6개 — 섬 4곳(이주·주호·유구·야마일국, che 에서도 수로로 이었다)과 **광평군·문산군 2곳은
내륙인데 인접 0 이라 실제 결함**(원인 UNKNOWN). 고립은 테스트가 안 잡지만 BFS 보급·거리·AI 이동에서
영원히 도달 불가한 도시가 된다.

※ 위 수치는 중복 접기(§5.7) 이전 스냅샷 기준이다 — 인덱스는 재빌드 후 다시 세야 한다.

### B. 州 축과 `regionMap` 8지역

- **州 소속 필드는 세 데이터 파일 어디에도 없다.** `junguozhi.json` 의 `vol` 은 續漢書 郡國志의 **卷**이지 州가 아니다.
- **다만 재추출은 필요 없다.** `tools/map/build_junguozhi.py:157-174` 의 `CANON_105` 가 이미 州 순서대로 郡을
  나열하고 州 이름을 주석으로 달고 있다 — 이 주석을 데이터로 올리기만 하면 郡→州 매핑이 나온다.
  (scope-204 의 "원문 재파싱 필요" 판단은 본 세션에서 정정했다.)
- **`regionMap` 8지역으로 실제 분기하는 게임 로직은 한 곳뿐이다** — `RecruitAlgorithm.kt:48,52-53,281`
  (`UnitConstraint.ReqRegions` / `ForbidRegions`)와 그 AI 대응 `AiTurnAdapter.kt:1695`. 나머지는 전부
  데이터 운반이거나 표시 문자열이고, `byRegion()` 은 main 호출자 0 이다. **8이라는 수에 묶인 로직이 없다.**
- **`units.json` 의 han 세트는 이미 州 축으로 쓰여 있다** — reqConstraints 가 요구하는 지역 라벨 39종이
  `幽州·冀州·并州·青州·揚州·益州·涼州` 같은 **州名**과 `烏桓·鮮卑·羌·山越·馬韓·邪馬壹國` 같은 **종족/소국명**이다.
  che 세트만 8지역 한글(중원/오월/…)을 쓴다.

**판단: 13州를 8지역에 억지 매핑하지 말고 지역 축 자체를 han 변형에서 갈아끼운다.**
`regionIdByName` 은 이미 `CityConstVariant` 의 오버라이드 포인트인데(`CityConstRegistry.kt:32,45,64`)
현재 두 구현 모두 `CityConst.regionMap` 을 하드 참조한다 — 이 한 줄만 변형별로 풀면 된다.
`ForbidRegions` 는 미해석 라벨을 no-op 으로 흘리므로 부분 매핑 상태에서도 안 터진다. che 8지역은
그대로 두므로 P0~P6 골든과 무관하다.

미검증: 郡 하나에 州와 종족이 동시에 필요한 유닛(`2100 유주돌기 [幽州,烏桓,鮮卑]`)이 있어
**1郡 = region 정수 1개** 모델로 충분한지. `ReqRegions` 가 `any` 매칭이라 아마 되지만 설계 확인이 필요하다.

## 5.7. 지도 데이터 정정 (2026-08-19, 사용자 지적)

- **같은 縣이 두 번 찍히던 문제.** CHGIS 가 같은 縣을 시기별·비정별로 여러 줄 싣는다(盧氏縣 `-100~1911` 과
  `23~1911`). 220년 필터만으로는 둘 다 통과한다. `build_han_places.py` 에 접기 규칙을 넣었다 — 이름이 같고
  0.5° 이내(또는 오늘날 비정지가 동일)면 한 점으로 보고, 220년을 더 좁게 감싸는 시기 조각을 남긴다.
  멀리 떨어진 동명(汝南 上蔡 / 豫章 上蔡)은 남긴다.
- **같은 좌표의 縣/國 두 줄**(彭城县 + 彭城国 侯國, 池阳 + 池阳县)도 접는다. 단 **짝이 郡 등급이면
  건드리지 않는다** — 그 점이 郡 승격의 근거다(下邳县/下邳郡 등 10쌍).
- 결과: 지점 1145 → **1132**, 郡 168 → **167**(馮翊郡 중복 해소). 郡國志 106郡 전원 생존.
- **시기 동률 2건은 자동으로 못 고르고 id 순으로 골랐고 빌더가 출력에 찍는다** — `馮乘縣(41975·43920)`,
  `馮翊郡(211473·211723)`. 특히 馮翊郡은 두 기록의 `presLoc` 이 `今陕西省大荔县` 로 같은데 경도가 1°(≈90km)
  다르다. 남은 쪽(108.94)보다 버린 쪽(109.94)이 大荔 실측에 가깝다. 다만 이 기록은 `begYr=220` 인
  **曹魏 馮翊郡**이라 220년 지도에 별개 郡으로 있어야 하는지부터가 미정이다(後漢은 左馮翊, 이미 고륙현으로
  따로 있다). 출처 없이 좌표를 손으로 고치지 않았다 — **결정 대기**.
- **郡治로 뽑히지 못한 COMMANDERY/PROVINCE 점 89+3개가 縣 마크로 찍히던 문제**는 렌더에서 막았다
  (`HanMapCanvas.tsx`). 데이터에서 빼는 건 `owner` 격자 인덱스가 걸려 있어 OPENSAM-204 로 미뤘다.
- **도로·해로·수로·나루터는 렌더에서 제거**했다(데이터는 유지). 사용자가 직접 세운다 —
  `build_terrain_grid.py` 의 도로 생성부는 재작성 대상.

## 6. 선결 확인 (UNKNOWN)

- **세계 도시 수를 몇으로 할 것인가.** 저장소 데이터는 `han-tiles.json` 郡 168 / `junguozhi.json` 郡 106. 티켓 확정 전에 이 숫자부터 정해야 한다. (기존 티켓 문구의 "161郡" 과 일치하는 수는 저장소에 없다.)
- 13州 → `regionMap` 8지역 매핑 규칙
- `adjacency.commandery` 가 `CityConstTest.bidirectionalConnectivity()` 불변식을 만족하는지 — 미검증
- 참고 갭: `map/*.json` 은 7개인데 `CityConstRegistry` 는 4개만 안다(cr/chess/ludo_rathowm 은 `of()` 가 `error()`)
