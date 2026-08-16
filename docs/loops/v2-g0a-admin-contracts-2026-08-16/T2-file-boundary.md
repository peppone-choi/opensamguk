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
