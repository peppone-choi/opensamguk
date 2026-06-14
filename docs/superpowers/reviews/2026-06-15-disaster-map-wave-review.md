# Cross-agent critique — 재난-맵 wave (city.state 영속 체인)

- **날짜**: 2026-06-15
- **브랜치**: `loop-parity-2026-06-14-c`
- **범위**: 데몬 write 경로 포함 9파일 — `infra/src`(V17 마이그·CityRowMapper·JdbcFlushExecutor), `logic/src`(LogicEntities City), `app/game-engine`(ChangeRecorder·PerTurnOverlay·WorldActionContext·WorldSnapshotLoader), `app/game-api`(CityReadEntity·WorldMapController).

## 변경 요지

PHP `city.state`(재해/사건 코드 6~9) opensamguk 미포팅 근본수정. V17 `ADD COLUMN state` + 영속 체인(diffCity dirty-detection·CityRowMapper·cityUpdate `state=:state`·WorldSnapshotLoader load·CityReadEntity) + applyDisaster reset/effect 기록 + WorldMapController가 맵 tuple state자리에 `it.state`(종전 frontState 대용 버그). MapViewer `event<state>.gif` 렌더는 기존 OK.

## Cross-agent critique — `grader-wave` (ce-correctness, fresh 적대)

로컬 Docker 사망(IT는 CI만) → 그래더가 flush 정합을 inspection으로 검증(미스매치=turn-freeze).

- **[CRITICAL PASS] flush param 정합**: cityUpdate `state=:state`(317) ↔ CityRowMapper.toColumns `"state" to c.state`(94) ↔ 바인딩 루프(toColumns 키=named param) ↔ LogicCity.City.state ↔ V17 컬럼 `state`. 미스매치 0 → BatchUpdateException 0 → turn-freeze 무.
- **[PASS] 미바인딩 :state 타처 없음**: ScenarioImporter city INSERT는 state 생략(positional)→DEFAULT 0. 타 UPDATE/INSERT city 없음.
- **[PASS] 마이그**: V17 DEFAULT 0(NOT NULL 충족, state-생략 INSERT 커버), V16 다음.
- **[PASS] diffCity**: `diffCol("state",...)` dirty-detection 추가, 기존 diff 무영향.
- **[PASS] applyDisaster**: reset 경로 `recorder.diffCity(preLogic, preLogic.copy(state=state))`(state→0 flush), effect 경로 postLogic에 `state=effect.stateCode`. PerTurnOverlay.toLogicCity `state=c.state` 매핑(preLogic.state 실반영).
- **[PASS] RaiseDisaster 골든 불가침**: RaiseDisaster.kt 무변경(순수 body/RNG/draw/DisasterCity*). 엔진 wiring(WorldActionContext)만.
- **[PASS] loader/read 왕복**: WorldSnapshotLoader SELECT+map state(재시작 복원), CityReadEntity @Column state, WorldMapController tuple 3번째 `it.state`(func_map.php `[city,level,state,nation,region,supply]` 정합).
- **[PASS] CI 검증**: `JdbcFlushExecutorSatelliteIT`가 풀 Flyway(V17 포함)+real City flush(cityUpdate) 실행 → `"state"` 키 누락시 미바인딩 :state로 IT fail. flush 정합 CI-커버.
- **[LOW 비차단]** state 값 read-back assertion 부재(param 미스매치=turn-freeze는 SatelliteIT가 잡음; 값정확성은 inspection 검증 `"state" to c.state`). 옵션: `SELECT state` assertion.

## 게이트

- 컴파일: `:logic :infra :app:game-engine :app:game-api` **BUILD SUCCESSFUL**(Java21, wave-builder).
- 실DB: CI jvm이 JdbcFlushExecutorSatelliteIT(V17+cityUpdate) + RaiseDisaster 골든 + 전 모듈 테스트 실행 = **이 PR의 jvm check가 게이트**. red면 머지/배포 금지.
- 골든/테스트 완화 0. 날조 0.

## Verdict: cleared

블로커/HIGH 0. flush 정합(turn-freeze 무)·골든 보존·CI flush IT 커버. ⚠️**CI jvm green 전 s1 배포 금지**(데몬 write 경로). 머지 후 s1 bump+검증은 별도.
