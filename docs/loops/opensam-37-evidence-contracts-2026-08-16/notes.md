# OPENSAM-37 [G0-A②] 출처·확실성 계약 — 레인 C 작업 노트

- 브랜치: `op-37-g0a2-evidence-contracts` (base `origin/main`)
- 범위: in-memory 계약 정의만. **DB write 0, 마이그레이션 0, v1 파일 수정 0.**
- 정본 근거: `docs/superpowers/specs/2026-07-13-v2-historical-city-army-terrain-design.md` §3, §11
  (sourceProximity 7값 L69, evidenceClass 5값 L44-50, WorldContentProfile L79-83, 엄격 고증 실패 조건 L379,
  sourceLicense·CHGIS L420)
- 티켓 상세: `docs/superpowers/plans/2026-07-17-v2-ticket-backlog/04-systems-micro.md` 그룹 A(L21-36)·K(L129-130)

## T2 파일 목록 (신규 추가만)

| # | 경로 | 산출물 |
|---|---|---|
| 1 | `logic/src/main/kotlin/opensamguk/logic/v2/evidence/EvidenceContracts.kt` | T1-A01, T1-A04, T1-A15, T1-K01 |
| 2 | `logic/src/main/kotlin/opensamguk/logic/v2/evidence/EvidenceContractValidator.kt` | T1-A05, T1-A06, T1-A16 |
| 3 | `logic/src/test/kotlin/opensamguk/logic/v2/evidence/EvidenceContractValidatorTest.kt` | 검증 |
| 4 | `docs/loops/opensam-37-evidence-contracts-2026-08-16/notes.md` | 본 노트 |
| 5 | `docs/loops/opensam-37-evidence-contracts-2026-08-16/chgis-license-review.md` | T1-K02 |

기존 파일 수정: **0건**.

## 타 레인 교집합 확인 = 0

동시 진행 레인의 배타 소유 경로와 위 5개 파일의 교집합은 없다.

| 레인 | 배타 소유 | 교집합 |
|---|---|---|
| A (R1) | `app/game-engine/**/turn/DirtyState.kt`, `**/turn/ChangeRecorder.kt`, `**/flush/DatabaseHooks.kt`, `infra/**/persistence/JdbcFlushExecutor.kt`, `**/config/DaemonLoopConfig.kt`, `infra/src/main/resources/db/migration_v2/**` | 없음 (레인 C는 `logic` 모듈 신규 패키지만 건드림, 마이그레이션 0) |
| B (OPENSAM-36) | 행정 계약 7종 (TemporalAdministrativeUnit / AdministrativeChange / PhysicalPlace / PlaceBudgetClass / SeatAssignment / PlaceControl / ScenarioPlacement) + validator | 없음 (타입명·파일명 전부 상이. 레인 C 타입: EvidenceRef / HistoricalClaim / WorldContentProfile / WorldContentOverlay / WorldContentSnapshot / SourceLicense / SourceProximity / EvidenceClass / ContractViolation / EvidenceContractValidator) |
| D | `web/game/**` | 없음 |
| E | `docs/superpowers/specs/**` | 없음 (레인 C 문서는 `docs/loops/` 아래 신규 디렉터리) |

주의: 레인 B도 `logic/**/v2/**` 아래에 놓일 수 있으나 **파일 단위 교집합은 0**이며 두 레인 모두 신규 파일만
추가하므로 co-widen(같은 파일 동시 확장)은 발생하지 않는다.

## 컨벤션 확인

`app/game-engine/src/test/kotlin/opensamguk/engine/v2/V2NamingConventionGuardTest.kt` — `class|object|interface V2X`
선언은 `opensamguk.*.v2.*` 패키지에 있어야 한다. 레인 C 타입은 `V2` 접두사를 쓰지 않지만 패키지는
`opensamguk.logic.v2.evidence`로 규약을 만족한다.

## 미해결 / 후속

- T1-A02/A03/A07/A08 (마이그레이션 + row mapper + flush)는 V2-0B 웨이브이며 본 티켓 범위 밖 — 의도적 미구현.
- T1-A09~A14 (ContentEntry / CatalogBudget / Slot)은 별도 티켓.
