# 재난-맵 wave — city.state 영속 체인 (2026-06-14 유저보고)

**상태**: V17 마이그레이션 완료. 나머지 8파일 정밀 스펙(아래). 데몬 write 경로라 **실DB flush IT(CI, 로컬 Docker 사망)** + RaiseDisaster 골든 보존 필수. **완성 전 s1 배포 금지**(불완전 flush=turn-freeze).

## 근본원인 (진단 완료)

PHP `city.state`(재해/사건 코드 6~9, RaiseDisaster.php) opensamguk 미포팅. city 스키마=`supply_state`(=PHP supply)+`front_state`(=PHP front)만, `state` 별개 컬럼 부재. 엔진 `WorldActionContext.applyDisaster`는 이미 in-memory city.state 설정하나 주석대로 "engine-only column not tracked by diffCity" → 영속 안 됨 → `WorldMapController`가 맵 tuple state자리에 `frontState` 대용(틀림) → MapViewer `event<state>.gif`(재해 6~9 렌더 가능)에 데이터 안 옴. (RaiseDisaster는 startyear+3년 후 발동 → fresh seed 즉시 비가시.)

## 편집 목록 (V17 외 8파일)

1. **✅ V17__city_disaster_state.sql** — `ALTER TABLE city ADD COLUMN state integer NOT NULL DEFAULT 0;` (완료)

2. **logic/.../domain/LogicEntities.kt** City(:63) — `val frontState: Int,` 다음에 `val state: Int = 0,` 추가(named-arg 구성이라 위치 무관, 디폴트 0).

3. **infra/.../persistence/CityRowMapper.kt** 3곳:
   - `fromMap`(~37): `frontState = intOf(row["front_state"]),` 다음 `state = intOf(row["state"]),`
   - `fromResultSet`(~65): `frontState = rs.getInt("front_state"),` 다음 `state = rs.getInt("state"),`
   - `toColumns`(~85): `"front_state" to c.frontState,` 다음 `"state" to c.state,`

4. **infra/.../persistence/JdbcFlushExecutor.kt** cityUpdate(~315): `front_state = :front_state,` 다음 줄 `state = :state,` 추가(UPDATE city SET).

5. **app/game-engine/.../turn/ChangeRecorder.kt** diffCity(~277): `diffCol(columns, "frontState", pre.frontState, post.frontState)` 다음 `diffCol(columns, "state", pre.state, post.state)`.
   ⚠️ diffCol의 컬럼명이 DB 컬럼이 아니라 RowPatch 키 → cityUpdate가 `:state` 바인딩하므로 키="state"가 `state=:state`와 일치해야. (다른 키는 camel→snake 변환 확인 필요: 기존 "frontState"→"front_state"? cityUpdate는 :front_state. CityRowMapper.toColumns가 snake 키 생성. diffCol 키는 RowPatch.columns 키인데 flush가 어떻게 매핑하는지 **확인**: diffCity columns 키 vs cityUpdate :param. 기존 supplyState/frontState diffCol 키와 :supply_state/:front_state 바인딩 사이 변환부 추적 필수 — 여기가 가장 실수나기 쉬움.)

6. **app/game-engine/.../world/WorldActionContext.kt**:
   - `toLogicCity`(:105 PerTurnOverlay): `frontState = c.frontState,` 다음 `state = c.state,`
   - `applyDisaster`(:441) reset 경로(442-446): recorder 미경유 → diffCity 기록 추가:
     ```kotlin
     for ((cityId, state) in result.stateResets) {
         val pre = world.getCityById(cityId) ?: continue
         val preLogic = PerTurnOverlay.toLogicCity(pre)
         recorder.diffCity(preLogic, preLogic.copy(state = state))   // 0→0이면 diffCity null=무기록(평시 효율)
         world.updateCity(pre.copy(state = state))
     }
     ```
   - effect 경로(~457 postLogic.copy): 첫 인자로 `state = effect.stateCode,` 추가(현재 postLogic이 state 미포함 → diffCity가 state변화 미감지).

7. **app/game-engine/.../boot/WorldSnapshotLoader.kt** loadCities(~114):
   - SELECT: `supply_state, front_state,` → `supply_state, front_state, state,`
   - City(:120): `frontState = rs.getInt("front_state"),` 다음 `state = rs.getInt("state"),`

8. **app/game-api/.../read/CityReadRepository.kt** CityReadEntity(:56-57): `@Column(name="front_state") var frontState: Int = 0,` 다음 `@Column(name = "state") var state: Int = 0,`. (toLogic ~110 `frontState = frontState` 다음 `state = state`도 선택적.)

9. **app/game-api/.../controller/WorldMapController.kt**(~101): cityList tuple `listOf(it.id, it.level, it.frontState, it.nationId, it.region, it.supplyState)` → 3번째 `it.frontState`를 `it.state`로(func_map.php:145 tuple=`[city,level,state,nation,region,supply]`). MapViewer는 이미 event<state>.gif 렌더(state>0, 6~9 포함).

## 게이트 (필수)

- 컴파일: `:logic :infra :app:game-engine :app:game-api`(로컬 Java21, Docker 불요).
- **flush round-trip IT(CI)**: city.state를 diffCity→flush→read 왕복 검증(JdbcFlushExecutor IT 패턴). + WorldMapController 단위(tuple state=city.state).
- **RaiseDisaster 골든**: RaiseDisasterTest(순수 body, DisasterCityRow 사용)는 불가침 — applyDisaster 엔진 wiring만 변경. 엔진 applyDisaster 테스트 있으면 state 영속 동반 갱신.
- fresh 그래더(적대): flush 키/param 매핑(#5 위험점)·diffCity·골든·turn-freeze·스키마.
- **CI jvm green 전 s1 배포 절대 금지**(데몬 flush 미스매치=turn-freeze).

## 핵심 위험점

#5 ChangeRecorder.diffCity 컬럼키 ↔ JdbcFlushExecutor cityUpdate `:param` 매핑. 기존 diffCol("supplyState",...) 키가 어떻게 `:supply_state`로 바인딩되는지(RowPatch→flush 변환부) 먼저 추적 후 "state" 키를 정확히 맞춰야 BatchUpdateException(turn-freeze) 회피.
