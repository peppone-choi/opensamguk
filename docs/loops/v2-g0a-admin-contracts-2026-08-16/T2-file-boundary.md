# OPENSAM-36 (G0-A①) T2 파일 경계

- 브랜치: `op-36-g0a1-admin-contracts` (base `origin/main` = d63f6fec)
- 성격: **in-memory 계약 정의만.** DB write 0, 마이그레이션 0, 기존 파일 수정 0.

## 내가 여는 파일 (전부 신규)

| 경로 | 내용 |
| --- | --- |
| `docs/loops/v2-g0a-admin-contracts-2026-08-16/T2-file-boundary.md` | 이 노트 |
| `logic/src/main/kotlin/opensamguk/logic/v2/geo/AdministrativeContracts.kt` | TemporalAdministrativeUnit, AdministrativeChange (+ 공통 provenance/valid-time) |
| `logic/src/main/kotlin/opensamguk/logic/v2/geo/PlaceContracts.kt` | PhysicalPlace, PlaceBudgetClass, PlaceControl, ScenarioPlacement |
| `logic/src/main/kotlin/opensamguk/logic/v2/geo/SeatContracts.kt` | SeatAssignment, SeatRole, 배지 파생 |
| `logic/src/main/kotlin/opensamguk/logic/v2/geo/GeoValidators.kt` | G0A-p/q validator + 예산 검증 |
| `logic/src/test/kotlin/opensamguk/logic/v2/geo/GeoValidatorsTest.kt` | validator 테스트 |

## 계약 7종 소유표 (전부 `logic/src/main/kotlin/opensamguk/logic/v2/geo/`)

| 계약 | 파일 | 소유(정본) | 체크리스트 |
| --- | --- | --- | --- |
| `TemporalAdministrativeUnit` | `AdministrativeContracts.kt` | 시간 범위를 가진 주·군·국·현·후국 단위와 부모 관계 | T1-B01 |
| `AdministrativeChange` | `AdministrativeContracts.kt` | 140년 baseline에 접는 행정 변경 + type priority(10~70) | T1-E01 / E02 |
| `PhysicalPlace` | `PlaceContracts.kt` | 물리 장소 identity·좌표 확정도·복원 배지. 치소 여부는 **소유하지 않는다** | T1-B05 / B08 / B10 |
| `PlaceControl` | `PlaceContracts.kt` | 물리 장소에 대한 지배(점령 = 이 레코드 변경) | T1-B15 |
| `ScenarioPlacement` | `PlaceContracts.kt` | 시나리오별 결정적 anchor 배치 | T1-E08 |
| `SeatAssignment` | `SeatContracts.kt` | **치소의 유일 canonical owner** + 배지 파생(`badgesFor`) | T1-B11 / B13 |
| `PlaceBudgetClass` | `PlaceContracts.kt` | 상호배타 4-class 예산 1,200/200/500/100 = 2,000 (P-12 동결값) | T1-B07 |

공통 값 객체 `ValidTime`(반열림 `[from, to)` 겹침 판정의 유일 정본)·`TemporalName`·`Confidence`·`GeoPoint`·
`CandidateRegion`은 `AdministrativeContracts.kt`/`PlaceContracts.kt`가 나눠 들고, validator 3종
(`validateSeatAssignments` G0A-p, `validatePlaceIdentityKeys` G0A-q, `validatePlaceBudget` T1-B07)은
`GeoValidators.kt`가 단독 소유한다. 전부 in-memory이며 DB write·flush 채널·RNG·로그·골든과 접점 0.

적대적 리뷰: `docs/superpowers/reviews/2026-08-16-opensam-36-admin-contracts-review.md`.

## R1(OPENSAM-150) 배타 소유 경로 — 교집합 검사

R1 소유:
1. `app/game-engine/**/turn/DirtyState.kt`
2. `app/game-engine/**/turn/ChangeRecorder.kt`
3. `app/game-engine/**/flush/DatabaseHooks.kt`
4. `infra/**/persistence/JdbcFlushExecutor.kt`
5. `app/game-engine/**/config/DaemonLoopConfig.kt`
6. `infra/src/main/resources/db/migration_v2/**`

내 목록은 전부 `logic/src/**`와 `docs/loops/**`. R1 목록은 전부 `app/game-engine/**`, `infra/**`.
**교집합 = 0.** (모듈 수준에서 이미 분리 — logic 모듈은 R1 목록에 한 건도 없음.)
