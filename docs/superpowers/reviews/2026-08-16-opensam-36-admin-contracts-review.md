# OPENSAM-36 G0-A① 행정 계약 적대적 리뷰

Date: 2026-08-16

Scope: `logic/` v2 행정·장소·치소 계약 7종과 `logic/src/main/kotlin/opensamguk/logic/v2/geo/GeoValidators.kt` (PR #407, 브랜치 `op-36-g0a1-admin-contracts`).

Reviewer: 작성자가 아닌 독립 에이전트. 목표는 통과가 아니라 파괴였다.

## 공격한 것과 결과

### 버틴 것

- **반열림 `[from, to)` 경계.** `ValidTime.overlaps`를 인접 구간(`[140,189)` vs `[189,220)`), 1일 겹침(`[140,190)` vs `[189,220)`),
  양쪽 열린 끝, 동일 구간으로 때렸다. 대칭성(`a.overlaps(b) == b.overlaps(a)`)도 논리식 전개로 확인 — 두 항의 논리곱이
  교환 시 동일하다. 0-길이·역전 구간은 `init`의 `require(validTo > validFrom)`이 생성 자체를 막으므로 겹침 판정에 도달하지 못한다.
- **군치·현치 co-location.** `validateSeatAssignments`는 `(administrativeUnitId, role)`로 그룹핑하므로 한 `PhysicalPlace`에
  `COMMANDERY_SEAT` + `COUNTY_SEAT`가 동시에 앉는 정상 케이스는 서로 다른 그룹이 되어 통과한다. 반대로 같은 단위·같은 역할의
  기간 겹침과 완전 동일 행은 잡힌다. 체크리스트 `T1-B12` 문구
  (`docs/superpowers/plans/2026-07-17-v2-ticket-backlog/04-systems-micro.md:48`)와 1:1 일치한다.
- **편측 근거 거부.** `validatePlaceIdentityKeys`는 `b.id in a.distinctPlaceClaimIds && a.id in b.distinctPlaceClaimIds`를
  요구한다. 한쪽만 주장하는 경우를 통과시키려 시도했으나 실패했다(= 거부됨). 자기 자신을 claim 목록에 넣는 우회도
  상대 id가 맞지 않으면 무력하다. 동일 id 중복은 별도 검사로 먼저 잡힌다.
- **`RESOLVED_POINT`/`CANDIDATE_REGION` 우회.** `data class`의 `copy()`는 primary constructor를 다시 호출하므로 `init` 검사가
  재실행된다 — `copy(coordinate = ...)`로 좌표 없는 `RESOLVED_POINT`나 좌표를 단 `CANDIDATE_REGION`을 만들 수 없다.
- **예산 정본 대조.** 1,200 / 200 / 500 / 100 = 2,000은 계약 동결 문서
  `docs/superpowers/specs/2026-08-16-v2-contract-freeze-p1-p15.md:313-314`(P-12)와 체크리스트 `T1-B07`의 동결값과 정확히 일치한다.
- **v1 패러티 침범 0.** `git diff --name-only --diff-filter=MD origin/main...HEAD`가 비어 있다 — 기존 파일 수정·삭제 0건.
  RNG/로그/골든/`ChangeRecorder`/flush 경로를 건드리지 않으며 신규 코드는 순수 in-memory다.
- **스텁·TODO·skip·placeholder 0건.** 신규 6파일 전체 grep 무매치.
- **`V2NamingConventionGuardTest`.** 신규 선언 중 `V2`로 시작하는 것이 없고 패키지도 `opensamguk.logic.v2.geo`라 규약 위반 없음.
- **테스트 양방향성.** 통과 케이스만 확인하는 허수가 아니다 — 겹침/중복/편측 claim/예산 불일치/좌표 날조/자기 부모/자기 의존/
  역전 구간까지 `assertFailsWith`와 위반 개수 단언으로 음성 방향을 덮는다.

### 깨진 것 (수정함)

- **MAJOR — `AdministrativeChange`의 시간 창이 `ValidTime` 규칙 밖에 있었다.** 파일 헤더는 `ValidTime`을
  "겹침 판정의 유일 정본"이라 선언하지만 `AdministrativeChange`는 `effectiveFrom`/`effectiveTo`를 검증 없는 두 개의
  `LocalDate`로 들고 있었다. `effectiveFrom = 200년, effectiveTo = 140년`인 역전 창과 0-길이 창이 생성되었다.
  T1-E02 접기 순서의 1차 키가 `effectiveFrom`이므로 이런 레코드는 baseline 접기를 조용히 오염시킨다.
  → `init`에 `require(effectiveTo == null || effectiveTo > effectiveFrom)` 추가, 역전·0길이 부정 테스트 2건 추가.
- **MAJOR — `CandidateRegion.envelope`가 투영 버전을 섞을 수 있었다.** `GeoPoint`는 replay 결정성을 위해
  `projectionVersion`을 좌표와 함께 나르는데, envelope는 점 개수(≥3)만 검사했다. 서로 다른 투영의 점을 섞은 polygon은
  어떤 replay에서도 같은 영역을 뜻하지 않으며, `ScenarioPlacement`의 `admissibleRegion`이 이걸 그대로 받으므로
  "결정적 재구성 anchor"라는 T1-E08 주장이 깨진다.
  → envelope 단일 `projectionVersion` 요구, 혼합/점부족 부정 테스트 추가.
- **MINOR — `showsReconstructionBadge`가 `uncertaintyRadius`를 무시했다.** 문서상 `uncertaintyRadius`는 T1-B10이
  "개연 경계를 확정 사료처럼 그리지 못하게 하는 신호"인데, 배지 파생식은 `locationResolution`과 `confidence`만 봤다.
  `RESOLVED_POINT` + `ATTESTED` + `uncertaintyRadius = 40.0`인 장소는 반경을 달고도 배지 없이 확정 좌표로 렌더된다.
  → 배지 조건에 `uncertaintyRadius != null` 추가, 해당 조합 테스트 추가.

## 의도적으로 열어 둔 것 (결함 아님, 후속 티켓 소유)

- `logic/v2/geo`는 아직 프로덕션 호출자가 없다. 이 티켓은 계약 정의(`[문서]` 등급 T1-B/E 항목)이며 소비는 T1-E02(접기)와
  G0-C가 담당한다. 배선 없음은 스텁이 아니라 범위다.
- `TemporalName.time`과 소유자 `time`의 포함 관계, `dependsOnChangeIds`의 순환 검사, SPLIT/MERGE의 successor lineage
  강제는 T1-E02(접기 순서·lineage)의 몫이다. 여기서 선반영하면 근거 없는 규칙 날조가 된다.
- `PlaceControl`/`SeatAssignment`에 polity 축이 없어 "두 단위가 동시에 CAPITAL"은 검출하지 못한다. 체크리스트가 요구하지
  않은 규칙이므로 발명하지 않았다.

## 게이트 증거

- `python3 tools/agent-system/check.py --strict --base origin/main` → Errors 0.
- `:logic:test` XML 실측은 커밋 메시지·PR 본문에 기록.

Verdict: cleared
